import XCTest
import AVFoundation
import UniformTypeIdentifiers
@testable import Ember

/// Google Drive books, end to end: a little server on the simulator stands in for Drive, and the
/// player streams through Ember's Drive loader. Also chapters read from inside files (local and
/// "Drive"), the way Ember on Android reads them.
@MainActor
final class DriveStreamTests: XCTestCase {
    private var server: CastServer!
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        server = CastServer()
        let started = await server.start()
        XCTAssertTrue(started, "the test server didn't start")
        DriveStream.shared.token = { "test" }
    }

    override func tearDown() async throws {
        DriveFiles.testMediaURL = nil
        server.stop()
    }

    /// A tone as an .m4b (written as .m4a, then renamed, as audiobook tools do).
    private func book(_ name: String, seconds: Int, freq: Double) -> URL {
        let m4a = dir.appendingPathComponent(name + ".m4a"), m4b = dir.appendingPathComponent(name + ".m4b")
        Demo.tone(m4a, seconds: seconds, freq: freq)
        try? FileManager.default.moveItem(at: m4a, to: m4b)
        return m4b
    }

    private func serve(_ file: URL) {
        let url = server.url(.file(file), ext: file.pathExtension, host: "127.0.0.1")!
        DriveFiles.testMediaURL = { _ in url }
    }

    func testContentTypesArePlayable() {
        let known = Set(AVURLAsset.audiovisualTypes().map(\.rawValue))
        for ext in ["m4b", "m4a", "mp3", "mp4"] {
            XCTAssertTrue(known.contains(DriveStream.contentType(ext)), "\(ext) → \(DriveStream.contentType(ext)) isn't a type AVFoundation plays")
        }
        print("m4b's own type: \(UTType(filenameExtension: "m4b")?.identifier ?? "none"); playable: \(known.contains(UTType(filenameExtension: "m4b")?.identifier ?? ""))")
    }

    func testDriveM4BStreamsAndPlays() async throws {
        let file = book("Book", seconds: 10, freq: 440)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        serve(file)
        let item = AVPlayerItem(asset: DriveStream.asset(URL(string: "emberdrive://abc123/Book.m4b")!))
        let player = AVPlayer(playerItem: item)
        for _ in 0..<150 where item.status == .unknown { try await Task.sleep(for: .milliseconds(100)) }
        XCTAssertEqual(item.status, .readyToPlay, "status \(item.status.rawValue): \(String(describing: item.error))")
        let tracks = try await item.asset.loadTracks(withMediaType: .audio)
        XCTAssertFalse(tracks.isEmpty, "no audio track came through the Drive stream")
        player.play()
        try await Task.sleep(for: .seconds(3))
        XCTAssertGreaterThan(player.currentTime().seconds, 1, "playback didn't move")
        player.pause()
    }

    func testChaptersInsideFiles() async throws {
        let file = book("Chapters", seconds: 10, freq: 330)
        let want: [(Int64, String)] = [(0, "Opening"), (3_000, "The Middle"), (7_000, "The End")]
        let r = await Mp4TagWriter.write(file, tags: nil, chapters: want, totalMs: 10_000)
        XCTAssertEqual(r, .ok)
        let local = await Mp4Chapters.read(file)
        XCTAssertEqual(local.map(\.title), want.map(\.1))
        XCTAssertEqual(local.map(\.startMs), want.map(\.0))
        serve(file)
        let remote = await Mp4Chapters.read(URL(string: "emberdrive://chap42/Chapters.m4b")!)
        XCTAssertEqual(remote.map(\.title), want.map(\.1))
    }

    func testTagWriterKeepsFilePlayable() async throws {
        let file = book("Tags", seconds: 6, freq: 550)
        let r = await Mp4TagWriter.write(file, tags: .init(title: "A Test Book", author: "Ada", narrator: "Sam", description: "About testing."), chapters: nil, totalMs: 6_000)
        XCTAssertEqual(r, .ok)
        XCTAssertTrue(Mp4TagWriter.hasOriginal(file))
        XCTAssertEqual(Mp4TagWriter.restoreOriginal(file), .ok)
        let d = try await AVURLAsset(url: file).load(.duration)
        XCTAssertGreaterThan(d.seconds, 5)
    }
}
