package com.opencanvas.core.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.opencanvas.core.models.DetectionBox
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-fast on-device face/subject detector using UltraFace-slim (1.1 MB ONNX).
 * Processes a 320x240 keyframe in under ~10-15 ms on CPU.
 */
class OnnxSubjectTracker(modelStream: InputStream? = null) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val stream = modelStream
            ?: OnnxSubjectTracker::class.java.getResourceAsStream("/ultraface_slim_320.onnx")
            ?: error("Could not find /ultraface_slim_320.onnx in resources")

        val bytes = stream.use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }
        session = env.createSession(bytes, opts)
    }

    /**
     * Detects faces in an ARGB or RGB pixel buffer.
     * Input dimensions [width] and [height] are automatically scaled to 320x240 for inference.
     */
    fun detectFaces(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        confThreshold: Float = 0.65f,
        nmsIouThreshold: Float = 0.40f,
    ): List<DetectionBox> {
        if (width <= 0 || height <= 0 || argbPixels.isEmpty()) return emptyList()

        // Prepare 1x3x240x320 float buffer (RGB NCHW format)
        val targetW = 320
        val targetH = 240
        val buffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)

        // Downsample/bilinear sample directly into planar float buffer
        val xRatio = width.toFloat() / targetW
        val yRatio = height.toFloat() / targetH

        // Red plane
        for (y in 0 until targetH) {
            val srcY = (y * yRatio).toInt().coerceIn(0, height - 1)
            val rowOffset = srcY * width
            for (x in 0 until targetW) {
                val srcX = (x * xRatio).toInt().coerceIn(0, width - 1)
                val pixel = argbPixels[rowOffset + srcX]
                val r = (pixel shr 16) and 0xFF
                buffer.put((r - 127.0f) / 128.0f)
            }
        }
        // Green plane
        for (y in 0 until targetH) {
            val srcY = (y * yRatio).toInt().coerceIn(0, height - 1)
            val rowOffset = srcY * width
            for (x in 0 until targetW) {
                val srcX = (x * xRatio).toInt().coerceIn(0, width - 1)
                val pixel = argbPixels[rowOffset + srcX]
                val g = (pixel shr 8) and 0xFF
                buffer.put((g - 127.0f) / 128.0f)
            }
        }
        // Blue plane
        for (y in 0 until targetH) {
            val srcY = (y * yRatio).toInt().coerceIn(0, height - 1)
            val rowOffset = srcY * width
            for (x in 0 until targetW) {
                val srcX = (x * xRatio).toInt().coerceIn(0, width - 1)
                val pixel = argbPixels[rowOffset + srcX]
                val b = pixel and 0xFF
                buffer.put((b - 127.0f) / 128.0f)
            }
        }
        buffer.flip()

        val inputTensor = OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        )

        val results = inputTensor.use { tensor ->
            val inputName = session.inputNames.iterator().next()
            session.run(mapOf(inputName to tensor))
        }

        return results.use { res ->
            // Output 0: scores shape [1, 4420, 2]
            // Output 1: boxes  shape [1, 4420, 4]
            @Suppress("UNCHECKED_CAST")
            val scoresArray = res.get(0).value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val boxesArray = res.get(1).value as Array<Array<FloatArray>>

            val numAnchors = scoresArray[0].size
            val candidates = ArrayList<DetectionBox>()

            for (i in 0 until numAnchors) {
                val faceScore = scoresArray[0][i][1]
                if (faceScore >= confThreshold) {
                    val box = boxesArray[0][i]
                    val xMin = box[0].coerceIn(0f, 1f)
                    val yMin = box[1].coerceIn(0f, 1f)
                    val xMax = box[2].coerceIn(0f, 1f)
                    val yMax = box[3].coerceIn(0f, 1f)

                    if (xMax > xMin && yMax > yMin) {
                        candidates.add(DetectionBox(xMin, yMin, xMax, yMax, faceScore))
                    }
                }
            }

            // Apply Non-Maximum Suppression (NMS)
            applyNms(candidates, nmsIouThreshold)
        }
    }

    /**
     * Picks the primary artist / main subject from detected faces based on box area,
     * persistence, and center proximity.
     */
    fun pickMainSubject(detections: List<DetectionBox>): DetectionBox? {
        if (detections.isEmpty()) return null
        if (detections.size == 1) return detections[0]

        // Score based on size (area) and proximity to center (1 - |cx - 0.5|)
        return detections.maxByOrNull { box ->
            val sizeScore = box.area * 0.7f
            val centerScore = (1.0f - abs(box.centerX - 0.5f) * 2f).coerceAtLeast(0f) * 0.3f
            sizeScore + centerScore
        }
    }

    private fun applyNms(boxes: MutableList<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        boxes.sortByDescending { it.confidence }
        val selected = ArrayList<DetectionBox>()

        for (box in boxes) {
            var suppress = false
            for (chosen in selected) {
                if (box.iou(chosen) > iouThreshold) {
                    suppress = true
                    break
                }
            }
            if (!suppress) {
                selected.add(box)
            }
        }
        return selected
    }

    override fun close() {
        session.close()
    }
}
