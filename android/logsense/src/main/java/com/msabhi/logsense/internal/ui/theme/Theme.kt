package com.msabhi.logsense.internal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.signals.SignalCategory

internal val LocalDarkTheme = staticCompositionLocalOf { false }

/** Resolved per-level colors for the current theme, provided down the tree for [color]. */
internal val LocalLevelColors = staticCompositionLocalOf<Map<LogLevel, Color>> { emptyMap() }

/**
 * Material 3 theme following the system light/dark setting (overridable in Settings). Uses
 * **Material You** dynamic color from the device wallpaper on Android 12+, and a neutral
 * fallback scheme below that (or a caller-supplied [accentColor]).
 */
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
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> {
            val accent = accentColor?.let { Color(it) }
            if (dark) darkColorScheme(primary = accent ?: Color(0xFF90A4AE)) else lightColorScheme(primary = accent ?: Color(0xFF37474F))
        }
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

/** Semantic "capture is live" green — deliberately separate from the Material You accent. */
@Composable
internal fun liveColor(): Color = if (LocalDarkTheme.current) Color(0xFF7DDB8F) else Color(0xFF2E7D32)

/** Signal categories borrow the level palette, so a severity reads the same everywhere. */
@Composable
internal fun SignalCategory.color(): Color = when (this) {
    SignalCategory.CRASH, SignalCategory.NATIVE -> LogLevel.FATAL.color()
    SignalCategory.ANR -> LogLevel.WARN.color()
    SignalCategory.MEMORY -> LogLevel.ERROR.color()
    SignalCategory.LIFECYCLE -> LogLevel.DEBUG.color()
    SignalCategory.CUSTOM -> MaterialTheme.colorScheme.primary
}

/**
 * A stable color per tag, so the same subsystem keeps the same hue for the whole session and you can
 * tell interleaved tags apart at a glance. Severity stays on the row stripe and message, not here.
 */
@Composable
internal fun tagColor(tag: String): Color {
    val palette = if (LocalDarkTheme.current) TAG_COLORS_DARK else TAG_COLORS_LIGHT
    return palette[(tag.hashCode() and 0x7FFFFFFF) % palette.size]
}

private val TAG_COLORS_DARK = listOf(
    Color(0xFF7FD1C1), // teal
    Color(0xFFB9A2F0), // violet
    Color(0xFFF0B27A), // amber
    Color(0xFF8FC7F5), // sky
    Color(0xFFE79AC4), // pink
    Color(0xFFA9D18E), // moss
    Color(0xFFF2C46B), // gold
    Color(0xFFCBA6A6), // clay
)

private val TAG_COLORS_LIGHT = listOf(
    Color(0xFF00796B),
    Color(0xFF5E35B1),
    Color(0xFFB2560D),
    Color(0xFF1565C0),
    Color(0xFFAD1457),
    Color(0xFF33691E),
    Color(0xFF8D6E00),
    Color(0xFF8D4E4E),
)

/** Built-in logcat color coding (light / dark pairs) — used when the user hasn't overridden a level. */
internal fun defaultLevelColor(level: LogLevel, dark: Boolean): Color = when (level) {
    LogLevel.VERBOSE -> if (dark) Color(0xFF9E9E9E) else Color(0xFF616161)
    LogLevel.DEBUG -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    LogLevel.INFO -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    LogLevel.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
    LogLevel.ERROR -> if (dark) Color(0xFFE57373) else Color(0xFFC62828)
    LogLevel.FATAL -> if (dark) Color(0xFFF06292) else Color(0xFFAD1457)
}
