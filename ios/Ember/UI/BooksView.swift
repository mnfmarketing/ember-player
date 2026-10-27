import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct BooksView: View {
    @Environment(AppModel.self) private var app
    @Query(sort: [SortDescriptor(\Book.addedAt, order: .reverse)]) private var books: [Book]
    @AppStorage("booksFilter") private var filter = 0
    @AppStorage("booksSort") private var sort = "recent"
    @State private var picking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("Show", selection: $filter) {
                    Text("All").tag(0)
                    Text("Listening").tag(1)
                    Text("Not started").tag(2)
                    Text("Finished").tag(3)
                }
                .pickerStyle(.segmented)

                if let s = app.library.scanning {
                    Label("Looking for books in \(s)…", systemImage: "magnifyingglass").font(.subheadline).foregroundStyle(.secondary)
                }
                if books.isEmpty {
                    ContentUnavailableView {
                        Label("No books yet", systemImage: "books.vertical")
                    } description: {
                        Text("Add a folder of audiobooks from the Files app: On My iPhone, iCloud Drive or another place you keep them.")
                    } actions: {
                        Button("Add a folder") { picking = true }.buttonStyle(.glassProminent)
                    }
                    .padding(.top, 40)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 18) {
                        ForEach(shown) { b in
                            NavigationLink(value: BookRoute(id: b.id)) { BookTile(book: b) }
                                .buttonStyle(.plain)
                                .bookMenu(b)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .navigationTitle("Books")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        Label("Recently played", systemImage: "clock").tag("recent")
                        Label("Recently added", systemImage: "plus.circle").tag("added")
                        Label("Title", systemImage: "textformat").tag("title")
                        Label("Author", systemImage: "person").tag("author")
                        Label("Progress", systemImage: "chart.bar").tag("progress")
                    }
                } label: { Image(systemName: "line.3.horizontal.decrease") }
                .accessibilityLabel("Sort")
                Button { picking = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add a folder")
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            ToolbarItem(placement: .topBarTrailing) { ProfileButton() }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { Task { await app.library.addFolder(url) } }
        }
        .navigationDestination(for: BookRoute.self) { BookView(bookID: $0.id) }
    }

    private var shown: [Book] {
        var list = books.filter { b in
            switch filter {
            case 1: return b.status == Book.statusInProgress
            case 2: return b.status == Book.statusNew
            case 3: return b.status == Book.statusFinished
            default: return true
            }
        }
        switch sort {
        case "recent": list.sort { ($0.lastPlayedAt, $0.addedAt) > ($1.lastPlayedAt, $1.addedAt) }
        case "title": list.sort { $0.displayTitle.localizedStandardCompare($1.displayTitle) == .orderedAscending }
        case "author": list.sort { ($0.author.lowercased(), $0.title.lowercased()) < ($1.author.lowercased(), $1.title.lowercased()) }
        case "progress": list.sort { $0.progress > $1.progress }
        default: break
        }
        return list
    }
}

struct BookTile: View {
    let book: Book

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CoverView(file: book.coverFile, title: book.displayTitle, color: book.coverColor)
                .shadow(color: .black.opacity(0.18), radius: 10, y: 5)
                .overlay(alignment: .bottomTrailing) {
                    if book.status == Book.statusFinished {
                        Image(systemName: "checkmark").font(.caption.weight(.bold))
                            .frame(width: 26, height: 26).background(.white, in: Circle()).foregroundStyle(.black).padding(8)
                    }
                }
            if book.status == Book.statusInProgress {
                ProgressLine(value: book.progress, tint: .accentColor).padding(.top, 4)
            } else {
                Color.clear.frame(height: 3).padding(.top, 4)
            }
            Text(book.displayTitle).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }

    private var detail: String {
        let who = book.author.isEmpty ? Fmt.duration(book.durationMs) : book.author
        switch book.status {
        case Book.statusFinished: return "\(who) · Finished"
        case Book.statusInProgress: return "\(who) · \(Int(book.progress * 100))%"
        default: return "\(who) · New"
        }
    }
}

/// Long-press a book: the quick actions.
struct BookMenu: ViewModifier {
    @Environment(AppModel.self) private var app
    let book: Book

    func body(content: Content) -> some View {
        content.contextMenu {
            Button { app.player.playBook(book.id) } label: {
                Label(book.status == Book.statusInProgress ? "Resume" : "Play", systemImage: "play.fill")
            }
            if app.settings.upNext.contains(book.id) {
                Button { app.settings.upNext.removeAll { $0 == book.id } } label: { Label("Remove from Up Next", systemImage: "text.badge.minus") }
            } else {
                Button { app.settings.upNext.append(book.id) } label: { Label("Add to Up Next", systemImage: "text.line.last.and.arrowtriangle.forward") }
            }
            if book.status == Book.statusFinished {
                Button { app.library.resetProgress(book.id) } label: { Label("Mark as not finished", systemImage: "arrow.uturn.backward") }
            } else {
                Button { app.library.markFinished(book.id) } label: { Label("Mark as finished", systemImage: "checkmark.circle") }
            }
            Button { app.library.resetProgress(book.id) } label: { Label("Start over", systemImage: "backward.end") }
            Menu {
                ForEach(app.library.shelves()) { s in
                    Button(s.name) {
                        if !s.books.contains(book.id) { s.books.append(book.id) }
                        app.library.changed()
                    }
                }
                Button("New shelf…") { app.newShelfFor = book.id }
            } label: { Label("Add to shelf", systemImage: "books.vertical") }
        }
    }
}

extension View {
    func bookMenu(_ book: Book) -> some View { modifier(BookMenu(book: book)) }
}
