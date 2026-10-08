/// One stretch of a song that maps onto its music video with a single constant offset:
/// for `songStartMs <= songTime < songEndMs`, `videoTime = songTime + offsetMs`.
///
/// A video whose music runs at another speed than the song's has a [rate] other than 1; then
/// `videoTime = songTime + offsetMs + (rate - 1) * (songTime - songStartMs)`.
class SyncSegment {
  const SyncSegment({
    required this.songStartMs,
    required this.songEndMs,
    required this.offsetMs,
    this.ncc = 0.0,
    this.rate = 1.0,
  });

  /// Reads one entry of the JSON the Kotlin core stores per (song, video) pair.
  factory SyncSegment.fromJson(Map<String, dynamic> json) => SyncSegment(
        songStartMs: (json['songStartMs'] as num).toInt(),
        songEndMs: (json['songEndMs'] as num).toInt(),
        offsetMs: (json['offsetMs'] as num).toInt(),
        ncc: (json['ncc'] as num?)?.toDouble() ?? 0.0,
        rate: (json['rate'] as num?)?.toDouble() ?? 1.0,
      );

  final int songStartMs;
  final int songEndMs;
  final int offsetMs;

  /// Mean normalised cross-correlation of the matched audio, 0..1 (diagnostic).
  final double ncc;

  /// Video seconds per song second.
  final double rate;

  /// `videoTime - songTime` at [songMs] (extrapolated, whether or not it lies inside the segment).
  int offsetAtMs(int songMs) =>
      rate == 1.0 ? offsetMs : offsetMs + ((rate - 1.0) * (songMs - songStartMs)).round();
}

/// How a song's timeline maps onto its music video's. A music video is usually an edit of the song,
/// so a map is a list of segments with their own offsets; song time outside every segment has no
/// matching picture. Same behaviour as `SyncMap` in the Kotlin core.
class SyncMap {
  SyncMap(Iterable<SyncSegment> segments) : segments = _normalise(segments);

  /// A map for a video that is the song shifted by one constant [offsetMs] for its whole length.
  factory SyncMap.constant(int offsetMs) => SyncMap([
        SyncSegment(songStartMs: 0, songEndMs: 1 << 53, offsetMs: offsetMs),
      ]);

  /// Reads the JSON the Kotlin core stores per (song, video) pair.
  factory SyncMap.fromJson(Map<String, dynamic> json) {
    final raw = json['segments'] as List<dynamic>?;
    if (raw != null && raw.isNotEmpty) {
      return SyncMap(raw.map((s) => SyncSegment.fromJson(s as Map<String, dynamic>)));
    }
    final offset = json['offsetMs'] as num?;
    return offset == null ? SyncMap(const []) : SyncMap.constant(offset.toInt());
  }

  /// The segments in song order, without overlaps.
  final List<SyncSegment> segments;

  bool get isEmpty => segments.isEmpty;

  /// The offset of the longest segment: what a client that can only use one number should use.
  int get primaryOffsetMs {
    SyncSegment? best;
    for (final s in segments) {
      if (best == null || s.songEndMs - s.songStartMs > best.songEndMs - best.songStartMs) best = s;
    }
    return best?.offsetMs ?? 0;
  }

  /// The offset in force at [songMs], or null where the song has no matching picture.
  int? offsetAt(int songMs) {
    for (final s in segments) {
      if (songMs < s.songStartMs) return null;
      if (songMs < s.songEndMs) return s.offsetAtMs(songMs);
    }
    return null;
  }

  /// How many video seconds pass per song second at [songMs] (1 outside every segment).
  double rateAt(int songMs) {
    for (final s in segments) {
      if (songMs < s.songStartMs) return 1.0;
      if (songMs < s.songEndMs) return s.rate;
    }
    return 1.0;
  }

  /// The offset of the segment nearest to [songMs], for callers that need a number even in a gap.
  int offsetNear(int songMs) {
    final inside = offsetAt(songMs);
    if (inside != null) return inside;
    SyncSegment? previous;
    for (final s in segments) {
      if (songMs < s.songStartMs) {
        if (previous == null) return s.offsetMs;
        return songMs - previous.songEndMs <= s.songStartMs - songMs
            ? previous.offsetAtMs(previous.songEndMs)
            : s.offsetMs;
      }
      previous = s;
    }
    return previous?.offsetAtMs(previous.songEndMs) ?? 0;
  }

  /// Whether the song has a matching picture at [songMs].
  bool covers(int songMs) => offsetAt(songMs) != null;

  static List<SyncSegment> _normalise(Iterable<SyncSegment> input) {
    final sorted = input.where((s) => s.songEndMs > s.songStartMs).toList()
      ..sort((a, b) => a.songStartMs.compareTo(b.songStartMs));
    final out = <SyncSegment>[];
    for (final s in sorted) {
      final previous = out.isEmpty ? null : out.last;
      if (previous == null || s.songStartMs >= previous.songEndMs) {
        out.add(s);
      } else if (s.songEndMs > previous.songEndMs) {
        // overlaps the previous one: it starts where the previous ends
        out.add(SyncSegment(
          songStartMs: previous.songEndMs,
          songEndMs: s.songEndMs,
          offsetMs: s.offsetAtMs(previous.songEndMs),
          ncc: s.ncc,
          rate: s.rate,
        ));
      }
    }
    return List.unmodifiable(out);
  }
}
