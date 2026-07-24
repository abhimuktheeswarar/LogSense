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
    private val extraTags: () -> Set<String> = { emptySet() },
) {

    // Precedence: a code-level analyticsExtractor wins; else regex patterns — the QA's Settings
    // pattern first (so a live tweak overrides the shipped one), then config.analyticsPatterns
    // (recompiled only when the Settings pattern changes; config patterns are constant); else the
    // built-in DefaultExtractor.
    private val configExtractor = config.analyticsExtractor
    private val configPatterns = config.analyticsPatterns.values.joinToString("\n")
    private var cachedPattern: String? = null
    private var cachedExtractor: ((String, String) -> AnalyticsEvent?)? = null

    private fun extractor(): (String, String) -> AnalyticsEvent? {
        configExtractor?.let { return it }
        val pattern = eventPattern()
        if (pattern != cachedPattern) {
            cachedPattern = pattern
            cachedExtractor = RegexExtractor.of("$pattern\n$configPatterns")
        }
        return cachedExtractor ?: DefaultExtractor
    }

    fun process(batch: List<LogEntry>) {
        // Tags from code config plus any the user added in Settings (evaluated live).
        val tags = config.analyticsTags + extraTags()
        if (tags.isEmpty()) return
        val extractor = extractor()
        val entities = batch.mapNotNull { entry ->
            if (entry.tag !in tags) return@mapNotNull null
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
