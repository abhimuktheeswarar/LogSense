import XCTest
import CoreGraphics
@testable import LogSense

final class DuoLayoutTests: XCTestCase {

    private func entries(_ times: [Int64]) -> [LogEntry] {
        times.enumerated().map { i, t in
            LogEntry(id: Int64(i), timeMs: t, pid: 1, tid: 1, level: .info, subsystem: "", tag: "t", message: "\(t)")
        }
    }

    func testNearestLinePicksTheClosestTimestamp() {
        let rows = entries([100, 200, 300, 400])
        XCTAssertEqual(1, rows.nearestIndex(toTimeMs: 240))
        XCTAssertEqual(2, rows.nearestIndex(toTimeMs: 260))
        XCTAssertEqual(1, rows.nearestIndex(toTimeMs: 250), "a tie goes to the earlier line")
        XCTAssertEqual(0, rows.nearestIndex(toTimeMs: 5))
        XCTAssertEqual(3, rows.nearestIndex(toTimeMs: 9_999))
        XCTAssertNil([LogEntry]().nearestIndex(toTimeMs: 1))
    }

    func testAJumpCentresTheWindowOnItsLine() {
        let rows = entries(Array(0..<10_000).map { Int64($0) })
        let slice = rows.renderSlice(window: 2_000, findActive: false, anchorId: 3_000)
        XCTAssertEqual(2_000, slice.rows.count)
        XCTAssertEqual(2_000, slice.rows.first?.id)
        XCTAssertTrue(slice.rows.contains { $0.id == 3_000 })
        XCTAssertEqual(2_000, slice.hiddenOlder)
        // Near either end the window clamps instead of running off the buffer.
        XCTAssertEqual(0, rows.renderSlice(window: 2_000, findActive: false, anchorId: 10).rows.first?.id)
        XCTAssertEqual(9_999, rows.renderSlice(window: 2_000, findActive: false, anchorId: 9_990).rows.last?.id)
    }

    func testSeatedNeedsAHalfOpenHingeAndAHorizontalFold() {
        let horizontal = CGRect(x: 0, y: 470, width: 669, height: 10)
        let vertical = CGRect(x: 470, y: 0, width: 10, height: 669)
        XCTAssertTrue(DuoGeometry.isSeated(hingePartiallyOpen: true, division: horizontal))
        XCTAssertFalse(DuoGeometry.isSeated(hingePartiallyOpen: true, division: vertical), "a book held sideways")
        XCTAssertFalse(DuoGeometry.isSeated(hingePartiallyOpen: false, division: horizontal), "lying flat open")
        XCTAssertFalse(DuoGeometry.isSeated(hingePartiallyOpen: true, division: nil))
    }

    func testBesideTakesTheRegionAfterAVerticalFold() {
        let bounds = CGRect(x: 0, y: 0, width: 951, height: 669)
        let fold = CGRect(x: 470, y: 0, width: 10, height: 669)
        XCTAssertEqual(CGRect(x: 480, y: 0, width: 471, height: 669), DuoGeometry.besideFrame(bounds: bounds, division: fold))
        XCTAssertNil(DuoGeometry.besideFrame(bounds: bounds, division: nil))
        XCTAssertNil(DuoGeometry.besideFrame(bounds: bounds, division: CGRect(x: 0, y: 330, width: 951, height: 10)))
    }
}
