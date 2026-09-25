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
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.core.ota.OtaFlow
import com.sideload.splitinstaller.core.ota.OtaInstallWorker
import com.sideload.splitinstaller.core.ota.OtaProblem
import com.sideload.splitinstaller.core.ota.OtaState
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

    private val otaCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val otaRecheck = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Called each time the app comes to the front. The background schedule may be hours away,
     * so opening the app asks too, when the last answer is older than a few hours.
     */
    fun checkOtaIfStale() {
        if (!OtaChannel.parse(prefs.otaChannel).isOn) return
        // A "check" made while the channel was off answered nothing, so it is never fresh.
        val last = OtaStore.status.value
        if (last.state != OtaState.OFF && System.currentTimeMillis() - last.checkedAt < OTA_STALE_MS) return
        // One at a time; a request that arrives meanwhile (e.g. the channel changed) runs after.
        if (!otaCheckRunning.compareAndSet(false, true)) {
            otaRecheck.set(true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { OtaFlow.check(getApplication(), auto = true) }
                    .onFailure { EventLog.warn("OTA check on launch failed: " + it.message) }
            } finally {
                otaCheckRunning.set(false)
                if (otaRecheck.getAndSet(false)) checkOtaIfStale()
            }
        }
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

    /** [onStarted] runs only when a transfer really started, not when one was already under way. */
    fun downloadOta(onStarted: () -> Unit = {}) = viewModelScope.launch {
        val release = OtaStore.status.value.release ?: return@launch
        val started = withContext(Dispatchers.IO) {
            runCatching { OtaFlow.startDownload(getApplication(), release, automatic = false) }
                .onFailure { EventLog.error("could not start the OTA download: " + it.message) }
                .getOrDefault(false)
        }
        if (started) onStarted()
    }

    /**
     * With Shizuku or root the worker installs it. Without, Android has to ask, and it can only
     * ask an app that is on screen: the ordinary install screen does that, after the same
     * checks the worker would make.
     */
    fun installOta() = viewModelScope.launch {
        val app = getApplication<Application>()
        val status = OtaStore.status.value
        val uri = status.fileUri?.let(Uri::parse) ?: return@launch
        val sourceUrl = status.fileSourceUrl ?: status.release?.url
        val silent = withContext(Dispatchers.IO) { OtaFlow.silentBackend(app) }
        if (silent != null) {
            OtaInstallWorker.enqueue(app, uri, sourceUrl, userAsked = true, replace = true)
            return@launch
        }
        // The file may have been deleted, e.g. by a storage cleaner: fetch it again instead.
        val release = status.release
        val missing = withContext(Dispatchers.IO) { !OtaFlow.fileExists(app, uri) }
        if (missing) {
            withContext(Dispatchers.IO) {
                OtaStore.clearMissingFile(app, uri.toString())
                if (release != null) OtaFlow.startDownload(app, release, automatic = false)
            }
            return@launch
        }
        val (info, problem) = withContext(Dispatchers.IO) { OtaFlow.inspectAndVerify(app, uri, sourceUrl) }
        if (problem != null || info == null) {
            withContext(Dispatchers.IO) { OtaFlow.refuse(app, uri, sourceUrl, problem ?: OtaProblem.UNREADABLE) }
            return@launch
        }
        open(uri)
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

/** How old the last check on this app's own updates may be before opening the app repeats it. */
private const val OTA_STALE_MS = 6 * 60 * 60 * 1000L
