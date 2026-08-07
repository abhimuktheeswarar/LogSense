import XCTest
@testable import LogSense

/// LogSense is a library: whatever a host passes in, it must not crash. Everything here feeds
/// deliberately awkward input through the code that a host's configuration reaches.
final class HostileConfigTests: XCTestCase {

    private func entry(_ tag: String, _ message: String) -> LogEntry {
        LogEntry(id: 1, timeMs: 1, pid: 1, tid: 1, level: .info, subsystem: "", tag: tag, message: message)
    }

    private func feed(_ custom: [String: String], _ lines: LogEntry...) -> SignalDetector {
        let detector = SignalDetector(config: LogSenseConfig(customSignals: custom)) { [] }
        detector.process(lines)
        return detector
    }

    func testCustomSignalQueriesMadeOfPunctuationDoNotCrash() {
        let nasty = [
            "colon": ":",
            "only quotes": "\"\"",
            "unbalanced quote": "\"open",
            "dashes": "---",
            "empty key value": "tag:",
            "unknown key": "banana:split",
            "regex-looking": "(?<name>.*)[a-z]+\\d{2,}",
            "newlines": "tag:foo\nmsg:bar",
            "tab": "\ttag:foo\t",
        ]
        let detector = feed(nasty, entry("Any", "any message at all"))
        // The point is that we got here; the queries are nonsense but must be inert, not fatal.
        XCTAssertNotNil(detector.hits)
    }

    func testACustomLabelCollidingWithABuiltInIdDoesNotBreakTheCatalog() {
        let detector = feed(["crash.fault": "msg:whatever"], entry("t", "whatever"))
        XCTAssertFalse(detector.hits.isEmpty)
    }

    func testAVeryLongQueryAndAVeryLongLineAreHandled() {
        let longQuery = "msg:" + String(repeating: "x", count: 10_000)
        let longLine = entry("t", String(repeating: "y", count: 200_000))
        let detector = feed(["huge": longQuery], longLine)
        XCTAssertTrue(detector.hits.isEmpty)
    }

    func testALineMatchingACustomSignalIsPreviewedWithoutOverrunning() {
        let detector = feed(["big": "msg:needle"], entry("t", "needle " + String(repeating: "z", count: 50_000)))
        XCTAssertEqual(1, detector.hits.count)
        XCTAssertLessThanOrEqual(detector.hits[0].preview.count, 200)
    }

    func testUnicodeAndControlCharactersInQueriesAndLinesAreSafe() {
        let detector = feed(
            ["emoji": "msg:🔥", "rtl": "msg:\u{202E}abc"],
            entry("t", "burning 🔥 here"),
            entry("t", "\u{00}\u{01} control chars")
        )
        XCTAssertEqual(["custom.emoji"], detector.hits.map(\.signal.id))
    }

    func testAnEmptyCustomMapBehavesLikeNoCustomSignals() {
        let detector = feed([:], entry("LayoutConstraints", "Unable to simultaneously satisfy constraints."))
        XCTAssertEqual(["ui.constraints"], detector.hits.map(\.signal.id))
    }

    func testTriageSurvivesACrashRecordWithEverythingMissing() {
        let bare = CrashRecord(
            timestamp: 0, sessionId: "", type: "", threadName: nil,
            exceptionClass: nil, message: nil, stacktrace: "", deviceInfo: "", logContext: ""
        )
        let read = triage(bare, appBinary: "")
        XCTAssertNil(read.appFrame)
        XCTAssertNil(read.note)
    }

    func testAppFrameSurvivesOddBinaryNames() {
        let trace = "0   Demo   0x0000000100000000 Screen.body + 12"
        // Empty, spaces, punctuation — none of these may crash.
        XCTAssertNil(appFrame(stacktrace: trace, appBinary: ""))
        XCTAssertNil(appFrame(stacktrace: trace, appBinary: "   "))
        XCTAssertNil(appFrame(stacktrace: trace, appBinary: "...."))
        XCTAssertEqual("Screen.body + 12", appFrame(stacktrace: trace, appBinary: "Demo"))
    }

    func testClearingSlicesSafelyAtEveryBoundaryIncludingNonsenseWatermarks() {
        let lines = (Int64(0)...4).map { id in
            LogEntry(id: id, timeMs: 1, pid: 1, tid: 1, level: .info, subsystem: "", tag: "t", message: "m")
        }
        XCTAssertEqual(5, lines.since(Int64.min).count)
        XCTAssertEqual(5, lines.since(-1).count)
        XCTAssertEqual(5, lines.since(Int64.max).count) // stale watermark, self-heals
        XCTAssertTrue(lines.since(4).isEmpty)
    }
}
