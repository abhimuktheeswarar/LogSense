#if os(iOS)
import SwiftUI

/// Logs · Events · Crashes, per the design. Events and Crashes arrive in later phases; their tabs
/// hold honest placeholders rather than half-features.
internal struct RootView: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    @ObservedObject private var state: LogSenseState
    /// The Settings override; empty = follow the config.
    @AppStorage(Prefs.themeKey) private var themeRaw = ""

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    @State private var selection: RootTab = .logs

    var body: some View {
        TabView(selection: $selection) {
            LogsScreen(core: core, onDone: onDone)
                .tabItem { Label("Logs", systemImage: "list.bullet.rectangle") }
                .tag(RootTab.logs)
            EventsScreen(core: core, onDone: onDone)
                .tabItem { Label("Events", systemImage: "chart.bar.xaxis") }
                .tag(RootTab.events)
            CrashesScreen(core: core, onDone: onDone)
                .tabItem { Label("Crashes", systemImage: "exclamationmark.triangle") }
                // Unread only — reading (or swiping) a report retires it from the badge.
                .badge(state.crashes.filter { !state.readCrashKeys.contains($0.readKey) }.count)
                .tag(RootTab.crashes)
            SignalsScreen(core: core, onDone: onDone)
                .tabItem { Label("Signals", systemImage: "waveform.path.ecg") }
                .badge(state.signalHits.count)
                .tag(RootTab.signals)
        }
        .modifier(DockBesideRequest(state: state))
        // iPhone Duo: fold, hinge and side rail, read once here for every screen below.
        .readsDuoPosture()
        .tint(core.config.accentColor ?? .accentColor)
        .preferredColorScheme(colorScheme(ThemeMode(rawValue: themeRaw) ?? core.config.theme))
        .onAppear {
            // Hosts may embed LogSenseView without our window — visibility rides the view too.
            core.setUIVisible(true)
            applyScriptedStart()
            if let requested = state.requestedTab {
                selection = requested
                state.requestedTab = nil
            }
        }
        .onChange(of: state.requestedTab) { requested in
            if let requested {
                selection = requested
                state.requestedTab = nil
            }
        }
        .onDisappear { core.setUIVisible(false) }
    }

    /// Scripted simulator runs (screenshots, layout checks): `LOGSENSE_START_TAB=logs|events|
    /// crashes|signals` opens on that tab, `LOGSENSE_START_BESIDE=1` docks beside the host when an
    /// iPhone Duo is open. Read once per launch.
    private func applyScriptedStart() {
        guard !Self.scriptedStartApplied else { return }
        Self.scriptedStartApplied = true
        let env = ProcessInfo.processInfo.environment
        switch env["LOGSENSE_START_TAB"] {
        case "events": selection = .events
        case "crashes": selection = .crashes
        case "signals": selection = .signals
        default: break
        }
        if env["LOGSENSE_START_BESIDE"] == "1" {
            state.dockBesideRequested = true
        }
    }

    private static var scriptedStartApplied = false

    private func colorScheme(_ mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// Carries out `LogSense.present(beside: true)`: docks once the layout reports an open iPhone Duo.
/// The fold region arrives with the first layout pass, so a request gets one short grace period;
/// if the Duo still isn't open then (closed, or not a Duo), LogSense simply stays full screen and
/// the request is dropped — it must never fire later, on some unrelated unfold.
private struct DockBesideRequest: ViewModifier {
    @ObservedObject var state: LogSenseState
    @Environment(\.duoPosture) private var duo
    /// When the pending request was first seen without an open Duo; its grace period runs from here.
    @State private var waitingSince: Date?
    @State private var tick = 0

    func body(content: Content) -> some View {
        content
            .onAppear { settle() }
            .onChange(of: state.dockBesideRequested) { _ in settle() }
            .onChange(of: duo.isOpen) { _ in settle() }
            .onChange(of: tick) { _ in settle() }
    }

    private func settle() {
        guard state.dockBesideRequested else { waitingSince = nil; return }
        if duo.isOpen {
            state.dockBesideRequested = false
            waitingSince = nil
            LogSenseWindow.dockBeside(division: duo.divisionInWindow)
        } else if let since = waitingSince {
            if Date().timeIntervalSince(since) >= 0.8 {
                state.dockBesideRequested = false
                waitingSince = nil
            }
        } else {
            waitingSince = Date()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.85) { tick += 1 }
        }
    }
}
#endif
