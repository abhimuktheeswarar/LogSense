import CoreGraphics

/// The iPhone Duo decisions that are plain geometry, kept free of SwiftUI so they are testable.
/// Layout is chosen from the fold region the system reports, never from the device model.
internal enum DuoGeometry {

    /// Seated: half-open on a desk, the fold running horizontally. The design's open question
    /// ("no public pose API") is answered by iOS 27.1's hinge status; the fold's axis tells
    /// seated (horizontal crease) from a book held sideways (vertical crease).
    static func isSeated(hingePartiallyOpen: Bool, division: CGRect?) -> Bool {
        guard hingePartiallyOpen, let division else { return false }
        return division.width > division.height
    }

    /// "Beside the host": LogSense takes the region after a vertical fold and leaves the host the
    /// region before it. Nil when there is no vertical fold to dock against.
    static func besideFrame(bounds: CGRect, division: CGRect?) -> CGRect? {
        guard let division, division.height > division.width,
              division.maxX < bounds.maxX, division.minX > bounds.minX
        else { return nil }
        return CGRect(x: division.maxX, y: bounds.minY, width: bounds.maxX - division.maxX, height: bounds.height)
    }
}
