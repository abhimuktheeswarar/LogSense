#if os(iOS)
import SwiftUI

/// The Logs tab's layout host. One pane everywhere. On an open iPhone Duo that pane goes full
/// width as a table; a second pane — two tabs, one on each side of the fold — is one tap away on
/// the rail, kept for the session but never the default.
internal struct LogsScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?

    @StateObject private var store = LogTabsStore()
    @ObservedObject private var state: LogSenseState
    @Environment(\.duoPosture) private var duo

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    /// Docked beside the host, LogSense has half the display — one pane is all that fits.
    private var showsSplit: Bool { duo.isOpen && store.splitRequested && !state.besideHost }

    /// The split switch, offered while a split is possible (open, not docked beside the host).
    private var splitSwitch: Binding<Bool>? {
        duo.isOpen && !state.besideHost ? $store.splitRequested : nil
    }

    var body: some View {
        Group {
            if showsSplit {
                NavigationStack {
                    FoldSplit {
                        LogsPane(core: core, onDone: onDone, store: store, role: .primary,
                                 isSplit: true, splitEnabled: splitSwitch)
                    } second: {
                        LogsPane(core: core, onDone: nil, store: store, role: .secondary, isSplit: true)
                    }
                    // The way back sits where the way in was: the same rail button, now filled.
                    // The bar exists only to put it on the rail, so it carries no title.
                    .navigationTitle("")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        if duo.verticalRail {
                            ToolbarItem(placement: .primaryAction) {
                                Button {
                                    store.splitRequested = false
                                } label: {
                                    Label("Single Stream", systemImage: "rectangle.split.2x1.fill")
                                }
                            }
                        }
                    }
                    .toolbar(duo.verticalRail ? .automatic : .hidden, for: .navigationBar)
                }
                .onAppear { store.ensureSecondaryTab() }
            } else {
                LogsPane(core: core, onDone: onDone, store: store, splitEnabled: splitSwitch)
            }
        }
        // However the split was asked for (rail button or More menu), its one-time tip is done.
        .onChange(of: store.splitRequested) { on in
            if on { UserDefaults.standard.set(true, forKey: Prefs.duoSplitHintShownKey) }
        }
        .onAppear {
            // Scripted runs: LOGSENSE_START_SPLIT=1 opens split (session-only, like the button).
            if ProcessInfo.processInfo.environment["LOGSENSE_START_SPLIT"] == "1" { store.splitRequested = true }
        }
    }
}

/// Seated: the lower half lies flat, so it holds every control — pressing never tips the screen.
/// Find stays open with thumb-sized steppers; the minimum level is one tap on a lettered segment.
internal struct SeatedDeck: View {
    let core: LogSenseCore
    @Binding var findQuery: SearchQuery
    @Binding var findIndex: Int
    let matchCount: Int
    @Binding var minLevel: LogLevel
    @Binding var autoscroll: Bool
    let onClear: () -> Void

    @ObservedObject private var state: LogSenseState
    @Environment(\.colorScheme) private var scheme

    init(
        core: LogSenseCore,
        findQuery: Binding<SearchQuery>,
        findIndex: Binding<Int>,
        matchCount: Int,
        minLevel: Binding<LogLevel>,
        autoscroll: Binding<Bool>,
        onClear: @escaping () -> Void
    ) {
        self.core = core
        _findQuery = findQuery
        _findIndex = findIndex
        self.matchCount = matchCount
        _minLevel = minLevel
        _autoscroll = autoscroll
        self.onClear = onClear
        self.state = core.state
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            findRow
            VStack(alignment: .leading, spacing: 8) {
                caption("Minimum level")
                levelSegments
            }
            HStack(spacing: 10) {
                if state.status == .paused {
                    deckButton("Resume", systemImage: "play.fill", tint: .orange) { core.resume() }
                } else {
                    deckButton("Pause", systemImage: "pause.fill", tint: .orange) { core.pause() }
                }
                deckButton("Latest", systemImage: "arrow.down", tint: .accentColor) { autoscroll = true }
                deckButton("Clear", systemImage: "trash", tint: .red, action: onClear)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(.secondarySystemBackground))
    }

    private var findRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Find in view", text: $findQuery.text)
                    .font(.system(size: 15, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: findQuery.text) { _ in findIndex = 0 }
                Text(verbatim: "\(matchCount == 0 ? 0 : findIndex + 1) of \(matchCount)")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .layoutPriority(1)
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12))
            stepper("chevron.up", label: "Previous match") { step(-1) }
            stepper("chevron.down", label: "Next match") { step(1) }
        }
    }

    private var levelSegments: some View {
        HStack(spacing: 6) {
            ForEach(LogLevel.allCases, id: \.self) { level in
                let selected = level == minLevel
                Button {
                    minLevel = level
                } label: {
                    Text(String(level.letter))
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .foregroundStyle(selected ? .white : level.color(scheme))
                        .background(
                            selected ? level.color(scheme) : level.color(scheme).opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Minimum level \(level.name)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }

    private func stepper(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(Color(.tertiarySystemFill), in: Circle())
        }
        .disabled(matchCount == 0)
        .accessibilityLabel(label)
    }

    private func deckButton(_ title: String, systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(tint)
                .background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private func caption(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 11.5, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
    }

    private func step(_ delta: Int) {
        guard matchCount > 0 else { return }
        findIndex = ((findIndex + delta) % matchCount + matchCount) % matchCount
    }
}
#endif
