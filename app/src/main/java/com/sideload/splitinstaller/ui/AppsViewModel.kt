package com.sideload.splitinstaller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.apps.AppExporter
import com.sideload.splitinstaller.core.apps.AppScanner
import com.sideload.splitinstaller.core.apps.InstallerInfo
import com.sideload.splitinstaller.core.apps.LaunchDiagnosis
import com.sideload.splitinstaller.core.apps.LaunchDiagnostics
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.update.Http
import com.sideload.splitinstaller.core.update.UpdateChecker
import com.sideload.splitinstaller.core.update.UpdateKind
import com.sideload.splitinstaller.core.update.UpdatePin
import com.sideload.splitinstaller.core.update.UpdatePins
import com.sideload.splitinstaller.core.update.UpdateResult
import com.sideload.splitinstaller.core.update.UpdateStore
import com.sideload.splitinstaller.core.update.UpdateWorker
import com.sideload.splitinstaller.core.install.Shell
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.CertInfo
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.VerifyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppFilter { ALL, UPDATES, ATTENTION, SPLIT, RISKY_SOURCE }

data class AppRow(val report: VerifyReport, val installer: InstallerInfo)

data class AppDetail(
    val row: AppRow,
    val signer: CertInfo? = null,
    val refreshing: Boolean = false,
    val diagnosing: Boolean = false,
    val diagnosis: LaunchDiagnosis? = null,
    val exporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportedTo: String? = null,
    val exportError: String? = null,
)

data class AppsState(
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val scannedOnce: Boolean = false,
    val apps: List<AppRow> = emptyList(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val includeSystem: Boolean = false,
    val detail: AppDetail? = null,
    /** Last answer from each package's pinned update source. */
    val updates: Map<String, UpdateResult> = emptyMap(),
    val pins: Map<String, UpdatePin> = emptyMap(),
    val checkingUpdates: Boolean = false,
) {
    val attention: Int get() = apps.count { it.report.needsAttention }
    val updateCount: Int get() = updates.values.count { it.hasUpdate }
    val withSplits: Int get() = apps.count { it.report.splitNames.isNotEmpty() }
    val riskySource: Int get() = apps.count { it.installer.risky }

    val visible: List<AppRow>
        get() {
            val q = query.trim().lowercase()
            return apps.filter { row ->
                val r = row.report
                val matchesFilter = when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.UPDATES -> updates[r.packageName]?.hasUpdate == true
                    AppFilter.ATTENTION -> r.needsAttention
                    AppFilter.SPLIT -> r.splitNames.isNotEmpty()
                    AppFilter.RISKY_SOURCE -> row.installer.risky
                }
                matchesFilter && (
                    q.isEmpty() ||
                        (r.label ?: "").lowercase().contains(q) ||
                        r.packageName.lowercase().contains(q)
                    )
            }
        }
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs.get(app)
    private val _state = MutableStateFlow(AppsState(includeSystem = prefs.showSystemApps))
    val state: StateFlow<AppsState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        val app = getApplication<Application>()
        UpdatePins.load(app)
        UpdateStore.load(app)
        viewModelScope.launch { UpdateStore.results.collect { r -> _state.update { it.copy(updates = r) } } }
        viewModelScope.launch { UpdatePins.pins.collect { p -> _state.update { it.copy(pins = p) } } }
        viewModelScope.launch { UpdateStore.checking.collect { c -> _state.update { it.copy(checkingUpdates = c) } } }
    }

    // ---- updates -------------------------------------------------------------

    /** Runs the same worker the schedule uses, so manual and automatic behave alike. */
    fun checkAllUpdates() {
        UpdateWorker.checkNow(getApplication())
    }

    fun checkUpdate(packageName: String) = viewModelScope.launch {
        val app = getApplication<Application>()
        val pin = UpdatePins[packageName] ?: return@launch
        UpdateStore.setChecking(true)
        try {
            val result = UpdateChecker.check(app, pin)
            UpdateChecker.log(result)
            UpdateStore.put(app, result)
        } finally {
            UpdateStore.setChecking(false)
        }
    }

    /** F-Droid is the one source that can confirm it has the package before pinning. */
    fun pinFDroid(packageName: String, label: String?, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val app = getApplication<Application>()
        val found = UpdateChecker.onFDroid(packageName)
        onResult(found)
        if (found) {
            UpdatePins.pin(app, UpdatePin(packageName, UpdateKind.FDROID, packageName, label))
            checkUpdate(packageName)
        }
    }

    fun pinGitHub(packageName: String, label: String?, input: String): Boolean {
        val repo = UpdateChecker.githubRepo(input) ?: return false
        UpdatePins.pin(getApplication(), UpdatePin(packageName, UpdateKind.GITHUB, repo, label))
        checkUpdate(packageName)
        return true
    }

    fun pinWeb(packageName: String, label: String?, url: String) {
        UpdatePins.pin(getApplication(), UpdatePin(packageName, UpdateKind.WEB, url, label))
        checkUpdate(packageName)
    }

    fun unpin(packageName: String) {
        val app = getApplication<Application>()
        UpdatePins.unpin(app, packageName)
        UpdateStore.remove(app, packageName)
    }

    /** Fetches an update the source publishes directly, and installs it if that is allowed. */
    fun downloadUpdate(result: UpdateResult) = viewModelScope.launch {
        val url = result.downloadUrl ?: return@launch
        val app = getApplication<Application>()
        val silent = BackendResolver.probe(app, rootConfirmed).silentAvailable
        withContext(Dispatchers.IO) {
            Downloads.start(
                app,
                DownloadRequest(url, Http.USER_AGENT, null, null, null),
                autoInstall = prefs.updateAutoInstall && silent,
                expectedPackage = result.packageName,
            )
        }
    }

    /** Set by the activity from the install side, which knows whether root was confirmed. */
    var rootConfirmed: Boolean = false

    fun shell(): Shell? = BackendResolver.anyShell(rootConfirmed)

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val app = getApplication<Application>()
            _state.update { it.copy(scanning = true, done = 0, total = 0) }
            EventLog.rule("scanning installed apps")
            val reports = AppScanner.scan(app, _state.value.includeSystem) { done, total ->
                _state.update { it.copy(done = done, total = total) }
            }
            val rows = reports.map { AppRow(it, AppScanner.installerInfo(app, it.installerPackage)) }
            val broken = rows.count { it.report.verdict == com.sideload.splitinstaller.core.verify.Verdict.BROKEN }
            EventLog.info("scanned ${rows.size} app(s): $broken broken, ${rows.count { it.report.needsAttention }} need attention")
            rows.filter { it.report.verdict == com.sideload.splitinstaller.core.verify.Verdict.BROKEN }.forEach {
                EventLog.error("broken install: ${it.report.label ?: it.report.packageName} (${it.report.packageName})")
            }
            _state.update { it.copy(scanning = false, scannedOnce = true, apps = rows) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setFilter(f: AppFilter) = _state.update { it.copy(filter = f) }

    fun setIncludeSystem(include: Boolean) {
        prefs.showSystemApps = include
        _state.update { it.copy(includeSystem = include) }
        scan()
    }

    // ---- detail ------------------------------------------------------------

    fun openDetail(packageName: String) = viewModelScope.launch {
        val app = getApplication<Application>()
        val existing = _state.value.apps.firstOrNull { it.report.packageName == packageName }
        val row = existing ?: withContext(Dispatchers.IO) {
            val report = InstallVerifier.verify(app, packageName)
            AppRow(report, AppScanner.installerInfo(app, report.installerPackage))
        }
        _state.update { it.copy(detail = AppDetail(row, refreshing = true)) }
        refreshDetail()
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    /** Full check for one app — this time with a shell, when there is one, for dumpsys. */
    fun refreshDetail() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        val app = getApplication<Application>()
        val pkg = detail.row.report.packageName
        _state.update { s -> s.copy(detail = s.detail?.copy(refreshing = true)) }
        val (report, signer) = withContext(Dispatchers.IO) {
            InstallVerifier.verify(app, pkg, shell = shell()) to ApkSignatures.installedSigner(app, pkg)
        }
        val row = AppRow(report, AppScanner.installerInfo(app, report.installerPackage))
        _state.update { s ->
            s.copy(
                detail = s.detail?.takeIf { it.row.report.packageName == pkg }?.copy(row = row, signer = signer, refreshing = false),
                apps = s.apps.map { if (it.report.packageName == pkg) row else it },
            )
        }
    }

    fun diagnose() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        val shell = shell() ?: return@launch
        val pkg = detail.row.report.packageName
        _state.update { s -> s.copy(detail = s.detail?.copy(diagnosing = true, diagnosis = null)) }
        EventLog.rule("launch diagnosis: $pkg")
        val result = LaunchDiagnostics.run(getApplication(), pkg, shell)
        EventLog.info("diagnosis: ${result.problem}")
        result.lines.takeLast(40).forEach { EventLog.info("  $it") }
        _state.update { s -> s.copy(detail = s.detail?.copy(diagnosing = false, diagnosis = result)) }
    }

    fun export() = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        val pkg = detail.row.report.packageName
        _state.update { s ->
            s.copy(detail = s.detail?.copy(exporting = true, exportProgress = 0f, exportedTo = null, exportError = null))
        }
        EventLog.rule("backing up $pkg")
        try {
            val result = AppExporter.export(getApplication(), pkg) { f ->
                _state.update { s -> s.copy(detail = s.detail?.copy(exportProgress = f)) }
            }
            EventLog.info("saved ${result.displayPath} (${humanSize(result.size)})")
            _state.update { s -> s.copy(detail = s.detail?.copy(exporting = false, exportedTo = result.displayPath)) }
        } catch (t: Throwable) {
            EventLog.error("backup failed: ${t.message}")
            _state.update { s -> s.copy(detail = s.detail?.copy(exporting = false, exportError = t.message ?: t.toString())) }
        }
    }

    /** After an uninstall or reinstall elsewhere, drop or refresh the app in the list. */
    fun onPackageChanged(packageName: String) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (!InstallVerifier.isInstalled(app, packageName)) {
            _state.update { s ->
                s.copy(
                    apps = s.apps.filterNot { it.report.packageName == packageName },
                    detail = s.detail?.takeIf { it.row.report.packageName != packageName },
                )
            }
        } else {
            val report = withContext(Dispatchers.IO) { InstallVerifier.verify(app, packageName) }
            val row = AppRow(report, AppScanner.installerInfo(app, report.installerPackage))
            _state.update { s ->
                val known = s.apps.any { it.report.packageName == packageName }
                s.copy(
                    apps = when {
                        known -> s.apps.map { if (it.report.packageName == packageName) row else it }
                        s.scannedOnce -> s.apps + row
                        else -> s.apps
                    },
                )
            }
            if (_state.value.detail?.row?.report?.packageName == packageName) refreshDetail()
        }
    }
}
