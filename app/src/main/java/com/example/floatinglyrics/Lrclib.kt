package com.example.floatinglyrics

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray

internal data class Song(val title: String, val artist: String, val durationMs: Long, val videoId: String? = null)
internal data class LyricLine(val timeMs: Long, val text: String)
internal data class Lyrics(val lines: List<LyricLine>, val plain: String)

internal object Lrclib {
    private val timestamp = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val feature = Regex("""\s*[\[(]?\s*\b(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val separator = Regex("""\s+(?:및|and|&|×|x)\s+|\s*,\s*""", RegexOption.IGNORE_CASE)

    fun find(song: Song): Lyrics? {
        val titles = song.title.split(" - ").map { it.replace(feature, "").trim() }
        val artists = song.artist.split(separator).map { it.replace(feature, "").trim() }
        if (titles.first().isBlank() || artists.first().isBlank()) return null

        val titleQueries = (listOf(song.title) + titles +
            alternateNames(titles, "マリーゴールド", "Marigold").takeIf {
                artists.any { normalize(it) in setOf("aimyon", "あいみょん") }
            }.orEmpty()).filter { it.isNotBlank() }.distinct()
        val artistQueries = (artists.take(2) +
            alternateNames(artists, "aimyon", "あいみょん")).distinct()
        val acceptedTitles = titleQueries.map(::normalize).toSet()
        val acceptedArtists = (listOf(song.artist) + artists + artistQueries).map(::normalize).toSet()
        var plainFallback: Pair<Lyrics, Double>? = null
        val queries = titleQueries.flatMap { title -> artistQueries.map { title to it } } +
            listOf("${titles.first()} ${artists.first()}" to null, titles.first() to null)
        for ((title, artistQuery) in queries.distinct()) {
            val results = try {
                search(title, artistQuery)
            } catch (error: java.io.IOException) {
                if (plainFallback != null) continue
                throw error
            }
            val candidates = (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                if (normalize(item.optString("trackName").replace(feature, "")) !in acceptedTitles) {
                    return@mapNotNull null
                }
                val artist = item.optString("artistName")
                val artistNames = listOf(artist) + artist.split(separator)
                if (artistNames.none { normalize(it.replace(feature, "")) in acceptedArtists }) {
                    return@mapNotNull null
                }
                val duration = item.optDouble("duration", 0.0)
                val difference = kotlin.math.abs(duration * 1000 - song.durationMs)
                if (song.durationMs > 0 && (duration <= 0 || difference > 7000)) return@mapNotNull null
                if (item.optBoolean("instrumental")) return@mapNotNull null

                val synced = item.optString("syncedLyrics", "").takeUnless { item.isNull("syncedLyrics") }.orEmpty()
                val lines = parse(synced)
                val plain = item.optString("plainLyrics", "").takeUnless { item.isNull("plainLyrics") }
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
                if (plain.isBlank()) return@mapNotNull null
                Lyrics(lines, plain) to difference
            }
            val best = candidates.minWithOrNull(
                compareBy<Pair<Lyrics, Double>> { it.first.lines.isEmpty() }.thenBy { it.second }
            )
            if (best != null) {
                if (best.first.lines.isNotEmpty()) return best.first
                if (best.second < (plainFallback?.second ?: Double.POSITIVE_INFINITY)) {
                    plainFallback = best
                }
            }
        }
        return plainFallback?.first
    }

    private fun alternateNames(names: List<String>, first: String, second: String): List<String> {
        val normalized = names.map(::normalize)
        return when {
            normalize(first) in normalized -> listOf(second)
            normalize(second) in normalized -> listOf(first)
            else -> emptyList()
        }
    }

    private fun search(title: String, artist: String?): JSONArray {
        val builder = Uri.parse("https://lrclib.net/api/search").buildUpon()
        if (artist == null) builder.appendQueryParameter("q", title)
        else builder.appendQueryParameter("track_name", title).appendQueryParameter("artist_name", artist)
        val url = builder.build()
        val connection = URL(url.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Lrclib-Client", "Floating Lyrics Android")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
        } finally {
            connection.disconnect()
        }
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

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
}





