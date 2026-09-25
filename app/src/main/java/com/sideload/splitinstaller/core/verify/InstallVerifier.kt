package com.sideload.splitinstaller.core.verify

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import com.sideload.splitinstaller.core.axml.ApkManifest
import com.sideload.splitinstaller.core.axml.AxmlParser
import com.sideload.splitinstaller.core.axml.NativeEngine
import com.sideload.splitinstaller.core.bundle.Finding
import com.sideload.splitinstaller.core.bundle.FindingCode
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.install.Shell
import com.sideload.splitinstaller.core.zip.ZipReader
import java.io.File

/** Ordered by severity, so a later finding can only make the verdict worse. */
enum class Verdict { OK, UNKNOWN, WARN, BROKEN, NOT_INSTALLED }

/** One APK file of an installed package, as it sits under /data/app. */
data class InstalledApk(
    val path: String,
    val splitName: String?,
    val size: Long,
    val abis: Set<String>,
    val soCount: Int,
    val readable: Boolean,
)

data class VerifyReport(
    val packageName: String,
    val verdict: Verdict,
    val label: String? = null,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val primaryCpuAbi: String? = null,
    val abiSource: String? = null,
    val nativeLibraryDir: String? = null,
    val nativeLibs: List<String> = emptyList(),
    /** ABIs that some installed APK really carries under `lib/<abi>/`. */
    val apkAbis: Set<String> = emptySet(),
    /** The subset of [apkAbis] this device can execute. */
    val usableAbis: Set<String> = emptySet(),
    val apks: List<InstalledApk> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val extractNativeLibs: Boolean = true,
    val engine: NativeEngine? = null,
    val nativeExpected: Boolean = false,
    val declaresSplitsRequired: Boolean = false,
    val installerPackage: String? = null,
    val firstInstallTime: Long = 0,
    val lastUpdateTime: Long = 0,
    val isSystem: Boolean = false,
    val findings: List<Finding> = emptyList(),
) {
    val sourceCount: Int get() = apks.size
    val totalSize: Long get() = apks.sumOf { it.size }
    val nativeLibCount: Int get() = maxOf(nativeLibs.size, apks.sumOf { it.soCount })
    val needsAttention: Boolean get() = verdict == Verdict.BROKEN || verdict == Verdict.WARN
}

/**
 * Answers the question the whole exercise turns on: did the native libraries actually
 * register?
 *
 * An install can report the right `versionName` and still be dead, because the ABI split
 * never made it in and no APK on disk has a `lib/<abi>/` for the loader to search.
 *
 * `nativeLibraryDir` alone cannot tell: since Android 6, apps built with
 * `extractNativeLibs=false` load their `.so` files straight out of the APK, so an empty
 * directory is normal for them. What is decisive is whether any installed APK carries
 * native code this device can run — so the installed APKs themselves are opened and read.
 */
object InstallVerifier {

    fun verify(
        context: Context,
        packageName: String,
        expectNativeLibs: Boolean? = null,
        expectedSplits: Collection<String> = emptyList(),
        shell: Shell? = null,
    ): VerifyReport {
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
            ?: return VerifyReport(
                packageName, Verdict.NOT_INSTALLED,
                findings = listOf(
                    Finding(Severity.ERROR, "package manager does not know $packageName", FindingCode.NOT_INSTALLED, listOf(packageName)),
                ),
            )
        val ai: ApplicationInfo = pkgInfo.applicationInfo
            ?: return VerifyReport(packageName, Verdict.UNKNOWN)

        val deviceAbis = Build.SUPPORTED_ABIS.toList()
        val splitNames = pkgInfo.splitNames?.toList().orEmpty()

        // ---- read the installed APKs themselves ------------------------------
        var manifest: ApkManifest? = null
        val paths = listOf<String?>(ai.sourceDir) + ai.splitSourceDirs.orEmpty().toList()
        val apks = paths.filterNotNull().mapIndexed { i, path ->
            val file = File(path)
            val splitName = if (i == 0) null else splitNames.getOrNull(i - 1)
            val probe = runCatching {
                ZipReader.open(file).use { zip ->
                    if (i == 0) {
                        manifest = zip["AndroidManifest.xml"]?.let { e ->
                            runCatching { AxmlParser.parse(zip.readAll(e, 8L shl 20)) }.getOrNull()
                        }
                    }
                    libLayout(zip)
                }
            }.getOrNull()
            InstalledApk(
                path = path,
                splitName = splitName,
                size = file.length(),
                abis = probe?.first.orEmpty(),
                soCount = probe?.second ?: 0,
                readable = probe != null,
            )
        }
        val anyReadable = apks.any { it.readable }
        val apkAbis = apks.flatMapTo(LinkedHashSet()) { it.abis }
        val usable = deviceAbis.filterTo(LinkedHashSet()) { it in apkAbis }

        val libDir = ai.nativeLibraryDir
        val listed = runCatching { File(libDir).list() }.getOrNull()
        val libs = listed?.sorted().orEmpty()
        val extract = (ai.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0

        val (abi, abiSource) = readPrimaryAbi(packageName, ai, shell, usable, libs)

        val engine = manifest?.engine
        val hasAbiSplit = splitNames.any { name ->
            name.substringAfterLast('.') in setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64")
        }
        val nativeExpected = expectNativeLibs
            ?: (engine != null || manifest?.requiresAbiSplit == true || apkAbis.isNotEmpty() || hasAbiSplit)

        // ---- verdict ----------------------------------------------------------
        val findings = ArrayList<Finding>()
        var verdict = Verdict.OK
        fun worsen(to: Verdict) {
            if (to.ordinal > verdict.ordinal) verdict = to
        }

        if (nativeExpected) {
            val readDumpsysNull = abiSource == "dumpsys" && abi == null
            when {
                anyReadable && usable.isEmpty() && apkAbis.isNotEmpty() -> {
                    worsen(Verdict.BROKEN)
                    findings += Finding(
                        Severity.ERROR,
                        "installed native code is for ${apkAbis.joinToString()}, but this device runs " +
                            deviceAbis.joinToString() + " — the app cannot load its libraries",
                        FindingCode.WRONG_ABI,
                        listOf(apkAbis.joinToString(), deviceAbis.joinToString()),
                    )
                }
                anyReadable && usable.isEmpty() -> {
                    worsen(Verdict.BROKEN)
                    findings += Finding(
                        Severity.ERROR,
                        (engine?.let { "a ${it.label} app, but " } ?: "") +
                            "no installed APK contains lib/<abi>/ and primaryCpuAbi is null. " +
                            "The ABI split was never installed: this fails at launch with " +
                            "\"dlopen failed … not found\", whatever the version says.",
                        if (engine != null) FindingCode.NO_NATIVE_ENGINE else FindingCode.NO_NATIVE,
                        listOfNotNull(engine?.label),
                    )
                }
                readDumpsysNull -> {
                    worsen(Verdict.BROKEN)
                    findings += Finding(
                        Severity.ERROR,
                        "dumpsys reports primaryCpuAbi=null — no native library path was registered",
                        FindingCode.DUMPSYS_NULL,
                    )
                }
                !anyReadable && abi == null && libs.isEmpty() -> {
                    worsen(Verdict.UNKNOWN)
                    findings += Finding(
                        Severity.WARN,
                        "could not read the installed APKs or primaryCpuAbi; state unknown",
                        FindingCode.UNREADABLE,
                    )
                }
                else -> {
                    val abis = usable.ifEmpty { setOfNotNull(abi) }.joinToString()
                    findings += Finding(
                        Severity.INFO,
                        "native code for $abis is installed" + (abi?.let { " · primaryCpuAbi=$it" } ?: ""),
                        FindingCode.NATIVE_OK,
                        listOf(abis),
                    )
                }
            }

            if (extract && usable.isNotEmpty() && listed != null && libs.isEmpty()) {
                worsen(Verdict.WARN)
                findings += Finding(
                    Severity.WARN,
                    "extractNativeLibs is on but $libDir is empty — the libraries were not " +
                        "extracted. Reinstalling usually fixes this.",
                    FindingCode.NOT_EXTRACTED,
                )
            }
            if (abi != null && abi !in deviceAbis) {
                worsen(Verdict.BROKEN)
                findings += Finding(
                    Severity.ERROR,
                    "installed for $abi, which this device cannot run",
                    FindingCode.ABI_UNSUPPORTED,
                    listOf(abi),
                )
            }
        } else {
            findings += Finding(
                Severity.INFO,
                "no native code expected; nothing ABI-related to verify",
                FindingCode.NO_NATIVE_EXPECTED,
            )
        }

        if (manifest?.declaresSplitsRequired == true && splitNames.isEmpty()) {
            worsen(Verdict.BROKEN)
            findings += Finding(
                Severity.ERROR,
                "the manifest declares required splits but only base.apk is installed — every " +
                    "split was dropped (the store-client failure mode)",
                FindingCode.BASE_ONLY,
            )
        }

        val missing = expectedSplits.filter { it.isNotBlank() && it !in splitNames }
        if (missing.isNotEmpty()) {
            worsen(Verdict.BROKEN)
            findings += Finding(
                Severity.ERROR,
                "splits missing after install: " + missing.joinToString(),
                FindingCode.SPLITS_MISSING,
                listOf(missing.joinToString()),
            )
        }

        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

        return VerifyReport(
            packageName = packageName,
            verdict = verdict,
            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull(),
            versionName = pkgInfo.versionName,
            versionCode = versionCodeOf(pkgInfo),
            primaryCpuAbi = abi,
            abiSource = abiSource,
            nativeLibraryDir = libDir,
            nativeLibs = libs,
            apkAbis = apkAbis,
            usableAbis = usable,
            apks = apks,
            splitNames = splitNames,
            extractNativeLibs = extract,
            engine = engine,
            nativeExpected = nativeExpected,
            declaresSplitsRequired = manifest?.declaresSplitsRequired == true,
            installerPackage = installerOfRecord(context, packageName),
            firstInstallTime = pkgInfo.firstInstallTime,
            lastUpdateTime = pkgInfo.lastUpdateTime,
            isSystem = isSystem,
            findings = findings,
        )
    }

    /** ABIs under `lib/` and the number of `.so` files, straight from the central directory. */
    private fun libLayout(zip: ZipReader): Pair<Set<String>, Int> {
        val abis = LinkedHashSet<String>()
        var count = 0
        for (e in zip.entries) {
            val n = e.name
            if (!n.startsWith("lib/")) continue
            val slash = n.indexOf('/', 4)
            if (slash <= 4) continue
            abis += n.substring(4, slash)
            if (n.endsWith(".so")) count++
        }
        return abis to count
    }

    /**
     * `primaryCpuAbi` is not public API. A shell reads it exactly as `adb shell dumpsys
     * package` does. Reflection works only where the ROM still allows it. Failing both, it
     * is inferred the way the package manager derives it: the first device ABI that some
     * installed APK carries.
     */
    private fun readPrimaryAbi(
        packageName: String,
        ai: ApplicationInfo,
        shell: Shell?,
        usable: Set<String>,
        libs: List<String>,
    ): Pair<String?, String?> {
        if (shell != null) {
            val r = runCatching {
                shell.run("dumpsys package $packageName | grep -i primaryCpuAbi", timeoutSeconds = 20)
            }.getOrNull()
            val value = r?.out?.lineSequence()
                ?.mapNotNull { line -> line.substringAfter("primaryCpuAbi=", "").trim().takeIf { it.isNotEmpty() } }
                ?.firstOrNull()
                ?.substringBefore(' ')
            if (value != null) return value.takeIf { it != "null" } to "dumpsys"
        }

        runCatching {
            val f = ApplicationInfo::class.java.getDeclaredField("primaryCpuAbi")
            f.isAccessible = true
            (f.get(ai) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it to "ApplicationInfo" }

        usable.firstOrNull()?.let { return it to "APK lib/ layout" }

        val fromDir = when (File(ai.nativeLibraryDir ?: "").name) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
        return if (fromDir != null && libs.isNotEmpty()) fromDir to "nativeLibraryDir" else null to null
    }

    fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    fun installedVersion(context: Context, packageName: String): Pair<Long, String?>? = runCatching {
        val p = context.packageManager.getPackageInfo(packageName, 0)
        versionCodeOf(p) to p.versionName
    }.getOrNull()

    fun installedSplits(context: Context, packageName: String): List<String> = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).splitNames?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun installerOfRecord(context: Context, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()
}
