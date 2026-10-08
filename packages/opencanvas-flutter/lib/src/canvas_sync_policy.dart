import 'sync_map.dart';

/// Tunable constants shared by every host that drives a [CanvasSyncPolicy].
class CanvasSyncDefaults {
  const CanvasSyncDefaults._();

  /// Recommended period, in milliseconds, between two [CanvasSyncPolicy.decide] calls.
  static const int checkIntervalMs = 250;

  /// Drift, in milliseconds, below which the video is considered in sync.
  static const int toleranceMs = 150;

  /// Drift, in milliseconds, above which the video is re-positioned with a hard seek.
  static const int hardSeekMs = 500;

  /// Song-position jump beyond what wall time explains, above which [CanvasSyncPolicy.seekDetected]
  /// reports a seek.
  static const int seekJumpMs = 1000;
}

/// What the host should do to the video player right now.
sealed class CanvasSyncAction {
  const CanvasSyncAction();
}

/// No matching picture here (a gap in the map, or before the video starts): show the still artwork.
class Hidden extends CanvasSyncAction {
  const Hidden();
}

/// Do nothing: the video is not ready yet, or the song is paused and the video is in place.
class Hold extends CanvasSyncAction {
  const Hold();
}

/// Hard-seek the video player to [videoMs] (always inside the video).
class SeekTo extends CanvasSyncAction {
  const SeekTo(this.videoMs);
  final int videoMs;
}

/// Keep playing at [speed] (1 when in sync, at most 5 % off to absorb small drift).
class Nudge extends CanvasSyncAction {
  const Nudge(this.speed);
  final double speed;
}

/// The song outlasts the video. The canvas never loops: show the still artwork.
class Ended extends CanvasSyncAction {
  const Ended();
}

/// Pure brain that keeps a canvas video locked to the song being played. Same rules as
/// `CanvasSyncPolicy` in the Kotlin core; it contains no player: use anything that reports a
/// position and seeks. On a track change create a new policy from the new track.
class CanvasSyncPolicy {
  CanvasSyncPolicy(
    this.syncMap,
    this.videoDurationMs, {
    this.toleranceMs = CanvasSyncDefaults.toleranceMs,
    this.hardSeekMs = CanvasSyncDefaults.hardSeekMs,
  })  : assert(toleranceMs >= 0, 'toleranceMs must be >= 0'),
        assert(hardSeekMs >= toleranceMs, 'hardSeekMs must be >= toleranceMs');

  final SyncMap syncMap;

  /// Video length in milliseconds; `<= 0` means the end is unknown.
  final int videoDurationMs;
  final int toleranceMs;
  final int hardSeekMs;

  /// Video position that matches [songPositionMs] (the nearest segment's offset in a gap). Not clamped.
  int targetVideoMs(int songPositionMs) => songPositionMs + syncMap.offsetNear(songPositionMs);

  /// Whether the video should be on screen at [songPositionMs].
  bool isVisible(int songPositionMs) {
    final offset = syncMap.offsetAt(songPositionMs);
    if (offset == null) return false;
    final target = songPositionMs + offset;
    return target >= 0 && (videoDurationMs <= 0 || target < videoDurationMs);
  }

  /// Decides what the host should do to the video player. `drift = videoPositionMs - target`, so a
  /// positive drift means the video is ahead.
  CanvasSyncAction decide(
    int songPositionMs,
    int videoPositionMs, {
    required bool songPlaying,
    required bool videoReady,
  }) {
    final offset = syncMap.offsetAt(songPositionMs);
    if (offset == null) return const Hidden();
    final target = songPositionMs + offset;
    if (target < 0) return const Hidden();
    if (videoDurationMs > 0 && target >= videoDurationMs) return const Ended();
    if (!videoReady) return const Hold();

    final rate = syncMap.rateAt(songPositionMs);
    final drift = videoPositionMs - target;
    final absDrift = drift.abs();
    if (!songPlaying) return absDrift > toleranceMs ? SeekTo(target) : const Hold();
    if (absDrift <= toleranceMs) return Nudge(rate);
    if (absDrift <= hardSeekMs) return Nudge(rate * _nudgeSpeed(drift));
    return SeekTo(target);
  }

  /// Whether the song position jumped by more than [CanvasSyncDefaults.seekJumpMs] beyond what
  /// wall-clock time explains, meaning the user scrubbed or skipped. Run [decide] at once when true.
  bool seekDetected(int prevSongMs, int songMs, int elapsedWallMs, {required bool playing}) {
    final expectedAdvance = playing ? (elapsedWallMs < 0 ? 0 : elapsedWallMs) : 0;
    return (songMs - prevSongMs - expectedAdvance).abs() > CanvasSyncDefaults.seekJumpMs;
  }

  static double _nudgeSpeed(int driftMs) => 1.0 - (driftMs / 4000.0).clamp(-0.05, 0.05);
}
