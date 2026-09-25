package com.sideload.splitinstaller.core.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import com.sideload.splitinstaller.core.apps.AppExporter
import com.sideload.splitinstaller.core.bundle.BundleFormat
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitApk
import com.sideload.splitinstaller.core.bundle.SplitKind
import com.sideload.splitinstaller.core.history.HistoryEntry
import com.sideload.splitinstaller.core.history.InstallHistory
import com.sideload.splitinstaller.core.obb.ObbInstaller
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.VerifyReport
import com.sideload.splitinstaller.core.zip.DataSource
import com.sideload.splitinstaller.core.zip.ZipEntryInfo
import com.sideload.splitinstaller.core.zip.ZipReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

data class InstallRequest(
    val info: BundleInfo,
    val selected: Set<String>,
    val backend: BackendKind,
    val installObb: Boolean = true,
    val allowDowngrade: Boolean = true,
    val grantAllPermissions: Boolean = false,
    val uninstallFirst: Boolean = false,
    val verifyChecksums: Boolean = true,
    /**
     * Add splits to the installed copy instead of replacing it — `install-multiple -p`.
     * Keeps app data; needs an exact versionCode match with what is installed.
     */
    val inherit: Boolean = false,
    /** Export the installed copy as .apks before replacing it. */
    val backupFirst: Boolean = false,
)

sealed interface InstallEvent {
    data class Log(val severity: Severity, val message: String) : InstallEvent
    data class Progress(val fraction: Float, val label: String) : InstallEvent
}

sealed interface InstallOutcome {
    data class Ok(val packageName: String?, val report: VerifyReport?, val backupPath: String? = null) : InstallOutcome
    data class Failed(val message: String, val hint: String? = null, val code: String? = null) : InstallOutcome
}

/** Codes for failures that are ours rather than the package manager's `INSTALL_FAILED_*`. */
object FailureCode {
    const val CORRUPT = "CORRUPT"
    const val BACKUP_FAILED = "BACKUP_FAILED"
    const val REPAIR_VERSION = "REPAIR_VERSION"
    const val UNINSTALL_INCOMPLETE = "UNINSTALL_INCOMPLETE"
    const val SHELL_UNAVAILABLE = "SHELL_UNAVAILABLE"
}

/** Signals that a bundle entry did not come out at the size or checksum the archive claims. */
class CorruptBundleException(message: String) : IOException(message)

/**
 * Streams the chosen splits straight from the bundle into one install session.
 *
 * Nothing is unpacked to disk on the way. Extracting first needs a second copy of the whole
 * bundle, and a truncated write there produces an APK that installs *badly* rather than
 * failing, so the failure surfaces later as a launch crash. Reading the archive's own
 * checksums while copying catches that class of damage before the package manager sees it.
 */
class Installer(private val context: Context) {

    suspend fun install(
        request: InstallRequest,
        emit: (InstallEvent) -> Unit,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val outcome = runInstall(request, emit)
        record(request, outcome)
        outcome
    }

    private suspend fun runInstall(
        request: InstallRequest,
        emit: (InstallEvent) -> Unit,
    ): InstallOutcome {
        val info = request.info
        val chosen = info.apks.filter { it.id in request.selected }
        if (chosen.isEmpty()) return InstallOutcome.Failed("nothing selected to install")
        val totalBytes = chosen.sumOf { it.size }
        val pkg = info.packageName

        emit(InstallEvent.Log(Severity.INFO, "bundle: ${info.displayName} (${info.format.label})"))
        emit(InstallEvent.Log(Severity.INFO, "package: ${pkg ?: "unknown"}"))
        emit(InstallEvent.Log(Severity.INFO, "backend: ${request.backend}" + if (request.inherit) " · repair (inherit existing)" else ""))
        emit(InstallEvent.Log(Severity.INFO, "installing ${chosen.size} APK(s), ${fmt(totalBytes)}"))
        chosen.forEach {
            emit(InstallEvent.Log(Severity.INFO, "  ${pad(fmt(it.size))}  ${it.fileName}  [${it.kind}]"))
        }

        val free = BackendResolver.probe(context).freeDataBytes
        if (free in 1 until (totalBytes + 64L * 1024 * 1024)) {
            emit(InstallEvent.Log(Severity.WARN, "only ${fmt(free)} free on /data for a ${fmt(totalBytes)} install"))
        }

        // ---- preflight: will the package manager accept this signer? ------------
        if (pkg != null && !request.uninstallFirst) {
            when (ApkSignatures.compare(context, pkg, info.signerSha256)) {
                SignatureMatch.MISMATCH -> emit(InstallEvent.Log(
                    Severity.WARN,
                    "the bundle's signer differs from the installed copy — expect " +
                        "INSTALL_FAILED_UPDATE_INCOMPATIBLE unless the old copy is removed first",
                ))
                SignatureMatch.MATCH -> emit(InstallEvent.Log(Severity.INFO, "signer matches the installed copy"))
                else -> Unit
            }
        }

        if (request.inherit) {
            if (pkg == null) return InstallOutcome.Failed("repair needs a known package name")
            val installed = InstallVerifier.installedVersion(context, pkg)
                ?: return InstallOutcome.Failed("$pkg is not installed, so there is nothing to repair")
            if (installed.first != info.versionCode) {
                return InstallOutcome.Failed(
                    "repair needs an exact versionCode match: installed ${installed.first}, bundle ${info.versionCode}",
                    hint = "Install the full bundle instead; splits from a different build cannot be merged.",
                    code = FailureCode.REPAIR_VERSION,
                )
            }
        }

        // ---- keep the last known-good build -------------------------------------
        var backupPath: String? = null
        if (request.backupFirst && !request.inherit && pkg != null && InstallVerifier.isInstalled(context, pkg)) {
            emit(InstallEvent.Log(Severity.INFO, "backing up the installed copy first"))
            try {
                val result = AppExporter.export(context, pkg) { f ->
                    emit(InstallEvent.Progress(f, "backup"))
                }
                backupPath = result.displayPath
                emit(InstallEvent.Log(Severity.INFO, "backup saved: ${result.displayPath} (${fmt(result.size)})"))
            } catch (t: Throwable) {
                return InstallOutcome.Failed(
                    "backup failed, so nothing was installed: ${t.message}",
                    hint = "Free some space or turn off \"back up before updating\", then try again.",
                    code = FailureCode.BACKUP_FAILED,
                )
            }
        }

        if (request.uninstallFirst && pkg != null) {
            if (!uninstall(pkg, request.backend, emit)) {
                return InstallOutcome.Failed(
                    "the uninstall did not complete, so the reinstall was not attempted",
                    hint = "Remove the app from Settings, then install this bundle again.",
                    code = FailureCode.UNINSTALL_INCOMPLETE,
                )
            }
        }

        var source: DataSource? = null
        return try {
            source = BundleInspector.openSource(context, info.uri)
            val zip = if (info.format == BundleFormat.APK) null else ZipReader(source, ownsSource = false)

            val outcome = when (request.backend) {
                BackendKind.PACKAGE_INSTALLER ->
                    viaPackageInstaller(request, chosen, zip, source, totalBytes, emit)
                BackendKind.SHIZUKU, BackendKind.ROOT -> {
                    val shell = BackendResolver.shellFor(request.backend)!!
                    viaShell(shell, request, chosen, zip, source, totalBytes, emit)
                }
            }

            if (outcome is InstallOutcome.Ok) {
                if (request.installObb && info.obbs.isNotEmpty() && zip != null && !request.inherit) {
                    ObbInstaller.install(
                        context = context,
                        zip = zip,
                        obbs = info.obbs,
                        shell = BackendResolver.shellFor(request.backend),
                        emit = emit,
                    )
                }
                InstallOutcome.Ok(pkg, verify(request, chosen, emit), backupPath)
            } else {
                outcome
            }
        } catch (e: CorruptBundleException) {
            emit(InstallEvent.Log(Severity.ERROR, e.message.orEmpty()))
            InstallOutcome.Failed(
                e.message.orEmpty(),
                hint = "The bundle file itself is damaged. Delete it and download it again; do not install it.",
                code = FailureCode.CORRUPT,
            )
        } catch (t: Throwable) {
            emit(InstallEvent.Log(Severity.ERROR, t.toString()))
            InstallOutcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            source?.close()
        }
    }

    private fun record(request: InstallRequest, outcome: InstallOutcome) {
        val info = request.info
        val ok = outcome as? InstallOutcome.Ok
        InstallHistory.add(
            context,
            HistoryEntry(
                time = System.currentTimeMillis(),
                packageName = info.packageName,
                label = info.appLabel ?: ok?.report?.label,
                versionName = info.versionName,
                versionCode = info.versionCode,
                bundleName = info.displayName,
                backend = request.backend.name,
                ok = ok != null,
                verdict = ok?.report?.verdict?.name,
                message = (outcome as? InstallOutcome.Failed)?.message,
                primaryCpuAbi = ok?.report?.primaryCpuAbi,
                splits = request.selected.size,
                repair = request.inherit,
            ),
        )
    }

    // ---- PackageInstaller --------------------------------------------------

    private suspend fun viaPackageInstaller(
        request: InstallRequest,
        chosen: List<SplitApk>,
        zip: ZipReader?,
        source: DataSource,
        totalBytes: Long,
        emit: (InstallEvent) -> Unit,
    ): InstallOutcome {
        val pi = context.packageManager.packageInstaller
        val mode = if (request.inherit) {
            PackageInstaller.SessionParams.MODE_INHERIT_EXISTING
        } else {
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        }
        val params = PackageInstaller.SessionParams(mode)
        request.info.packageName?.let { params.setAppPackageName(it) }
        params.setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
        runCatching { params.setSize(totalBytes) }
        runCatching { params.setOriginatingUid(android.os.Process.myUid()) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isInstallerOfRecord(request.info.packageName)) {
            // Only an update to something we installed ourselves can skip the dialog.
            runCatching {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                emit(InstallEvent.Log(Severity.INFO, "updating a package we installed; no confirmation needed"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE) }
        }
        applyHiddenFlags(params, request, emit)

        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            return InstallOutcome.Failed("could not create an install session: ${t.message}")
        }
        emit(InstallEvent.Log(Severity.INFO, "session $sessionId created"))

        val events = InstallEvents.register(sessionId)
        try {
            pi.openSession(sessionId).use { session ->
                var written = 0L
                for (apk in chosen) {
                    val before = written
                    emit(InstallEvent.Progress(before.toFloat() / totalBytes, apk.fileName))
                    session.openWrite(sessionName(apk), 0, apk.size).use { out ->
                        openApk(zip, source, apk).use { input ->
                            written = before + pump(input, out, apk, request.verifyChecksums) { done ->
                                emit(InstallEvent.Progress((before + done).toFloat() / totalBytes, apk.fileName))
                            }
                        }
                        session.fsync(out)
                    }
                    emit(InstallEvent.Log(Severity.INFO, "wrote ${apk.fileName}"))
                }
                emit(InstallEvent.Progress(1f, "committing"))
                session.commit(statusIntent(sessionId).intentSender)
            }
        } catch (t: Throwable) {
            InstallEvents.unregister(sessionId)
            runCatching { pi.abandonSession(sessionId) }
            if (t is CorruptBundleException) throw t
            return InstallOutcome.Failed("writing the session failed: ${t.message}")
        }

        // The commit reports back at least once, and twice when it wants a confirmation.
        try {
            repeat(3) {
                val intent = withTimeoutOrNull(20 * 60 * 1000L) { events.receive() }
                    ?: return InstallOutcome.Failed("the installer never reported back")

                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                emit(InstallEvent.Log(Severity.INFO, InstallEvents.statusMessage(intent)))

                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = confirmIntent(intent)
                            ?: return InstallOutcome.Failed("the system asked for confirmation but sent no dialog")
                        emit(InstallEvent.Log(Severity.INFO, "waiting for the system confirmation dialog"))
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure { return InstallOutcome.Failed("could not show the confirmation dialog: ${it.message}") }
                    }

                    PackageInstaller.STATUS_SUCCESS -> return InstallOutcome.Ok(request.info.packageName, null)

                    else -> {
                        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                        return InstallOutcome.Failed(
                            InstallEvents.statusMessage(intent),
                            hint = InstallEvents.explain(intent),
                            code = Regex("INSTALL_[A-Z_]+").find(raw)?.value
                                ?: raw.substringBefore(':').trim().ifBlank { null },
                        )
                    }
                }
            }
            return InstallOutcome.Failed("the installer kept asking for confirmation")
        } finally {
            InstallEvents.unregister(sessionId)
        }
    }

    private fun isInstallerOfRecord(packageName: String?): Boolean {
        if (packageName == null) return false
        return InstallVerifier.installerOfRecord(context, packageName) == context.packageName
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallEventReceiver::class.java)
            .setAction(InstallEvents.ACTION)
            .putExtra(InstallEvents.EXTRA_SESSION, sessionId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /**
     * Replace-existing and allow-downgrade have never been public API. The package manager
     * still honours downgrade only for debuggable apps on a production build.
     */
    private fun applyHiddenFlags(
        params: PackageInstaller.SessionParams,
        request: InstallRequest,
        emit: (InstallEvent) -> Unit,
    ) {
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            field.isAccessible = true
            var flags = field.getInt(params)
            flags = flags or 0x00000002 // INSTALL_REPLACE_EXISTING
            if (request.allowDowngrade) flags = flags or 0x00000080 // INSTALL_ALLOW_DOWNGRADE
            if (request.grantAllPermissions) flags = flags or 0x00000100 // INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS
            field.setInt(params, flags)
        }.onFailure {
            emit(InstallEvent.Log(Severity.INFO, "this ROM hides the replace/downgrade flags; using defaults"))
        }
    }

    // ---- shell (Shizuku / root) --------------------------------------------

    private fun viaShell(
        shell: Shell,
        request: InstallRequest,
        chosen: List<SplitApk>,
        zip: ZipReader?,
        source: DataSource,
        totalBytes: Long,
        emit: (InstallEvent) -> Unit,
    ): InstallOutcome {
        // A full install sends every split the new version needs in one transaction, because
        // split sets change between releases and reusing an old file list is how ABI splits go
        // missing. Repair is the one deliberate exception: `-p` inherits what is installed.
        val create = buildString {
            append("pm install-create -r")
            if (request.inherit) append(" -p ").append(shellQuote(request.info.packageName.orEmpty()))
            if (request.allowDowngrade) append(" -d")
            if (request.grantAllPermissions) append(" -g")
            append(" -S ").append(totalBytes)
        }

        val created = shell.run(create, timeoutSeconds = 60)
        val sessionId = Regex("\\[(\\d+)]").find(created.text)?.groupValues?.get(1)?.toIntOrNull()
            ?: return InstallOutcome.Failed(
                "pm install-create failed: ${created.text.ifBlank { "no output" }}",
                hint = if (shell.id == "shizuku") "Is the Shizuku service still running?" else null,
                code = FailureCode.SHELL_UNAVAILABLE,
            )
        emit(InstallEvent.Log(Severity.INFO, "session $sessionId created via ${shell.id}"))

        var written = 0L
        for (apk in chosen) {
            emit(InstallEvent.Progress(written.toFloat() / totalBytes, apk.fileName))
            val before = written
            val cmd = "pm install-write -S ${apk.size} $sessionId ${shellQuote(sessionName(apk))} -"
            val result = try {
                shell.run(cmd, timeoutSeconds = 3600) { out ->
                    openApk(zip, source, apk).use { input ->
                        written = before + pump(input, out, apk, request.verifyChecksums) { done ->
                            emit(InstallEvent.Progress((before + done).toFloat() / totalBytes, apk.fileName))
                        }
                    }
                }
            } catch (e: CorruptBundleException) {
                shell.run("pm install-abandon $sessionId", timeoutSeconds = 30)
                throw e
            }
            if (!result.ok && "Success" !in result.text) {
                shell.run("pm install-abandon $sessionId", timeoutSeconds = 30)
                return InstallOutcome.Failed("pm install-write failed on ${apk.fileName}: ${result.text}")
            }
            emit(InstallEvent.Log(Severity.INFO, "wrote ${apk.fileName}"))
        }

        emit(InstallEvent.Progress(1f, "committing"))
        val commit = shell.run("pm install-commit $sessionId", timeoutSeconds = 900)
        emit(InstallEvent.Log(Severity.INFO, commit.text.ifBlank { "no output from install-commit" }))

        if (commit.ok && "Success" in commit.text) {
            return InstallOutcome.Ok(request.info.packageName, null)
        }
        shell.run("pm install-abandon $sessionId", timeoutSeconds = 30)
        return InstallOutcome.Failed(
            commit.text.ifBlank { "pm install-commit failed" },
            hint = shellHint(commit.text),
            code = Regex("INSTALL_[A-Z_]+").find(commit.text)?.value,
        )
    }

    private fun shellHint(output: String): String? = when {
        "INSTALL_FAILED_MISSING_SPLIT" in output ->
            "A partial install of this package is already on the device. Uninstall it, then install " +
                "the full bundle in one transaction."
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output ->
            "Signature mismatch with the installed copy — a different signer, so a repack or a " +
                "different build channel. Uninstall the existing copy or use a verbatim re-host."
        "INSTALL_FAILED_VERSION_DOWNGRADE" in output ->
            "Older than what is installed. Production builds only allow that for debuggable apps — uninstall first."
        "INSTALL_FAILED_NO_MATCHING_ABIS" in output ->
            "None of the selected APKs carry native code this device can run."
        else -> null
    }

    private suspend fun uninstall(
        packageName: String,
        backend: BackendKind,
        emit: (InstallEvent) -> Unit,
    ): Boolean {
        emit(InstallEvent.Log(Severity.INFO, "uninstalling $packageName first"))
        val shell = BackendResolver.shellFor(backend)
        if (shell != null) {
            val r = shell.run("pm uninstall $packageName", timeoutSeconds = 120)
            emit(InstallEvent.Log(Severity.INFO, r.text.ifBlank { "uninstall returned nothing" }))
            return r.ok && "Success" in r.text
        }

        // No shell: go through PackageInstaller, which raises the system uninstall dialog.
        val pseudo = InstallEvents.nextPseudoSession()
        val events = InstallEvents.register(pseudo)
        return try {
            context.packageManager.packageInstaller.uninstall(packageName, statusIntent(pseudo).intentSender)
            var rounds = 0
            var result = false
            while (rounds++ < 3) {
                val intent = withTimeoutOrNull(10 * 60 * 1000L) { events.receive() }
                if (intent == null) {
                    emit(InstallEvent.Log(Severity.WARN, "the uninstall never reported back"))
                    break
                }
                emit(InstallEvent.Log(Severity.INFO, InstallEvents.statusMessage(intent)))
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = confirmIntent(intent) ?: break
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                    continue
                }
                result = status == PackageInstaller.STATUS_SUCCESS
                break
            }
            result
        } catch (t: Throwable) {
            emit(InstallEvent.Log(Severity.WARN, "uninstall failed: " + t.message))
            false
        } finally {
            InstallEvents.unregister(pseudo)
        }
    }

    // ---- copying -----------------------------------------------------------

    private fun openApk(zip: ZipReader?, source: DataSource, apk: SplitApk): InputStream {
        if (zip == null) {
            // A bare .apk: the bundle *is* the APK.
            return object : InputStream() {
                private var pos = 0L
                private val one = ByteArray(1)
                override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = source.readAt(pos, b, off, len)
                    if (n > 0) pos += n
                    return n
                }
            }
        }
        val entry: ZipEntryInfo = zip[apk.entryName]
            ?: throw IOException("${apk.entryName} vanished from the bundle")
        return zip.open(entry)
    }

    /**
     * Copy one APK, checking as we go that it is all there.
     *
     * The size check catches a truncated archive; the CRC check catches silent corruption,
     * which is the kind that installs successfully and then fails at launch.
     */
    private fun pump(
        input: InputStream,
        output: OutputStream,
        apk: SplitApk,
        verifyChecksum: Boolean,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(1 shl 20)
        val crc = if (verifyChecksum) CRC32() else null
        var copied = 0L
        var sinceReport = 0L

        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            crc?.update(buffer, 0, n)
            copied += n
            sinceReport += n
            if (sinceReport >= 4L shl 20) {
                onProgress(copied)
                sinceReport = 0
            }
        }
        output.flush()

        if (apk.size > 0 && copied != apk.size) {
            throw CorruptBundleException(
                "${apk.fileName}: expected ${apk.size} bytes, got $copied — the bundle is truncated"
            )
        }
        if (crc != null && apk.crc != 0L && crc.value != apk.crc) {
            throw CorruptBundleException(
                "${apk.fileName}: checksum mismatch (archive says ${apk.crc.toString(16)}, " +
                    "read ${crc.value.toString(16)}) — the download is damaged"
            )
        }
        onProgress(copied)
        return copied
    }

    private fun sessionName(apk: SplitApk): String {
        val base = apk.splitName?.takeIf { it.isNotBlank() } ?: "base"
        return (base + ".apk").replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    // ---- verification ------------------------------------------------------

    private fun verify(
        request: InstallRequest,
        chosen: List<SplitApk>,
        emit: (InstallEvent) -> Unit,
    ): VerifyReport? {
        val pkg = request.info.packageName ?: return null
        val expectNative = request.info.engine != null ||
            chosen.any { it.abis.isNotEmpty() } ||
            (request.inherit && request.info.availableAbis.isNotEmpty())
        val expectedSplits = chosen
            .filter { it.kind != SplitKind.STANDALONE }
            .mapNotNull { it.splitName?.takeIf(String::isNotBlank) }

        val report = InstallVerifier.verify(
            context = context,
            packageName = pkg,
            expectNativeLibs = expectNative,
            expectedSplits = expectedSplits,
            shell = BackendResolver.shellFor(request.backend),
        )
        report.findings.forEach { emit(InstallEvent.Log(it.severity, it.message)) }
        return report
    }

    companion object {
        fun fmt(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        private fun pad(s: String) = s.padStart(9)
    }
}
