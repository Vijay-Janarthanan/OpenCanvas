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
    val resolution: OpenCanvasResolution = OpenCanvasResolution.STANDARD_480P,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long = 10000L,
    val audioOffsetMs: Long = 0L,
    val trajectory: CropTrajectory? = null,
    val targetAspectRatio: Float = 9f / 16f,
    val source: String = "OpenCanvas",
)

/**
 * Video stream resolution and dimensions configuration for OpenCanvas.
 * Allows developers to customize target height and width or choose standard presets.
 */
@Serializable
data class OpenCanvasResolution(
    /** Maximum vertical height in pixels (e.g., 360, 480, 720, 1080). */
    val maxHeight: Int = 480,
    /** Optional maximum horizontal width in pixels (e.g., 640, 854, 1280, 1920). */
    val maxWidth: Int? = null,
    /** Friendly quality label (e.g., "360p", "480p", "720p", "1080p", "custom"). */
    val label: String = "${maxHeight}p",
) {
    companion object {
        val LOW_360P = OpenCanvasResolution(maxHeight = 360, maxWidth = 640, label = "360p")
        val STANDARD_480P = OpenCanvasResolution(maxHeight = 480, maxWidth = 854, label = "480p")
        val HD_720P = OpenCanvasResolution(maxHeight = 720, maxWidth = 1280, label = "720p")
        val FULL_HD_1080P = OpenCanvasResolution(maxHeight = 1080, maxWidth = 1920, label = "1080p")
        val AUTO = STANDARD_480P

        /** Creates a custom resolution with explicit height and optional width. */
        fun fromDimensions(height: Int, width: Int? = null): OpenCanvasResolution =
            OpenCanvasResolution(maxHeight = height, maxWidth = width, label = if (width != null) "${width}x${height}" else "${height}p")

        /** Parses a resolution string like "360", "480p", "720", "1080p". */
        fun fromLabel(raw: String): OpenCanvasResolution = when (raw.lowercase().replace("p", "").trim()) {
            "360" -> LOW_360P
            "480" -> STANDARD_480P
            "720" -> HD_720P
            "1080" -> FULL_HD_1080P
            else -> STANDARD_480P
        }
    }

    /** Formats the yt-dlp video format selector for this resolution. */
    fun toYtDlpFormat(): String {
        val h = maxHeight.coerceAtLeast(240)
        val wClause = if (maxWidth != null && maxWidth > 0) "[width<=$maxWidth]" else ""
        return "bestvideo[height<=$h]$wClause[ext=mp4]/bestvideo[height<=$h]$wClause/bestvideo[ext=mp4]/best[ext=mp4]/best"
    }
}

