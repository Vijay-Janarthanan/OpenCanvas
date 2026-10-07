package com.opencanvas.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp4FrameSizesTest {

    private val sizes = IntArray(500) { 300 + (it * 37) % 211 }

    @Test
    fun readsTheAudioTrackOfAProgressiveFile() = runTest {
        val frames = assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.progressive(sizes))))
        assertContentEquals(sizes, frames.sizes)
        assertEquals(1024.0 / 44_100.0, frames.frameSeconds, 1e-9)
    }

    @Test
    fun reportsTheBitrateOfTheVideoTrack() = runTest {
        // 1000 video samples of 7000..7999 bytes, 3000 ticks apart at 90 kHz: 33.3 s, 7 499 500 bytes
        val frames = assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.progressive(sizes))))
        assertEquals(7_499_500 * 8.0 / 1000.0 / (1000 * 3000 / 90_000.0), frames.videoKbps, 0.01)
    }

    @Test
    fun anAudioOnlyFileHasNoVideoBitrate() = runTest {
        assertEquals(0.0, assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.fragmented(sizes)))).videoKbps)
    }

    @Test
    fun takesTheFrameLengthFromTheTrackNotFromAssumptions() = runTest {
        val frames = assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.progressive(sizes, timescale = 22_050, delta = 1024))))
        assertEquals(1024.0 / 22_050.0, frames.frameSeconds, 1e-9)
    }

    @Test
    fun readsBytesProportionalToTheHeaderNotTheFile() = runTest {
        val file = Mp4TestFiles.progressive(sizes) + ByteArray(5_000_000) // a long tail after moov
        val reader = MemoryReader(file)
        assertNotNull(Mp4FrameSizes.read(reader))
        assertTrue(reader.requested < 100_000, "read ${reader.requested} bytes")
    }

    @Test
    fun findsMoovWrittenAfterTheMediaData() = runTest {
        val frames = assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.progressive(sizes, moovLast = true))))
        assertContentEquals(sizes, frames.sizes)
    }

    @Test
    fun readsTheFramesOfAFragmentedFile() = runTest {
        val frames = assertNotNull(Mp4FrameSizes.read(MemoryReader(Mp4TestFiles.fragmented(sizes))))
        assertContentEquals(sizes, frames.sizes)
        assertEquals(1024.0 / 44_100.0, frames.frameSeconds, 1e-9)
    }

    @Test
    fun returnsNullWithoutAnAudioTrackOrWithGarbage() = runTest {
        assertNull(Mp4FrameSizes.read(MemoryReader(ByteArray(0))))
        assertNull(Mp4FrameSizes.read(MemoryReader(ByteArray(64) { it.toByte() })))
        val videoOnly = Mp4TestFiles.box("ftyp", ByteArray(8)) + Mp4TestFiles.box("moov", Mp4TestFiles.box("trak", Mp4TestFiles.box("mdia")))
        assertNull(Mp4FrameSizes.read(MemoryReader(videoOnly)))
    }

    @Test
    fun survivesATruncatedMoovWithoutThrowing() = runTest {
        val file = Mp4TestFiles.progressive(sizes)
        assertNull(Mp4FrameSizes.read(MemoryReader(file.copyOf(file.size / 3))))
    }
}
