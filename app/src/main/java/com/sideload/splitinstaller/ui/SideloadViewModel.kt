package com.sideload.splitinstaller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.bundle.BundleInspector
import com.sideload.splitinstaller.core.bundle.BundleScanner
import com.sideload.splitinstaller.core.bundle.Finding
import com.sideload.splitinstaller.core.bundle.FoundBundle
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitApk
import com.sideload.splitinstaller.core.bundle.SplitKind
import com.sideload.splitinstaller.core.bundle.SplitSelector
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.DeviceReport
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.install.InstallRequest
import com.sideload.splitinstaller.core.install.Installer
import com.sideload.splitinstaller.core.install.RootShell
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.ota.OtaFlow
import com.sideload.splitinstaller.core.ota.OtaInstallWorker
import com.sideload.splitinstaller.core.ota.OtaStore
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.CertInfo
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.VerifyReport
import com.sideload.splitinstaller.core.watch.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledState(
    val versionCode: Long,
    val versionName: String?,
    val installer: String?,
    val splitNames: List<String>,
)

data class UiState(
    val device: DeviceReport? = null,
    val rootConfirmed: Boolean = false,
    val scanning: Boolean = false,
    val scannedOnce: Boolean = false,
    val found: List<FoundBundle> = emptyList(),
    val opening: Boolean = false,
    val bundle: BundleInfo? = null,
    val installedState: InstalledState? = null,
    val selection: Set<String> = emptySet(),
    val notes: Map<String, com.sideload.splitinstaller.core.bundle.SplitNote> = emptyMap(),
    val selectionProblems: List<Finding> = emptyList(),
    val signatureMatch: SignatureMatch = SignatureMatch.UNKNOWN,
    val installedSigner: CertInfo? = null,
    /** Splits the bundle has and the same-version install lacks — what a repair would add. */
    val repairSplits: List<SplitApk> = emptyList(),
    val backupFirst: Boolean = false,
    val installing: Boolean = false,
    val repairing: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val outcome: InstallOutcome? = null,
    val verifyReport: VerifyReport? = null,
    val error: String? = null,
    val watchEnabled: Boolean = false,
)

class SideloadViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val prefs: Prefs = Prefs.get(app)
    val log = EventLog.lines

    init {
        _state.update { it.copy(watchEnabled = prefs.watchEnabled, backupFirst = prefs.backupBeforeUpdate) }
        refreshCapabilities()
    }

    // ---- capabilities ------------------------------------------------------

    fun refreshCapabilities() = viewModelScope.launch {
        val report = withContext(Dispatchers.IO) {
            BackendResolver.probe(getApplication(), _state.value.rootConfirmed)
        }
        _state.update { it.copy(device = report) }
    }

    /** Actually exercise root, which may raise the superuser prompt. */
    fun testRoot() = viewModelScope.launch {
        EventLog.rule("root check")
        val result = withContext(Dispatchers.IO) { RootShell.probe() }
        val ok = result.ok && "uid=0" in result.out
        EventLog.add(
            if (ok) Severity.INFO else Severity.WARN,
            if (ok) "root granted: " + result.out.trim()
            else "root not available: " + result.text.ifBlank { "no response" },
        )
        _state.update { it.copy(rootConfirmed = ok) }
        refreshCapabilities()
    }

    fun setBackend(kind: BackendKind?) {
        prefs.backend = kind
        refreshCapabilities()
    }

    fun effectiveBackend(): BackendKind? {
        val device = _state.value.device ?: return null
        prefs.backend?.let { preferred ->
            val cap = device.capabilities.firstOrNull { it.kind == preferred }
            if (cap?.state == BackendState.READY) return preferred
        }
        return device.capabilities.firstOrNull { it.state == BackendState.READY }?.kind
    }

    // ---- discovery ---------------------------------------------------------

    fun scan() = viewModelScope.launch {
        _state.update { it.copy(scanning = true, error = null) }
        val found = withContext(Dispatchers.IO) { BundleScanner.scan(getApplication()) }
        EventLog.info("scan found " + found.size + " bundle(s)")
        _state.update { it.copy(scanning = false, scannedOnce = true, found = found) }
    }

    fun open(uri: Uri) = viewModelScope.launch {
        _state.update {
            it.copy(
                opening = true, error = null, bundle = null, outcome = null, verifyReport = null,
                progress = 0f, progressLabel = "",
            )
        }
        EventLog.rule("reading " + uri.lastPathSegment.orEmpty())
        try {
            val app = getApplication<Application>()
            val info = withContext(Dispatchers.IO) { BundleInspector.inspect(app, uri) }
            val choice = SplitSelector.autoSelect(app, info)
            val installed = withContext(Dispatchers.IO) { installedState(info.packageName) }
            val match = withContext(Dispatchers.IO) {
                ApkSignatures.compare(app, info.packageName, info.signerSha256)
            }
            val installedSigner = if (installed != null && info.packageName != null) {
                withContext(Dispatchers.IO) { ApkSignatures.installedSigner(app, info.packageName) }
            } else null

            EventLog.info(
                info.format.label + " · " + (info.packageName ?: "package unknown") +
                    " · " + (info.versionName ?: "?") + " (" + info.versionCode + ")" +
                    " · " + info.apks.size + " APK(s)" + (info.engine?.let { " · " + it.label } ?: ""),
            )
            info.findings.forEach { EventLog.add(it.severity, it.message) }
            info.signers.firstOrNull()?.let {
                EventLog.info("signer: " + it.commonName + " · SHA-256 " + it.formatted(8) + "…")
            }
            if (match == SignatureMatch.MISMATCH) {
                EventLog.warn("signer differs from the installed copy — an update will be refused")
            }

            _state.update {
                it.copy(
                    opening = false,
                    bundle = info,
                    installedState = installed,
                    selection = choice.selected,
                    notes = choice.notes,
                    selectionProblems = SplitSelector.validate(info, choice.selected),
                    signatureMatch = match,
                    installedSigner = installedSigner,
                    repairSplits = repairCandidates(info, installed, choice.selected),
                )
            }
        } catch (t: Throwable) {
            EventLog.error("could not read the bundle: " + (t.message ?: t.toString()))
            _state.update { it.copy(opening = false, error = t.message ?: t.toString()) }
        }
    }

    private fun installedState(pkg: String?): InstalledState? {
        pkg ?: return null
        val app = getApplication<Application>()
        val (code, name) = InstallVerifier.installedVersion(app, pkg) ?: return null
        return InstalledState(
            versionCode = code,
            versionName = name,
            installer = InstallVerifier.installerOfRecord(app, pkg),
            splitNames = InstallVerifier.installedSplits(app, pkg),
        )
    }

    /**
     * Only the exact same build can take extra splits without a full reinstall, and only
     * splits it does not already have are worth sending.
     */
    private fun repairCandidates(info: BundleInfo, installed: InstalledState?, auto: Set<String>): List<SplitApk> {
        if (installed == null || info.versionCode <= 0 || installed.versionCode != info.versionCode) return emptyList()
        return info.apks.filter { apk ->
            apk.id in auto &&
                apk.kind != SplitKind.BASE && apk.kind != SplitKind.STANDALONE &&
                !apk.splitName.isNullOrBlank() && apk.splitName !in installed.splitNames
        }
    }

    fun closeBundle() {
        _state.update {
            it.copy(
                bundle = null, outcome = null, verifyReport = null, progress = 0f, progressLabel = "",
                repairSplits = emptyList(), signatureMatch = SignatureMatch.UNKNOWN, installedSigner = null,
            )
        }
    }

    // ---- selection ---------------------------------------------------------

    fun toggleSplit(id: String) {
        _state.update { s ->
            val info = s.bundle ?: return@update s
            val apk = info.apks.firstOrNull { it.id == id }
            // The base is never optional; unticking it only produces an install that cannot start.
            if (apk?.kind == SplitKind.BASE && id in s.selection) return@update s
            val next = if (id in s.selection) s.selection - id else s.selection + id
            s.copy(selection = next, selectionProblems = SplitSelector.validate(info, next))
        }
    }

    fun resetSelection() {
        val info = _state.value.bundle ?: return
        val choice = SplitSelector.autoSelect(getApplication(), info)
        _state.update {
            it.copy(
                selection = choice.selected,
                notes = choice.notes,
                selectionProblems = SplitSelector.validate(info, choice.selected),
            )
        }
    }

    fun selectAll() {
        _state.update { s ->
            val info = s.bundle ?: return@update s
            val all = info.apks.map { it.id }.toSet()
            s.copy(selection = all, selectionProblems = SplitSelector.validate(info, all))
        }
    }

    // ---- this app's own update ---------------------------------------------

    /** Hidden for this session after "later"; the next check brings it back. */
    private val _otaDismissed = MutableStateFlow(false)
    val otaDismissed: StateFlow<Boolean> = _otaDismissed.asStateFlow()

    fun checkOta() = viewModelScope.launch {
        _otaDismissed.value = false
        runCatching { OtaFlow.check(getApplication(), auto = false) }
            .onFailure { EventLog.error("OTA check failed: " + it.message) }
    }

    fun downloadOta() = viewModelScope.launch {
        val release = OtaStore.status.value.release ?: return@launch
        withContext(Dispatchers.IO) {
            runCatching { OtaFlow.startDownload(getApplication(), release) }
                .onFailure { EventLog.error("could not start the OTA download: " + it.message) }
        }
    }

    /** The worker does the verifying; this only asks for it. */
    fun installOta() {
        val uri = OtaStore.status.value.fileUri?.let(Uri::parse) ?: return
        OtaInstallWorker.enqueue(getApplication(), uri, userAsked = true)
    }

    fun dismissOta() {
        _otaDismissed.value = true
    }

    fun undismissOta() {
        _otaDismissed.value = false
    }

    fun setBackupFirst(enabled: Boolean) {
        prefs.backupBeforeUpdate = enabled
        _state.update { it.copy(backupFirst = enabled) }
    }

    // ---- install -----------------------------------------------------------

    fun install(uninstallFirst: Boolean = false) = runInstall(repair = false, uninstallFirst = uninstallFirst)

    /** Add only the missing splits to the installed copy, keeping its data. */
    fun repair() = runInstall(repair = true, uninstallFirst = false)

    private fun runInstall(repair: Boolean, uninstallFirst: Boolean) = viewModelScope.launch {
        val s = _state.value
        val info = s.bundle ?: return@launch
        val backend = effectiveBackend() ?: run {
            _state.update { it.copy(error = "no usable install backend") }
            return@launch
        }
        val selection = if (repair) s.repairSplits.map { it.id }.toSet() else s.selection
        if (selection.isEmpty()) return@launch

        _state.update {
            it.copy(
                installing = true, repairing = repair, outcome = null, verifyReport = null,
                progress = 0f, progressLabel = "", error = null,
            )
        }
        EventLog.rule((if (repair) "repairing " else "installing ") + info.displayName)

        val outcome = Installer(getApplication()).install(
            InstallRequest(
                info = info,
                selected = selection,
                backend = backend,
                installObb = prefs.installObb,
                allowDowngrade = prefs.allowDowngrade,
                grantAllPermissions = prefs.grantAllPermissions,
                uninstallFirst = uninstallFirst,
                verifyChecksums = prefs.verifyChecksums,
                inherit = repair,
                backupFirst = s.backupFirst && !uninstallFirst,
            ),
        ) { event ->
            when (event) {
                is InstallEvent.Log -> EventLog.add(event.severity, event.message)
                is InstallEvent.Progress -> _state.update {
                    it.copy(progress = event.fraction.coerceIn(0f, 1f), progressLabel = event.label)
                }
            }
        }

        when (outcome) {
            is InstallOutcome.Ok -> EventLog.info(if (repair) "repair finished" else "install finished")
            is InstallOutcome.Failed -> {
                EventLog.error(outcome.message)
                outcome.hint?.let { EventLog.warn(it) }
            }
        }

        val installed = withContext(Dispatchers.IO) { installedState(info.packageName) }
        val match = withContext(Dispatchers.IO) {
            ApkSignatures.compare(getApplication(), info.packageName, info.signerSha256)
        }
        _state.update {
            it.copy(
                installing = false,
                progress = 1f,
                outcome = outcome,
                verifyReport = (outcome as? InstallOutcome.Ok)?.report,
                installedState = installed,
                signatureMatch = match,
                repairSplits = repairCandidates(info, installed, it.selection),
            )
        }
    }

    /** Re-run the post-install checks on demand, without installing anything. */
    fun reverify() = viewModelScope.launch {
        val info = _state.value.bundle ?: return@launch
        val pkg = info.packageName ?: return@launch
        val backend = effectiveBackend()
        EventLog.rule("verifying $pkg")
        val report = withContext(Dispatchers.IO) {
            InstallVerifier.verify(
                context = getApplication(),
                packageName = pkg,
                expectNativeLibs = info.engine != null || info.availableAbis.isNotEmpty(),
                expectedSplits = info.apks
                    .filter { it.id in _state.value.selection && it.kind != SplitKind.STANDALONE }
                    .mapNotNull { it.splitName?.takeIf(String::isNotBlank) },
                shell = backend?.let { BackendResolver.shellFor(it) },
            )
        }
        report.findings.forEach { EventLog.add(it.severity, it.message) }
        _state.update { it.copy(verifyReport = report) }
    }

    // ---- watcher -----------------------------------------------------------

    fun setWatch(enabled: Boolean) {
        prefs.watchEnabled = enabled
        _state.update { it.copy(watchEnabled = enabled) }
        val app = getApplication<Application>()
        if (enabled) WatchService.start(app) else WatchService.stop(app)
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
