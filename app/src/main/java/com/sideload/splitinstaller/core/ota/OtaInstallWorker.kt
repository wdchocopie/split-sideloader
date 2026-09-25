package com.sideload.splitinstaller.core.ota

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.otaProblemRes
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.SplitSelector
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.install.InstallRequest
import com.sideload.splitinstaller.core.install.Installer
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks a downloaded copy of this app, and installs it over the running one.
 *
 * The install ends this process — Android stops an app it is replacing — so nothing after
 * the commit is guaranteed to run. The attempt is therefore written down first, and
 * [OtaStore.reportAttempt] reads it back on the next launch to say how it went.
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
        val userAsked = inputData.getBoolean(KEY_USER_ASKED, false)
        OtaStore.load(context)

        val (info, problem) = OtaFlow.inspectAndVerify(context, uri)
        if (problem != null || info == null) {
            val reason = context.getString(otaProblemRes(problem ?: OtaProblem.UNREADABLE))
            EventLog.error("OTA refused: $reason")
            OtaStore.setFile(context, null)
            OtaStore.put(
                context,
                OtaStore.status.value.copy(
                    state = OtaState.ERROR,
                    message = reason,
                    checkedAt = System.currentTimeMillis(),
                ),
            )
            Notifications.result(context, context.getString(R.string.ota_refused_title), reason, null)
            return@withContext Result.success()
        }

        OtaStore.setFile(context, uri.toString())
        OtaStore.put(
            context,
            OtaStore.status.value.copy(state = OtaState.READY, checkedAt = System.currentTimeMillis(), message = null),
        )

        val backend = OtaFlow.silentBackend(context)
        val prefs = Prefs.get(context)
        val mayInstall = userAsked || (prefs.otaAutoInstall && OtaStore.mayInstallItself)
        if (backend == null || !mayInstall) {
            // Without a silent method Android needs a visible confirmation, and an app
            // cannot put that on screen from the background.
            Notifications.otaReady(context, info.versionName ?: "?")
            return@withContext Result.success()
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
            Notifications.result(context, context.getString(R.string.ota_failed_title), outcome.message, uri)
        }
        Result.success()
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_USER_ASKED = "user"

        fun enqueue(context: Context, uri: Uri, userAsked: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<OtaInstallWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_USER_ASKED to userAsked))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
