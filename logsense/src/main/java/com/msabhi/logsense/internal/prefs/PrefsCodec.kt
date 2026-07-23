package com.msabhi.logsense.internal.prefs

import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.logs.LogTab
import com.msabhi.logsense.internal.logs.ViewMode
import com.msabhi.logsense.internal.reader.LogLevel
import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON (de)serialization for [LogSensePrefs] — no Android deps, so it's unit-testable. */
internal object PrefsCodec {

    fun encodeTabs(tabs: List<LogTab>): String {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("minLevel", t.filter.minLevel.name)
                    .put("query", t.filter.query)
                    .put("viewMode", t.viewMode.name),
            )
        }
        return arr.toString()
    }

    fun decodeTabs(json: String): List<LogTab> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LogTab(
                id = o.getLong("id"),
                name = o.optString("name", "Logs"),
                filter = LogFilter(
                    minLevel = enumOr(o.optString("minLevel"), LogLevel.VERBOSE),
                    query = o.optString("query", ""),
                ),
                viewMode = enumOr(o.optString("viewMode"), ViewMode.STANDARD),
            )
        }
    }.getOrDefault(emptyList())

    fun encodeColors(colors: Map<LogLevel, LevelColorOverride>): String {
        val o = JSONObject()
        colors.forEach { (level, c) ->
            o.put(
                level.name,
                JSONObject()
                    .put("light", c.light ?: JSONObject.NULL)
                    .put("dark", c.dark ?: JSONObject.NULL),
            )
        }
        return o.toString()
    }

    fun decodeColors(json: String): Map<LogLevel, LevelColorOverride> = runCatching {
        val o = JSONObject(json)
        buildMap {
            o.keys().forEach { key ->
                val level = runCatching { LogLevel.valueOf(key) }.getOrNull() ?: return@forEach
                val c = o.getJSONObject(key)
                put(
                    level,
                    LevelColorOverride(
                        light = if (c.isNull("light")) null else c.getInt("light"),
                        dark = if (c.isNull("dark")) null else c.getInt("dark"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}
