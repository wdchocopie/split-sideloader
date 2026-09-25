package com.sideload.splitinstaller.core.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import com.sideload.splitinstaller.core.verify.InstallVerifier
import com.sideload.splitinstaller.core.verify.VerifyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Where a package came from, as far as the package manager remembers. */
data class InstallerInfo(val packageName: String?, val label: String, val risky: Boolean)

/**
 * Health check for everything already on the device.
 *
 * This is the on-device version of running `dumpsys package` against every app: the
 * failure it hunts for is the one a store client leaves behind — a base-only install that
 * reports the right version and dies at launch.
 */
object AppScanner {

    /**
     * Store clients observed to produce base-only installs from bundles that install fine
     * with every split. Not malicious — their installers just drop the ABI split.
     */
    private val RISKY_INSTALLERS = setOf(
        "com.qooapp.qoohelper",
        "com.apkpure.aegon",
    )

    private val KNOWN_INSTALLERS = mapOf(
        "com.android.vending" to "Google Play",
        "com.qooapp.qoohelper" to "QooApp",
        "com.apkpure.aegon" to "APKPure",
        "com.aurora.store" to "Aurora Store",
        "org.fdroid.fdroid" to "F-Droid",
        "com.looker.droidify" to "Droid-ify",
        "com.aefyr.sai" to "SAI",
        "com.aefyr.sai.fdroid" to "SAI",
        "com.android.shell" to "ADB",
        "com.android.packageinstaller" to "System installer",
        "com.google.android.packageinstaller" to "System installer",
        "com.huawei.appmarket" to "AppGallery",
        "com.xiaomi.market" to "Xiaomi GetApps",
        "com.xiaomi.mipicks" to "Xiaomi GetApps",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.taptap" to "TapTap",
        "com.taptap.global" to "TapTap",
        "com.tencent.android.qqdownloader" to "Tencent MyApp",
        "com.heytap.market" to "OPPO App Market",
        "com.oppo.market" to "OPPO App Market",
        "com.bbk.appstore" to "vivo App Store",
        "com.uptodown" to "Uptodown",
        "com.amazon.venezia" to "Amazon Appstore",
    )

    fun installerInfo(context: Context, installer: String?): InstallerInfo = when {
        installer == null -> InstallerInfo(null, "—", risky = false)
        installer == context.packageName -> InstallerInfo(installer, "Split Sideloader", risky = false)
        else -> InstallerInfo(
            installer,
            KNOWN_INSTALLERS[installer] ?: installer,
            risky = installer in RISKY_INSTALLERS,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(
        context: Context,
        includeSystem: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<VerifyReport> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .filter { p ->
                val ai = p.applicationInfo ?: return@filter false
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                includeSystem || !system
            }
            .filter { it.packageName != context.packageName }

        val total = packages.size
        val done = AtomicInteger(0)
        onProgress(0, total)

        // Mostly I/O on central directories; a little parallelism halves a large scan.
        val io = Dispatchers.IO.limitedParallelism(4)
        coroutineScope {
            packages.map { p ->
                async(io) {
                    val report = InstallVerifier.verify(context, p.packageName)
                    onProgress(done.incrementAndGet(), total)
                    report
                }
            }.awaitAll()
        }.sortedWith(
            compareByDescending<VerifyReport> { it.verdict.ordinal }
                .thenBy { (it.label ?: it.packageName).lowercase() }
        )
    }
}
