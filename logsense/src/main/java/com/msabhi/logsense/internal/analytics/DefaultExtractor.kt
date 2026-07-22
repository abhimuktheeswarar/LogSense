package com.msabhi.logsense.internal.analytics

import com.msabhi.logsense.AnalyticsEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in extractor for common analytics log formats, most specific first:
 * 1. `event_name Bundle[{key=value, k2=v2}]`  — Bundle.toString payload (Firebase style)
 * 2. `event_name {"key": "value"}`            — JSON payload
 * 3. `event_name key=value, k2=v2`            — plain key=value pairs
 * 4. anything else                            — whole message as the event name
 *
 * The event name is the text before the payload; falls back to the log tag when absent.
 */
internal object DefaultExtractor : (String, String) -> AnalyticsEvent? {

    override fun invoke(tag: String, message: String): AnalyticsEvent {
        val msg = message.trim()

        val bundleIdx = msg.indexOf(BUNDLE_PREFIX)
        if (bundleIdx >= 0 && msg.endsWith("}]")) {
            val inner = msg.substring(bundleIdx + BUNDLE_PREFIX.length, msg.length - 2)
            return AnalyticsEvent(name(msg.substring(0, bundleIdx), tag), parseKeyValues(inner))
        }

        val braceIdx = msg.indexOf('{')
        if (braceIdx >= 0 && msg.endsWith("}")) {
            runCatching {
                val params = JSONObject(msg.substring(braceIdx)).toMap()
                return AnalyticsEvent(name(msg.substring(0, braceIdx), tag), params)
            }
        }

        if ('=' in msg) {
            val tokens = msg.split(' ', limit = 2)
            return when {
                '=' in tokens[0] -> AnalyticsEvent(tag, parseKeyValues(msg)) // params only, no name
                tokens.size == 2 -> AnalyticsEvent(name(tokens[0], tag), parseKeyValues(tokens[1]))
                else -> AnalyticsEvent(msg)
            }
        }

        return AnalyticsEvent(msg.ifEmpty { tag })
    }

    private const val BUNDLE_PREFIX = "Bundle[{"

    private fun name(prefix: String, tag: String): String =
        prefix.trim().trimEnd(':', ',', '-').trim().ifEmpty { tag }

    private fun parseKeyValues(text: String): Map<String, Any?> =
        text.split(',')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else pair.substring(0, idx).trim() to pair.substring(idx + 1).trim() as Any?
            }
            .toMap()
}

internal fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    for (key in this@toMap.keys()) {
        put(
            key,
            when (val value = this@toMap.opt(key)) {
                is JSONObject, is JSONArray -> value.toString() // nested kept as text — deliberate
                JSONObject.NULL -> null
                else -> value
            },
        )
    }
}
