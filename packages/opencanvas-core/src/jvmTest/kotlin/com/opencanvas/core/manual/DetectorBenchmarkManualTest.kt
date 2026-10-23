package com.opencanvas.core.manual

import com.opencanvas.core.onnx.*
import org.junit.Assume.assumeTrue
import kotlin.test.*
import java.io.File
import javax.imageio.ImageIO

class DetectorBenchmarkManualTest {
    @Test fun compareDetectorsOnARealReferenceImage() {
        val path = System.getenv("OPENCANVAS_REFERENCE_IMAGE")
        assumeTrue(path != null)
        val image = ImageIO.read(File(path!!))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        OnnxSubjectTracker().use { previous ->
            val start = System.nanoTime()
            val faces = previous.detectFaces(pixels, image.width, image.height)
            println("ULTRAFACE: faces=${faces.size}, ms=${(System.nanoTime()-start)/1e6}")
        }
        OnnxPeopleDetector().use { detector ->
            val timings = mutableListOf<Double>()
            repeat(6) { i ->
                val start = System.nanoTime()
                val subjects = detector.detect(pixels, image.width, image.height)
                timings += (System.nanoTime()-start)/1e6
                if (i == 0) println("YUNET+YOLOX: faces=${subjects.faces.size}, people=${subjects.people.size}")
                assertTrue(subjects.faces.size >= 3, "Positive inference must detect the reference group")
            }
            println("YUNET+YOLOX: warm median ms=${timings.drop(1).sorted()[2]}")
        }
    }
}
