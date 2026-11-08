import SwiftUI

/// Car mode: the player made simple for a glance. The cover sets the mood (blurred, dimmed), and only
/// four things can be touched, all large: back, play/pause, forward and bookmark. Nothing to scrub by
/// accident, and the screen stays on while it's open.
struct CarModeView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    @State private var marked = false

    var body: some View {
        ZStack {
            Color(hex: 0x0B0908).ignoresSafeArea()
            if let l = player.loaded {
                backdrop(l)
                GeometryReader { g in
                    let landscape = g.size.width > g.size.height * 1.15
                    VStack(spacing: 0) {
                        topRow
                        if landscape {
                            HStack(spacing: 32) {
                                cover(l).frame(width: min(g.size.height - 90, 420), height: min(g.size.height - 90, 420))
                                VStack(alignment: .leading, spacing: 0) {
                                    details(l, center: false)
                                    Spacer().frame(height: 24)
                                    controls(l, play: min(112, g.size.height * 0.36), side: min(80, g.size.height * 0.26), withMark: g.size.height < 380)
                                    if g.size.height >= 380 { markPill.padding(.top, 20) }
                                }
                                .frame(maxWidth: 640)
                            }
                            .frame(maxHeight: .infinity)
                        } else {
                            VStack(spacing: 0) {
                                Spacer(minLength: 12)
                                let side = min(g.size.width * 0.82, g.size.height * 0.42, 380)
                                cover(l).frame(width: side, height: side)
                                Spacer(minLength: 16)
                                details(l, center: true)
                                controls(l, play: 132, side: 88, withMark: false).padding(.top, 28)
                                markPill.padding(.top, 24).padding(.bottom, 8)
                            }
                            .frame(maxWidth: 560)
                            .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                }
            } else {
                VStack {
                    topRow.padding(.horizontal, 24)
                    Spacer()
                    Text("Start a book, then come back to car mode.").font(.title3).foregroundStyle(.white.opacity(0.8)).multilineTextAlignment(.center)
                    Spacer()
                }
            }
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .statusBarHidden(false)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func backdrop(_ l: Player.Loaded) -> some View {
        ZStack {
            if let img = Covers.image(l.coverFile) {
                Image(uiImage: img).resizable().scaledToFill().blur(radius: 50).scaleEffect(1.3)
            } else {
                Theme.deep(l.color)
            }
            LinearGradient(stops: [.init(color: .black.opacity(0.55), location: 0), .init(color: .black.opacity(0.72), location: 0.5), .init(color: .black.opacity(0.9), location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }

    private var topRow: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 22, weight: .semibold)).frame(width: 52, height: 52)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel("Exit car mode")
            Spacer()
            Text("CAR MODE").font(.system(size: 13, weight: .medium)).kerning(2).foregroundStyle(.white.opacity(0.5))
            Spacer()
            TimelineView(.periodic(from: .now, by: 15)) { ctx in
                Text(ctx.date.formatted(date: .omitted, time: .shortened)).font(.system(size: 17, weight: .medium)).foregroundStyle(.white.opacity(0.8))
            }
            .frame(width: 70, alignment: .trailing)
        }
    }

    private func cover(_ l: Player.Loaded) -> some View {
        CoverView(file: l.coverFile, title: l.title, color: l.color, corner: 18)
            .shadow(color: .black.opacity(0.55), radius: 28, y: 12)
    }

    private func details(_ l: Player.Loaded, center: Bool) -> some View {
        let left = Int64(Double(max(player.chapterEnd - player.positionMs, 0)) / max(player.speed, 0.1))
        let frac = Double(player.positionMs - player.chapterStart) / Double(max(player.chapterEnd - player.chapterStart, 1))
        let accent = Theme.accent(settings: app.settings, bookColor: l.color, dark: true)
        return VStack(alignment: center ? .center : .leading, spacing: 6) {
            Text(Names.shortTitle(l.title)).font(.system(size: 30, design: .serif)).lineLimit(2).multilineTextAlignment(center ? .center : .leading)
            Text([l.chapters.count > 1 ? player.chapter?.title : nil, "\(Fmt.duration(left)) left"].compactMap { $0 }.joined(separator: "  ·  "))
                .font(.system(size: 18)).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.16))
                    Capsule().fill(accent).frame(width: g.size.width * min(max(frac, 0), 1))
                }
            }
            .frame(height: 4)
            .padding(.top, 10)
            .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, alignment: center ? .center : .leading)
    }

    private func controls(_ l: Player.Loaded, play: CGFloat, side: CGFloat, withMark: Bool) -> some View {
        let accent = Theme.accent(settings: app.settings, bookColor: l.color, dark: true)
        return HStack {
            Spacer()
            round(side, icon: "gobackward.\(app.settings.skipBackSec)", label: "Back \(app.settings.skipBackSec) seconds") {
                player.skip(seconds: -app.settings.skipBackSec)
            }
            Spacer()
            Button {
                Haptics.tap()
                player.toggle()
            } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: play * 0.42, weight: .bold))
                    .foregroundStyle(.black.opacity(0.85))
                    .frame(width: play, height: play)
                    .background(accent, in: Circle())
                    .shadow(color: accent.opacity(0.6), radius: 24)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            Spacer()
            round(side, icon: "goforward.\(app.settings.skipForwardSec)", label: "Forward \(app.settings.skipForwardSec) seconds") {
                player.skip(seconds: app.settings.skipForwardSec)
            }
            if withMark {
                Spacer()
                round(side, icon: marked ? "checkmark" : "bookmark.fill", label: "Bookmark") { mark() }
            }
            Spacer()
        }
    }

    private func round(_ size: CGFloat, icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            Image(systemName: icon).font(.system(size: size * 0.4, weight: .semibold)).frame(width: size, height: size)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }

    private var markPill: some View {
        Button { mark() } label: {
            Label(marked ? "Bookmarked" : "Bookmark", systemImage: marked ? "checkmark" : "bookmark.fill")
                .font(.system(size: 20, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 60)
        }
        .buttonStyle(.glass)
        .frame(maxWidth: 360)
        .frame(maxWidth: .infinity)
    }

    private func mark() {
        player.bookmarkHere()
        withAnimation { marked = true }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { marked = false }
        }
    }
}

/// The widgets as they'd look on the Home and Lock Screens (for the simulator checks).
struct WidgetPreviews: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let s = WidgetBridge.snapshot(app)
        let img = Covers.image(app.player.loaded?.coverFile)
        ScrollView {
            VStack(spacing: 20) {
                Text("Widgets").font(.title2.weight(.bold))
                HStack(spacing: 16) {
                    tile(170, 170) { NowPlayingWidgetView(snap: s, image: img, family: "small") }
                    tile(170, 170, bg: AnyView(Color(red: 0.07, green: 0.06, blue: 0.07))) { ListeningWidgetView(snap: s) }
                }
                tile(364, 170) { NowPlayingWidgetView(snap: s, image: img, family: "medium") }
                HStack(spacing: 16) {
                    LockScreenWidgetView(snap: s, family: "rectangular").padding(10).frame(width: 220, height: 76)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.white)
                    LockScreenWidgetView(snap: s, family: "circular").frame(width: 64, height: 64).padding(6)
                        .background(.black.opacity(0.55), in: Circle()).foregroundStyle(.white)
                }
            }
            .padding(20)
        }
        .background(LinearGradient(colors: [Color(hex: 0x3B5B8C), Color(hex: 0x8C5B7A)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }

    private func tile<V: View>(_ w: CGFloat, _ h: CGFloat, bg: AnyView? = nil, @ViewBuilder _ v: () -> V) -> some View {
        v().padding(16).frame(width: w, height: h)
            .background(bg ?? AnyView(NowPlayingWidgetView.background(WidgetBridge.snapshot(app))))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}
