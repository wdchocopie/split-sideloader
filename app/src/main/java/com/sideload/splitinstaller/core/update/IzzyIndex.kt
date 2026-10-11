package com.sideload.splitinstaller.core.update

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Searching IzzyOnDroid, which has no search API: its catalogue (index-v1.jar, about 4 MB) is
 * fetched at most once a day, cut down to name, summary and package, kept in the cache folder,
 * and searched on the phone. Only the list comes from here; Install asks the package API for
 * the build, and the file goes through the usual checks of package and signer.
 */
object IzzyIndex {

    data class Entry(val packageName: String, val name: String, val summary: String?)

    private const val INDEX = "https://apt.izzysoft.de/fdroid/repo/index-v1.jar"
    private const val PAGE = "https://apt.izzysoft.de/fdroid/index/apk/"
    private const val CACHE = "izzy-apps.tsv"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val LIMIT = 30

    @Volatile private var memory: Pair<Long, List<Entry>>? = null

    suspend fun search(context: Context, query: String): List<AppHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        rank(entries(context), query).map { e ->
            AppHit(e.name, e.summary, SourceRef(UpdateKind.IZZYONDROID, e.packageName), PAGE + e.packageName, e.packageName)
        }
    }

    /** The cached list, fetched again once a day old; a stale list beats none when offline. */
    @Synchronized
    private fun entries(context: Context): List<Entry> {
        val file = File(context.cacheDir, CACHE)
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!fresh) {
            try {
                download(file)
            } catch (e: IOException) {
                if (!file.isFile) throw e
            }
        }
        memory?.let { (stamp, list) -> if (stamp == file.lastModified()) return list }
        return readTsv(file).also { memory = file.lastModified() to it }
    }

    private fun download(target: File) {
        val connection = (URL(INDEX).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", Http.USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw Http.HttpError(code, "HTTP $code")
            val entries = ZipInputStream(Capped(connection.inputStream, MAX_BYTES)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: throw IOException("index-v1.json missing")
                    if (entry.name == "index-v1.json") break
                }
                parseIndex(zip)
            }
            if (entries.isEmpty()) throw IOException("empty catalogue")
            val tmp = File(target.path + ".tmp")
            tmp.bufferedWriter().use { out -> entries.forEach { out.write(toTsv(it)); out.write("\n") } }
            if (!tmp.renameTo(target)) throw IOException("could not save the catalogue")
        } finally {
            connection.disconnect()
        }
    }

    /** Reads only "apps" from index-v1.json, streaming: the whole file is some 16 MB of JSON. */
    private fun parseIndex(input: InputStream): List<Entry> {
        val out = ArrayList<Entry>(2048)
        val reader = JsonReader(input.reader(Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "apps") {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) readApp(reader)?.let(out::add)
            reader.endArray()
            break
        }
        return out
    }

    private fun readApp(reader: JsonReader): Entry? {
        var pkg: String? = null
        var name: String? = null
        var summary: String? = null
        val localized = HashMap<String, Pair<String?, String?>>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> pkg = string(reader)
                "name" -> name = string(reader)
                "summary" -> summary = string(reader)
                "localized" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val locale = reader.nextName()
                        var n: String? = null
                        var s: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> n = string(reader)
                                "summary" -> s = string(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        localized[locale] = n to s
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val p = pkg ?: return null
        val local = localized["en-US"] ?: localized["en"] ?: localized.values.firstOrNull()
        return Entry(p, name ?: local?.first ?: p, summary ?: local?.second)
    }

    private fun string(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString().trim().ifEmpty { null } else { reader.skipValue(); null }

    // ---- the cached list and the search over it: plain Kotlin, tested off-device ----------

    fun toTsv(e: Entry): String = listOf(e.packageName, e.name, e.summary.orEmpty()).joinToString("\t") { clean(it) }

    fun fromTsv(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 2 || parts[0].isBlank()) return null
        return Entry(parts[0], parts[1].ifBlank { parts[0] }, parts.getOrNull(2)?.ifBlank { null })
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun readTsv(file: File): List<Entry> = file.useLines { lines -> lines.mapNotNull(::fromTsv).toList() }

    /**
     * Every word of [query] has to appear in the name, package or summary. The exact name comes
     * first, then names that start with it, then names holding every word, then package names,
     * then summaries; shorter names first within each.
     */
    fun rank(entries: List<Entry>, query: String, limit: Int = LIMIT): List<Entry> {
        val q = query.trim().lowercase()
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            val name = e.name.lowercase()
            val pkg = e.packageName.lowercase()
            val summary = e.summary?.lowercase().orEmpty()
            if (!words.all { w -> w in name || w in pkg || w in summary }) return@mapNotNull null
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                words.all { it in name } -> 2
                words.all { it in pkg } -> 3
                else -> 4
            }
            Triple(score, e.name.length, e)
        }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }
    }

    /** Stops a download that grows past [max] bytes. */
    private class Capped(private val inner: InputStream, private val max: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun close() = inner.close()
        private fun bump(n: Long) {
            count += n
            if (count > max) throw IOException("catalogue too large")
        }
    }
}
