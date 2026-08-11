package com.msabhi.logsense.internal.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Capped in-memory ring buffer of log entries. Lost on process death by design.
 *
 * Ingest ([append]/[appendContinuation]) only mutates the deque and marks it dirty — the expensive
 * `toList()` + [StateFlow] emit happens in [flush], which a caller ticks at a bounded rate. This
 * keeps a burst of hundreds of lines/sec from re-copying and re-rendering the whole list per batch.
 */
internal class LogBuffer(maxLines: Int) {

    /** Floored at one line. A zero or negative cap makes the trim loop in [append] keep popping past
     *  an empty deque, so the buffer defends itself rather than trusting every caller to clamp. */
    private val maxLines = maxLines.coerceAtLeast(1)

    private val deque = ArrayDeque<LogEntry>()
    private val mutex = Mutex()
    private var dirty = false
    private val _snapshot = MutableStateFlow<List<LogEntry>>(emptyList())
    val snapshot: StateFlow<List<LogEntry>> get() = _snapshot

    /** Cumulative entries seen this run — keeps climbing past the buffer cap; reset by [clear]. */
    private val _totalReceived = MutableStateFlow(0)
    val totalReceived: StateFlow<Int> get() = _totalReceived

    /** Safe to call from any thread, including a crashing one (used for crash log-context). */
    fun currentSnapshot(): List<LogEntry> = _snapshot.value

    suspend fun append(entries: List<LogEntry>) = mutex.withLock {
        deque.addAll(entries)
        while (deque.size > maxLines) deque.removeFirst()
        _totalReceived.value += entries.size
        dirty = true
    }

    /** Attaches a continuation line to the newest entry (multi-line log output). */
    suspend fun appendContinuation(raw: String) = mutex.withLock {
        val last = deque.removeLastOrNull() ?: return@withLock
        deque.addLast(last.copy(message = last.message + "\n" + raw))
        dirty = true
    }

    /** Publishes accumulated changes to observers. Cheap no-op when nothing changed since last flush. */
    suspend fun flush() = mutex.withLock {
        if (dirty) {
            _snapshot.value = deque.toList()
            dirty = false
        }
    }

    suspend fun clear() = mutex.withLock {
        deque.clear()
        _snapshot.value = emptyList()
        _totalReceived.value = 0
        dirty = false
    }
}
