package com.example.floatinglyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupLyricsTest {
    @Test fun instrumentalBreakLastsUntilTheNextTimestamp() {
        val lyrics = Lyrics(listOf(LyricLine(0, "First"), LyricLine(1000, " "), LyricLine(3000, "Next")), "")
        fun at(position: Long) = LyricsTiming.popupText(
            lyrics, LyricsTiming.indexAt(lyrics.lines, position), emptyMap(), 1
        )
        assertEquals("First", at(999))
        assertEquals("♪", at(1000))
        assertEquals("♪", at(2999))
        assertEquals("Next", at(3000))
    }
}
