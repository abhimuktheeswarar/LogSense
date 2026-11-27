import XCTest
@testable import LogSense

final class RefilterTests: XCTestCase {

    private func entries(_ ids: ClosedRange<Int64>) -> [LogEntry] {
        ids.map {
            LogEntry(id: $0, timeMs: $0, pid: 1, tid: 1, level: .info, subsystem: "",
                     tag: $0 % 2 == 0 ? "Network" : "UI", message: $0 % 2 == 0 ? "GET /api/\($0)" : "tap \($0)")
        }
    }

    private let api = LogFilter(minLevel: .debug, query: "/api")

    func testANewStreamUpdateOnlyAddsTheLinesThatArrived() {
        let first = entries(1...10).refiltered(by: api, previous: nil)
        XCTAssertEqual([2, 4, 6, 8, 10], first.rows.map(\.id))
        // Lines 1–3 evicted, 11–14 arrived.
        let next = entries(4...14).refiltered(by: api, previous: first)
        XCTAssertEqual([4, 6, 8, 10, 12, 14], next.rows.map(\.id))
        XCTAssertEqual(14, next.lastId)
        XCTAssertEqual(next, entries(4...14).refiltered(by: api, previous: nil), "same as a full pass")
    }

    func testANewFilterOrARestartedBufferIsAFullPass() {
        let first = entries(1...10).refiltered(by: api, previous: nil)
        let tap = LogFilter(minLevel: .debug, query: "tap")
        XCTAssertEqual([1, 3, 5, 7, 9], entries(1...10).refiltered(by: tap, previous: first).rows.map(\.id))
        // Cleared and restarted: ids went back down, so nothing from before may survive.
        XCTAssertEqual([2], entries(1...3).refiltered(by: api, previous: first).rows.map(\.id))
    }
}
