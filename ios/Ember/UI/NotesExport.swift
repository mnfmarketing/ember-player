import SwiftUI
import SwiftData

/// A book's notes as Markdown (Notion, Obsidian and most notes apps import it): its details, your
/// rating, bookmarks and clips, with chapters and times. The same as Ember on Android.
@MainActor
enum NotesExport {
    static func markdown(_ lib: Library, bookID: String) -> String {
        guard let b = lib.book(bookID) else { return "" }
        let marks = lib.bookmarks(bookID)
        let clips = lib.clips(bookID)
        let chapters = lib.chapters(bookID)
        func chapterAt(_ ms: Int64, _ fallback: String) -> String {
            fallback.isEmpty ? (chapters.last { $0.startMs <= ms }?.title ?? "") : fallback
        }
        func day(_ ms: Int64) -> String { Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .omitted) }
        func indent(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: "  \n  ") }
        var o = "# \(b.title)\n\n"
        var facts: [String] = []
        if !b.author.isEmpty { facts.append("**Author:** \(b.author)") }
        if !b.narrator.isEmpty { facts.append("**Narrated by:** \(b.narrator)") }
        if !b.series.isEmpty {
            let n = b.seriesIndex.map { $0 == $0.rounded() ? " #\(Int($0))" : " #\($0)" } ?? ""
            facts.append("**Series:** \(b.series)\(n)")
        }
        if b.rating > 0 { facts.append("**My rating:** " + String(repeating: "★", count: b.rating) + String(repeating: "☆", count: 5 - b.rating) + " (\(b.rating)/5)") }
        switch b.status {
        case Book.statusFinished: facts.append("**Status:** " + (b.finishedAt > 0 ? "Finished \(day(b.finishedAt))" : "Finished"))
        case Book.statusInProgress: facts.append("**Status:** Listening (\(Int(b.progress * 100))%)")
        default: facts.append("**Status:** Not started")
        }
        if b.durationMs > 0, !b.isExternal { facts.append("**Length:** \(Fmt.duration(b.durationMs))") }
        o += facts.map { $0 + "  \n" }.joined()
        if !b.desc.isEmpty { o += "\n> " + b.desc.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: "\n> ") + "\n" }
        o += "\n## Bookmarks\n\n"
        if marks.isEmpty { o += "_No bookmarks._\n" }
        for m in marks {
            let ch = chapterAt(m.positionMs, m.chapterTitle)
            o += "- **\(Fmt.clock(m.positionMs))**" + (ch.isEmpty ? "" : " · \(ch)")
            if !m.note.isEmpty { o += "  \n  " + indent(m.note) }
            o += "\n"
        }
        o += "\n## Clips\n\n"
        if clips.isEmpty { o += "_No clips._\n" }
        for c in clips {
            let ch = chapterAt(c.startMs, c.chapterTitle)
            o += "- **\(Fmt.clock(c.startMs))–\(Fmt.clock(c.endMs))**" + (ch.isEmpty ? "" : " · \(ch)")
            if !c.note.isEmpty { o += "  \n  " + indent(c.note) }
            o += "\n"
        }
        o += "\n---\n_Exported from Ember on \(Date().formatted(date: .abbreviated, time: .omitted))_\n"
        return o
    }

    /// The notes as a file to share.
    static func file(_ lib: Library, bookID: String) -> URL? {
        guard let b = lib.book(bookID) else { return nil }
        let name = b.displayTitle.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|")).joined(separator: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name) notes.md")
        guard (try? Data(markdown(lib, bookID: bookID).utf8).write(to: url, options: .atomic)) != nil else { return nil }
        return url
    }
}

/// Books with bookmarks or clips, each ready to share as Markdown.
struct NotesExportView: View {
    @Environment(AppModel.self) private var app
    @Query private var marks: [Bookmark]
    @Query private var clips: [Clip]
    @Query private var books: [Book]

    var body: some View {
        let ids = Set(marks.map(\.bookID)).union(clips.filter { !$0.isEpisode }.map(\.mediaID))
        let list = books.filter { ids.contains($0.id) }.sorted { $0.lastPlayedAt > $1.lastPlayedAt }
        List {
            if list.isEmpty {
                Text("Bookmarks and clips you make show up here, ready to share with your notes app.").foregroundStyle(.secondary)
            }
            Section {
                ForEach(list) { b in
                    if let url = NotesExport.file(app.library, bookID: b.id) {
                        ShareLink(item: url) {
                            HStack(spacing: 12) {
                                CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 6).frame(width: 40, height: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(b.displayTitle).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                    let m = marks.filter { $0.bookID == b.id }.count, c = clips.filter { $0.mediaID == b.id }.count
                                    Text("\(m) bookmark\(m == 1 ? "" : "s") · \(c) clip\(c == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "square.and.arrow.up").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            } footer: {
                if !list.isEmpty { Text("Each book's details, rating, bookmarks and clips as Markdown, which Notion, Obsidian and most notes apps can import.") }
            }
        }
        .navigationTitle("Export notes")
    }
}
