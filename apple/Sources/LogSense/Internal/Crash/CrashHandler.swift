import Foundation

/// Captures uncaught NSExceptions — the ObjC-layer crashes (unrecognized selector, NSRange, KVC,
/// collection mutation, UIKit assertions). Swift signal-level crashes (fatalError, force-unwraps)
/// are covered by MetricKit on the next launch; LogSense deliberately installs **no signal
/// handlers** — production crash reporters own those, and competing there is how their reporting
/// silently breaks.
///
/// Always chains: the previously installed handler (the host's crash reporter) runs afterwards no
/// matter what, so LogSense can never be the reason a real crash goes unreported.
internal enum CrashHandler {

    struct Context {
        let pendingDir: URL
        let buffer: LogBuffer
        let sessionId: String
        let contextLines: Int
        let deviceInfo: String
    }

    // The handler must be a context-free C function pointer, so its inputs live in statics.
    private static var context: Context?
    private static var previous: (@convention(c) (NSException) -> Void)?

    static func install(_ ctx: Context) {
        guard context == nil else { return }
        context = ctx
        previous = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler(handler)
    }

    private static let handler: @convention(c) (NSException) -> Void = { exception in
        // Whatever happens in our capture, the previous handler always runs.
        defer { previous?(exception) }
        guard let ctx = context else { return }
        let record = CrashRecord(
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            sessionId: ctx.sessionId,
            type: "EXCEPTION",
            threadName: Thread.isMainThread ? "main" : (Thread.current.name?.isEmpty == false ? Thread.current.name! : "background"),
            exceptionClass: exception.name.rawValue,
            message: exception.reason,
            stacktrace: exception.callStackSymbols.joined(separator: "\n"),
            deviceInfo: ctx.deviceInfo,
            logContext: ctx.buffer.currentSnapshot()
                .suffix(max(0, ctx.contextLines))
                .map { "\(Format.time($0.timeMs)) \($0.level.letter) \($0.tag): \($0.message)" }
                .joined(separator: "\n")
        )
        CrashFileStore.write(record, into: ctx.pendingDir)
    }
}
