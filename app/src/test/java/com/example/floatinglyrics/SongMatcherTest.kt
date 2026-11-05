package com.example.floatinglyrics

import org.junit.Assert.*
import org.junit.Test

class SongMatcherTest {
    @Test fun featurePunctuationAndEnglishArtistNameDoNotHideDomesticLyrics() {
        val requested = SongMatcher.profile(Song("Red Wine (feat. Gist)", "unofficialboyy", 200000))
        assertNotNull(SongMatcher.match(requested, "Red Wine (Feat.Gist)", "unofficialboyy", 201000))
        assertNotNull(SongMatcher.match(SongMatcher.profile(Song("브레인워시", "씨잼 및 블랙넛", 154000)),
            "브레인워시", "희대의, C Jamm, Black Nut", 154245))
    }

    @Test fun bilingualTitlesAndKnownArtistAliasesMatch() {
        val requested = SongMatcher.profile(Song("マリーゴールド - Marigold", "aimyon", 307000))
        assertNotNull(SongMatcher.match(requested, "マリーゴールド", "あいみょん", 307000))
        assertNotNull(SongMatcher.match(SongMatcher.profile(Song("Emerald", "back number", 223000)),
            "エメラルド", "back number", 223000))
    }

    @Test fun recordingVersionsAndUnrelatedArtistsAreRejected() {
        val requested = SongMatcher.profile(Song("Through The Wire", "Kanye West", 222000))
        assertNull(SongMatcher.match(requested, "Through The Wire (Live)", "Kanye West", 222000))
        assertNull(SongMatcher.match(requested, "Through The Wire (Remix)", "Kanye West", 222000))
        assertNull(SongMatcher.match(requested, "Through The Wire", "Someone Else", 222000))
        assertNull(SongMatcher.match(requested, "Through The Wire", "Kanye West", 270000))
    }

    @Test fun knownCollaborationCanBeIndexedUnderAnotherParticipatingArtist() {
        val requested = SongMatcher.profile(Song("Who I Smoke", "Yungeen Ace", 152000))
        assertNotNull(SongMatcher.match(requested, "Who I Smoke", "Spinabenz", 152000))
        assertNull(SongMatcher.match(requested, "Who I Smoke", "Someone Else", 152000))
    }

    @Test fun featureRemovalDoesNotRemoveFollowingVersionTags() {
        val requested = SongMatcher.profile(Song("Song (Feat. Guest) (Live)", "Artist", 200000))
        assertNotNull(SongMatcher.match(requested, "Song (Live)", "Artist", 200000))
        assertNull(SongMatcher.match(requested, "Song", "Artist", 200000))
    }

    @Test fun onlyNearDurationAndSameMainArtistAllowMinorTypos() {
        val requested = SongMatcher.profile(Song("Through The Wire", "Kanye West", 222000))
        assertNotNull(SongMatcher.match(requested, "Through The Wir", "Kanye West", 222000))
        assertNull(SongMatcher.match(requested, "Through The Wir", "Kanye West", 240000))
        assertNull(SongMatcher.match(requested, "Through The Wir", "Kanye West"))
    }

    @Test fun longerVideoCanUsePlainLyricsWithoutApplyingWrongTimestamps() {
        val requested = SongMatcher.profile(Song("Song", "Artist", 220000))
        val match = SongMatcher.match(requested, "Song", "Artist", 200000)
        assertNotNull(match)
        assertFalse(match!!.canSync)
    }

    @Test fun lateVideoIdAndSmallMetadataChangesDoNotRestartLookup() {
        val initial = Song("Track", "Artist", 200000)
        assertTrue(SongMatcher.sameRecording(initial, initial.copy(durationMs = 200500, videoId = "abcdefghijk")))
        assertFalse(SongMatcher.sameRecording(initial.copy(videoId = "abcdefghijk"),
            initial.copy(videoId = "12345678901")))
    }

    @Test fun collaboratorOrderSharesARecordingCacheKey() {
        assertEquals(SongMatcher.recordingKey(Song("Track", "Artist & Guest", 200000)),
            SongMatcher.recordingKey(Song("Track", "Guest 및 Artist", 200000)))
    }
}

class LyricsTimingTest {
    @Test fun duplicateTimestampsSelectTheLastRowAndNextDistinctBoundary() {
        val rows = listOf(LyricLine(1000, "one"), LyricLine(1000, "two"), LyricLine(3000, "three"))
        assertEquals(-1, LyricsTiming.indexAt(rows, 999))
        assertEquals(1, LyricsTiming.indexAt(rows, 1000))
        assertEquals(1, LyricsTiming.indexAt(rows, 2999))
        assertEquals(2, LyricsTiming.indexAt(rows, 3000))
        assertEquals(2, LyricsTiming.indexAt(rows, Long.MAX_VALUE))
        assertEquals(-1, LyricsTiming.indexAt(emptyList(), 100))
    }
}
