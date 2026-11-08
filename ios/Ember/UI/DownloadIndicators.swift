import SwiftUI

/// The Downloads button's icon: an arrow, with a progress ring and how many are going while
/// anything downloads.
struct DownloadIcon: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let a = app.downloadActivity
        ZStack {
            if a.count > 0 {
                Circle().stroke(.quaternary, lineWidth: 2.5).frame(width: 24, height: 24)
                if let p = a.progress {
                    Circle().trim(from: 0, to: max(0.02, p))
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 24, height: 24)
                } else {
                    // Episodes don't say how far along they are: a turning arc.
                    TimelineView(.animation) { t in
                        Circle().trim(from: 0, to: 0.28)
                            .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(t.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1) * 360))
                            .frame(width: 24, height: 24)
                    }
                }
                Image(systemName: "arrow.down").font(.system(size: 11, weight: .bold)).foregroundStyle(Color.accentColor)
            } else {
                Image(systemName: "arrow.down.circle")
            }
        }
        .overlay(alignment: .topTrailing) {
            if a.count > 1 {
                Text("\(min(a.count, 9))").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 15, height: 15).background(Color.accentColor, in: Circle())
                    .offset(x: 7, y: -6)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(a.count > 0 ? "Downloads, \(a.count) downloading" : "Downloads")
    }
}

/// A slim bar on Home while anything downloads: "Downloading 2 · 45%". Tap for Downloads.
struct DownloadingBar: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let a = app.downloadActivity
        if a.count > 0 {
            NavigationLink { DownloadsView() } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "arrow.down.circle.fill").foregroundStyle(Color.accentColor)
                        Text("Downloading \(a.count)" + (a.progress.map { " · \(Int($0 * 100))%" } ?? "")).font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    if let p = a.progress {
                        ProgressView(value: p)
                    } else {
                        ProgressView().progressViewStyle(.linear)
                    }
                }
                .padding(14)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
        }
    }
}
