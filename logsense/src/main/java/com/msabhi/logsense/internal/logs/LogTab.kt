package com.msabhi.logsense.internal.logs

import com.msabhi.logsense.internal.reader.LogEntry
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

/**
 * One Android-Studio-style logcat tab: its own filter + view preferences. [paused] and
 * [clearedAtId] are runtime-only — [com.msabhi.logsense.internal.prefs.PrefsCodec] persists neither,
 * and a clear watermark would be meaningless next run anyway, since entry ids restart with the reader.
 */
internal data class LogTab(
    val id: Long,
    val name: String,
    val filter: LogFilter = LogFilter(),
    val viewMode: ViewMode = ViewMode.STANDARD,
    val paused: Boolean = false,
    /** Newest entry id at the moment this tab was cleared; it shows only lines after this. */
    val clearedAtId: Long = 0L,
)

/**
 * The slice of entries a tab shows after clearing: everything newer than [clearedAtId].
 *
 * Tabs are filters over one shared buffer, so "clearing" a tab hides lines rather than dropping
 * them — other tabs still show them and signal hits still point at real lines. Entries are stored
 * in id order, so this is a binary search returning a `subList` **view**: no copy, and a cleared tab
 * then filters *fewer* lines than an uncleared one.
 */
internal fun List<LogEntry>.since(clearedAtId: Long): List<LogEntry> {
    if (clearedAtId <= 0L || isEmpty()) return this
    // ponytail: the reader restarts its id counter whenever it is recreated, so a watermark taken
    // before a restart would hide every new line. Rather than plumbing a reset through, self-heal:
    // a watermark ahead of the newest line can only be stale, so ignore it.
    if (last().id < clearedAtId) return this
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].id <= clearedAtId) low = mid + 1 else high = mid
    }
    return subList(low, size)
}

/** Per-level color override (ARGB ints, null = use the built-in default) for light / dark themes. */
internal data class LevelColorOverride(val light: Int?, val dark: Int?)
