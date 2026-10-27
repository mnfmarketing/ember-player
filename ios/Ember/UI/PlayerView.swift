import SwiftUI
import AVKit

/// The player: full screen on iPhone (swipe down to close), a column beside the library on iPad.
struct PlayerView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    var column = false

    @State private var scrubbing: Double?
    @State private var sheet: PlayerSheet?
    @State private var locked = false
    @State private var drag: CGFloat = 0

    enum PlayerSheet: Identifiable {
        case chapters, sleep, speed, log, marks, sound
        var id: Self { self }
    }

    var body: some View {
        if let l = player.loaded {
            content(l)
        } else {
            VStack(spacing: 12) {
                Image(systemName: "flame.fill").font(.largeTitle).foregroundStyle(Theme.ember)
                Text("Nothing playing").font(.headline)
                if !column { Button("Close") { dismiss() } }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func content(_ l: Player.Loaded) -> some View {
        let deep = Theme.deep(l.color)
        let soft = Theme.soft(l.color)
        GeometryReader { g in
            let coverSide = min(g.size.width - 48, g.size.height * 0.38, 360)
            VStack(spacing: 0) {
                topBar(l, soft: soft)
                Spacer(minLength: 12)
                CoverView(file: l.coverFile, title: l.title, color: l.color, corner: 14)
                    .frame(width: coverSide, height: coverSide)
                    .shadow(color: .black.opacity(0.5), radius: 24, y: 12)
                    .scaleEffect(player.isPlaying ? 1 : 0.94)
                    .animation(.spring(duration: 0.4), value: player.isPlaying)
                    .onTapGesture(count: 2) { }
                Spacer(minLength: 16)
                titleRow(l, soft: soft)
                HStack(spacing: 8) {
                    Pill(title: "Listening log", icon: "clock.arrow.circlepath") { sheet = .log }
                    if !l.isEpisode { Pill(title: "Bookmarks", icon: "bookmark") { sheet = .marks } }
                    Spacer()
                }
                .padding(.top, 10)
                scrubber(l, soft: soft).padding(.top, 18)
                transport.padding(.top, 6)
                roundButtons(soft: soft).padding(.top, 12)
                HStack {
                    Spacer()
                    RoutePicker().frame(width: 44, height: 44)
                    Spacer()
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, column ? 16 : 4)
            .frame(width: g.size.width, height: g.size.height)
        }
        .foregroundStyle(.white)
        .background(LinearGradient(colors: [deep.opacity(0.95), deep.opacity(0.75), .black.opacity(0.95)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .background(Color.black.ignoresSafeArea())
        .environment(\.colorScheme, .dark)
        .offset(y: drag)
        .gesture(DragGesture(minimumDistance: 20).onChanged { v in
            if v.translation.height > 0, v.startLocation.y < 420 { drag = v.translation.height }
        }.onEnded { v in
            if v.translation.height > 140 { dismiss() }
            withAnimation(.spring) { drag = 0 }
        }, including: column ? .none : .all)
        .overlay { if locked { PocketLock(locked: $locked) } }
        .sheet(item: $sheet) { s in
            switch s {
            case .chapters: ChaptersSheet().presentationDetents([.medium, .large])
            case .sleep: SleepSheet().presentationDetents([.medium])
            case .speed: SpeedSheet().presentationDetents([.height(320)])
            case .log: ListenLogView(bookID: l.id, title: l.title)
            case .marks: BookmarksView(bookID: l.id)
            case .sound:
                if let b = app.library.book(l.id) { BookSoundView(book: b).presentationDetents([.medium]) }
            }
        }
    }

    private func topBar(_ l: Player.Loaded, soft: Color) -> some View {
        HStack {
            if !column {
                Button { dismiss() } label: { Image(systemName: "chevron.down").font(.headline).frame(width: 40, height: 40) }
                    .buttonStyle(.glass).buttonBorderShape(.circle)
                    .accessibilityLabel("Close player")
            }
            Spacer()
            if !l.chapters.isEmpty {
                Text("Chapter \(player.chapterIndex + 1) of \(l.chapters.count)").font(.subheadline.weight(.semibold)).foregroundStyle(soft)
            }
            Spacer()
            if !l.isEpisode {
                Button { sheet = .sound } label: { Image(systemName: "slider.horizontal.3").frame(width: 40, height: 40) }
                    .buttonStyle(.glass).buttonBorderShape(.circle)
                    .accessibilityLabel("Sound")
            }
            Button { locked = true } label: { Image(systemName: "lock").frame(width: 40, height: 40) }
                .buttonStyle(.glass).buttonBorderShape(.circle)
                .accessibilityLabel("Pocket lock")
        }
        .padding(.top, 8)
    }

    private func titleRow(_ l: Player.Loaded, soft: Color) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(l.title).font(.title3.weight(.bold)).lineLimit(1)
                Text(player.chapter?.title ?? l.subtitle).font(.body).foregroundStyle(soft).lineLimit(1)
            }
            Spacer()
        }
    }

    private func scrubber(_ l: Player.Loaded, soft: Color) -> some View {
        let wholeBook = app.settings.bookTimeLeft || l.chapters.count <= 1
        let start = wholeBook ? 0 : player.chapterStart
        let end = wholeBook ? l.durationMs : player.chapterEnd
        let length = max(Double(end - start), 1)
        let shown = scrubbing ?? Double(player.positionMs - start)
        return VStack(spacing: 6) {
            Slider(value: Binding(get: { min(max(shown, 0), length) }, set: { scrubbing = $0 }), in: 0...length) { editing in
                if !editing, let s = scrubbing {
                    player.seek(to: start + Int64(s))
                    scrubbing = nil
                }
            }
            .tint(.white)
            .accessibilityLabel(wholeBook ? "Book position" : "Chapter position")
            .accessibilityValue(Fmt.clock(Int64(shown)))
            HStack {
                Text(Fmt.clock(Int64(shown))).monospacedDigit()
                Spacer()
                Button {
                    app.settings.bookTimeLeft.toggle()
                } label: {
                    Text("−\(Fmt.clock(Int64((length - shown) / player.speed)))\(wholeBook ? " in book" : " in chapter")").monospacedDigit()
                }
                .buttonStyle(.plain)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(soft)
        }
    }

    private var transport: some View {
        HStack {
            Button { player.previousChapter() } label: { Image(systemName: "backward.end.fill").font(.title2) }
                .accessibilityLabel("Previous chapter")
            Spacer()
            Button { player.skip(seconds: -app.settings.skipBackSec) } label: { Image(systemName: "gobackward.\(app.settings.skipBackSec)").font(.system(size: 34)) }
                .accessibilityLabel("Skip back \(app.settings.skipBackSec) seconds")
            Spacer()
            Button { player.toggle() } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 52)).frame(width: 76, height: 76).contentTransition(.symbolEffect(.replace))
            }
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            Spacer()
            Button { player.skip(seconds: app.settings.skipForwardSec) } label: { Image(systemName: "goforward.\(app.settings.skipForwardSec)").font(.system(size: 34)) }
                .accessibilityLabel("Skip forward \(app.settings.skipForwardSec) seconds")
            Spacer()
            Button { player.nextChapter() } label: { Image(systemName: "forward.end.fill").font(.title2) }
                .accessibilityLabel("Next chapter")
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.impact(weight: .light), trigger: player.chapterIndex)
    }

    private func roundButtons(soft: Color) -> some View {
        HStack(spacing: 0) {
            Round(label: "Speed", text: Fmt.speed(player.speed)) { sheet = .speed }
            Round(label: player.sleep.shortLabel ?? "Sleep", icon: player.sleep.isOn ? "moon.fill" : "moon", highlighted: player.sleep.isOn) { sheet = .sleep }
            Round(label: "Bookmark", icon: "bookmark") { player.bookmarkHere() }
            Round(label: "Chapters", icon: "list.bullet") { sheet = .chapters }
        }
    }
}

private struct Round: View {
    var label: String
    var icon: String?
    var text: String?
    var highlighted = false
    let action: () -> Void

    var body: some View {
        VStack(spacing: 5) {
            Button(action: action) {
                Group {
                    if let icon { Image(systemName: icon).font(.title3) } else { Text(text ?? "").font(.subheadline.weight(.bold)) }
                }
                .frame(width: 50, height: 50)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .tint(highlighted ? Color.accentColor : nil)
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.8))
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// AirPlay and Bluetooth outputs.
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = .white
        v.prioritizesVideoDevices = false
        return v
    }

    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

/// Dims the screen and ignores taps (for listening with the phone in a pocket). Hold to unlock.
private struct PocketLock: View {
    @Binding var locked: Bool
    @State private var holding = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.88).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: holding ? "lock.open.fill" : "lock.fill").font(.system(size: 44))
                Text("Pocket lock is on").font(.headline)
                Text("Press and hold anywhere to unlock").font(.subheadline).foregroundStyle(.secondary)
            }
            .foregroundStyle(.white)
        }
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: 1) { locked = false } onPressingChanged: { holding = $0 }
        .accessibilityAction(named: "Unlock") { locked = false }
    }
}

struct ChaptersSheet: View {
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(Array((player.loaded?.chapters ?? []).enumerated()), id: \.offset) { i, c in
                        Button {
                            player.goTo(chapter: i)
                            dismiss()
                        } label: {
                            HStack {
                                if i == player.chapterIndex { Image(systemName: "waveform").foregroundStyle(Color.accentColor) }
                                Text(c.title).foregroundStyle(.primary)
                                Spacer()
                                Text(Fmt.clock(c.endMs - c.startMs)).foregroundStyle(.secondary).monospacedDigit()
                            }
                        }
                        .id(i)
                    }
                }
                .onAppear { proxy.scrollTo(player.chapterIndex, anchor: .center) }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct SleepSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if player.sleep.isOn {
                    Section {
                        Button("Turn off", role: .destructive) {
                            player.sleep.cancel()
                            dismiss()
                        }
                    } footer: {
                        Text("Shake your phone to start the timer over.")
                    }
                }
                Section {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { m in
                        Button("\(m) minutes") {
                            app.settings.lastSleepMinutes = m
                            player.sleep.start(minutes: m, positionMs: player.positionMs)
                            dismiss()
                        }
                    }
                    Button("End of chapter") {
                        player.sleep.startEndOfChapter(positionMs: player.positionMs, chapterEnd: player.chapterEnd)
                        dismiss()
                    }
                }
            }
            .navigationTitle("Sleep timer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct SpeedSheet: View {
    @Environment(Player.self) private var player

    var body: some View {
        VStack(spacing: 18) {
            Text(Fmt.speed(player.speed)).font(.system(size: 44, weight: .bold)).monospacedDigit()
            Slider(value: Binding(get: { player.speed }, set: { player.setSpeed(($0 * 20).rounded() / 20) }), in: 0.5...3.0)
                .accessibilityLabel("Speed")
            HStack {
                ForEach([0.8, 1.0, 1.2, 1.5, 2.0], id: \.self) { s in
                    Button(Fmt.speed(s)) { player.setSpeed(s) }
                        .buttonStyle(.glass)
                        .tint(abs(player.speed - s) < 0.01 ? Color.accentColor : nil)
                }
            }
        }
        .padding(24)
    }
}
