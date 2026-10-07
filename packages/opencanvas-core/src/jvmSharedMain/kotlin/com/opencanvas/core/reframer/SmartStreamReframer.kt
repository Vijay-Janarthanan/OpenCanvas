package com.opencanvas.core.reframer

import com.opencanvas.core.filter.CropKinematics
import com.opencanvas.core.filter.OneEuroFilter
import com.opencanvas.core.filter.SceneCutDetector
import com.opencanvas.core.models.CropKeyframe
import com.opencanvas.core.models.CropTrajectory
import com.opencanvas.core.onnx.OnnxSubjectTracker

/**
 * Real-time dynamic video reframer for direct video streams.
 * Computes smooth, cinematic 9:16 focal center trajectories from raw video frames.
 */
class SmartStreamReframer(
    private val tracker: OnnxSubjectTracker = OnnxSubjectTracker(),
    private val sourceAspect: Float = 16f / 9f,
    private val targetAspect: Float = 9f / 16f,
) : AutoCloseable {

    private val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.007)
    private val cutDetector = SceneCutDetector(boxJumpThreshold = 0.28f)
    private var lastClampedCropX = 0.5f

    /**
     * Processes a single video frame at timestamp [timestampSec].
     * Returns the smoothed normalized horizontal focal center [0.0f, 1.0f].
     */
    fun processFrame(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        timestampSec: Double,
    ): Float {
        val faces = tracker.detectFaces(argbPixels, width, height)
        val mainSubject = tracker.pickMainSubject(faces)

        val targetCenterX = if (mainSubject != null) {
            mainSubject.centerX
        } else {
            // If no face found in this frame, smoothly drift toward center (0.5)
            0.5f
        }

        val isCut = cutDetector.checkCenterJump(targetCenterX)
        if (isCut) {
            // Reset filter so camera snaps immediately on scene cut without whip-panning
            filter.reset()
        }

        val smoothedX = filter.filter(targetCenterX.toDouble(), timestampSec).toFloat()
        val clampedX = CropKinematics.clampFocalCenter(smoothedX, sourceAspect, targetAspect)
        lastClampedCropX = clampedX

        return clampedX
    }

    /**
     * Builds a full pre-computed [CropTrajectory] from a series of sampled keyframes.
     */
    fun buildTrajectory(
        videoId: String,
        sampledFrames: List<Triple<Double, IntArray, Pair<Int, Int>>>, // timestamp, pixels, (width, height)
    ): CropTrajectory {
        filter.reset()
        cutDetector.reset()

        val keyframes = ArrayList<CropKeyframe>(sampledFrames.size)

        for ((t, pixels, dim) in sampledFrames) {
            val (w, h) = dim
            val faces = tracker.detectFaces(pixels, w, h)
            val mainSubject = tracker.pickMainSubject(faces)
            val rawCenterX = mainSubject?.centerX ?: 0.5f

            val isCut = cutDetector.checkCenterJump(rawCenterX)
            if (isCut) filter.reset()

            val smoothedX = filter.filter(rawCenterX.toDouble(), t).toFloat()
            val clampedX = CropKinematics.clampFocalCenter(smoothedX, sourceAspect, targetAspect)

            keyframes.add(CropKeyframe(t = t, cx = clampedX, isSceneCut = isCut))
        }

        return CropTrajectory(videoId = videoId, keyframes = keyframes)
    }

    override fun close() {
        tracker.close()
    }
}
