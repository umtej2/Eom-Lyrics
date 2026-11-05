package com.example.floatinglyrics

import android.view.accessibility.AccessibilityNodeInfo

internal data class PlayerScreenState(val expanded: Boolean, val playlistVisible: Boolean)

/** Checks player and queue controls only. Artwork, titles and coordinates are not used. */
internal object PlayerLayoutDetector {
    private val timeLabel = Regex("(?:\\d+:)?\\d{1,2}:\\d{2}")

    fun isExpandedPlayer(root: AccessibilityNodeInfo): Boolean = inspect(root).expanded

    fun inspect(root: AccessibilityNodeInfo): PlayerScreenState {
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
            hasTimeline = hasTimeline || id == "time_bar" || id.contains("seek_bar") ||
                id.contains("seekbar") || node.className?.toString() == "android.widget.SeekBar"
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
        val expanded = matchesExpandedPlayer(hasTimeline, hasPrevious, hasNext, hasCollapse, timeLabels)
        return PlayerScreenState(expanded, expanded && matchesVisiblePlaylist(
            hasQueueRows, hasQueueCollection, hasSelectedQueueTab, hasVisibleList
        ))
    }

    internal fun matchesExpandedPlayer(
        hasTimeline: Boolean, hasPrevious: Boolean, hasNext: Boolean,
        hasCollapse: Boolean, timeLabels: Int
    ): Boolean = hasTimeline &&
        (hasCollapse || (hasPrevious && hasNext && timeLabels >= 2))

    internal fun matchesVisiblePlaylist(
        hasQueueRows: Boolean, hasQueueCollection: Boolean,
        hasSelectedQueueTab: Boolean, hasVisibleList: Boolean
    ): Boolean = hasQueueRows || hasQueueCollection || (hasSelectedQueueTab && hasVisibleList)
}
