import SwiftUI
import OSLog
import LogSense

/// Buttons that exercise every capture path, one per claim the SDK makes.
struct ContentView: View {

    private let logger = Logger(subsystem: "com.msabhi.logsense.demo", category: "Demo")
    private let analytics = Logger(subsystem: "com.msabhi.logsense.demo", category: "Analytics")
    @State private var burstTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
                Section("LogSense") {
                    Button("Open LogSense") { LogSense.present() }
                }

                Section("Logger (unified log)") {
                    Button("Debug") { logger.debug("Debug line: cache warmed in 12ms") }
                    Button("Info") { logger.info("Info line: session refreshed") }
                    Button("Notice") { logger.notice("Notice line: fallback endpoint in use") }
                    Button("Error") { logger.error("Error line: request failed with 502 (retrying)") }
                    Button("Fault") { logger.fault("Fault line: state invariant broken") }
                }

                Section("Other capture paths") {
                    Button("NSLog") { NSLog("NSLog line: legacy pathway still speaks") }
                    Button("print()") { print("print line: stdout, invisible to the unified log") }
                    Button("Public vs private interpolation") {
                        let secret = "token-123"
                        logger.info("public value: \(secret, privacy: .public)")
                        logger.info("private value: \(secret)")
                    }
                    Button(burstTask == nil ? "Burst: 300 lines" : "Stop burst") {
                        if let task = burstTask {
                            task.cancel()
                            burstTask = nil
                        } else {
                            burstTask = Task {
                                for i in 1...300 where !Task.isCancelled {
                                    logger.debug("burst line \(i, privacy: .public) of 300")
                                    if i % 25 == 0 { try? await Task.sleep(nanoseconds: 40_000_000) }
                                }
                                burstTask = nil
                            }
                        }
                    }
                }

                Section("Analytics-shaped lines") {
                    Button("Bundle shape") {
                        analytics.info("add_to_cart Bundle[{item_id=42, qty=2}]")
                    }
                    Button("JSON shape") {
                        analytics.info(#"purchase {"sku":"pro","price":9.99}"#)
                    }
                    Button("Arrow + Swift dict shape") {
                        analytics.info(#"logEvent = seat_selected -> ["seat": "L4", "fare": 849]"#)
                    }
                }

                Section("Trouble") {
                    Button("Custom signal line") { logger.error("Demo trouble: payment declined") }
                    Button("Unsatisfiable constraints line") {
                        logger.error("Unable to simultaneously satisfy constraints.")
                    }
                    Button("Crash: NSException") {
                        NSException(name: .invalidArgumentException,
                                    reason: "LogSense demo crash",
                                    userInfo: nil).raise()
                    }
                    Button("Crash: fatalError") {
                        fatalError("LogSense demo crash (signal path)")
                    }
                }
            }
            .navigationTitle("LogSense Demo")
        }
        .onAppear(perform: emitStartupChatter)
    }

    /// A few lines on launch so the stream is never empty on first open; auto-opens LogSense when
    /// launched with LOGSENSE_AUTO_OPEN=1 (used by scripted simulator runs).
    private func emitStartupChatter() {
        logger.info("Demo launched")
        logger.debug("warming caches")
        logger.notice("using fallback endpoint")
        NSLog("NSLog line at startup")
        print("print line at startup")
        analytics.info("screen_view screen=Home, source=launch")
        if ProcessInfo.processInfo.environment["LOGSENSE_AUTO_OPEN"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { LogSense.present() }
        }
        if ProcessInfo.processInfo.environment["LOGSENSE_CRASH_AFTER"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                NSException(name: .invalidArgumentException,
                            reason: "LogSense scripted demo crash",
                            userInfo: nil).raise()
            }
        }
    }
}
