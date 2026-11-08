import SwiftUI
import PDFKit
import UniformTypeIdentifiers

/// A book's details: title, author, narrator, series, description, and its companion PDF. Can look
/// them up on Audible's catalog and (for M4B files) save them into the file too.
struct EditBookView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    @State private var title = ""
    @State private var author = ""
    @State private var narrator = ""
    @State private var series = ""
    @State private var index = ""
    @State private var desc = ""
    @State private var coverURL: URL?
    @State private var intoFile = false
    @State private var lookingUp = false
    @State private var pickingPDF = false
    @State private var saving = false
    @State private var message: String?
    @State private var loaded = false
    @State private var confirmRestore = false
    @FocusState private var field: Int?

    var body: some View {
        let file = app.library.singleFile(bookID)
        Form {
            Section {
                TextField("Title", text: $title).focused($field, equals: 0).submitLabel(.next).onSubmit { field = 1 }
                TextField("Author", text: $author).focused($field, equals: 1).submitLabel(.next).onSubmit { field = 2 }
                TextField("Narrator", text: $narrator).focused($field, equals: 2).submitLabel(.next).onSubmit { field = 3 }
                HStack {
                    TextField("Series", text: $series).focused($field, equals: 3).submitLabel(.next).onSubmit { field = 4 }
                    TextField("Book #", text: $index).keyboardType(.decimalPad).frame(width: 80).focused($field, equals: 4)
                }
            }
            Section("Description") {
                TextField("What it's about", text: $desc, axis: .vertical).lineLimit(3...12).focused($field, equals: 5)
            }
            Section {
                Button { lookingUp = true } label: { Label("Look up details", systemImage: "sparkle.magnifyingglass") }
                if let coverURL {
                    HStack {
                        AsyncImage(url: coverURL) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.2) }
                            .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 6))
                        Text("New cover").foregroundStyle(.secondary)
                        Spacer()
                        Button("Keep the old one") { self.coverURL = nil }.font(.caption)
                    }
                }
            } footer: {
                Text("From Audible's catalog: series and number, narrator, description and cover.")
            }
            if let file {
                Section {
                    Toggle("Also save into the audio file", isOn: $intoFile)
                    if Mp4TagWriter.hasOriginal(file) {
                        Button("Restore the original file", role: .destructive) { confirmRestore = true }
                    }
                } footer: {
                    Text("Writes these details into \(file.lastPathComponent), so other apps see them too. Only the file's index changes, never the audio, and Ember keeps what it needs to put the original back.")
                }
            }
            PDFSection(bookID: bookID)
            if let message { Section { Text(message).foregroundStyle(.secondary) } }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? "Saving…" : "Save") { Task { await save(file) } }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || saving)
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { field = nil }
            }
        }
        .sheet(isPresented: $lookingUp) {
            BookInfoSheet(title: title, author: author) { m in apply(m) }
        }
        .confirmationDialog("Restore the original file?", isPresented: $confirmRestore, titleVisibility: .visible) {
            Button("Restore", role: .destructive) {
                guard let file else { return }
                switch Mp4TagWriter.restoreOriginal(file) {
                case .ok: message = "The original file is back."
                case .failed(let why): message = why
                }
            }
        } message: {
            Text("Puts the file back exactly as it was before Ember first changed it.")
        }
        .onAppear {
            guard !loaded, let b = app.library.book(bookID) else { return }
            loaded = true
            title = b.title
            author = b.author
            narrator = b.narrator
            series = b.series
            index = b.seriesIndex.map { $0 == $0.rounded() ? String(Int($0)) : String($0) } ?? ""
            desc = b.desc
        }
    }

    private func apply(_ m: BookInfo.Match) {
        title = m.title
        if !m.authors.isEmpty { author = m.authors }
        if !m.narrators.isEmpty { narrator = m.narrators }
        if !m.series.isEmpty { series = m.series }
        if let i = m.seriesIndex { index = i == i.rounded() ? String(Int(i)) : String(i) }
        if !m.description.isEmpty { desc = m.description }
        coverURL = m.coverURL
    }

    private func save(_ file: URL?) async {
        guard let b = app.library.book(bookID) else { return }
        saving = true
        defer { saving = false }
        b.title = title.trimmingCharacters(in: .whitespaces)
        b.author = author.trimmingCharacters(in: .whitespaces)
        b.narrator = narrator.trimmingCharacters(in: .whitespaces)
        b.series = series.trimmingCharacters(in: .whitespaces)
        b.seriesIndex = Double(index.replacingOccurrences(of: ",", with: "."))
        b.desc = desc.trimmingCharacters(in: .whitespacesAndNewlines)
        b.userEdited = true
        var coverData: Data?
        if let coverURL, let (data, _) = try? await URLSession.shared.data(from: coverURL), let saved = Covers.save(id: bookID + "-\(Clock.now)", data: data) {
            Covers.remove(b.coverFile)
            b.coverFile = saved.0
            b.coverColor = saved.1
            coverData = data
        }
        app.library.changed()
        if intoFile, let file {
            let r = await Mp4TagWriter.write(file, tags: .init(title: b.title, author: b.author, narrator: b.narrator, description: b.desc), chapters: nil,
                                             totalMs: b.durationMs, cover: coverData)
            if case .failed(let why) = r {
                message = "Saved in Ember. " + why
                return
            }
        }
        dismiss()
    }
}

/// Matches from Audible's catalog. Tap one to use its details.
struct BookInfoSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let author: String
    let pick: (BookInfo.Match) -> Void
    @State private var q = ""
    @State private var results: [BookInfo.Match]?

    var body: some View {
        NavigationStack {
            List {
                if let results {
                    if results.isEmpty { Text("Nothing found. Try fewer words, or the author's name.").foregroundStyle(.secondary) }
                    ForEach(results) { m in
                        Button {
                            pick(m)
                            dismiss()
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                AsyncImage(url: m.coverURL) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.2) }
                                    .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(m.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                    Text(m.authors).font(.caption).foregroundStyle(.secondary)
                                    let extra = [m.narrators.isEmpty ? nil : "Narrated by \(m.narrators)",
                                                 m.series.isEmpty ? nil : m.series + (m.seriesIndex.map { ", Book \($0 == $0.rounded() ? String(Int($0)) : String($0))" } ?? ""),
                                                 m.lengthMs > 0 ? Fmt.duration(m.lengthMs) : nil, m.year.isEmpty ? nil : m.year].compactMap { $0 }
                                    Text(extra.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Look up details")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Title, author or keywords")
            .onSubmit(of: .search) { Task { await run(keywords: q) } }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .task { if results == nil { await run(keywords: nil) } }
        }
    }

    private func run(keywords: String?) async {
        results = nil
        var r = await BookInfo.search(title: title, author: author, keywords: keywords)
        if r.isEmpty, keywords == nil { r = await BookInfo.search(title: title, author: "", keywords: BookInfo.cleanTitle(title)) }
        results = r
    }
}

/// The companion PDF (maps, appendices...): from the book's folder, or one you pick.
struct PDFSection: View {
    @Environment(AppModel.self) private var app
    let bookID: String
    @State private var picking = false

    var body: some View {
        let _ = app.library.version
        let b = app.library.book(bookID)
        Section {
            if let p = b?.pdf {
                NavigationLink { PDFScreen(bookID: bookID) } label: {
                    Label((p as NSString).lastPathComponent, systemImage: "doc.richtext")
                }
                if p.hasPrefix("file:") {
                    Button("Remove the PDF", role: .destructive) { app.library.removePDF(bookID) }
                }
            }
            Button { picking = true } label: { Label(b?.pdf == nil ? "Add a PDF…" : "Use another PDF…", systemImage: "doc.badge.plus") }
        } header: {
            Text("Companion PDF")
        } footer: {
            Text("A PDF in the book's folder is found by itself.")
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.pdf]) { r in
            if case .success(let url) = r { _ = app.library.attachPDF(bookID, from: url) }
        }
    }
}

struct PDFScreen: View {
    @Environment(AppModel.self) private var app
    let bookID: String
    @State private var url: URL?
    @State private var failed = false

    var body: some View {
        Group {
            if let url {
                PDFKitView(url: url).ignoresSafeArea(edges: .bottom)
            } else if failed {
                ContentUnavailableView("Can't open the PDF", systemImage: "doc.questionmark", description: Text("Its file may have moved, or Google Drive needs a connection."))
            } else {
                ProgressView()
            }
        }
        .navigationTitle(app.library.book(bookID)?.displayTitle ?? "PDF")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let url { ToolbarItem(placement: .topBarTrailing) { ShareLink(item: url) } }
        }
        .task {
            guard let b = app.library.book(bookID) else { return failed = true }
            url = await app.library.pdfURL(b)
            failed = url == nil
        }
    }
}

private struct PDFKitView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PDFView {
        let v = PDFView()
        v.autoScales = true
        v.displayMode = .singlePageContinuous
        v.document = PDFDocument(url: url)
        return v
    }

    func updateUIView(_ v: PDFView, context: Context) {}
}

/// Chapters you can rename, move and add. The keyboard never hides what you're typing: the list
/// scrolls with it, and Next moves to the following name.
struct ChapterEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    struct Row: Identifiable {
        let id = UUID()
        var title: String
        var startMs: Int64
    }
    @State private var rows: [Row] = []
    @State private var intoFile = false
    @State private var saving = false
    @State private var message: String?
    @State private var loaded = false
    @FocusState private var focused: UUID?

    var body: some View {
        let file = app.library.singleFile(bookID)
        let playing = player.loaded?.id == bookID
        List {
            Section {
                ForEach($rows) { $r in
                    HStack(spacing: 10) {
                        TextField("Chapter name", text: $r.title)
                            .focused($focused, equals: r.id)
                            .submitLabel(r.id == rows.last?.id ? .done : .next)
                            .onSubmit {
                                if let i = rows.firstIndex(where: { $0.id == r.id }), i + 1 < rows.count { focused = rows[i + 1].id } else { focused = nil }
                            }
                        Menu {
                            if playing { Button("Start here (\(Fmt.clock(player.positionMs)))") { r.startMs = player.positionMs } }
                            Button("A little earlier (−5s)") { r.startMs = max(r.startMs - 5_000, 0) }
                            Button("A little later (+5s)") { r.startMs += 5_000 }
                        } label: {
                            Text(Fmt.clock(r.startMs)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { rows.remove(atOffsets: $0) }
            } footer: {
                Text(playing ? "Tap a time to move a chapter to where you are now." : "Play the book to set chapters at the spot you're listening to.")
            }
            Section {
                Button {
                    let at = playing ? player.positionMs : (rows.last.map { $0.startMs + 60_000 } ?? 0)
                    let r = Row(title: "Chapter \(rows.count + 1)", startMs: at)
                    rows.append(r)
                    rows.sort { $0.startMs < $1.startMs }
                    focused = r.id
                } label: { Label(playing ? "Add a chapter here (\(Fmt.clock(player.positionMs)))" : "Add a chapter", systemImage: "plus") }
            }
            if file != nil {
                Section {
                    Toggle("Also save into the audio file", isOn: $intoFile)
                } footer: {
                    Text("Writes the chapters into the M4B, so other apps see them too. Ember keeps a way back to the original.")
                }
            }
            if let message { Section { Text(message).foregroundStyle(.secondary) } }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Chapters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? "Saving…" : "Save") { Task { await save(file) } }.disabled(rows.isEmpty || saving)
            }
            ToolbarItemGroup(placement: .keyboard) {
                Button("Next") {
                    if let f = focused, let i = rows.firstIndex(where: { $0.id == f }), i + 1 < rows.count { focused = rows[i + 1].id } else { focused = nil }
                }
                Spacer()
                Button("Done typing") { focused = nil }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            rows = app.library.chapters(bookID).map { Row(title: $0.title, startMs: $0.startMs) }
            if let i = app.testChapterFocus, rows.indices.contains(i) { focused = rows[i].id }
        }
    }

    private func save(_ file: URL?) async {
        saving = true
        defer { saving = false }
        let list = rows.map { ($0.title.trimmingCharacters(in: .whitespaces).isEmpty ? "Chapter" : $0.title, $0.startMs) }
        app.library.setChapters(bookID, list)
        if player.loaded?.id == bookID { player.reloadChapters() }
        if intoFile, let file, let b = app.library.book(bookID) {
            let r = await Mp4TagWriter.write(file, tags: nil, chapters: list.map { ($0.1, $0.0) }.sorted { $0.0 < $1.0 }, totalMs: b.durationMs)
            if case .failed(let why) = r {
                message = "Saved in Ember. " + why
                return
            }
        }
        dismiss()
    }
}

/// Merge books into one (for a book split into several folders or files), in the order you choose.
struct MergeSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    @State private var chosen: [String] = []
    @State private var title = ""
    @State private var working = false

    var body: some View {
        NavigationStack {
            let candidates = app.library.book(bookID).map { app.library.mergeCandidates($0) } ?? []
            Form {
                Section {
                    TextField("Name of the merged book", text: $title)
                } footer: {
                    Text("Parts play in the order you tick them. Your place, bookmarks, clips and listening log come along, and you can split it again later.")
                }
                Section("Parts") {
                    ForEach(candidates) { b in
                        Button {
                            if let i = chosen.firstIndex(of: b.id) { chosen.remove(at: i) } else { chosen.append(b.id) }
                        } label: {
                            HStack {
                                if let i = chosen.firstIndex(of: b.id) {
                                    Text("\(i + 1)").font(.caption.weight(.bold)).foregroundStyle(.white)
                                        .frame(width: 24, height: 24).background(Color.accentColor, in: Circle())
                                } else {
                                    Image(systemName: "circle").font(.title3).foregroundStyle(.secondary).frame(width: 24)
                                }
                                VStack(alignment: .leading) {
                                    Text(b.displayTitle).foregroundStyle(.primary)
                                    Text(Fmt.duration(b.durationMs)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Merge books")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(working ? "Merging…" : "Merge") {
                        working = true
                        Task {
                            let id = await app.library.merge(chosen, title: title.trimmingCharacters(in: .whitespaces))
                            working = false
                            dismiss()
                            if let id { app.openBook = BookRoute(id: id) }
                        }
                    }
                    .disabled(chosen.count < 2 || title.trimmingCharacters(in: .whitespaces).isEmpty || working)
                }
            }
            .onAppear {
                guard chosen.isEmpty, let b = app.library.book(bookID) else { return }
                chosen = [bookID]
                title = Names.stripDisc(b.title)
            }
        }
    }
}
