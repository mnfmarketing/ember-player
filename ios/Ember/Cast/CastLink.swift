import Foundation
import AVFoundation
import SwiftUI
import GoogleCast

/// Chromecast: finds Cast devices on your Wi-Fi and hands what's playing to one, keeping your place.
/// While casting, the player's buttons (and the lock screen's) control the Chromecast.
@MainActor
@Observable
final class CastLink {
    /// The Chromecast you're connected to.
    private(set) var deviceName: String?
    /// What's playing is on the Chromecast.
    private(set) var active = false
    @ObservationIgnored let server = CastServer()
    @ObservationIgnored weak var player: Player?
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var keepAlive: AVAudioPlayer?
    @ObservationIgnored private var sawPlaying = false
    @ObservationIgnored private var lastMs: Int64?

    func setUp() {
        let options = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID))
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        GCKCastContext.setSharedInstanceWith(options)
        server.token = DriveStream.shared.token
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    private var client: GCKRemoteMediaClient? {
        GCKCastContext.sharedInstance().sessionManager.currentCastSession?.remoteMediaClient
    }

    private func poll() {
        let sm = GCKCastContext.sharedInstance().sessionManager
        let name: String? = sm.connectionState == .connected ? (sm.currentCastSession?.device.friendlyName ?? "Chromecast") : nil
        if name != deviceName {
            let was = deviceName
            deviceName = name
            if name != nil, was == nil {
                Task {
                    guard await server.start() else { return }
                    player?.castStarted()
                }
            } else if name == nil, was != nil {
                let at = lastMs
                active = false
                stopKeepAlive()
                server.stop()
                player?.castEnded(fileMs: at)
            }
        }
        guard active, let c = client, let status = c.mediaStatus else { return }
        lastMs = Int64(c.approximateStreamPosition() * 1000)
        if status.playerState == .playing || status.playerState == .buffering { sawPlaying = true }
        if status.playerState == .idle, status.idleReason == .finished, sawPlaying {
            sawPlaying = false
            player?.castItemEnded()
        }
    }

    /// Where the Chromecast is in the current file.
    var fileMs: Int64? {
        guard active, let c = client, c.mediaStatus != nil else { return nil }
        return Int64(c.approximateStreamPosition() * 1000)
    }

    /// Sends one file of what's playing to the Chromecast.
    func load(_ l: Player.Loaded, index: Int, atMs: Int64, rate: Double, play: Bool) {
        guard let client else { return }
        let src = l.urls[index]
        let ext = src.pathExtension
        let url: URL?
        if src.isFileURL {
            url = server.url(.file(src), ext: ext)
        } else if let id = DriveStream.fileID(src) {
            url = server.url(.drive(id), ext: ext)
        } else {
            url = src
        }
        guard let url else {
            player?.notice = "Casting needs your \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone") on Wi-Fi"
            return
        }
        let meta = GCKMediaMetadata(metadataType: .musicTrack)
        meta.setString(l.title, forKey: kGCKMetadataKeyTitle)
        meta.setString(l.subtitle, forKey: kGCKMetadataKeyArtist)
        if let cu = Covers.url(l.coverFile), let cover = server.url(.file(cu), ext: cu.pathExtension) {
            meta.addImage(GCKImage(url: cover, width: 600, height: 600))
        }
        let b = GCKMediaInformationBuilder(contentURL: url)
        b.streamType = .buffered
        b.contentType = CastServer.mime(ext)
        b.metadata = meta
        let o = GCKMediaLoadOptions()
        o.playPosition = Double(atMs) / 1000
        o.autoplay = play
        o.playbackRate = Float(rate)
        client.loadMedia(b.build(), with: o)
        active = true
        sawPlaying = false
        lastMs = atMs
        startKeepAlive()
    }

    func play() { client?.play() }
    func pause() { client?.pause() }

    func seek(fileMs: Int64) {
        let o = GCKMediaSeekOptions()
        o.interval = Double(fileMs) / 1000
        o.resumeState = .unchanged
        client?.seek(with: o)
        lastMs = fileMs
    }

    func setRate(_ r: Double) { client?.setPlaybackRate(Float(r)) }

    /// iOS stops apps that aren't playing sound in the background, which would stop the server the
    /// Chromecast reads from. So while casting, Ember plays silence.
    private func startKeepAlive() {
        guard keepAlive == nil else { return }
        let rate = 8000, seconds = 2
        var wav = Data()
        func u32(_ v: Int) { var x = UInt32(v).littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: Int) { var x = UInt16(v).littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        let bytes = rate * seconds * 2
        wav.append(Data("RIFF".utf8)); u32(36 + bytes); wav.append(Data("WAVEfmt ".utf8))
        u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        wav.append(Data("data".utf8)); u32(bytes)
        wav.append(Data(count: bytes))
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        keepAlive = try? AVAudioPlayer(data: wav)
        keepAlive?.numberOfLoops = -1
        keepAlive?.volume = 0
        keepAlive?.play()
    }

    private func stopKeepAlive() {
        keepAlive?.stop()
        keepAlive = nil
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, policy: .longFormAudio)
    }
}

/// Google's Cast button: picks a Chromecast (or stops casting).
struct CastButton: UIViewRepresentable {
    var tint: UIColor = .white

    func makeUIView(context: Context) -> GCKUICastButton {
        let b = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 24, height: 24))
        b.tintColor = tint
        return b
    }

    func updateUIView(_ v: GCKUICastButton, context: Context) { v.tintColor = tint }
}
