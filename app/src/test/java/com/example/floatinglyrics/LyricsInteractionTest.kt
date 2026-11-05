package com.example.floatinglyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsInteractionTest {
    @Test fun translatedAndWrappedTextKeepTheSameTimedRow() {
        val ranges = listOf(0 until 18, null, 20 until 48)
        assertEquals(0, LyricsInteraction.lineAt(15, ranges))
        assertEquals(2, LyricsInteraction.lineAt(45, ranges))
    }

    @Test fun spacingAndEmptyRowsNeverSeek() {
        val ranges = listOf(0 until 8, null, 12 until 19)
        assertNull(LyricsInteraction.lineAt(9, ranges))
        assertNull(LyricsInteraction.lineAt(-1, ranges))
        assertNull(LyricsInteraction.lineAt(19, ranges))
    }

    @Test fun queueExpansionShrinksTheAvailableHeaderWithoutCoveringTheTitle() {
        val full = PlayerRegion.aboveTitle(53, 1027, 270, 1320, 24, 80)!!
        val queue = PlayerRegion.aboveTitle(53, 1027, 270, 900, 24, 80)!!
        assertEquals(full.width, queue.width)
        assertTrue(queue.height < full.height)
        assertTrue(queue.bottom < 900)
    }

    @Test fun collapsedOrInvalidRegionsAreRejected() {
        assertNull(PlayerRegion.aboveTitle(53, 1027, 270, 300, 24, 80))
        assertNull(PlayerRegion.aboveTitle(100, 100, 0, 900, 24, 80))
    }
}
