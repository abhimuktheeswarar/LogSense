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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.signals.SignalCategory
import com.msabhi.logsense.internal.signals.SignalHit
import com.msabhi.logsense.internal.signals.triage
import com.msabhi.logsense.internal.ui.theme.color
import kotlinx.coroutines.launch

/**
 * Everything worth looking at in this run, newest first: catalog matches from the live stream, and
 * the crash, ANR or native fault ingested at launch — i.e. whatever ended the previous run.
 *
 * A matched line jumps into the Logs tab. A crash opens its report. Hits live with the log buffer,
 * so this list resets when the stream does; the Crashes tab keeps the durable history.
 */
@Composable
internal fun SignalsScreen(
    core: LogSenseCore,
    wide: Boolean,
    selectedCrashId: Long?,
    onOpenCrash: (Long) -> Unit,
    onJumpToLogs: () -> Unit,
) {
    val hits by core.signals.hits.collectAsState()
    val muted by core.prefs.mutedSignals.collectAsState()
    val crashes by remember { core.database.crashDao().observeAll() }.collectAsState(initial = emptyList())
    val launchCrashIds by core.launchCrashIds.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf<SignalCategory?>(null) } // null = All

    val rows = remember(hits, crashes, launchCrashIds, muted, core.sessionId) {
        val fromCrashes = crashes
            .filter { it.sessionId == core.sessionId || it.id in launchCrashIds }
            .map { SignalRow.Crash(it) }
        val fromHits = hits.filterNot { it.signal.id in muted }.map { SignalRow.Hit(it) }
        collapseRepeats((fromCrashes + fromHits).sortedByDescending { it.timeMs })
    }
    // Counts are occurrences, not rows — a row folded to "×13" still contributes 13.
    val counts = remember(rows) { rows.groupBy { it.category }.mapValues { (_, r) -> r.sumOf { it.occurrences } } }
    val total = remember(rows) { rows.sumOf { it.occurrences } }
    val shown = remember(rows, category) { rows.filter { category == null || it.category == category } }

    fun mute(hit: SignalHit) {
        core.prefs.setSignalMuted(hit.signal.id, true)
        uiScope.launch {
            val result = snackbar.showSnackbar(
                message = "${hit.signal.label} muted",
                actionLabel = "UNDO",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) core.prefs.setSignalMuted(hit.signal.id, false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                if (rows.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectPill("All $total", category == null) { category = null }
                        SignalCategory.entries.forEach { entry ->
                            val count = counts[entry] ?: return@forEach
                            SelectPill("${entry.label} $count", category == entry) { category = entry }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    SignalsEmpty(hasAny = rows.isNotEmpty())
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.key }) { row ->
                            when (row) {
                                // Swipe to mute, matching the swipe-to-delete on Events and Crashes.
                                // Warn-coloured, not error: muting is reversible and hides nothing
                                // that already happened.
                                is SignalRow.Hit -> SwipeToDeleteRow(
                                    onDelete = { mute(row.hit) },
                                    label = "Mute",
                                    icon = LogSenseIcons.Close,
                                    background = LogLevel.WARN.color(),
                                ) {
                                    HitRow(
                                        hit = row.hit,
                                        repeats = row.repeats,
                                        onClick = {
                                            val id = row.hit.entryId ?: return@HitRow
                                            core.jumpToLogId.value = id
                                            onJumpToLogs()
                                        },
                                    )
                                }

                                is SignalRow.Crash -> CrashSignalRow(
                                    crash = row.crash,
                                    appPackage = core.appContext.packageName,
                                    selected = wide && row.crash.id == selectedCrashId,
                                    onClick = { onOpenCrash(row.crash.id) },
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            if (wide && selectedCrashId != null) {
                VerticalDivider()
                Box(Modifier.weight(1.2f)) { CrashDetailPane(core, selectedCrashId) }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

/** What the Signals tab shows without being opened: how many, and how bad the worst one is. */
internal data class SignalSummary(val count: Int, val worst: SignalCategory?)

/**
 * Summarises what this run is carrying, for the Signals tab. Shares its definition of "counts" with
 * the screen's own `All N` pill so the two can never disagree.
 */
@Composable
internal fun rememberSignalSummary(core: LogSenseCore): SignalSummary {
    val hits by core.signals.hits.collectAsState()
    val muted by core.prefs.mutedSignals.collectAsState()
    val crashes by remember { core.database.crashDao().observeAll() }.collectAsState(initial = emptyList())
    val launchCrashIds by core.launchCrashIds.collectAsState()
    return remember(hits, muted, crashes, launchCrashIds, core.sessionId) {
        val liveHits = hits.filter { it.signal.id !in muted }
        val ownCrashes = crashes.filter { it.sessionId == core.sessionId || it.id in launchCrashIds }
        val categories = liveHits.map { it.signal.category } + ownCrashes.map { crashCategory(it.type) }
        SignalSummary(
            count = liveHits.size + ownCrashes.size,
            worst = categories.minByOrNull { it.severity },
        )
    }
}

private fun crashCategory(type: String) = when (type) {
    "ANR" -> SignalCategory.ANR
    "NATIVE" -> SignalCategory.NATIVE
    else -> SignalCategory.CRASH
}

/* ---------------- rows ---------------- */

/**
 * Folds a run of the same signal into one row carrying a count. A single StrictMode policy or a
 * stuttering frame fires over and over; thirteen identical rows say nothing that "×13" doesn't.
 * The kept hit is the newest of the run, so jumping lands on the most recent occurrence.
 */
internal fun collapseRepeats(rows: List<SignalRow>): List<SignalRow> {
    val out = ArrayList<SignalRow>(rows.size)
    rows.forEach { row ->
        val last = out.lastOrNull()
        if (row is SignalRow.Hit && last is SignalRow.Hit && last.hit.signal.id == row.hit.signal.id) {
            out[out.lastIndex] = last.copy(repeats = last.repeats + 1)
        } else {
            out.add(row)
        }
    }
    return out
}

internal sealed interface SignalRow {
    val key: Any
    val timeMs: Long
    val category: SignalCategory

    /** How many occurrences this row stands for — see [collapseRepeats]. */
    val occurrences: Int

    data class Hit(val hit: SignalHit, val repeats: Int = 1) : SignalRow {
        override val key get() = "h:${hit.signal.id}:${hit.entryId}:${hit.timeMs}"
        override val timeMs get() = hit.timeMs
        override val category get() = hit.signal.category
        override val occurrences get() = repeats
    }

    data class Crash(val crash: CrashEntity) : SignalRow {
        override val key get() = "c:${crash.id}"
        override val timeMs get() = crash.timestamp
        override val occurrences get() = 1
        override val category get() = crashCategory(crash.type)
    }
}

@Composable
private fun HitRow(hit: SignalHit, repeats: Int, onClick: () -> Unit) {
    val jumpable = hit.entryId != null
    SignalRowShell(
        color = hit.signal.category.color(),
        title = if (repeats > 1) "${hit.signal.label}  ×$repeats" else hit.signal.label,
        timeMs = hit.timeMs,
        subtitle = "${hit.tag}: ${hit.preview}",
        // A reported signal has no matched line, so there is nowhere to jump — don't pretend.
        onClick = if (jumpable) onClick else null,
    )
}

@Composable
private fun CrashSignalRow(crash: CrashEntity, appPackage: String, selected: Boolean, onClick: () -> Unit) {
    val read = remember(crash.id) { triage(crash, appPackage) }
    val title = crash.exceptionClass?.substringAfterLast('.')?.substringAfterLast('$')
        ?: when (crash.type) {
            "ANR" -> "Application not responding"
            "NATIVE" -> "Native fault"
            else -> "Crash"
        }
    SignalRowShell(
        color = MaterialTheme.colorScheme.error,
        title = title,
        timeMs = crash.timestamp,
        subtitle = read.appFrame ?: crash.message?.takeIf { it.isNotBlank() } ?: read.cause,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SignalRowShell(
    color: androidx.compose.ui.graphics.Color,
    title: String,
    timeMs: Long,
    subtitle: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (selected) cs.surfaceContainer else cs.surface)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = timeMs.asTime(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun SignalsEmpty(hasAny: Boolean) {
    EmptyState(
        if (hasAny) {
            "No signals in this category."
        } else {
            "Nothing flagged yet. Crashes, ANRs, native faults, memory pressure and lifecycle " +
                "events are watched for automatically — add your own with customSignals."
        },
    )
}
