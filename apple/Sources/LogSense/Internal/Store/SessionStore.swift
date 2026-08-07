import Foundation

/// One stored crash, identified by the file that holds it.
internal struct StoredCrash: Identifiable, Equatable {
    /// The crash file's path — stable identity for lists and deletes.
    let id: String
    var record: CrashRecord
}

/// One stored analytics event with the session it belongs to.
internal struct StoredEvent: Identifiable, Equatable {
    let sessionId: String
    let record: EventRecord
    var id: String { record.id }
}

/// Persistence for sessions and crash reports — plain JSON files, no database. The volumes
/// (≤ maxSessions dirs, ≤ maxStoredCrashes small files) don't justify SQLite.
///
/// Layout under `root`:
/// - `sessions/<startedAtMs>-<uuid8>/crash_<ts>_<rnd>.json` — the dir name *is* the session
///   metadata: its id and its start time. No meta file to keep in sync.
/// - `pending-crashes/` — written by a dying process (`CrashFileStore`), moved into a session
///   dir on the next healthy launch.
/// - `earlier/` — crashes that can't be attributed to a known session.
internal final class SessionStore {

    let root: URL
    let currentSessionId: String

    private let fm = FileManager.default
    private let maxSessions: Int
    private let retentionDays: Int
    private let maxStoredCrashes: Int

    private var sessionsDir: URL { root.appendingPathComponent("sessions") }
    private var earlierDir: URL { root.appendingPathComponent("earlier") }
    var pendingDir: URL { root.appendingPathComponent("pending-crashes") }

    init(root: URL, config: LogSenseConfig, now: Date = Date()) throws {
        self.root = root
        self.maxSessions = max(1, config.maxSessions)
        self.retentionDays = max(1, config.retentionDays)
        self.maxStoredCrashes = max(0, config.maxStoredCrashes)
        let startedAt = Int64(now.timeIntervalSince1970 * 1000)
        self.currentSessionId = "\(startedAt)-\(UUID().uuidString.prefix(8))"
        for dir in [sessionsDir, earlierDir, pendingDir] {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        try fm.createDirectory(
            at: sessionsDir.appendingPathComponent(currentSessionId),
            withIntermediateDirectories: true
        )
        prune(now: now)
    }

    // MARK: - sessions

    /// Session ids sorted oldest → newest. Millisecond prefixes share a digit count until the
    /// year 2286, so lexicographic order is chronological order.
    func sessionIds() -> [String] {
        ((try? fm.contentsOfDirectory(atPath: sessionsDir.path)) ?? []).sorted()
    }

    static func startedAt(of sessionId: String) -> Int64 {
        Int64(sessionId.prefix(while: \.isNumber)) ?? 0
    }

    func sessionStartedAt(_ id: String) -> Int64 { Self.startedAt(of: id) }

    /// The session running at `ms` — the latest one started at or before it. MetricKit
    /// diagnostics arrive with timestamps, not session ids.
    func attributeSession(forTimestamp ms: Int64) -> String {
        sessionIds().last { Self.startedAt(of: $0) <= ms } ?? earlierSessionId
    }

    private func dir(forSession id: String) -> URL {
        id == earlierSessionId ? earlierDir : sessionsDir.appendingPathComponent(id)
    }

    // MARK: - crashes

    @discardableResult
    func add(_ record: CrashRecord) -> StoredCrash {
        var record = record
        let sessionGone = record.sessionId != earlierSessionId
            && !fm.fileExists(atPath: dir(forSession: record.sessionId).path)
        if record.sessionId.isEmpty || sessionGone {
            record.sessionId = attributeSession(forTimestamp: record.timestamp)
        }
        let url = dir(forSession: record.sessionId)
            .appendingPathComponent("crash_\(record.timestamp)_\(UUID().uuidString.prefix(4)).json")
        if let data = try? JSONEncoder().encode(record) {
            try? data.write(to: url)
        }
        enforceCrashCap()
        // Resolved path, matching what directory listing returns — ids must compare equal.
        return StoredCrash(id: url.resolvingSymlinksInPath().path, record: record)
    }

    /// Moves crash files a dying process left in `pending-crashes/` into their session dir.
    /// A `.tmp` file is a kill-mid-write leftover; a file that doesn't decode is garbage.
    /// Both are deleted, never surfaced.
    func ingestPendingCrashes() -> [StoredCrash] {
        let files = (try? fm.contentsOfDirectory(at: pendingDir, includingPropertiesForKeys: nil)) ?? []
        var out: [StoredCrash] = []
        for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            defer { try? fm.removeItem(at: file) }
            guard file.pathExtension == "json",
                  let data = try? Data(contentsOf: file),
                  let record = try? JSONDecoder().decode(CrashRecord.self, from: data)
            else { continue }
            out.append(add(record))
        }
        return out
    }

    /// Every stored crash, newest first.
    func loadCrashes() -> [StoredCrash] {
        var out: [StoredCrash] = []
        let dirs = sessionIds().map { dir(forSession: $0) } + [earlierDir]
        for dir in dirs {
            let files = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
            for file in files where file.lastPathComponent.hasPrefix("crash_") && file.pathExtension == "json" {
                guard let data = try? Data(contentsOf: file),
                      let record = try? JSONDecoder().decode(CrashRecord.self, from: data)
                else { continue }
                out.append(StoredCrash(id: file.resolvingSymlinksInPath().path, record: record))
            }
        }
        return out.sorted { $0.record.timestamp > $1.record.timestamp }
    }

    func delete(id: String) {
        try? fm.removeItem(atPath: id)
    }

    func deleteAll() {
        for crash in loadCrashes() { delete(id: crash.id) }
    }

    // MARK: - events

    /// Events append to one `events.jsonl` per session — one JSON object per line, append-only on
    /// the hot path; the per-session cap trims by rewrite only when actually exceeded.
    private let eventLock = NSLock()
    private var currentEventCount = -1 // lazily counted on first append

    func appendEvents(_ records: [EventRecord], maxPerSession: Int) -> [StoredEvent] {
        guard !records.isEmpty else { return [] }
        eventLock.lock(); defer { eventLock.unlock() }
        let url = eventsFile(forSession: currentSessionId)
        if currentEventCount < 0 {
            currentEventCount = readEvents(at: url).count
        }
        let encoder = JSONEncoder()
        var data = Data()
        for record in records {
            guard let line = try? encoder.encode(record) else { continue }
            data.append(line)
            data.append(0x0A)
        }
        if let handle = FileHandle(forWritingAtPath: url.path) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: url)
        }
        currentEventCount += records.count
        if currentEventCount > maxPerSession {
            // A chatty current run never evicts previous sessions' events — trim only its own file.
            let kept = readEvents(at: url).suffix(maxPerSession)
            writeEvents(Array(kept), to: url)
            currentEventCount = kept.count
        }
        return records.map { StoredEvent(sessionId: currentSessionId, record: $0) }
    }

    /// Every stored event, newest first.
    func loadEvents() -> [StoredEvent] {
        eventLock.lock(); defer { eventLock.unlock() }
        var out: [StoredEvent] = []
        for sessionId in sessionIds() {
            for record in readEvents(at: eventsFile(forSession: sessionId)) {
                out.append(StoredEvent(sessionId: sessionId, record: record))
            }
        }
        return out.sorted { $0.record.timestamp > $1.record.timestamp }
    }

    func deleteEvent(_ event: StoredEvent) {
        eventLock.lock(); defer { eventLock.unlock() }
        let url = eventsFile(forSession: event.sessionId)
        let kept = readEvents(at: url).filter { $0.id != event.record.id }
        writeEvents(kept, to: url)
        if event.sessionId == currentSessionId { currentEventCount = kept.count }
    }

    func deleteAllEvents() {
        eventLock.lock(); defer { eventLock.unlock() }
        for sessionId in sessionIds() {
            try? fm.removeItem(at: eventsFile(forSession: sessionId))
        }
        currentEventCount = 0
    }

    private func eventsFile(forSession id: String) -> URL {
        dir(forSession: id).appendingPathComponent("events.jsonl")
    }

    private func readEvents(at url: URL) -> [EventRecord] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        return data.split(separator: 0x0A).compactMap { try? decoder.decode(EventRecord.self, from: $0) }
    }

    private func writeEvents(_ records: [EventRecord], to url: URL) {
        let encoder = JSONEncoder()
        var data = Data()
        for record in records {
            guard let line = try? encoder.encode(record) else { continue }
            data.append(line)
            data.append(0x0A)
        }
        try? data.write(to: url)
    }

    // MARK: - retention

    private func prune(now: Date) {
        let cutoff = Int64(now.timeIntervalSince1970 * 1000) - Int64(retentionDays) * 86_400_000
        let previous = sessionIds().filter { $0 != currentSessionId }
        let keep = Set(previous.suffix(maxSessions - 1).filter { Self.startedAt(of: $0) >= cutoff })
        for id in previous where !keep.contains(id) {
            try? fm.removeItem(at: dir(forSession: id))
        }
        let earlierFiles = (try? fm.contentsOfDirectory(at: earlierDir, includingPropertiesForKeys: nil)) ?? []
        for file in earlierFiles {
            // crash_<ts>_<rnd>.json — the name carries the timestamp so age-pruning needs no decode.
            let ts = Int64(file.lastPathComponent.dropFirst("crash_".count).prefix(while: \.isNumber)) ?? 0
            if ts < cutoff { try? fm.removeItem(at: file) }
        }
        enforceCrashCap()
    }

    // ponytail: re-lists everything per call — n ≤ maxStoredCrashes small files, called on rare
    // events (launch ingest, a crash arriving). Index in memory if it ever matters.
    private func enforceCrashCap() {
        for extra in loadCrashes().dropFirst(maxStoredCrashes) {
            delete(id: extra.id)
        }
    }
}
