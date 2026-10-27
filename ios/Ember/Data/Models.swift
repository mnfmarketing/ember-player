import Foundation
import SwiftData
import CryptoKit

/// A book: audio in a folder (or several), or a book that lives in another app (Audible, Libby...).
@Model
final class Book {
    @Attribute(.unique) var id: String
    var title: String
    var author: String = ""
    var narrator: String = ""
    var series: String = ""
    var seriesIndex: Double?
    var desc: String = ""
    /// File name of the cover in Ember's covers folder.
    var coverFile: String?
    /// The cover's main color (0xRRGGBB), for tinting the book page and player.
    var coverColor: Int?
    var source: String = Book.sourceLocal
    var folderID: String?
    /// The book's folder or file, relative to its library folder.
    var path: String = ""
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var status: Int = Book.statusNew
    var rating: Int = 0
    var speed: Double = 1
    var addedAt: Date = Date()
    var lastPlayedAt: Int64 = 0
    var finishedAt: Int64 = 0
    var externalApp: String = ""
    var externalLink: String = ""
    var userEdited: Bool = false
    var skipIntroSec: Int = 0
    var skipOutroSec: Int = 0
    var customEffects: Bool = false
    var trimSilence: Bool = false
    var voiceBoost: Bool = false

    init(id: String, title: String) {
        self.id = id
        self.title = title
    }

    static let sourceLocal = "local"
    static let sourceDrive = "drive"
    static let sourceExternal = "external"
    static let statusNew = 0
    static let statusInProgress = 1
    static let statusFinished = 2

    var isExternal: Bool { source == Book.sourceExternal }
    var displayTitle: String { Names.shortTitle(title) }

    var progress: Double {
        if status == Book.statusFinished { return 1 }
        guard durationMs > 0 else { return 0 }
        return min(max(Double(positionMs) / Double(durationMs), 0), 1)
    }

    var remainingMs: Int64 { max(durationMs - positionMs, 0) }
}

/// One audio file of a book, in play order.
@Model
final class Track {
    var bookID: String
    var idx: Int
    /// Path relative to the library folder (local), or a Drive file id.
    var path: String
    var title: String
    var durationMs: Int64

    init(bookID: String, idx: Int, path: String, title: String, durationMs: Int64) {
        self.bookID = bookID
        self.idx = idx
        self.path = path
        self.title = title
        self.durationMs = durationMs
    }
}

@Model
final class Chapter {
    var bookID: String
    var idx: Int
    var title: String
    var startMs: Int64
    var endMs: Int64

    init(bookID: String, idx: Int, title: String, startMs: Int64, endMs: Int64) {
        self.bookID = bookID
        self.idx = idx
        self.title = title
        self.startMs = startMs
        self.endMs = endMs
    }
}

@Model
final class Bookmark {
    var bookID: String
    var positionMs: Int64
    var note: String = ""
    var chapterTitle: String = ""
    /// Milliseconds since 1970: also what makes a bookmark the same one on every device.
    var createdAt: Int64

    init(bookID: String, positionMs: Int64, note: String = "", chapterTitle: String = "", createdAt: Int64 = Clock.now) {
        self.bookID = bookID
        self.positionMs = positionMs
        self.note = note
        self.chapterTitle = chapterTitle
        self.createdAt = createdAt
    }
}

/// One entry in a book's listening log: play, pause, skip or jump.
@Model
final class ListenEvent {
    var bookID: String
    var at: Int64
    var kind: Int
    var positionMs: Int64
    var toMs: Int64 = -1
    var speed: Double = 1
    var reason: Int = 0
    /// This device's own entries: a number of their own. Entries from other devices: their id and device.
    var seq: Int64 = 0
    var uid: String = ""
    var device: String = ""

    init(bookID: String, at: Int64, kind: Int, positionMs: Int64, toMs: Int64 = -1, speed: Double = 1, reason: Int = 0) {
        self.bookID = bookID
        self.at = at
        self.kind = kind
        self.positionMs = positionMs
        self.toMs = toMs
        self.speed = speed
        self.reason = reason
    }

    static let play = 1
    static let pause = 2
    static let skip = 3
    static let jump = 4

    static let reasonNone = 0
    static let reasonSleep = 1
    static let reasonUnplugged = 2
    static let reasonOtherApp = 3
    static let reasonFinished = 4
    static let reasonFlipped = 5
}

/// Minutes listened on a day (days since 1970 in local time, the same numbering as Android).
@Model
final class DayListening {
    @Attribute(.unique) var day: Int64
    var ms: Int64

    init(day: Int64, ms: Int64) {
        self.day = day
        self.ms = ms
    }
}

@Model
final class Shelf {
    var name: String
    var createdAt: Date = Date()
    var books: [String] = []

    init(name: String) {
        self.name = name
    }
}

/// A folder you picked in the Files app (On My iPhone, iCloud Drive...), or a Google Drive folder.
@Model
final class LibraryFolder {
    @Attribute(.unique) var id: String
    var name: String
    /// Permission to read the folder again after Ember restarts.
    var bookmarkData: Data?
    /// For Google Drive folders: the folder's id.
    var driveID: String?
    var lastScanAt: Date?

    init(id: String = UUID().uuidString, name: String, bookmarkData: Data? = nil, driveID: String? = nil) {
        self.id = id
        self.name = name
        self.bookmarkData = bookmarkData
        self.driveID = driveID
    }
}

@Model
final class Podcast {
    @Attribute(.unique) var id: String
    var feedURL: String
    var title: String
    var author: String = ""
    var desc: String = ""
    var artworkURL: String = ""
    var artworkFile: String?
    var color: Int?
    var subscribedAt: Date = Date()
    var lastRefreshAt: Date?
    var autoDownload: Bool = false
    var notify: Bool = true
    var speed: Double = 1
    var autoQueue: Bool = false
    var autoArchive: Bool = true
    var skipIntroSec: Int = 0
    var skipOutroSec: Int = 0
    var customEffects: Bool = false
    var trimSilence: Bool = false
    var voiceBoost: Bool = false
    var category: String = ""

    init(id: String, feedURL: String, title: String) {
        self.id = id
        self.feedURL = feedURL
        self.title = title
    }
}

@Model
final class Episode {
    @Attribute(.unique) var id: String
    var podcastID: String
    var guid: String
    var title: String
    var desc: String = ""
    var audioURL: String
    var durationMs: Int64 = 0
    var publishedAt: Date?
    var positionMs: Int64 = 0
    var played: Bool = false
    var playedAt: Int64 = 0
    var lastPlayedAt: Int64 = 0
    var archived: Bool = false
    /// The downloaded file's name in Ember's downloads folder.
    var downloadFile: String?
    var chaptersURL: String = ""

    init(id: String, podcastID: String, guid: String, title: String, audioURL: String) {
        self.id = id
        self.podcastID = podcastID
        self.guid = guid
        self.title = title
        self.audioURL = audioURL
    }

    var progress: Double {
        if played { return 1 }
        guard durationMs > 0 else { return 0 }
        return min(Double(positionMs) / Double(durationMs), 1)
    }
}

/// Podcast Up Next, in order.
@Model
final class QueueItem {
    @Attribute(.unique) var episodeID: String
    var position: Double

    init(episodeID: String, position: Double) {
        self.episodeID = episodeID
        self.position = position
    }
}

enum Clock {
    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Days since 1970 in local time (Android's LocalDate.toEpochDay()).
    static func epochDay(_ date: Date = Date()) -> Int64 {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let midnight = utc.date(from: c) ?? date
        return Int64((midnight.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    static func date(epochDay: Int64) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let d = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let c = utc.dateComponents([.year, .month, .day], from: d)
        return Calendar.current.date(from: c) ?? d
    }
}

enum Ids {
    /// Java's UUID.nameUUIDFromBytes (version 3), so ids match the Android app where that matters.
    static func nameUUID(_ s: String) -> String {
        var bytes = [UInt8](Insecure.MD5.hash(data: Data(s.utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let a = hex.prefix(8), b = hex.dropFirst(8).prefix(4), c = hex.dropFirst(12).prefix(4), d = hex.dropFirst(16).prefix(4), e = hex.dropFirst(20)
        return "\(a)-\(b)-\(c)-\(d)-\(e)"
    }

    static func podcast(_ feedURL: String) -> String { "pod-" + nameUUID(feedURL.trimmingCharacters(in: .whitespaces)) }
    static func episode(podcastID: String, guid: String) -> String { "ep-" + nameUUID("\(podcastID)|\(guid)") }
}
