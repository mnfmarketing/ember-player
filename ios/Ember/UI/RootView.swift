import SwiftUI
import SwiftData

struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.colorScheme) private var scheme
    @State private var shelfName = ""

    var body: some View {
        @Bindable var app = app
        let wide = hSize == .regular
        TabView(selection: $app.selectedTab) {
            Tab("Home", systemImage: "house.fill", value: AppTab.home) {
                NavigationStack { HomeView() }
            }
            Tab("Books", systemImage: "books.vertical.fill", value: AppTab.books) {
                NavigationStack { BooksView() }
            }
            Tab("Podcasts", systemImage: "mic.fill", value: AppTab.podcasts) {
                NavigationStack { PodcastsView() }
            }
            Tab(value: AppTab.search, role: .search) {
                NavigationStack { SearchView() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabBarMinimizeBehavior(.onScrollDown)
        .miniPlayer(!wide && app.player.loaded != nil)
        // iPad: the player stays open beside the library, like the Fold unfolded.
        .inspector(isPresented: .constant(wide && app.player.loaded != nil)) {
            PlayerView(column: true)
                .inspectorColumnWidth(min: 320, ideal: 360, max: 520)
        }
        .fullScreenCover(isPresented: $app.playerOpen) { PlayerView() }
        .sheet(isPresented: $app.profileOpen) { ProfileView() }
        .alert(
            app.sync.prompt.map { "Continue from your \($0.deviceName)?" } ?? "",
            isPresented: Binding(get: { app.sync.prompt != nil }, set: { _ in }),
            presenting: app.sync.prompt
        ) { _ in
            Button("Stay here", role: .cancel) { app.sync.answer(go: false) }
            Button("Go there") { app.sync.answer(go: true) }
        } message: { p in
            Text("You're at \(spot(p.bookID, p.positionMs)) on your \(p.deviceName)\(p.at > 0 ? " (\(ago(p.at)))" : "").")
        }
        .alert("New shelf", isPresented: Binding(get: { app.newShelfFor != nil }, set: { if !$0 { app.newShelfFor = nil } })) {
            TextField("Name", text: $shelfName)
            Button("Cancel", role: .cancel) { app.newShelfFor = nil }
            Button("Add") {
                let name = shelfName.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty, let id = app.newShelfFor {
                    let shelf = Shelf(name: name)
                    shelf.books = [id]
                    app.library.context.insert(shelf)
                    app.library.changed()
                }
                shelfName = ""
                app.newShelfFor = nil
            }
        }
        .overlay(alignment: .top) { NoticeToast() }
        .tint(Theme.accent(settings: app.settings, bookColor: app.player.loaded?.color, dark: scheme == .dark))
        .task(id: app.testRoute) { openTestRoute() }
    }

    private func spot(_ bookID: String, _ ms: Int64) -> String {
        let ch = app.library.chapters(bookID).last { $0.startMs <= ms }
        return ch.map { "\($0.title) · \(Fmt.clock(ms - $0.startMs))" } ?? Fmt.clock(ms)
    }

    private func ago(_ at: Int64) -> String { Clock.now - at < 60_000 ? "just now" : Fmt.ago(at) }

    /// The simulator checks open screens with -EmberRoute.
    private func openTestRoute() {
        guard let r = app.testRoute else { return }
        switch r {
        case "books": app.selectedTab = .books
        case "podcasts": app.selectedTab = .podcasts
        case "search": app.selectedTab = .search
        case "player": if hSize != .regular { app.playerOpen = true }  // iPad: the player column is already open
        case "profile": app.profileOpen = true
        case "book":
            app.selectedTab = .home
            app.openBook = BookRoute(id: app.settings.lastBookID ?? "demo-0")
        case "show":
            app.selectedTab = .podcasts
            if let p = app.podcasts.podcasts().first { app.openShow = ShowRoute(id: p.id) }
        default: app.selectedTab = .home
        }
    }
}

/// The glass bar above the tabs: what's playing, play/pause and skip forward. Tap to open the player.
struct MiniPlayer: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        if let l = player.loaded {
            HStack(spacing: 10) {
                CoverView(file: l.coverFile, title: l.title, color: l.color, corner: 7)
                    .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(l.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if placement != .inline {
                        Text(subtitle(l)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if let s = player.sleep.shortLabel, placement != .inline {
                    Label(s, systemImage: "moon.fill").font(.caption.weight(.semibold)).labelStyle(.titleAndIcon)
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(.quaternary, in: Capsule())
                }
                Button { player.toggle() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title3)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
                Button { player.skip(seconds: app.settings.skipForwardSec) } label: {
                    Image(systemName: "goforward.\(app.settings.skipForwardSec)").font(.title3)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel("Skip forward \(app.settings.skipForwardSec) seconds")
            }
            .padding(.horizontal, 10)
            .contentShape(Rectangle())
            .onTapGesture { app.playerOpen = true }
            .tint(.primary)
        } else {
            HStack {
                Image(systemName: "flame.fill").foregroundStyle(Theme.ember)
                Text("Pick a book to start").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 14)
        }
    }

    private func subtitle(_ l: Player.Loaded) -> String {
        let left = max(player.chapterEnd - player.positionMs, 0)
        if let ch = player.chapter { return "\(ch.title) · \(Fmt.duration(Int64(Double(left) / player.speed))) left" }
        return l.subtitle
    }
}

/// One-off messages from the player ("Bookmarked at 12:03", "Up Next: …") and the Go back offer.
struct NoticeToast: View {
    @Environment(Player.self) private var player
    @State private var shown: String?
    @State private var jump: Player.Jump?

    var body: some View {
        VStack(spacing: 8) {
            if let shown {
                Text(shown)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .glassEffect(.regular, in: Capsule())
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            if let jump {
                HStack(spacing: 12) {
                    Text("Moved from \(Fmt.clock(jump.fromMs))").font(.subheadline)
                    Button("Go back") {
                        player.seek(to: jump.fromMs, logged: false)
                        self.jump = nil
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .glassEffect(.regular, in: Capsule())
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .padding(.top, 8)
        .animation(.spring(duration: 0.35), value: shown)
        .animation(.spring(duration: 0.35), value: jump)
        .onChange(of: player.notice) { _, n in
            guard let n else { return }
            shown = n
            player.notice = nil
            Task {
                try? await Task.sleep(for: .seconds(3))
                if shown == n { shown = nil }
            }
        }
        .onChange(of: player.lastJump) { _, j in
            guard let j else { return }
            jump = j
            player.lastJump = nil
            Task {
                try? await Task.sleep(for: .seconds(6))
                if jump == j { jump = nil }
            }
        }
    }
}

/// The round avatar at the top right of each tab: your photo, or your first letter.
struct ProfileButton: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Button { app.profileOpen = true } label: { Avatar(size: 30) }
            .accessibilityLabel("Profile")
    }
}

struct Avatar: View {
    @Environment(AppModel.self) private var app
    var size: CGFloat

    var body: some View {
        let _ = app.settings.profilePhotoAt
        Group {
            if let img = ProfilePhoto.image() {
                Image(uiImage: img).resizable().scaledToFill()
            } else if let first = app.settings.profileName.trimmingCharacters(in: .whitespaces).first {
                Text(String(first).uppercased())
                    .font(.system(size: size * 0.46, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Theme.ember)
            } else {
                Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

private extension View {
    /// The mini player above the tab bar. Hidden (not an empty glass bar) when nothing is loaded or the iPad shows the player column.
    @ViewBuilder
    func miniPlayer(_ show: Bool) -> some View {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: show) { MiniPlayer() }
        } else if show {
            tabViewBottomAccessory { MiniPlayer() }
        } else {
            self
        }
    }
}
