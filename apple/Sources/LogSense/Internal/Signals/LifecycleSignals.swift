#if os(iOS)
import Foundation
import UIKit

/// Records the signals no log line carries — straight from the platform notifications that know:
/// memory warnings, thermal pressure, the scene becoming active, and the first activation with a
/// real cold-start number measured from process start. Plain NotificationCenter observers, no
/// swizzling.
internal final class LifecycleSignals {

    private var observers: [NSObjectProtocol] = []
    private var sawFirstActive = false

    func start(record: @escaping (Signal, Int64, String) -> Void) {
        let nc = NotificationCenter.default

        observers.append(nc.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
        ) { _ in
            record(BuiltInSignals.memoryWarning, Self.nowMs(), "The system asked the app to free memory")
        })

        observers.append(nc.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil
        ) { _ in
            let state = ProcessInfo.processInfo.thermalState
            guard state == .serious || state == .critical else { return }
            record(BuiltInSignals.thermalState, Self.nowMs(),
                   "Thermal state \(state == .critical ? "critical" : "serious") — the system is throttling")
        })

        observers.append(nc.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            let now = Self.nowMs()
            if !self.sawFirstActive {
                self.sawFirstActive = true
                if let startMs = Self.processStartMs() {
                    record(BuiltInSignals.firstFrame, now, "Cold start: \(now - startMs) ms from process start to active")
                } else {
                    record(BuiltInSignals.firstFrame, now, "First activation")
                }
            }
            record(BuiltInSignals.foreground, now, "Scene became active")
        })
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// The kernel's record of when this process started — what a cold-start number should be
    /// measured from, not from whenever our code first ran.
    static func processStartMs() -> Int64? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return nil }
        let tv = info.kp_proc.p_starttime
        return Int64(tv.tv_sec) * 1000 + Int64(tv.tv_usec) / 1000
    }
}
#endif
