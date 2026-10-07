package com.music.bitchord.data.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenCanvas Provider for BitChord.
 *
 * Plugs directly into BitChord's [CanvasRepository] (Android) and [DesktopCanvasClient] (Desktop).
 * When Spotify, Apple Music, Tidal, and Community index all return no canvas,
 * this provider dynamically matches the song's Official Music Video, extracts the
 * engagement heatmap loop, and provides a vertical 9:16 Canvas.
 *
 * Attribution: Powered by OpenCanvas — Created by Vijay (Apache-2.0 License).
 */
object OpenCanvasProvider {

    private const val TAG = "OpenCanvasProvider"

    /**
     * Resolves an OpenCanvas looping video for [title] and [artist].
     * Compatible with BitChord's existing [CanvasArtwork] model.
     */
    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        isFullVideoMode: Boolean = false,
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        val mode = if (isFullVideoMode) OpenCanvasMode.FULL_SYNCED_VIDEO else OpenCanvasMode.LOOP_CANVAS

        val track: OpenCanvasTrack = OpenCanvas.resolve(
            title = title,
            artist = artist,
            durationSec = 0L,
            mode = mode,
        ) ?: return@withContext null

        CanvasArtwork(
            url = track.videoStreamUrl,
            title = track.title,
            artist = track.artist,
            album = album,
            source = CanvasSource.OTHER,
        )
    }
}
