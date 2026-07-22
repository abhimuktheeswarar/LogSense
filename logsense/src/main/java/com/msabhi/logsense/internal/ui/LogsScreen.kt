package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.color
import kotlinx.coroutines.launch

@Composable
internal fun LogsScreen(core: LogSenseCore) {
    val entries by core.buffer.snapshot.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.VERBOSE) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // ponytail: in-memory filter over ~15k lines per snapshot; move to a background flow if it ever janks
    val filtered = remember(entries, query, minLevel, tagFilter) {
        entries.filter { entry ->
            entry.level.ordinal >= minLevel.ordinal &&
                (tagFilter == null || entry.tag == tagFilter) &&
                (query.isEmpty() || entry.message.contains(query, true) || entry.tag.contains(query, true))
        }
    }
    val tags = remember(entries) { entries.mapTo(mutableSetOf()) { it.tag }.sorted() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search") },
                leadingIcon = { Icon(LogSenseIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(LogSenseIcons.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            IconButton(onClick = { core.scope.launch { ShareUtil.shareLogFile(context, filtered) } }) {
                Icon(LogSenseIcons.Share, contentDescription = "Share logs")
            }
            IconButton(onClick = { core.scope.launch { core.buffer.clear() } }) {
                Icon(LogSenseIcons.Delete, contentDescription = "Clear logs")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = minLevel == level,
                    onClick = { minLevel = level },
                    label = { Text(level.letter.toString()) },
                )
            }
            TagFilterChip(tags, tagFilter) { tagFilter = it }
        }

        LogList(filtered)
    }
}

@Composable
private fun TagFilterChip(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(selected ?: "Tag") },
            trailingIcon = { Icon(LogSenseIcons.ArrowDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All tags") }, onClick = { onSelect(null); expanded = false })
            tags.forEach { tag ->
                DropdownMenuItem(text = { Text(tag) }, onClick = { onSelect(tag); expanded = false })
            }
        }
    }
}

@Composable
private fun LogList(filtered: List<LogEntry>) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val followTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(filtered.size) {
        if (followTail && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { entry -> LogRow(entry) }
        }
        if (!followTail && filtered.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(filtered.lastIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(LogSenseIcons.ArrowDown, contentDescription = "Jump to latest")
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = entry.level.color()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(
            text = entry.level.letter.toString(),
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .background(levelColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Row {
                Text(
                    text = entry.timeMs.asTime(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.level.ordinal >= LogLevel.ERROR.ordinal) {
                        levelColor
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
