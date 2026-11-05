package com.example.floatinglyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistVisibilityTest {
    @Test fun collapsedUpNextTabDoesNotShrinkTheWindow() {
        assertFalse(PlayerLayoutDetector.matchesVisiblePlaylist(false, false, false, false))
        assertFalse(PlayerLayoutDetector.matchesVisiblePlaylist(false, false, true, false))
    }

    @Test fun unrelatedListDoesNotShrinkTheWindow() {
        assertFalse(PlayerLayoutDetector.matchesVisiblePlaylist(false, false, false, true))
    }

    @Test fun visibleQueueRowsOrCollectionUseHalfHeight() {
        assertTrue(PlayerLayoutDetector.matchesVisiblePlaylist(true, false, false, true))
        assertTrue(PlayerLayoutDetector.matchesVisiblePlaylist(false, true, false, true))
    }

    @Test fun selectedUpNextWithAnActualListUsesHalfHeight() {
        assertTrue(PlayerLayoutDetector.matchesVisiblePlaylist(false, false, true, true))
    }
}
