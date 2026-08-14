package com.msabhi.logsense.internal.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live adb signals, from sources any app on any Android can read — no permissions.
 *
 * [attached] — "is a development machine attached right now": the sticky `USB_STATE` broadcast
 * (connected + adb function active) or an emulator. Connection-granularity; drives desk mode
 * ([com.msabhi.logsense.LogSenseConfig.pauseLogsWhileAdbConnected]).
 *
 * [adbPossible] — "could an adb host connect at all": [attached], or either debugging toggle
 * (`adb_enabled` for USB/legacy-tcpip, `adb_wifi_enabled` for Android 11+ wireless debugging).
 * Toggle-granularity on purpose: it decides the reader's transport, where the safe error is
 * assuming adb when there is none — never the reverse. adb has no other doors into a device:
 * every transport runs through adbd, and adbd only comes up via these toggles or an emulator.
 * A key an OEM hid or renamed reads as 0 and the toggle door looks closed — the reader's
 * clear-signature self-heal covers that residue (see [LogcatReader]).
 */
internal class AdbAttachment(context: Context) {

    private val resolver = context.contentResolver
    private var usbAdb = false

    private val _attached = MutableStateFlow(isEmulator())
    val attached: StateFlow<Boolean> get() = _attached

    private val _adbPossible = MutableStateFlow(true) // assume the worst until first recompute
    val adbPossible: StateFlow<Boolean> get() = _adbPossible

    init {
        // The action string is a protected system broadcast without a public constant.
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                usbAdb = intent.getBooleanExtra("connected", false) &&
                    intent.getBooleanExtra("adb", false)
                recompute()
            }
        }
        // Sticky: registration delivers the current state immediately. Only the system can send
        // protected broadcasts, so NOT_EXPORTED is both safe and sufficient.
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        sticky?.let { receiver.onReceive(context, it) }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = recompute()
        }
        for (key in arrayOf(Settings.Global.ADB_ENABLED, WIRELESS_DEBUG_KEY)) {
            runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer) }
        }
        recompute()
    }

    private fun recompute() {
        _attached.value = usbAdb || isEmulator()
        _adbPossible.value = _attached.value ||
            globalSetting(Settings.Global.ADB_ENABLED) || globalSetting(WIRELESS_DEBUG_KEY)
    }

    /** Settings.Global is world-readable; a missing key (feature absent or never enabled) is 0. */
    private fun globalSetting(key: String): Boolean =
        runCatching { Settings.Global.getInt(resolver, key, 0) != 0 }.getOrDefault(false)

    private fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.PRODUCT.contains("sdk_gphone")

    private companion object {
        /** AOSP's Android 11+ wireless-debugging toggle; no public constant. */
        const val WIRELESS_DEBUG_KEY = "adb_wifi_enabled"
    }
}

/**
 * Whether a line should be kept in the buffer while desk mode pauses retention. Pure so the
 * policy is testable — and even paused, pinned tags always survive (they are the "must not
 * lose" set by definition). Two pin sets so the hot path never allocates a union per line.
 */
internal fun shouldRetainLine(
    retentionPaused: Boolean,
    configPins: Set<String>,
    userPins: Set<String>,
    tag: String,
): Boolean = !retentionPaused || tag in configPins || tag in userPins
