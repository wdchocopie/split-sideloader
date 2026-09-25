package com.sideload.splitinstaller.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.ota.OtaStatus
import com.sideload.splitinstaller.core.ota.OtaState
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.bundle.FoundBundle
import com.sideload.splitinstaller.core.history.HistoryEntry
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.Capability
import com.sideload.splitinstaller.core.install.DeviceReport

class HomeActions(
    val onPickFile: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onOpenFound: (FoundBundle) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCapability: (Capability) -> Unit = {},
    val onGrantInstall: () -> Unit = {},
    val onGrantStorage: () -> Unit = {},
    val onGrantNotifications: () -> Unit = {},
    val onToggleWatch: (Boolean) -> Unit = {},
    val onOpenSources: () -> Unit = {},
    val onOpenHistory: (HistoryEntry) -> Unit = {},
    val onOtaDownload: () -> Unit = {},
    val onOtaInstall: () -> Unit = {},
    val onOtaDismiss: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    history: List<HistoryEntry>,
    autoInstall: Boolean,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    ota: OtaStatus? = null,
    otaDownloading: Boolean = false,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.action_recheck))
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") { StatusHero(state.device, actions.onCapability) }

            if (ota != null && ota.hasUpdate) {
                item(key = "ota") { OtaCard(ota, state.device?.silentAvailable == true, otaDownloading, actions) }
            }

            val device = state.device
            if (device != null && device.missingPermissions > 0) {
                item(key = "perms") { PermissionCard(device, actions) }
            }

            item(key = "tiles") { ActionTiles(state.scanning, actions.onPickFile, actions.onScan) }
            item(key = "sources") { SourcesEntry(actions.onOpenSources) }

            item(key = "watch") {
                WatchCard(state.watchEnabled, autoInstall, device?.silentAvailable == true, actions.onToggleWatch)
            }

            if (state.found.isNotEmpty() || state.scannedOnce) {
                item(key = "found-label") {
                    SectionLabel(stringResource(R.string.section_found, state.found.size))
                }
                if (state.found.isEmpty()) {
                    item(key = "found-empty") {
                        AppCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.SearchOff, Tone.NEUTRAL, 40.dp)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    stringResource(R.string.found_none),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(state.found, key = { "f-" + it.key }) { found ->
                    FoundRow(found) { actions.onOpenFound(found) }
                }
            }

            if (history.isNotEmpty()) {
                item(key = "recent-label") { SectionLabel(stringResource(R.string.section_recent)) }
                item(key = "recent") {
                    AppCard(contentPadding = PaddingValues(6.dp), spacing = 0.dp) {
                        history.take(6).forEach { entry ->
                            HistoryRow(entry) { actions.onOpenHistory(entry) }
                        }
                    }
                }
            }
        }
    }
}

// ---- hero -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHero(device: DeviceReport?, onCapability: (Capability) -> Unit) {
    if (device == null) {
        AppCard(shape = RoundedCornerShape(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.hero_checking), style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val silent = device.silentBackend
    val tone = when {
        silent != null -> Tone.SUCCESS
        device.best != null -> Tone.PRIMARY
        else -> Tone.WARNING
    }
    val title: Int
    val subtitle: Int
    val icon: ImageVector
    when {
        silent == BackendKind.SHIZUKU -> { title = R.string.hero_silent_title; subtitle = R.string.hero_via_shizuku; icon = Icons.Rounded.Bolt }
        silent == BackendKind.ROOT -> { title = R.string.hero_silent_title; subtitle = R.string.hero_via_root; icon = Icons.Rounded.Bolt }
        device.best != null -> { title = R.string.hero_prompt_title; subtitle = R.string.hero_prompt_sub; icon = Icons.Rounded.TouchApp }
        else -> { title = R.string.hero_blocked_title; subtitle = R.string.hero_blocked_sub; icon = Icons.Rounded.Block }
    }
    val onTone = tone.onContainer()

    AppCard(
        color = tone.container(),
        contentColor = onTone,
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(20.dp),
        spacing = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(onTone.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onTone.copy(alpha = 0.8f),
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            device.capabilities.forEach { cap -> BackendChip(cap, onTone) { onCapability(cap) } }
        }

        HorizontalDivider(color = onTone.copy(alpha = 0.14f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(16.dp), tint = onTone.copy(alpha = 0.75f))
                Spacer(Modifier.width(8.dp))
                Text(
                    device.model + " · Android " + device.androidRelease + " · " + device.primaryAbi,
                    style = MaterialTheme.typography.bodySmall,
                    color = onTone.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (device.totalDataBytes > 0) {
                val used = 1f - device.freeDataBytes.toFloat() / device.totalDataBytes
                LinearProgressIndicator(
                    progress = { used.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = onTone.copy(alpha = 0.7f),
                    trackColor = onTone.copy(alpha = 0.14f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Text(
                    stringResource(R.string.hero_storage, humanSize(device.freeDataBytes), humanSize(device.totalDataBytes)),
                    style = MaterialTheme.typography.labelMedium,
                    color = onTone.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun BackendChip(cap: Capability, onTone: Color, onClick: () -> Unit) {
    val dot = when (cap.state) {
        BackendState.READY -> AppTheme.status.success
        BackendState.NEEDS_ACTION -> AppTheme.status.warning
        BackendState.UNAVAILABLE -> onTone.copy(alpha = 0.35f)
    }
    val name = when (cap.kind) {
        BackendKind.SHIZUKU -> "Shizuku"
        BackendKind.ROOT -> "Root"
        BackendKind.PACKAGE_INSTALLER -> stringResource(R.string.backend_normal_short)
    }
    val action = when (cap.action) {
        "grant" -> stringResource(R.string.action_grant)
        "start" -> stringResource(R.string.action_open)
        "test" -> stringResource(R.string.action_test)
        else -> null
    }
    Surface(
        onClick = onClick,
        enabled = cap.action != null,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (AppTheme.isDark) 0.28f else 0.6f),
        contentColor = onTone,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dot, 8.dp)
            Spacer(Modifier.width(7.dp))
            Text(name, style = MaterialTheme.typography.labelLarge)
            if (action != null) {
                Text(
                    " · $action",
                    style = MaterialTheme.typography.labelMedium,
                    color = onTone.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ---- permissions ----------------------------------------------------------------------

@Composable
private fun PermissionCard(device: DeviceReport, actions: HomeActions) {
    AppCard {
        CardTitle(
            stringResource(R.string.perm_title),
            icon = Icons.Rounded.Shield,
            trailing = { StatusPill(device.missingPermissions.toString(), Tone.WARNING) },
        )
        if (!device.canRequestInstalls) {
            PermissionRow(Icons.Rounded.InstallMobile, R.string.perm_install, R.string.perm_install_why, actions.onGrantInstall)
        }
        if (!device.hasAllFilesAccess) {
            PermissionRow(Icons.Rounded.FolderOpen, R.string.perm_files, R.string.perm_files_why, actions.onGrantStorage)
        }
        if (!device.canPostNotifications) {
            PermissionRow(Icons.Rounded.Notifications, R.string.perm_notif, R.string.perm_notif_why, actions.onGrantNotifications)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: Int, why: Int, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, Tone.WARNING, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(stringResource(R.string.action_grant))
        }
    }
}

// ---- actions ----------------------------------------------------------------------------

@Composable
private fun ActionTiles(scanning: Boolean, onPick: () -> Unit, onScan: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(R.string.tile_open_title),
            subtitle = stringResource(R.string.tile_open_sub),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onPick,
        )
        ActionTile(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Rounded.ManageSearch,
            title = stringResource(R.string.tile_scan_title),
            subtitle = stringResource(R.string.tile_scan_sub),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            busy = scanning,
            onClick = onScan,
        )
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(content.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = content)
                } else {
                    Icon(icon, null, Modifier.size(24.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun SourcesEntry(onOpen: () -> Unit) {
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.TravelExplore, Tone.INFO, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_sources_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_sources_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchCard(enabled: Boolean, autoInstall: Boolean, silent: Boolean, onToggle: (Boolean) -> Unit) {
    AppCard(onClick = { onToggle(!enabled) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                if (enabled) Icons.Rounded.Radar else Icons.Rounded.VisibilityOff,
                if (enabled) Tone.PRIMARY else Tone.NEUTRAL,
                44.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.watch_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when {
                            !enabled -> R.string.watch_desc_off
                            silent && autoInstall -> R.string.watch_desc_silent
                            else -> R.string.watch_desc_prompt
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        AnimatedVisibility(enabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    stringResource(if (autoInstall) R.string.watch_pill_auto_on else R.string.watch_pill_auto_off),
                    if (autoInstall) Tone.SUCCESS else Tone.NEUTRAL,
                )
                StatusPill(
                    stringResource(if (silent) R.string.watch_pill_silent else R.string.watch_pill_notify),
                    if (silent) Tone.SUCCESS else Tone.WARNING,
                )
            }
        }
    }
}

// ---- lists ------------------------------------------------------------------------------

@Composable
private fun FoundRow(found: FoundBundle, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FormatTile(formatOf(found.name))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    found.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    humanSize(found.size) + " · " + relativeTime(found.modified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(entry.packageName, entry.label ?: entry.bundleName, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label ?: entry.packageName ?: entry.bundleName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        entry.versionName,
                        relativeTime(entry.time),
                        if (entry.repair) stringResource(R.string.history_repair) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            val (text, tone, icon) = historyStatus(entry)
            StatusPill(stringResource(text), tone, icon)
        }
    }
}

private fun historyStatus(entry: HistoryEntry): Triple<Int, Tone, ImageVector> = when {
    !entry.ok -> Triple(R.string.status_failed, Tone.DANGER, Icons.Rounded.Error)
    entry.verdict == "BROKEN" -> Triple(R.string.status_broken, Tone.DANGER, Icons.Rounded.Error)
    entry.verdict == "WARN" -> Triple(R.string.status_attention, Tone.WARNING, Icons.Rounded.Warning)
    entry.verdict == "OK" -> Triple(R.string.status_working, Tone.SUCCESS, Icons.Rounded.CheckCircle)
    else -> Triple(R.string.status_installed, Tone.NEUTRAL, Icons.Rounded.CheckCircle)
}

/** This app has a newer build waiting. Shown here because it is the first screen. */
@Composable
private fun OtaCard(ota: OtaStatus, silent: Boolean, downloading: Boolean, actions: HomeActions) {
    val ready = ota.state == OtaState.READY
    AppCard(color = Tone.INFO.container(), contentColor = Tone.INFO.onContainer()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ota_card_title, ota.release?.versionName ?: "?"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.ota_card_from, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ota.release?.notes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (ready && !silent) {
            Text(stringResource(R.string.ota_needs_tap), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = if (ready) actions.onOtaInstall else actions.onOtaDownload,
                enabled = ready || !downloading,
            ) {
                Icon(
                    if (ready) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                    null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ready) stringResource(R.string.ota_install_now)
                    else if (downloading) stringResource(R.string.ota_downloading)
                    else stringResource(R.string.ota_download) +
                        (ota.release?.size?.takeIf { it > 0 }?.let { " · " + humanSize(it) } ?: "")
                )
            }
            TextButton(onClick = actions.onOtaDismiss) { Text(stringResource(R.string.ota_later)) }
        }
    }
}
