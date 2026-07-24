package com.msabhi.logsense

/**
 * Configuration for [LogSense.init]. All parameters have sensible defaults;
 * construct with named arguments for just what you need.
 */
public class LogSenseConfig(
    /** Log tags whose lines are treated as analytics events. Empty = analytics disabled. */
    public val analyticsTags: Set<String> = emptySet(),
    /**
     * Converts a matching log line into an [AnalyticsEvent]. Return null to skip the line.
     * When null, a built-in extractor is used that understands
     * `name {json}`, `name Bundle[{k=v}]` and `name k=v, k2=v2` formats.
     */
    public val analyticsExtractor: ((tag: String, message: String) -> AnalyticsEvent?)? = null,
    /** Max log lines kept in the in-memory buffer. */
    public val maxBufferedLines: Int = 50_000,
    /**
     * Whether LogSense installs a `Thread.UncaughtExceptionHandler` to capture JVM crashes.
     * It always **chains to any handler already installed**, so your existing crash reporter
     * (Crashlytics, etc.) still runs. Set false to leave the uncaught-exception handler untouched
     * (ANRs and native crashes are still captured via `ApplicationExitInfo`).
     */
    public val captureJvmCrashes: Boolean = true,
    /** Number of most recent log lines attached to a crash report as context. */
    public val crashContextLines: Int = 200,
    /** Stored events/crashes older than this are deleted on init. */
    public val retentionDays: Int = 7,
    /** Max analytics events kept in storage. */
    public val maxStoredEvents: Int = 1_000,
    /** Max crash reports kept in storage. */
    public val maxStoredCrashes: Int = 50,
    /** Max number of recent sessions (process runs) whose events/crashes are kept; older are pruned. */
    public val maxSessions: Int = 10,
    /** Show the ongoing "capturing" notification (requires notifications enabled by the user). */
    public val showNotification: Boolean = true,
    /** Force light/dark, or follow the system. */
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    /** ARGB color int used as the accent (Material primary) color; null = LogSense default. */
    public val accentColor: Int? = null,
)
