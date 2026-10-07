package com.opencanvas.core

import com.opencanvas.core.filter.CropKinematics
import com.opencanvas.core.filter.OneEuroFilter
import com.opencanvas.core.filter.SceneCutDetector
import com.opencanvas.core.heatmap.HeatmapParser
import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.models.CropKeyframe
import com.opencanvas.core.models.CropTrajectory
import com.opencanvas.core.models.DetectionBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCanvasCoreTest {

    @Test
    fun testOneEuroFilterSmoothsJitter() {
        val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.007)
        var t = 0.0
        // Simulate stationary signal with noisy jitter
        val rawValues = listOf(0.50, 0.52, 0.48, 0.53, 0.47, 0.51, 0.49)
        val filtered = mutableListOf<Double>()

        for (v in rawValues) {
            filtered.add(filter.filter(v, t))
            t += 0.033 // ~30 fps
        }

        // The filtered variance should be much lower than raw noise
        val maxDeviation = filtered.map { kotlin.math.abs(it - 0.50) }.maxOrNull() ?: 1.0
        assertTrue(maxDeviation < 0.025, "1-Euro filter did not smooth jitter adequately: $maxDeviation")
    }

    @Test
    fun testCropKinematicsClamping() {
        // 16:9 widescreen source (1.777), 9:16 portrait target (0.5625)
        val normWidth = CropKinematics.normalizedCropWidth(16f / 9f, 9f / 16f)
        assertEquals(81f / 256f, normWidth, 0.001f)

        val halfCrop = normWidth / 2f
        // Extreme left edge should be clamped to halfCrop
        val clampLeft = CropKinematics.clampFocalCenter(0.0f, 16f / 9f, 9f / 16f)
        assertEquals(halfCrop, clampLeft, 0.001f)

        // Extreme right edge should be clamped to 1.0 - halfCrop
        val clampRight = CropKinematics.clampFocalCenter(1.0f, 16f / 9f, 9f / 16f)
        assertEquals(1.0f - halfCrop, clampRight, 0.001f)

        // Center remains centered
        val clampCenter = CropKinematics.clampFocalCenter(0.5f, 16f / 9f, 9f / 16f)
        assertEquals(0.5f, clampCenter, 0.001f)
    }

    @Test
    fun testCropTrajectorySceneCutSnapping() {
        val trajectory = CropTrajectory(
            videoId = "test1234",
            keyframes = listOf(
                CropKeyframe(t = 0.0, cx = 0.30f),
                CropKeyframe(t = 1.0, cx = 0.35f),
                // Scene cut at t = 2.0 with instant snap to right
                CropKeyframe(t = 2.0, cx = 0.80f, isSceneCut = true),
                CropKeyframe(t = 3.0, cx = 0.82f),
            )
        )

        // At t = 0.5 (within shot 1), interpolates smoothly
        val midShot1 = trajectory.cropAt(0.5)
        assertTrue(midShot1 in 0.30f..0.35f)

        // Right before cut at t = 1.99s, stays in shot 1
        val beforeCut = trajectory.cropAt(1.99)
        assertTrue(beforeCut < 0.40f, "Should stay near shot 1 before cut: $beforeCut")

        // Exactly at cut t = 2.0s, snaps to 0.80f
        val atCut = trajectory.cropAt(2.0)
        assertEquals(0.80f, atCut, 0.001f)
    }

    @Test
    fun testHeatmapPeakWindowSelection() {
        val mockPlayerJson = """
            {
                "frameworkUpdates": {
                    "entityBatchUpdate": {
                        "mutations": [
                            {
                                "payload": {
                                    "macroMarkersListEntity": {
                                        "markersList": {
                                            "markers": [
                                                {"startMillis": "10000", "durationMillis": "2000", "intensityScoreNormalized": 0.2},
                                                {"startMillis": "45000", "durationMillis": "2000", "intensityScoreNormalized": 0.98},
                                                {"startMillis": "80000", "durationMillis": "2000", "intensityScoreNormalized": 0.4}
                                            ]
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val (startMs, endMs) = HeatmapParser.findPeakLoopWindow(
            playerResponseJson = mockPlayerJson,
            videoDurationMs = 180000L,
            preferredLoopDurationMs = 10000L
        )

        // Peak is at 45000ms. With a 10s loop centered at peak: 45000 - 5000 = 40000 to 50000.
        assertEquals(40000L, startMs)
        assertEquals(50000L, endMs)
    }

    @Test
    fun testVideoMatcherPrioritization() {
        val candidates = listOf(
            VideoCandidate(
                videoId = "vid_lyrics",
                title = "The Weeknd - Blinding Lights (Lyrics)",
                channelTitle = "7clouds",
                durationSec = 200,
            ),
            VideoCandidate(
                videoId = "vid_reaction",
                title = "Vocal Coach Reacts to Blinding Lights",
                channelTitle = "Music Reactions",
                durationSec = 600,
            ),
            VideoCandidate(
                videoId = "vid_omv",
                title = "The Weeknd - Blinding Lights (Official Video)",
                channelTitle = "TheWeekndVEVO",
                durationSec = 263,
                isOfficialMusicVideo = true,
            )
        )

        val best = VideoMatcher.pickBest(
            candidates = candidates,
            expectedTitle = "Blinding Lights",
            expectedArtist = "The Weeknd",
            expectedDurationSec = 260
        )

        assertNotNull(best)
        assertEquals("vid_omv", best.videoId)
    }

    @Test
    fun testSceneCutDetectorJump() {
        val detector = SceneCutDetector(boxJumpThreshold = 0.25f)
        assertFalse(detector.checkCenterJump(0.50f))
        assertFalse(detector.checkCenterJump(0.55f)) // Small movement (0.05)
        assertTrue(detector.checkCenterJump(0.85f))  // Big jump (0.30) -> camera cut!
    }

    @Test
    fun testDetectionBoxIoU() {
        val boxA = DetectionBox(0.1f, 0.1f, 0.5f, 0.5f, 0.9f)
        val boxB = DetectionBox(0.1f, 0.1f, 0.5f, 0.5f, 0.85f)
        assertEquals(1.0f, boxA.iou(boxB), 0.001f)

        val boxC = DetectionBox(0.6f, 0.6f, 0.9f, 0.9f, 0.9f)
        assertEquals(0.0f, boxA.iou(boxC), 0.001f)
    }
}
