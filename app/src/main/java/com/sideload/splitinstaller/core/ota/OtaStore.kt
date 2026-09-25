package com.sideload.splitinstaller.core.ota

import android.content.Context
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.log.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * What the app knows about its own next version, kept across launches.
 *
 * It has to survive a launch, because an app cannot watch itself being updated: the system
 * kills the process the moment the new copy lands. So the attempt is written down first,
 * and read back on the next start to see how it went.
 */
object OtaStore {

    private const val FILE = "ota.json"

    private val _status = MutableStateFlow(OtaStatus())
    val status: StateFlow<OtaStatus> = _status

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _status.value = runCatching { read(file(context)) }.getOrDefault(OtaStatus())
            loaded = true
        }
    }

    fun setChecking(value: Boolean) {
        _checking.value = value
    }

    /** Keeps the attempt counters, which belong to this device rather than to a check. */
    fun put(context: Context, status: OtaStatus) {
        load(context)
        synchronized(this) {
            val current = _status.value
            val sameBuild = current.release != null && current.release == status.release
            val next = status.copy(
                attemptedVersion = current.attemptedVersion,
                attemptFailures = current.attemptFailures,
                // A check that finds the same build again keeps the file already fetched.
                fileUri = status.fileUri ?: current.fileUri.takeIf { sameBuild },
                state = if (sameBuild && current.state == OtaState.READY && status.state == OtaState.UPDATE) {
                    OtaState.READY
                } else {
                    status.state
                },
            )
            _status.value = next
            runCatching { write(file(context), next) }
        }
    }

    fun setFile(context: Context, uri: String?) = update(context) { it.copy(fileUri = uri) }

    /** Written before the install starts, since the process will not be alive after it. */
    fun markAttempt(context: Context, versionCode: Long) = update(context) {
        it.copy(attemptedVersion = versionCode)
    }

    /**
     * Called on every launch. If an attempt was recorded and this copy is that version, the
     * update worked; otherwise it did not, and after two tries the app stops trying by
     * itself rather than looping on a build that will not install here.
     */
    fun reportAttempt(context: Context): Boolean {
        load(context)
        val current = _status.value
        val attempted = current.attemptedVersion
        if (attempted <= 0) return false
        return if (BuildConfig.VERSION_CODE >= attempted) {
            EventLog.info("OTA: now running " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            update(context) {
                it.copy(
                    state = OtaState.UP_TO_DATE,
                    fileUri = null,
                    attemptedVersion = 0,
                    attemptFailures = 0,
                )
            }
            true
        } else {
            val failures = current.attemptFailures + 1
            EventLog.warn("OTA: the update to versionCode $attempted did not take (attempt $failures)")
            update(context) { it.copy(attemptedVersion = 0, attemptFailures = failures) }
            false
        }
    }

    /** Two failed self-installs is enough; after that it waits to be asked. */
    val mayInstallItself: Boolean get() = _status.value.attemptFailures < 2

    fun clearFailures(context: Context) = update(context) { it.copy(attemptFailures = 0) }

    private fun update(context: Context, transform: (OtaStatus) -> OtaStatus) {
        load(context)
        synchronized(this) {
            val next = transform(_status.value)
            _status.value = next
            runCatching { write(file(context), next) }
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(file: File): OtaStatus {
        if (!file.isFile) return OtaStatus()
        val o = JSONObject(file.readText())
        val release = o.optJSONObject("release")?.let { r ->
            OtaRelease(
                versionName = r.optString("versionName").ifBlank { null },
                versionCode = r.optLong("versionCode"),
                url = r.optString("url"),
                fileName = r.optString("fileName"),
                sha256 = r.optString("sha256").ifBlank { null },
                size = r.optLong("size"),
                notes = r.optString("notes").ifBlank { null },
                pageUrl = r.optString("pageUrl").ifBlank { null },
                minSdk = r.optInt("minSdk"),
            )
        }
        return OtaStatus(
            state = runCatching { OtaState.valueOf(o.optString("state")) }.getOrNull() ?: OtaState.OFF,
            release = release,
            checkedAt = o.optLong("at"),
            message = o.optString("message").ifBlank { null },
            fileUri = o.optString("file").ifBlank { null },
            attemptedVersion = o.optLong("attempted"),
            attemptFailures = o.optInt("failures"),
        )
    }

    private fun write(file: File, status: OtaStatus) {
        val o = JSONObject()
            .put("state", status.state.name)
            .put("at", status.checkedAt)
            .put("message", status.message.orEmpty())
            .put("file", status.fileUri.orEmpty())
            .put("attempted", status.attemptedVersion)
            .put("failures", status.attemptFailures)
        status.release?.let { r ->
            o.put(
                "release",
                JSONObject()
                    .put("versionName", r.versionName.orEmpty())
                    .put("versionCode", r.versionCode)
                    .put("url", r.url)
                    .put("fileName", r.fileName)
                    .put("sha256", r.sha256.orEmpty())
                    .put("size", r.size)
                    .put("notes", r.notes.orEmpty())
                    .put("pageUrl", r.pageUrl.orEmpty())
                    .put("minSdk", r.minSdk)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
