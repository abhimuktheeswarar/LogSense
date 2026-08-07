#if os(iOS)
import SwiftUI

internal extension SignalCategory {
    var color: Color {
        switch self {
        case .crash: return Color(hex: 0xFF453A)
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
                        .font(.system(size: 19, weight: .semibold))
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

                    Text(entry.message)
                        .font(.system(size: 12.5, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(13)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 14)

                    HStack(spacing: 10) {
                        actionButton("Copy line") {
                            UIPasteboard.general.string = entry.message
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
                    ShareLink(item: "\(Format.time(entry.timeMs)) \(entry.level.letter) \(entry.tag): \(entry.message)") {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
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
