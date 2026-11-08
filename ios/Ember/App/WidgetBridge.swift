import Foundation
import WidgetKit

/// Keeps the widgets up to date: what's playing and today's listening.
@MainActor
enum WidgetBridge {
    private static var last: WidgetSnapshot?
    private static var lastCover: String??
    private static var pending: Task<Void, Never>?

    static func snapshot(_ app: AppModel) -> WidgetSnapshot {
        let p = app.player
        let s = app.library.stats()
        var w = WidgetSnapshot()
        if let l = p.loaded {
            w.title = Names.shortTitle(l.title)
            w.author = l.subtitle
            w.chapter = l.chapters.count > 1 ? (p.chapter?.title ?? "") : ""
            w.progress = l.durationMs > 0 ? Double(p.positionMs) / Double(l.durationMs) : 0
            w.leftMs = Int64(Double(max(l.durationMs - p.positionMs, 0)) / max(p.speed, 0.1))
            w.isPlaying = p.isPlaying
            w.isEpisode = l.isEpisode
            w.color = l.color
            w.hasCover = l.coverFile != nil
        }
        w.todayMs = s.todayMs
        w.goalMs = Int64(app.settings.dailyGoalMin) * 60_000
        w.streak = s.streak
        w.skipBack = app.settings.skipBackSec
        w.skipForward = app.settings.skipForwardSec
        return w
    }

    /// Soon (changes in a burst become one update).
    static func update(_ app: AppModel, now: Bool = false) {
        pending?.cancel()
        pending = Task { @MainActor in
            if !now { try? await Task.sleep(for: .seconds(2)) }
            if Task.isCancelled { return }
            write(app)
        }
    }

    private static func write(_ app: AppModel) {
        guard SharedStore.container != nil else { return }
        var w = snapshot(app)
        let cover = app.player.loaded?.coverFile
        if lastCover == nil || lastCover! != cover {
            SharedStore.writeCover(Covers.image(cover))
            lastCover = .some(cover)
        }
        // Only progress moved a little: not worth spending the widgets' refresh budget.
        if var l = last {
            l.updatedAt = w.updatedAt
            l.progress = w.progress
            l.leftMs = w.leftMs
            l.todayMs = w.todayMs
            if l == w, abs((last?.progress ?? 0) - w.progress) < 0.01, (w.todayMs - (last?.todayMs ?? 0)) < 60_000 { return }
        }
        w.updatedAt = Date()
        SharedStore.write(w)
        last = w
        WidgetCenter.shared.reloadAllTimelines()
    }
}
