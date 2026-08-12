# LogSense

An in-app log, analytics-event and crash viewer for **debug builds** — Android and iOS, one repo,
one version line. Debug builds usually don't report to your crash service, and verifying analytics
events means being tethered to a desktop log viewer. LogSense puts the stream on the device itself:
a live color-coded log viewer, analytics events lifted out of the stream and rendered structured,
crash reports that survive process death, and a signal catalog that flags known trouble patterns —
with a hard rule that the tool never noticeably burdens the host app.

| Platform | Docs | Install |
|---|---|---|
| **Android** (Compose, minSdk per library manifest) | [android/README.md](android/README.md) | `debugImplementation("com.msabhi:logsense:0.6.3")` + `releaseImplementation("com.msabhi:logsense-no-op:0.6.3")` |
| **iOS** (SwiftUI, iOS 16+) | [apple/README.md](apple/README.md) | Swift Package Manager: `.package(url: "https://github.com/abhimuktheeswarar/LogSense.git", from: "0.6.3")` |

## Android

<p align="center">
  <img src="docs/screenshots/logs.png" width="18%" alt="Live logs" />
  <img src="docs/screenshots/events.png" width="18%" alt="Analytics events" />
  <img src="docs/screenshots/event_detail.png" width="18%" alt="Event detail" />
  <img src="docs/screenshots/crashes.png" width="18%" alt="Crashes" />
  <img src="docs/screenshots/crash_detail.png" width="18%" alt="Crash detail" />
</p>

Live logcat with per-tab filters, analytics events with per-SDK regex extraction, crash capture +
ANR/native pickup via `ApplicationExitInfo`, 43-signal catalog, Material You. Release builds ship
the `logsense-no-op` twin artifact — zero tool code in production.
→ [Full Android documentation](android/README.md)

## iOS

<p align="center">
  <img src="docs/screenshots/apple/logs.png" width="18%" alt="Live logs" />
  <img src="docs/screenshots/apple/events.png" width="18%" alt="Analytics events" />
  <img src="docs/screenshots/apple/event_detail.png" width="18%" alt="Event detail" />
  <img src="docs/screenshots/apple/crash_detail.png" width="18%" alt="Crash report" />
  <img src="docs/screenshots/apple/signals.png" width="18%" alt="Signals" />
</p>

The same product in Apple's vocabulary: unified-log capture (`Logger`/`os_log`/`NSLog`) plus
`print()` via stdout interception, analytics events with the same named-group regex model, crash
capture that chains to your existing reporter + MetricKit, signals, a Live Activity in the Dynamic
Island, and a home-screen quick action. SPM only — no CocoaPods.
→ [Full iOS documentation](apple/README.md)

## Repository layout

```
android/   Gradle project: logsense, logsense-no-op, sample app
apple/     Swift package sources, tests, demo app (manifest lives at the repo root — SPM requires it)
docs/      Shared design brief and screenshots
```

Both platforms release together under one semver tag (`vX.Y.Z`): the tag is what SPM resolves,
and the same version is published to Maven Central. See [PUBLISHING.md](PUBLISHING.md).

## License

[Apache 2.0](LICENSE)
