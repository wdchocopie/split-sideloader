package com.sideload.splitinstaller.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.log.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    lines: List<LogLine>,
    onClear: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var level by rememberSaveable { mutableStateOf(0) } // 0 all, 1 warnings+, 2 errors
    val filtered = remember(lines, level) {
        when (level) {
            1 -> lines.filter { it.severity != Severity.INFO || it.message.startsWith("────") }
            2 -> lines.filter { it.severity == Severity.ERROR || it.message.startsWith("────") }
            else -> lines
        }
    }
    val warnCount = remember(lines) { lines.count { it.severity == Severity.WARN } }
    val errCount = remember(lines) { lines.count { it.severity == Severity.ERROR } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_log)) },
                actions = {
                    IconButton(onClick = onShare, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.action_share_log))
                    }
                    IconButton(onClick = onClear, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.action_clear_log))
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelChip(stringResource(R.string.log_all, lines.size), level == 0) { level = 0 }
                LevelChip(stringResource(R.string.log_warnings, warnCount + errCount), level == 1) { level = 1 }
                LevelChip(stringResource(R.string.log_errors, errCount), level == 2) { level = 2 }
            }

            if (filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.log_empty_title),
                    body = stringResource(R.string.log_empty_body),
                    modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }

            val listState = rememberLazyListState()
            LaunchedEffect(filtered.size) {
                if (filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
            }
            Surface(
                modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(filtered) { line -> LogRow(line) }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogRow(line: LogLine) {
    if (line.message.startsWith("────")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(Modifier.width(14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                line.message.trim('─', ' '),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        return
    }
    val color = when (line.severity) {
        Severity.INFO -> MaterialTheme.colorScheme.onSurface
        Severity.WARN -> AppTheme.status.warning
        Severity.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            TIME.format(Date(line.at)),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            line.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}
