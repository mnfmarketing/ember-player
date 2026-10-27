import Foundation
import SwiftData
import Observation

/// The library: books, their files and chapters, bookmarks, the listening log, stats and folders.
@MainActor
@Observable
final class Library {
    let context: ModelContext
    let settings: Settings
    /// Bumped after changes that screens drawing from plain fetches should pick up.
    private(set) var version = 0
    var scanning: String?
    var folderProblems: [String: String] = [:]
    /// Google Drive books (set by the app once sign-in exists).
    @ObservationIgnored var drive: DriveFiles?
    /// Drive books being saved for offline listening: book id → files done / total.
    var saving: [String: (Int, Int)] = [:]

    init(context: ModelContext, settings: Settings) {
        self.context = context
        self.settings = settings
    }

    func changed() {
        try? context.save()
        version &+= 1
    }

    // MARK: Reading

    func books() -> [Book] {
        let d = FetchDescriptor<Book>(sortBy: [SortDescriptor(\.lastPlayedAt, order: .reverse), SortDescriptor(\.addedAt, order: .reverse)])
        return (try? context.fetch(d)) ?? []
    }

    func book(_ id: String) -> Book? {
        var d = FetchDescriptor<Book>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    func tracks(_ bookID: String) -> [Track] {
        let d = FetchDescriptor<Track>(predicate: #Predicate { $0.bookID == bookID }, sortBy: [SortDescriptor(\.idx)])
        return (try? context.fetch(d)) ?? []
    }

    func chapters(_ bookID: String) -> [Chapter] {
        let d = FetchDescriptor<Chapter>(predicate: #Predicate { $0.bookID == bookID }, sortBy: [SortDescriptor(\.idx)])
        return (try? context.fetch(d)) ?? []
    }

    func bookmarks(_ bookID: String) -> [Bookmark] {
        let d = FetchDescriptor<Bookmark>(predicate: #Predicate { $0.bookID == bookID }, sortBy: [SortDescriptor(\.positionMs)])
        return (try? context.fetch(d)) ?? []
    }

    func allBookmarks() -> [Bookmark] { (try? context.fetch(FetchDescriptor<Bookmark>())) ?? [] }

    func folders() -> [LibraryFolder] {
        (try? context.fetch(FetchDescriptor<LibraryFolder>(sortBy: [SortDescriptor(\.name)]))) ?? []
    }

    func shelves() -> [Shelf] { (try? context.fetch(FetchDescriptor<Shelf>(sortBy: [SortDescriptor(\.name)]))) ?? [] }

    func days() -> [DayListening] { (try? context.fetch(FetchDescriptor<DayListening>(sortBy: [SortDescriptor(\.day)]))) ?? [] }

    func listenLog(_ bookID: String, limit: Int = 400) -> [ListenEvent] {
        var d = FetchDescriptor<ListenEvent>(predicate: #Predicate { $0.bookID == bookID }, sortBy: [SortDescriptor(\.at, order: .reverse)])
        d.fetchLimit = limit
        return (try? context.fetch(d)) ?? []
    }

    func allListenLog(_ bookID: String) -> [ListenEvent] {
        let d = FetchDescriptor<ListenEvent>(predicate: #Predicate { $0.bookID == bookID })
        return (try? context.fetch(d)) ?? []
    }

    // MARK: Changing

    func setProgress(_ id: String, positionMs: Int64, playedAt: Int64 = Clock.now, status: Int? = Book.statusInProgress) {
        guard let b = book(id) else { return }
        b.positionMs = positionMs
        b.lastPlayedAt = playedAt
        if let status, b.status != Book.statusFinished || status == Book.statusFinished { b.status = status }
        try? context.save()
    }

    func markFinished(_ id: String) {
        guard let b = book(id) else { return }
        b.status = Book.statusFinished
        if b.finishedAt == 0 { b.finishedAt = Clock.now }
        b.positionMs = b.isExternal ? 100 : b.durationMs
        changed()
    }

    func resetProgress(_ id: String) {
        guard let b = book(id) else { return }
        b.status = Book.statusNew
        b.positionMs = 0
        b.finishedAt = 0
        changed()
    }

    func addBookmark(_ bookID: String, positionMs: Int64, note: String = "", chapterTitle: String = "") {
        context.insert(Bookmark(bookID: bookID, positionMs: positionMs, note: note, chapterTitle: chapterTitle))
        changed()
    }

    func delete(_ bookmark: Bookmark) {
        context.delete(bookmark)
        changed()
    }

    func addListening(_ ms: Int64, day: Int64 = Clock.epochDay()) {
        guard ms > 0 else { return }
        let d = FetchDescriptor<DayListening>(predicate: #Predicate { $0.day == day })
        if let row = try? context.fetch(d).first {
            row.ms += ms
        } else {
            context.insert(DayListening(day: day, ms: ms))
        }
        try? context.save()
    }

    func setDay(_ day: Int64, ms: Int64) {
        let d = FetchDescriptor<DayListening>(predicate: #Predicate { $0.day == day })
        if let row = try? context.fetch(d).first { row.ms = ms } else { context.insert(DayListening(day: day, ms: ms)) }
    }

    func deleteBook(_ id: String) {
        if let b = book(id) {
            Covers.remove(b.coverFile)
            context.delete(b)
        }
        try? context.delete(model: Track.self, where: #Predicate { $0.bookID == id })
        try? context.delete(model: Chapter.self, where: #Predicate { $0.bookID == id })
        try? context.delete(model: Bookmark.self, where: #Predicate { $0.bookID == id })
        try? context.delete(model: ListenEvent.self, where: #Predicate { $0.bookID == id })
        for s in shelves() where s.books.contains(id) { s.books.removeAll { $0 == id } }
        settings.upNext.removeAll { $0 == id }
        changed()
    }

    func clearLog(_ bookID: String) {
        try? context.delete(model: ListenEvent.self, where: #Predicate { $0.bookID == bookID })
        changed()
    }

    // MARK: Folders

    /// Adds a folder picked in the Files app (keeping permission to read it later) and scans it.
    func addFolder(_ url: URL) async {
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            folderProblems["new"] = "Ember couldn't keep access to that folder. Try picking it again."
            return
        }
        let folder = LibraryFolder(name: url.lastPathComponent, bookmarkData: data)
        context.insert(folder)
        changed()
        await scan(folder)
    }

    func removeFolder(_ folder: LibraryFolder) {
        let fid: String? = folder.id
        let d = FetchDescriptor<Book>(predicate: #Predicate { $0.folderID == fid })
        for b in (try? context.fetch(d)) ?? [] {
            if folder.driveID != nil { removeDownload(b.id) }
            deleteBook(b.id)
        }
        context.delete(folder)
        changed()
    }

    /// The folder's location, with read access started (call stop on it when done).
    func open(_ folder: LibraryFolder) -> URL? {
        guard let data = folder.bookmarkData else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { return nil }
        if stale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) { folder.bookmarkData = fresh }
        _ = url.startAccessingSecurityScopedResource()
        return url
    }

    /// Where a book's file is now: the local file, a downloaded Drive copy, or a Drive stream.
    func fileURL(book: Book, track: Track) -> URL? {
        if let d = DriveTrack(track.path) { return d.local ?? d.stream }
        guard let fid = book.folderID, let folder = folders().first(where: { $0.id == fid }), let root = open(folder) else { return nil }
        return root.appendingPathComponent(track.path)
    }

    func scanAll() async {
        for f in folders() where f.id != "demo" { await scan(f) }
    }

    func scan(_ folder: LibraryFolder) async {
        if folder.driveID != nil { return await scanDrive(folder) }
        guard let root = open(folder) else {
            folderProblems[folder.id] = "Ember can't open this folder any more. Remove it and add it again."
            return
        }
        defer { root.stopAccessingSecurityScopedResource() }
        scanning = folder.name
        defer { scanning = nil }
        var scanner = Scanner()
        var found: [Scanner.Found] = []
        scanner.walk(root, root: root, into: &found)
        folderProblems[folder.id] = nil
        var keep = Set<String>()
        for f in found {
            let id = Ids.nameUUID(folder.id + ":" + f.key)
            keep.insert(id)
            if let existing = book(id), tracks(id).count == f.files.count { _ = existing; continue }
            let s = await Scanner.read(f, root: root)
            importBook(id: id, folder: folder, found: f, scanned: s)
        }
        // Books whose files are gone from the folder.
        if !found.isEmpty {
            let fid: String? = folder.id
            let d = FetchDescriptor<Book>(predicate: #Predicate { $0.folderID == fid })
            for b in (try? context.fetch(d)) ?? [] where !keep.contains(b.id) { deleteBook(b.id) }
        }
        folder.lastScanAt = Date()
        changed()
        if !scanner.waiting.isEmpty {
            // Files downloading from iCloud: look again in a little while.
            let fid = folder.id
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(45))
                if let f = self.folders().first(where: { $0.id == fid }) { await self.scan(f) }
            }
        }
    }

    // MARK: Google Drive

    func addDriveFolder(id: String, name: String) async {
        if let have = folders().first(where: { $0.driveID == id }) { return await scan(have) }
        let folder = LibraryFolder(name: name, driveID: id)
        context.insert(folder)
        changed()
        await scan(folder)
    }

    private func scanDrive(_ folder: LibraryFolder) async {
        guard let drive, let rootID = folder.driveID else { return }
        guard drive.connected else {
            folderProblems[folder.id] = "Google Drive needs you to sign in again (Settings → Library folders)."
            return
        }
        scanning = folder.name
        defer { scanning = nil }
        var found: [Scanner.Found] = []
        do {
            try await Scanner.walkDrive(drive, folderID: rootID, name: folder.name, into: &found)
        } catch {
            folderProblems[folder.id] = "Couldn't reach this Drive folder. Check your connection, or that it's still in your Drive."
            return
        }
        folderProblems[folder.id] = nil
        var keep = Set<String>()
        for f in found {
            let id = Ids.nameUUID("drive:" + f.key)
            keep.insert(id)
            if book(id) != nil, tracks(id).count == f.files.count { continue }
            var s = await Scanner.read(f, root: URL(fileURLWithPath: "/"))
            // Tracks point at Drive files ("drive:<id>/<name>").
            for (i, url) in f.files.enumerated() where i < s.tracks.count {
                s.tracks[i].path = "drive:" + (url.host ?? "") + url.path
            }
            if s.art == nil, book(id)?.coverFile == nil, let c = f.cover, let cid = DriveStream.fileID(c) {
                s.art = try? await drive.download(cid)
            }
            importBook(id: id, folder: folder, found: f, scanned: s)
            book(id)?.source = Book.sourceDrive
            changed()
        }
        if !found.isEmpty {
            let fid: String? = folder.id
            let d = FetchDescriptor<Book>(predicate: #Predicate { $0.folderID == fid })
            for b in (try? context.fetch(d)) ?? [] where !keep.contains(b.id) { removeDownload(b.id); deleteBook(b.id) }
        }
        folder.lastScanAt = Date()
        try? context.save()
        changed()
    }

    /// Whether every file of a Drive book is saved on this device (nil: not a Drive book).
    func isDownloaded(_ bookID: String) -> Bool? {
        let ts = tracks(bookID).compactMap { DriveTrack($0.path) }
        guard !ts.isEmpty else { return nil }
        return ts.allSatisfy { $0.local != nil }
    }

    /// Saves a Drive book on this device so it plays without a connection.
    func download(_ bookID: String) async {
        guard let drive, saving[bookID] == nil else { return }
        let ts = tracks(bookID).compactMap { DriveTrack($0.path) }
        saving[bookID] = (0, ts.count)
        defer { saving[bookID] = nil; changed() }
        for (i, t) in ts.enumerated() {
            if t.local == nil {
                do { _ = try await drive.save(t.id, ext: t.ext) } catch {
                    folderProblems["download"] = "Couldn't download all of this book. Check your connection and try again."
                    return
                }
            }
            saving[bookID] = (i + 1, ts.count)
        }
    }

    func removeDownload(_ bookID: String) {
        for t in tracks(bookID).compactMap({ DriveTrack($0.path) }) {
            try? FileManager.default.removeItem(at: DriveFiles.localURL(t.id, ext: t.ext))
        }
        changed()
    }

    private func importBook(id: String, folder: LibraryFolder, found: Scanner.Found, scanned s: Scanner.Scanned) {
        let b = book(id) ?? {
            let nb = Book(id: id, title: s.title)
            context.insert(nb)
            return nb
        }()
        if !b.userEdited {
            b.title = s.title
            b.author = s.author
            b.narrator = s.narrator
        }
        b.source = Book.sourceLocal
        b.folderID = folder.id
        b.path = found.key
        b.durationMs = s.durationMs
        if b.coverFile == nil, let art = s.art, let saved = Covers.save(id: id, data: art) {
            b.coverFile = saved.0
            b.coverColor = saved.1
        }
        try? context.delete(model: Track.self, where: #Predicate { $0.bookID == id })
        try? context.delete(model: Chapter.self, where: #Predicate { $0.bookID == id })
        for (i, t) in s.tracks.enumerated() { context.insert(Track(bookID: id, idx: i, path: t.path, title: t.title, durationMs: t.durationMs)) }
        for (i, c) in s.chapters.enumerated() { context.insert(Chapter(bookID: id, idx: i, title: c.title, startMs: c.startMs, endMs: c.endMs)) }
        try? context.save()
    }

    // MARK: Stats

    struct Stats {
        var todayMs: Int64 = 0
        var streak = 0
        var week: [(Date, Int64)] = []
        var totalMs: Int64 = 0
        var finishedThisYear = 0
        var finishedAllTime = 0
    }

    func stats() -> Stats {
        var s = Stats()
        let all = days()
        let byDay = Dictionary(all.map { ($0.day, $0.ms) }, uniquingKeysWith: +)
        let today = Clock.epochDay()
        s.todayMs = byDay[today] ?? 0
        s.totalMs = all.reduce(0) { $0 + $1.ms }
        let goal = Int64(settings.dailyGoalMin) * 60_000
        var d = today
        if (byDay[d] ?? 0) < goal { d -= 1 }
        while (byDay[d] ?? 0) >= goal, goal > 0 {
            s.streak += 1
            d -= 1
        }
        let weekStart = today - Int64((Calendar.current.component(.weekday, from: Date()) + 5) % 7)
        s.week = (0..<7).map { i in (Clock.date(epochDay: weekStart + Int64(i)), byDay[weekStart + Int64(i)] ?? 0) }
        let year = Calendar.current.component(.year, from: Date())
        for b in books() where b.status == Book.statusFinished {
            s.finishedAllTime += 1
            if b.finishedAt > 0, Calendar.current.component(.year, from: Date(timeIntervalSince1970: Double(b.finishedAt) / 1000)) == year { s.finishedThisYear += 1 }
        }
        return s
    }
}

/// A track stored in Google Drive: "drive:<file id>/<file name>".
struct DriveTrack {
    var id: String
    var name: String
    var ext: String { (name as NSString).pathExtension.lowercased() }

    init?(_ path: String) {
        guard path.hasPrefix("drive:") else { return nil }
        let rest = path.dropFirst(6)
        guard let slash = rest.firstIndex(of: "/") else { return nil }
        id = String(rest[..<slash])
        name = String(rest[rest.index(after: slash)...])
    }

    var local: URL? {
        let u = DriveFiles.localURL(id, ext: ext)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    var stream: URL { DriveFiles.streamURL(.init(id: id, name: name, mimeType: "", size: 0)) }
}
