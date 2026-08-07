import Foundation

/// Device facts attached to a crash report, one `key: value` per line — the UI splits lines into
/// the grouped list the design shows.
internal enum DeviceInfo {

    static func summary(sessionStartedAtMs: Int64) -> String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        let bundleId = Bundle.main.bundleIdentifier ?? "?"
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let ramGb = Double(ProcessInfo.processInfo.physicalMemory) / Double(1 << 30)
        return [
            "Device: \(modelIdentifier())",
            "OS: \(osName()) \(os.majorVersion).\(os.minorVersion).\(os.patchVersion)",
            "App: \(bundleId) \(version) (\(build))",
            "RAM: \(String(format: "%.1f", ramGb)) GB",
            "Session: \(Format.time(sessionStartedAtMs))",
        ].joined(separator: "\n")
    }

    private static func modelIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        return withUnsafeBytes(of: &systemInfo.machine) { bytes in
            String(decoding: bytes.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }

    private static func osName() -> String {
        #if os(iOS)
        return "iOS"
        #else
        return "macOS"
        #endif
    }
}
