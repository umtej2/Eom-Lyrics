package com.example.floatinglyrics

import android.net.Uri
import org.json.JSONArray

internal data class Song(val title: String, val artist: String, val durationMs: Long, val videoId: String? = null)
internal data class LyricLine(val timeMs: Long, val text: String)
internal data class Lyrics(val lines: List<LyricLine>, val plain: String)

internal object Lrclib {
    private val timestamp = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    suspend fun find(song: Song): Lyrics? {
        val profile = SongMatcher.profile(song)
        if (profile.titles.isEmpty() || profile.artists.isEmpty()) return null
        val main = profile.artists.first()
        val queries = buildList<Pair<String, String?>> {
            add(profile.titles.first() to main)
            profile.titles.drop(1).take(1).forEach { add(it to main) }
            profile.artists.drop(1).take(1).forEach { add(profile.titles.first() to it) }
            add(profile.titles.first() to null)
        }.distinct()
        var plainFallback: Lyrics? = null
        for ((title, artist) in queries) {
            val results = try {
                search(title, artist)
            } catch (error: java.io.IOException) {
                if (plainFallback != null) return plainFallback
                throw error
            }
            val candidates = (0 until minOf(results.length(), 50)).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                if (item.optBoolean("instrumental")) return@mapNotNull null
                val duration = item.optDouble("duration", 0.0)
                val match = SongMatcher.match(profile, item.optString("trackName"),
                    item.optString("artistName"), (duration * 1000).toLong()) ?: return@mapNotNull null
                val synced = item.optString("syncedLyrics").takeUnless { item.isNull("syncedLyrics") }.orEmpty()
                val parsed = parse(synced)
                val lines = if (match.canSync) parsed else emptyList()
                val plain = item.optString("plainLyrics").takeUnless { item.isNull("plainLyrics") }
                    ?.trim()?.takeIf(String::isNotEmpty)
                    ?: parsed.map { it.text }.filter(String::isNotBlank).joinToString("\n")
                if (plain.isBlank()) return@mapNotNull null
                Lyrics(lines, plain) to match.score
            }.sortedWith(compareByDescending<Pair<Lyrics, Int>> { it.first.lines.isNotEmpty() }
                .thenByDescending { it.second })
            val best = candidates.firstOrNull()?.first ?: continue
            if (best.lines.isNotEmpty()) return best
            if (plainFallback == null) plainFallback = best
        }
        return plainFallback
    }

    private suspend fun search(title: String, artist: String?): JSONArray {
        val builder = Uri.parse("https://lrclib.net/api/search").buildUpon()
        if (artist == null) builder.appendQueryParameter("q", title)
        else builder.appendQueryParameter("track_name", title).appendQueryParameter("artist_name", artist)
        return JSONArray(LyricsHttp.get(builder.build().toString()))
    }

    internal fun parse(text: String): List<LyricLine> {
        val adjustment = offset.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return text.lineSequence().flatMap { line ->
            val stamps = timestamp.findAll(line).toList()
            val lyric = timestamp.replace(line, "").trim()
            stamps.asSequence().mapNotNull { stamp ->
                val minutes = stamp.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = stamp.groupValues[2].toLong()
                val millis = stamp.groupValues[3].padEnd(3, '0').toLong()
                LyricLine((minutes * 60000 + seconds * 1000 + millis - adjustment).coerceAtLeast(0), lyric)
            }
        }.sortedBy { it.timeMs }.toList()
    }

}
