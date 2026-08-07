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
                .badge(state.crashes.count)
                .tag(RootTab.crashes)
        }
        .tint(core.config.accentColor ?? .accentColor)
        .preferredColorScheme(colorScheme(ThemeMode(rawValue: themeRaw) ?? core.config.theme))
        .onAppear {
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
    }

    private func colorScheme(_ mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

#endif
