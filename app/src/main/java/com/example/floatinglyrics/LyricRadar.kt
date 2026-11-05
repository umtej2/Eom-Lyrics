package com.example.floatinglyrics

import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object LyricRadar {
    private const val BASE = "https://ls.1010diy.com"
    private val feature = Regex("""\s*[\[(]?\s*\b(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val explicit = Regex("""\s*[\[(]explicit[\])]\s*$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s*(?:,|·|、|＆|/|\+)\s*|\s+(?:및|and|&|×|x)\s+""", RegexOption.IGNORE_CASE)
    private val credits = Regex("""^(?:词|曲|编曲|作词|作曲|作詞|編曲|작사|작곡|편곡)\s*[:：]""")

    fun find(song: Song): Lyrics? {
        val title = cleanTitle(song.title)
        val artists = song.artist.split(separators).map(String::trim).filter(String::isNotEmpty)
        if (title.isBlank() || artists.isEmpty()) return null
        val artistKeys = artists.map(::artistKey).toMutableSet()
        val queries = mutableListOf<String>()
        val titles = (listOf(title) + title.split(" - ")).distinct().toMutableList()
        if ("backnumber" in artistKeys && titles.any { normalize(it) in setOf("emerald", "エメラルド") }) {
            titles.addAll(0, listOf("エメラルド", "Emerald"))
        }
        if (titles.any { normalize(it) == "브레인워시" } &&
            ("씨잼" in artistKeys || "블랙넛" in artistKeys ||
                normalize(song.artist).contains("씨잼블랙넛"))
        ) {
            artistKeys.addAll(listOf("씨잼", "블랙넛"))
            queries.add("브레인워시 씨잼")
        }
        if (titles.any { normalize(it) == "whoismoke" } &&
            artistKeys.any { it == "yungeenace" || it == "spinabenz" }
        ) {
            artistKeys.addAll(listOf("yungeenace", "spinabenz", "fastmoneygoon", "whoppawitdachoppa"))
            queries.add("Who I Smoke Yungeen Ace")
        }
        val titleKeys = titles.map(::normalize).toSet()
        queries.addAll(titles.distinct().take(2).map { "$it ${artists.first()}" })
        queries.add(titles.first())

        val seen = mutableSetOf<String>()
        var plainFallback: Lyrics? = null
        var lastError: IOException? = null
        for (query in queries.distinct().take(4)) {
            val items = try {
                data("songs", query) as? JSONArray ?: continue
            } catch (error: IOException) {
                lastError = error
                continue
            }
            val matches = (0 until minOf(items.length(), 20)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                if (listOf(item.optString("title"), item.optString("full_title"))
                        .none { normalize(cleanTitle(it)) in titleKeys }
                ) return@mapNotNull null
                val names = item.optJSONArray("str_artist") ?: item.optJSONArray("artist")
                val keys = if (names == null) item.optString("artist").split(separators).map(::artistKey)
                    else (0 until names.length()).map { artistKey(names.optString(it)) }
                val artistMatches = keys.distinct().count { it in artistKeys }
                if (artistMatches == 0) return@mapNotNull null
                val duration = item.optLong("duration")
                val delta = if (duration > 0 && song.durationMs > 0)
                    kotlin.math.abs(duration - song.durationMs) else 0L
                if (delta > 15000) return@mapNotNull null
                val info = item.optString("song_info").takeIf(String::isNotBlank) ?: return@mapNotNull null
                Candidate(info, delta, artistMatches)
            }.sortedWith(compareBy<Candidate> { it.delta > 7000 }.thenByDescending { it.artistMatches }
                .thenBy { it.delta })
            for (candidate in matches) {
                if (seen.size >= 6) return plainFallback
                if (!seen.add(candidate.info)) continue
                val lyrics = try {
                    data("lyrics", candidate.info) as? JSONObject ?: continue
                } catch (error: IOException) {
                    lastError = error
                    continue
                }
                val synced = Lrclib.parse(lyrics.optString("syncedLyrics"))
                    .filterNot { credits.containsMatchIn(it.text) }
                val plain = lyrics.optString("plainLyrics").takeUnless { lyrics.isNull("plainLyrics") }
                    .orEmpty().lineSequence().filterNot { credits.containsMatchIn(it) }.joinToString("\n").trim()
                    .ifBlank { synced.map { it.text }.filter(String::isNotBlank).joinToString("\n") }
                if (plain.isBlank()) continue
                if (candidate.delta <= 7000 && synced.count { it.text.isNotBlank() } >= 2) {
                    return Lyrics(synced, plain)
                }
                if (plainFallback == null) plainFallback = Lyrics(emptyList(), plain)
            }
        }
        return plainFallback ?: lastError?.let { throw it }
    }

    private data class Candidate(val info: String, val delta: Long, val artistMatches: Int)

    private fun data(path: String, keyword: String): Any? {
        val builder = Uri.parse("$BASE/$path").buildUpon().appendQueryParameter("keyword", keyword)
        if (path == "songs") builder.appendQueryParameter("page", "1")
        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "FloatingLyrics/1.0")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            return json.opt("data")
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanTitle(value: String): String = value.replace(feature, "").replace(explicit, "").trim()

    private fun artistKey(value: String): String = when (val key = normalize(value)) {
        "cjamm" -> "씨잼"
        "blacknut" -> "블랙넛"
        "whoppawitthachoppa" -> "whoppawitdachoppa"
        else -> key
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
