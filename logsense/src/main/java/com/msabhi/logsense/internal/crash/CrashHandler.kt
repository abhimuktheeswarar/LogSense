package com.msabhi.logsense.internal.crash

import android.content.Context
import com.msabhi.logsense.internal.notify.Notifications
import com.msabhi.logsense.internal.reader.LogBuffer

/** Chains to any previously installed handler (Crashlytics-style) — the app still crashes normally. */
internal class CrashHandler(
    private val context: Context,
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
        // Alert from the dying process itself so the crash shows immediately, not only on the next
        // launch. Best-effort: the notify() binder call usually completes before the process dies.
        // The next launch replaces this with a deep-linkable notification once the crash is ingested.
        runCatching {
            Notifications.postCrashAlert(
                context,
                title = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name },
                text = throwable.message?.takeIf { it.isNotBlank() } ?: "Tap to view details",
            )
        }
        previous?.uncaughtException(thread, throwable)
    }
}
