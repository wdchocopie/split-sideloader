package com.sideload.splitinstaller.core.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * One small GET, for the version APIs.
 *
 * Deliberately minimal: update checks are a few kilobytes of JSON a day, and an HTTP
 * client dependency would be more code than this.
 */
object Http {

    const val USER_AGENT = "SplitSideloader/1.5 (Android)"
    private const val MAX_BYTES = 4 * 1024 * 1024

    class HttpError(val code: Int, message: String) : IOException(message)

    fun getString(url: String, accept: String? = null, timeoutMs: Int = 20_000): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
            accept?.let { setRequestProperty("Accept", it) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw HttpError(code, "HTTP $code")
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = StringBuilder()
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("response too large")
                    out.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
