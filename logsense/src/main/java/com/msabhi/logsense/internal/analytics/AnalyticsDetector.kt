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
    private val settingsTagPatterns: () -> Map<String, String?> = { emptyMap() },
) {

    // Each captured tag gets its own extractor: a code-level analyticsExtractor overrides everything;
    // else the tag's regex (skips non-matching lines); else the built-in DefaultExtractor. Tags set
    // in config are authoritative — a Settings tag can't shadow one. The per-tag map is rebuilt only
    // when the live Settings tags change (config tags are constant).
    private val configExtractor = config.analyticsExtractor
    private var cachedTagPatterns: Map<String, String?>? = null
    private var cachedByTag: Map<String, (String, String) -> AnalyticsEvent?> = emptyMap()

    private fun extractors(): Map<String, (String, String) -> AnalyticsEvent?> {
        val merged = settingsTagPatterns() + config.analyticsTagPatterns // config wins on any collision
        if (merged != cachedTagPatterns) {
            cachedTagPatterns = merged
            cachedByTag = merged.mapValues { (_, pattern) -> configExtractor ?: extractorFor(pattern) }
        }
        return cachedByTag
    }

    fun process(batch: List<LogEntry>) {
        val byTag = extractors()
        if (byTag.isEmpty()) return
        val entities = batch.mapNotNull { entry ->
            val extractor = byTag[entry.tag] ?: return@mapNotNull null // tag not captured
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
            eventDao().trimCountInSession(sessionId, config.maxStoredEvents.coerceAtLeast(0))
        }
    }
}

/** A tag's extractor: its regex (unusable/empty falls back), or the built-in parser when null. */
internal fun extractorFor(pattern: String?): (String, String) -> AnalyticsEvent? =
    pattern?.takeIf { it.isNotBlank() }?.let { RegexExtractor.of(it) } ?: DefaultExtractor
