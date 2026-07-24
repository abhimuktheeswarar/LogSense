package com.msabhi.logsense.internal.prefs

import android.content.Context
import android.content.SharedPreferences
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.logs.LogScroll
import com.msabhi.logsense.internal.logs.LogTab
import com.msabhi.logsense.internal.reader.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Disk-backed UI preferences (SharedPreferences, no extra dependency): the user's logcat tabs and
 * their per-level color overrides. Survives process death so tabs and colors persist across runs.
 */
internal class LogSensePrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("logsense_prefs", Context.MODE_PRIVATE)

    /** Live so the Compose theme recomposes the moment a color is edited in Settings. */
    val levelColors = MutableStateFlow(loadLevelColors())

    /** Global horizontal-scroll mode for the Logs viewer (Settings). Live so tabs re-render at once. */
    val logScroll = MutableStateFlow(
        sp.getString(KEY_LOG_SCROLL, null)?.let { runCatching { LogScroll.valueOf(it) }.getOrNull() } ?: LogScroll.ENTRY,
    )

    /** Whether events/crashes from earlier app runs are retained. Crashes default on (you rarely
     *  want to miss one); events default off (they're high-volume and usually only this run matters). */
    val keepPastEvents = MutableStateFlow(sp.getBoolean(KEY_KEEP_EVENTS, false))
    val keepPastCrashes = MutableStateFlow(sp.getBoolean(KEY_KEEP_CRASHES, true))

    /** Extra logcat tags (Settings, one per line) to treat as analytics events, merged with config. */
    val eventTags = MutableStateFlow(sp.getString(KEY_EVENT_TAGS, "").orEmpty())

    fun setEventTags(tags: String) {
        eventTags.value = tags
        sp.edit().putString(KEY_EVENT_TAGS, tags).apply()
    }

    /** Optional user-defined regex (Settings) for parsing events; empty = use the built-in parser. */
    val eventPattern = MutableStateFlow(sp.getString(KEY_EVENT_PATTERN, "").orEmpty())

    fun setEventPattern(pattern: String) {
        eventPattern.value = pattern
        sp.edit().putString(KEY_EVENT_PATTERN, pattern).apply()
    }

    fun setLogScroll(mode: LogScroll) {
        logScroll.value = mode
        sp.edit().putString(KEY_LOG_SCROLL, mode.name).apply()
    }

    fun setKeepPastEvents(enabled: Boolean) {
        keepPastEvents.value = enabled
        sp.edit().putBoolean(KEY_KEEP_EVENTS, enabled).apply()
    }

    fun setKeepPastCrashes(enabled: Boolean) {
        keepPastCrashes.value = enabled
        sp.edit().putBoolean(KEY_KEEP_CRASHES, enabled).apply()
    }

    fun loadTabs(): List<LogTab> =
        sp.getString(KEY_TABS, null)
            ?.let { PrefsCodec.decodeTabs(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(DEFAULT_TAB)

    fun saveTabs(tabs: List<LogTab>) {
        sp.edit().putString(KEY_TABS, PrefsCodec.encodeTabs(tabs)).apply()
    }

    fun setLevelColor(level: LogLevel, light: Int?, dark: Int?) {
        val updated = levelColors.value.toMutableMap()
        if (light == null && dark == null) updated.remove(level) else updated[level] = LevelColorOverride(light, dark)
        levelColors.value = updated
        sp.edit().putString(KEY_COLORS, PrefsCodec.encodeColors(updated)).apply()
    }

    fun resetLevelColors() {
        levelColors.value = emptyMap()
        sp.edit().remove(KEY_COLORS).apply()
    }

    private fun loadLevelColors(): Map<LogLevel, LevelColorOverride> =
        sp.getString(KEY_COLORS, null)?.let { PrefsCodec.decodeColors(it) } ?: emptyMap()

    /** The user's chosen theme mode, or null if they've never set one (fall back to config default). */
    fun loadThemeMode(): ThemeMode? =
        sp.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }

    fun saveThemeMode(mode: ThemeMode) {
        sp.edit().putString(KEY_THEME, mode.name).apply()
    }

    companion object {
        private const val KEY_TABS = "tabs"
        private const val KEY_COLORS = "level_colors"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LOG_SCROLL = "log_scroll"
        private const val KEY_KEEP_EVENTS = "keep_past_events"
        private const val KEY_KEEP_CRASHES = "keep_past_crashes"
        private const val KEY_EVENT_TAGS = "event_tags"
        private const val KEY_EVENT_PATTERN = "event_pattern"
        val DEFAULT_TAB = LogTab(id = 0, name = "All")
    }
}
