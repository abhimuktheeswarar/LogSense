package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.analytics.toMap
import com.msabhi.logsense.internal.data.EventEntity
import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher
import kotlinx.coroutines.launch
import org.json.JSONObject

private sealed interface EventListItem {
    val key: Any
    data class Header(val meta: SessionMeta, val ids: List<Long>) : EventListItem {
        override val key get() = "h:${meta.id}"
    }
    data class Row(val event: EventEntity) : EventListItem {
        override val key get() = event.id
    }
}

@Composable
internal fun EventsScreen(
    core: LogSenseCore,
    wide: Boolean,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
) {
    val dao = remember { core.database.eventDao() }
    val events by remember { dao.observeAll() }.collectAsState(initial = emptyList())
    val sessions by remember { core.database.sessionDao().observeAll() }.collectAsState(initial = emptyList())
    val startedAt = remember(sessions) { sessions.associate { it.id to it.startedAt } }
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) } // null = All
    var filterText by rememberSaveable { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(SearchQuery()) }
    val checked = remember { mutableStateListOf<Long>() }
    val pending = remember { mutableStateListOf<Long>() }
    var showDeleteAll by remember { mutableStateOf(false) }
    val selectionMode = checked.isNotEmpty()

    val tags = remember(events) { (core.config.analyticsTags + events.map { it.tag }).toSortedSet().toList() }

    val filtered = remember(events, selectedTag, filterText, pending.toList()) {
        events.filter { e ->
            e.id !in pending &&
                (selectedTag == null || e.tag == selectedTag) &&
                (
                    filterText.isEmpty() ||
                        e.name.contains(filterText, true) ||
                        e.paramsJson.contains(filterText, true) ||
                        e.tag.contains(filterText, true)
                    )
        }
    }
    val groups = remember(filtered, sessions, core.sessionId) {
        groupBySession(filtered, core.sessionId, { startedAt[it] ?: 0L }, { it.sessionId }, { it.timestamp })
    }
    val listItems = remember(groups) {
        buildList { groups.forEach { (m, rows) -> add(EventListItem.Header(m, rows.map { it.id })); rows.forEach { add(EventListItem.Row(it)) } } }
    }

    val matcher = remember(search) { if (search.isActive) TextMatcher.from(search) else null }
    val matchIds = remember(filtered, matcher) {
        if (matcher == null) emptyList()
        else filtered.filter { matcher.matches("${it.name} ${it.paramsJson}") }.map { it.id }
    }
    var matchPos by remember(matcher) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(matchPos, matchIds, listItems) {
        val id = matchIds.getOrNull(matchPos) ?: return@LaunchedEffect
        val idx = listItems.indexOfFirst { it is EventListItem.Row && it.event.id == id }
        if (idx >= 0) listState.scrollToItem(idx)
    }

    fun deleteWithUndo(ids: List<Long>, noun: String) {
        if (ids.isEmpty()) return
        pending.addAll(ids)
        val label = "${ids.size} $noun${if (ids.size == 1) "" else "s"} deleted"
        uiScope.launch {
            // Default duration with an actionLabel is Indefinite — force Short so it auto-dismisses
            // (and the delete commits on timeout).
            val result = snackbar.showSnackbar(label, actionLabel = "UNDO", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                pending.removeAll(ids.toSet())
            } else {
                core.scope.launch { dao.deleteIds(ids) } // ids stay hidden; the DB row is gone
            }
        }
    }

    fun shareSelected(asFile: Boolean) {
        val toExport = events.filter { it.id in checked }
        if (toExport.isEmpty()) return
        val json = EventExport.toJsonString(toExport)
        if (asFile) core.scope.launch { ShareUtil.shareJsonFile(context, "events", json) }
        else ShareUtil.shareText(context, "events", json)
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                if (selectionMode) {
                    SelectionBar(
                        count = checked.size,
                        onClose = { checked.clear() },
                        onExport = { asFile -> shareSelected(asFile) },
                        onDelete = { val ids = checked.toList(); checked.clear(); deleteWithUndo(ids, "event") },
                        onSelectAll = { checked.clear(); checked.addAll(filtered.map { it.id }) },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilledFilterField(filterText, { filterText = it }, "Filter events by name, param or tag", Modifier.weight(1f))
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(LogSenseIcons.Search, contentDescription = "Find", tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showDeleteAll = true }, enabled = events.any { it.id !in pending }) {
                            Icon(LogSenseIcons.Delete, contentDescription = "Delete all events")
                        }
                    }
                    if (searchOpen) {
                        SearchBar(
                            query = search,
                            onQueryChange = { search = it; matchPos = 0 },
                            matchCount = matchIds.size,
                            currentMatch = matchPos,
                            onPrev = { if (matchIds.isNotEmpty()) matchPos = (matchPos - 1 + matchIds.size) % matchIds.size },
                            onNext = { if (matchIds.isNotEmpty()) matchPos = (matchPos + 1) % matchIds.size },
                            onClose = { searchOpen = false; search = SearchQuery() },
                        )
                    }
                    if (tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectPill("All", selectedTag == null) { selectedTag = null }
                            tags.forEach { tag -> SelectPill(tag, selectedTag == tag) { selectedTag = tag } }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    EventEmpty()
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(listItems, key = { it.key }) { item ->
                            when (item) {
                                is EventListItem.Header -> SessionHeader(
                                    meta = item.meta,
                                    selectionMode = selectionMode,
                                    checked = item.ids.isNotEmpty() && item.ids.all { it in checked },
                                    onClick = {
                                        if (selectionMode) toggleAll(checked, item.ids)
                                    },
                                    onLongClick = { toggleAll(checked, item.ids) },
                                    onDeleteSession = { deleteWithUndo(item.ids, "event") },
                                    onShare = { asFile ->
                                        val json = EventExport.toJsonString(events.filter { it.id in item.ids })
                                        if (asFile) core.scope.launch { ShareUtil.shareJsonFile(context, "events", json) }
                                        else ShareUtil.shareText(context, "events", json)
                                    },
                                )
                                is EventListItem.Row -> {
                                    val event = item.event
                                    val row: @Composable () -> Unit = {
                                        EventRow(
                                            event = event,
                                            openSelected = wide && event.id == selectedId && !selectionMode,
                                            checked = event.id in checked,
                                            selectionMode = selectionMode,
                                            matcher = matcher,
                                            onClick = { if (selectionMode) toggle(checked, event.id) else onOpen(event.id) },
                                            onLongClick = { toggle(checked, event.id) },
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    if (selectionMode) row() else SwipeToDeleteRow(onDelete = { deleteWithUndo(listOf(event.id), "event") }) { row() }
                                }
                            }
                        }
                    }
                }
            }
            if (wide && selectedId != null && !selectionMode) {
                VerticalDivider()
                Box(Modifier.weight(1.2f)) { EventDetailPane(core, selectedId) }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))

        if (showDeleteAll) {
            DeleteAllDialog(
                kind = "event",
                reportCount = events.size,
                sessionCount = events.mapTo(mutableSetOf()) { it.sessionId }.size,
                onConfirm = { core.scope.launch { dao.clear() } },
                onDismiss = { showDeleteAll = false },
            )
        }
    }
}

/**
 * A share icon button whose tap opens an anchored dropdown with "Share as text" / "Share as file".
 * [fileLabel] names the file format (e.g. "JSON file", ".txt file").
 */
@Composable
internal fun ShareMenuButton(
    contentDescription: String,
    fileLabel: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = LocalContentColor.current,
    onText: () -> Unit,
    onFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(LogSenseIcons.Share, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Share as text") }, onClick = { open = false; onText() })
            DropdownMenuItem(text = { Text("Share as $fileLabel") }, onClick = { open = false; onFile() })
        }
    }
}

private fun toggle(list: MutableList<Long>, id: Long) {
    if (!list.remove(id)) list.add(id)
}

private fun toggleAll(list: MutableList<Long>, ids: List<Long>) {
    if (ids.all { it in list }) list.removeAll(ids.toSet()) else ids.forEach { if (it !in list) list.add(it) }
}

@Composable
private fun EventEmpty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(LogSenseIcons.Lines, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
            Text("No analytics events yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Configure analyticsTags in LogSenseConfig and fire an event.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(260.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(
    event: EventEntity,
    openSelected: Boolean,
    checked: Boolean,
    selectionMode: Boolean,
    matcher: TextMatcher?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val highlightColor = cs.primary.copy(alpha = 0.38f)
    val preview = remember(event.paramsJson) { paramsPreview(event.paramsJson, cs.primary) }
    val rowSelected = checked || openSelected
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (checked) Modifier.background(cs.primary.copy(alpha = 0.09f)) else if (rowSelected) Modifier.background(cs.surfaceContainer) else Modifier.background(cs.surface))
            .drawBehind {
                if (openSelected && !checked) drawRect(cs.primary, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            }
            .padding(start = if (openSelected && !checked) 13.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode) {
            Box(
                Modifier.padding(end = 12.dp, top = 2.dp).size(22.dp).clip(CircleShape)
                    .then(if (checked) Modifier.background(cs.primary) else Modifier.border(1.5.dp, cs.outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(LogSenseIcons.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(15.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(highlight(event.name, matcher, highlightColor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(event.timestamp.asDateTime(), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
                Text(event.tag, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
            }
            if (preview.isNotEmpty()) {
                Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** `key`=value, key2=value2 with keys tinted in [keyColor]. Empty when there are no params. */
private fun paramsPreview(paramsJson: String, keyColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val map = runCatching { JSONObject(paramsJson).toMap() }.getOrDefault(emptyMap())
    if (map.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(", ")
            pushStyle(SpanStyle(color = keyColor)); append(k); pop()
            append("=")
            append(v?.toString() ?: "null")
        }
    }
}

/** A filled, rounded filter field (plain text, no query tokens). Shared look with the Logs filter. */
@Composable
internal fun FilledFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
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
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
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

/** A simple selectable rounded pill (used for Event tag sub-tabs). */
@Composable
internal fun SelectPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (selected) Modifier.background(cs.secondaryContainer) else Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
    }
}
