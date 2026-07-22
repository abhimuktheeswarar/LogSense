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
import com.msabhi.logsense.internal.reader.LogLevel

internal val LocalDarkTheme = staticCompositionLocalOf { false }

/** Neutral developer-tool theme. No dynamic color — LogSense looks identical on every device. */
@Composable
internal fun LogSenseTheme(
    themeMode: ThemeMode,
    accentColor: Int?,
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
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Fixed per-level colors (light / dark pairs) — the logcat color coding. */
@Composable
internal fun LogLevel.color(): Color {
    val dark = LocalDarkTheme.current
    return when (this) {
        LogLevel.VERBOSE -> if (dark) Color(0xFF9E9E9E) else Color(0xFF616161)
        LogLevel.DEBUG -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
        LogLevel.INFO -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        LogLevel.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        LogLevel.ERROR -> if (dark) Color(0xFFE57373) else Color(0xFFC62828)
        LogLevel.FATAL -> if (dark) Color(0xFFF06292) else Color(0xFFAD1457)
    }
}
