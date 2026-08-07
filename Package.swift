// swift-tools-version: 5.9

// SPM requires the manifest at the repository root; all Apple-platform sources live under apple/.
// macOS is listed only so `swift test` runs the pure-logic tests on a Mac without a simulator.
import PackageDescription

let package = Package(
    name: "LogSense",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "LogSense", targets: ["LogSense"]),
    ],
    targets: [
        .target(name: "LogSense", path: "apple/Sources/LogSense"),
        .testTarget(name: "LogSenseTests", dependencies: ["LogSense"], path: "apple/Tests/LogSenseTests"),
    ]
)
