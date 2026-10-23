package com.opencanvas.core.stream

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import kotlin.test.*
import org.junit.Test
import java.nio.file.Files

class PublisherSelectionTest {
    @Test fun verifiedMusicPublisherDoesNotNeedTheLiteralWordsOfficialVideo() {
        val label = VideoCandidate("AAAAAAAAAAA", "Kala Chashma | Baar Baar Dekho", "Zee Music Company", 180, isVerifiedChannel = true)
        assertTrue(VideoMatcher.hasPublisherEvidence(label, "Kala Chashma", "Badshah"))
        assertFalse(VideoMatcher.hasPublisherEvidence(label.copy(channelTitle = "Dance Fitness Studio"), "Kala Chashma", "Badshah", "Baar Baar Dekho"))
    }
    @Test fun fanDanceCannotBecomeTheOfficialVideoByMatchingAudioOrTitle() {
        val dance = VideoCandidate("RRIYowOPF6s", "Tippulu Tappulu Full Video Song ll Godavari movie", "ch geetha", 276)
        assertFalse(VideoMatcher.hasPublisherEvidence(dance, "Tippulu Tappulu", "Shreya Ghoshal", "Godavari"))
    }

    @Test fun verifiedFilmPublisherHandlesSpacingAndAnEditedVideoLength() {
        val film = VideoCandidate("ZKI_a1D_79U", "Godavari Songs | Manasa Vacha | Sumanth", "TeluguOne", 239,
            isVerifiedChannel = true)
        assertTrue(VideoMatcher.hasPublisherEvidence(film, "Manasavacha", "Unni Krishnan, Chitra", "Godavari"))
        assertEquals(listOf(film), VideoMatcher.rank(listOf(film), "Manasavacha", "Unni Krishnan, Chitra", 288, album = "Godavari"))
    }

    @Test fun unrelatedVerifiedArtistDoesNotSupplyPublisherEvidenceForAnotherSong() {
        val unrelated = VideoCandidate("AAAAAAAAAAA", "Chethi Mandaram", "P Unnikrishnan Official", 214, true)
        assertFalse(VideoMatcher.hasPublisherEvidence(unrelated, "Manasavacha", "Unni Krishnan, Chitra", "Godavari"))
    }

    @Test fun oldAndCallerChosenMapsAreNotAutomaticSelections() {
        val folder = Files.createTempDirectory("selection-policy").toFile()
        try {
            val store = SyncStore(folder)
            val offset = SyncOffset(0, .8)
            store.put("song", "old-video", offset)
            assertNull(store.verifiedVideoFor("song"))
            store.put("song", "official-video", offset, verifiedSelection = true)
            assertEquals("official-video", store.verifiedVideoFor("song"))
            assertEquals("official-video", SyncStore(folder).verifiedVideoFor("song"))
        } finally { folder.deleteRecursively() }
    }
}
