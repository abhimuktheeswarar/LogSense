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
    @State private var dockBesideIfOpen = false

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
        .modifier(ScriptedDock(requested: $dockBesideIfOpen))
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
            // After the first layout pass, once the fold region is known.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { dockBesideIfOpen = true }
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

/// Docks beside the host once the posture shows an open Duo (scripted runs only).
private struct ScriptedDock: ViewModifier {
    @Binding var requested: Bool
    @Environment(\.duoPosture) private var duo

    func body(content: Content) -> some View {
        content.onChange(of: requested) { requested in
            guard requested, duo.isOpen else { return }
            LogSenseWindow.dockBeside(division: duo.divisionInWindow)
        }
    }
}
#endif
