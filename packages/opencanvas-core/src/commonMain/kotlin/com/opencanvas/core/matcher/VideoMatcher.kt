package com.opencanvas.core.matcher

import kotlin.math.abs

data class VideoCandidate(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val durationSec: Long,
    val isOfficialMusicVideo: Boolean = false,
)

object VideoMatcher {

    private val NEGATIVE_KEYWORDS = listOf(
        "lyric", "lyrics", "audio", "official audio", "reaction",
        "live", "concert", "cover", "karaoke", "instrumental",
        "slowed", "reverb", "1 hour", "10 hours", "parody",
        "behind the scenes", "making of", "teaser", "trailer"
    )

    private val POSITIVE_KEYWORDS = listOf(
        "official video", "official music video", "music video"
    )

    /**
     * Scores how likely a candidate video is the true visual music video for a given track.
     * Higher score = better match.
     */
    fun scoreCandidate(
        candidate: VideoCandidate,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Long = 0L,
    ): Int {
        var score = 0
        val titleNorm = candidate.title.lowercase()
        val artistNorm = expectedArtist.lowercase()
        val trackTitleNorm = expectedTitle.lowercase()
        val channelNorm = candidate.channelTitle.lowercase()

        // 1. YouTube Music OMV badge
        if (candidate.isOfficialMusicVideo) {
            score += 100
        }

        // 2. Channel credibility
        if (channelNorm.contains(artistNorm)) score += 35
        if (channelNorm.endsWith("vevo") || channelNorm.contains("vevo")) score += 30
        if (channelNorm.endsWith(" - topic")) score += 10

        // 3. Positive title cues
        if (POSITIVE_KEYWORDS.any { titleNorm.contains(it) }) score += 40
        if (titleNorm.contains(trackTitleNorm)) score += 30

        // 4. Negative title penalties
        for (neg in NEGATIVE_KEYWORDS) {
            if (titleNorm.contains(neg)) {
                // Harsh penalty for lyric/reaction/audio
                score -= 100
            }
        }

        // 5. Duration closeness (if expected duration known)
        if (expectedDurationSec > 0L && candidate.durationSec > 0L) {
            val diffSec = abs(candidate.durationSec - expectedDurationSec)
            when {
                diffSec <= 8L -> score += 30
                diffSec <= 20L -> score += 15
                diffSec > 60L -> score -= 40
            }
        }

        return score
    }

    /**
     * Picks the best official music video among candidates.
     */
    fun pickBest(
        candidates: List<VideoCandidate>,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Long = 0L,
    ): VideoCandidate? {
        if (candidates.isEmpty()) return null
        return candidates
            .map { it to scoreCandidate(it, expectedTitle, expectedArtist, expectedDurationSec) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
