package com.msabhi.logsense.internal.reader

import android.os.Process
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.IOException

/**
 * Streams this process's own logcat (`logcat --pid=<myPid> -v epoch` — no permission needed)
 * and feeds parsed batches into [buffer]. Restarts the logcat process if it dies, resuming
 * from the last seen timestamp via -T so no lines are lost across restarts.
 */
internal class LogcatReader(
    private val buffer: LogBuffer,
    private val onBatch: (List<LogEntry>) -> Unit,
    private val captureEnabled: StateFlow<Boolean> = MutableStateFlow(true),
) {

    private val parser = LogParser()
    private var lastEpochRaw: String? = null
    private var lastTimeMs = 0L

    /** -T re-emits lines at the resume timestamp; drop those until a new line is seen. */
    private var dedupeActive = false

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val process = startProcess()
            if (process == null) {
                delay(RESTART_DELAY_MS)
                continue
            }
            dedupeActive = lastEpochRaw != null
            try {
                val reader = process.inputStream.bufferedReader()
                while (currentCoroutineContext().isActive) {
                    // ponytail: while paused the logcat process keeps running and its pipe buffers;
                    // a very long pause can drop the oldest lines. Fine for a debug tool.
                    if (!captureEnabled.value) captureEnabled.first { it }
                    val batch = ArrayList<LogEntry>()
                    val eof = drain(reader, batch)
                    if (batch.isNotEmpty()) {
                        lastEpochRaw = batch.last().epochRaw
                        lastTimeMs = batch.last().timeMs
                        buffer.append(batch)
                        onBatch(batch)
                    }
                    if (eof) break
                    if (batch.isEmpty()) delay(POLL_DELAY_MS)
                }
            } catch (_: IOException) {
                // stream broke — fall through to restart
            } finally {
                process.destroy()
            }
            delay(RESTART_DELAY_MS)
        }
    }

    /** Reads all currently available lines. Returns true when the stream hit EOF (process died). */
    private suspend fun drain(reader: BufferedReader, batch: MutableList<LogEntry>): Boolean {
        while (reader.ready()) {
            val raw = reader.readLine() ?: return true
            if (parser.isDivider(raw)) continue
            val entry = parser.parse(raw)
            when {
                entry == null -> // continuation of a multi-line message
                    if (batch.isNotEmpty()) {
                        val last = batch.removeAt(batch.lastIndex)
                        batch.add(last.copy(message = last.message + "\n" + raw))
                    } else {
                        buffer.appendContinuation(raw)
                    }
                dedupeActive && entry.timeMs <= lastTimeMs -> Unit // already seen before restart
                else -> {
                    dedupeActive = false
                    batch.add(entry)
                }
            }
        }
        return false
    }

    private fun startProcess(): java.lang.Process? = try {
        val command = buildList {
            add("logcat")
            add("-v")
            add("epoch")
            add("--pid=${Process.myPid()}")
            lastEpochRaw?.let { add("-T"); add(it) }
        }
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val POLL_DELAY_MS = 100L
        const val RESTART_DELAY_MS = 1_000L
    }
}
