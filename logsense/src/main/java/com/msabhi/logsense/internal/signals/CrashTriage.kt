package com.msabhi.logsense.internal.signals

import com.msabhi.logsense.internal.data.CrashEntity

/**
 * A first read of a crash.
 *
 * [appFrame] is the part that always earns its place: the topmost frame in *your* package, lifted
 * out of forty lines of framework trace. [note] is deliberately rare — it appears only for faults
 * whose remedy is not obvious from the exception name. Telling a developer that a
 * NullPointerException means something was null is noise dressed as help, so nothing is shown at all
 * for the exceptions that explain themselves.
 */
internal data class Triage(
    /** The topmost frame belonging to the host app, or null when the trace has none. */
    val appFrame: String?,
    /** What to check, when that isn't already obvious from the exception. Usually null. */
    val note: String?,
)

internal fun triage(crash: CrashEntity, appPackage: String): Triage {
    val simpleName = crash.exceptionClass
        ?.substringAfterLast('.')
        ?.substringAfterLast('$')
        .orEmpty()
    return Triage(
        appFrame = appFrame(crash.stacktrace, appPackage),
        note = NOTES[simpleName] ?: byType(crash.type),
    )
}

/**
 * The topmost `at <appPackage>…` frame. Falls back to progressively shorter package prefixes so a
 * build with an `applicationIdSuffix` (`com.foo.app.debug` running `com.foo.app` classes) still
 * finds its own code. LogSense's own frames never count — they are never the bug being triaged.
 */
internal fun appFrame(stacktrace: String, appPackage: String): String? {
    val lines = stacktrace.lineSequence().map { it.trim() }.filter { it.startsWith("at ") }.toList()
    val parts = appPackage.split('.')
    for (depth in parts.size downTo 2) {
        val prefix = "at " + parts.take(depth).joinToString(".") + "."
        val frame = lines.firstOrNull { it.startsWith(prefix) && !it.startsWith(LOGSENSE_PREFIX) }
        if (frame != null) return frame.removePrefix("at ")
    }
    return null
}

private const val LOGSENSE_PREFIX = "at com.msabhi.logsense."

/** ANR and native faults carry no usable JVM trace, so a pointer is worth more than nothing. */
private fun byType(type: String): String? = when (type) {
    "ANR" -> "Check the signals logged just before this — skipped frames, long monitor contention " +
        "and blocking GCs all point at what held the main thread."

    "NATIVE" -> "SIGSEGV is a bad pointer, SIGABRT an explicit abort or failed assert, SIGBUS a " +
        "misaligned or missing mapping."

    else -> null
}

/**
 * Only faults whose remedy isn't obvious from the name. Adding NullPointerException here would mean
 * writing "something was null" under a crash that already says NullPointerException — if an entry
 * would only restate the exception, it does not belong.
 */
private val NOTES: Map<String, String> = mapOf(
    "UninitializedPropertyAccessException" to
        "A lateinit read before assignment — usually the read moved ahead of onCreate/onViewCreated, " +
        "or happens after the owner was destroyed. `isInitialized` guards it when the order can't change.",
    "OutOfMemoryError" to
        "The message gives the size of the failed allocation. Check the memory signals just before this.",
    "SQLiteConstraintException" to
        "A unique, not-null or foreign-key constraint. Check the insert's conflict strategy.",
    "SSLHandshakeException" to
        "Usually an expired or untrusted certificate, a device clock that is wrong, or pinning that no " +
        "longer matches the server's chain.",
    "BadTokenException" to
        "A window added to an activity that was already gone — a dialog or popup shown after it finished.",
    "WindowLeaked" to
        "A dialog or popup outlived its activity. Dismiss it in onDestroy, or hold it lifecycle-aware.",
    "ActivityNotFoundException" to
        "Nothing on the device handles the intent. Resolve it first and handle the empty case — common " +
        "on devices with no browser, dialer or camera app.",
    "TransactionTooLargeException" to
        "A Binder transaction over roughly 1 MB per process. Pass an id and re-read the data rather than " +
        "putting the object in the Bundle or Intent.",
    "ForegroundServiceDidNotStartInTimeException" to
        "startForeground must be called within five seconds of the service starting, before any slow setup.",
    "RemoteServiceException" to
        "The system killed the app for misusing a system service — an invalid notification channel and a " +
        "missing startForeground are the usual two.",
    "UnsatisfiedLinkError" to
        "The ABI isn't packaged for this device, or a JNI signature doesn't match its Kotlin/Java declaration.",
    "NoClassDefFoundError" to
        "Present at compile time, missing at runtime: usually shrinking removed it, or an SDK-gated API " +
        "was touched on an older device.",
    "ClassNotFoundException" to
        "Often a reflective lookup whose name shrinking rewrote — add a keep rule if so.",
    "NetworkOnMainThreadException" to
        "The frame below is where the request was issued; move it to a background dispatcher.",
)
