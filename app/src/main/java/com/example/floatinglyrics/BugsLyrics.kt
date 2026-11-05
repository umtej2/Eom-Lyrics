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

internal object BugsLyrics {
    private const val BASE = "https://music.bugs.co.kr"
    private val hangul = Regex("[가-힣]")
    private val rows = Regex("""<tr\b[^>]*\btrackId="(\d+)"[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val title = Regex("""<p class="title"[^>]*>\s*<a[^>]*\btitle="([^"]*)"""")
    private val artist = Regex("""<p class="artist"[^>]*>\s*<a[^>]*\btitle="([^"]*)"""")
    private val feature = Regex("""\s*[\[(]?\s*\b(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s+(?:및|and|&|×|x)\s+|\s*,\s*""", RegexOption.IGNORE_CASE)

    fun find(song: Song): Lyrics? {
        if (!hangul.containsMatchIn(song.title + song.artist) && "bfree" !in normalize(song.artist)) return null
        val name = song.title.replace(feature, "").trim()
        val expectedTitles = (listOf(name) + name.split(" - ")).map(::normalize).toSet()
        val artistNames = (listOf(song.artist) + song.artist.split(separators)).map(::normalize).toMutableSet()
        if ("비프리" in artistNames || "bfree" in artistNames) artistNames.addAll(listOf("비프리", "bfree"))
        val queryArtist = song.artist.split(separators).first()
        val seen = mutableSetOf<String>()
        var fallback: Lyrics? = null
        for (query in listOf("$name $queryArtist", name).distinct()) {
            val html = get("$BASE/search/track?q=${Uri.encode(query)}")
            for (row in rows.findAll(html).take(30)) {
                val body = row.groupValues[2]
                val candidateTitle = title.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                val candidateArtist = artist.find(body)?.groupValues?.get(1)?.let(::decode) ?: continue
                if (normalize(candidateTitle.replace(feature, "")) !in expectedTitles) continue
                val names = listOf(candidateArtist, candidateArtist.substringBefore('(')) +
                    candidateArtist.split(separators)
                if (names.none { normalize(it) in artistNames }) continue
                val id = row.groupValues[1]
                if (seen.size >= 3) return fallback
                if (!seen.add(id)) continue
                val lines = payload("T", id).split('＃').mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0) return@mapNotNull null
                    val seconds = line.substring(0, separator).toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                    LyricLine((seconds * 1000).toLong(), line.substring(separator + 1).trim())
                }.sortedBy { it.timeMs }
                if (lines.count { it.text.isNotBlank() } >= 2) {
                    return Lyrics(lines, lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"))
                }
                if (fallback == null) {
                    val plain = payload("N", id).trim()
                    if (plain.lineSequence().count { it.isNotBlank() } >= 2) fallback = Lyrics(emptyList(), plain)
                }
            }
        }
        return fallback
    }

    private fun payload(kind: String, id: String): String = try {
        val item = JSONObject(get("$BASE/player/lyrics/$kind/$id"))
        if (item.isNull("lyrics")) "" else item.optString("lyrics")
    } catch (_: JSONException) {
        ""
    } catch (_: java.io.FileNotFoundException) {
        ""
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "FloatingLyrics/1.0")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(text: String): String = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
}
