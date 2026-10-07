package com.opencanvas.core

import com.opencanvas.core.cdn.CommunityCdnClient
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.stream.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenCanvas Engine Entry Point.
 *
 * Provides a simple, one-line asynchronous API to resolve high-quality,
 * vertically reframed Canvas loops or full vertical music videos for any song.
 *
 * Attribution: OpenCanvas by Vijay (Apache 2.0).
 */
object OpenCanvas {

    const val VERSION = "1.0.0"
    const val AUTHOR = "Vijay"

    private val memoryCache = ConcurrentHashMap<String, OpenCanvasTrack>()

    /**
     * Resolves an [OpenCanvasTrack] for any track with zero backend servers required.
     *
     * @param title Track title (e.g., "Starboy")
     * @param artist Artist name (e.g., "The Weeknd")
     * @param durationSec Track duration in seconds (optional, helps candidate verification)
     * @param mode [OpenCanvasMode.LOOP_CANVAS] (8-12s chorus loop) or [OpenCanvasMode.FULL_SYNCED_VIDEO]
     * @param resolution Target [OpenCanvasResolution] (e.g. 360p, 480p, 720p, 1080p, or custom height/width)
     * @return Resolved [OpenCanvasTrack], or null if no official video was matched.
     */
    suspend fun resolve(
        title: String,
        artist: String,
        durationSec: Long = 0L,
        mode: OpenCanvasMode = OpenCanvasMode.LOOP_CANVAS,
        resolution: com.opencanvas.core.models.OpenCanvasResolution = com.opencanvas.core.models.OpenCanvasResolution.STANDARD_480P,
    ): OpenCanvasTrack? = withContext(Dispatchers.IO) {
        val cacheKey = "$artist|$title|$mode|${resolution.label}".lowercase()
        memoryCache[cacheKey]?.let { return@withContext it }

        // 1. Find the official music video
        val candidate = YouTubeStreamResolver.findOfficialVideo(title, artist, durationSec)
            ?: return@withContext null

        val videoId = candidate.videoId

        // 2. Resolve loop window or full duration
        val (startMs, endMs) = if (mode == OpenCanvasMode.LOOP_CANVAS) {
            val estimatedDurationMs = if (candidate.durationSec > 0) candidate.durationSec * 1000L else 200000L
            YouTubeStreamResolver.resolveLoopWindow(videoId, estimatedDurationMs)
        } else {
            0L to (if (candidate.durationSec > 0) candidate.durationSec * 1000L else 240000L)
        }

        // 3. Resolve direct playable MP4 stream URL via yt-dlp at requested resolution
        val playableStreamUrl = YouTubeStreamResolver.resolveVideoStreamUrl(videoId, resolution)
            ?: return@withContext null

        // 4. Construct resolved OpenCanvas track
        val track = OpenCanvasTrack(
            videoId = videoId,
            videoStreamUrl = playableStreamUrl,
            title = candidate.title.ifBlank { title },
            artist = artist,
            mode = mode,
            resolution = resolution,
            loopStartMs = startMs,
            loopEndMs = endMs,
            audioOffsetMs = 0L,
            trajectory = null,
            targetAspectRatio = 9f / 16f,
            source = "OpenCanvas by $AUTHOR",
        )

        memoryCache[cacheKey] = track
        track
    }

    /**
     * Clears cached trajectories and metadata.
     */
    fun clearCache() {
        memoryCache.clear()
    }
}
