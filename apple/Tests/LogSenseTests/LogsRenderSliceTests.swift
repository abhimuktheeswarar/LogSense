import XCTest
@testable import LogSense

final class LogsRenderSliceTests: XCTestCase {

    private func entries(_ n: Int) -> [LogEntry] {
        (0..<Int64(n)).map {
            LogEntry(id: $0, timeMs: $0, pid: 1, tid: 1,
                     level: .info, subsystem: "", tag: "t", message: "line \($0)")
        }
    }

    func testALargeResultRendersOnlyTheNewestWindow() {
        let slice = entries(50_000).renderSlice(window: 2_000, findActive: false)
        XCTAssertEqual(2_000, slice.rows.count)
        XCTAssertEqual(48_000, slice.hiddenOlder)
        XCTAssertEqual(49_999, slice.rows.last?.id)
        XCTAssertEqual(48_000, slice.rows.first?.id)
    }

    func testAResultWithinTheWindowRendersWhole() {
        let slice = entries(1_500).renderSlice(window: 2_000, findActive: false)
        XCTAssertEqual(1_500, slice.rows.count)
        XCTAssertEqual(0, slice.hiddenOlder)
    }

    func testFindRendersEverythingSoEveryMatchIsReachable() {
        let slice = entries(5_000).renderSlice(window: 2_000, findActive: true)
        XCTAssertEqual(5_000, slice.rows.count)
        XCTAssertEqual(0, slice.hiddenOlder)
    }
}
