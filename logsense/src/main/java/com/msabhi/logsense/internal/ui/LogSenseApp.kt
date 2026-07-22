package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.R
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.ui.theme.LogSenseTheme

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
) {
    val themeMode by core.themeMode.collectAsState()
    LogSenseTheme(themeMode, core.config.accentColor) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var detail by rememberSaveable(stateSaver = DetailSaver) { mutableStateOf<Detail?>(null) }

        LaunchedEffect(pendingCrashId) {
            if (pendingCrashId != null) {
                tab = 2
                detail = Detail.Crash(pendingCrashId)
                onCrashIdConsumed()
            }
        }

        BoxWithConstraints {
            val wide = maxWidth >= 840.dp
            val current = detail
            if (current != null && !wide) {
                DetailScaffold(core, current, onBack = { detail = null })
            } else {
                TabsScaffold(
                    core = core,
                    tab = tab,
                    onTab = { tab = it; detail = null },
                    wide = wide,
                    detail = current,
                    onOpenDetail = { detail = it },
                    themeMode = themeMode,
                    onThemeToggle = { core.themeMode.value = themeMode.next() },
                )
            }
        }
    }
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
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
    themeMode: ThemeMode,
    onThemeToggle: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(R.string.logsense_name)) },
                actions = {
                    TextButton(onClick = onThemeToggle) {
                        Text(
                            when (themeMode) {
                                ThemeMode.SYSTEM -> "Auto"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                        )
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
