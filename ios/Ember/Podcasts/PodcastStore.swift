import Foundation
import SwiftData
import Observation
import UserNotifications

/// Shows you follow, their episodes, downloads and Up Next.
@MainActor
@Observable
final class PodcastStore {
    let context: ModelContext
    let settings: Settings
    private(set) var version = 0
    var refreshing = false
    /// Episode id → download progress (0...1) while downloading.
    var downloading: [String: Double] = [:]

    init(context: ModelContext, settings: Settings) {
        self.context = context
        self.settings = settings
    }

    func changed() {
        try? context.save()
        version &+= 1
    }

    static var downloadsDir: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Episodes", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func downloadURL(_ file: String) -> URL? { downloadsDir.appendingPathComponent(file) }

    // MARK: Reading

    func podcasts() -> [Podcast] { (try? context.fetch(FetchDescriptor<Podcast>(sortBy: [SortDescriptor(\.title)]))) ?? [] }

    func podcast(_ id: String) -> Podcast? {
        var d = FetchDescriptor<Podcast>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    func episode(_ id: String) -> Episode? {
        var d = FetchDescriptor<Episode>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    func episodes(_ podcastID: String) -> [Episode] {
        let d = FetchDescriptor<Episode>(predicate: #Predicate { $0.podcastID == podcastID }, sortBy: [SortDescriptor(\.publishedAt, order: .reverse)])
        return (try? context.fetch(d)) ?? []
    }

    func episode(podcastID: String, guid: String) -> Episode? { episode(Ids.episode(podcastID: podcastID, guid: guid)) }

    /// New episodes from every show, newest first.
    func newEpisodes(limit: Int = 60) -> [Episode] {
        var d = FetchDescriptor<Episode>(predicate: #Predicate { !$0.played && !$0.archived }, sortBy: [SortDescriptor(\.publishedAt, order: .reverse)])
        d.fetchLimit = limit
        return (try? context.fetch(d)) ?? []
    }

    func withProgress() -> [Episode] {
        let d = FetchDescriptor<Episode>(predicate: #Predicate { $0.played || $0.positionMs > 0 || $0.archived })
        return (try? context.fetch(d)) ?? []
    }

    func queue() -> [QueueItem] { (try? context.fetch(FetchDescriptor<QueueItem>(sortBy: [SortDescriptor(\.position)]))) ?? [] }

    func queuedEpisodes() -> [Episode] { queue().compactMap { episode($0.episodeID) } }

    // MARK: Following

    /// Follows a show (reads its feed). Returns its id, or nil if the feed couldn't be read.
    @discardableResult
    func follow(_ feedURL: String, artworkHint: String? = nil) async -> String? {
        let id = Ids.podcast(feedURL)
        if podcast(id) != nil { return id }
        guard let feed = try? await Feeds.fetch(feedURL) else { return nil }
        let p = Podcast(id: id, feedURL: feedURL.trimmingCharacters(in: .whitespaces), title: feed.title)
        p.author = feed.author
        p.desc = feed.desc
        p.artworkURL = feed.artworkURL.isEmpty ? (artworkHint ?? "") : feed.artworkURL
        p.category = feed.category
        p.lastRefreshAt = Date()
        context.insert(p)
        for e in feed.episodes { insert(e, podcastID: id) }
        changed()
        await fetchArtwork(p)
        if p.notify { await Self.askForAlerts() }
        return id
    }

    /// Asks once, when alerts first matter (following a show or turning its alerts on), not at launch.
    static func askForAlerts() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    private func insert(_ e: ParsedEpisode, podcastID: String) {
        let ep = Episode(id: Ids.episode(podcastID: podcastID, guid: e.guid), podcastID: podcastID, guid: e.guid, title: e.title, audioURL: e.audioURL)
        ep.desc = e.desc
        ep.durationMs = e.durationMs
        ep.publishedAt = e.publishedAt
        ep.chaptersURL = e.chaptersURL
        context.insert(ep)
    }

    private func fetchArtwork(_ p: Podcast) async {
        guard p.artworkFile == nil, let url = URL(string: p.artworkURL), let got = try? await URLSession.shared.data(from: url) else { return }
        if let saved = Covers.save(id: p.id, data: got.0) {
            p.artworkFile = saved.0
            p.color = saved.1
            changed()
        }
    }

    func unfollow(_ p: Podcast) {
        let pid = p.id
        for e in episodes(pid) {
            removeDownload(e)
            if let q = queue().first(where: { $0.episodeID == e.id }) { context.delete(q) }
        }
        try? context.delete(model: Episode.self, where: #Predicate { $0.podcastID == pid })
        Covers.remove(p.artworkFile)
        context.delete(p)
        changed()
    }

    func importOPML(_ data: Data) async -> (added: Int, failed: Int) {
        var added = 0, failed = 0
        for url in Feeds.parseOPML(data) {
            if await follow(url) != nil { added += 1 } else { failed += 1 }
        }
        return (added, failed)
    }

    /// Checks every show for new episodes. Returns the new ones.
    @discardableResult
    func refreshAll() async -> [Episode] {
        guard !refreshing else { return [] }
        refreshing = true
        defer { refreshing = false }
        var found: [Episode] = []
        for p in podcasts() {
            guard let feed = try? await Feeds.fetch(p.feedURL) else { continue }
            let known = Set(episodes(p.id).map(\.guid))
            let isFirst = known.isEmpty
            for e in feed.episodes where !known.contains(e.guid) {
                insert(e, podcastID: p.id)
                if !isFirst, let ep = episode(podcastID: p.id, guid: e.guid) { found.append(ep) }
            }
            p.lastRefreshAt = Date()
            if p.artworkFile == nil { await fetchArtwork(p) }
            for ep in found where ep.podcastID == p.id {
                if p.autoQueue { addToQueue(ep, next: false) }
                if p.autoDownload { Task { await self.download(ep) } }
            }
        }
        changed()
        notify(found)
        return found
    }

    private func notify(_ eps: [Episode]) {
        for ep in eps.prefix(5) {
            guard let p = podcast(ep.podcastID), p.notify else { continue }
            let c = UNMutableNotificationContent()
            c.title = p.title
            c.body = ep.title
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: ep.id, content: c, trigger: nil))
        }
    }

    // MARK: Episodes

    func setPlayed(_ ep: Episode, _ played: Bool) {
        ep.played = played
        ep.playedAt = played ? Clock.now : 0
        ep.positionMs = played ? ep.durationMs : 0
        if played {
            ep.archived = ep.archived || (podcast(ep.podcastID)?.autoArchive ?? true)
            removeFromQueue(ep)
            removeDownload(ep)
        } else {
            ep.archived = false
        }
        changed()
    }

    func setArchived(_ ep: Episode, _ archived: Bool) {
        ep.archived = archived
        if archived {
            removeFromQueue(ep)
            removeDownload(ep)
        }
        changed()
    }

    func addToQueue(_ ep: Episode, next: Bool) {
        let q = queue()
        if let have = q.first(where: { $0.episodeID == ep.id }) { context.delete(have) }
        let pos = next ? (q.first?.position ?? 0) - 1 : (q.last?.position ?? 0) + 1
        context.insert(QueueItem(episodeID: ep.id, position: pos))
        changed()
    }

    func removeFromQueue(_ ep: Episode) {
        for q in queue() where q.episodeID == ep.id { context.delete(q) }
        changed()
    }

    func move(from: IndexSet, to: Int) {
        var ids = queue().map(\.episodeID)
        ids.move(fromOffsets: from, toOffset: to)
        setQueue(ids)
    }

    func setQueue(_ ids: [String]) {
        for q in queue() { context.delete(q) }
        for (i, id) in ids.enumerated() { context.insert(QueueItem(episodeID: id, position: Double(i))) }
        changed()
    }

    // MARK: Downloads

    func download(_ ep: Episode) async {
        guard ep.downloadFile == nil, downloading[ep.id] == nil, let url = URL(string: ep.audioURL) else { return }
        downloading[ep.id] = 0
        defer { downloading[ep.id] = nil }
        do {
            let (tmp, resp) = try await URLSession.shared.download(from: url)
            let ext = (resp.suggestedFilename as NSString?)?.pathExtension.lowercased()
            let name = ep.id + "." + ((ext?.isEmpty == false) ? ext! : "mp3")
            let dest = PodcastStore.downloadsDir.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            ep.downloadFile = name
            changed()
        } catch {
            // Stays streamable; try again later.
        }
    }

    func removeDownload(_ ep: Episode) {
        guard let f = ep.downloadFile, let url = PodcastStore.downloadURL(f) else { return }
        try? FileManager.default.removeItem(at: url)
        ep.downloadFile = nil
    }

    func downloaded() -> [Episode] {
        let d = FetchDescriptor<Episode>(predicate: #Predicate { $0.downloadFile != nil })
        return (try? context.fetch(d)) ?? []
    }
}
