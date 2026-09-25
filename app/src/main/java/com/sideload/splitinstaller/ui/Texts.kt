package com.sideload.splitinstaller.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.bundle.Finding
import com.sideload.splitinstaller.core.failureHintRes
import com.sideload.splitinstaller.core.findingRes
import com.sideload.splitinstaller.core.bundle.SplitNote
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.Capability
import com.sideload.splitinstaller.core.install.InstallOutcome

// The core speaks English, because its sentences end up in logs people paste into bug
// reports. Everything the UI shows goes through here to reach the reader's language.

@Composable
fun Finding.localized(): String {
    val res = findingRes(code) ?: return message
    return stringResource(res, *args.toTypedArray())
}

@Composable
fun SplitNote.localized(): String = when (code) {
    SplitNote.BASE -> stringResource(R.string.note_base)
    SplitNote.STANDALONE -> stringResource(R.string.note_standalone)
    SplitNote.ABI -> stringResource(R.string.note_abi, arg.orEmpty())
    SplitNote.DENSITY -> stringResource(R.string.note_density, arg.orEmpty())
    SplitNote.DENSITY_ANY -> stringResource(R.string.note_density_any)
    SplitNote.LANGUAGE -> stringResource(R.string.note_language, arg.orEmpty())
    SplitNote.LANGUAGE_FALLBACK -> stringResource(R.string.note_language_fallback)
    SplitNote.FEATURE -> stringResource(R.string.note_feature)
    else -> stringResource(R.string.note_unclassified)
}

@Composable
fun capabilityText(cap: Capability): String = when (cap.kind) {
    BackendKind.SHIZUKU -> when (cap.state) {
        BackendState.READY -> cap.detail
        BackendState.NEEDS_ACTION -> stringResource(R.string.cap_shizuku_grant)
        BackendState.UNAVAILABLE -> stringResource(R.string.cap_shizuku_off)
    }
    BackendKind.ROOT -> when (cap.state) {
        BackendState.READY -> stringResource(R.string.cap_root_ready)
        BackendState.NEEDS_ACTION -> stringResource(R.string.cap_root_untested)
        BackendState.UNAVAILABLE -> stringResource(R.string.cap_root_none)
    }
    BackendKind.PACKAGE_INSTALLER ->
        stringResource(if (cap.state == BackendState.READY) R.string.cap_pi_ready else R.string.cap_pi_grant)
}

@Composable
fun failureHint(failed: InstallOutcome.Failed): String? =
    failureHintRes(failed.code)?.let { stringResource(it) } ?: failed.hint
