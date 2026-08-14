package com.msabhi.logsense.internal.reader

/**
 * Decides which lines to drop after a logcat reconnect. `-T <epoch>` re-emits every line at the
 * resume timestamp, and time alone cannot separate those re-emissions from never-seen lines logged
 * in the same millisecond — a burst puts dozens of lines there, and dropping the whole millisecond
 * loses real logs. So the identity of every tail-millisecond entry is remembered, and only exact
 * re-emissions are dropped.
 */
internal class ResumeDeduper {

    private var tailMs = NO_TAIL
    private val tailKeys = HashSet<String>()
    private var active = false
    private var overlapSeen = false

    /** Called when a new logcat process starts. Returns whether a resume overlap is expected —
     *  false before anything was captured. */
    fun arm(): Boolean {
        active = tailMs != NO_TAIL
        overlapSeen = false
        return active
    }

    /** True once this session re-delivered anything from before the resume point. An armed
     *  session that delivers lines but never trips this had its resume point rotate out of
     *  logd's ring (or cleared) — the in-between lines are gone. */
    fun tookOverlap(): Boolean = overlapSeen

    /** Records the identities of the newest-millisecond entries of a delivered batch. */
    fun remember(batch: List<LogEntry>) {
        val newest = batch.last().timeMs
        if (newest != tailMs) {
            tailMs = newest
            tailKeys.clear()
        }
        for (entry in batch) {
            if (entry.timeMs == tailMs && tailKeys.size < MAX_KEYS) tailKeys.add(key(entry))
        }
    }

    /** True when [entry] is a re-emission of a line already captured before the reconnect. */
    fun shouldDrop(entry: LogEntry): Boolean {
        if (!active) return false
        if (entry.timeMs > tailMs) {
            active = false
            return false
        }
        if (entry.timeMs < tailMs) {
            overlapSeen = true
            return true // strictly before the resume point: seen by definition
        }
        // Same millisecond as the resume point: drop only known re-emissions. Past the key cap the
        // set is incomplete, so fall back to dropping the whole millisecond — bounded memory wins.
        val drop = tailKeys.size >= MAX_KEYS || key(entry) in tailKeys
        if (drop) overlapSeen = true
        return drop
    }

    /** Continuation lines merge into [LogEntry.message] with '\n', but a re-emitted header line
     *  parses back to just its first line — so identity uses the first line only. */
    private fun key(entry: LogEntry) =
        "${entry.epochRaw}|${entry.tid}|${entry.message.substringBefore('\n')}"

    private companion object {
        const val NO_TAIL = -1L
        const val MAX_KEYS = 4_096
    }
}
