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

    var body: some View {
        TabView {
            LogsScreen(core: core, onDone: onDone)
                .tabItem { Label("Logs", systemImage: "list.bullet.rectangle") }
            EventsScreen(core: core)
                .tabItem { Label("Events", systemImage: "chart.bar.xaxis") }
            CrashesScreen(core: core)
                .tabItem { Label("Crashes", systemImage: "exclamationmark.triangle") }
                .badge(state.crashes.count)
        }
        .tint(core.config.accentColor ?? .accentColor)
        .preferredColorScheme(colorScheme(ThemeMode(rawValue: themeRaw) ?? core.config.theme))
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
