import XCTest
@testable import LogSense

final class SignalDetectorTests: XCTestCase {

    private var nextId: Int64 = 0

    private func entry(
        _ tag: String, _ message: String, level: LogLevel = .info, subsystem: String = ""
    ) -> LogEntry {
        nextId += 1
        return LogEntry(id: nextId, timeMs: 1_000 + nextId, pid: 42, tid: 42,
                        level: level, subsystem: subsystem, tag: tag, message: message)
    }

    private func detector(
        config: LogSenseConfig = LogSenseConfig(), muted: Set<String> = []
    ) -> SignalDetector {
        SignalDetector(config: config) { muted }
    }

    private func idsFor(_ entries: LogEntry...) -> [String] {
        let d = detector()
        d.process(entries)
        return d.hits.map(\.signal.id)
    }

    func testDetectsAFaultLine() {
        XCTAssertEqual(
            ["crash.fault"],
            idsFor(entry("app", "something went irrecoverably wrong", level: .fault))
        )
    }

    func testDetectsUnsatisfiableConstraints() {
        XCTAssertEqual(
            ["ui.constraints"],
            idsFor(
                entry("LayoutConstraints", "Unable to simultaneously satisfy constraints."),
                entry("MyTag", "constraints satisfied fine")
            )
        )
    }

    func testDetectsBackgroundPublishing() {
        XCTAssertEqual(
            ["ui.background_publish"],
            idsFor(entry("SwiftUI", "Publishing changes from background threads is not allowed"))
        )
    }

    func testDetectsATSBlockAndTimeout() {
        XCTAssertEqual(
            ["net.ats", "net.timeout"],
            idsFor(
                entry("NSURLSession", "App Transport Security has blocked a cleartext HTTP connection"),
                entry("NSURLSession", "Task <A> failed: The request timed out.")
            )
        )
    }

    func testCoreDataErrorsMatchOnlyTheCoreDataSubsystem() {
        XCTAssertEqual(
            ["data.coredata"],
            idsFor(
                entry("error", "Could not save context", level: .error, subsystem: "com.apple.coredata"),
                entry("error", "Could not save context", level: .error, subsystem: "com.demo.app")
            )
        )
    }

    func testAFaultLineProducesAtMostOneHit() {
        // A fault line about constraints names two rules; the more specific one wins because it is first.
        XCTAssertEqual(
            ["ui.constraints"],
            idsFor(entry("LayoutConstraints", "Unable to simultaneously satisfy constraints.", level: .fault))
        )
    }

    func testOrdinaryLinesProduceNothing() {
        XCTAssertEqual(
            [],
            idsFor(entry("Network", "GET /v1/cart -> 200"), entry("CartVM", "state=Loading"))
        )
    }

    func testAMutedSignalStopsMatching() {
        let d = detector(muted: ["ui.constraints"])
        d.process([entry("LayoutConstraints", "Unable to simultaneously satisfy constraints.")])
        XCTAssertEqual([], d.hits.map(\.signal.id))
    }

    func testUnmutingTakesEffectWithoutRestarting() {
        var muted: Set<String> = ["ui.constraints"]
        let d = SignalDetector(config: LogSenseConfig()) { muted }
        d.process([entry("LayoutConstraints", "Unable to simultaneously satisfy constraints.")])
        XCTAssertTrue(d.hits.isEmpty)
        muted = []
        d.process([entry("LayoutConstraints", "Unable to simultaneously satisfy constraints.")])
        XCTAssertEqual(["ui.constraints"], d.hits.map(\.signal.id))
    }

    func testCustomSignalsFireAndWinOverBuiltIns() {
        let config = LogSenseConfig(customSignals: [
            "Payment declined": "tag:Checkout msg:declined",
            "Our own constraint note": #"msg:"Unable to simultaneously satisfy constraints""#,
        ])
        let d = detector(config: config)
        d.process([
            entry("Checkout", "payment declined: insufficient funds"),
            entry("LayoutConstraints", "Unable to simultaneously satisfy constraints."),
        ])
        XCTAssertEqual(
            ["custom.Payment declined", "custom.Our own constraint note"],
            d.hits.map(\.signal.id)
        )
        XCTAssertTrue(d.hits.allSatisfy { $0.signal.category == .custom })
    }

    func testABlankCustomQueryNeverMatches() {
        let d = detector(config: LogSenseConfig(customSignals: ["Everything": "   "]))
        d.process([entry("Network", "GET /v1/cart")])
        XCTAssertTrue(d.hits.isEmpty)
    }

    func testAHitPointsAtTheLineItMatched() {
        let line = entry("LayoutConstraints", "Unable to simultaneously satisfy constraints.\ncontinuation line")
        let d = detector()
        d.process([line])
        let hit = d.hits[0]
        XCTAssertEqual(line.id, hit.entryId)
        XCTAssertEqual(line.timeMs, hit.timeMs)
        XCTAssertEqual("LayoutConstraints", hit.tag)
        XCTAssertEqual("Unable to simultaneously satisfy constraints.", hit.preview) // first line only
    }

    func testHitsAreCappedOldestEvicted() {
        let d = detector()
        for i in 0..<600 {
            d.process([entry("app", "fault number \(i)", level: .fault)])
        }
        let hits = d.hits
        XCTAssertEqual(500, hits.count)
        XCTAssertEqual("fault number 100", hits.first?.preview)
        XCTAssertEqual("fault number 599", hits.last?.preview)
    }

    func testRecordedSignalsCarryNoLineToJumpTo() {
        let d = detector()
        d.record(BuiltInSignals.memoryWarning, timeMs: 123, detail: "didReceiveMemoryWarning")
        let hit = d.hits[0]
        XCTAssertNil(hit.entryId)
        XCTAssertEqual(123, hit.timeMs)
        XCTAssertEqual("didReceiveMemoryWarning", hit.preview)
    }

    func testMutingAlsoSilencesRecordedSignals() {
        let d = detector(muted: [BuiltInSignals.memoryWarning.id])
        d.record(BuiltInSignals.memoryWarning, timeMs: 123, detail: "didReceiveMemoryWarning")
        XCTAssertTrue(d.hits.isEmpty)
    }

    func testClearDropsEveryHit() {
        let d = detector()
        d.process([entry("app", "boom", level: .fault)])
        d.clear()
        XCTAssertTrue(d.hits.isEmpty)
    }

    func testEveryDefaultMutedIdExistsInTheCatalog() {
        // A typo here would silently mute nothing, and the noisy signal would still fire.
        let ids = Set(BuiltInSignals.catalog.map(\.id))
        let defaults = BuiltInSignals.mutedByDefault
        XCTAssertTrue(ids.isSuperset(of: defaults), "unknown ids: \(defaults.subtracting(ids))")
        XCTAssertFalse(defaults.isEmpty)
    }

    func testTheSignalsMutedByDefaultAreTheOnesThatFireOnAHealthyRun() {
        let d = detector(muted: BuiltInSignals.mutedByDefault)
        d.process([
            entry("connection", "Task <B>.<1> finished with error [-999]", subsystem: "com.apple.CFNetwork"),
        ])
        d.record(BuiltInSignals.foreground, timeMs: 1, detail: "scene became active")
        XCTAssertTrue(d.hits.isEmpty, "a healthy run should flag nothing")
    }

    func testBuiltInIdsAreUnique() {
        let ids = BuiltInSignals.catalog.map(\.id)
        XCTAssertEqual(ids.count, Set(ids).count)
    }
}
