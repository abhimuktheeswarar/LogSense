package com.msabhi.logsense.internal.reader

/**
 * Parses `logcat -v epoch` lines:
 * `         1737541357.983  1234  1300 D SomeTag : message`
 *
 * Returns null for lines that are not entry headers (buffer dividers, continuation
 * lines of multi-line output) — the caller decides what to do with those.
 */
internal class LogParser {

    private val regex = Regex("""^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s?(.*)$""")
    private var nextId = 0L

    fun isDivider(raw: String): Boolean = raw.startsWith("--------- beginning of")

    fun parse(raw: String): LogEntry? {
        val m = regex.matchEntire(raw) ?: return null
        val (sec, msec, pid, tid, level, tag, message) = m.destructured
        val logLevel = LogLevel.fromLetter(level[0]) ?: return null
        return LogEntry(
            id = nextId++,
            timeMs = (sec.toLongOrNull() ?: return null) * 1000 + (msec.take(3).toLongOrNull() ?: 0),
            epochRaw = "$sec.$msec",
            pid = pid.toIntOrNull() ?: 0,
            tid = tid.toIntOrNull() ?: 0,
            level = logLevel,
            tag = tag,
            message = message,
        )
    }
}
