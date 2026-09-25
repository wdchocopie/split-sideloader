package com.sideload.splitinstaller.core.sources

import java.net.URI
import java.net.URLDecoder

/**
 * Turns what a server says about a download into a safe file name.
 *
 * Pure Kotlin on purpose, so it can be tested without a device. The name ends up as a
 * path under Download/, so it must never be able to climb out of that folder.
 */
object DownloadNames {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_LENGTH = 150

    fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val raw = fromContentDisposition(contentDisposition)
            ?: fromUrl(url)
            ?: ""
        var name = sanitize(raw)
        // A bare package name like "com.foo" has dots but no real extension.
        val ext = name.substringAfterLast('.', "").lowercase()
        if (name.isNotEmpty() && ext !in KNOWN_EXTENSIONS) {
            when {
                mimeType?.contains("android.package-archive") == true -> name += ".apk"
                mimeType?.contains("xapk") == true -> name += ".xapk"
            }
        }
        return name.ifBlank { "download.bin" }
    }

    private val KNOWN_EXTENSIONS = setOf("apk", "apks", "apkm", "xapk", "apkx", "zip", "obb")

    /** RFC 6266: an encoded `filename*` wins over a plain `filename`. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val params = splitParams(header)

        params["filename*"]?.let { value ->
            val parts = value.split("'", limit = 3)
            if (parts.size == 3) {
                val charset = parts[0].ifBlank { "UTF-8" }
                return runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), charset) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }
        return params["filename"]?.takeIf { it.isNotBlank() }
    }

    private fun splitParams(header: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val s = header
        while (i < s.length) {
            val semi = s.indexOf(';', i)
            if (semi < 0) break
            i = semi + 1
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().lowercase()
            var j = eq + 1
            while (j < s.length && s[j] == ' ') j++
            val value: String
            if (j < s.length && s[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < s.length && s[j] != '"') {
                    if (s[j] == '\\' && j + 1 < s.length) j++
                    sb.append(s[j])
                    j++
                }
                value = sb.toString()
                i = j + 1
            } else {
                val end = s.indexOf(';', j).let { if (it < 0) s.length else it }
                value = s.substring(j, end).trim()
                i = end
            }
            if (key.isNotEmpty() && key !in out) out[key] = value
        }
        return out
    }

    private fun fromUrl(url: String): String? = runCatching {
        val path = URI(url).rawPath ?: return null
        val last = path.substringAfterLast('/')
        URLDecoder.decode(last.replace("+", "%2B"), "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Keeps the last path segment only, drops characters no filesystem wants, bounds the length. */
    fun sanitize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(ILLEGAL, "_").trim().trim('.', ' ')
        if (name == "." || name == "..") name = ""
        if (name.length > MAX_LENGTH) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
            name = name.take(MAX_LENGTH - ext.length) + ext
        }
        return name
    }
}
