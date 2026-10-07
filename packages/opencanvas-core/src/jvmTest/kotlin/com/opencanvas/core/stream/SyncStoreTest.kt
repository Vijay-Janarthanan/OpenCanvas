package com.opencanvas.core.stream

import com.opencanvas.core.sync.SyncSegment
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStoreTest {

    private val directory: File = Files.createTempDirectory("opencanvas-store").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun offset(ms: Long, segments: List<SyncSegment> = emptyList()) = SyncOffset(offsetMs = ms, confidence = 0.9, segments = segments, coverage = 0.95)

    @Test
    fun keyIsTrackPipeVideo() {
        assertEquals("track123|video456", SyncStore.key("track123", "video456"))
    }

    @Test
    fun storesAndReturnsPerPairInMemory() {
        val store = SyncStore()
        assertNull(store["t1", "v1"])
        store.put("t1", "v1", offset(100))
        store.put("t1", "v2", offset(200))
        store.put("t2", "v1", offset(300))
        assertEquals(100L, store["t1", "v1"]?.offsetMs)
        assertEquals(200L, store["t1", "v2"]?.offsetMs)
        assertEquals(300L, store["t2", "v1"]?.offsetMs)
        assertEquals(3, store.size)
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val store = SyncStore(capacity = 2)
        store.put("a", "v", offset(1))
        store.put("b", "v", offset(2))
        store["a", "v"] // a is now the most recently used
        store.put("c", "v", offset(3))
        assertEquals(2, store.size)
        assertEquals(1L, store["a", "v"]?.offsetMs)
        assertNull(store["b", "v"])
    }

    @Test
    fun persistsAMapAcrossInstances() {
        val segments = listOf(SyncSegment(0L, 127_500L, 22_612L), SyncSegment(127_500L, 201_600L, 25_198L))
        SyncStore(directory).put("J7p4bzqLvCw", "4NRXx6U8ABQ", offset(22_612L, segments))

        val reloaded = SyncStore(directory)["J7p4bzqLvCw", "4NRXx6U8ABQ"]
        assertEquals(22_612L, reloaded?.offsetMs)
        assertEquals(segments, reloaded?.segments)
        assertEquals(0.95, reloaded?.coverage)
    }

    @Test
    fun remembersWhichVideoWasMeasuredForASong() {
        SyncStore(directory).put("song_1", "video-1", offset(5))
        assertEquals("video-1", SyncStore(directory).videoFor("song_1"))
        assertNull(SyncStore(directory).videoFor("other"))
        assertNull(SyncStore().videoFor("song_1"))
    }

    @Test
    fun neverTurnsAnUnsafeIdIntoAFileName() {
        val store = SyncStore(directory)
        store.put("../evil", "v", offset(1))
        store.put("a/b", "v", offset(1))
        assertTrue(directory.listFiles().isNullOrEmpty())
        assertEquals(1L, store["../evil", "v"]?.offsetMs) // still served from memory
    }

    @Test
    fun ignoresDamagedFiles() {
        directory.resolve("t_v.json").writeText("{not json")
        assertNull(SyncStore(directory)["t", "v"])
        assertNull(SyncStore(directory).videoFor("t"))
    }

    @Test
    fun clearRemovesMemoryAndFiles() {
        val store = SyncStore(directory)
        store.put("t", "v", offset(1))
        store.clear()
        assertNull(store["t", "v"])
        assertFalse(directory.resolve("t_v.json").exists())
    }
}
