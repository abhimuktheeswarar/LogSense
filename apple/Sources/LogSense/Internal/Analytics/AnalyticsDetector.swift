import Foundation

/// One extracted analytics event, ready to persist. `raw` keeps the originating log line — the
/// detail screen shows it verbatim.
internal struct EventRecord: Codable, Equatable {
    var id: String = UUID().uuidString
    var timestamp: Int64
    var tag: String
    var name: String
    var params: [String: String]
    var raw: String
}

/// Picks analytics events out of parsed log batches. Each captured tag gets its own extractor: a
/// code-level `analyticsExtractor` overrides everything; else the tag's regex (skips non-matching
/// lines); else the built-in `DefaultExtractor`. Tags set in config are authoritative — a Settings
/// tag can't shadow one. The per-tag map is rebuilt only when the live Settings tags change.
internal final class AnalyticsDetector {

    private let config: LogSenseConfig
    private let settingsTagPatterns: () -> [String: String?]
    private var cachedMerged: [String: String?]?
    private var cachedByTag: [String: Extractor] = [:]

    init(config: LogSenseConfig, settingsTagPatterns: @escaping () -> [String: String?] = { [:] }) {
        self.config = config
        self.settingsTagPatterns = settingsTagPatterns
    }

    private func extractors() -> [String: Extractor] {
        // Config wins on any collision.
        let merged = settingsTagPatterns().merging(config.analyticsTagPatterns) { _, fromConfig in fromConfig }
        if merged != cachedMerged {
            cachedMerged = merged
            cachedByTag = merged.mapValues { pattern in
                config.analyticsExtractor ?? extractorFor(pattern)
            }
        }
        return cachedByTag
    }

    func process(_ batch: [LogEntry]) -> [EventRecord] {
        let byTag = extractors()
        if byTag.isEmpty { return [] }
        return batch.compactMap { entry in
            guard let extractor = byTag[entry.tag],
                  let event = extractor(entry.tag, entry.message)
            else { return nil }
            return EventRecord(
                timestamp: entry.timeMs,
                // An extractor may name the event's real source (regex `tag` group) — on
                // platforms where every SDK's lines share one log tag, that's the only way
                // events split into meaningful tags.
                tag: event.tag ?? entry.tag,
                name: event.name,
                params: event.params,
                raw: entry.message
            )
        }
    }
}
