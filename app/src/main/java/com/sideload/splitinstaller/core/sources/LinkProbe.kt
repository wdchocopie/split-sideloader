package com.sideload.splitinstaller.core.sources

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Why a link could not be used. Codes, so the screen can say it in either language. */
class LinkProblem(val code: String, val httpCode: Int = 0) : IOException(code) {
    companion object {
        const val NOT_A_LINK = "not_a_link"
        /** Plain http: an APK fetched that way can be swapped on the way, and Android refuses it. */
        const val CLEARTEXT = "cleartext"
        const val REDIRECTS = "redirects"
        const val HTTP = "http"
        const val UNREACHABLE = "unreachable"
        const val NO_FILE = "no_file"
    }
}

/**
 * The first kilobytes of what a link serves, and where it ends up. A GET with a Range rather
 * than a HEAD: signed CDN links (GitHub's release files among them) refuse a HEAD. Redirects are
 * followed by hand so that none of them can lead to plain http.
 */
data class LinkProbe(
    val finalUrl: String,
    val contentType: String?,
    val contentDisposition: String?,
    /** The whole file's size, when the server says it. */
    val size: Long?,
    val head: ByteArray,
) {
    val isFile: Boolean get() = Links.looksLikeZip(head)

    /** What the bytes say comes first: some servers label every file text/html. */
    val isPage: Boolean get() = !isFile && Links.looksLikeHtml(contentType, head)

    companion object {

        private const val MAX_REDIRECTS = 5
        private const val HEAD_BYTES = 2048

        fun probe(url: String, userAgent: String, timeoutMs: Int = 15_000): LinkProbe {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!Links.isHttps(current)) throw LinkProblem(LinkProblem.CLEARTEXT)
                val connection = try {
                    (URL(current).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Range", "bytes=0-${HEAD_BYTES - 1}")
                    }
                } catch (t: Throwable) {
                    throw LinkProblem(LinkProblem.UNREACHABLE)
                }
                try {
                    val code = try {
                        connection.responseCode
                    } catch (t: IOException) {
                        throw LinkProblem(LinkProblem.UNREACHABLE)
                    }
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: throw LinkProblem(LinkProblem.HTTP, code)
                        current = URL(URL(current), location).toString()
                        return@repeat
                    }
                    if (code !in 200..299) throw LinkProblem(LinkProblem.HTTP, code)
                    val head = connection.inputStream.use { input ->
                        val buffer = ByteArray(HEAD_BYTES)
                        var read = 0
                        while (read < buffer.size) {
                            val n = input.read(buffer, read, buffer.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        buffer.copyOf(read)
                    }
                    return LinkProbe(
                        finalUrl = current,
                        contentType = connection.contentType,
                        contentDisposition = connection.getHeaderField("Content-Disposition"),
                        size = totalSize(connection.getHeaderField("Content-Range"), connection.contentLengthLong, code),
                        head = head,
                    )
                } finally {
                    connection.disconnect()
                }
            }
            throw LinkProblem(LinkProblem.REDIRECTS)
        }

        /** "bytes 0-2047/52428800" says the whole size; a server that ignored the Range says it plainly. */
        internal fun totalSize(contentRange: String?, contentLength: Long, code: Int): Long? {
            contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.let { return it }
            return contentLength.takeIf { code == 200 && it > 0 }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LinkProbe && finalUrl == other.finalUrl && head.contentEquals(other.head)

    override fun hashCode(): Int = finalUrl.hashCode() * 31 + head.contentHashCode()
}
