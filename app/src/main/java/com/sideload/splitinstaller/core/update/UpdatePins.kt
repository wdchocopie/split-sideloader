package com.sideload.splitinstaller.core.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Which source each package takes its updates from. Small, local, and yours to change. */
object UpdatePins {

    private const val PREFS = "updates"
    private const val KEY_PINS = "pins"

    private val _pins = MutableStateFlow<Map<String, UpdatePin>>(emptyMap())
    val pins: StateFlow<Map<String, UpdatePin>> = _pins

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _pins.value = read(context)
            loaded = true
        }
    }

    operator fun get(packageName: String): UpdatePin? = _pins.value[packageName]

    fun pin(context: Context, pin: UpdatePin) {
        load(context)
        synchronized(this) {
            val next = _pins.value + (pin.packageName to pin)
            write(context, next)
            _pins.value = next
        }
    }

    fun unpin(context: Context, packageName: String) {
        load(context)
        synchronized(this) {
            val next = _pins.value - packageName
            write(context, next)
            _pins.value = next
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Map<String, UpdatePin> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_PINS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("pkg").ifBlank { return@mapNotNull null }
            val kind = runCatching { UpdateKind.valueOf(o.optString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            val value = o.optString("value").ifBlank { return@mapNotNull null }
            pkg to UpdatePin(pkg, kind, value, o.optString("label").ifBlank { null }, o.optLong("at"))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun write(context: Context, pins: Map<String, UpdatePin>) {
        val arr = JSONArray()
        pins.values.forEach {
            arr.put(
                JSONObject()
                    .put("pkg", it.packageName)
                    .put("kind", it.kind.name)
                    .put("value", it.value)
                    .put("label", it.label.orEmpty())
                    .put("at", it.pinnedAt)
            )
        }
        prefs(context).edit { putString(KEY_PINS, arr.toString()) }
    }
}
