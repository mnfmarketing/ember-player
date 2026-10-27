import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct ShowRoute: Hashable { var id: String }

struct PodcastsView: View {
    @Environment(AppModel.self) private var app
    @Query(sort: \Podcast.title) private var shows: [Podcast]
    @Query(sort: [SortDescriptor(\Episode.publishedAt, order: .reverse)]) private var episodes: [Episode]
    @Query(sort: \QueueItem.position) private var queue: [QueueItem]
    @AppStorage("podcastsTab") private var tab = 0
    @State private var finding = false
    @State private var addingRSS = false
    @State private var rss = ""
    @State private var importing = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("Show", selection: $tab) {
                Text("Shows").tag(0)
                Text("New").tag(1)
                Text("Up Next").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            switch tab {
            case 1: newList
            case 2: upNext
            default: showsGrid
            }
        }
        .navigationTitle("Podcasts")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { finding = true } label: { Label("Find a show", systemImage: "magnifyingglass") }
                    Button { addingRSS = true } label: { Label("Add by RSS link", systemImage: "link") }
                    Button { importing = true } label: { Label("Import OPML", systemImage: "square.and.arrow.down") }
                } label: { Image(systemName: "plus") }
                .accessibilityLabel("Add a show")
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            ToolbarItem(placement: .topBarTrailing) { ProfileButton() }
        }
        .refreshable { await app.podcasts.refreshAll() }
        .sheet(isPresented: $finding) { NavigationStack { FindShowView() } }
        .alert("Add by RSS link", isPresented: $addingRSS) {
            TextField("https://…", text: $rss).keyboardType(.URL).textInputAutocapitalization(.never)
            Button("Cancel", role: .cancel) { rss = "" }
            Button("Follow") {
                let url = rss.trimmingCharacters(in: .whitespaces)
                rss = ""
                Task {
                    if await app.podcasts.follow(url) == nil { app.player.notice = "Couldn't read that feed." }
                    app.sync.request("follow")
                }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "opml") ?? .xml, .xml, .text]) { result in
            guard case .success(let url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource()
            let data = try? Data(contentsOf: url)
            if ok { url.stopAccessingSecurityScopedResource() }
            guard let data else { return }
            Task {
                let r = await app.podcasts.importOPML(data)
                app.player.notice = "Followed \(r.added) show\(r.added == 1 ? "" : "s")" + (r.failed > 0 ? " (\(r.failed) couldn't be read)" : "")
                app.sync.request("follow")
            }
        }
        .navigationDestination(for: ShowRoute.self) { ShowView(podcastID: $0.id) }
    }

    @ViewBuilder
    private var showsGrid: some View {
        if shows.isEmpty {
            ContentUnavailableView {
                Label("No shows yet", systemImage: "mic")
            } description: {
                Text("Find a show, add one by its RSS link, or import an OPML file from your old podcast app.")
            } actions: {
                Button("Find a show") { finding = true }.buttonStyle(.glassProminent)
            }
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 14)], spacing: 14) {
                    ForEach(shows) { p in
                        NavigationLink(value: ShowRoute(id: p.id)) {
                            CoverView(file: p.artworkFile, title: p.title, color: p.color, corner: 12)
                                .overlay(alignment: .topTrailing) {
                                    let n = episodes.filter { $0.podcastID == p.id && !$0.played && !$0.archived && $0.positionMs == 0 && ($0.publishedAt ?? .distantPast) > (p.subscribedAt) }.count
                                    if n > 0 {
                                        Text("\(n)").font(.caption2.weight(.bold)).foregroundStyle(.white)
                                            .padding(.horizontal, 6).padding(.vertical, 2).background(Color.accentColor, in: Capsule()).padding(6)
                                    }
                                }
                        }
                        .accessibilityLabel(p.title)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private var newList: some View {
        let ids = Set(shows.map(\.id))
        let list = episodes.filter { ids.contains($0.podcastID) && !$0.played && !$0.archived }.prefix(60)
        return List {
            ForEach(Array(list)) { e in EpisodeRow(episode: e, showTitle: true) }
        }
        .listStyle(.plain)
    }

    private var upNext: some View {
        let byID = Dictionary(episodes.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let list = queue.compactMap { byID[$0.episodeID] }
        return Group {
            if list.isEmpty {
                ContentUnavailableView("Up Next is empty", systemImage: "text.line.first.and.arrowtriangle.forward",
                                       description: Text("Swipe an episode to add it, or turn on Add to Up Next for a show."))
            } else {
                List {
                    ForEach(list) { e in EpisodeRow(episode: e, showTitle: true) }
                        .onMove { app.podcasts.move(from: $0, to: $1) }
                        .onDelete { idx in idx.map { list[$0] }.forEach { app.podcasts.removeFromQueue($0) } }
                }
                .listStyle(.plain)
                .toolbar { EditButton() }
            }
        }
    }
}

struct EpisodeRow: View {
    @Environment(AppModel.self) private var app
    @Environment(Player.self) private var player
    let episode: Episode
    var showTitle = false

    var body: some View {
        let e = episode
        let playing = player.loaded?.id == "episode:" + e.id
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                if showTitle, let p = app.podcasts.podcast(e.podcastID) {
                    Text(p.title.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary).lineLimit(1)
                } else if let d = e.publishedAt {
                    Text(d.formatted(.dateTime.month(.abbreviated).day()).uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
                Spacer()
            }
            Text(e.title).font(.headline).lineLimit(2)
            if !e.desc.isEmpty { Text(e.desc).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
            HStack(spacing: 12) {
                Button {
                    if playing { player.toggle() } else { player.playEpisode(e) }
                } label: {
                    Label(pillText(e, playing: playing), systemImage: playing && player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.caption.weight(.bold))
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                if e.played {
                    Image(systemName: "checkmark.circle").foregroundStyle(.secondary).accessibilityLabel("Played")
                } else if e.downloadFile != nil {
                    Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary).accessibilityLabel("Downloaded")
                } else if app.podcasts.downloading[e.id] != nil {
                    ProgressView().controlSize(.small)
                }
                Spacer()
            }
            .padding(.top, 2)
        }
        .padding(.vertical, 6)
        .swipeActions(edge: .trailing) {
            Button { app.podcasts.setArchived(e, !e.archived) } label: { Label(e.archived ? "Unarchive" : "Archive", systemImage: "archivebox") }.tint(.gray)
            Button { app.podcasts.setPlayed(e, !e.played) } label: { Label(e.played ? "Unplayed" : "Played", systemImage: "checkmark") }.tint(.green)
        }
        .swipeActions(edge: .leading) {
            Button { app.podcasts.addToQueue(e, next: true) } label: { Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") }.tint(.orange)
            Button { app.podcasts.addToQueue(e, next: false) } label: { Label("Up Next", systemImage: "text.line.last.and.arrowtriangle.forward") }.tint(.blue)
        }
        .contextMenu {
            if e.downloadFile == nil {
                Button { Task { await app.podcasts.download(e) } } label: { Label("Download", systemImage: "arrow.down.circle") }
            } else {
                Button(role: .destructive) { app.podcasts.removeDownload(e); app.podcasts.changed() } label: { Label("Remove download", systemImage: "trash") }
            }
            Button { app.podcasts.addToQueue(e, next: false) } label: { Label("Add to Up Next", systemImage: "text.line.last.and.arrowtriangle.forward") }
            Button { app.podcasts.setPlayed(e, !e.played) } label: { Label(e.played ? "Mark unplayed" : "Mark played", systemImage: "checkmark") }
        }
    }

    private func pillText(_ e: Episode, playing: Bool) -> String {
        if e.played { return "Played" }
        let left = max(e.durationMs - e.positionMs, 0)
        if e.durationMs == 0 { return "Play" }
        return e.positionMs > 0 ? "\(Fmt.duration(left)) left" : Fmt.duration(e.durationMs)
    }
}

struct ShowView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let podcastID: String
    @Query private var found: [Podcast]
    @Query private var episodes: [Episode]
    @State private var filter = 0
    @State private var limit = 30
    @State private var settings = false

    init(podcastID: String) {
        self.podcastID = podcastID
        _found = Query(filter: #Predicate<Podcast> { $0.id == podcastID })
        _episodes = Query(filter: #Predicate<Episode> { $0.podcastID == podcastID }, sort: [SortDescriptor(\Episode.publishedAt, order: .reverse)])
    }

    var body: some View {
        if let p = found.first {
            List {
                VStack(spacing: 12) {
                    CoverView(file: p.artworkFile, title: p.title, color: p.color, corner: 14)
                        .frame(width: 196, height: 196)
                        .shadow(color: .black.opacity(0.25), radius: 16, y: 8)
                    Text(p.title).font(.title2.weight(.bold)).multilineTextAlignment(.center)
                    Text([p.author, p.category].filter { !$0.isEmpty }.joined(separator: " · ")).font(.subheadline).foregroundStyle(.secondary)
                    HStack(spacing: 22) {
                        RoundAction(title: "Following", icon: "checkmark", on: true) { }
                        RoundAction(title: "Alerts", icon: p.notify ? "bell.fill" : "bell", on: p.notify) { p.notify.toggle(); done() }
                        RoundAction(title: "Auto-get", icon: "arrow.down.circle", on: p.autoDownload) { p.autoDownload.toggle(); done() }
                        RoundAction(title: "Settings", icon: "gearshape", on: false) { settings = true }
                    }
                    .padding(.top, 4)
                    Picker("Episodes", selection: $filter) {
                        Text("Episodes").tag(0)
                        Text("Unplayed").tag(1)
                        Text("Downloaded").tag(2)
                        Text("Archived").tag(3)
                    }
                    .pickerStyle(.segmented)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
                let list = filtered
                ForEach(list.prefix(limit)) { e in EpisodeRow(episode: e) }
                if list.count > limit {
                    Button("Load more") { limit += 30 }.frame(maxWidth: .infinity)
                }
            }
            .listStyle(.plain)
            .navigationBarTitleDisplayMode(.inline)
            .refreshable { await app.podcasts.refreshAll() }
            .sheet(isPresented: $settings) { ShowSettingsView(podcast: p) }
        } else {
            ContentUnavailableView("Show not found", systemImage: "mic.slash")
        }
    }

    private func done() {
        app.podcasts.changed()
        app.sync.request("show")
    }

    private var filtered: [Episode] {
        switch filter {
        case 1: return episodes.filter { !$0.played && !$0.archived }
        case 2: return episodes.filter { $0.downloadFile != nil }
        case 3: return episodes.filter { $0.archived }
        default: return episodes.filter { !$0.archived }
        }
    }
}

struct RoundAction: View {
    let title: String
    let icon: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        VStack(spacing: 5) {
            Button(action: action) { Image(systemName: icon).font(.title3).frame(width: 46, height: 46) }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .tint(on ? Color.accentColor : nil)
            Text(title).font(.caption)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(on ? "On" : "Off")
    }
}

struct ShowSettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Bindable var podcast: Podcast
    @State private var confirmUnfollow = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("New episode alerts", isOn: $podcast.notify)
                    Toggle("Auto download new episodes", isOn: $podcast.autoDownload)
                    Toggle("Add new episodes to Up Next", isOn: $podcast.autoQueue)
                    Toggle("Archive played episodes", isOn: $podcast.autoArchive)
                }
                Section("Playback") {
                    Picker("Speed", selection: $podcast.speed) {
                        ForEach([0.8, 1.0, 1.1, 1.2, 1.3, 1.5, 1.8, 2.0], id: \.self) { Text(Fmt.speed($0)).tag($0) }
                    }
                    Picker("Skip intro", selection: $podcast.skipIntroSec) {
                        ForEach([0, 5, 10, 15, 30, 45, 60, 90], id: \.self) { Text($0 == 0 ? "Off" : "\($0) s").tag($0) }
                    }
                    Picker("Skip outro", selection: $podcast.skipOutroSec) {
                        ForEach([0, 5, 10, 15, 30, 45, 60], id: \.self) { Text($0 == 0 ? "Off" : "\($0) s").tag($0) }
                    }
                }
                Section {
                    Button("Unfollow", role: .destructive) { confirmUnfollow = true }
                }
            }
            .navigationTitle(podcast.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .onDisappear {
                app.podcasts.changed()
                app.sync.request("show")
            }
            .confirmationDialog("Unfollow \(podcast.title)?", isPresented: $confirmUnfollow, titleVisibility: .visible) {
                Button("Unfollow", role: .destructive) {
                    app.podcasts.unfollow(podcast)
                    app.sync.request("unfollow")
                    dismiss()
                }
            }
        }
    }
}

/// Top shows and search, from Apple's podcast directory. Tap one to preview it before following.
struct FindShowView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [SearchResult] = []
    @State private var charts: [SearchResult] = []
    @State private var genre: Int?
    @State private var loading = false

    var body: some View {
        List {
            if query.isEmpty {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            Button("Top") { genre = nil }.buttonStyle(.glass).tint(genre == nil ? Color.accentColor : nil)
                            ForEach(Feeds.genres, id: \.1) { g in
                                Button(g.0) { genre = g.1 }.buttonStyle(.glass).tint(genre == g.1 ? Color.accentColor : nil)
                            }
                        }
                    }
                    .listRowInsets(EdgeInsets())
                }
            }
            ForEach(query.isEmpty ? charts : results) { r in
                NavigationLink { ShowPreview(result: r) } label: {
                    HStack(spacing: 12) {
                        AsyncImage(url: URL(string: r.artworkURL)) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.2) }
                            .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading) {
                            Text(r.title).font(.headline).lineLimit(2)
                            Text(r.author).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                }
            }
            if loading { ProgressView().frame(maxWidth: .infinity) }
        }
        .navigationTitle("Find a show")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Shows and people")
        .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } } }
        .task(id: genre) {
            loading = true
            charts = await Feeds.topCharts(genreID: genre, genreName: Feeds.genres.first { $0.1 == genre }?.0)
            loading = false
        }
        .task(id: query) {
            guard !query.isEmpty else { return }
            try? await Task.sleep(for: .milliseconds(350))
            if Task.isCancelled { return }
            results = await Feeds.search(query)
        }
    }
}

struct ShowPreview: View {
    @Environment(AppModel.self) private var app
    let result: SearchResult
    @State private var feed: ParsedFeed?
    @State private var following = false
    @State private var busy = false

    var body: some View {
        List {
            VStack(spacing: 10) {
                AsyncImage(url: URL(string: result.artworkURL)) { $0.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.2) }
                    .frame(width: 180, height: 180).clipShape(RoundedRectangle(cornerRadius: 14))
                Text(result.title).font(.title2.weight(.bold)).multilineTextAlignment(.center)
                Text(result.author).foregroundStyle(.secondary)
                Button {
                    busy = true
                    Task {
                        let id = await app.podcasts.follow(result.feedURL, artworkHint: result.artworkURL)
                        following = id != nil
                        busy = false
                        app.sync.request("follow")
                    }
                } label: {
                    Label(following ? "Following" : "Follow", systemImage: following ? "checkmark" : "plus")
                        .frame(minWidth: 140)
                }
                .buttonStyle(.glassProminent)
                .disabled(following || busy)
                if let d = feed?.desc, !d.isEmpty { Text(d).font(.subheadline).foregroundStyle(.secondary).lineLimit(6) }
            }
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
            ForEach(Array((feed?.episodes ?? []).prefix(20).enumerated()), id: \.offset) { _, e in
                VStack(alignment: .leading, spacing: 3) {
                    if let d = e.publishedAt { Text(d.formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary) }
                    Text(e.title).font(.headline)
                    if e.durationMs > 0 { Text(Fmt.duration(e.durationMs)).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
        .listStyle(.plain)
        .task {
            following = app.podcasts.podcast(Ids.podcast(result.feedURL)) != nil
            feed = try? await Feeds.fetch(result.feedURL)
        }
    }
}
