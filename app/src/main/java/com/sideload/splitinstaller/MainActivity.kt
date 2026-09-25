package com.sideload.splitinstaller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.history.HistoryEntry
import com.sideload.splitinstaller.core.history.InstallHistory
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.Capability
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.core.ota.OtaState
import com.sideload.splitinstaller.core.ota.OtaStore
import com.sideload.splitinstaller.core.update.UpdateWorker
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.ui.AppDetailActions
import com.sideload.splitinstaller.ui.AppNavigationBar
import com.sideload.splitinstaller.ui.AppDetailScreen
import com.sideload.splitinstaller.ui.AppsActions
import com.sideload.splitinstaller.ui.AppsScreen
import com.sideload.splitinstaller.ui.AppsViewModel
import com.sideload.splitinstaller.ui.BundleActions
import com.sideload.splitinstaller.ui.BundleScreen
import com.sideload.splitinstaller.ui.EmptyState
import com.sideload.splitinstaller.ui.HomeActions
import com.sideload.splitinstaller.ui.HomeScreen
import com.sideload.splitinstaller.ui.LogScreen
import com.sideload.splitinstaller.ui.SettingsActions
import com.sideload.splitinstaller.ui.SettingsScreen
import com.sideload.splitinstaller.ui.SettingsValues
import com.sideload.splitinstaller.ui.SideloadViewModel
import com.sideload.splitinstaller.ui.SplitSideloaderTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.ui.BrowserActions
import com.sideload.splitinstaller.ui.BrowserScreen
import com.sideload.splitinstaller.ui.SourcesActions
import com.sideload.splitinstaller.ui.SourcesScreen
import com.sideload.splitinstaller.ui.SourcesViewModel
import com.sideload.splitinstaller.ui.Tabs
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val vm: SideloadViewModel by viewModels()
    private val appsVm: AppsViewModel by viewModels()
    private val srcVm: SourcesViewModel by viewModels()
    private val prefs by lazy { Prefs.get(this) }

    private var pendingUninstall: String? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refreshCapabilities() }
    private val binderListener = Shizuku.OnBinderReceivedListener { vm.refreshCapabilities() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { vm.refreshCapabilities() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.open(uri)
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCapabilities() }

    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshCapabilities() }

    private val openSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshCapabilities() }

    private val uninstall =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingUninstall?.let { pkg ->
                appsVm.onPackageChanged(pkg)
                if (!InstallVerifier.isInstalled(this, pkg)) EventLog.info("uninstalled $pkg")
            }
            pendingUninstall = null
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Languages.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }

        setContent {
            val theme by prefs.theme.collectAsState()
            SplitSideloaderTheme(theme) { Root() }
        }
        handleIntent(intent)

        // A bundle that finishes downloading while the app is open goes straight to the
        // install screen, unless an install is already under way, which it must not interrupt.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Downloads.completed.collect { item ->
                    val uri = item.uri
                    if (item.status != DownloadStatus.DONE || !item.isBundle || uri == null) return@collect
                    if (item.expectedPackage == packageName) return@collect
                    val current = vm.state.value
                    // A download marked "installs itself" is already with InstallWorker;
                    // opening the same file here would run two installs over each other.
                    if (item.autoInstall && current.device?.silentAvailable == true) {
                        toast(getString(R.string.notif_installing))
                        return@collect
                    }
                    if (!current.installing && current.bundle == null) {
                        vm.open(uri)
                    } else {
                        toast(getString(R.string.download_done_toast, item.fileName))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshCapabilities()
        vm.checkOtaIfStale()
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
        super.onDestroy()
    }

    /** Set by the update notification so the app opens on what it is about. */
    private var showUpdatesRequest by androidx.compose.runtime.mutableStateOf(false)
    private var showOtaRequest by androidx.compose.runtime.mutableStateOf(false)

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATES, false) == true) {
            showUpdatesRequest = true
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_OTA, false) == true) {
            showOtaRequest = true
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.open(uri)
    }

    // ---- UI ---------------------------------------------------------------------------

    @Composable
    private fun Root() {
        val state by vm.state.collectAsState()
        val apps by appsVm.state.collectAsState()
        val sources by srcVm.state.collectAsState()
        val ota by OtaStore.status.collectAsState()
        val otaChecking by OtaStore.checking.collectAsState()
        val otaDismissed by vm.otaDismissed.collectAsState()
        val logLines by vm.log.collectAsState()
        val history by InstallHistory.entries.collectAsState()
        val theme by prefs.theme.collectAsState()
        var tab by rememberSaveable { mutableIntStateOf(Tabs.INSTALL) }
        var settings by remember { mutableStateOf(readSettings()) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(state.error) {
            state.error?.let {
                snackbar.showSnackbar(it)
                vm.clearError()
            }
        }
        LaunchedEffect(state.rootConfirmed) { appsVm.rootConfirmed = state.rootConfirmed }
        LaunchedEffect(showOtaRequest) {
            if (showOtaRequest) {
                tab = Tabs.INSTALL
                vm.undismissOta()
                showOtaRequest = false
            }
        }
        LaunchedEffect(showUpdatesRequest) {
            if (showUpdatesRequest) {
                tab = Tabs.APPS
                appsVm.setFilter(com.sideload.splitinstaller.ui.AppFilter.UPDATES)
                showUpdatesRequest = false
            }
        }
        LaunchedEffect(state.outcome) {
            (state.outcome as? InstallOutcome.Ok)?.packageName?.let { appsVm.onPackageChanged(it) }
        }

        val shellAvailable = state.device?.capabilities?.any {
            (it.kind == BackendKind.SHIZUKU || it.kind == BackendKind.ROOT) && it.state == BackendState.READY
        } == true

        // Overlays sit above the tabs; back closes them before it leaves the app. The browser
        // handles back itself: page history first, then close.
        val overlay = when {
            state.opening -> 1
            state.bundle != null -> 2
            sources.browser != null -> 3
            apps.detail != null && tab == Tabs.APPS -> 4
            else -> 0
        }
        BackHandler(enabled = state.bundle != null) { vm.closeBundle() }
        BackHandler(enabled = overlay == 4) { appsVm.closeDetail() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            AnimatedContent(
                targetState = overlay,
                transitionSpec = {
                    if (initialState == 0 || (targetState != 0 && targetState < initialState)) {
                        (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                    } else {
                        fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(200)))
                    }
                },
                label = "overlay",
            ) { layer ->
                when (layer) {
                    1 -> EmptyState(
                        icon = Icons.Rounded.InstallMobile,
                        title = stringResource(R.string.reading_bundle),
                        body = stringResource(R.string.reading_bundle_body),
                        modifier = Modifier.fillMaxSize().padding(top = 160.dp),
                    )
                    2 -> BundleScreen(
                        state = state,
                        backend = vm.effectiveBackend(),
                        actions = BundleActions(
                            onBack = vm::closeBundle,
                            onToggle = vm::toggleSplit,
                            onReset = vm::resetSelection,
                            onSelectAll = vm::selectAll,
                            onInstall = { uninstallFirst -> vm.install(uninstallFirst) },
                            onRepair = vm::repair,
                            onReverify = vm::reverify,
                            onLaunch = ::launchApp,
                            onBackupToggle = vm::setBackupFirst,
                        ),
                    )
                    3 -> sources.browser?.let { target ->
                        BrowserScreen(
                            target = target,
                            resumeUrl = sources.lastUrl,
                            downloads = sources.downloads,
                            actions = BrowserActions(
                                onClose = srcVm::closeBrowser,
                                onUrl = srcVm::onBrowserUrl,
                                onDownload = { request ->
                                    srcVm.download(request)
                                    toast(getString(R.string.download_started))
                                },
                                onOpenExternal = ::openExternal,
                                onInstall = { item -> item.uri?.let { vm.open(it) } },
                                onPinCurrent = { url ->
                                    val pkg = sources.pinFor
                                    if (pkg != null) {
                                        appsVm.pinWeb(pkg, sources.pinLabel, url)
                                        srcVm.clearPinRequest()
                                        srcVm.closeBrowser()
                                        toast(getString(R.string.browser_pinned))
                                    }
                                },
                                onCancelPin = srcVm::clearPinRequest,
                            ),
                            pinFor = sources.pinFor,
                            pinLabel = sources.pinLabel,
                        )
                    }
                    4 -> apps.detail?.let { detail ->
                        AppDetailScreen(
                            detail = detail,
                            shellAvailable = shellAvailable,
                            actions = AppDetailActions(
                                onBack = appsVm::closeDetail,
                                onRefresh = appsVm::refreshDetail,
                                onLaunch = ::launchApp,
                                onDiagnose = appsVm::diagnose,
                                onExport = appsVm::export,
                                onAppInfo = ::openAppInfo,
                                onUninstall = ::requestUninstall,
                                onRepairWithBundle = { pickFile.launch(arrayOf("*/*")) },
                                onFindUpdate = { query -> srcVm.searchFor(query) },
                                onCheckUpdate = appsVm::checkUpdate,
                                onPinFDroid = { pkg, label, onResult -> appsVm.pinFDroid(pkg, label) { onResult(it) } },
                                onPinGitHub = { pkg, label, input -> appsVm.pinGitHub(pkg, label, input) },
                                onPinWeb = { pkg, label ->
                                    // Find the page first; the browser then offers to pin it.
                                    srcVm.searchFor(label ?: pkg, pinFor = pkg, pinLabel = label)
                                },
                                onUnpin = appsVm::unpin,
                                onDownloadUpdate = { result ->
                                    appsVm.downloadUpdate(result)
                                    toast(getString(R.string.download_started))
                                },
                                onOpenPage = { url -> srcVm.openUrl(url) },
                            ),
                            pin = apps.pins[detail.row.report.packageName],
                            update = apps.updates[detail.row.report.packageName],
                            checkingUpdate = apps.checkingUpdates,
                        )
                    }
                    else -> Tabs(
                        tab = tab,
                        onTab = { tab = it },
                        state = state,
                        apps = apps,
                        sources = sources,
                        logLines = logLines,
                        history = history,
                        theme = theme,
                        settings = settings,
                        onSettings = { next ->
                            settings = next
                            writeSettings(next)
                            vm.setBackend(next.backend)
                        },
                        snackbar = snackbar,
                        ota = ota,
                        otaChecking = otaChecking,
                        otaDismissed = otaDismissed,
                    )
                }
            }
        }
    }

    @Composable
    private fun Tabs(
        tab: Int,
        onTab: (Int) -> Unit,
        state: com.sideload.splitinstaller.ui.UiState,
        apps: com.sideload.splitinstaller.ui.AppsState,
        sources: com.sideload.splitinstaller.ui.SourcesState,
        logLines: List<com.sideload.splitinstaller.core.log.LogLine>,
        history: List<HistoryEntry>,
        theme: com.sideload.splitinstaller.core.ThemeSettings,
        settings: SettingsValues,
        onSettings: (SettingsValues) -> Unit,
        snackbar: SnackbarHostState,
        ota: com.sideload.splitinstaller.core.ota.OtaStatus,
        otaChecking: Boolean,
        otaDismissed: Boolean,
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                AppNavigationBar(tab, apps.attention, sources.downloads.count { it.active }, onTab)
            },
        ) { padding ->
            val inner = Modifier.fillMaxSize().padding(padding)
            Crossfade(targetState = tab, animationSpec = tween(180), label = "tabs") { t ->
                when (t) {
                    Tabs.INSTALL -> HomeScreen(
                        state = state,
                        history = history,
                        autoInstall = settings.autoInstall,
                        actions = HomeActions(
                            onPickFile = { pickFile.launch(arrayOf("*/*")) },
                            onScan = vm::scan,
                            onOpenFound = { vm.open(it.uri) },
                            onRefresh = vm::refreshCapabilities,
                            onCapability = ::handleCapability,
                            onGrantInstall = ::requestInstallPermission,
                            onGrantStorage = ::requestStorage,
                            onGrantNotifications = ::requestNotificationPermission,
                            onToggleWatch = vm::setWatch,
                            onOpenSources = { onTab(Tabs.SOURCES) },
                            onOtaDownload = {
                                vm.downloadOta { toast(getString(R.string.download_started)) }
                            },
                            onOtaInstall = vm::installOta,
                            onOtaDismiss = vm::dismissOta,
                            onOpenHistory = { entry ->
                                val pkg = entry.packageName
                                if (pkg != null && InstallVerifier.isInstalled(this, pkg)) {
                                    onTab(Tabs.APPS)
                                    appsVm.openDetail(pkg)
                                } else {
                                    toast(getString(R.string.not_installed_now))
                                }
                            },
                        ),
                        modifier = inner,
                        ota = ota.takeIf { !otaDismissed && OtaChannel.parse(settings.otaChannel).isOn },
                        otaDownloading = sources.downloads.any {
                            it.active && !it.waitingForWifi && it.expectedPackage == packageName
                        },
                    )
                    Tabs.SOURCES -> SourcesScreen(
                        state = sources,
                        actions = SourcesActions(
                            onQuery = srcVm::setQuery,
                            onSelect = srcVm::select,
                            onSearch = srcVm::search,
                            onOpenSource = srcVm::openHome,
                            onShowAdd = srcVm::showAdd,
                            onAdd = srcVm::addSource,
                            onRemoveSource = srcVm::removeSource,
                            onInstallDownload = { item -> item.uri?.let { vm.open(it) } },
                            onCancelDownload = srcVm::cancel,
                            onForgetDownload = srcVm::forget,
                            onOpenExternal = ::openExternal,
                        ),
                        modifier = inner,
                    )
                    Tabs.APPS -> AppsScreen(
                        state = apps,
                        actions = AppsActions(
                            onScan = appsVm::scan,
                            onQuery = appsVm::setQuery,
                            onFilter = appsVm::setFilter,
                            onToggleSystem = appsVm::setIncludeSystem,
                            onOpen = { appsVm.openDetail(it) },
                            onCheckUpdates = appsVm::checkAllUpdates,
                        ),
                        modifier = inner,
                    )
                    Tabs.LOG -> LogScreen(
                        lines = logLines,
                        onClear = EventLog::clear,
                        onShare = ::shareLog,
                        modifier = inner,
                    )
                    else -> SettingsScreen(
                        theme = theme,
                        values = settings,
                        device = state.device,
                        ota = ota,
                        otaChecking = otaChecking,
                        actions = SettingsActions(
                            onTheme = { next -> prefs.updateTheme { next } },
                            onValues = onSettings,
                            onCopy = ::copyToClipboard,
                            onAppInfo = { openAppInfo(packageName) },
                            onClearHistory = {
                                InstallHistory.clear(this)
                                toast(getString(R.string.history_cleared))
                            },
                            onBatteryExempt = ::requestBatteryExemption,
                            onOtaCheck = vm::checkOta,
                            onOtaDownload = {
                                vm.downloadOta { toast(getString(R.string.download_started)) }
                            },
                            onOtaInstall = vm::installOta,
                        ),
                        modifier = inner,
                    )
                }
            }
        }
    }

    // ---- settings bridge ------------------------------------------------------------------

    private fun readSettings() = SettingsValues(
        backend = prefs.backend,
        installObb = prefs.installObb,
        allowDowngrade = prefs.allowDowngrade,
        grantAll = prefs.grantAllPermissions,
        verifyChecksums = prefs.verifyChecksums,
        backupBeforeUpdate = prefs.backupBeforeUpdate,
        autoInstall = prefs.autoInstall,
        updatesOnly = prefs.autoInstallUpdatesOnly,
        updateInterval = prefs.updateInterval,
        updateWifiOnly = prefs.updateWifiOnly,
        updateAutoDownload = prefs.updateAutoDownload,
        updateAutoInstall = prefs.updateAutoInstall,
        language = prefs.language,
        otaChannel = prefs.otaChannel,
        otaAutoDownload = prefs.otaAutoDownload,
        otaAutoInstall = prefs.otaAutoInstall,
    )

    private fun writeSettings(v: SettingsValues) {
        prefs.backend = v.backend
        prefs.installObb = v.installObb
        prefs.allowDowngrade = v.allowDowngrade
        prefs.grantAllPermissions = v.grantAll
        prefs.verifyChecksums = v.verifyChecksums
        prefs.backupBeforeUpdate = v.backupBeforeUpdate
        prefs.autoInstall = v.autoInstall
        prefs.autoInstallUpdatesOnly = v.updatesOnly
        prefs.updateInterval = v.updateInterval
        prefs.updateWifiOnly = v.updateWifiOnly
        prefs.updateAutoDownload = v.updateAutoDownload
        prefs.updateAutoInstall = v.updateAutoInstall
        // A different language means different resources, so the screen is rebuilt.
        val languageChanged = prefs.language != v.language
        prefs.language = v.language
        // Only what changed is written, so a build's defaults are never frozen into settings by
        // an unrelated change.
        if (v.otaChannel.trim() != prefs.otaChannel) {
            prefs.otaChannel = v.otaChannel
            // What the old channel said no longer applies: drop its build, file and transfers,
            // and ask the new one straight away.
            OtaStore.retire(this, OtaState.OFF, forgetCheck = true)
            vm.checkOtaIfStale()
        }
        if (v.otaAutoDownload != prefs.otaAutoDownload) prefs.otaAutoDownload = v.otaAutoDownload
        if (v.otaAutoInstall != prefs.otaAutoInstall) prefs.otaAutoInstall = v.otaAutoInstall
        vm.setBackupFirst(v.backupBeforeUpdate)
        if (languageChanged) recreate()
        // The schedule follows the setting immediately, not at the next launch.
        runCatching { UpdateWorker.apply(this) }
    }

    // ---- actions ----------------------------------------------------------------------------

    private fun handleCapability(cap: Capability) {
        when (cap.kind) {
            BackendKind.SHIZUKU -> when (cap.action) {
                "grant" -> runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                    .onFailure { toast(getString(R.string.shizuku_request_failed)) }
                "start" -> openShizukuApp()
            }
            BackendKind.PACKAGE_INSTALLER -> requestInstallPermission()
            BackendKind.ROOT -> vm.testRoot()
        }
    }

    private fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        runCatching { openSettings.launch(intent) }
            .onFailure { openSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { openSettings.launch(intent) }
                .onFailure { openSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestLegacyStorage.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) startActivity(intent) else toast(getString(R.string.shizuku_not_installed))
    }

    private fun openAppInfo(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    private fun requestUninstall(pkg: String) {
        pendingUninstall = pkg
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { uninstall.launch(intent) }.onFailure { toast(it.message ?: "uninstall failed") }
    }

    /** Aggressive ROMs freeze background work; this is the switch that stops them. */
    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + packageName),
        )
        runCatching { openSettings.launch(direct) }
            .onFailure {
                runCatching { openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }.onFailure { toast(it.message ?: url) }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent) else toast(getString(R.string.no_launcher_activity))
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SplitSideloader", text))
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(getString(R.string.copied))
    }

    private fun shareLog() {
        val text = EventLog.dump().takeLast(200_000)
        if (text.isBlank()) {
            toast(getString(R.string.log_empty))
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " log")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share_log)))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        /** Extra on the launch intent from an update notification. */
        const val EXTRA_SHOW_UPDATES = "show_updates"

        /** Extra on the launch intent from this app's own update notification. */
        const val EXTRA_SHOW_OTA = "show_ota"
        private const val SHIZUKU_REQUEST = 4211
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
