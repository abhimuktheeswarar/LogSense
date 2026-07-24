package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.color
import kotlinx.coroutines.launch

private sealed interface CrashListItem {
    val key: Any
    data class Header(val meta: SessionMeta, val ids: List<Long>) : CrashListItem {
        override val key get() = "h:${meta.id}"
    }
    data class Row(val crash: CrashEntity) : CrashListItem {
        override val key get() = crash.id
    }
}

@Composable
internal fun CrashesScreen(
    core: LogSenseCore,
    wide: Boolean,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
) {
    val dao = remember { core.database.crashDao() }
    val crashes by remember { dao.observeAll() }.collectAsState(initial = emptyList())
    val sessions by remember { core.database.sessionDao().observeAll() }.collectAsState(initial = emptyList())
    val startedAt = remember(sessions) { sessions.associate { it.id to it.startedAt } }
    val uiScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val checked = remember { mutableStateListOf<Long>() }
    val pending = remember { mutableStateListOf<Long>() }
    var showDeleteAll by remember { mutableStateOf(false) }
    var filterText by rememberSaveable { mutableStateOf("") }
    val selectionMode = checked.isNotEmpty()

    val visible = remember(crashes, pending.toList()) { crashes.filter { it.id !in pending } }
    val filtered = remember(visible, filterText) {
        if (filterText.isBlank()) {
            visible
        } else {
            visible.filter { c ->
                c.type.contains(filterText, true) ||
                    c.exceptionClass?.contains(filterText, true) == true ||
                    c.message?.contains(filterText, true) == true ||
                    c.threadName?.contains(filterText, true) == true ||
                    c.stacktrace.contains(filterText, true)
            }
        }
    }
    val groups = remember(filtered, sessions, core.sessionId) {
        groupBySession(filtered, core.sessionId, { startedAt[it] ?: 0L }, { it.sessionId }, { it.timestamp })
    }
    val listItems = remember(groups) {
        buildList { groups.forEach { (m, rows) -> add(CrashListItem.Header(m, rows.map { it.id })); rows.forEach { add(CrashListItem.Row(it)) } } }
    }

    fun deleteWithUndo(ids: List<Long>) {
        if (ids.isEmpty()) return
        pending.addAll(ids)
        val label = "${ids.size} crash report${if (ids.size == 1) "" else "s"} deleted"
        uiScope.launch {
            // Default duration with an actionLabel is Indefinite — force Short so it auto-dismisses.
            val result = snackbar.showSnackbar(label, actionLabel = "UNDO", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) pending.removeAll(ids.toSet())
            else core.scope.launch { dao.deleteIds(ids) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                if (selectionMode) {
                    SelectionBar(
                        count = checked.size,
                        onClose = { checked.clear() },
                        onExport = null,
                        onDelete = { val ids = checked.toList(); checked.clear(); deleteWithUndo(ids) },
                        onSelectAll = { checked.clear(); checked.addAll(filtered.map { it.id }) },
                    )
                } else if (visible.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilledFilterField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            placeholder = "Filter crashes by type, class or message",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showDeleteAll = true }, enabled = visible.isNotEmpty()) {
                            Icon(LogSenseIcons.Delete, contentDescription = "Delete all crashes")
                        }
                    }
                }

                if (visible.isEmpty()) {
                    CrashEmpty()
                } else if (filtered.isEmpty()) {
                    CrashNoMatches(filterText) { filterText = "" }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(listItems, key = { it.key }) { item ->
                            when (item) {
                                is CrashListItem.Header -> SessionHeader(
                                    meta = item.meta,
                                    selectionMode = selectionMode,
                                    checked = item.ids.isNotEmpty() && item.ids.all { it in checked },
                                    onClick = { if (selectionMode) toggleAll(checked, item.ids) },
                                    onLongClick = { toggleAll(checked, item.ids) },
                                    onDeleteSession = { deleteWithUndo(item.ids) },
                                )
                                is CrashListItem.Row -> {
                                    val crash = item.crash
                                    val row: @Composable () -> Unit = {
                                        CrashRow(
                                            crash = crash,
                                            openSelected = wide && crash.id == selectedId && !selectionMode,
                                            checked = crash.id in checked,
                                            selectionMode = selectionMode,
                                            onClick = { if (selectionMode) toggle(checked, crash.id) else onOpen(crash.id) },
                                            onLongClick = { toggle(checked, crash.id) },
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    if (selectionMode) row() else SwipeToDeleteRow(onDelete = { deleteWithUndo(listOf(crash.id)) }) { row() }
                                }
                            }
                        }
                    }
                }
            }
            if (wide && selectedId != null && !selectionMode) {
                VerticalDivider()
                Box(Modifier.weight(1.2f)) { CrashDetailPane(core, selectedId) }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))

        if (showDeleteAll) {
            DeleteAllDialog(
                kind = "crash",
                reportCount = crashes.size,
                sessionCount = crashes.mapTo(mutableSetOf()) { it.sessionId }.size,
                onConfirm = { core.scope.launch { dao.clear() } },
                onDismiss = { showDeleteAll = false },
            )
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
private fun CrashEmpty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No crashes captured. That's a good thing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun CrashNoMatches(query: String, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "No crashes match \"$query\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.size(14.dp))
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
internal fun CrashTypeBadge(type: String) {
    val color = when (type) {
        "ANR" -> LogLevel.WARN.color()
        "NATIVE" -> LogLevel.FATAL.color()
        else -> LogLevel.ERROR.color()
    }
    Text(
        text = type,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrashRow(
    crash: CrashEntity,
    openSelected: Boolean,
    checked: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (checked) Modifier.background(cs.primary.copy(alpha = 0.09f)) else if (openSelected) Modifier.background(cs.surfaceContainer) else Modifier.background(cs.surface))
            .drawBehind {
                if (openSelected && !checked) drawRect(cs.primary, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
            }
            .padding(start = if (openSelected && !checked) 13.dp else 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode) {
            Box(
                Modifier.padding(end = 12.dp, top = 2.dp).size(22.dp).clip(CircleShape)
                    .then(if (checked) Modifier.background(cs.primary) else Modifier.border(1.5.dp, cs.outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(LogSenseIcons.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(15.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrashTypeBadge(crash.type)
                Spacer(Modifier.width(9.dp))
                Text(
                    crash.exceptionClass?.substringAfterLast('.') ?: crash.type,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            crash.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            }
            Text(
                crash.timestamp.asDateTime() + (crash.threadName?.let { "  ·  thread: $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
            )
        }
    }
}
