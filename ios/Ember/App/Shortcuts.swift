import AppIntents
import Foundation

// Siri and the Shortcuts app.

struct ResumeBookIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Resume listening"
    static let description = IntentDescription("Plays the book or episode you were listening to.")

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let p = AppModel.shared.player
        if p.loaded == nil { p.restoreLast() }
        guard let l = p.loaded else { return .result(dialog: "There's nothing to resume yet. Pick a book in Ember first.") }
        p.resume()
        return .result(dialog: "Playing \(Names.shortTitle(l.title)).")
    }
}

struct PausePlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Pause listening"

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.player.pause()
        return .result()
    }
}

struct SleepTimerIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Set a sleep timer"
    static let description = IntentDescription("Stops Ember after a while, or at the end of the chapter (0 minutes).")

    @Parameter(title: "Minutes", default: 30, inclusiveRange: (0, 240))
    var minutes: Int

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let p = AppModel.shared.player
        if p.loaded == nil { p.restoreLast() }
        if minutes <= 0 {
            p.sleep.startEndOfChapter(positionMs: p.positionMs, chapterEnd: p.chapterEnd)
            return .result(dialog: "Ember will stop at the end of this chapter.")
        }
        AppModel.shared.settings.lastSleepMinutes = minutes
        p.sleep.start(minutes: minutes, positionMs: p.positionMs)
        if !p.isPlaying { p.resume() }
        return .result(dialog: "Sleep timer set for \(minutes) minutes.")
    }
}

struct BookmarkIntent: AppIntent {
    static let title: LocalizedStringResource = "Add a bookmark"
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let p = AppModel.shared.player
        guard p.loaded?.isEpisode == false else { return .result(dialog: "Bookmarks are for books. Start one in Ember first.") }
        p.bookmarkHere()
        return .result(dialog: "Bookmarked at \(Fmt.clock(p.positionMs)).")
    }
}

struct ListenedTodayIntent: AppIntent {
    static let title: LocalizedStringResource = "How much have I listened today"
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let m = AppModel.shared
        let s = m.library.stats()
        let mins = Int(s.todayMs / 60_000), goal = m.settings.dailyGoalMin
        let streak = s.streak > 1 ? " You're on a \(s.streak)-day streak." : ""
        if mins >= goal { return .result(dialog: "\(mins) minutes today. Goal reached!\(streak)") }
        return .result(dialog: "\(mins) of your \(goal) minutes today.\(streak)")
    }
}

struct CarModeIntent: AppIntent {
    static let title: LocalizedStringResource = "Open car mode"
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.carModeOpen = true
        return .result()
    }
}

struct EmberShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ResumeBookIntent(), phrases: ["Resume my book in \(.applicationName)", "Keep listening in \(.applicationName)", "Play \(.applicationName)"],
                    shortTitle: "Resume listening", systemImageName: "play.fill")
        AppShortcut(intent: PausePlaybackIntent(), phrases: ["Pause \(.applicationName)"], shortTitle: "Pause", systemImageName: "pause.fill")
        AppShortcut(intent: SleepTimerIntent(), phrases: ["Set a sleep timer in \(.applicationName)", "\(.applicationName) sleep timer"],
                    shortTitle: "Sleep timer", systemImageName: "moon.fill")
        AppShortcut(intent: BookmarkIntent(), phrases: ["Bookmark this in \(.applicationName)", "Add a bookmark in \(.applicationName)"],
                    shortTitle: "Bookmark", systemImageName: "bookmark.fill")
        AppShortcut(intent: ListenedTodayIntent(), phrases: ["How much have I listened today in \(.applicationName)", "My \(.applicationName) goal"],
                    shortTitle: "Today's listening", systemImageName: "flame.fill")
        AppShortcut(intent: CarModeIntent(), phrases: ["Open \(.applicationName) car mode", "\(.applicationName) car mode"],
                    shortTitle: "Car mode", systemImageName: "car.fill")
    }
}
