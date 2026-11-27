import XCTest
@testable import LogSense

final class CrashAlertPolicyTests: XCTestCase {

    private func record(_ type: String, ts: Int64, message: String = "m", stack: String = "s", cls: String? = nil) -> CrashRecord {
        CrashRecord(timestamp: ts, type: type, exceptionClass: cls, message: message,
                    stacktrace: stack, deviceInfo: "", logContext: "")
    }

    func testADeliveryOfManyReportsAlertsOnceWithASummary() {
        let batch = (0..<40).map { record("HANG", ts: 2_000 + Int64($0), message: "hang \($0)") }
            + [record("SIGNAL", ts: 3_000, cls: "SIGSEGV"), record("SIGNAL", ts: 3_100, message: "x", cls: "SIGABRT")]
        let alert = CrashAlertPolicy.alert(for: batch, firstStartMs: 1_000, nowMs: 10_000, lastAlertMs: nil)
        XCTAssertEqual(CrashAlertPolicy.Alert(title: "2 crashes captured", body: "Latest: SIGABRT. Open LogSense for the reports."), alert)
    }

    func testHangsAndHistoryNeverAlert() {
        XCTAssertNil(CrashAlertPolicy.alert(for: [record("HANG", ts: 5_000)], firstStartMs: 1_000, nowMs: 10_000, lastAlertMs: nil),
                     "hangs are recorded quietly")
        XCTAssertNil(CrashAlertPolicy.alert(for: [record("SIGNAL", ts: 500)], firstStartMs: 1_000, nowMs: 10_000, lastAlertMs: nil),
                     "reports from before LogSense's first start are history")
    }

    func testAlertsAreSpacedAMinuteApart() {
        let crash = [record("EXCEPTION", ts: 5_000, message: "boom", cls: "NSRangeException")]
        XCTAssertNil(CrashAlertPolicy.alert(for: crash, firstStartMs: 0, nowMs: 100_000, lastAlertMs: 60_000))
        XCTAssertEqual(CrashAlertPolicy.Alert(title: "NSRangeException", body: "boom"),
                       CrashAlertPolicy.alert(for: crash, firstStartMs: 0, nowMs: 130_000, lastAlertMs: 60_000))
    }

    func testRedeliveredReportsAreDropped() {
        let a = record("HANG", ts: 1, message: "0.27 s", stack: "frames")
        let sameHangLater = record("HANG", ts: 99, message: "0.27 s", stack: "frames")
        let other = record("HANG", ts: 2, message: "0.40 s", stack: "frames")
        let stored: Set<String> = [CrashAlertPolicy.key(a)]
        XCTAssertEqual([other], CrashAlertPolicy.fresh([sameHangLater, other, other], existingKeys: stored))
    }
}
