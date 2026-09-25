package com.sideload.splitinstaller.core.bundle

import android.net.Uri
import com.sideload.splitinstaller.core.axml.NativeEngine
import com.sideload.splitinstaller.core.sign.ApkSignature
import com.sideload.splitinstaller.core.sign.CertInfo

enum class BundleFormat(val label: String) {
    XAPK("XAPK"),
    APKM("APKM"),
    APKS("APKS"),
    ZIP("ZIP"),
    APK("APK"),
}

enum class SplitKind {
    /** The base APK. Never optional. */
    BASE,

    /** `config.<abi>` — the split that carries `lib/<abi>/` shared objects. Dropping it is the classic failure. */
    ABI,

    /** `config.<density>` — drawables for one screen bucket. */
    DENSITY,

    /** `config.<lang>` — one language's strings. */
    LOCALE,

    /** A dynamic feature module or asset pack. */
    FEATURE,

    /** A fat APK from an `.apks` `standalones/` folder; used only when there are no splits. */
    STANDALONE,

    UNKNOWN,
}

data class SplitApk(
    val entryName: String,
    val fileName: String,
    val size: Long,
    val kind: SplitKind,
    val splitName: String? = null,
    val packageName: String? = null,
    val versionCode: Long = 0,
    val versionName: String? = null,
    val minSdk: Int = 0,
    /** ABIs for which this APK actually ships `lib/<abi>/` entries. */
    val abis: Set<String> = emptySet(),
    /** The ABI this split targets by name, which may differ from what it really contains. */
    val targetAbi: String? = null,
    val densityDpi: Int? = null,
    val locale: String? = null,
    val nativeLibCount: Int = 0,
    val inspected: Boolean = false,
    /** CRC32 recorded in the bundle's central directory, 0 when unknown. */
    val crc: Long = 0,
    /** Who signed this APK; null when it could not be read in place. */
    val signature: ApkSignature? = null,
) {
    val id: String get() = entryName
}

data class ObbFile(
    val entryName: String,
    val fileName: String,
    val size: Long,
    /** Path relative to the external storage root, e.g. `Android/obb/com.x/main.1.com.x.obb`. */
    val targetPath: String,
)

enum class Severity { INFO, WARN, ERROR }

/**
 * One conclusion about a bundle or an install.
 *
 * [message] is the English, technical form that goes to the log, where it has to make
 * sense in a bug report. [code] and [args] let the UI say the same thing in the reader's
 * language instead.
 */
data class Finding(
    val severity: Severity,
    val message: String,
    val code: String? = null,
    val args: List<String> = emptyList(),
)

/** Stable identifiers for every [Finding] the core produces. */
object FindingCode {
    const val NO_BASE = "b_no_base"
    const val ABI_MISMATCH = "b_abi_mismatch"
    const val ENGINE_NO_LIBS = "b_engine_no_libs"
    const val NO_LIBS = "b_no_libs"
    const val SIGNERS_DIFFER = "b_signers_differ"
    const val MIN_SDK = "b_min_sdk"
    const val PKG_DISAGREE = "b_pkg_disagree"
    const val VERSION_DISAGREE = "b_version_disagree"
    const val GUESSED = "b_guessed"

    const val SEL_NO_BASE = "s_no_base"
    const val SEL_ABI_DESELECTED = "s_abi_deselected"
    const val SEL_ABI_UNSUPPORTED = "s_abi_unsupported"

    const val NOT_INSTALLED = "v_not_installed"
    const val WRONG_ABI = "v_wrong_abi"
    const val NO_NATIVE = "v_no_native"
    const val NO_NATIVE_ENGINE = "v_no_native_engine"
    const val DUMPSYS_NULL = "v_dumpsys_null"
    const val UNREADABLE = "v_unreadable"
    const val NATIVE_OK = "v_native_ok"
    const val NOT_EXTRACTED = "v_not_extracted"
    const val ABI_UNSUPPORTED = "v_abi_unsupported"
    const val NO_NATIVE_EXPECTED = "v_no_native_expected"
    const val BASE_ONLY = "v_base_only"
    const val SPLITS_MISSING = "v_splits_missing"
}

data class BundleInfo(
    val uri: Uri,
    val displayName: String,
    val totalSize: Long,
    val format: BundleFormat,
    val packageName: String?,
    val appLabel: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val apks: List<SplitApk>,
    val obbs: List<ObbFile>,
    val findings: List<Finding>,
    val icon: IconBytes? = null,
    /** Engine detected from the base manifest; its apps cannot start without native code. */
    val engine: NativeEngine? = null,
) {
    val base: SplitApk? get() = apks.firstOrNull { it.kind == SplitKind.BASE }
        ?: apks.firstOrNull { it.kind == SplitKind.STANDALONE }

    val hasArm64Libs: Boolean get() = apks.any { "arm64-v8a" in it.abis }

    val availableAbis: Set<String> get() = apks.flatMapTo(HashSet()) { it.abis }

    val hasError: Boolean get() = findings.any { it.severity == Severity.ERROR }

    /** Distinct current signers across every APK whose signature could be read. */
    val signers: List<CertInfo>
        get() = apks.mapNotNull { it.signature?.signer }.distinctBy { it.sha256 }

    /** Every certificate the bundle can be matched against, rotation lineage included. */
    val signerSha256: Set<String>
        get() = apks.flatMapTo(HashSet()) { it.signature?.allSha256.orEmpty() }

    val unsignedOrUnreadable: Int get() = apks.count { it.signature?.signer == null }
}
