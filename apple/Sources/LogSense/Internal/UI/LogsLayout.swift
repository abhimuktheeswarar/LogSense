#if os(iOS)
import SwiftUI

/// The Logs tab's layout host. One pane everywhere — except on an open iPhone Duo, where the
/// extra width becomes a second pane rather than longer lines: two tabs, one on each side of the
/// fold (the split itself is the system's, so the seam lands on the crease).
internal struct LogsScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?

    @StateObject private var store = LogTabsStore()
    @ObservedObject private var state: LogSenseState
    @Environment(\.duoPosture) private var duo
    @AppStorage(Prefs.duoSplitKey) private var splitEnabled = true

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    /// Docked beside the host, LogSense has half the display — one pane is all that fits.
    private var showsSplit: Bool { duo.isOpen && splitEnabled && !state.besideHost }

    var body: some View {
        Group {
            if showsSplit {
                NavigationStack {
                    FoldSplit {
                        LogsPane(core: core, onDone: onDone, store: store, role: .primary,
                                 isSplit: true, splitEnabled: $splitEnabled)
                    } second: {
                        LogsPane(core: core, onDone: nil, store: store, role: .secondary, isSplit: true)
                    }
                    .toolbar(.hidden, for: .navigationBar)
                }
                .onAppear { store.ensureSecondaryTab() }
            } else {
                LogsPane(core: core, onDone: onDone, store: store,
                         splitEnabled: duo.isOpen && !state.besideHost ? $splitEnabled : nil)
            }
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
