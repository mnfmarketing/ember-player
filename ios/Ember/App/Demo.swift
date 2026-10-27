import Foundation
import AVFoundation
import UIKit
import SwiftData
import SwiftUI

/// A made-up library for the simulator screenshots (launch argument -EmberDemo YES):
/// a few books with short generated audio, covers, progress, bookmarks, stats and a podcast.
@MainActor
enum Demo {

    struct DemoBook {
        var title: String
        var author: String
        var color: Int
        var ink: Int
        var series: String = ""
        var index: Double? = nil
        var files: Int = 3
    }

    static let books: [DemoBook] = [
        DemoBook(title: "The Lantern Thief of Vell", author: "Ada Wren", color: 0x0E4A47, ink: 0xF3D9A4, series: "The Vell Cycle", index: 2),
        DemoBook(title: "Saltglass", author: "Corin Hale", color: 0x233A66, ink: 0xE8EEF7),
        DemoBook(title: "Moth & Meridian", author: "Mara Ellison", color: 0x6B2A1F, ink: 0xF5E6D3),
        DemoBook(title: "The Ninth Orchard", author: "Theo Vance", color: 0x2F4A2A, ink: 0xE9E2C8),
        DemoBook(title: "Ironbloom", author: "Lio Park", color: 0x1E1E22, ink: 0xFF8A3D),
        DemoBook(title: "A Hymn for Low Tide", author: "June Halvorsen", color: 0xC9B79A, ink: 0x2B2620),
    ]

    static func seed(_ app: AppModel) async {
        let lib = app.library
        if lib.book("demo-0") != nil { return }
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Demo", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let folder = LibraryFolder(id: "demo", name: "Demo", bookmarkData: try? root.bookmarkData())
        lib.context.insert(folder)
        let now = Clock.now
        // The audio first, off the main thread and in parallel (it takes a while on a simulator).
        var jobs: [(URL, Int, Double)] = []
        for (i, d) in books.enumerated() {
            let dir = root.appendingPathComponent(d.title, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            for f in 0..<d.files {
                let url = dir.appendingPathComponent(String(format: "%02d Chapter %d.m4a", f + 1, f + 1))
                if !FileManager.default.fileExists(atPath: url.path) { jobs.append((url, 120 + 30 * f, 180 + Double(i * 30 + f * 15))) }
            }
        }
        await withTaskGroup(of: Void.self) { group in
            for j in jobs { group.addTask { Demo.tone(j.0, seconds: j.1, freq: j.2) } }
        }
        for (i, d) in books.enumerated() {
            let id = "demo-\(i)"
            let dir = root.appendingPathComponent(d.title, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var offset: Int64 = 0
            let b = Book(id: id, title: d.title)
            b.author = d.author
            b.narrator = ["Elise Marr", "Tom Okafor", "Priya Das"][i % 3]
            b.series = d.series
            b.seriesIndex = d.index
            b.folderID = "demo"
            b.path = d.title
            b.addedAt = Date(timeIntervalSinceNow: Double(-86_400 * (10 - i)))
            if let jpg = cover(d).jpegData(compressionQuality: 0.9), let saved = Covers.save(id: id, data: jpg) {
                b.coverFile = saved.0
                b.coverColor = saved.1
            }
            for f in 0..<d.files {
                let name = String(format: "%02d Chapter %d.m4a", f + 1, f + 1)
                let secs = 120 + 30 * f
                let ms = Int64(secs) * 1000
                lib.context.insert(Track(bookID: id, idx: f, path: "\(d.title)/\(name)", title: "Chapter \(f + 1)", durationMs: ms))
                lib.context.insert(Chapter(bookID: id, idx: f, title: ["The Night Market", "Lanterns", "A Borrowed Key", "The Tide Gate"][f % 4], startMs: offset, endMs: offset + ms))
                offset += ms
            }
            b.durationMs = offset
            switch i {
            case 0:
                b.positionMs = offset * 47 / 100
                b.status = Book.statusInProgress
                b.lastPlayedAt = now - 60_000
                b.rating = 4
                b.speed = 1.2
            case 2:
                b.status = Book.statusFinished
                b.positionMs = offset
                b.finishedAt = now - 3 * 86_400_000
                b.lastPlayedAt = now - 3 * 86_400_000
                b.rating = 5
            case 3:
                b.positionMs = offset * 12 / 100
                b.status = Book.statusInProgress
                b.lastPlayedAt = now - 2 * 86_400_000
            case 5:
                b.positionMs = offset * 64 / 100
                b.status = Book.statusInProgress
                b.lastPlayedAt = now - 5 * 86_400_000
            default: break
            }
            lib.context.insert(b)
        }
        lib.context.insert(Bookmark(bookID: "demo-0", positionMs: 95_000, note: "The night market scene. Come back to this.", chapterTitle: "The Night Market", createdAt: now - 86_400_000))
        let shelf = Shelf(name: "Favorites")
        shelf.books = ["demo-0", "demo-2"]
        lib.context.insert(shelf)
        let today = Clock.epochDay()
        for (n, mins) in [38, 52, 70, 34, 58, 76, 44, 31, 60, 45, 50, 36].enumerated() {
            lib.context.insert(DayListening(day: today - Int64(n), ms: Int64(mins) * 60_000))
        }
        app.settings.upNext = ["demo-1", "demo-4", "demo-3"]
        app.settings.lastBookID = "demo-0"
        app.settings.profileName = "Ben"
        seedPodcast(app)
        try? lib.context.save()
        lib.changed()
        // For the simulator checks: the demo library is ready.
        FileManager.default.createFile(atPath: root.appendingPathComponent(".ready").path, contents: Data())
    }

    private static func seedPodcast(_ app: AppModel) {
        let feed = "https://example.com/the-slow-read.xml"
        let pid = Ids.podcast(feed)
        let p = Podcast(id: pid, feedURL: feed, title: "The Slow Read")
        p.author = "Nia Brooks"
        p.category = "Books"
        p.desc = "A weekly show about reading slowly, rereading, and the books that stay with us."
        if let jpg = showCover().jpegData(compressionQuality: 0.9), let saved = Covers.save(id: pid, data: jpg) {
            p.artworkFile = saved.0
            p.color = saved.1
        }
        app.podcasts.context.insert(p)
        let titles = [
            ("Rereading the books that made us", "Why the second read is often the real one, and how to pick what to revisit.", 48),
            ("Narrators we would follow anywhere", "A listener-mailbag episode on voices that change how a story lands.", 41),
            ("The case for slow fantasy", "Big worlds, small stakes, and the pleasure of a book that takes its time.", 55),
            ("Margins and footnotes", "Notes, annotations and the books we argue with.", 37),
        ]
        for (i, t) in titles.enumerated() {
            let guid = "slow-\(i)"
            let e = Episode(id: Ids.episode(podcastID: pid, guid: guid), podcastID: pid, guid: guid, title: t.0, audioURL: "https://example.com/slow-\(i).mp3")
            e.desc = t.1
            e.durationMs = Int64(t.2) * 60_000
            e.publishedAt = Date(timeIntervalSinceNow: Double(-7 * 86_400 * i - 3 * 86_400))
            if i == 1 { e.positionMs = 12 * 60_000; e.lastPlayedAt = Clock.now - 86_400_000 }
            app.podcasts.context.insert(e)
        }
    }

    nonisolated private static func tone(_ final: URL, seconds: Int, freq: Double) {
        // Written beside the real name and moved into place, so a stopped launch never leaves half a file.
        let temp = final.deletingLastPathComponent().appendingPathComponent(".partial-" + final.lastPathComponent)
        try? FileManager.default.removeItem(at: temp)
        if writeTone(temp, seconds: seconds, freq: freq) { try? FileManager.default.moveItem(at: temp, to: final) }
    }

    /// Writes a short tone as AAC (so the demo books really play).
    nonisolated private static func writeTone(_ url: URL, seconds: Int, freq: Double) -> Bool {
        let rate = 22_050.0
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1) else { return false }
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32_000]
        guard let file = try? AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false) else { return false }
        let chunk: AVAudioFrameCount = 22_050
        var t = 0.0
        for _ in 0..<seconds {
            guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunk) else { return false }
            buf.frameLength = chunk
            let p = buf.floatChannelData![0]
            for i in 0..<Int(chunk) {
                p[i] = Float(sin(2 * .pi * freq * t) * 0.12)
                t += 1 / rate
            }
            try? file.write(from: buf)
        }
        file.close()
        return true
    }

    private static func cover(_ d: DemoBook) -> UIImage {
        let size = CGSize(width: 800, height: 800)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor(Color(hex: d.color)).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let ink = UIColor(Color(hex: d.ink))
            ink.setStroke()
            let ring = UIBezierPath(ovalIn: CGRect(x: 340, y: 150, width: 120, height: 120))
            ring.lineWidth = 12
            ring.stroke()
            let para = NSMutableParagraphStyle()
            para.alignment = .center
            let titleFont = UIFont(descriptor: UIFont.systemFont(ofSize: 76, weight: .semibold).fontDescriptor.withDesign(.serif) ?? UIFont.systemFont(ofSize: 76).fontDescriptor, size: 76)
            (d.title as NSString).draw(in: CGRect(x: 70, y: 310, width: 660, height: 300),
                                       withAttributes: [.font: titleFont, .foregroundColor: ink, .paragraphStyle: para])
            (d.author.uppercased() as NSString).draw(in: CGRect(x: 70, y: 640, width: 660, height: 60),
                                                    withAttributes: [.font: UIFont.systemFont(ofSize: 30, weight: .medium), .foregroundColor: ink, .paragraphStyle: para, .kern: 6])
        }
    }

    private static func showCover() -> UIImage {
        let size = CGSize(width: 600, height: 600)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor(Color(hex: 0x8A3B12)).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            UIColor(Color(hex: 0xF6E7D2)).setFill()
            UIBezierPath(ovalIn: CGRect(x: 54, y: 54, width: 90, height: 90)).fill()
            let font = UIFont(descriptor: UIFont.systemFont(ofSize: 110, weight: .semibold).fontDescriptor.withDesign(.serif) ?? UIFont.systemFont(ofSize: 110).fontDescriptor, size: 110)
            ("The Slow\nRead" as NSString).draw(in: CGRect(x: 54, y: 280, width: 520, height: 280), withAttributes: [.font: font, .foregroundColor: UIColor(Color(hex: 0xF6E7D2))])
        }
    }
}
