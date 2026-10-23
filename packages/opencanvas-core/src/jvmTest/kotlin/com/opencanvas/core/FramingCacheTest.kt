package com.opencanvas.core

import com.opencanvas.core.onnx.*
import com.opencanvas.core.models.DetectionBox
import com.opencanvas.core.reframer.SmartFrameReframer
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class FramingCacheTest {
    @Test fun repeatedVideoReusesTheModelVersionedTrajectoryAndClosesItsDetector() {
        val directory = Files.createTempDirectory("framing-cache").toFile()
        var calls = 0
        var closes = 0
        fun detector() = object : SubjectDetector {
            override fun detect(pixels: IntArray, width: Int, height: Int): Subjects {
                calls++
                return Subjects(listOf(DetectionBox(.7f, .2f, .8f, .4f, .95f)), emptyList())
            }
            override fun close() { closes++ }
        }
        try {
            SmartFrameReframer(detector(), videoId = "AAAAAAAAAAA", cacheDirectory = directory).use {
                assertTrue(it.analyze(IntArray(64 * 36), 64, 36, 1.0).cx > .7f)
                assertEquals(1, it.trajectory().keyframes.size)
            }
            SmartFrameReframer(detector(), videoId = "AAAAAAAAAAA", cacheDirectory = directory).use {
                assertTrue(it.analyze(IntArray(64 * 36), 64, 36, 1.0).cx > .7f)
            }
            assertEquals(1, calls)
            assertEquals(2, closes)
        } finally { directory.deleteRecursively() }
    }
}
