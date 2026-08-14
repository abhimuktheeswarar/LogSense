package com.msabhi.logsense.internal.signals

import com.msabhi.logsense.LogSenseConfig
import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.logs.LogQuery
import com.msabhi.logsense.internal.reader.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One reported signal. [entryId] is the [LogEntry] it was matched on, so the UI can jump straight
 * to that line — null for signals reported by the platform rather than matched in the log, which
 * have no line to jump to.
 */
internal data class SignalHit(
    val signal: Signal,
    val entryId: Long?,
    val timeMs: Long,
    val tag: String,
    /** First line of the message, truncated — the full text lives in the buffer entry. */
    val preview: String,
)

/**
 * Matches incoming log batches against the [BuiltInSignals] catalog. Fed from the same `onBatch`
 * hook as `AnalyticsDetector`, so matching happens once per line on the reader's thread rather than
 * per tab on every recomposition.
 *
 * Hits are in-memory and die with the process, exactly like the log buffer they point into — that's
 * what keeps jump-to-line honest.
 */
internal class SignalDetector(
    private val config: LogSenseConfig,
    private val muted: () -> Set<String> = { emptySet() },
    /** True while LogSense's own UI is in the foreground — see the jank suppression in [process]. */
    private val ownUiVisible: () -> Boolean = { false },
) {

    // Predicates are rebuilt only when the muted set changes (the catalog and config are constant),
    // mirroring AnalyticsDetector.extractors().
    private var cachedMuted: Set<String>? = null
    private var compiled: List<Pair<Signal, (LogEntry) -> Boolean>> = emptyList()

    private val _hits = MutableStateFlow<List<SignalHit>>(emptyList())
    val hits: StateFlow<List<SignalHit>> get() = _hits

    /** Hits arrive from the reader thread, the main thread and the IO scope — guard the append. */
    private val lock = Any()

    private fun rules(): List<Pair<Signal, (LogEntry) -> Boolean>> {
        val mutedNow = muted()
        if (mutedNow != cachedMuted) {
            cachedMuted = mutedNow
            compiled = BuiltInSignals.catalog(config.customSignals)
                // A blank query compiles to "no terms", which matches every line — never a real rule.
                .filter { it.query.isNotBlank() && it.id !in mutedNow }
                .map { it to LogQuery.compile(LogFilter(query = it.query)) }
        }
        return compiled
    }

    /**
     * ponytail: ~33 predicates × 1–2 substring scans per line, first match wins. At a 5k line/sec
     * burst that's roughly 150ms of one background core per second — fine for a debug tool. If it
     * ever shows up in a profile, bucket the rules by tag and only run the bucket a line can match.
     */
    fun process(batch: List<LogEntry>) {
        val rules = rules()
        if (rules.isEmpty()) return
        // Frame-skip/long-frame lines produced while LogSense's own UI is foreground measure
        // LogSense, not the host — and reporting them feeds itself: each jank hit updates the
        // Signals UI, which janks, which logs another line. Suppress just those two while visible.
        val suppressSelfJank = ownUiVisible()
        val found = batch.mapNotNull { entry ->
            val signal = rules.firstOrNull { (_, matches) -> matches(entry) }?.first ?: return@mapNotNull null
            if (suppressSelfJank && signal.id in SELF_JANK_IDS) return@mapNotNull null
            SignalHit(
                signal = signal,
                entryId = entry.id,
                timeMs = entry.timeMs,
                tag = entry.tag,
                preview = entry.message.substringBefore('\n').take(PREVIEW_CHARS),
            )
        }
        add(found)
    }

    /**
     * Records a signal the log stream can't show us — a process exit reason, an activity coming up.
     * Safe to call from any thread; [process] runs on the reader's.
     */
    fun record(signal: Signal, timeMs: Long, detail: String) {
        if (signal.id in muted()) return
        add(listOf(SignalHit(signal, entryId = null, timeMs = timeMs, tag = signal.category.label, preview = detail)))
    }

    fun clear() {
        synchronized(lock) {
            current = emptyList()
            _hits.value = emptyList()
            dirty = false
        }
    }

    /*
     * Publication is rate-limited: every [_hits] emission recomposes the scaffold badge on every
     * tab and the whole Signals screen, so per-batch emissions during a chatty stream turn into a
     * once-a-second recomposition churn (measured as continuous skipped frames in a heavy host).
     * A quiet-period hit still publishes immediately; bursts defer to [publish], ticked by the
     * core's flush loop.
     */
    private var current: List<SignalHit> = emptyList()

    @Volatile
    private var dirty = false

    @Volatile
    private var lastPublishMs = 0L

    private fun add(found: List<SignalHit>) {
        if (found.isEmpty()) return
        synchronized(lock) {
            val merged = current + found
            current = if (merged.size > MAX_HITS) merged.takeLast(MAX_HITS) else merged
            val now = System.currentTimeMillis()
            if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                _hits.value = current
                lastPublishMs = now
                dirty = false
            } else {
                dirty = true
            }
        }
    }

    /** Trailing edge of the rate limit — called from the core's flush ticker. */
    fun publish() {
        if (!dirty) return
        synchronized(lock) {
            _hits.value = current
            lastPublishMs = System.currentTimeMillis()
            dirty = false
        }
    }

    private companion object {
        /** The buffer evicts too, so keeping more hits than this only accumulates dead pointers. */
        const val MAX_HITS = 500
        const val PREVIEW_CHARS = 200
        const val PUBLISH_INTERVAL_MS = 500L

        /** Suppressed while LogSense's own UI is foreground — they'd be measuring LogSense. */
        val SELF_JANK_IDS = setOf("anr.skipped_frames", "anr.davey")
    }
}
