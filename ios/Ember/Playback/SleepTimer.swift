import Foundation
import Observation
import UIKit

/// The sleep timer: minutes, or the end of the chapter. Shaking the phone starts it over
/// (and, within 5 minutes after it stopped the book, keeps listening).
@MainActor
@Observable
final class SleepTimer {
    enum Mode: Equatable {
        case off
        case minutes(Int)
        case endOfChapter
    }

    private(set) var mode: Mode = .off
    private(set) var endsAt: Date?
    /// End of chapter: the place it stops at.
    @ObservationIgnored private var stopAtMs: Int64 = -1
    /// Where you were when you last set it or shook (the "Fell asleep?" Go back spot).
    @ObservationIgnored private(set) var awakeMs: Int64 = -1
    @ObservationIgnored private(set) var expiredAt: Date?
    @ObservationIgnored var onExpire: (() -> Void)?
    @ObservationIgnored var onShakeAfterExpiry: (() -> Void)?
    @ObservationIgnored private var lastPosition: Int64 = 0
    /// Bumped every second while running so the countdown redraws.
    private(set) var tickCount = 0

    var isOn: Bool { mode != .off }

    var remainingSeconds: Int? {
        guard let endsAt else { return nil }
        return max(Int(endsAt.timeIntervalSinceNow.rounded()), 0)
    }

    /// "28m", "Ch." or nil, for the mini player and the player's round button.
    var shortLabel: String? {
        _ = tickCount
        switch mode {
        case .off: return nil
        case .endOfChapter: return "Ch."
        case .minutes:
            guard let s = remainingSeconds else { return nil }
            return s >= 60 ? "\((s + 59) / 60)m" : "\(s)s"
        }
    }

    init() {
        _ = NotificationCenter.default.addObserver(forName: .emberShake, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.shaken() }
        }
    }

    func start(minutes: Int, positionMs: Int64) {
        mode = .minutes(minutes)
        endsAt = Date().addingTimeInterval(Double(minutes) * 60)
        awakeMs = positionMs
        expiredAt = nil
    }

    /// For the screenshots and checks: a timer of a few seconds.
    func start(seconds: Int, positionMs: Int64) {
        mode = .minutes(max(seconds / 60, 1))
        endsAt = Date().addingTimeInterval(Double(seconds))
        awakeMs = positionMs
        expiredAt = nil
    }

    func startEndOfChapter(positionMs: Int64, chapterEnd: Int64) {
        mode = .endOfChapter
        endsAt = nil
        stopAtMs = chapterEnd
        awakeMs = positionMs
        expiredAt = nil
    }

    func cancel() {
        mode = .off
        endsAt = nil
        stopAtMs = -1
    }

    func tick(positionMs: Int64, chapterEnd: Int64) {
        lastPosition = positionMs
        guard isOn else { return }
        tickCount &+= 1
        switch mode {
        case .off: break
        case .minutes:
            if let endsAt, Date() >= endsAt { expire() }
        case .endOfChapter:
            if stopAtMs > 0, positionMs >= stopAtMs - 400 { expire() }
        }
    }

    private func expire() {
        cancel()
        expiredAt = Date()
        onExpire?()
    }

    private func shaken() {
        switch mode {
        case .minutes(let m):
            endsAt = Date().addingTimeInterval(Double(m) * 60)
            awakeMs = lastPosition
            Haptics.tap()
        case .endOfChapter:
            awakeMs = lastPosition
            Haptics.tap()
        case .off:
            if let e = expiredAt, Date().timeIntervalSince(e) < 5 * 60 {
                expiredAt = nil
                onShakeAfterExpiry?()
                Haptics.tap()
            }
        }
    }
}

extension Notification.Name {
    static let emberShake = Notification.Name("emberShake")
}

extension UIWindow {
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake { NotificationCenter.default.post(name: .emberShake, object: nil) }
        super.motionEnded(motion, with: event)
    }
}

enum Haptics {
    @MainActor static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}
