import Foundation
import SwiftData
import Observation
import BackgroundTasks
import UserNotifications

/// Everything the app shares: the library, the player, podcasts, sync and your settings.
@MainActor
@Observable
final class AppModel {
    let container: ModelContainer
    let settings = Settings()
    let library: Library
    let podcasts: PodcastStore
    let player: Player
    let google = GoogleAuth()
    let sync: SyncService

    /// What's open: the full player, the profile sheet.
    var playerOpen = false
    var profileOpen = false
    var selectedTab: AppTab = .home
    /// Pages opened from elsewhere (and by the simulator checks).
    var openBook: BookRoute?
    var openShow: ShowRoute?
    /// "New shelf…" from a book's menu: the book to put on it.
    var newShelfFor: String?
    /// Settings opened in the profile sheet: a page, or the list.
    var openSettings: SettingsTarget?
    /// Downloads opened from the Books tab (and by the checks).
    var openDownloads = false
    /// Screens opened by the simulator checks (launch argument -EmberRoute).
    var testRoute: String?
    /// The checks: text typed into Settings' search box (-EmberSettingsSearch), pretend downloads (-EmberFakeDownloads 2,45).
    var testSettingsQuery: String?
    var fakeDownloads: (count: Int, progress: Double)?

    /// What's downloading now (episodes and Google Drive books): how many, and how far along in all
    /// (nil when that can't be told: episodes don't report their progress).
    var downloadActivity: (count: Int, progress: Double?) {
        if let f = fakeDownloads { return (f.count, f.progress) }
        let eps = podcasts.downloading.count
        let books = Array(library.saving.values)
        let count = eps + books.count
        guard count > 0 else { return (0, nil) }
        if eps > 0 { return (count, nil) }
        let done = books.reduce(0) { $0 + $1.0 }
        let total = books.reduce(0) { $0 + $1.1 }
        return (count, total > 0 ? Double(done) / Double(total) : nil)
    }

    init() {
        let schema = Schema([Book.self, Track.self, Chapter.self, Bookmark.self, ListenEvent.self, DayListening.self,
                             Shelf.self, LibraryFolder.self, Podcast.self, Episode.self, QueueItem.self])
        // SwiftData keeps the library in Application Support; make sure the folder is there first.
        try? FileManager.default.createDirectory(at: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0], withIntermediateDirectories: true)
        let config = ModelConfiguration(schema: schema)
        do {
            container = try ModelContainer(for: schema, configurations: [config])
        } catch {
            // Never lose the library to a failed upgrade: keep the old store aside and start fresh.
            let url = config.url
            let aside = url.deletingLastPathComponent().appendingPathComponent("library-\(Int(Date().timeIntervalSince1970)).store")
            try? FileManager.default.moveItem(at: url, to: aside)
            container = try! ModelContainer(for: schema, configurations: [config])
        }
        let ctx = container.mainContext
        library = Library(context: ctx, settings: settings)
        podcasts = PodcastStore(context: ctx, settings: settings)
        player = Player(library: library, settings: settings)
        sync = SyncService(library: library, podcasts: podcasts, settings: settings, auth: google)
        player.podcasts = podcasts
        library.drive = DriveFiles(auth: google)
        sync.player = player
        player.onSyncWorthy = { [weak self] reason in self?.sync.request(reason, delay: reason == "playing" ? 1 : 4) }
        player.sleep.onShakeAfterExpiry = { [weak self] in
            guard let self else { return }
            self.player.resume()
            self.player.sleep.start(minutes: self.settings.lastSleepMinutes, positionMs: self.player.positionMs)
        }
    }

    /// Runs once the window is up.
    func started() async {
        let args = UserDefaults.standard
        if args.bool(forKey: "EmberDemo") { await Demo.seed(self) }
        if let q = args.string(forKey: "EmberSettingsSearch") { testSettingsQuery = q }
        if let f = args.string(forKey: "EmberFakeDownloads") {
            let p = f.split(separator: ",")
            if p.count == 2, let n = Int(p[0]), let pct = Double(p[1]) { fakeDownloads = (n, pct / 100) }
        }
        if let route = args.string(forKey: "EmberRoute") { testRoute = route }
        if args.bool(forKey: "EmberSyncTest") { sync.enableTestFolder() }
        player.restoreLast()
        if let fake = args.string(forKey: "EmberSyncFake") {
            let parts = fake.split(separator: ",")
            if parts.count == 2, let pos = Int64(parts[1]) { await sync.testFakeOtherDevice(bookID: String(parts[0]), positionMs: pos) }
        }
        sync.request("open", delay: 1)
        await library.scanAll()
    }

    func foreground() {
        sync.request("open", delay: 1.5)
    }

    func background() {
        player.saveProgress()
        Backup.daily(self)
        sync.request("background", delay: 0)
        scheduleRefresh()
    }

    // MARK: Background refresh (new episodes, sync)

    static let refreshID = "app.ember.refresh"

    func scheduleRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: Self.refreshID)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }

    func backgroundRefresh() async {
        scheduleRefresh()
        await podcasts.refreshAll()
        await sync.syncNow()
    }
}

enum AppTab: Hashable {
    case home, books, podcasts, search
}
