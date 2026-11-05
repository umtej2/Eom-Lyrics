package com.example.floatinglyrics

import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object YouTubeMusicLyrics {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    private val web = Client("WEB_REMIX", 67, "1.20241023.01.00")
    private val mobile = Client("ANDROID_MUSIC", 21, "7.21.50")

    fun videoId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (idPattern.matches(value)) return value
        val uri = Uri.parse(value)
        if (!uri.isHierarchical) return null
        val candidate = when (uri.host?.lowercase()) {
            "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube.com" ->
                uri.getQueryParameter("v") ?: uri.pathSegments.takeIf { it.firstOrNull() == "shorts" }?.getOrNull(1)
            "youtu.be" -> uri.pathSegments.firstOrNull()
            else -> null
        }
        return candidate?.takeIf(idPattern::matches)
    }

    fun find(song: Song): Lyrics? {
        val id = song.videoId ?: return null
        val next = request("next", JSONObject().put("videoId", id).put("isAudioOnly", true), web)
        val browseId = findValue(next, "browseId") { it is String && it.startsWith("MPLY") } as? String
            ?: return null
        val body = JSONObject().put("browseId", browseId)
        val mobileLyrics = try {
            val json = request("browse", body, mobile)
            parseTimed(findValue(json, "timedLyricsData") { it is JSONArray } as? JSONArray)
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
        if (mobileLyrics?.lines?.isNotEmpty() == true) return mobileLyrics
        val webLyrics = try {
            val json = request("browse", body, web)
            val shelf = findValue(json, "musicDescriptionShelfRenderer") { it is JSONObject } as? JSONObject
            text(shelf?.opt("description")).trim().takeIf(String::isNotBlank)
        } catch (error: IOException) {
            if (mobileLyrics != null) return mobileLyrics
            throw error
        }
        return webLyrics?.let { Lyrics(emptyList(), it) } ?: mobileLyrics
    }

    private fun parseTimed(rows: JSONArray?): Lyrics? {
        if (rows == null || rows.length() == 0) return null
        val entries = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val plain = entries.joinToString("\n") { it.optString("lyricLine") }.trim()
        if (plain.isBlank()) return null
        val timed = entries.mapNotNull { row ->
            val time = row.optJSONObject("cueRange")?.optLong("startTimeMilliseconds", -1) ?: -1
            if (time < 0) null else LyricLine(time, row.optString("lyricLine").removeSurrounding("♪").trim())
        }
        return Lyrics(if (timed.size >= entries.size * 0.8) timed.sortedBy { it.timeMs } else emptyList(), plain)
    }

    private fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.optString("simpleText").ifBlank {
            value.optString("content").ifBlank {
                val runs = value.optJSONArray("runs")
                (0 until (runs?.length() ?: 0)).joinToString("") { runs?.optJSONObject(it)?.optString("text").orEmpty() }
            }
        }
        else -> ""
    }

    private fun findValue(value: Any?, key: String, depth: Int = 0, matches: (Any) -> Boolean): Any? {
        if (depth > 40) return null
        when (value) {
            is JSONObject -> {
                val direct = value.opt(key)
                if (direct != null && matches(direct)) return direct
                for (name in value.keys()) findValue(value.opt(name), key, depth + 1, matches)?.let { return it }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findValue(value.opt(index), key, depth + 1, matches)?.let { return it }
            }
        }
        return null
    }

    private fun request(endpoint: String, body: JSONObject, client: Client): JSONObject {
        val connection = URL("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4500
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Youtube-Client-Name", client.id.toString())
            connection.setRequestProperty("X-Youtube-Client-Version", client.version)
            val context = JSONObject().put("client", JSONObject()
                .put("clientName", client.name).put("clientVersion", client.version)
                .put("hl", "ko").put("gl", "KR"))
            body.put("context", context)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("YouTube Music HTTP $status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private data class Client(val name: String, val id: Int, val version: String)
}
