import Foundation
import AVFoundation

/// Reads a folder picked in the Files app and finds the books in it (the same rules as Android):
/// a folder of audio files is one book; a folder of M4Bs (or the top level) has one book per file;
/// CD1/CD2/CD3 folders with nothing else beside them become one book.
struct Scanner {

    static let audioExt: Set<String> = ["mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4", "caf", "aiff"]
    static let containerExt: Set<String> = ["m4b", "m4a", "mp4"]
    static let imageExt: Set<String> = ["jpg", "jpeg", "png", "webp", "heic"]

    struct Found {
        /// Unique within the library folder: the book's folder or file path, relative to it.
        var key: String
        var files: [URL]
        var fallbackTitle: String
        var cover: URL?
        var pdf: URL?
        /// Disc folders: each disc's file count, in order.
        var parts: [Int] = []
    }

    struct Scanned {
        var title: String
        var author: String
        var narrator: String
        var durationMs: Int64
        var tracks: [(path: String, title: String, durationMs: Int64)]
        var chapters: [(title: String, startMs: Int64, endMs: Int64)]
        var art: Data?
    }

    /// Files still in iCloud that haven't downloaded yet (Ember asks for them and scans again later).
    var waiting: [URL] = []

    mutating func walk(_ dir: URL, root: URL, depth: Int = 0, into out: inout [Found]) {
        guard depth <= 8 else { return }
        let kids = list(dir)
        let audio = kids.filter { !$0.isDir && Scanner.audioExt.contains($0.ext) }.sorted { Names.naturalLess($0.name, $1.name) }
        let images = kids.filter { !$0.isDir && Scanner.imageExt.contains($0.ext) }
        let pdfs = kids.filter { !$0.isDir && $0.ext == "pdf" }.sorted { Names.naturalLess($0.name, $1.name) }
        let dirs = kids.filter { $0.isDir }.sorted { Names.naturalLess($0.name, $1.name) }
        let folderCover = Scanner.pickCover(images)
        let rel = relative(dir, root)

        if audio.isEmpty, dirs.count >= 2, dirs.allSatisfy({ Names.isDiscName($0.name) }) {
            let discs = dirs.map { ($0, list($0.url)) }
            let usable = discs.allSatisfy { _, k in !k.contains { $0.isDir } && k.contains { !$0.isDir && Scanner.audioExt.contains($0.ext) } }
            if usable {
                let parts = discs.map { _, k in k.filter { !$0.isDir && Scanner.audioExt.contains($0.ext) }.sorted { Names.naturalLess($0.name, $1.name) } }
                let discCover = discs.lazy.compactMap { _, k in Scanner.pickCover(k.filter { Scanner.imageExt.contains($0.ext) }) }.first
                out.append(Found(key: rel, files: parts.flatMap { $0.map(\.url) }, fallbackTitle: dir.lastPathComponent,
                                 cover: (folderCover ?? discCover)?.url, pdf: pdfs.first?.url, parts: parts.map(\.count)))
                return
            }
        }

        if !audio.isEmpty {
            let allContainers = audio.allSatisfy { Scanner.containerExt.contains($0.ext) }
            let loose = (depth == 0 && (!dirs.isEmpty || allContainers)) || (allContainers && audio.count > 1)
            if loose {
                for f in audio {
                    let base = f.base.lowercased()
                    let cover = images.first { $0.base.lowercased() == base } ?? (audio.count == 1 ? folderCover : nil)
                    let pdf = pdfs.first { $0.base.lowercased() == base } ?? (audio.count == 1 ? pdfs.first : nil)
                    out.append(Found(key: relative(f.url, root), files: [f.url], fallbackTitle: f.base, cover: cover?.url, pdf: pdf?.url))
                }
            } else {
                out.append(Found(key: rel, files: audio.map(\.url), fallbackTitle: dir.lastPathComponent, cover: folderCover?.url, pdf: pdfs.first?.url))
            }
        }
        for d in dirs { walk(d.url, root: root, depth: depth + 1, into: &out) }
    }

    struct Entry {
        var url: URL
        var name: String
        var isDir: Bool
        var ext: String { (name as NSString).pathExtension.lowercased() }
        var base: String { (name as NSString).deletingPathExtension }
    }

    private mutating func list(_ dir: URL) -> [Entry] {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey], options: []) else { return [] }
        var out: [Entry] = []
        for u in urls {
            var name = u.lastPathComponent
            // Not downloaded from iCloud yet: ".Name.mp3.icloud".
            if name.hasPrefix("."), name.hasSuffix(".icloud") {
                name = String(name.dropFirst().dropLast(".icloud".count))
                let real = u.deletingLastPathComponent().appendingPathComponent(name)
                if Scanner.audioExt.contains((name as NSString).pathExtension.lowercased()) {
                    try? fm.startDownloadingUbiquitousItem(at: real)
                    waiting.append(real)
                }
                continue
            }
            if name.hasPrefix(".") { continue }
            let isDir = (try? u.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            out.append(Entry(url: u, name: name, isDir: isDir == true))
        }
        return out
    }

    private func relative(_ url: URL, _ root: URL) -> String {
        let r = root.standardizedFileURL.path
        let p = url.standardizedFileURL.path
        return p.hasPrefix(r) ? String(p.dropFirst(r.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/")) : url.lastPathComponent
    }

    static func pickCover(_ images: [Entry]) -> Entry? {
        let preferred = ["cover", "folder", "front", "art", "poster"]
        return images.min { a, b in
            func rank(_ e: Entry) -> Int { preferred.firstIndex { e.base.lowercased().contains($0) } ?? 99 }
            return rank(a) < rank(b)
        }
    }

    // MARK: Reading a book's files

    static func read(_ f: Found, root: URL) async -> Scanned {
        var title: String?
        var author: String?
        var narrator: String?
        var art: Data?
        var tracks: [(String, String, Int64)] = []
        var chapters: [(String, Int64, Int64)] = []
        var offset: Int64 = 0
        for (i, url) in f.files.enumerated() {
            let asset = AVURLAsset(url: url)
            let duration = (try? await asset.load(.duration)).map { Int64(($0.seconds.isFinite ? $0.seconds : 0) * 1000) } ?? 0
            let common = (try? await asset.load(.commonMetadata)) ?? []
            let all = (try? await asset.load(.metadata)) ?? []
            let trackTitle = await string(common, .commonIdentifierTitle)
            if i == 0 {
                title = await string(common, .commonIdentifierAlbumName)
                author = await firstString(all, [.iTunesMetadataAlbumArtist, .id3MetadataBand])
                if author == nil { author = await string(common, .commonIdentifierArtist) }
                if author == nil { author = await string(common, .commonIdentifierAuthor) }
                narrator = await firstString(all, [.iTunesMetadataComposer, .id3MetadataComposer])
                if f.files.count == 1, (title ?? "").isEmpty { title = trackTitle }
                if let item = common.first(where: { $0.identifier == .commonIdentifierArtwork }) {
                    art = try? await item.load(.dataValue)
                }
            }
            let fileTitle = url.deletingPathExtension().lastPathComponent
            tracks.append((relative(url, root), (trackTitle?.isEmpty == false ? trackTitle! : fileTitle), duration))
            if f.files.count == 1 {
                var found: [(String, Int64)] = []
                if containerExt.contains(url.pathExtension.lowercased()) {
                    let groups = (try? await asset.loadChapterMetadataGroups(bestMatchingPreferredLanguages: Locale.preferredLanguages)) ?? []
                    for g in groups {
                        let t = await string(g.items, .commonIdentifierTitle) ?? ""
                        found.append((t, Int64(g.timeRange.start.seconds * 1000)))
                    }
                }
                if found.isEmpty {
                    chapters.append(((title?.isEmpty == false ? title! : f.fallbackTitle), 0, duration))
                } else {
                    for (ci, c) in found.enumerated() {
                        let end = ci + 1 < found.count ? found[ci + 1].1 : duration
                        chapters.append((c.0.isEmpty ? "Chapter \(ci + 1)" : c.0, c.1, max(end, c.1)))
                    }
                }
            } else {
                chapters.append((Names.cleanChapterTitle(tag: trackTitle, fileTitle: fileTitle), offset, offset + duration))
            }
            offset += duration
        }
        var bookTitle = f.fallbackTitle
        if let t = title?.trimmingCharacters(in: .whitespaces), !t.isEmpty { bookTitle = f.parts.isEmpty ? t : Names.stripDisc(t) }
        if !f.parts.isEmpty { renumberAcrossDiscs(&chapters, &tracks, bookTitle: bookTitle) }
        if art == nil, let cover = f.cover { art = try? Data(contentsOf: cover) }
        return Scanned(
            title: bookTitle,
            author: (author ?? "").trimmingCharacters(in: .whitespaces),
            narrator: (narrator ?? "").trimmingCharacters(in: .whitespaces),
            durationMs: offset,
            tracks: tracks.map { (path: $0.0, title: Names.chapter($0.1, bookTitle: bookTitle, index: 0), durationMs: $0.2) },
            chapters: chapters.enumerated().map { (title: Names.chapter($0.element.0, bookTitle: bookTitle, index: $0.offset), startMs: $0.element.1, endMs: $0.element.2) },
            art: art
        )
    }

    private static func relative(_ url: URL, _ root: URL) -> String {
        let r = root.standardizedFileURL.path
        let p = url.standardizedFileURL.path
        return p.hasPrefix(r) ? String(p.dropFirst(r.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/")) : url.lastPathComponent
    }

    private static func string(_ items: [AVMetadataItem], _ id: AVMetadataIdentifier) async -> String? {
        guard let item = items.first(where: { $0.identifier == id }) else { return nil }
        let s = try? await item.load(.stringValue)
        return s?.trimmingCharacters(in: .whitespaces).isEmpty == false ? s : nil
    }

    private static func firstString(_ items: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> String? {
        for id in ids {
            if let s = await string(items, id) { return s }
        }
        return nil
    }

    /// Disc 2's "Track 01" would read "Chapter 1" again: number straight through when file numbers restart.
    private static func renumberAcrossDiscs(_ chapters: inout [(String, Int64, Int64)], _ tracks: inout [(String, String, Int64)], bookTitle: String) {
        func numbers(_ titles: [String]) -> [(String, Int)?] {
            titles.enumerated().map { i, t in
                let c = Names.chapter(t, bookTitle: bookTitle, index: i)
                let parts = c.split(separator: " ")
                guard parts.count == 2, parts[0] == "Chapter" || parts[0] == "Part", let n = Int(parts[1]) else { return nil }
                return (String(parts[0]), n)
            }
        }
        let nums = numbers(chapters.map(\.0))
        let seq = nums.compactMap { $0?.1 }
        if zip(seq, seq.dropFirst()).allSatisfy({ $1 > $0 }) { return }
        for i in chapters.indices { if let n = nums[i] { chapters[i].0 = "\(n.0) \(i + 1)" } }
        let tnums = numbers(tracks.map(\.1))
        for i in tracks.indices { if let n = tnums[i] { tracks[i].1 = "\(n.0) \(i + 1)" } }
    }
}
