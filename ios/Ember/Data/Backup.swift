import Foundation
import SwiftData

/// Backups in the same JSON format as Ember on Android (format 1), so a backup from the Fold
/// restores here and the other way round. Restoring merges: books are matched by title and author,
/// progress is taken from whichever was played more recently.
@MainActor
enum Backup {

    static func export(_ app: AppModel) -> String {
        let lib = app.library
        var root: [String: Any] = ["app": "Ember", "format": 1, "createdAt": Clock.now]
        let books = lib.books()
        root["books"] = books.map { b -> [String: Any] in
            [
                "id": b.id, "title": b.title, "author": b.author, "narrator": b.narrator, "series": b.series,
                "seriesIndex": b.seriesIndex.map { $0 as Any } ?? NSNull(), "description": b.desc, "source": b.source,
                "durationMs": b.durationMs, "positionMs": b.positionMs, "status": b.status, "rating": b.rating, "speed": b.speed,
                "addedAt": Int64(b.addedAt.timeIntervalSince1970 * 1000), "lastPlayedAt": b.lastPlayedAt, "finishedAt": b.finishedAt,
                "externalApp": b.externalApp, "externalLink": b.externalLink, "userEdited": b.userEdited,
                "customEffects": b.customEffects, "trimSilence": b.trimSilence, "voiceBoost": b.voiceBoost,
                "skipIntroSec": b.skipIntroSec, "skipOutroSec": b.skipOutroSec,
            ]
        }
        root["bookmarks"] = lib.allBookmarks().map { ["bookId": $0.bookID, "positionMs": $0.positionMs, "note": $0.note, "chapterTitle": $0.chapterTitle, "createdAt": $0.createdAt] }
        root["shelves"] = lib.shelves().map { ["name": $0.name, "createdAt": Int64($0.createdAt.timeIntervalSince1970 * 1000), "books": $0.books] as [String: Any] }
        root["listening"] = lib.days().filter { $0.ms > 0 }.map { ["day": $0.day, "ms": $0.ms] }
        let shows = app.podcasts.podcasts()
        let feedOf = Dictionary(shows.map { ($0.id, $0.feedURL) }, uniquingKeysWith: { a, _ in a })
        root["podcasts"] = shows.map { p -> [String: Any] in
            ["feedUrl": p.feedURL, "title": p.title, "autoDownload": p.autoDownload, "notify": p.notify, "speed": p.speed,
             "autoQueue": p.autoQueue, "autoArchive": p.autoArchive, "skipIntroSec": p.skipIntroSec, "skipOutroSec": p.skipOutroSec,
             "customEffects": p.customEffects, "trimSilence": p.trimSilence, "voiceBoost": p.voiceBoost]
        }
        root["episodes"] = app.podcasts.withProgress().compactMap { e -> [String: Any]? in
            guard let feed = feedOf[e.podcastID] else { return nil }
            return ["feedUrl": feed, "guid": e.guid, "positionMs": e.positionMs, "played": e.played, "playedAt": e.playedAt, "lastPlayedAt": e.lastPlayedAt, "archived": e.archived]
        }
        root["queue"] = app.podcasts.queuedEpisodes().compactMap { e -> [String: Any]? in
            guard let feed = feedOf[e.podcastID] else { return nil }
            return ["feedUrl": feed, "guid": e.guid]
        }
        let s = app.settings
        root["settings"] = [
            "skipBackSec": s.skipBackSec, "skipForwardSec": s.skipForwardSec, "autoRewindSec": s.autoRewindSec,
            "dailyGoalMin": s.dailyGoalMin, "yearlyGoalBooks": s.yearlyGoalBooks, "bookTimeLeft": s.bookTimeLeft,
            "seriesAutoPlay": s.seriesAutoPlay, "sleepFade": s.sleepFade, "profileName": s.profileName, "upNext": s.upNext,
        ] as [String: Any]
        if let photo = ProfilePhoto.data(), photo.count < 400_000 { root["profilePhoto"] = photo.base64EncodedString() }
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])) ?? Data()
        app.settings.lastBackupAt = Date()
        return String(decoding: data, as: UTF8.self)
    }

    /// Ember's folder in the Files app (On My iPhone › Ember › Backups).
    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Backups", isDirectory: true)
    }

    /// Once a day (when Ember goes to the background): a backup in Ember's folder, keeping the newest 14.
    static func daily(_ app: AppModel) {
        if let last = app.settings.lastAutoBackupAt, Date().timeIntervalSince(last) < 20 * 3600 { return }
        guard !app.library.books().isEmpty || !app.podcasts.podcasts().isEmpty else { return }
        let fm = FileManager.default
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = "Ember backup \(Date().formatted(.iso8601.year().month().day())).json"
        guard (try? Data(export(app).utf8).write(to: folder.appendingPathComponent(name), options: .atomic)) != nil else { return }
        app.settings.lastAutoBackupAt = Date()
        let all = ((try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("Ember backup ") && $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
        for old in all.dropFirst(14) { try? fm.removeItem(at: old) }
    }

    private static func key(_ title: String, _ author: String) -> String {
        func n(_ s: String) -> String { s.lowercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) } }
        return n(title) + "|" + n(author)
    }

    /// Merges a backup into the library. Returns a sentence about what came back.
    static func restore(_ app: AppModel, text: String) async -> String {
        guard let data = text.data(using: .utf8), let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any], root["app"] as? String == "Ember" else {
            return "That isn't an Ember backup."
        }
        let lib = app.library
        let existing = lib.books()
        var byKey: [String: [Book]] = [:]
        for b in existing { byKey[key(b.title, b.author), default: []].append(b) }
        var idMap: [String: String] = [:]
        var used = Set<String>()
        var matched = 0, waiting = 0
        for o in root["books"] as? [[String: Any]] ?? [] {
            let oldID = o["id"] as? String ?? ""
            let external = o["source"] as? String == Book.sourceExternal
            let target = lib.book(oldID) ?? byKey[key(o["title"] as? String ?? "", o["author"] as? String ?? "")]?.first { !used.contains($0.id) && $0.isExternal == external }
            guard let target else {
                if external {
                    let b = Book(id: oldID, title: o["title"] as? String ?? "")
                    b.author = o["author"] as? String ?? ""
                    b.source = Book.sourceExternal
                    b.durationMs = 100
                    b.positionMs = int64(o["positionMs"])
                    b.status = Int(int64(o["status"]))
                    b.externalApp = o["externalApp"] as? String ?? ""
                    b.externalLink = o["externalLink"] as? String ?? ""
                    b.lastPlayedAt = int64(o["lastPlayedAt"])
                    lib.context.insert(b)
                    idMap[oldID] = oldID
                    matched += 1
                } else {
                    waiting += 1
                }
                continue
            }
            used.insert(target.id)
            idMap[oldID] = target.id
            matched += 1
            let played = int64(o["lastPlayedAt"])
            if played > target.lastPlayedAt || (played == target.lastPlayedAt && target.status == Book.statusNew && target.positionMs == 0) {
                target.positionMs = min(max(int64(o["positionMs"]), 0), max(target.durationMs, 0))
                target.status = Int(int64(o["status"] ?? target.status))
                target.lastPlayedAt = played
                target.finishedAt = int64(o["finishedAt"])
                target.speed = (o["speed"] as? NSNumber)?.doubleValue ?? target.speed
            }
            if target.rating == 0 { target.rating = Int(int64(o["rating"])) }
            if target.skipIntroSec == 0, target.skipOutroSec == 0 {
                target.skipIntroSec = Int(int64(o["skipIntroSec"]))
                target.skipOutroSec = Int(int64(o["skipOutroSec"]))
            }
        }
        var marks = 0
        let have = Set(lib.allBookmarks().map { "\($0.bookID)|\($0.positionMs / 1000)" })
        for m in root["bookmarks"] as? [[String: Any]] ?? [] {
            guard let id = idMap[m["bookId"] as? String ?? ""] else { continue }
            let pos = int64(m["positionMs"])
            if have.contains("\(id)|\(pos / 1000)") { continue }
            lib.context.insert(Bookmark(bookID: id, positionMs: pos, note: m["note"] as? String ?? "", chapterTitle: m["chapterTitle"] as? String ?? "", createdAt: int64(m["createdAt"] ?? Clock.now)))
            marks += 1
        }
        var shelves = Dictionary(lib.shelves().map { ($0.name.lowercased(), $0) }, uniquingKeysWith: { a, _ in a })
        for s in root["shelves"] as? [[String: Any]] ?? [] {
            let name = (s["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { continue }
            let shelf: Shelf
            if let have = shelves[name.lowercased()] {
                shelf = have
            } else {
                shelf = Shelf(name: name)
                lib.context.insert(shelf)
                shelves[name.lowercased()] = shelf
            }
            for old in s["books"] as? [String] ?? [] { if let id = idMap[old], !shelf.books.contains(id) { shelf.books.append(id) } }
        }
        let days = Dictionary(lib.days().map { ($0.day, $0.ms) }, uniquingKeysWith: +)
        for d in root["listening"] as? [[String: Any]] ?? [] {
            let day = int64(d["day"]), ms = int64(d["ms"])
            if ms > (days[day] ?? 0) { lib.setDay(day, ms: ms) }
        }
        if let s = root["settings"] as? [String: Any] {
            if app.settings.profileName.isEmpty, let n = s["profileName"] as? String { app.settings.profileName = n }
            if let g = s["dailyGoalMin"] as? NSNumber { app.settings.dailyGoalMin = g.intValue }
            if let g = s["yearlyGoalBooks"] as? NSNumber { app.settings.yearlyGoalBooks = g.intValue }
            for id in (s["upNext"] as? [String] ?? []).compactMap({ idMap[$0] }) where !app.settings.upNext.contains(id) { app.settings.upNext.append(id) }
        }
        if app.settings.profilePhotoAt == 0, let p = root["profilePhoto"] as? String, let d = Data(base64Encoded: p), ProfilePhoto.save(d) {
            app.settings.profilePhotoAt = Clock.now
        }
        var shows = 0
        for s in root["podcasts"] as? [[String: Any]] ?? [] {
            guard let feed = s["feedUrl"] as? String, let id = await app.podcasts.follow(feed), let p = app.podcasts.podcast(id) else { continue }
            shows += 1
            p.autoDownload = s["autoDownload"] as? Bool ?? p.autoDownload
            p.notify = s["notify"] as? Bool ?? p.notify
            p.speed = (s["speed"] as? NSNumber)?.doubleValue ?? p.speed
            p.autoQueue = s["autoQueue"] as? Bool ?? p.autoQueue
            p.autoArchive = s["autoArchive"] as? Bool ?? p.autoArchive
        }
        for e in root["episodes"] as? [[String: Any]] ?? [] {
            guard let feed = e["feedUrl"] as? String, let guid = e["guid"] as? String,
                  let ep = app.podcasts.episode(podcastID: Ids.podcast(feed), guid: guid) else { continue }
            if int64(e["lastPlayedAt"]) < ep.lastPlayedAt, !(e["played"] as? Bool ?? false) { continue }
            ep.positionMs = int64(e["positionMs"])
            ep.played = e["played"] as? Bool ?? false
            ep.playedAt = int64(e["playedAt"])
            ep.lastPlayedAt = max(ep.lastPlayedAt, int64(e["lastPlayedAt"]))
            ep.archived = e["archived"] as? Bool ?? ep.played
        }
        lib.changed()
        app.podcasts.changed()
        var out = "Restored \(matched) book\(matched == 1 ? "" : "s")"
        if marks > 0 { out += ", \(marks) bookmark\(marks == 1 ? "" : "s")" }
        if shows > 0 { out += ", \(shows) podcast\(shows == 1 ? "" : "s")" }
        out += "."
        if waiting > 0 { out += " \(waiting) more aren't in this library yet (add their folders, then restore again)." }
        return out
    }
}
