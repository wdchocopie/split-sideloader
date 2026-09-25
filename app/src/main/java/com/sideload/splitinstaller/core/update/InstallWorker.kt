package com.sideload.splitinstaller.core.update

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
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitSelector
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.install.InstallRequest
import com.sideload.splitinstaller.core.install.Installer
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.verify.Verdict
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs an update that finished downloading, without anyone having to be looking.
 *
 * Only runs with a silent backend: the ordinary installer needs a visible activity for its
 * confirmation dialog, and throwing that on screen unprompted would be worse than a
 * notification.
 */
class InstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.watching(applicationContext, applicationContext.getString(R.string.notif_installing))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_WORK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_WORK, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = Languages.wrap(applicationContext)
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return@withContext Result.failure()
        val expected = inputData.getString(KEY_PACKAGE)
        val prefs = Prefs.get(context)

        val backend = silentBackend()
        if (backend == null) {
            EventLog.warn("downloaded update is ready, but no silent method is available to install it")
            Notifications.result(context, context.getString(R.string.notif_update_ready_title), uri.lastPathSegment.orEmpty(), uri)
            return@withContext Result.success()
        }

        runCatching { setForeground(getForegroundInfo()) }

        val info = try {
            BundleInspector.inspect(context, uri)
        } catch (t: Throwable) {
            EventLog.error("could not read the downloaded update: " + t.message)
            Notifications.result(context, context.getString(R.string.notif_failed_title), t.message.orEmpty(), uri)
            return@withContext Result.failure()
        }

        if (expected != null && info.packageName != null && info.packageName != expected) {
            EventLog.error("downloaded file is ${info.packageName}, expected $expected; not installing")
            Notifications.result(context, context.getString(R.string.notif_failed_title), info.displayName, uri)
            return@withContext Result.failure()
        }
        if (info.hasError) {
            info.findings.filter { it.severity == Severity.ERROR }.forEach { EventLog.error(it.message) }
            Notifications.found(context, info.displayName, uri)
            return@withContext Result.success()
        }

        val choice = SplitSelector.autoSelect(context, info)
        val problems = SplitSelector.validate(info, choice.selected)
        if (problems.isNotEmpty()) {
            problems.forEach { EventLog.add(it.severity, it.message) }
            Notifications.found(context, info.displayName, uri)
            return@withContext Result.success()
        }

        EventLog.rule("auto-installing " + info.displayName)
        val outcome = Installer(context).install(
            InstallRequest(
                info = info,
                selected = choice.selected,
                backend = backend,
                installObb = prefs.installObb,
                allowDowngrade = prefs.allowDowngrade,
                grantAllPermissions = prefs.grantAllPermissions,
                verifyChecksums = prefs.verifyChecksums,
                backupFirst = prefs.backupBeforeUpdate,
            )
        ) { event ->
            if (event is InstallEvent.Log) EventLog.add(event.severity, event.message)
        }

        when (outcome) {
            is InstallOutcome.Ok -> {
                val broken = outcome.report?.verdict == Verdict.BROKEN
                val title = context.getString(
                    if (broken) R.string.notif_broken_title else R.string.notif_updated_title
                )
                val detail = (info.appLabel ?: info.packageName ?: info.displayName) +
                    " " + (info.versionName ?: "") +
                    (outcome.report?.let { "\nprimaryCpuAbi=" + (it.primaryCpuAbi ?: "null") } ?: "")
                Notifications.result(context, title, detail, uri)
                if (info.packageName != null) {
                    UpdateStore.remove(context, info.packageName)
                }
            }
            is InstallOutcome.Failed -> Notifications.result(
                context,
                context.getString(R.string.notif_failed_title),
                outcome.message,
                uri,
            )
        }
        Result.success()
    }

    private fun silentBackend(): BackendKind? {
        val report = BackendResolver.probe(applicationContext)
        return report.capabilities.firstOrNull { it.state == BackendState.READY && it.silent }?.kind
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_PACKAGE = "package"

        fun enqueue(context: Context, uri: Uri, packageName: String?) {
            val request = OneTimeWorkRequestBuilder<InstallWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_PACKAGE to packageName))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
