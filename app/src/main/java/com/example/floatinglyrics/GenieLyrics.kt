package com.example.floatinglyrics

import android.net.Uri
import android.text.Html
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

internal object GenieLyrics {
    private val rows = Regex("""<tr\b[^>]*class="list"[^>]*songid="(\d+)"[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val title = Regex("""class="title ellipsis"[^>]*\btitle="([^"]*)"""")
    private val artist = Regex("""class="artist ellipsis"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val plain = Regex("""<pre\s+id="pLyrics"[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL)

    suspend fun find(song: Song): Lyrics? {
        val profile = SongMatcher.profile(song)
        val name = profile.titles.firstOrNull() ?: return null
        val main = profile.artists.firstOrNull() ?: return null
        val seen = mutableSetOf<String>()
        var fallback: Lyrics? = null
        for (query in listOf("$name $main", name).distinct()) {
            val html = LyricsHttp.get("https://www.genie.co.kr/search/searchSong?query=${Uri.encode(query)}")
            for (row in rows.findAll(html).take(30)) {
                val body = row.groupValues[2]
                val track = title.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                val performers = artist.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                if (SongMatcher.match(profile, track, performers) == null) continue
                val id = row.groupValues[1]
                if (!seen.add(id)) continue
                val lines = optional { timed(id) }.orEmpty()
                if (lines.count { it.text.isNotBlank() } >= 2) {
                    return Lyrics(lines, lines.map { it.text }.filter(String::isNotBlank).joinToString("\n"))
                }
                if (fallback == null) {
                    val text = optional { plainText(id) }
                    if (!text.isNullOrBlank()) fallback = Lyrics(emptyList(), text)
                }
                if (seen.size >= 3) return fallback
            }
        }
        return fallback
    }

    private suspend fun timed(id: String): List<LyricLine> {
        val response = LyricsHttp.get("https://dn.genie.co.kr/app/purchase/get_msl.asp?songid=$id&callback=x")
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val json = JSONObject(response.substring(start, end + 1))
        return json.keys().asSequence().mapNotNull { key ->
            val time = key.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            LyricLine(time, json.optString(key).trim())
        }.sortedBy { it.timeMs }.toList()
    }

    private suspend fun plainText(id: String): String? {
        val html = LyricsHttp.get("https://www.genie.co.kr/detail/songInfo?xgnm=$id")
        val markup = plain.find(html)?.groupValues?.get(1) ?: return null
        if (markup.contains("가사 정보가 없습니다")) return null
        return decode(markup.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "<br>"))
            .takeIf { it.lineSequence().count(String::isNotBlank) >= 2 }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun decode(value: String): String = Html.fromHtml(
        value.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY
    ).toString().trim()

}
