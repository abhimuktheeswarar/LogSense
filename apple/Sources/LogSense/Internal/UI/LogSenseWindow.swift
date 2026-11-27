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
        restoreHost()
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
        // The window also covers the crease, so the fold shows LogSense's background rather than
        // a black gap; the inset keeps every control off the crease itself.
        let crease = division?.width ?? 0
        window.frame = CGRect(x: frame.minX - crease, y: frame.minY, width: frame.width + crease, height: frame.height)
        window.rootViewController?.additionalSafeAreaInsets.left = crease
        dockedDivision = division
        layoutHost()
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
        restoreHost()
        guard let window, let windowScene = window.windowScene else { return }
        window.rootViewController?.additionalSafeAreaInsets.left = 0
        window.frame = windowScene.coordinateSpace.bounds
        setBeside(false)
    }

    /// The fold LogSense is docked against, while docked.
    private static var dockedDivision: CGRect?
    /// The host's windows LogSense shrank, to give back their full size.
    private static var resizedHostWindows: [UIWindow] = []

    /// Side by side (the default): the host app's own windows shrink to the near side of the fold,
    /// so it lays itself out in its half instead of running on under LogSense. Overlay (a Settings
    /// choice): the host keeps the whole display and LogSense covers its far half. Debug-only, and
    /// only ever while docked — every way out restores the host's full size.
    static func layoutHost() {
        restoreHost()
        guard let window, let windowScene = window.windowScene, dockedDivision != nil,
              !UserDefaults.standard.bool(forKey: Prefs.besideOverlayKey),
              let frame = DuoGeometry.hostFrame(bounds: windowScene.coordinateSpace.bounds, division: dockedDivision)
        else { return }
        let hostWindows = windowScene.windows.filter {
            $0 !== window && !($0 is LogSenseOverlayWindow) && $0.windowLevel == .normal && !$0.isHidden
        }
        for hostWindow in hostWindows { hostWindow.frame = frame }
        resizedHostWindows = hostWindows
    }

    private static func restoreHost() {
        let bounds = window?.windowScene?.coordinateSpace.bounds
        for hostWindow in resizedHostWindows {
            hostWindow.frame = bounds ?? hostWindow.windowScene?.coordinateSpace.bounds ?? hostWindow.frame
        }
        resizedHostWindows = []
    }

    private static func setBeside(_ beside: Bool) {
        if !beside { dockedDivision = nil }
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
