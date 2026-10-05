package com.sideload.splitinstaller.core.ota

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.AppVisibility
import com.sideload.splitinstaller.core.Busy
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.SplitSelector
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.install.InstallRequest
import com.sideload.splitinstaller.core.install.Installer
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Checks a downloaded copy of this app, and installs it over the running one.
 *
 * The install ends this process — Android stops an app it is replacing — so nothing after
 * the commit is guaranteed to run. The attempt is therefore written down first, and
 * [OtaStore.reportAttempt] reads it back on the next launch to say how it went.
 *
 * Runs as unique work, so there is never more than one of these at a time.
 */
class OtaInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val text = applicationContext.getString(R.string.ota_installing)
        val notification = Notifications.watching(applicationContext, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_OTA_WORK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_OTA_WORK, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = Languages.wrap(applicationContext)
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return@withContext Result.failure()
        val sourceUrl = inputData.getString(KEY_SOURCE)
        val userAsked = inputData.getBoolean(KEY_USER_ASKED, false)
        val prefs = Prefs.get(context)
        OtaStore.load(context)

        // Clearing the channel turns OTA off, including a download that was already under way.
        if (!userAsked && !OtaChannel.parse(prefs.otaChannel).isOn) return@withContext Result.success()

        // A run that no longer matches what is being waited for ends quietly: one left over from
        // before a successful update (whoever started it — the tap's own install ended the
        // process), or, for automatic runs, an older build's file whose run was put off.
        val release = OtaStore.status.value.release
        val done = release == null || Ota.isNewer(release) != true
        val superseded = !userAsked && release != null && sourceUrl != null && sourceUrl != release.url
        if (done || superseded) {
            EventLog.info("OTA: dropping an install run that is no longer current")
            if (OtaStore.status.value.fileUri != uri.toString()) Downloads.removeByUri(context, uri.toString())
            if (done) OtaStore.reconcile(context, cancelWork = false)
            return@withContext Result.success()
        }

        // The file may have been deleted meanwhile; that is not a bad build.
        if (!OtaFlow.fileExists(context, uri)) {
            val stored = OtaStore.status.value
            OtaStore.clearMissingFile(context, uri.toString())
            // Someone asked for this build: fetch it again and carry on, rather than stop.
            val release = stored.release
            if (release != null && (userAsked || stored.installRequested)) {
                OtaStore.requestInstall(context, release.identity)
                OtaFlow.startDownload(context, release, automatic = false)
            }
            return@withContext Result.success()
        }

        val (info, problem) = OtaFlow.inspectAndVerify(context, uri, sourceUrl)
        if (problem != null || info == null) {
            OtaFlow.refuse(context, uri, sourceUrl, problem ?: OtaProblem.UNREADABLE)
            return@withContext Result.success()
        }

        OtaStore.setReady(context, uri.toString(), sourceUrl)

        // Without Shizuku or root Android asks first, and only an app on screen can be asked:
        // the card on the Install tab does that.
        val backend = OtaFlow.silentBackend(context)
        if (backend == null) {
            OtaFlow.notifyReadyOnce(context, info.versionName)
            return@withContext Result.success()
        }

        // A tap on "Update" for this build counts as asking, even when it was the download
        // finishing that started this run. A retried run only still counts while the tap does:
        // "Later" withdraws it.
        val requested = OtaStore.status.value.installRequested
        val asked = requested || (userAsked && runAttemptCount == 0)
        if (!asked) {
            if (!prefs.otaInstallsItself || !OtaStore.mayInstallItself) {
                OtaFlow.notifyReadyOnce(context, info.versionName)
                return@withContext Result.success()
            }
            // Replacing the app closes it: never under someone using it, and never in the middle
            // of an install or a backup, which would be cut off with it.
            if (AppVisibility.foreground || Busy.any) {
                OtaFlow.notifyReadyOnce(context, info.versionName)
                return@withContext if (runAttemptCount < MAX_DEFERRALS) Result.retry() else Result.success()
            }
        }
        // Even when asked, an install or backup already running is let finish first — waited
        // for here, so the update follows straight after it rather than a quarter-hour later.
        if (Busy.any) {
            val cleared = withTimeoutOrNull(BUSY_WAIT_MS) { Busy.count.first { it == 0 } } != null
            if (!cleared) {
                return@withContext if (runAttemptCount < MAX_DEFERRALS) Result.retry() else Result.success()
            }
        }

        EventLog.rule("OTA: installing " + (info.versionName ?: "?") + " (" + info.versionCode + ")")
        OtaStore.markAttempt(context, info.versionCode)
        runCatching { setForeground(getForegroundInfo()) }

        val choice = SplitSelector.autoSelect(context, info)
        val outcome = Installer(context).install(
            InstallRequest(
                info = info,
                selected = choice.selected,
                backend = backend,
                installObb = false,
                allowDowngrade = false,
                grantAllPermissions = false,
                verifyChecksums = prefs.verifyChecksums,
                backupFirst = false,
            )
        ) { event ->
            if (event is InstallEvent.Log) EventLog.add(event.severity, event.message)
        }

        // Reached only when the install did not replace this process, i.e. it failed.
        if (outcome is InstallOutcome.Failed) {
            EventLog.error("OTA install failed: " + outcome.message)
            // Counted here, since the process survived; waiting for the next launch to notice
            // would let a background run try the same build again before then.
            OtaStore.recordFailure(context)
            // The silent route failed for this build, which says nothing about the build: the
            // next try at it goes through the system dialog. A tap on Update stays in force, so
            // with the app on screen the dialog follows at once; otherwise it waits for a tap.
            // Marked only for the build that was tried: a check meanwhile may have stored another.
            OtaStore.status.value.release
                ?.takeIf { it.versionCode == 0L || it.versionCode == info.versionCode }
                ?.let { OtaStore.markSilentFailed(context, it.identity) }
            EventLog.warn("OTA: $backend could not install it; the next try asks through the system dialog")
            // Always said: the dialog that should follow can fail too, before it ever shows.
            Notifications.result(context, context.getString(R.string.ota_failed_title), outcome.message, uri)
        }
        Result.success()
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_SOURCE = "source"
        private const val KEY_USER_ASKED = "user"

        /** The one queue OTA install runs go through; [OtaStore.retire] empties it. */
        const val UNIQUE = "ota-install"

        /** Waiting for the app to close or other work to finish: about nine hours at most. */
        private const val MAX_DEFERRALS = 8

        /** How long an asked run waits in place for other work; under WorkManager's ten minutes. */
        private const val BUSY_WAIT_MS = 8 * 60 * 1000L

        /**
         * @param replace true for a tap, and for a file that has just finished downloading,
         *                which supersedes whatever run is waiting; false for a re-check, which
         *                never disturbs a run already queued.
         */
        fun enqueue(
            context: Context,
            uri: Uri,
            sourceUrl: String?,
            userAsked: Boolean = false,
            replace: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<OtaInstallWorker>()
                .setInputData(
                    workDataOf(KEY_URI to uri.toString(), KEY_SOURCE to sourceUrl, KEY_USER_ASKED to userAsked)
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
