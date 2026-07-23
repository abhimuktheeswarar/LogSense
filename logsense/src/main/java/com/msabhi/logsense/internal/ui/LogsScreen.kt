package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.logs.LogQuery
import com.msabhi.logsense.internal.logs.LogScroll
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
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (on) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
                    )
                    .clickable { onSelect(i) }
                    .padding(start = 13.dp, end = if (on && tabs.size > 1) 6.dp else 13.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (on && tabs.size > 1) {
                    Icon(
                        LogSenseIcons.Close,
                        contentDescription = "Close tab",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 5.dp).size(16.dp).clickable { onClose(i) },
                    )
                }
            }
        }
        // dashed "add" pill
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .clickable(onClick = onAdd)
                .padding(7.dp),
        ) { Icon(LogSenseIcons.Add, contentDescription = "New tab", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun LogTabContent(core: LogSenseCore, tab: LogTab, onTabChange: (LogTab) -> Unit) {
    val liveEntries by core.buffer.snapshot.collectAsState()
    // Per-tab pause freezes this tab's view while the shared buffer keeps filling.
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }
    LaunchedEffect(tab.paused) { frozen = if (tab.paused) core.buffer.currentSnapshot() else null }
    val entries = frozen ?: liveEntries
    val bufferedWhilePaused = if (tab.paused) (liveEntries.size - (frozen?.size ?: 0)).coerceAtLeast(0) else 0

    val predicate = remember(tab.filter) { LogQuery.compile(tab.filter) }
    val filtered = remember(entries, predicate) { entries.filter(predicate) }
    val groups = remember(filtered) { groupRuns(filtered) }
    val items = remember(groups) { flatten(groups) }
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
    val logScroll by core.prefs.logScroll.collectAsState()

    LaunchedEffect(matchPos, matchIndices, items, groups, logScroll) {
        val entry = matchIndices.getOrNull(matchPos)?.let { filtered[it] } ?: return@LaunchedEffect
        // Scroll-entry renders one item per group, so target the group; other modes target the line.
        val row = if (logScroll == LogScroll.ENTRY) {
            groups.indexOfFirst { g -> g.lines.any { it.entry.id == entry.id } }
        } else {
            items.indexOfFirst { it is LogItem.Line && it.entry.id == entry.id }
        }
        if (row >= 0) listState.scrollToItem(row)
    }

    fun update(transform: LogFilter.() -> LogFilter) = onTabChange(tab.copy(filter = tab.filter.transform()))

    Column(Modifier.fillMaxSize()) {
        // filter row: field + min-level + overflow
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterField(
                value = tab.filter.query,
                onValueChange = { update { copy(query = it) } },
                modifier = Modifier.weight(1f),
            )
            MinLevelChip(tab.filter.minLevel) { update { copy(minLevel = it) } }
            LogsOverflowMenu(
                tab = tab,
                tags = tags,
                scroll = logScroll,
                onSetScroll = { core.prefs.setLogScroll(it) },
                onToggleFind = { searchOpen = !searchOpen },
                onInsertTag = { tagValue -> update { copy(query = withTag(query, tagValue)) } },
                onTabChange = onTabChange,
                onRestart = { core.restartReader() },
                onShareText = { ShareUtil.shareLogText(context, filtered) },
                onShareFile = { core.scope.launch { ShareUtil.shareLogFile(context, filtered) } },
                onClear = { core.scope.launch { core.buffer.clear() } },
            )
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

        if (tab.paused) FrozenBanner(bufferedWhilePaused)

        when {
            entries.isEmpty() -> EmptyLogs(core.appName)
            filtered.isEmpty() -> NoMatches(tab.filter, onClear = { update { LogFilter() } })
            else -> LogList(groups, items, listState, tab.viewMode, logScroll, matcher, autoFollow = !tab.paused)
        }
    }
}

/* ---------------- tag grouping ---------------- */

private sealed interface LogItem {
    val key: Any
    data class Band(val tag: String, val level: LogLevel, val pid: Int, val firstId: Long) : LogItem {
        override val key get() = "b$firstId"
    }
    data class Line(val entry: LogEntry) : LogItem {
        override val key get() = entry.id
    }
}

/** A same-tag run: the band header plus every consecutive line under it. */
private data class LogGroup(val band: LogItem.Band, val lines: List<LogItem.Line>) {
    val key: Any get() = band.key
}

/** Collapses runs of same-tag rows into groups (band header + its lines). */
private fun groupRuns(filtered: List<LogEntry>): List<LogGroup> {
    val out = ArrayList<LogGroup>()
    var i = 0
    while (i < filtered.size) {
        val first = filtered[i]
        val lines = ArrayList<LogItem.Line>()
        while (i < filtered.size && filtered[i].tag == first.tag) {
            lines.add(LogItem.Line(filtered[i])); i++
        }
        out.add(LogGroup(LogItem.Band(first.tag, first.level, first.pid, first.id), lines))
    }
    return out
}

/** Flattens groups back to a band/line item stream (used by every mode except Scroll-entry). */
private fun flatten(groups: List<LogGroup>): List<LogItem> =
    ArrayList<LogItem>().apply { groups.forEach { g -> add(g.band); addAll(g.lines) } }

/** `tag:value` (quoted if it has spaces), appended to the current query. */
private fun withTag(query: String, tag: String): String {
    val token = if (tag.any { it.isWhitespace() }) "tag:\"$tag\"" else "tag:$tag"
    return if (query.isBlank()) token else "${query.trimEnd()} $token"
}

/* ---------------- controls ---------------- */

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LogSenseIcons.FilterList, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Filter — tag:foo -tag:bar level:E",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = cs.onSurface,
                ),
                visualTransformation = FilterQueryTransformation(cs.primary, cs.error),
                cursorBrush = SolidColor(cs.primary),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(LogSenseIcons.Close, contentDescription = "Clear filter", tint = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MinLevelChip(minLevel: LogLevel, onSelect: (LogLevel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, cs.outline, RoundedCornerShape(9.dp))
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${minLevel.letter}+",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
            )
            Icon(LogSenseIcons.ArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LogLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text("${level.letter}  ${level.name.lowercase().replaceFirstChar { it.uppercase() }}+") },
                    onClick = { onSelect(level); expanded = false },
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LogsOverflowMenu(
    tab: LogTab,
    tags: List<String>,
    scroll: LogScroll,
    onSetScroll: (LogScroll) -> Unit,
    onToggleFind: () -> Unit,
    onInsertTag: (String) -> Unit,
    onTabChange: (LogTab) -> Unit,
    onRestart: () -> Unit,
    onShareText: () -> Unit,
    onShareFile: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var tagOpen by remember { mutableStateOf(false) }
    var tagQuery by remember { mutableStateOf("") }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(LogSenseIcons.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Find") },
                leadingIcon = { Icon(LogSenseIcons.Search, contentDescription = null) },
                onClick = { menuOpen = false; onToggleFind() },
            )
            DropdownMenuItem(
                text = { Text("Filter by tag…") },
                leadingIcon = { Icon(LogSenseIcons.FilterList, contentDescription = null) },
                onClick = { menuOpen = false; tagQuery = ""; tagOpen = true },
            )
            DropdownMenuItem(
                text = { Text(if (tab.paused) "Resume this tab" else "Freeze this tab") },
                leadingIcon = { Icon(if (tab.paused) LogSenseIcons.Play else LogSenseIcons.Pause, contentDescription = null) },
                onClick = { menuOpen = false; onTabChange(tab.copy(paused = !tab.paused)) },
            )
            DropdownMenuItem(
                text = { Text("Compact view") },
                trailingIcon = { if (tab.viewMode == ViewMode.COMPACT) Icon(LogSenseIcons.Check, contentDescription = null) },
                onClick = { onTabChange(tab.copy(viewMode = if (tab.viewMode == ViewMode.STANDARD) ViewMode.COMPACT else ViewMode.STANDARD)) },
            )
            HorizontalDivider()
            // Long-line horizontal scrolling (mutually exclusive; tap to switch, menu stays open).
            listOf(
                LogScroll.WRAP to "Wrap lines",
                LogScroll.LINE to "Scroll line",
                LogScroll.ENTRY to "Scroll entry",
                LogScroll.PAN to "Pan view",
            ).forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (scroll == mode) Icon(LogSenseIcons.Check, contentDescription = null) },
                    onClick = { onSetScroll(mode) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Restart logcat") },
                leadingIcon = { Icon(LogSenseIcons.Restart, contentDescription = null) },
                onClick = { menuOpen = false; onRestart() },
            )
            DropdownMenuItem(
                text = { Text("Share as text") },
                leadingIcon = { Icon(LogSenseIcons.Share, contentDescription = null) },
                onClick = { menuOpen = false; onShareText() },
            )
            DropdownMenuItem(
                text = { Text("Share as .txt file") },
                leadingIcon = { Icon(LogSenseIcons.Share, contentDescription = null) },
                onClick = { menuOpen = false; onShareFile() },
            )
            DropdownMenuItem(
                text = { Text("Clear") },
                leadingIcon = { Icon(LogSenseIcons.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onClear() },
            )
        }
    }

    // Tag picker as a bottom sheet: a pinned search field over a scrollable tag list. (A LazyColumn
    // can't live inside a DropdownMenu — the menu measures content by intrinsic width, which a
    // SubcomposeLayout/lazy list can't provide — so this is a sheet, not a dropdown.)
    if (tagOpen) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { tagOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    placeholder = { Text("Filter tags") },
                    leadingIcon = { Icon(LogSenseIcons.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val shown = tags.filter { it.contains(tagQuery, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(shown, key = { it }) { tag ->
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInsertTag(tag); tagOpen = false }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- banners & empty states ---------------- */

@Composable
private fun FrozenBanner(buffered: Int) {
    val warn = LogLevel.WARN.color()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(warn.copy(alpha = 0.14f))
            .border(1.dp, warn.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LogSenseIcons.Pause, contentDescription = null, tint = warn, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Frozen — this tab is paused",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (buffered > 0) {
            Text("+$buffered buffered", style = MaterialTheme.typography.labelMedium, color = warn, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyLogs(appName: String) {
    EmptyState(
        icon = LogSenseIcons.Lines,
        title = "Waiting for the first line…",
        body = "Connected to logcat. Interact with $appName to see its logs stream in here, newest at the bottom.",
    )
}

@Composable
private fun NoMatches(filter: LogFilter, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyStateInner(
            icon = LogSenseIcons.Search,
            title = "Nothing matches this filter",
            body = "No captured lines match ${filter.query.ifBlank { "level ${filter.minLevel.letter}+" }}. The stream is still recording.",
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .clickable(onClick = onClear)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Clear filter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyStateInner(icon, title, body)
    }
}

@Composable
private fun EmptyStateInner(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 260.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/* ---------------- list & rows ---------------- */

@Composable
private fun LogList(
    groups: List<LogGroup>,
    items: List<LogItem>,
    listState: LazyListState,
    viewMode: ViewMode,
    scroll: LogScroll,
    matcher: TextMatcher?,
    autoFollow: Boolean,
) {
    val scope = rememberCoroutineScope()
    // Scroll-entry renders one item per tag group; every other mode renders a flat band/line stream.
    val entry = scroll == LogScroll.ENTRY
    val count = if (entry) groups.size else items.size
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    val followTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(count, autoFollow) {
        if (autoFollow && followTail && count > 0) listState.scrollToItem(count - 1)
    }

    // PAN mode: the whole list pans left/right as one (a single shared horizontal scroll), so a long
    // line is read by swiping the entire view. Other modes lay out at viewport width; ENTRY scrolls
    // each tag section together, LINE scrolls each row's message, WRAP wraps.
    val hScroll = rememberScrollState()
    val pan = scroll == LogScroll.PAN
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = if (pan) Modifier.fillMaxHeight().horizontalScroll(hScroll) else Modifier.fillMaxSize(),
        ) {
            if (entry) {
                items(groups, key = { it.key }) { group -> LogGroupItem(group, viewMode, matcher) }
            } else {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        // Header spans the viewport (with a divider) except in PAN, where it pans with the rows.
                        is LogItem.Band -> TagBand(item, fillWidth = !pan)
                        is LogItem.Line -> LogRow(item.entry, viewMode, scroll, matcher)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!atTop && count > 0) {
                LogFab(LogSenseIcons.ArrowUp, "Scroll to top", primary = false) { scope.launch { listState.scrollToItem(0) } }
            }
            if (!followTail && count > 0) {
                LogFab(LogSenseIcons.ArrowDown, "Jump to latest", primary = true) { scope.launch { listState.scrollToItem(count - 1) } }
            }
        }
    }
}

/**
 * A whole tag section for **Scroll entry** mode: a fixed full-width header over the section's lines,
 * which share one horizontal scroll so swiping any line pans the entire section together (each
 * section independent of the others).
 */
@Composable
private fun LogGroupItem(group: LogGroup, viewMode: ViewMode, matcher: TextMatcher?) {
    val hs = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        TagBand(group.band, fillWidth = true)
        Column(Modifier.horizontalScroll(hs)) {
            // PAN-style rows: wrap-content, single un-wrapped line — the section's shared scroll pans them.
            group.lines.forEach { line -> LogRow(line.entry, viewMode, LogScroll.PAN, matcher) }
        }
    }
}

@Composable
internal fun LogFab(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (primary) cs.primaryContainer else cs.surfaceContainerHighest
    val fg = if (primary) cs.onPrimaryContainer else cs.onSurfaceVariant
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = fg) }
}

@Composable
private fun TagBand(band: LogItem.Band, fillWidth: Boolean) {
    Row(
        // Wrap-content (no flexible divider) when the header pans with the rows (PAN mode).
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = band.tag,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            color = colorForLevel(band.level),
        )
        Spacer(Modifier.width(8.dp))
        if (fillWidth) {
            Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "pid ${band.pid}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun colorForLevel(level: LogLevel): Color = level.color()

@Composable
private fun LogRow(entry: LogEntry, viewMode: ViewMode, scroll: LogScroll, matcher: TextMatcher?) {
    val cs = MaterialTheme.colorScheme
    val levelColor = entry.level.color()
    val isErr = entry.level == LogLevel.ERROR
    val isFatal = entry.level == LogLevel.FATAL
    val rowBg = when {
        isFatal -> levelColor.copy(alpha = 0.09f)
        isErr -> levelColor.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val msgColor = when {
        isErr || isFatal -> levelColor
        else -> cs.onSurface
    }
    val message = highlight(entry.message, matcher, cs.primary.copy(alpha = 0.38f))
    val compact = viewMode == ViewMode.COMPACT
    val vPad = if (compact) 1.5.dp else 3.dp

    // WRAP/LINE occupy the viewport (body takes the remaining width). PAN is wrap-content so the
    // un-wrapped message defines the row's natural width (the list — or the enclosing Scroll-entry
    // section — provides the horizontal scroll). Scroll-entry rows are rendered in PAN layout.
    val fillWidth = scroll == LogScroll.WRAP || scroll == LogScroll.LINE

    Row(
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .background(rowBg)
            .padding(horizontal = 14.dp, vertical = vPad),
        verticalAlignment = Alignment.Top,
    ) {
        // gutter: stripe + level letter
        Box(Modifier.size(width = 3.dp, height = 15.dp).clip(RoundedCornerShape(2.dp)).background(levelColor))
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.level.letter.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = levelColor,
        )
        Spacer(Modifier.width(10.dp))
        Column(if (fillWidth) Modifier.weight(1f) else Modifier) {
            if (compact) {
                MessageText(prefix = entry.timeMs.asShortTime() + "  ", text = message, color = msgColor, scroll = scroll)
            } else {
                Text(
                    text = entry.timeMs.asTime(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                )
                MessageText(prefix = null, text = message, color = msgColor, scroll = scroll)
            }
        }
    }
}

@Composable
private fun MessageText(prefix: String?, text: AnnotatedString, color: Color, scroll: LogScroll) {
    val body = if (prefix == null) text else AnnotatedString(prefix) + text
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val wrap = scroll == LogScroll.WRAP
    // LINE mode gives each row's message its own horizontal scroll (gutter/timestamp stay put).
    val lineScroll = if (scroll == LogScroll.LINE) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    SelectionContainer(lineScroll) {
        Text(
            text = body,
            style = style,
            color = color,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
        )
    }
}
