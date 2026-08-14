package com.msabhi.logsense.internal.reader

import java.io.ByteArrayOutputStream

/**
 * Splits the raw logcat byte stream into lines and forwards **only this process's own lines**
 * as Strings.
 *
 * This exists because a `readLine()`-based pipeline charset-decodes and allocates a String for
 * every line before any filter can run — and the uid-filtered stream still carries every
 * sibling process of this app plus whole burst backlogs. The GC churn of allocating garbage
 * Strings lands on the host app. Here the pid field is read directly from the bytes (the epoch
 * header is plain ASCII), so a foreign line costs a byte scan and nothing else: no decode, no
 * allocation.
 *
 * Continuation lines of multi-line messages have no header; they inherit the fate of the
 * header line above them, exactly like the parser attributes them downstream.
 */
internal class OwnLineSplitter(private val myPid: Int) {

    private val pending = ByteArrayOutputStream(256)
    private var lastWasOwn = false

    /** Feeds [len] bytes of [chunk]; complete own-lines are handed to [emit]. */
    fun feed(chunk: ByteArray, len: Int, emit: (String) -> Unit) {
        var start = 0
        for (i in 0 until len) {
            if (chunk[i] == NEWLINE) {
                if (pending.size() > 0) {
                    pending.write(chunk, start, i - start)
                    val line = pending.toByteArray()
                    pending.reset()
                    consume(line, 0, line.size, emit)
                } else {
                    consume(chunk, start, i - start, emit)
                }
                start = i + 1
            }
        }
        if (start < len) pending.write(chunk, start, len - start)
        // logcat entries cap at ~5KB; anything past this is a corrupt stream — drop and resync.
        if (pending.size() > MAX_LINE_BYTES) pending.reset()
    }

    private fun consume(b: ByteArray, off: Int, rawLen: Int, emit: (String) -> Unit) {
        val len = if (rawLen > 0 && b[off + rawLen - 1] == CR) rawLen - 1 else rawLen
        if (len == 0) return
        val pid = scanPidBytes(b, off, len)
        val own = when (pid) {
            null -> lastWasOwn // continuation or divider: inherits, never flips, the header's fate
            myPid -> true.also { lastWasOwn = true }
            else -> false.also { lastWasOwn = false }
        }
        if (own) emit(String(b, off, len, Charsets.UTF_8))
    }

    /** [scanLogcatPid] on raw ASCII bytes: `  1737541357.983  1234  ...` → 1234, else null. */
    private fun scanPidBytes(b: ByteArray, off: Int, len: Int): Int? {
        var i = off
        val end = off + len
        while (i < end && b[i] == SPACE) i++
        var sawDigit = false
        var sawDot = false
        while (i < end) {
            val c = b[i]
            when {
                c in DIGIT_0..DIGIT_9 -> { sawDigit = true; i++ }
                c == DOT && !sawDot -> { sawDot = true; i++ }
                else -> break
            }
        }
        if (!sawDigit || !sawDot || i >= end || b[i] != SPACE) return null
        while (i < end && b[i] == SPACE) i++
        var pid = 0
        var pidDigits = 0
        while (i < end && b[i] in DIGIT_0..DIGIT_9) {
            pid = pid * 10 + (b[i] - DIGIT_0)
            i++
            pidDigits++
        }
        if (pidDigits == 0 || i >= end || b[i] != SPACE) return null
        return pid
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val SPACE = ' '.code.toByte()
        const val DOT = '.'.code.toByte()
        const val DIGIT_0 = '0'.code.toByte()
        const val DIGIT_9 = '9'.code.toByte()
        const val MAX_LINE_BYTES = 64 * 1024
    }
}
