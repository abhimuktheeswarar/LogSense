package com.msabhi.logsense.internal.analytics

import com.msabhi.logsense.AnalyticsEvent

/**
 * A user-supplied extractor built from a regex entered in Settings — the escape hatch for log
 * formats the [DefaultExtractor] can't infer. The pattern is expected to expose:
 *
 * - a named group **`name`** — the event name (required; a line with no match, or an empty name, is
 *   treated as "not an event" and skipped),
 * - an optional named group **`params`** — a `key=value, …` blob, parsed by [parseKeyValues]
 *   (so commas inside a value and wrapping braces are handled the same as the built-in parser).
 *
 * Example for `logEvent = purchase -> {sku=pro, qty=2}`:
 * `(?<name>\w+)\s*->\s*\{(?<params>.*)\}`
 */
internal class RegexExtractor(private val regex: Regex) : (String, String) -> AnalyticsEvent? {

    override fun invoke(tag: String, message: String): AnalyticsEvent? {
        val match = regex.find(message) ?: return null
        val name = group(match, "name")?.trim()?.ifEmpty { null } ?: return null
        val params = group(match, "params")?.let { parseKeyValues(it) } ?: emptyMap()
        return AnalyticsEvent(name, params)
    }

    /** Named-group lookup that tolerates a pattern which doesn't declare [name] (returns null). */
    private fun group(match: MatchResult, name: String): String? =
        runCatching { (match.groups as? MatchNamedGroupCollection)?.get(name)?.value }.getOrNull()

    companion object {
        /** Compiles [pattern]; returns null when blank or not a valid regex, so callers can fall back. */
        fun of(pattern: String): RegexExtractor? =
            pattern.trim().takeIf { it.isNotEmpty() }
                ?.let { p -> runCatching { RegexExtractor(Regex(p)) }.getOrNull() }
    }
}
