import { SyncMap } from './syncMap.js';

export const CanvasSyncDefaults = {
  /** Recommended period, in milliseconds, between two `decide` calls. */
  CHECK_INTERVAL_MS: 250,
  /** Drift, in milliseconds, below which the video is considered in sync. */
  TOLERANCE_MS: 150,
  /** Drift, in milliseconds, above which the video is re-positioned with a hard seek. */
  HARD_SEEK_MS: 500,
  /** Song-position jump beyond what wall time explains, above which `seekDetected` reports a seek. */
  SEEK_JUMP_MS: 1000,
} as const;

/** What the host should do to the video player right now. */
export type CanvasSyncAction =
  /** No matching picture here (a gap in the map, or before the video starts): show the still artwork. */
  | { kind: 'hidden' }
  /** Do nothing: the video is not ready yet, or the song is paused and the video is in place. */
  | { kind: 'hold' }
  /** Hard-seek the video player to `videoMs` (always inside the video). */
  | { kind: 'seek'; videoMs: number }
  /** Keep playing at `speed` (1 when in sync, at most 5 % off to absorb small drift). */
  | { kind: 'nudge'; speed: number }
  /** The song outlasts the video. The canvas never loops: show the still artwork. */
  | { kind: 'ended' };

const NUDGE_DIVISOR_MS = 4000;
const MAX_NUDGE = 0.05;

/**
 * Pure brain that keeps a canvas video locked to the song being played. Same rules as
 * `CanvasSyncPolicy` in the Kotlin core; it contains no player: use anything that reports a position
 * and seeks. On a track change create a new policy from the new track.
 */
export class CanvasSyncPolicy {
  constructor(
    readonly syncMap: SyncMap,
    /** Video length in milliseconds; `<= 0` means the end is unknown. */
    readonly videoDurationMs: number,
    readonly toleranceMs: number = CanvasSyncDefaults.TOLERANCE_MS,
    readonly hardSeekMs: number = CanvasSyncDefaults.HARD_SEEK_MS,
  ) {
    if (toleranceMs < 0) throw new RangeError(`toleranceMs must be >= 0, was ${toleranceMs}`);
    if (hardSeekMs < toleranceMs) throw new RangeError(`hardSeekMs (${hardSeekMs}) must be >= toleranceMs (${toleranceMs})`);
  }

  /** Video position that matches `songPositionMs` (the nearest segment's offset in a gap). Not clamped. */
  targetVideoMs(songPositionMs: number): number {
    return songPositionMs + this.syncMap.offsetNear(songPositionMs);
  }

  /** Whether the video should be on screen at `songPositionMs`. */
  isVisible(songPositionMs: number): boolean {
    const offset = this.syncMap.offsetAt(songPositionMs);
    if (offset === null) return false;
    const target = songPositionMs + offset;
    return target >= 0 && (this.videoDurationMs <= 0 || target < this.videoDurationMs);
  }

  /**
   * Decides what the host should do to the video player. `drift = videoPositionMs - target`, so a
   * positive drift means the video is ahead.
   */
  decide(songPositionMs: number, videoPositionMs: number, songPlaying: boolean, videoReady: boolean): CanvasSyncAction {
    const offset = this.syncMap.offsetAt(songPositionMs);
    if (offset === null) return { kind: 'hidden' };
    const target = songPositionMs + offset;
    if (target < 0) return { kind: 'hidden' };
    if (this.videoDurationMs > 0 && target >= this.videoDurationMs) return { kind: 'ended' };
    if (!videoReady) return { kind: 'hold' };

    const rate = this.syncMap.rateAt(songPositionMs);
    const drift = videoPositionMs - target;
    const absDrift = Math.abs(drift);
    if (!songPlaying) return absDrift > this.toleranceMs ? { kind: 'seek', videoMs: target } : { kind: 'hold' };
    if (absDrift <= this.toleranceMs) return { kind: 'nudge', speed: rate };
    if (absDrift <= this.hardSeekMs) return { kind: 'nudge', speed: rate * nudgeSpeed(drift) };
    return { kind: 'seek', videoMs: target };
  }

  /**
   * Whether the song position jumped by more than `SEEK_JUMP_MS` beyond what wall-clock time
   * explains, meaning the user scrubbed or skipped. Run `decide` at once when it returns true.
   */
  seekDetected(prevSongMs: number, songMs: number, elapsedWallMs: number, playing: boolean): boolean {
    const expectedAdvance = playing ? Math.max(elapsedWallMs, 0) : 0;
    return Math.abs(songMs - prevSongMs - expectedAdvance) > CanvasSyncDefaults.SEEK_JUMP_MS;
  }
}

function nudgeSpeed(driftMs: number): number {
  return 1 - Math.min(MAX_NUDGE, Math.max(-MAX_NUDGE, driftMs / NUDGE_DIVISOR_MS));
}
