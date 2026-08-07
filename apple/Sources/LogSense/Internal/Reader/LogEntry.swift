import Foundation

/// The five unified-logging levels. No fake V/W — iOS has exactly these.
internal enum LogLevel: Int, CaseIterable, Comparable {
    case debug, info, notice, error, fault

    var letter: Character {
        switch self {
        case .debug: return "D"
        case .info: return "I"
        case .notice: return "N"
        case .error: return "E"
        case .fault: return "F"
        }
    }

    var name: String {
        switch self {
        case .debug: return "debug"
        case .info: return "info"
        case .notice: return "notice"
        case .error: return "error"
        case .fault: return "fault"
        }
    }

    static func < (lhs: LogLevel, rhs: LogLevel) -> Bool { lhs.rawValue < rhs.rawValue }

    static func fromLetter(_ letter: Character) -> LogLevel? {
        allCases.first { $0.letter == Character(letter.uppercased()) }
    }

    /// Accepts a letter ("E") or a full name ("error"/"ERROR"), case-insensitive.
    static func fromName(_ name: String) -> LogLevel? {
        let t = name.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return nil }
        if t.count == 1 { return fromLetter(t.first!) }
        return allCases.first { $0.name.caseInsensitiveCompare(t) == .orderedSame }
    }
}

internal struct LogEntry: Identifiable, Equatable {
    let id: Int64
    let timeMs: Int64
    let pid: Int32
    let tid: UInt64
    let level: LogLevel
    /// Unified-log subsystem; empty for NSLog / bare os_log / stdout lines.
    let subsystem: String
    /// Unified-log category; falls back to the sender binary name when empty, "print" for stdout.
    let tag: String
    let message: String
}
