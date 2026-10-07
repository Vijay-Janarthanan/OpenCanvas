package com.opencanvas.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMapTest {

    /** The measured map of the official Blinding Lights video: a 2.6 s insertion in the bridge. */
    private val blindingLights = SyncMap(
        listOf(
            SyncSegment(0, 127_500, 22_612),
            SyncSegment(127_500, 201_573, 25_198),
        ),
    )

    @Test
    fun offsetAtFollowsTheSegments() {
        assertEquals(22_612L, blindingLights.offsetAt(0))
        assertEquals(22_612L, blindingLights.offsetAt(127_499))
        assertEquals(25_198L, blindingLights.offsetAt(127_500)) // end is exclusive, start inclusive
        assertEquals(25_198L, blindingLights.offsetAt(201_572))
        assertNull(blindingLights.offsetAt(201_573))
        assertNull(blindingLights.offsetAt(-1))
    }

    @Test
    fun primaryOffsetIsTheLongestSegments() {
        assertEquals(22_612L, blindingLights.primaryOffsetMs) // 127.5 s beats 74 s
        assertEquals(0L, SyncMap(emptyList()).primaryOffsetMs)
    }

    @Test
    fun constantMapCoversEverything() {
        val map = SyncMap.constant(22_500)
        assertEquals(22_500L, map.offsetAt(0))
        assertEquals(22_500L, map.offsetAt(10_000_000))
        assertTrue(map.covers(1))
        assertEquals(22_500L, map.primaryOffsetMs)
    }

    @Test
    fun segmentsAreSortedAndOverlapsTrimmed() {
        val map = SyncMap(
            listOf(
                SyncSegment(50_000, 90_000, 3_000),
                SyncSegment(0, 60_000, 1_000), // overlaps the segment above by 10 s
            ),
        )
        assertEquals(listOf(SyncSegment(0, 60_000, 1_000), SyncSegment(60_000, 90_000, 3_000)), map.segments)
        assertEquals(3_000L, map.offsetAt(75_000))
    }

    @Test
    fun aSegmentFullyInsideAnotherIsDropped() {
        val map = SyncMap(listOf(SyncSegment(0, 100, 1), SyncSegment(10, 50, 2)))
        assertEquals(listOf(SyncSegment(0, 100, 1)), map.segments)
    }

    @Test
    fun gapsHaveNoPicture() {
        val map = SyncMap(listOf(SyncSegment(0, 50_000, 1_000), SyncSegment(80_000, 120_000, 2_000)))
        assertFalse(map.covers(60_000))
        assertNull(map.offsetAt(60_000))
        assertEquals(1_000L, map.offsetNear(60_000)) // 10 s from the first segment's end, 20 s from the next start
        assertEquals(2_000L, map.offsetNear(70_000)) // now the second is closer
        assertEquals(2_000L, map.offsetNear(130_000)) // past the end: the last segment's offset
        assertEquals(1_000L, map.offsetNear(-5_000)) // before the start: the first segment's offset
    }

    @Test
    fun emptyMapCoversNothing() {
        val map = SyncMap(emptyList())
        assertTrue(map.isEmpty)
        assertNull(map.offsetAt(0))
        assertEquals(0L, map.offsetNear(5))
    }

    @Test
    fun aSegmentMustHaveALength() {
        assertFailsWith<IllegalArgumentException> { SyncSegment(100, 100, 0) }
        assertFailsWith<IllegalArgumentException> { SyncSegment(100, 50, 0) }
    }

    // --- the policy across an edit point ---------------------------------------------------------

    private val policy = CanvasSyncPolicy(blindingLights, videoDurationMs = 262_513)

    @Test
    fun targetUsesTheSegmentInForce() {
        assertEquals(60_000L + 22_612, policy.targetVideoMs(60_000))
        assertEquals(150_000L + 25_198, policy.targetVideoMs(150_000))
    }

    @Test
    fun inSyncInsideASegmentHolds() {
        val song = 60_000L
        val action = policy.decide(song, policy.targetVideoMs(song), songPlaying = true, videoReady = true)
        assertEquals(CanvasSyncAction.Nudge(1.0f), action)
    }

    @Test
    fun crossingAnEditPointSeeksByTheChangeOfOffset() {
        // the song has just passed 127.5 s; the video is still where the first segment put it
        val song = 127_600L
        val videoStillOnOldSegment = song + 22_612
        val action = policy.decide(song, videoStillOnOldSegment, songPlaying = true, videoReady = true)
        assertEquals(CanvasSyncAction.SeekTo(song + 25_198), action) // 2.586 s > hard seek threshold
    }

    @Test
    fun aGapInTheMapShowsTheStillArtwork() {
        val gappy = CanvasSyncPolicy(
            SyncMap(listOf(SyncSegment(0, 50_000, 1_000), SyncSegment(80_000, 120_000, 2_000))),
            videoDurationMs = 300_000,
        )
        assertFalse(gappy.isVisible(60_000))
        assertEquals(CanvasSyncAction.Hidden, gappy.decide(60_000, 61_000, songPlaying = true, videoReady = true))
        assertTrue(gappy.isVisible(90_000))
    }

    @Test
    fun theVideoStillEndsWhereItEnds() {
        val ending = CanvasSyncPolicy(SyncMap(listOf(SyncSegment(0, 400_000, 100_000))), videoDurationMs = 250_000)
        assertTrue(ending.isVisible(100_000))
        assertFalse(ending.isVisible(150_000)) // target 250 000 is the end
        assertEquals(CanvasSyncAction.Ended, ending.decide(150_000, 249_000, songPlaying = true, videoReady = true))
    }

    @Test
    fun theConstantOffsetConstructorStillWorks() {
        val constant = CanvasSyncPolicy(22_500, 262_000)
        assertEquals(22_500L, constant.offsetMs)
        assertEquals(30_000L + 22_500, constant.targetVideoMs(30_000))
        assertTrue(constant.isVisible(0))
        assertFalse(CanvasSyncPolicy(-5_000, 100_000).isVisible(1_000)) // before the video starts
    }
}
