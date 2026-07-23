package com.msabhi.logsense.internal.logs

import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel

/** How a log row is rendered — mirrors Android Studio's Standard / Compact view. */
internal enum class ViewMode { STANDARD, COMPACT }

/**
 * A narrowing filter over the live log stream (min level + exact tag + free text). Applies to
 * incoming lines too — this is the "filter" half of Android Studio's filter-vs-search split.
 */
internal data class LogFilter(
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val tag: String? = null,
    val text: String = "",
) {
    fun matches(entry: LogEntry): Boolean =
        entry.level.ordinal >= minLevel.ordinal &&
            (tag == null || entry.tag == tag) &&
            (text.isEmpty() || entry.message.contains(text, true) || entry.tag.contains(text, true))
}

/** One Android-Studio-style logcat tab: its own filter + view preferences. [paused] is runtime-only. */
internal data class LogTab(
    val id: Long,
    val name: String,
    val filter: LogFilter = LogFilter(),
    val viewMode: ViewMode = ViewMode.STANDARD,
    val softWrap: Boolean = false,
    val paused: Boolean = false,
)

/** Per-level color override (ARGB ints, null = use the built-in default) for light / dark themes. */
internal data class LevelColorOverride(val light: Int?, val dark: Int?)
