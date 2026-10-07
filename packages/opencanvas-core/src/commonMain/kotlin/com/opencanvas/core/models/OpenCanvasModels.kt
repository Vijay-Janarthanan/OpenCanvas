package com.opencanvas.core.models

import kotlinx.serialization.Serializable

/**
 * Playback mode for OpenCanvas.
 */
@Serializable
enum class OpenCanvasMode {
    /** Plays a short 8–15s visual climax loop continuously behind the track (Spotify-style). */
    LOOP_CANVAS,
    /** Streams the entire music video live from start to finish, reframed to 9:16 portrait. */
    FULL_SYNCED_VIDEO,
}

/**
 * A discrete crop position keyframe in normalized coordinates [0.0f, 1.0f].
 */
@Serializable
data class CropKeyframe(
    val t: Double,
    val cx: Float,
    val cy: Float = 0.5f,
    val isSceneCut: Boolean = false,
)

/**
 * A continuous horizontal crop trajectory over time.
 */
@Serializable
data class CropTrajectory(
    val videoId: String,
    val keyframes: List<CropKeyframe> = emptyList(),
) {
    /**
     * Resolves the normalized horizontal focal center [0.0f, 1.0f] at any given timestamp.
     * Snaps instantly at scene cut boundaries without whip-panning;
     * interpolates smoothly within the same shot.
     */
    fun cropAt(timestampSec: Double): Float {
        if (keyframes.isEmpty()) return 0.5f
        if (timestampSec <= keyframes.first().t) return keyframes.first().cx
        if (timestampSec >= keyframes.last().t) return keyframes.last().cx

        // Binary search for the surrounding keyframes
        var low = 0
        var high = keyframes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTime = keyframes[mid].t
            when {
                midTime < timestampSec -> low = mid + 1
                midTime > timestampSec -> high = mid - 1
                else -> return keyframes[mid].cx
            }
        }

        val kPrev = keyframes[high.coerceAtLeast(0)]
        val kNext = keyframes[low.coerceAtMost(keyframes.size - 1)]

        // If next keyframe is a scene cut, snap immediately rather than interpolating across shots
        if (kNext.isSceneCut) {
            return if (timestampSec < kNext.t) kPrev.cx else kNext.cx
        }

        val dt = kNext.t - kPrev.t
        if (dt <= 1e-4) return kPrev.cx

        val progress = ((timestampSec - kPrev.t) / dt).toFloat().coerceIn(0f, 1f)
        // Smooth Hermite / S-curve interpolation
        val smoothProgress = progress * progress * (3f - 2f * progress)
        return kPrev.cx + (kNext.cx - kPrev.cx) * smoothProgress
    }
}

/**
 * Normalized 2D bounding box returned by on-device object/face detector.
 */
data class DetectionBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val confidence: Float,
) {
    val centerX: Float get() = (xMin + xMax) / 2f
    val centerY: Float get() = (yMin + yMax) / 2f
    val width: Float get() = (xMax - xMin).coerceAtLeast(0f)
    val height: Float get() = (yMax - yMin).coerceAtLeast(0f)
    val area: Float get() = width * height

    /** Intersection over Union (IoU) with another bounding box. */
    fun iou(other: DetectionBox): Float {
        val interXMin = maxOf(xMin, other.xMin)
        val interYMin = maxOf(yMin, other.yMin)
        val interXMax = minOf(xMax, other.xMax)
        val interYMax = minOf(yMax, other.yMax)

        val interWidth = (interXMax - interXMin).coerceAtLeast(0f)
        val interHeight = (interYMax - interYMin).coerceAtLeast(0f)
        val interArea = interWidth * interHeight

        val unionArea = area + other.area - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}

/**
 * Resolved OpenCanvas track ready for hardware playback.
 */
@Serializable
data class OpenCanvasTrack(
    val videoId: String,
    val videoStreamUrl: String,
    val title: String,
    val artist: String,
    val mode: OpenCanvasMode = OpenCanvasMode.LOOP_CANVAS,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long = 10000L,
    val audioOffsetMs: Long = 0L,
    val trajectory: CropTrajectory? = null,
    val targetAspectRatio: Float = 9f / 16f,
    val source: String = "OpenCanvas",
)
