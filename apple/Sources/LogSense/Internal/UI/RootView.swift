#if os(iOS)
import SwiftUI

/// Logs · Events · Crashes, per the design. Events and Crashes arrive in later phases; their tabs
/// hold honest placeholders rather than half-features.
internal struct RootView: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    @ObservedObject private var state: LogSenseState

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    var body: some View {
        TabView {
            LogsScreen(core: core, onDone: onDone)
                .tabItem { Label("Logs", systemImage: "list.bullet.rectangle") }
            ComingSoonScreen(
                title: "Events",
                body: "Analytics events lifted out of the log stream land here."
            )
            .tabItem { Label("Events", systemImage: "chart.bar.xaxis") }
            CrashesScreen(core: core)
                .tabItem { Label("Crashes", systemImage: "exclamationmark.triangle") }
                .badge(state.crashes.count)
        }
        .tint(core.config.accentColor ?? .accentColor)
        .preferredColorScheme(colorScheme(core.config.theme))
    }

    private func colorScheme(_ mode: ThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

internal struct ComingSoonScreen: View {
    let title: String
    let body_: String

    init(title: String, body: String) {
        self.title = title
        self.body_ = body
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "hammer")
                .font(.system(size: 27))
                .foregroundStyle(.secondary)
                .frame(width: 56, height: 56)
                .background(Color(.tertiarySystemFill), in: Circle())
            Text(title).font(.headline)
            Text(body_)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif
