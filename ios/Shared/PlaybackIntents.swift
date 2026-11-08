import AppIntents
import Foundation

// Buttons on the widgets. They run inside Ember (which plays in the background), not the widget.

struct TogglePlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or pause"
    static let description = IntentDescription("Plays or pauses the book or episode in Ember.")

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        let p = AppModel.shared.player
        if p.loaded == nil { p.restoreLast() }
        p.toggle()
        #endif
        return .result()
    }
}

struct SkipBackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Skip back"

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        let m = AppModel.shared
        if m.player.loaded == nil { m.player.restoreLast() }
        m.player.skip(seconds: -m.settings.skipBackSec)
        #endif
        return .result()
    }
}

struct SkipForwardIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Skip forward"

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        let m = AppModel.shared
        if m.player.loaded == nil { m.player.restoreLast() }
        m.player.skip(seconds: m.settings.skipForwardSec)
        #endif
        return .result()
    }
}
