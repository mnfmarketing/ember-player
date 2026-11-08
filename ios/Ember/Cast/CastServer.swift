import Foundation
import Network

/// A tiny web server on the iPhone. A Chromecast can only play audio it can fetch over the network,
/// so local and Google Drive files are shared to it on your Wi-Fi, behind a random address that
/// changes every time casting starts.
final class CastServer: @unchecked Sendable {
    enum Source {
        case file(URL)
        case drive(String)
    }

    private let q = DispatchQueue(label: "app.ember.cast-server")
    private var listener: NWListener?
    private var secret = CastServer.newSecret()
    private var sources: [String: (Source, String)] = [:]
    private(set) var port: UInt16 = 0
    var token: (@Sendable () async -> String?)?

    private static func newSecret() -> String { UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() }

    var running: Bool { listener != nil && port != 0 }

    func start() async -> Bool {
        if running { return true }
        guard let l = try? NWListener(using: .tcp, on: .any) else { return false }
        secret = Self.newSecret()
        listener = l
        l.newConnectionHandler = { [weak self] c in self?.handle(c) }
        l.start(queue: q)
        for _ in 0..<40 {
            if let p = l.port?.rawValue, p != 0 {
                port = p
                return true
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return false
    }

    func stop() {
        listener?.cancel()
        listener = nil
        port = 0
        q.async { self.sources.removeAll() }
    }

    /// The address a Chromecast can fetch this from (nil when the iPhone isn't on Wi-Fi).
    func url(_ source: Source, ext: String) -> URL? {
        guard running, let ip = Self.wifiAddress() else { return nil }
        let key = UUID().uuidString.prefix(12).lowercased()
        q.sync { sources[String(key)] = (source, ext.lowercased()) }
        let e = ext.isEmpty ? "" : "." + ext
        return URL(string: "http://\(ip):\(port)/\(secret)/\(key)\(e)")
    }

    static func wifiAddress() -> String? {
        var addrs: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addrs) == 0, let first = addrs else { return nil }
        defer { freeifaddrs(addrs) }
        var found: String?
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            let i = cur.pointee
            if let a = i.ifa_addr, a.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: i.ifa_name)
                if name == "en0" || (found == nil && name.hasPrefix("en")) {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(a, socklen_t(a.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        found = String(cString: host)
                        if name == "en0" { break }
                    }
                }
            }
            p = i.ifa_next
        }
        return found
    }

    static func mime(_ ext: String) -> String {
        switch ext.lowercased() {
        case "m4b", "m4a", "mp4", "aac": return "audio/mp4"
        case "mp3": return "audio/mpeg"
        case "ogg", "oga", "opus": return "audio/ogg"
        case "flac": return "audio/flac"
        case "wav": return "audio/wav"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        default: return "application/octet-stream"
        }
    }

    // MARK: Requests

    private func handle(_ c: NWConnection) {
        c.start(queue: q)
        readHead(c, Data())
    }

    private func readHead(_ c: NWConnection, _ buf: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, done, err in
            guard let self else { return }
            var b = buf
            if let data { b.append(data) }
            if let r = b.range(of: Data("\r\n\r\n".utf8)) {
                self.respond(c, String(decoding: b[..<r.lowerBound], as: UTF8.self))
            } else if done || err != nil || b.count > 65_536 {
                c.cancel()
            } else {
                self.readHead(c, b)
            }
        }
    }

    private func fail(_ c: NWConnection, _ code: Int) {
        let head = "HTTP/1.1 \(code) \(code == 404 ? "Not Found" : "Bad Request")\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        c.send(content: Data(head.utf8), completion: .contentProcessed { _ in c.cancel() })
    }

    private func respond(_ c: NWConnection, _ head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { return fail(c, 400) }
        let isHead = parts[0] == "HEAD"
        let comps = parts[1].split(separator: "?")[0].split(separator: "/")
        guard comps.count == 2, comps[0] == secret, let key = comps[1].split(separator: ".").first, let hit = sources[String(key)] else {
            return fail(c, 404)
        }
        let (src, ext) = hit
        var start: Int64 = 0
        var end: Int64?
        var ranged = false
        for l in lines.dropFirst() where l.lowercased().hasPrefix("range:") {
            let v = l.dropFirst(6).trimmingCharacters(in: .whitespaces)
            guard v.hasPrefix("bytes=") else { continue }
            let r = v.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            if r.count == 2 {
                start = Int64(r[0]) ?? 0
                end = Int64(r[1])
                ranged = true
            }
        }
        switch src {
        case .file(let url): serveFile(c, url, ext: ext, start: start, end: end, ranged: ranged, headOnly: isHead)
        case .drive(let id): serveDrive(c, id, ext: ext, start: start, end: end, ranged: ranged, headOnly: isHead)
        }
    }

    private func header(total: Int64, start: Int64, end: Int64, ranged: Bool, ext: String) -> Data {
        var h = ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
        h += "Content-Type: \(Self.mime(ext))\r\nAccept-Ranges: bytes\r\nContent-Length: \(max(end - start + 1, 0))\r\n"
        if ranged { h += "Content-Range: bytes \(start)-\(end)/\(total)\r\n" }
        h += "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        return Data(h.utf8)
    }

    private func serveFile(_ c: NWConnection, _ url: URL, ext: String, start: Int64, end: Int64?, ranged: Bool, headOnly: Bool) {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value,
              let fh = try? FileHandle(forReadingFrom: url) else { return fail(c, 404) }
        let s = min(max(start, 0), max(size - 1, 0))
        let e = min(end ?? (size - 1), size - 1)
        c.send(content: header(total: size, start: s, end: e, ranged: ranged, ext: ext), completion: .contentProcessed { [weak self] err in
            guard err == nil, !headOnly else {
                try? fh.close()
                c.cancel()
                return
            }
            try? fh.seek(toOffset: UInt64(s))
            self?.pump(c, fh, left: e - s + 1)
        })
    }

    private func pump(_ c: NWConnection, _ fh: FileHandle, left: Int64) {
        guard left > 0, let data = try? fh.read(upToCount: Int(min(left, 256 << 10))), !data.isEmpty else {
            try? fh.close()
            c.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
            return
        }
        c.send(content: data, completion: .contentProcessed { [weak self] err in
            if err != nil {
                try? fh.close()
                c.cancel()
                return
            }
            self?.pump(c, fh, left: left - Int64(data.count))
        })
    }

    private func serveDrive(_ c: NWConnection, _ id: String, ext: String, start: Int64, end: Int64?, ranged: Bool, headOnly: Bool) {
        let token = token
        Task {
            guard let t = await token?() else { return self.q.async { self.fail(c, 404) } }
            let chunk: Int64 = 4 << 20
            var pos = max(start, 0)
            var total: Int64 = -1
            var last: Int64 = end ?? -1
            var sentHead = false
            while true {
                let to = last >= 0 ? min(pos + chunk - 1, last) : pos + chunk - 1
                var r = URLRequest(url: DriveFiles.mediaURL(id))
                r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
                r.setValue("bytes=\(pos)-\(to)", forHTTPHeaderField: "Range")
                guard let (data, resp) = try? await URLSession.shared.data(for: r), let http = resp as? HTTPURLResponse,
                      http.statusCode == 206 || http.statusCode == 200 else { break }
                if total < 0 {
                    total = Int64(data.count)
                    if let cr = http.value(forHTTPHeaderField: "Content-Range"), let slash = cr.lastIndex(of: "/") {
                        total = Int64(cr[cr.index(after: slash)...]) ?? total
                    }
                    if last < 0 || last >= total { last = total - 1 }
                }
                if !sentHead {
                    sentHead = true
                    let ok = await send(c, header(total: total, start: pos, end: last, ranged: ranged, ext: ext))
                    if !ok || headOnly { break }
                }
                guard await send(c, data) else { break }
                pos += Int64(data.count)
                if data.isEmpty || pos > last { break }
            }
            q.async {
                if !sentHead { self.fail(c, 404) } else { c.cancel() }
            }
        }
    }

    private func send(_ c: NWConnection, _ data: Data) async -> Bool {
        await withCheckedContinuation { cont in
            c.send(content: data, completion: .contentProcessed { err in cont.resume(returning: err == nil) })
        }
    }
}
