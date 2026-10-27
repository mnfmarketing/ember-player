import Foundation
import SwiftData
import Observation
import UIKit

/// Keeps your place, notes, stats, podcasts and listening log the same as on your Fold (and iPad),
/// through Google Drive's hidden Ember folder. The same items and rules as Android's SyncManager.
///
/// Sends: on pause, every 5 minutes while playing, when Ember goes to the background.
/// Checks: when Ember opens or comes back on screen, and on background refresh.
@MainActor
@Observable
final class SyncService {

    struct Prompt: Equatable {
        var bookID: String
        var positionMs: Int64
        var deviceName: String
        var at: Int64
        var key: String
    }

    private(set) var isOn: Bool
    private(set) var running = false
    private(set) var lastSyncAt: Date?
    private(set) var error: String?
    private(set) var otherDevices: [String] = []
    var prompt: Prompt?

    @ObservationIgnored private let d = UserDefaults.standard
    @ObservationIgnored private let library: Library
    @ObservationIgnored private let podcasts: PodcastStore
    @ObservationIgnored private let settings: Settings
    @ObservationIgnored let auth: GoogleAuth
    @ObservationIgnored weak var player: Player?
    @ObservationIgnored private var pending: Task<Void, Never>?
    @ObservationIgnored private var names: [String: String]

    /// Item kinds this app doesn't have yet: never treated as deleted here, never overwritten from here.
    static let notHere: [String] = ["clip/"]

    init(library: Library, podcasts: PodcastStore, settings: Settings, auth: GoogleAuth) {
        self.library = library
        self.podcasts = podcasts
        self.settings = settings
        self.auth = auth
        isOn = UserDefaults.standard.bool(forKey: "syncOn")
        names = (UserDefaults.standard.dictionary(forKey: "syncNames") as? [String: String]) ?? [:]
        let last = UserDefaults.standard.double(forKey: "syncLastAt")
        lastSyncAt = last > 0 ? Date(timeIntervalSince1970: last) : nil
        publishDevices()
    }

    var deviceID: String {
        if let id = d.string(forKey: "syncDeviceID") { return id }
        let id = "i-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10).lowercased()
        d.set(id, forKey: "syncDeviceID")
        return id
    }

    var deviceName: String {
        if let n = d.string(forKey: "syncDeviceName"), !n.isEmpty { return n }
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    }

    func nameOf(_ id: String) -> String { id.isEmpty || id == deviceID ? deviceName : (names[id] ?? "Other device") }

    private func publishDevices() {
        otherDevices = Array(Set(names.filter { $0.key != deviceID }.map(\.value))).sorted()
    }

    private var testFolder: Bool { d.bool(forKey: "syncTestFolder") }
    static var testDir: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("sync-test") }

    private func remote() -> SyncRemote {
        testFolder ? FolderRemote(dir: Self.testDir) : DriveAppDataRemote(token: { [auth] in await auth.token() })
    }

    // MARK: On and off

    func turnOn() async -> Bool {
        if !testFolder {
            if !auth.has(GoogleAuth.appData) {
                guard await auth.connect(scopes: [GoogleAuth.appData]) else {
                    error = auth.lastError
                    return false
                }
            }
        }
        isOn = true
        d.set(true, forKey: "syncOn")
        error = nil
        request("turned on", delay: 0)
        return true
    }

    func turnOff() {
        isOn = false
        d.set(false, forKey: "syncOn")
        prompt = nil
    }

    func setDeviceName(_ n: String) {
        d.set(n.trimmingCharacters(in: .whitespaces), forKey: "syncDeviceName")
        request("renamed", delay: 0)
    }

    func enableTestFolder() {
        d.set(true, forKey: "syncTestFolder")
        isOn = true
        d.set(true, forKey: "syncOn")
    }

    /// Syncs soon; requests close together become one.
    func request(_ reason: String, delay: Double = 3) {
        guard isOn else { return }
        pending?.cancel()
        pending = Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            if Task.isCancelled { return }
            await self.syncNow()
        }
    }

    // MARK: Syncing

    @discardableResult
    func syncNow() async -> Bool {
        guard isOn, !running else { return false }
        running = true
        defer { running = false }
        do {
            let r = remote()
            var files: [String: String] = [:]
            var snap: Snapshot?
            for attempt in 0..<3 {
                files = try await r.list()
                let remoteStamp = files["state.json"]
                var remoteText: String?
                if remoteStamp != nil { remoteText = try await r.read("state.json") }
                let remoteState = SyncState.parse(remoteText)
                let shadow = SyncState.parse(try? String(contentsOf: shadowURL, encoding: .utf8))
                let s = snapshot(shadow)
                snap = s
                let now = Clock.now
                var frozen = shadow.unapplied.union(frozenKeys(s))
                for k in shadow.items.keys where Self.notHere.contains(where: { k.hasPrefix($0) }) { frozen.insert(k) }
                for k in remoteState.items.keys where Self.notHere.contains(where: { k.hasPrefix($0) }) { frozen.insert(k) }
                let result = SyncMerge.merge(remote: remoteState.items, shadow: shadow.items, local: s.items, ownersNow: s.owners,
                                             ownersBefore: shadow.owners, me: deviceID, now: now, frozen: frozen)
                var devices = remoteState.devices
                let mine = devices[deviceID]
                devices[deviceID] = (deviceName, now)
                let stale = mine == nil || mine!.name != deviceName || now - mine!.seen > 6 * 3_600_000
                if result.upload || stale {
                    if attempt < 2, remoteStamp != nil {
                        let now2 = try await r.stamp("state.json")
                        if now2 != remoteStamp { continue }
                    }
                    var st = SyncState()
                    st.items = result.merged
                    st.devices = devices
                    try await r.write("state.json", st.toJSON())
                }
                if result.won.contains("photo") { try? await uploadPhoto(r) }
                names = devices.mapValues(\.name)
                d.set(names, forKey: "syncNames")
                publishDevices()

                let unapplied = await apply(result.apply, merged: result.merged, s: s, r: r)
                var newShadow = SyncState()
                newShadow.items = result.merged
                newShadow.owners = s.owners
                newShadow.unapplied = unapplied
                try? newShadow.toJSON().write(to: shadowURL, atomically: true, encoding: .utf8)
                break
            }
            if let snap { try await syncLogs(r, snap, files) }
            library.changed()
            podcasts.changed()
            lastSyncAt = Date()
            d.set(Date().timeIntervalSince1970, forKey: "syncLastAt")
            error = nil
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    private var dir: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Sync")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    private var shadowURL: URL { dir.appendingPathComponent("shadow.json") }
    private var logIndexURL: URL { dir.appendingPathComponent("logs.json") }

    // MARK: This library as items

    struct Snapshot {
        var items: [String: LocalItem] = [:]
        var owners: Set<String> = ["*"]
        var books: [String: Book] = [:]
        var keyOf: [String: String] = [:]
        var shows: [String: Podcast] = [:]
        var ownDays: [Int64: Int64] = [:]
        var dbDays: [Int64: Int64] = [:]
    }

    private func snapshot(_ shadow: SyncState) -> Snapshot {
        var s = Snapshot()
        let me = deviceID
        for b in library.books().sorted(by: { $0.lastPlayedAt > $1.lastPlayedAt }) {
            guard let bk = SyncKeys.book(title: b.title, author: b.author, external: b.isExternal), s.books[bk] == nil else { continue }
            s.books[bk] = b
            s.keyOf[b.id] = bk
            s.owners.insert(bk)
            s.items["b/\(bk)/pos"] = LocalItem(v: Canon.obj([("p", .int(b.positionMs)), ("lp", .int(b.lastPlayedAt)), ("d", .int(b.durationMs / 1000))]), o: bk, hint: b.lastPlayedAt, hinted: true)
            s.items["b/\(bk)/status"] = LocalItem(v: Canon.obj([("s", .int(Int64(b.status))), ("f", .int(b.finishedAt))]), o: bk)
            s.items["b/\(bk)/speed"] = LocalItem(v: Canon.obj([("v", .int(Int64((b.speed * 100).rounded())))]), o: bk)
            s.items["b/\(bk)/rating"] = LocalItem(v: Canon.obj([("v", .int(Int64(b.rating)))]), o: bk)
            s.items["b/\(bk)/sound"] = LocalItem(v: Canon.obj([("ce", .bool(b.customEffects)), ("ts", .bool(b.trimSilence)), ("vb", .bool(b.voiceBoost)),
                                                               ("si", .int(Int64(b.skipIntroSec))), ("so", .int(Int64(b.skipOutroSec)))]), o: bk)
        }
        for m in library.allBookmarks() {
            guard let bk = s.keyOf[m.bookID] else { continue }
            s.items["mark/\(m.createdAt)"] = LocalItem(v: Canon.obj([("b", .str(bk)), ("p", .int(m.positionMs)), ("n", .str(m.note)), ("c", .str(m.chapterTitle))]), o: bk)
        }
        let shows = podcasts.podcasts()
        let byID = Dictionary(shows.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for p in shows {
            s.shows[p.feedURL] = p
            s.owners.insert(SyncKeys.show(p.feedURL))
            s.items["show/\(p.feedURL)"] = LocalItem(v: Canon.obj([
                ("ad", .bool(p.autoDownload)), ("nt", .bool(p.notify)), ("sp", .int(Int64((p.speed * 100).rounded()))), ("aq", .bool(p.autoQueue)),
                ("aa", .bool(p.autoArchive)), ("si", .int(Int64(p.skipIntroSec))), ("so", .int(Int64(p.skipOutroSec))), ("ce", .bool(p.customEffects)),
                ("ts", .bool(p.trimSilence)), ("vb", .bool(p.voiceBoost)),
            ]), o: "*")
        }
        for e in podcasts.withProgress() {
            guard let p = byID[e.podcastID] else { continue }
            let ek = SyncKeys.episode(feed: p.feedURL, guid: e.guid)
            let owner = SyncKeys.show(p.feedURL)
            s.items["ep/\(ek)"] = LocalItem(v: Canon.obj([("f", .str(p.feedURL)), ("g", .str(e.guid)), ("p", .int(e.positionMs)), ("pl", .bool(e.played)),
                                                          ("pa", .int(e.playedAt)), ("lp", .int(e.lastPlayedAt))]), o: owner, hint: max(e.lastPlayedAt, e.playedAt), hinted: true)
            s.items["epa/\(ek)"] = LocalItem(v: Canon.obj([("f", .str(p.feedURL)), ("g", .str(e.guid)), ("v", .bool(e.archived))]), o: owner)
        }
        s.items["podq"] = LocalItem(v: Canon.obj([("v", .list(podcasts.queuedEpisodes().compactMap { e in byID[e.podcastID].map { SyncKeys.episode(feed: $0.feedURL, guid: e.guid) } }))]), o: "*")
        for sh in library.shelves() {
            let lower = sh.name.lowercased()
            s.items["shelf/\(lower)"] = LocalItem(v: Canon.obj([("n", .str(sh.name))]), o: "*")
            for id in sh.books {
                guard let bk = s.keyOf[id] else { continue }
                s.items["shelfbook/\(lower)/\(bk)"] = LocalItem(v: Canon.obj([("s", .str(lower)), ("b", .str(bk))]), o: bk)
            }
        }
        s.items["upnext"] = LocalItem(v: Canon.obj([("v", .list(settings.upNext.compactMap { s.keyOf[$0] }))]), o: "*")
        s.items["goal"] = LocalItem(v: Canon.obj([("d", .int(Int64(settings.dailyGoalMin))), ("y", .int(Int64(settings.yearlyGoalBooks)))]), o: "*")
        s.items["name"] = LocalItem(v: Canon.obj([("v", .str(settings.profileName))]), o: "*")
        s.items["photo"] = LocalItem(v: Canon.obj([("at", .int(settings.profilePhotoAt))]), o: "*")

        var others: [Int64: Int64] = [:]
        for (k, e) in shadow.items where k.hasPrefix("day/") {
            let parts = k.split(separator: "/")
            guard parts.count == 3, String(parts[2]) != me, let day = Int64(parts[1]), let v = e.v else { continue }
            others[day, default: 0] += value(v, "v")
        }
        for row in library.days() {
            s.dbDays[row.day] = row.ms
            let own = max(row.ms - (others[row.day] ?? 0), 0)
            s.ownDays[row.day] = own
            if own > 0 { s.items["day/\(row.day)/\(me)"] = LocalItem(v: Canon.obj([("v", .int(own))]), o: "*") }
        }
        return s
    }

    private func json(_ v: String?) -> [String: Any]? {
        guard let v, let d = v.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
    }

    private func value(_ v: String, _ key: String) -> Int64 { int64(json(v)?[key]) }

    // MARK: Changes from your other devices

    private func apply(_ changes: [String: SyncEntry], merged: [String: SyncEntry], s: Snapshot, r: SyncRemote) async -> Set<String> {
        var unapplied = Set<String>()
        let order = changes.keys.sorted { a, b in
            func rank(_ k: String) -> Int { k.hasPrefix("shelf/") ? 0 : (k.hasPrefix("show/") ? 1 : 2) }
            return rank(a) < rank(b)
        }
        var queueChanged = false
        for key in order {
            let e = changes[key]!
            let v = json(e.v)
            if key.hasPrefix("b/") {
                applyBook(key, e, v, s)
            } else if key.hasPrefix("mark/") {
                applyMark(key, v, s)
            } else if key.hasPrefix("shelf/") {
                applyShelf(String(key.dropFirst(6)), v)
            } else if key.hasPrefix("shelfbook/") {
                applyShelfBook(key, v, merged, s)
            } else if key == "upnext", let a = v?["v"] as? [String] {
                settings.upNext = a.compactMap { s.books[$0]?.id }
            } else if key == "goal", let v {
                settings.dailyGoalMin = Int(int64(v["d"] ?? 30))
                settings.yearlyGoalBooks = Int(int64(v["y"] ?? 24))
            } else if key == "name", let n = v?["v"] as? String {
                settings.profileName = n
            } else if key == "photo", let v {
                await applyPhoto(int64(v["at"]), r)
            } else if key.hasPrefix("show/") {
                let ok = await applyShow(String(key.dropFirst(5)), v, s)
                if !ok { unapplied.insert(key) }
            } else if key.hasPrefix("ep/") {
                applyEpisode(v)
            } else if key.hasPrefix("epa/"), let v, let ep = episodeFor(v) {
                let a = (v["v"] as? Bool) ?? false
                if ep.archived != a { podcasts.setArchived(ep, a) }
            } else if key == "podq" {
                queueChanged = true
            }
        }
        if queueChanged, let a = json(merged["podq"]?.v)?["v"] as? [String] {
            let ids: [String] = a.compactMap { k in
                guard let cut = k.lastIndex(of: "|") else { return nil }
                let feed = String(k[..<cut]), guid = String(k[k.index(after: cut)...])
                guard let ep = podcasts.episode(podcastID: Ids.podcast(feed), guid: guid), !ep.played else { return nil }
                return ep.id
            }
            podcasts.setQueue(ids)
        }
        applyDays(merged, s)
        try? library.context.save()
        return unapplied
    }

    private func applyBook(_ key: String, _ e: SyncEntry, _ v: [String: Any]?, _ s: Snapshot) {
        guard let v else { return }
        let parts = key.split(separator: "/").map(String.init)
        guard parts.count == 3, let b = s.books[parts[1]] else { return }
        switch parts[2] {
        case "pos": applyPlace(key, b, v, e)
        case "status":
            b.status = Int(int64(v["s"]))
            b.finishedAt = int64(v["f"])
        case "speed": b.speed = min(max(Double(int64(v["v"] ?? 100)) / 100, 0.5), 3.5)
        case "rating": b.rating = Int(int64(v["v"]))
        case "sound":
            b.customEffects = v["ce"] as? Bool ?? false
            b.trimSilence = v["ts"] as? Bool ?? false
            b.voiceBoost = v["vb"] as? Bool ?? false
            b.skipIntroSec = Int(int64(v["si"]))
            b.skipOutroSec = Int(int64(v["so"]))
        default: break
        }
    }

    /// Another device's place. A book you aren't listening to just moves; the one in the player asks first.
    private func applyPlace(_ key: String, _ b: Book, _ v: [String: Any], _ e: SyncEntry) {
        let p = int64(v["p"]), lp = int64(v["lp"]), d = int64(v["d"])
        let local = b.durationMs / 1000
        if !b.isExternal, d > 0, local > 0, abs(d - local) > max(5, local / 200) { return }
        let target = b.durationMs > 0 ? min(max(p, 0), b.durationMs) : p
        if let player, player.loaded?.id == b.id {
            if player.isPlaying { return }
            if abs(target - player.positionMs) < 15_000 { return }
            freeze(key, since: Clock.now)
            prompt = Prompt(bookID: b.id, positionMs: target, deviceName: nameOf(e.d), at: lp, key: key)
            return
        }
        unfreeze(key)
        if prompt?.key == key { prompt = nil }
        b.positionMs = target
        b.lastPlayedAt = lp
    }

    private func applyMark(_ key: String, _ v: [String: Any]?, _ s: Snapshot) {
        guard let created = Int64(key.dropFirst(5)) else { return }
        let existing = (try? library.context.fetch(FetchDescriptor<Bookmark>(predicate: #Predicate { $0.createdAt == created }))) ?? []
        guard let v else {
            for m in existing { library.context.delete(m) }
            return
        }
        guard let bk = v["b"] as? String, let b = s.books[bk] else { return }
        if let m = existing.first(where: { $0.bookID == b.id }) {
            m.positionMs = int64(v["p"])
            m.note = v["n"] as? String ?? ""
            m.chapterTitle = v["c"] as? String ?? ""
        } else {
            library.context.insert(Bookmark(bookID: b.id, positionMs: int64(v["p"]), note: v["n"] as? String ?? "", chapterTitle: v["c"] as? String ?? "", createdAt: created))
        }
    }

    private func applyShelf(_ lower: String, _ v: [String: Any]?) {
        let shelf = library.shelves().first { $0.name.lowercased() == lower }
        if v == nil {
            if let shelf { library.context.delete(shelf) }
        } else if shelf == nil {
            library.context.insert(Shelf(name: (v?["n"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? lower))
        }
    }

    private func applyShelfBook(_ key: String, _ v: [String: Any]?, _ merged: [String: SyncEntry], _ s: Snapshot) {
        let rest = String(key.dropFirst("shelfbook/".count))
        guard let cut = rest.lastIndex(of: "/") else { return }
        let lower = String(rest[..<cut])
        guard let b = s.books[String(rest[rest.index(after: cut)...])] else { return }
        var shelf = library.shelves().first { $0.name.lowercased() == lower }
        if v == nil {
            shelf?.books.removeAll { $0 == b.id }
            return
        }
        if shelf == nil {
            let name = (json(merged["shelf/\(lower)"]?.v)?["n"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? lower
            let sh = Shelf(name: name)
            library.context.insert(sh)
            shelf = sh
        }
        if let shelf, !shelf.books.contains(b.id) { shelf.books.append(b.id) }
    }

    private func applyPhoto(_ at: Int64, _ r: SyncRemote) async {
        if at <= 0 {
            ProfilePhoto.delete()
            settings.profilePhotoAt = 0
            return
        }
        guard let text = try? await r.read("photo.b64"), let data = Data(base64Encoded: text), ProfilePhoto.save(data) else { return }
        settings.profilePhotoAt = at
    }

    private func uploadPhoto(_ r: SyncRemote) async throws {
        guard let data = ProfilePhoto.data(), data.count < 600_000 else { return }
        try await r.write("photo.b64", data.base64EncodedString())
    }

    private func applyShow(_ feed: String, _ v: [String: Any]?, _ s: Snapshot) async -> Bool {
        guard let v else {
            if let p = s.shows[feed] { podcasts.unfollow(p) }
            return true
        }
        var p = s.shows[feed]
        if p == nil, let id = await podcasts.follow(feed) { p = podcasts.podcast(id) }
        guard let p else { return false }
        p.autoDownload = v["ad"] as? Bool ?? false
        p.notify = v["nt"] as? Bool ?? true
        p.speed = min(max(Double(int64(v["sp"] ?? 100)) / 100, 0.5), 3.5)
        p.autoQueue = v["aq"] as? Bool ?? false
        p.autoArchive = v["aa"] as? Bool ?? true
        p.skipIntroSec = Int(int64(v["si"]))
        p.skipOutroSec = Int(int64(v["so"]))
        p.customEffects = v["ce"] as? Bool ?? false
        p.trimSilence = v["ts"] as? Bool ?? false
        p.voiceBoost = v["vb"] as? Bool ?? false
        return true
    }

    private func episodeFor(_ v: [String: Any]) -> Episode? {
        guard let f = v["f"] as? String, let g = v["g"] as? String else { return nil }
        return podcasts.episode(podcastID: Ids.podcast(f), guid: g)
    }

    private func applyEpisode(_ v: [String: Any]?) {
        guard let v, let ep = episodeFor(v) else { return }
        if player?.loaded?.id == "episode:" + ep.id { return }
        ep.positionMs = int64(v["p"])
        ep.played = v["pl"] as? Bool ?? false
        ep.playedAt = int64(v["pa"])
        ep.lastPlayedAt = int64(v["lp"])
        if ep.played { podcasts.removeFromQueue(ep) }
    }

    private func applyDays(_ merged: [String: SyncEntry], _ s: Snapshot) {
        let me = deviceID
        var others: [Int64: Int64] = [:]
        for (k, e) in merged where k.hasPrefix("day/") {
            let parts = k.split(separator: "/")
            guard parts.count == 3, String(parts[2]) != me, let day = Int64(parts[1]), let v = e.v else { continue }
            others[day, default: 0] += value(v, "v")
        }
        for day in Set(others.keys).union(s.ownDays.keys) {
            let total = (s.ownDays[day] ?? 0) + (others[day] ?? 0)
            if total != (s.dbDays[day] ?? 0) { library.setDay(day, ms: total) }
        }
    }

    // MARK: "Go there?"

    private var frozenMap: [String: Int64] {
        get { (d.dictionary(forKey: "syncFrozen") as? [String: NSNumber])?.mapValues(\.int64Value) ?? [:] }
        set { d.set(newValue.mapValues { NSNumber(value: $0) }, forKey: "syncFrozen") }
    }

    private func freeze(_ key: String, since: Int64) {
        if frozenMap[key] == nil { frozenMap[key] = since }
    }

    private func unfreeze(_ key: String) { frozenMap[key] = nil }

    private func frozenKeys(_ s: Snapshot) -> Set<String> {
        var out = Set<String>()
        for (k, since) in frozenMap {
            if (s.items[k]?.hint ?? 0) > since {
                unfreeze(k)
                if prompt?.key == k { prompt = nil }
            } else {
                out.insert(k)
            }
        }
        return out
    }

    /// "Go there" or "Stay here" (staying makes this device's place the newest, so it goes out).
    func answer(go: Bool) {
        guard let p = prompt else { return }
        prompt = nil
        unfreeze(p.key)
        if go {
            if player?.loaded?.id == p.bookID { player?.seek(to: p.positionMs) } else { library.setProgress(p.bookID, positionMs: p.positionMs) }
        } else if let b = library.book(p.bookID) {
            b.lastPlayedAt = Clock.now
            try? library.context.save()
        }
        request("answered", delay: 2)
    }

    // MARK: Listening logs

    private func uid(_ e: ListenEvent) -> String { e.device.isEmpty ? "\(deviceID):\(e.seq)" : e.uid }

    private func signature(_ bookID: String) -> String {
        let all = library.allListenLog(bookID)
        return "\(all.count):\(all.map(\.at).max() ?? 0):\(all.map(\.seq).max() ?? 0)"
    }

    private func syncLogs(_ r: SyncRemote, _ s: Snapshot, _ files: [String: String]) async throws {
        var index = (try? JSONSerialization.jsonObject(with: Data(contentsOf: logIndexURL))) as? [String: [String: Any]] ?? [:]
        let now = Clock.now
        for (bk, b) in s.books where !b.isExternal {
            let name = SyncKeys.logFile(bk)
            let sig = signature(b.id)
            let rec = index[bk]
            let remoteMod = files[name]
            if let rec, rec["sig"] as? String == sig, (rec["mod"] as? String ?? "") == (remoteMod ?? "") { continue }
            if rec == nil, sig.hasPrefix("0:"), remoteMod == nil { continue }
            var localCleared = int64(rec?["cl"])
            if let rec, !(rec["sig"] as? String ?? "0:").hasPrefix("0:"), sig.hasPrefix("0:") { localCleared = now }

            var remoteFile: LogFile?
            if remoteMod != nil { remoteFile = LogFile.parse(try await r.read(name)) }
            let rows = library.allListenLog(b.id)
            let localEntries = rows.map { LogEntry(u: uid($0), d: $0.device.isEmpty ? deviceID : $0.device, at: $0.at, k: $0.kind, p: $0.positionMs,
                                                   to: $0.toMs, s: Int(($0.speed * 100).rounded()), r: $0.reason) }
            let (merged, cleared) = LogMerge.merge(remote: remoteFile?.entries ?? [], remoteCleared: remoteFile?.cleared ?? 0, local: localEntries, localCleared: localCleared)
            let mergedIDs = Set(merged.map(\.u))
            var byUID: [String: ListenEvent] = [:]
            for row in rows { byUID[uid(row)] = row }
            for row in rows where !mergedIDs.contains(uid(row)) { library.context.delete(row) }
            for m in merged {
                if let row = byUID[m.u] {
                    if !row.device.isEmpty, row.at != m.at || row.toMs != m.to {
                        row.at = m.at
                        row.toMs = m.to
                    }
                } else {
                    let e = ListenEvent(bookID: b.id, at: m.at, kind: m.k, positionMs: m.p, toMs: m.to, speed: Double(m.s) / 100, reason: m.r)
                    e.uid = m.u
                    e.device = m.d
                    library.context.insert(e)
                }
            }
            try? library.context.save()
            var mod = remoteMod
            let same = remoteFile != nil && remoteFile!.cleared == cleared && remoteFile!.entries == merged
            if !same, !(remoteFile == nil && merged.isEmpty && cleared == 0) {
                mod = try await r.write(name, LogFile(bookKey: bk, cleared: cleared, entries: merged).toJSON())
            }
            index[bk] = ["sig": signature(b.id), "mod": mod ?? "", "cl": cleared]
        }
        if let data = try? JSONSerialization.data(withJSONObject: index) { try? data.write(to: logIndexURL) }
    }

    // MARK: Simulator checks

    /// Writes a change as if the Fold had made it: a newer place, a bookmark and two log entries.
    func testFakeOtherDevice(bookID: String, positionMs: Int64) async {
        let r = remote()
        guard let b = library.book(bookID), let bk = SyncKeys.book(title: b.title, author: b.author, external: b.isExternal) else { return }
        let now = Clock.now
        let dev = "test-fold"
        var st = SyncState.parse(try? await r.read("state.json"))
        st.items["b/\(bk)/pos"] = SyncEntry(v: Canon.obj([("p", .int(positionMs)), ("lp", .int(now)), ("d", .int(b.durationMs / 1000))]), t: now, d: dev, o: bk)
        st.items["mark/\(now)"] = SyncEntry(v: Canon.obj([("b", .str(bk)), ("p", .int(positionMs - 5000)), ("n", "Saved on the Fold"), ("c", "")]), t: now, d: dev, o: bk)
        st.devices[dev] = ("Fold", now)
        try? await r.write("state.json", st.toJSON())
        let name = SyncKeys.logFile(bk)
        let lf = LogFile.parse(try? await r.read(name))
        var entries = lf?.entries ?? []
        entries.append(LogEntry(u: "\(dev):1", d: dev, at: now - 120_000, k: ListenEvent.play, p: positionMs - 110_000, to: -1, s: 100, r: 0))
        entries.append(LogEntry(u: "\(dev):2", d: dev, at: now - 5_000, k: ListenEvent.pause, p: positionMs, to: -1, s: 100, r: ListenEvent.reasonUnplugged))
        try? await r.write(name, LogFile(bookKey: bk, cleared: lf?.cleared ?? 0, entries: entries.sorted { $0.at > $1.at }).toJSON())
    }
}

/// Your profile photo (512 px JPEG in Ember's own storage; never uploaded except to your own sync folder).
enum ProfilePhoto {
    static var url: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("profile.jpg") }
    static func data() -> Data? { try? Data(contentsOf: url) }
    static func image() -> UIImage? { UIImage(contentsOfFile: url.path) }

    @discardableResult
    static func save(_ data: Data) -> Bool {
        guard let img = UIImage(data: data) else { return false }
        let side = min(img.size.width, img.size.height)
        let crop = CGRect(x: (img.size.width - side) / 2, y: (img.size.height - side) / 2, width: side, height: side)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let out = UIGraphicsImageRenderer(size: CGSize(width: 512, height: 512), format: format).image { _ in
            img.draw(in: CGRect(x: -crop.minX * 512 / side, y: -crop.minY * 512 / side, width: img.size.width * 512 / side, height: img.size.height * 512 / side))
        }
        guard let jpg = out.jpegData(compressionQuality: 0.85) else { return false }
        return (try? jpg.write(to: url, options: .atomic)) != nil
    }

    static func delete() { try? FileManager.default.removeItem(at: url) }
}
