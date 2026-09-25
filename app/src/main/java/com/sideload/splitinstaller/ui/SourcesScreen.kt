package com.sideload.splitinstaller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.Source
import com.sideload.splitinstaller.core.sources.SourceTrust

class SourcesActions(
    val onQuery: (String) -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onOpenSource: (Source) -> Unit = {},
    val onShowAdd: (Boolean) -> Unit = {},
    val onAdd: (name: String, home: String, search: String) -> Unit = { _, _, _ -> },
    val onRemoveSource: (String) -> Unit = {},
    val onInstallDownload: (DownloadItem) -> Unit = {},
    val onCancelDownload: (Long) -> Unit = {},
    val onForgetDownload: (Long) -> Unit = {},
    val onOpenExternal: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(state: SourcesState, actions: SourcesActions, modifier: Modifier = Modifier) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_sources)) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "search") { SearchCard(state, actions) }

            if (state.downloads.isNotEmpty()) {
                item(key = "dl-label") { SectionLabel(stringResource(R.string.section_downloads)) }
                items(state.downloads, key = { "dl-" + it.id }) { item -> DownloadRow(item, actions) }
            }

            item(key = "src-label") { SectionLabel(stringResource(R.string.section_sources)) }
            items(state.sources, key = { "src-" + it.id }) { source -> SourceRow(source, actions) }
            item(key = "add") {
                OutlinedButton(onClick = { actions.onShowAdd(true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_source))
                }
            }

            item(key = "tips") { TipsCard() }
        }
    }

    if (state.showAdd) AddSourceDialog(state.addError, actions)
}

// ---- search ---------------------------------------------------------------------------------

@Composable
private fun SearchCard(state: SourcesState, actions: SourcesActions) {
    AppCard(spacing = 14.dp) {
        TextField(
            value = state.query,
            onValueChange = actions.onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.sources_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { actions.onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
                }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.sources.forEach { source ->
                FilterChip(
                    selected = source.id == state.selectedId,
                    onClick = { actions.onSelect(source.id) },
                    label = { Text(source.name) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }
        Button(onClick = actions.onSearch, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
            Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (state.query.isBlank()) R.string.sources_open_on else R.string.sources_search_on,
                    state.selected.name,
                )
            )
        }
        Text(
            stringResource(R.string.sources_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- downloads ------------------------------------------------------------------------------

@Composable
private fun DownloadRow(item: DownloadItem, actions: SourcesActions) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatTile(formatOf(item.fileName), 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        downloadStatus(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            DownloadStatus.DONE -> Tone.SUCCESS.accent()
                            DownloadStatus.FAILED -> Tone.DANGER.accent()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when (item.status) {
                    DownloadStatus.DONE ->
                        if (item.isBundle && item.uri != null) {
                            FilledTonalButton(onClick = { actions.onInstallDownload(item) }, contentPadding = PaddingValues(horizontal = 16.dp)) {
                                Text(stringResource(R.string.action_install_short))
                            }
                        } else {
                            IconButton(onClick = { actions.onForgetDownload(item.id) }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.action_forget))
                            }
                        }
                    DownloadStatus.FAILED -> Row {
                        item.sourceUrl?.let { url ->
                            IconButton(onClick = { actions.onOpenExternal(url) }) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open_external))
                            }
                        }
                        IconButton(onClick = { actions.onForgetDownload(item.id) }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_forget))
                        }
                    }
                    else -> IconButton(onClick = { actions.onCancelDownload(item.id) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel_download))
                    }
                }
            }
            if (item.active) {
                val fraction = item.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape))
                }
            }
            if (item.status == DownloadStatus.FAILED) {
                Text(
                    stringResource(R.string.download_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun downloadStatus(item: DownloadItem): String {
    val host = item.host?.let { " · $it" }.orEmpty()
    return when (item.status) {
        DownloadStatus.PENDING -> stringResource(R.string.download_pending)
        DownloadStatus.RUNNING -> {
            val f = item.fraction
            if (f != null) {
                "${(f * 100).toInt()}% · " + stringResource(R.string.download_progress, humanSize(item.bytes), humanSize(item.total))
            } else {
                humanSize(item.bytes)
            }
        }
        DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
        DownloadStatus.DONE -> stringResource(R.string.download_done, humanSize(item.total.coerceAtLeast(item.bytes))) + host
        DownloadStatus.FAILED -> stringResource(R.string.download_failed, item.reasonText)
    }
}

// ---- sources ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(source: Source, actions: SourcesActions) {
    Surface(
        onClick = { actions.onOpenSource(source) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceTile(source)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    source.host,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    source.formats.forEach { MetaPill(it) }
                    val (text, tone) = when (source.trust) {
                        SourceTrust.VERBATIM -> R.string.trust_verbatim to Tone.SUCCESS
                        SourceTrust.FILES_ONLY -> R.string.trust_files_only to Tone.WARNING
                        SourceTrust.FOSS -> R.string.trust_foss to Tone.INFO
                        SourceTrust.CUSTOM -> R.string.trust_custom to Tone.NEUTRAL
                    }
                    MetaPill(stringResource(text), tone = tone)
                }
            }
            if (source.builtin) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = { actions.onRemoveSource(source.id) }) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.action_remove))
                }
            }
        }
    }
}

@Composable
private fun SourceTile(source: Source) {
    val tone = when (source.id) {
        "apkmirror" -> Tone.WARNING
        "apkpure" -> Tone.SUCCESS
        "fdroid" -> Tone.INFO
        "github" -> Tone.NEUTRAL
        else -> Tone.PRIMARY
    }
    val initials = when (source.id) {
        "apkmirror" -> "AM"
        "apkpure" -> "AP"
        "fdroid" -> "FD"
        "github" -> "GH"
        else -> source.name.split(' ', '-', '.').filter { it.isNotBlank() }
            .let { words -> if (words.size >= 2) words.take(2).joinToString("") { it.take(1) } else source.name.take(2) }
            .uppercase()
    }
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tone.container()),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = tone.onContainer(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun TipsCard() {
    AppCard {
        CardTitle(stringResource(R.string.sources_tips_title), Icons.Rounded.Lightbulb)
        listOf(R.string.sources_tip_1, R.string.sources_tip_2, R.string.sources_tip_3).forEach { tip ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AddSourceDialog(error: Boolean, actions: SourcesActions) {
    var name by rememberSaveable { mutableStateOf("") }
    var home by rememberSaveable { mutableStateOf("https://") }
    var search by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { actions.onShowAdd(false) },
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.add_source_name)) }, singleLine = true)
                OutlinedTextField(
                    home, { home = it },
                    label = { Text(stringResource(R.string.add_source_home)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    search, { search = it },
                    label = { Text(stringResource(R.string.add_source_search)) },
                    placeholder = { Text("https://example.com/search?q={q}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (error) {
                    Text(
                        stringResource(R.string.add_source_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onAdd(name, home, search) }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = { actions.onShowAdd(false) }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
