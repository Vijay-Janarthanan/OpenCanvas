package com.opencanvas.core.manual

import com.opencanvas.core.*
import com.opencanvas.core.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FilmVideoManualTest {
    @Test fun filmVideosHavePublisherEvidenceAndMeasuredSync() = runBlocking {
        assumeTrue(System.getenv("OPENCANVAS_LIVE") == "true")
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = null, communityMapsUrl = null, log = ::println))
        for ((title, artist, audio) in listOf(
            Triple("Manasavacha", "Unni Krishnan, Chitra", "1R-gKCrq_HE"),
            Triple("Tippulu Tappulu", "Shreya Ghoshal", "ZDAT8DZi0jo"),
        )) {
            val result = withTimeout(35_000) { OpenCanvas.resolve(title, artist, 288,
                OpenCanvasMode.FULL_SYNCED_VIDEO, OpenCanvasResolution.HD_720P,
                trackVideoId = audio, album = "Godavari") }
            assertNotNull(result, title)
            assertEquals(SyncStage.MEASURED, result.syncStage, title)
            assertTrue(result.videoId != audio && result.videoId != "RRIYowOPF6s")
            println("VERIFIED FILM: $title -> ${result.videoId}, confidence=${result.syncConfidence}")
        }
    }
}
