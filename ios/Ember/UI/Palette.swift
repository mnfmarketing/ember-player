import SwiftUI
import UIKit
import ObjectiveC

/// One theme's colors in light or dark mode: the screen, cards and the accent.
struct Palette {
    var background: UIColor
    var surface: UIColor
    var surfaceHigh: UIColor
    var accent: UIColor
}

struct ThemeDef: Identifiable {
    let id: String
    let name: String
    /// Nil: the system's own colors (the Ember theme).
    let dark: Palette?
    let light: Palette?
    let darkAccent: Int
    let lightAccent: Int
}

/// The themes (the same seven as Ember on the Fold). Each has a light and a dark version, so they
/// follow the phone's dark mode, including its night schedule.
enum Themes {
    static func hsl(_ h: Double, _ s: Double, _ l: Double) -> UIColor {
        let s = min(max(s, 0), 1), l = min(max(l, 0), 1)
        let c = (1 - abs(2 * l - 1)) * s
        let hp = (h.truncatingRemainder(dividingBy: 360)) / 60
        let x = c * (1 - abs(hp.truncatingRemainder(dividingBy: 2) - 1))
        var (r, g, b) = (0.0, 0.0, 0.0)
        switch hp {
        case 0..<1: (r, g, b) = (c, x, 0)
        case 1..<2: (r, g, b) = (x, c, 0)
        case 2..<3: (r, g, b) = (0, c, x)
        case 3..<4: (r, g, b) = (0, x, c)
        case 4..<5: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        let m = l - c / 2
        return UIColor(red: r + m, green: g + m, blue: b + m, alpha: 1)
    }

    static func ui(_ hex: Int) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    private static func dark(_ hue: Double, _ sat: Double, _ accent: Int) -> Palette {
        Palette(background: hsl(hue, sat, 0.065), surface: hsl(hue, sat, 0.115), surfaceHigh: hsl(hue, sat, 0.16), accent: ui(accent))
    }

    private static func light(_ hue: Double, _ sat: Double, _ accent: Int) -> Palette {
        Palette(background: hsl(hue, sat, 0.945), surface: hsl(hue, sat, 0.985), surfaceHigh: hsl(hue, sat * 0.95, 0.9), accent: ui(accent))
    }

    private static func def(_ id: String, _ name: String, _ dh: Double, _ ds: Double, _ da: Int, _ lh: Double, _ ls: Double, _ la: Int) -> ThemeDef {
        ThemeDef(id: id, name: name, dark: dark(dh, ds, da), light: light(lh, ls, la), darkAccent: da, lightAccent: la)
    }

    static let all: [ThemeDef] = [
        ThemeDef(id: "ember", name: "Ember", dark: nil, light: nil, darkAccent: 0xFF8A3D, lightAccent: 0xE87A2E),
        def("paper", "Paper", 32, 0.22, 0xE0B072, 40, 0.62, 0x8C5A26),
        def("midnight", "Midnight", 224, 0.38, 0x86A9FF, 220, 0.62, 0x2D59B5),
        def("slate", "Slate", 210, 0.16, 0x8DB4DA, 210, 0.30, 0x3D6890),
        def("graphite", "Graphite", 0, 0, 0xF0A04B, 0, 0, 0xB0560F),
        def("forest", "Forest", 150, 0.26, 0x7FCB9A, 140, 0.36, 0x2E7D4F),
        def("ocean", "Ocean", 188, 0.36, 0x5CCBC9, 186, 0.50, 0x137C80),
    ]

    static func byID(_ id: String) -> ThemeDef { all.first { $0.id == id } ?? all[0] }

    /// Fixed accent choices, and the custom one from a hue.
    static let swatches: [(String, Int)] = [
        ("Orange", 0xF2A65A), ("Red", 0xE5574C), ("Amber", 0xF5B841), ("Gold", 0xD4B04A),
        ("Green", 0x6CC48A), ("Teal", 0x3FBDB3), ("Blue", 0x5B9BF0), ("Steel", 0x93A6BB),
    ]

    static func hex(_ c: UIColor) -> Int {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Int(r * 255) << 16) | (Int(g * 255) << 8) | Int(b * 255)
    }

    static func fromHue(_ hue: Double) -> Int { hex(hsl(hue, 0.72, 0.62)) }

    /// The palette in force (nil: the system's colors).
    static func palette(theme: String, trueBlack: Bool, dark: Bool) -> Palette? {
        let t = byID(theme)
        var p = dark ? t.dark : t.light
        if dark, trueBlack {
            var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            (p?.surface ?? .black).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
            let hue = Double(h) * 360, sat = p == nil ? 0 : Double(s)
            p = Palette(background: .black, surface: hsl(hue, sat, 0.075), surfaceHigh: hsl(hue, sat, 0.12), accent: p?.accent ?? ui(t.darkAccent))
        }
        return p
    }
}

/// A trait that changes when the theme does, so UIKit redraws everything drawn in system colors.
struct EmberThemeTrait: UITraitDefinition {
    static let defaultValue = 0
    static let affectsColorAppearance = true
    static let name = "EmberTheme"
}

/// Themes recolor the system backgrounds (screens, lists, cards) everywhere at once, so every
/// screen follows without each one knowing about themes.
@MainActor
enum ThemeColors {
    nonisolated(unsafe) static var theme = "ember"
    nonisolated(unsafe) static var trueBlack = false
    private static var installed = false
    private static var generation = 0

    private enum Role { case background, surface, surfaceHigh }

    nonisolated private static func color(_ role: Role, _ tc: UITraitCollection) -> UIColor? {
        guard let p = Themes.palette(theme: theme, trueBlack: trueBlack, dark: tc.userInterfaceStyle == .dark) else { return nil }
        switch role {
        case .background: return p.background
        case .surface: return p.surface
        case .surfaceHigh: return p.surfaceHigh
        }
    }

    static func install(theme: String, trueBlack: Bool) {
        self.theme = theme
        self.trueBlack = trueBlack
        guard !installed else { return }
        installed = true
        let roles: [(Selector, Role)] = [
            (#selector(getter: UIColor.systemBackground), .background),
            (#selector(getter: UIColor.secondarySystemBackground), .surface),
            (#selector(getter: UIColor.tertiarySystemBackground), .surfaceHigh),
            (#selector(getter: UIColor.systemGroupedBackground), .background),
            (#selector(getter: UIColor.secondarySystemGroupedBackground), .surface),
            (#selector(getter: UIColor.tertiarySystemGroupedBackground), .surfaceHigh),
        ]
        typealias Getter = @convention(c) (AnyClass, Selector) -> UIColor
        for (sel, role) in roles {
            guard let m = class_getClassMethod(UIColor.self, sel) else { continue }
            let original = unsafeBitCast(method_getImplementation(m), to: Getter.self)(UIColor.self, sel)
            let themed = UIColor { tc in color(role, tc) ?? original.resolvedColor(with: tc) }
            let block: @convention(block) (AnyObject) -> UIColor = { _ in themed }
            method_setImplementation(m, imp_implementationWithBlock(block))
        }
    }

    /// After a change: everything on screen picks up the new colors.
    static func apply(theme: String, trueBlack: Bool) {
        guard theme != self.theme || trueBlack != self.trueBlack else { return }
        self.theme = theme
        self.trueBlack = trueBlack
        generation += 1
        for scene in UIApplication.shared.connectedScenes {
            guard let ws = scene as? UIWindowScene else { continue }
            for w in ws.windows { w.traitOverrides[EmberThemeTrait.self] = generation }
        }
    }
}
