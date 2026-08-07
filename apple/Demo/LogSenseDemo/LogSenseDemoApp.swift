import SwiftUI
import LogSense

@main
struct LogSenseDemoApp: App {

    init() {
        #if DEBUG
        LogSense.start(LogSenseConfig(
            analyticsTagPatterns: [
                // Built-in parser handles this tag's shapes.
                "Analytics": nil,
                // Per-tag regex escape hatch: only lines matching it become events.
                "Telemetry": #"(?<name>\w+)\s*->\s*\{(?<params>.*)\}"#,
                // A second built-in-parser tag, to see multi-tag scoping in the Events tab.
                "Metrics": nil,
            ],
            customSignals: ["Demo trouble": "tag:Demo msg:trouble"]
        ))
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
