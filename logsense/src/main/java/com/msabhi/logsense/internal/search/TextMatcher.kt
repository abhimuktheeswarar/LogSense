package com.msabhi.logsense.internal.search

/** Android-Studio-style find options for the search bar. */
internal data class SearchQuery(
    val text: String = "",
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
) {
    val isActive: Boolean get() = text.isNotEmpty()
}

/**
 * Compiles a [SearchQuery] into a reusable matcher — literal / match-case / whole-word / regex,
 * mirroring Android Studio's find bar. An invalid regex (or half-typed pattern) resolves to
 * "matches nothing" instead of throwing.
 */
internal class TextMatcher private constructor(
    private val regex: Regex?,
    private val literal: String?,
    private val ignoreCase: Boolean,
    private val matchNothing: Boolean,
) {
    /** True when [input] contains at least one match. Empty query matches everything. */
    fun matches(input: String): Boolean = when {
        matchNothing -> false
        regex != null -> regex.containsMatchIn(input)
        literal != null -> input.contains(literal, ignoreCase)
        else -> true
    }

    /** Non-empty match ranges within [input], for highlighting. Empty when the query is blank. */
    fun ranges(input: String): List<IntRange> = when {
        matchNothing -> emptyList()
        regex != null -> regex.findAll(input).map { it.range }.filter { it.first <= it.last }.toList()
        literal != null && literal.isNotEmpty() -> buildList {
            var i = input.indexOf(literal, 0, ignoreCase)
            while (i >= 0) {
                add(i until i + literal.length)
                i = input.indexOf(literal, i + literal.length, ignoreCase)
            }
        }
        else -> emptyList()
    }

    companion object {
        fun from(query: SearchQuery): TextMatcher {
            val ignoreCase = !query.matchCase
            if (query.text.isEmpty()) return TextMatcher(null, null, ignoreCase, matchNothing = false)
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return when {
                query.regex -> compile(query.text, opts, ignoreCase)
                query.wholeWord -> compile("""\b${Regex.escape(query.text)}\b""", opts, ignoreCase)
                else -> TextMatcher(null, query.text, ignoreCase, matchNothing = false)
            }
        }

        private fun compile(pattern: String, opts: Set<RegexOption>, ignoreCase: Boolean): TextMatcher =
            runCatching { Regex(pattern, opts) }
                .map { TextMatcher(it, null, ignoreCase, matchNothing = false) }
                .getOrElse { TextMatcher(null, null, ignoreCase, matchNothing = true) }
    }
}
