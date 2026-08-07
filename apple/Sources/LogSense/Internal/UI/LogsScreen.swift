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

    // Android's tab model: each tab owns its filter, min level and density, persisted across runs.
    // "All" (id 0) is the one tab that can't be closed — a stable home to come back to.
    @State private var tabs: [SavedFilter] = LogsScreen.loadTabs()
    @State private var activeTabId: Int64 = 0
    /// Per-tab clear watermarks — runtime-only, like Android: entry ids restart with the reader,
    /// so persisting one would be meaningless next run.
    @State private var clearedAt: [Int64: Int64] = [:]
    @State private var addingTab = false
    @State private var renamingTab = false
    @State private var tabNameDraft = ""

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    // ponytail: filtering re-runs on each ~1 Hz publish over the whole buffer. Fine at real
    // volumes for a debug tool; move off-main behind a task if it ever shows in a profile.
    private var displayed: [LogEntry] {
        let visible = Array(state.snapshot.since(clearedAt[activeTabId] ?? 0))
        let filter = LogFilter(minLevel: minLevel, query: query)
        if query.isEmpty && minLevel == .debug { return visible }
        let predicate = LogQuery.compile(filter)
        return visible.filter(predicate)
    }

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
        return displayed.filter { m.matches($0.message) || m.matches($0.tag) }.map(\.id)
    }

    /// Regular width shows the line inspector beside the stream; compact presents a sheet.
    private var isRegular: Bool { hSize == .regular }

    private var sheetSelection: Binding<LogEntry?> {
        Binding(
            get: { isRegular ? nil : selectedEntry },
            set: { selectedEntry = $0 }
        )
    }

    /// Hits keyed by the line they matched, for the gutter/pill on rows.
    private var hitsByEntryId: [Int64: SignalHit] {
        var out: [Int64: SignalHit] = [:]
        for hit in state.signalHits {
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
                if state.status == .paused { pausedBanner }
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
        .onChange(of: query) { _ in writeBackActiveTab() }
        .onChange(of: minLevel) { _ in writeBackActiveTab() }
        .onChange(of: viewMode) { _ in writeBackActiveTab() }
        .sheet(item: sheetSelection) { entry in
            LogLineSheet(entry: entry, hit: hitsByEntryId[entry.id]) { tag in
                query = "tag:\"\(tag)\""
            }
        }
        .alert("New tab", isPresented: $addingTab) {
            TextField("Name", text: $tabNameDraft)
            Button("Add") {
                let name = tabNameDraft.trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { return }
                let next = SavedFilter(
                    id: (tabs.map(\.id).max() ?? 0) + 1,
                    name: name,
                    filter: LogFilter(minLevel: minLevel, query: query),
                    viewMode: viewMode
                )
                tabs.append(next)
                Prefs.setSavedFilters(tabs)
                selectTab(next.id)
                tabNameDraft = ""
            }
            Button("Cancel", role: .cancel) { tabNameDraft = "" }
        } message: {
            Text("Starts from the current filter, level and density; the tab keeps its own from here on.")
        }
        .alert("Rename tab", isPresented: $renamingTab) {
            TextField("Name", text: $tabNameDraft)
            Button("Rename") {
                let name = tabNameDraft.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty, let index = tabs.firstIndex(where: { $0.id == activeTabId }) {
                    tabs[index].name = name
                    Prefs.setSavedFilters(tabs)
                }
                tabNameDraft = ""
            }
            Button("Cancel", role: .cancel) { tabNameDraft = "" }
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
        Prefs.setSavedFilters(tabs)
        if activeTabId == id { selectTab(0) }
    }

    private var tabsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(tabs, id: \.id) { tab in
                    let isActive = tab.id == activeTabId
                    Button {
                        selectTab(tab.id)
                    } label: {
                        Text(tab.name)
                            .font(.system(size: 13, weight: isActive ? .semibold : .medium))
                            .padding(.horizontal, 13)
                            .padding(.vertical, 6)
                            .background(isActive ? Color.accentColor : Color(.tertiarySystemFill), in: Capsule())
                            .foregroundStyle(isActive ? .white : .primary)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            tabNameDraft = tab.name
                            selectTab(tab.id)
                            renamingTab = true
                        } label: {
                            Label("Rename…", systemImage: "pencil")
                        }
                        if tab.id != 0 {
                            Button(role: .destructive) {
                                closeTab(tab.id)
                            } label: {
                                Label("Close tab", systemImage: "xmark")
                            }
                        }
                    }
                }
                Button {
                    tabNameDraft = ""
                    addingTab = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
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
                    if state.status == .paused {
                        headerButton("play.fill") { core.resume() }
                    } else {
                        headerButton("pause.fill") { core.pause() }
                    }
                    headerButton("magnifyingglass") {
                        findActive.toggle()
                        if !findActive { findQuery = SearchQuery() }
                    }
                    Menu {
                        Picker("Density", selection: $viewMode) {
                            Text("Standard").tag(ViewMode.standard)
                            Text("Compact").tag(ViewMode.compact)
                            Text("Raw").tag(ViewMode.raw)
                        }
                        Toggle("Wrap long lines", isOn: $wrap)
                        Toggle("Autoscroll", isOn: $autoscroll)
                        ShareLink(item: shareText(rows)) { Label("Share…", systemImage: "square.and.arrow.up") }
                        Button {
                            showSettings = true
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                        // Clears this tab only — a watermark, not a buffer wipe: other tabs still
                        // show the lines and signal hits still point at real entries.
                        Button("Clear tab", role: .destructive) {
                            clearedAt[activeTabId] = state.snapshot.last?.id ?? 0
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
            tabsRow
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
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
                if isFiltering {
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
        Circle()
            .fill(color)
            .frame(width: 7, height: 7)
            .modifier(PulseEffect(active: pulsing))
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
                                .listRowBackground(rowBackground(entry, matches: matches))
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

    private func rowBackground(_ entry: LogEntry, matches: [Int64]) -> Color {
        if isRegular, selectedEntry?.id == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if findActive, matches.indices.contains(findIndex), matches[findIndex] == entry.id {
            return Color.accentColor.opacity(0.12)
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
                CompactRow(entry: entry)
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
                    Text(matches.isEmpty ? "0" : "\(findIndex + 1) of \(matches.count)")
                        .font(.system(size: 12, weight: .medium))
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

    private func shareText(_ rows: [LogEntry]) -> String {
        rows.map { entry in
            "\(Format.time(entry.timeMs)) \(entry.level.letter) \(entry.tag): \(entry.message)"
        }
        .joined(separator: "\n")
    }
}

// MARK: - rows

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
                messageText
                    .font(.system(size: 12.5, design: .monospaced))
                    .lineLimit(wrap ? nil : 1)
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
            messageText
                .font(.system(size: 12.5, design: .monospaced))
                .lineLimit(wrap ? nil : 1)
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
            Text(entry.message)
                .lineLimit(1)
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
        Text("\(Format.time(entry.timeMs)) \(String(entry.level.letter)) \(entry.tag): \(entry.message)")
            .font(.system(size: 11.5, design: .monospaced))
            .lineLimit(wrap ? nil : 1)
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

private struct PulseEffect: ViewModifier {
    let active: Bool
    @State private var dim = false

    func body(content: Content) -> some View {
        content
            .opacity(active && dim ? 0.25 : 1)
            .animation(active ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : .default, value: dim)
            .onAppear { if active { dim = true } }
    }
}

#endif
