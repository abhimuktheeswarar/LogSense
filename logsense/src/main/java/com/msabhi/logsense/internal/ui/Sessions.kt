package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.internal.data.EARLIER_SESSION_ID
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.color

/** One session's grouping metadata (derived from the rows currently shown for it). */
internal data class SessionMeta(
    val id: String,
    val isCurrent: Boolean,
    val startedAt: Long,
    val count: Int,
    val newestTs: Long,
    val oldestTs: Long,
)

/** Groups [items] by their session and orders groups current-first, then newest run first. */
internal fun <T> groupBySession(
    items: List<T>,
    currentSessionId: String,
    startedAtOf: (String) -> Long,
    sessionOf: (T) -> String,
    timeOf: (T) -> Long,
): List<Pair<SessionMeta, List<T>>> {
    val grouped = LinkedHashMap<String, MutableList<T>>()
    items.forEach { grouped.getOrPut(sessionOf(it)) { mutableListOf() }.add(it) }
    return grouped.map { (sid, rows) ->
        val times = rows.map(timeOf)
        SessionMeta(
            id = sid,
            isCurrent = sid == currentSessionId,
            startedAt = startedAtOf(sid),
            count = rows.size,
            newestTs = times.max(),
            oldestTs = times.min(),
        ) to rows
    }.sortedByDescending { (m, _) -> if (m.isCurrent) Long.MAX_VALUE else m.startedAt }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionHeader(
    meta: SessionMeta,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteSession: () -> Unit,
    onShare: ((asFile: Boolean) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (checked) Modifier.background(cs.primary.copy(alpha = 0.09f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp, top = 15.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            SelectCheckbox(checked)
            Spacer(Modifier.width(10.dp))
        }
        SessionPill(meta.isCurrent)
        Spacer(Modifier.width(9.dp))
        Text(
            text = sessionHeaderText(meta, cs.onSurface, cs.onSurfaceVariant),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (!selectionMode) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(LogSenseIcons.MoreVert, contentDescription = "Session options", tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onShare != null) {
                        DropdownMenuItem(
                            text = { Text("Share as text") },
                            leadingIcon = { Icon(LogSenseIcons.Share, contentDescription = null) },
                            onClick = { menu = false; onShare(false) },
                        )
                        DropdownMenuItem(
                            text = { Text("Share as JSON file") },
                            leadingIcon = { Icon(LogSenseIcons.Share, contentDescription = null) },
                            onClick = { menu = false; onShare(true) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete session") },
                        leadingIcon = { Icon(LogSenseIcons.Delete, contentDescription = null) },
                        onClick = { menu = false; onDeleteSession() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionPill(current: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = if (current) {
            Modifier.clip(RoundedCornerShape(5.dp)).background(cs.primary)
        } else {
            Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, cs.outline, RoundedCornerShape(5.dp))
        }.padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (current) "CURRENT" else "PREVIOUS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
            ),
            color = if (current) cs.onPrimary else cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectCheckbox(checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (checked) Modifier.background(cs.primary)
                else Modifier.border(2.dp, cs.outline, RoundedCornerShape(6.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(LogSenseIcons.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/** "This run · started 14:06 · 4" / "Today · 09:12 – 09:41 · 2" / "Earlier · 3". */
private fun sessionHeaderText(meta: SessionMeta, onSurface: Color, onVar: Color) = buildAnnotatedString {
    val primary = when {
        meta.isCurrent -> "This run"
        meta.id == EARLIER_SESSION_ID || meta.startedAt == 0L -> "Earlier"
        else -> (if (meta.startedAt > 0) meta.startedAt else meta.newestTs).asDayLabel()
    }
    withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.Medium)) { append(primary) }
    val secondary = when {
        meta.isCurrent -> " · started ${meta.startedAt.asHourMinute()} · ${meta.count}"
        meta.id == EARLIER_SESSION_ID || meta.startedAt == 0L -> " · ${meta.count}"
        meta.count > 1 -> " · ${meta.oldestTs.asHourMinute()} – ${meta.newestTs.asHourMinute()} · ${meta.count}"
        else -> " · ${meta.newestTs.asHourMinute()} · ${meta.count}"
    }
    withStyle(SpanStyle(color = onVar, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) { append(secondary) }
}

/** Wraps [content] in a swipe-to-delete (end→start) with a red delete background. */
@Composable
internal fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    // The severity-palette error red (matches the design's --e), not the Material error role.
    val error = LogLevel.ERROR.color()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize().background(error).padding(end = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text("Delete", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Icon(LogSenseIcons.Delete, contentDescription = null, tint = Color.White)
            }
        },
        content = { content() },
    )
}

/** Contextual selection bar (secondary container) shown in place of the filter/tabs when selecting. */
@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onExport: ((asFile: Boolean) -> Unit)?,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().background(cs.secondaryContainer).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(LogSenseIcons.Close, contentDescription = "Exit selection", tint = cs.onSecondaryContainer)
        }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (onExport != null) {
            ShareMenuButton(
                contentDescription = "Export selected",
                fileLabel = "JSON file",
                tint = cs.onSecondaryContainer,
                onText = { onExport(false) },
                onFile = { onExport(true) },
            )
        }
        IconButton(onClick = onDelete) {
            Icon(LogSenseIcons.Delete, contentDescription = "Delete selected", tint = cs.onSecondaryContainer)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(LogSenseIcons.MoreVert, contentDescription = "More", tint = cs.onSecondaryContainer)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Select all") }, onClick = { menu = false; onSelectAll() })
            }
        }
    }
}

@Composable
internal fun DeleteAllDialog(
    kind: String, // "event" | "crash"
    reportCount: Int,
    sessionCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val plural = if (kind == "crash") "crash reports" else "events"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all $plural?") },
        text = {
            Text(
                "This removes $reportCount ${if (reportCount == 1) kind else "${kind}s"} across " +
                    "$sessionCount ${if (sessionCount == 1) "session" else "sessions"}, including previous runs. " +
                    "This can't be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("Delete all", color = cs.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
