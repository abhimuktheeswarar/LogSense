import SwiftUI
import LogSense

@main
struct LogSenseDemoApp: App {

    init() {
        #if DEBUG
        LogSense.start(LogSenseConfig(
            analyticsTagPatterns: ["Analytics": nil],
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
