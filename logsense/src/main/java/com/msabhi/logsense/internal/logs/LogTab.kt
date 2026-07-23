package com.msabhi.logsense.internal.logs

import com.msabhi.logsense.internal.reader.LogLevel

/** How a log row is rendered — mirrors Android Studio's Standard / Compact view. */
internal enum class ViewMode { STANDARD, COMPACT }

/**
 * How long lines scroll horizontally (a global preference set in Settings, applied to every tab):
 * - [WRAP]  — wrap onto multiple lines, no horizontal scroll.
 * - [LINE]  — each row's message scrolls horizontally on its own (gutter/timestamp stay put).
 * - [ENTRY] — each row scrolls horizontally as a whole unit, independent of other rows.
 * - [PAN]   — the whole list pans left/right together as one.
 */
internal enum class LogScroll { WRAP, LINE, ENTRY, PAN }

/**
 * A narrowing filter over the live log stream: a min level plus an Android-Studio-style query
 * string ([LogQuery]) supporting `tag:`, `-tag:`, `message:`, `level:` and bare words. This is
 * the "filter" half of the filter-vs-search split; it applies to incoming lines too.
 */
internal data class LogFilter(
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val query: String = "",
)

/** One Android-Studio-style logcat tab: its own filter + view preferences. [paused] is runtime-only. */
internal data class LogTab(
    val id: Long,
    val name: String,
    val filter: LogFilter = LogFilter(),
    val viewMode: ViewMode = ViewMode.STANDARD,
    val paused: Boolean = false,
)

/** Per-level color override (ARGB ints, null = use the built-in default) for light / dark themes. */
internal data class LevelColorOverride(val light: Int?, val dark: Int?)
