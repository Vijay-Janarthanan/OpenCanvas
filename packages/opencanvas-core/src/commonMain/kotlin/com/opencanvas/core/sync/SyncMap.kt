package com.opencanvas.core.sync

import kotlinx.serialization.Serializable

/**
 * One stretch of a song that maps onto its music video with a single constant offset:
 * for `songStartMs <= songTime < songEndMs`, `videoTime = songTime + offsetMs`.
 *
 * @property ncc Mean normalised cross-correlation of the matched audio in `0..1`, a diagnostic of how
 *   well the segment matches; `0.0` when it was not measured.
 */
@Serializable
data class SyncSegment(
    val songStartMs: Long,
    val songEndMs: Long,
    val offsetMs: Long,
    val ncc: Double = 0.0,
) {
    init {
        require(songEndMs > songStartMs) { "segment must have a positive length: $songStartMs..$songEndMs" }
    }

    operator fun contains(songMs: Long): Boolean = songMs >= songStartMs && songMs < songEndMs
}

/**
 * How a song's timeline maps onto its music video's.
 *
 * A music video is usually an *edit* of the song: it has an intro, may repeat or drop bars and adds
 * an outro, so one constant offset is only right for part of the song. A map is a list of segments,
 * each with its own offset; song time outside every segment has no matching picture and a player
 * shows the still artwork there.
 *
 * Segments are sorted by start and must not overlap; overlapping or empty input is trimmed rather
 * than rejected, since the map comes from measured data.
 */
class SyncMap(segments: List<SyncSegment>) {

    /** The segments in song order, without overlaps. */
    val segments: List<SyncSegment> = normalise(segments)

    /** True when no part of the song maps onto the video. */
    val isEmpty: Boolean get() = segments.isEmpty()

    /**
     * The offset of the longest segment: what a client that can only use one number should use.
     * Zero for an empty map.
     */
    val primaryOffsetMs: Long
        get() = segments.maxByOrNull { it.songEndMs - it.songStartMs }?.offsetMs ?: 0L

    /** The offset in force at [songMs], or null where the song has no matching picture. */
    fun offsetAt(songMs: Long): Long? {
        // Few segments (a handful at most): a linear scan is as fast as a binary search.
        for (segment in segments) {
            if (songMs < segment.songStartMs) return null
            if (songMs < segment.songEndMs) return segment.offsetMs
        }
        return null
    }

    /**
     * The offset of the segment nearest to [songMs], for callers that need a number even where the
     * song has no picture. Zero for an empty map.
     */
    fun offsetNear(songMs: Long): Long {
        offsetAt(songMs)?.let { return it }
        var previous: SyncSegment? = null
        for (segment in segments) {
            if (songMs < segment.songStartMs) {
                // in a gap (or before the first segment): whichever neighbour is closer
                val before = previous ?: return segment.offsetMs
                return if (songMs - before.songEndMs <= segment.songStartMs - songMs) before.offsetMs else segment.offsetMs
            }
            previous = segment
        }
        return previous?.offsetMs ?: 0L // past the end: the last segment's offset
    }

    /** Whether the song has a matching picture at [songMs]. */
    fun covers(songMs: Long): Boolean = offsetAt(songMs) != null

    override fun equals(other: Any?): Boolean = other is SyncMap && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "SyncMap($segments)"

    companion object {
        /** A map for a video that is the song shifted by one constant [offsetMs] for its whole length. */
        fun constant(offsetMs: Long): SyncMap = SyncMap(listOf(SyncSegment(0L, Long.MAX_VALUE, offsetMs)))

        private fun normalise(input: List<SyncSegment>): List<SyncSegment> {
            val sorted = input.sortedBy { it.songStartMs }
            val out = ArrayList<SyncSegment>(sorted.size)
            for (segment in sorted) {
                val previous = out.lastOrNull()
                if (previous == null || segment.songStartMs >= previous.songEndMs) {
                    out += segment
                } else if (segment.songEndMs > previous.songEndMs) {
                    // overlaps the previous one: it starts where the previous ends
                    out += SyncSegment(previous.songEndMs, segment.songEndMs, segment.offsetMs)
                }
            }
            return out
        }
    }
}
