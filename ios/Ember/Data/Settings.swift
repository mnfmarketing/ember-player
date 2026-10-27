import Foundation
import Observation

/// Your choices, kept in UserDefaults. Names and defaults follow the Android app.
@Observable
final class Settings {
    private let d = UserDefaults.standard

    var skipBackSec: Int { didSet { d.set(skipBackSec, forKey: "skipBackSec") } }
    var skipForwardSec: Int { didSet { d.set(skipForwardSec, forKey: "skipForwardSec") } }
    /// Smart rewind after a pause (seconds); the real amount grows with how long you were away.
    var autoRewindSec: Int { didSet { d.set(autoRewindSec, forKey: "autoRewindSec") } }
    var dailyGoalMin: Int { didSet { d.set(dailyGoalMin, forKey: "dailyGoalMin") } }
    var yearlyGoalBooks: Int { didSet { d.set(yearlyGoalBooks, forKey: "yearlyGoalBooks") } }
    var lastBookID: String? { didSet { d.set(lastBookID, forKey: "lastBookID") } }
    /// The player's time left counts down the whole book instead of the chapter.
    var bookTimeLeft: Bool { didSet { d.set(bookTimeLeft, forKey: "bookTimeLeft") } }
    var seriesAutoPlay: Bool { didSet { d.set(seriesAutoPlay, forKey: "seriesAutoPlay") } }
    var sleepFade: Bool { didSet { d.set(sleepFade, forKey: "sleepFade") } }
    var lastSleepMinutes: Int { didSet { d.set(lastSleepMinutes, forKey: "lastSleepMinutes") } }
    /// "system", "light" or "dark".
    var appearance: String { didSet { d.set(appearance, forKey: "appearance") } }
    /// "book" (the playing cover's color), "theme", or "fixed" with `accentColor`.
    var accentMode: String { didSet { d.set(accentMode, forKey: "accentMode") } }
    var accentColor: Int { didSet { d.set(accentColor, forKey: "accentColor") } }
    var profileName: String { didSet { d.set(profileName, forKey: "profileName") } }
    var profilePhotoAt: Int64 { didSet { d.set(profilePhotoAt, forKey: "profilePhotoAt") } }
    /// Books to play next, in order (book ids).
    var upNext: [String] { didSet { d.set(upNext, forKey: "upNext") } }
    var lastBackupAt: Date? { didSet { d.set(lastBackupAt, forKey: "lastBackupAt") } }
    /// Podcasts: most unplayed episodes kept downloaded per show by auto-download.
    var autoDownloadCount: Int { didSet { d.set(autoDownloadCount, forKey: "autoDownloadCount") } }
    var podcastSpeedDefault: Double { didSet { d.set(podcastSpeedDefault, forKey: "podcastSpeedDefault") } }

    init() {
        let d = UserDefaults.standard
        func int(_ k: String, _ def: Int) -> Int { d.object(forKey: k) == nil ? def : d.integer(forKey: k) }
        func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
        skipBackSec = int("skipBackSec", 15)
        skipForwardSec = int("skipForwardSec", 30)
        autoRewindSec = int("autoRewindSec", 5)
        dailyGoalMin = int("dailyGoalMin", 30)
        yearlyGoalBooks = int("yearlyGoalBooks", 24)
        lastBookID = d.string(forKey: "lastBookID")
        bookTimeLeft = bool("bookTimeLeft", false)
        seriesAutoPlay = bool("seriesAutoPlay", true)
        sleepFade = bool("sleepFade", false)
        lastSleepMinutes = int("lastSleepMinutes", 30)
        appearance = d.string(forKey: "appearance") ?? "system"
        accentMode = d.string(forKey: "accentMode") ?? "book"
        accentColor = int("accentColor", 0xE87A2E)
        profileName = d.string(forKey: "profileName") ?? ""
        profilePhotoAt = Int64(d.integer(forKey: "profilePhotoAt"))
        upNext = d.stringArray(forKey: "upNext") ?? []
        lastBackupAt = d.object(forKey: "lastBackupAt") as? Date
        autoDownloadCount = int("autoDownloadCount", 2)
        podcastSpeedDefault = d.object(forKey: "podcastSpeedDefault") == nil ? 1 : d.double(forKey: "podcastSpeedDefault")
    }

    var firstName: String {
        let n = profileName.trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? "You" : String(n.split(separator: " ").first ?? "You")
    }
}
