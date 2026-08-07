import Foundation
#if os(iOS)
import UIKit
#endif

/// Deallocation-expectation tracking — the practical core of leak detection. An object that
/// *should* be going away (a dismissed screen, anything handed to `watch`) is held weakly and
/// checked after a grace period; still being alive then means something kept a strong reference
/// past its lifetime. This is the technique, not the whole memory graph: it names the leaked
/// object, and Instruments names the cycle.
internal enum LeakWatch {

    /// Reports a leak's description. Set once by the core; the test injects its own.
    static var onLeak: ((String) -> Void)?

    private static var watchKey: UInt8 = 0

    /// Expects `object` to deallocate within `timeout`; reports through `onLeak` if it doesn't.
    /// Watching the same instance twice is a no-op — one verdict per object.
    static func expectDealloc(of object: AnyObject, name: String, timeout: TimeInterval) {
        guard objc_getAssociatedObject(object, &watchKey) == nil else { return }
        objc_setAssociatedObject(object, &watchKey, true, .OBJC_ASSOCIATION_RETAIN)
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak object] in
            guard let object else { return } // deallocated on time — the healthy outcome
            onLeak?("\(name) is still in memory \(Int(timeout))s after it should have deallocated — look for a retain cycle or a lingering strong reference")
        }
    }

    #if os(iOS)
    /// Automatic screen coverage: swizzles `viewDidDisappear` — the one place LogSense swizzles,
    /// and only when `detectLeakedScreens` is on. It observes (calls through, changes nothing),
    /// so there is no host behavior to collide with. A screen leaving for good (dismissed or
    /// popped) gets a deallocation expectation.
    private static var screenWatchInstalled = false

    @MainActor
    static func installScreenWatch() {
        guard !screenWatchInstalled else { return }
        screenWatchInstalled = true
        guard
            let original = class_getInstanceMethod(
                UIViewController.self, #selector(UIViewController.viewDidDisappear(_:))
            ),
            let hook = class_getInstanceMethod(
                UIViewController.self, #selector(UIViewController.logsense_viewDidDisappear(_:))
            )
        else { return }
        method_exchangeImplementations(original, hook)
    }
    #endif
}

#if os(iOS)
extension UIViewController {
    @objc fileprivate func logsense_viewDidDisappear(_ animated: Bool) {
        logsense_viewDidDisappear(animated) // the original implementation, post-exchange
        // Leaving for good — not merely covered by a push or a presented sheet.
        if isBeingDismissed || isMovingFromParent {
            LeakWatch.expectDealloc(
                of: self,
                name: String(describing: type(of: self)),
                timeout: 3
            )
        }
    }
}
#endif
