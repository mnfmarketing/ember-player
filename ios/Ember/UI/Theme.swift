import SwiftUI

extension Color {
    init(hex: Int, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

enum Theme {
    static let ember = Color(hex: 0xE87A2E)
    static let emberDark = Color(hex: 0xFF8A3D)

    /// A cover's color made deep enough for white text on top (book pages, the player).
    static func deep(_ hex: Int?) -> Color {
        guard let hex else { return Color(hex: 0x1C3A38) }
        var (h, s, b) = hsb(hex)
        b = min(b, 0.42)
        s = min(max(s, 0.25), 0.75)
        return Color(hue: h, saturation: s, brightness: b)
    }

    /// The lighter tint used for text and small details on a deep cover background.
    static func soft(_ hex: Int?) -> Color {
        guard let hex else { return Color(hex: 0xB9D6D2) }
        var (h, s, _) = hsb(hex)
        s = min(s, 0.35)
        return Color(hue: h, saturation: s, brightness: 0.92)
    }

    static func hsb(_ hex: Int) -> (Double, Double, Double) {
        let ui = UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return (Double(h), Double(s), Double(b))
    }

    /// The accent: the playing book's color (Match the book), or Ember's.
    static func accent(settings: Settings, bookColor: Int?, dark: Bool) -> Color {
        switch settings.accentMode {
        case "fixed": return Color(hex: settings.accentColor)
        case "book":
            if let c = bookColor {
                var (h, s, _) = hsb(c)
                s = max(s, 0.45)
                return Color(hue: h, saturation: s, brightness: dark ? 0.95 : 0.62)
            }
            return dark ? emberDark : ember
        default:
            return dark ? emberDark : ember
        }
    }
}

/// A book or show cover: the image, or the title on the cover's color when there's no picture.
struct CoverView: View {
    var file: String?
    var title: String
    var color: Int?
    var corner: CGFloat = 10

    var body: some View {
        GeometryReader { g in
            ZStack {
                if let img = Covers.image(file) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    Theme.deep(color ?? 0x5A4636)
                    VStack(spacing: g.size.width * 0.06) {
                        Image(systemName: "flame.fill")
                            .font(.system(size: g.size.width * 0.16))
                            .foregroundStyle(Theme.soft(color ?? 0x5A4636))
                        Text(title)
                            .font(.system(size: max(g.size.width * 0.11, 9), weight: .semibold, design: .serif))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.white.opacity(0.92))
                            .lineLimit(4)
                            .minimumScaleFactor(0.6)
                            .padding(.horizontal, g.size.width * 0.1)
                    }
                }
            }
            .frame(width: g.size.width, height: g.size.height)
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// A thin progress line under a cover.
struct ProgressLine: View {
    var value: Double
    var tint: Color

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(.secondary.opacity(0.25))
                Capsule().fill(tint).frame(width: max(g.size.width * value, value > 0 ? 3 : 0))
            }
        }
        .frame(height: 3)
    }
}
