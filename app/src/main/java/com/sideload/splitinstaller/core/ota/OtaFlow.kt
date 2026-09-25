package com.sideload.splitinstaller.core.ota

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.AppVisibility
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.otaProblemRes
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.update.Http
import com.sideload.splitinstaller.core.watch.Notifications

/**
 * Checking for this app's own update, and fetching it.
 *
 * Installing it is [OtaInstallWorker]'s job, because the process does not survive that.
 */
object OtaFlow {

    /**
     * @param auto true for the background schedule and the check on opening the app, which
     *             may also start the download; a check you asked for never downloads by itself.
     */
    suspend fun check(context: Context, auto: Boolean): OtaStatus {
        val prefs = Prefs.get(context)
        val asked = prefs.otaChannel
        val channel = OtaChannel.parse(asked)
        OtaStore.load(context)
        OtaStore.setChecking(true)
        val status = try {
            Ota.check(context, channel)
        } finally {
            OtaStore.setChecking(false)
        }
        if (Prefs.get(context).otaChannel != asked) {
            EventLog.info("OTA: the channel changed during the check; its answer is dropped")
            return OtaStore.status.value
        }
        Ota.log(status)
        OtaStore.put(context, status)

        val current = OtaStore.status.value
        val release = current.release
        if (!auto || release == null) return current

        // A build whose file was refused is not fetched again by itself; a tap still can.
        val refused = release.identity == current.refusedKey
        if (current.state == OtaState.UPDATE && !refused) {
            if (prefs.otaAutoDownload) {
                startDownload(context, release, automatic = true)
            } else if (!AppVisibility.foreground) {
                Notifications.otaAvailable(context, release.versionName ?: "?")
            }
        }

        // A build downloaded and checked but put off, because the app was open or busy: a later
        // background run is the moment to install it — if it can be installed without asking.
        val file = current.fileUri
        if (current.state == OtaState.READY && file != null && prefs.otaInstallsItself &&
            OtaStore.mayInstallItself && !AppVisibility.foreground && silentBackend(context) != null
        ) {
            OtaInstallWorker.enqueue(context, Uri.parse(file), current.fileSourceUrl ?: release.url)
        }
        return current
    }

    /**
     * Fetches the build, once. A finished file is checked again rather than fetched again, a
     * transfer under way is left alone, and a failed one is not retried by itself.
     *
     * @param automatic true when nobody tapped anything: then "Wi-Fi only" applies, as it
     *                  does to the background schedule. A tap overrides a transfer that is
     *                  waiting for Wi-Fi, and retries a failed one.
     * @return true when a new download was started.
     */
    fun startDownload(context: Context, release: OtaRelease, automatic: Boolean): Boolean {
        val own = Downloads.list(context).filter {
            it.expectedPackage == BuildConfig.APPLICATION_ID && it.sourceUrl == release.url
        }
        own.firstOrNull { it.status == DownloadStatus.DONE && it.uri != null }?.let { done ->
            val file = done.uri!!
            val same = fileExists(context, file) &&
                (release.sha256 == null || release.sha256.equals(Ota.sha256(context, file), ignoreCase = true))
            if (same) {
                OtaInstallWorker.enqueue(context, file, release.url)
                return false
            }
            // Deleted, or a different build published under the same address: fetch it anew.
            Downloads.cancel(context, done.id)
        }
        own.firstOrNull { it.active }?.let { running ->
            if (automatic || !running.waitingForWifi) return false
            Downloads.cancel(context, running.id)
        }
        own.firstOrNull { it.status == DownloadStatus.FAILED }?.let { failed ->
            if (automatic) return false
            Downloads.cancel(context, failed.id)
        }

        val wifiOnly = automatic && Prefs.get(context).updateWifiOnly
        if (wifiOnly && isMetered(context)) {
            EventLog.info("OTA: " + (release.versionName ?: "?") + " waits for an unmetered network")
            return false
        }
        EventLog.info("OTA: downloading " + release.fileName)
        Downloads.start(
            context,
            DownloadRequest(
                url = release.url,
                userAgent = Http.USER_AGENT,
                contentDisposition = null,
                mimeType = "application/vnd.android.package-archive",
                referer = null,
            ),
            autoInstall = false,
            expectedPackage = BuildConfig.APPLICATION_ID,
            // Pauses on mobile data rather than finishing there, when Wi-Fi only is on.
            allowMetered = !wifiOnly,
        )
        return true
    }

    /**
     * Reads the downloaded file and decides whether it may replace this copy.
     *
     * The SHA-256 is taken from the release the file was downloaded for, never from whatever
     * release is stored by the time the check runs: a manifest that moved on during the
     * download must not turn a good file into a "mismatch".
     */
    fun inspectAndVerify(context: Context, uri: Uri, sourceUrl: String?): Pair<BundleInfo?, String?> {
        val info = runCatching { BundleInspector.inspect(context, uri) }.getOrNull()
            ?: return null to OtaProblem.UNREADABLE
        val release = OtaStore.status.value.release
        val expected = release?.sha256?.takeIf { sourceUrl != null && release.url == sourceUrl }
        val actual = if (expected != null) Ota.sha256(context, uri) else null
        return info to Ota.verify(context, info, expected, actual)
    }

    /** A file that failed the checks: say why once, forget it, and do not fetch it again by itself. */
    fun refuse(context: Context, uri: Uri, sourceUrl: String?, problem: String) {
        val reason = context.getString(otaProblemRes(problem))
        EventLog.error("OTA refused: $reason")
        val release = OtaStore.status.value.release
        val key = if (release != null && (sourceUrl == null || release.url == sourceUrl)) release.identity else sourceUrl
        OtaStore.markRefused(context, key, reason)
        Downloads.removeByUri(context, uri.toString())
        Notifications.result(context, context.getString(R.string.ota_refused_title), reason, null)
    }

    /** The "ready, tap to install" notification, once per build, and not while the app is open. */
    fun notifyReadyOnce(context: Context, versionName: String?) {
        val key = OtaStore.status.value.release?.identity ?: versionName ?: return
        if (OtaStore.status.value.readyNotifiedKey == key || AppVisibility.foreground) return
        OtaStore.markReadyNotified(context, key)
        Notifications.otaReady(context, versionName ?: "?")
    }

    /** Whether a downloaded file can still be opened; the user may have deleted it. */
    fun fileExists(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }
            .getOrDefault(false)

    fun silentBackend(context: Context): BackendKind? =
        BackendResolver.probe(context).capabilities
            .firstOrNull { it.state == BackendState.READY && it.silent }?.kind

    private fun isMetered(context: Context): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }
            .getOrNull() ?: true
}
