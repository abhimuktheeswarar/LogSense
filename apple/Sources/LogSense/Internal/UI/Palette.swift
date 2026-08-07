#if os(iOS)
import SwiftUI

/// Severity colors from the design — the same hues iOS uses elsewhere, so they need no legend.
/// Notice takes the orange slot: it sits between info and error, and orange reads as "attention".
internal extension LogLevel {

    func color(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkColor : lightColor
    }

    private var darkColor: Color {
        switch self {
        case .debug: return Color(hex: 0x64D2FF)
        case .info: return Color(hex: 0x30D158)
        case .notice: return Color(hex: 0xFF9F0A)
        case .error: return Color(hex: 0xFF453A)
        case .fault: return Color(hex: 0xBF5AF2)
        }
    }

    private var lightColor: Color {
        switch self {
        case .debug: return Color(hex: 0x0071A4)
        case .info: return Color(hex: 0x248A3D)
        case .notice: return Color(hex: 0xB25000)
        case .error: return Color(hex: 0xD70015)
        case .fault: return Color(hex: 0x8944AB)
        }
    }

    /// Chip fill: the level color at a low opacity, per the design (14–18% dark, 10–12% light).
    func chipFill(_ scheme: ColorScheme) -> Color {
        color(scheme).opacity(scheme == .dark ? 0.16 : 0.11)
    }
}

internal extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
#endif
