#if os(iOS)
import SwiftUI

/// What the iPhone Duo's shape means for LogSense's layout, read once at the root and handed down.
/// Everything here comes from iOS 27.1 APIs; earlier systems (and every non-folding device) get
/// the default value, which is exactly today's layout.
internal struct DuoPosture: Equatable {
    /// The fold region in the root's coordinate space, when the system reports one.
    var division: CGRect?
    /// The same region in window coordinates — what docking the window beside the host needs.
    var divisionInWindow: CGRect?
    /// A hinge exists and reports closed — the outer display.
    var hingeClosed = false
    /// Half open with a horizontal fold: stream up, controls down.
    var seated = false
    /// The system moved toolbars and tab bars to a vertical edge (the right-hand rail).
    var verticalRail = false
    /// The root's size, to tell "beside the host" when the window is narrower than the display.
    var size: CGSize = .zero

    /// Open: a vertical fold splits the display into a pane on each side.
    var isOpen: Bool {
        guard let division, !seated else { return false }
        return division.height > division.width
    }
}

private struct DuoPostureKey: EnvironmentKey {
    static let defaultValue = DuoPosture()
}

extension EnvironmentValues {
    var duoPosture: DuoPosture {
        get { self[DuoPostureKey.self] }
        set { self[DuoPostureKey.self] = newValue }
    }
}

extension View {
    /// Reads the fold, hinge and rail and publishes them as `duoPosture` to everything inside.
    @ViewBuilder
    func readsDuoPosture() -> some View {
        if #available(iOS 27.1, *) {
            modifier(DuoPostureReader())
        } else {
            self
        }
    }
}

@available(iOS 27.1, *)
private struct DuoPostureReader: ViewModifier {
    @Environment(\.toolbarVerticalEdge) private var verticalEdge
    @State private var hingeClosed = false
    @State private var hingePartiallyOpen = false

    func body(content: Content) -> some View {
        GeometryReader { proxy in
            // Lying flat, the fold is reported inactive (nothing is hidden by it), yet the panes
            // still belong on either side of the crease — so inactive regions count too.
            let division = proxy.reservedRegions(kind: .division, options: .includeInactive).first?.frame
            let origin = proxy.frame(in: .global).origin
            content
                .environment(\.duoPosture, DuoPosture(
                    division: division,
                    divisionInWindow: division?.offsetBy(dx: origin.x, dy: origin.y),
                    hingeClosed: hingeClosed,
                    seated: DuoGeometry.isSeated(hingePartiallyOpen: hingePartiallyOpen, division: division),
                    verticalRail: verticalEdge != nil,
                    size: proxy.size
                ))
        }
        .onHingeChange { _, context in
            hingeClosed = context.hinge?.status == .closed
            hingePartiallyOpen = context.hinge?.status == .partiallyOpen
        }
    }
}
#endif

#if os(iOS)
/// Two panes split on the fold itself: the first before the crease, the second after it, the fold
/// region kept empty (nothing tappable on the crease) with a hairline down its middle. A vertical
/// fold sets them side by side; a horizontal one (seated) stacks them. The boundary comes from
/// the region the system reports, not from half the width — the side rail makes halves unequal.
internal struct FoldSplit<First: View, Second: View>: View {
    let first: First
    let second: Second
    @Environment(\.duoPosture) private var duo

    init(@ViewBuilder first: () -> First, @ViewBuilder second: () -> Second) {
        self.first = first()
        self.second = second()
    }

    var body: some View {
        GeometryReader { proxy in
            let origin = proxy.frame(in: .global).origin
            let fold = (duo.divisionInWindow ?? .zero).offsetBy(dx: -origin.x, dy: -origin.y)
            if fold.height > fold.width {
                let lead = min(max(fold.minX, 0), proxy.size.width)
                let gap = min(fold.width, proxy.size.width - lead)
                HStack(spacing: 0) {
                    // The side rail's inset belongs to the pane that meets it, not the one
                    // that ends at the crease.
                    first.ignoresSafeArea(.container, edges: .trailing).frame(width: lead)
                    crease(vertical: true).frame(width: gap)
                    second.frame(maxWidth: .infinity)
                }
            } else {
                let top = min(max(fold.minY, 0), proxy.size.height)
                let gap = min(fold.height, proxy.size.height - top)
                VStack(spacing: 0) {
                    first.frame(height: top).ignoresSafeArea(.container, edges: .bottom)
                    crease(vertical: false).frame(height: gap)
                    second.frame(maxHeight: .infinity).ignoresSafeArea(.container, edges: .top)
                }
            }
        }
    }

    private func crease(vertical: Bool) -> some View {
        ZStack {
            Color(.systemBackground)
            if vertical {
                Rectangle().fill(Color(.separator)).frame(width: 0.5)
            } else {
                Rectangle().fill(Color(.separator)).frame(height: 0.5)
            }
        }
        .accessibilityHidden(true)
    }
}
#endif
