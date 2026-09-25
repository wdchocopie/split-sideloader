package com.sideload.splitinstaller.core.install

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import java.io.File

enum class BackendKind {
    /** Runs `pm` as the shell user through Shizuku. Silent, survives until reboot. */
    SHIZUKU,

    /** Runs `pm` as root. Silent, but root itself blocks a lot of the apps worth sideloading. */
    ROOT,

    /** The ordinary in-app installer. Always works; always shows a confirmation per install. */
    PACKAGE_INSTALLER,
}

enum class BackendState { READY, NEEDS_ACTION, UNAVAILABLE }

data class Capability(
    val kind: BackendKind,
    val state: BackendState,
    val silent: Boolean,
    val detail: String,
    /** A hint the UI turns into a button: "grant", "start" or "test". */
    val action: String? = null,
)

data class DeviceReport(
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val primaryAbi: String,
    val densityDpi: Int,
    val is64Bit: Boolean,
    val suBinary: String?,
    val capabilities: List<Capability>,
    val canRequestInstalls: Boolean,
    val hasAllFilesAccess: Boolean,
    val canPostNotifications: Boolean,
    /** Aggressive ROMs stop background work without this. */
    val batteryUnrestricted: Boolean,
    val freeDataBytes: Long,
    val totalDataBytes: Long,
    val freeExternalBytes: Long,
) {
    val best: Capability? get() = capabilities.firstOrNull { it.state == BackendState.READY }
    val silentAvailable: Boolean get() = capabilities.any { it.state == BackendState.READY && it.silent }
    val silentBackend: BackendKind? get() = capabilities.firstOrNull { it.state == BackendState.READY && it.silent }?.kind
    val missingPermissions: Int
        get() = listOf(canRequestInstalls, hasAllFilesAccess, canPostNotifications).count { !it }
}

/**
 * Works out what this device will let us do before anything is installed.
 *
 * Deliberately passive: it looks for an `su` binary rather than invoking it, so opening the
 * app never throws a root prompt at you. Actually exercising root is a separate, explicit
 * step.
 */
object BackendResolver {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
        "/data/adb/magisk/su", "/system/bin/failsafe/su",
    )

    fun findSu(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Whether shared storage can be walked and written directly. Android 11+ gates this
     * behind All-files access; before that, the classic storage permission is what counts.
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun batteryUnrestricted(context: Context): Boolean = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun probe(context: Context, rootConfirmed: Boolean = false): DeviceReport {
        val caps = ArrayList<Capability>()

        // ---- Shizuku ----------------------------------------------------------
        caps += when {
            !ShizukuShell.isRunning() -> Capability(
                BackendKind.SHIZUKU, BackendState.UNAVAILABLE, silent = true,
                detail = "service not running",
                action = "start",
            )
            ShizukuShell.isGranted() -> Capability(
                BackendKind.SHIZUKU, BackendState.READY, silent = true,
                detail = ShizukuShell.versionName().ifBlank { "connected" } +
                    if (ShizukuShell.serviceUid() == 0) " · root" else " · adb",
            )
            else -> Capability(
                BackendKind.SHIZUKU, BackendState.NEEDS_ACTION, silent = true,
                detail = "running, permission not granted",
                action = "grant",
            )
        }

        // ---- root -------------------------------------------------------------
        val su = findSu()
        caps += when {
            su == null -> Capability(
                BackendKind.ROOT, BackendState.UNAVAILABLE, silent = true,
                detail = "no su binary",
            )
            rootConfirmed -> Capability(
                BackendKind.ROOT, BackendState.READY, silent = true, detail = su,
            )
            else -> Capability(
                BackendKind.ROOT, BackendState.NEEDS_ACTION, silent = true,
                detail = "su at $su, not tested",
                action = "test",
            )
        }

        // ---- the always-there fallback ----------------------------------------
        val canInstall = canRequestInstalls(context)
        caps += Capability(
            BackendKind.PACKAGE_INSTALLER,
            if (canInstall) BackendState.READY else BackendState.NEEDS_ACTION,
            silent = false,
            detail = if (canInstall) "one system prompt per install" else "install permission not granted",
            action = if (canInstall) null else "grant",
        )

        val data = Environment.getDataDirectory()
        return DeviceReport(
            model = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            densityDpi = context.resources.displayMetrics.densityDpi,
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
            suBinary = su,
            capabilities = caps,
            canRequestInstalls = canInstall,
            hasAllFilesAccess = hasAllFilesAccess(context),
            canPostNotifications = canPostNotifications(context),
            batteryUnrestricted = batteryUnrestricted(context),
            freeDataBytes = freeBytes(data),
            totalDataBytes = totalBytes(data),
            freeExternalBytes = freeBytes(Environment.getExternalStorageDirectory()),
        )
    }

    fun shellFor(kind: BackendKind): Shell? = when (kind) {
        BackendKind.SHIZUKU -> ShizukuShell
        BackendKind.ROOT -> RootShell
        BackendKind.PACKAGE_INSTALLER -> null
    }

    /** A shell for read-only diagnostics, if any backend offers one right now. */
    fun anyShell(rootConfirmed: Boolean): Shell? = when {
        ShizukuShell.isGranted() -> ShizukuShell
        rootConfirmed -> RootShell
        else -> null
    }

    private fun freeBytes(dir: File?): Long = runCatching {
        val fs = StatFs(dir?.absolutePath ?: return@runCatching 0L)
        fs.availableBlocksLong * fs.blockSizeLong
    }.getOrDefault(0L)

    private fun totalBytes(dir: File?): Long = runCatching {
        val fs = StatFs(dir?.absolutePath ?: return@runCatching 0L)
        fs.blockCountLong * fs.blockSizeLong
    }.getOrDefault(0L)
}
