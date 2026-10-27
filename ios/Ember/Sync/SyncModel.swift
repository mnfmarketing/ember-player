import Foundation
import CryptoKit

/*
 Sync between devices through a hidden Ember folder on Google Drive.
 This is the same format and the same rules as the Android app (SyncModel.kt), item for item:
 each synced thing is an item with a key, a value (canonical JSON text), when it last changed,
 on which device, and what it belongs to. Each device keeps a "shadow" of the items after its
 last sync; library vs shadow = what changed here, shadow vs Drive = what changed elsewhere.
 When both changed, the newest change wins. Unit-tested in SyncModelTests (the same cases as
 Android's SyncMergeTest).
 */

/// One synced item. `v` nil means deleted (kept so the deletion reaches other devices).
struct SyncEntry: Equatable {
    var v: String?
    var t: Int64
    var d: String
    var o: String
}

/// An item as it is in this library now. `hinted`: it carries its own time (last played) in `hint`.
struct LocalItem {
    var v: String
    var o: String
    var hint: Int64 = 0
    var hinted: Bool = false
}

/// A value for `Canon.obj`.
enum CanonValue {
    case str(String)
    case int(Int64)
    case bool(Bool)
    case list([String])
    case null
}

extension CanonValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral {
    init(stringLiteral value: String) { self = .str(value) }
    init(integerLiteral value: Int64) { self = .int(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
}

/// Canonical JSON: sorted keys, whole numbers only, the same text on every device for the same value.
enum Canon {

    static func obj(_ pairs: [(String, CanonValue)]) -> String {
        var out = "{"
        for (i, (k, v)) in pairs.sorted(by: { $0.0 < $1.0 }).enumerated() {
            if i > 0 { out += "," }
            str(&out, k)
            out += ":"
            switch v {
            case .str(let s): str(&out, s)
            case .int(let n): out += String(n)
            case .bool(let b): out += b ? "true" : "false"
            case .null: out += "null"
            case .list(let l):
                out += "["
                for (j, s) in l.enumerated() {
                    if j > 0 { out += "," }
                    str(&out, s)
                }
                out += "]"
            }
        }
        return out + "}"
    }

    /// The canonical form of any JSON text (nil for nil or unreadable text).
    static func of(_ text: String?) -> String? {
        guard let text, let data = text.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        var out = ""
        write(&out, any)
        return out
    }

    static func same(_ a: String?, _ b: String?) -> Bool {
        if a == b { return true }
        guard let a, let b else { return false }
        return of(a) == of(b)
    }

    private static func write(_ out: inout String, _ v: Any) {
        switch v {
        case is NSNull:
            out += "null"
        case let d as [String: Any]:
            out += "{"
            for (i, k) in d.keys.sorted().enumerated() {
                if i > 0 { out += "," }
                str(&out, k)
                out += ":"
                write(&out, d[k]!)
            }
            out += "}"
        case let a as [Any]:
            out += "["
            for (i, x) in a.enumerated() {
                if i > 0 { out += "," }
                write(&out, x)
            }
            out += "]"
        case let s as String:
            str(&out, s)
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                out += n.boolValue ? "true" : "false"
            } else {
                let d = n.doubleValue
                if d == d.rounded(), abs(d) < 9.0e15 { out += String(Int64(d)) } else { out += String(d) }
            }
        default:
            str(&out, "\(v)")
        }
    }

    private static func str(_ out: inout String, _ s: String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 {
                    out += String(format: "\\u%04x", u.value)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }
}

/// Keys that identify the same book or show on every device.
enum SyncKeys {

    private static let letters: Set<Unicode.GeneralCategory> = [
        .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber,
    ]
    private static let marks: Set<Unicode.GeneralCategory> = [.nonspacingMark, .spacingMark, .enclosingMark]

    /// Lower case, no accents, letters and digits only ("The Lantern Thief" → "thelanternthief").
    static func norm(_ s: String) -> String {
        var noMarks = String.UnicodeScalarView()
        for u in s.decomposedStringWithCanonicalMapping.unicodeScalars where !marks.contains(u.properties.generalCategory) {
            noMarks.append(u)
        }
        var out = String.UnicodeScalarView()
        // Same as Android (which works on 16-bit characters): letters and digits outside that range are dropped.
        for u in String(noMarks).lowercased().unicodeScalars where u.value <= 0xFFFF && letters.contains(u.properties.generalCategory) {
            out.append(u)
        }
        return String(out)
    }

    /// A book's key: its title and author ("x|" in front for books in other apps). Nil without a title.
    static func book(title: String, author: String, external: Bool) -> String? {
        let t = norm(title)
        if t.isEmpty { return nil }
        return (external ? "x|" : "") + t + "|" + norm(author)
    }

    static func show(_ feedURL: String) -> String { "f|" + feedURL.trimmingCharacters(in: .whitespaces) }

    static func episode(feed: String, guid: String) -> String { feed.trimmingCharacters(in: .whitespaces) + "|" + guid }

    static func logFile(_ bookKey: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(bookKey.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "log-" + hex.prefix(20) + ".json"
    }

    static func deletable(_ key: String) -> Bool {
        key.hasPrefix("mark/") || key.hasPrefix("clip/") || key.hasPrefix("shelf/") || key.hasPrefix("shelfbook/") || key.hasPrefix("show/")
    }
}

/// The synced state as stored in state.json (and as each device's shadow).
struct SyncState {
    static let format = 1

    var items: [String: SyncEntry] = [:]
    /// Device id → (name, last seen).
    var devices: [String: (name: String, seen: Int64)] = [:]
    /// The shadow only: books and shows present here at the last sync.
    var owners: Set<String> = []
    /// The shadow only: items that couldn't go into this library yet.
    var unapplied: Set<String> = []

    func toJSON() -> String {
        var root: [String: Any] = ["app": "Ember", "sync": SyncState.format]
        var items: [String: Any] = [:]
        for (k, e) in self.items {
            var j: [String: Any] = ["t": e.t, "d": e.d, "o": e.o]
            if let v = e.v { j["v"] = v }
            items[k] = j
        }
        root["items"] = items
        var devs: [String: Any] = [:]
        for (id, d) in devices { devs[id] = ["name": d.name, "seen": d.seen] }
        root["devices"] = devs
        if !owners.isEmpty { root["owners"] = owners.sorted() }
        if !unapplied.isEmpty { root["unapplied"] = unapplied.sorted() }
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    static func parse(_ text: String?) -> SyncState {
        var s = SyncState()
        guard let text, let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return s }
        if let items = root["items"] as? [String: Any] {
            for (k, any) in items {
                guard let j = any as? [String: Any] else { continue }
                s.items[k] = SyncEntry(v: j["v"] as? String, t: int64(j["t"]), d: j["d"] as? String ?? "", o: j["o"] as? String ?? "")
            }
        }
        if let devs = root["devices"] as? [String: Any] {
            for (id, any) in devs {
                guard let j = any as? [String: Any] else { continue }
                s.devices[id] = (j["name"] as? String ?? "", int64(j["seen"]))
            }
        }
        if let a = root["owners"] as? [String] { s.owners = Set(a) }
        if let a = root["unapplied"] as? [String] { s.unapplied = Set(a) }
        return s
    }
}

func int64(_ any: Any?) -> Int64 {
    if let n = any as? NSNumber { return n.int64Value }
    if let s = any as? String { return Int64(s) ?? 0 }
    return 0
}

enum SyncMerge {

    static let tombstoneDays: Int64 = 120

    struct Result {
        var merged: [String: SyncEntry]
        var apply: [String: SyncEntry]
        var won: Set<String>
        var upload: Bool
    }

    static func merge(
        remote: [String: SyncEntry],
        shadow: [String: SyncEntry],
        local: [String: LocalItem],
        ownersNow: Set<String>,
        ownersBefore: Set<String>,
        me: String,
        now: Int64,
        frozen: Set<String> = []
    ) -> Result {
        var merged = remote
        for (k, e) in shadow where merged[k] == nil { merged[k] = e }

        var changes: [String: SyncEntry] = [:]
        for (k, li) in local {
            if frozen.contains(k) { continue }
            if let sv = shadow[k], Canon.same(sv.v, li.v) { continue }
            let firstSeen = !ownersBefore.contains(li.o)
            let stamp: Int64 = li.hinted ? li.hint : (firstSeen ? 0 : now)
            changes[k] = SyncEntry(v: li.v, t: stamp, d: me, o: li.o)
        }
        for (k, e) in changes where k.hasSuffix("/status") && e.t == now {
            let pk = String(k.dropLast("/status".count)) + "/pos"
            guard let li = local[pk], !frozen.contains(pk) else { continue }
            changes[pk] = SyncEntry(v: li.v, t: max(now, li.hint), d: me, o: li.o)
        }
        for (k, se) in shadow {
            guard se.v != nil, local[k] == nil, !frozen.contains(k), SyncKeys.deletable(k) else { continue }
            guard ownersNow.contains(se.o), ownersBefore.contains(se.o) else { continue }
            changes[k] = SyncEntry(v: nil, t: now, d: me, o: se.o)
        }

        var won = Set<String>()
        for (k, le) in changes {
            let r = merged[k]
            if let r, Canon.same(r.v, le.v) { continue }
            if r == nil || le.t >= r!.t {
                merged[k] = le
                won.insert(k)
            }
        }

        let cutoff = now - tombstoneDays * 86_400_000
        merged = merged.filter { !($0.value.v == nil && $0.value.t < cutoff) }

        var apply: [String: SyncEntry] = [:]
        for (k, e) in merged where !won.contains(k) {
            let li = local[k]
            if e.v == nil {
                if li != nil { apply[k] = e }
            } else if li == nil || !Canon.same(li!.v, e.v) {
                apply[k] = e
            }
        }

        let upload = merged.count != remote.count || merged.contains { k, e in
            guard let r = remote[k] else { return true }
            return r.t != e.t || r.d != e.d || !Canon.same(r.v, e.v)
        }
        return Result(merged: merged, apply: apply, won: won, upload: upload)
    }
}

/// One listening log entry as stored on Drive. `u` is unique everywhere: "<device>:<number>".
struct LogEntry: Equatable {
    var u: String
    var d: String
    var at: Int64
    var k: Int
    var p: Int64
    var to: Int64
    /// Speed × 100.
    var s: Int
    var r: Int
}

struct LogFile {
    var bookKey: String
    var cleared: Int64
    var entries: [LogEntry]

    func toJSON() -> String {
        let e: [[Any]] = entries.map { [$0.u, $0.d, $0.at, $0.k, $0.p, $0.to, $0.s, $0.r] }
        let root: [String: Any] = ["app": "Ember", "sync": SyncState.format, "book": bookKey, "cleared": cleared, "e": e]
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    static func parse(_ text: String?) -> LogFile? {
        guard let text, let data = text.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let rows = o["e"] as? [[Any]] ?? []
        let list: [LogEntry] = rows.compactMap { r in
            guard r.count >= 8 else { return nil }
            return LogEntry(u: r[0] as? String ?? "", d: r[1] as? String ?? "", at: int64(r[2]), k: Int(int64(r[3])),
                            p: int64(r[4]), to: int64(r[5]), s: Int(int64(r[6])), r: Int(int64(r[7])))
        }
        return LogFile(bookKey: o["book"] as? String ?? "", cleared: int64(o["cleared"]), entries: list)
    }
}

enum LogMerge {
    static let keep = 400

    static func merge(remote: [LogEntry], remoteCleared: Int64, local: [LogEntry], localCleared: Int64, keep: Int = LogMerge.keep) -> (entries: [LogEntry], cleared: Int64) {
        let cleared = max(remoteCleared, localCleared)
        var byId: [String: LogEntry] = [:]
        var order: [String] = []
        for e in remote + local {
            if let have = byId[e.u] {
                if e.at > have.at || (e.at == have.at && e.to != have.to) { byId[e.u] = e }
            } else {
                byId[e.u] = e
                order.append(e.u)
            }
        }
        let list = order.compactMap { byId[$0] }
            .filter { $0.at > cleared }
            .sorted { $0.at != $1.at ? $0.at > $1.at : $0.u > $1.u }
        return (Array(list.prefix(keep)), cleared)
    }
}
