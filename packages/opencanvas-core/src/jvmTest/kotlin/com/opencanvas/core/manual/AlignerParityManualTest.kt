package com.opencanvas.core.manual

import com.opencanvas.core.sync.AlignResult
import com.opencanvas.core.sync.SyncAligner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Manual check that the on-device [SyncAligner] reproduces the Python reference
 * (`tools/opencanvas-stream-server/server.py`, `estimate_map`) on real song/video pairs.
 *
 * A pair is three files in the dump directory (`OPENCANVAS_DUMPS`): `<track>_<video>.track.env.f32` and
 * `<track>_<video>.video.env.f32` (raw little-endian float32, the 50 Hz frame-size envelopes) and
 * `<track>_<video>.expected.json` (the Python answer on exactly those arrays). Each pair is aligned
 * with [com.opencanvas.core.sync.AlignParams.ENVELOPE] and compared with Python: the same number of
 * segments, every segment offset within 5 ms, every boundary within 5 s and the confidence within 0.03.
 * The table, with the time `align` took, goes to `build/reports/aligner-parity.txt`.
 *
 *     OPENCANVAS_MANUAL=1 OPENCANVAS_DUMPS=<directory> ./gradlew :packages:opencanvas-core:jvmTest --tests "*AlignerParity*"
 */
class AlignerParityManualTest {

    private class Segment(val startMs: Double, val endMs: Double, val offsetMs: Double, val ncc: Double)

    private class Expected(
        val rate: Int,
        val offsetMs: Double,
        val confidence: Double,
        val coverage: Double,
        val segments: List<Segment>,
        val waveformSegments: List<Segment>,
    )

    private class DumpPair(val name: String, val track: FloatArray, val video: FloatArray, val expected: Expected)

    @Test
    fun kotlinAlignerMatchesPython() {
        assumeTrue("set OPENCANVAS_MANUAL=1 to run", System.getenv("OPENCANVAS_MANUAL") == "1")
        val dumps = System.getenv("OPENCANVAS_DUMPS")
            ?: throw AssumptionViolatedException("set OPENCANVAS_DUMPS to the directory with the envelope dumps")
        val directory = File(dumps)
        val pairs = loadPairs(directory)
        assumeTrue("no dump pairs in $directory", pairs.isNotEmpty())

        val report = StringBuilder()
        val failures = ArrayList<String>()
        report.appendLine("Aligner parity: Kotlin SyncAligner.align (AlignParams.ENVELOPE) vs Python estimate_map")
        report.appendLine("${pairs.size} pairs in $directory; JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        report.appendLine(
            "Acceptance: same segment count, offsets within ${MAX_OFFSET_MS.toInt()} ms, " +
                "boundaries within ${MAX_BOUNDARY_S.toInt()} s, confidence within $MAX_CONFIDENCE",
        )
        report.appendLine()
        report.appendLine(
            "%-26s %6s %6s %7s | %-8s %-8s | %-10s %-9s | %8s | %s".format(
                "pair", "song s", "vid s", "segs", "conf kt", "conf py", "max d_off", "max d_bnd", "align ms", "verdict",
            ),
        )

        val details = StringBuilder()
        var slowest = 0.0
        for (pair in pairs) {
            val (result, millis) = timedAlign(pair)
            slowest = max(slowest, millis)
            val problems = compare(pair.expected, result)
            failures += problems.map { "${pair.name}: $it" }

            val expected = pair.expected
            val count = minOf(result.segments.size, expected.segments.size)
            val worstOffset = (0 until count).maxOfOrNull { abs(result.segments[it].offsetMs - expected.segments[it].offsetMs) } ?: 0.0
            val worstBoundary = (0 until count).maxOfOrNull { boundaryDiff(result, expected, it) } ?: 0.0
            report.appendLine(
                "%-26s %6.1f %6.1f %3d/%-3d | %-8.4f %-8.4f | %7.1f ms %6.2f s  | %8.2f | %s".format(
                    pair.name.take(26), pair.track.size / expected.rate.toDouble(), pair.video.size / expected.rate.toDouble(),
                    result.segments.size, expected.segments.size, result.confidence, expected.confidence,
                    worstOffset, worstBoundary, millis, if (problems.isEmpty()) "ok" else "FAIL",
                ),
            )
            details.append(describeSegments(pair, result, problems))
        }
        val failedPairs = failures.map { it.substringBefore(':') }.distinct().size
        report.appendLine()
        report.append(details)
        report.appendLine()
        report.appendLine("${pairs.size - failedPairs} of ${pairs.size} pairs within tolerance; slowest align ${"%.1f".format(slowest)} ms")

        File("build/reports").apply { mkdirs() }.resolve("aligner-parity.txt").writeText(report.toString())
        println(report)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // --- running and comparing -------------------------------------------------------------------

    /** The result of the last of [RUNS] alignments and the fastest of them, in milliseconds (one more first warms the JIT up). */
    private fun timedAlign(pair: DumpPair): Pair<AlignResult, Double> {
        var result = SyncAligner.align(pair.track, pair.video, pair.expected.rate)
        var best = Double.MAX_VALUE
        repeat(RUNS) {
            val started = System.nanoTime()
            result = SyncAligner.align(pair.track, pair.video, pair.expected.rate)
            best = minOf(best, (System.nanoTime() - started) / 1e6)
        }
        return result to best
    }

    private fun boundaryDiff(result: AlignResult, expected: Expected, index: Int): Double =
        max(
            abs(result.segments[index].songStartMs - expected.segments[index].startMs),
            abs(result.segments[index].songEndMs - expected.segments[index].endMs),
        ) / 1000.0

    /** What differs from Python beyond the tolerances; empty when the pair passes. */
    private fun compare(expected: Expected, result: AlignResult): List<String> {
        if (result.segments.size != expected.segments.size) {
            return listOf("${result.segments.size} segments, python ${expected.segments.size}")
        }
        val problems = ArrayList<String>()
        for (i in expected.segments.indices) {
            val offsetDiff = abs(result.segments[i].offsetMs - expected.segments[i].offsetMs)
            if (offsetDiff > MAX_OFFSET_MS) problems += "segment $i offset differs by $offsetDiff ms"
            val boundary = boundaryDiff(result, expected, i)
            if (boundary > MAX_BOUNDARY_S) problems += "segment $i boundary differs by $boundary s"
        }
        val confidenceDiff = abs(result.confidence - expected.confidence)
        if (confidenceDiff > MAX_CONFIDENCE) problems += "confidence differs by $confidenceDiff"
        return problems
    }

    private fun describeSegments(pair: DumpPair, result: AlignResult, problems: List<String>): String {
        val expected = pair.expected
        val out = StringBuilder()
        out.appendLine(
            "${pair.name}: python offset ${expected.offsetMs.toLong()} ms (kotlin ${result.offsetMs}), coverage " +
                "${"%.4f".format(expected.coverage)} (kotlin ${"%.4f".format(result.coverage)})" +
                (result.error?.let { ", kotlin error: $it" } ?: ""),
        )
        for (i in 0 until maxOf(result.segments.size, expected.segments.size)) {
            val p = expected.segments.getOrNull(i)
            val k = result.segments.getOrNull(i)
            out.appendLine(
                "    seg %d  python %s  kotlin %s".format(
                    i,
                    p?.let { "%6.1f..%6.1f s offset %7d ncc %.3f".format(it.startMs / 1000, it.endMs / 1000, it.offsetMs.toLong(), it.ncc) }
                        ?: "-".padEnd(43),
                    k?.let { "%6.1f..%6.1f s offset %7d ncc %.3f".format(it.songStartMs / 1000.0, it.songEndMs / 1000.0, it.offsetMs, it.ncc) }
                        ?: "-",
                ),
            )
        }
        out.appendLine(
            "    waveform map (informational): " + expected.waveformSegments.joinToString("; ") {
                "%.1f..%.1f s offset %d".format(it.startMs / 1000, it.endMs / 1000, it.offsetMs.toLong())
            },
        )
        problems.forEach { out.appendLine("    MISMATCH: $it") }
        return out.toString()
    }

    // --- loading ---------------------------------------------------------------------------------

    private fun loadPairs(directory: File): List<DumpPair> =
        directory.listFiles { f -> f.name.endsWith(".expected.json") }.orEmpty().sortedBy { it.name }.mapNotNull { json ->
            val name = json.name.removeSuffix(".expected.json")
            val trackFile = File(directory, "$name.track.env.f32")
            val videoFile = File(directory, "$name.video.env.f32")
            if (!trackFile.isFile || !videoFile.isFile) return@mapNotNull null
            DumpPair(name, readFloats(trackFile), readFloats(videoFile), parseExpected(json.readText()))
        }

    private fun readFloats(file: File): FloatArray {
        val floats = FloatArray((file.length() / 4).toInt())
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    private fun parseExpected(text: String): Expected {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
        fun segments(key: String) = root.getValue(key).jsonArray.map {
            val s = it.jsonObject
            Segment(s.number("songStartMs"), s.number("songEndMs"), s.number("offsetMs"), s.number("ncc"))
        }
        return Expected(
            rate = root.getValue("envRateHz").jsonPrimitive.int,
            offsetMs = root.number("offsetMs"),
            confidence = root.number("confidence"),
            coverage = root.number("coverage"),
            segments = segments("segments"),
            waveformSegments = segments("waveformSegments"),
        )
    }

    private companion object {
        const val RUNS = 5
        const val MAX_OFFSET_MS = 5.0
        const val MAX_BOUNDARY_S = 5.0
        const val MAX_CONFIDENCE = 0.03
    }
}
