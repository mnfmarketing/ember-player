import Foundation

/// Tidier names for what's shown on screen (the same rules as Android's Names.kt).
enum Names {

    private static let numbered = try! NSRegularExpression(
        pattern: #"^(?:(track|part|pt|chapter|chap|ch|cd|disc|disk|file|section|kapitel|capitulo|chapitre)\.?\s*[-_#:.]?\s*)?0*(\d{1,4})$"#,
        options: [.caseInsensitive])
    private static let numberThenTitle = try! NSRegularExpression(pattern: #"^0*(\d{1,4})\s*[-_.:)]\s*(.+)$"#)
    private static let separators = CharacterSet(charactersIn: " -–—:_.,|/")

    private static func match(_ re: NSRegularExpression, _ s: String) -> [String]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func fullMatch(_ re: NSRegularExpression, _ s: String) -> [String]? {
        guard let g = match(re, s), g.first == s else { return nil }
        return g
    }

    /// A chapter or file name as people would say it: "Book - Track 029" → "Chapter 29". Real names are left alone.
    static func chapter(_ raw: String, bookTitle: String, index: Int) -> String {
        let original = raw.trimmingCharacters(in: .whitespaces)
        var t = original
        let titles = Array(Set([bookTitle.trimmingCharacters(in: .whitespaces), shortTitle(bookTitle)].filter { $0.count > 2 }))
            .sorted { $0.count > $1.count }
        for b in titles where t.lowercased().hasPrefix(b.lowercased()) {
            let after = String(t.dropFirst(b.count))
            let rest = after.trimmingCharacters(in: separators)
            let firstAfter = after.drop { $0 == " " }.first
            let setApart = firstAfter.map { "-–—:_|/.(".contains($0) || $0.isNumber } ?? true
            if rest.isEmpty || fullMatch(numbered, rest) != nil || setApart {
                t = rest
                break
            }
        }
        if t.isEmpty { return "Chapter \(index + 1)" }
        if let g = match(numberThenTitle, t) {
            let rest = g[2].trimmingCharacters(in: separators)
            if titles.contains(where: { $0.caseInsensitiveCompare(rest) == .orderedSame }), let n = Int(g[1]) { return "Chapter \(n)" }
        }
        if let g = fullMatch(numbered, t), let n = Int(g[2]) {
            switch g[1].lowercased() {
            case "part", "pt": return "Part \(n)"
            case "cd", "disc", "disk": return "Disc \(n)"
            default: return "Chapter \(n)"
            }
        }
        return t != original ? t : original
    }

    private static let noise: [NSRegularExpression] = [
        #"\s*[\(\[]\s*(un)?abridged(\s+edition)?\s*[\)\]]"#,
        #"\s*[\(\[]\s*audio\s*book\s*[\)\]]"#,
        #"\s*[\(\[][^\)\]]*(book|bk\.?|volume|vol\.?|#)\s*\d+[^\)\]]*[\)\]]"#,
        #"\s*[,:]\s*(book|volume|vol\.?)\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s*$"#,
        #"\s*[:\-–—]\s*an?\s+([a-z]+\s+){0,2}(novel|novella|thriller|memoir|mystery|romance|romantasy|story|tale|fantasy)\s*$"#,
    ].map { try! NSRegularExpression(pattern: $0, options: [.caseInsensitive]) }

    /// A title without filler: ": A Novel", "(Unabridged)", "(Series, Book 1)". The book page keeps the full one.
    static func shortTitle(_ title: String) -> String {
        var t = title.trimmingCharacters(in: .whitespaces)
        for re in noise {
            t = re.stringByReplacingMatches(in: t, range: NSRange(location: 0, length: (t as NSString).length), withTemplate: "")
        }
        t = t.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: ":-–—,")).trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? title.trimmingCharacters(in: .whitespaces) : t
    }

    private static let discName = try! NSRegularExpression(
        pattern: #"^(?:.*[\s._-])?(?:cd|disc|disk|part|pt)\.?\s*[-_#]?\s*\d{1,3}(?:\s*(?:of|/)\s*\d{1,3})?$|^\d{1,3}$"#,
        options: [.caseInsensitive])

    /// "CD1", "CD 01", "Disc 2", "Part 4", "01", or "Book Title CD1".
    static func isDiscName(_ name: String) -> Bool {
        fullMatch(discName, name.trimmingCharacters(in: .whitespaces)) != nil
    }

    private static let discSuffix = try! NSRegularExpression(
        pattern: #"[\s,:._-]*[\(\[]?\s*(cd|disc|disk|part|pt)\.?\s*[-_#]?\s*\d{1,3}(\s*(of|/)\s*\d{1,3})?\s*[\)\]]?\s*$"#,
        options: [.caseInsensitive])

    /// "Book Title CD1" as an album tag on a disc: just the book's title.
    static func stripDisc(_ t: String) -> String {
        let s = discSuffix.stringByReplacingMatches(in: t, range: NSRange(location: 0, length: (t as NSString).length), withTemplate: "")
            .trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? t : s
    }

    /// A file's chapter name: its title tag, or the file name without leading numbers.
    static func cleanChapterTitle(tag: String?, fileTitle: String) -> String {
        if let tag, !tag.trimmingCharacters(in: .whitespaces).isEmpty { return tag.trimmingCharacters(in: .whitespaces) }
        let stripped = fileTitle.replacingOccurrences(of: #"^[\d\s._-]+"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        return stripped.isEmpty ? fileTitle : stripped
    }

    /// Sorts "2" before "10", like Android's natural order.
    static func naturalLess(_ a: String, _ b: String) -> Bool { naturalCompare(a, b) < 0 }

    static func naturalCompare(_ a: String, _ b: String) -> Int {
        func parts(_ s: String) -> [String] {
            var out: [String] = []
            var cur = ""
            var digits = false
            for ch in s.lowercased() {
                let d = ch.isASCII && ch.isNumber
                if cur.isEmpty || d == digits {
                    cur.append(ch)
                } else {
                    out.append(cur)
                    cur = String(ch)
                }
                digits = d
            }
            if !cur.isEmpty { out.append(cur) }
            return out
        }
        let pa = parts(a), pb = parts(b)
        for i in 0..<min(pa.count, pb.count) {
            let x = pa[i], y = pb[i]
            var cmp: Int
            if let fx = x.first, let fy = y.first, fx.isASCII, fx.isNumber, fy.isASCII, fy.isNumber {
                let nx = String(x.drop { $0 == "0" }).isEmpty ? "0" : String(x.drop { $0 == "0" })
                let ny = String(y.drop { $0 == "0" }).isEmpty ? "0" : String(y.drop { $0 == "0" })
                cmp = nx.count != ny.count ? nx.count - ny.count : (nx < ny ? -1 : (nx > ny ? 1 : 0))
            } else {
                cmp = x < y ? -1 : (x > y ? 1 : 0)
            }
            if cmp != 0 { return cmp }
        }
        return pa.count - pb.count
    }
}

/// Times as people read them.
enum Fmt {
    /// 1:02:03 or 12:03.
    static func clock(_ ms: Int64) -> String {
        let s = max(ms, 0) / 1000
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    /// 6h 12m, 18m, 45s.
    static func duration(_ ms: Int64) -> String {
        let totalMin = max(ms, 0) / 60_000
        let h = totalMin / 60, m = totalMin % 60
        if h > 0 { return m > 0 ? "\(h)h \(m)m" : "\(h)h" }
        if m > 0 { return "\(m)m" }
        return "\(max(ms, 0) / 1000)s"
    }

    static func speed(_ s: Double) -> String {
        let r = (s * 100).rounded() / 100
        if r == r.rounded() { return String(format: "%.1f×", r) }
        return String(format: r * 10 == (r * 10).rounded() ? "%.1f×" : "%.2f×", r)
    }

    static func ago(_ ms: Int64) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return f.localizedString(for: Date(timeIntervalSince1970: Double(ms) / 1000), relativeTo: Date())
    }
}
