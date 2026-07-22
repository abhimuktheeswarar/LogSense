# LogSense

On-device logcat, analytics-event and crash viewer for Android debug builds — like Chucker, but for logs.

Debug builds usually don't report to Crashlytics, so a crash seen away from the laptop is lost, and verifying
analytics events means being tethered to Android Studio's logcat. LogSense streams your app's **own** logcat
right on the device: a live color-coded log viewer, analytics events lifted out of the stream and rendered
structured, and crash reports that survive process death — all reachable from a notification or a separate
launcher icon.

## Features

- **Live log viewer** — level color coding, min-level / tag / text filtering, follow-tail with jump-to-latest,
  share/export as a text file. Reads only the host app's own logs (`logcat --pid`), so **no permissions,
  no root, no adb** needed.
- **Analytics events** — configure which log tags carry analytics; LogSense parses matching lines into
  structured `name + params` events (built-in support for `name {json}`, `name Bundle[{k=v}]` and
  `name k=v, k2=v2` formats, or plug in your own extractor). Events persist across restarts.
- **Crash capture** — an uncaught-exception handler writes the stacktrace, device info and the last ~200
  log lines to disk *before* the process dies, then surfaces a notification on next launch. ANRs and native
  crashes are picked up via `ApplicationExitInfo` (API 30+).
- **UI** — Jetpack Compose, Material 3, neutral developer theme, light/dark with in-app override,
  portrait/landscape/two-pane on large screens.
- **Zero release footprint** — a `logsense-no-op` twin artifact means release builds ship no tool code.

## Setup

```kotlin
// build.gradle.kts
dependencies {
    debugImplementation(project(":logsense"))
    releaseImplementation(project(":logsense-no-op"))
}
```

```kotlin
// Application class
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogSense.init(this, LogSenseConfig(analyticsTags = setOf("Analytics")))
    }
}
```

That's it. Open LogSense from the ongoing notification, the LogSense launcher icon, or programmatically via
`startActivity(LogSense.getLaunchIntent(context))`.

### Configuration

Everything is optional; defaults shown:

```kotlin
LogSense.init(
    this,
    LogSenseConfig(
        analyticsTags = emptySet(),      // tags whose lines are analytics events
        analyticsExtractor = null,       // custom (tag, message) -> AnalyticsEvent?; null = built-in parser
        maxBufferedLines = 15_000,       // in-memory log ring buffer size
        crashContextLines = 200,         // log lines attached to each crash report
        retentionDays = 7,               // stored events/crashes older than this are trimmed
        maxStoredEvents = 1_000,
        maxStoredCrashes = 50,
        showNotification = true,         // ongoing "recording" notification
        theme = ThemeMode.SYSTEM,        // or force LIGHT / DARK
        accentColor = null,              // ARGB int used as the Material primary color
    ),
)
```

### Notes

- **Notifications**: LogSense declares `POST_NOTIFICATIONS` but never requests it — request it from your app
  (see the sample's `MainActivity`). Without it the UI is still reachable via the launcher icon.
- **Launcher icon**: remove it if unwanted:
  ```xml
  <activity-alias android:name="com.msabhi.logsense.Launcher" tools:node="remove" />
  ```
- Live logs live in memory only and are cleared on process death; analytics events and crashes are persisted
  (Room) and clearable from the UI.
- Log lines are capped by logcat at ~4 KB — very large analytics payloads may arrive truncated.

## Sample app

The `app` module demos everything: log generators for each level, the three analytics formats, a JVM crash
button and an ANR button.

## Modules

| Module | Contents |
|---|---|
| `logsense` | The library (Compose UI, logcat reader, Room storage, crash handler) |
| `logsense-no-op` | Same public API as empty stubs, zero dependencies |
| `app` | Sample/demo app |
