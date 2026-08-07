#if os(iOS)
import SwiftUI

internal extension SignalCategory {
    var color: Color {
        switch self {
        case .fault: return Color(hex: 0xFF453A)
        case .hang, .memory: return Color(hex: 0xFF9F0A)
        case .custom: return Color(hex: 0x0A84FF)
        case .ui, .network: return Color(hex: 0x64D2FF)
        case .data: return Color(hex: 0x5E5CE6)
        case .resource, .lifecycle: return Color(hex: 0x98989D)
        }
    }
}

/// One line, in full: level, time, thread, tag, the message selectable, and the signal that flagged
/// it when there is one. The design's line inspector, sized for a sheet.
internal struct LogLineSheet: View {
    let entry: LogEntry
    let hit: SignalHit?
    let onFilterTag: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    /// Android's tested rule: the Pretty/Raw toggle appears only when the message holds parseable
    /// JSON, and Pretty is preselected when it does.
    private let pretty: String?
    @State private var showPretty: Bool

    init(entry: LogEntry, hit: SignalHit?, onFilterTag: @escaping (String) -> Void) {
        self.entry = entry
        self.hit = hit
        self.onFilterTag = onFilterTag
        let pretty = Format.prettyJson(in: entry.message)
        self.pretty = pretty
        _showPretty = State(initialValue: pretty != nil)
    }

    /// Copy and Share send whatever is currently displayed, pretty or raw.
    private var displayedBody: String {
        showPretty ? (pretty ?? entry.message) : entry.message
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 9) {
                        Text(String(entry.level.letter))
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(entry.level.color(scheme))
                            .frame(width: 24, height: 24)
                            .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 7))
                        Text("\(Format.time(entry.timeMs)) · pid \(entry.pid) · tid \(entry.tid)")
                            .font(.system(size: 12.5))
                            .foregroundStyle(.secondary)
                    }
                    Text(entry.tag)
                        .font(.system(size: 19, weight: .semibold, design: .monospaced))
                        .foregroundStyle(TagColor.color(for: entry.tag, scheme: scheme))
                        .lineLimit(2)
                        .truncationMode(.middle)
                        .padding(.top, 10)
                    if !entry.subsystem.isEmpty {
                        Text(entry.subsystem)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(.tertiary)
                            .padding(.top, 2)
                    }

                    if let hit {
                        HStack(spacing: 7) {
                            Circle().fill(hit.signal.category.color).frame(width: 7, height: 7)
                            Text(hit.signal.label)
                                .font(.system(size: 12.5, weight: .semibold))
                                .foregroundStyle(hit.signal.category.color)
                            Text(hit.signal.category.label)
                                .font(.system(size: 11.5))
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(hit.signal.category.color.opacity(0.12), in: Capsule())
                        .padding(.top, 12)
                    }

                    if pretty != nil {
                        HStack(spacing: 8) {
                            selectPill("Pretty", isOn: showPretty) { showPretty = true }
                            selectPill("Raw", isOn: !showPretty) { showPretty = false }
                        }
                        .padding(.top, 12)
                    }

                    Text(displayedBody)
                        .font(.system(size: 12.5, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(13)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 14)

                    HStack(spacing: 10) {
                        actionButton("Copy line") {
                            UIPasteboard.general.string = displayedBody
                        }
                        actionButton("Filter this tag") {
                            onFilterTag(entry.tag)
                            dismiss()
                        }
                    }
                    .padding(.top, 14)
                }
                .padding(16)
            }
            .navigationTitle("Log line")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: displayedBody) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func selectPill(_ label: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: isOn ? .semibold : .medium))
                .padding(.horizontal, 13)
                .padding(.vertical, 6)
                .background(isOn ? Color.accentColor : Color(.tertiarySystemFill), in: Capsule())
                .foregroundStyle(isOn ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    private func actionButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
#endif
