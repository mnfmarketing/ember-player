import SwiftUI
import SwiftData

/// A book's page: cover-tinted, with Resume, the listening log and bookmarks, and its chapters.
struct BookView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    let bookID: String
    @Query private var found: [Book]
    @State private var showLog = false
    @State private var showMarks = false
    @State private var showSound = false
    @State private var confirmRemove = false

    init(bookID: String) {
        self.bookID = bookID
        _found = Query(filter: #Predicate<Book> { $0.id == bookID })
    }

    var body: some View {
        if let b = found.first {
            content(b)
        } else {
            ContentUnavailableView("Book not found", systemImage: "book.closed")
        }
    }

    @ViewBuilder
    private func content(_ b: Book) -> some View {
        let deep = Theme.deep(b.coverColor)
        let soft = Theme.soft(b.coverColor)
        let playing = player.loaded?.id == b.id
        let pos = playing ? player.positionMs : b.positionMs
        let chapters = app.library.chapters(b.id)
        let current = chapters.lastIndex { $0.startMs <= pos } ?? 0
        ScrollView {
            VStack(spacing: 14) {
                CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 12)
                    .frame(width: 200, height: 200)
                    .shadow(color: .black.opacity(0.5), radius: 20, y: 10)
                    .padding(.top, 8)
                VStack(spacing: 3) {
                    Text(b.title).font(.title2.weight(.bold)).multilineTextAlignment(.center)
                    if !b.author.isEmpty { Text(b.author).font(.body).foregroundStyle(soft) }
                    let extra = [b.narrator.isEmpty ? nil : "Narrated by \(b.narrator)",
                                 b.series.isEmpty ? nil : (b.seriesIndex.map { "\(b.series), Book \(Int($0))" } ?? b.series)].compactMap { $0 }
                    if !extra.isEmpty { Text(extra.joined(separator: " · ")).font(.subheadline).foregroundStyle(soft.opacity(0.85)).multilineTextAlignment(.center) }
                }
                .foregroundStyle(.white)

                HStack(spacing: 10) {
                    Button {
                        let was = playing && player.isPlaying
                        if playing { player.toggle() } else { player.playBook(b.id) }
                        if !was, UIDevice.current.userInterfaceIdiom == .phone { app.playerOpen = true }
                    } label: {
                        Label(resumeLabel(b, playing: playing, chapter: chapters.indices.contains(current) ? chapters[current].title : nil),
                              systemImage: playing && player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(deep)
                    .disabled(b.isExternal)
                    Button {
                        if app.settings.upNext.contains(b.id) { app.settings.upNext.removeAll { $0 == b.id } } else { app.settings.upNext.append(b.id) }
                    } label: {
                        Image(systemName: app.settings.upNext.contains(b.id) ? "checkmark" : "text.line.last.and.arrowtriangle.forward")
                            .font(.headline).frame(width: 34, height: 34)
                    }
                    .buttonStyle(.glass)
                    .accessibilityLabel(app.settings.upNext.contains(b.id) ? "In Up Next" : "Add to Up Next")
                }

                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        Pill(title: "Listening log", icon: "clock.arrow.circlepath") { showLog = true }
                        Pill(title: "Bookmarks", icon: "bookmark") { showMarks = true }
                        Pill(title: "Sound", icon: "slider.horizontal.3") { showSound = true }
                    }
                    .padding(.horizontal, 2)
                }
                .scrollIndicators(.hidden)
                .scrollBounceBehavior(.basedOnSize)

                HStack(spacing: 0) {
                    Fact(label: "LENGTH", value: Fmt.duration(b.durationMs), soft: soft)
                    Divider().frame(height: 30).overlay(.white.opacity(0.25))
                    Fact(label: b.status == Book.statusFinished ? "FINISHED" : "LEFT", value: b.status == Book.statusFinished ? "✓" : Fmt.duration(max(b.durationMs - pos, 0)), soft: soft)
                    Divider().frame(height: 30).overlay(.white.opacity(0.25))
                    VStack(spacing: 2) {
                        Text("RATING").font(.caption).foregroundStyle(soft)
                        HStack(spacing: 2) {
                            ForEach(1...5, id: \.self) { n in
                                Image(systemName: n <= b.rating ? "star.fill" : "star")
                                    .font(.caption).foregroundStyle(n <= b.rating ? Color(hex: 0xF3D9A4) : .white.opacity(0.4))
                                    .onTapGesture {
                                        b.rating = b.rating == n ? 0 : n
                                        app.library.changed()
                                    }
                            }
                        }
                        .accessibilityElement()
                        .accessibilityLabel("Rating \(b.rating) of 5")
                        .accessibilityAdjustableAction { dir in
                            b.rating = dir == .increment ? min(b.rating + 1, 5) : max(b.rating - 1, 0)
                            app.library.changed()
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .foregroundStyle(.white)

                if let saved = app.library.isDownloaded(b.id) {
                    DriveRow(bookID: b.id, saved: saved, soft: soft)
                }

                if !b.desc.isEmpty {
                    Text(b.desc).font(.subheadline).foregroundStyle(soft).frame(maxWidth: .infinity, alignment: .leading)
                }

                if !chapters.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Chapters").font(.title3.weight(.bold)).foregroundStyle(.white)
                        VStack(spacing: 0) {
                            ForEach(Array(chapters.enumerated()), id: \.offset) { i, c in
                                Button {
                                    if playing { player.goTo(chapter: i) } else {
                                        app.library.setProgress(b.id, positionMs: c.startMs)
                                        player.playBook(b.id)
                                    }
                                } label: {
                                    HStack(spacing: 12) {
                                        Group {
                                            if i == current && b.status != Book.statusFinished && (playing || b.positionMs > 0) {
                                                Image(systemName: "waveform").foregroundStyle(Color.accentColor)
                                            } else if c.endMs <= pos || b.status == Book.statusFinished {
                                                Image(systemName: "checkmark").foregroundStyle(.secondary)
                                            } else {
                                                Text("\(i + 1)").foregroundStyle(.secondary)
                                            }
                                        }
                                        .frame(width: 22)
                                        Text(c.title).lineLimit(1).foregroundStyle(i == current ? .primary : (c.endMs <= pos ? .secondary : .primary))
                                        Spacer()
                                        Text(Fmt.clock(c.endMs - c.startMs)).font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
                                    }
                                    .padding(.horizontal, 16).frame(minHeight: 50)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                if i < chapters.count - 1 { Divider().padding(.leading, 50) }
                            }
                        }
                        .background(Color(.secondarySystemBackground).opacity(0.92), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 30)
        }
        .background(LinearGradient(colors: [deep.opacity(1), deep.opacity(0.85), .black], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if b.status == Book.statusFinished {
                        Button { app.library.resetProgress(b.id) } label: { Label("Mark as not finished", systemImage: "arrow.uturn.backward") }
                    } else {
                        Button { app.library.markFinished(b.id) } label: { Label("Mark as finished", systemImage: "checkmark.circle") }
                    }
                    Button { app.library.resetProgress(b.id) } label: { Label("Start over", systemImage: "backward.end") }
                    Button(role: .destructive) { confirmRemove = true } label: { Label("Remove from Ember", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis") }
                .accessibilityLabel("More")
            }
        }
        .environment(\.colorScheme, .dark)
        .sheet(isPresented: $showLog) { ListenLogView(bookID: b.id, title: b.displayTitle) }
        .sheet(isPresented: $showMarks) { BookmarksView(bookID: b.id) }
        .sheet(isPresented: $showSound) { BookSoundView(book: b).presentationDetents([.medium]) }
        .confirmationDialog("Remove this book from Ember?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                app.library.deleteBook(b.id)
                dismiss()
            }
        } message: {
            Text("Its files stay in your folder. Rescanning the folder brings it back.")
        }
    }

    private func resumeLabel(_ b: Book, playing: Bool, chapter: String?) -> String {
        if playing && player.isPlaying { return "Pause" }
        if b.status == Book.statusFinished { return "Listen again" }
        if b.positionMs > 0 || playing { return "Resume" + (chapter.map { " · \($0)" } ?? "") }
        return "Play"
    }
}

struct Pill: View {
    let title: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.subheadline.weight(.medium)).lineLimit(1).fixedSize().padding(.horizontal, 4)
        }
        .buttonStyle(.glass)
        .controlSize(.small)
    }
}

private struct Fact: View {
    let label: String
    let value: String
    let soft: Color

    var body: some View {
        VStack(spacing: 2) {
            Text(label).font(.caption).foregroundStyle(soft)
            Text(value).font(.headline)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// A book's own skips: repeated intros and outros at the start and end of each file.
struct BookSoundView: View {
    @Environment(AppModel.self) private var app
    @Bindable var book: Book

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Skip at the start of each file", selection: $book.skipIntroSec) {
                        ForEach([0, 3, 5, 10, 15, 30], id: \.self) { Text($0 == 0 ? "Off" : "\($0) s").tag($0) }
                    }
                    Picker("Skip at the end of each file", selection: $book.skipOutroSec) {
                        ForEach([0, 3, 5, 10, 15], id: \.self) { Text($0 == 0 ? "Off" : "\($0) s").tag($0) }
                    }
                } header: {
                    Text("Just for this book")
                } footer: {
                    Text("For books that repeat the same few seconds at every file. Scrubbing or picking a chapter still goes exactly where you choose.")
                }
            }
            .navigationTitle("Sound")
            .navigationBarTitleDisplayMode(.inline)
            .onDisappear { app.library.changed() }
        }
    }
}

/// Drive books: stream by default, or save on this device for listening offline.
private struct DriveRow: View {
    @Environment(AppModel.self) private var app
    let bookID: String
    let saved: Bool
    let soft: Color

    var body: some View {
        let _ = app.library.version
        HStack(spacing: 12) {
            Image(systemName: saved ? "checkmark.icloud.fill" : "icloud").font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                if let p = app.library.saving[bookID] {
                    Text("Downloading… \(p.0) of \(p.1)").font(.subheadline.weight(.semibold))
                    ProgressView(value: Double(p.0), total: Double(max(p.1, 1))).tint(.white)
                } else {
                    Text(saved ? "Downloaded" : "Streams from Google Drive").font(.subheadline.weight(.semibold))
                    Text(saved ? "Plays without a connection." : "Download it to listen offline.").font(.caption).foregroundStyle(soft)
                }
            }
            Spacer()
            if app.library.saving[bookID] == nil {
                if saved {
                    Button("Remove", role: .destructive) { app.library.removeDownload(bookID) }.buttonStyle(.glass)
                } else {
                    Button("Download") { Task { await app.library.download(bookID) } }.buttonStyle(.glass)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(14)
        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
