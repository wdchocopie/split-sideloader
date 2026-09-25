package com.sideload.splitinstaller.core.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The last answer from each source, so the app can show it without asking again. */
object UpdateStore {

    private const val FILE = "updates.json"

    private val _results = MutableStateFlow<Map<String, UpdateResult>>(emptyMap())
    val results: StateFlow<Map<String, UpdateResult>> = _results

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _results.value = runCatching { read(file(context)) }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    fun setChecking(value: Boolean) {
        _checking.value = value
    }

    fun put(context: Context, result: UpdateResult) {
        load(context)
        synchronized(this) {
            val next = _results.value + (result.packageName to result)
            _results.value = next
            runCatching { write(file(context), next) }
        }
    }

    fun remove(context: Context, packageName: String) {
        load(context)
        synchronized(this) {
            val next = _results.value - packageName
            _results.value = next
            runCatching { write(file(context), next) }
        }
    }

    val updateCount: Int get() = _results.value.values.count { it.hasUpdate }

    /** Pinned pages the app cannot read a version from, unchecked for longer than [days]. */
    fun staleManual(days: Int, now: Long = System.currentTimeMillis()): List<UpdateResult> =
        _results.value.values.filter {
            it.kind == UpdateKind.WEB && now - it.checkedAt > days * 86_400_000L
        }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(file: File): Map<String, UpdateResult> {
        if (!file.isFile) return emptyMap()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("pkg").ifBlank { return@mapNotNull null }
            val kind = runCatching { UpdateKind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val state = runCatching { UpdateState.valueOf(o.optString("state")) }.getOrNull() ?: return@mapNotNull null
            pkg to UpdateResult(
                packageName = pkg,
                kind = kind,
                state = state,
                label = o.optString("label").ifBlank { null },
                installedVersionName = o.optString("installed").ifBlank { null },
                installedVersionCode = o.optLong("installedCode"),
                availableVersionName = o.optString("available").ifBlank { null },
                availableVersionCode = o.optLong("availableCode").takeIf { it > 0 },
                downloadUrl = o.optString("url").ifBlank { null },
                fileName = o.optString("file").ifBlank { null },
                size = o.optLong("size"),
                pageUrl = o.optString("page").ifBlank { null },
                checkedAt = o.optLong("at"),
                message = o.optString("message").ifBlank { null },
            )
        }.toMap()
    }

    private fun write(file: File, results: Map<String, UpdateResult>) {
        val arr = JSONArray()
        results.values.forEach {
            arr.put(
                JSONObject()
                    .put("pkg", it.packageName)
                    .put("kind", it.kind.name)
                    .put("state", it.state.name)
                    .put("label", it.label.orEmpty())
                    .put("installed", it.installedVersionName.orEmpty())
                    .put("installedCode", it.installedVersionCode)
                    .put("available", it.availableVersionName.orEmpty())
                    .put("availableCode", it.availableVersionCode ?: 0L)
                    .put("url", it.downloadUrl.orEmpty())
                    .put("file", it.fileName.orEmpty())
                    .put("size", it.size)
                    .put("page", it.pageUrl.orEmpty())
                    .put("at", it.checkedAt)
                    .put("message", it.message.orEmpty())
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
