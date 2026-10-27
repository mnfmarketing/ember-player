import Foundation

struct ParsedEpisode {
    var guid: String
    var title: String
    var desc: String
    var audioURL: String
    var durationMs: Int64
    var publishedAt: Date?
    var chaptersURL: String
}

struct ParsedFeed {
    var title: String
    var author: String
    var desc: String
    var artworkURL: String
    var episodes: [ParsedEpisode]
    var category: String
}

struct SearchResult: Identifiable, Hashable {
    var title: String
    var author: String
    var feedURL: String
    var artworkURL: String
    var id: String { feedURL }
}

/// Podcast feeds, Apple's public directory (search and charts) and OPML. Same as Android's Feeds.
enum Feeds {

    static let genres: [(String, Int)] = [
        ("True Crime", 1488), ("Comedy", 1303), ("News", 1489), ("Society & Culture", 1324), ("History", 1487),
        ("Fiction", 1483), ("Arts", 1301), ("Business", 1321), ("Education", 1304), ("Health & Fitness", 1512),
        ("Science", 1533), ("Technology", 1318), ("Sports", 1545), ("TV & Film", 1309), ("Religion & Spirituality", 1314),
        ("Kids & Family", 1305), ("Leisure", 1502), ("Music", 1310),
    ]

    private static func get(_ url: String) async throws -> Data {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 30)
        req.setValue("Ember-Audiobooks/1.0 (iOS podcast player)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/rss+xml, application/xml, text/xml, */*", forHTTPHeaderField: "Accept")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) { throw URLError(.badServerResponse) }
        return data
    }

    static func fetch(_ feedURL: String) async throws -> ParsedFeed {
        let data = try await get(feedURL)
        let p = FeedParser()
        let parser = XMLParser(data: data)
        parser.delegate = p
        parser.shouldProcessNamespaces = false
        parser.parse()
        return p.result()
    }

    static func search(_ term: String) async -> [SearchResult] {
        let q = term.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? term
        guard let data = try? await get("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=\(q)"),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = root["results"] as? [[String: Any]] else { return [] }
        return results.compactMap(result)
    }

    private static func result(_ o: [String: Any]) -> SearchResult? {
        guard let feed = o["feedUrl"] as? String, !feed.isEmpty else { return nil }
        return SearchResult(
            title: (o["collectionName"] as? String) ?? (o["trackName"] as? String) ?? "",
            author: o["artistName"] as? String ?? "",
            feedURL: feed,
            artworkURL: (o["artworkUrl600"] as? String) ?? (o["artworkUrl100"] as? String) ?? ""
        )
    }

    /// Top shows from Apple Podcasts' charts, overall or for one category.
    static func topCharts(genreID: Int?, genreName: String?) async -> [SearchResult] {
        let url = genreID == nil
            ? "https://rss.applemarketingtools.com/api/v2/us/podcasts/top/50/podcasts.json"
            : "https://itunes.apple.com/us/rss/toppodcasts/limit=50/genre=\(genreID!)/json"
        var ids: [String] = []
        if let data = try? await get(url), let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let feed = root["feed"] as? [String: Any] {
            if let results = feed["results"] as? [[String: Any]] { ids += results.compactMap { $0["id"] as? String } }
            let entries = (feed["entry"] as? [[String: Any]]) ?? ((feed["entry"] as? [String: Any]).map { [$0] } ?? [])
            for e in entries {
                if let id = ((e["id"] as? [String: Any])?["attributes"] as? [String: Any])?["im:id"] as? String { ids.append(id) }
            }
        }
        if !ids.isEmpty {
            var byID: [String: SearchResult] = [:]
            for chunk in stride(from: 0, to: ids.count, by: 100).map({ Array(ids[$0..<min($0 + 100, ids.count)]) }) {
                guard let data = try? await get("https://itunes.apple.com/lookup?entity=podcast&id=" + chunk.joined(separator: ",")),
                      let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let results = root["results"] as? [[String: Any]] else { continue }
                for o in results {
                    if let r = result(o), let id = o["collectionId"] as? NSNumber { byID[id.stringValue] = r }
                }
            }
            let ordered = ids.compactMap { byID[$0] }
            if !ordered.isEmpty { return ordered }
        }
        return await search(genreName ?? "podcast")
    }

    /// Feed links out of an OPML export.
    static func parseOPML(_ data: Data) -> [String] {
        let p = OPMLParser()
        let parser = XMLParser(data: data)
        parser.delegate = p
        parser.parse()
        var seen = Set<String>()
        return p.urls.filter { seen.insert($0).inserted }
    }

    static func parseDuration(_ text: String) -> Int64 {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":").map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard !parts.isEmpty, parts.allSatisfy({ $0 != nil }) else { return 0 }
        let p = parts.map { $0! }
        let s: Double
        switch p.count {
        case 1: s = p[0]
        case 2: s = p[0] * 60 + p[1]
        default: s = p[0] * 3600 + p[1] * 60 + p[2]
        }
        return Int64(s * 1000)
    }

    private static let formats = [
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd",
    ]

    static func parseDate(_ text: String) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        let t = text.trimmingCharacters(in: .whitespaces)
        for fmt in formats {
            f.dateFormat = fmt
            if let d = f.date(from: t) { return d }
        }
        return nil
    }

    /// Plain text from a show's HTML description.
    static func cleanHTML(_ s: String) -> String {
        guard !s.isEmpty else { return "" }
        var t = s.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: [.regularExpression, .caseInsensitive])
        t = t.replacingOccurrences(of: "</p>", with: "\n\n", options: .caseInsensitive)
        t = t.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let entities = ["&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&#39;": "'", "&apos;": "'", "&nbsp;": " ", "&#8217;": "’", "&#8220;": "“", "&#8221;": "”", "&#8212;": "—"]
        for (k, v) in entities { t = t.replacingOccurrences(of: k, with: v) }
        t = t.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private final class FeedParser: NSObject, XMLParserDelegate {
    var title = "", author = "", desc = "", artwork = "", category = ""
    var episodes: [ParsedEpisode] = []
    var inItem = false, inImage = false
    var eTitle = "", eGuid = "", eDesc = "", eSummary = "", eURL = "", eLink = "", eChapters = ""
    var eDuration: Int64 = 0
    var ePub: Date?
    var text = ""
    var depth = 0

    static let leaf: Set<String> = ["title", "guid", "id", "description", "content:encoded", "itunes:summary", "itunes:duration",
                                    "pubdate", "published", "updated", "link", "url", "itunes:author"]

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        let n = name.lowercased()
        text = ""
        switch n {
        case "item", "entry":
            inItem = true
            eTitle = ""; eGuid = ""; eDesc = ""; eSummary = ""; eURL = ""; eLink = ""; eChapters = ""; eDuration = 0; ePub = nil
        case "image" where !inItem && a["href"] == nil:
            inImage = true
        case "itunes:image":
            if !inItem, let h = a["href"], !h.isEmpty { artwork = h }
        case "enclosure" where inItem:
            let type = a["type"] ?? "", url = a["url"] ?? ""
            if !url.isEmpty, eURL.isEmpty || type.hasPrefix("audio") { eURL = url }
        case "itunes:category" where !inItem:
            if category.isEmpty { category = (a["text"] ?? "").trimmingCharacters(in: .whitespaces) }
        case "podcast:chapters" where inItem:
            eChapters = (a["url"] ?? eChapters).trimmingCharacters(in: .whitespaces)
        case "link" where inItem && a["rel"] == "enclosure":
            eURL = a["href"] ?? eURL
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, foundCDATA data: Data) { text += String(decoding: data, as: UTF8.self) }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        let n = name.lowercased()
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if FeedParser.leaf.contains(n) {
            if inItem {
                switch n {
                case "title": eTitle = t
                case "guid", "id": eGuid = t
                case "description", "content:encoded": if t.count > eDesc.count { eDesc = t }
                case "itunes:summary": eSummary = t
                case "itunes:duration": eDuration = Feeds.parseDuration(t)
                case "pubdate", "published", "updated": if ePub == nil { ePub = Feeds.parseDate(t) }
                case "link": eLink = t
                default: break
                }
            } else if inImage {
                if n == "url", artwork.isEmpty { artwork = t }
            } else {
                switch n {
                case "title": if title.isEmpty { title = t }
                case "itunes:author": author = t
                case "description", "itunes:summary": if desc.isEmpty { desc = t }
                default: break
                }
            }
        }
        if (n == "item" || n == "entry") && inItem {
            inItem = false
            if !eURL.isEmpty {
                let guid = !eGuid.isEmpty ? eGuid : (!eURL.isEmpty ? eURL : (!eLink.isEmpty ? eLink : eTitle))
                episodes.append(ParsedEpisode(guid: guid, title: eTitle.isEmpty ? "Untitled episode" : eTitle,
                                              desc: Feeds.cleanHTML(eDesc.isEmpty ? eSummary : eDesc), audioURL: eURL,
                                              durationMs: eDuration, publishedAt: ePub, chaptersURL: eChapters))
            }
        } else if n == "image" {
            inImage = false
        }
        text = ""
    }

    func result() -> ParsedFeed {
        ParsedFeed(title: title.isEmpty ? "Untitled podcast" : title, author: author, desc: Feeds.cleanHTML(desc), artworkURL: artwork, episodes: episodes, category: category)
    }
}

private final class OPMLParser: NSObject, XMLParserDelegate {
    var urls: [String] = []
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        guard name.lowercased() == "outline" else { return }
        if let u = a["xmlUrl"] ?? a["xmlurl"], !u.isEmpty { urls.append(u.trimmingCharacters(in: .whitespaces)) }
    }
}
