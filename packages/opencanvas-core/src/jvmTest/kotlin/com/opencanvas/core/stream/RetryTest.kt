package com.opencanvas.core.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryTest {

    @Test
    fun aHangingFirstAttemptIsFollowedByASecondWithItsOwnLimit() = runBlocking {
        val limits = ArrayList<Long>()
        val result = withRetry(listOf(100L, 2_000L)) { timeoutMs ->
            limits += timeoutMs
            if (limits.size == 1) delay(60_000L)
            "answer"
        }
        assertEquals("answer", result)
        assertEquals(listOf(100L, 2_000L), limits)
    }

    @Test
    fun anIoFailureIsRetried() = runBlocking {
        var attempts = 0
        val result = withRetry(listOf(500L, 500L)) {
            if (++attempts == 1) throw IOException("connection reset")
            attempts
        }
        assertEquals(2, result)
    }

    @Test
    fun whenEveryAttemptFailsTheCallerSeesAnIoErrorNotACancellation() = runBlocking {
        val failure = assertFailsWith<IOException> { withRetry(listOf(50L, 50L)) { delay(60_000L) } }
        assertTrue(failure !is CancellationException)
    }

    @Test
    fun otherExceptionsAreNotRetried() = runBlocking {
        var attempts = 0
        assertFailsWith<IllegalStateException> { withRetry(listOf(500L, 500L)) { attempts++; error("bug") } }
        assertEquals(1, attempts)
    }

    @Test
    fun tryOrNullTurnsFailureIntoNullButLetsCancellationThrough() = runBlocking {
        assertNull(tryOrNull { error("nope") })
        val job = async {
            tryOrNull { delay(60_000L) }
            "carried on"
        }
        delay(50L)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        Unit
    }
}
