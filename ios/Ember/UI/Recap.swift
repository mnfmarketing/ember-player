import SwiftUI

/// A month or a year of listening, as a card to share.
struct Recap {
    var label: String
    var periodWord: String
    var listenedMs: Int64 = 0
    var daysListened = 0
    var finished: [Book] = []
    var topAuthor: String?
    var topNarrator: String?
    var longestStreak = 0
    var bestDay: String?
    var bestDayMs: Int64 = 0
    var favorite: Book?
    var clips = 0
    var clipQuote: String?
    var accent: Int?

    /// 0 = this month, 1 = last month, 2 = this year.
    @MainActor static func make(_ lib: Library, period: Int) -> Recap {
        let cal = Calendar.current
        let now = Date()
        var start: Date, end: Date, label: String, word: String
        switch period {
        case 1:
            let thisMonth = cal.date(from: cal.dateComponents([.year, .month], from: now))!
            start = cal.date(byAdding: .month, value: -1, to: thisMonth)!
            end = cal.date(byAdding: .day, value: -1, to: thisMonth)!
            label = start.formatted(.dateTime.month(.wide))
            word = "month"
        case 2:
            start = cal.date(from: cal.dateComponents([.year], from: now))!
            end = now
            label = String(cal.component(.year, from: now))
            word = "year"
        default:
            start = cal.date(from: cal.dateComponents([.year, .month], from: now))!
            end = now
            label = now.formatted(.dateTime.month(.wide))
            word = "month"
        }
        var r = Recap(label: label, periodWord: word)
        let d0 = Clock.epochDay(start), d1 = Clock.epochDay(end)
        let days = lib.days().filter { $0.day >= d0 && $0.day <= d1 && $0.ms > 0 }
        r.listenedMs = days.reduce(0) { $0 + $1.ms }
        r.daysListened = days.count
        var run = 0, prev = Int64.min
        for d in days.map(\.day).sorted() {
            run = d == prev + 1 ? run + 1 : 1
            r.longestStreak = max(r.longestStreak, run)
            prev = d
        }
        if let best = days.max(by: { $0.ms < $1.ms }) {
            r.bestDayMs = best.ms
            r.bestDay = Clock.date(epochDay: best.day).formatted(.dateTime.month(.abbreviated).day())
        }
        let from = Int64(start.timeIntervalSince1970 * 1000)
        let to = Int64((cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: end)) ?? end).timeIntervalSince1970 * 1000)
        let books = lib.books()
        r.finished = books.filter { $0.status == Book.statusFinished && $0.finishedAt >= from && $0.finishedAt < to }.sorted { $0.finishedAt < $1.finishedAt }
        let pool = r.finished.isEmpty ? books.filter { $0.lastPlayedAt >= from && $0.lastPlayedAt < to } : r.finished
        func top(_ key: (Book) -> String) -> String? {
            let counts = Dictionary(grouping: pool.map { key($0).split(separator: ",").first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? "" }.filter { !$0.isEmpty }, by: { $0 }).mapValues(\.count)
            return counts.max { ($0.value, $1.key) < ($1.value, $0.key) }?.key
        }
        r.topAuthor = top(\.author)
        r.topNarrator = top(\.narrator)
        r.favorite = r.finished.filter { $0.rating > 0 }.max { $0.rating < $1.rating }
        let clips = lib.clips().filter { $0.createdAt >= from && $0.createdAt < to }
        r.clips = clips.count
        r.clipQuote = clips.first { !$0.note.isEmpty && $0.note.count < 160 }?.note
        r.accent = (r.favorite ?? r.finished.last ?? pool.first)?.coverColor
        return r
    }
}

struct RecapArt: View {
    let recap: Recap
    let format: CardFormat

    var body: some View {
        let size = format.size
        let story = format == .story
        let accent = recap.accent.map(CardColors.tune) ?? 0xC2410C
        let pop = Color(hex: CardColors.lighten(accent))
        let ink = Color(hex: 0xF8F1EB)
        let soft = Color(hex: 0xF3E6DC).opacity(0.8)
        let faint = Color(hex: 0xF3E6DC).opacity(0.5)
        let hours = Double(recap.listenedMs) / 3_600_000
        let big = hours >= 10 ? String(format: "%.0f", hours) : String(format: "%.1f", hours)
        VStack(alignment: .leading, spacing: 0) {
            Text("MY \(recap.label.uppercased())").font(.system(size: 32, weight: .semibold)).kerning(4).foregroundStyle(pop)
            Text("in audiobooks").font(.system(size: story ? 92 : 80, design: .serif)).foregroundStyle(ink).padding(.top, 8)
            HStack(alignment: .center, spacing: 24) {
                Text(big).font(.system(size: story ? 260 : 200, weight: .semibold)).foregroundStyle(ink).minimumScaleFactor(0.5).lineLimit(1)
                VStack(alignment: .leading, spacing: 6) {
                    Text(hours == 1 ? "hour" : "hours")
                    Text("listened")
                }
                .font(.system(size: 44)).foregroundStyle(soft)
            }
            .padding(.top, story ? 50 : 20)
            Text("on \(recap.daysListened) \(recap.daysListened == 1 ? "day" : "days") this \(recap.periodWord)").font(.system(size: 34)).foregroundStyle(faint)
            if !recap.finished.isEmpty {
                let covers = Array(recap.finished.suffix(6))
                let side: CGFloat = story ? 230 : 190
                let width = size.width - 168
                let step = min(side + 24, (width - side) / CGFloat(max(covers.count - 1, 1)))
                ZStack(alignment: .topLeading) {
                    ForEach(Array(covers.enumerated()), id: \.offset) { i, b in
                        CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 14)
                            .frame(width: side, height: side)
                            .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
                            .offset(x: CGFloat(i) * step)
                    }
                }
                .frame(width: width, height: side, alignment: .topLeading)
                .padding(.top, story ? 70 : 44)
            }
            let tiles: [(String, String)] = [("BOOKS FINISHED", "\(recap.finished.count)"),
                                             ("LONGEST STREAK", "\(recap.longestStreak) \(recap.longestStreak == 1 ? "day" : "days")")]
                + (recap.topAuthor.map { [("TOP AUTHOR", $0)] } ?? []) + (recap.topNarrator.map { [("TOP NARRATOR", $0)] } ?? [])
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 40, alignment: .topLeading), GridItem(.flexible(), alignment: .topLeading)], alignment: .leading, spacing: story ? 44 : 30) {
                ForEach(tiles, id: \.0) { k, v in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(k).font(.system(size: 28, weight: .semibold)).kerning(2.5).foregroundStyle(faint)
                        Text(v).font(.system(size: story ? 54 : 46, weight: .semibold)).foregroundStyle(ink).lineLimit(2)
                    }
                }
            }
            .padding(.top, story ? 70 : 46)
            VStack(alignment: .leading, spacing: 20) {
                if let f = recap.favorite { Text("Favorite: \(f.displayTitle)  " + String(repeating: "★", count: f.rating)).lineLimit(2) }
                if let d = recap.bestDay, recap.bestDayMs > 0 { Text("Biggest day: \(d) · \(Fmt.duration(recap.bestDayMs))").lineLimit(1) }
            }
            .font(.system(size: 36)).foregroundStyle(soft)
            .padding(.top, 30)
            if story, let q = recap.clipQuote {
                Text("\u{201C}\(q)\u{201D}").font(.system(size: 42, design: .serif)).italic().foregroundStyle(ink).lineLimit(5).padding(.top, 40)
            }
            Spacer(minLength: 0)
            HStack(spacing: 14) {
                Spacer()
                Circle().fill(pop).frame(width: 16, height: 16)
                Text("LISTENED WITH EMBER").font(.system(size: 28)).kerning(3).foregroundStyle(faint)
            }
        }
        .padding(.horizontal, 84)
        .padding(.top, story ? 150 : 90)
        .padding(.bottom, 84)
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .background(LinearGradient(colors: [Color(hex: mix(accent, 0x0B0807, 0.72)), Color(hex: 0x0B0807)], startPoint: .top, endPoint: .bottom))
        .environment(\.colorScheme, .dark)
    }

    private func mix(_ a: Int, _ b: Int, _ t: Double) -> Int {
        func ch(_ x: Int, _ s: Int) -> Double { Double((x >> s) & 0xFF) }
        func m(_ s: Int) -> Int { Int(ch(a, s) * (1 - t) + ch(b, s) * t) }
        return (m(16) << 16) | (m(8) << 8) | m(0)
    }
}

struct RecapView: View {
    @Environment(AppModel.self) private var app
    @State private var period = 0
    @State private var format: CardFormat = .story
    @State private var image: UIImage?
    @State private var saved: String?

    var body: some View {
        Form {
            Section {
                Picker("Period", selection: $period) {
                    Text("This month").tag(0)
                    Text("Last month").tag(1)
                    Text("This year").tag(2)
                }
                .pickerStyle(.segmented)
                Picker("Size", selection: $format) { ForEach(CardFormat.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
            }
            Section {
                Group {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    } else {
                        ProgressView().frame(height: 300)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(maxHeight: 520)
                .listRowBackground(Color.clear)
            }
            if let image {
                Section {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview("My listening", image: Image(uiImage: image))) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        Task { saved = await CardImage.save(image) ? "Saved to Photos" : "Ember can't save to Photos (allow it in Settings)" }
                    } label: { Label("Save to Photos", systemImage: "photo.badge.arrow.down") }
                    if let saved { Text(saved).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
        .navigationTitle("Your recap")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(period)|\(format.rawValue)") {
            let r = Recap.make(app.library, period: period)
            image = CardImage.render(RecapArt(recap: r, format: format), size: format.size)
            saved = nil
        }
    }
}
