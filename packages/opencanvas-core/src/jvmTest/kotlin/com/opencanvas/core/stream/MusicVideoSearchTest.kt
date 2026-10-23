package com.opencanvas.core.stream

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicVideoSearchTest {

    @Test
    fun unrelatedVerifiedArtistsDoNotSuppressATitleOnlyFilmSearch() = runBlocking {
        val queries = ArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val query = chain.request().url.queryParameter("search_query").orEmpty()
            queries += query
            val body = if (query == "\"Life Is Beautiful\" video song") {
                page(renderer("C-5VT5JxuEQ", "Life Is Beautiful Video Song - Life Is Beautiful Movie", "Aditya Music", "4:45", "BADGE_STYLE_TYPE_VERIFIED"))
            } else {
                page(renderer("1-t1I6PioKU", "Life is Beautiful - Deep Music (House) [Official Video]", "Deep Music", "3:27", "BADGE_STYLE_TYPE_VERIFIED_ARTIST"))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val found = MusicVideoSearch(client).find("Life Is Beautiful", "K.K", 323)
        assertTrue(found.any { it.videoId == "C-5VT5JxuEQ" })
        assertTrue("\"Life Is Beautiful\" video song" in queries)
    }

    @Test
    fun anotherSongFromASameNamedFilmDoesNotStopTheTitleTrackSearch() = runBlocking {
        val queries = ArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val query = chain.request().url.queryParameter("search_query").orEmpty()
            queries += query
            val body = if (query == "\"Life Is Beautiful\" video song") {
                page(renderer("C-5VT5JxuEQ", "Life Is Beautiful Video Song - Life Is Beautiful Movie", "Aditya Music", "4:45", "BADGE_STYLE_TYPE_VERIFIED"))
            } else {
                page(renderer("w9is6878qjA", "Beautiful Girl Full Video Song - Life is Beautiful Movie", "Aditya Music", "4:44", "BADGE_STYLE_TYPE_VERIFIED"))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val found = MusicVideoSearch(client).find("Life Is Beautiful", "K.K", 323, album = "Life Is Beautiful")
        assertTrue(found.any { it.videoId == "C-5VT5JxuEQ" })
        assertTrue(queries.size > 1)
    }

    private fun renderer(
        id: String,
        title: String,
        channel: String,
        length: String,
        badge: String? = null,
        url: String = "/watch?v=$id",
    ): String {
        val badges = badge?.let { """{"metadataBadgeRenderer":{"style":"$it"}}""" }.orEmpty()
        return """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"$channel"}]},""" +
            """"lengthText":{"simpleText":"$length"},"ownerBadges":[$badges],""" +
            """"navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"$url"}}}}}"""
    }

    private fun page(vararg items: String) =
        "<html><body><script>var ytInitialData = {\"contents\":{\"items\":[${items.joinToString(",")}]}};</script></body></html>"

    @Test
    fun aSongAudioHitDoesNotSuppressTheReversedFilmTitleFallback() = runBlocking {
        val queries = ArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val query = chain.request().url.queryParameter("search_query").orEmpty()
            queries += query
            val body = if (query.startsWith("\"Tappulu Tippulu\"")) {
                page(renderer("FILMVIDEO01", "Tappulu Tippulu Song 4K | Godavari", "TeluguOne Music", "4:20", "BADGE_STYLE_TYPE_VERIFIED"))
            } else {
                page(renderer("SONGAUDIO01", "Tippulu Tappulu", "Shreya Ghoshal", "4:23", "BADGE_STYLE_TYPE_VERIFIED_ARTIST"))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val found = MusicVideoSearch(client).find("Tippulu Tappulu", "Shreya Ghoshal", 263, album = "Godavari")
        assertTrue(found.any { it.videoId == "FILMVIDEO01" })
        assertEquals(2, queries.size)
    }

    @Test
    fun aMatchingPublisherVideoAvoidsSpeculativeFallbackRequests() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(page(renderer("OFFICIAL001", "Artist - Song Name (Official Video)", "Artist", "4:00", "BADGE_STYLE_TYPE_VERIFIED_ARTIST")).toResponseBody()).build()
        }.build()
        assertEquals("OFFICIAL001", MusicVideoSearch(client).find("Song Name", "Artist").first().videoId)
        assertEquals(1, calls)
    }

    @Test
    fun readsChannelLengthAndBadgeOfEveryVideoResult() {
        val html = page(
            renderer("AAAAAAAAAAA", "The Weeknd - Blinding Lights (Official Video)", "The Weeknd", "4:22", "BADGE_STYLE_TYPE_VERIFIED_ARTIST"),
            renderer("BBBBBBBBBBB", "A long interview", "Some Show", "1:02:05", "BADGE_STYLE_TYPE_VERIFIED"),
        )
        val found = MusicVideoSearch.parseSearchResults(html)
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), found.map { it.videoId })
        assertEquals("The Weeknd", found[0].channelTitle)
        assertEquals(262L, found[0].durationSec)
        assertEquals(3_725L, found[1].durationSec)
        assertTrue(found[0].isOfficialMusicVideo)
        assertTrue(!found[1].isOfficialMusicVideo)
    }

    @Test
    fun leavesShortsAndRepeatedResultsOut() {
        val html = page(
            renderer("SHORTSSHORT", "Blinding Lights #shorts", "Someone", "0:15", url = "/shorts/SHORTSSHORT"),
            renderer("AAAAAAAAAAA", "Blinding Lights", "The Weeknd", "4:22"),
            renderer("AAAAAAAAAAA", "Blinding Lights", "The Weeknd", "4:22"),
        )
        assertEquals(listOf("AAAAAAAAAAA"), MusicVideoSearch.parseSearchResults(html).map { it.videoId })
    }

    @Test
    fun aPageWithoutResultsOrJsonIsEmpty() {
        assertEquals(emptyList(), MusicVideoSearch.parseSearchResults("<html>consent</html>"))
        assertEquals(emptyList(), MusicVideoSearch.parseSearchResults("<script>var ytInitialData = {broken;</script>"))
    }

    @Test
    fun ranksTheOfficialVideoAboveAudioUploadsLiveTakesAndStageShows() {
        val candidates = listOf(
            VideoCandidate("AUDIO000001", "The Weeknd - Blinding Lights (Official Audio)", "The Weeknd", 204, true),
            VideoCandidate("STAGE000001", "BTS 'Dynamite' @ America's Got Talent 2020", "BANGTANTV", 202, true),
            VideoCandidate("LIVE0000001", "Blinding Lights (Live at the Super Bowl)", "NFL", 600),
            VideoCandidate("OFFICIAL001", "The Weeknd - Blinding Lights (Official Video)", "The Weeknd", 263, true),
        )
        val ranked = VideoMatcher.rank(candidates, "Blinding Lights", "The Weeknd", 200)
        assertEquals(listOf("OFFICIAL001"), ranked.map { it.videoId })
    }

    @Test
    fun theArtistsOwnChannelBeatsAReUploaderWithTheSameTitle() {
        val candidates = listOf(
            VideoCandidate("REUPLOAD001", "Luis Fonsi, Despacito ft Daddy Yankee (Official Video)", "DJ DY", 282, true),
            VideoCandidate("OWNCHANNEL1", "Luis Fonsi - Despacito ft. Daddy Yankee", "Luis Fonsi", 282, true),
        )
        assertEquals("OWNCHANNEL1", VideoMatcher.rank(candidates, "Despacito", "Luis Fonsi", 229).first().videoId)
    }

    @Test
    fun aWordOfTheSongsOwnTitleIsNotHeldAgainstTheVideo() {
        val candidates = listOf(VideoCandidate("LIVEFOREVER1", "Oasis - Live Forever (Official Video)", "Oasis", 276, true))
        assertEquals(1, VideoMatcher.rank(candidates, "Live Forever", "Oasis").size)
    }

    @Test
    fun theOfficialMvOutranksTheArtistsOwnBehindTheScenesAndSingAlongUploads() {
        val candidates = listOf(
            VideoCandidate("SINGWITHME01", "BTS Sing 'Dynamite' with me (feat. Big Hit staff)", "BANGTANTV", 200, true),
            VideoCandidate("EPISODE0001", "[EPISODE] BTS 'Dynamite' MV Shoot Sketch", "BANGTANTV", 343, true),
            VideoCandidate("OFFICIALMV01", "BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS", 224, false),
        )
        assertEquals(listOf("OFFICIALMV01"), VideoMatcher.rank(candidates, "Dynamite", "BTS", 199).map { it.videoId })
    }

    @Test
    fun aVideoSongTitleCountsAsAMusicVideo() {
        val candidates = listOf(
            VideoCandidate("VIDEOSONG001", "ButtaBomma Video Song (4K) (Telugu) | Allu Arjun", "Aditya Music", 194, false),
            VideoCandidate("DUBBED000001", "Butta Bomma song Hindi", "Icon movie", 216, false),
        )
        assertEquals("VIDEOSONG001", VideoMatcher.rank(candidates, "Butta Bomma", "Armaan Malik", 193).first().videoId)
    }

    @Test
    fun anyCreditedArtistsChannelCountsAndFanUploadsAndRemixesDoNot() {
        val candidates = listOf(
            VideoCandidate("FANREMIX001", "The Kid LAROI, Justin Bieber - Stay Remix feat. Juice WRLD, Post Malone & Ariana Grande (MV)", "JUSTIN BIEBER FAN MUSIC VIDEOS", 275, false),
            VideoCandidate("OFFICIAL002", "The Kid LAROI, Justin Bieber - STAY (Official Video)", "TheKidLAROIVEVO", 158, false),
        )
        val ranked = VideoMatcher.rank(candidates, "STAY", "The Kid LAROI, Justin Bieber", 142)
        assertEquals(listOf("OFFICIAL002"), ranked.map { it.videoId })
    }

    @Test
    fun aFullSongUploadIsAnAudioPosterNotTheMusicVideo() {
        val candidates = listOf(
            VideoCandidate("POSTER00001", "Nenu Nenuga Full Song ll Manmadhudu Songs ll Nagarjuna", "Aditya Music", 260, false),
            VideoCandidate("VIDEOSONG002", "Nenu Nenuga Lene Video Song | Manmadhudu", "Annapurna Studios", 260, false),
        )
        assertEquals(listOf("VIDEOSONG002"), VideoMatcher.rank(candidates, "Nenu Nenuga", "S.P.Charan", 262).map { it.videoId })
    }

    @Test
    fun aClipIsNeverTheMusicVideo() {
        val candidates = listOf(VideoCandidate("CLIP0000001", "Gangnam Style (Official Music Video)", "officialpsy", 21, true))
        assertEquals(emptyList(), VideoMatcher.rank(candidates, "Gangnam Style", "PSY"))
    }
}
