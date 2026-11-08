import Foundation

/// Reads chapter markers from M4B / M4A / MP4 files the way Ember on Android does: Nero chapters
/// (udta/chpl) and QuickTime chapter text tracks (tref/chap). AVFoundation only knows the second
/// kind, so books with Nero chapters looked like one long chapter. Works on local files and on
/// Google Drive files (reading just the parts it needs).
enum Mp4Chapters {
    struct Raw: Equatable {
        let startMs: Int64
        let title: String
    }

    /// Random-access bytes.
    protocol Source {
        var size: Int64 { get }
        func read(_ at: Int64, _ count: Int) async -> [UInt8]
    }

    final class FileSource: Source {
        private let h: FileHandle
        let size: Int64

        init?(_ url: URL) {
            guard let h = try? FileHandle(forReadingFrom: url), let s = try? h.seekToEnd() else { return nil }
            self.h = h
            size = Int64(s)
        }

        deinit { try? h.close() }

        func read(_ at: Int64, _ count: Int) async -> [UInt8] {
            guard at >= 0, count > 0, (try? h.seek(toOffset: UInt64(at))) != nil else { return [] }
            return Array((try? h.read(upToCount: count)) ?? Data())
        }
    }

    /// A Drive file, read with range requests in 256 KB blocks (kept, since the index is read in pieces).
    final class DriveSource: Source, @unchecked Sendable {
        private let id: String
        private let token: String
        private(set) var size: Int64 = 0
        private var blocks: [Int64: [UInt8]] = [:]
        private static let block: Int64 = 256 << 10

        private init(id: String, token: String) {
            self.id = id
            self.token = token
        }

        static func open(id: String) async -> DriveSource? {
            guard let t = await DriveStream.shared.token?() else { return nil }
            let s = DriveSource(id: id, token: t)
            guard let first = await s.fetch(0) else { return nil }
            s.blocks[0] = first
            return s.size > 0 ? s : nil
        }

        private func fetch(_ n: Int64) async -> [UInt8]? {
            let from = n * Self.block
            var r = URLRequest(url: DriveFiles.mediaURL(id))
            r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            r.setValue("bytes=\(from)-\(from + Self.block - 1)", forHTTPHeaderField: "Range")
            guard let (data, resp) = try? await URLSession.shared.data(for: r), let http = resp as? HTTPURLResponse else { return nil }
            if http.statusCode == 206, let cr = http.value(forHTTPHeaderField: "Content-Range"), let slash = cr.lastIndex(of: "/") {
                size = Int64(cr[cr.index(after: slash)...]) ?? size
                return Array(data)
            }
            if http.statusCode == 200 {
                size = Int64(data.count)
                let end = min(Int(from + Self.block), data.count)
                return from < Int64(data.count) ? Array(data[Int(from)..<end]) : []
            }
            return nil
        }

        func read(_ at: Int64, _ count: Int) async -> [UInt8] {
            guard at >= 0, count > 0, at < size else { return [] }
            var out: [UInt8] = []
            out.reserveCapacity(count)
            var pos = at
            let end = min(at + Int64(count), size)
            while pos < end {
                let n = pos / Self.block
                if blocks[n] == nil { blocks[n] = await fetch(n) ?? [] }
                guard let b = blocks[n], !b.isEmpty else { break }
                let inBlock = Int(pos - n * Self.block)
                guard inBlock < b.count else { break }
                let take = min(b.count - inBlock, Int(end - pos))
                out += b[inBlock..<(inBlock + take)]
                pos += Int64(take)
            }
            return out
        }
    }

    private static let maxMoov: Int64 = 64 << 20

    /// Chapters in a local or Drive file (empty if none, or not an MP4 file).
    static func read(_ url: URL) async -> [Raw] {
        if url.isFileURL {
            guard let s = FileSource(url) else { return [] }
            return await parse(s)
        }
        if let id = DriveStream.fileID(url) {
            let local = DriveFiles.localURL(id, ext: url.pathExtension.lowercased())
            if FileManager.default.fileExists(atPath: local.path), let s = FileSource(local) { return await parse(s) }
            guard let s = await DriveSource.open(id: id) else { return [] }
            return await parse(s)
        }
        return []
    }

    static func parse(_ src: Source) async -> [Raw] {
        let size = src.size
        var pos: Int64 = 0
        var moov: [UInt8]?
        while pos + 8 <= size {
            let h = await src.read(pos, 16)
            guard h.count >= 8 else { break }
            var boxSize = Int64(u32(h, 0))
            let type = typeOf(h, 4)
            var hdr: Int64 = 8
            if boxSize == 1 {
                guard h.count >= 16 else { break }
                boxSize = Int64(bitPattern: u64(h, 8))
                hdr = 16
            } else if boxSize == 0 {
                boxSize = size - pos
            }
            guard boxSize >= hdr else { break }
            if type == "moov" {
                let content = boxSize - hdr
                guard content > 0, content <= maxMoov else { return [] }
                let m = await src.read(pos + hdr, Int(content))
                guard Int64(m.count) == content else { return [] }
                moov = m
                break
            }
            pos += boxSize
        }
        guard let m = moov else { return [] }
        let all = 0..<m.count
        let nero = find(m, all, ["udta", "chpl"]).map { chpl(m, $0) } ?? []
        let qt = await quickTime(m, src)
        if qt.count > 1 { return qt }
        if nero.count > 1 { return nero }
        return qt.isEmpty ? nero : qt
    }

    // MARK: Boxes (as ranges in the moov bytes)

    private static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        guard o + 4 <= b.count else { return 0 }
        return UInt32(b[o]) << 24 | UInt32(b[o + 1]) << 16 | UInt32(b[o + 2]) << 8 | UInt32(b[o + 3])
    }

    private static func u64(_ b: [UInt8], _ o: Int) -> UInt64 { UInt64(u32(b, o)) << 32 | UInt64(u32(b, o + 4)) }
    private static func u16(_ b: [UInt8], _ o: Int) -> Int { o + 2 <= b.count ? Int(b[o]) << 8 | Int(b[o + 1]) : 0 }
    private static func typeOf(_ b: [UInt8], _ o: Int) -> String {
        guard o + 4 <= b.count else { return "" }
        return String(bytes: b[o..<o + 4], encoding: .isoLatin1) ?? ""
    }

    private static func boxes(_ b: [UInt8], _ r: Range<Int>) -> [(String, Range<Int>)] {
        var out: [(String, Range<Int>)] = []
        var p = r.lowerBound
        while p + 8 <= r.upperBound {
            var size = Int(u32(b, p))
            let type = typeOf(b, p + 4)
            var hdr = 8
            if size == 1 {
                guard p + 16 <= r.upperBound else { break }
                size = Int(clamping: u64(b, p + 8))
                hdr = 16
            } else if size == 0 {
                size = r.upperBound - p
            }
            guard size >= hdr, p + size <= r.upperBound else { break }
            out.append((type, (p + hdr)..<(p + size)))
            p += size
        }
        return out
    }

    private static func find(_ b: [UInt8], _ r: Range<Int>, _ path: [String]) -> Range<Int>? {
        var cur = r
        for name in path {
            guard let next = boxes(b, cur).first(where: { $0.0 == name })?.1 else { return nil }
            cur = next
        }
        return cur
    }

    private static func chpl(_ b: [UInt8], _ r: Range<Int>) -> [Raw] {
        guard r.count >= 5 else { return [] }
        var p = r.lowerBound
        let version = b[p]
        p += 4
        if version != 0 { p += 4 }
        guard p < r.upperBound else { return [] }
        let count = Int(b[p])
        p += 1
        var out: [Raw] = []
        for i in 0..<count {
            guard p + 9 <= r.upperBound else { break }
            let start = Int64(bitPattern: u64(b, p)) / 10_000
            let len = Int(b[p + 8])
            p += 9
            guard p + len <= r.upperBound else { break }
            let t = String(decoding: b[p..<(p + len)], as: UTF8.self).trimmingCharacters(in: .whitespaces)
            out.append(Raw(startMs: start, title: t.isEmpty ? "Chapter \(i + 1)" : t))
            p += len
        }
        return out
    }

    private static func fullBoxField(_ b: [UInt8], _ r: Range<Int>) -> UInt32 {
        let version = b[r.lowerBound]
        return u32(b, r.lowerBound + 4 + (version == 1 ? 16 : 8))
    }

    private static func quickTime(_ m: [UInt8], _ src: Source) async -> [Raw] {
        let all = 0..<m.count
        var chapID: UInt32?
        var byID: [UInt32: Range<Int>] = [:]
        for (type, t) in boxes(m, all) where type == "trak" {
            let kids = boxes(m, t)
            guard let tkhd = kids.first(where: { $0.0 == "tkhd" })?.1, tkhd.count >= 24 else { continue }
            byID[fullBoxField(m, tkhd)] = t
            if chapID == nil, let tref = kids.first(where: { $0.0 == "tref" })?.1,
               let chap = boxes(m, tref).first(where: { $0.0 == "chap" })?.1, chap.count >= 4 {
                chapID = u32(m, chap.lowerBound)
            }
        }
        guard let id = chapID, let trak = byID[id], let mdia = find(m, trak, ["mdia"]), let mdhd = find(m, mdia, ["mdhd"]), mdhd.count >= 24,
              let stbl = find(m, mdia, ["minf", "stbl"]) else { return [] }
        let timescale = Int64(fullBoxField(m, mdhd))
        guard timescale > 0 else { return [] }
        var kids: [String: Range<Int>] = [:]
        for (t, r) in boxes(m, stbl) where kids[t] == nil { kids[t] = r }
        guard let stts = kids["stts"], let stsz = kids["stsz"], let stsc = kids["stsc"] else { return [] }
        let wide = kids["stco"] == nil
        guard let co = kids["stco"] ?? kids["co64"] else { return [] }

        var times: [Int64] = []
        var acc: Int64 = 0
        let entries = Int(u32(m, stts.lowerBound + 4))
        var p = stts.lowerBound + 8
        outer: for _ in 0..<entries {
            guard p + 8 <= stts.upperBound else { break }
            let n = Int(u32(m, p)), delta = Int64(u32(m, p + 4))
            p += 8
            for _ in 0..<n {
                times.append(acc)
                acc += delta
                if times.count > 5000 { break outer }
            }
        }

        let fixed = Int(u32(m, stsz.lowerBound + 4))
        let sampleCount = min(Int(u32(m, stsz.lowerBound + 8)), 5000)
        var sizes: [Int] = []
        for i in 0..<sampleCount {
            let o = stsz.lowerBound + 12 + i * 4
            sizes.append(fixed != 0 ? fixed : (o + 4 <= stsz.upperBound ? Int(u32(m, o)) : 0))
        }

        let nOffsets = Int(u32(m, co.lowerBound + 4))
        var chunkOffsets: [Int64] = []
        for i in 0..<min(nOffsets, 100_000) {
            let o = co.lowerBound + 8 + i * (wide ? 8 : 4)
            guard o + (wide ? 8 : 4) <= co.upperBound else { break }
            chunkOffsets.append(wide ? Int64(bitPattern: u64(m, o)) : Int64(u32(m, o)))
        }

        let stscEntries = Int(u32(m, stsc.lowerBound + 4))
        var first: [Int] = [], per: [Int] = []
        for i in 0..<stscEntries {
            let o = stsc.lowerBound + 8 + i * 12
            guard o + 12 <= stsc.upperBound else { break }
            first.append(Int(u32(m, o)))
            per.append(Int(u32(m, o + 4)))
        }
        var offsets = [Int64](repeating: 0, count: sampleCount)
        var sample = 0
        for i in first.indices {
            let endChunk = i + 1 < first.count ? first[i + 1] - 1 : chunkOffsets.count
            var chunk = first[i]
            while chunk <= endChunk, chunk >= 1, chunk - 1 < chunkOffsets.count, sample < sampleCount {
                var off = chunkOffsets[chunk - 1]
                for _ in 0..<per[i] where sample < sampleCount {
                    offsets[sample] = off
                    off += Int64(sizes[sample])
                    sample += 1
                }
                chunk += 1
            }
        }

        var out: [Raw] = []
        for i in 0..<min(sampleCount, times.count) {
            var title = ""
            if sizes[i] >= 2 {
                let bytes = await src.read(offsets[i], min(sizes[i], 1024))
                if bytes.count >= 2 {
                    let len = min(u16(bytes, 0), bytes.count - 2)
                    let t = Array(bytes[2..<(2 + len)])
                    if t.count >= 2, t[0] == 0xFE, t[1] == 0xFF {
                        title = (String(bytes: t, encoding: .utf16) ?? "").trimmingCharacters(in: .whitespaces)
                    } else {
                        title = String(decoding: t, as: UTF8.self).trimmingCharacters(in: .whitespaces)
                    }
                }
            }
            out.append(Raw(startMs: times[i] * 1000 / timescale, title: title.isEmpty ? "Chapter \(i + 1)" : title))
        }
        return out
    }
}
