package com.opencanvas.core.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic pseudo-random signal in `-amplitude..amplitude` (own LCG, so every platform and run
 * sees the same samples). Shared by the other tests of this package.
 */
internal fun lcgNoise(count: Int, seed: Long, amplitude: Float = 1f): FloatArray {
    var state = seed * 2862933555777941757L + 3037000493L
    return FloatArray(count) {
        state = state * 6364136223846793005L + 1442695040888963407L
        ((state ushr 40).toInt() / 8388608f - 1f) * amplitude
    }
}

class FftTest {

    @Test
    fun forwardMatchesTheNaiveDft() {
        for (n in listOf(2, 4, 8, 64, 256)) {
            val re = lcgNoise(n, seed = n.toLong()).map { it.toDouble() }.toDoubleArray()
            val im = lcgNoise(n, seed = 100L + n).map { it.toDouble() }.toDoubleArray()
            val expectedRe = DoubleArray(n)
            val expectedIm = DoubleArray(n)
            for (k in 0 until n) {
                for (t in 0 until n) {
                    val angle = -2.0 * PI * k * t / n
                    expectedRe[k] += re[t] * cos(angle) - im[t] * sin(angle)
                    expectedIm[k] += re[t] * sin(angle) + im[t] * cos(angle)
                }
            }

            val fft = Fft(n)
            val outRe = re.copyOf()
            val outIm = im.copyOf()
            fft.forward(outRe, outIm)

            for (k in 0 until n) {
                assertEquals(expectedRe[k], outRe[k], 1e-9, "re[$k] of $n")
                assertEquals(expectedIm[k], outIm[k], 1e-9, "im[$k] of $n")
            }
        }
    }

    @Test
    fun inverseUndoesForward() {
        val n = 4096
        val re = lcgNoise(n, seed = 1).map { it.toDouble() }.toDoubleArray()
        val im = lcgNoise(n, seed = 2).map { it.toDouble() }.toDoubleArray()
        val outRe = re.copyOf()
        val outIm = im.copyOf()

        Fft(n).apply {
            forward(outRe, outIm)
            inverse(outRe, outIm)
        }

        for (i in 0 until n) {
            assertEquals(re[i], outRe[i], 1e-12)
            assertEquals(im[i], outIm[i], 1e-12)
        }
    }

    @Test
    fun sizeMustBeAPowerOfTwo() {
        for (bad in listOf(0, 1, 3, 6, 100)) {
            assertTrue(runCatching { Fft(bad) }.isFailure, "size $bad")
        }
    }

    @Test
    fun correlationPeaksAtTheShiftOfACopy() {
        val video = lcgNoise(3_000, seed = 1) // not a power of two: the transform pads it
        val length = 400
        val correlator = VideoCorrelator(video, minLocalRms = 1e-4)

        for (position in listOf(0, 1, 777, 1_500, 2_599, 2_600)) {
            val curve = assertNotNull(correlator.curve(video, position, length))
            assertEquals(3_000 - length + 1, curve.size)
            val best = curve.indices.maxBy { curve[it] }
            assertEquals(position, best, "peak for a copy taken at $position")
            assertTrue(curve[best] > 0.999999, "ncc ${curve[best]} at $position")
        }
    }

    @Test
    fun correlationMatchesABruteForceNcc() {
        val video = lcgNoise(2_000, seed = 3)
        val length = 300
        // a probe that is partly related to the video, so the curve has values of every size
        val unrelated = lcgNoise(length, seed = 4)
        val probe = FloatArray(length) { unrelated[it] + video[900 + it] * 0.5f }
        val curve = assertNotNull(VideoCorrelator(video, minLocalRms = 1e-4).curve(probe, 0, length))

        val probeMean = probe.average()
        val probeNorm = sqrt(probe.sumOf { (it - probeMean) * (it - probeMean) })
        for (k in listOf(0, 450, 899, 900, 901, 1_500, 1_700)) {
            val mean = (0 until length).sumOf { video[k + it].toDouble() } / length
            val norm = sqrt((0 until length).sumOf { (video[k + it] - mean) * (video[k + it] - mean) })
            val dot = (0 until length).sumOf { (probe[it] - probeMean) * (video[k + it] - mean) }
            assertEquals(dot / (probeNorm * norm), curve[k], 1e-9, "ncc at $k")
        }
    }

    @Test
    fun aFlatOrOversizedProbeHasNothingToCorrelate() {
        val correlator = VideoCorrelator(lcgNoise(1_000, seed = 1), minLocalRms = 1e-4)

        assertNull(correlator.curve(FloatArray(200) { 0.25f }, 0, 200))
        assertNull(correlator.curve(FloatArray(2_000), 0, 2_000))
        assertNull(correlator.curve(FloatArray(1) { 1f }, 0, 1))
    }

    @Test
    fun quietStretchesOfTheSignalScoreZero() {
        val video = lcgNoise(2_000, seed = 5)
        for (i in 800 until 1_300) video[i] = 0f
        val curve = assertNotNull(VideoCorrelator(video, minLocalRms = 1e-4).curve(video, 200, 100))

        for (k in 800..1_200) assertTrue(curve[k] == 0.0, "slice at $k is silent but scored ${curve[k]}")
        assertTrue(curve[200] > 0.999999)
    }
}
