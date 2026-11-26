package com.example.floatinglyrics

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal data class PlayerScreenState(val expanded: Boolean, val playlistVisible: Boolean)

/** Checks player and queue controls only. Artwork, titles and coordinates are not used. */
internal object PlayerLayoutDetector {
    private val browseTabLabels = setOf("홈", "검색", "탐색", "보관함", "Home", "Search", "Explore", "Library")
    private val timeLabel = Regex("(?:\\d+:)?\\d{1,2}:\\d{2}")

    fun isExpandedPlayer(root: AccessibilityNodeInfo): Boolean = inspect(root).expanded

    fun inspect(root: AccessibilityNodeInfo): PlayerScreenState {
        val screen = Rect().also(root::getBoundsInScreen)
        val bounds = Rect()
        val bottomTabs = mutableSetOf<String>()
        var compactTimeline = false
        var hasTimeline = false
        var hasPrevious = false
        var hasNext = false
        var hasCollapse = false
        var timeLabels = 0
        var hasQueueRows = false
        var hasQueueCollection = false
        var hasSelectedQueueTab = false
        var hasVisibleList = false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            // Do not use controls from hidden player pages behind the current page.
            if (!node.isVisibleToUser) continue
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            val description = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            node.getBoundsInScreen(bounds)
            if (screen.height() > 0 && bounds.centerY() > screen.top + screen.height() * 0.85f) {
                val tab = listOf(text, description.substringBefore(',').trim()).firstOrNull {
                    it in browseTabLabels
                }
                if (tab != null) bottomTabs.add(tab)
            }
            val list = node.collectionInfo != null ||
                node.className?.toString()?.let { it.endsWith("RecyclerView") || it.endsWith("ListView") } == true
            val hasVisibleChildren = (0 until node.childCount).any {
                node.getChild(it)?.isVisibleToUser == true
            }
            hasVisibleList = hasVisibleList || (list && hasVisibleChildren)
            // A collapsed Up next tab is not a visible queue. Require actual
            // rows, a populated queue collection, or a selected tab plus a list.
            val queueId = id.contains("queue", ignoreCase = true)
            hasQueueCollection = hasQueueCollection || (queueId && list && hasVisibleChildren)
            hasQueueRows = hasQueueRows ||
                listOf("queue_item", "queue_list_item", "queue_track").any(id::contains) ||
                description.contains("remove from queue", ignoreCase = true) ||
                description.contains("재생목록에서 삭제") || description.contains("재생목록에서 제거")
            hasSelectedQueueTab = hasSelectedQueueTab || (node.isSelected &&
                (text.equals("Up next", ignoreCase = true) || text == "다음 트랙" || text == "재생목록" ||
                    description.equals("Up next", ignoreCase = true) || description == "다음 트랙"))
            val timeline = id == "time_bar" || id.contains("seek_bar") ||
                id.contains("seekbar") || node.className?.toString() == "android.widget.SeekBar"
            if (timeline) {
                hasTimeline = true
                // In the expanded queue the playback slider moves above the screen midpoint.
                if (screen.height() > screen.width() && bounds.width() > screen.width() * 0.6f &&
                    bounds.centerY() in (screen.top + screen.height() / 3)..screen.centerY()
                ) compactTimeline = true
            }
            hasPrevious = hasPrevious || id.contains("previous") ||
                description.contains("이전 곡") || description.contains("previous track", ignoreCase = true)
            hasNext = hasNext || id.contains("next_button") || id.contains("next_track") ||
                description.contains("다음 곡") || description.contains("next track", ignoreCase = true)
            hasCollapse = hasCollapse || id == "player_collapse_button"
            if (timeLabel.matches(node.text?.toString()?.trim().orEmpty())) timeLabels++
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::addLast)
            }
        }
        val expanded = matchesExpandedPlayer(hasTimeline, hasPrevious, hasNext, hasCollapse, timeLabels, bottomTabs.size)
        return PlayerScreenState(expanded, expanded && (compactTimeline || matchesVisiblePlaylist(
            hasQueueRows, hasQueueCollection, hasSelectedQueueTab, hasVisibleList
        )))
    }

    internal fun matchesExpandedPlayer(
        hasTimeline: Boolean, hasPrevious: Boolean, hasNext: Boolean,
        hasCollapse: Boolean, timeLabels: Int, bottomTabCount: Int = 0
    ): Boolean = bottomTabCount < 2 && hasTimeline &&
        (hasCollapse || (hasPrevious && hasNext && timeLabels >= 2))

    internal fun matchesVisiblePlaylist(
        hasQueueRows: Boolean, hasQueueCollection: Boolean,
        hasSelectedQueueTab: Boolean, hasVisibleList: Boolean
    ): Boolean = hasQueueRows || hasQueueCollection || (hasSelectedQueueTab && hasVisibleList)
}
