import Foundation

/// One session's grouping metadata (derived from the rows currently shown for it).
internal struct SessionMeta: Equatable {
    let id: String
    let isCurrent: Bool
    let startedAt: Int64
    let count: Int
    let newestTs: Int64
    let oldestTs: Int64
}

/// Groups `items` by their session and orders groups current-first, then newest run first.
internal func groupBySession<T>(
    _ items: [T],
    currentSessionId: String,
    startedAtOf: (String) -> Int64,
    sessionOf: (T) -> String,
    timeOf: (T) -> Int64
) -> [(SessionMeta, [T])] {
    var order: [String] = []
    var grouped: [String: [T]] = [:]
    for item in items {
        let sid = sessionOf(item)
        if grouped[sid] == nil { order.append(sid) }
        grouped[sid, default: []].append(item)
    }
    return order
        .map { sid -> (SessionMeta, [T]) in
            let rows = grouped[sid]!
            let times = rows.map(timeOf)
            let meta = SessionMeta(
                id: sid,
                isCurrent: sid == currentSessionId,
                startedAt: startedAtOf(sid),
                count: rows.count,
                newestTs: times.max()!,
                oldestTs: times.min()!
            )
            return (meta, rows)
        }
        .sorted { a, b in sortKey(a.0) > sortKey(b.0) }
}

private func sortKey(_ meta: SessionMeta) -> Int64 {
    meta.isCurrent ? Int64.max : meta.startedAt
}
