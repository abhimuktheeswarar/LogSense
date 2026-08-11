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
        // The previous handler is the host's crash reporter, and it runs from a `finally` for a
        // reason: if anything of ours threw first, their reporter would never see the crash and
        // LogSense would be the reason a real fault went unreported. Nothing here may mask the
        // original throwable either — the process is dying and this is our last chance to be quiet.
        try {
            val lines = buffer.currentSnapshot().takeLast(contextLines.coerceAtLeast(0))
            store.writeCrash(thread, throwable, lines)
            // Alert from the dying process itself so the crash shows immediately, not only on the
            // next launch. Best-effort: the notify() binder call usually completes before the
            // process dies. The next launch replaces this with a deep-linkable notification.
            Notifications.postCrashAlert(
                context,
                title = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name },
                text = throwable.message?.takeIf { it.isNotBlank() } ?: "Tap to view details",
            )
        } catch (_: Throwable) {
            // Deliberately silent: logging or rethrowing here risks losing the host's report.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}
