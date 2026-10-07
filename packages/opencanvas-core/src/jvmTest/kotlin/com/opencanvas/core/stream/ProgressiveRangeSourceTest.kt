package com.opencanvas.core.stream

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.Random

class ProgressiveRangeSourceTest {

    private lateinit var server: HttpServer
    private val payload = ByteArray(3_000_000).also { Random(7).nextBytes(it) }
    private val rangeHeaders = Collections.synchronizedList(ArrayList<String?>())
    private val client = OkHttpClient()
    private var honourRanges = true
    private var perRequestDelayMs = 0L

    private val url get() = "http://127.0.0.1:${server.address.port}/clip.mp4"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/clip.mp4") { ex -> serve(ex) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun serve(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        rangeHeaders += range
        if (perRequestDelayMs > 0) Thread.sleep(perRequestDelayMs)
        if (range == null || !honourRanges) {
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
            return
        }
        val (from, toRaw) = range.removePrefix("bytes=").split('-')
        val start = from.toLong()
        if (start >= payload.size) {
            ex.sendResponseHeaders(416, -1)
            ex.close()
            return
        }
        val end = minOf(toRaw.toLong(), payload.size - 1L)
        val body = payload.copyOfRange(start.toInt(), end.toInt() + 1)
        ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
        ex.sendResponseHeaders(206, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun readAll(source: ProgressiveRangeSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheWholeFileInOrderThroughRanges() {
        ProgressiveRangeSource(url, client, initialChunkBytes = 64 * 1024, maxChunkBytes = 512 * 1024).use { s ->
            assertArrayEquals(payload, readAll(s))
            assertEquals(payload.size.toLong(), s.length())
        }
        assertTrue("every request must be ranged, or the host throttles it", rangeHeaders.all { it != null })
    }

    @Test
    fun startsSmallThenGrows() {
        ProgressiveRangeSource(url, client, initialChunkBytes = 64 * 1024, maxChunkBytes = 1024 * 1024).use { s ->
            readAll(s)
            val sizes = s.requestLog.map { it.second }
            assertEquals("first request is the small one", 64 * 1024, sizes.first())
            assertEquals(128 * 1024, sizes[1])
            assertTrue("chunks keep growing", sizes[2] > sizes[1])
            assertTrue("and are capped", sizes.all { it <= 1024 * 1024 })
        }
    }

    @Test
    fun aSeekFetchesASmallRangeAtTheTargetFirst() {
        // Read-ahead is capped so the file is not already fully buffered when the seek happens.
        ProgressiveRangeSource(
            url, client,
            initialChunkBytes = 64 * 1024, maxChunkBytes = 1024 * 1024, aheadLimitBytes = 64 * 1024,
        ).use { s ->
            val head = ByteArray(10_000)
            assertEquals(10_000, s.read(head, 0, head.size))
            s.seek(2_500_000)
            val got = ByteArray(1000)
            assertEquals(1000, s.read(got, 0, got.size))
            assertArrayEquals(payload.copyOfRange(2_500_000, 2_501_000), got)
            val afterSeek = s.requestLog.first { it.first >= 2_500_000 - 1 }
            assertEquals("the first request at the target is the small one", 64 * 1024, afterSeek.second)
        }
    }

    @Test
    fun seekingBackIntoBufferedDataNeedsNoNewRequest() {
        ProgressiveRangeSource(url, client, initialChunkBytes = 256 * 1024, maxChunkBytes = 256 * 1024).use { s ->
            val buf = ByteArray(200_000)
            assertEquals(200_000, s.read(buf, 0, buf.size))
            val requestsBefore = s.requestLog.size
            s.seek(0)
            val again = ByteArray(1000)
            assertEquals(1000, s.read(again, 0, again.size))
            assertArrayEquals(payload.copyOfRange(0, 1000), again)
            assertEquals("the head is served from memory", 1, s.requestLog.count { it.first == 0L })
            assertTrue(s.requestLog.size >= requestsBefore)
        }
    }

    @Test
    fun endOfFileReadsMinusOneAndLengthIsKnown() {
        ProgressiveRangeSource(url, client).use { s ->
            s.seek(payload.size - 10L)
            val tail = ByteArray(100)
            assertEquals(10, s.read(tail, 0, tail.size))
            assertEquals(-1, s.read(tail, 0, tail.size))
            assertEquals(payload.size.toLong(), s.length())
        }
    }

    @Test
    fun aServerThatIgnoresRangesStillWorks() {
        honourRanges = false
        ProgressiveRangeSource(url, client).use { s -> assertArrayEquals(payload, readAll(s)) }
    }

    @Test
    fun completeFileCanBePersisted() {
        ProgressiveRangeSource(url, client, initialChunkBytes = 128 * 1024, maxChunkBytes = 1024 * 1024).use { s ->
            assertTrue(s.awaitComplete(10_000))
            assertTrue(s.isComplete)
            val target = Files.createTempFile("opencanvas-test", ".mp4")
            try {
                assertTrue(s.writeCompleteTo(target))
                assertArrayEquals(payload, Files.readAllBytes(target))
            } finally {
                Files.deleteIfExists(target)
            }
        }
    }

    @Test
    fun incompleteFileIsNotPersisted() {
        perRequestDelayMs = 400
        ProgressiveRangeSource(url, client, initialChunkBytes = 64 * 1024, maxChunkBytes = 64 * 1024).use { s ->
            val target = Files.createTempFile("opencanvas-test", ".mp4")
            try {
                assertFalse(s.writeCompleteTo(target))
            } finally {
                Files.deleteIfExists(target)
            }
        }
    }
}
