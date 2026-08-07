# LogSense for iOS

The same product as the Android library, expressed in Apple's vocabulary: an in-app developer/QA
tool for **debug builds** that streams the app's own logs live, lifts analytics events out of the
stream, captures crashes that survive process death, and flags known trouble patterns. SwiftUI,
iOS 16+, zero dependencies.

## Install

Swift Package Manager, from the repository root (the `Package.swift` at the root points into
`apple/`):

```swift
.package(url: "https://github.com/abhimuktheeswarar/LogSense.git", from: "0.6.0")
```

## Integrate

```swift
#if DEBUG
LogSense.start()
#endif
```

Call it at the **top of** `application(_:didFinishLaunchingWithOptions:)` (or your `App` init),
**before any crash-reporting SDK configures**. Crash reporters chain to whatever exception handler
is already installed — starting LogSense first means both fire; starting it second is fine too
(LogSense chains the same way), but first is the ordering that never surprises anyone.

Open the UI with `LogSense.present()` (its own overlay window above your app), or embed
`LogSenseView` wherever you like — a debug menu, a hidden gesture of your choosing. LogSense
deliberately claims no global gesture: shake, in particular, is often taken.

There is no separate no-op artifact: keep the calls behind `#if DEBUG` and dead-code stripping
removes the library from release builds.

## What capture sees

| Source | Captured | Notes |
|---|---|---|
| `Logger` / `os_log` | ✓ | From your code, third-party SDKs and system frameworks in-process |
| `NSLog` | ✓ | Arrives with the binary name as its tag |
| `print()` | ✓ | Via stdout interception (`captureStandardOutput`, on by default); the Xcode console still works |
| `%{public}` vs private | ⚠︎ | Dynamic interpolations logged without `%{public}` read back as `<private>` when the app runs detached from Xcode — that's unified logging's privacy model, not a LogSense gap |

The buffer is in-memory only and dies with the process, by design. Unified logging truncates very
long messages (~1 KB); log accordingly.

## Crashes

- **NSExceptions** are captured in-process by a chaining uncaught-exception handler: last log lines
  attached as context, written durably (fsync + rename) before the process dies, ingested on the
  next launch. Your existing crash reporter still runs — always.
- **Swift traps, memory faults, watchdog kills and hangs** arrive via MetricKit on the next launch
  (iOS 15+ delivery). Those stacks are unsymbolicated (binary + offset) — the report says so
  rather than pretending otherwise. MetricKit does not deliver on the simulator; use
  Xcode ▸ Debug ▸ Simulate MetricKit Diagnostic, or a device.
- LogSense installs **no signal handlers**, deliberately: production crash reporters own those.

## Configuration

Everything is a defaulted parameter on `LogSenseConfig` — analytics tag patterns (with an optional
per-tag regex exposing `(?<name>)` and `(?<params>)` groups), custom signals in the same query
syntax as the Logs filter (`tag:` `-tag:` `msg:` `sub:` `level:` bare words `"quoted phrases"`),
buffer/retention caps, theme and accent. See the doc comments in `LogSenseConfig.swift`.

## Demo

`apple/Demo/LogSenseDemo.xcodeproj` — one button per capture claim: every level, NSLog, print,
private-vs-public interpolation, burst, analytics payload shapes, signal lines, and both crash
paths. Scripted-run hooks: launch env `LOGSENSE_AUTO_OPEN=1` opens LogSense after launch,
`LOGSENSE_CRASH_AFTER=1` raises a crash a few seconds in.

## Development

Pure logic is tested on macOS — `swift test` from the repository root, no simulator needed.
The package builds for iOS with `xcodebuild -scheme LogSense -destination 'generic/platform=iOS Simulator' build`.
