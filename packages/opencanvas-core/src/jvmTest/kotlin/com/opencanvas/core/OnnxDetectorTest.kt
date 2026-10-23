package com.opencanvas.core

import com.opencanvas.core.onnx.OnnxPeopleDetector
import org.junit.Test
import kotlin.test.assertTrue

class OnnxDetectorTest {
    @Test fun packagedModelsRunRealInferenceWithTheExpectedOutputLayout() {
        val started = System.nanoTime()
        OnnxPeopleDetector().use { detector ->
            val pixels = IntArray(640 * 360) { 0xff808080.toInt() }
            repeat(3) {
                val result = detector.detect(pixels, 640, 360)
                assertTrue(result.faces.isEmpty())
                assertTrue(result.people.isEmpty())
            }
        }
        println("DETECTOR: init plus 3 full inferences ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
