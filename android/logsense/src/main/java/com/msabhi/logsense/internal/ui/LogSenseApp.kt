package com.msabhi.logsense.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.R
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.LogSenseTheme
import com.msabhi.logsense.internal.ui.theme.color
import com.msabhi.logsense.internal.ui.theme.liveColor
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface Detail {
    val id: Long

    data class Event(override val id: Long) : Detail
    data class Crash(override val id: Long) : Detail
}

private val DetailSaver = listSaver<Detail?, Long>(
    save = { detail ->
        when (detail) {
            null -> emptyList()
            is Detail.Event -> listOf(0L, detail.id)
            is Detail.Crash -> listOf(1L, detail.id)
        }
    },
    restore = { saved ->
        when {
            saved.isEmpty() -> null
            saved[0] == 0L -> Detail.Event(saved[1])
            else -> Detail.Crash(saved[1])
        }
    },
)

@Composable
internal fun LogSenseApp(
    core: LogSenseCore,
    pendingCrashId: Long?,
    onCrashIdConsumed: () -> Unit,
    openCrashes: Boolean = false,
    onOpenCrashesConsumed: () -> Unit = {},
) {
    val themeMode by core.themeMode.collectAsState()
    val levelColors by core.prefs.levelColors.collectAsState()
    LogSenseTheme(themeMode, core.config.accentColor, levelColorOverrides = levelColors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var detail by rememberSaveable(stateSaver = DetailSaver) { mutableStateOf<Detail?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // True while we opened from the crash notification and are waiting for that crash to ingest.
        // Deliberately not saveable: it must not survive as `true` past the effect that clears it.
        var openingCrash by remember { mutableStateOf(false) }

        LaunchedEffect(pendingCrashId) {
            if (pendingCrashId != null) {
                tab = 2
                detail = Detail.Crash(pendingCrashId)
                onCrashIdConsumed()
            }
        }

        // Opened from the immediate crash alert: land on Crashes, then — as soon as the crash that
        // fired it finishes ingesting (it has no id at alert time) — open its detail directly instead
        // of leaving the user on the list. Falls back to the list if nothing lands within the window.
        LaunchedEffect(openCrashes) {
            if (openCrashes) {
                tab = 2
                detail = null
                showSettings = false
                openingCrash = true
                val id = withTimeoutOrNull(10_000) { core.lastCrashId.filterNotNull().first() }
                if (id != null) detail = Detail.Crash(id)
                openingCrash = false
                onOpenCrashesConsumed()
            }
        }

        // Keep each tab's transient state (selected event tag, scroll position, filter) alive while a
        // detail or settings screen replaces the tabs on phones, so returning lands back where you were.
        // Without this, TabsScaffold leaves the composition and its rememberSaveable state resets.
        val tabsState = rememberSaveableStateHolder()

        BoxWithConstraints {
            val wide = maxWidth >= 840.dp
            val current = detail
            when {
                openingCrash && current == null -> CrashLoadingScreen(onCancel = { openingCrash = false })
                showSettings -> SettingsScreen(core) { showSettings = false }
                current != null && !wide -> DetailScaffold(core, current, onBack = { detail = null })
                else -> tabsState.SaveableStateProvider("tabs") {
                    TabsScaffold(
                        core = core,
                        tab = tab,
                        onTab = { tab = it; detail = null },
                        wide = wide,
                        detail = current,
                        onOpenDetail = { detail = it },
                        onSettings = { showSettings = true },
                        // A signal jump lands on the Logs tab; LogsScreen consumes core.jumpToLogId.
                        onJumpToLogs = { tab = 0; detail = null },
                    )
                }
            }
        }
    }
}

/** Shown while the app was opened from the crash notification and we're waiting for that crash to
 *  finish ingesting after a cold start — so the user sees "loading", not a blank screen or the list. */
@Composable
private fun CrashLoadingScreen(onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CircularProgressIndicator()
            Text(
                "Loading crash…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A tab label with a filled count chip beside it, coloured by the **worst** signal currently held —
 * the same palette as the inline pills and gutter stripes, so three crashes don't look like three
 * lifecycle notes. The number says how many, the colour says how bad.
 *
 * This only fits because the tab row is content-sized; in an equal-width TabRow the chip's padding
 * is squeezed to nothing and the digits wrap. (A Material BadgedBox is the wrong tool here — it
 * anchors to the content's corner, so the badge lands on top of the word rather than beside it.)
 *
 * The count is bounded: the detector keeps at most 500 hits and stored crashes are capped by
 * `maxStoredCrashes`, so this tops out around three digits. [formatCount] would compact anything
 * past 999 to "1.2k" regardless, and the tab simply grows to fit.
 */
@Composable
private fun TabLabelWithCount(title: String, summary: SignalSummary) {
    val chip = summary.worst?.color() ?: MaterialTheme.colorScheme.primary
    // The category palette spans light ambers and dark reds across the two themes, so pick the
    // readable ink rather than assuming one; onPrimary would be wrong for most of them.
    val ink = if (chip.luminance() > 0.5f) Color.Black else Color.White
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(chip)
                .defaultMinSize(minWidth = 18.dp)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatCount(summary.count),
                style = MaterialTheme.typography.labelSmall,
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun LivePill(capturing: Boolean, held: Int, ratePerSec: Int) {
    // Green while live, red (the severity error color) when paused. Live shows the capture
    // rate — a full ring makes any held count a static number, but the rate keeps telling you
    // what the app is doing right now. Paused shows held: "this much is here to browse".
    val color = if (capturing) liveColor() else LogLevel.ERROR.color()
    val label = when {
        !capturing -> "PAUSED · ${formatCount(held)}"
        held == 0 -> "CONNECTING"
        ratePerSec > 0 -> "LIVE · ${formatCount(ratePerSec)}/s"
        else -> "LIVE"
    }
    // Breathing pulse via size — the dot stays fully solid (same vibrant green), only scaling in/out;
    // steady when paused.
    val pulse = rememberInfiniteTransition(label = "live")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dotScale",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer {
                    val s = if (capturing) scale else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsScaffold(
    core: LogSenseCore,
    tab: Int,
    onTab: (Int) -> Unit,
    wide: Boolean,
    detail: Detail?,
    onOpenDetail: (Detail) -> Unit,
    onSettings: () -> Unit,
    onJumpToLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val capturing by core.captureEnabled.collectAsState()
                    val held by core.buffer.held.collectAsState()
                    val rate by core.buffer.ratePerSec.collectAsState()
                    Column {
                        Text(
                            text = core.appName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.logsense_name),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            LivePill(capturing = capturing, held = held, ratePerSec = rate)
                        }
                    }
                },
                actions = {
                    val capturing by core.captureEnabled.collectAsState()
                    if (tab == 0) {
                        IconButton(onClick = { core.setCaptureEnabled(!capturing) }) {
                            Icon(
                                if (capturing) LogSenseIcons.Pause else LogSenseIcons.Play,
                                contentDescription = if (capturing) "Pause capture" else "Resume capture",
                                tint = if (capturing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // Events share lives in the Events header (EventsScreen) so it can honor the
                    // selected tag + filter; Crashes has its own per-report share.
                    IconButton(onClick = onSettings) {
                        Icon(LogSenseIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val signals = rememberSignalSummary(core)
            // Scrollable (i.e. content-sized) rather than equal-width: a fixed TabRow gives each tab
            // 384/4 dp minus Material's 16dp padding a side, which "Signals" alone very nearly fills
            // — the count then wraps onto a second line. Content sizing also survives "1.2k".
            // divider = {}: a scrollable tab row draws its divider *inside* the scrolling content,
            // so it ends where the tabs end and leaves a gap to the right. Ours spans the container.
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, divider = {}) {
                listOf("Logs", "Events", "Crashes", "Signals").forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { onTab(index) },
                        text = {
                            // The Signals tab carries a count: it's the one tab whose contents you
                            // want to know about without opening it.
                            if (index == 3 && signals.count > 0) TabLabelWithCount(title, signals) else Text(title)
                        },
                    )
                }
            }
            HorizontalDivider()
            when (tab) {
                0 -> LogsScreen(core)
                1 -> EventsScreen(
                    core = core,
                    wide = wide,
                    selectedId = (detail as? Detail.Event)?.id,
                    onOpen = { onOpenDetail(Detail.Event(it)) },
                )
                2 -> CrashesScreen(
                    core = core,
                    wide = wide,
                    selectedId = (detail as? Detail.Crash)?.id,
                    onOpen = { onOpenDetail(Detail.Crash(it)) },
                )
                3 -> SignalsScreen(
                    core = core,
                    wide = wide,
                    selectedCrashId = (detail as? Detail.Crash)?.id,
                    onOpenCrash = { onOpenDetail(Detail.Crash(it)) },
                    onJumpToLogs = onJumpToLogs,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(core: LogSenseCore, detail: Detail, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (detail is Detail.Event) "Event" else "Crash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LogSenseIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding).fillMaxSize()) {
            when (detail) {
                is Detail.Event -> EventDetailPane(core, detail.id)
                is Detail.Crash -> CrashDetailPane(core, detail.id)
            }
        }
    }
}
