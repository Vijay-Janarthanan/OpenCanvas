package com.opencanvas.core.stream

import com.opencanvas.core.TestServer
import com.opencanvas.core.models.OpenCanvasResolution
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerTubeBackendTest {

    private val server = TestServer()

    @Before
    fun serve() {
        server.respond("/player") { _ -> 200 to PLAYER_RESPONSE.encodeToByteArray() }
    }

    @After
    fun stop() = server.close()

    private fun backend(clock: () -> Long = System::currentTimeMillis) =
        InnerTubeBackend(profile = InnerTubeClient.ANDROID, clock = clock, endpoint = "${server.baseUrl}/player")

    @Test
    fun readsOnlyTheStreamsThatCarryAPlainUrl() {
        val formats = assertNotNull(InnerTubeBackend.parsePlayerResponse(PLAYER_RESPONSE))
        assertEquals(listOf(18, 134), formats.map { it.itag })
        val muxed = formats.first()
        assertTrue(muxed.hasVideo && muxed.hasAudio)
        assertEquals(360, muxed.height)
        assertEquals(203_313L, muxed.durationMs)
        assertEquals(2_538_793L, muxed.contentLength)
    }

    @Test
    fun anUnplayableVideoHasNoStreams() {
        val body = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
        assertNull(InnerTubeBackend.parsePlayerResponse(body))
        assertNull(InnerTubeBackend.parsePlayerResponse("not json"))
    }

    @Test
    fun aPlayableVideoWithoutPlainUrlsGivesAnEmptyList() {
        val body = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","signatureCipher":"s=abc"}]}}"""
        assertEquals(emptyList(), InnerTubeBackend.parsePlayerResponse(body))
    }

    @Test
    fun prefersTheTallestVideoWithinTheResolutionAndVideoOnlyOverMuxed() {
        val formats = assertNotNull(InnerTubeBackend.parsePlayerResponse(PLAYER_RESPONSE))
        // 134 is video only at 360p as well; at the same height the silent one is the better canvas
        assertEquals(134, InnerTubeBackend.pickVideo(formats, OpenCanvasResolution.LOW_360P)?.itag)
        // nothing fits under 240p: the shortest available is used rather than none
        assertEquals(360, InnerTubeBackend.pickVideo(formats, OpenCanvasResolution(maxHeight = 240))?.height)
    }

    @Test
    fun readsAudioFramesFromTheSmallestProgressiveMp4() {
        val formats = assertNotNull(InnerTubeBackend.parsePlayerResponse(PLAYER_RESPONSE))
        assertEquals(18, InnerTubeBackend.pickAudio(formats)?.itag)
    }

    @Test
    fun resolvesVideoAndAudioOfTheSameIdWithOneRequestAndKeepsTheAnswer() = runBlocking {
        val backend = backend()
        val video = assertNotNull(backend.resolveVideo("VIDEOID0001", OpenCanvasResolution.LOW_360P))
        val audio = assertNotNull(backend.resolveAudio("VIDEOID0001"))
        backend.resolveVideo("VIDEOID0001", OpenCanvasResolution.LOW_360P)

        assertEquals(1, server.requests.count { it.startsWith("POST /player") })
        assertEquals(360, video.height)
        assertTrue(video.headers.getValue("User-Agent").startsWith("com.google.android.youtube/"))
        assertEquals(audio.url, "https://example.invalid/itag18")
    }

    @Test
    fun anExpiredAnswerIsFetchedAgain() = runBlocking {
        var now = 0L
        val backend = backend { now }
        backend.resolveAudio("VIDEOID0002")
        now += InnerTubeBackend.CACHE_TTL_MS + 1
        backend.resolveAudio("VIDEOID0002")
        assertEquals(2, server.requests.count { it.startsWith("POST /player") })
    }

    @Test
    fun aFailingEndpointYieldsNullNotAnException() = runBlocking {
        server.respond("/broken") { _ -> 500 to "oops".encodeToByteArray() }
        val backend = InnerTubeBackend(endpoint = "${server.baseUrl}/broken")
        assertNull(backend.resolveVideo("VIDEOID0003", OpenCanvasResolution.LOW_360P))
    }

    @Test
    fun theSharpTierPicksH264Within720pAndAsksForAVisitorTokenOnce() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        server.respond("/visitor") { _ -> 200 to """{"responseContext":{"visitorData":"VISITOR-TOKEN"}}""".encodeToByteArray() }
        server.respond("/sharp") { exchange ->
            seen += exchange.requestHeaders.getFirst("X-Goog-Visitor-Id").orEmpty() + "|" +
                exchange.requestHeaders.getFirst("X-YouTube-Client-Name").orEmpty() + "|" +
                exchange.requestBody.readBytes().decodeToString()
            200 to SHARP_RESPONSE.encodeToByteArray()
        }
        val backend = InnerTubeBackend(
            profile = InnerTubeClient.VISIONOS,
            endpoint = "${server.baseUrl}/sharp",
            visitorEndpoint = "${server.baseUrl}/visitor",
        )

        val first = assertNotNull(backend.resolveVideo("VIDEOID0004", OpenCanvasResolution.HD_720P))
        val second = assertNotNull(backend.resolveVideo("VIDEOID0005", OpenCanvasResolution.LOW_360P))

        assertEquals(1280, first.width)
        assertEquals(720, first.height)
        assertEquals("https://example.invalid/itag136", first.url) // H.264 over VP9 and AV1 at the same height
        assertEquals(360, second.height)
        assertEquals(1, server.requests.count { it.startsWith("POST /visitor") })
        assertTrue(seen.all { it.startsWith("VISITOR-TOKEN|101|") }, seen.toString())
        assertTrue(seen.first().contains("\"visitorData\":\"VISITOR-TOKEN\""))
        assertTrue(seen.first().contains("\"clientName\":\"VISIONOS\""))
        assertTrue(first.headers.getValue("User-Agent").contains("Macintosh"))
    }

    @Test
    fun aRefusedVisitorTokenIsReplacedOnce() = runBlocking {
        var tokens = 0
        server.respond("/visitor") { _ -> 200 to """{"responseContext":{"visitorData":"TOKEN-${++tokens}"}}""".encodeToByteArray() }
        server.respond("/sharp") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            if ("TOKEN-1" in body) 200 to """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""".encodeToByteArray()
            else 200 to SHARP_RESPONSE.encodeToByteArray()
        }
        val backend = InnerTubeBackend(profile = InnerTubeClient.VISIONOS, endpoint = "${server.baseUrl}/sharp", visitorEndpoint = "${server.baseUrl}/visitor")
        assertNotNull(backend.resolveVideo("VIDEOID0006", OpenCanvasResolution.HD_720P))
        assertEquals(2, tokens)
    }

    @Test
    fun withoutAVisitorTokenTheSharpTierGivesUpInsteadOfAskingBlind() = runBlocking {
        server.respond("/visitor") { _ -> 500 to "no".encodeToByteArray() }
        server.respond("/sharp") { _ -> 200 to SHARP_RESPONSE.encodeToByteArray() }
        val backend = InnerTubeBackend(profile = InnerTubeClient.VISIONOS, endpoint = "${server.baseUrl}/sharp", visitorEndpoint = "${server.baseUrl}/visitor")
        assertNull(backend.resolveVideo("VIDEOID0007", OpenCanvasResolution.HD_720P))
        assertEquals(0, server.requests.count { it.startsWith("POST /sharp") })
    }

    @Test
    fun anAudioOnlyMp4IsTheFallbackSyncSourceWhenThereIsNoProgressiveOne() {
        val formats = assertNotNull(InnerTubeBackend.parsePlayerResponse(SHARP_RESPONSE))
        val picked = assertNotNull(InnerTubeBackend.pickAudio(formats))
        assertEquals(139, picked.itag) // the smallest AAC stream; webm/opus cannot be read by the MP4 parser
    }

    private companion object {
        val SHARP_RESPONSE = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"lengthSeconds":"263"},
             "streamingData":{"adaptiveFormats":[
               {"itag":247,"url":"https://example.invalid/itag247","mimeType":"video/webm; codecs=\"vp9\"","width":1280,"height":720,"bitrate":1543298},
               {"itag":398,"url":"https://example.invalid/itag398","mimeType":"video/mp4; codecs=\"av01.0.05M.08\"","width":1280,"height":720,"bitrate":1134261},
               {"itag":136,"url":"https://example.invalid/itag136","mimeType":"video/mp4; codecs=\"avc1.4D401F\"","width":1280,"height":720,"bitrate":1075121},
               {"itag":134,"url":"https://example.invalid/itag134","mimeType":"video/mp4; codecs=\"avc1.4D401E\"","width":640,"height":360,"bitrate":446975},
               {"itag":251,"url":"https://example.invalid/itag251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":143140},
               {"itag":140,"url":"https://example.invalid/itag140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130445},
               {"itag":139,"url":"https://example.invalid/itag139","mimeType":"audio/mp4; codecs=\"mp4a.40.5\"","bitrate":49981}
             ]}}
        """.trimIndent()

        val PLAYER_RESPONSE = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"lengthSeconds":"203"},
             "streamingData":{
               "formats":[{"itag":18,"url":"https://example.invalid/itag18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                           "width":640,"height":360,"bitrate":108895,"contentLength":"2538793","approxDurationMs":"203313"}],
               "adaptiveFormats":[
                 {"itag":134,"url":"https://example.invalid/itag134","mimeType":"video/mp4; codecs=\"avc1.4D401E\"","width":640,"height":360,"bitrate":446975,"contentLength":"8678833"},
                 {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","width":1920,"height":1080,"bitrate":3546432,"signatureCipher":"s=abc&url=x"},
                 {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130445}
               ]}}
        """.trimIndent()
    }
}
