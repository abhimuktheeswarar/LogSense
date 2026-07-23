package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.logs.LogTab
import com.msabhi.logsense.internal.logs.ViewMode
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher
import com.msabhi.logsense.internal.ui.theme.color
import kotlinx.coroutines.launch

@Composable
internal fun LogsScreen(core: LogSenseCore) {
    val tabs = remember { core.prefs.loadTabs().toMutableStateList() }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, tabs.lastIndex)

    fun persist() = core.prefs.saveTabs(tabs)

    Column(Modifier.fillMaxSize()) {
        LogTabStrip(
            tabs = tabs,
            selected = index,
            onSelect = { selected = it },
            onAdd = {
                val id = (tabs.maxOfOrNull { it.id } ?: -1L) + 1
                tabs.add(LogTab(id = id, name = "Logs ${tabs.size + 1}"))
                selected = tabs.lastIndex
                persist()
            },
            onClose = { i ->
                if (tabs.size > 1) {
                    tabs.removeAt(i)
                    selected = index.coerceAtMost(tabs.lastIndex)
                    persist()
                }
            },
        )
        val tab = tabs[index]
        key(tab.id) {
            LogTabContent(core, tab, onTabChange = { tabs[index] = it; persist() })
        }
    }
}

@Composable
private fun LogTabStrip(
    tabs: List<LogTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onClose: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val isSelected = i == selected
            Row(
                modifier = Modifier
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(start = 12.dp, end = if (isSelected && tabs.size > 1) 2.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (isSelected && tabs.size > 1) {
                    IconButton(onClick = { onClose(i) }, modifier = Modifier.size(28.dp)) {
                        Icon(LogSenseIcons.Close, contentDescription = "Close tab", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(LogSenseIcons.Add, contentDescription = "New tab")
        }
    }
}

@Composable
private fun LogTabContent(core: LogSenseCore, tab: LogTab, onTabChange: (LogTab) -> Unit) {
    val liveEntries by core.buffer.snapshot.collectAsState()
    // Per-tab pause freezes this tab's view while the shared buffer keeps filling.
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }
    LaunchedEffect(tab.paused) { frozen = if (tab.paused) core.buffer.currentSnapshot() else null }
    val entries = frozen ?: liveEntries

    val filtered = remember(entries, tab.filter) { entries.filter { tab.filter.matches(it) } }
    val tags = remember(entries) { entries.mapTo(sortedSetOf()) { it.tag }.toList() }

    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(SearchQuery()) }
    val matcher = remember(search) { if (search.isActive) TextMatcher.from(search) else null }
    val matchIndices = remember(filtered, matcher) {
        if (matcher == null) emptyList()
        else filtered.indices.filter { matcher.matches("${filtered[it].tag} ${filtered[it].message}") }
    }
    var matchPos by remember(matcher) { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(matchPos, matchIndices) {
        matchIndices.getOrNull(matchPos)?.let { listState.scrollToItem(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = tab.filter.text,
                onValueChange = { onTabChange(tab.copy(filter = tab.filter.copy(text = it))) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Filter") },
                trailingIcon = {
                    if (tab.filter.text.isNotEmpty()) {
                        IconButton(onClick = { onTabChange(tab.copy(filter = tab.filter.copy(text = ""))) }) {
                            Icon(LogSenseIcons.Close, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
            )
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(LogSenseIcons.Search, contentDescription = "Find")
            }
            IconButton(onClick = { core.scope.launch { ShareUtil.shareLogFile(context, filtered) } }) {
                Icon(LogSenseIcons.Share, contentDescription = "Share logs")
            }
            IconButton(onClick = { core.scope.launch { core.buffer.clear() } }) {
                Icon(LogSenseIcons.Delete, contentDescription = "Clear logs")
            }
        }

        if (searchOpen) {
            SearchBar(
                query = search,
                onQueryChange = { search = it; matchPos = 0 },
                matchCount = matchIndices.size,
                currentMatch = matchPos,
                onPrev = { if (matchIndices.isNotEmpty()) matchPos = (matchPos - 1 + matchIndices.size) % matchIndices.size },
                onNext = { if (matchIndices.isNotEmpty()) matchPos = (matchPos + 1) % matchIndices.size },
                onClose = { searchOpen = false; search = SearchQuery() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = tab.filter.minLevel == level,
                    onClick = { onTabChange(tab.copy(filter = tab.filter.copy(minLevel = level))) },
                    label = { Text(level.letter.toString()) },
                )
            }
            TagFilterChip(tags, tab.filter.tag) { onTabChange(tab.copy(filter = tab.filter.copy(tag = it))) }

            IconButton(onClick = { onTabChange(tab.copy(paused = !tab.paused)) }) {
                Icon(
                    if (tab.paused) LogSenseIcons.Play else LogSenseIcons.Pause,
                    contentDescription = if (tab.paused) "Resume this tab" else "Pause this tab",
                )
            }
            IconButton(onClick = { core.restartReader() }) {
                Icon(LogSenseIcons.Restart, contentDescription = "Restart logcat")
            }
            ToggleIcon(LogSenseIcons.WrapText, "Soft wrap", tab.softWrap) {
                onTabChange(tab.copy(softWrap = !tab.softWrap))
            }
            ToggleIcon(LogSenseIcons.Density, "Compact view", tab.viewMode == ViewMode.COMPACT) {
                onTabChange(tab.copy(viewMode = if (tab.viewMode == ViewMode.STANDARD) ViewMode.COMPACT else ViewMode.STANDARD))
            }
            IconButton(onClick = { scope.launch { listState.scrollToItem(0) } }) {
                Icon(LogSenseIcons.ArrowUp, contentDescription = "Scroll to top")
            }
            IconButton(onClick = { scope.launch { if (filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex) } }) {
                Icon(LogSenseIcons.ArrowDown, contentDescription = "Scroll to bottom")
            }
        }

        LogList(filtered, listState, tab.viewMode, tab.softWrap, matcher, autoFollow = !tab.paused)
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagFilterChip(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var tagQuery by remember { mutableStateOf("") }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true; tagQuery = "" },
            label = { Text(selected ?: "Tag") },
            trailingIcon = { Icon(LogSenseIcons.ArrowDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OutlinedTextField(
                value = tagQuery,
                onValueChange = { tagQuery = it },
                placeholder = { Text("Filter tags") },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 200.dp),
            )
            DropdownMenuItem(text = { Text("All tags") }, onClick = { onSelect(null); expanded = false })
            tags.filter { it.contains(tagQuery, ignoreCase = true) }.forEach { tag ->
                DropdownMenuItem(text = { Text(tag) }, onClick = { onSelect(tag); expanded = false })
            }
        }
    }
}

@Composable
private fun LogList(
    filtered: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewMode: ViewMode,
    softWrap: Boolean,
    matcher: TextMatcher?,
    autoFollow: Boolean,
) {
    val scope = rememberCoroutineScope()
    val followTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(filtered.size, autoFollow) {
        if (autoFollow && followTail && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { entry -> LogRow(entry, viewMode, softWrap, matcher) }
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
private fun LogRow(entry: LogEntry, viewMode: ViewMode, softWrap: Boolean, matcher: TextMatcher?) {
    val levelColor = entry.level.color()
    val messageColor = if (entry.level.ordinal >= LogLevel.ERROR.ordinal) levelColor else MaterialTheme.colorScheme.onSurface
    val message = highlight(entry.message, matcher, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))

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
        if (viewMode == ViewMode.COMPACT) {
            MessageText(message, messageColor, softWrap, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
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
                MessageText(message, messageColor, softWrap, Modifier)
            }
        }
    }
}

@Composable
private fun MessageText(text: AnnotatedString, color: Color, softWrap: Boolean, modifier: Modifier) {
    // ponytail: soft-wrap off scrolls each row horizontally on its own (per-row scroll state);
    // a shared list-wide horizontal scrollbar would be nicer but isn't worth the wiring here.
    SelectionContainer(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            softWrap = softWrap,
            maxLines = if (softWrap) Int.MAX_VALUE else 1,
            modifier = if (softWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
