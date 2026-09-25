package com.sideload.splitinstaller.core.ota

import android.content.Context
import android.net.Uri
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sources.DownloadRequest
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
     * @param auto true when this came from the background schedule, which may also start
     *             the download; a check you asked for never downloads behind your back.
     */
    suspend fun check(context: Context, auto: Boolean): OtaStatus {
        val prefs = Prefs.get(context)
        val channel = OtaChannel.parse(prefs.otaChannel)
        OtaStore.load(context)
        OtaStore.setChecking(true)
        val status = try {
            Ota.check(context, channel)
        } finally {
            OtaStore.setChecking(false)
        }
        Ota.log(status)
        OtaStore.put(context, status)

        val release = status.release
        if (status.state == OtaState.UPDATE && release != null && auto && prefs.otaAutoDownload) {
            startDownload(context, release)
        }
        if (status.state == OtaState.UPDATE && release != null && auto && !prefs.otaAutoDownload) {
            Notifications.otaAvailable(context, release.versionName ?: "?")
        }
        return status
    }

    /**
     * The file is tracked as ours by [DownloadRequest]'s expected package, which is how
     * [Downloads] knows to hand it to [OtaInstallWorker] rather than the ordinary flow.
     */
    fun startDownload(context: Context, release: OtaRelease) {
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
        )
    }

    /** Reads the downloaded file and decides whether it is allowed to replace this copy. */
    fun inspectAndVerify(context: Context, uri: Uri): Pair<com.sideload.splitinstaller.core.bundle.BundleInfo?, String?> {
        val info = runCatching { BundleInspector.inspect(context, uri) }.getOrNull()
            ?: return null to OtaProblem.UNREADABLE
        val expected = OtaStore.status.value.release?.sha256
        val actual = if (expected != null) Ota.sha256(context, uri) else null
        return info to Ota.verify(context, info, expected, actual)
    }

    fun silentBackend(context: Context): BackendKind? =
        BackendResolver.probe(context).capabilities
            .firstOrNull { it.state == BackendState.READY && it.silent }?.kind
}
