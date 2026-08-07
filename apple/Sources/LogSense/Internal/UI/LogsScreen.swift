#if os(iOS)
import SwiftUI

/// The live stream, per the design: LOGSENSE caption over the host's name, Live pulse + counts,
/// filter field with a min-level menu, Wrap/Autoscroll pills, Standard/Compact/Raw density, find
/// bar above the keyboard, paused banner, and the waiting/no-match states.
internal struct LogsScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?

    @ObservedObject private var state: LogSenseState
    @Environment(\.colorScheme) private var scheme
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var query = ""
    @State private var minLevel: LogLevel = .debug
    @State private var viewMode: ViewMode = .standard
    @State private var wrap = true
    @State private var autoscroll = true
    @State private var findQuery = SearchQuery()
    @State private var findActive = false
    @State private var findIndex = 0
    @State private var selectedEntry: LogEntry?
    @State private var showSettings = false
    /// An entry id the Signals tab asked to scroll to; consumed by the list's scroll proxy.
    @State private var revealTarget: Int64?

    // Android's tab model: each tab owns its filter, min level and density, persisted across runs.
    // "All" (id 0) is the one tab that can't be closed — a stable home to come back to.
    @State private var tabs: [SavedFilter] = LogsScreen.loadTabs()
    @State private var activeTabId: Int64 = 0
    /// Per-tab pause: a **view freeze**, not a capture pause — runtime-only, like Android's
    /// `LogTab.paused`. The frozen rows are value copies, so they outlive buffer eviction.
    @State private var pausedTabs: Set<Int64> = []
    @State private var frozen: [Int64: [LogEntry]] = [:]
    @State private var tabForm: TabFormMode?

    private enum TabFormMode: Identifiable {
        case new
        case edit(Int64)

        var id: Int64 {
            switch self {
            case .new: return -1
            case .edit(let tabId): return tabId
            }
        }
    }

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    // ponytail: filtering re-runs on each ~1 Hz publish over the whole buffer. Fine at real
    // volumes for a debug tool; move off-main behind a task if it ever shows in a profile.
    private func filteredRows(minLevel: LogLevel, query: String) -> [LogEntry] {
        let visible = state.snapshot
        if query.isEmpty && minLevel == .debug { return visible }
        let predicate = LogQuery.compile(LogFilter(minLevel: minLevel, query: query))
        return visible.filter(predicate)
    }

    /// The active tab's live view of the stream, ignoring any freeze.
    private var liveFiltered: [LogEntry] {
        filteredRows(minLevel: minLevel, query: query)
    }

    private var displayed: [LogEntry] {
        if pausedTabs.contains(activeTabId) { return frozen[activeTabId] ?? [] }
        return liveFiltered
    }

    private var isActiveTabPaused: Bool { pausedTabs.contains(activeTabId) }

    private static func loadTabs() -> [SavedFilter] {
        var loaded = Prefs.savedFilters()
        if !loaded.contains(where: { $0.id == 0 }) {
            loaded.insert(SavedFilter(id: 0, name: "All"), at: 0)
        }
        return loaded
    }

    private var matcher: TextMatcher { TextMatcher.from(findQuery) }

    private var matchIds: [Int64] {
        guard findActive, findQuery.isActive else { return [] }
        let m = matcher
        // Android's tested rule: match against "tag message" joined by one space — per entry,
        // not per occurrence.
        return displayed.filter { m.matches("\($0.tag) \($0.message)") }.map(\.id)
    }

    /// Regular width shows the line inspector beside the stream; compact presents a sheet.
    private var isRegular: Bool { hSize == .regular }

    private var sheetSelection: Binding<LogEntry?> {
        Binding(
            get: { isRegular ? nil : selectedEntry },
            set: { selectedEntry = $0 }
        )
    }

    /// Hits keyed by the line they matched, for the gutter/pill on rows. Muted signals are
    /// dropped here too, like Android — a mute hides pills and stripes, not just tab rows.
    private var hitsByEntryId: [Int64: SignalHit] {
        let muted = Prefs.mutedSignals()
        var out: [Int64: SignalHit] = [:]
        for hit in state.signalHits where !muted.contains(hit.signal.id) {
            if let id = hit.entryId { out[id] = hit }
        }
        return out
    }

    var body: some View {
        let rows = displayed
        let matches = matchIds
        NavigationStack {
            VStack(spacing: 0) {
                header(rows: rows)
                tabsRow
                if isActiveTabPaused { frozenTabBanner.padding(.top, 8) }
                if state.status == .paused { pausedBanner.padding(.top, 8) }
                content(rows: rows, matches: matches)
            }
            // The custom header replaces the bar visually, but the title still feeds the pushed
            // screen's "‹ Logs" back item — continuing the task pushes, per the design.
            .navigationTitle("Logs")
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showSettings) {
                SettingsScreen(core: core)
            }
            .safeAreaInset(edge: .bottom) {
                if findActive { findBar(matches: matches) }
            }
        }
        .onAppear { selectTab(activeTabId) }
        .onChange(of: state.revealEntryId) { id in
            // A Signals-tab jump: land on All (it shows everything), stop following the tail,
            // and open the line's sheet/inspector.
            guard let id else { return }
            state.revealEntryId = nil
            selectTab(0)
            autoscroll = false
            revealTarget = id
            if let entry = state.snapshot.first(where: { $0.id == id }) {
                selectedEntry = entry
            }
        }
        .onChange(of: query) { _ in writeBackActiveTab() }
        .onChange(of: minLevel) { _ in writeBackActiveTab() }
        .onChange(of: viewMode) { _ in writeBackActiveTab() }
        .sheet(item: sheetSelection) { entry in
            LogLineSheet(entry: entry, hit: hitsByEntryId[entry.id]) { tag in
                query = "tag:\"\(tag)\""
            }
        }
        .sheet(item: $tabForm) { mode in
            switch mode {
            case .new:
                TabFormSheet(title: "New Tab", confirm: "Add", initial: nil, defaultName: nextDefaultTabName()) { draft in
                    let next = SavedFilter(
                        id: (tabs.map(\.id).max() ?? 0) + 1,
                        name: draft.name,
                        filter: LogFilter(minLevel: draft.minLevel, query: draft.query),
                        viewMode: draft.viewMode
                    )
                    tabs.append(next)
                    Prefs.setSavedFilters(tabs)
                    selectTab(next.id)
                }
            case .edit(let tabId):
                TabFormSheet(
                    title: "Tab Settings",
                    confirm: "Done",
                    initial: tabs.first { $0.id == tabId }
                ) { draft in
                    guard let index = tabs.firstIndex(where: { $0.id == tabId }) else { return }
                    tabs[index].name = draft.name
                    tabs[index].filter = LogFilter(minLevel: draft.minLevel, query: draft.query)
                    tabs[index].viewMode = draft.viewMode
                    Prefs.setSavedFilters(tabs)
                    if tabId == activeTabId { selectTab(tabId) }
                }
            }
        }
    }

    // MARK: tabs

    private func selectTab(_ id: Int64) {
        guard let tab = tabs.first(where: { $0.id == id }) else { return }
        activeTabId = id
        query = tab.filter.query
        minLevel = tab.filter.minLevel
        viewMode = tab.viewMode
    }

    /// The active tab owns the filter state — edits write through and persist, like Android.
    private func writeBackActiveTab() {
        guard let index = tabs.firstIndex(where: { $0.id == activeTabId }) else { return }
        tabs[index].filter = LogFilter(minLevel: minLevel, query: query)
        tabs[index].viewMode = viewMode
        Prefs.setSavedFilters(tabs)
    }

    private func closeTab(_ id: Int64) {
        guard id != 0 else { return }
        tabs.removeAll { $0.id == id }
        pausedTabs.remove(id)
        frozen[id] = nil
        Prefs.setSavedFilters(tabs)
        if activeTabId == id { selectTab(0) }
    }

    /// Freezes what that tab shows right now; capture keeps running for every tab.
    private func pauseTab(_ id: Int64) {
        guard let tab = tabs.first(where: { $0.id == id }) else { return }
        frozen[id] = id == activeTabId
            ? liveFiltered
            : filteredRows(minLevel: tab.filter.minLevel, query: tab.filter.query)
        pausedTabs.insert(id)
    }

    private func resumeTab(_ id: Int64) {
        pausedTabs.remove(id)
        frozen[id] = nil
    }

    /// "Log 1", "Log 2", … — the first number not already taken, so the name is never mandatory.
    private func nextDefaultTabName() -> String {
        let names = Set(tabs.map(\.name))
        var n = 1
        while names.contains("Log \(n)") { n += 1 }
        return "Log \(n)"
    }

    /// "Starting a custom tab from everything is the common way to make one."
    private func duplicateTab(_ tab: SavedFilter) {
        let next = SavedFilter(
            id: (tabs.map(\.id).max() ?? 0) + 1,
            name: tab.id == 0 ? "All copy" : "\(tab.name) copy",
            filter: tab.filter,
            viewMode: tab.viewMode
        )
        tabs.append(next)
        Prefs.setSavedFilters(tabs)
        selectTab(next.id)
    }

    /// A full-bleed band, per rev 4.1: the tab strip reads as its own bar — tinted, hairlined —
    /// not as pills floating in the header.
    private var tabsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(tabs, id: \.id) { tab in
                    let isActive = tab.id == activeTabId
                    let isPaused = pausedTabs.contains(tab.id)
                    Button {
                        selectTab(tab.id)
                    } label: {
                        HStack(spacing: 5) {
                            if isPaused {
                                Image(systemName: "pause.fill").font(.system(size: 9, weight: .bold))
                            }
                            Text(tab.name)
                                .font(.system(size: 13, weight: isActive || isPaused ? .semibold : .medium))
                        }
                        .padding(.horizontal, 13)
                        .padding(.vertical, 6)
                        .background(
                            isPaused ? Color(hex: 0xFF9F0A) : (isActive ? Color.accentColor : Color(.tertiarySystemFill)),
                            in: Capsule()
                        )
                        .foregroundStyle(isPaused ? .black : (isActive ? .white : .primary))
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        // "All is the raw stream and has nothing to configure" — no settings,
                        // no rename, no Close for id 0.
                        if tab.id != 0 {
                            Button {
                                tabForm = .edit(tab.id)
                            } label: {
                                Label("Tab Settings…", systemImage: "line.3.horizontal")
                            }
                        }
                        if isPaused {
                            Button {
                                resumeTab(tab.id)
                            } label: {
                                Label("Resume This Tab", systemImage: "play.fill")
                            }
                        } else {
                            Button {
                                pauseTab(tab.id)
                            } label: {
                                Label("Pause This Tab", systemImage: "pause.fill")
                            }
                        }
                        Button {
                            duplicateTab(tab)
                        } label: {
                            Label(tab.id == 0 ? "Duplicate as New Tab" : "Duplicate", systemImage: "plus.square.on.square")
                        }
                        if tab.id != 0 {
                            Button(role: .destructive) {
                                closeTab(tab.id)
                            } label: {
                                Label("Close Tab", systemImage: "trash")
                            }
                        }
                    }
                }
                Button {
                    tabForm = .new
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 9)
        }
        .background(scheme == .dark ? Color(hex: 0x1C1C1E).opacity(0.55) : Color(hex: 0xF2F2F7).opacity(0.7))
        .overlay(alignment: .bottom) { Divider() }
    }

    /// The per-tab freeze banner: what is waiting, and the way back.
    private var frozenTabBanner: some View {
        let buffered = max(0, liveFiltered.count - (frozen[activeTabId]?.count ?? 0))
        return HStack(spacing: 10) {
            Image(systemName: "pause.fill").foregroundStyle(Color(hex: 0xFF9F0A))
            VStack(alignment: .leading, spacing: 2) {
                Text("Frozen — this tab is paused")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xFF9F0A))
                // Android shows the buffered count only once there is one.
                Text(buffered > 0
                    ? "+\(buffered.formatted()) buffered · other tabs keep recording"
                    : "Other tabs keep recording")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                resumeTab(activeTabId)
            } label: {
                Text("Resume")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color(hex: 0xFF9F0A), in: Capsule())
            }
        }
        .padding(12)
        .background(Color(hex: 0xFF9F0A).opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0xFF9F0A).opacity(0.4), lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    // MARK: header

    private func header(rows: [LogEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // One nav row: the labelled back item to the host and the trailing actions share it;
            // the large title sits alone beneath. Stacking a second header row would cost 44pt on
            // a screen whose job is fitting log lines.
            HStack(alignment: .center) {
                if let onDone {
                    BackButton(label: core.hostName, action: onDone)
                }
                Spacer()
                HStack(spacing: 8) {
                    // Like Android's app bar pause: global — every tab stops. Per-tab freezing
                    // lives in the overflow menu and the tab's long-press.
                    if state.status == .paused {
                        Button {
                            core.resume()
                        } label: {
                            Image(systemName: "play.fill")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color(hex: 0xFF9F0A))
                                .frame(width: 34, height: 34)
                                .background(Color(hex: 0xFF9F0A).opacity(0.22), in: Circle())
                        }
                    } else {
                        headerButton("pause.fill") { core.pause() }
                    }
                    headerButton("magnifyingglass") {
                        findActive.toggle()
                        if !findActive { findQuery = SearchQuery() }
                    }
                    // The design's "View · «tab»" menu: density, line behavior, export, then the
                    // destructive buffer wipe.
                    Menu {
                        Picker("Density", selection: $viewMode) {
                            Text("Standard").tag(ViewMode.standard)
                            Text("Compact").tag(ViewMode.compact)
                            Text("Raw").tag(ViewMode.raw)
                        }
                        Toggle("Wrap Long Lines", isOn: $wrap)
                        Toggle("Autoscroll", isOn: $autoscroll)
                        if isActiveTabPaused {
                            Button {
                                resumeTab(activeTabId)
                            } label: {
                                Label("Resume This Tab", systemImage: "play.fill")
                            }
                        } else {
                            Button {
                                pauseTab(activeTabId)
                            } label: {
                                Label("Pause This Tab", systemImage: "pause.fill")
                            }
                        }
                        Divider()
                        ShareLink(item: shareText(rows)) { Label("Export…", systemImage: "square.and.arrow.up") }
                        Button {
                            showSettings = true
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                        Divider()
                        // A real wipe, per the design: signal hits die with the buffer they
                        // point into, and frozen tabs thaw — their snapshots would lie.
                        Button(role: .destructive) {
                            core.clearBuffer()
                            pausedTabs = []
                            frozen = [:]
                        } label: {
                            Label("Clear Buffer", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 34, height: 34)
                            .background(Color(.tertiarySystemFill), in: Circle())
                    }
                }
            }
            // Root titled by tab: the back item carries the host name, freeing the title slot.
            VStack(alignment: .leading, spacing: 5) {
                Text("LOGSENSE")
                    .font(.system(size: 11.5, weight: .semibold))
                    .kerning(1)
                    .foregroundStyle(.secondary)
                Text("Logs")
                    .font(.system(size: 34, weight: .bold))
            }
            statusRow(rows: rows)
            filterField
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private func headerButton(_ systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 34, height: 34)
                .background(Color(.tertiarySystemFill), in: Circle())
        }
    }

    private func statusRow(rows: [LogEntry]) -> some View {
        HStack(spacing: 7) {
            switch state.status {
            case .waiting:
                dot(.orange, pulsing: false)
                Text("Connecting").foregroundStyle(.orange).fontWeight(.semibold)
                Text("· 0 lines")
            case .live:
                dot(.green, pulsing: true)
                Text("Live").foregroundStyle(.green).fontWeight(.semibold)
                if !pausedTabs.isEmpty {
                    let recording = tabs.count - tabs.filter { pausedTabs.contains($0.id) }.count
                    Text("· \(recording) of \(tabs.count) tabs recording")
                } else if isFiltering {
                    Text("· \(rows.count.formatted()) of \(state.snapshot.count.formatted())")
                } else {
                    Text("· \(state.totalReceived.formatted()) lines · buffer \(core.bufferLimit.formatted())")
                }
            case .paused:
                dot(.orange, pulsing: false)
                Text("Paused").foregroundStyle(.orange).fontWeight(.semibold)
                Text("· \(state.snapshot.count.formatted()) lines · \(state.bufferedWhilePaused.formatted()) buffered")
            }
        }
        .font(.system(size: 12.5, weight: .medium))
        .foregroundStyle(.secondary)
    }

    private func dot(_ color: Color, pulsing: Bool) -> some View {
        PulsingDot(color: color, active: pulsing)
    }

    private var isFiltering: Bool { !query.isEmpty || minLevel != .debug }

    private var filterField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            TextField("Filter logs", text: $query)
                .font(.system(size: 15, design: query.isEmpty ? .default : .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
            }
            Menu {
                Picker("Minimum level", selection: $minLevel) {
                    Text("All (D+)").tag(LogLevel.debug)
                    Text("Info (I+)").tag(LogLevel.info)
                    Text("Notice (N+)").tag(LogLevel.notice)
                    Text("Error (E+)").tag(LogLevel.error)
                    Text("Fault (F)").tag(LogLevel.fault)
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "line.3.horizontal.decrease")
                    Text(minLevel == .debug ? "All" : "\(minLevel.letter)+")
                }
                .font(.system(size: 13, weight: .medium))
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 36)
        .background(
            RoundedRectangle(cornerRadius: 11)
                .fill(isFiltering ? Color.accentColor.opacity(0.15) : Color(.tertiarySystemFill))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 11)
                .strokeBorder(isFiltering ? Color.accentColor.opacity(0.45) : .clear, lineWidth: 0.5)
        )
    }

    // MARK: banners & states

    private var pausedBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "pause.fill").foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("Capture paused")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.orange)
                Text("New lines are buffered, not shown. Resume to catch up.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                core.resume()
            } label: {
                Text("Resume")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.orange, in: Capsule())
            }
        }
        .padding(12)
        .background(Color.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.orange.opacity(0.4), lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func emptyState(rows: [LogEntry]) -> some View {
        if state.status == .waiting {
            EmptyStateView(
                icon: "list.bullet.rectangle",
                title: "Waiting for the first line",
                body: "Attached to the log stream. Use \(core.hostName) and its output appears here, newest at the bottom.",
                actionLabel: nil, action: nil
            )
        } else if isFiltering {
            EmptyStateView(
                icon: "line.3.horizontal.decrease",
                title: "No lines match" + (query.isEmpty ? "" : " “\(query)”"),
                body: "Capture is still running. Clear the filter, or lower the minimum level.",
                actionLabel: "Clear filter",
                action: { query = ""; minLevel = .debug }
            )
        } else {
            EmptyStateView(
                icon: "list.bullet.rectangle",
                title: "Nothing here yet",
                body: "Cleared. New lines appear as they arrive.",
                actionLabel: nil, action: nil
            )
        }
    }

    // MARK: content

    @ViewBuilder
    private func content(rows: [LogEntry], matches: [Int64]) -> some View {
        if rows.isEmpty {
            emptyState(rows: rows).frame(maxHeight: .infinity)
        } else {
            let hits = hitsByEntryId
            HStack(spacing: 0) {
                ScrollViewReader { proxy in
                    List {
                        ForEach(rows) { entry in
                            row(entry, hit: hits[entry.id])
                                .id(entry.id)
                                .contentShape(Rectangle())
                                .onTapGesture { selectedEntry = entry }
                                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                                .listRowSeparator(viewMode == .standard ? .visible : .hidden)
                                .listRowBackground(rowBackground(entry, matches: matches, hit: hits[entry.id]))
                        }
                        // Fixed-size sentinel: scrolling to the last real row is unreliable when
                        // rows wrap to variable heights — the sentinel always lands at the bottom.
                        Color.clear
                            .frame(height: 1)
                            .id(Self.bottomSentinel)
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .opacity(isActiveTabPaused ? 0.62 : 1)
                    .environment(\.defaultMinListRowHeight, 10)
                    .onAppear {
                        // First layout pass hasn't happened yet; scroll on the next runloop turn.
                        if autoscroll {
                            DispatchQueue.main.async { proxy.scrollTo(Self.bottomSentinel, anchor: .bottom) }
                        }
                    }
                    // The last id, not the count: at the buffer cap the count stops changing while
                    // lines keep flowing.
                    .onChange(of: rows.last?.id) { _ in
                        if autoscroll, state.status != .paused {
                            proxy.scrollTo(Self.bottomSentinel, anchor: .bottom)
                        }
                    }
                    .onChange(of: autoscroll) { on in
                        if on {
                            withAnimation { proxy.scrollTo(Self.bottomSentinel, anchor: .bottom) }
                        }
                    }
                    .onChange(of: findIndex) { _ in
                        if findActive, matches.indices.contains(findIndex) {
                            withAnimation { proxy.scrollTo(matches[findIndex], anchor: .center) }
                        }
                    }
                    .onChange(of: revealTarget) { target in
                        if let target {
                            withAnimation { proxy.scrollTo(target, anchor: .center) }
                            revealTarget = nil
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        VStack(spacing: 10) {
                            fabButton("arrow.up") {
                                autoscroll = false
                                if let first = rows.first {
                                    withAnimation { proxy.scrollTo(first.id, anchor: .top) }
                                }
                            }
                            if !autoscroll {
                                fabButton("arrow.down") {
                                    // The onChange(of: autoscroll) hook does the scrolling.
                                    autoscroll = true
                                }
                            }
                        }
                        .padding(.trailing, 18)
                        .padding(.bottom, 14)
                    }
                    .simultaneousGesture(DragGesture().onChanged { _ in autoscroll = false })
                }
                if isRegular, let selected = selectedEntry,
                   let index = rows.firstIndex(where: { $0.id == selected.id }) {
                    Divider()
                    LineInspector(
                        entry: selected,
                        hit: hitsByEntryId[selected.id],
                        neighbors: Array(rows[max(0, index - 2)...min(rows.count - 1, index + 2)]),
                        onFilterTag: { tag in query = "tag:\"\(tag)\"" },
                        onClose: { selectedEntry = nil }
                    )
                }
            }
        }
    }

    /// Android's tested precedence: selection/find first, then signal tint (which wins over
    /// fault/error), then fault, then error.
    private func rowBackground(_ entry: LogEntry, matches: [Int64], hit: SignalHit?) -> Color {
        if isRegular, selectedEntry?.id == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if findActive, matches.indices.contains(findIndex), matches[findIndex] == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if let hit {
            return hit.signal.category.color.opacity(0.10)
        }
        if entry.level == .fault {
            return entry.level.color(scheme).opacity(0.09)
        }
        if entry.level == .error {
            return entry.level.color(scheme).opacity(0.08)
        }
        return .clear
    }

    private static let bottomSentinel = "logsense.bottom"

    private func fabButton(_ systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        }
    }

    @ViewBuilder
    private func row(_ entry: LogEntry, hit: SignalHit?) -> some View {
        let base = Group {
            switch viewMode {
            case .standard where isRegular:
                // Regular width: the design's iPad stream row — time, chip, 92pt tag column, message.
                PadRow(entry: entry, wrap: wrap, highlight: findActive ? matcher : nil)
            case .standard:
                StandardRow(entry: entry, wrap: wrap, highlight: findActive ? matcher : nil, hit: hit)
            case .compact:
                CompactRow(entry: entry, wrap: wrap)
            case .raw:
                RawRow(entry: entry, wrap: wrap)
            }
        }
        if let hit {
            base.overlay(alignment: .leading) {
                Rectangle().fill(hit.signal.category.color).frame(width: 3)
            }
        } else {
            base
        }
    }

    // MARK: find bar

    private func findBar(matches: [Int64]) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    TextField("Find in view", text: $findQuery.text)
                        .font(.system(size: 14, design: .monospaced))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: findQuery.text) { _ in findIndex = 0 }
                    Text("\(matches.isEmpty ? 0 : findIndex + 1)/\(matches.count)")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .layoutPriority(1)
                }
                .padding(.horizontal, 9)
                .frame(height: 34)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 9))
                Button { step(-1, within: matches) } label: { Image(systemName: "chevron.up") }
                    .disabled(matches.isEmpty)
                Button { step(1, within: matches) } label: { Image(systemName: "chevron.down") }
                    .disabled(matches.isEmpty)
                Button("Done") {
                    findActive = false
                    findQuery = SearchQuery()
                }
                .fontWeight(.semibold)
            }
            HStack(spacing: 14) {
                findToggle("Aa", isOn: $findQuery.matchCase)
                findToggle("W", isOn: $findQuery.wholeWord)
                findToggle(".*", isOn: $findQuery.regex)
                Spacer()
            }
            .font(.system(size: 13, weight: .medium, design: .monospaced))
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func findToggle(_ label: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            findIndex = 0
        } label: {
            Text(label)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    isOn.wrappedValue ? Color.accentColor.opacity(0.25) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 6)
                )
                .foregroundStyle(isOn.wrappedValue ? Color.accentColor : Color.secondary)
        }
    }

    private func step(_ delta: Int, within matches: [Int64]) {
        guard !matches.isEmpty else { return }
        findIndex = ((findIndex + delta) % matches.count + matches.count) % matches.count
    }

    /// Android's exact shared-line format: `HH:mm:ss.SSS pid-tid L tag: message`, every line
    /// newline-terminated including the last.
    private func shareText(_ rows: [LogEntry]) -> String {
        rows.map { entry in
            "\(Format.time(entry.timeMs)) \(entry.pid)-\(entry.tid) \(entry.level.letter) \(entry.tag): \(entry.message)\n"
        }
        .joined()
    }
}

// MARK: - tab form

/// One sheet for New Tab and Tab Settings — "two doors to the same place is a menu that has to
/// be read twice." Name, filter query, view and minimum level, every one scoped to this tab alone.
private struct TabFormSheet: View {
    let title: String
    let confirm: String
    let onSave: (Draft) -> Void
    @Environment(\.dismiss) private var dismiss

    struct Draft {
        var name: String
        var query: String
        var viewMode: ViewMode
        var minLevel: LogLevel
    }

    @State private var draft: Draft

    private let defaultName: String

    init(
        title: String,
        confirm: String,
        initial: SavedFilter?,
        defaultName: String = "",
        onSave: @escaping (Draft) -> Void
    ) {
        self.title = title
        self.confirm = confirm
        self.onSave = onSave
        self.defaultName = initial?.name ?? defaultName
        _draft = State(initialValue: Draft(
            name: initial?.name ?? defaultName,
            query: initial?.filter.query ?? "",
            viewMode: initial?.viewMode ?? .standard,
            minLevel: initial?.filter.minLevel ?? .debug
        ))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Name") {
                        TextField("Payments", text: $draft.name)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Filter") {
                        TextField("tag:PaymentSDK upi", text: $draft.query)
                            .multilineTextAlignment(.trailing)
                            .font(.system(size: 14, design: .monospaced))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                } footer: {
                    Text("Same query language as the filter field. Leave it empty for an unfiltered tab.")
                }

                Section("View") {
                    Picker("View", selection: $draft.viewMode) {
                        Text("Standard").tag(ViewMode.standard)
                        Text("Compact").tag(ViewMode.compact)
                        Text("Raw").tag(ViewMode.raw)
                    }
                    .pickerStyle(.segmented)
                }

                Section("Minimum level") {
                    Picker("Minimum level", selection: $draft.minLevel) {
                        ForEach(LogLevel.allCases, id: \.self) { level in
                            Text(String(level.letter))
                                .font(.system(.body, design: .monospaced))
                                .tag(level)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                } footer: {
                    Text("A tab keeps its own filter, scroll position and pause state. Capture keeps running for every tab, including the ones you are not looking at. Custom tabs can be closed at any time; All cannot.")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm) {
                        draft.name = draft.name.trimmingCharacters(in: .whitespaces)
                        // The name is never mandatory — a cleared field falls back to the default.
                        if draft.name.isEmpty { draft.name = defaultName }
                        onSave(draft)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - rows

/// Android's LINE scroll mode: with wrap off, the message scrolls horizontally on its own —
/// the gutter, chip and metadata stay put.
private struct LineScrollable<Content: View>: View {
    let wrap: Bool
    @ViewBuilder let content: Content

    var body: some View {
        if wrap {
            content
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                content.fixedSize(horizontal: true, vertical: false)
            }
        }
    }
}

private struct StandardRow: View {
    let entry: LogEntry
    let wrap: Bool
    let highlight: TextMatcher?
    let hit: SignalHit?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(String(entry.level.letter))
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(entry.level.color(scheme))
                .frame(width: 21, height: 21)
                .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6.5))
            VStack(alignment: .leading, spacing: 3) {
                LineScrollable(wrap: wrap) {
                    messageText
                        .font(.system(size: 12.5, design: .monospaced))
                        // Android's tested rule: error and fault messages read in their level color.
                        .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                        .lineLimit(wrap ? nil : 1)
                }
                HStack(spacing: 9) {
                    Text(Format.time(entry.timeMs))
                    Text(entry.tag).foregroundStyle(.secondary)
                    if let hit {
                        Text(hit.signal.label)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(hit.signal.category.color)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1.5)
                            .background(hit.signal.category.color.opacity(0.13), in: Capsule())
                    }
                }
                .font(.system(size: 10.5))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var messageText: Text {
        if let highlight {
            return Text(Format.highlighted(entry.message, matcher: highlight))
        }
        return Text(entry.message)
    }
}

/// The design's regular-width stream row: time, level chip, a fixed 92pt tag column, message.
private struct PadRow: View {
    let entry: LogEntry
    let wrap: Bool
    let highlight: TextMatcher?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 11) {
            Text(Format.time(entry.timeMs))
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(.tertiary)
            Text(String(entry.level.letter))
                .font(.system(size: 10.5, weight: .bold, design: .monospaced))
                .foregroundStyle(entry.level.color(scheme))
                .frame(width: 19, height: 19)
                .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6))
            Text(entry.tag)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(width: 92, alignment: .leading)
            LineScrollable(wrap: wrap) {
                messageText
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                    .lineLimit(wrap ? nil : 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var messageText: Text {
        if let highlight {
            return Text(Format.highlighted(entry.message, matcher: highlight))
        }
        return Text(entry.message)
    }
}

/// The design's line inspector: a 324pt panel beside the stream on regular width — the line in
/// full, its neighbors for context, and the two actions that follow from a line.
private struct LineInspector: View {
    let entry: LogEntry
    let hit: SignalHit?
    let neighbors: [LogEntry]
    let onFilterTag: (String) -> Void
    let onClose: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    caption("Line inspector")
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.secondary)
                            .frame(width: 26, height: 26)
                            .background(Color(.tertiarySystemFill), in: Circle())
                    }
                }
                HStack(spacing: 9) {
                    Text(String(entry.level.letter))
                        .font(.system(size: 10.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(entry.level.color(scheme))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6))
                    Text("\(Format.time(entry.timeMs)) · pid \(entry.pid) · tid \(entry.tid)")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 14)
                Text(entry.tag)
                    .font(.system(size: 19, weight: .semibold))
                    .padding(.top, 10)
                if let hit {
                    HStack(spacing: 6) {
                        Circle().fill(hit.signal.category.color).frame(width: 6, height: 6)
                        Text(hit.signal.label)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(hit.signal.category.color)
                    }
                    .padding(.top, 6)
                }
                Text(entry.message)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(13)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 12)

                caption("Around this line").padding(.top, 16)
                VStack(spacing: 0) {
                    ForEach(neighbors) { neighbor in
                        HStack(spacing: 9) {
                            Text(Format.time(neighbor.timeMs).suffix(6))
                                .foregroundStyle(.tertiary)
                            Text(String(neighbor.level.letter))
                                .fontWeight(.bold)
                                .foregroundStyle(neighbor.level.color(scheme))
                            Text(neighbor.message)
                                .lineLimit(1)
                                .foregroundStyle(neighbor.id == entry.id ? .primary : .secondary)
                        }
                        .font(.system(size: 11, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        if neighbor.id != neighbors.last?.id { Divider().padding(.leading, 12) }
                    }
                }
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 9)

                HStack(spacing: 9) {
                    inspectorButton("Copy line") { UIPasteboard.general.string = entry.message }
                    inspectorButton("Filter this tag") { onFilterTag(entry.tag) }
                }
                .padding(.top, 14)
            }
            .padding(18)
        }
        .frame(width: 324)
        .background(Color(.systemBackground))
    }

    private func caption(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 12.5, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
    }

    private func inspectorButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13.5, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 11))
        }
    }
}

private struct CompactRow: View {
    let entry: LogEntry
    let wrap: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(Format.time(entry.timeMs))
                .foregroundStyle(.tertiary)
            Text(String(entry.level.letter))
                .fontWeight(.bold)
                .foregroundStyle(entry.level.color(scheme))
            Text(entry.tag)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: 96, alignment: .leading)
            // Compact never wraps; the toggle picks truncate vs horizontal scroll.
            LineScrollable(wrap: wrap) {
                Text(entry.message)
                    .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .font(.system(size: 11.5, design: .monospaced))
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct RawRow: View {
    let entry: LogEntry
    let wrap: Bool

    var body: some View {
        LineScrollable(wrap: wrap) {
            Text("\(Format.time(entry.timeMs)) \(String(entry.level.letter)) \(entry.tag): \(entry.message)")
                .font(.system(size: 11.5, design: .monospaced))
                .lineLimit(wrap ? nil : 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 3)
    }
}

// MARK: - shared bits

/// The way out of the overlay window: a standard back item **labelled with its destination** —
/// the host app's name — shown on every root. A ✕ says the thing disappears without saying where
/// you land; one step backwards to the host is what dismissal means here.
internal struct BackButton: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .semibold))
                Text(label)
                    .font(.system(size: 17))
                    .lineLimit(1)
            }
        }
        .accessibilityLabel("Back to \(label)")
    }
}

internal struct EmptyStateView: View {
    let icon: String
    let title: String
    let body_: String
    let actionLabel: String?
    let action: (() -> Void)?

    init(icon: String, title: String, body: String, actionLabel: String?, action: (() -> Void)?) {
        self.icon = icon
        self.title = title
        self.body_ = body
        self.actionLabel = actionLabel
        self.action = action
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 26))
                .foregroundStyle(.secondary)
                .frame(width: 56, height: 56)
                .background(Color(.tertiarySystemFill), in: Circle())
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .multilineTextAlignment(.center)
            Text(body_)
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
            if let actionLabel, let action {
                Button(action: action) {
                    Text(actionLabel)
                        .font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 9)
                        .background(Color.accentColor.opacity(0.2), in: Capsule())
                }
            }
        }
        .padding(.horizontal, 44)
    }
}

/// TimelineView-driven blink instead of a `repeatForever` animation: a persistent repeating
/// animation on a view whose layout shifts (the status text next to it re-renders every second)
/// leaks into those position changes and the dot rides every reflow. Deriving opacity from the
/// clock animates nothing but opacity.
private struct PulsingDot: View {
    let color: Color
    let active: Bool

    var body: some View {
        if active {
            TimelineView(.periodic(from: .now, by: 0.9)) { timeline in
                let on = Int(timeline.date.timeIntervalSinceReferenceDate / 0.9) % 2 == 0
                Circle()
                    .fill(color)
                    .frame(width: 7, height: 7)
                    .opacity(on ? 1 : 0.3)
                    .animation(.easeInOut(duration: 0.85), value: on)
            }
        } else {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
        }
    }
}

#endif
