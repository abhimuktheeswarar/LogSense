import SwiftUI
import os

public enum LogSense {

    /// Starts LogSense: log capture (and, in later phases, analytics detection and crash
    /// reporting). Call once at the **top of** `application(_:didFinishLaunchingWithOptions:)` /
    /// your `App` init, before any crash-reporting SDK configures — that ordering is what makes
    /// exception-handler chaining correct in both directions. Subsequent calls are ignored.
    ///
    /// Gate the call (and ideally the dependency) behind `#if DEBUG` — LogSense is a debug-build
    /// tool.
    @MainActor
    public static func start(_ config: LogSenseConfig = LogSenseConfig()) {
        // This runs during the host's launch, so failing here would stop their app from starting.
        // A debug tool must never be the reason an app won't launch: if setup fails for any
        // reason, LogSense stays off and the app carries on without it.
        do {
            try LogSenseCore.start(config)
        } catch {
            os_log(.error, "LogSense failed to start; the app continues without it: %{public}@",
                   String(describing: error))
        }
    }

    #if os(iOS)
    /// Shows the LogSense UI in its own overlay window above the host app — LogSense keeps its
    /// place while the host navigates underneath. No-op until `start` has been called.
    @MainActor
    public static func present() {
        LogSenseWindow.present()
    }
    #endif
}

#if os(iOS)
/// SwiftUI entry point for hosts that prefer to embed or present the UI themselves.
/// Renders nothing if `LogSense.start` was never called.
public struct LogSenseView: View {
    public init() {}

    public var body: some View {
        if let core = LogSenseCore.shared {
            RootView(core: core, onDone: nil)
        }
    }
}
#endif
