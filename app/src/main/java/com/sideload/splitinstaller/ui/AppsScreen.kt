package com.sideload.splitinstaller.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.update.UpdateResult
import com.sideload.splitinstaller.core.verify.Verdict

class AppsActions(
    val onScan: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onFilter: (AppFilter) -> Unit = {},
    val onToggleSystem: (Boolean) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(state: AppsState, actions: AppsActions, modifier: Modifier = Modifier) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_apps)) },
                actions = {
                    IconButton(onClick = actions.onCheckUpdates, enabled = !state.checkingUpdates) {
                        Icon(Icons.Rounded.SystemUpdateAlt, stringResource(R.string.updates_check_all))
                    }
                    IconButton(onClick = actions.onScan, enabled = !state.scanning) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.apps_rescan))
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
        if (!state.scannedOnce && !state.scanning) {
            EmptyState(
                icon = Icons.Rounded.HealthAndSafety,
                title = stringResource(R.string.apps_empty_title),
                body = stringResource(R.string.apps_empty_body),
                modifier = Modifier.padding(padding).padding(top = 24.dp),
            ) {
                Button(onClick = actions.onScan) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.apps_scan))
                }
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.scanning && state.scannedOnce,
            onRefresh = actions.onScan,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val visible = state.visible
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.scanning) {
                    item(key = "progress") { ScanProgress(state.done, state.total) }
                }

                if (state.checkingUpdates) {
                    item(key = "checking") {
                        AppCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.updates_checking), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (state.updateCount > 0 && state.filter != AppFilter.UPDATES) {
                    item(key = "updates-banner") {
                        AppCard(
                            onClick = { actions.onFilter(AppFilter.UPDATES) },
                            color = Tone.INFO.container(),
                            contentColor = Tone.INFO.onContainer(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pluralStringResource(R.plurals.updates_available, state.updateCount, state.updateCount),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(stringResource(R.string.updates_banner_body), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (state.scannedOnce) {
                    item(key = "stats") { StatsRow(state) }
                    if (state.attention > 0 && state.filter != AppFilter.ATTENTION) {
                        item(key = "banner") { AttentionBanner(state.attention) { actions.onFilter(AppFilter.ATTENTION) } }
                    }
                }

                item(key = "search") { SearchField(state.query, actions.onQuery) }
                item(key = "filters") { FilterRow(state, actions) }

                if (visible.isEmpty() && state.scannedOnce) {
                    item(key = "none") {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = stringResource(R.string.apps_none_title),
                            body = stringResource(R.string.apps_none_body),
                        )
                    }
                }
                items(visible, key = { it.report.packageName }) { row ->
                    AppListRow(row, state.updates[row.report.packageName]) {
                        actions.onOpen(row.report.packageName)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanProgress(done: Int, total: Int) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apps_scanning), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("$done / $total", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun StatsRow(state: AppsState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(state.apps.size.toString(), stringResource(R.string.stat_apps), Tone.NEUTRAL, Modifier.weight(1f))
        StatTile(state.withSplits.toString(), stringResource(R.string.stat_split), Tone.INFO, Modifier.weight(1f))
        StatTile(
            state.attention.toString(),
            stringResource(R.string.stat_attention),
            if (state.attention > 0) Tone.DANGER else Tone.SUCCESS,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, tone: Tone, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.surfaceContainerLow else tone.container(),
        contentColor = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else tone.onContainer(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AttentionBanner(count: Int, onShow: () -> Unit) {
    AppCard(
        onClick = onShow,
        color = Tone.DANGER.container(),
        contentColor = Tone.DANGER.onContainer(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Error, null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apps_banner_title, count), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.apps_banner_body), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.apps_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun FilterRow(state: AppsState, actions: AppsActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(stringResource(R.string.filter_all), state.filter == AppFilter.ALL) { actions.onFilter(AppFilter.ALL) }
        Chip(stringResource(R.string.filter_updates, state.updateCount), state.filter == AppFilter.UPDATES) {
            actions.onFilter(AppFilter.UPDATES)
        }
        Chip(stringResource(R.string.filter_attention, state.attention), state.filter == AppFilter.ATTENTION) {
            actions.onFilter(AppFilter.ATTENTION)
        }
        Chip(stringResource(R.string.filter_split, state.withSplits), state.filter == AppFilter.SPLIT) {
            actions.onFilter(AppFilter.SPLIT)
        }
        Chip(stringResource(R.string.filter_risky, state.riskySource), state.filter == AppFilter.RISKY_SOURCE) {
            actions.onFilter(AppFilter.RISKY_SOURCE)
        }
        Chip(stringResource(R.string.filter_system), state.includeSystem) { actions.onToggleSystem(!state.includeSystem) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppListRow(row: AppRow, update: UpdateResult?, onClick: () -> Unit) {
    val r = row.report
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.packageName, r.label ?: r.packageName, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                TitleWithMono(r.label ?: r.packageName, r.packageName, MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    MetaPill(r.versionName ?: r.versionCode.toString())
                    update?.takeIf { it.hasUpdate }?.let {
                        MetaPill("→ " + (it.availableVersionName ?: "?"), tone = Tone.INFO)
                    }
                    if (r.splitNames.isNotEmpty()) MetaPill(stringResource(R.string.pill_splits, r.splitNames.size), tone = Tone.INFO)
                    (r.primaryCpuAbi ?: r.usableAbis.firstOrNull())?.let { MetaPill(it, mono = true) }
                    r.engine?.let { MetaPill(it.label, tone = Tone.PRIMARY) }
                    if (row.installer.risky) MetaPill(row.installer.label, tone = Tone.WARNING)
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                r.verdict.icon(), null,
                Modifier.size(24.dp),
                tint = if (r.verdict == Verdict.OK) AppTheme.status.success.copy(alpha = 0.8f) else r.verdict.tone().accent(),
            )
        }
    }
}
