package com.sideload.splitinstaller.core.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val time: Long,
    val packageName: String?,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val bundleName: String,
    val backend: String,
    val ok: Boolean,
    /** Post-install verdict name, when the install got that far. */
    val verdict: String?,
    val message: String?,
    val primaryCpuAbi: String?,
    val splits: Int,
    val repair: Boolean = false,
)

/**
 * What was installed, when, and whether it actually came out working.
 *
 * Kept small and local on purpose — it is a record for the person holding the phone, not
 * telemetry — and capped so it never grows into something worth thinking about.
 */
object InstallHistory {

    private const val FILE = "history.json"
    private const val LIMIT = 100

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _entries.value = runCatching { read(file(context)) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    fun add(context: Context, entry: HistoryEntry) {
        load(context)
        synchronized(this) {
            val next = (listOf(entry) + _entries.value).take(LIMIT)
            _entries.value = next
            runCatching { write(file(context), next) }
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            _entries.value = emptyList()
            runCatching { file(context).delete() }
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(file: File): List<HistoryEntry> {
        if (!file.isFile) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(
                time = o.optLong("time"),
                packageName = o.optString("pkg").ifBlank { null },
                label = o.optString("label").ifBlank { null },
                versionName = o.optString("versionName").ifBlank { null },
                versionCode = o.optLong("versionCode"),
                bundleName = o.optString("bundle"),
                backend = o.optString("backend"),
                ok = o.optBoolean("ok"),
                verdict = o.optString("verdict").ifBlank { null },
                message = o.optString("message").ifBlank { null },
                primaryCpuAbi = o.optString("abi").ifBlank { null },
                splits = o.optInt("splits"),
                repair = o.optBoolean("repair"),
            )
        }
    }

    private fun write(file: File, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("time", e.time)
                    .put("pkg", e.packageName.orEmpty())
                    .put("label", e.label.orEmpty())
                    .put("versionName", e.versionName.orEmpty())
                    .put("versionCode", e.versionCode)
                    .put("bundle", e.bundleName)
                    .put("backend", e.backend)
                    .put("ok", e.ok)
                    .put("verdict", e.verdict.orEmpty())
                    .put("message", e.message.orEmpty())
                    .put("abi", e.primaryCpuAbi.orEmpty())
                    .put("splits", e.splits)
                    .put("repair", e.repair)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
