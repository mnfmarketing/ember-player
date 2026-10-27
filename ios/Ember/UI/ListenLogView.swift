import SwiftUI
import SwiftData

/// Every play, pause, skip and jump, newest first (from every device when sync is on).
/// Tap one to go back to that spot.
struct ListenLogView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    let title: String
    @Query private var events: [ListenEvent]
    @State private var confirmClear = false

    init(bookID: String, title: String) {
        self.bookID = bookID
        self.title = title
        var d = FetchDescriptor<ListenEvent>(predicate: #Predicate { $0.bookID == bookID }, sortBy: [SortDescriptor(\.at, order: .reverse)])
        d.fetchLimit = 400
        _events = Query(d)
    }

    var body: some View {
        let chapters = app.library.chapters(bookID)
        let manyDevices = events.contains { !$0.device.isEmpty && $0.device != app.sync.deviceID }
        NavigationStack {
            Group {
                if events.isEmpty {
                    ContentUnavailableView("Nothing yet", systemImage: "clock.arrow.circlepath",
                                           description: Text("Every play, pause, skip and jump will show up here, so you can always get back to where you were."))
                } else {
                    List {
                        ForEach(groups, id: \.0) { day, list in
                            Section(day) {
                                ForEach(list) { e in
                                    Button { go(e.positionMs) } label: { row(e, chapters: chapters, device: manyDevices ? app.sync.nameOf(e.device) : nil) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Listening log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
                if !events.isEmpty { ToolbarItem(placement: .topBarTrailing) { Button("Clear all") { confirmClear = true } } }
            }
            .safeAreaInset(edge: .top) {
                Text(title).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
            }
            .confirmationDialog("Clear the listening log?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { app.library.clearLog(bookID) }
            } message: {
                Text("This only clears the log for this book. Your place in the book stays where it is.")
            }
        }
    }

    private var groups: [(String, [ListenEvent])] {
        var out: [(String, [ListenEvent])] = []
        for e in events {
            let label = dayLabel(e.at)
            if out.last?.0 == label { out[out.count - 1].1.append(e) } else { out.append((label, [e])) }
        }
        return out
    }

    private func dayLabel(_ at: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(at) / 1000)
        if Calendar.current.isDateInToday(d) { return "Today" }
        if Calendar.current.isDateInYesterday(d) { return "Yesterday" }
        return d.formatted(date: .abbreviated, time: .omitted)
    }

    private func go(_ ms: Int64) {
        if player.loaded?.id == bookID {
            player.seek(to: ms)
        } else if bookID.hasPrefix("episode:") {
            if let ep = app.podcasts.episode(String(bookID.dropFirst(8))) {
                ep.positionMs = ms
                player.playEpisode(ep)
            }
        } else {
            app.library.setProgress(bookID, positionMs: ms)
            player.playBook(bookID)
        }
        player.notice = "Back to \(Fmt.clock(ms))"
        dismiss()
    }

    @ViewBuilder
    private func row(_ e: ListenEvent, chapters: [Chapter], device: String?) -> some View {
        let delta = e.toMs - e.positionMs
        let what = describe(e, delta: delta, chapters: chapters)
        HStack(spacing: 12) {
            Image(systemName: what.0)
                .font(.body.weight(.semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 36, height: 36)
                .background(Color.accentColor.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(what.1).font(.subheadline.weight(.semibold))
                Text((e.kind == ListenEvent.skip || e.kind == ListenEvent.jump ? "From " : "") + spot(chapters, e.positionMs)).font(.subheadline)
                Text(Date(timeIntervalSince1970: Double(e.at) / 1000).formatted(date: .omitted, time: .shortened) + (device.map { " · \($0)" } ?? ""))
                    .font(.caption).foregroundStyle(.secondary)
                if e.kind == ListenEvent.pause, e.reason == ListenEvent.reasonSleep, e.toMs >= 0 {
                    Button("Last awake around \(spot(chapters, e.toMs))") { go(e.toMs) }
                        .font(.caption.weight(.medium))
                        .buttonStyle(.borderless)
                }
            }
            Spacer()
            if e.speed != 1 { Text(Fmt.speed(e.speed)).font(.caption).foregroundStyle(.secondary) }
        }
        .contentShape(Rectangle())
        .accessibilityHint("Goes back to this spot")
    }

    private func spot(_ chapters: [Chapter], _ ms: Int64) -> String {
        if let c = chapters.last(where: { $0.startMs <= ms }) { return "\(c.title) · \(Fmt.clock(ms - c.startMs))" }
        return Fmt.clock(ms)
    }

    private func describe(_ e: ListenEvent, delta: Int64, chapters: [Chapter]) -> (String, String) {
        switch e.kind {
        case ListenEvent.play:
            return ("play.fill", e.reason == ListenEvent.reasonFlipped ? "Play · phone turned up" : "Play")
        case ListenEvent.pause:
            let why: String
            switch e.reason {
            case ListenEvent.reasonSleep: why = " · sleep timer"
            case ListenEvent.reasonUnplugged: why = " · headphones disconnected"
            case ListenEvent.reasonOtherApp: why = " · another app"
            case ListenEvent.reasonFinished: why = " · finished"
            case ListenEvent.reasonFlipped: why = " · phone turned over"
            default: why = ""
            }
            return ("pause.fill", "Pause" + why)
        case ListenEvent.skip:
            return (delta < 0 ? "gobackward" : "goforward", (delta < 0 ? "Skipped back " : "Skipped forward ") + amount(abs(delta)))
        default:
            if let c = chapters.first(where: { abs($0.startMs - e.toMs) < 1500 }) { return ("arrow.up.arrow.down", "Went to \(c.title)") }
            return ("arrow.up.arrow.down", (delta < 0 ? "Jumped back " : "Jumped ahead ") + amount(abs(delta)))
        }
    }

    private func amount(_ ms: Int64) -> String {
        let s = ms / 1000
        if s < 60 { return "\(s)s" }
        if s < 3600 { return s % 60 == 0 ? "\(s / 60)m" : "\(s / 60)m \(s % 60)s" }
        return Fmt.duration(ms)
    }
}

struct BookmarksView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    @Query private var marks: [Bookmark]
    @State private var editing: Bookmark?
    @State private var note = ""

    init(bookID: String) {
        self.bookID = bookID
        _marks = Query(filter: #Predicate<Bookmark> { $0.bookID == bookID }, sort: \Bookmark.positionMs)
    }

    var body: some View {
        NavigationStack {
            Group {
                if marks.isEmpty {
                    ContentUnavailableView("No bookmarks", systemImage: "bookmark", description: Text("Tap Bookmark in the player to save a spot (and add a note)."))
                } else {
                    List {
                        ForEach(marks) { m in
                            Button {
                                if player.loaded?.id == bookID { player.seek(to: m.positionMs) } else {
                                    app.library.setProgress(bookID, positionMs: m.positionMs)
                                    player.playBook(bookID)
                                }
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text((m.chapterTitle.isEmpty ? "" : "\(m.chapterTitle) · ") + Fmt.clock(m.positionMs)).font(.subheadline.weight(.semibold))
                                    if !m.note.isEmpty { Text(m.note).font(.subheadline).foregroundStyle(.secondary) }
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) { app.library.delete(m) } label: { Label("Delete", systemImage: "trash") }
                                Button {
                                    note = m.note
                                    editing = m
                                } label: { Label("Note", systemImage: "square.and.pencil") }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Bookmarks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } } }
            .alert("Note", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
                TextField("What happens here", text: $note)
                Button("Cancel", role: .cancel) { editing = nil }
                Button("Save") {
                    editing?.note = note
                    app.library.changed()
                    editing = nil
                }
            }
        }
    }
}
