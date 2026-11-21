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

/// Long books can have an index bigger than Ember used to read (64 MB): the big sample tables are
/// skipped now, and the length comes from the file's own header.
final class BigIndexTests: XCTestCase {
    private final class Memory: Mp4Chapters.Source {
        let bytes: [UInt8]
        var size: Int64 { Int64(bytes.count) }
        init(_ b: [UInt8]) { bytes = b }
        func read(_ at: Int64, _ count: Int) async -> [UInt8] {
            guard at >= 0, at < size else { return [] }
            return Array(bytes[Int(at)..<min(Int(at) + count, bytes.count)])
        }
    }

    private func be32(_ v: Int) -> [UInt8] { [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }
    private func box(_ type: String, _ body: [UInt8]) -> [UInt8] { be32(body.count + 8) + Array(type.utf8) + body }

    func testHugeIndexStillGivesChaptersAndLength() async {
        // mvhd v0: version/flags, created, modified, timescale 1000, duration 7_200_000 (2 hours)
        let mvhd = box("mvhd", be32(0) + be32(0) + be32(0) + be32(1000) + be32(7_200_000) + [UInt8](repeating: 0, count: 80))
        var chpl: [UInt8] = [1, 0, 0, 0, 0, 0, 0, 0, 2]
        for (ms, t) in [(0, "First"), (3_600_000, "Second")] {
            let v = UInt64(ms) * 10_000
            chpl += (0..<8).map { UInt8((v >> (56 - 8 * $0)) & 0xFF) }
            chpl += [UInt8(t.utf8.count)] + Array(t.utf8)
        }
        let bigTable = box("stbl", box("stsz", [UInt8](repeating: 0, count: 3 << 20)))
        let trak = box("trak", box("mdia", box("minf", bigTable)))
        let moov = box("moov", mvhd + trak + box("udta", box("chpl", chpl)))
        let file = box("ftyp", Array("M4B ".utf8) + be32(0)) + moov
        let info = await Mp4Chapters.infoOf(Memory(file))
        XCTAssertEqual(info.durationMs, 7_200_000)
        XCTAssertEqual(info.chapters.map(\.title), ["First", "Second"])
        XCTAssertEqual(info.chapters.map(\.startMs), [0, 3_600_000])
    }
}
