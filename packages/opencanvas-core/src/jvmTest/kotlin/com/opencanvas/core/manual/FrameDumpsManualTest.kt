package com.opencanvas.core.manual

import com.opencanvas.core.sync.FrameEnvelope
import com.opencanvas.core.sync.Mp4FrameSizes
import com.opencanvas.core.sync.RangeReader
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual check of the MP4 header parser and the envelope builder against reference data made by the
 * Python implementation from real YouTube streams: for every pair in the dump directory the first
 * bytes of both files go through [Mp4FrameSizes] and [FrameEnvelope], and the frame sizes must match
 * exactly and the envelope to within float precision.
 *
 *     OPENCANVAS_MANUAL=1 OPENCANVAS_DUMPS=<dir> ./gradlew :packages:opencanvas-core:jvmTest --tests "*FrameDumpsManualTest*"
 */
class FrameDumpsManualTest {

    private class HeadReader(private val head: ByteArray, override val length: Long) : RangeReader {
        override suspend fun read(from: Long, count: Int): ByteArray =
            if (from >= head.size) ByteArray(0) else head.copyOfRange(from.toInt(), minOf(head.size.toLong(), from + count).toInt())
    }

    @Test
    fun parserAndEnvelopeMatchThePythonReference() {
        assumeTrue("set OPENCANVAS_MANUAL=1 to run", System.getenv("OPENCANVAS_MANUAL") == "1")
        val dumps = System.getenv("OPENCANVAS_DUMPS")
        assumeTrue("set OPENCANVAS_DUMPS to the reference dump directory", dumps != null)
        val directory = File(dumps!!)
        val prefixes = directory.listFiles { _, name -> name.endsWith(".expected.json") }.orEmpty().map { it.name.removeSuffix(".expected.json") }
        assertTrue(prefixes.isNotEmpty(), "no dumps in $directory")
        var worst = 0f
        for (prefix in prefixes) {
            for (side in listOf("track", "video")) {
                val head = File(directory, "$prefix.$side.head.bin").readBytes()
                val frames = assertNotNull(runBlocking { Mp4FrameSizes.read(HeadReader(head, -1L)) }, "$prefix $side: no frames")
                assertContentEquals(ints(File(directory, "$prefix.$side.sizes.i32")), frames.sizes, "$prefix $side sizes")
                val expected = floats(File(directory, "$prefix.$side.env.f32"))
                val envelope = FrameEnvelope.build(frames)
                assertTrue(abs(envelope.size - expected.size) <= 1, "$prefix $side: ${envelope.size} samples, expected ${expected.size}")
                val diff = (0 until minOf(envelope.size, expected.size)).maxOf { abs(envelope[it] - expected[it]) }
                worst = maxOf(worst, diff)
                assertTrue(diff < 1e-3f, "$prefix $side envelope differs by $diff")
            }
        }
        println("${prefixes.size} pairs: sizes identical, worst envelope difference $worst")
    }

    private fun ints(file: File): IntArray = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { IntArray(it.remaining()).also(it::get) }

    private fun floats(file: File): FloatArray = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { FloatArray(it.remaining()).also(it::get) }
}
