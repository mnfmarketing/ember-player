import SwiftUI
import SwiftData
import UniformTypeIdentifiers

enum SettingsSection: Hashable { case appearance, playback, library, podcasts, sync, backup, about }

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    /// Show just one topic (from the profile page), or everything (nil).
    var section: SettingsSection?

    var body: some View {
        Form {
            if show(.appearance) { AppearanceSection() }
            if show(.playback) { PlaybackSection() }
            if show(.library) { LibrarySection() }
            if show(.podcasts) { PodcastSection() }
            if show(.sync) { SyncSection() }
            if show(.backup) { BackupSection() }
            if show(.about) { AboutSection() }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func show(_ s: SettingsSection) -> Bool { section == nil || section == s }

    private var title: String {
        switch section {
        case .appearance: return "Appearance"
        case .playback: return "Playback"
        case .library: return "Library folders"
        case .podcasts: return "Podcasts"
        case .sync: return "Sync"
        case .backup: return "Backup and restore"
        case .about: return "Good to know"
        case nil: return "Settings"
        }
    }
}

private struct AppearanceSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Section("Appearance") {
            Picker("Light or dark", selection: $s.appearance) {
                Text("Match phone").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            Picker("Accent color", selection: $s.accentMode) {
                Text("Match the book").tag("book")
                Text("Ember orange").tag("theme")
                Text("Fixed color").tag("fixed")
            }
            if s.accentMode == "fixed" {
                HStack {
                    ForEach([0xE87A2E, 0xD9443A, 0xE0A019, 0x2E9E6B, 0x1E8A8A, 0x2F6FDE, 0x5B7083], id: \.self) { c in
                        Circle().fill(Color(hex: c)).frame(width: 30, height: 30)
                            .overlay(Circle().stroke(.primary, lineWidth: s.accentColor == c ? 2 : 0).padding(-3))
                            .onTapGesture { s.accentColor = c }
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }
}

private struct PlaybackSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Section {
            Picker("Skip back", selection: $s.skipBackSec) { ForEach([5, 10, 15, 30, 45, 60], id: \.self) { Text("\($0) seconds").tag($0) } }
            Picker("Skip forward", selection: $s.skipForwardSec) { ForEach([10, 15, 30, 45, 60, 90], id: \.self) { Text("\($0) seconds").tag($0) } }
            Picker("Rewind after a pause", selection: $s.autoRewindSec) {
                Text("Off").tag(0)
                ForEach([3, 5, 10, 15, 30], id: \.self) { Text("\($0) seconds").tag($0) }
            }
            Toggle("Play the next book in a series", isOn: $s.seriesAutoPlay)
            Toggle("Time left counts the whole book", isOn: $s.bookTimeLeft)
        } header: {
            Text("Playback")
        } footer: {
            Text("Rewind after a pause goes back a little after short pauses, the full amount after a few minutes, and more after an hour or a night.")
        }
    }
}

private struct LibrarySection: View {
    @Environment(AppModel.self) private var app
    @Query(sort: \LibraryFolder.name) private var folders: [LibraryFolder]
    @State private var picking = false

    var body: some View {
        Section {
            ForEach(folders) { f in
                VStack(alignment: .leading, spacing: 2) {
                    Label(f.name, systemImage: f.driveID == nil ? "folder" : "externaldrive.connected.to.line.below")
                    if let p = app.library.folderProblems[f.id] {
                        Text(p).font(.caption).foregroundStyle(.red)
                    } else if let at = f.lastScanAt {
                        Text("Checked \(at.formatted(.relative(presentation: .named)))").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button("Remove", role: .destructive) { app.library.removeFolder(f) }
                    Button("Rescan") { Task { await app.library.scan(f) } }.tint(.blue)
                }
            }
            Button { picking = true } label: { Label("Add a folder", systemImage: "plus") }
            if !folders.isEmpty {
                Button { Task { await app.library.scanAll() } } label: { Label("Look for new books", systemImage: "arrow.clockwise") }
            }
        } header: {
            Text("Library folders")
        } footer: {
            Text("Folders from the Files app: On My iPhone, iCloud Drive or other places. Books in iCloud that aren't downloaded yet are fetched and added a little later. Removing a folder only removes its books from Ember, never the files.")
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { r in
            if case .success(let url) = r { Task { await app.library.addFolder(url) } }
        }
    }
}

private struct PodcastSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Section {
            Picker("Episodes kept per show", selection: $s.autoDownloadCount) { ForEach([1, 2, 3, 5, 10], id: \.self) { Text("\($0)").tag($0) } }
            Button { Task { await app.podcasts.refreshAll() } } label: {
                Label(app.podcasts.refreshing ? "Checking…" : "Check for new episodes", systemImage: "arrow.clockwise")
            }
        } header: {
            Text("Podcasts")
        } footer: {
            Text("Auto download is off for new follows (turn it on per show). It only fetches new releases. iPhone checks for new episodes in the background when it decides to, so alerts can come a little late.")
        }
    }
}

private struct SyncSection: View {
    @Environment(AppModel.self) private var app
    @State private var naming = false
    @State private var name = ""

    var body: some View {
        let sync = app.sync
        Section {
            Toggle(isOn: Binding(get: { sync.isOn }, set: { on in
                if on { Task { _ = await sync.turnOn() } } else { sync.turnOff() }
            })) {
                VStack(alignment: .leading) {
                    Text("Sync with Google Drive")
                    Text(status).font(.caption).foregroundStyle(sync.error != nil && sync.isOn ? Color.red : Color.secondary)
                }
            }
            if sync.isOn {
                Button {
                    name = sync.deviceName
                    naming = true
                } label: {
                    LabeledContent("This device", value: sync.deviceName)
                }
                .foregroundStyle(.primary)
                LabeledContent("Also syncing with", value: sync.otherDevices.isEmpty ? "No other devices yet" : sync.otherDevices.joined(separator: ", "))
                Button { Task { await sync.syncNow() } } label: { Label(sync.running ? "Syncing…" : "Sync now", systemImage: "arrow.triangle.2.circlepath") }
                    .disabled(sync.running)
            }
            if !app.google.isConfigured {
                Text("Google sign-in needs setting up for this build (an iOS sign-in client in Ember's Google Cloud project).").font(.caption).foregroundStyle(.secondary)
            }
        } header: {
            Text("Sync")
        } footer: {
            Text("Keeps your place, bookmarks, stats, podcasts and listening log the same on your Fold and your other devices, through a hidden Ember folder on your Google Drive. Library folders and the look stay separate on each device.")
        }
        .alert("Name this device", isPresented: $naming) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Save") { if !name.isEmpty { sync.setDeviceName(name) } }
        }
    }

    private var status: String {
        let sync = app.sync
        if !sync.isOn { return "Off" }
        if sync.running { return "Syncing…" }
        if let e = sync.error { return "Couldn't sync: \(e)" }
        if let at = sync.lastSyncAt { return Date().timeIntervalSince(at) < 60 ? "On · synced just now" : "On · synced \(at.formatted(.relative(presentation: .named)))" }
        return "On"
    }
}

private struct BackupSection: View {
    @Environment(AppModel.self) private var app
    @State private var exporting = false
    @State private var importing = false
    @State private var doc: BackupDocument?
    @State private var message: String?

    var body: some View {
        Section {
            Button { doc = BackupDocument(text: Backup.export(app)); exporting = true } label: { Label("Save a backup…", systemImage: "square.and.arrow.up") }
            Button { importing = true } label: { Label("Restore from a backup…", systemImage: "arrow.counterclockwise") }
            if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
        } header: {
            Text("Backup and restore")
        } footer: {
            Text("The same backup file as Ember on Android: a backup from your Fold restores here, and the other way round. Restoring adds to your library rather than replacing it.")
        }
        .fileExporter(isPresented: $exporting, document: doc, contentType: .json, defaultFilename: "Ember backup \(Date().formatted(.iso8601.year().month().day()))") { _ in }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { r in
            guard case .success(let url) = r else { return }
            let ok = url.startAccessingSecurityScopedResource()
            let text = try? String(contentsOf: url, encoding: .utf8)
            if ok { url.stopAccessingSecurityScopedResource() }
            guard let text else { return }
            Task { message = await Backup.restore(app, text: text) }
        }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

private struct AboutSection: View {
    var body: some View {
        Section("Good to know") {
            info("Scrubbing", "Drag the slider; Ember only moves when you let go. Tap the time left to switch between the chapter and the whole book.")
            info("Sleep timer", "Shake your phone to start it over. Shake within 5 minutes after it stops to keep listening.")
            info("Pocket lock", "Tap the lock in the player so pocket taps do nothing. Press and hold to unlock. Headphone and lock screen controls keep working.")
            info("Headphones", "Double-press skips forward, triple-press skips back.")
            info("Listening log", "Every play, pause, skip and jump. Tap one to go back there.")
        }
    }

    private func info(_ t: String, _ b: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(t).font(.subheadline.weight(.semibold))
            Text(b).font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

/// Books, authors, shows and episodes.
struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Query private var books: [Book]
    @Query private var shows: [Podcast]
    @Query(sort: [SortDescriptor(\Episode.publishedAt, order: .reverse)]) private var episodes: [Episode]
    @State private var q = ""

    var body: some View {
        let t = fold(q)
        List {
            if !t.isEmpty {
                let bs = books.filter { fold($0.title + " " + $0.author + " " + $0.narrator + " " + $0.series).contains(t) }
                if !bs.isEmpty {
                    Section("Books") {
                        ForEach(bs) { b in
                            NavigationLink(value: BookRoute(id: b.id)) {
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
                let ps = shows.filter { fold($0.title + " " + $0.author).contains(t) }
                if !ps.isEmpty {
                    Section("Shows") { ForEach(ps) { p in NavigationLink(p.title, value: ShowRoute(id: p.id)) } }
                }
                let es = episodes.filter { fold($0.title).contains(t) }.prefix(30)
                if !es.isEmpty {
                    Section("Episodes") { ForEach(Array(es)) { e in EpisodeRow(episode: e, showTitle: true) } }
                }
            }
        }
        .navigationTitle("Search")
        .searchable(text: $q, placement: .automatic, prompt: "Books, authors, shows")
        .overlay { if q.isEmpty { ContentUnavailableView("Search everything", systemImage: "magnifyingglass", description: Text("Books, authors, narrators, series, shows and episodes.")) } }
        .navigationDestination(for: BookRoute.self) { BookView(bookID: $0.id) }
        .navigationDestination(for: ShowRoute.self) { ShowView(podcastID: $0.id) }
    }

    private func fold(_ s: String) -> String { s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).trimmingCharacters(in: .whitespaces) }
}
