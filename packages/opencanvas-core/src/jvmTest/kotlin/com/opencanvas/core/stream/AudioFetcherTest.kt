package com.opencanvas.core.stream

import com.opencanvas.core.TestServer
import com.opencanvas.core.sync.Mp4FrameSizes
import com.opencanvas.core.sync.Mp4TestFiles
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioFetcherTest {

    private val server = TestServer()
    private val fetcher = AudioFetcher(OkHttpClient())
    private val payload = ByteArray(2_500_000).also { Random(11).nextBytes(it) }

    @After
    fun stop() = server.close()

    private fun stream(path: String, maxRangeBytes: Long = 512 * 1024L) =
        AudioStream(url = "${server.baseUrl}$path", maxRangeBytes = maxRangeBytes)

    @Test
    fun readsASliceAsConcurrentRangesAndPutsItBackInOrder() = runBlocking {
        server.file("/a.mp4", payload)
        val slice = fetcher.read(stream("/a.mp4"), from = 100_000L, length = 1_300_000L)
        assertContentEquals(payload.copyOfRange(100_000, 1_400_000), slice.bytes)
        assertEquals(payload.size.toLong(), slice.totalLength)
        assertTrue(server.requests.size >= 3, "a 1.3 MB slice needs several 512 KiB ranges, saw ${server.requests}")
    }

    @Test
    fun aSliceThatRunsPastTheEndIsShortAndBeyondTheEndIsEmpty() = runBlocking {
        server.file("/a.mp4", payload)
        val tail = fetcher.read(stream("/a.mp4"), from = 2_400_000L, length = 1_000_000L)
        assertContentEquals(payload.copyOfRange(2_400_000, payload.size), tail.bytes)
        assertEquals(0, fetcher.read(stream("/a.mp4"), from = 9_000_000L, length = 1_000L).bytes.size)
    }

    @Test
    fun aServerThatIgnoresRangesIsNotDownloadedToTheEnd() = runBlocking {
        server.file("/a.mp4", payload, honourRanges = false)
        val slice = fetcher.read(stream("/a.mp4"), from = 0L, length = 200_000L)
        assertContentEquals(payload.copyOfRange(0, 200_000), slice.bytes)
    }

    @Test
    fun readsALocalFileThroughAFileUri() = runBlocking {
        val file: File = Files.createTempFile("opencanvas", ".mp4").toFile().apply { writeBytes(payload); deleteOnExit() }
        val slice = fetcher.read(AudioStream(url = file.toURI().toString()), from = 10L, length = 100L)
        assertContentEquals(payload.copyOfRange(10, 110), slice.bytes)
        assertEquals(payload.size.toLong(), slice.totalLength)
    }

    @Test
    fun theHeaderOfAProgressiveFileCostsOneRequest() = runBlocking {
        val sizes = IntArray(4000) { 300 + (it * 37) % 211 }
        server.file("/song.mp4", Mp4TestFiles.progressive(sizes) + ByteArray(3_000_000))
        val frames = assertNotNull(Mp4FrameSizes.read(StreamReader(fetcher, stream("/song.mp4"))))
        assertContentEquals(sizes, frames.sizes)
        assertEquals(1, server.requests.size, "the head and the moov box both fit in the first request: ${server.requests}")
    }

    @Test
    fun aMoovBoxPastTheHeadIsFetchedWithOneMoreRange() = runBlocking {
        val sizes = IntArray(4000) { 300 + (it * 37) % 211 }
        // moov written last, behind 700 kB of media data: beyond the 320 KiB first request
        server.file("/padded.mp4", Mp4TestFiles.progressive(sizes, moovLast = true, mediaBytes = 700_000))
        val frames = assertNotNull(Mp4FrameSizes.read(StreamReader(fetcher, stream("/padded.mp4"))))
        assertContentEquals(sizes, frames.sizes)
        assertTrue(server.requests.count { it.contains("/padded.mp4") } in 2..3, server.requests.toString())
    }
}
