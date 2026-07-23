package com.msabhi.logsense.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.LocalDarkTheme
import com.msabhi.logsense.internal.ui.theme.defaultLevelColor

private val PALETTE: List<Int> = listOf(
    0xFF9E9E9E, 0xFF616161, 0xFF64B5F6, 0xFF1565C0, 0xFF81C784, 0xFF2E7D32,
    0xFFFFB74D, 0xFFB26A00, 0xFFE57373, 0xFFC62828, 0xFFF06292, 0xFFAD1457,
    0xFFBA68C8, 0xFF26A69A, 0xFFFFFFFF, 0xFF000000,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(core: LogSenseCore, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val overrides by core.prefs.levelColors.collectAsState()
    val dark = LocalDarkTheme.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LogSenseIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { core.prefs.resetLevelColors() }) { Text("Reset") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Log level colors", style = MaterialTheme.typography.titleMedium)
            Text(
                "Editing the ${if (dark) "dark" else "light"} theme — toggle theme from the top bar to set the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LogLevel.entries.forEach { level ->
                LevelColorRow(level, dark, overrides[level]) { argb ->
                    val current = overrides[level]
                    if (dark) {
                        core.prefs.setLevelColor(level, light = current?.light, dark = argb)
                    } else {
                        core.prefs.setLevelColor(level, light = argb, dark = current?.dark)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelColorRow(level: LogLevel, dark: Boolean, override: LevelColorOverride?, onPick: (Int?) -> Unit) {
    val currentSlot = if (dark) override?.dark else override?.light
    val resolved = currentSlot?.let { Color(it) } ?: defaultLevelColor(level, dark)

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = level.letter.toString(),
                color = resolved,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(resolved.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(level.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(defaultLevelColor(level, dark), selected = currentSlot == null, isDefault = true) { onPick(null) }
            PALETTE.forEach { argb ->
                Swatch(Color(argb), selected = currentSlot == argb, isDefault = false) { onPick(argb) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, isDefault: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(if (isDefault) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isDefault) {
            Text("·", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
        }
    }
}
