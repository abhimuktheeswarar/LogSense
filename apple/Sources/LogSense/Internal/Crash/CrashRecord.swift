import Foundation

/// Bucket id for events/crashes whose session is unknown (predates session tracking or
/// couldn't be attributed).
internal let earlierSessionId = "earlier"

/// One stored crash. `type` is "EXCEPTION" (NSException caught in-process), "CRASH"
/// (signal-level crash from MetricKit) or "HANG" (MXHangDiagnostic).
internal struct CrashRecord: Codable, Equatable {
    var timestamp: Int64
    var sessionId: String = earlierSessionId
    var type: String
    var threadName: String?
    var exceptionClass: String?
    var message: String?
    var stacktrace: String
    var deviceInfo: String
    var logContext: String
}
