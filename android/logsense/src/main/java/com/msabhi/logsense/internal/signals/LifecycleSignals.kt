package com.msabhi.logsense.internal.signals

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.view.doOnPreDraw

/**
 * Reports activity start and the first frame as signals. The platform logs both, but only from
 * `system_server`, which a single-process logcat read can't see — so LogSense observes them
 * directly instead. The first-frame timing is measured from the process start clock, which makes it
 * a real cold-start number rather than a scraped one.
 *
 * LogSense's own UI is skipped: opening the viewer is not an event about the host app.
 */
internal object LifecycleSignals {

    fun install(app: Application, signals: SignalDetector) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            private var firstFrameReported = false

            // This runs on the host's main thread, inside their activity's start. Anything thrown
            // here would surface as a crash in *their* lifecycle, at every activity transition, so
            // the whole body is guarded — including the pre-draw callback, which runs later on the
            // same thread and would be just as fatal.
            override fun onActivityStarted(activity: Activity) {
                runCatching {
                    val name = activity.javaClass.name
                    if (name.startsWith(OWN_PACKAGE)) return
                    signals.record(
                        BuiltInSignals.ACTIVITY_START,
                        System.currentTimeMillis(),
                        activity.javaClass.simpleName,
                    )
                    if (firstFrameReported) return
                    firstFrameReported = true
                    activity.window.decorView.doOnPreDraw {
                        runCatching {
                            val elapsed = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                            signals.record(
                                BuiltInSignals.FIRST_FRAME,
                                System.currentTimeMillis(),
                                "${activity.javaClass.simpleName} · ${elapsed} ms after process start",
                            )
                        }
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private const val OWN_PACKAGE = "com.msabhi.logsense."
}
