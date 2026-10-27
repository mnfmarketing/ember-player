import Foundation
import AVFoundation
import MediaPlayer
import Observation
import UIKit

/// Plays books (one or many files, one timeline) and podcast episodes. Saves your place, keeps the
/// listening log and stats, and answers the lock screen, Control Center and headphone buttons.
@MainActor
@Observable
final class Player {

    struct Loaded {
        var id: String
        var isEpisode: Bool
        var title: String
        var subtitle: String
        var coverFile: String?
        var color: Int?
        var urls: [URL]
        var durations: [Int64]
        var offsets: [Int64]
        var chapters: [Chapter]
        var durationMs: Int64
    }

    struct Jump: Equatable {
        var bookID: String
        var fromMs: Int64
        var toMs: Int64
        var stamp = Date()
    }

    private(set) var loaded: Loaded?
    private(set) var isPlaying = false
    private(set) var positionMs: Int64 = 0
    private(set) var speed: Double = 1
    private(set) var buffering = false
    /// A big move (the scrubber, a chapter): the app offers to go back.
    var lastJump: Jump?
    /// One-off messages ("Up Next: …", "Went back 30s since you paused").
    var notice: String?
    /// A book played to its end.
    var finished: String?

    let sleep = SleepTimer()
    private let av = AVPlayer()
    @ObservationIgnored private var index = 0
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var lastTick: Date?
    @ObservationIgnored private var listenAccumMs: Int64 = 0
    @ObservationIgnored private var ticks = 0
    @ObservationIgnored private var quietSeek = false
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private let library: Library
    private let settings: Settings
    @ObservationIgnored var podcasts: PodcastStore?
    /// Called when your place changes in a way your other devices should hear about soon.
    @ObservationIgnored var onSyncWorthy: ((String) -> Void)?

    init(library: Library, settings: Settings) {
        self.library = library
        self.settings = settings
        av.automaticallyWaitsToMinimizeStalling = true
        timeObserver = av.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 2), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] n in
            let item = n.object as? AVPlayerItem
            MainActor.assumeIsolated { self?.itemEnded(item) }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            let info = n.userInfo
            MainActor.assumeIsolated { self?.interrupted(info) }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            let info = n.userInfo
            MainActor.assumeIsolated { self?.routeChanged(info) }
        })
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.saveProgress() }
        })
        sleep.onExpire = { [weak self] in self?.sleepExpired() }
        setUpRemoteCommands()
    }

    // MARK: Loading

    /// Loads a book at its saved place (and plays it unless `play` is false).
    func playBook(_ id: String, play: Bool = true) {
        if loaded?.id == id {
            if play { resume() }
            return
        }
        guard let b = library.book(id), !b.isExternal else { return }
        let tracks = library.tracks(id)
        guard !tracks.isEmpty else { return }
        saveProgress()
        var urls: [URL] = []
        for t in tracks {
            guard let u = library.fileURL(book: b, track: t) else {
                notice = "Ember can't find this book's files. Its folder may have moved."
                return
            }
            urls.append(u)
        }
        var offsets: [Int64] = []
        var acc: Int64 = 0
        for t in tracks {
            offsets.append(acc)
            acc += t.durationMs
        }
        let restart = b.status == Book.statusFinished || b.positionMs >= b.durationMs - 1000
        var start = restart ? 0 : b.positionMs
        let intro = Int64(b.skipIntroSec) * 1000
        if start == 0, intro > 0, tracks[0].durationMs == 0 || tracks[0].durationMs >= intro + 10_000 { start = intro }
        loaded = Loaded(id: id, isEpisode: false, title: b.displayTitle, subtitle: b.author, coverFile: b.coverFile, color: b.coverColor,
                        urls: urls, durations: tracks.map(\.durationMs), offsets: offsets, chapters: library.chapters(id), durationMs: acc)
        speed = b.speed
        settings.lastBookID = id
        settings.upNext.removeAll { $0 == id }
        if restart { library.setProgress(id, positionMs: 0) }
        load(at: start)
        if play { resume() }
        updateNowPlaying()
    }

    func playEpisode(_ ep: Episode, play: Bool = true) {
        let key = "episode:" + ep.id
        if loaded?.id == key {
            if play { resume() }
            return
        }
        saveProgress()
        let show = podcasts?.podcast(ep.podcastID)
        let url: URL
        if let f = ep.downloadFile, let local = PodcastStore.downloadURL(f), FileManager.default.fileExists(atPath: local.path) {
            url = local
        } else if let remote = URL(string: ep.audioURL) {
            url = remote
        } else { return }
        loaded = Loaded(id: key, isEpisode: true, title: ep.title, subtitle: show?.title ?? "", coverFile: show?.artworkFile, color: show?.color,
                        urls: [url], durations: [ep.durationMs], offsets: [0], chapters: [], durationMs: ep.durationMs)
        speed = show?.speed ?? 1
        settings.lastBookID = key
        let start = ep.played ? 0 : ep.positionMs
        load(at: start)
        if play { resume() }
        updateNowPlaying()
    }

    /// Opens the last book or episode (paused) when the app starts.
    func restoreLast() {
        guard loaded == nil, let id = settings.lastBookID else { return }
        if id.hasPrefix("episode:") {
            if let ep = podcasts?.episode(String(id.dropFirst(8))) { playEpisode(ep, play: false) }
        } else {
            playBook(id, play: false)
        }
    }

    private func load(at global: Int64) {
        guard let l = loaded else { return }
        var idx = 0
        for (i, o) in l.offsets.enumerated() where o <= global { idx = i }
        index = idx
        let item = AVPlayerItem(asset: DriveStream.asset(l.urls[idx]))
        item.audioTimePitchAlgorithm = .spectral
        av.replaceCurrentItem(with: item)
        let inTrack = max(global - l.offsets[idx], 0)
        quietSeek = true
        av.seek(to: CMTime(value: inTrack, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        positionMs = global
    }

    // MARK: Controls

    func resume() {
        guard loaded != nil else { return }
        if av.currentItem == nil { load(at: positionMs) }
        activateSession()
        smartRewind()
        av.playImmediately(atRate: Float(speed))
        isPlaying = true
        lastTick = Date()
        log(.play, at: positionMs)
        UserDefaults.standard.removeObject(forKey: "pausedAt")
        updateNowPlaying()
    }

    func pause(reason: Int = ListenEvent.reasonNone, awakeMs: Int64 = -1) {
        guard isPlaying else { return }
        captureListening()
        flushListening()
        av.pause()
        isPlaying = false
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "pausedAt")
        log(.pause, at: positionMs, reason: reason, to: awakeMs)
        saveProgress()
        onSyncWorthy?("pause")
        updateNowPlaying()
    }

    func toggle() { isPlaying ? pause() : resume() }

    func skip(seconds: Int) {
        seek(to: positionMs + Int64(seconds) * 1000)
    }

    func seek(to global: Int64, logged: Bool = true) {
        guard let l = loaded else { return }
        let target = min(max(global, 0), max(l.durationMs - 500, 0))
        let from = positionMs
        var idx = 0
        for (i, o) in l.offsets.enumerated() where o <= target { idx = i }
        if idx != index {
            let wasPlaying = isPlaying
            load(at: target)
            if wasPlaying { av.playImmediately(atRate: Float(speed)) }
        } else {
            av.seek(to: CMTime(value: target - l.offsets[idx], timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        positionMs = target
        if logged { moved(from: from, to: target) }
        saveProgress()
        updateNowPlaying()
    }

    func setSpeed(_ s: Double) {
        speed = min(max(s, 0.5), 3.5)
        if isPlaying { av.rate = Float(speed) }
        if let l = loaded {
            if l.isEpisode {
                if let ep = podcasts?.episode(String(l.id.dropFirst(8))), let show = podcasts?.podcast(ep.podcastID) { show.speed = speed }
            } else if let b = library.book(l.id) {
                b.speed = speed
            }
            try? library.context.save()
        }
        updateNowPlaying()
    }

    var chapterIndex: Int {
        guard let l = loaded, !l.chapters.isEmpty else { return 0 }
        return l.chapters.lastIndex { $0.startMs <= positionMs } ?? 0
    }

    var chapter: Chapter? { loaded?.chapters.indices.contains(chapterIndex) == true ? loaded!.chapters[chapterIndex] : nil }

    var chapterStart: Int64 { chapter?.startMs ?? 0 }
    var chapterEnd: Int64 {
        if let c = chapter, c.endMs > c.startMs { return c.endMs }
        return loaded?.durationMs ?? 0
    }

    func nextChapter() {
        guard let l = loaded, chapterIndex + 1 < l.chapters.count else { return }
        seek(to: l.chapters[chapterIndex + 1].startMs)
    }

    func previousChapter() {
        guard let l = loaded, !l.chapters.isEmpty else { return seek(to: 0) }
        let i = chapterIndex
        // More than 3 seconds into a chapter: back to its start.
        if positionMs - l.chapters[i].startMs > 3000 || i == 0 { seek(to: l.chapters[i].startMs) } else { seek(to: l.chapters[i - 1].startMs) }
    }

    func goTo(chapter i: Int) {
        guard let l = loaded, l.chapters.indices.contains(i) else { return }
        seek(to: l.chapters[i].startMs)
    }

    func bookmarkHere() {
        guard let l = loaded, !l.isEpisode else { return }
        library.addBookmark(l.id, positionMs: positionMs, chapterTitle: chapter?.title ?? "")
        notice = "Bookmarked at \(Fmt.clock(positionMs))"
        onSyncWorthy?("bookmark")
    }

    // MARK: Time passing

    private func tick() {
        guard let l = loaded else { return }
        let t = av.currentTime()
        if t.isValid, t.seconds.isFinite {
            positionMs = l.offsets[index] + Int64(t.seconds * 1000)
        }
        buffering = av.timeControlStatus == .waitingToPlayAtSpecifiedRate
        guard isPlaying else { return }
        captureListening()
        ticks += 1
        if ticks % 10 == 0 { saveProgress() }
        if ticks % 600 == 0 { onSyncWorthy?("playing") }
        if listenAccumMs >= 15_000 { flushListening() }
        if ticks % 2 == 0 { updateNowPlaying(elapsedOnly: true) }
        sleep.tick(positionMs: positionMs, chapterEnd: chapterEnd)
        outroWatch()
    }

    private func captureListening() {
        let now = Date()
        if let last = lastTick, isPlaying {
            let ms = Int64(now.timeIntervalSince(last) * 1000)
            if ms > 0, ms < 5_000 { listenAccumMs += ms }
        }
        lastTick = now
    }

    private func flushListening() {
        let ms = listenAccumMs
        listenAccumMs = 0
        // Podcasts don't count toward your book stats.
        guard ms > 0, loaded?.isEpisode == false else { return }
        library.addListening(ms)
    }

    func saveProgress() {
        guard let l = loaded else { return }
        if l.isEpisode {
            if let ep = podcasts?.episode(String(l.id.dropFirst(8))) {
                ep.positionMs = positionMs
                ep.lastPlayedAt = Clock.now
                if ep.durationMs == 0, let d = av.currentItem?.duration, d.isNumeric { ep.durationMs = Int64(d.seconds * 1000) }
                try? library.context.save()
            }
        } else {
            library.setProgress(l.id, positionMs: positionMs)
        }
    }

    /// The end of each file skips the book's repeated outro.
    private func outroWatch() {
        guard let l = loaded, !l.isEpisode, let b = library.book(l.id), b.skipOutroSec > 0, index + 1 < l.urls.count else { return }
        let end = l.offsets[index] + l.durations[index]
        if l.durations[index] > Int64(b.skipOutroSec) * 1000 + 10_000, positionMs >= end - Int64(b.skipOutroSec) * 1000 {
            advanceFile()
        }
    }

    private func itemEnded(_ item: AVPlayerItem?) {
        guard item === av.currentItem, let l = loaded else { return }
        if index + 1 < l.urls.count {
            advanceFile()
        } else {
            bookEnded()
        }
    }

    /// The next file starts by itself (after the book's repeated intro).
    private func advanceFile() {
        guard let l = loaded else { return }
        index += 1
        var start = l.offsets[index]
        if !l.isEpisode, let b = library.book(l.id), b.skipIntroSec > 0, l.durations[index] >= Int64(b.skipIntroSec) * 1000 + 10_000 {
            start += Int64(b.skipIntroSec) * 1000
        }
        load(at: start)
        if isPlaying { av.playImmediately(atRate: Float(speed)) }
    }

    private func bookEnded() {
        guard let l = loaded else { return }
        captureListening()
        flushListening()
        isPlaying = false
        log(.pause, at: l.durationMs, reason: ListenEvent.reasonFinished)
        if l.isEpisode {
            if let ep = podcasts?.episode(String(l.id.dropFirst(8))) {
                podcasts?.setPlayed(ep, true)
                if let next = podcasts?.queue().first, let nextEp = podcasts?.episode(next.episodeID) {
                    loaded = nil
                    playEpisode(nextEp)
                }
            }
            return
        }
        if let b = library.book(l.id) {
            b.status = Book.statusFinished
            b.finishedAt = Clock.now
            b.lastPlayedAt = Clock.now
            b.positionMs = b.durationMs
            library.changed()
            finished = b.id
            onSyncWorthy?("finished")
            // Up Next first, then the next book in the series.
            for nextID in settings.upNext where nextID != b.id {
                if let next = library.book(nextID), !next.isExternal {
                    loaded = nil
                    playBook(nextID)
                    notice = "Up Next: \(next.displayTitle)"
                    return
                }
            }
            if settings.seriesAutoPlay, let next = nextInSeries(b) {
                loaded = nil
                playBook(next.id)
                notice = "Next in \(b.series): \(next.displayTitle)"
            }
        }
    }

    func nextInSeries(_ book: Book) -> Book? {
        let series = book.series.trimmingCharacters(in: .whitespaces)
        guard !series.isEmpty else { return nil }
        let same = library.books().filter {
            $0.id != book.id && !$0.isExternal && $0.series.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(series) == .orderedSame && $0.status != Book.statusFinished
        }
        if let idx = book.seriesIndex {
            return same.filter { ($0.seriesIndex ?? -1) > idx }.min { ($0.seriesIndex ?? 0) < ($1.seriesIndex ?? 0) }
        }
        return same.sorted { ($0.seriesIndex ?? .greatestFiniteMagnitude, $0.title.lowercased()) < ($1.seriesIndex ?? .greatestFiniteMagnitude, $1.title.lowercased()) }.first
    }

    // MARK: Smart rewind, sleep, interruptions

    /// How far smart rewind goes back after a pause of `gap`, given your setting.
    static func rewind(gapMs: Int64, baseMs: Int64) -> Int64 {
        if baseMs <= 0 || gapMs < 10_000 { return 0 }
        if gapMs < 5 * 60_000 { return max(1_000, baseMs / 2) }
        if gapMs < 3_600_000 { return baseMs }
        if gapMs < 8 * 3_600_000 { return baseMs * 2 }
        return baseMs * 3
    }

    private func smartRewind() {
        let pausedAt = UserDefaults.standard.double(forKey: "pausedAt")
        guard pausedAt > 0 else { return }
        let gap = Int64((Date().timeIntervalSince1970 - pausedAt) * 1000)
        let back = Player.rewind(gapMs: gap, baseMs: Int64(settings.autoRewindSec) * 1000)
        guard back > 0 else { return }
        seek(to: positionMs - back, logged: false)
        if gap >= 5 * 60_000 { notice = "Went back \(back / 1000)s since you paused" }
    }

    private func sleepExpired() {
        let awake = sleep.awakeMs
        pause(reason: ListenEvent.reasonSleep, awakeMs: awake)
        SleepMarks.set(bookID: loaded?.id ?? "", positionMs: positionMs, awakeMs: awake)
    }

    private func activateSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .spokenAudio, policy: .longFormAudio)
        try? s.setActive(true)
    }

    private func interrupted(_ info: [AnyHashable: Any]?) {
        guard let raw = info?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            if isPlaying {
                pause(reason: ListenEvent.reasonOtherApp)
                resumeAfterInterruption = true
            }
        } else if resumeAfterInterruption {
            resumeAfterInterruption = false
            let opts = (info?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
            if opts.contains(.shouldResume) { resume() }
        }
    }

    @ObservationIgnored private var resumeAfterInterruption = false

    private func routeChanged(_ info: [AnyHashable: Any]?) {
        guard let raw = info?[AVAudioSessionRouteChangeReasonKey] as? UInt, let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        // Headphones unplugged or disconnected: pause (iOS already stopped the sound).
        if reason == .oldDeviceUnavailable, isPlaying { pause(reason: ListenEvent.reasonUnplugged) }
    }

    // MARK: Listening log

    enum LogKind { case play, pause }

    private func log(_ kind: LogKind, at pos: Int64, reason: Int = ListenEvent.reasonNone, to: Int64 = -1) {
        guard let l = loaded else { return }
        let id = l.id
        let last = library.listenLog(id, limit: 3).first { $0.device.isEmpty }
        let now = Clock.now
        switch kind {
        case .play:
            if let last, last.kind == ListenEvent.play, now - last.at < 3_000 { return }
            insert(ListenEvent(bookID: id, at: now, kind: ListenEvent.play, positionMs: pos, speed: speed, reason: reason))
        case .pause:
            if let last, last.kind == ListenEvent.pause, abs(last.positionMs - pos) < 2_000, now - last.at < 3_000 { return }
            insert(ListenEvent(bookID: id, at: now, kind: ListenEvent.pause, positionMs: pos, toMs: to, speed: speed, reason: reason))
        }
    }

    /// A skip or jump. Quick skips in the same direction merge into one entry.
    private func moved(from: Int64, to: Int64) {
        guard let l = loaded, !quietSeek else {
            quietSeek = false
            return
        }
        let delta = to - from
        guard abs(delta) >= 1_500 else { return }
        let skipF = Int64(settings.skipForwardSec) * 1000, skipB = Int64(settings.skipBackSec) * 1000
        let isSkip = abs(delta - skipF) < 1_500 || abs(delta + skipB) < 1_500
        let now = Clock.now
        if let last = library.listenLog(l.id, limit: 3).first(where: { $0.device.isEmpty }),
           last.kind == ListenEvent.skip || last.kind == ListenEvent.jump,
           now - last.at < 8_000,
           (last.toMs - last.positionMs > 0) == (delta > 0) {
            last.at = now
            last.toMs = to
            if !(last.kind == ListenEvent.skip && isSkip) { last.kind = ListenEvent.jump }
            try? library.context.save()
            if last.kind == ListenEvent.jump, abs(last.toMs - last.positionMs) >= 30_000 { lastJump = Jump(bookID: l.id, fromMs: last.positionMs, toMs: to) }
            return
        }
        insert(ListenEvent(bookID: l.id, at: now, kind: isSkip ? ListenEvent.skip : ListenEvent.jump, positionMs: from, toMs: to, speed: speed))
        if !isSkip, abs(delta) >= 30_000 { lastJump = Jump(bookID: l.id, fromMs: from, toMs: to) }
    }

    private func insert(_ e: ListenEvent) {
        let seq = Int64(UserDefaults.standard.integer(forKey: "logSeq")) + 1
        UserDefaults.standard.set(Int(seq), forKey: "logSeq")
        e.seq = seq
        library.context.insert(e)
        try? library.context.save()
        if seq % 25 == 0 { trimLog(e.bookID) }
    }

    private func trimLog(_ bookID: String) {
        let all = library.listenLog(bookID, limit: 2000)
        for e in all.dropFirst(400) { library.context.delete(e) }
        try? library.context.save()
    }

    // MARK: Lock screen and Control Center

    private func setUpRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.resume() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.skipForwardCommand.preferredIntervals = [NSNumber(value: settings.skipForwardSec)]
        c.skipBackwardCommand.preferredIntervals = [NSNumber(value: settings.skipBackSec)]
        c.skipForwardCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.skip(seconds: self?.settings.skipForwardSec ?? 30) }; return .success }
        c.skipBackwardCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.skip(seconds: -(self?.settings.skipBackSec ?? 15)) }; return .success }
        // Headphone double / triple press arrive as next / previous: skip, like most audiobook apps.
        c.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.skip(seconds: self?.settings.skipForwardSec ?? 30) }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.skip(seconds: -(self?.settings.skipBackSec ?? 15)) }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let t = e.positionTime
            MainActor.assumeIsolated {
                guard let self else { return }
                self.seek(to: self.chapterStart + Int64(t * 1000))
            }
            return .success
        }
        c.changePlaybackRateCommand.supportedPlaybackRates = [0.8, 1.0, 1.2, 1.5, 1.8, 2.0]
        c.changePlaybackRateCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackRateCommandEvent else { return .commandFailed }
            let r = Double(e.playbackRate)
            MainActor.assumeIsolated { self?.setSpeed(r) }
            return .success
        }
        c.bookmarkCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.bookmarkHere() }; return .success }
    }

    @ObservationIgnored private var artworkFile: String?
    @ObservationIgnored private var artwork: MPMediaItemArtwork?

    /// The lock screen shows the chapter: its name, its length and how far into it you are.
    func updateNowPlaying(elapsedOnly: Bool = false) {
        let center = MPNowPlayingInfoCenter.default()
        guard let l = loaded else {
            center.nowPlayingInfo = nil
            return
        }
        let chStart = chapterStart
        let chLen = max(chapterEnd - chStart, 1)
        if elapsedOnly, var info = center.nowPlayingInfo {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs - chStart) / 1000
            info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? speed : 0
            center.nowPlayingInfo = info
            return
        }
        if artworkFile != l.coverFile {
            artworkFile = l.coverFile
            if let img = Covers.image(l.coverFile) {
                artwork = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            } else {
                artwork = nil
            }
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: chapter?.title ?? l.title,
            MPMediaItemPropertyArtist: l.subtitle,
            MPMediaItemPropertyAlbumTitle: l.title,
            MPMediaItemPropertyPlaybackDuration: Double(chLen) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs - chStart) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: speed,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        center.nowPlayingInfo = info
    }
}

/// "Fell asleep?": where the sleep timer stopped a book, and where you were last awake.
enum SleepMarks {
    struct Mark: Codable {
        var bookID: String
        var positionMs: Int64
        var awakeMs: Int64
        var at: Date
    }

    static func set(bookID: String, positionMs: Int64, awakeMs: Int64) {
        let m = Mark(bookID: bookID, positionMs: positionMs, awakeMs: awakeMs, at: Date())
        UserDefaults.standard.set(try? JSONEncoder().encode(m), forKey: "sleepMark")
    }

    static func get() -> Mark? {
        guard let d = UserDefaults.standard.data(forKey: "sleepMark"), let m = try? JSONDecoder().decode(Mark.self, from: d) else { return nil }
        // Only the morning after.
        return Date().timeIntervalSince(m.at) < 16 * 3600 ? m : nil
    }

    static func clear() { UserDefaults.standard.removeObject(forKey: "sleepMark") }
}
