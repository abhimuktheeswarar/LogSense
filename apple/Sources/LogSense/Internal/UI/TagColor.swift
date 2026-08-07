import Foundation
#if os(iOS)
import SwiftUI
#endif

/// Per-tag stable colors, ported exactly from Android: the same tag must get the same color on
/// both platforms, so the hash is **Java's `String.hashCode`** (31-multiplier over UTF-16 units,
/// 32-bit wraparound) — Swift's own `hashValue` is seeded per-process and would repaint every run.
internal enum TagColor {

    static func javaHash(_ string: String) -> Int32 {
        var hash: Int32 = 0
        for unit in string.utf16 {
            hash = hash &* 31 &+ Int32(unit)
        }
        return hash
    }

    static func paletteIndex(for tag: String, paletteSize: Int = 8) -> Int {
        Int(javaHash(tag) & 0x7FFF_FFFF) % paletteSize
    }

    #if os(iOS)
    private static let dark: [UInt32] = [
        0x7FD1C1, 0xB9A2F0, 0xF0B27A, 0x8FC7F5, 0xE79AC4, 0xA9D18E, 0xF2C46B, 0xCBA6A6,
    ]
    private static let light: [UInt32] = [
        0x00796B, 0x5E35B1, 0xB2560D, 0x1565C0, 0xAD1457, 0x33691E, 0x8D6E00, 0x8D4E4E,
    ]

    static func color(for tag: String, scheme: ColorScheme) -> Color {
        let palette = scheme == .dark ? dark : light
        return Color(hex: palette[paletteIndex(for: tag, paletteSize: palette.count)])
    }
    #endif
}
