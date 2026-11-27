import Foundation

/// How a log row is rendered — the design's Standard / Compact / Raw density control.
internal enum ViewMode: String, CaseIterable {
    case standard, compact, raw
}

/// A named, persisted filter the user can re-apply — the iOS replacement for Android's filter tabs.
internal struct SavedFilter: Equatable {
    var id: Int64
    var name: String
    var filter: LogFilter = LogFilter()
    var viewMode: ViewMode = .standard
}

/// A tab's filtered view of the stream, with what it was computed from — see `refiltered`.
internal struct FilteredRows: Equatable {
    let filter: LogFilter
    let rows: [LogEntry]
    /// The newest buffer line this result has seen.
    let lastId: Int64
}

extension Array where Element == LogEntry {
    /// The slice of entries shown after clearing: everything newer than `clearedAtId`.
    ///
    /// "Clearing" hides lines rather than dropping them — signal hits still point at real lines.
    /// Entries are stored in id order, so this is a binary search returning a slice: no copy.
    func since(_ clearedAtId: Int64) -> ArraySlice<LogEntry> {
        if clearedAtId <= 0 || isEmpty { return self[...] }
        // ponytail: the reader restarts its id counter whenever it is recreated, so a watermark taken
        // before a restart would hide every new line. Rather than plumbing a reset through, self-heal:
        // a watermark ahead of the newest line can only be stale, so ignore it.
        if last!.id < clearedAtId { return self[...] }
        var low = 0
        var high = count
        while low < high {
            let mid = (low + high) / 2
            if self[mid].id <= clearedAtId { low = mid + 1 } else { high = mid }
        }
        return self[low...]
    }

    /// The rows the Logs list renders: the newest `window`, plus how many older ones that hides.
    /// Find gets everything, so each match it steps to is in the list. A jump (`anchorId`, the
    /// other half synced to a moment) centres the window on that line instead, so it is rendered
    /// even when it is far older than the newest `window` lines.
    func renderSlice(window: Int, findActive: Bool, anchorId: Int64? = nil) -> (rows: [LogEntry], hiddenOlder: Int) {
        guard !findActive, count > window else { return (self, 0) }
        if let anchorId, let anchor = firstIndex(where: { $0.id == anchorId }) {
            let start = Swift.max(0, Swift.min(anchor - window / 2, count - window))
            return (Array(self[start..<(start + window)]), start)
        }
        return (Array(suffix(window)), count - window)
    }

    /// A tab's filtered rows, brought up to date with `snapshot` as cheaply as possible. With the
    /// same filter as `previous`, only lines that arrived since are filtered (and evicted ones
    /// dropped) — the 1 Hz stream update costs the new lines, not the whole buffer. A new filter,
    /// or a buffer that was cleared or restarted its ids, is a full pass.
    func refiltered(by filter: LogFilter, previous: FilteredRows?) -> FilteredRows {
        let newestId = last?.id ?? 0
        if let previous, previous.filter == filter, newestId >= previous.lastId {
            let oldestId = first?.id ?? .max
            let predicate = LogQuery.compile(filter)
            var rows = Array(previous.rows.drop { $0.id < oldestId })
            rows.append(contentsOf: since(previous.lastId).filter(predicate))
            return FilteredRows(filter: filter, rows: rows, lastId: newestId)
        }
        return FilteredRows(filter: filter, rows: self.filter(LogQuery.compile(filter)), lastId: newestId)
    }

    /// The line closest in time to `timeMs` — the other half of an open iPhone Duo jumps there.
    /// Entries arrive in time order, so this is a binary search; ties go to the earlier line.
    func nearestIndex(toTimeMs timeMs: Int64) -> Int? {
        guard !isEmpty else { return nil }
        var low = 0
        var high = count
        while low < high {
            let mid = (low + high) / 2
            if self[mid].timeMs < timeMs { low = mid + 1 } else { high = mid }
        }
        if low == count { return count - 1 }
        if low == 0 { return 0 }
        return timeMs - self[low - 1].timeMs <= self[low].timeMs - timeMs ? low - 1 : low
    }
}
