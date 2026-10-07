package com.opencanvas.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A throwaway HTTP server for tests: files that honour range requests the way googlevideo does, and
 * arbitrary handlers for everything else. Requests are recorded so a test can count them.
 */
internal class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requestLog = Collections.synchronizedList(ArrayList<String>())

    init {
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every request seen so far, as `"GET /path Range"`. */
    val requests: List<String> get() = synchronized(requestLog) { requestLog.toList() }

    /** Serves [data] at [path], answering range requests with 206 and the rest with 200. */
    fun file(path: String, data: ByteArray, honourRanges: Boolean = true) {
        server.createContext(path) { exchange ->
            record(exchange)
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null || !honourRanges) {
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
                return@createContext
            }
            val (from, toRaw) = range.removePrefix("bytes=").split('-')
            val start = from.toLong()
            if (start >= data.size) {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return@createContext
            }
            val end = minOf(toRaw.toLong(), data.size - 1L)
            val body = data.copyOfRange(start.toInt(), end.toInt() + 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    /** Answers every request to [path] with [handler]'s status and body. */
    fun respond(path: String, handler: (HttpExchange) -> Pair<Int, ByteArray>) {
        server.createContext(path) { exchange ->
            record(exchange)
            val (status, body) = handler(exchange)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun record(exchange: HttpExchange) {
        requestLog += "${exchange.requestMethod} ${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Range").orEmpty()}".trim()
    }

    override fun close() = server.stop(0)
}
