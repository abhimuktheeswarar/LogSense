import XCTest
@testable import LogSense

final class CrashTriageTests: XCTestCase {

    private func crash(
        type: String = "EXCEPTION",
        exceptionClass: String? = nil,
        message: String? = nil,
        stacktrace: String = ""
    ) -> CrashRecord {
        CrashRecord(
            timestamp: 0, sessionId: "s", type: type, threadName: "main",
            exceptionClass: exceptionClass, message: message,
            stacktrace: stacktrace, deviceInfo: "", logContext: ""
        )
    }

    private let trace = """
        0   CoreFoundation   0x00000001804ae0f8 __exceptionPreprocess + 172
        1   libobjc.A.dylib  0x000000018009a498 objc_exception_throw + 72
        2   DemoHost         0x0000000102a4cd2c CartViewModel.load() + 84
        3   DemoHost         0x0000000102a4d100 CartScreen.body.getter + 220
        4   UIKitCore        0x0000000184b3b0f4 -[UIViewController loadView] + 180
        """

    func testAnExceptionThatExplainsItselfGetsNoNote() {
        // A bespoke exception name already says what it is; restating it is noise dressed as help.
        let result = triage(crash(exceptionClass: "DemoCartEmptyException", stacktrace: trace), appBinary: "DemoHost")
        XCTAssertNil(result.note)
        XCTAssertNotNil(result.appFrame)
    }

    func testAnExceptionWithANonObviousRemedyGetsANote() {
        let result = triage(crash(exceptionClass: "NSUnknownKeyException"), appBinary: "DemoHost")
        XCTAssertTrue(result.note!.contains("outlet"))
    }

    func testAHangPointsAtTheSignalsBeforeItHavingNoException() {
        let result = triage(crash(type: "HANG", exceptionClass: nil), appBinary: "DemoHost")
        XCTAssertTrue(result.note!.contains("main thread"))
    }

    func testASignalCrashExplainsItsTrap() {
        let result = triage(crash(type: "CRASH", exceptionClass: "SIGTRAP"), appBinary: "DemoHost")
        XCTAssertTrue(result.note!.contains("force-unwrapped"))
    }

    func testAWatchdogCodeGetsItsNote() {
        let result = triage(crash(type: "CRASH", exceptionClass: "0x8badf00d"), appBinary: "DemoHost")
        XCTAssertTrue(result.note!.contains("watchdog"))
    }

    func testADottedExceptionNameStillResolves() {
        let result = triage(crash(exceptionClass: "Foundation.NSRangeException"), appBinary: "DemoHost")
        XCTAssertTrue(result.note!.contains("bounds"))
    }

    func testTheAppFrameIsTheTopmostHostFrameNotTheFrameworkOne() {
        XCTAssertEqual("CartViewModel.load() + 84", appFrame(stacktrace: trace, appBinary: "DemoHost"))
    }

    func testATraceWithNoHostFrameYieldsNil() {
        let frameworkOnly = """
            0   CoreFoundation   0x00000001804ae0f8 __exceptionPreprocess + 172
            1   UIKitCore        0x0000000184b3b0f4 -[UIViewController loadView] + 180
            """
        XCTAssertNil(appFrame(stacktrace: frameworkOnly, appBinary: "DemoHost"))
    }

    func testLogSenseOwnFramesAreNeverOfferedAsTheAppFrame() {
        // LogSense is statically linked, so its frames carry the host binary's name.
        let ourTrace = """
            0   DemoHost   0x0000000102a10000 LogSenseCore.start() + 40
            1   UIKitCore  0x0000000184b3b0f4 -[UIViewController loadView] + 180
            """
        XCTAssertNil(appFrame(stacktrace: ourTrace, appBinary: "DemoHost"))
    }

    func testAnEmptyTraceYieldsNil() {
        XCTAssertNil(appFrame(stacktrace: "", appBinary: "DemoHost"))
    }
}
