package com.opencanvas.core.stream

import com.opencanvas.core.heatmap.HeatmapParser
import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.regex.Pattern

/**
 * Lightweight client-side YouTube stream and metadata resolver.
 * Queries search and InnerTube player endpoints directly with zero backend.
 */
object YouTubeStreamResolver {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(15))
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Searches YouTube for candidate videos and picks the best Official Music Video (OMV).
     */
    fun findOfficialVideo(
        title: String,
        artist: String,
        durationSec: Long = 0L,
    ): VideoCandidate? {
        val query = "$artist $title official music video"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://www.youtube.com/results?search_query=$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull() ?: return null

        val candidates = parseSearchHtml(html)
        return VideoMatcher.pickBest(candidates, title, artist, durationSec)
    }

    /**
     * Fetches the raw InnerTube player response containing playback markers and stream formats.
     */
    fun fetchPlayerData(videoId: String): String? {
        val url = "https://www.youtube.com/youtubei/v1/player"
        val payload = """
            {
                "context": {
                    "client": {
                        "clientName": "WEB",
                        "clientVersion": "2.20240313.05.00",
                        "hl": "en",
                        "gl": "US"
                    }
                },
                "videoId": "$videoId"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /**
     * Resolves the best 8-12s loop window for a video using its engagement heatmap.
     */
    fun resolveLoopWindow(
        videoId: String,
        videoDurationMs: Long,
        preferredLoopMs: Long = 10000L,
    ): Pair<Long, Long> {
        val playerJson = fetchPlayerData(videoId)
        return if (playerJson != null) {
            HeatmapParser.findPeakLoopWindow(playerJson, videoDurationMs, preferredLoopMs)
        } else {
            // Fallback: 40% into video
            val start = (videoDurationMs * 0.40).toLong()
            start to (start + preferredLoopMs).coerceAtMost(videoDurationMs)
        }
    }

    private fun parseSearchHtml(html: String): List<VideoCandidate> {
        val candidates = mutableListOf<VideoCandidate>()
        val pattern = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>")
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return emptyList()

        val jsonStr = matcher.group(1) ?: return emptyList()

        // Simple regex fallback to extract video IDs and titles
        val videoPattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
        val titlePattern = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}\\]")

        val vMatcher = videoPattern.matcher(jsonStr)
        val tMatcher = titlePattern.matcher(jsonStr)

        val seen = HashSet<String>()
        while (vMatcher.find() && candidates.size < 6) {
            val vid = vMatcher.group(1) ?: continue
            if (seen.add(vid)) {
                val title = if (tMatcher.find()) tMatcher.group(1).orEmpty() else "Video"
                candidates.add(
                    VideoCandidate(
                        videoId = vid,
                        title = title,
                        channelTitle = "",
                        durationSec = 0L,
                        isOfficialMusicVideo = title.contains("Official", ignoreCase = true)
                    )
                )
            }
        }
        return candidates
    }

    /**
     * Resolves a direct playable MP4 video stream URL using yt-dlp at the requested [resolution].
     */
    fun resolveVideoStreamUrl(
        videoId: String,
        resolution: com.opencanvas.core.models.OpenCanvasResolution = com.opencanvas.core.models.OpenCanvasResolution.STANDARD_480P,
    ): String? {
        val maxH = resolution.maxHeight
        val maxW = resolution.maxWidth ?: 0
        val endpoints = listOf(
            "http://127.0.0.1:18999/resolve?v=$videoId&max_height=$maxH&max_width=$maxW",
            "http://10.0.2.2:18999/resolve?v=$videoId&max_height=$maxH&max_width=$maxW",
        )
        for (ep in endpoints) {
            val streamUrl = runCatching {
                val req = Request.Builder().url(ep).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val match = Pattern.compile("\"streamUrl\"\\s*:\\s*\"(http[^\"]+)\"").matcher(body)
                        if (match.find()) match.group(1)?.replace("\\/", "/") else null
                    } else null
                }
            }.getOrNull()
            if (!streamUrl.isNullOrBlank()) return streamUrl
        }

        // Direct CLI fallback on Desktop
        val cliStreamUrl = runCatching {
            val pb = ProcessBuilder(
                "python", "-m", "yt_dlp", "-g",
                "-f", resolution.toYtDlpFormat(),
                "https://www.youtube.com/watch?v=$videoId"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            output.lines().firstOrNull { it.startsWith("http") }
        }.getOrNull()

        if (!cliStreamUrl.isNullOrBlank()) return cliStreamUrl

        return null
    }
}
