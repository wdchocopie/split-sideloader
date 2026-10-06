package com.sideload.splitinstaller.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
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
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitSelector
import com.sideload.splitinstaller.core.install.AutoInstall
import com.sideload.splitinstaller.core.install.AutoInstallBlock
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.install.InstallRequest
import com.sideload.splitinstaller.core.install.Installer
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.Verdict
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Installs a file that finished downloading, without anyone having to be looking: an update
 * of a pinned app, or a link someone asked to install.
 *
 * Only runs with a silent backend: the ordinary installer needs a visible activity for its
 * confirmation dialog, and throwing that on screen unprompted would be worse than a
 * notification.
 */
class InstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = inputData.getString(KEY_NAME)
        val text = if (inputData.getBoolean(KEY_ASKED, false) && name != null) {
            applicationContext.getString(R.string.installing_now, name)
        } else {
            applicationContext.getString(R.string.notif_installing)
        }
        val notification = Notifications.watching(applicationContext, text)
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
        val asked = inputData.getBoolean(KEY_ASKED, false)
        val fileName = inputData.getString(KEY_NAME) ?: uri.lastPathSegment.orEmpty()
        val prefs = Prefs.get(context)

        val backend = BackendResolver.backgroundSilent(context, prefs.backend)
        if (backend == null) {
            EventLog.warn("a downloaded file is ready, but no silent method is available to install it")
            if (asked) {
                Notifications.downloaded(context, fileName, uri)
            } else {
                Notifications.result(context, context.getString(R.string.notif_update_ready_title), uri.lastPathSegment.orEmpty(), uri)
            }
            return@withContext Result.success()
        }

        runCatching { setForeground(getForegroundInfo()) }

        // An install or a backup already running goes first: two at once only get in each other's way.
        if (Busy.any) withTimeoutOrNull(BUSY_WAIT_MS) { Busy.count.first { it == 0 } }

        val info = try {
            BundleInspector.inspect(context, uri)
        } catch (t: Throwable) {
            EventLog.error("could not read the downloaded file: " + t.message)
            Notifications.result(context, context.getString(R.string.notif_failed_title), t.message.orEmpty(), uri)
            return@withContext Result.failure()
        }

        val choice = SplitSelector.autoSelect(context, info)
        val problems = SplitSelector.validate(info, choice.selected)
        val installed = info.packageName?.let { InstallVerifier.installedVersion(context, it) }
        val block = AutoInstall.blockedBy(
            packageName = info.packageName,
            expectedPackage = expected,
            ownPackage = BuildConfig.APPLICATION_ID,
            bundleHasError = info.hasError,
            selectionProblems = problems.isNotEmpty(),
            signerMismatch = ApkSignatures.compare(context, info.packageName, info.signerSha256) == SignatureMatch.MISMATCH,
            installedVersionCode = installed?.first,
            versionCode = info.versionCode,
        )
        if (block != null) {
            EventLog.warn("not installing ${info.displayName} by itself: " + block.name.lowercase().replace('_', ' '))
            info.findings.filter { it.severity == Severity.ERROR }.forEach { EventLog.error(it.message) }
            problems.forEach { EventLog.add(it.severity, it.message) }
            if (block == AutoInstallBlock.OTHER_PACKAGE) {
                EventLog.error("downloaded file is ${info.packageName}, expected $expected")
                Notifications.result(context, context.getString(R.string.notif_failed_title), info.displayName, uri)
                return@withContext Result.failure()
            }
            // Someone can still look at it and decide: the notification opens it.
            if (asked) {
                Notifications.result(context, context.getString(R.string.notif_needs_look), info.displayName, uri)
            } else {
                Notifications.found(context, info.displayName, uri)
            }
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
                    when {
                        broken -> R.string.notif_broken_title
                        installed != null -> R.string.notif_updated_title
                        else -> R.string.notif_done_title
                    }
                )
                val detail = (info.appLabel ?: info.packageName ?: info.displayName) +
                    " " + (info.versionName ?: "") +
                    (outcome.report?.let { "\nprimaryCpuAbi=" + (it.primaryCpuAbi ?: "null") } ?: "")
                // Never a broken install, and never this app (which is not what was installed).
                val launchable = info.packageName?.takeIf { !broken && it != BuildConfig.APPLICATION_ID }
                val verdict = outcome.report?.verdict
                val works = verdict == Verdict.OK || verdict == Verdict.UNKNOWN
                // Only an install someone asked for opens by itself, not an update arriving on schedule.
                if (launchable != null && asked && works && prefs.openAfterInstall && AppVisibility.foreground) {
                    // With a screen of this app showing, starting another app's activity is allowed.
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage(launchable)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let(context::startActivity)
                    }.onFailure { EventLog.warn("could not open $launchable: " + it.message) }
                }
                Notifications.result(context, title, detail, uri, launchPackage = launchable)
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

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_PACKAGE = "package"
        private const val KEY_ASKED = "asked"
        private const val KEY_NAME = "name"
        private const val BUSY_WAIT_MS = 10 * 60 * 1000L

        /**
         * [asked]: someone asked for this file to be installed (a link, a search result) rather
         * than it being an update that arrived by itself. [name]: the file's name, for notices.
         */
        fun enqueue(context: Context, uri: Uri, packageName: String?, asked: Boolean = false, name: String? = null) {
            val request = OneTimeWorkRequestBuilder<InstallWorker>()
                .setInputData(
                    workDataOf(KEY_URI to uri.toString(), KEY_PACKAGE to packageName, KEY_ASKED to asked, KEY_NAME to name)
                )
                .build()
            // Once per file: a second completion broadcast or a double tap must not install it twice.
            WorkManager.getInstance(context).enqueueUniqueWork("install-$uri", ExistingWorkPolicy.KEEP, request)
        }
    }
}
