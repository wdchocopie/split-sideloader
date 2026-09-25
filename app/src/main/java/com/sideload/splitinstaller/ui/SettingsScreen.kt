package com.sideload.splitinstaller.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.AppLanguage
import com.sideload.splitinstaller.core.ota.OtaStatus
import com.sideload.splitinstaller.core.ota.OtaState
import com.sideload.splitinstaller.core.ota.OtaChannelKind
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.ThemeMode
import com.sideload.splitinstaller.core.ThemeSettings
import com.sideload.splitinstaller.core.UpdateInterval
import com.sideload.splitinstaller.core.install.BackendKind
import com.sideload.splitinstaller.core.install.BackendState
import com.sideload.splitinstaller.core.install.DeviceReport

/** Everything the settings screen shows, hoisted so the screen itself holds no storage. */
data class SettingsValues(
    val backend: BackendKind? = null,
    val installObb: Boolean = true,
    val allowDowngrade: Boolean = true,
    val grantAll: Boolean = false,
    val verifyChecksums: Boolean = true,
    val backupBeforeUpdate: Boolean = false,
    val autoInstall: Boolean = false,
    val updatesOnly: Boolean = true,
    val updateInterval: UpdateInterval = UpdateInterval.DAILY,
    val updateWifiOnly: Boolean = true,
    val updateAutoDownload: Boolean = false,
    val updateAutoInstall: Boolean = false,
    /** "owner/repo" or an https manifest URL; empty means this app does not update itself. */
    val otaChannel: String = "",
    val otaAutoDownload: Boolean = true,
    val otaAutoInstall: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

class SettingsActions(
    val onTheme: (ThemeSettings) -> Unit = {},
    val onValues: (SettingsValues) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onAppInfo: () -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onBatteryExempt: () -> Unit = {},
    val onOtaCheck: () -> Unit = {},
    val onOtaDownload: () -> Unit = {},
    val onOtaInstall: () -> Unit = {},
)

private const val SHIZUKU_START =
    "pkg install android-tools\n" +
        "adb pair 127.0.0.1:PAIRING_PORT\n" +
        "adb connect 127.0.0.1:CONNECT_PORT\n" +
        "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeSettings,
    values: SettingsValues,
    device: DeviceReport?,
    ota: OtaStatus = OtaStatus(),
    otaChecking: Boolean = false,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- appearance -----------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_appearance)) }
            item { AppearanceCard(theme, actions.onTheme) }
            item { LanguageCard(values.language) { actions.onValues(values.copy(language = it)) } }

            // ---- install method --------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_backend)) }
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), spacing = 0.dp) {
                    BackendRow(stringResource(R.string.backend_auto), stringResource(R.string.settings_backend_desc), values.backend == null, true, null) {
                        actions.onValues(values.copy(backend = null))
                    }
                    BackendKind.entries.forEach { kind ->
                        val cap = device?.capabilities?.firstOrNull { it.kind == kind }
                        BackendRow(
                            title = when (kind) {
                                BackendKind.SHIZUKU -> "Shizuku"
                                BackendKind.ROOT -> "Root (su)"
                                BackendKind.PACKAGE_INSTALLER -> stringResource(R.string.backend_normal)
                            },
                            subtitle = cap?.let { capabilityText(it) },
                            selected = values.backend == kind,
                            enabled = cap?.state != BackendState.UNAVAILABLE,
                            state = cap?.state,
                        ) { actions.onValues(values.copy(backend = kind)) }
                    }
                }
            }

            // ---- install options ---------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_install)) }
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), spacing = 0.dp) {
                    SwitchRow(stringResource(R.string.opt_verify), stringResource(R.string.opt_verify_desc), values.verifyChecksums,
                        { actions.onValues(values.copy(verifyChecksums = it)) }, Icons.Rounded.VerifiedUser)
                    SwitchRow(stringResource(R.string.opt_obb), stringResource(R.string.opt_obb_desc), values.installObb,
                        { actions.onValues(values.copy(installObb = it)) }, Icons.Rounded.SdStorage)
                    SwitchRow(stringResource(R.string.opt_backup), stringResource(R.string.opt_backup_desc), values.backupBeforeUpdate,
                        { actions.onValues(values.copy(backupBeforeUpdate = it)) }, Icons.Rounded.Backup)
                    SwitchRow(stringResource(R.string.opt_grant_all), stringResource(R.string.opt_grant_all_desc), values.grantAll,
                        { actions.onValues(values.copy(grantAll = it)) }, Icons.Rounded.LockOpen)
                    SwitchRow(stringResource(R.string.opt_downgrade), stringResource(R.string.opt_downgrade_desc), values.allowDowngrade,
                        { actions.onValues(values.copy(allowDowngrade = it)) }, Icons.Rounded.Update)
                }
            }

            // ---- automation -----------------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_watch)) }
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), spacing = 0.dp) {
                    SwitchRow(stringResource(R.string.opt_auto_install), stringResource(R.string.opt_auto_install_desc), values.autoInstall,
                        { actions.onValues(values.copy(autoInstall = it)) }, Icons.Rounded.Downloading)
                    SwitchRow(stringResource(R.string.opt_updates_only), stringResource(R.string.opt_updates_only_desc), values.updatesOnly,
                        { actions.onValues(values.copy(updatesOnly = it)) }, Icons.Rounded.SystemUpdateAlt, enabled = values.autoInstall)
                    if (values.autoInstall && device?.silentAvailable != true) {
                        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                            FindingRow(com.sideload.splitinstaller.core.bundle.Severity.WARN, stringResource(R.string.auto_install_needs_silent))
                        }
                    }
                }
            }

            // ---- updates ------------------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_updates)) }
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 6.dp) {
                    Text(stringResource(R.string.updates_interval_title), style = MaterialTheme.typography.titleSmall)
                    val options = listOf(
                        UpdateInterval.OFF to R.string.updates_off,
                        UpdateInterval.DAILY to R.string.updates_daily,
                        UpdateInterval.WEEKLY to R.string.updates_weekly,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        options.forEachIndexed { i, (interval, label) ->
                            SegmentedButton(
                                selected = values.updateInterval == interval,
                                onClick = { actions.onValues(values.copy(updateInterval = interval)) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                            ) { Text(stringResource(label), maxLines = 1) }
                        }
                    }
                    SwitchRow(
                        stringResource(R.string.updates_wifi_only), stringResource(R.string.updates_wifi_only_desc),
                        values.updateWifiOnly, { actions.onValues(values.copy(updateWifiOnly = it)) },
                        Icons.Rounded.Wifi,
                        // It also governs this app's own automatic downloads.
                        enabled = values.updateInterval != UpdateInterval.OFF || OtaChannel.parse(values.otaChannel).isOn,
                    )
                    SwitchRow(
                        stringResource(R.string.updates_auto_download), stringResource(R.string.updates_auto_download_desc),
                        values.updateAutoDownload, { actions.onValues(values.copy(updateAutoDownload = it)) },
                        Icons.Rounded.Download, enabled = values.updateInterval != UpdateInterval.OFF,
                    )
                    SwitchRow(
                        stringResource(R.string.updates_auto_install), stringResource(R.string.updates_auto_install_desc),
                        values.updateAutoInstall, { actions.onValues(values.copy(updateAutoInstall = it)) },
                        Icons.Rounded.InstallMobile,
                    )
                    if (values.updateAutoInstall && device?.silentAvailable != true) {
                        FindingRow(
                            com.sideload.splitinstaller.core.bundle.Severity.WARN,
                            stringResource(R.string.updates_needs_silent),
                        )
                    }
                    if (device != null && !device.batteryUnrestricted) {
                        FindingRow(
                            com.sideload.splitinstaller.core.bundle.Severity.WARN,
                            stringResource(R.string.updates_battery_warning),
                        )
                        FilledTonalButton(onClick = actions.onBatteryExempt) {
                            Icon(Icons.Rounded.BatteryAlert, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.updates_battery_action))
                        }
                    }
                    Text(
                        stringResource(R.string.updates_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---- this app ---------------------------------------------------------------
            item { SectionLabel(stringResource(R.string.ota_section)) }
            item { OtaSettings(values, ota, otaChecking, device?.silentAvailable == true, actions) }

            // ---- device -----------------------------------------------------------------------
            if (device != null) {
                item { SectionLabel(stringResource(R.string.section_device)) }
                item {
                    AppCard {
                        KeyValue(stringResource(R.string.label_model), device.model)
                        KeyValue(stringResource(R.string.label_android), device.androidRelease + " (API " + device.sdk + ")")
                        KeyValue(stringResource(R.string.label_abis), device.abis.joinToString(", "), mono = true)
                        KeyValue(stringResource(R.string.label_density), device.densityDpi.toString() + " dpi")
                        KeyValue(stringResource(R.string.label_free_data), humanSize(device.freeDataBytes))
                        KeyValue(
                            stringResource(R.string.label_all_files),
                            stringResource(if (device.hasAllFilesAccess) R.string.yes else R.string.no),
                            valueColor = if (device.hasAllFilesAccess) Tone.SUCCESS.accent() else Tone.WARNING.accent(),
                        )
                        device.suBinary?.let { KeyValue(stringResource(R.string.label_su), it, mono = true) }
                    }
                }
            }

            // ---- Shizuku over Termux -----------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_shizuku_help)) }
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.Top) {
                        IconBadge(Icons.Rounded.Terminal, Tone.INFO, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.shizuku_help_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    ConsoleBlock(SHIZUKU_START.lines())
                    Text(
                        stringResource(R.string.shizuku_help_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { actions.onCopy(SHIZUKU_START) }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_copy))
                    }
                }
            }

            // ---- about -------------------------------------------------------------------------
            item { SectionLabel(stringResource(R.string.settings_about)) }
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Info, Tone.PRIMARY, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "v" + BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.onAppInfo) { Text(stringResource(R.string.action_app_info)) }
                        OutlinedButton(onClick = actions.onClearHistory) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_clear_history))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(theme: ThemeSettings, onTheme: (ThemeSettings) -> Unit) {
    AppCard {
        Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
        val options = listOf(
            Triple(ThemeMode.SYSTEM, R.string.theme_system, Icons.Rounded.BrightnessAuto),
            Triple(ThemeMode.LIGHT, R.string.theme_light, Icons.Rounded.LightMode),
            Triple(ThemeMode.DARK, R.string.theme_dark, Icons.Rounded.DarkMode),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (mode, label, icon) ->
                SegmentedButton(
                    selected = theme.mode == mode,
                    onClick = { onTheme(theme.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    icon = { SegmentedButtonDefaults.Icon(active = theme.mode == mode) { Icon(icon, null, Modifier.size(18.dp)) } },
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        PalettePreview()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SwitchRow(
            title = stringResource(R.string.theme_dynamic),
            description = stringResource(if (dynamicSupported) R.string.theme_dynamic_desc else R.string.theme_dynamic_unsupported),
            checked = theme.dynamicColor && dynamicSupported,
            onChange = { onTheme(theme.copy(dynamicColor = it)) },
            icon = Icons.Rounded.Palette,
            enabled = dynamicSupported,
        )
        SwitchRow(
            title = stringResource(R.string.theme_amoled),
            description = stringResource(R.string.theme_amoled_desc),
            checked = theme.amoled,
            onChange = { onTheme(theme.copy(amoled = it)) },
            icon = Icons.Rounded.Contrast,
            enabled = theme.mode != ThemeMode.LIGHT,
        )
    }
}

/** The live scheme at a glance, so a theme change is visible before leaving the screen. */
@Composable
private fun PalettePreview() {
    val c = MaterialTheme.colorScheme
    val s = AppTheme.status
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(c.primary, c.secondary, c.tertiary, s.success, s.warning, c.error, c.surfaceContainerHighest).forEach { color ->
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(color)
                    .border(1.dp, c.outlineVariant.copy(alpha = 0.6f), CircleShape),
            )
        }
    }
}

@Composable
private fun BackendRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    state: BackendState?,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                    )
                }
            }
            when (state) {
                BackendState.READY -> StatusPill(stringResource(R.string.state_ready), Tone.SUCCESS)
                BackendState.NEEDS_ACTION -> StatusPill(stringResource(R.string.state_needs_action), Tone.WARNING)
                BackendState.UNAVAILABLE -> StatusPill(stringResource(R.string.state_unavailable), Tone.NEUTRAL)
                null -> Unit
            }
        }
    }
}

/**
 * This app is sideloaded too, so it cannot be updated by a store either. The channel is
 * whatever you publish to; leaving it empty simply turns the whole thing off.
 */
@Composable
private fun OtaSettings(
    values: SettingsValues,
    ota: OtaStatus,
    checking: Boolean,
    silent: Boolean,
    actions: SettingsActions,
) {
    // One state object for the whole visit, so every commit — including the one on leaving —
    // reads what is in the field now. It follows the stored value when that changes.
    var channel by rememberSaveable { mutableStateOf(values.otaChannel) }
    LaunchedEffect(values.otaChannel) {
        if (channel.trim() != values.otaChannel.trim()) channel = values.otaChannel
    }
    val parsed = OtaChannel.parse(channel)
    // Committed when editing ends, not per keystroke: a half-typed address would read as "off"
    // and throw away a build that is already downloaded.
    val focus = LocalFocusManager.current
    val latestValues by rememberUpdatedState(values)
    val latestActions by rememberUpdatedState(actions)
    val commit = {
        if (channel.trim() != latestValues.otaChannel.trim()) {
            latestActions.onValues(latestValues.copy(otaChannel = channel.trim()))
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latestCommit() } }
    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 8.dp) {
        KeyValue(stringResource(R.string.ota_installed), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        OutlinedTextField(
            value = channel,
            onValueChange = { channel = it },
            label = { Text(stringResource(R.string.ota_channel_label)) },
            placeholder = { Text(stringResource(R.string.ota_channel_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
        )
        Text(
            stringResource(
                when (parsed.kind) {
                    OtaChannelKind.GITHUB -> R.string.ota_channel_github
                    OtaChannelKind.MANIFEST -> R.string.ota_channel_manifest
                    OtaChannelKind.OFF -> R.string.ota_channel_off
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (parsed.isOn) {
            SwitchRow(
                stringResource(R.string.ota_auto_download), stringResource(R.string.ota_auto_download_desc),
                values.otaAutoDownload, { actions.onValues(values.copy(otaAutoDownload = it)) },
                Icons.Rounded.Download,
            )
            SwitchRow(
                stringResource(R.string.ota_auto_install), stringResource(R.string.ota_auto_install_desc),
                values.otaAutoInstall, { actions.onValues(values.copy(otaAutoInstall = it)) },
                Icons.Rounded.InstallMobile, enabled = values.otaAutoDownload,
            )
            if (values.otaAutoInstall && !silent) {
                FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.WARN,
                    stringResource(R.string.ota_needs_silent),
                )
            }

            when (ota.state) {
                OtaState.UPDATE -> FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.INFO,
                    stringResource(R.string.ota_state_update, ota.release?.versionName ?: "?"),
                )
                OtaState.READY -> FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.INFO,
                    stringResource(R.string.ota_state_ready, ota.release?.versionName ?: "?"),
                )
                OtaState.UP_TO_DATE -> Text(
                    stringResource(R.string.ota_state_current),
                    style = MaterialTheme.typography.bodySmall,
                )
                OtaState.UNKNOWN -> FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.WARN,
                    stringResource(R.string.ota_state_unknown, ota.release?.versionName ?: "?"),
                )
                OtaState.ERROR -> FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.WARN,
                    stringResource(R.string.ota_state_error, ota.message ?: "?"),
                )
                OtaState.OFF -> Unit
            }
            if (ota.checkedAt > 0) {
                Text(
                    stringResource(R.string.update_checked_at, relativeTime(ota.checkedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ota.stoppedRetrying) {
                FindingRow(
                    com.sideload.splitinstaller.core.bundle.Severity.WARN,
                    stringResource(R.string.ota_attempt_failed),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { commit(); actions.onOtaCheck() }, enabled = !checking) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ota_check_now))
                }
                when (ota.state) {
                    OtaState.UPDATE -> TextButton(onClick = { commit(); actions.onOtaDownload() }) {
                        Text(stringResource(R.string.ota_download))
                    }
                    OtaState.READY -> TextButton(onClick = { commit(); actions.onOtaInstall() }) {
                        Text(stringResource(R.string.ota_install_now))
                    }
                    else -> Unit
                }
            }
        }
        Text(
            stringResource(R.string.ota_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The app's own language. Android 13 has this in system settings, but not every ROM shows
 * it, and this app also runs on 8.0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard(current: AppLanguage, onChange: (AppLanguage) -> Unit) {
    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), spacing = 6.dp) {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
        val options = listOf(
            AppLanguage.SYSTEM to R.string.language_system,
            AppLanguage.VI to R.string.language_vi,
            AppLanguage.EN to R.string.language_en,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            options.forEachIndexed { i, (language, label) ->
                SegmentedButton(
                    selected = current == language,
                    onClick = { onChange(language) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(
            stringResource(R.string.language_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
