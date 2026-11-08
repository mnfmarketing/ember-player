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
    var lastAutoBackupAt: Date? { didSet { d.set(lastAutoBackupAt, forKey: "lastAutoBackupAt") } }
    /// Podcasts: most unplayed episodes kept downloaded per show by auto-download.
    var autoDownloadCount: Int { didSet { d.set(autoDownloadCount, forKey: "autoDownloadCount") } }
    var podcastSpeedDefault: Double { didSet { d.set(podcastSpeedDefault, forKey: "podcastSpeedDefault") } }

    // Sound (the names follow the Android backup: skipSilence, narrationBoost, eqEnabled...).
    var eqOn: Bool { didSet { d.set(eqOn, forKey: "eqEnabled") } }
    /// One of SoundPresets' keys, or "custom" (eqCustom).
    var eqPreset: String { didSet { d.set(eqPreset, forKey: "eqPreset") } }
    var eqCustom: [Int] { didSet { d.set(eqCustom, forKey: "eqCustom") } }
    var trimSilence: Bool { didSet { d.set(trimSilence, forKey: "skipSilence") } }
    var voiceBoost: Bool { didSet { d.set(voiceBoost, forKey: "narrationBoost") } }

    /// How much audio a clip captures, counting back from when you tap Clip.
    var clipLengthSec: Int { didSet { d.set(clipLengthSec, forKey: "clipLengthSec") } }
    /// When a book finishes, ask for a rating and offer StoryGraph / Goodreads.
    var rateOnFinish: Bool { didSet { d.set(rateOnFinish, forKey: "rateOnFinish") } }

    // Sleep timer.
    var flipToPause: Bool { didSet { d.set(flipToPause, forKey: "flipToPause") } }
    /// Bedtime: playing between these times (minutes after midnight) starts the sleep timer by itself.
    var bedtimeOn: Bool { didSet { d.set(bedtimeOn, forKey: "bedtimeOn") } }
    var bedtimeFromMin: Int { didSet { d.set(bedtimeFromMin, forKey: "bedtimeFromMin") } }
    var bedtimeToMin: Int { didSet { d.set(bedtimeToMin, forKey: "bedtimeToMin") } }
    var bedtimeTimerMin: Int { didSet { d.set(bedtimeTimerMin, forKey: "bedtimeTimerMin") } }

    // Themes.
    /// One of Palette.themes (each has a light and a dark version).
    var themeID: String { didSet { d.set(themeID, forKey: "themeId") } }
    /// Dark mode on pure black.
    var trueBlack: Bool { didSet { d.set(trueBlack, forKey: "trueBlack") } }

    // Car mode.
    /// Open car mode when the phone connects to CarPlay or a car's Bluetooth.
    var carModeAuto: Bool { didSet { d.set(carModeAuto, forKey: "carModeAuto") } }
    /// Bluetooth outputs (their ids) you've marked as your car.
    var carDevices: [String] { didSet { d.set(carDevices, forKey: "carDevices") } }

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
        lastAutoBackupAt = d.object(forKey: "lastAutoBackupAt") as? Date
        autoDownloadCount = int("autoDownloadCount", 2)
        podcastSpeedDefault = d.object(forKey: "podcastSpeedDefault") == nil ? 1 : d.double(forKey: "podcastSpeedDefault")
        eqOn = bool("eqEnabled", false)
        eqPreset = d.string(forKey: "eqPreset") ?? "spoken"
        eqCustom = (d.array(forKey: "eqCustom") as? [Int]).flatMap { $0.count == 5 ? $0 : nil } ?? [0, 0, 0, 0, 0]
        trimSilence = bool("skipSilence", false)
        voiceBoost = bool("narrationBoost", false)
        clipLengthSec = int("clipLengthSec", 30)
        rateOnFinish = bool("rateOnFinish", true)
        flipToPause = bool("flipToPause", false)
        bedtimeOn = bool("bedtimeOn", false)
        bedtimeFromMin = int("bedtimeFromMin", 22 * 60)
        bedtimeToMin = int("bedtimeToMin", 6 * 60)
        bedtimeTimerMin = int("bedtimeTimerMin", 30)
        themeID = d.string(forKey: "themeId") ?? "ember"
        trueBlack = bool("trueBlack", false)
        carModeAuto = bool("carModeAuto", true)
        carDevices = d.stringArray(forKey: "carDevices") ?? []
    }

    /// Is it bedtime now (the window may run past midnight)?
    func isBedtime(_ date: Date = Date()) -> Bool {
        guard bedtimeOn else { return false }
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        if bedtimeFromMin == bedtimeToMin { return false }
        if bedtimeFromMin < bedtimeToMin { return m >= bedtimeFromMin && m < bedtimeToMin }
        return m >= bedtimeFromMin || m < bedtimeToMin
    }

    var firstName: String {
        let n = profileName.trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? "You" : String(n.split(separator: " ").first ?? "You")
    }
}
