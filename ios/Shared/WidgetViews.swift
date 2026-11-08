import SwiftUI
import AppIntents
#if canImport(WidgetKit)
import WidgetKit
#endif

private func hexColor(_ hex: Int) -> Color {
    Color(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
}

private func deep(_ hex: Int?) -> Color {
    guard let hex else { return Color(red: 0.11, green: 0.23, blue: 0.22) }
    let ui = UIColor(hexColor(hex))
    var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    ui.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
    return Color(hue: Double(h), saturation: Double(min(max(s, 0.25), 0.75)), brightness: Double(min(b, 0.4)))
}

private func hm(_ ms: Int64) -> String {
    let m = Int(ms / 60_000)
    return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
}

/// The cover, or a flame on the cover's color when there's no picture.
struct WidgetCover: View {
    let snap: WidgetSnapshot
    var image: UIImage?
    var corner: CGFloat = 10

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    deep(snap.color)
                    Image(systemName: "flame.fill").font(.title2).foregroundStyle(.white.opacity(0.8))
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}

/// Now playing on the Home Screen: cover, title, time left, and play/pause with skips.
struct NowPlayingWidgetView: View {
    let snap: WidgetSnapshot
    var image: UIImage?
    var family: String

    var body: some View {
        if !snap.hasBook {
            VStack(spacing: 6) {
                Image(systemName: "flame.fill").font(.title).foregroundStyle(.orange)
                Text("Pick a book in Ember").font(.caption).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.85))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if family == "small" {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    WidgetCover(snap: snap, image: image).frame(width: 56, height: 56)
                    Spacer()
                    Button(intent: TogglePlaybackIntent()) {
                        Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill").font(.title3).frame(width: 40, height: 40)
                    }
                    .buttonStyle(.plain)
                    .background(.white.opacity(0.18), in: Circle())
                }
                Spacer(minLength: 0)
                Text(snap.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text("\(hm(snap.leftMs)) left").font(.caption).foregroundStyle(.white.opacity(0.7))
                progress
            }
            .foregroundStyle(.white)
        } else {
            HStack(spacing: 14) {
                WidgetCover(snap: snap, image: image, corner: 12).frame(width: 110, height: 110)
                VStack(alignment: .leading, spacing: 4) {
                    Text(snap.title).font(.headline).lineLimit(2)
                    Text(snap.chapter.isEmpty ? snap.author : snap.chapter).font(.caption).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                    Text("\(hm(snap.leftMs)) left").font(.caption).foregroundStyle(.white.opacity(0.6))
                    Spacer(minLength: 4)
                    progress
                    HStack(spacing: 0) {
                        Button(intent: SkipBackIntent()) { Image(systemName: "gobackward.\(snap.skipBack)").font(.title3).frame(maxWidth: .infinity, minHeight: 34) }
                        Button(intent: TogglePlaybackIntent()) { Image(systemName: snap.isPlaying ? "pause.fill" : "play.fill").font(.title2).frame(maxWidth: .infinity, minHeight: 34) }
                        Button(intent: SkipForwardIntent()) { Image(systemName: "goforward.\(snap.skipForward)").font(.title3).frame(maxWidth: .infinity, minHeight: 34) }
                    }
                    .buttonStyle(.plain)
                }
            }
            .foregroundStyle(.white)
        }
    }

    private var progress: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.22))
                Capsule().fill(.white).frame(width: g.size.width * min(max(snap.progress, 0), 1))
            }
        }
        .frame(height: 4)
    }

    static func background(_ snap: WidgetSnapshot) -> some View {
        LinearGradient(colors: [deep(snap.color), deep(snap.color).opacity(0.8), .black.opacity(0.9)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Lock Screen: a line with the book and time left, or a ring of today's goal.
struct LockScreenWidgetView: View {
    let snap: WidgetSnapshot
    var family: String

    var body: some View {
        if family == "circular" {
            ZStack {
                Circle().stroke(.white.opacity(0.25), lineWidth: 5)
                Circle().trim(from: 0, to: min(Double(snap.todayMs) / Double(max(snap.goalMs, 1)), 1)).stroke(.white, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90))
                VStack(spacing: 0) {
                    Image(systemName: "flame.fill").font(.caption2)
                    Text("\(snap.todayMs / 60_000)").font(.system(size: 15, weight: .bold))
                }
            }
            .padding(2)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Label(snap.hasBook ? snap.title : "Ember", systemImage: snap.isPlaying ? "waveform" : "flame.fill").font(.headline).lineLimit(1)
                Text(snap.hasBook ? "\(hm(snap.leftMs)) left · \(Int(snap.progress * 100))%" : "Nothing playing").font(.caption).lineLimit(1)
                Text("Today \(snap.todayMs / 60_000) of \(snap.goalMs / 60_000) min · \(snap.streak)-day streak").font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Today's listening against your goal, and your streak.
struct ListeningWidgetView: View {
    let snap: WidgetSnapshot

    var body: some View {
        let frac = min(Double(snap.todayMs) / Double(max(snap.goalMs, 1)), 1)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "flame.fill").foregroundStyle(.orange)
                Text("Today").font(.subheadline.weight(.semibold))
                Spacer()
            }
            ZStack {
                Circle().stroke(.white.opacity(0.15), lineWidth: 10)
                Circle().trim(from: 0, to: frac).stroke(Color.orange, style: StrokeStyle(lineWidth: 10, lineCap: .round)).rotationEffect(.degrees(-90))
                VStack(spacing: 0) {
                    Text("\(snap.todayMs / 60_000)").font(.title2.weight(.bold))
                    Text("of \(snap.goalMs / 60_000) min").font(.caption2).foregroundStyle(.white.opacity(0.7))
                }
            }
            .frame(maxWidth: .infinity)
            Text("\(snap.streak)-day streak").font(.caption).foregroundStyle(.white.opacity(0.75)).frame(maxWidth: .infinity)
        }
        .foregroundStyle(.white)
    }
}
