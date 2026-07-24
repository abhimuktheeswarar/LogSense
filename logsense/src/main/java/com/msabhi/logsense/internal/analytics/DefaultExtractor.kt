package com.msabhi.logsense.internal.analytics

import com.msabhi.logsense.AnalyticsEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in extractor for common analytics log shapes — no per-app configuration needed. It reads,
 * most specific first:
 *
 * 1. `<name> Bundle[{key=value, ...}]`   — Android `Bundle.toString` (Firebase style)
 * 2. `<name> {"key": "value", ...}`      — JSON payload
 * 3. `<name> {key=value, ...}`           — brace-wrapped key=value (JSON-ish, unquoted values)
 * 4. `<verb> = <name> -> <payload>`      — arrow-separated (e.g. `logEvent = purchase -> {...}`)
 * 5. `<name> key=value, k2=v2`           — plain key=value pairs
 * 6. anything else                       — the whole message as the event name
 *
 * The event **name** is the last identifier before the payload, so a leading verb (`logEvent`,
 * `track`, `GA ->` …), arrows and separators are skipped automatically. A value may
 * itself contain commas — pairs are only split before the next `key=`, and any wrapping braces or
 * brackets are stripped. Falls back to the log **tag** when no name is present.
 *
 * Not every app's format fits these shapes; supply
 * [com.msabhi.logsense.LogSenseConfig.analyticsExtractor] for anything bespoke.
 */
internal object DefaultExtractor : (String, String) -> AnalyticsEvent? {

    override fun invoke(tag: String, message: String): AnalyticsEvent {
        val msg = message.trim()

        // 1. Bundle[{ ... }]
        val bundleIdx = msg.indexOf(BUNDLE_PREFIX)
        if (bundleIdx >= 0) {
            val end = msg.lastIndexOf("}]")
            if (end > bundleIdx) {
                val inner = msg.substring(bundleIdx + BUNDLE_PREFIX.length, end)
                return AnalyticsEvent(nameBefore(msg, bundleIdx, tag), parseKeyValues(inner))
            }
        }

        // 2 & 3. { ... } — real JSON if it parses, otherwise brace-wrapped key=value.
        val open = msg.indexOf('{')
        val close = msg.lastIndexOf('}')
        if (open in 0 until close) {
            val inner = msg.substring(open, close + 1)
            return AnalyticsEvent(nameBefore(msg, open, tag), parseParams(inner))
        }

        // 4. arrow-separated: `<verb> = <name> -> <payload>`  /  `<name> => <payload>`
        val arrow = ARROWS.mapNotNull { a -> msg.indexOf(a).takeIf { it >= 0 } }.minOrNull()
        if (arrow != null) {
            return AnalyticsEvent(nameBefore(msg, arrow, tag), parseKeyValues(msg.substring(arrow + 2)))
        }

        // 5. plain key=value pairs, optionally prefixed by a name.
        if ('=' in msg) {
            val tokens = msg.split(' ', limit = 2)
            return when {
                '=' in tokens[0] -> AnalyticsEvent(tag, parseKeyValues(msg)) // params only, no name
                tokens.size == 2 -> AnalyticsEvent(cleanName(tokens[0], tag), parseKeyValues(tokens[1]))
                else -> AnalyticsEvent(msg)
            }
        }

        // 6. plain text.
        return AnalyticsEvent(msg.ifEmpty { tag })
    }

    private const val BUNDLE_PREFIX = "Bundle[{"
    private val ARROWS = listOf("->", "=>")
    private val NAME_TOKEN = Regex("""[\w.]+""")

    /** Event name = the last identifier in the text before the payload (skips verbs/arrows/colons). */
    private fun nameBefore(msg: String, payloadStart: Int, tag: String): String =
        NAME_TOKEN.findAll(msg.substring(0, payloadStart)).lastOrNull()?.value ?: tag

    private fun cleanName(prefix: String, tag: String): String =
        prefix.trim().trimEnd(':', ',', '-').trim().ifEmpty { tag }
}

/** A `key = value` pair, where the value runs until the next `, key=` or the end of the text. */
private val KV = Regex("""([\w.]+)\s*=\s*(.*?)(?=,\s*[\w.]+\s*=|$)""")

/**
 * Params from a captured payload: real JSON if it parses to a non-empty object (many SDKs log the
 * event's attributes as JSON), otherwise `key=value`. Shared by [DefaultExtractor] and the
 * user-supplied [RegexExtractor] so a regex whose `params` group grabs a `{ ... }` object gets the
 * same JSON handling as the built-in parser.
 */
internal fun parseParams(text: String): Map<String, Any?> =
    runCatching { JSONObject(text.trim()).toMap() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: parseKeyValues(text)

/**
 * Parses `key=value, k2=v2` into a map, tolerating wrapping braces/brackets and commas *inside*
 * values. Shared by [DefaultExtractor] and the user-supplied [RegexExtractor] `params` group.
 */
internal fun parseKeyValues(text: String): Map<String, Any?> {
    val body = text.trim().trim('{', '}', '[', ']', ' ')
    return KV.findAll(body).associate { m -> m.groupValues[1].trim() to (m.groupValues[2].trim() as Any?) }
}

internal fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    for (key in this@toMap.keys()) {
        when (val value = this@toMap.opt(key)) {
            JSONObject.NULL -> put(key, null)
            is JSONObject, is JSONArray -> put(key, value.toString()) // nested kept as text — deliberate
            // A string that is itself a JSON object = an SDK cramming a whole attribute set through
            // one string field as (double-)escaped JSON (MoEngage's EVENT_ATTRS / EVENT_ATTRS_CUST is
            // exactly this). Unwrap it so those attributes become their own rows, un-escaped, instead
            // of one unreadable `{\"k\":v}` blob. Real nested objects above are still kept as text.
            is String -> value.asJsonObject()?.let { putAll(it.toMap()) } ?: put(key, value)
            else -> put(key, value)
        }
    }
}

/** The string parsed as a non-empty JSON object, or null if it isn't one. */
private fun String.asJsonObject(): JSONObject? =
    takeIf { it.startsWith('{') }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.length() > 0 }
