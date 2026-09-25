package com.sideload.splitinstaller.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.apps.LaunchProblem
import com.sideload.splitinstaller.core.update.UpdateKind
import com.sideload.splitinstaller.core.update.UpdatePin
import com.sideload.splitinstaller.core.update.UpdateResult
import com.sideload.splitinstaller.core.update.UpdateState
import java.text.DateFormat
import java.util.Date

class AppDetailActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLaunch: (String) -> Unit = {},
    val onDiagnose: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onAppInfo: (String) -> Unit = {},
    val onUninstall: (String) -> Unit = {},
    val onRepairWithBundle: () -> Unit = {},
    val onFindUpdate: (String) -> Unit = {},
    val onCheckUpdate: (String) -> Unit = {},
    val onPinFDroid: (pkg: String, label: String?, onResult: (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    val onPinGitHub: (pkg: String, label: String?, input: String) -> Boolean = { _, _, _ -> false },
    val onPinWeb: (pkg: String, label: String?) -> Unit = { _, _ -> },
    val onUnpin: (String) -> Unit = {},
    val onDownloadUpdate: (UpdateResult) -> Unit = {},
    val onOpenPage: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(
    detail: AppDetail,
    shellAvailable: Boolean,
    actions: AppDetailActions,
    pin: UpdatePin? = null,
    update: UpdateResult? = null,
    checkingUpdate: Boolean = false,
) {
    val r = detail.row.report
    val label = r.label ?: r.packageName
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
                    IconButton(onClick = actions.onRefresh, enabled = !detail.refreshing) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.action_reverify))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(r.packageName, label, 68.dp)
                        Spacer(Modifier.width(16.dp))
                        TitleWithMono(label, r.packageName, MaterialTheme.typography.titleLarge)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetaPill((r.versionName ?: "?") + " (" + r.versionCode + ")")
                        MetaPill(
                            stringResource(R.string.pill_from, detail.row.installer.label),
                            tone = if (detail.row.installer.risky) Tone.WARNING else null,
                        )
                        r.engine?.let { MetaPill(it.label, tone = Tone.PRIMARY) }
                        if (r.isSystem) MetaPill(stringResource(R.string.pill_system))
                    }
                }
            }

            if (detail.refreshing) {
                item(key = "refreshing") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                }
            }

            item(key = "verdict") { VerdictHero(r) }

            item(key = "update") {
                UpdateCard(label, r.packageName, pin, update, checkingUpdate, actions)
            }

            if (detail.row.installer.risky) {
                item(key = "risky") {
                    AppCard(color = Tone.WARNING.container(), contentColor = Tone.WARNING.onContainer()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Warning, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.risky_installer_body, detail.row.installer.label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item(key = "actions") {
                ActionGrid(
                    listOf(
                        GridAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_launch)) { actions.onLaunch(r.packageName) },
                        GridAction(
                            Icons.Rounded.BugReport,
                            stringResource(R.string.action_diagnose),
                            enabled = shellAvailable && !detail.diagnosing,
                            hint = if (shellAvailable) null else stringResource(R.string.needs_shell),
                        ) { actions.onDiagnose() },
                        GridAction(Icons.Rounded.Backup, stringResource(R.string.action_backup), enabled = !detail.exporting) { actions.onExport() },
                        GridAction(Icons.Rounded.Healing, stringResource(R.string.action_repair_with_bundle)) { actions.onRepairWithBundle() },
                        GridAction(Icons.Rounded.TravelExplore, stringResource(R.string.action_find_update)) { actions.onFindUpdate(label) },
                        GridAction(Icons.Rounded.Info, stringResource(R.string.action_app_info)) { actions.onAppInfo(r.packageName) },
                        GridAction(Icons.Rounded.DeleteOutline, stringResource(R.string.action_uninstall), danger = true) {
                            actions.onUninstall(r.packageName)
                        },
                    )
                )
            }

            if (detail.exporting || detail.exportedTo != null || detail.exportError != null) {
                item(key = "export") { ExportCard(detail) }
            }

            if (detail.diagnosing || detail.diagnosis != null) {
                item(key = "diagnosis") { DiagnosisCard(detail) }
            }

            item(key = "native") { NativeLibsCard(r) }
            item(key = "apks") { InstalledApksCard(r) }

            detail.signer?.let { signer ->
                item(key = "signer") {
                    AppCard {
                        CardTitle(stringResource(R.string.sig_title), Icons.Rounded.Security)
                        CertDetails(signer)
                    }
                }
            }

            item(key = "info") {
                AppCard {
                    CardTitle(stringResource(R.string.card_info), Icons.Rounded.Memory)
                    KeyValue(stringResource(R.string.label_installer), r.installerPackage ?: "—", mono = r.installerPackage != null)
                    if (r.firstInstallTime > 0) KeyValue(stringResource(R.string.label_first_install), formatDate(r.firstInstallTime))
                    if (r.lastUpdateTime > 0) KeyValue(stringResource(R.string.label_last_update), formatDate(r.lastUpdateTime))
                    KeyValue(stringResource(R.string.label_total_size), humanSize(r.totalSize))
                }
            }

            item(key = "findings") { FindingsCard(r.findings) }
        }
    }
}

private class GridAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val hint: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun ActionGrid(items: List<GridAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { a -> ActionCell(a, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionCell(action: GridAction, modifier: Modifier) {
    val content = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (action.danger) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (action.enabled) 1f else 0.5f),
        contentColor = content,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(action.icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(action.label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                action.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ExportCard(detail: AppDetail) {
    AppCard {
        CardTitle(stringResource(R.string.card_backup), Icons.Rounded.Backup)
        when {
            detail.exporting -> {
                Text(stringResource(R.string.backup_running), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { detail.exportProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            detail.exportedTo != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = Tone.SUCCESS.accent())
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.backup_done), style = MaterialTheme.typography.bodyMedium)
                }
                KeyValue(stringResource(R.string.label_saved_to), detail.exportedTo, mono = true)
            }
            detail.exportError != null -> FindingRow(com.sideload.splitinstaller.core.bundle.Severity.ERROR, detail.exportError)
        }
    }
}

@Composable
private fun DiagnosisCard(detail: AppDetail) {
    AppCard {
        CardTitle(stringResource(R.string.card_diagnosis), Icons.Rounded.BugReport)
        if (detail.diagnosing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.diagnosis_running), style = MaterialTheme.typography.bodyMedium)
            }
            return@AppCard
        }
        val d = detail.diagnosis ?: return@AppCard
        val (title, body, tone) = when (d.problem) {
            LaunchProblem.MISSING_NATIVE_LIB -> Triple(R.string.diag_missing_lib, R.string.diag_missing_lib_body, Tone.DANGER)
            LaunchProblem.ANTI_CHEAT -> Triple(R.string.diag_anticheat, R.string.diag_anticheat_body, Tone.WARNING)
            LaunchProblem.NATIVE_CRASH -> Triple(R.string.diag_native_crash, R.string.diag_native_crash_body, Tone.DANGER)
            LaunchProblem.JAVA_CRASH -> Triple(R.string.diag_java_crash, R.string.diag_java_crash_body, Tone.DANGER)
            LaunchProblem.NONE_FOUND -> Triple(R.string.diag_none, R.string.diag_none_body, Tone.SUCCESS)
            LaunchProblem.NO_LAUNCHER -> Triple(R.string.diag_no_launcher, R.string.diag_no_launcher_body, Tone.NEUTRAL)
            LaunchProblem.SHELL_FAILED -> Triple(R.string.diag_shell_failed, R.string.diag_shell_failed_body, Tone.WARNING)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = tone.container(), contentColor = tone.onContainer()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(if (tone == Tone.SUCCESS) Icons.Rounded.Shield else Icons.Rounded.Error, null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
                    d.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (d.lines.isNotEmpty()) {
            val error = MaterialTheme.colorScheme.error
            val warn = AppTheme.status.warning
            val normal = MaterialTheme.colorScheme.onSurfaceVariant
            ConsoleBlock(d.lines.takeLast(80)) { line ->
                when {
                    line.contains(" E/") || line.contains(" F/") || line.contains("FATAL") -> error
                    line.contains(" W/") -> warn
                    else -> normal
                }
            }
        }
    }
}

private fun formatDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

// ---- updates ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(
    label: String,
    packageName: String,
    pin: UpdatePin?,
    update: UpdateResult?,
    busy: Boolean,
    actions: AppDetailActions,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    AppCard {
        CardTitle(
            stringResource(R.string.card_update),
            Icons.Rounded.SystemUpdateAlt,
            trailing = {
                if (pin != null) {
                    StatusPill(
                        stringResource(
                            when (pin.kind) {
                                UpdateKind.FDROID -> R.string.pin_fdroid
                                UpdateKind.GITHUB -> R.string.pin_github
                                UpdateKind.WEB -> R.string.pin_web
                            }
                        ),
                        if (pin.kind == UpdateKind.WEB) Tone.NEUTRAL else Tone.INFO,
                    )
                }
            },
        )

        if (pin == null) {
            Text(
                stringResource(R.string.update_no_pin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_set_source))
            }
        } else {
            KeyValue(stringResource(R.string.label_update_source), pin.value, mono = true)

            when (update?.state) {
                UpdateState.UPDATE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(20.dp), tint = Tone.INFO.accent())
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(
                                R.string.update_available,
                                update.installedVersionName ?: "?",
                                update.availableVersionName ?: "?",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (update.canDownload) {
                        Button(onClick = { actions.onDownloadUpdate(update) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.update_download) +
                                    (update.size.takeIf { it > 0 }?.let { " · " + humanSize(it) } ?: "")
                            )
                        }
                    }
                }
                UpdateState.UP_TO_DATE -> StatusLineRow(
                    Icons.Rounded.CheckCircle, Tone.SUCCESS,
                    stringResource(R.string.update_current, update.installedVersionName ?: "?"),
                )
                UpdateState.MANUAL -> StatusLineRow(
                    Icons.Rounded.OpenInBrowser, Tone.NEUTRAL, stringResource(R.string.update_manual),
                )
                UpdateState.UNKNOWN -> StatusLineRow(
                    Icons.AutoMirrored.Rounded.HelpOutline, Tone.WARNING,
                    stringResource(R.string.update_unknown, update.availableVersionName ?: "?"),
                )
                UpdateState.ERROR -> FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.WARN,
                    stringResource(R.string.update_error, update.message ?: "?"),
                )
                null -> Text(
                    stringResource(R.string.update_not_checked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            update?.takeIf { it.checkedAt > 0 }?.let {
                Text(
                    stringResource(R.string.update_checked_at, relativeTime(it.checkedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Four labels do not fit one line in either language, so let them wrap.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.onCheckUpdate(packageName) }, enabled = !busy) {
                    Text(stringResource(R.string.update_check_now))
                }
                (update?.pageUrl ?: pin.value.takeIf { pin.kind == UpdateKind.WEB })?.let { url ->
                    TextButton(onClick = { actions.onOpenPage(url) }) { Text(stringResource(R.string.update_open_page)) }
                }
                TextButton(onClick = { showPicker = true }) { Text(stringResource(R.string.update_change_source)) }
                TextButton(onClick = { actions.onUnpin(packageName) }) { Text(stringResource(R.string.update_unpin)) }
            }
        }
    }

    if (showPicker) {
        PinSourceDialog(
            label = label,
            packageName = packageName,
            onDismiss = { showPicker = false },
            actions = actions,
        )
    }
}

@Composable
private fun StatusLineRow(icon: ImageVector, tone: Tone, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = tone.accent())
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PinSourceDialog(
    label: String,
    packageName: String,
    onDismiss: () -> Unit,
    actions: AppDetailActions,
) {
    var choice by rememberSaveable { mutableStateOf(UpdateKind.FDROID) }
    var repo by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pin_body), style = MaterialTheme.typography.bodySmall)
                UpdateKind.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = kind; error = null },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == kind, onClick = { choice = kind; error = null })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                stringResource(
                                    when (kind) {
                                        UpdateKind.FDROID -> R.string.pin_fdroid
                                        UpdateKind.GITHUB -> R.string.pin_github
                                        UpdateKind.WEB -> R.string.pin_web
                                    }
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(
                                    when (kind) {
                                        UpdateKind.FDROID -> R.string.pin_fdroid_desc
                                        UpdateKind.GITHUB -> R.string.pin_github_desc
                                        UpdateKind.WEB -> R.string.pin_web_desc
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (choice == UpdateKind.GITHUB) {
                    OutlinedTextField(
                        repo,
                        { repo = it; error = null },
                        label = { Text(stringResource(R.string.pin_github_field)) },
                        placeholder = { Text("owner/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (choice) {
                    UpdateKind.FDROID -> {
                        actions.onPinFDroid(packageName, label) { found ->
                            if (found) onDismiss() else error = R.string.pin_fdroid_missing
                        }
                    }
                    UpdateKind.GITHUB -> {
                        if (actions.onPinGitHub(packageName, label, repo)) onDismiss() else error = R.string.pin_github_invalid
                    }
                    UpdateKind.WEB -> {
                        actions.onPinWeb(packageName, label)
                        onDismiss()
                    }
                }
            }) { Text(stringResource(R.string.pin_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
