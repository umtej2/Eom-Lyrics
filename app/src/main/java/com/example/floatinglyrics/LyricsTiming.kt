package com.example.floatinglyrics

internal object LyricsTiming {
    fun indexAt(lines: List<LyricLine>, position: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= position) low = middle + 1 else high = middle
        }
        return low - 1
    }
}
