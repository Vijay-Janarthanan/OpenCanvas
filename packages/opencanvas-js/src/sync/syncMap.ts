/**
 * One stretch of a song that maps onto its music video with a single constant offset:
 * for `songStartMs <= songTime < songEndMs`, `videoTime = songTime + offsetMs`.
 *
 * A video whose music runs at another speed than the song's has a `rate` other than 1; then
 * `videoTime = songTime + offsetMs + (rate - 1) * (songTime - songStartMs)`.
 */
export interface SyncSegment {
  songStartMs: number;
  songEndMs: number;
  offsetMs: number;
  /** Mean normalised cross-correlation of the matched audio, 0..1 (diagnostic). */
  ncc?: number;
  /** Video seconds per song second; 1 when omitted. */
  rate?: number;
}

/** `videoTime - songTime` at `songMs` (extrapolated, whether or not it lies inside the segment). */
export function offsetAtMs(segment: SyncSegment, songMs: number): number {
  const rate = segment.rate ?? 1;
  return rate === 1 ? segment.offsetMs : segment.offsetMs + Math.round((rate - 1) * (songMs - segment.songStartMs));
}

/**
 * How a song's timeline maps onto its music video's. A music video is usually an edit of the song,
 * so a map is a list of segments with their own offsets; song time outside every segment has no
 * matching picture. Same behaviour as `SyncMap` in the Kotlin core.
 */
export class SyncMap {
  readonly segments: readonly SyncSegment[];

  constructor(segments: readonly SyncSegment[]) {
    this.segments = normalise(segments);
  }

  /** A map for a video that is the song shifted by one constant offset for its whole length. */
  static constant(offsetMs: number): SyncMap {
    return new SyncMap([{ songStartMs: 0, songEndMs: Number.MAX_SAFE_INTEGER, offsetMs }]);
  }

  /** Reads the JSON the Kotlin core stores per (song, video) pair, and the community maps. */
  static fromJson(json: { segments?: readonly SyncSegment[]; offsetMs?: number }): SyncMap {
    if (json.segments && json.segments.length > 0) return new SyncMap(json.segments);
    return json.offsetMs === undefined ? new SyncMap([]) : SyncMap.constant(json.offsetMs);
  }

  get isEmpty(): boolean {
    return this.segments.length === 0;
  }

  /** The offset of the longest segment: what a client that can only use one number should use. */
  get primaryOffsetMs(): number {
    let best: SyncSegment | undefined;
    for (const s of this.segments) {
      if (!best || s.songEndMs - s.songStartMs > best.songEndMs - best.songStartMs) best = s;
    }
    return best?.offsetMs ?? 0;
  }

  /** The offset in force at `songMs`, or `null` where the song has no matching picture. */
  offsetAt(songMs: number): number | null {
    for (const s of this.segments) {
      if (songMs < s.songStartMs) return null;
      if (songMs < s.songEndMs) return offsetAtMs(s, songMs);
    }
    return null;
  }

  /** How many video seconds pass per song second at `songMs` (1 outside every segment). */
  rateAt(songMs: number): number {
    for (const s of this.segments) {
      if (songMs < s.songStartMs) return 1;
      if (songMs < s.songEndMs) return s.rate ?? 1;
    }
    return 1;
  }

  /** The offset of the segment nearest to `songMs`, for callers that need a number even in a gap. */
  offsetNear(songMs: number): number {
    const inside = this.offsetAt(songMs);
    if (inside !== null) return inside;
    let previous: SyncSegment | undefined;
    for (const s of this.segments) {
      if (songMs < s.songStartMs) {
        if (!previous) return s.offsetMs;
        return songMs - previous.songEndMs <= s.songStartMs - songMs ? offsetAtMs(previous, previous.songEndMs) : s.offsetMs;
      }
      previous = s;
    }
    return previous ? offsetAtMs(previous, previous.songEndMs) : 0;
  }

  /** Whether the song has a matching picture at `songMs`. */
  covers(songMs: number): boolean {
    return this.offsetAt(songMs) !== null;
  }
}

function normalise(input: readonly SyncSegment[]): SyncSegment[] {
  const sorted = [...input]
    .filter((s) => s.songEndMs > s.songStartMs)
    .sort((a, b) => a.songStartMs - b.songStartMs);
  const out: SyncSegment[] = [];
  for (const s of sorted) {
    const previous = out[out.length - 1];
    if (!previous || s.songStartMs >= previous.songEndMs) {
      out.push(s);
    } else if (s.songEndMs > previous.songEndMs) {
      // overlaps the previous one: it starts where the previous ends
      out.push({ songStartMs: previous.songEndMs, songEndMs: s.songEndMs, offsetMs: offsetAtMs(s, previous.songEndMs), ncc: s.ncc, rate: s.rate });
    }
  }
  return out;
}
