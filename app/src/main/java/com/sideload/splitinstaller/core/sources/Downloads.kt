package com.sideload.splitinstaller.core.sources

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.content.edit
import com.sideload.splitinstaller.core.AppVisibility
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.apps.AppExporter
import com.sideload.splitinstaller.core.bundle.BundleScanner
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.ota.OtaInstallWorker
import com.sideload.splitinstaller.core.update.InstallWorker
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

enum class DownloadStatus { PENDING, RUNNING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: Long,
    val fileName: String,
    val host: String?,
    val status: DownloadStatus,
    val bytes: Long,
    val total: Long,
    /** DownloadManager's reason code: an HTTP status for server refusals, ERROR_* otherwise. */
    val reason: Int,
    val uri: Uri?,
    val sourceUrl: String?,
    val time: Long,
    /** Install it as soon as it lands, without waiting for anyone to look at the screen. */
    val autoInstall: Boolean = false,
    /** The package this file is supposed to be, when it came from an update check. */
    val expectedPackage: String? = null,
    /**
     * Someone asked for this file to be installed — a link they confirmed, an app they picked
     * in a search — so it is installed as it lands: silently where it can be, otherwise through
     * the system's dialog while the app is on screen.
     */
    val installNow: Boolean = false,
) {
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val isBundle: Boolean get() = BundleScanner.isBundleName(fileName)
    val active: Boolean
        get() = status == DownloadStatus.PENDING || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED

    /** Held back by DownloadManager until an unmetered network is available. */
    val waitingForWifi: Boolean
        get() = status == DownloadStatus.PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI

    val reasonText: String
        get() = when (reason) {
            in 100..599 -> "HTTP $reason"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "connection dropped"
            DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file exists"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage missing"
            else -> "error $reason"
        }
}

/** What a web page hands over when you tap its download button. */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val referer: String?,
)

/**
 * Downloads started from the in-app browser.
 *
 * The system DownloadManager does the transfer: it survives the app being closed, resumes
 * over flaky networks, and handles the multi-gigabyte files games ship. The page's own
 * cookies and user agent travel with the request, so the site sees the same visitor that
 * pressed its download button.
 */
object Downloads {

    private const val PREFS = "downloads"
    private const val KEY_TRACKED = "tracked"
    private const val KEY_HANDLED = "handled"

    private val _completed = MutableSharedFlow<DownloadItem>(extraBufferCapacity = 8)
    /** Finished downloads, for whoever is on screen to act on. */
    val completed: SharedFlow<DownloadItem> = _completed

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Something was started or removed, from anywhere in the app: lists should look again. */
    val changed: SharedFlow<Unit> = _changed

    fun start(
        context: Context,
        request: DownloadRequest,
        autoInstall: Boolean = false,
        expectedPackage: String? = null,
        /** False pauses the transfer on mobile data until an unmetered network is back. */
        allowMetered: Boolean = true,
        /** See [DownloadItem.installNow]. */
        installNow: Boolean = false,
        /** The in-app browser's cookies for that site: wanted for a page's button, not for a pasted link. */
        withCookies: Boolean = true,
    ): DownloadItem {
        val dm = context.getSystemService(DownloadManager::class.java)
        val name = DownloadNames.fileName(request.url, request.contentDisposition, request.mimeType)
        val uri = Uri.parse(request.url)
        // Named after the page the button was on: the file itself often comes from a CDN.
        val page = request.referer?.takeIf { it.startsWith("https://") }?.let { Uri.parse(it).host }
        val host = (page ?: uri.host)?.removePrefix("www.")

        val req = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription(host)
            .setMimeType(mimeFor(name, request.mimeType))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(true)
        request.userAgent?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("User-Agent", it) }
        if (withCookies) {
            runCatching { CookieManager.getInstance().getCookie(request.url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("Cookie", it) }
        }
        request.referer?.takeIf { it.startsWith("https://") }?.let { req.addRequestHeader("Referer", it) }
        setDestination(context, req, name)

        val id = dm.enqueue(req)
        track(context, id, Tracked(host, autoInstall, expectedPackage, request.url, installNow))
        _changed.tryEmit(Unit)
        EventLog.info(
            "download started: $name from ${host ?: "?"}" +
                if (autoInstall || installNow) " (installs itself when done)" else ""
        )
        return DownloadItem(
            id, name, host, DownloadStatus.PENDING, 0, -1, 0, null, request.url,
            System.currentTimeMillis(), autoInstall, expectedPackage, installNow,
        )
    }

    /** Download/SplitSideloader/, next to the backups, so every bundle this app touched is in one place. */
    private fun setDestination(context: Context, req: DownloadManager.Request, name: String) {
        val sharedOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || BackendResolver.hasAllFilesAccess(context)
        if (sharedOk) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AppExporter.FOLDER).mkdirs()
            }
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, AppExporter.FOLDER + "/" + name)
        } else {
            req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        }
    }

    /**
     * Bundles go out as octet-stream so MediaStore keeps their extension rather than
     * "correcting" it; plain APKs keep their real type.
     */
    private fun mimeFor(name: String, server: String?): String = when {
        name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
        BundleScanner.isBundleName(name) -> "application/octet-stream"
        !server.isNullOrBlank() -> server
        else -> "application/octet-stream"
    }

    fun list(context: Context): List<DownloadItem> {
        val tracked = tracked(context)
        if (tracked.isEmpty()) return emptyList()
        val dm = context.getSystemService(DownloadManager::class.java)
        val out = ArrayList<DownloadItem>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*tracked.keys.toLongArray()))?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val bytesCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                val timeCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val status = when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.DONE
                        else -> DownloadStatus.FAILED
                    }
                    val info = tracked[id]
                    out += DownloadItem(
                        id = id,
                        fileName = c.getString(titleCol).orEmpty(),
                        host = info?.host,
                        status = status,
                        bytes = c.getLong(bytesCol),
                        total = c.getLong(totalCol),
                        reason = c.getInt(reasonCol),
                        uri = if (status == DownloadStatus.DONE) runCatching { dm.getUriForDownloadedFile(id) }.getOrNull() else null,
                        sourceUrl = info?.requestedUrl ?: c.getString(uriCol),
                        time = c.getLong(timeCol),
                        autoInstall = info?.autoInstall == true,
                        expectedPackage = info?.expectedPackage,
                        installNow = info?.installNow == true,
                    )
                }
            }
        }
        // Forget downloads the system no longer knows about, e.g. cleared from its own list.
        val stale = tracked.keys - out.map { it.id }.toSet()
        if (stale.isNotEmpty()) untrack(context, stale)
        return out.sortedByDescending { it.time }
    }

    /** Stops a transfer and deletes the partial file. */
    fun cancel(context: Context, id: Long) {
        runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
        untrack(context, setOf(id))
        _changed.tryEmit(Unit)
    }

    /** Drops a finished download from the list; the file itself stays where it is. */
    fun forget(context: Context, id: Long) = untrack(context, setOf(id))

    /** Deletes one of our downloads, file included, by the uri it finished under. */
    fun removeByUri(context: Context, uri: String) {
        list(context).firstOrNull { it.uri?.toString() == uri }?.let { cancel(context, it.id) }
    }

    /** Stops every transfer still running for one package, e.g. when its update channel is cleared. */
    fun cancelActiveFor(context: Context, packageName: String) {
        list(context).filter { it.active && it.expectedPackage == packageName }.forEach { cancel(context, it.id) }
    }

    internal fun onFinished(context: Context, id: Long) {
        if (id !in tracked(context)) return
        val item = list(context).firstOrNull { it.id == id } ?: return
        // Once per download: the same one announced again must not install it again.
        if ((item.status == DownloadStatus.DONE || item.status == DownloadStatus.FAILED) && !firstTime(context, id)) return
        when (item.status) {
            DownloadStatus.DONE -> {
                EventLog.info("download finished: ${item.fileName}")
                val uri = item.uri
                when {
                    uri == null || !item.isBundle -> Unit
                    // Our own package never goes through the ordinary install flow: it has
                    // to be checked against the copy that is running first.
                    item.expectedPackage == BuildConfig.APPLICATION_ID ->
                        OtaInstallWorker.enqueue(context, uri, item.sourceUrl, replace = true)
                    // The worker checks there is a silent method before it installs anything.
                    item.autoInstall -> InstallWorker.enqueue(context, uri, item.expectedPackage, name = item.fileName)
                    item.installNow -> when {
                        BackendResolver.backgroundSilent(context, Prefs.get(context).backend) != null ->
                            InstallWorker.enqueue(context, uri, item.expectedPackage, asked = true, name = item.fileName)
                        // The app on screen opens it and asks through the system's dialog.
                        AppVisibility.foreground -> Unit
                        else -> Notifications.downloaded(context, item.fileName, uri)
                    }
                    else -> Notifications.downloaded(context, item.fileName, uri)
                }
            }
            DownloadStatus.FAILED -> {
                EventLog.error("download failed: ${item.fileName} (${item.reasonText})")
                Notifications.downloadFailed(context, item.fileName, item.reasonText)
            }
            else -> Unit
        }
        _completed.tryEmit(item)
    }

    // ---- which downloads are ours ------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Tracked(
        val host: String?,
        val autoInstall: Boolean,
        val expectedPackage: String?,
        /**
         * The URL as requested. DownloadManager replaces its own copy with the target of a
         * permanent redirect, so its column cannot say which release a file belongs to.
         */
        val requestedUrl: String? = null,
        val installNow: Boolean = false,
    )

    /**
     * id → what the download is for, kept as "id|host|auto|package|url" strings; the URL is last.
     * The auto slot is "1" for an automatic install, "2" for [DownloadItem.installNow] — which an
     * older build reads as not automatic, the safe side.
     */
    private fun tracked(context: Context): Map<Long, Tracked> =
        prefs(context).getStringSet(KEY_TRACKED, emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('|', limit = 5)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            id to Tracked(
                host = parts.getOrNull(1)?.ifBlank { null },
                autoInstall = parts.getOrNull(2) == "1",
                expectedPackage = parts.getOrNull(3)?.ifBlank { null },
                requestedUrl = parts.getOrNull(4)?.ifBlank { null },
                installNow = parts.getOrNull(2) == "2",
            )
        }.toMap()

    @Synchronized
    private fun track(context: Context, id: Long, info: Tracked) {
        val next = prefs(context).getStringSet(KEY_TRACKED, emptySet()).orEmpty().toMutableSet()
        next += listOf(
            id.toString(),
            info.host.orEmpty(),
            when {
                info.installNow -> "2"
                info.autoInstall -> "1"
                else -> "0"
            },
            info.expectedPackage.orEmpty(),
            info.requestedUrl.orEmpty(),
        ).joinToString("|")
        // The newest 50 by id: a set has no order, and dropping a running one would lose it.
        val kept = next.sortedBy { it.substringBefore('|').toLongOrNull() ?: 0L }.takeLast(50).toSet()
        prefs(context).edit { putStringSet(KEY_TRACKED, kept) }
    }

    /** True the first time a finished download is seen; the newest 100 are remembered. */
    @Synchronized
    private fun firstTime(context: Context, id: Long): Boolean {
        val seen = prefs(context).getStringSet(KEY_HANDLED, emptySet()).orEmpty()
        if (id.toString() in seen) return false
        val next = (seen + id.toString()).sortedBy { it.toLongOrNull() ?: 0L }.takeLast(100).toSet()
        prefs(context).edit { putStringSet(KEY_HANDLED, next) }
        return true
    }

    @Synchronized
    private fun untrack(context: Context, ids: Set<Long>) {
        val next = prefs(context).getStringSet(KEY_TRACKED, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|').toLongOrNull() in ids }
            .toSet()
        prefs(context).edit { putStringSet(KEY_TRACKED, next) }
    }
}

/**
 * The system announces every finished download to the app that queued it. The id is
 * checked against our own list and the result re-read from DownloadManager, so a forged
 * broadcast cannot make the app open a file it did not download.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        Thread {
            try {
                Downloads.onFinished(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
