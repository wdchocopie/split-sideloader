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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import com.sideload.splitinstaller.core.linkProblemRes
import com.sideload.splitinstaller.core.sources.DownloadItem
import com.sideload.splitinstaller.core.sources.DownloadStatus
import com.sideload.splitinstaller.core.sources.LinkProblem
import com.sideload.splitinstaller.core.update.FDroidHit
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
    val onLinkText: (String) -> Unit = {},
    val onLinkGo: () -> Unit = {},
    /** The link in the browser instead, after the server refused it. */
    val onLinkOpenPage: () -> Unit = {},
    val onFDroidInstall: (FDroidHit) -> Unit = {},
    val onFDroidPage: (String) -> Unit = {},
    val onFDroidClose: () -> Unit = {},
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
            if (state.fdroid.busy || state.fdroid.hits != null || state.fdroid.failed) {
                item(key = "fdroid") { FDroidResults(state.fdroid, actions) }
            }
            item(key = "link") { LinkCard(state.link, actions) }

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

// ---- F-Droid search results -----------------------------------------------------------------

@Composable
private fun FDroidResults(search: FDroidSearch, actions: SourcesActions) {
    AppCard(spacing = 8.dp) {
        CardTitle(stringResource(R.string.fdroid_results_title), Icons.Rounded.Search) {
            IconButton(onClick = actions.onFDroidClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
        }
        when {
            search.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
            search.failed && search.hits == null -> Text(
                stringResource(R.string.fdroid_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            search.hits.isNullOrEmpty() -> Text(stringResource(R.string.fdroid_none), style = MaterialTheme.typography.bodySmall)
            else -> search.hits.take(MAX_HITS).forEach { hit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            hit.summary ?: hit.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { actions.onFDroidPage(hit.packageName) }) { Text(stringResource(R.string.fdroid_page)) }
                    FilledTonalButton(
                        onClick = { actions.onFDroidInstall(hit) },
                        enabled = search.installing == null,
                    ) { Text(stringResource(R.string.action_install)) }
                }
            }
        }
        if (search.failed && search.hits != null) {
            Text(stringResource(R.string.fdroid_no_build), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private const val MAX_HITS = 8

// ---- download from a link -------------------------------------------------------------------

@Composable
private fun LinkCard(link: LinkState, actions: SourcesActions) {
    val clipboard = LocalClipboardManager.current
    AppCard(spacing = 10.dp) {
        CardTitle(stringResource(R.string.link_title), Icons.Rounded.Link)
        OutlinedTextField(
            value = link.text,
            onValueChange = actions.onLinkText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.link_hint)) },
            singleLine = true,
            trailingIcon = {
                // The clipboard is read only on this tap: Android shows that it was read.
                IconButton(onClick = { clipboard.getText()?.text?.let(actions.onLinkText) }) {
                    Icon(Icons.Rounded.ContentPaste, stringResource(R.string.link_paste))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.onLinkGo() }),
        )
        link.problem?.let { code ->
            Text(
                if (code == LinkProblem.HTTP) stringResource(R.string.link_p_http, link.httpCode)
                else stringResource(linkProblemRes(code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            // A site that wants its own page visited first (a login, a cookie) may still give the
            // file to the browser.
            if (code == LinkProblem.HTTP) {
                TextButton(onClick = actions.onLinkOpenPage) { Text(stringResource(R.string.link_open_page)) }
            }
        }
        Button(
            onClick = actions.onLinkGo,
            enabled = link.text.isNotBlank() && !link.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (link.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_checking))
            } else {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_go))
            }
        }
        Text(
            stringResource(R.string.link_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Asked before anything is downloaded, whether the link was typed here or shared from elsewhere. */
@Composable
fun LinkConfirmDialog(plan: LinkPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Download, null) },
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plan.fileName, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.link_confirm_from, plan.origin), style = MaterialTheme.typography.bodyMedium)
                plan.servedBy?.let { Text(stringResource(R.string.link_confirm_served, it), style = MaterialTheme.typography.bodyMedium) }
                plan.versionName?.let { Text(stringResource(R.string.link_confirm_version, it), style = MaterialTheme.typography.bodyMedium) }
                plan.size?.let { Text(stringResource(R.string.link_confirm_size, humanSize(it)), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(R.string.link_confirm_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.link_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * A shared link that leads to a web page. Opened only on a tap: a page in the browser can start
 * downloads by itself, and nobody asked for this one from inside the app.
 */
@Composable
fun LinkPageDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.TravelExplore, null) },
        title = { Text(stringResource(R.string.link_page_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.link_page_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.link_open_page)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
