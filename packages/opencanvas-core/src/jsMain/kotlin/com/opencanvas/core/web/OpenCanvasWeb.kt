package com.opencanvas.core.web

import com.opencanvas.core.sync.CanvasSyncAction
import com.opencanvas.core.sync.CanvasSyncPolicy
import com.opencanvas.core.sync.FrameEnvelope
import com.opencanvas.core.sync.Mp4FrameSizes
import com.opencanvas.core.sync.RangeReader
import com.opencanvas.core.sync.SyncAligner
import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.Date
import kotlin.js.Promise

/**
 * Where the engine reads a file from: a size and a way to read a byte range. In a browser that is a
 * `Blob` (`file.slice(from, from + count).arrayBuffer()`) or an HTTP range request.
 */
external interface JsRangeReader {
    val length: Double
    fun read(from: Double, count: Int): Promise<JsAny?>
}

/** What measuring a song against a music video found, as JSON for the page. */
@Serializable
class WebMeasurement(
    val ok: Boolean,
    val error: String?,
    val offsetMs: Long,
    val confidence: Double,
    val coverage: Double,
    val segments: List<SyncSegment>,
    val songSeconds: Double,
    val videoSeconds: Double,
    val videoKbps: Double,
    val videoMotion: Double,
    /** True for a poster or slideshow with the song over it: not a music video. */
    val stillPicture: Boolean,
    val songBytesRead: Long,
    val videoBytesRead: Long,
    val readMs: Long,
    val alignMs: Long,
)

private class CountingReader(private val source: JsRangeReader) : RangeReader {
    var bytesRead = 0L
        private set

    override val length: Long get() = source.length.toLong()

    override suspend fun read(from: Long, count: Int): ByteArray {
        val chunk = source.read(from.toDouble(), count).await() ?: return ByteArray(0)
        val bytes = toBytes(chunk)
        bytesRead += bytes.size
        return bytes
    }

    // an ArrayBuffer or any typed array from the page, viewed as bytes without a copy
    private fun toBytes(chunk: JsAny): ByteArray {
        val a = chunk.asDynamic()
        val buffer = if (a.buffer != undefined) a.buffer else a
        val offset: Int = if (a.byteOffset != undefined) (a.byteOffset as Number).toInt() else 0
        val size: Int = (a.byteLength as Number).toInt()
        return js("new Int8Array(buffer, offset, size)").unsafeCast<ByteArray>()
    }
}

private val json = Json { encodeDefaults = true }

// The same limits the apps use to refuse a still picture with a soundtrack (CanvasSession).
private const val STILL_PICTURE_KBPS = 40.0
private const val STILL_PICTURE_MOTION = 0.12

/**
 * Reads the frame tables of the song's and the video's MP4 headers (a few hundred kilobytes each,
 * whatever the file's size), aligns them and resolves to a [WebMeasurement] as JSON. Rejects when a
 * file has no readable AAC audio. The classes underneath are the ones Android and the desktop run;
 * [WebCanvasPolicy] then keeps a `<video>` on the song.
 */
@OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)
@JsExport
fun measure(song: JsRangeReader, video: JsRangeReader): Promise<String> = GlobalScope.promise {
    val songReader = CountingReader(song)
    val videoReader = CountingReader(video)
    val started = Date().getTime()
    val (songFrames, videoFrames) = coroutineScope {
        val a = async { Mp4FrameSizes.read(songReader) }
        val b = async { Mp4FrameSizes.read(videoReader) }
        a.await() to b.await()
    }
    val readMs = (Date().getTime() - started).toLong()
    if (songFrames == null) throw IllegalStateException("The song has no readable AAC audio in an MP4/M4A container.")
    if (videoFrames == null) throw IllegalStateException("The video has no readable AAC audio in an MP4 container.")
    val alignStarted = Date().getTime()
    val align = SyncAligner.align(FrameEnvelope.build(songFrames), FrameEnvelope.build(videoFrames), FrameEnvelope.RATE_HZ)
    val alignMs = (Date().getTime() - alignStarted).toLong()
    val still = (videoFrames.videoKbps in 0.01..STILL_PICTURE_KBPS) || videoFrames.videoMotion < STILL_PICTURE_MOTION
    json.encodeToString(
        WebMeasurement(
            ok = align.error == null && align.segments.isNotEmpty() && !still,
            error = align.error ?: if (still) "a still picture with a soundtrack, not a music video" else null,
            offsetMs = align.offsetMs,
            confidence = align.confidence,
            coverage = align.coverage,
            segments = align.segments,
            songSeconds = songFrames.sizes.size * songFrames.frameSeconds,
            videoSeconds = videoFrames.sizes.size * videoFrames.frameSeconds,
            videoKbps = videoFrames.videoKbps,
            videoMotion = videoFrames.videoMotion,
            stillPicture = still,
            songBytesRead = songReader.bytesRead,
            videoBytesRead = videoReader.bytesRead,
            readMs = readMs,
            alignMs = alignMs,
        ),
    )
}

/**
 * Keeps a video on the song: the page asks [decide] every 250 ms (and at once after a seek) and
 * does what the answer says. The answer is JSON: `{"kind":"seek","videoMs":N}`, `{"kind":"nudge",
 * "speed":S}`, `{"kind":"hold"}`, `{"kind":"hidden"}` or `{"kind":"ended"}`.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
class WebCanvasPolicy(segmentsJson: String, videoDurationMs: Double, toleranceMs: Double = 150.0, hardSeekMs: Double = 500.0) {
    private val policy = CanvasSyncPolicy(
        SyncMap(json.decodeFromString<List<SyncSegment>>(segmentsJson)),
        videoDurationMs.toLong(),
        toleranceMs.toLong(),
        hardSeekMs.toLong(),
    )

    fun decide(songMs: Double, videoMs: Double, songPlaying: Boolean, videoReady: Boolean): String =
        when (val action = policy.decide(songMs.toLong(), videoMs.toLong(), songPlaying, videoReady)) {
            CanvasSyncAction.Hidden -> """{"kind":"hidden"}"""
            CanvasSyncAction.Hold -> """{"kind":"hold"}"""
            CanvasSyncAction.Ended -> """{"kind":"ended"}"""
            is CanvasSyncAction.SeekTo -> """{"kind":"seek","videoMs":${action.videoMs}}"""
            is CanvasSyncAction.Nudge -> """{"kind":"nudge","speed":${action.speed}}"""
        }

    /** Where the video should be when the song is at [songMs] (the nearest stretch's offset in a gap). */
    fun targetVideoMs(songMs: Double): Double = policy.targetVideoMs(songMs.toLong()).toDouble()

    /** Whether the song has a matching picture at [songMs]. */
    fun isVisible(songMs: Double): Boolean = policy.isVisible(songMs.toLong())

    /** The song moved by more than wall time explains: the user scrubbed or skipped. */
    fun seekDetected(prevSongMs: Double, songMs: Double, elapsedWallMs: Double, playing: Boolean): Boolean =
        policy.seekDetected(prevSongMs.toLong(), songMs.toLong(), elapsedWallMs.toLong(), playing)
}
