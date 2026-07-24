package com.msabhi.logsense.internal.analytics

import com.msabhi.logsense.AnalyticsEvent
import com.msabhi.logsense.LogSenseConfig
import com.msabhi.logsense.internal.data.EventDao
import com.msabhi.logsense.internal.data.EventEntity
import com.msabhi.logsense.internal.reader.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Picks analytics events out of parsed log batches and persists them. */
internal class AnalyticsDetector(
    private val config: LogSenseConfig,
    private val eventDao: () -> EventDao,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val eventPattern: () -> String = { "" },
) {

    // Precedence: a code-level analyticsExtractor wins; else the user's Settings regex (recompiled
    // only when it changes); else the built-in DefaultExtractor.
    private val configExtractor = config.analyticsExtractor
    private var cachedPattern: String? = null
    private var cachedRegex: RegexExtractor? = null

    private fun extractor(): (String, String) -> AnalyticsEvent? {
        configExtractor?.let { return it }
        val pattern = eventPattern()
        if (pattern != cachedPattern) {
            cachedPattern = pattern
            cachedRegex = RegexExtractor.of(pattern)
        }
        return cachedRegex ?: DefaultExtractor
    }

    fun process(batch: List<LogEntry>) {
        if (config.analyticsTags.isEmpty()) return
        val extractor = extractor()
        val entities = batch.mapNotNull { entry ->
            if (entry.tag !in config.analyticsTags) return@mapNotNull null
            val event = runCatching { extractor(entry.tag, entry.message) }.getOrNull() ?: return@mapNotNull null
            EventEntity(
                timestamp = entry.timeMs,
                sessionId = sessionId,
                tag = entry.tag,
                name = event.name,
                paramsJson = JSONObject(event.params).toString(),
            )
        }
        if (entities.isEmpty()) return
        scope.launch {
            eventDao().insert(entities)
            // Per-session cap so a chatty current run never evicts previous sessions' events.
            eventDao().trimCountInSession(sessionId, config.maxStoredEvents)
        }
    }
}
