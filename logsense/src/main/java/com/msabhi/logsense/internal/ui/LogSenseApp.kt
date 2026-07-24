package com.msabhi.logsense.internal.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.R
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.LogSenseTheme
import com.msabhi.logsense.internal.ui.theme.color
import com.msabhi.logsense.internal.ui.theme.liveColor
import kotlinx.coroutines.launch

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

        LaunchedEffect(pendingCrashId) {
            if (pendingCrashId != null) {
                tab = 2
                detail = Detail.Crash(pendingCrashId)
                onCrashIdConsumed()
            }
        }

        // Immediate crash alert (no row yet): just land on the Crashes list.
        LaunchedEffect(openCrashes) {
            if (openCrashes) {
                tab = 2
                detail = null
                showSettings = false
                onOpenCrashesConsumed()
            }
        }

        BoxWithConstraints {
            val wide = maxWidth >= 840.dp
            val current = detail
            when {
                showSettings -> SettingsScreen(core) { showSettings = false }
                current != null && !wide -> DetailScaffold(core, current, onBack = { detail = null })
                else -> TabsScaffold(
                    core = core,
                    tab = tab,
                    onTab = { tab = it; detail = null },
                    wide = wide,
                    detail = current,
                    onOpenDetail = { detail = it },
                    onSettings = { showSettings = true },
                )
            }
        }
    }
}

@Composable
private fun LivePill(capturing: Boolean, count: Int) {
    // Green while live, red (the severity error color) when paused.
    val color = if (capturing) liveColor() else LogLevel.ERROR.color()
    val label = when {
        !capturing -> "PAUSED · ${formatCount(count)}"
        count == 0 -> "CONNECTING"
        else -> "LIVE · ${formatCount(count)}"
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val capturing by core.captureEnabled.collectAsState()
                    val total by core.buffer.totalReceived.collectAsState()
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
                            LivePill(capturing = capturing, count = total)
                        }
                    }
                },
                actions = {
                    val capturing by core.captureEnabled.collectAsState()
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    if (tab == 0) {
                        IconButton(onClick = { core.setCaptureEnabled(!capturing) }) {
                            Icon(
                                if (capturing) LogSenseIcons.Pause else LogSenseIcons.Play,
                                contentDescription = if (capturing) "Pause capture" else "Resume capture",
                                tint = if (capturing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (tab == 1) {
                        val eventCount by remember { core.database.eventDao().observeAll() }.collectAsState(initial = emptyList())
                        ShareMenuButton(
                            contentDescription = "Export all events",
                            fileLabel = "JSON file",
                            enabled = eventCount.isNotEmpty(),
                            onText = {
                                core.scope.launch {
                                    val all = core.database.eventDao().getAll()
                                    if (all.isNotEmpty()) ShareUtil.shareText(ctx, "events_all", EventExport.toJsonString(all))
                                }
                            },
                            onFile = {
                                core.scope.launch {
                                    val all = core.database.eventDao().getAll()
                                    if (all.isNotEmpty()) ShareUtil.shareJsonFile(ctx, "events_all", EventExport.toJsonString(all))
                                }
                            },
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(LogSenseIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Logs", "Events", "Crashes").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { onTab(index) }, text = { Text(title) })
                }
            }
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
