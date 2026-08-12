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

    /// Capture health, updated every poll — a capture that silently sees nothing is a bug
    /// that can't be reported; these counters make the failure mode visible (diagnostics
    /// file, future UI). Written and read on the poll thread only.
    private(set) var polls = 0
    private(set) var entriesDelivered = 0
    private(set) var lastError: String?

    /// Opening a store costs up to ~1s regardless of new-line count, and a retained store DOES
    /// see entries logged after its creation (measured on current OS — the old frozen-snapshot
    /// observation no longer holds). Reuse it while polls deliver; after an empty poll,
    /// re-create — so on any OS where the store really is a snapshot, capture self-heals
    /// within one poll instead of going silently blind.
    private var cachedStore: OSLogStore?
    private var lastPollDelivered = false

    /// Delivering in chunks instead of returning one array keeps a big backlog walk from
    /// holding the screen empty for its whole duration: materializing entries costs ~1ms each,
    /// so a chatty host's first poll runs for seconds — the first chunk puts lines on screen
    /// while the walk is still going. Returns the total entry count delivered.
    func poll(deliver: ([LogEntry]) -> Void) -> Int {
        polls += 1
        let store: OSLogStore
        if let cached = cachedStore, lastPollDelivered {
            store = cached
        } else {
            do {
                store = try OSLogStore(scope: .currentProcessIdentifier)
            } catch {
                lastError = "store init: \(error)"
                return 0
            }
            cachedStore = store
        }
        let entries: AnySequence<OSLogEntry>
        do {
            if let newestDate {
                // Push the boundary into the store as a predicate: the position hint is
                // ignored on some OS versions, and materializing one OSLogEntry costs ~1ms —
                // a chatty host's history must be skipped natively, not object-by-object.
                // (`.reverse` + early-stop would also work, but that option is silently
                // ignored too — measured, not assumed.) newestDate comes from entry dates,
                // so the comparison never crosses clock domains.
                entries = try store.getEntries(
                    at: store.position(date: newestDate),
                    matching: NSPredicate(format: "date >= %@", newestDate as NSDate)
                )
            } else {
                entries = try store.getEntries()
            }
        } catch {
            // A store that rejects the predicate still tails fine the expensive way.
            lastError = "getEntries: \(error)"
            do {
                entries = try store.getEntries()
            } catch {
                lastError = "getEntries fallback: \(error)"
                return 0
            }
        }

        var out: [LogEntry] = []
        var total = 0
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
            // ~0.25s of materialization per chunk; dedupe state commits after the full walk,
            // so entries handed out early have already passed it.
            if out.count == 256 {
                total += out.count
                deliver(out)
                out.removeAll(keepingCapacity: true)
            }
        }
        if !out.isEmpty {
            total += out.count
            deliver(out)
        }
        if total > 0 {
            if batchNewest == newestDate {
                boundarySeen.formUnion(batchBoundary)
            } else {
                newestDate = batchNewest
                boundarySeen = batchBoundary
            }
        }
        entriesDelivered += total
        lastPollDelivered = total > 0
        return total
    }

    /// One-line health summary for the diagnostics file.
    var health: String {
        "polls=\(polls) entries=\(entriesDelivered) newest=\(newestDate.map { Format.time(Int64($0.timeIntervalSince1970 * 1000)) } ?? "nil") lastError=\(lastError ?? "none")"
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
