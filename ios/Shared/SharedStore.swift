import Foundation
import UIKit

/// What the widgets show: what's playing and today's listening. The app writes it to the App Group
/// it shares with the widgets.
struct WidgetSnapshot: Codable, Equatable {
    var title: String = ""
    var author: String = ""
    var chapter: String = ""
    var progress: Double = 0
    var leftMs: Int64 = 0
    var isPlaying = false
    var isEpisode = false
    var color: Int?
    var hasCover = false
    var todayMs: Int64 = 0
    var goalMs: Int64 = 30 * 60_000
    var streak = 0
    var skipBack = 15
    var skipForward = 30
    var updatedAt = Date()

    var hasBook: Bool { !title.isEmpty }

    static let demo = WidgetSnapshot(title: "The Long Way Home", author: "Ada Brennan", chapter: "Chapter 7", progress: 0.42, leftMs: 3 * 3_600_000 + 12 * 60_000,
                                     isPlaying: true, color: 0x8A4B2E, todayMs: 22 * 60_000, goalMs: 30 * 60_000, streak: 6)
}

enum SharedStore {
    static let baseGroup = "group.app.ember.audiobooks"

    /// The App Group's folder. SideStore renames App Groups (it adds your team id), so every
    /// spelling it may have used is tried.
    static let container: URL? = {
        var names: [String] = []
        if let groups = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] { names += groups }
        for part in (Bundle.main.bundleIdentifier ?? "").split(separator: ".") where part.count == 10 && part.allSatisfy({ $0.isUppercase || $0.isNumber }) {
            names.append(baseGroup + "." + part)
        }
        names.append(baseGroup)
        for n in names {
            if let u = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: n) { return u }
        }
        return nil
    }()

    private static var snapshotURL: URL? { container?.appendingPathComponent("widget.json") }
    static var coverURL: URL? { container?.appendingPathComponent("widget-cover.jpg") }

    static func read() -> WidgetSnapshot? {
        guard let u = snapshotURL, let d = try? Data(contentsOf: u) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: d)
    }

    static func write(_ s: WidgetSnapshot) {
        guard let u = snapshotURL, let d = try? JSONEncoder().encode(s) else { return }
        try? d.write(to: u, options: .atomic)
    }

    static func cover() -> UIImage? {
        guard let u = coverURL, let d = try? Data(contentsOf: u) else { return nil }
        return UIImage(data: d)
    }

    static func writeCover(_ img: UIImage?) {
        guard let u = coverURL else { return }
        guard let img else {
            try? FileManager.default.removeItem(at: u)
            return
        }
        let side: CGFloat = 300
        let r = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let small = r.image { _ in img.draw(in: CGRect(x: 0, y: 0, width: side, height: side)) }
        try? small.jpegData(compressionQuality: 0.85)?.write(to: u, options: .atomic)
    }
}
