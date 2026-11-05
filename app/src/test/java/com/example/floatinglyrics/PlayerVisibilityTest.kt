package com.example.floatinglyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVisibilityTest {
    @Test fun homeAndSearchDoNotShowPlayerButtons() {
        assertFalse(PlayerLayoutDetector.matchesExpandedPlayer(false, false, false, false, 0))
    }

    @Test fun miniPlayerProgressAndNextButtonAreNotEnough() {
        assertFalse(PlayerLayoutDetector.matchesExpandedPlayer(true, false, true, false, 0))
        assertFalse(PlayerLayoutDetector.matchesExpandedPlayer(true, true, true, false, 0))
    }

    @Test fun trackDurationLabelsWithoutAPlayerAreNotEnough() {
        assertFalse(PlayerLayoutDetector.matchesExpandedPlayer(false, true, true, false, 5))
    }

    @Test fun fullPlayerAndPlaylistCanKeepButtonsWithoutArtworkOrTitle() {
        assertTrue(PlayerLayoutDetector.matchesExpandedPlayer(true, false, true, true, 0))
        assertTrue(PlayerLayoutDetector.matchesExpandedPlayer(true, true, true, false, 2))
    }
}
