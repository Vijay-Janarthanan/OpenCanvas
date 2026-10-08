package com.opencanvas.core.sync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncAlignerTest {

    // the rate of the frame-size envelopes the aligner is fed on the device: 1 sample = 20 ms
    private val rate = 50

    private fun samples(seconds: Double) = (seconds * rate).roundToInt()

    private fun noise(seconds: Double, seed: Long, amplitude: Float = 0.3f) = lcgNoise(samples(seconds), seed, amplitude)

    private fun slice(signal: FloatArray, fromSeconds: Double, toSeconds: Double) =
        signal.copyOfRange(samples(fromSeconds), samples(toSeconds))

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun assertNear(expected: Long, actual: Long, toleranceMs: Long = 5, message: String = "") {
        assertTrue(abs(expected - actual) <= toleranceMs, "$message expected $expected +-$toleranceMs ms but was $actual")
    }

    @Test
    fun aVideoThatContainsTheSongAfterAnIntroGivesOneSegment() {
        val track = noise(60.0, seed = 1)
        val video = concat(noise(12.5, seed = 2), track, noise(8.0, seed = 3))

        val result = SyncAligner.align(track, video, rate)

        assertEquals(null, result.error)
        assertNear(12_500, result.offsetMs)
        assertTrue(result.confidence > 0.9, "confidence ${result.confidence}")
        assertTrue(result.coverage > 0.9, "coverage ${result.coverage}") // 11 windows of a 5 s hop over 60 s: 55/60
        val segment = result.segments.single()
        assertEquals(0L, segment.songStartMs)
        assertEquals(60_000L, segment.songEndMs)
        assertNear(12_500, segment.offsetMs)
        assertTrue(segment.ncc > 0.9, "ncc ${segment.ncc}")
    }

    @Test
    fun theOffsetIsFoundBetweenSamples() {
        val track = noise(60.0, seed = 1)
        // 7.31 s is 365.5 samples: half a sample off the grid; the video holds the song delayed by linear interpolation
        val delayed = FloatArray(track.size) { j -> if (j == 0) track[0] / 2 else (track[j] + track[j - 1]) / 2 }
        val video = concat(lcgNoise(365, seed = 2, amplitude = 0.3f), delayed, noise(5.0, seed = 3))

        val result = SyncAligner.align(track, video, rate)

        assertEquals(null, result.error)
        assertNear(7_310, result.offsetMs, toleranceMs = 10) // half a sample is 10 ms
    }

    @Test
    fun anInsertedBlockStartsASecondSegmentWithTheLargerOffset() {
        val track = noise(80.0, seed = 1)
        val video = concat(
            noise(10.0, seed = 2),
            slice(track, 0.0, 40.0),
            noise(20.0, seed = 3), // the video repeats a bar the song does not have
            slice(track, 40.0, 80.0),
            noise(5.0, seed = 4),
        )

        val result = SyncAligner.align(track, video, rate)

        assertEquals(null, result.error)
        assertEquals(2, result.segments.size, "segments: ${result.segments}")
        val (before, after) = result.segments
        assertNear(10_000, before.offsetMs)
        assertNear(30_000, after.offsetMs)
        assertEquals(before.songEndMs, after.songStartMs)
        assertTrue(before.songEndMs in 35_000L..45_000L, "edit located at ${before.songEndMs}")
        assertEquals(0L, before.songStartMs)
        assertEquals(80_000L, after.songEndMs)
        assertTrue(result.offsetMs == before.offsetMs || result.offsetMs == after.offsetMs) // the longest segment's
    }

    @Test
    fun aPatternRepeatedEveryElevenSecondsDoesNotShiftTheOffsetByOnePeriod() {
        // a loop repeats all through the song, with unique material on top
        val loop = noise(11.0, seed = 5)
        val unique = noise(60.0, seed = 6)
        val track = FloatArray(samples(60.0)) { loop[it % loop.size] + unique[it] }
        val video = concat(noise(5.0, seed = 7), track, noise(5.0, seed = 8))

        val result = SyncAligner.align(track, video, rate)

        assertEquals(null, result.error)
        val segment = result.segments.single()
        assertNear(5_000, segment.offsetMs)
        assertTrue(result.coverage > 0.9, "coverage ${result.coverage}")
    }

    @Test
    fun aRepeatedRiffDoesNotMakeTheMapJumpBetweenRepeats() {
        // the same 10 s riff three times, 25 s apart: windows inside a riff match all three repeats equally well
        val riff = noise(10.0, seed = 7)
        val song = concat(
            noise(15.0, seed = 1), riff, noise(15.0, seed = 2), riff, noise(15.0, seed = 3), riff, noise(15.0, seed = 4),
        )
        val slightlyDifferent = noise(90.0, seed = 5, amplitude = 0.02f)
        val video = concat(
            noise(7.0, seed = 8),
            FloatArray(song.size) { song[it] + slightlyDifferent[it] },
            noise(5.0, seed = 9),
        )

        val result = SyncAligner.align(song, video, rate)

        assertEquals(null, result.error)
        assertEquals(1, result.segments.size, "segments: ${result.segments}")
        assertNear(7_000, result.segments.single().offsetMs)
    }

    @Test
    fun theReferenceTuningFindsTheSameOffsetOnTheSameSignals() {
        val track = noise(60.0, seed = 1)
        val video = concat(noise(12.5, seed = 2), track)

        val result = SyncAligner.align(track, video, rate, AlignParams())

        assertEquals(null, result.error)
        assertNear(12_500, result.offsetMs)
    }

    @Test
    fun silenceCannotBeAligned() {
        val silent = FloatArray(samples(40.0))
        val video = noise(60.0, seed = 1)

        val envelope = SyncAligner.align(silent, video, rate)
        assertNotNull(envelope.error)
        assertEquals(0L, envelope.offsetMs)
        assertEquals(0.0, envelope.confidence)
        assertTrue(envelope.segments.isEmpty())

        // the reference tuning gates on loudness and says so
        assertTrue(SyncAligner.align(silent, video, rate, AlignParams()).error!!.contains("silent"))
    }

    @Test
    fun audioShorterThanAWindowCannotBeAligned() {
        val result = SyncAligner.align(noise(3.0, seed = 1), noise(60.0, seed = 2), rate)

        assertTrue(result.error!!.contains("too short"), result.error)
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun unrelatedSignalsCannotBeAligned() {
        val result = SyncAligner.align(noise(60.0, seed = 1), noise(70.0, seed = 99), rate)

        assertTrue(result.error!!.contains("could not align"), result.error)
        assertTrue(result.coverage < 0.25)
        assertTrue(result.segments.isEmpty())
        assertEquals(0.0, result.confidence)
    }

    @Test
    fun theEnvelopeTuningOnlyChangesWhatItSays() {
        val reference = AlignParams()

        assertEquals(reference, AlignParams.ENVELOPE.copy(silenceFloorRms = 0.003, silenceRelRms = 0.10, minLocalRms = 1e-3, candidateMinNcc = 0.45))
        assertEquals(10.0, reference.windowS)
        assertEquals(5.0, reference.hopS)
        assertEquals(6, reference.candidatesPerWindow)
        assertEquals(150.0, reference.sameOffsetMs)
        assertEquals(0.25, reference.minCoverage)
    }

    @Test
    fun aVideoRunningAtFilmSpeedGetsASegmentWithItsRate() {
        val track = noise(80.0, seed = 7)
        val ratio = 25.0 / 24.0 // the video plays the song 4 % slower, as a PAL transfer of a film does
        val offsetS = 12.0
        val stretched = FloatArray((track.size * ratio).toInt()) {
            val x = it / ratio
            val i = x.toInt().coerceAtMost(track.size - 2)
            val f = (x - i).toFloat()
            track[i] * (1 - f) + track[i + 1] * f
        }
        val video = concat(noise(offsetS, seed = 8), stretched, noise(6.0, seed = 9))

        val result = SyncAligner.align(track, video, rate)

        assertEquals(null, result.error)
        val segment = result.segments.maxBy { it.songEndMs - it.songStartMs }
        assertTrue(abs(segment.rate - ratio) < 0.002, "rate ${segment.rate}")
        for (songMs in listOf(10_000L, 40_000L, 70_000L)) {
            val expected = (ratio * songMs + offsetS * 1000).toLong()
            val actual = songMs + SyncMap(result.segments).offsetAt(songMs)!!
            assertNear(expected, actual, 250, "video time at song $songMs")
        }
    }
}
