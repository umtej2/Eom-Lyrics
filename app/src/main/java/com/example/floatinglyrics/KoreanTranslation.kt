package com.example.floatinglyrics

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class TranslationHttpException(val status: Int) : IOException("Translation HTTP $status")
internal class TranslationCooldownException : IOException("Translation temporarily rate limited")

internal object KoreanTranslation {
    private const val MARK = "‖"
    private const val CHUNK_LIMIT = 1800
    private const val CACHE_LIMIT = 32
    private const val COOLDOWN_MS = 120_000L
    @Volatile private var blockedUntil = 0L
    private val japanese = Regex("""[\u3041-\u3096\u30A1-\u30FA\uFF66-\uFF9D]""")
    private val korean = Regex("""[\u1100-\u11FF\u3131-\u318E\uAC00-\uD7A3]""")
    private val latin = Regex("""[A-Za-z]""")

    fun retryAfter(): Long = blockedUntil

    fun sourceLanguage(lyrics: Lyrics): String? {
        val text = lyrics.plain
        val japaneseCount = japanese.findAll(text).count()
        val koreanCount = korean.findAll(text).count()
        val latinCount = latin.findAll(text).count()
        return when {
            japaneseCount >= 3 && japaneseCount > koreanCount -> "ja"
            koreanCount > 0 && latinCount < koreanCount * 4 -> null
            latinCount >= 3 -> "en"
            else -> null
        }
    }

    suspend fun translate(context: Context, lyrics: Lyrics, source: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val lines = (lyrics.lines.map { it.text } + lyrics.plain.lines())
                .map(String::trim)
                .filter { line -> !korean.containsMatchIn(line) && line.any(Char::isLetter) }
                .distinct()
            if (lines.isEmpty()) return@withContext emptyMap()

            val directory = File(context.cacheDir, "lyric-translations")
            val key = MessageDigest.getInstance("SHA-256")
                .digest(("v1:$source\n" + lines.joinToString("\n")).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = File(directory, "$key.json")
            readCache(file)?.let { return@withContext it }

            val result = mutableMapOf<String, String>()
            var fallbackRequests = 0
            var allLinesAligned = true
            for (batch in batches(lines)) {
                ensureActive()
                val translated = request(source, batch.joinToString("\n$MARK\n"))
                val parts = if (batch.size == 1) listOf(translated) else translated.split(MARK)
                if (parts.size == batch.size && parts.all { it.isNotBlank() }) {
                    batch.zip(parts).forEach { (line, value) -> result[line] = value.trim() }
                } else {
                    for (line in batch) {
                        if (fallbackRequests >= 4) {
                            allLinesAligned = false
                            break
                        }
                        ensureActive()
                        fallbackRequests++
                        result[line] = request(source, line).trim()
                    }
                }
            }
            ensureActive()
            val translations = result.filter { (original, translated) ->
                translated.isNotBlank() && translated != original
            }
            if (!allLinesAligned || result.values.any { it.isBlank() }) {
                if (translations.isEmpty()) throw IOException("Could not align translated lyrics")
                return@withContext translations
            }
            if (translations.isNotEmpty()) writeCache(directory, file, translations)
            translations
        }

    private fun batches(lines: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var batch = mutableListOf<String>()
        var length = 0
        for (line in lines) {
            if (MARK in line) {
                if (batch.isNotEmpty()) batches.add(batch)
                batches.add(listOf(line))
                batch = mutableListOf()
                length = 0
                continue
            }
            if (batch.isNotEmpty() && length + line.length > CHUNK_LIMIT) {
                batches.add(batch)
                batch = mutableListOf()
                length = 0
            }
            batch.add(line)
            length += line.length + 3
        }
        if (batch.isNotEmpty()) batches.add(batch)
        return batches
    }

    private suspend fun request(source: String, text: String): String {
        if (SystemClock.elapsedRealtime() < blockedUntil) throw TranslationCooldownException()
        val url = Uri.parse("https://translate.googleapis.com/translate_a/single").buildUpon()
            .appendQueryParameter("client", "gtx")
            .appendQueryParameter("sl", source)
            .appendQueryParameter("tl", "ko")
            .appendQueryParameter("dt", "t")
            .build()
        val response = try {
            LyricsHttp.request(url.toString(), "q=" + URLEncoder.encode(text, "UTF-8"),
                mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"))
        } catch (error: LyricsHttpException) {
            if (error.status in 300..399 || error.status == 403 || error.status == 429) {
                blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
            }
            throw TranslationHttpException(error.status)
        }
        val segments = JSONArray(response).getJSONArray(0)
        return buildString {
            for (index in 0 until segments.length()) {
                val part = segments.optJSONArray(index)?.opt(0) as? String
                if (part != null) append(part)
            }
        }
    }

    private fun readCache(file: File): Map<String, String>? = try {
        val cached = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        file.setLastModified(System.currentTimeMillis())
        cached.keys().asSequence().associateWith { cached.getString(it) }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun writeCache(directory: File, file: File, translations: Map<String, String>) {
        directory.mkdirs()
        val cache = AtomicFile(file)
        try {
            val output = cache.startWrite()
            try {
                output.write(JSONObject(translations).toString().toByteArray(Charsets.UTF_8))
                cache.finishWrite(output)
            } catch (_: IOException) {
                cache.failWrite(output)
                return
            }
        } catch (_: IOException) {
            return
        }
        directory.listFiles { entry -> entry.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.drop(CACHE_LIMIT)
            ?.forEach { it.delete() }
    }
}



