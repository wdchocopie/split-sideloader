package com.sideload.splitinstaller.core.sources

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** How far a source's files can be trusted. */
enum class SourceTrust {
    /** Re-hosts the publisher's own files unchanged (APKMirror). */
    VERBATIM,

    /** Direct file downloads are fine; its own installer app is not. */
    FILES_ONLY,

    /** Open-source builds, signed by the store or the author. */
    FOSS,

    /** Added by you. */
    CUSTOM,
}

data class Source(
    val id: String,
    val name: String,
    val homeUrl: String,
    /** Search page with `{q}` where the query goes; null when the source has no search. */
    val searchUrl: String?,
    val formats: List<String>,
    val trust: SourceTrust,
    val builtin: Boolean,
) {
    val host: String
        get() = runCatching { URI(homeUrl).host?.removePrefix("www.") }.getOrNull() ?: homeUrl

    fun searchFor(query: String): String {
        val q = query.trim()
        if (q.isEmpty() || searchUrl == null) return homeUrl
        return searchUrl.replace("{q}", URLEncoder.encode(q, "UTF-8"))
    }

    fun owns(url: String?): Boolean {
        val h = runCatching { URI(url ?: return false).host?.removePrefix("www.") }.getOrNull() ?: return false
        return h == host || h.endsWith(".$host")
    }
}

/**
 * The sources the app offers, built in and user-added.
 *
 * Every one of them is browsed as a website, the way its owner serves it. Nothing here
 * talks to a private API or scrapes a page in the background: the sites that matter sit
 * behind bot protection and forbid automated downloads, and a person tapping "download"
 * in a browser is exactly what they are built for.
 */
object Sources {

    val BUILTIN = listOf(
        Source(
            id = "apkmirror",
            name = "APKMirror",
            homeUrl = "https://www.apkmirror.com/",
            searchUrl = "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s={q}",
            formats = listOf("APKM", "APK"),
            trust = SourceTrust.VERBATIM,
            builtin = true,
        ),
        Source(
            id = "apkpure",
            name = "APKPure",
            homeUrl = "https://apkpure.com/",
            searchUrl = "https://apkpure.com/search?q={q}",
            formats = listOf("XAPK", "APK"),
            trust = SourceTrust.FILES_ONLY,
            builtin = true,
        ),
        Source(
            id = "fdroid",
            name = "F-Droid",
            homeUrl = "https://f-droid.org/",
            searchUrl = "https://search.f-droid.org/?q={q}&lang=en",
            formats = listOf("APK"),
            trust = SourceTrust.FOSS,
            builtin = true,
        ),
        Source(
            id = "github",
            name = "GitHub Releases",
            homeUrl = "https://github.com/",
            searchUrl = "https://github.com/search?q={q}&type=repositories",
            formats = listOf("APK", "APKS"),
            trust = SourceTrust.FOSS,
            builtin = true,
        ),
    )

    private const val PREFS = "sources"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_SELECTED = "selected"

    private val _all = MutableStateFlow(BUILTIN)
    val all: StateFlow<List<Source>> = _all

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _all.value = BUILTIN + readCustom(context)
            loaded = true
        }
    }

    fun byId(id: String?): Source? = _all.value.firstOrNull { it.id == id }

    fun selected(context: Context): Source {
        load(context)
        val id = prefs(context).getString(KEY_SELECTED, null)
        return byId(id) ?: BUILTIN.first()
    }

    fun select(context: Context, id: String) = prefs(context).edit { putString(KEY_SELECTED, id) }

    /** Accepts only https pages; a search template, when given, must say where the query goes. */
    fun validate(name: String, home: String, search: String): Boolean {
        if (name.isBlank()) return false
        if (!home.trim().startsWith("https://")) return false
        val s = search.trim()
        if (s.isNotEmpty() && (!s.startsWith("https://") || "{q}" !in s)) return false
        return runCatching { URI(home.trim()).host != null }.getOrDefault(false)
    }

    fun addCustom(context: Context, name: String, home: String, search: String): Source? {
        if (!validate(name, home, search)) return null
        load(context)
        val source = Source(
            id = "custom-" + System.currentTimeMillis(),
            name = name.trim(),
            homeUrl = home.trim(),
            searchUrl = search.trim().ifEmpty { null },
            formats = emptyList(),
            trust = SourceTrust.CUSTOM,
            builtin = false,
        )
        val custom = _all.value.filterNot { it.builtin } + source
        writeCustom(context, custom)
        _all.value = BUILTIN + custom
        return source
    }

    fun removeCustom(context: Context, id: String) {
        load(context)
        val custom = _all.value.filterNot { it.builtin || it.id == id }
        writeCustom(context, custom)
        _all.value = BUILTIN + custom
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readCustom(context: Context): List<Source> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_CUSTOM, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Source(
                id = o.optString("id"),
                name = o.optString("name"),
                homeUrl = o.optString("home"),
                searchUrl = o.optString("search").ifBlank { null },
                formats = emptyList(),
                trust = SourceTrust.CUSTOM,
                builtin = false,
            ).takeIf { it.id.isNotBlank() && it.homeUrl.startsWith("https://") }
        }
    }.getOrDefault(emptyList())

    private fun writeCustom(context: Context, custom: List<Source>) {
        val arr = JSONArray()
        custom.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("home", it.homeUrl)
                    .put("search", it.searchUrl.orEmpty())
            )
        }
        prefs(context).edit { putString(KEY_CUSTOM, arr.toString()) }
    }
}
