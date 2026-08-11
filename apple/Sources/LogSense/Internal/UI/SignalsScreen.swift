#if os(iOS)
import SwiftUI

/// The signal catalog's hits, in the Android app's shape translated to the design language:
/// category pills with counts, colored rows, tap-to-jump to the flagged line, swipe-to-mute.
/// Hits are in-memory and die with the buffer they point into — that's what keeps jumps honest.
internal struct SignalsScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    @ObservedObject private var state: LogSenseState
    @State private var categoryScope: SignalCategory?
    /// Muting hides existing hits too, like Android — everywhere, not just future matching.
    @State private var muted = Prefs.mutedSignals()
    @State private var showSettings = false

    init(core: LogSenseCore, onDone: (() -> Void)? = nil) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    private var audibleHits: [SignalHit] {
        state.signalHits.filter { !muted.contains($0.signal.id) }
    }

    /// Newest first, like Crashes and Events.
    private var hits: [SignalHit] {
        let all = audibleHits.reversed()
        guard let categoryScope else { return Array(all) }
        return all.filter { $0.signal.category == categoryScope }
    }

    private var categories: [(SignalCategory, Int)] {
        var counts: [SignalCategory: Int] = [:]
        for hit in audibleHits { counts[hit.signal.category, default: 0] += 1 }
        return SignalCategory.allCases.compactMap { category in
            counts[category].map { (category, $0) }
        }
    }

    var body: some View {
        NavigationStack {
            // Pills live in the layout, not a top safeAreaInset — an inset there swallows the
            // large navigation title.
            VStack(spacing: 0) {
                LiveStatusRow(status: state.status, detail: "\(audibleHits.count.formatted()) flagged")
                if categories.count > 1 { categoryPills }
                signalsList
            }
            .onAppear { muted = Prefs.mutedSignals() }
            .navigationTitle("Signals")
            .toolbar {
                if let onDone {
                    ToolbarItem(placement: .topBarLeading) {
                        BackButton(label: core.hostName, action: onDone)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            showSettings = true
                        } label: {
                            Label("Settings…", systemImage: "gearshape")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsScreen(core: core)
            }
        }
    }

    @ViewBuilder
    private var signalsList: some View {
        Group {
                if state.signalHits.isEmpty {
                    EmptyStateView(
                        icon: "waveform.path.ecg",
                        title: "Nothing flagged",
                        body: "Known trouble patterns land here the moment they appear in the stream. A healthy run flags nothing — the routine signals ship muted.",
                        actionLabel: nil, action: nil
                    )
                    // Greedy, so the empty state centers in the space under the Live row
                    // instead of the whole VStack clustering mid-screen.
                    .frame(maxHeight: .infinity)
                } else {
                    List {
                        ForEach(Array(hits.enumerated()), id: \.offset) { _, hit in
                            SignalRow(hit: hit)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    // Reported signals have no line to jump to.
                                    guard let entryId = hit.entryId else { return }
                                    state.requestedTab = .logs
                                    state.revealEntryId = entryId
                                }
                                .swipeActions {
                                    Button {
                                        muted.insert(hit.signal.id)
                                        Prefs.setMutedSignals(muted)
                                    } label: {
                                        Label("Mute", systemImage: "bell.slash")
                                    }
                                    .tint(Color(hex: 0xFF9F0A))
                                }
                        }
                    }
                    .listStyle(.plain)
                }
        }
    }

    private var categoryPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                pill(label: "All", color: nil, count: state.signalHits.count, isOn: categoryScope == nil) {
                    categoryScope = nil
                }
                ForEach(categories, id: \.0) { category, count in
                    pill(label: category.label, color: category.color, count: count, isOn: categoryScope == category) {
                        categoryScope = categoryScope == category ? nil : category
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .background(.bar)
    }

    private func pill(
        label: String, color: Color?, count: Int, isOn: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let color {
                    Circle().fill(color).frame(width: 7, height: 7)
                }
                Text(label)
                Text(count.formatted()).foregroundStyle(isOn ? .white.opacity(0.7) : .secondary)
            }
            .font(.system(size: 13, weight: isOn ? .semibold : .medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(isOn ? Color.accentColor : Color(.tertiarySystemFill), in: Capsule())
            .foregroundStyle(isOn ? .white : .primary)
        }
        .buttonStyle(.plain)
    }
}

private struct SignalRow: View {
    let hit: SignalHit

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(hit.signal.category.color)
                .frame(width: 8, height: 8)
                .padding(.top, 5)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(hit.signal.label)
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(1)
                    Text(hit.signal.category.label)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(hit.signal.category.color)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1.5)
                        .background(hit.signal.category.color.opacity(0.13), in: Capsule())
                }
                if !hit.preview.isEmpty {
                    Text(hit.preview)
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                HStack(spacing: 9) {
                    Text(Format.time(hit.timeMs))
                    Text(hit.tag).foregroundStyle(.secondary)
                    if hit.entryId == nil {
                        Text("reported").italic()
                    }
                }
                .font(.system(size: 11))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
            if hit.entryId != nil {
                Image(systemName: "chevron.forward")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 5)
            }
        }
        .padding(.vertical, 3)
    }
}
#endif
