#if os(iOS)
import SwiftUI
import UIKit

/// Presents LogSense in a second `UIWindow` above the host app — the iOS stand-in for Android's
/// separate task: LogSense keeps its place while the host navigates underneath, and dismissing it
/// never disturbs the host's view hierarchy.
/// The overlay window gets its own class so appearance rules can be scoped to it: hosts set
/// process-wide UIKit appearance proxies (a real app was observed hiding every tab bar via
/// `UITabBar.appearance().isHidden = true` for one of its screens), and a blanket proxy also
/// applies to the UIKit views backing LogSense's SwiftUI UI. A rule scoped to this container
/// is more specific, so it wins here without touching the host's own windows.
internal final class LogSenseOverlayWindow: UIWindow {}

@MainActor
internal enum LogSenseWindow {

    private static var window: UIWindow?

    static func present() {
        guard let core = LogSenseCore.shared else { return }
        core.setUIVisible(true)
        if let window {
            window.isHidden = false
            window.makeKey()
            return
        }
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })
            ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first
        else { return }

        // Neutralize host appearance proxies inside the overlay before any view lands in it.
        UITabBar.appearance(whenContainedInInstancesOf: [LogSenseOverlayWindow.self]).isHidden = false

        let window = LogSenseOverlayWindow(windowScene: scene)
        window.windowLevel = .normal + 1
        window.rootViewController = UIHostingController(
            rootView: RootView(core: core, onDone: { dismiss() })
        )
        window.makeKeyAndVisible()
        Self.window = window
    }

    static func dismiss() {
        LogSenseCore.shared?.setUIVisible(false)
        window?.isHidden = true
        window = nil
    }
}
#endif
