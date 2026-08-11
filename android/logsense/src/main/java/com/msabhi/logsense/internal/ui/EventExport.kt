package com.msabhi.logsense.internal.ui

import com.msabhi.logsense.internal.data.EventEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes analytics events to valid, machine-readable JSON. A single event becomes an object
 * with `name`, `tag`, `time` (ISO) / `timeMs`, and `params` (nested object); a list becomes a
 * JSON array of those objects. Used by single-event share, multi-select export and export-all.
 */
internal object EventExport {

    fun toJson(event: EventEntity): JSONObject = JSONObject().apply {
        put("name", event.name)
        put("tag", event.tag)
        put("time", event.timestamp.asIso())
        put("timeMs", event.timestamp)
        put("params", runCatching { JSONObject(event.paramsJson) }.getOrElse { JSONObject() })
    }

    /** Pretty-printed single object. */
    fun toJsonString(event: EventEntity): String = toJson(event).toString(2)

    /** Pretty-printed array of objects (newest first, as stored). */
    fun toJsonString(events: List<EventEntity>): String {
        val arr = JSONArray()
        events.forEach { arr.put(toJson(it)) }
        return arr.toString(2)
    }
}
