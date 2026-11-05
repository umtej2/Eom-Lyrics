package com.example.floatinglyrics

import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LyricsHttpException(val status: Int) : IOException("Lyrics HTTP $status")

internal object LyricsHttp {
    private val requests = Semaphore(3)
    private val charsetPattern = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)

    suspend fun get(url: String): String = request(url)

    suspend fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String =
        requests.withPermit {
            coroutineScope {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "FloatingLyrics/1.0")
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                suspendCancellableCoroutine<String> { continuation ->
                    val worker = launch(Dispatchers.IO) {
                        try {
                            ensureActive()
                            if (body != null) {
                                val bytes = body.toByteArray(Charsets.UTF_8)
                                connection.setFixedLengthStreamingMode(bytes.size)
                                connection.outputStream.use { it.write(bytes) }
                            }
                            val status = connection.responseCode
                            if (status == 404) throw FileNotFoundException("Lyrics HTTP 404")
                            if (status !in 200..299) throw LyricsHttpException(status)
                            val charset = charsetPattern.find(connection.contentType.orEmpty())
                                ?.groupValues?.get(1)?.trim('"', '\'')
                                ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
                                ?: Charsets.UTF_8
                            val text = connection.inputStream.bufferedReader(charset).use { reader ->
                                val result = StringBuilder()
                                val buffer = CharArray(8192)
                                while (true) {
                                    ensureActive()
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    if (result.length + count > 2_000_000) throw IOException("Lyrics response too large")
                                    result.append(buffer, 0, count)
                                }
                                result.toString()
                            }
                            continuation.resume(text)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            connection.disconnect()
                        }
                    }
                    continuation.invokeOnCancellation {
                        worker.cancel()
                        connection.disconnect()
                    }
                }
            }
        }
}
