package com.opencanvas.core.sync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameEnvelopeTest {

    @Test
    fun sampleCountFollowsTheDurationOfTheFrames() {
        val frames = AudioFrames(IntArray(1000) { 400 }, 1024.0 / 44_100.0)
        val envelope = FrameEnvelope.build(frames)
        assertEquals((frames.sizes.size - 1) * frames.frameSeconds * FrameEnvelope.RATE_HZ, envelope.size.toDouble(), 1.0)
    }

    @Test
    fun aConstantBitrateHasNoEnvelope() {
        val envelope = FrameEnvelope.build(AudioFrames(IntArray(800) { 400 }, 0.0232))
        assertTrue(envelope.all { abs(it) < 1e-5f })
    }

    @Test
    fun theSameMusicWithDifferentFrameLengthsGivesTheSameShape() {
        // loudness rising and falling every 4 s, seen by a 23 ms and by a 46 ms encoder
        fun shape(seconds: Double) = 300 + (200 * (1 + sin(2 * PI * seconds / 4.0))).toInt()
        val short = FrameEnvelope.build(AudioFrames(IntArray(2000) { shape(it * 0.0232) }, 0.0232))
        val long = FrameEnvelope.build(AudioFrames(IntArray(1000) { shape(it * 0.0464) }, 0.0464))
        val n = minOf(short.size, long.size) - 100
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        for (i in 50 until n) {
            dot += short[i] * long[i]
            a += short[i] * short[i]
            b += long[i] * long[i]
        }
        assertTrue(dot / sqrt(a * b) > 0.95)
    }

    @Test
    fun removesTheSlowDriftOfTheBitrate() {
        val drifting = AudioFrames(IntArray(3000) { 300 + it / 3 }, 0.0232)
        val envelope = FrameEnvelope.build(drifting)
        assertTrue(envelope.drop(200).dropLast(200).all { abs(it) < 0.05f })
    }

    @Test
    fun tooFewFramesGiveAnEmptyEnvelope() {
        assertEquals(0, FrameEnvelope.build(AudioFrames(intArrayOf(300), 0.0232)).size)
        assertEquals(0, FrameEnvelope.build(AudioFrames(IntArray(10) { 300 }, 0.0)).size)
    }
}
