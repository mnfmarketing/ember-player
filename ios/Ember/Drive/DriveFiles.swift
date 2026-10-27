import Foundation
import AVFoundation
import UniformTypeIdentifiers

/// Books in Google Drive (read-only): browse folders, read files, and stream audio to the player.
/// Audio is addressed as `emberdrive://<file id>/<file name>`; `DriveStream` answers the player's
/// byte-range requests for those, adding the sign-in token, so nothing is downloaded unless asked.
@MainActor
final class DriveFiles {

    struct File: Identifiable, Hashable {
        var id: String
        var name: String
        var mimeType: String
        var size: Int64
        var isFolder: Bool { mimeType == "application/vnd.google-apps.folder" }
        var ext: String { (name as NSString).pathExtension.lowercased() }
        var base: String { (name as NSString).deletingPathExtension }
        var isAudio: Bool { !isFolder && (mimeType.hasPrefix("audio/") || Scanner.audioExt.contains(ext)) }
        var isImage: Bool { !isFolder && (mimeType.hasPrefix("image/") || Scanner.imageExt.contains(ext)) }
    }

    enum Failure: Error { case signedOut, http(Int) }

    let auth: GoogleAuth

    init(auth: GoogleAuth) {
        self.auth = auth
        DriveStream.shared.token = { [weak auth] in await auth?.token() }
    }

    var connected: Bool { auth.has(GoogleAuth.driveReadOnly) }

    func connect() async -> Bool { await auth.connect(scopes: [GoogleAuth.driveReadOnly]) }

    /// A folder's contents ("root" is My Drive; "shared" lists what's shared with you).
    func list(_ folderID: String, foldersOnly: Bool = false) async throws -> [File] {
        var q = folderID == "shared" ? "sharedWithMe and trashed = false" : "'\(folderID)' in parents and trashed = false"
        if foldersOnly { q += " and mimeType = 'application/vnd.google-apps.folder'" }
        var out: [File] = []
        var page: String?
        repeat {
            var c = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            c.queryItems = [
                .init(name: "q", value: q),
                .init(name: "fields", value: "nextPageToken, files(id, name, mimeType, size)"),
                .init(name: "pageSize", value: "1000"),
                .init(name: "orderBy", value: "folder, name"),
                .init(name: "supportsAllDrives", value: "true"),
                .init(name: "includeItemsFromAllDrives", value: "true"),
            ]
            if let page { c.queryItems?.append(.init(name: "pageToken", value: page)) }
            let data = try await get(c.url!)
            let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            for f in o["files"] as? [[String: Any]] ?? [] {
                out.append(File(id: f["id"] as? String ?? "", name: f["name"] as? String ?? "", mimeType: f["mimeType"] as? String ?? "",
                                size: Int64(f["size"] as? String ?? "") ?? 0))
            }
            page = o["nextPageToken"] as? String
        } while page != nil
        return out
    }

    func download(_ fileID: String) async throws -> Data {
        try await get(Self.mediaURL(fileID))
    }

    /// Saves a file for listening offline. Returns where it went.
    func save(_ fileID: String, ext: String) async throws -> URL {
        guard let token = await auth.token() else { throw Failure.signedOut }
        var r = URLRequest(url: Self.mediaURL(fileID))
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (tmp, resp) = try await URLSession.shared.download(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw Failure.http(code) }
        let dest = Self.localURL(fileID, ext: ext)
        try? FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dest
        try? d.setResourceValues(values)
        return dest
    }

    private func get(_ url: URL) async throws -> Data {
        guard let token = await auth.token() else { throw Failure.signedOut }
        var r = URLRequest(url: url)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw code == 401 ? Failure.signedOut : Failure.http(code) }
        return data
    }

    nonisolated static func mediaURL(_ fileID: String) -> URL {
        URL(string: "https://www.googleapis.com/drive/v3/files/\(fileID)?alt=media&supportsAllDrives=true")!
    }

    /// Where a downloaded Drive file lives (it may not be there).
    nonisolated static func localURL(_ fileID: String, ext: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Drive", isDirectory: true)
            .appendingPathComponent(ext.isEmpty ? fileID : "\(fileID).\(ext)")
    }

    nonisolated static func streamURL(_ file: File) -> URL {
        var c = URLComponents()
        c.scheme = DriveStream.scheme
        c.host = file.id
        c.path = "/" + file.name
        return c.url!
    }
}

/// Answers AVFoundation's requests for `emberdrive://` URLs with byte ranges from Google Drive.
final class DriveStream: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "emberdrive"
    static let shared = DriveStream()
    static let chunk: Int64 = 4 << 20
    let queue = DispatchQueue(label: "app.ember.drive-stream")
    var token: (@Sendable () async -> String?)?

    /// An asset for any URL; Drive ones are routed through this loader.
    static func asset(_ url: URL) -> AVURLAsset {
        let a = AVURLAsset(url: url)
        if url.scheme == scheme { a.resourceLoader.setDelegate(shared, queue: shared.queue) }
        return a
    }

    static func fileID(_ url: URL) -> String? { url.scheme == scheme ? url.host : nil }

    func resourceLoader(_ loader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = request.request.url, let id = url.host else { return false }
        let ext = url.pathExtension.lowercased()
        let wantInfo = request.contentInformationRequest != nil
        var start: Int64 = 0
        var length: Int64 = 2
        if let d = request.dataRequest {
            start = d.currentOffset != 0 ? d.currentOffset : d.requestedOffset
            length = d.requestsAllDataToEndOfResource ? -1 : Int64(d.requestedLength) - (start - d.requestedOffset)
        }
        // At most a few MB per answer (about 8 minutes of a typical audiobook); AVFoundation asks again for the rest.
        if length < 0 || length > Self.chunk { length = Self.chunk }
        let box = RequestBox(request)
        Task {
            guard let token = await self.token?() else {
                self.queue.async { box.request.finishLoading(with: DriveFiles.Failure.signedOut) }
                return
            }
            var r = URLRequest(url: DriveFiles.mediaURL(id))
            r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            r.setValue("bytes=\(start)-\(start + max(length, 1) - 1)", forHTTPHeaderField: "Range")
            do {
                let (data, resp) = try await URLSession.shared.data(for: r)
                let http = resp as? HTTPURLResponse
                guard let http, http.statusCode == 206 || http.statusCode == 200 else {
                    self.queue.async { box.request.finishLoading(with: DriveFiles.Failure.http(http?.statusCode ?? 0)) }
                    return
                }
                var total = Int64(data.count)
                if let range = http.value(forHTTPHeaderField: "Content-Range"), let slash = range.lastIndex(of: "/") {
                    total = Int64(range[range.index(after: slash)...]) ?? total
                }
                self.queue.async {
                    if box.request.isCancelled { return }
                    if wantInfo, let info = box.request.contentInformationRequest {
                        info.contentType = UTType(filenameExtension: ext)?.identifier ?? UTType.audio.identifier
                        info.contentLength = total
                        info.isByteRangeAccessSupported = true
                    }
                    // A 200 means the whole file came back: hand over just the part that was asked for.
                    var chunk = data
                    if http.statusCode == 200, start > 0, start < Int64(data.count) {
                        chunk = data.subdata(in: Int(start)..<min(data.count, Int(start + length)))
                    }
                    box.request.dataRequest?.respond(with: chunk)
                    box.request.finishLoading()
                }
            } catch {
                self.queue.async { box.request.finishLoading(with: error) }
            }
        }
        return true
    }
}

private final class RequestBox: @unchecked Sendable {
    let request: AVAssetResourceLoadingRequest
    init(_ r: AVAssetResourceLoadingRequest) { request = r }
}
