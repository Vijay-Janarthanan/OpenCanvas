package com.opencanvas.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the language-neutral vectors in `packages/conformance`, the same file the TypeScript and
 * Dart implementations are checked against, so the three agree with this one.
 */
class SyncVectorsTest {

    private val vectors: JsonObject = Json.parseToJsonElement(File("../conformance/sync-vectors.json").readText()).jsonObject

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long

    @Test
    fun mapsAndPolicyAgreeWithTheVectors() {
        var checks = 0
        for (case in vectors.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val map = SyncMap(
                case.getValue("segments").jsonArray.map {
                    val s = it.jsonObject
                    SyncSegment(s.long("songStartMs"), s.long("songEndMs"), s.long("offsetMs"), rate = s["rate"]?.jsonPrimitive?.double ?: 1.0)
                },
            )
            val tolerance = case.longOrNull("toleranceMs") ?: CanvasSyncDefaults.TOLERANCE_MS
            val hardSeek = case.longOrNull("hardSeekMs") ?: CanvasSyncDefaults.HARD_SEEK_MS
            val policy = CanvasSyncPolicy(map, case.long("videoDurationMs"), tolerance, hardSeek)

            for (o in case.getValue("offsetAt").jsonArray.map { it.jsonObject }) {
                val songMs = o.long("songMs")
                assertEquals(o.longOrNull("expect"), map.offsetAt(songMs), "$name: offsetAt($songMs)")
                checks++
            }
            for (d in case.getValue("decide").jsonArray.map { it.jsonObject }) {
                val songMs = d.long("songMs")
                val videoMs = d.long("videoMs")
                val playing = d.getValue("playing").jsonPrimitive.boolean
                val ready = d.getValue("ready").jsonPrimitive.boolean
                val got = policy.decide(songMs, videoMs, playing, ready)
                val expected = d.getValue("expect").jsonObject
                val label = "$name: decide($songMs, $videoMs, $playing, $ready)"
                when (val kind = expected.getValue("kind").jsonPrimitive.content) {
                    "hidden" -> assertEquals(CanvasSyncAction.Hidden, got, label)
                    "hold" -> assertEquals(CanvasSyncAction.Hold, got, label)
                    "ended" -> assertEquals(CanvasSyncAction.Ended, got, label)
                    "seek" -> assertEquals(CanvasSyncAction.SeekTo(expected.long("videoMs")), got, label)
                    "nudge" -> {
                        assertTrue(got is CanvasSyncAction.Nudge, "$label: was $got")
                        val speed = expected.getValue("speed").jsonPrimitive.double
                        assertTrue(abs((got as CanvasSyncAction.Nudge).speed - speed) < 1e-4, "$label: speed ${got.speed} vs $speed")
                    }
                    else -> fail("unknown action $kind")
                }
                checks++
            }
        }
        for (s in vectors.getValue("seekDetected").jsonArray.map { it.jsonObject }) {
            val policy = CanvasSyncPolicy(SyncMap(emptyList()), 0L)
            val got = policy.seekDetected(s.long("prevSongMs"), s.long("songMs"), s.long("elapsedWallMs"), s.getValue("playing").jsonPrimitive.boolean)
            assertEquals(s.getValue("expect").jsonPrimitive.boolean, got, "seekDetected $s")
            checks++
        }
        assertTrue(checks > 80, "the vectors file was read: $checks checks")
    }
}
