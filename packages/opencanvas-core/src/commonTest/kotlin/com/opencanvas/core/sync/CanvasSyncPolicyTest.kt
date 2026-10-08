package com.opencanvas.core.sync

import com.opencanvas.core.sync.CanvasSyncAction.Ended
import com.opencanvas.core.sync.CanvasSyncAction.Hidden
import com.opencanvas.core.sync.CanvasSyncAction.Hold
import com.opencanvas.core.sync.CanvasSyncAction.Nudge
import com.opencanvas.core.sync.CanvasSyncAction.SeekTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanvasSyncPolicyTest {

    // Official Blinding Lights video: song starts 22.5 s into a 263 s video.
    private val blindingLights = CanvasSyncPolicy(offsetMs = 22_500L, videoDurationMs = 263_000L)

    private data class DecideCase(
        val name: String,
        val policy: CanvasSyncPolicy,
        val songMs: Long,
        val videoMs: Long,
        val playing: Boolean = true,
        val ready: Boolean = true,
        val expected: CanvasSyncAction,
    )

    private fun assertSameAction(expected: CanvasSyncAction, actual: CanvasSyncAction, name: String) {
        if (expected is Nudge) {
            val nudge = assertIs<Nudge>(actual, "$name: expected $expected but was $actual")
            assertEquals(expected.speed, nudge.speed, 0.0001f, "$name: speed")
        } else {
            assertEquals(expected, actual, name)
        }
    }

    private fun runCases(cases: List<DecideCase>) {
        for (c in cases) {
            val actual = c.policy.decide(c.songMs, c.videoMs, c.playing, c.ready)
            assertSameAction(c.expected, actual, c.name)
        }
    }

    // ---- targetVideoMs / isVisible ----

    @Test
    fun targetIsSongPositionPlusOffsetWithoutClamping() {
        assertEquals(22_500L, blindingLights.targetVideoMs(0L))
        assertEquals(32_500L, blindingLights.targetVideoMs(10_000L))
        val negative = CanvasSyncPolicy(offsetMs = -5_000L, videoDurationMs = 100_000L)
        assertEquals(-5_000L, negative.targetVideoMs(0L))
        assertEquals(-1L, negative.targetVideoMs(4_999L))
        assertEquals(500_000L, negative.targetVideoMs(505_000L))
    }

    @Test
    fun visibilityFollowsVideoBounds() {
        data class Case(val name: String, val policy: CanvasSyncPolicy, val songMs: Long, val visible: Boolean)
        val negativeOffset = CanvasSyncPolicy(offsetMs = -5_000L, videoDurationMs = 100_000L)
        val noOffset = CanvasSyncPolicy(offsetMs = 0L, videoDurationMs = 100_000L)
        val unknownEnd = CanvasSyncPolicy(offsetMs = 0L, videoDurationMs = 0L)
        val cases = listOf(
            Case("positive offset, song start is inside the video", blindingLights, 0L, true),
            Case("negative offset, song start is before the video", negativeOffset, 0L, false),
            Case("negative offset, last hidden ms", negativeOffset, 4_999L, false),
            Case("negative offset, first visible ms", negativeOffset, 5_000L, true),
            Case("last visible ms", noOffset, 99_999L, true),
            Case("target == duration is not visible", noOffset, 100_000L, false),
            Case("far past the end", noOffset, 900_000L, false),
            Case("unknown duration never ends", unknownEnd, 10_000_000L, true),
            Case("unknown duration still hides before start", CanvasSyncPolicy(-1L, 0L), 0L, false),
        )
        for (c in cases) assertEquals(c.visible, c.policy.isVisible(c.songMs), c.name)
    }

    // ---- decide ----

    @Test
    fun hiddenBeforeTheVideoStarts() {
        val negative = CanvasSyncPolicy(offsetMs = -5_000L, videoDurationMs = 200_000L)
        runCases(
            listOf(
                DecideCase("playing", negative, 3_000L, 0L, expected = Hidden),
                DecideCase("paused", negative, 3_000L, 0L, playing = false, expected = Hidden),
                DecideCase("not ready still hides", negative, 3_000L, 0L, ready = false, expected = Hidden),
                DecideCase("last hidden ms", negative, 4_999L, 0L, expected = Hidden),
                DecideCase("first visible ms is not hidden", negative, 5_000L, 0L, expected = Nudge(1.0f)),
            ),
        )
    }

    @Test
    fun endedAtOrPastTheVideoDuration() {
        runCases(
            listOf(
                DecideCase("target == duration", blindingLights, 240_500L, 262_900L, expected = Ended),
                DecideCase("well past the end", blindingLights, 300_000L, 262_900L, expected = Ended),
                DecideCase("paused past the end", blindingLights, 300_000L, 0L, playing = false, expected = Ended),
                DecideCase("not ready past the end", blindingLights, 300_000L, 0L, ready = false, expected = Ended),
                DecideCase("last ms before the end", blindingLights, 240_499L, 262_999L, expected = Nudge(1.0f)),
            ),
        )
    }

    @Test
    fun unknownDurationNeverEnds() {
        val unknownEnd = CanvasSyncPolicy(offsetMs = 22_500L, videoDurationMs = 0L)
        runCases(
            listOf(
                DecideCase("hours in", unknownEnd, 36_000_000L, 36_022_500L, expected = Nudge(1.0f)),
                DecideCase("hours in, drifted", unknownEnd, 36_000_000L, 0L, expected = SeekTo(36_022_500L)),
            ),
        )
    }

    @Test
    fun holdsWhenVideoIsNotReady() {
        runCases(
            listOf(
                DecideCase("in sync", blindingLights, 10_000L, 32_500L, ready = false, expected = Hold),
                DecideCase("far off", blindingLights, 10_000L, 0L, ready = false, expected = Hold),
                DecideCase("paused", blindingLights, 10_000L, 0L, playing = false, ready = false, expected = Hold),
            ),
        )
    }

    @Test
    fun playingInsideToleranceRunsAtNormalSpeed() {
        // target = 32_500
        runCases(
            listOf(
                DecideCase("exact", blindingLights, 10_000L, 32_500L, expected = Nudge(1.0f)),
                DecideCase("video ahead by tolerance", blindingLights, 10_000L, 32_650L, expected = Nudge(1.0f)),
                DecideCase("video behind by tolerance", blindingLights, 10_000L, 32_350L, expected = Nudge(1.0f)),
                DecideCase("video ahead by 1 ms", blindingLights, 10_000L, 32_501L, expected = Nudge(1.0f)),
            ),
        )
    }

    @Test
    fun nudgeDirectionAndLimits() {
        // target = 32_500; speed = 1 - clamp(drift / 4000, -0.05, 0.05); ahead (drift > 0) slows down.
        runCases(
            listOf(
                DecideCase("ahead 151 ms slows slightly", blindingLights, 10_000L, 32_651L, expected = Nudge(1f - 151f / 4000f)),
                DecideCase("ahead 200 ms reaches the limit", blindingLights, 10_000L, 32_700L, expected = Nudge(0.95f)),
                DecideCase("ahead 500 ms is clamped", blindingLights, 10_000L, 33_000L, expected = Nudge(0.95f)),
                DecideCase("behind 151 ms speeds up slightly", blindingLights, 10_000L, 32_349L, expected = Nudge(1f + 151f / 4000f)),
                DecideCase("behind 200 ms reaches the limit", blindingLights, 10_000L, 32_300L, expected = Nudge(1.05f)),
                DecideCase("behind 500 ms is clamped", blindingLights, 10_000L, 32_000L, expected = Nudge(1.05f)),
            ),
        )
        for (videoMs in 32_000L..33_000L step 7L) {
            val action = blindingLights.decide(10_000L, videoMs, songPlaying = true, videoReady = true)
            if (action is Nudge) {
                assertTrue(action.speed in 0.95f..1.05f, "speed ${action.speed} out of bounds at video $videoMs")
                if (videoMs > 32_650L) assertTrue(action.speed < 1f, "ahead must slow down at video $videoMs")
                if (videoMs < 32_350L) assertTrue(action.speed > 1f, "behind must speed up at video $videoMs")
            }
        }
    }

    @Test
    fun hardSeekBeyondThreshold() {
        runCases(
            listOf(
                DecideCase("ahead at the threshold still nudges", blindingLights, 10_000L, 33_000L, expected = Nudge(0.95f)),
                DecideCase("ahead 501 ms seeks", blindingLights, 10_000L, 33_001L, expected = SeekTo(32_500L)),
                DecideCase("behind 501 ms seeks", blindingLights, 10_000L, 31_999L, expected = SeekTo(32_500L)),
                DecideCase("user scrubbed forward", blindingLights, 120_000L, 32_500L, expected = SeekTo(142_500L)),
                DecideCase("user scrubbed backward", blindingLights, 5_000L, 142_500L, expected = SeekTo(27_500L)),
                DecideCase("new track video still at 0", blindingLights, 0L, 0L, expected = SeekTo(22_500L)),
            ),
        )
    }

    @Test
    fun pausedSongRepositionsVideoWithinTolerance() {
        runCases(
            listOf(
                DecideCase("exact", blindingLights, 10_000L, 32_500L, playing = false, expected = Hold),
                DecideCase("ahead by tolerance", blindingLights, 10_000L, 32_650L, playing = false, expected = Hold),
                DecideCase("behind by tolerance", blindingLights, 10_000L, 32_350L, playing = false, expected = Hold),
                DecideCase("ahead 151 ms seeks", blindingLights, 10_000L, 32_651L, playing = false, expected = SeekTo(32_500L)),
                DecideCase("behind 151 ms seeks", blindingLights, 10_000L, 32_349L, playing = false, expected = SeekTo(32_500L)),
                DecideCase("scrub while paused", blindingLights, 100_000L, 32_500L, playing = false, expected = SeekTo(122_500L)),
            ),
        )
    }

    @Test
    fun customThresholdsAreHonoured() {
        val tight = CanvasSyncPolicy(offsetMs = 0L, videoDurationMs = 100_000L, toleranceMs = 50L, hardSeekMs = 200L)
        runCases(
            listOf(
                DecideCase("inside tight tolerance", tight, 10_000L, 10_050L, expected = Nudge(1.0f)),
                DecideCase("nudge band", tight, 10_000L, 10_100L, expected = Nudge(1f - 100f / 4000f)),
                DecideCase("beyond tight hard seek", tight, 10_000L, 10_201L, expected = SeekTo(10_000L)),
                DecideCase("paused beyond tight tolerance", tight, 10_000L, 10_051L, playing = false, expected = SeekTo(10_000L)),
            ),
        )
    }

    @Test
    fun invalidThresholdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CanvasSyncPolicy(0L, 1_000L, toleranceMs = -1L) }
        assertFailsWith<IllegalArgumentException> { CanvasSyncPolicy(0L, 1_000L, toleranceMs = 300L, hardSeekMs = 200L) }
        // Equal thresholds are allowed: no nudge band, only in-sync or seek.
        val equal = CanvasSyncPolicy(0L, 100_000L, toleranceMs = 200L, hardSeekMs = 200L)
        assertEquals(SeekTo(10_000L), equal.decide(10_000L, 10_201L, songPlaying = true, videoReady = true))
    }

    @Test
    fun defaultsMatchTheDocumentedValues() {
        assertEquals(150L, blindingLights.toleranceMs)
        assertEquals(500L, blindingLights.hardSeekMs)
        assertEquals(250L, CanvasSyncDefaults.CHECK_INTERVAL_MS)
        assertEquals(0.4, CanvasSyncDefaults.MIN_SYNC_CONFIDENCE)
    }

    // ---- seekDetected ----

    private data class SeekCase(
        val name: String,
        val prevMs: Long,
        val nowMs: Long,
        val elapsedMs: Long,
        val playing: Boolean,
        val expected: Boolean,
    )

    @Test
    fun seekDetectionTable() {
        val cases = listOf(
            SeekCase("normal play", 10_000L, 10_250L, 250L, true, false),
            SeekCase("normal play, position ran 300 ms ahead of wall time", 10_000L, 10_550L, 250L, true, false),
            SeekCase("normal play, slight stall", 10_000L, 10_100L, 250L, true, false),
            SeekCase("unexplained advance exactly at the limit", 10_000L, 11_250L, 250L, true, false),
            SeekCase("unexplained advance just over the limit", 10_000L, 11_251L, 250L, true, true),
            SeekCase("forward seek", 10_000L, 60_000L, 250L, true, true),
            SeekCase("backward seek", 60_000L, 10_000L, 250L, true, true),
            SeekCase("small backward step", 10_000L, 9_500L, 250L, true, false),
            SeekCase("backward step just over the limit", 10_000L, 8_700L, 250L, true, true),
            SeekCase("restart on next track", 180_000L, 0L, 250L, true, true),
            SeekCase("host stalled but position kept up", 10_000L, 15_000L, 5_000L, true, false),
            SeekCase("paused, unchanged", 10_000L, 10_000L, 250L, false, false),
            SeekCase("paused, tiny drift", 10_000L, 10_400L, 250L, false, false),
            SeekCase("scrub while paused forward", 10_000L, 50_000L, 250L, false, true),
            SeekCase("scrub while paused backward", 50_000L, 10_000L, 250L, false, true),
            SeekCase("paused over a long interval", 10_000L, 10_000L, 60_000L, false, false),
            SeekCase("negative elapsed treated as zero", 10_000L, 10_000L, -50L, true, false),
            SeekCase("negative elapsed does not hide a jump", 10_000L, 20_000L, -50L, true, true),
        )
        for (c in cases) {
            assertEquals(
                c.expected,
                blindingLights.seekDetected(c.prevMs, c.nowMs, c.elapsedMs, c.playing),
                c.name,
            )
        }
    }

    @Test
    fun seekDetectionIsIndependentOfOffsetAndDuration() {
        val other = CanvasSyncPolicy(offsetMs = -9_000L, videoDurationMs = 0L)
        assertFalse(other.seekDetected(10_000L, 10_250L, 250L, true))
        assertTrue(other.seekDetected(10_000L, 60_000L, 250L, true))
    }

    // ---- scenario ----

    @Test
    fun scenarioPlayThenScrubThenPauseThenEnd() {
        val policy = blindingLights
        var video = 0L

        // Track starts: the video is at 0, the song at 0 -> jump straight to the intro end.
        assertEquals(SeekTo(22_500L), policy.decide(0L, video, songPlaying = true, videoReady = true))
        video = 22_500L

        // Playing normally for a second: stays in sync.
        for (songMs in 250L..1_000L step 250L) {
            video += 250L
            assertEquals(
                Nudge(1.0f),
                policy.decide(songMs, video, songPlaying = true, videoReady = true),
                "song $songMs",
            )
        }

        // User scrubs to 2:00 -> detected immediately, then the video jumps.
        assertTrue(policy.seekDetected(1_000L, 120_000L, 250L, playing = true))
        assertEquals(SeekTo(142_500L), policy.decide(120_000L, video, songPlaying = true, videoReady = true))
        video = 142_500L

        // Pause: video already in place -> hold.
        assertEquals(Hold, policy.decide(120_000L, video, songPlaying = false, videoReady = true))

        // Song reaches past the end of the video: ended, never loops.
        assertEquals(Ended, policy.decide(250_000L, 262_999L, songPlaying = true, videoReady = true))
    }

    @Test
    fun aVideoAtFilmSpeedIsPlayedAtThatSpeedToKeepUp() {
        val policy = CanvasSyncPolicy(SyncMap(listOf(SyncSegment(0, 200_000, 1_000, rate = 1.0417))), 300_000)
        val target = 20_000 + 1_000 + (0.0417 * 20_000).toLong()
        val action = policy.decide(20_000, target, songPlaying = true, videoReady = true)
        assertEquals(CanvasSyncAction.Nudge(1.0417f), action)
    }
}
