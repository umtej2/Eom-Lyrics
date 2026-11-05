package com.example.floatinglyrics

import android.net.Uri
import android.text.Html
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

internal object BugsLyrics {
    private const val BASE = "https://music.bugs.co.kr"
    private val rows = Regex("""<tr\b[^>]*\btrackId="(\d+)"[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val title = Regex("""<p class="title"[^>]*>\s*<a[^>]*\btitle="([^"]*)"""")
    private val artist = Regex("""<p class="artist"[^>]*>\s*<a[^>]*\btitle="([^"]*)"""")


    suspend fun find(song: Song): Lyrics? {
        val profile = SongMatcher.profile(song)
        val name = profile.titles.firstOrNull() ?: return null
        val main = profile.artists.firstOrNull() ?: return null
        val seen = mutableSetOf<String>()
        var fallback: Lyrics? = null
        for (query in listOf("$name $main", name).distinct()) {
            val html = LyricsHttp.get("$BASE/search/track?q=${Uri.encode(query)}")
            for (row in rows.findAll(html).take(30)) {
                val body = row.groupValues[2]
                val candidateTitle = title.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                val candidateArtist = artist.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                if (SongMatcher.match(profile, candidateTitle, candidateArtist) == null) continue
                val id = row.groupValues[1]
                if (!seen.add(id)) continue
                val lines = payload("T", id).split('＃').mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0) return@mapNotNull null
                    val seconds = line.substring(0, separator).toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                    LyricLine((seconds * 1000).toLong(), line.substring(separator + 1).trim())
                }.sortedBy { it.timeMs }
                if (lines.count { it.text.isNotBlank() } >= 2) {
                    return Lyrics(lines, lines.map { it.text }.filter(String::isNotBlank).joinToString("\n"))
                }
                if (fallback == null) {
                    val plain = payload("N", id).trim()
                    if (plain.lineSequence().count(String::isNotBlank) >= 2) fallback = Lyrics(emptyList(), plain)
                }
                if (seen.size >= 3) return fallback
            }
        }
        return fallback
    }

    private suspend fun payload(kind: String, id: String): String = try {
        val item = JSONObject(LyricsHttp.get("$BASE/player/lyrics/$kind/$id"))
        if (item.isNull("lyrics")) "" else item.optString("lyrics")
    } catch (_: JSONException) {
        ""
    } catch (_: java.io.FileNotFoundException) {
        ""
    }

    private fun decode(text: String): String = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()

}
