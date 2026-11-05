package com.example.floatinglyrics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class LyricsDiskCache(context: Context) {
    private val directory = File(context.cacheDir, "song-lyrics-v1")
    private val mutex = Mutex()

    suspend fun read(song: Song): Lyrics? = mutex.withLock { readUnlocked(song) }

    suspend fun write(song: Song, lyrics: Lyrics) = mutex.withLock { writeUnlocked(song, lyrics) }

    private suspend fun readUnlocked(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val file = fileFor(song)
        if (!file.exists() || file.length() > 500_000 ||
            System.currentTimeMillis() - file.lastModified() > 30L * 24 * 60 * 60 * 1000
        ) return@withContext null
        try {
            val data = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
            val plain = data.getString("plain")
            val source = Song(data.getString("title"), data.getString("artist"), data.getLong("duration"))
            if (plain.isBlank() || !SongMatcher.sameRecording(song, source)) return@withContext null
            val rows = data.getJSONArray("lines")
            val lines = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                LyricLine(row.getLong("time"), row.getString("text"))
            }.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }
            file.setLastModified(System.currentTimeMillis())
            Lyrics(lines, plain)
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private suspend fun writeUnlocked(song: Song, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        val rows = JSONArray()
        lyrics.lines.forEach { rows.put(JSONObject().put("time", it.timeMs).put("text", it.text)) }
        val data = JSONObject().put("title", song.title).put("artist", song.artist)
            .put("duration", song.durationMs).put("plain", lyrics.plain).put("lines", rows)
            .toString().toByteArray(Charsets.UTF_8)
        if (data.size > 500_000) return@withContext
        directory.mkdirs()
        val file = AtomicFile(fileFor(song))
        try {
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (_: IOException) {
                file.failWrite(output)
                return@withContext
            }
        } catch (_: IOException) {
            return@withContext
        }
        var bytes = 0L
        directory.listFiles { entry -> entry.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                bytes += entry.length()
                if (index >= 128 || bytes > 4 * 1024 * 1024) entry.delete()
            }
    }

    suspend fun remove(song: Song) = mutex.withLock {
        withContext(Dispatchers.IO) { AtomicFile(fileFor(song)).delete() }
    }

    private fun fileFor(song: Song): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(SongMatcher.recordingKey(song).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
}
