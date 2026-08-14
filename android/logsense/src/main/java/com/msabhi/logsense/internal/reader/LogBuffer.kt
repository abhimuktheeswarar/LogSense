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
internal class LogBuffer(maxLines: Int, private val pinnedTags: () -> Set<String> = { emptySet() }) {

    /** Floored at one line. A zero or negative cap makes the trim loop in [append] keep popping past
     *  an empty deque, so the buffer defends itself rather than trusting every caller to clamp. */
    private val maxLines = maxLines.coerceAtLeast(1)

    private val deque = ArrayDeque<LogEntry>()

    /** Evicted lines whose tag is pinned — they outlive the cap instead of dropping. Everything
     *  here is older than everything in [deque] (eviction pops the oldest), so the published view
     *  is simply pinned + deque, still in order. Own cap so a floody pinned tag stays bounded. */
    private val pinned = ArrayDeque<LogEntry>()

    /** Published size (pinned + ring), readable without the lock — pacing decisions only. */
    @Volatile
    var approxSize: Int = 0
        private set
    private val mutex = Mutex()
    private var dirty = false
    private val _snapshot = MutableStateFlow<List<LogEntry>>(emptyList())
    val snapshot: StateFlow<List<LogEntry>> get() = _snapshot

    /*
     * Counters accumulate per append but their StateFlows are published only on [flush] ticks:
     * during a flood at the cap every batch bumps both, and per-batch emissions recompose the
     * whole Logs tab and top bar at batch rate — measured as a main-thread storm. The raw values
     * stay readable any time via [totalReceivedNow] (used by the capture notification).
     */
    private var receivedCount = 0
    private var evictedCount = 0

    @Volatile
    var totalReceivedNow: Int = 0
        private set

    /** Cumulative entries seen this run — keeps climbing past the buffer cap; reset by [clear]. */
    /** Lines currently held (ring + pinned side-store). Shown while paused — "what is there to
     *  browse" — and it never contradicts the configured buffer limit the way a cumulative
     *  count would. */
    private val _held = MutableStateFlow(0)
    val held: StateFlow<Int> get() = _held

    /** Capture rate over the last ~[RATE_WINDOW_MS], published on the flush tick. This is the
     *  live top-bar number: a full ring shows a static held count, but the rate keeps saying
     *  what the app is doing right now — quiet, steady, or spamming. */
    private val _ratePerSec = MutableStateFlow(0)
    val ratePerSec: StateFlow<Int> get() = _ratePerSec
    private var rateWindowStart = 0L
    private var rateWindowReceived = 0

    /** Cumulative entries pushed out by the cap; reset by [clear]. Surfaced in the UI so "no search
     *  match" is never silently ambiguous with "the line was evicted". */
    private val _evicted = MutableStateFlow(0)
    val evicted: StateFlow<Int> get() = _evicted

    /** Safe to call from any thread, including a crashing one (used for crash log-context). */
    fun currentSnapshot(): List<LogEntry> = _snapshot.value

    /** Crash-time view: a direct copy when the lock is free (the usual case), else the last flush.
     *  Runs on a crashing thread, so it must neither suspend nor block. Needed because [flush]
     *  skips materializing while nothing subscribes — a crash with the UI never opened would
     *  otherwise see an empty context. */
    fun crashSnapshot(): List<LogEntry> =
        if (mutex.tryLock()) {
            try {
                materialize()
            } finally {
                mutex.unlock()
            }
        } else {
            _snapshot.value
        }

    /** Callers hold [mutex]. */
    private fun materialize(): List<LogEntry> =
        if (pinned.isEmpty()) {
            deque.toList()
        } else {
            ArrayList<LogEntry>(pinned.size + deque.size).apply {
                addAll(pinned)
                addAll(deque)
            }
        }

    suspend fun append(entries: List<LogEntry>) = mutex.withLock {
        deque.addAll(entries)
        var dropped = 0
        // Resolved once per batch, not per evicted line — the provider unions config + UI pins.
        val pins = if (deque.size > maxLines) pinnedTags() else emptySet()
        while (deque.size > maxLines) {
            val evicted = deque.removeFirst()
            if (evicted.tag in pins) {
                pinned.addLast(evicted)
                if (pinned.size > MAX_PINNED_LINES) {
                    pinned.removeFirst()
                    dropped++
                }
            } else {
                dropped++
            }
        }
        evictedCount += dropped
        receivedCount += entries.size
        totalReceivedNow = receivedCount
        approxSize = pinned.size + deque.size
        dirty = true
    }

    /** Attaches a continuation line to the newest entry (multi-line log output). */
    suspend fun appendContinuation(raw: String) = mutex.withLock {
        val last = deque.removeLastOrNull() ?: return@withLock
        deque.addLast(last.copy(message = last.message + "\n" + raw))
        dirty = true
    }

    /** Publishes accumulated changes to observers. Cheap no-op when nothing changed since the last
     *  flush — or when nothing is subscribed: materializing tens of thousands of entries 10×/sec
     *  is pure host-app cost while the LogSense UI is closed. `dirty` stays set, so the first
     *  flush after a subscriber arrives publishes everything accumulated meanwhile. */
    suspend fun flush(now: Long = System.currentTimeMillis()) = mutex.withLock {
        // Counter publications are cheap Int emissions — always land them on the tick, so the
        // top-bar count and eviction banner stay fresh at flush cadence, never at batch cadence.
        _evicted.value = evictedCount
        _held.value = approxSize
        if (rateWindowStart == 0L) {
            rateWindowStart = now
            rateWindowReceived = receivedCount
        } else if (now - rateWindowStart >= RATE_WINDOW_MS) {
            _ratePerSec.value =
                ((receivedCount - rateWindowReceived) * 1000L / (now - rateWindowStart)).toInt()
            rateWindowStart = now
            rateWindowReceived = receivedCount
        }
        if (dirty && _snapshot.subscriptionCount.value > 0) {
            _snapshot.value = materialize()
            dirty = false
        }
    }

    /** Drops the captured stream — pinned lines included. Pinning guards against *involuntary*
     *  loss (ring rotation); an explicit Clear is the user saying everything goes. */
    suspend fun clear() = mutex.withLock {
        deque.clear()
        pinned.clear()
        _snapshot.value = emptyList()
        receivedCount = 0
        evictedCount = 0
        totalReceivedNow = 0
        _evicted.value = 0
        _held.value = 0
        _ratePerSec.value = 0
        rateWindowStart = 0L
        rateWindowReceived = 0
        approxSize = 0
        dirty = false
    }

    private companion object {
        const val MAX_PINNED_LINES = 10_000

        /** Wide enough to absorb the poll transport's dump cadence, so the rate reads steady
         *  instead of flickering between zero and a spike on every dump. */
        const val RATE_WINDOW_MS = 2_000L
    }
}
