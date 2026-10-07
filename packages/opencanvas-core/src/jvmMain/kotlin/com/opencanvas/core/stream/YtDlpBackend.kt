package com.opencanvas.core.stream

import com.opencanvas.core.models.OpenCanvasResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Resolves video streams of any height with [yt-dlp](https://github.com/yt-dlp/yt-dlp) when it is
 * installed on the machine, which makes it the easiest way to a sharp 720p or 1080p canvas on the
 * desktop. It needs no server: the executable is started per lookup and, with [warmUp], once at
 * startup so its first real lookup does not pay for loading it.
 *
 * Only the desktop JVM build contains this class: a phone cannot run yt-dlp, so there the sharp stream
 * comes from the host app's own resolver. Either way the built-in client (see
 * `OpenCanvasConfig.instantBackend`) covers the instant start while this one works.
 *
 * @param command The command that starts yt-dlp, for example `listOf("python", "-m", "yt_dlp")`;
 *   null looks for `yt-dlp`, then for the Python module, on the `PATH`.
 */
class YtDlpBackend(private val command: List<String>? = null) : StreamBackend {

    private val resolvedCommand: List<String>? by lazy { command ?: locate() }

    override fun warmUp() {
        thread(isDaemon = true, name = "opencanvas-ytdlp-warmup") { resolvedCommand }
    }

    override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution): StreamInfo? =
        withContext(Dispatchers.IO) {
            val base = resolvedCommand ?: return@withContext null
            val height = resolution.maxHeight.coerceAtLeast(144)
            val width = resolution.maxWidth?.let { "[width<=$it]" }.orEmpty()
            val selector = "bv*[height<=$height]$width[ext=mp4]/bv*[height<=$height]$width/b[height<=$height]$width"
            val output = run(base + listOf("-f", selector, "-j", "--no-playlist", "--no-warnings", "https://www.youtube.com/watch?v=$videoId"))
                ?: return@withContext null
            parse(output)
        }

    private fun run(arguments: List<String>): String? = try {
        val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        if (process.exitValue() == 0) text else null
    } catch (_: IOException) {
        null // yt-dlp is not installed
    }

    private fun locate(): List<String>? = listOf(
        listOf("yt-dlp"),
        listOf("python", "-m", "yt_dlp"),
        listOf("python3", "-m", "yt_dlp"),
        listOf("py", "-m", "yt_dlp"),
    ).firstOrNull { candidate -> run(candidate + "--version") != null }

    internal companion object {
        private const val LOOKUP_TIMEOUT_SECONDS = 60L

        /** Reads the single-video JSON `yt-dlp -j` prints. */
        fun parse(output: String): StreamInfo? {
            val json = runCatching { Json.parseToJsonElement(output.trim().lineSequence().first { it.startsWith("{") }) as? JsonObject }.getOrNull() ?: return null
            val url = (json["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") } ?: return null
            val headers = (json["http_headers"] as? JsonObject)
                ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
                ?.toMap()
                .orEmpty()
            return StreamInfo(
                url = url,
                durationMs = ((json["duration"] as? JsonPrimitive)?.doubleOrNull?.times(1000.0))?.toLong() ?: 0L,
                width = (json["width"] as? JsonPrimitive)?.intOrNull ?: 0,
                height = (json["height"] as? JsonPrimitive)?.intOrNull ?: 0,
                headers = headers,
            )
        }
    }
}
