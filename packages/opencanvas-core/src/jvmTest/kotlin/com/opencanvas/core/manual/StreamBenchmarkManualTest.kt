package com.opencanvas.core.manual

import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.stream.InnerTubeBackend
import com.opencanvas.core.stream.ProgressiveRangeSource
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.YtDlpBackend
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Manual benchmark of how fast a canvas can start and seek, measured on a real stream.
 *
 * Compares the three ways a player can fetch the clip:
 *  - one plain GET of the whole file (what a naive cache does - throttled by the host),
 *  - one open-ended ranged GET (what a media library does by default),
 *  - [ProgressiveRangeSource]: a small first range that grows, with priority fetches after a seek.
 *
 * Needs the network, and `yt-dlp` on the PATH for the 480p/720p cases; run it with
 *
 *     OPENCANVAS_MANUAL=1 ./gradlew :packages:opencanvas-core:jvmTest --tests "*StreamBenchmarkManualTest*"
 *
 * and read `build/reports/opencanvas-stream-benchmark.md`.
 */
class StreamBenchmarkManualTest {

    private val base = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The client for the stream under test: every request carries the headers its URL was minted for. */
    private lateinit var client: OkHttpClient

    @Test
    fun measureStartAndSeek() {
        assumeTrue("set OPENCANVAS_MANUAL=1 to run", System.getenv("OPENCANVAS_MANUAL") == "1")
        val report = StringBuilder("# OpenCanvas stream benchmark\n\n")
        for ((label, backend, resolution, videoId) in CASES) {
            val info = runBlocking { backend.resolveVideo(videoId, resolution) }
            if (info == null) {
                report.append("## $label\n\nstream could not be resolved\n\n")
                continue
            }
            client = base.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().apply { info.headers.forEach { (name, value) -> header(name, value) } }.build())
            }.build()
            val total = headLength(info.url)
            report.append("## $label ($videoId, ${info.width}x${info.height}, ${total / 1024} KiB, ${info.durationMs / 1000}s)\n\n")
            val bitrate = if (info.durationMs > 0) total * 1000.0 / info.durationMs else 0.0
            report.append("Average bitrate: ${"%.0f".format(bitrate / 1000)} kB/s (a clip only needs this to play in real time).\n\n")

            // The progressive source and the seeks are measured first, each on the stream as it was minted. A
            // plain GET of the whole file comes last: the host throttles it, and has answered later requests on
            // a URL that was just read that way with 403.
            val started = System.nanoTime()
            var oursRow = ""
            var sizesLine = ""
            ProgressiveRangeSource(info.url, client).use { source ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var firstByteMs = -1L
                var oneSecondMs = -1L
                while (read < total) {
                    val n = source.read(buf, 0, buf.size)
                    if (n < 0) break
                    read += n
                    val ms = (System.nanoTime() - started) / 1_000_000
                    if (firstByteMs < 0) firstByteMs = ms
                    if (oneSecondMs < 0 && read >= bitrate) oneSecondMs = ms
                }
                val doneMs = (System.nanoTime() - started) / 1_000_000
                val sizes = source.requestLog.take(8).joinToString(", ") { "${it.second / 1024} KiB" }
                oursRow = "| Progressive ranges (ours) | $firstByteMs ms | $oneSecondMs ms | ${"%.1f".format(doneMs / 1000.0)} s |\n\n"
                sizesLine = "Request sizes: $sizes ... (doubling to ${ProgressiveRangeSource.DEFAULT_MAX_CHUNK / 1024} KiB).\n\n"
            }

            // seek latency: time from seek() to the first byte at the new position, cold buffer
            val rnd = Random(42)
            val seeks = (1..8).map { rnd.nextLong(total / 20, total - 400_000) }
            val latencies = ArrayList<Long>()
            ProgressiveRangeSource(info.url, client, aheadLimitBytes = 256 * 1024).use { source ->
                source.length()
                val buf = ByteArray(32 * 1024)
                for (position in seeks) {
                    val t0 = System.nanoTime()
                    source.seek(position)
                    source.read(buf, 0, buf.size)
                    latencies += (System.nanoTime() - t0) / 1_000_000
                }
            }
            latencies.sort()

            // one plain GET, measured for a few seconds, extrapolated to the full file
            val plain = plainGetRate(info.url, windowMs = 8_000)
            report.append("| Method | First byte | First 1 s of video (~${"%.0f".format(bitrate / 1000)} kB) | Whole file |\n|---|---|---|---|\n")
            report.append(
                "| Plain GET, whole file | ${plain.firstByteMs} ms | " +
                    "${"%.1f".format(bitrate / plain.bytesPerSec)} s | ${"%.0f".format(total / plain.bytesPerSec)} s (extrapolated from ${plain.bytes / 1024} KiB in ${plain.windowMs / 1000} s) |\n",
            )
            report.append(oursRow)
            report.append(sizesLine)
            report.append(
                "Seek to a random position (8 seeks, nothing buffered there): median ${latencies[latencies.size / 2]} ms, " +
                    "worst ${latencies.last()} ms, until the first bytes of the new position are readable.\n\n",
            )
        }
        val out = File("build/reports").apply { mkdirs() }
        File(out, "opencanvas-stream-benchmark.md").writeText(report.toString())
        println(report)
    }

    private class PlainRate(val firstByteMs: Long, val bytes: Long, val windowMs: Long) {
        val bytesPerSec get() = bytes * 1000.0 / windowMs.coerceAtLeast(1)
    }

    private fun plainGetRate(url: String, windowMs: Long): PlainRate {
        val started = System.nanoTime()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val stream = response.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            var bytes = 0L
            var firstByteMs = -1L
            while ((System.nanoTime() - started) / 1_000_000 < windowMs) {
                val n = stream.read(buf)
                if (n < 0) break
                bytes += n
                if (firstByteMs < 0) firstByteMs = (System.nanoTime() - started) / 1_000_000
            }
            return PlainRate(firstByteMs, bytes, (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun headLength(url: String): Long {
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            return response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
        }
    }

    private companion object {
        data class Case(val label: String, val backend: StreamBackend, val resolution: OpenCanvasResolution, val videoId: String)

        val CASES = listOf(
            Case("360p, built-in client", InnerTubeBackend(), OpenCanvasResolution.fromLabel("360p"), "4NRXx6U8ABQ"),
            Case("720p, yt-dlp", YtDlpBackend(), OpenCanvasResolution.fromLabel("720p"), "4NRXx6U8ABQ"),
            Case("480p, yt-dlp", YtDlpBackend(), OpenCanvasResolution.fromLabel("480p"), "4NRXx6U8ABQ"),
        )
    }
}
