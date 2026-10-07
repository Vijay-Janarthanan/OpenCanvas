package com.opencanvas.core

import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.AudioStream
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.StreamInfo
import com.opencanvas.core.sync.Mp4TestFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole engine against a local server: a song and a "music video" whose audio is that song after a
 * 12 s intro, as two MP4 files with synthetic audio frame sizes. Nothing here touches YouTube or any
 * decoder, so it also shows that the measurement needs only the file headers.
 */
class CanvasSessionTest {

    private val server = TestServer()
    private val cache = Files.createTempDirectory("opencanvas-session").toFile()
    private val frameSeconds = 1024.0 / 44_100.0
    private val introFrames = 517 // 12.005 s
    private val song = music(seed = 1, frames = 5_200)
    private val padding = ByteArray(400_000)

    private val songStream get() = AudioStream("${server.baseUrl}/song.mp4")

    @Before
    fun serve() {
        val rnd = Random(5)
        val video = music(seed = 2, frames = introFrames) +
            IntArray(song.size) { (song[it] + rnd.nextInt(31) - 15).coerceAtLeast(60) } // another encode of the same music +
            music(seed = 3, frames = 300)
        server.file("/song.mp4", Mp4TestFiles.progressive(song) + padding)
        server.file("/video.mp4", Mp4TestFiles.progressive(video) + padding)
        server.file("/unrelated.mp4", Mp4TestFiles.progressive(music(seed = 99, frames = 6_000)) + padding)
    }

    @After
    fun stop() {
        server.close()
        cache.deleteRecursively()
    }

    /**
     * Loudness that changes every few seconds with a beat and frame-to-frame detail on top: enough
     * structure to line two files up. Each piece has its own tempo and its own detail, as two
     * different songs would.
     */
    private fun music(seed: Long, frames: Int): IntArray {
        val rnd = Random(seed)
        val beat = 17 + (seed * 7 % 11).toInt()
        val out = IntArray(frames)
        var at = 0
        while (at < frames) {
            val length = 100 + rnd.nextInt(300)
            val level = 250 + rnd.nextInt(700)
            repeat(length) {
                if (at < frames) {
                    out[at] = level + rnd.nextInt(300) - 150 + if (at % beat == 0) 150 else 0
                    at++
                }
            }
        }
        return out
    }

    private fun backend(video: String = "/video.mp4", audio: Boolean = true) = object : StreamBackend {
        override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution) =
            StreamInfo("${server.baseUrl}$video", 262_000L, 640, 360, mapOf("X-Test" to "1"))

        override suspend fun resolveAudio(videoId: String) =
            if (audio) AudioStream("${server.baseUrl}$video") else error("no audio expected")
    }

    private fun configure(backend: StreamBackend, instant: StreamBackend? = null) =
        OpenCanvas.configure(
            OpenCanvasConfig(
                streamBackend = backend,
                instantBackend = instant,
                cacheDirectory = cache,
                communityMapsUrl = null,
                httpClient = OkHttpClient(),
            ),
        )

    private fun resolve(title: String = "Song") = runBlocking {
        withTimeout(30_000L) {
            OpenCanvas.resolveFlow(
                title = title,
                artist = "Artist",
                mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
                songAudio = SongAudio.Stream(songStream),
                videoId = "AAAAAAAAAAA",
            ).toList()
        }
    }

    @Test
    fun alignsASongWithItsVideoOnTheDeviceFromTheFileHeadersOnly() {
        configure(backend())
        val tracks = resolve()

        val last = tracks.last()
        assertEquals(SyncStage.MEASURED, last.syncStage)
        assertTrue(abs(last.audioOffsetMs - 12_005L) <= 60L, "offset ${last.audioOffsetMs} ms")
        assertTrue(last.syncConfidence > 0.5, "confidence ${last.syncConfidence}")
        assertEquals(mapOf("X-Test" to "1"), last.videoHeaders)
        assertTrue(last.syncMap().covers(60_000L))
        // the stream is published before the map, so a player can start buffering at once
        assertEquals(SyncStage.PENDING, tracks.first().syncStage)
        // headers only: nowhere near the 400 kB of media data behind them was read
        val read = server.requests.filter { it.contains("mp4") }.size
        assertTrue(read <= 4, "range requests: ${server.requests}")
    }

    @Test
    fun aSongMeasuredBeforeStartsWithItsMapAndTouchesNoAudio() {
        configure(backend())
        resolve()
        val before = server.requests.size

        // a restart of the app: new engine, same cache directory, a backend that must not be asked for audio
        configure(backend(audio = false))
        val tracks = resolve()

        assertEquals(SyncStage.MEASURED, tracks.first().syncStage)
        assertTrue(abs(tracks.last().audioOffsetMs - 12_005L) <= 60L)
        assertEquals(before, server.requests.size, "a cached map needs no download: ${server.requests.drop(before)}")
    }

    @Test
    fun aVideoThatIsNotTheSongGetsNoMapSoThePlayerShowsTheArtwork() {
        configure(backend(video = "/unrelated.mp4"))
        val last = resolve().last()
        assertEquals(SyncStage.NONE, last.syncStage, "offset ${last.audioOffsetMs}, confidence ${last.syncConfidence}, map ${last.syncSegments}")
        assertEquals(0.0, last.syncConfidence)
    }

    /** A backend that takes its time over the video's audio, and counts how often it is asked. */
    private fun slowBackend(asked: AtomicInteger) = object : StreamBackend {
        override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution) =
            StreamInfo("${server.baseUrl}/video.mp4", 262_000L, 640, 360)

        override suspend fun resolveAudio(videoId: String): AudioStream {
            asked.incrementAndGet()
            delay(600L)
            return AudioStream("${server.baseUrl}/video.mp4")
        }
    }

    private fun flowOf(title: String) = OpenCanvas.resolveFlow(
        title = title,
        artist = "Artist",
        mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
        songAudio = SongAudio.Stream(songStream),
        videoId = "AAAAAAAAAAA",
    )

    @Test
    fun aRequestThatRestartsWithinMomentsFindsTheWorkItsPredecessorStarted() = runBlocking {
        val asked = AtomicInteger()
        configure(slowBackend(asked))

        // a UI that stops listening (a recomposition, late metadata) and asks the same question again
        withTimeoutOrNull(200L) { flowOf("Restarted").collect { } }
        val last = withTimeout(30_000L) { flowOf("Restarted").toList() }.last()

        assertEquals(SyncStage.MEASURED, last.syncStage)
        assertEquals(1, asked.get(), "the second request must share the first one's work")
        Unit
    }

    @Test
    fun aPrefetchedTrackIsAlreadyUnderWayWhenTheHostAsks() = runBlocking {
        val asked = AtomicInteger()
        configure(slowBackend(asked))

        OpenCanvas.prefetch(
            title = "Prefetched",
            artist = "Artist",
            mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
            songAudio = SongAudio.Stream(songStream),
            videoId = "AAAAAAAAAAA",
        )
        withTimeout(5_000L) { while (asked.get() == 0) delay(10L) } // work began with nobody listening
        val last = withTimeout(30_000L) { flowOf("Prefetched").toList() }.last()

        assertEquals(SyncStage.MEASURED, last.syncStage)
        assertEquals(1, asked.get())
        Unit
    }

    @Test
    fun aTallerStreamThatArrivesLaterReplacesTheInstantOne() {
        val instant = backend()
        val sharp = object : StreamBackend {
            override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution): StreamInfo {
                delay(400L)
                return StreamInfo("${server.baseUrl}/video.mp4?sharp", 262_000L, 1280, 720)
            }
        }
        configure(sharp, instant)
        val tracks = resolve()

        assertTrue(tracks.first().videoStreamUrl.endsWith("/video.mp4"), "the instant stream starts first")
        assertTrue(tracks.last().videoStreamUrl.endsWith("?sharp"), "the sharp one replaces it")
        assertEquals(SyncStage.MEASURED, tracks.last().syncStage)
    }
}
