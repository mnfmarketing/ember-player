import SwiftUI
import SwiftData

/// New clip, from the player: the last stretch you heard, with a quote or note.
struct ClipSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    @State private var length = 30
    @State private var endMs: Int64 = 0
    @State private var note = ""
    @State private var card: CardSource?
    @FocusState private var typing: Bool

    var body: some View {
        NavigationStack {
            Form {
                if let l = player.loaded {
                    let start = max(endMs - Int64(length) * 1000, 0)
                    Section {
                        HStack(spacing: 12) {
                            CoverView(file: l.coverFile, title: l.title, color: l.color, corner: 8).frame(width: 52, height: 52)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text("\(Fmt.clock(start)) – \(Fmt.clock(endMs))\(chapter(start).map { " · \($0)" } ?? "")")
                                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                            }
                            Spacer()
                            Button {
                                player.playRange(mediaID: l.id, startMs: start, endMs: endMs)
                            } label: { Label("Listen", systemImage: "play.fill") }
                            .buttonStyle(.bordered)
                        }
                        Picker("Length", selection: $length) {
                            ForEach([15, 30, 60, 90, 120], id: \.self) { Text("\($0)s").tag($0) }
                        }
                        .pickerStyle(.segmented)
                    }
                    Section {
                        TextField("What was said, or why it stuck with you", text: $note, axis: .vertical)
                            .lineLimit(3...8)
                            .focused($typing)
                    } header: {
                        Text("Quote or note")
                    }
                    Section {
                        Button {
                            _ = save(l, start: start)
                            dismiss()
                        } label: { Label("Save clip", systemImage: "scissors") }
                        Button {
                            let c = save(l, start: start)
                            card = CardSource(clip: c)
                        } label: { Label("Save and make a quote card", systemImage: "quote.bubble") }
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("New clip")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { typing = false }
                }
            }
            .onAppear {
                if endMs == 0 {
                    endMs = player.positionMs
                    length = app.settings.clipLengthSec
                }
            }
            .navigationDestination(item: $card) { QuoteCardView(source: $0) }
        }
    }

    private func chapter(_ at: Int64) -> String? {
        guard let l = player.loaded, l.chapters.count > 1 else { return nil }
        return l.chapters.last { $0.startMs <= at }?.title
    }

    private func save(_ l: Player.Loaded, start: Int64) -> Clip {
        let c = Clip(mediaID: l.id, title: l.title, subtitle: l.subtitle, startMs: start, endMs: endMs,
                     note: note.trimmingCharacters(in: .whitespacesAndNewlines), chapterTitle: chapter(start) ?? "",
                     coverFile: l.coverFile, color: l.color)
        app.library.context.insert(c)
        app.library.changed()
        app.sync.request("clip")
        player.notice = "Clip saved"
        return c
    }
}

/// One clip in a list: its book, where it is, and its quote.
struct ClipRow: View {
    let clip: Clip
    var showBook = true

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if showBook {
                CoverView(file: clip.coverFile, title: clip.title, color: clip.color, corner: 6).frame(width: 44, height: 44)
            }
            VStack(alignment: .leading, spacing: 3) {
                if showBook { Text(Names.shortTitle(clip.title)).font(.subheadline.weight(.semibold)).lineLimit(1) }
                Text([clip.chapterTitle.isEmpty ? nil : clip.chapterTitle, "\(Fmt.clock(clip.startMs)) · \(clip.lengthMs / 1000)s"].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                if !clip.note.isEmpty {
                    Text("\u{201C}\(clip.note)\u{201D}").font(.subheadline).italic().lineLimit(4)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(Color.accentColor)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// Every clip (or one book's), newest first. Tap to listen.
struct ClipsView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    var mediaID: String?
    @Query(sort: [SortDescriptor(\Clip.createdAt, order: .reverse)]) private var all: [Clip]
    @State private var editing: Clip?
    @State private var note = ""
    @State private var card: CardSource?

    var body: some View {
        let clips = mediaID.map { id in all.filter { $0.mediaID == id }.sorted { $0.startMs < $1.startMs } } ?? all
        Group {
            if clips.isEmpty {
                ContentUnavailableView("No clips yet", systemImage: "scissors",
                                       description: Text("Tap Clip in the player to keep the last \(app.settings.clipLengthSec) seconds you heard, with the quote that made you stop."))
            } else {
                List {
                    ForEach(clips) { c in
                        Button { player.playRange(mediaID: c.mediaID, startMs: c.startMs, endMs: c.endMs) } label: { ClipRow(clip: c, showBook: mediaID == nil) }
                            .buttonStyle(.plain)
                            .swipeActions {
                                Button(role: .destructive) {
                                    app.library.delete(c)
                                    app.sync.request("clip")
                                } label: { Label("Delete", systemImage: "trash") }
                                Button {
                                    note = c.note
                                    editing = c
                                } label: { Label("Note", systemImage: "square.and.pencil") }
                            }
                            .contextMenu {
                                Button { card = CardSource(clip: c) } label: { Label("Quote card", systemImage: "quote.bubble") }
                                Button {
                                    note = c.note
                                    editing = c
                                } label: { Label("Edit note", systemImage: "square.and.pencil") }
                                Button(role: .destructive) {
                                    app.library.delete(c)
                                    app.sync.request("clip")
                                } label: { Label("Delete", systemImage: "trash") }
                            }
                    }
                }
            }
        }
        .navigationTitle("Clips")
        .navigationDestination(item: $card) { QuoteCardView(source: $0) }
        .alert("Clip note", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("Quote or note", text: $note)
            Button("Cancel", role: .cancel) { editing = nil }
            Button("Save") {
                editing?.note = note
                app.library.changed()
                app.sync.request("clip")
                editing = nil
            }
        }
    }
}
