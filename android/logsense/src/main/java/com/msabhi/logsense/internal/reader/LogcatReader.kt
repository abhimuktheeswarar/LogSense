package com.msabhi.logsense.internal.reader

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Streams this process's own logcat and feeds parsed batches into [buffer], over one of two
 * transports:
 *
 *  - **Follow** — a long-lived `logcat` child, push-based, zero loss window. But logd's clear
 *    (`logcat -c`, e.g. Android Studio's Clear button) stalls for seconds on any connected
 *    unprivileged (uid-filtered) follow reader and kicks every attached stream — Studio's
 *    included (measured 4.4 s vs 0.12 s). So follow runs only while [pollMode] says adb is
 *    provably impossible — nobody who could run a clear exists.
 *  - **Poll** — short-lived `logcat -d -T <last>` dumps on a cadence. A reader only exists for
 *    milliseconds at a time, so a clear has nothing to stall, on any Android, with no
 *    permissions. The identical pipeline consumes both transports: events, signals, and pins
 *    flow the same, lines just arrive in sub-second batches.
 *
 * Restarts and polls both resume from the last seen timestamp via -T; [ResumeDeduper] drops the
 * re-emitted overlap so no line is lost or duplicated across sessions. When an armed session
 * delivers lines but the expected overlap never re-appears, the lines between sessions rotated
 * out of logd's ring (or the device log was cleared) — [onPossibleGap] surfaces that instead of
 * losing lines silently.
 *
 * Self-heal backstop: if a *follow* session dies without LogSense killing it, that is the kick
 * signature of a clear — meaning something with shell access exists that [pollMode] didn't see
 * (an OEM skin hiding a toggle key, some exotic transport). The reader prefers poll for
 * [SELF_HEAL_POLL_MS] after each such kick, so even a wrong "no adb" answer degrades to the
 * safe transport after one incident.
 *
 * The read path is split in two on purpose. If the child's ~64KB stdout pipe ever fills, the
 * child blocks, stops pulling from logd, and logd overwrites the unread lines — logs lost
 * silently, and precisely during bursts, when they matter most. So a dedicated pump does
 * nothing but blocking reads into [LINE_QUEUE_CAP] lines of in-heap slack, and parsing plus
 * detector work happens downstream where it can never stall the pipe.
 */
internal class LogcatReader(
    private val buffer: LogBuffer,
    private val onBatch: (List<LogEntry>) -> Unit,
    private val captureEnabled: StateFlow<Boolean> = MutableStateFlow(true),
    /** True → poll transport (adb possible); false → follow transport (adb provably impossible). */
    private val pollMode: StateFlow<Boolean> = MutableStateFlow(true),
    /** Tightens the poll cadence while someone is actually watching the LogSense UI. */
    private val uiVisible: () -> Boolean = { false },
    /** Lines between sessions are gone from logd (ring rotation or a device clear). */
    private val onPossibleGap: () -> Unit = {},
    /** Retention policy for the buffer only — detectors always see the full batch. Lets desk
     *  mode keep events/signals/pins recording while the rolling line buffer pauses. */
    private val retainLine: (LogEntry) -> Boolean = { true },
) {

    private val parser = LogParser()
    private val deduper = ResumeDeduper()
    private val myPid = Process.myPid()
    private var lastEpochRaw: String? = null

    /** True while the previous line was dropped (foreign pid or reconnect re-emission), so its
     *  continuation lines (which parse as null) drop with it instead of gluing onto the last
     *  kept entry. */
    private var lastLineDropped = false

    private var sessionDelivered = false
    private var sessionLines = 0

    /** Set by the watchers before destroying the child, so an EOF can be told apart from a kick. */
    private var endedByUs = false

    /** Until this uptime, poll even if [pollMode] says follow — see the class KDoc. */
    private var preferPollUntil = 0L

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            // Paused: no child process at all, so nothing can back up anywhere. Resume restarts
            // with -T at the last seen line, recovering the paused period from what logd still has.
            if (!captureEnabled.value) captureEnabled.first { it }
            val dump = pollMode.value || SystemClock.elapsedRealtime() < preferPollUntil
            val started = session(dump)
            delay(
                when {
                    !started -> RESTART_DELAY_MS
                    dump -> pollDelayMs()
                    else -> RESTART_DELAY_MS
                },
            )
        }
    }

    /** One logcat child from start to EOF — a bounded dump or an open-ended follow. */
    private suspend fun session(dump: Boolean): Boolean {
        val process = startProcess(dump) ?: return false
        val expectOverlap = deduper.arm()
        lastLineDropped = false
        sessionDelivered = false
        sessionLines = 0
        endedByUs = false
        val lines = Channel<String>(LINE_QUEUE_CAP)
        coroutineScope {
            launch(Dispatchers.IO) { // the pump
                try {
                    // Bytes, not readLine(): the uid-filtered stream still carries every sibling
                    // process of this app plus burst backlogs, and decoding + allocating a String
                    // per line before filtering is garbage this process doesn't need to make. The
                    // splitter rejects foreign lines at byte level; only kept lines become Strings.
                    val splitter = OwnLineSplitter(myPid)
                    val stream = process.inputStream
                    val chunk = ByteArray(PUMP_CHUNK_BYTES)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        // Blocks when the queue is full — backpressure with far more slack
                        // than the pipe, and the consumer never sleeps while lines wait.
                        splitter.feed(chunk, n) { line -> lines.trySendBlocking(line) }
                    }
                } catch (_: IOException) {
                    // stream broke — the close below ends consume() and we restart with -T
                } finally {
                    lines.close()
                }
            }
            val pauseWatch = launch {
                captureEnabled.first { !it }
                endedByUs = true
                process.destroy() // EOFs the pump, which ends consume()
            }
            val modeWatch = if (dump) null else launch {
                pollMode.first { it } // adb became possible mid-follow: get out of the way now
                endedByUs = true
                process.destroy()
            }
            try {
                consume(lines)
            } finally {
                // Runs on cancellation too: destroying the process unblocks the pump's read,
                // cancelling the channel unblocks a pump stuck on a full queue.
                pauseWatch.cancel()
                modeWatch?.cancel()
                endedByUs = endedByUs || !currentCoroutineContext().isActive
                process.destroy()
                lines.cancel()
            }
        } // coroutineScope joins the pump, which the destroy/cancel above unblocked
        if (expectOverlap && sessionDelivered && !deduper.tookOverlap()) onPossibleGap()
        if (!dump && !endedByUs) {
            // A follow child we never killed EOFed: the kick signature of a device-log clear.
            preferPollUntil = SystemClock.elapsedRealtime() + SELF_HEAL_POLL_MS
        }
        return true
    }

    private fun pollDelayMs(): Long = when {
        sessionLines >= BUSY_LINES -> POLL_BUSY_MS // volume is high; shrink the loss window
        uiVisible() -> POLL_VISIBLE_MS
        else -> POLL_IDLE_MS
    }

    /** Receives raw lines until the channel closes (child died or was killed), parsing and
     *  delivering them in bounded batches. Suspends when idle — no polling. */
    private suspend fun consume(lines: ReceiveChannel<String>) {
        while (true) {
            val first = lines.receiveCatching().getOrNull() ?: return
            val batch = ArrayList<LogEntry>()
            ingest(first, batch)
            while (batch.size < MAX_BATCH_LINES) {
                val raw = lines.tryReceive().getOrNull() ?: break
                ingest(raw, batch)
            }
            if (batch.isNotEmpty()) {
                sessionDelivered = true
                sessionLines += batch.size
                lastEpochRaw = batch.last().epochRaw
                deduper.remember(batch)
                // Filtered after continuation-merging, so a retained line keeps its full text.
                val kept = batch.filter(retainLine)
                if (kept.isNotEmpty()) buffer.append(kept)
                onBatch(batch)
            }
        }
    }

    private suspend fun ingest(raw: String, batch: MutableList<LogEntry>) {
        if (parser.isDivider(raw)) return
        // Same-uid sibling processes (a host app's :remote processes) share the uid-filtered
        // stream; a scan-cost pid check rejects their header lines before the parser ever runs.
        val scannedPid = scanLogcatPid(raw)
        if (scannedPid != null && scannedPid != myPid) {
            lastLineDropped = true
            return
        }
        val entry = parser.parse(raw)
        when {
            entry == null -> when { // continuation of a multi-line message
                lastLineDropped -> Unit // its header line was a re-emission; this was captured with it
                batch.isNotEmpty() -> {
                    val last = batch.removeAt(batch.lastIndex)
                    batch.add(last.copy(message = last.message + "\n" + raw))
                }
                else -> buffer.appendContinuation(raw)
            }
            entry.pid != myPid -> lastLineDropped = true
            deduper.shouldDrop(entry) -> lastLineDropped = true
            else -> {
                lastLineDropped = false
                batch.add(entry)
            }
        }
    }

    private fun startProcess(dump: Boolean): java.lang.Process? = try {
        val command = buildList {
            add("logcat")
            if (dump) add("-d")
            add("-v")
            add("epoch")
            lastEpochRaw?.let { add("-T"); add(it) }
        }
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val RESTART_DELAY_MS = 1_000L

        /** Poll cadence: watching the UI / idle in the background / a burst is flowing. */
        const val POLL_VISIBLE_MS = 700L
        const val POLL_IDLE_MS = 2_000L
        const val POLL_BUSY_MS = 400L

        /** One session's line count that counts as a burst for [POLL_BUSY_MS]. */
        const val BUSY_LINES = 1_000

        /** How long a follow kick keeps the reader on the poll transport. */
        const val SELF_HEAL_POLL_MS = 30 * 60 * 1_000L

        /** ~40× the pipe's depth; a few MB of transient heap at worst, empty outside bursts. */
        const val LINE_QUEUE_CAP = 16_384

        /** Caps one batch's size so a long backlog still flushes to the buffer incrementally. */
        const val MAX_BATCH_LINES = 2_000

        /** Big reads keep syscall count low while the splitter walks the bytes. */
        const val PUMP_CHUNK_BYTES = 32 * 1024
    }
}

/**
 * Extracts the pid column of a `logcat -v epoch` header line without regex:
 * `  1737541357.983  1234  1300 D Tag : message` → 1234. Returns null for anything not shaped
 * like a header (continuation lines of multi-line messages) — those take the full parse path.
 */
internal fun scanLogcatPid(raw: String): Int? {
    var i = 0
    val n = raw.length
    while (i < n && raw[i] == ' ') i++
    var sawDigit = false
    var sawDot = false
    while (i < n) {
        val c = raw[i]
        when {
            c in '0'..'9' -> { sawDigit = true; i++ }
            c == '.' && !sawDot -> { sawDot = true; i++ }
            else -> break
        }
    }
    if (!sawDigit || !sawDot || i >= n || raw[i] != ' ') return null
    while (i < n && raw[i] == ' ') i++
    var pid = 0
    var pidDigits = 0
    while (i < n && raw[i] in '0'..'9') {
        pid = pid * 10 + (raw[i] - '0')
        i++
        pidDigits++
    }
    if (pidDigits == 0 || i >= n || raw[i] != ' ') return null
    return pid
}
