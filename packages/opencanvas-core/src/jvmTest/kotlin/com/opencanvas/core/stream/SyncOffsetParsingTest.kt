package com.opencanvas.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncOffsetParsingTest {

    private fun parse(body: String): SyncOffset? = YouTubeStreamResolver.parseSyncResponse(body)

    private fun syncBody(offset: String = "22500", confidence: String = "0.93") =
        """{"trackId":"4NRXx6U8ABQ","videoId":"fHI8X4OXluQ","offsetMs":$offset,"confidence":$confidence,""" +
            """"probes":[{"t":30000,"offsetMs":22498},{"t":120000,"offsetMs":22503}],"cached":false,"computeMs":14210}"""

    @Test
    fun parsesAFullDaemonResponse() {
        assertEquals(SyncOffset(22_500L, 0.93), parse(syncBody()))
    }

    @Test
    fun parsesNegativeAndZeroOffsets() {
        assertEquals(SyncOffset(-4_200L, 0.93), parse(syncBody(offset = "-4200")))
        assertEquals(SyncOffset(0L, 0.93), parse(syncBody(offset = "0")))
    }

    @Test
    fun parsesNumberVariants() {
        data class Case(val name: String, val body: String, val expected: SyncOffset?)
        val cases = listOf(
            Case("fractional offset rounds", syncBody(offset = "22500.4"), SyncOffset(22_500L, 0.93)),
            Case("fractional offset rounds up", syncBody(offset = "22500.6"), SyncOffset(22_501L, 0.93)),
            Case("exponent notation", syncBody(offset = "2.25e4"), SyncOffset(22_500L, 0.93)),
            Case("numeric strings", syncBody(offset = "\"22500\"", confidence = "\"0.9\""), SyncOffset(22_500L, 0.9)),
            Case("confidence 1", syncBody(confidence = "1"), SyncOffset(22_500L, 1.0)),
            Case("confidence above 1 is clamped", syncBody(confidence = "1.7"), SyncOffset(22_500L, 1.0)),
        )
        for (c in cases) assertEquals(c.expected, parse(c.body), c.name)
    }

    @Test
    fun confidenceThresholdIsEnforced() {
        assertEquals(0.4, YouTubeStreamResolver.MIN_SYNC_CONFIDENCE)
        assertNotNull(parse(syncBody(confidence = "0.4")), "exactly the minimum is accepted")
        assertNull(parse(syncBody(confidence = "0.39")))
        assertNull(parse(syncBody(confidence = "0")))
        assertNull(parse(syncBody(confidence = "-0.5")))
        assertNotNull(YouTubeStreamResolver.parseSyncResponse(syncBody(confidence = "0.2"), minConfidence = 0.1))
        assertNull(YouTubeStreamResolver.parseSyncResponse(syncBody(confidence = "0.8"), minConfidence = 0.9))
    }

    @Test
    fun rejectsMalformedOrIncompleteBodies() {
        val cases = mapOf(
            "empty" to "",
            "blank" to "   ",
            "not json" to "<html>502 Bad Gateway</html>",
            "truncated json" to """{"offsetMs":22500,"confid""",
            "json array" to "[22500, 0.9]",
            "json string" to "\"22500\"",
            "json null" to "null",
            "empty object" to "{}",
            "missing offset" to """{"confidence":0.9}""",
            "missing confidence" to """{"offsetMs":22500}""",
            "null offset" to """{"offsetMs":null,"confidence":0.9}""",
            "null confidence" to """{"offsetMs":22500,"confidence":null}""",
            "non numeric offset" to """{"offsetMs":"soon","confidence":0.9}""",
            "non numeric confidence" to """{"offsetMs":22500,"confidence":"high"}""",
            "object offset" to """{"offsetMs":{"v":1},"confidence":0.9}""",
            "array confidence" to """{"offsetMs":22500,"confidence":[0.9]}""",
            "NaN offset" to """{"offsetMs":"NaN","confidence":0.9}""",
            "infinite confidence" to """{"offsetMs":22500,"confidence":"Infinity"}""",
            "error payload" to """{"error":"video unavailable"}""",
        )
        for ((name, body) in cases) assertNull(parse(body), name)
    }

    @Test
    fun ignoresUnknownFields() {
        val body = """{"offsetMs":1500,"confidence":0.8,"extra":{"a":[1,2,3]},"futureField":true}"""
        assertEquals(SyncOffset(1_500L, 0.8), parse(body))
    }
}
