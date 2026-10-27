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
    /// "New shelf…" from a book's menu: the book to put on it.
    var newShelfFor: String?
    /// Screens opened by the simulator checks (launch argument -EmberRoute).
    var testRoute: String?

    init() {
        let schema = Schema([Book.self, Track.self, Chapter.self, Bookmark.self, ListenEvent.self, DayListening.self,
                             Shelf.self, LibraryFolder.self, Podcast.self, Episode.self, QueueItem.self])
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
        if let route = args.string(forKey: "EmberRoute") { testRoute = route }
        if args.bool(forKey: "EmberSyncTest") { sync.enableTestFolder() }
        player.restoreLast()
        if let fake = args.string(forKey: "EmberSyncFake") {
            let parts = fake.split(separator: ",")
            if parts.count == 2, let pos = Int64(parts[1]) { await sync.testFakeOtherDevice(bookID: String(parts[0]), positionMs: pos) }
        }
        sync.request("open", delay: 1)
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        await library.scanAll()
    }

    func foreground() {
        sync.request("open", delay: 1.5)
    }

    func background() {
        player.saveProgress()
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
