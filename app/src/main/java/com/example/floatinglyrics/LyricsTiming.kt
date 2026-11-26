package com.example.floatinglyrics

internal object LyricsTiming {
    /** Returns the last line at or before position, or -1 before the first timestamp. */
    fun indexAt(lines: List<LyricLine>, position: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= position) low = middle + 1 else high = middle
        }
        return low - 1
    }

    fun popupText(loaded: Lyrics, activeLine: Int, translations: Map<String, String>, maxLines: Int): String {
        if (loaded.lines.isNotEmpty() && loaded.lines.getOrNull(activeLine)?.text.isNullOrBlank()) return "♪"
        val rows = if (loaded.lines.isEmpty()) loaded.plain.lineSequence()
            else loaded.lines.asSequence().drop(activeLine).map { it.text }
        return rows.map(String::trim).filter(String::isNotBlank).flatMap { original ->
            sequenceOf(original, translations[original]).filterNotNull()
        }.take(maxLines).joinToString("\n").ifBlank { "♪" }
    }
}
