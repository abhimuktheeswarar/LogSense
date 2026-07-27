package com.msabhi.logsense.internal.signals

import com.msabhi.logsense.internal.data.CrashEntity

/**
 * A first read of a crash: what most likely went wrong, the frame in *your* code to look at, and
 * what to do next. A stack trace already says all of this, but it says it in forty lines — this is
 * the three that matter, shown above the trace.
 */
internal data class Triage(
    val cause: String,
    /** The topmost frame belonging to the host app, or null when the trace has none. */
    val appFrame: String?,
    val nextSteps: String,
)

internal fun triage(crash: CrashEntity, appPackage: String): Triage {
    val simpleName = crash.exceptionClass
        ?.substringAfterLast('.')
        ?.substringAfterLast('$')
        .orEmpty()
    val known = KNOWN[simpleName] ?: byType(crash.type)
    return Triage(known.cause, appFrame(crash.stacktrace, appPackage), known.nextSteps)
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

private class Known(val cause: String, val nextSteps: String)

private fun byType(type: String): Known = when (type) {
    "ANR" -> Known(
        "The main thread stopped responding long enough for Android to kill the app.",
        "Look at the signals logged just before this one — skipped frames, long monitor contention " +
            "and blocking GCs all point at the work that blocked the main thread.",
    )

    "NATIVE" -> Known(
        "A native (C/C++) fault killed the process before the JVM could report anything.",
        "Match the signal against your NDK code or a native dependency: SIGSEGV is a bad pointer, " +
            "SIGABRT an explicit abort or failed assert, SIGBUS a misaligned or missing mapping.",
    )

    else -> Known(
        "The app threw an exception no handler caught, so Android terminated the process.",
        "Start at the frame below and work outwards; the message above usually names the value or " +
            "state that was wrong.",
    )
}

/**
 * Causes and next steps for the exception types that actually show up. Anything not here falls back
 * to [byType] — a generic entry is better than a wrong specific one.
 */
private val KNOWN: Map<String, Known> = mapOf(
    "NullPointerException" to Known(
        "Something was null where the code assumed it wouldn't be.",
        "The message names the method that was called on null. Common sources: a view accessed " +
            "before inflation, a callback firing after the owner was released, a nullable API " +
            "result taken with `!!`.",
    ),
    "KotlinNullPointerException" to Known(
        "A `!!` assertion was made on a null value.",
        "Replace the `!!` at the frame below with an explicit null check or an early return.",
    ),
    "UninitializedPropertyAccessException" to Known(
        "A `lateinit` property was read before it was assigned.",
        "Check the lifecycle ordering — this usually means the read happens before onCreate/onViewCreated, " +
            "or after the owner was destroyed. `isInitialized` guards the read if the order can't change.",
    ),
    "IllegalStateException" to Known(
        "The object was used in a state that doesn't allow the operation.",
        "The message usually names the expected state. Fragment and lifecycle transitions are the " +
            "usual source; check what ran before this call.",
    ),
    "IllegalArgumentException" to Known(
        "A value passed to a method was outside what it accepts.",
        "Validate the argument at the frame below — the message normally quotes the offending value.",
    ),
    "IndexOutOfBoundsException" to Known(
        "An index outside the collection's bounds was read.",
        "Check the list hasn't changed size between the index being computed and used — a common " +
            "adapter bug when data updates off the main thread.",
    ),
    "ArrayIndexOutOfBoundsException" to Known(
        "An index outside the array's bounds was read.",
        "Compare the index against the array's actual length at the frame below.",
    ),
    "ConcurrentModificationException" to Known(
        "A collection was modified while it was being iterated.",
        "Iterate over a copy, use an iterator's own `remove`, or move the mutation off the loop.",
    ),
    "ClassCastException" to Known(
        "A value was cast to a type it isn't.",
        "The message names both types. Check any `as` cast on a heterogeneous list or a Bundle/Intent extra.",
    ),
    "NumberFormatException" to Known(
        "A string that isn't a number was parsed as one.",
        "Guard the parse with `toIntOrNull()` / `toDoubleOrNull()` — server and deep-link input is " +
            "the usual source.",
    ),
    "OutOfMemoryError" to Known(
        "The app asked for more heap than it is allowed.",
        "The message says how large the failed allocation was. Large bitmaps and unbounded caches or " +
            "lists are the usual causes; check the memory signals just before this.",
    ),
    "StackOverflowError" to Known(
        "Recursion ran away, or an object graph is cyclic.",
        "Look for the repeating block of frames in the trace — that's the cycle.",
    ),
    "NetworkOnMainThreadException" to Known(
        "A network call was made on the main thread, which Android forbids.",
        "Move the call to a background dispatcher. The frame below is where the request was issued.",
    ),
    "SecurityException" to Known(
        "The app tried something it doesn't hold permission for.",
        "The message names the permission or the protected component. Check the runtime grant, not " +
            "just the manifest declaration.",
    ),
    "SQLiteException" to Known(
        "A database statement failed.",
        "The message carries the SQL error. Check schema drift against your migrations first.",
    ),
    "SQLiteConstraintException" to Known(
        "A database write violated a constraint (unique, not-null or foreign key).",
        "Check the conflict strategy on the insert and whether the row already exists.",
    ),
    "SSLHandshakeException" to Known(
        "The TLS handshake with the server failed.",
        "Usual causes: an expired or untrusted certificate, a device clock that is wrong, or a " +
            "pinning configuration that no longer matches the server's chain.",
    ),
    "UnknownHostException" to Known(
        "The host name couldn't be resolved.",
        "Usually no connectivity rather than a bug — confirm the host name and how the app behaves offline.",
    ),
    "SocketTimeoutException" to Known(
        "The server didn't respond within the client's timeout.",
        "Check the endpoint's latency and whether the configured timeout is realistic for it.",
    ),
    "BadTokenException" to Known(
        "A window was added to an activity that was already gone.",
        "A dialog or popup is being shown after the activity finished — check the lifecycle state " +
            "before showing it.",
    ),
    "WindowLeaked" to Known(
        "A dialog or popup outlived the activity that owned it.",
        "Dismiss it in onDestroy, or hold it in a lifecycle-aware owner.",
    ),
    "ActivityNotFoundException" to Known(
        "No app on the device can handle the intent.",
        "Resolve the intent before starting it, and handle the empty case — this fires often on " +
            "devices without a browser, dialer or camera app.",
    ),
    "TransactionTooLargeException" to Known(
        "Too much data was passed across a Binder transaction (the limit is about 1 MB per process).",
        "Shrink what goes into the Bundle or Intent — pass an id and re-read the data instead of " +
            "passing the object.",
    ),
    "ForegroundServiceDidNotStartInTimeException" to Known(
        "A service started in the foreground didn't call startForeground in time.",
        "Call startForeground within five seconds of the service starting, before any slow setup work.",
    ),
    "RemoteServiceException" to Known(
        "The system killed the app for misusing a system service.",
        "The message names the misuse — an invalid notification channel and a missing " +
            "startForeground are the two common ones.",
    ),
    "UnsatisfiedLinkError" to Known(
        "A native library couldn't be loaded, or a native method has no implementation.",
        "Check the ABI is packaged for this device and that the JNI signature matches the Kotlin/Java " +
            "declaration.",
    ),
    "NoClassDefFoundError" to Known(
        "A class present at compile time is missing at runtime.",
        "Usually shrinking removed it, or an SDK-gated API was touched on an older device — check " +
            "your keep rules and the API level guard.",
    ),
    "ClassNotFoundException" to Known(
        "A class was looked up by name and not found.",
        "Check for a reflective lookup whose name shrinking rewrote, and keep it if so.",
    ),
)
