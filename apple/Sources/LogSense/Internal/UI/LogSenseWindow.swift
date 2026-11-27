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

        neutralizeHostAppearanceProxies()

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
        setBeside(false)
        sceneObservation = nil
        window?.isHidden = true
        window = nil
    }

    private static var sceneObservation: NSKeyValueObservation?

    /// iPhone Duo, open: shrink LogSense to the region after the fold so the host app stays live
    /// and touchable on the other side — tap there, read the lines it caused here. Our own window
    /// is resized, so this works for any host, with or without multiple-scene support.
    static func dockBeside(division: CGRect?) {
        guard let window, let windowScene = window.windowScene,
              let frame = DuoGeometry.besideFrame(bounds: windowScene.coordinateSpace.bounds, division: division)
        else { return }
        window.frame = frame
        setBeside(true)
        // Folding (or any other resize) changes the scene; the dock no longer means anything then,
        // so LogSense goes back to covering the app — the closed layout.
        sceneObservation = windowScene.observe(\.effectiveGeometry, options: [.new]) { _, _ in
            Task { @MainActor in expand() }
        }
    }

    /// Back to covering the whole app.
    static func expand() {
        sceneObservation = nil
        guard let window, let windowScene = window.windowScene else { return }
        window.frame = windowScene.coordinateSpace.bounds
        setBeside(false)
    }

    private static func setBeside(_ beside: Bool) {
        guard let state = LogSenseCore.shared?.state, state.besideHost != beside else { return }
        if beside { state.besideSinceMs = Int64(Date().timeIntervalSince1970 * 1000) }
        state.besideHost = beside
    }

    /// Hosts customize UIKit chrome process-wide for their own brand — global appearance
    /// proxies observed in real apps: hiding every tab bar, white nav-bar backgrounds and
    /// custom title fonts. Those also style the UIKit views backing LogSense's SwiftUI UI
    /// (a white bar behind a dark theme reads as broken colors). Rules scoped to the overlay
    /// window are more specific, so they win here — and only here; fresh appearance objects
    /// also take precedence over the host's legacy `barTintColor`/`backgroundColor` settings.
    private static func neutralizeHostAppearanceProxies() {
        let overlay: [UIAppearanceContainer.Type] = [LogSenseOverlayWindow.self]
        UITabBar.appearance(whenContainedInInstancesOf: overlay).isHidden = false
        let navBar = UINavigationBar.appearance(whenContainedInInstancesOf: overlay)
        let standard = UINavigationBarAppearance()          // system default, follows dark/light
        let scrollEdge = UINavigationBarAppearance()
        scrollEdge.configureWithTransparentBackground()     // the system large-title resting look
        navBar.standardAppearance = standard
        navBar.compactAppearance = standard
        navBar.scrollEdgeAppearance = scrollEdge
        navBar.barTintColor = nil
        navBar.tintColor = nil
        // `backgroundColor` is a plain UIView property — a host setting it via the proxy
        // paints the bar's backing view directly, bypassing the appearance objects above.
        navBar.backgroundColor = nil
        navBar.titleTextAttributes = nil
    }
}
#endif
