package com.sideload.splitinstaller.core

import androidx.annotation.StringRes
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.bundle.FindingCode
import com.sideload.splitinstaller.core.install.FailureCode
import com.sideload.splitinstaller.core.ota.OtaProblem

// Code -> string resource. Plain functions, so the watch service can use them for
// notifications as well as the UI for screens.

@StringRes
fun findingRes(code: String?): Int? = when (code) {
    FindingCode.NO_BASE -> R.string.f_no_base
    FindingCode.ABI_MISMATCH -> R.string.f_abi_mismatch
    FindingCode.ENGINE_NO_LIBS -> R.string.f_engine_no_libs
    FindingCode.NO_LIBS -> R.string.f_no_libs
    FindingCode.SIGNERS_DIFFER -> R.string.f_signers_differ
    FindingCode.MIN_SDK -> R.string.f_min_sdk
    FindingCode.PKG_DISAGREE -> R.string.f_pkg_disagree
    FindingCode.VERSION_DISAGREE -> R.string.f_version_disagree
    FindingCode.GUESSED -> R.string.f_guessed
    FindingCode.SEL_NO_BASE -> R.string.f_sel_no_base
    FindingCode.SEL_ABI_DESELECTED -> R.string.f_sel_abi_deselected
    FindingCode.SEL_ABI_UNSUPPORTED -> R.string.f_sel_abi_unsupported
    FindingCode.NOT_INSTALLED -> R.string.f_not_installed
    FindingCode.WRONG_ABI -> R.string.f_wrong_abi
    FindingCode.NO_NATIVE -> R.string.f_no_native
    FindingCode.NO_NATIVE_ENGINE -> R.string.f_no_native_engine
    FindingCode.DUMPSYS_NULL -> R.string.f_dumpsys_null
    FindingCode.UNREADABLE -> R.string.f_unreadable
    FindingCode.NATIVE_OK -> R.string.f_native_ok
    FindingCode.NOT_EXTRACTED -> R.string.f_not_extracted
    FindingCode.ABI_UNSUPPORTED -> R.string.f_abi_unsupported
    FindingCode.NO_NATIVE_EXPECTED -> R.string.f_no_native_expected
    FindingCode.BASE_ONLY -> R.string.f_base_only
    FindingCode.SPLITS_MISSING -> R.string.f_splits_missing
    else -> null
}

/** What to do about a failure, keyed on the installer's own error code where there is one. */
@StringRes
fun failureHintRes(code: String?): Int? {
    val c = code.orEmpty()
    return when {
        "MISSING_SPLIT" in c -> R.string.hint_missing_split
        "UPDATE_INCOMPATIBLE" in c || "INCONSISTENT_CERTIFICATES" in c -> R.string.hint_update_incompatible
        "VERSION_DOWNGRADE" in c -> R.string.hint_downgrade
        "INSUFFICIENT_STORAGE" in c -> R.string.hint_storage
        "NO_MATCHING_ABIS" in c -> R.string.hint_no_abis
        "PARSE_FAILED" in c -> R.string.hint_parse
        "INVALID_APK" in c -> R.string.hint_invalid
        c == FailureCode.CORRUPT -> R.string.hint_corrupt
        c == FailureCode.BACKUP_FAILED -> R.string.hint_backup
        c == FailureCode.REPAIR_VERSION -> R.string.hint_repair_version
        c == FailureCode.UNINSTALL_INCOMPLETE -> R.string.hint_uninstall
        c == FailureCode.SHELL_UNAVAILABLE -> R.string.hint_shell
        else -> null
    }
}

/** Why a downloaded copy of this app was refused. */
@StringRes
fun otaProblemRes(code: String): Int = when (code) {
    OtaProblem.WRONG_PACKAGE -> R.string.ota_p_wrong_package
    OtaProblem.NOT_NEWER -> R.string.ota_p_not_newer
    OtaProblem.SIGNER_MISMATCH -> R.string.ota_p_signer_mismatch
    OtaProblem.SIGNER_UNKNOWN -> R.string.ota_p_signer_unknown
    OtaProblem.DIGEST_MISMATCH -> R.string.ota_p_digest_mismatch
    else -> R.string.ota_p_unreadable
}
