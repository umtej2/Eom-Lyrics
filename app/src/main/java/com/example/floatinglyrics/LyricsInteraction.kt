package com.example.floatinglyrics

internal object LyricsInteraction {
    fun lineAt(offset: Int, ranges: List<IntRange?>): Int? =
        ranges.indexOfFirst { it != null && offset in it }.takeIf { it >= 0 }
}

internal data class PlayerRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun aboveTitle(
            left: Int, right: Int, safeTop: Int, titleTop: Int, gap: Int, minimumHeight: Int
        ): PlayerRegion? {
            val bottom = titleTop - gap
            if (right <= left || bottom - safeTop < minimumHeight) return null
            return PlayerRegion(left, safeTop, right, bottom)
        }
    }
}
