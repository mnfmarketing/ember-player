import SwiftUI
import SwiftData

struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    @Query(sort: [SortDescriptor(\Book.lastPlayedAt, order: .reverse)]) private var books: [Book]
    @Query private var days: [DayListening]

    var body: some View {
        let s = app.library.stats()
        let _ = days.count
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 6) {
                    Image(systemName: "flame.fill").font(.system(size: 28)).foregroundStyle(Theme.ember)
                    Text("Ember").font(.system(size: 34, weight: .bold))
                }
                .accessibilityAddTraits(.isHeader)

                if let b = continueBook {
                    ContinueCard(book: b)
                } else {
                    EmptyHome()
                }

                if let mark = SleepMarks.get(), mark.awakeMs >= 0, player.loaded?.id == mark.bookID, !player.isPlaying {
                    FellAsleepCard(mark: mark)
                }

                GoalCard(todayMs: s.todayMs, goalMin: app.settings.dailyGoalMin, streak: s.streak)

                let upNext = app.settings.upNext.compactMap { id in books.first { $0.id == id } }
                if !upNext.isEmpty {
                    BookRow(title: "Up Next", books: upNext, numbered: true)
                }
                let recent = books.filter { !$0.isExternal }.sorted { $0.addedAt > $1.addedAt }.prefix(10)
                if !recent.isEmpty {
                    BookRow(title: "Recently Added", books: Array(recent), numbered: false)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { ProfileButton() } }
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: BookRoute.self) { BookView(bookID: $0.id) }
    }

    private var continueBook: Book? {
        if let id = player.loaded?.id, let b = books.first(where: { $0.id == id }) { return b }
        return books.first { $0.status == Book.statusInProgress && !$0.isExternal }
    }
}

struct BookRoute: Hashable { var id: String }

private struct ContinueCard: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    let book: Book

    var body: some View {
        let playing = player.loaded?.id == book.id
        let pos = playing ? player.positionMs : book.positionMs
        let chapter = app.library.chapters(book.id).last { $0.startMs <= pos }
        HStack(alignment: .top, spacing: 16) {
            NavigationLink(value: BookRoute(id: book.id)) {
                CoverView(file: book.coverFile, title: book.displayTitle, color: book.coverColor)
                    .frame(width: 128, height: 128)
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 6)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("CONTINUE LISTENING").font(.caption.weight(.semibold)).foregroundStyle(Theme.soft(book.coverColor))
                Text(book.displayTitle).font(.title3.weight(.semibold)).lineLimit(2).foregroundStyle(.white)
                if let chapter {
                    Text("\(chapter.title) · \(Fmt.duration(max(chapter.endMs - pos, 0))) left")
                        .font(.subheadline).foregroundStyle(Theme.soft(book.coverColor)).lineLimit(1)
                }
                ProgressLine(value: book.durationMs > 0 ? Double(pos) / Double(book.durationMs) : 0, tint: .white)
                HStack {
                    Button {
                        if playing { player.toggle() } else { player.playBook(book.id) }
                    } label: {
                        Label(playing && player.isPlaying ? "Pause" : "Resume", systemImage: playing && player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(Theme.deep(book.coverColor))
                    Spacer()
                    Text("\(Fmt.duration(max(book.durationMs - pos, 0))) left").font(.caption).foregroundStyle(Theme.soft(book.coverColor))
                }
            }
        }
        .padding(16)
        .background(Theme.deep(book.coverColor), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }
}

private struct EmptyHome: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your audiobooks, all in one place").font(.title3.weight(.semibold))
            Text("Add a folder of audiobooks from the Files app (On My iPhone or iCloud Drive), or follow a podcast.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button("Go to Books") { app.selectedTab = .books }.buttonStyle(.glassProminent)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }
}

private struct FellAsleepCard: View {
    @Environment(Player.self) private var player
    let mark: SleepMarks.Mark

    var body: some View {
        HStack {
            Image(systemName: "moon.zzz.fill").font(.title2)
            VStack(alignment: .leading) {
                Text("Fell asleep?").font(.headline)
                Text("Last awake around \(Fmt.clock(mark.awakeMs))").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Go back") {
                player.seek(to: mark.awakeMs)
                SleepMarks.clear()
            }
            .buttonStyle(.glass)
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct GoalCard: View {
    let todayMs: Int64
    let goalMin: Int
    let streak: Int

    var body: some View {
        let mins = Int(todayMs / 60_000)
        let frac = goalMin > 0 ? min(Double(mins) / Double(goalMin), 1) : 0
        HStack(spacing: 14) {
            ZStack {
                Circle().stroke(.quaternary, lineWidth: 7)
                Circle().trim(from: 0, to: frac).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 7, lineCap: .round)).rotationEffect(.degrees(-90))
                if frac >= 1 { Image(systemName: "checkmark").font(.headline.weight(.bold)).foregroundStyle(Color.accentColor) }
            }
            .frame(width: 52, height: 52)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(frac >= 1 ? "Goal met" : "\(mins) of \(goalMin) min today").font(.headline)
                Text(frac >= 1 ? "\(mins) minutes today" : "\(max(goalMin - mins, 0)) min to your goal").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            if streak > 0 {
                Label("\(streak) day\(streak == 1 ? "" : "s")", systemImage: "flame.fill")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(.quaternary, in: Capsule())
            }
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct BookRow: View {
    let title: String
    let books: [Book]
    let numbered: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title2.weight(.bold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(Array(books.enumerated()), id: \.element.id) { i, b in
                        NavigationLink(value: BookRoute(id: b.id)) {
                            VStack(alignment: .leading, spacing: 6) {
                                CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 8)
                                    .frame(width: 120, height: 120)
                                    .overlay(alignment: .topLeading) {
                                        if numbered {
                                            Text("\(i + 1)").font(.caption.weight(.bold)).foregroundStyle(.white)
                                                .frame(width: 22, height: 22).background(.black.opacity(0.6), in: Circle()).padding(6)
                                        }
                                    }
                                Text(b.displayTitle).font(.footnote.weight(.semibold)).lineLimit(1).foregroundStyle(.primary)
                                Text(Fmt.duration(b.durationMs)).font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(width: 120)
                        }
                        .buttonStyle(.plain)
                        .bookMenu(b)
                    }
                }
            }
            .scrollClipDisabled()
        }
    }
}
