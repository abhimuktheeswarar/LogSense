package com.msabhi.logsense.internal.crash

import com.msabhi.logsense.internal.reader.LogBuffer

/** Chains to any previously installed handler (Crashlytics-style) — the app still crashes normally. */
internal class CrashHandler(
    private val store: CrashFileStore,
    private val contextLines: Int,
    private val buffer: LogBuffer,
) : Thread.UncaughtExceptionHandler {

    private val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        store.writeCrash(thread, throwable, buffer.currentSnapshot().takeLast(contextLines))
        previous?.uncaughtException(thread, throwable)
    }
}
