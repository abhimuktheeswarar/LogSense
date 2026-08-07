import SwiftUI
import os
#if os(iOS)
import UIKit
import UserNotifications
#endif

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

    /// Asserts that `object` is about to die: if it is still in memory after `timeout` seconds,
    /// a "Screen leaked" signal fires naming it — a retain cycle or a lingering strong reference
    /// kept it alive. The screen case is automatic (`detectLeakedScreens`); this is the same
    /// check for anything else:
    ///
    /// ```swift
    /// deinit paranoia, view models, session-scoped services:
    /// LogSense.watchDeallocation(of: viewModel)
    /// ```
    public static func watchDeallocation(
        of object: AnyObject,
        name: String? = nil,
        timeout: TimeInterval = 3
    ) {
        LeakWatch.expectDealloc(
            of: object,
            name: name ?? String(describing: type(of: object)),
            timeout: timeout
        )
    }

    #if os(iOS)
    /// Shows the LogSense UI in its own overlay window above the host app — LogSense keeps its
    /// place while the host navigates underneath. No-op until `start` has been called.
    @MainActor
    public static func present() {
        LogSenseWindow.present()
    }

    /// Handles the "Open LogSense" Home Screen quick action (registered automatically by `start`).
    /// iOS delivers quick-action taps to *your* scene delegate, so forward them:
    ///
    /// ```swift
    /// func windowScene(_ scene: UIWindowScene, performActionFor item: UIApplicationShortcutItem,
    ///                  completionHandler: @escaping (Bool) -> Void) {
    ///     if LogSense.handleShortcut(item) { completionHandler(true); return }
    ///     // your own shortcuts…
    /// }
    /// ```
    /// Returns true when the item was LogSense's (and the UI was opened), false otherwise.
    @MainActor
    @discardableResult
    public static func handleShortcut(_ item: UIApplicationShortcutItem) -> Bool {
        guard item.type == LogSenseCore.shortcutType else { return false }
        present()
        return true
    }

    /// Handles a tap on a LogSense notification (the crash alert), opening the report list.
    /// If your app has its own `UNUserNotificationCenter` delegate, forward responses:
    ///
    /// ```swift
    /// func userNotificationCenter(_ center: UNUserNotificationCenter,
    ///                             didReceive response: UNNotificationResponse,
    ///                             withCompletionHandler completionHandler: @escaping () -> Void) {
    ///     if LogSense.handleNotificationResponse(response) { completionHandler(); return }
    ///     // your own handling…
    /// }
    /// ```
    /// When your app sets no delegate at all, LogSense claims the vacant slot itself and this
    /// forwarding is unnecessary. Returns true when the notification was LogSense's.
    @MainActor
    @discardableResult
    public static func handleNotificationResponse(_ response: UNNotificationResponse) -> Bool {
        guard response.notification.request.identifier == LogSenseCore.crashNotificationId else {
            return false
        }
        present()
        LogSenseCore.shared?.state.requestedTab = .crashes
        return true
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
