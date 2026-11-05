package com.example.floatinglyrics

import android.net.Uri
import android.text.Html
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

internal object GenieLyrics {
    private val hangul = Regex("[가-힣]")
    private val rows = Regex("""<tr\b[^>]*class="list"[^>]*songid="(\d+)"[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val title = Regex("""class="title ellipsis"[^>]*\btitle="([^"]*)"""")
    private val artist = Regex("""class="artist ellipsis"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val plain = Regex("""<pre\s+id="pLyrics"[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL)
    private val feature = Regex("""\s*[\[(]?\s*\b(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s*(?:,|·|、)\s*|\s+(?:및|and|&|×|x)\s+""", RegexOption.IGNORE_CASE)

    fun find(song: Song): Lyrics? {
        if (!hangul.containsMatchIn(song.title + song.artist)) return null
        val name = song.title.replace(feature, "").trim()
        val artistNames = artistKeys(song.artist).toSet()
        val seen = mutableSetOf<String>()
        var fallback: Lyrics? = null
        for (query in listOf("$name ${song.artist.split(separators).first()}", name).distinct()) {
            val html = get("https://www.genie.co.kr/search/searchSong?query=${Uri.encode(query)}")
            for (row in rows.findAll(html).take(30)) {
                val body = row.groupValues[2]
                val track = title.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                val performers = artist.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                if (normalize(track.replace(feature, "")) != normalize(name)) continue
                if (artistKeys(performers).none { it in artistNames }) continue
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

    private fun timed(id: String): List<LyricLine> {
        val response = get("https://dn.genie.co.kr/app/purchase/get_msl.asp?songid=$id&callback=x")
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val json = JSONObject(response.substring(start, end + 1))
        return json.keys().asSequence().mapNotNull { key ->
            val time = key.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            LyricLine(time, json.optString(key).trim())
        }.sortedBy { it.timeMs }.toList()
    }

    private fun plainText(id: String): String? {
        val html = get("https://www.genie.co.kr/detail/songInfo?xgnm=$id")
        val markup = plain.find(html)?.groupValues?.get(1) ?: return null
        if (markup.contains("가사 정보가 없습니다")) return null
        return decode(markup.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "<br>"))
            .takeIf { it.lineSequence().count(String::isNotBlank) >= 2 }
    }

    private fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4500
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "FloatingLyrics/1.0")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(value: String): String = Html.fromHtml(
        value.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY
    ).toString().trim()

    private fun artistKeys(value: String): List<String> =
        (listOf(value) + value.split(separators)).flatMap { name ->
            listOf(name, name.substringBefore('('), name.substringAfter('(', "").substringBefore(')'))
        }.map(::artistKey).filter(String::isNotBlank).distinct()

    private fun artistKey(value: String): String = when (val key = normalize(value)) {
        "cjamm" -> "씨잼"
        "blacknut" -> "블랙넛"
        "bfree" -> "비프리"
        else -> key
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}

