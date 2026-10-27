import SwiftUI
import SwiftData
import PhotosUI

/// Your profile: photo and name, your listening, shortcuts, and settings by topic.
struct ProfileView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Query private var days: [DayListening]
    @Query private var books: [Book]
    @State private var photo: PhotosPickerItem?
    @State private var editingName = false
    @State private var name = ""

    var body: some View {
        let _ = days.count
        let s = app.library.stats()
        let finished = books.filter { $0.status == Book.statusFinished }.count
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 8) {
                        PhotosPicker(selection: $photo, matching: .images) {
                            Avatar(size: 104)
                                .overlay(alignment: .bottomTrailing) {
                                    Image(systemName: "camera.fill").font(.footnote)
                                        .frame(width: 34, height: 34).background(.background, in: Circle())
                                        .foregroundStyle(.primary)
                                        .overlay(Circle().stroke(.quaternary))
                                }
                        }
                        .accessibilityLabel("Change photo")
                        Button {
                            name = app.settings.profileName
                            editingName = true
                        } label: {
                            Text(app.settings.profileName.isEmpty ? "Add your name" : app.settings.profileName).font(.title.weight(.bold))
                        }
                        .buttonStyle(.plain)
                        Text("\(finished) book\(finished == 1 ? "" : "s") finished · \(Int(s.totalMs / 3_600_000)) hours").font(.subheadline).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }

                Section {
                    NavigationLink { StatsView() } label: {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("Your Listening").font(.title3.weight(.bold))
                            HStack {
                                stat("Today", "\(s.todayMs / 60_000)", "/ \(app.settings.dailyGoalMin)m")
                                stat("Streak", "\(s.streak)", s.streak == 1 ? "day" : "days")
                                stat("This year", "\(s.finishedThisYear)", s.finishedThisYear == 1 ? "book" : "books")
                            }
                            WeekBars(week: s.week)
                        }
                        .padding(.vertical, 6)
                    }
                }

                Section("Shortcuts") {
                    NavigationLink { UpNextBooksView() } label: { Row(icon: "text.line.last.and.arrowtriangle.forward", color: .green, title: "Up Next") }
                    NavigationLink { DownloadsView() } label: { Row(icon: "arrow.down.circle.fill", color: .blue, title: "Downloads") }
                }

                Section("Settings") {
                    NavigationLink { SettingsView(section: .appearance) } label: { Row(icon: "circle.lefthalf.filled", color: .orange, title: "Appearance") }
                    NavigationLink { SettingsView(section: .playback) } label: { Row(icon: "play.fill", color: .orange, title: "Playback") }
                    NavigationLink { SettingsView(section: .library) } label: { Row(icon: "folder.fill", color: .green, title: "Library folders") }
                    NavigationLink { SettingsView(section: .podcasts) } label: { Row(icon: "mic.fill", color: Color(hex: 0x8A3B12), title: "Podcasts") }
                    NavigationLink { SettingsView(section: .sync) } label: { Row(icon: "arrow.triangle.2.circlepath", color: .blue, title: "Sync with Google Drive") }
                    NavigationLink { SettingsView(section: .backup) } label: { Row(icon: "externaldrive.fill", color: .gray, title: "Backup and restore") }
                    NavigationLink { SettingsView(section: .about) } label: { Row(icon: "info", color: .blue, title: "Good to know") }
                }

                Section {
                    Text("Ember for \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone") · \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"))")
                        .font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                        .listRowBackground(Color.clear)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink { SettingsView(section: nil) } label: { Image(systemName: "gearshape") }.accessibilityLabel("All settings")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "checkmark") }
                        .buttonStyle(.glassProminent)
                        .accessibilityLabel("Done")
                }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), ProfilePhoto.save(data) {
                        app.settings.profilePhotoAt = Clock.now
                        app.sync.request("photo")
                    }
                }
            }
            .alert("Your name", isPresented: $editingName) {
                TextField("Name", text: $name)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    app.settings.profileName = name.trimmingCharacters(in: .whitespaces)
                    app.sync.request("name")
                }
            }
        }
    }

    private func stat(_ label: String, _ big: String, _ small: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(big).font(.title2.weight(.bold))
                Text(small).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct Row: View {
    let icon: String
    let color: Color
    let title: String

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: icon).font(.footnote.weight(.semibold)).foregroundStyle(.white)
                .frame(width: 29, height: 29).background(color, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
    }
}

struct WeekBars: View {
    let week: [(Date, Int64)]

    var body: some View {
        let top = max(week.map(\.1).max() ?? 1, 1)
        HStack(alignment: .bottom, spacing: 10) {
            ForEach(Array(week.enumerated()), id: \.offset) { _, d in
                VStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(d.1 > 0 ? Color.accentColor : Color.secondary.opacity(0.2))
                        .frame(height: max(CGFloat(Double(d.1) / Double(top)) * 76, 6))
                    Text(d.0.formatted(.dateTime.weekday(.narrow))).font(.caption2).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 96, alignment: .bottom)
        .accessibilityElement()
        .accessibilityLabel("Minutes listened each day this week")
        .accessibilityValue(week.map { "\($0.0.formatted(.dateTime.weekday(.wide))): \($0.1 / 60_000) minutes" }.joined(separator: ", "))
    }
}

struct StatsView: View {
    @Environment(AppModel.self) private var app
    @Query private var days: [DayListening]
    @Query(filter: #Predicate<Book> { $0.status == 2 }, sort: [SortDescriptor(\Book.finishedAt, order: .reverse)]) private var finished: [Book]

    var body: some View {
        let _ = days.count
        let s = app.library.stats()
        List {
            Section {
                GoalCard(todayMs: s.todayMs, goalMin: app.settings.dailyGoalMin, streak: s.streak).listRowInsets(EdgeInsets())
            }
            Section("This week") { WeekBars(week: s.week) }
            Section("All time") {
                LabeledContent("Listened", value: "\(s.totalMs / 3_600_000) hours")
                LabeledContent("Books finished", value: "\(s.finishedAllTime)")
                LabeledContent("This year", value: "\(s.finishedThisYear) of \(app.settings.yearlyGoalBooks)")
            }
            Section("Goals") {
                Picker("Daily goal", selection: Binding(get: { app.settings.dailyGoalMin }, set: { app.settings.dailyGoalMin = $0; app.sync.request("goal") })) {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { Text("\($0) min").tag($0) }
                }
                Picker("Books this year", selection: Binding(get: { app.settings.yearlyGoalBooks }, set: { app.settings.yearlyGoalBooks = $0; app.sync.request("goal") })) {
                    ForEach([12, 24, 36, 52, 100], id: \.self) { Text("\($0)").tag($0) }
                }
            }
            if !finished.isEmpty {
                Section("Finished") {
                    ForEach(finished) { b in
                        HStack {
                            CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 6).frame(width: 44, height: 44)
                            VStack(alignment: .leading) {
                                Text(b.displayTitle).font(.subheadline.weight(.semibold))
                                Text(b.author).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Your Listening")
    }
}

struct UpNextBooksView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let books = app.settings.upNext.compactMap { app.library.book($0) }
        List {
            if books.isEmpty {
                Text("Add books from their page or by holding their cover.").foregroundStyle(.secondary)
            }
            ForEach(books) { b in
                HStack {
                    CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 6).frame(width: 44, height: 44)
                    Text(b.displayTitle)
                    Spacer()
                    Button { app.player.playBook(b.id) } label: { Image(systemName: "play.fill") }.buttonStyle(.borderless)
                }
            }
            .onMove { app.settings.upNext.move(fromOffsets: $0, toOffset: $1); app.sync.request("upnext") }
            .onDelete { idx in app.settings.upNext.remove(atOffsets: idx); app.sync.request("upnext") }
        }
        .navigationTitle("Up Next")
        .toolbar { EditButton() }
    }
}

struct DownloadsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let _ = app.podcasts.version
        let eps = app.podcasts.downloaded()
        List {
            if eps.isEmpty { Text("Downloaded episodes show up here.").foregroundStyle(.secondary) }
            ForEach(eps) { e in EpisodeRow(episode: e, showTitle: true) }
            if !eps.isEmpty {
                Button("Remove played downloads", role: .destructive) {
                    for e in eps where e.played { app.podcasts.removeDownload(e) }
                    app.podcasts.changed()
                }
            }
        }
        .navigationTitle("Downloads")
    }
}
