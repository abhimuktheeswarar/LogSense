package com.msabhi.logsense.internal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.reader.LogLevel

internal val LocalDarkTheme = staticCompositionLocalOf { false }

/** Resolved per-level colors for the current theme, provided down the tree for [color]. */
internal val LocalLevelColors = staticCompositionLocalOf<Map<LogLevel, Color>> { emptyMap() }

/** Neutral developer-tool theme. No dynamic color — LogSense looks identical on every device. */
@Composable
internal fun LogSenseTheme(
    themeMode: ThemeMode,
    accentColor: Int?,
    levelColorOverrides: Map<LogLevel, LevelColorOverride> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = accentColor?.let { Color(it) }
    val scheme = if (dark) {
        darkColorScheme(primary = accent ?: Color(0xFF90A4AE))
    } else {
        lightColorScheme(primary = accent ?: Color(0xFF37474F))
    }
    val levelColors = LogLevel.entries.associateWith { level ->
        resolveLevelColor(level, dark, levelColorOverrides[level])
    }
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalLevelColors provides levelColors,
        content = { MaterialTheme(colorScheme = scheme, content = content) },
    )
}

/** The color for [this] level, honoring any user override, falling back to the built-in default. */
@Composable
internal fun LogLevel.color(): Color =
    LocalLevelColors.current[this] ?: defaultLevelColor(this, LocalDarkTheme.current)

private fun resolveLevelColor(level: LogLevel, dark: Boolean, override: LevelColorOverride?): Color {
    val argb = if (dark) override?.dark else override?.light
    return argb?.let { Color(it) } ?: defaultLevelColor(level, dark)
}

/** Built-in logcat color coding (light / dark pairs) — used when the user hasn't overridden a level. */
internal fun defaultLevelColor(level: LogLevel, dark: Boolean): Color = when (level) {
    LogLevel.VERBOSE -> if (dark) Color(0xFF9E9E9E) else Color(0xFF616161)
    LogLevel.DEBUG -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    LogLevel.INFO -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    LogLevel.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
    LogLevel.ERROR -> if (dark) Color(0xFFE57373) else Color(0xFFC62828)
    LogLevel.FATAL -> if (dark) Color(0xFFF06292) else Color(0xFFAD1457)
}
