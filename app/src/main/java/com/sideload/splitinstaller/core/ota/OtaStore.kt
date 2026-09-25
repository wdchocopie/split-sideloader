package com.sideload.splitinstaller.core.ota

import android.content.Context
import androidx.work.WorkManager
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sources.Downloads
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

    /** Stores the result of a check, merged with what this device already knows. */
    fun put(context: Context, status: OtaStatus) = update(context) { merge(it, status) }

    /**
     * The merge rule, kept pure so it can be tested:
     * - attempt counters and the refused/notified markers belong to the device, not to a check;
     * - a check that finds the same build again keeps the file already fetched, and READY;
     * - a check that failed outright says nothing about the build already known, so a pending
     *   UPDATE or READY survives it — only the time and the message change.
     */
    internal fun merge(current: OtaStatus, status: OtaStatus): OtaStatus {
        if (status.state == OtaState.ERROR && status.release == null && current.pending) {
            return current.copy(checkedAt = status.checkedAt, message = status.message)
        }
        val sameBuild = current.release != null && current.release == status.release
        val keepFile = sameBuild && status.fileUri == null
        return status.copy(
            attemptedVersion = current.attemptedVersion,
            attemptFailures = current.attemptFailures,
            refusedKey = current.refusedKey,
            readyNotifiedKey = current.readyNotifiedKey,
            fileUri = if (keepFile) current.fileUri else status.fileUri,
            fileSourceUrl = if (keepFile) current.fileSourceUrl else status.fileSourceUrl,
            state = if (sameBuild && current.state == OtaState.READY && status.state == OtaState.UPDATE) {
                OtaState.READY
            } else {
                status.state
            },
        )
    }

    /** The file passed every check and is waiting to be installed. */
    fun setReady(context: Context, uri: String, sourceUrl: String?) = update(context) {
        it.copy(
            state = OtaState.READY,
            fileUri = uri,
            fileSourceUrl = sourceUrl,
            message = null,
            checkedAt = System.currentTimeMillis(),
        )
    }

    /** The stored file is gone (deleted by the user, or cleaned up): back to "available". */
    fun clearMissingFile(context: Context, uri: String) = update(context) {
        if (it.fileUri != uri) {
            it
        } else {
            it.copy(
                fileUri = null,
                fileSourceUrl = null,
                state = if (it.state == OtaState.READY) OtaState.UPDATE else it.state,
            )
        }
    }

    fun markRefused(context: Context, key: String?, reason: String) = update(context) {
        it.copy(
            state = OtaState.ERROR,
            fileUri = null,
            fileSourceUrl = null,
            message = reason,
            refusedKey = key,
            checkedAt = System.currentTimeMillis(),
        )
    }

    fun markReadyNotified(context: Context, key: String) = update(context) { it.copy(readyNotifiedKey = key) }

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
            update(context) { it.copy(attemptedVersion = 0, attemptFailures = 0) }
            retire(context, OtaState.UP_TO_DATE)
            true
        } else {
            val failures = current.attemptFailures + 1
            EventLog.warn("OTA: the update to versionCode $attempted did not take (attempt $failures)")
            update(context) { it.copy(attemptedVersion = 0, attemptFailures = failures) }
            false
        }
    }

    /** Two failed self-installs is enough; after that it waits to be asked. */
    val mayInstallItself: Boolean get() = !_status.value.stoppedRetrying

    /** An install that failed without replacing the process: the attempt is over, and it counts. */
    fun recordFailure(context: Context) = update(context) {
        it.copy(attemptedVersion = 0, attemptFailures = it.attemptFailures + 1)
    }

    /**
     * On launch: a stored build that is not newer than the running one — because it was just
     * installed, by any route — is finished with.
     *
     * @param cancelWork false when called from the install worker itself.
     */
    fun reconcile(context: Context, cancelWork: Boolean = true) {
        load(context)
        val current = _status.value
        val release = current.release ?: return
        if (!current.pending) return
        if (Ota.isNewer(release) == true) return
        update(context) { it.copy(attemptFailures = 0, attemptedVersion = 0) }
        retire(context, OtaState.UP_TO_DATE, cancelWork = cancelWork)
    }

    /**
     * Ends everything that belongs to the stored build: its downloaded file is deleted, a
     * transfer still running for it is stopped, and a queued install run is dropped.
     *
     * @param forgetCheck also forget the stored answer, so the next check runs at once — used
     *                    when the channel changes and the old answer no longer applies.
     */
    fun retire(context: Context, state: OtaState, forgetCheck: Boolean = false, cancelWork: Boolean = true) {
        load(context)
        val current = _status.value
        runCatching { current.fileUri?.let { Downloads.removeByUri(context, it) } }
        runCatching { Downloads.cancelActiveFor(context, BuildConfig.APPLICATION_ID) }
        if (cancelWork) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(OtaInstallWorker.UNIQUE) }
        }
        update(context) {
            val base = it.copy(state = state, fileUri = null, fileSourceUrl = null)
            if (forgetCheck) base.copy(release = null, checkedAt = 0, message = null) else base
        }
    }

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
            fileSourceUrl = o.optString("fileSource").ifBlank { null },
            attemptedVersion = o.optLong("attempted"),
            attemptFailures = o.optInt("failures"),
            refusedKey = o.optString("refused").ifBlank { null },
            readyNotifiedKey = o.optString("notified").ifBlank { null },
        )
    }

    private fun write(file: File, status: OtaStatus) {
        val o = JSONObject()
            .put("state", status.state.name)
            .put("at", status.checkedAt)
            .put("message", status.message.orEmpty())
            .put("file", status.fileUri.orEmpty())
            .put("fileSource", status.fileSourceUrl.orEmpty())
            .put("attempted", status.attemptedVersion)
            .put("failures", status.attemptFailures)
            .put("refused", status.refusedKey.orEmpty())
            .put("notified", status.readyNotifiedKey.orEmpty())
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
