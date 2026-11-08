import Foundation

/// Book details from Audible's public catalog (the same source Audiobookshelf uses): series and
/// number, narrator, description, cover and length.
enum BookInfo {
    struct Match: Identifiable, Hashable {
        let asin: String
        let title: String
        let subtitle: String
        let authors: String
        let narrators: String
        let series: String
        let seriesIndex: Double?
        let description: String
        let coverURL: URL?
        let lengthMs: Int64
        let year: String
        var id: String { asin.isEmpty ? title + authors : asin }
    }

    static func search(title: String, author: String, keywords: String? = nil) async -> [Match] {
        var c = URLComponents(string: "https://api.audible.com/1.0/catalog/products")!
        var items = [
            URLQueryItem(name: "num_results", value: "12"),
            URLQueryItem(name: "products_sort_by", value: "Relevance"),
            URLQueryItem(name: "response_groups", value: "contributors,product_desc,product_attrs,media,series"),
            URLQueryItem(name: "image_sizes", value: "500,1024"),
        ]
        if let keywords {
            items.append(URLQueryItem(name: "keywords", value: keywords))
        } else {
            items.append(URLQueryItem(name: "title", value: cleanTitle(title)))
            if !author.trimmingCharacters(in: .whitespaces).isEmpty { items.append(URLQueryItem(name: "author", value: author)) }
        }
        c.queryItems = items
        guard let url = c.url else { return [] }
        var r = URLRequest(url: url, timeoutInterval: 15)
        r.setValue("Ember-Audiobooks/1.0", forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, _) = try? await URLSession.shared.data(for: r),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let products = root["products"] as? [[String: Any]] else { return [] }
        return products.compactMap { p in
            let title = p["title"] as? String ?? ""
            guard !title.isEmpty else { return nil }
            let series = (p["series"] as? [[String: Any]])?.first
            let images = p["product_images"] as? [String: Any]
            let cover = (images?["1024"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (images?["500"] as? String)
            let summary = (p["publisher_summary"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (p["merchandising_summary"] as? String ?? "")
            return Match(
                asin: p["asin"] as? String ?? "",
                title: title,
                subtitle: p["subtitle"] as? String ?? "",
                authors: names(p["authors"]),
                narrators: names(p["narrators"]),
                series: series?["title"] as? String ?? "",
                seriesIndex: (series?["sequence"] as? String).flatMap(Double.init),
                description: stripHTML(summary),
                coverURL: cover.flatMap(URL.init(string:)),
                lengthMs: Int64((p["runtime_length_min"] as? NSNumber)?.intValue ?? 0) * 60_000,
                year: String((p["release_date"] as? String ?? "").prefix(4))
            )
        }
    }

    /// The match to trust without asking: the title and author line up and the length is close.
    static func confident(_ b: Book, _ matches: [Match]) -> Match? {
        let bt = norm(cleanTitle(b.title))
        return matches.first { m in
            let t = norm(m.title)
            let titleOK = !t.isEmpty && (t == bt || t.hasPrefix(bt) || bt.hasPrefix(t))
            let authorOK = b.author.trimmingCharacters(in: .whitespaces).isEmpty || norm(m.authors).contains(String(norm(b.author).prefix(12)))
            let lengthOK = b.durationMs <= 0 || b.isExternal || m.lengthMs <= 0 || Double(abs(m.lengthMs - b.durationMs)) / Double(b.durationMs) < 0.08
            return titleOK && authorOK && lengthOK
        }
    }

    /// "Book 01 - The Name (Unabridged)" → "The Name".
    static func cleanTitle(_ t: String) -> String {
        var s = t
        for (p, opts) in [(#"\((un)?abridged\)"#, NSString.CompareOptions.caseInsensitive),
                          (#"^(book|vol\.?|volume)\s*\d+\s*[-:.]\s*"#, .caseInsensitive),
                          (#"^\d+\s*[-.]\s*"#, [])] {
            s = s.replacingOccurrences(of: p, with: "", options: opts.union(.regularExpression))
        }
        return s.trimmingCharacters(in: .whitespaces)
    }

    private static func norm(_ s: String) -> String { s.lowercased().filter { ($0.isLetter || $0.isNumber) && $0.isASCII } }

    private static func names(_ v: Any?) -> String {
        ((v as? [[String: Any]]) ?? []).compactMap { $0["name"] as? String }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    private static func stripHTML(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<br\\s*/?>|</p>", with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (a, b) in [("&amp;", "&"), ("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&lt;", "<"), ("&gt;", ">"), ("&nbsp;", " ")] {
            s = s.replacingOccurrences(of: a, with: b)
        }
        return s.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
