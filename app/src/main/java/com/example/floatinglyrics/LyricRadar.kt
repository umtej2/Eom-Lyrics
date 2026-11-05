package com.example.floatinglyrics

import android.net.Uri
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object LyricRadar {
    private const val BASE = "https://ls.1010diy.com"
    private val credits = Regex("""^(?:词|曲|编曲|作词|作曲|作詞|編曲|작사|작곡|편곡)\s*[:：]""")

    suspend fun find(song: Song): Lyrics? {
        val profile = SongMatcher.profile(song)
        val main = profile.artists.firstOrNull() ?: return null
        val titles = profile.titles.take(2)
        if (titles.isEmpty()) return null
        val queries = (titles.map { "$it $main" } + titles.first()).distinct()
        val seen = mutableSetOf<String>()
        var plainFallback: Lyrics? = null
        var failure: IOException? = null
        for (query in queries) {
            val items = try {
                data("songs", query) as? JSONArray ?: continue
            } catch (error: IOException) {
                failure = error
                continue
            }
            val matches = (0 until minOf(items.length(), 20)).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val names = item.optJSONArray("str_artist") ?: item.optJSONArray("artist")
                val artist = if (names == null) item.optString("artist")
                    else (0 until names.length()).joinToString(", ") { names.optString(it) }
                val duration = item.optLong("duration")
                val match = listOf(item.optString("title"), item.optString("full_title")).mapNotNull {
                    SongMatcher.match(profile, it, artist, duration)
                }.maxByOrNull { it.score } ?: return@mapNotNull null
                val info = item.optString("song_info").takeIf(String::isNotBlank) ?: return@mapNotNull null
                Candidate(info, match)
            }.sortedWith(compareByDescending<Candidate> { it.match.canSync }.thenByDescending { it.match.score })
            for (candidate in matches) {
                if (seen.size >= 4) return plainFallback ?: failure?.let { throw it }
                if (!seen.add(candidate.info)) continue
                val result = try {
                    data("lyrics", candidate.info) as? JSONObject ?: continue
                } catch (error: IOException) {
                    failure = error
                    continue
                }
                val parsed = Lrclib.parse(result.optString("syncedLyrics"))
                    .filterNot { credits.containsMatchIn(it.text) }
                val plain = result.optString("plainLyrics").takeUnless { result.isNull("plainLyrics") }
                    .orEmpty().lineSequence().filterNot { credits.containsMatchIn(it) }.joinToString("\n").trim()
                    .ifBlank { parsed.map { it.text }.filter(String::isNotBlank).joinToString("\n") }
                if (plain.isNotBlank()) {
                    if (candidate.match.canSync && parsed.count { it.text.isNotBlank() } >= 2) {
                        return Lyrics(parsed, plain)
                    }
                    if (plainFallback == null) plainFallback = Lyrics(emptyList(), plain)
                }
                if (seen.size >= 4) return plainFallback
            }
        }
        return plainFallback ?: failure?.let { throw it }
    }

    private data class Candidate(val info: String, val match: SongMatcher.Match)

    private suspend fun data(path: String, keyword: String): Any? {
        val builder = Uri.parse("$BASE/$path").buildUpon().appendQueryParameter("keyword", keyword)
        if (path == "songs") builder.appendQueryParameter("page", "1")
        val json = JSONObject(LyricsHttp.get(builder.build().toString()))
        val code = json.optInt("code", 200)
        if (code != 0 && code != 200) throw IOException("LyricRadar status $code")
        return json.opt("data")
    }
}
