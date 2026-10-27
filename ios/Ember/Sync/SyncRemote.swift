import Foundation

/// Where the sync files live: Drive's hidden app folder, or a plain folder for the simulator checks.
protocol SyncRemote {
    func list() async throws -> [String: String]
    func read(_ name: String) async throws -> String?
    @discardableResult func write(_ name: String, _ text: String) async throws -> String?
    func stamp(_ name: String) async throws -> String?
}

struct SyncError: LocalizedError {
    var message: String
    var errorDescription: String? { message }
}

/// Drive's appDataFolder: a hidden folder only Ember can see (the same one the Android app uses).
final class DriveAppDataRemote: SyncRemote {
    private let token: () async -> String?
    private var ids: [String: String] = [:]
    private static let files = "https://www.googleapis.com/drive/v3/files"
    private static let upload = "https://www.googleapis.com/upload/drive/v3/files"

    init(token: @escaping () async -> String?) {
        self.token = token
    }

    private func auth() async throws -> String {
        guard let t = await token() else { throw SyncError(message: "Google Drive needs signing in again for sync") }
        return t
    }

    private func send(_ method: String, _ url: String, body: Data? = nil, type: String? = nil) async throws -> Data? {
        var req = URLRequest(url: URL(string: url)!, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue("Bearer \(try await auth())", forHTTPHeaderField: "Authorization")
        if let body {
            req.httpBody = body
            req.setValue(type ?? "application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 404 { return nil }
        guard (200..<300).contains(code) else {
            throw SyncError(message: code == 401 || code == 403 ? "Google Drive turned sync down (\(code)). Try turning sync off and on." : "Google Drive returned \(code)")
        }
        return data
    }

    func list() async throws -> [String: String] {
        var out: [String: String] = [:]
        var newest: [String: String] = [:]
        var page: String?
        repeat {
            var c = URLComponents(string: Self.files)!
            c.queryItems = [.init(name: "spaces", value: "appDataFolder"), .init(name: "pageSize", value: "1000"),
                            .init(name: "fields", value: "nextPageToken,files(id,name,version,modifiedTime)")]
            if let page { c.queryItems?.append(.init(name: "pageToken", value: page)) }
            guard let data = try await send("GET", c.url!.absoluteString),
                  let j = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { break }
            for f in j["files"] as? [[String: Any]] ?? [] {
                let name = f["name"] as? String ?? ""
                let mod = f["modifiedTime"] as? String ?? ""
                if newest[name] == nil || mod > newest[name]! {
                    newest[name] = mod
                    ids[name] = f["id"] as? String
                    out[name] = (f["version"] as? String) ?? "\(f["version"] ?? "")"
                }
            }
            page = (j["nextPageToken"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } while page != nil
        return out
    }

    private func id(_ name: String) async throws -> String? {
        if let i = ids[name] { return i }
        _ = try await list()
        return ids[name]
    }

    func read(_ name: String) async throws -> String? {
        guard let i = try await id(name) else { return nil }
        guard let d = try await send("GET", "\(Self.files)/\(i)?alt=media") else {
            ids[name] = nil
            return nil
        }
        return String(decoding: d, as: UTF8.self)
    }

    func stamp(_ name: String) async throws -> String? {
        guard let i = try await id(name), let d = try await send("GET", "\(Self.files)/\(i)?fields=version"),
              let j = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
        return (j["version"] as? String) ?? "\(j["version"] ?? "")"
    }

    @discardableResult
    func write(_ name: String, _ text: String) async throws -> String? {
        if let i = try await id(name) {
            if let d = try await send("PATCH", "\(Self.upload)/\(i)?uploadType=media&fields=version", body: Data(text.utf8)),
               let j = try JSONSerialization.jsonObject(with: d) as? [String: Any] {
                return (j["version"] as? String) ?? "\(j["version"] ?? "")"
            }
            ids[name] = nil
        }
        let boundary = "ember\(UUID().uuidString)"
        let meta = try JSONSerialization.data(withJSONObject: ["name": name, "parents": ["appDataFolder"]])
        var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body += meta
        body += Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8)
        body += Data(text.utf8)
        body += Data("\r\n--\(boundary)--\r\n".utf8)
        guard let d = try await send("POST", "\(Self.upload)?uploadType=multipart&fields=id,version", body: body, type: "multipart/related; boundary=\(boundary)"),
              let j = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
        ids[name] = j["id"] as? String
        return (j["version"] as? String) ?? "\(j["version"] ?? "")"
    }
}

/// A plain folder standing in for Drive (simulator checks).
final class FolderRemote: SyncRemote {
    let dir: URL

    init(dir: URL) {
        self.dir = dir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func stampOf(_ url: URL) -> String? {
        guard let a = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        let m = (a[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return "\(Int64(m * 1000))-\((a[.size] as? NSNumber)?.intValue ?? 0)"
    }

    func list() async throws -> [String: String] {
        var out: [String: String] = [:]
        for u in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] where !u.lastPathComponent.hasSuffix(".tmp") {
            out[u.lastPathComponent] = stampOf(u)
        }
        return out
    }

    func read(_ name: String) async throws -> String? { try? String(contentsOf: dir.appendingPathComponent(name), encoding: .utf8) }

    @discardableResult
    func write(_ name: String, _ text: String) async throws -> String? {
        let url = dir.appendingPathComponent(name)
        try text.write(to: url, atomically: true, encoding: .utf8)
        return stampOf(url)
    }

    func stamp(_ name: String) async throws -> String? { stampOf(dir.appendingPathComponent(name)) }
}
