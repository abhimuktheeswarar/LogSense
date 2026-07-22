package com.msabhi.logsense.internal.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Capped in-memory ring buffer of log entries. Lost on process death by design. */
internal class LogBuffer(private val maxLines: Int) {

    private val deque = ArrayDeque<LogEntry>()
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<List<LogEntry>>(emptyList())
    val snapshot: StateFlow<List<LogEntry>> get() = _snapshot

    /** Safe to call from any thread, including a crashing one. */
    fun currentSnapshot(): List<LogEntry> = _snapshot.value

    suspend fun append(entries: List<LogEntry>) = mutex.withLock {
        deque.addAll(entries)
        while (deque.size > maxLines) deque.removeFirst()
        publish()
    }

    /** Attaches a continuation line to the newest entry (multi-line log output). */
    suspend fun appendContinuation(raw: String) = mutex.withLock {
        val last = deque.removeLastOrNull() ?: return@withLock
        deque.addLast(last.copy(message = last.message + "\n" + raw))
        publish()
    }

    suspend fun clear() = mutex.withLock {
        deque.clear()
        publish()
    }

    private fun publish() {
        _snapshot.value = deque.toList()
    }
}
