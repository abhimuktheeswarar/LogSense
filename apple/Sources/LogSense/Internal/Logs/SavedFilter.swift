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
}
