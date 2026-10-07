package com.opencanvas.core.stream

import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `/sync` response now carries a map of segments, not just one offset. */
class SyncMapParsingTest {

    private val full = """
        {"trackId":"J7p4bzqLvCw","videoId":"4NRXx6U8ABQ","offsetMs":22612,"confidence":0.8484,"coverage":0.6201,
         "segments":[{"songStartMs":0,"songEndMs":127500,"offsetMs":22612,"ncc":0.83},
                     {"songStartMs":127500,"songEndMs":201573,"offsetMs":25198,"ncc":0.87}],
         "cached":false,"computeMs":9702}
    """.trimIndent()

    @Test
    fun parsesTheSegmentsAndCoverage() {
        val parsed = assertNotNull(YouTubeStreamResolver.parseSyncResponse(full))
        assertEquals(22_612L, parsed.offsetMs)
        assertEquals(0.8484, parsed.confidence)
        assertEquals(0.6201, parsed.coverage)
        assertEquals(
            listOf(SyncSegment(0, 127_500, 22_612), SyncSegment(127_500, 201_573, 25_198)),
            parsed.segments,
        )
        assertEquals(25_198L, parsed.toMap().offsetAt(150_000))
    }

    @Test
    fun aDaemonWithoutTheMapGivesOneConstantOffset() {
        val old = """{"offsetMs":22500,"confidence":0.9}"""
        val parsed = assertNotNull(YouTubeStreamResolver.parseSyncResponse(old))
        assertTrue(parsed.segments.isEmpty())
        assertEquals(1.0, parsed.coverage)
        assertEquals(SyncMap.constant(22_500), parsed.toMap())
        assertEquals(22_500L, parsed.toMap().offsetAt(1_000_000))
    }

    @Test
    fun malformedSegmentsAreSkipped() {
        val body = """
            {"offsetMs":1000,"confidence":0.9,"segments":[
              {"songStartMs":0,"songEndMs":50000,"offsetMs":1000},
              {"songStartMs":50000,"songEndMs":50000,"offsetMs":2000},
              {"songStartMs":60000,"songEndMs":"x","offsetMs":2000},
              "nonsense",
              {"songStartMs":70000,"songEndMs":90000}
            ]}
        """.trimIndent()
        val parsed = assertNotNull(YouTubeStreamResolver.parseSyncResponse(body))
        assertEquals(listOf(SyncSegment(0, 50_000, 1_000)), parsed.segments)
    }

    @Test
    fun lowConfidenceIsStillRejectedWhateverTheSegments() {
        val body = """{"offsetMs":22612,"confidence":0.2,"segments":[{"songStartMs":0,"songEndMs":1000,"offsetMs":1}]}"""
        assertNull(YouTubeStreamResolver.parseSyncResponse(body))
    }

    @Test
    fun coverageIsClampedToTheUnitInterval() {
        val body = """{"offsetMs":1,"confidence":0.9,"coverage":7}"""
        assertEquals(1.0, YouTubeStreamResolver.parseSyncResponse(body)?.coverage)
    }
}
