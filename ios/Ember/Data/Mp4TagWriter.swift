import Foundation
import AVFoundation
import CryptoKit

/// Writes title/author/narrator/description tags and chapters into M4B / M4A files (the same as
/// Ember on Android).
///
/// Safety:
///  - The audio is never moved or rewritten. Only the file's index ("moov" box) changes.
///  - If the index sits at the end of the file it's rewritten in place; otherwise a new index is
///    added at the end and the old one is retired (renamed "free"), so no audio offsets shift.
///  - Before the first edit, enough is saved to restore the original file exactly.
///  - After every write the file is read back; if it doesn't check out, it's put back.
enum Mp4TagWriter {
    struct Tags {
        var title: String
        var author: String
        var narrator: String
        var description: String
    }

    enum Outcome: Equatable {
        case ok
        case failed(String)
    }

    private static let maxMoov: UInt64 = 96 << 20

    // MARK: Boxes

    private final class Box {
        let type: String
        var payload: [UInt8]?
        var children: [Box]?
        /// Version/flags of "full" container boxes like meta.
        let prefix: [UInt8]?

        init(_ type: String, payload: [UInt8]? = nil, children: [Box]? = nil, prefix: [UInt8]? = nil) {
            self.type = type
            self.payload = payload
            self.children = children
            self.prefix = prefix
        }

        func child(_ t: String) -> Box? { children?.first { $0.type == t } }

        func childOrAdd(_ t: String, _ make: () -> Box) -> Box {
            if let c = child(t) { return c }
            let b = make()
            children?.append(b)
            return b
        }
    }

    private struct Unreadable: Error {}

    private static let containers: Set<String> = ["moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "tref", "ilst"]

    private static func typeOf(_ b: [UInt8], _ off: Int) -> String { String(bytes: b[off..<off + 4], encoding: .isoLatin1) ?? "????" }
    private static func typeBytes(_ t: String) -> [UInt8] { Array(t.data(using: .isoLatin1) ?? Data("????".utf8)) }
    private static func u32(_ b: [UInt8], _ o: Int) -> UInt64 { UInt64(b[o]) << 24 | UInt64(b[o + 1]) << 16 | UInt64(b[o + 2]) << 8 | UInt64(b[o + 3]) }
    private static func u64(_ b: [UInt8], _ o: Int) -> UInt64 { u32(b, o) << 32 | u32(b, o + 4) }
    private static func be32(_ v: UInt64) -> [UInt8] { [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }
    private static func be64(_ v: UInt64) -> [UInt8] { be32(v >> 32) + be32(v & 0xFFFF_FFFF) }
    private static func be16(_ v: Int) -> [UInt8] { [UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }

    private static func parse(_ d: [UInt8], _ start: Int, _ end: Int) throws -> [Box] {
        var out: [Box] = []
        var p = start
        while p + 8 <= end {
            var size = u32(d, p)
            let type = typeOf(d, p + 4)
            var hdr = 8
            if size == 1 {
                guard p + 16 <= end else { throw Unreadable() }
                size = u64(d, p + 8)
                hdr = 16
            } else if size == 0 {
                size = UInt64(end - p)
            }
            guard size >= UInt64(hdr), UInt64(p) + size <= UInt64(end) else { throw Unreadable() }
            let cs = p + hdr, ce = p + Int(size)
            if type == "meta" {
                // ISO meta has 4 version/flag bytes before its children; QuickTime's doesn't.
                let isFull = ce - cs >= 12 && u32(d, cs) == 0 && typeOf(d, cs + 8).allSatisfy { $0.isLetter || $0.isNumber }
                if isFull {
                    out.append(Box(type, children: try parse(d, cs + 4, ce), prefix: Array(d[cs..<cs + 4])))
                } else {
                    out.append(Box(type, children: try parse(d, cs, ce)))
                }
            } else if containers.contains(type) {
                out.append(Box(type, children: try parse(d, cs, ce)))
            } else {
                out.append(Box(type, payload: Array(d[cs..<ce])))
            }
            p = ce
        }
        return out
    }

    private static func serialize(_ box: Box) -> [UInt8] {
        var body: [UInt8] = box.prefix ?? []
        if let kids = box.children { for k in kids { body += serialize(k) } } else if let p = box.payload { body += p }
        let size = UInt64(body.count) + 8
        if size > 0xFFFF_FFFF { return be32(1) + typeBytes(box.type) + be64(UInt64(body.count) + 16) + body }
        return be32(size) + typeBytes(box.type) + body
    }

    // MARK: Edits

    private static func textItem(_ type: String, _ value: String) -> Box {
        let text = Array(value.utf8)
        return Box(type, payload: be32(UInt64(16 + text.count)) + typeBytes("data") + be32(1) + be32(0) + text)
    }

    /// The iTunes-style tag list ("mdir" metadata). Files written by Apple's own encoders may carry a
    /// QuickTime-style "mdta" metadata box instead, which readers don't take iTunes tags from, so an
    /// iTunes one is added in front of it.
    private static func ilst(_ moov: Box) -> Box {
        let udta = moov.childOrAdd("udta") { Box("udta", children: []) }
        func handler(_ meta: Box) -> String? {
            guard let p = meta.child("hdlr")?.payload, p.count >= 12 else { return nil }
            return String(bytes: p[8..<12], encoding: .isoLatin1)
        }
        let meta: Box
        if let m = udta.children?.first(where: { $0.type == "meta" && handler($0) == "mdir" }) {
            meta = m
        } else {
            let hdlr = be32(0) + be32(0) + typeBytes("mdir") + typeBytes("appl") + be32(0) + be32(0) + [0]
            meta = Box("meta", children: [Box("hdlr", payload: hdlr)], prefix: [0, 0, 0, 0])
            udta.children?.insert(meta, at: 0)
        }
        return meta.childOrAdd("ilst") { Box("ilst", children: []) }
    }

    /// The title tags as written in the file (for checking a write).
    static func readTitle(_ url: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        guard let size = try? h.seekToEnd(), let tops = try? topLevel(h, size: size), let top = tops.first(where: { $0.type == "moov" }),
              top.size <= maxMoov, let bytes = try? read(h, top.offset, Int(top.size)) else { return nil }
        let hdr = u32(bytes, 0) == 1 ? 16 : 8
        guard let kids = try? parse(bytes, hdr, bytes.count), let udta = Box("moov", children: kids).child("udta") else { return nil }
        for meta in udta.children ?? [] where meta.type == "meta" {
            for item in meta.child("ilst")?.children ?? [] where item.type == "\u{A9}nam" || item.type == "\u{A9}alb" {
                if let p = item.payload, p.count > 16 { return String(decoding: p[16...], as: UTF8.self) }
            }
        }
        return nil
    }

    private static func applyTags(_ moov: Box, _ t: Tags) {
        let list = ilst(moov)
        func set(_ type: String, _ v: String) {
            list.children?.removeAll { $0.type == type }
            if !v.trimmingCharacters(in: .whitespaces).isEmpty { list.children?.append(textItem(type, v)) }
        }
        set("\u{A9}nam", t.title)
        set("\u{A9}alb", t.title)
        set("\u{A9}ART", t.author)
        set("aART", t.author)
        set("\u{A9}wrt", t.narrator)
        set("desc", String(t.description.prefix(255)))
        set("ldes", t.description)
    }

    private static func applyCover(_ moov: Box, _ image: [UInt8]) {
        let list = ilst(moov)
        list.children?.removeAll { $0.type == "covr" }
        let png = image.count > 4 && image[0] == 0x89 && image[1] == 0x50
        list.children?.append(Box("covr", payload: be32(UInt64(16 + image.count)) + typeBytes("data") + be32(png ? 14 : 13) + be32(0) + image))
    }

    private static func chpl(_ chapters: [(Int64, String)]) -> [UInt8] {
        let list = chapters.prefix(255)
        var o: [UInt8] = [1, 0, 0, 0, 0, 0, 0, 0, UInt8(list.count)]
        for (start, title) in list {
            o += be64(UInt64(max(start, 0)) * 10_000)
            let t = Array(Array(title.utf8).prefix(255))
            o.append(UInt8(t.count))
            o += t
        }
        return o
    }

    private struct ChapterTrack {
        let stbl: Box
        let mdhd: Box
        let timescale: UInt64
    }

    private static func chapterTrack(_ moov: Box) -> ChapterTrack? {
        let traks = moov.children?.filter { $0.type == "trak" } ?? []
        var chapID: UInt64?
        for t in traks {
            if let c = t.child("tref")?.child("chap")?.payload, c.count >= 4 { chapID = u32(c, 0) }
        }
        guard let id = chapID else { return nil }
        for t in traks {
            guard let tkhd = t.child("tkhd")?.payload, tkhd.count >= 24 else { continue }
            let tid = u32(tkhd, tkhd[0] == 1 ? 20 : 12)
            guard tid == id, let mdia = t.child("mdia"), let mdhd = mdia.child("mdhd"), let p = mdhd.payload, p.count >= 24,
                  let stbl = mdia.child("minf")?.child("stbl") else { continue }
            return ChapterTrack(stbl: stbl, mdhd: mdhd, timescale: u32(p, p[0] == 1 ? 20 : 12))
        }
        return nil
    }

    /// QuickTime chapter text samples: 2-byte length, UTF-8 text, then an "encd" marker.
    private static func samples(_ chapters: [(Int64, String)]) -> [[UInt8]] {
        chapters.map { _, title in
            let t = Array(Array(title.utf8).prefix(65_000))
            return be16(t.count) + t + be32(12) + typeBytes("encd") + be32(0x0000_0100)
        }
    }

    private static func rebuild(_ ct: ChapterTrack, _ chapters: [(Int64, String)], totalMs: Int64, _ smp: [[UInt8]], dataOffset: UInt64) {
        let ts = max(ct.timescale, 1)
        let starts = chapters.map { UInt64(max($0.0, 0)) * ts / 1000 }
        let end = UInt64(max(totalMs, 0)) * ts / 1000
        let deltas = starts.enumerated().map { i, s in max((i + 1 < starts.count ? starts[i + 1] : end) &- s, 1) }
        ct.stbl.children?.removeAll { ["stts", "stsz", "stsc", "stco", "co64", "stss", "sdtp", "ctts", "stz2"].contains($0.type) }
        var stts = be32(0) + be32(UInt64(deltas.count))
        for d in deltas { stts += be32(1) + be32(min(d, 0xFFFF_FFFF)) }
        ct.stbl.children?.append(Box("stts", payload: stts))
        var stsz = be32(0) + be32(0) + be32(UInt64(smp.count))
        for s in smp { stsz += be32(UInt64(s.count)) }
        ct.stbl.children?.append(Box("stsz", payload: stsz))
        ct.stbl.children?.append(Box("stsc", payload: be32(0) + be32(1) + be32(1) + be32(UInt64(smp.count)) + be32(1)))
        if dataOffset > 0xFFFF_FFFF {
            ct.stbl.children?.append(Box("co64", payload: be32(0) + be32(1) + be64(dataOffset)))
        } else {
            ct.stbl.children?.append(Box("stco", payload: be32(0) + be32(1) + be32(dataOffset)))
        }
        // The chapter track's length matches the chapters'.
        guard var p = ct.mdhd.payload else { return }
        let total = deltas.reduce(0, +)
        if p[0] == 1, p.count >= 32 {
            p.replaceSubrange(24..<32, with: be64(total))
        } else if p.count >= 20 {
            p.replaceSubrange(16..<20, with: be32(min(total, 0xFFFF_FFFF)))
        }
        ct.mdhd.payload = p
    }

    // MARK: Files

    private struct Top {
        let type: String
        let offset: UInt64
        let size: UInt64
    }

    private static func read(_ h: FileHandle, _ at: UInt64, _ n: Int) throws -> [UInt8] {
        try h.seek(toOffset: at)
        return Array(try h.read(upToCount: n) ?? Data())
    }

    private static func write(_ h: FileHandle, _ at: UInt64, _ bytes: [UInt8]) throws {
        try h.seek(toOffset: at)
        try h.write(contentsOf: Data(bytes))
    }

    private static func topLevel(_ h: FileHandle, size: UInt64) throws -> [Top] {
        var out: [Top] = []
        var pos: UInt64 = 0
        while pos + 8 <= size {
            let hdr = try read(h, pos, 16)
            guard hdr.count >= 8 else { break }
            var s = u32(hdr, 0)
            let t = typeOf(hdr, 4)
            if s == 1, hdr.count >= 16 { s = u64(hdr, 8) } else if s == 0 { s = size - pos }
            guard s >= 8 else { break }
            out.append(Top(type: t, offset: pos, size: s))
            pos += s
        }
        return out
    }

    private static func backupDir() -> URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TagBackups", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    private static func backupKey(_ url: URL) -> String {
        Insecure.SHA1.hash(data: Data(url.standardizedFileURL.path.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func hasOriginal(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: backupDir().appendingPathComponent(backupKey(url) + ".json").path)
    }

    static func canWrite(_ url: URL) -> Bool {
        url.isFileURL && ["m4b", "m4a", "mp4", "aac"].contains(url.pathExtension.lowercased())
    }

    /// Writes tags and/or chapters (start ms, title) into the file. totalMs sizes the last chapter.
    static func write(_ url: URL, tags: Tags?, chapters: [(Int64, String)]?, totalMs: Int64, cover: Data? = nil) async -> Outcome {
        guard canWrite(url) else { return .failed("Only M4B and M4A files can be changed.") }
        guard let h = try? FileHandle(forUpdating: url) else { return .failed("Ember doesn't have permission to change this file.") }
        defer { try? h.close() }
        do {
            let fileSize = try h.seekToEnd()
            let tops = try topLevel(h, size: fileSize)
            guard let moovTop = tops.first(where: { $0.type == "moov" }) else { return .failed("This doesn't look like an M4B/M4A file.") }
            guard moovTop.size <= maxMoov else { return .failed("This file's index is unusually large, so Ember left it alone.") }
            let tailRewritable = tops.filter { $0.offset > moovTop.offset }.allSatisfy { $0.type == "free" || $0.type == "skip" }
            let moovBytes = try read(h, moovTop.offset, Int(moovTop.size))
            let hdr = u32(moovBytes, 0) == 1 ? 16 : 8
            let moov: Box
            do { moov = Box("moov", children: try parse(moovBytes, hdr, moovBytes.count)) } catch { return .failed("Ember couldn't read this file's structure.") }
            if let tags { applyTags(moov, tags) }
            if let cover { applyCover(moov, Array(cover)) }
            let ct = chapters != nil ? chapterTrack(moov) : nil
            let smp = (chapters != nil && ct != nil) ? samples(chapters!) : []
            if let chapters {
                let udta = moov.childOrAdd("udta") { Box("udta", children: []) }
                udta.children?.removeAll { $0.type == "chpl" }
                udta.children?.append(Box("chpl", payload: chpl(chapters)))
            }
            let writePos = tailRewritable ? moovTop.offset : fileSize
            let sampleBytes = smp.flatMap { $0 }
            if let ct, let chapters {
                // [moov][free: chapter text]. The text's offset depends on moov's size, which
                // depends on whether a 32- or 64-bit offset table is needed.
                var co64 = false
                for _ in 0..<2 {
                    rebuild(ct, chapters, totalMs: totalMs, smp, dataOffset: co64 ? 0x1_0000_0000 : 0)
                    let off = writePos + UInt64(serialize(moov).count) + 8
                    co64 = off > 0xFFFF_FFFF
                    rebuild(ct, chapters, totalMs: totalMs, smp, dataOffset: off)
                }
            }
            let newMoov = serialize(moov)
            let freeBox: [UInt8] = sampleBytes.isEmpty ? [] : be32(UInt64(8 + sampleBytes.count)) + typeBytes("free") + sampleBytes

            // Keep what's needed to restore the original, once per file.
            let key = backupKey(url)
            let meta = backupDir().appendingPathComponent(key + ".json"), bin = backupDir().appendingPathComponent(key + ".bin")
            let undoTail: [UInt8]? = try tailRewritable ? read(h, moovTop.offset, Int(fileSize - moovTop.offset)) : nil
            if !FileManager.default.fileExists(atPath: meta.path) {
                if let undoTail { try Data(undoTail).write(to: bin, options: .atomic) }
                let info: [String: Any] = ["size": fileSize, "moovOffset": moovTop.offset, "tail": tailRewritable]
                try JSONSerialization.data(withJSONObject: info).write(to: meta, options: .atomic)
            }

            do {
                if tailRewritable {
                    try write(h, writePos, newMoov)
                    if !freeBox.isEmpty { try write(h, writePos + UInt64(newMoov.count), freeBox) }
                    try h.truncate(atOffset: writePos + UInt64(newMoov.count + freeBox.count))
                } else {
                    // Add the new index labelled "free", then swap labels so the file is valid at every step.
                    var staged = newMoov
                    staged.replaceSubrange(4..<8, with: typeBytes("free"))
                    try write(h, writePos, staged)
                    if !freeBox.isEmpty { try write(h, writePos + UInt64(newMoov.count), freeBox) }
                    try h.synchronize()
                    try write(h, writePos + 4, typeBytes("moov"))
                    try h.synchronize()
                    try write(h, moovTop.offset + 4, typeBytes("free"))
                }
                try h.synchronize()
            } catch {
                rollback(h, moovTop, fileSize, undoTail)
                return .failed("Writing failed, so the file was left as it was.")
            }

            if !(await verify(url, tags: tags, chapters: chapters)) {
                rollback(h, moovTop, fileSize, undoTail)
                return .failed("The saved file didn't check out, so Ember put it back the way it was.")
            }
            return .ok
        } catch {
            return .failed("This file couldn't be changed. Nothing was saved to it.")
        }
    }

    private static func rollback(_ h: FileHandle, _ moovTop: Top, _ oldSize: UInt64, _ undoTail: [UInt8]?) {
        if let undoTail { try? write(h, moovTop.offset, undoTail) } else { try? write(h, moovTop.offset + 4, typeBytes("moov")) }
        try? h.truncate(atOffset: oldSize)
        try? h.synchronize()
    }

    /// Reads the file back: still playable, the title in place, and the chapters as written.
    private static func verify(_ url: URL, tags: Tags?, chapters: [(Int64, String)]?) async -> Bool {
        let asset = AVURLAsset(url: url)
        guard let d = try? await asset.load(.duration), d.seconds.isFinite, d.seconds > 0 else { return false }
        if let tags, !tags.title.trimmingCharacters(in: .whitespaces).isEmpty {
            let common = (try? await asset.load(.commonMetadata)) ?? []
            var found = false
            for item in common where item.identifier == .commonIdentifierAlbumName || item.identifier == .commonIdentifierTitle {
                if let s = try? await item.load(.stringValue), s.trimmingCharacters(in: .whitespaces) == tags.title.trimmingCharacters(in: .whitespaces) { found = true }
            }
            if !found, readTitle(url)?.trimmingCharacters(in: .whitespaces) != tags.title.trimmingCharacters(in: .whitespaces) { return false }
        }
        if let chapters {
            guard let read = readChpl(url) else { return false }
            let want = Array(chapters.prefix(255))
            guard read.count >= want.count else { return false }
            for (r, w) in zip(read, want) where r.1.trimmingCharacters(in: .whitespaces) != w.1.trimmingCharacters(in: .whitespaces) || abs(r.0 - w.0) > 50 {
                return false
            }
        }
        return true
    }

    /// The Nero chapter list ("chpl") in a file: start ms and title.
    static func readChpl(_ url: URL) -> [(Int64, String)]? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        guard let size = try? h.seekToEnd(), let tops = try? topLevel(h, size: size), let top = tops.first(where: { $0.type == "moov" }),
              top.size <= maxMoov, let bytes = try? read(h, top.offset, Int(top.size)) else { return nil }
        let hdr = u32(bytes, 0) == 1 ? 16 : 8
        guard let kids = try? parse(bytes, hdr, bytes.count), let p = Box("moov", children: kids).child("udta")?.child("chpl")?.payload, p.count >= 9 else { return nil }
        var out: [(Int64, String)] = []
        let n = Int(p[8])
        var i = 9
        for _ in 0..<n {
            guard i + 9 <= p.count else { break }
            let start = Int64(u64(p, i) / 10_000)
            let len = Int(p[i + 8])
            guard i + 9 + len <= p.count else { break }
            out.append((start, String(decoding: p[(i + 9)..<(i + 9 + len)], as: UTF8.self)))
            i += 9 + len
        }
        return out
    }

    /// Puts the file back exactly as it was before Ember's first edit.
    static func restoreOriginal(_ url: URL) -> Outcome {
        let key = backupKey(url)
        let meta = backupDir().appendingPathComponent(key + ".json"), bin = backupDir().appendingPathComponent(key + ".bin")
        guard let d = try? Data(contentsOf: meta), let info = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else {
            return .failed("There's no saved original for this file.")
        }
        guard let h = try? FileHandle(forUpdating: url) else { return .failed("Ember doesn't have permission to change this file.") }
        defer { try? h.close() }
        let size = (info["size"] as? NSNumber)?.uint64Value ?? 0
        let moovOffset = (info["moovOffset"] as? NSNumber)?.uint64Value ?? 0
        do {
            if info["tail"] as? Bool == true {
                guard let tail = try? Data(contentsOf: bin) else { return .failed("The saved original is missing.") }
                try write(h, moovOffset, Array(tail))
            } else {
                try write(h, moovOffset + 4, typeBytes("moov"))
            }
            try h.truncate(atOffset: size)
            try h.synchronize()
        } catch {
            return .failed("Restoring failed.")
        }
        try? FileManager.default.removeItem(at: meta)
        try? FileManager.default.removeItem(at: bin)
        return .ok
    }
}
