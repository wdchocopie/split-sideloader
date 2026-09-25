package com.sideload.splitinstaller

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.sideload.splitinstaller.core.AppLanguage
import com.sideload.splitinstaller.core.ThemeMode
import com.sideload.splitinstaller.core.ThemeSettings
import com.sideload.splitinstaller.core.UpdateInterval
import com.sideload.splitinstaller.core.apps.InstallerInfo
import com.sideload.splitinstaller.core.apps.LaunchDiagnosis
import com.sideload.splitinstaller.core.apps.LaunchProblem
import com.sideload.splitinstaller.core.axml.NativeEngine
import com.sideload.splitinstaller.core.bundle.BundleFormat
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.bundle.Finding
import com.sideload.splitinstaller.core.bundle.FindingCode
import com.sideload.splitinstaller.core.bundle.FoundBundle
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitApk
import com.sideload.splitinstaller.core.bundle.SplitKind
import com.sideload.splitinstaller.core.bundle.SplitNote
import com.sideload.splitinstaller.core.history.HistoryEntry
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.Capability
import com.sideload.splitinstaller.core.install.DeviceReport
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.log.LogLine
import com.sideload.splitinstaller.core.ota.OtaRelease
import com.sideload.splitinstaller.core.ota.OtaState
import com.sideload.splitinstaller.core.ota.OtaStatus
import com.sideload.splitinstaller.core.sign.ApkSignature
import com.sideload.splitinstaller.core.sign.CertInfo
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.sign.SignatureScheme
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.Source
import com.sideload.splitinstaller.core.sources.SourceTrust
import com.sideload.splitinstaller.core.sources.Sources
import com.sideload.splitinstaller.core.update.UpdateKind
import com.sideload.splitinstaller.core.update.UpdatePin
import com.sideload.splitinstaller.core.update.UpdateResult
import com.sideload.splitinstaller.core.update.UpdateState
import com.sideload.splitinstaller.core.verify.InstalledApk
import com.sideload.splitinstaller.core.verify.Verdict
import com.sideload.splitinstaller.core.verify.VerifyReport
import com.sideload.splitinstaller.ui.AppDetail
import com.sideload.splitinstaller.ui.AppDetailActions
import com.sideload.splitinstaller.ui.AppDetailScreen
import com.sideload.splitinstaller.ui.AppNavigationBar
import com.sideload.splitinstaller.ui.AppRow
import com.sideload.splitinstaller.ui.AppsActions
import com.sideload.splitinstaller.ui.AppsScreen
import com.sideload.splitinstaller.ui.AppFilter
import com.sideload.splitinstaller.ui.AppsState
import com.sideload.splitinstaller.ui.BundleActions
import com.sideload.splitinstaller.ui.BundleScreen
import com.sideload.splitinstaller.ui.HomeActions
import com.sideload.splitinstaller.ui.HomeScreen
import com.sideload.splitinstaller.ui.InstalledState
import com.sideload.splitinstaller.ui.LogScreen
import com.sideload.splitinstaller.ui.SettingsActions
import com.sideload.splitinstaller.ui.SettingsScreen
import com.sideload.splitinstaller.ui.SettingsValues
import com.sideload.splitinstaller.ui.SourcesActions
import com.sideload.splitinstaller.ui.SourcesScreen
import com.sideload.splitinstaller.ui.SourcesState
import com.sideload.splitinstaller.ui.SplitSideloaderTheme
import com.sideload.splitinstaller.ui.Tabs
import com.sideload.splitinstaller.ui.UiState
import org.junit.Rule
import org.junit.Test

/**
 * Renders the real screens with realistic data, in both themes, so the design can be
 * reviewed without a device. The scenario is the one the app exists for: a Unity game
 * whose store-client install lost its ABI split.
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 4200, locale = "vi"),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 0.5,
        useDeviceResolution = true,
    )

    /** The app's own language setting means English is a first-class rendering, not a fallback. */
    private fun englishShot(name: String, dark: Boolean, height: Int = 4200, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(screenHeight = height, locale = "en"))
        shot(name, dark, content = content)
    }

    /** Settings is longer than one tall frame, and a LazyColumn only renders what fits. */
    private fun tallShot(name: String, dark: Boolean, height: Int, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(screenHeight = height, locale = "vi"))
        shot(name, dark, content = content)
    }

    private fun shot(name: String, dark: Boolean, amoled: Boolean = false, content: @Composable () -> Unit) {
        paparazzi.snapshot(name) {
            SplitSideloaderTheme(ThemeSettings(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, amoled = amoled)) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
    }

    @Composable
    private fun WithNav(tab: Int, attention: Int = 1, downloading: Int = 1, content: @Composable (Modifier) -> Unit) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = { AppNavigationBar(tab, attention, downloading) {} },
        ) { padding -> content(Modifier.padding(padding)) }
    }

    // ---- english -----------------------------------------------------------------------------

    @Test fun home_en() = englishShot("home_en", dark = true) { HomeTab(Samples.otaUpdate) }

    @Test fun app_detail_en() = englishShot("app_detail_en", dark = false) {
        AppDetailScreen(Samples.brokenDetail, shellAvailable = true, actions = AppDetailActions())
    }

    @Test fun settings_en() = englishShot("settings_en", dark = true, height = 7000) {
        WithNav(Tabs.SETTINGS) { m ->
            SettingsScreen(
                theme = ThemeSettings(ThemeMode.DARK),
                values = Samples.settings,
                device = Samples.device,
                ota = Samples.otaReady,
                actions = SettingsActions(),
                modifier = m,
            )
        }
    }

    // ---- install tab ------------------------------------------------------------------------

    @Test fun home_light() = shot("home_light", dark = false) { HomeTab() }
    @Test fun home_dark() = shot("home_dark", dark = true) { HomeTab() }

    @Test fun home_ota_light() = shot("home_ota_light", dark = false) { HomeTab(Samples.otaUpdate) }
    @Test fun home_ota_dark() = shot("home_ota_dark", dark = true) { HomeTab(Samples.otaReady) }

    @Composable
    private fun HomeTab(ota: OtaStatus? = null) = WithNav(Tabs.INSTALL) { m ->
        HomeScreen(
            state = UiState(device = Samples.device, found = Samples.found, scannedOnce = true, watchEnabled = true),
            history = Samples.history,
            autoInstall = true,
            actions = HomeActions(),
            modifier = m,
            ota = ota,
        )
    }

    // ---- bundle -----------------------------------------------------------------------------

    @Test fun bundle_light() = shot("bundle_light", dark = false) {
        BundleScreen(Samples.bundleIdle, BackendKind.SHIZUKU, BundleActions())
    }

    @Test fun bundle_dark_installing() = shot("bundle_dark_installing", dark = true) {
        BundleScreen(
            Samples.bundleIdle.copy(installing = true, progress = 0.43f, progressLabel = "config.arm64_v8a.apk"),
            BackendKind.SHIZUKU,
            BundleActions(),
        )
    }

    @Test fun bundle_dark_done() = shot("bundle_dark_done", dark = true) {
        BundleScreen(
            Samples.bundleIdle.copy(
                outcome = InstallOutcome.Ok(Samples.PKG, Samples.okReport),
                verifyReport = Samples.okReport,
            ),
            BackendKind.SHIZUKU,
            BundleActions(),
        )
    }

    @Test fun bundle_light_repair() = shot("bundle_light_repair", dark = false) {
        BundleScreen(Samples.bundleRepair, BackendKind.PACKAGE_INSTALLER, BundleActions())
    }

    // ---- apps -------------------------------------------------------------------------------

    @Test fun apps_light() = shot("apps_light", dark = false) {
        WithNav(Tabs.APPS, attention = 2) { m -> AppsScreen(Samples.apps, AppsActions(), m) }
    }

    @Test fun apps_dark() = shot("apps_dark", dark = true) {
        WithNav(Tabs.APPS, attention = 2) { m -> AppsScreen(Samples.apps, AppsActions(), m) }
    }

    @Test fun apps_updates_dark() = shot("apps_updates_dark", dark = true) {
        WithNav(Tabs.APPS, attention = 2) { m ->
            AppsScreen(Samples.apps.copy(filter = AppFilter.UPDATES, updates = Samples.updates), AppsActions(), m)
        }
    }

    @Test fun apps_updates_light() = shot("apps_updates_light", dark = false) {
        WithNav(Tabs.APPS, attention = 2) { m ->
            AppsScreen(Samples.apps.copy(updates = Samples.updates), AppsActions(), m)
        }
    }

    // ---- sources ------------------------------------------------------------------------------

    @Test fun sources_light() = shot("sources_light", dark = false) {
        WithNav(Tabs.SOURCES) { m -> SourcesScreen(Samples.sources, SourcesActions(), m) }
    }

    @Test fun sources_dark() = shot("sources_dark", dark = true) {
        WithNav(Tabs.SOURCES) { m -> SourcesScreen(Samples.sources, SourcesActions(), m) }
    }

    @Test fun app_detail_dark() = shot("app_detail_dark", dark = true) {
        AppDetailScreen(Samples.brokenDetail, shellAvailable = true, actions = AppDetailActions())
    }

    @Test fun app_detail_update_light() = shot("app_detail_update_light", dark = false) {
        AppDetailScreen(
            Samples.healthyDetail, shellAvailable = true, actions = AppDetailActions(),
            pin = Samples.githubPin, update = Samples.githubUpdate,
        )
    }

    @Test fun app_detail_no_source_dark() = shot("app_detail_no_source_dark", dark = true) {
        AppDetailScreen(Samples.healthyDetail, shellAvailable = false, actions = AppDetailActions())
    }

    @Test fun app_detail_amoled() = shot("app_detail_amoled", dark = true, amoled = true) {
        AppDetailScreen(Samples.brokenDetail, shellAvailable = true, actions = AppDetailActions())
    }

    // ---- log & settings -----------------------------------------------------------------------

    @Test fun log_dark() = shot("log_dark", dark = true) {
        WithNav(Tabs.LOG) { m -> LogScreen(Samples.log, {}, {}, m) }
    }

    @Test fun settings_light() = tallShot("settings_light", dark = false, height = 7000) {
        WithNav(Tabs.SETTINGS) { m ->
            SettingsScreen(
                theme = ThemeSettings(ThemeMode.LIGHT),
                values = Samples.settings,
                device = Samples.device,
                ota = Samples.otaReady,
                actions = SettingsActions(),
                modifier = m,
            )
        }
    }

    @Test fun settings_dark() = tallShot("settings_dark", dark = true, height = 7000) {
        WithNav(Tabs.SETTINGS) { m ->
            SettingsScreen(
                theme = ThemeSettings(ThemeMode.DARK),
                values = Samples.settings,
                device = Samples.device,
                ota = Samples.otaReady,
                actions = SettingsActions(),
                modifier = m,
            )
        }
    }
}

private object Samples {
    const val PKG = "com.HoYoverse.Nap"
    private val now = System.currentTimeMillis()
    private const val MB = 1024L * 1024

    val device = DeviceReport(
        model = "Blackshark SHARK PRS-A0",
        androidRelease = "13",
        sdk = 33,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        primaryAbi = "arm64-v8a",
        densityDpi = 440,
        is64Bit = true,
        suBinary = null,
        capabilities = listOf(
            Capability(BackendKind.SHIZUKU, BackendState.READY, true, "v13 (uid 2000) · adb"),
            Capability(BackendKind.ROOT, BackendState.UNAVAILABLE, true, "no su binary"),
            Capability(BackendKind.PACKAGE_INSTALLER, BackendState.READY, false, "one system prompt per install"),
        ),
        canRequestInstalls = true,
        hasAllFilesAccess = false,
        canPostNotifications = false,
        batteryUnrestricted = false,
        freeDataBytes = 61_400 * MB,
        totalDataBytes = 227_000 * MB,
        freeExternalBytes = 61_400 * MB,
    )

    private val playCert = CertInfo(
        sha256 = "c3a1f59e0b7d44e28a1b6f0d9e3c5a7b2d4f6e8091a3b5c7d9e1f2a4b6c8d0e2",
        subject = "CN=Android,OU=Android,O=Google Inc.,L=Mountain View,ST=California,C=US",
        issuer = "CN=Android,OU=Android,O=Google Inc.,L=Mountain View,ST=California,C=US",
        notBefore = 0,
        notAfter = 0,
    )
    private val sig = ApkSignature(setOf(SignatureScheme.V2, SignatureScheme.V3), playCert, v1SignerFile = "META-INF/BNDLTOOL.RSA")

    private val base = SplitApk(
        "com.HoYoverse.Nap.apk", "com.HoYoverse.Nap.apk", 33 * MB, SplitKind.BASE,
        packageName = PKG, versionCode = 20200, versionName = "2.2.0", inspected = true, signature = sig,
    )
    private val arm64 = SplitApk(
        "config.arm64_v8a.apk", "config.arm64_v8a.apk", 171 * MB, SplitKind.ABI,
        splitName = "config.arm64_v8a", packageName = PKG, versionCode = 20200,
        abis = setOf("arm64-v8a"), targetAbi = "arm64-v8a", nativeLibCount = 14, inspected = true, signature = sig,
    )
    private val assets = SplitApk(
        "UnityDataAssetPack.apk", "UnityDataAssetPack.apk", 279 * MB, SplitKind.FEATURE,
        splitName = "UnityDataAssetPack", packageName = PKG, versionCode = 20200, inspected = true, signature = sig,
    )

    val bundle = BundleInfo(
        uri = Uri.parse("content://downloads/zzz.xapk"),
        displayName = "Zenless Zone Zero_2.2.0_APKPure.xapk",
        totalSize = 483 * MB,
        format = BundleFormat.XAPK,
        packageName = PKG,
        appLabel = "Zenless Zone Zero",
        versionCode = 20200,
        versionName = "2.2.0",
        minSdk = 24,
        apks = listOf(base, arm64, assets),
        obbs = emptyList(),
        findings = emptyList(),
        engine = NativeEngine.UNITY,
    )

    private val notes = mapOf(
        base.id to SplitNote(SplitNote.BASE),
        arm64.id to SplitNote(SplitNote.ABI, "arm64-v8a"),
        assets.id to SplitNote(SplitNote.FEATURE),
    )

    val bundleIdle = UiState(
        device = device,
        bundle = bundle,
        installedState = InstalledState(20100, "2.1.0", "com.sideload.splitinstaller", listOf("config.arm64_v8a", "UnityDataAssetPack")),
        selection = bundle.apks.map { it.id }.toSet(),
        notes = notes,
        signatureMatch = SignatureMatch.MATCH,
        backupFirst = true,
    )

    val bundleRepair = UiState(
        device = device,
        bundle = bundle,
        installedState = InstalledState(20200, "2.2.0", "com.qooapp.qoohelper", listOf("UnityDataAssetPack")),
        selection = bundle.apks.map { it.id }.toSet(),
        notes = notes,
        signatureMatch = SignatureMatch.MATCH,
        repairSplits = listOf(arm64),
    )

    private const val DIR = "/data/app/~~bK3wQ1x9Tq==/com.HoYoverse.Nap-Zk2mRr8V=="

    val okReport = VerifyReport(
        packageName = PKG, verdict = Verdict.OK, label = "Zenless Zone Zero",
        versionName = "2.2.0", versionCode = 20200,
        primaryCpuAbi = "arm64-v8a", abiSource = "dumpsys",
        nativeLibraryDir = "$DIR/lib/arm64",
        nativeLibs = listOf("libanogs.so", "libil2cpp.so", "libmain.so", "libunity.so", "libxlua.so", "libBugly.so"),
        apkAbis = setOf("arm64-v8a"), usableAbis = setOf("arm64-v8a"),
        apks = listOf(
            InstalledApk("$DIR/base.apk", null, 33 * MB, emptySet(), 0, true),
            InstalledApk("$DIR/split_config.arm64_v8a.apk", "config.arm64_v8a", 171 * MB, setOf("arm64-v8a"), 14, true),
            InstalledApk("$DIR/split_UnityDataAssetPack.apk", "UnityDataAssetPack", 279 * MB, emptySet(), 0, true),
        ),
        splitNames = listOf("config.arm64_v8a", "UnityDataAssetPack"),
        extractNativeLibs = true, engine = NativeEngine.UNITY, nativeExpected = true,
        installerPackage = "com.sideload.splitinstaller",
        findings = listOf(Finding(Severity.INFO, "native code for arm64-v8a is installed", FindingCode.NATIVE_OK, listOf("arm64-v8a"))),
    )

    private val brokenReport = VerifyReport(
        packageName = PKG, verdict = Verdict.BROKEN, label = "Zenless Zone Zero",
        versionName = "2.2.0", versionCode = 20200,
        primaryCpuAbi = null, abiSource = "dumpsys",
        nativeLibraryDir = "$DIR/lib/arm64",
        apks = listOf(
            InstalledApk("$DIR/base.apk", null, 33 * MB, emptySet(), 0, true),
            InstalledApk("$DIR/split_UnityDataAssetPack.apk", "UnityDataAssetPack", 279 * MB, emptySet(), 0, true),
        ),
        splitNames = listOf("UnityDataAssetPack"),
        engine = NativeEngine.UNITY, nativeExpected = true,
        installerPackage = "com.qooapp.qoohelper",
        firstInstallTime = now - 3 * 86_400_000L,
        lastUpdateTime = now - 3 * 86_400_000L,
        findings = listOf(
            Finding(Severity.ERROR, "a Unity app, but no installed APK contains lib/<abi>/", FindingCode.NO_NATIVE_ENGINE, listOf("Unity")),
        ),
    )

    private fun app(
        label: String, pkg: String, version: String, verdict: Verdict, splits: Int,
        installer: InstallerInfo, engine: NativeEngine? = null, abi: String? = "arm64-v8a",
    ) = AppRow(
        VerifyReport(
            packageName = pkg, verdict = verdict, label = label, versionName = version,
            primaryCpuAbi = abi, splitNames = List(splits) { "config.$it" }, engine = engine,
            nativeExpected = engine != null,
        ),
        installer,
    )

    private val play = InstallerInfo("com.android.vending", "Google Play", false)
    private val qoo = InstallerInfo("com.qooapp.qoohelper", "QooApp", true)
    private val apkpure = InstallerInfo("com.apkpure.aegon", "APKPure", true)
    private val self = InstallerInfo("com.sideload.splitinstaller", "Split Sideloader", false)
    private val fdroid = InstallerInfo("org.fdroid.fdroid", "F-Droid", false)

    val apps = AppsState(
        scannedOnce = true,
        apps = listOf(
            AppRow(brokenReport, qoo),
            app("Blue Archive", "com.nexon.bluearchive", "1.60.3", Verdict.WARN, 2, apkpure, NativeEngine.UNITY),
            app("Honkai: Star Rail", "com.HoYoverse.hkrpgoversea", "3.5.0", Verdict.OK, 2, self, NativeEngine.UNITY),
            app("Genshin Impact", "com.miHoYo.GenshinImpact", "5.8.0", Verdict.OK, 3, play, NativeEngine.UNITY),
            app("Shizuku", "moe.shizuku.privileged.api", "13.5.4", Verdict.OK, 0, play, abi = null),
            app("Termux", "com.termux", "0.119.0", Verdict.OK, 0, fdroid),
            app("Telegram", "org.telegram.messenger", "11.14.1", Verdict.OK, 4, play),
        ),
    )

    val brokenDetail = AppDetail(
        row = AppRow(brokenReport, qoo),
        signer = playCert,
        diagnosis = LaunchDiagnosis(
            LaunchProblem.MISSING_NATIVE_LIB,
            listOf(
                "09-22 14:03:11.482 E/Unity   (12055): Failed to load libmain.so",
                "09-22 14:03:11.483 E/linker  (12055): library \"libmain.so\" not found",
                "09-22 14:03:11.490 E/AndroidRuntime(12055): FATAL EXCEPTION: main",
                "09-22 14:03:11.490 E/AndroidRuntime(12055): Process: com.HoYoverse.Nap, PID: 12055",
                "09-22 14:03:11.490 E/AndroidRuntime(12055): java.lang.UnsatisfiedLinkError: dlopen failed: library \"libmain.so\" not found",
                "09-22 14:03:11.490 E/AndroidRuntime(12055): \tat java.lang.Runtime.loadLibrary0(Runtime.java:1082)",
                "09-22 14:03:11.490 E/AndroidRuntime(12055): \tat com.unity3d.player.UnityPlayer.<clinit>(Unknown Source:2)",
                "09-22 14:03:11.502 W/ActivityManager( 1640):   Force finishing activity com.HoYoverse.Nap/.MainActivity",
            ),
        ),
    )

    /** Termux ships on F-Droid and GitHub, which is exactly what the two automatic kinds cover. */
    val githubPin = UpdatePin("com.termux", UpdateKind.GITHUB, "termux/termux-app", "Termux")
    val githubUpdate = UpdateResult(
        packageName = "com.termux",
        kind = UpdateKind.GITHUB,
        state = UpdateState.UPDATE,
        label = "Termux",
        installedVersionName = "0.119.0",
        availableVersionName = "v0.120.1",
        downloadUrl = "https://github.com/termux/termux-app/releases/download/v0.120.1/termux-app_v0.120.1+github-debug_arm64-v8a.apk",
        fileName = "termux-app_v0.120.1_arm64-v8a.apk",
        size = 94 * MB,
        pageUrl = "https://github.com/termux/termux-app/releases/tag/v0.120.1",
        checkedAt = now - 9 * 60_000L,
    )

    val updates = mapOf(
        "com.termux" to githubUpdate,
        PKG to UpdateResult(
            packageName = PKG, kind = UpdateKind.WEB, state = UpdateState.MANUAL, label = "Zenless Zone Zero",
            installedVersionName = "2.2.0",
            pageUrl = "https://www.apkmirror.com/apk/hoyoverse/zenless-zone-zero/",
            checkedAt = now - 9 * 60_000L,
        ),
        "com.nexon.bluearchive" to UpdateResult(
            packageName = "com.nexon.bluearchive", kind = UpdateKind.WEB, state = UpdateState.UPDATE,
            label = "Blue Archive", installedVersionName = "1.60.3", availableVersionName = "1.61.0",
            pageUrl = "https://www.apkmirror.com/apk/nexon-company/blue-archive/",
            checkedAt = now - 9 * 60_000L,
        ),
        "org.fdroid.fdroid" to UpdateResult(
            packageName = "org.fdroid.fdroid", kind = UpdateKind.FDROID, state = UpdateState.UP_TO_DATE,
            label = "F-Droid", installedVersionName = "1.21.1", availableVersionName = "1.21.1",
            checkedAt = now - 9 * 60_000L,
        ),
    )

    val settings = SettingsValues(
        autoInstall = true,
        updateInterval = UpdateInterval.DAILY,
        updateWifiOnly = true,
        updateAutoDownload = true,
        updateAutoInstall = true,
        language = AppLanguage.SYSTEM,
        otaChannel = "wdchocopie/split-sideloader",
        otaAutoDownload = true,
        otaAutoInstall = true,
    )

    private val otaRelease = OtaRelease(
        versionName = "1.9.0",
        versionCode = 9,
        url = "https://example.org/r/SplitSideloader-1.9.0.apk",
        fileName = "SplitSideloader-1.9.0.apk",
        sha256 = "a3196fd24e21e129c2ca2b861d6f3371ece65e7ed7fceecb9c6087f062aba4a7",
        size = 12 * MB,
        notes = "Sửa lỗi đọc .apkm có ZIP64, thêm nguồn tự thêm.",
    )

    val otaUpdate = OtaStatus(OtaState.UPDATE, otaRelease, checkedAt = now - 5 * 60_000L)
    val otaReady = OtaStatus(
        OtaState.READY, otaRelease, checkedAt = now - 5 * 60_000L,
        fileUri = "content://downloads/9",
    )

    private val termuxReport = VerifyReport(
        packageName = "com.termux", verdict = Verdict.OK, label = "Termux", versionName = "0.119.0",
        versionCode = 119, primaryCpuAbi = "arm64-v8a", abiSource = "dumpsys",
        nativeLibraryDir = "/data/app/~~9pQ2==/com.termux-4Lm7==/lib/arm64",
        nativeLibs = listOf("libtermux-bootstrap.so"),
        apkAbis = setOf("arm64-v8a"), usableAbis = setOf("arm64-v8a"),
        extractNativeLibs = true, nativeExpected = true, installerPackage = "com.sideload.splitinstaller",
        firstInstallTime = now - 40 * 86_400_000L, lastUpdateTime = now - 6 * 86_400_000L,
        findings = listOf(Finding(Severity.INFO, "native code for arm64-v8a is installed", FindingCode.NATIVE_OK, listOf("arm64-v8a"))),
    )

    val healthyDetail = AppDetail(row = AppRow(termuxReport, self), signer = playCert)

    val found = listOf(
        FoundBundle(Uri.parse("content://d/1"), "Zenless Zone Zero_2.2.0_APKPure.xapk", 483 * MB, now - 12 * 60_000L, null),
        FoundBundle(Uri.parse("content://d/2"), "com.kurogame.wutheringwaves.global_2.6.1.apkm", 1_240 * MB, now - 26 * 3_600_000L, null),
        FoundBundle(Uri.parse("content://d/3"), "Termux_0.119.0.apks", 108 * MB, now - 3 * 86_400_000L, null),
    )

    val history = listOf(
        HistoryEntry(now - 4 * 60_000L, PKG, "Zenless Zone Zero", "2.2.0", 20200, "zzz.xapk", "SHIZUKU", true, "OK", null, "arm64-v8a", 3),
        HistoryEntry(now - 2 * 3_600_000L, "com.HoYoverse.hkrpgoversea", "Honkai: Star Rail", "3.5.0", 1, "hsr.apkm", "SHIZUKU", true, "OK", null, "arm64-v8a", 1, repair = true),
        HistoryEntry(now - 30 * 3_600_000L, "com.nexon.bluearchive", "Blue Archive", "1.60.3", 1, "ba.xapk", "PACKAGE_INSTALLER", false, null, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null, 3),
    )

    val log: List<LogLine> = listOf(
        "──── reading Zenless Zone Zero_2.2.0_APKPure.xapk ────" to Severity.INFO,
        "XAPK · com.HoYoverse.Nap · 2.2.0 (20200) · 3 APK(s) · Unity" to Severity.INFO,
        "signer: Android · SHA-256 C3:A1:F5:9E:0B:7D:44:E2…" to Severity.INFO,
        "──── installing Zenless Zone Zero_2.2.0_APKPure.xapk ────" to Severity.INFO,
        "backend: SHIZUKU" to Severity.INFO,
        "installing 3 APK(s), 483.0 MB" to Severity.INFO,
        "signer matches the installed copy" to Severity.INFO,
        "backing up the installed copy first" to Severity.INFO,
        "backup saved: Download/SplitSideloader/Zenless_Zone_Zero_2.1.0_20100.apks (471.2 MB)" to Severity.INFO,
        "session 1284210417 created via shizuku" to Severity.INFO,
        "wrote com.HoYoverse.Nap.apk" to Severity.INFO,
        "wrote config.arm64_v8a.apk" to Severity.INFO,
        "wrote UnityDataAssetPack.apk" to Severity.INFO,
        "Success" to Severity.INFO,
        "native code for arm64-v8a is installed · primaryCpuAbi=arm64-v8a" to Severity.INFO,
        "──── scanning installed apps ────" to Severity.INFO,
        "scanned 142 app(s): 1 broken, 2 need attention" to Severity.INFO,
        "broken install: Zenless Zone Zero (com.HoYoverse.Nap)" to Severity.ERROR,
        "extractNativeLibs is on but the lib folder is empty — the libraries were not extracted" to Severity.WARN,
    ).mapIndexed { i, (msg, sev) -> LogLine(now - (40 - i) * 7_000L, sev, msg) }

    val sources = SourcesState(
        sources = Sources.BUILTIN + Source(
            "custom-1", "HoYoverse", "https://www.hoyoverse.com/", null,
            emptyList(), SourceTrust.CUSTOM, builtin = false,
        ),
        selectedId = "apkmirror",
        query = "zenless zone zero",
        downloads = listOf(
            DownloadItem(
                1, "com.HoYoverse.Nap_2.3.0-20300_4arch_7dpi_apkmirror.com.apkm", "apkmirror.com",
                DownloadStatus.RUNNING, 208 * MB, 486 * MB, 0, null, null, now,
            ),
            DownloadItem(
                2, "Termux_0.119.0_APKPure.xapk", "apkpure.com",
                DownloadStatus.DONE, 108 * MB, 108 * MB, 0, Uri.parse("content://downloads/2"), null, now - 600_000L,
            ),
            DownloadItem(
                3, "Wuthering Waves_2.6.1_APKPure.xapk", "apkpure.com",
                DownloadStatus.FAILED, 0, -1, 403, null, "https://d.apkpure.com/b/XAPK/x", now - 900_000L,
            ),
        ),
    )
}
