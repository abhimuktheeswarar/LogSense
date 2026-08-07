import Foundation
import OSLog

/// Polls the unified log for this process's own entries — `Logger`/`os_log` and `NSLog`, from app
/// code, third-party SDKs and system frameworks alike. `OSLogStore` is a snapshot API, not a tail,
/// so the reader re-polls on a cadence and dedupes across polls.
///
/// The store is re-created per poll: a cached store is itself a snapshot and stops seeing new
/// lines. The `position(date:)` hint is known to be ignored on some OS versions (every entry since
/// process start comes back), so dedupe never trusts it: entries older than the newest date already
/// seen are dropped, and entries *at* that date are dropped when their fingerprint was seen —
/// the same shape as the Android reader's `-T <epoch>` resume dedupe.
internal final class LogReader {

    private var newestDate: Date?
    /// Fingerprints of the entries carrying `newestDate`, the only ambiguous boundary.
    private var boundarySeen: Set<Int> = []

    func poll() -> [LogEntry] {
        guard let store = try? OSLogStore(scope: .currentProcessIdentifier) else { return [] }
        let entries: AnySequence<OSLogEntry>
        do {
            if let newestDate {
                entries = try store.getEntries(at: store.position(date: newestDate))
            } else {
                entries = try store.getEntries()
            }
        } catch {
            return []
        }

        var out: [LogEntry] = []
        var batchNewest = newestDate ?? .distantPast
        var batchBoundary: Set<Int> = []
        for case let log as OSLogEntryLog in entries {
            if let newestDate {
                if log.date < newestDate { continue }
                if log.date == newestDate, boundarySeen.contains(fingerprint(log)) { continue }
            }
            if log.date > batchNewest {
                batchNewest = log.date
                batchBoundary = [fingerprint(log)]
            } else if log.date == batchNewest {
                batchBoundary.insert(fingerprint(log))
            }
            out.append(map(log))
        }
        if !out.isEmpty {
            if batchNewest == newestDate {
                boundarySeen.formUnion(batchBoundary)
            } else {
                newestDate = batchNewest
                boundarySeen = batchBoundary
            }
        }
        return out
    }

    private func fingerprint(_ log: OSLogEntryLog) -> Int {
        var hasher = Hasher()
        hasher.combine(log.composedMessage)
        hasher.combine(log.threadIdentifier)
        return hasher.finalize()
    }

    private func map(_ log: OSLogEntryLog) -> LogEntry {
        let category = log.category
        return LogEntry(
            id: 0, // stamped by LogBuffer.append
            timeMs: Int64(log.date.timeIntervalSince1970 * 1000),
            pid: log.processIdentifier,
            tid: log.threadIdentifier,
            level: LogLevel(log.level),
            subsystem: log.subsystem,
            // NSLog and bare os_log lines have no category — the logging binary's name is the
            // closest thing to Android's tag semantics they carry.
            tag: category.isEmpty ? log.sender : category,
            message: log.composedMessage
        )
    }
}

private extension LogLevel {
    init(_ level: OSLogEntryLog.Level) {
        switch level {
        case .debug: self = .debug
        case .info: self = .info
        case .error: self = .error
        case .fault: self = .fault
        case .notice, .undefined: self = .notice
        @unknown default: self = .notice
        }
    }
}
