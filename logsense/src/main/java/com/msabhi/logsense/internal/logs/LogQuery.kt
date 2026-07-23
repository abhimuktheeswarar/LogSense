package com.msabhi.logsense.internal.logs

import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel

/**
 * Compiles an Android-Studio-style filter query into a predicate. Space-separated terms are ANDed:
 *
 * - `tag:foo`        tag contains "foo" (case-insensitive)
 * - `-tag:foo`       tag does NOT contain "foo"
 * - `message:foo`    (or `msg:foo`) message contains "foo"; `-message:` negates
 * - `level:E`        (or `level:error`) raise the minimum level
 * - `foo`            bare word: tag OR message contains "foo"
 * - `"two words"`    quotes keep spaces together
 *
 * A plain query with no `key:` is just a simple text filter, so `tag:xxx` and free text both work.
 */
internal object LogQuery {

    fun compile(filter: LogFilter): (LogEntry) -> Boolean {
        val terms = parse(filter.query)
        val min = terms.filterIsInstance<Term.MinLevel>()
            .fold(filter.minLevel) { acc, t -> maxOf(acc, t.level) }
        val predicates = terms.filterNot { it is Term.MinLevel }
        return { e -> e.level.ordinal >= min.ordinal && predicates.all { it.matches(e) } }
    }

    private fun parse(query: String): List<Term> = tokenize(query).mapNotNull { token ->
        val negate = token.length > 1 && token.startsWith('-')
        val body = if (negate) token.substring(1) else token
        val colon = body.indexOf(':')
        if (colon <= 0) return@mapNotNull if (body.isEmpty()) null else Term.Text(body, negate)
        val key = body.substring(0, colon).lowercase()
        val value = body.substring(colon + 1)
        if (value.isEmpty()) return@mapNotNull null
        when (key) {
            "tag" -> Term.Field(value, negate) { it.tag }
            "message", "msg" -> Term.Field(value, negate) { it.message }
            "level" -> LogLevel.fromName(value)?.let { Term.MinLevel(it) }
            "package", "pkg" -> null // single-app capture: package is always the host
            else -> Term.Text(body, negate) // unknown key → treat the whole token as text
        }
    }

    private fun tokenize(query: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in query) {
            when {
                c == '"' -> inQuote = !inQuote
                c.isWhitespace() && !inQuote -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private sealed interface Term {
        fun matches(e: LogEntry): Boolean

        class Field(val value: String, val negate: Boolean, val select: (LogEntry) -> String) : Term {
            override fun matches(e: LogEntry) = select(e).contains(value, ignoreCase = true) xor negate
        }

        class Text(val value: String, val negate: Boolean) : Term {
            override fun matches(e: LogEntry) =
                (e.tag.contains(value, true) || e.message.contains(value, true)) xor negate
        }

        class MinLevel(val level: LogLevel) : Term {
            override fun matches(e: LogEntry) = true
        }
    }
}
