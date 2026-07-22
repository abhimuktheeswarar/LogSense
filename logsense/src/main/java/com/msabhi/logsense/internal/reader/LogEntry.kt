package com.msabhi.logsense.internal.reader

internal enum class LogLevel(val letter: Char) {
    VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E'), FATAL('F');

    companion object {
        fun fromLetter(letter: Char): LogLevel? = entries.firstOrNull { it.letter == letter }
    }
}

internal data class LogEntry(
    val id: Long,
    val timeMs: Long,
    /** Raw "seconds.millis" epoch string, reused for logcat's -T argument. */
    val epochRaw: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
