package com.msabhi.logsense.internal.prefs

import android.content.Context
import android.content.SharedPreferences
import com.msabhi.logsense.internal.logs.LevelColorOverride
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

    companion object {
        private const val KEY_TABS = "tabs"
        private const val KEY_COLORS = "level_colors"
        val DEFAULT_TAB = LogTab(id = 0, name = "All")
    }
}
