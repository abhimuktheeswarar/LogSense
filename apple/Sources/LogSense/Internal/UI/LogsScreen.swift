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

    @State private var query = ""
    @State private var minLevel: LogLevel = .debug
    @State private var viewMode: ViewMode = .standard
    @State private var wrap = true
    @State private var autoscroll = true
    @State private var findQuery = SearchQuery()
    @State private var findActive = false
    @State private var findIndex = 0

    init(core: LogSenseCore, onDone: (() -> Void)?) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    // ponytail: filtering re-runs on each ~1 Hz publish over the whole buffer. Fine at real
    // volumes for a debug tool; move off-main behind a task if it ever shows in a profile.
    private var displayed: [LogEntry] {
        let visible = Array(state.snapshot.since(state.clearedAtId))
        let filter = LogFilter(minLevel: minLevel, query: query)
        if query.isEmpty && minLevel == .debug { return visible }
        let predicate = LogQuery.compile(filter)
        return visible.filter(predicate)
    }

    private var matcher: TextMatcher { TextMatcher.from(findQuery) }

    private var matchIds: [Int64] {
        guard findActive, findQuery.isActive else { return [] }
        let m = matcher
        return displayed.filter { m.matches($0.message) || m.matches($0.tag) }.map(\.id)
    }

    var body: some View {
        let rows = displayed
        let matches = matchIds
        VStack(spacing: 0) {
            header(rows: rows)
            if state.status == .paused { pausedBanner }
            content(rows: rows, matches: matches)
        }
        .safeAreaInset(edge: .bottom) {
            if findActive { findBar(matches: matches) }
        }
    }

    // MARK: header

    private func header(rows: [LogEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("LOGSENSE")
                        .font(.system(size: 11.5, weight: .semibold))
                        .kerning(1)
                        .foregroundStyle(.secondary)
                    Text(core.hostName)
                        .font(.system(size: 34, weight: .bold))
                        .lineLimit(1)
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
                        Button("Clear", role: .destructive) { core.clear() }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 34, height: 34)
                            .background(Color(.tertiarySystemFill), in: Circle())
                    }
                    if let onDone {
                        Button("Done", action: onDone)
                            .font(.system(size: 15, weight: .semibold))
                    }
                }
            }
            statusRow(rows: rows)
            filterField
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
            ScrollViewReader { proxy in
                List {
                    ForEach(rows) { entry in
                        row(entry)
                            .id(entry.id)
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowSeparator(viewMode == .standard ? .visible : .hidden)
                            .listRowBackground(rowBackground(entry, matches: matches))
                    }
                }
                .listStyle(.plain)
                .environment(\.defaultMinListRowHeight, 10)
                .onChange(of: state.snapshot.count) { _ in
                    if autoscroll, state.status != .paused, let last = rows.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
                .onChange(of: findIndex) { _ in
                    if findActive, matches.indices.contains(findIndex) {
                        withAnimation { proxy.scrollTo(matches[findIndex], anchor: .center) }
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if !autoscroll {
                        jumpToLatestButton {
                            autoscroll = true
                            if let last = rows.last { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
                .simultaneousGesture(DragGesture().onChanged { _ in autoscroll = false })
            }
        }
    }

    private func rowBackground(_ entry: LogEntry, matches: [Int64]) -> Color {
        if findActive, matches.indices.contains(findIndex), matches[findIndex] == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        return .clear
    }

    private func jumpToLatestButton(action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "arrow.down")
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        }
        .padding(.trailing, 18)
        .padding(.bottom, 14)
    }

    @ViewBuilder
    private func row(_ entry: LogEntry) -> some View {
        switch viewMode {
        case .standard: StandardRow(entry: entry, wrap: wrap, highlight: findActive ? matcher : nil)
        case .compact: CompactRow(entry: entry)
        case .raw: RawRow(entry: entry, wrap: wrap)
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

internal enum Format {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    static func time(_ ms: Int64) -> String {
        timeFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// The message with find-bar matches marked, for `Text(AttributedString)`.
    static func highlighted(_ text: String, matcher: TextMatcher) -> AttributedString {
        var attributed = AttributedString(text)
        for utf16Range in matcher.ranges(text) {
            guard let lower = text.utf16.index(text.utf16.startIndex, offsetBy: utf16Range.lowerBound, limitedBy: text.utf16.endIndex),
                  let upper = text.utf16.index(text.utf16.startIndex, offsetBy: utf16Range.upperBound, limitedBy: text.utf16.endIndex),
                  let stringRange = Range<String.Index>(uncheckedBounds: (lower, upper)) as Range<String.Index>?,
                  let range = Range<AttributedString.Index>(stringRange, in: attributed)
            else { continue }
            attributed[range].backgroundColor = .yellow
            attributed[range].foregroundColor = .black
        }
        return attributed
    }
}
#endif
