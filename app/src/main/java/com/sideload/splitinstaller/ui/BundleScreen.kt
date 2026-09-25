package com.sideload.splitinstaller.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.bundle.SplitApk
import com.sideload.splitinstaller.core.bundle.SplitKind
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.InstallOutcome
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.verify.Verdict

class BundleActions(
    val onBack: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onReset: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onInstall: (uninstallFirst: Boolean) -> Unit = {},
    val onRepair: () -> Unit = {},
    val onReverify: () -> Unit = {},
    val onLaunch: (String) -> Unit = {},
    val onBackupToggle: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleScreen(state: UiState, backend: BackendKind?, actions: BundleActions) {
    val info = state.bundle ?: return
    val label = info.appLabel ?: info.packageName ?: info.displayName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.installedState != null) {
                        IconButton(onClick = actions.onReverify) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.action_reverify))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = { InstallBar(state, info, backend, actions) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { BundleHeader(info) }

            (state.outcome as? InstallOutcome.Failed)?.let { failed ->
                item(key = "failed") { FailureCard(failed, actions) }
            }
            state.verifyReport?.let { report ->
                item(key = "verdict") { VerdictHero(report) }
            }

            item(key = "version") { VersionCard(state) }
            item(key = "signature") { SignatureCard(state, info, actions) }

            if (state.repairSplits.isNotEmpty() && !state.installing) {
                item(key = "repair") { RepairCard(state.repairSplits, actions.onRepair) }
            }

            if (info.findings.isNotEmpty()) {
                item(key = "checks") { FindingsCard(info.findings) }
            }

            item(key = "splits") { SplitsCard(info, state, actions) }

            if (state.selectionProblems.isNotEmpty()) {
                item(key = "problems") {
                    AppCard(color = Tone.DANGER.container(), contentColor = Tone.DANGER.onContainer()) {
                        CardTitle(stringResource(R.string.section_selection_problems), Icons.Rounded.Error)
                        state.selectionProblems.forEach { Text("• " + it.localized(), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            if (info.obbs.isNotEmpty()) {
                item(key = "obb") {
                    AppCard {
                        CardTitle(stringResource(R.string.section_obb, info.obbs.size), Icons.Rounded.SdStorage)
                        info.obbs.forEach { obb -> KeyValue(humanSize(obb.size), obb.targetPath, mono = true) }
                    }
                }
            }

            if (state.installedState != null) {
                item(key = "options") {
                    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.opt_backup),
                            description = stringResource(R.string.opt_backup_desc),
                            checked = state.backupFirst,
                            onChange = actions.onBackupToggle,
                            icon = Icons.Rounded.Backup,
                        )
                    }
                }
            }

            state.verifyReport?.let { report ->
                item(key = "native") { NativeLibsCard(report) }
                item(key = "apks") { InstalledApksCard(report) }
            }
        }
    }
}

// ---- header -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleHeader(info: BundleInfo) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(info.packageName, info.appLabel ?: info.displayName, 68.dp, bytes = info.icon)
            Spacer(Modifier.width(16.dp))
            TitleWithMono(
                title = info.appLabel ?: info.packageName ?: info.displayName,
                mono = info.packageName,
                titleStyle = MaterialTheme.typography.titleLarge,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(info.format.label, Tone.INFO)
            MetaPill(humanSize(info.totalSize))
            MetaPill(stringResource(R.string.pill_apks, info.apks.size))
            if (info.minSdk > 0) MetaPill("API ${info.minSdk}+")
            info.engine?.let { MetaPill(it.label, tone = Tone.PRIMARY) }
            if (info.availableAbis.isNotEmpty()) MetaPill(info.availableAbis.joinToString(" · "), mono = true)
        }
    }
}

// ---- version ------------------------------------------------------------------------------

@Composable
private fun VersionCard(state: UiState) {
    val info = state.bundle ?: return
    val installed = state.installedState
    AppCard {
        CardTitle(stringResource(R.string.version_title), Icons.Rounded.Update)
        if (installed == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionBlock(stringResource(R.string.version_bundle), info.versionName, info.versionCode, Modifier.weight(1f))
                StatusPill(stringResource(R.string.version_new), Tone.INFO)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionBlock(stringResource(R.string.version_installed), installed.versionName, installed.versionCode, Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward, null,
                    Modifier.padding(horizontal = 8.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VersionBlock(stringResource(R.string.version_bundle), info.versionName, info.versionCode, Modifier.weight(1f))
            }
            val delta = info.versionCode - installed.versionCode
            val (text, tone) = when {
                info.versionCode <= 0 -> R.string.version_unknown to Tone.NEUTRAL
                delta > 0 -> R.string.version_upgrade to Tone.SUCCESS
                delta < 0 -> R.string.version_downgrade to Tone.WARNING
                else -> R.string.version_same to Tone.NEUTRAL
            }
            StatusPill(stringResource(text), tone)
            if (delta < 0) FindingRow(Severity.WARN, stringResource(R.string.downgrade_warning))
        }
    }
}

@Composable
private fun VersionBlock(label: String, name: String?, code: Long, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name ?: "?", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            code.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- signature ----------------------------------------------------------------------------

@Composable
private fun SignatureCard(state: UiState, info: BundleInfo, actions: BundleActions) {
    val signers = info.signers
    AppCard {
        CardTitle(stringResource(R.string.sig_title), Icons.Rounded.VerifiedUser)
        when (state.signatureMatch) {
            SignatureMatch.MATCH -> StatusLine(Icons.Rounded.GppGood, Tone.SUCCESS, stringResource(R.string.sig_match))
            SignatureMatch.MISMATCH -> {
                StatusLine(Icons.Rounded.GppBad, Tone.DANGER, stringResource(R.string.sig_mismatch))
                Text(
                    stringResource(R.string.sig_mismatch_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { actions.onInstall(true) }, enabled = !state.installing) {
                    Text(stringResource(R.string.action_uninstall_retry))
                }
            }
            else -> Unit
        }
        when {
            signers.size > 1 -> StatusLine(
                Icons.Rounded.GppBad, Tone.DANGER,
                stringResource(R.string.sig_inconsistent, signers.size),
            )
            signers.size == 1 && info.apks.size > 1 && info.unsignedOrUnreadable == 0 -> StatusLine(
                Icons.Rounded.GppGood, Tone.SUCCESS,
                stringResource(R.string.sig_consistent, info.apks.size),
            )
            signers.isEmpty() -> StatusLine(Icons.Rounded.GppMaybe, Tone.WARNING, stringResource(R.string.sig_unreadable))
        }
        signers.firstOrNull()?.let { cert ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            val sig = info.apks.firstOrNull { it.signature?.signer?.sha256 == cert.sha256 }?.signature
            CertDetails(
                cert,
                schemes = sig?.schemes?.map { it.label }.orEmpty(),
                v1File = sig?.v1SignerFile,
            )
        }
        if (state.signatureMatch == SignatureMatch.MISMATCH) {
            state.installedSigner?.let { installed ->
                KeyValue(stringResource(R.string.label_installed_signer), installed.commonName)
                Fingerprint(installed, color = Tone.DANGER.accent())
            }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, tone: Tone, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = tone.accent())
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// ---- repair -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepairCard(splits: List<SplitApk>, onRepair: () -> Unit) {
    AppCard(color = Tone.INFO.container(), contentColor = Tone.INFO.onContainer()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Tone.INFO.onContainer().copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Healing, null, Modifier.size(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.repair_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.repair_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            splits.forEach { MetaPill(it.splitName.orEmpty() + " · " + humanSize(it.size), mono = true) }
        }
        Button(
            onClick = onRepair,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
        ) {
            Icon(Icons.Rounded.Build, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_repair))
        }
    }
}

// ---- splits -------------------------------------------------------------------------------

@Composable
private fun SplitsCard(info: BundleInfo, state: UiState, actions: BundleActions) {
    AppCard(spacing = 4.dp) {
        CardTitle(stringResource(R.string.section_splits, info.apks.size), Icons.Rounded.Layers)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = actions.onReset, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_auto_select))
            }
            TextButton(onClick = actions.onSelectAll, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(stringResource(R.string.action_select_all))
            }
        }
        SplitKind.entries.forEach { kind ->
            val group = info.apks.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            Text(
                kindLabel(kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
            )
            group.forEach { apk ->
                SplitRow(
                    apk = apk,
                    checked = apk.id in state.selection,
                    locked = apk.kind == SplitKind.BASE,
                    note = state.notes[apk.id],
                    enabled = !state.installing,
                    onToggle = { actions.onToggle(apk.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitRow(
    apk: SplitApk,
    checked: Boolean,
    locked: Boolean,
    note: com.sideload.splitinstaller.core.bundle.SplitNote?,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        enabled = enabled && !locked,
        shape = RoundedCornerShape(14.dp),
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled && !locked)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    apk.splitName?.takeIf { it.isNotBlank() } ?: apk.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    MetaPill(humanSize(apk.size))
                    if (apk.nativeLibCount > 0) MetaPill("${apk.nativeLibCount} .so", tone = Tone.PRIMARY)
                    apk.abis.forEach { MetaPill(it, mono = true) }
                    if (!apk.inspected) MetaPill(stringResource(R.string.name_guessed), tone = Tone.WARNING)
                }
                note?.let {
                    Text(
                        it.localized(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: SplitKind): String = stringResource(
    when (kind) {
        SplitKind.BASE -> R.string.kind_base
        SplitKind.ABI -> R.string.kind_abi
        SplitKind.DENSITY -> R.string.kind_density
        SplitKind.LOCALE -> R.string.kind_locale
        SplitKind.FEATURE -> R.string.kind_feature
        SplitKind.STANDALONE -> R.string.kind_standalone
        SplitKind.UNKNOWN -> R.string.kind_unknown
    }
)

// ---- outcome ------------------------------------------------------------------------------

@Composable
private fun FailureCard(failed: InstallOutcome.Failed, actions: BundleActions) {
    AppCard(color = Tone.DANGER.container(), contentColor = Tone.DANGER.onContainer(), shape = RoundedCornerShape(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Error, null, Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.result_failed), style = MaterialTheme.typography.titleLarge)
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)) {
            Text(
                failed.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        failureHint(failed)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val destructive = failed.code?.let {
            "MISSING_SPLIT" in it || "UPDATE_INCOMPATIBLE" in it || "VERSION_DOWNGRADE" in it
        } == true
        if (destructive) {
            Button(
                onClick = { actions.onInstall(true) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.action_uninstall_retry)) }
            Text(stringResource(R.string.uninstall_retry_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- the bar at the bottom ------------------------------------------------------------------

@Composable
private fun InstallBar(state: UiState, info: BundleInfo, backend: BackendKind?, actions: BundleActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            val phase = when {
                state.installing -> 1
                state.outcome is InstallOutcome.Ok -> 2
                state.outcome is InstallOutcome.Failed -> 3
                else -> 0
            }
            AnimatedContent(targetState = phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bar") { p ->
                when (p) {
                    1 -> ProgressBarContent(state)
                    2 -> DoneBarContent(state, actions)
                    3 -> FailedBarContent(state, actions)
                    else -> IdleBarContent(state, info, backend, actions)
                }
            }
        }
    }
}

@Composable
private fun IdleBarContent(state: UiState, info: BundleInfo, backend: BackendKind?, actions: BundleActions) {
    val selected = info.apks.filter { it.id in state.selection }
    val blocked = state.selectionProblems.any { it.severity == Severity.ERROR }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.bar_summary, selected.size, humanSize(selected.sumOf { it.size })),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    when (backend) {
                        BackendKind.SHIZUKU -> R.string.bar_via_shizuku
                        BackendKind.ROOT -> R.string.bar_via_root
                        BackendKind.PACKAGE_INSTALLER -> R.string.bar_via_normal
                        null -> R.string.bar_no_backend
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (backend == null) Tone.DANGER.accent() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { actions.onInstall(false) },
            enabled = !blocked && selected.isNotEmpty() && backend != null,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_install))
        }
    }
}

@Composable
private fun ProgressBarContent(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.progressLabel) {
                    "" -> stringResource(R.string.bar_preparing)
                    "committing" -> stringResource(R.string.bar_committing)
                    "backup" -> stringResource(R.string.bar_backup)
                    else -> state.progressLabel
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                (state.progress * 100).toInt().toString() + "%",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            stringResource(if (state.repairing) R.string.bar_repairing_hint else R.string.bar_installing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneBarContent(state: UiState, actions: BundleActions) {
    val ok = state.outcome as? InstallOutcome.Ok ?: return
    val verdict = ok.report?.verdict
    val tone = verdict?.tone() ?: Tone.SUCCESS
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(verdict?.icon() ?: Icons.Rounded.CheckCircle, tone, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when {
                        verdict == Verdict.BROKEN -> R.string.bar_done_broken
                        state.repairing -> R.string.bar_repaired
                        else -> R.string.bar_done
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                ok.backupPath?.let { stringResource(R.string.bar_backup_saved) }
                    ?: stringResource(R.string.bar_done_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ok.packageName?.let { pkg ->
            Spacer(Modifier.width(8.dp))
            Button(onClick = { actions.onLaunch(pkg) }, enabled = verdict != Verdict.BROKEN) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_launch))
            }
        }
    }
}

@Composable
private fun FailedBarContent(state: UiState, actions: BundleActions) {
    val failed = state.outcome as? InstallOutcome.Failed ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.Error, Tone.DANGER, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.result_failed), style = MaterialTheme.typography.titleMedium)
            Text(
                failed.code ?: failed.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { actions.onInstall(false) }) { Text(stringResource(R.string.action_retry)) }
    }
}
