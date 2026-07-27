# LogSense — UI Design Brief

A design brief for the LogSense Android UI. Hand this to a designer to
produce screens. It describes the **current feature set** so the design matches the real app.

---

## 1. Product

On-device **logcat + analytics + crash viewer** for Android developers (a debug-build tool,
"like Chucker but for logs"). It streams the host app's *own* logcat live, lifts analytics events
out of the stream, and captures crashes that survive process death. Audience: **Android
engineers**, used during development/QA, often for long sessions.

- **Platform:** Android, **Jetpack Compose + Material 3**.
- **Theming:** **Material You** (dynamic color from wallpaper, Android 12+); follows **system
  light/dark** by default with a manual override; **dark-first** mindset (devs live in dark mode).
- **Responsive:** phone (single pane) and large screen / landscape / tablet (**two-pane**
  list + detail at ≥ 840dp width).

## 2. Design principles & constraints

- **Host app leads, LogSense is quiet.** The top bar shows the **host app's name prominently**
  (e.g. "Acme") with **"LogSense" as a small subtitle**. No LogSense brand color — the accent
  comes from Material You so it blends into whatever app it's embedded in.
- **One accent, quiet metadata, message-first.** The log **message is the hero**; timestamp/pid
  are subdued. Spend the single accent on *state* (active tab, selection, focus, pause).
- **Severity palette is separate from the accent.** Six log levels — **V, D, I, W, E, F** — each a
  distinct, restrained, **user-configurable** color (light + dark). Error/fatal must read at a
  glance without fighting the theme.
- **Monospace for log/data content**, clean sans for chrome/labels. Tabular figures for timestamps.
- **Information design over decoration.** It's scanned and operated, not read. Encode state in form
  (pill/stripe/chip), not just text.
- **Accessibility:** log text is selectable/copyable; ≥ 48dp touch targets; sufficient contrast in
  both themes; visible focus.

## 3. Global chrome

- **Top app bar:** `[host app name (prominent) / "LogSense" (small subtitle)]` … `[Settings gear]`.
  A subtle **live/paused** indicator + line count is desirable (e.g. "● LIVE · 6.7k").
- **Primary tabs (always):** **Logs · Events · Crashes · Signals** (4 top-level tabs).
- **Settings** opens as its own screen (back arrow).

## 4. Screens

### 4A. Logs (the main screen — needs the most design love)

A live, dense stream of the host app's logcat.

- **Multiple log tabs (user-created).** A secondary tab strip under the primary tabs: pills like
  `All`, `Network`, plus a `+` to add and `×` to close (min one). Each tab keeps its **own filter +
  view settings**, and tabs **persist across app restarts**.
- **Filter (narrows the stream, Android-Studio-style query):** a filter field supporting a query
  language —
  - `tag:foo`, `-tag:foo` (exclude), `message:foo` / `msg:foo`, `-message:foo`, `level:E`
    (or `level:error`), bare words (match tag OR message), `"quoted phrases"`. Terms are ANDed.
    Plain text alone works as a simple filter.
  - A separate **min-level dropdown** (V/D/I/W/E/F, shown like "V+").
  - A **tag autocomplete** helper that inserts `tag:<name>`.
- **Search / Find (searches *within* the current view — distinct from Filter):** a toggleable find
  bar with **match case (Aa) · whole word (W) · regex (.\*)**, a **match count (n/m)**, **prev/next**
  navigation, and **highlighted matches** in rows. It does *not* remove non-matches (unlike Filter).
- **Play/Pause per tab** (freezes that tab's view while capture continues) **and global pause/resume
  from the notification** (stops capture entirely).
- **View mode:** **Standard** (timestamp+tag header line, then message) vs **Compact** (single line).
- **Soft-wrap toggle:** on = message wraps; off = single line with horizontal scroll.
- **Scroll controls:** **scroll-to-top** and **jump-to-latest** (contextual FABs); auto
  **follow-tail** when at bottom.
- **Restart logcat**, **Share/export as .txt**, **Clear buffer**.
- **Overflow menu (⋮)** currently holds: Find · Filter by tag… · Soft wrap ✓ · Compact view ✓ ·
  Restart logcat · Share · Clear. *(Designer may re-balance what's a visible icon vs. in the menu.)*

**Log row anatomy:** level (color-coded), timestamp `HH:mm:ss.SSS`, tag, message (monospace,
multi-line supported). PID/TID are available if useful. Error/fatal messages are color-tinted.
A nice move: **group consecutive same-tag lines** so the tag isn't repeated on every row.

**States:** empty ("no logs yet"), paused (frozen), filtered-no-results, very long lines
(truncate + horizontal scroll when wrap off), a burst of identical lines.

### 4B. Events (analytics)

Analytics events parsed out of the log stream (from configured tags).

- **Per-tag sub-tabs:** `All` + one tab per analytics tag (e.g. `Telemetry`, `ANALYTICS`).
  Tags can contain spaces and be long.
- **Live keyword filter** (narrows by name / params / tag as events arrive) **+ the same Find bar**
  (match case/word/regex, count, prev/next, highlight).
- **Delete all.** Empty state: "No analytics events yet. Configure analyticsTags and fire an event."
- **Event row:** event **name** (prominent), timestamp + source tag (quiet), **params preview**
  (one line, monospace, ellipsized JSON).
- **Event detail** (pane on wide, full screen on narrow): name, tag, timestamp,
  **pretty-printed params**.

### 4C. Crashes

Crashes captured before process death.

- **Crash row:** a **type badge** (`JVM` / `ANR` / `NATIVE`), exception class name, message,
  timestamp.
- **Crash detail** (pane/full): type, exception class, message, a **triage card** (likely cause,
  the topmost stack frame belonging to the app rather than the framework, and what to check next),
  **full stacktrace**, **device info**, and the **last ~200 log lines** of context. Reachable via a
  **notification deep-link**.
- **Delete all.** Empty state: "No crashes captured. That's a good thing."

### 4D. Signals

Everything worth looking at in this run, newest first — catalog matches from the live stream plus the
crash, ANR or native fault ingested at launch (i.e. what ended the previous run).

- **Category pills:** `All` + one per category present (**Crash · ANR · Native · Memory · Lifecycle ·
  Custom**), each with a count.
- **Signal row:** a category-colored dot, the signal **label**, the timestamp, and a one-line
  monospace preview of the matched line (or the crash's own app stack frame). A **mute (×)** on the
  right switches that signal off, with undo.
- **Tap targets:** a matched line jumps to the **Logs** tab and scrolls to it — clearing that tab's
  filter, with undo, if the filter would hide it. A crash opens its report.
- Signals reported by the platform rather than matched in the log (force-stop, kill by signal,
  low-memory kill, first frame) have **no line to jump to** — the row must not look tappable.
- **Empty state:** "Nothing flagged yet…" — this is the good outcome, so it should read calmly.

**The same signals on the Logs screen (three more surfaces):**

- **Gutter strip** — a signalled row's level stripe thickens and takes the category color.
- **Inline pill** — a small colored chip carrying the signal label, next to the timestamp.
- **Minimap rail** — a thin strip down the right edge of the log list with one dot per signal,
  positioned by where its line sits in the stream. Tapping a dot scrolls to that line.

### 4E. Log line detail (sheet)

Tapping any log row opens a bottom sheet with the **whole** line: level, tag, full timestamp,
pid/tid, the signal pill if it matched one, and the untruncated message — monospace, wrapped,
selectable. When the line holds JSON, a **Pretty / Raw** toggle. Actions: **Copy · Share ·
filter by this tag**. Rows in the list are therefore *not* individually selectable text.

### 4F. Detail navigation (Events / Crashes / Signals)

- **Narrow:** tapping a row opens a full-screen detail with a back arrow.
- **Wide (≥840dp):** **two-pane** — list on the left, detail on the right (selected row highlighted).

### 4G. Settings

- **Theme:** segmented control **System / Light / Dark** (persisted). Caption: colors follow
  wallpaper (Material You) on Android 12+.
- **Log level colors:** per-level (V/D/I/W/E/F) color pickers, **separate for light and dark**, with
  a "default" option and a **Reset**. Each level row shows a live swatch/preview.
- **Signals:** the catalog grouped by category, collapsed by default, each group showing `on/total`.
  Expanding lists every signal with its query and a switch. Muting one stops it matching entirely.

## 5. Notifications

- **Ongoing capture notification:** title = **host app name**, subtext = **"LogSense"**, body =
  "Recording logs — tap to view" (or "Capture paused…"), with a **Pause / Resume** action button.
  Tap opens the UI.
- **Crash notification:** title = exception class, subtext "LogSense", tap deep-links to that crash.

## 6. States to design (checklist)

Empty (each tab) · loading / first-connect · **paused** (per-tab and global) · filtered-no-results ·
long-line truncation · error/fatal rows · two-pane (wide) · light & dark · Material You accent
variations · notification (running & paused).

## 7. Component inventory

Top app bar (2-line title) · primary tab row · secondary tab strip with add/close pills · query
filter field · min-level dropdown · find bar with toggle chips + counter + nav · overflow menu ·
log list rows (grouped) · level badge/pill/stripe · analytics event card + params · crash badge +
stacktrace view · two-pane splitter · segmented control · color-swatch picker · FABs
(scroll top / latest) · empty states.

## 8. Realistic content to design with (use real data, not lorem)

Typical Android system logs:

```
V GraphicsEnvironment  angle_gl_driver_selection_pkgs=[]
D nativeloader  Load libframework-connectivity-tiramisu-jni.so
D CompatChangeReporter  Compat change id reported: 202956589; UID 10215
W ActivityManager  Slow operation: 132ms so far, now startProcess
E OkHttp  <-- HTTP FAILED: java.net.SocketTimeoutException: timeout
```

Example analytics, showing two tag styles + long params:

```
D Telemetry   logEvent = screen_view -> {screen=splash, page=splash}
I ANALYTICS   GA -> screen_view : Bundle[{language=en, screen=splash}]
D Telemetry   logEvent = home_open -> {home_clicks=OfferSectionLoaded, category=Shop}
```

Crash: `RuntimeException: LogSense demo crash` (JVM), plus an ANR.

## 9. Deliverables to ask the designer for

Logs (standard + compact, with filter query and find bar active), Logs empty & paused, Events
(per-tag tabs) + event detail, Crashes + crash detail, Settings, the wide / two-pane layout, and the
capture notification — each in **light and dark**.
