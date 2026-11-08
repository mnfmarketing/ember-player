import SwiftUI

/// Apps that keep their audiobooks to themselves. Ember keeps those books on your shelf, opens the
/// right app when you tap play, and lets you log progress so your stats stay complete.
enum ExternalApp: String, CaseIterable, Identifiable {
    case audible = "AUDIBLE", libby = "LIBBY", spotify = "SPOTIFY", other = "OTHER"
    var id: String { rawValue }

    static func from(_ key: String) -> ExternalApp { ExternalApp(rawValue: key) ?? .other }

    var label: String {
        switch self {
        case .audible: return "Audible"
        case .libby: return "Libby"
        case .spotify: return "Spotify"
        case .other: return "Other"
        }
    }

    var scheme: URL? {
        switch self {
        case .audible: return URL(string: "audible://")
        case .libby: return URL(string: "libby://")
        case .spotify: return URL(string: "spotify://")
        case .other: return nil
        }
    }

    var appStore: URL? {
        switch self {
        case .audible: return URL(string: "https://apps.apple.com/app/id379693831")
        case .libby: return URL(string: "https://apps.apple.com/app/id1076402606")
        case .spotify: return URL(string: "https://apps.apple.com/app/id324684580")
        case .other: return nil
        }
    }

    @MainActor static func open(_ b: Book) {
        let app = from(b.externalApp)
        if let link = URL(string: b.externalLink.trimmingCharacters(in: .whitespaces)), link.scheme != nil {
            UIApplication.shared.open(link)
            return
        }
        guard let s = app.scheme else { return }
        UIApplication.shared.open(s) { ok in
            if !ok, let store = app.appStore { UIApplication.shared.open(store) }
        }
    }
}

/// Add a book you listen to in Audible, Libby or Spotify.
struct AddExternalView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var which: ExternalApp = .audible
    @State private var title = ""
    @State private var author = ""
    @State private var series = ""
    @State private var index = ""
    @State private var link = ""
    @State private var covers: [URL] = []
    @State private var chosen: URL?
    @State private var searching = false
    @State private var matches: [BookInfo.Match] = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Where you listen", selection: $which) { ForEach(ExternalApp.allCases) { Text($0.label).tag($0) } }
                        .pickerStyle(.segmented)
                } footer: {
                    Text("Audible, Libby and Spotify lock their audio to their own apps, so Ember keeps these on your shelf, opens the right app when you tap play, and lets you log progress so your stats stay complete.")
                }
                Section {
                    TextField("Title", text: $title)
                    TextField("Author", text: $author)
                    HStack {
                        TextField("Series (optional)", text: $series)
                        TextField("Book #", text: $index).keyboardType(.decimalPad).frame(width: 80)
                    }
                    TextField("Share link (optional)", text: $link).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                Section {
                    Button(searching ? "Searching…" : "Find covers and details") { Task { await find() } }
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || searching)
                    if !covers.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(covers, id: \.self) { u in
                                    AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.2) }
                                        .frame(width: 84, height: 84).clipShape(RoundedRectangle(cornerRadius: 8))
                                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.accentColor, lineWidth: chosen == u ? 3 : 0))
                                        .onTapGesture { chosen = u }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                } footer: {
                    if covers.isEmpty { Text("If you skip this, Ember looks up a cover by itself.") }
                }
            }
            .navigationTitle("Add from another app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { Task { await add() } }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func find() async {
        searching = true
        defer { searching = false }
        matches = await BookInfo.search(title: title, author: author)
        var urls = matches.compactMap(\.coverURL)
        urls += await OpenLibrary.covers(title: title, author: author)
        var seen = Set<URL>()
        covers = urls.filter { seen.insert($0).inserted }.prefix(10).map { $0 }
        chosen = covers.first
        if let m = matches.first {
            if author.isEmpty { author = m.authors }
            if series.isEmpty, !m.series.isEmpty {
                series = m.series
                if let i = m.seriesIndex { index = i == i.rounded() ? String(Int(i)) : String(i) }
            }
        }
    }

    private func add() async {
        let id = "ext-" + UUID().uuidString.lowercased()
        let b = Book(id: id, title: title.trimmingCharacters(in: .whitespaces))
        b.author = author.trimmingCharacters(in: .whitespaces)
        b.series = series.trimmingCharacters(in: .whitespaces)
        b.seriesIndex = Double(index.replacingOccurrences(of: ",", with: "."))
        b.source = Book.sourceExternal
        b.durationMs = 100
        b.externalApp = which.rawValue
        b.externalLink = link.trimmingCharacters(in: .whitespaces)
        b.userEdited = true
        if let m = matches.first(where: { $0.coverURL == chosen }) ?? matches.first {
            b.narrator = m.narrators
            b.desc = m.description
        }
        app.library.context.insert(b)
        app.library.changed()
        dismiss()
        var url = chosen
        if url == nil { url = (await BookInfo.search(title: b.title, author: b.author)).first?.coverURL }
        if url == nil { url = await OpenLibrary.covers(title: b.title, author: b.author).first }
        if let url, let (data, _) = try? await URLSession.shared.data(from: url), let saved = Covers.save(id: id, data: data) {
            b.coverFile = saved.0
            b.coverColor = saved.1
            app.library.changed()
        }
        app.openBook = BookRoute(id: id)
    }
}

enum OpenLibrary {
    static func covers(title: String, author: String) async -> [URL] {
        var c = URLComponents(string: "https://openlibrary.org/search.json")!
        c.queryItems = [URLQueryItem(name: "title", value: title), URLQueryItem(name: "limit", value: "8"), URLQueryItem(name: "fields", value: "cover_i")]
        if !author.isEmpty { c.queryItems?.append(URLQueryItem(name: "author", value: author)) }
        guard let url = c.url, let (data, _) = try? await URLSession.shared.data(from: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let docs = root["docs"] as? [[String: Any]] else { return [] }
        return docs.compactMap { ($0["cover_i"] as? NSNumber).flatMap { URL(string: "https://covers.openlibrary.org/b/id/\($0.intValue)-L.jpg") } }
    }
}

/// On an Audible/Libby/Spotify book's page: open it there, and keep your progress here.
struct ExternalControls: View {
    @Environment(AppModel.self) private var app
    @Bindable var book: Book
    let deep: Color
    @State private var logging = false
    @State private var minutes = 30

    var body: some View {
        let which = ExternalApp.from(book.externalApp)
        VStack(spacing: 12) {
            Button { ExternalApp.open(book) } label: {
                Label("Open in \(which.label)", systemImage: "arrow.up.forward.app").font(.headline).frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent)
            .tint(.white)
            .foregroundStyle(deep)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Progress").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("\(book.positionMs)%").font(.subheadline.monospacedDigit())
                }
                Slider(value: Binding(get: { Double(book.positionMs) }, set: { set(Int($0.rounded())) }), in: 0...100, step: 1)
                    .tint(.white)
                    .accessibilityLabel("Progress in \(which.label)")
                Button { logging = true } label: { Label("Log listening time", systemImage: "clock.badge.checkmark") }
                    .buttonStyle(.glass)
                    .controlSize(.small)
            }
            .foregroundStyle(.white)
            .padding(14)
            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .sheet(isPresented: $logging) {
            NavigationStack {
                Form {
                    Picker("Listened today", selection: $minutes) { ForEach([10, 15, 20, 30, 45, 60, 90, 120], id: \.self) { Text("\($0) min").tag($0) } }
                        .pickerStyle(.wheel)
                }
                .navigationTitle("Log listening")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { logging = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add") {
                            app.library.addListening(Int64(minutes) * 60_000)
                            book.lastPlayedAt = Clock.now
                            if book.status == Book.statusNew { book.status = Book.statusInProgress }
                            app.library.changed()
                            logging = false
                        }
                    }
                }
            }
            .presentationDetents([.height(320)])
        }
    }

    private func set(_ p: Int) {
        let v = min(max(p, 0), 100)
        book.positionMs = Int64(v)
        book.status = v == 0 ? Book.statusNew : (v == 100 ? Book.statusFinished : Book.statusInProgress)
        book.finishedAt = v == 100 ? (book.finishedAt > 0 ? book.finishedAt : Clock.now) : 0
        book.lastPlayedAt = Clock.now
        app.library.changed()
    }
}
