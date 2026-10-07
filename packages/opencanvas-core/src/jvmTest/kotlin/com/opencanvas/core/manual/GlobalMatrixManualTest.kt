package com.opencanvas.core.manual

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.InnerTubeBackend
import com.opencanvas.core.sync.CanvasSyncDefaults
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Manual end-to-end check that the engine works for songs of every kind, not just one: it runs the
 * real pipeline (find the official video, resolve its stream, measure the song-to-video map from the
 * two file headers) for a spread of languages, genres and eras, and writes a table. It starts from
 * an empty cache and without the community maps, so every number is a cold start.
 *
 * Needs the network, and `yt-dlp` on the PATH only to look up each song's YouTube Music id (the id a
 * player would already have), so it only runs when asked:
 *
 *     OPENCANVAS_MANUAL=1 ./gradlew :packages:opencanvas-core:jvmTest --tests "*GlobalMatrixManualTest*"
 *
 * The report lands in `build/reports/opencanvas-global-matrix.md` (and `.json`) under the module.
 */
class GlobalMatrixManualTest {

    private data class Song(val title: String, val artist: String, val language: String)

    private data class Row(
        val song: Song,
        val trackId: String?,
        val videoId: String?,
        val videoTitle: String?,
        val videoSec: Long,
        val offsetMs: Long,
        val confidence: Double,
        val millis: Long,
        val note: String,
        val firstMs: Long = 0,
        val height: Int = 0,
    ) {
        val aligned get() = confidence >= CanvasSyncDefaults.MIN_SYNC_CONFIDENCE
    }

    @Test
    fun resolveAcrossLanguagesAndGenres() {
        assumeTrue("set OPENCANVAS_MANUAL=1 to run", System.getenv("OPENCANVAS_MANUAL") == "1")
        // every request slower than a second is logged, to see what a slow song waited for
        val timed = InnerTubeBackend.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val started = System.nanoTime()
            try {
                chain.proceed(chain.request())
            } finally {
                val ms = (System.nanoTime() - started) / 1_000_000
                if (ms > 1_000) println("  slow request ${ms} ms ${chain.request().method} ${chain.request().url.host}${chain.request().url.encodedPath.take(40)} ${chain.request().header("Range").orEmpty()}")
            }
        }.build()
        OpenCanvas.configure(
            OpenCanvasConfig(
                streamBackend = InnerTubeBackend.visionOs(timed),
                instantBackend = InnerTubeBackend.android(timed),
                cacheDirectory = Files.createTempDirectory("opencanvas-matrix").toFile(),
                communityMapsUrl = null,
                httpClient = timed,
                log = { println(it) },
            ),
        )
        val rows = SONGS.map { song -> runBlocking { measure(song) } }

        val out = File("build/reports").apply { mkdirs() }
        File(out, "opencanvas-global-matrix.md").writeText(markdown(rows))
        File(out, "opencanvas-global-matrix.json").writeText(json(rows))
        println(markdown(rows))
    }

    private suspend fun measure(song: Song): Row {
        val topic = topicTrack(song)
            ?: return Row(song, null, null, null, 0, 0, 0.0, 0, "no song audio found on YouTube Music")
        val trackId = topic.first
        val started = System.nanoTime()
        var firstMs = 0L
        var track: OpenCanvasTrack? = null
        runCatching {
            OpenCanvas.resolveFlow(
                title = song.title,
                artist = song.artist,
                durationSec = topic.second,
                mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
                resolution = OpenCanvasResolution.HD_720P,
                trackVideoId = trackId,
            ).collect {
                if (track == null) firstMs = (System.nanoTime() - started) / 1_000_000
                track = it
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val result = track ?: return Row(song, trackId, null, null, 0, 0, 0.0, millis, "no official video matched")
        val note = when (result.syncStage) {
            SyncStage.MEASURED -> "aligned"
            else -> "video not alignable with the song (live/cover/different mix?) - still cover shown"
        }
        return Row(
            song, trackId, result.videoId, result.title, result.videoDurationMs / 1000,
            result.audioOffsetMs, result.syncConfidence, millis, note, firstMs,
            height = OpenCanvasResolution.HD_720P.maxHeight,
        )
    }

    /** The YouTube Music (auto-generated "Topic") audio of the song - what a player actually plays. */
    private fun topicTrack(song: Song): Pair<String, Long>? {
        val query = "ytsearch8:${song.artist} ${song.title} topic"
        val process = ProcessBuilder(
            "yt-dlp", query, "--flat-playlist", "--print", "%(id)s|%(channel)s|%(duration)s",
        ).redirectErrorStream(false).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(60, TimeUnit.SECONDS)
        val parsed = lines.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) null else Triple(parts[0], parts[1], parts[2].toDoubleOrNull() ?: 0.0)
        }
        return (parsed.firstOrNull { it.second.endsWith("- Topic") } ?: parsed.firstOrNull())?.let { it.first to it.third.toLong() }
    }

    private fun markdown(rows: List<Row>): String = buildString {
        val aligned = rows.count { it.aligned }
        appendLine("# OpenCanvas global check")
        appendLine()
        appendLine("${rows.size} songs, $aligned aligned (confidence >= ${CanvasSyncDefaults.MIN_SYNC_CONFIDENCE}).")
        appendLine()
        appendLine("| Song | Language | Video | Video length | Offset (s) | Confidence | Video ready | Map ready | Result |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        rows.forEach { r ->
            appendLine(
                "| ${r.song.artist} - ${r.song.title} | ${r.song.language} | ${r.videoId ?: "-"} | " +
                    "${if (r.videoSec > 0) "${r.videoSec}s" else "-"} | " +
                    "${if (r.aligned) "%+.2f".format(r.offsetMs / 1000.0) else "-"} | " +
                    "${"%.2f".format(r.confidence)} | ${"%.1f".format(r.firstMs / 1000.0)}s | ${"%.1f".format(r.millis / 1000.0)}s | ${r.note} |",
            )
        }
    }

    private fun json(rows: List<Row>): String = rows.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { r ->
        """  {"artist":"${esc(r.song.artist)}","title":"${esc(r.song.title)}","language":"${r.song.language}",""" +
            """"trackId":${q(r.trackId)},"videoId":${q(r.videoId)},"videoSec":${r.videoSec},""" +
            """"offsetMs":${r.offsetMs},"confidence":${"%.3f".format(r.confidence)},"videoReadyMs":${r.firstMs},"mapReadyMs":${r.millis},""" +
            """"aligned":${r.aligned},"note":"${esc(r.note)}"}"""
    }

    private fun q(s: String?) = if (s == null) "null" else "\"${esc(s)}\""
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val SONGS = listOf(
            Song("Blinding Lights", "The Weeknd", "English"),
            Song("STAY", "The Kid LAROI", "English"),
            Song("As It Was", "Harry Styles", "English"),
            Song("Shape of You", "Ed Sheeran", "English"),
            Song("Levitating", "Dua Lipa", "English"),
            Song("Believer", "Imagine Dragons", "English"),
            Song("Calm Down", "Rema", "English / Afrobeats"),
            Song("Yellow", "Coldplay", "English (2000)"),
            Song("Tum Hi Ho", "Arijit Singh", "Hindi"),
            Song("Kala Chashma", "Badshah", "Hindi / Punjabi"),
            Song("Why This Kolaveri Di", "Dhanush", "Tamil"),
            Song("Vaathi Coming", "Anirudh Ravichander", "Tamil"),
            Song("Butta Bomma", "Armaan Malik", "Telugu"),
            Song("Dynamite", "BTS", "Korean"),
            Song("Despacito", "Luis Fonsi", "Spanish"),
            Song("Gangnam Style", "PSY", "Korean (2012)"),
        )
    }
}
