package com.sideload.splitinstaller.core.sources

import java.net.URI

/**
 * Links someone pasted or shared. Pure Kotlin, so it is tested off the device: a browser or a
 * chat app hands over a title, the link and whatever else around it.
 */
object Links {

    // Stops at spaces, quotes and brackets, and at CJK and full-width punctuation, which chat
    // apps put right after a link with no space.
    private val URL = Regex("""https?://[^\s<>"'`　-〿＀-￯]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\""

    /**
     * The first http(s) link in [text], without the punctuation a sentence puts after it. The
     * scheme comes back in lower case: DownloadManager refuses "HTTPS://".
     */
    fun extractUrl(text: CharSequence?): String? {
        val match = URL.find(text ?: return null) ?: return null
        val raw = match.value.trimEnd { it in TRAILING }
        val colon = raw.indexOf("://")
        val url = raw.substring(0, colon).lowercase() + raw.substring(colon)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return url
    }

    private val FILE_EXTENSIONS = listOf(".apk", ".apks", ".apkm", ".xapk", ".apkx", ".zip")
    private val FILE_PATHS = listOf("/releases/download/", "/-/package_files/", "/uploads/", "/raw/")

    /**
     * A link to one file rather than to a project or an app's page: it ends in a package file's
     * extension, or has the shape of a release download on GitHub, GitLab or Forgejo.
     */
    fun isFileLink(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase() ?: return false
        return FILE_EXTENSIONS.any { path.endsWith(it) } || FILE_PATHS.any { it in path }
    }

    fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    /** "PK\u0003\u0004": a zip, which every APK and every bundle is. */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

    /** A page rather than a file: a download page, a login wall, an expired link. */
    fun looksLikeHtml(contentType: String?, head: ByteArray): Boolean {
        if (contentType?.contains("html", ignoreCase = true) == true) return true
        val start = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        return start.startsWith("<!doctype") || start.startsWith("<html")
    }

    /**
     * The extension for a zip the link gave no name for. A bundle keeps APKs or a manifest at the
     * front; an APK starts with its own entries. Only a fallback: a named file keeps its name.
     */
    fun extensionFor(head: ByteArray): String {
        if (!looksLikeZip(head) || head.size < 30) return ".apk"
        val nameLength = (head[26].toInt() and 0xff) or ((head[27].toInt() and 0xff) shl 8)
        if (30 + nameLength > head.size) return ".apk"
        val first = String(head, 30, nameLength, Charsets.UTF_8)
        val bundle = first.endsWith(".apk", ignoreCase = true) ||
            first in setOf("manifest.json", "info.json", "toc.pb", "meta.sai_v2.json")
        return if (bundle) ".apks" else ".apk"
    }
}
