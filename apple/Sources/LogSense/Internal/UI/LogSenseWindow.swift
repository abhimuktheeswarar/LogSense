#if os(iOS)
import SwiftUI
import UIKit

/// Presents LogSense in a second `UIWindow` above the host app — the iOS stand-in for Android's
/// separate task: LogSense keeps its place while the host navigates underneath, and dismissing it
/// never disturbs the host's view hierarchy.
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

        let window = UIWindow(windowScene: scene)
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
