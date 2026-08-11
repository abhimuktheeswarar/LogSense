import SwiftUI
import OSLog
import UIKit
import LogSense

/// A screen that leaks by design: a global strongly retains it past its dismissal, so
/// LogSense's leaked-screen signal fires ~3s after it goes away.
private var leakedRetainer: [UIViewController] = []

final class LeakyViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let label = UILabel()
        label.text = "This screen leaks on purpose.\nIt dismisses itself in a second."
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        leakedRetainer.append(self) // the "forgotten" strong reference
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.dismiss(animated: true)
        }
    }
}

/// Buttons that exercise every capture path, one per claim the SDK makes.
struct ContentView: View {

    private let logger = Logger(subsystem: "com.msabhi.logsense.demo", category: "Demo")
    private let analytics = Logger(subsystem: "com.msabhi.logsense.demo", category: "Analytics")
    private let telemetry = Logger(subsystem: "com.msabhi.logsense.demo", category: "Telemetry")
    private let metrics = Logger(subsystem: "com.msabhi.logsense.demo", category: "Metrics")
    @State private var burstTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
                Section("LogSense") {
                    Button("Open LogSense") { LogSense.present() }
                }

                Section("Logger (unified log)") {
                    Button("Debug") { logger.debug("Debug line: cache warmed in 12ms") }
                    Button("Info") { logger.info("Info line: session refreshed") }
                    Button("Notice") { logger.notice("Notice line: fallback endpoint in use") }
                    Button("Error") { logger.error("Error line: request failed with 502 (retrying)") }
                    Button("Fault") { logger.fault("Fault line: state invariant broken") }
                }

                Section("Other capture paths") {
                    Button("NSLog") { NSLog("NSLog line: legacy pathway still speaks") }
                    Button("print()") { print("print line: stdout, invisible to the unified log") }
                    Button("Public vs private interpolation") {
                        let secret = "token-123"
                        logger.info("public value: \(secret, privacy: .public)")
                        logger.info("private value: \(secret)")
                    }
                    Button(burstTask == nil ? "Burst: 300 lines" : "Stop burst") {
                        if let task = burstTask {
                            task.cancel()
                            burstTask = nil
                        } else {
                            burstTask = Task {
                                for i in 1...300 where !Task.isCancelled {
                                    logger.debug("burst line \(i, privacy: .public) of 300")
                                    if i % 25 == 0 { try? await Task.sleep(nanoseconds: 40_000_000) }
                                }
                                burstTask = nil
                            }
                        }
                    }
                }

                Section("Analytics-shaped lines") {
                    Button("Bundle shape") {
                        analytics.info("add_to_cart Bundle[{item_id=42, qty=2}]")
                    }
                    Button("JSON shape") {
                        analytics.info(#"purchase {"sku":"pro","price":9.99}"#)
                    }
                    Button("Arrow + Swift dict shape") {
                        analytics.info(#"logEvent = plan_upgraded -> ["plan": "pro", "period": "annual"]"#)
                    }
                    Button("Telemetry tag (regex-matched)") {
                        telemetry.info("logEvent = checkout_started -> {cart=3, total=1499}")
                        telemetry.info("this line does not match the regex and is skipped")
                    }
                    Button("Metrics tag") {
                        metrics.info("screen_render screen=Home, ms=182")
                    }
                }

                Section("Trouble") {
                    Button("Leak a screen (retain cycle)") { presentLeakyScreen() }
                    Button("Custom signal line") { logger.error("Demo trouble: payment declined") }
                    Button("Unsatisfiable constraints line") {
                        logger.error("Unable to simultaneously satisfy constraints.")
                    }
                    Button("Crash: NSException") {
                        NSException(name: .invalidArgumentException,
                                    reason: "LogSense demo crash",
                                    userInfo: nil).raise()
                    }
                    Button("Crash: fatalError") {
                        fatalError("LogSense demo crash (signal path)")
                    }
                }
            }
            .navigationTitle("LogSense Demo")
        }
        .onAppear(perform: emitStartupChatter)
    }

    private func presentLeakyScreen() {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        scene?.keyWindow?.rootViewController?.present(LeakyViewController(), animated: true)
    }

    /// A few lines on launch so the stream is never empty on first open; auto-opens LogSense when
    /// launched with LOGSENSE_AUTO_OPEN=1 (used by scripted simulator runs).
    private func emitStartupChatter() {
        logger.info("Demo launched")
        logger.debug("warming caches")
        logger.notice("using fallback endpoint")
        NSLog("NSLog line at startup")
        print("print line at startup")
        // Events across every configured tag, so the Events screen has pills to scope by.
        analytics.info("screen_view screen=Home, source=launch")
        analytics.info("add_to_cart Bundle[{item_id=42, qty=2}]")
        analytics.info(#"purchase {"sku":"pro","price":9.99}"#)
        telemetry.info("logEvent = checkout_started -> {cart=3, total=1499}")
        telemetry.info("logEvent = app_ready -> {cold_start_ms=412}")
        metrics.info("screen_render screen=Home, ms=182")
        metrics.info("frame_drop screen=Home, dropped=4")
        // Late enough to be the newest lines once LogSense is open — keeps scripted screenshots honest.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.5) {
            logger.error("Demo trouble: payment declined")
            logger.error("Unable to simultaneously satisfy constraints.")
        }
        if ProcessInfo.processInfo.environment["LOGSENSE_SEED"] == "1" { emitShowcase() }
        if ProcessInfo.processInfo.environment["LOGSENSE_AUTO_OPEN"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { LogSense.present() }
        }
        _ = leakedRetainer // silence unused warnings; see presentLeakyScreen
        if ProcessInfo.processInfo.environment["LOGSENSE_CRASH_AFTER"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                NSException(name: .invalidArgumentException,
                            reason: "LogSense scripted demo crash",
                            userInfo: nil).raise()
            }
        }
    }

    /// LOGSENSE_SEED=1: a realistic session's worth of traffic — several subsystem tags so the
    /// per-tag colors show, every level, analytics across all configured tags with rich params,
    /// and a couple of signal lines. Exists so screenshots look like a real app, not filler.
    private func emitShowcase() {
        let network = Logger(subsystem: "com.msabhi.logsense.demo", category: "Network")
        let auth = Logger(subsystem: "com.msabhi.logsense.demo", category: "Auth")
        let cache = Logger(subsystem: "com.msabhi.logsense.demo", category: "Cache")
        let router = Logger(subsystem: "com.msabhi.logsense.demo", category: "Router")
        let sync = Logger(subsystem: "com.msabhi.logsense.demo", category: "Sync")

        auth.info("session restored for user_284 (expires in 41m)")
        cache.debug("hit: catalog/home.json (age 38s, 214KB)")
        network.info("GET /v2/catalog/home -> 200 in 142ms (2.1KB gzip)")
        router.notice("deep link: app://offers/summer -> OffersScreen")
        network.debug("prefetch: 6 product images queued")
        sync.info("delta sync: 18 records in 96ms")
        network.error("GET /v2/recommendations -> 503 in 2841ms (retry 1/3, backoff 800ms)")
        cache.notice("evicted 3 entries (memory pressure, freed 1.2MB)")
        auth.debug("access token refresh scheduled at 14:32")
        network.info("GET /v2/recommendations -> 200 in 204ms (retry succeeded)")
        router.info("push: CheckoutScreen (cart: 3 items)")
        sync.notice("conflict on record note_9214 resolved: server wins")

        analytics.info("screen_view screen=Offers, source=deeplink")
        analytics.info("add_to_cart Bundle[{item_id=8143, title=Desk Lamp, qty=1, price=39.99}]")
        analytics.info(#"purchase {"order_id":"ORD-58312","total":127.5,"currency":"USD","items":3,"payment":"card"}"#)
        telemetry.info("logEvent = checkout_started -> {cart=3, total=127.5, coupon=WELCOME10}")
        telemetry.info("logEvent = search_performed -> {query=desk lamp, results=24, latency_ms=118}")
        metrics.info("screen_render screen=Offers, ms=96")
        metrics.info("cold_start phase=first_frame, ms=412")

        // A second and third wave so the showcase is still the newest content when a scripted
        // screenshot lands, rather than whatever system chatter followed launch.
        for (delay, wave) in [(6.0, 1), (12.0, 2)] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                network.info("GET /v2/cart -> 200 in 88ms (wave \(wave))")
                auth.info("token refreshed (rotated, expires in 60m)")
                cache.debug("hit: profile/user_284.json (age 4s)")
                network.fault("TLS handshake failed for cdn-fallback (self-signed leaf)")
                logger.error("Demo trouble: payment declined")
                router.notice("restored navigation stack: 3 screens")
                analytics.info("screen_view screen=Checkout, source=push")
                metrics.info("frame_drop screen=Checkout, dropped=7")
                sync.info("uploaded 2 pending changes in 143ms")
            }
        }
    }
}
