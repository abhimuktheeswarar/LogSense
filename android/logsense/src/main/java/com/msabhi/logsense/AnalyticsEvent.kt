package com.msabhi.logsense

/** An analytics event extracted from a log line. */
public class AnalyticsEvent(
    public val name: String,
    public val params: Map<String, Any?> = emptyMap(),
)
