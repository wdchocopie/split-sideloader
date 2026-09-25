package com.sideload.splitinstaller.core.watch

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.failureHintRes
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.bundle.BundleScanner
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
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Watches the download folders and reacts when a bundle lands.
 *
 * What "reacts" means depends on what the device allows. With a silent backend it can
 * install outright; without one, Android insists on a confirmation per install, so the
 * honest behaviour is a notification rather than a dialog appearing over whatever you were
 * doing.
 */
class WatchService : LifecycleService() {

    private val observers = ArrayList<FileObserver>()
    private val installing = Mutex()
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val dirs = BundleScanner.watchDirs(this)
        startInForeground(getString(R.string.watch_dirs_n, dirs.size))

        if (!BackendResolver.hasAllFilesAccess(this)) {
            EventLog.warn(
                "watching without All-files access: Android will not report new files in " +
                    "the shared Download folder, so detection will be unreliable"
            )
        }

        stopObservers()
        dirs.forEach { dir -> observers += observe(dir) }
        observers.forEach { runCatching { it.startWatching() } }
        EventLog.info("watching " + dirs.size + " folder(s): " + dirs.joinToString { it.name })

        // Catch anything that arrived while we were not running.
        lifecycleScope.launch { sweep() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopObservers()
        EventLog.info("stopped watching")
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = Notifications.watching(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_WATCH, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_WATCH, notification)
        }
    }

    private fun stopObservers() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    @Suppress("DEPRECATION")
    private fun observe(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val handle: (Int, String?) -> Unit = { _, name ->
            if (name != null && BundleScanner.isBundleName(name)) {
                lifecycleScope.launch { onCandidate(File(dir, name)) }
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        } else {
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        }
    }

    private suspend fun sweep() {
        val found = withContext(Dispatchers.IO) { BundleScanner.scan(this@WatchService) }
        // Only the newest handful, so enabling the watcher does not stampede through a
        // folder full of old downloads.
        found.take(5).forEach { candidate ->
            if (!prefs.isHandled(candidate.key)) {
                EventLog.info("already present: " + candidate.name)
                prefs.markHandled(candidate.key)
            }
        }
    }

    private suspend fun onCandidate(file: File) {
        val settled = withContext(Dispatchers.IO) { waitUntilSettled(file) } ?: return
        val key = file.absolutePath + "|" + settled
        if (prefs.isHandled(key)) return
        prefs.markHandled(key)

        EventLog.rule("detected " + file.name)
        EventLog.info(file.absolutePath + " (" + Installer.fmt(settled) + ")")

        val backend = pickSilentBackend()
        if (!prefs.autoInstall || backend == null) {
            val why = when {
                !prefs.autoInstall -> "auto-install is off"
                else -> "no silent backend, so an install would need a confirmation dialog"
            }
            EventLog.info("not installing automatically: $why")
            Notifications.found(this, file.name, file.toUri())
            return
        }
        installing.withLock { autoInstall(file, backend) }
    }

    /** A file that is still downloading grows; wait for it to hold the same size. */
    private fun waitUntilSettled(file: File): Long? {
        var last = -1L
        repeat(60) {
            if (!file.isFile) return null
            val size = file.length()
            if (size > 0 && size == last) return size
            last = size
            Thread.sleep(1_000)
        }
        return file.length().takeIf { it > 0 }
    }

    private suspend fun autoInstall(file: File, backend: BackendKind) {
        val uri = file.toUri()
        val info = try {
            withContext(Dispatchers.IO) { BundleInspector.inspect(this@WatchService, uri) }
        } catch (t: Throwable) {
            EventLog.error("could not read " + file.name + ": " + t.message)
            Notifications.result(this, getString(R.string.notif_failed_title), file.name + "\n" + t.message, uri)
            return
        }

        val pkg = info.packageName
        if (prefs.autoInstallUpdatesOnly) {
            if (pkg == null || !InstallVerifier.isInstalled(this, pkg)) {
                EventLog.info("skipping: auto-install is limited to updates and " + (pkg ?: "this package") + " is not installed")
                Notifications.found(this, file.name, uri)
                return
            }
        }
        if (info.hasError) {
            info.findings.filter { it.severity == Severity.ERROR }.forEach { EventLog.error(it.message) }
            Notifications.found(this, file.name, uri)
            return
        }

        val choice = SplitSelector.autoSelect(this, info)
        val problems = SplitSelector.validate(info, choice.selected)
        if (problems.isNotEmpty()) {
            problems.forEach { EventLog.add(it.severity, it.message) }
            Notifications.found(this, file.name, uri)
            return
        }

        val installer = Installer(this)
        val request = InstallRequest(
            info = info,
            selected = choice.selected,
            backend = backend,
            installObb = prefs.installObb,
            allowDowngrade = prefs.allowDowngrade,
            grantAllPermissions = prefs.grantAllPermissions,
            verifyChecksums = prefs.verifyChecksums,
            backupFirst = prefs.backupBeforeUpdate,
        )

        val outcome = installer.install(request) { event ->
            when (event) {
                is InstallEvent.Log -> EventLog.add(event.severity, event.message)
                is InstallEvent.Progress -> startInForeground(
                    event.label + " " + (event.fraction * 100).toInt() + "%"
                )
            }
        }
        startInForeground(getString(R.string.watch_dirs_n, observers.size))

        when (outcome) {
            is InstallOutcome.Ok -> {
                val verdict = outcome.report?.verdict
                val title = if (verdict == Verdict.BROKEN) {
                    getString(R.string.notif_broken_title)
                } else {
                    getString(R.string.notif_done_title)
                }
                val detail = buildString {
                    append(info.appLabel ?: pkg ?: file.name)
                    outcome.report?.let {
                        append("\n")
                        append("primaryCpuAbi=").append(it.primaryCpuAbi ?: "null")
                        append(" · ").append(it.nativeLibs.size).append(" native libs")
                        append(" · ").append(it.splitNames.size).append(" splits")
                    }
                }
                Notifications.result(this, title, detail, uri)
            }
            is InstallOutcome.Failed -> Notifications.result(
                this,
                getString(R.string.notif_failed_title),
                outcome.message + ((failureHintRes(outcome.code)?.let(::getString) ?: outcome.hint)?.let { "\n" + it } ?: ""),
                uri,
            )
        }
    }

    private fun pickSilentBackend(): BackendKind? {
        val report = BackendResolver.probe(this, rootConfirmed = prefs.backend == BackendKind.ROOT)
        prefs.backend?.let { preferred ->
            val cap = report.capabilities.firstOrNull { it.kind == preferred }
            if (cap != null && cap.state == BackendState.READY && cap.silent) return preferred
        }
        return report.capabilities.firstOrNull { it.state == BackendState.READY && it.silent }?.kind
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
