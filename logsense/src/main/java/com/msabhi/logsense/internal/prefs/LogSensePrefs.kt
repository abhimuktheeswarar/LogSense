package com.msabhi.logsense.internal.prefs

import android.content.Context
import android.content.SharedPreferences
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.logs.LogScroll
import com.msabhi.logsense.internal.logs.LogTab
import com.msabhi.logsense.internal.reader.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

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

    /** QA-added analytics tags (Settings), each with an optional regex (null = built-in parser).
     *  Merged with config's tags, which are authoritative and can't be edited here. */
    val tagPatterns = MutableStateFlow(loadTagPatterns())

    fun setTagPatterns(map: Map<String, String?>) {
        tagPatterns.value = map
        sp.edit().putString(KEY_TAG_PATTERNS, encodeTagPatterns(map)).apply()
    }

    private fun loadTagPatterns(): Map<String, String?> =
        sp.getString(KEY_TAG_PATTERNS, null)?.let { decodeTagPatterns(it) } ?: emptyMap()

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
        private const val KEY_TAG_PATTERNS = "tag_patterns"
        val DEFAULT_TAB = LogTab(id = 0, name = "All")
    }
}

/** `{tag: regex|null}` — regex null when a tag uses the built-in parser. */
private fun encodeTagPatterns(map: Map<String, String?>): String =
    JSONObject().apply { for ((tag, pattern) in map) put(tag, pattern ?: JSONObject.NULL) }.toString()

private fun decodeTagPatterns(text: String): Map<String, String?> =
    runCatching {
        val json = JSONObject(text)
        buildMap { for (tag in json.keys()) put(tag, if (json.isNull(tag)) null else json.getString(tag)) }
    }.getOrDefault(emptyMap())
