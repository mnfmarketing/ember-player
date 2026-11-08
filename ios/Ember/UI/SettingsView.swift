import SwiftUI
import SwiftData
import UniformTypeIdentifiers

/// The Settings pages, in the order the list shows them (same topics as Ember on the Fold).
enum SettingsSection: String, CaseIterable, Hashable, Identifiable {
    case appearance, playback, sleep, sound, library, downloads, podcasts, car, sync, backup, goals, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .appearance: return "Appearance"
        case .playback: return "Playback"
        case .sleep: return "Sleep timer"
        case .sound: return "Sound"
        case .car: return "Car mode"
        case .library: return "Library"
        case .downloads: return "Downloads and storage"
        case .podcasts: return "Podcasts"
        case .sync: return "Sync"
        case .backup: return "Backup and restore"
        case .goals: return "Goals"
        case .about: return "About"
        }
    }

    var icon: String {
        switch self {
        case .appearance: return "circle.lefthalf.filled"
        case .playback: return "play.fill"
        case .sleep: return "moon.fill"
        case .sound: return "slider.vertical.3"
        case .car: return "car.fill"
        case .library: return "folder.fill"
        case .downloads: return "arrow.down.circle.fill"
        case .podcasts: return "mic.fill"
        case .sync: return "arrow.triangle.2.circlepath"
        case .backup: return "externaldrive.fill"
        case .goals: return "flag.fill"
        case .about: return "info"
        }
    }

    var color: Color {
        switch self {
        case .appearance: return Color(hex: 0x7E8CE0)
        case .playback: return Color(hex: 0xE2683C)
        case .sleep: return Color(hex: 0x5E5CE6)
        case .sound: return Color(hex: 0xD6457A)
        case .car: return Color(hex: 0x3A8F5C)
        case .library: return Color(hex: 0x3FA7D6)
        case .downloads: return Color(hex: 0x4CAF7D)
        case .podcasts: return Color(hex: 0x9C6ADE)
        case .sync: return Color(hex: 0x2BA3A0)
        case .backup: return Color(hex: 0x4A8FD6)
        case .goals: return Color(hex: 0xE0A030)
        case .about: return Color(hex: 0x8A8F98)
        }
    }
}

/// Settings opened from elsewhere: a page, or the list (nil).
struct SettingsTarget: Hashable, Identifiable {
    var section: SettingsSection?
    var id: String { section?.rawValue ?? "list" }
}

/// One setting the search box can find: its name, other words for it, and its page.
private struct SettingHit: Identifiable {
    let label: String
    let section: SettingsSection
    var words: String = ""
    var id: String { label }
}

private let settingIndex: [SettingHit] = [
    SettingHit(label: "Light or dark", section: .appearance, words: "dark mode night match phone"),
    SettingHit(label: "Theme", section: .appearance, words: "colors colours paper midnight slate graphite forest ocean look"),
    SettingHit(label: "True black", section: .appearance, words: "oled dark battery"),
    SettingHit(label: "Accent color", section: .appearance, words: "colour highlight book cover custom"),
    SettingHit(label: "Ask for a rating when a book ends", section: .playback, words: "finished storygraph goodreads rate"),
    SettingHit(label: "Clip length", section: .playback, words: "clips quote share"),
    SettingHit(label: "Fade out", section: .sleep, words: "sleep volume quieter"),
    SettingHit(label: "Bedtime", section: .sleep, words: "sleep night automatic evening"),
    SettingHit(label: "Flip to pause", section: .sleep, words: "face down turn over"),
    SettingHit(label: "Equalizer", section: .sound, words: "eq bass treble spoken clarity"),
    SettingHit(label: "Trim silence", section: .sound, words: "skip silence pauses gaps smart speed"),
    SettingHit(label: "Voice boost", section: .sound, words: "louder narration volume loud"),
    SettingHit(label: "Car mode", section: .car, words: "driving car bluetooth big buttons"),
    SettingHit(label: "Skip back", section: .playback, words: "rewind seconds jump"),
    SettingHit(label: "Skip forward", section: .playback, words: "seconds jump ahead"),
    SettingHit(label: "Rewind after a pause", section: .playback, words: "smart rewind resume"),
    SettingHit(label: "Play the next book in a series", section: .playback, words: "series autoplay auto-play"),
    SettingHit(label: "Time left counts the whole book", section: .playback, words: "chapter remaining"),
    SettingHit(label: "Library folders", section: .library, words: "add folder files icloud rescan scan"),
    SettingHit(label: "Google Drive", section: .library, words: "drive cloud stream"),
    SettingHit(label: "Downloads", section: .downloads, words: "downloaded offline space storage episodes"),
    SettingHit(label: "Episodes kept per show", section: .podcasts, words: "auto download keep"),
    SettingHit(label: "Check for new episodes", section: .podcasts, words: "refresh"),
    SettingHit(label: "Sync with Google Drive", section: .sync, words: "devices fold android other device"),
    SettingHit(label: "Save a backup", section: .backup, words: "backup export"),
    SettingHit(label: "Restore from a backup", section: .backup, words: "restore import"),
    SettingHit(label: "Daily listening goal", section: .goals, words: "minutes streak"),
    SettingHit(label: "Books this year", section: .goals, words: "yearly goal"),
    SettingHit(label: "Tips and gestures", section: .about, words: "good to know pocket lock headphones scrubbing"),
    SettingHit(label: "Version", section: .about, words: "build update"),
]

struct SettingsView: View {
    /// Show one page (from the profile page), or the list of pages (nil).
    var section: SettingsSection?

    var body: some View {
        if let section { SettingsPage(section: section) } else { SettingsList() }
    }
}

/// One topic's settings on their own page.
struct SettingsPage: View {
    let section: SettingsSection
    /// Off beside the list (iPad), where the bar keeps saying "Settings".
    var showsTitle = true

    var body: some View {
        Form {
            switch section {
            case .appearance: AppearanceSection()
            case .playback: PlaybackSection()
            case .sleep: SleepSection()
            case .sound: SoundSection()
            case .car: CarSection()
            case .library: LibrarySection()
            case .downloads: DownloadsSection()
            case .podcasts: PodcastSection()
            case .sync: SyncSection()
            case .backup: BackupSection()
            case .goals: GoalsSection()
            case .about: AboutSection()
            }
        }
        .navigationTitle(showsTitle ? section.title : "Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The list of pages, each with what's set there, and a search box. With room (iPad), the list on
/// the left and the open page on the right.
private struct SettingsList: View {
    @Environment(AppModel.self) private var app
    @Query(sort: \LibraryFolder.name) private var folders: [LibraryFolder]
    @State private var q = ""
    @State private var selected: SettingsSection = .appearance

    var body: some View {
        GeometryReader { geo in
            if geo.size.width >= 620 {
                HStack(spacing: 0) {
                    list(wide: true).frame(width: min(360, geo.size.width * 0.42))
                    Divider()
                    SettingsPage(section: selected, showsTitle: false).id(selected)
                }
            } else {
                list(wide: false)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search settings")
        .onAppear { if let t = app.testSettingsQuery { q = t; app.testSettingsQuery = nil } }
    }

    @ViewBuilder
    private func list(wide: Bool) -> some View {
        let words = q.lowercased().split(separator: " ").map(String.init)
        List {
            if words.isEmpty {
                Section {
                    ForEach(SettingsSection.allCases) { s in
                        row(s, title: s.title, sub: summary(s), wide: wide)
                    }
                }
            } else {
                let hits = settingIndex.filter { h in
                    let text = (h.label + " " + h.words + " " + h.section.title).lowercased()
                    return words.allSatisfy { text.contains($0) }
                }
                if hits.isEmpty {
                    Text("Nothing matches \u{201C}\(q.trimmingCharacters(in: .whitespaces))\u{201D}").foregroundStyle(.secondary)
                }
                ForEach(hits) { h in row(h.section, title: h.label, sub: h.section.title, wide: wide) }
            }
        }
    }

    @ViewBuilder
    private func row(_ s: SettingsSection, title: String, sub: String, wide: Bool) -> some View {
        let label = HStack(spacing: 14) {
            Image(systemName: s.icon).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 30, height: 30).background(s.color, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if !sub.isEmpty { Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
        }
        if wide {
            Button { selected = s } label: {
                HStack { label; Spacer(); Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary) }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .listRowBackground(selected == s && q.isEmpty ? Color.accentColor.opacity(0.15) : nil)
        } else {
            NavigationLink { SettingsPage(section: s) } label: { label }
        }
    }

    private func summary(_ s: SettingsSection) -> String {
        let st = app.settings
        switch s {
        case .appearance:
            let mode = st.appearance == "light" ? "Light" : st.appearance == "dark" ? "Dark" : "Match phone"
            return "\(Themes.byID(st.themeID).name) · \(mode)\(st.trueBlack ? " · true black" : "")"
        case .playback: return "Back \(st.skipBackSec)s · forward \(st.skipForwardSec)s"
        case .sleep:
            let bits = [st.sleepFade ? "fades out" : nil, st.bedtimeOn ? "bedtime" : nil, st.flipToPause ? "flip to pause" : nil].compactMap { $0 }
            return bits.isEmpty ? "Shake to start over" : bits.joined(separator: " · ").capitalizedFirst
        case .sound:
            let bits = [st.eqOn ? SoundPresets.label(st) : nil, st.trimSilence ? "trim silence" : nil, st.voiceBoost ? "voice boost" : nil].compactMap { $0 }
            return bits.isEmpty ? "Equalizer off" : bits.joined(separator: " · ").capitalizedFirst
        case .car: return st.carModeAuto ? "Opens when you connect to your car" : "Open it from the player"
        case .library: return folders.isEmpty ? "No folders yet" : "\(folders.count) folder\(folders.count == 1 ? "" : "s")"
        case .downloads:
            let a = app.downloadActivity
            return a.count > 0 ? "Downloading \(a.count)" : "Episodes and Google Drive books"
        case .podcasts: return "Keep up to \(st.autoDownloadCount) per show"
        case .sync:
            if !app.sync.isOn { return "Off" }
            if let at = app.sync.lastSyncAt { return "On · synced \(at.formatted(.relative(presentation: .named)))" }
            return "On"
        case .backup:
            if let at = st.lastBackupAt ?? st.lastAutoBackupAt { return "Last backup \(at.formatted(.relative(presentation: .named)))" }
            return "A backup each day in Files"
        case .goals: return "\(st.dailyGoalMin) min a day · \(st.yearlyGoalBooks) books a year"
        case .about: return "Build \(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") · tips and gestures"
        }
    }
}

private struct DownloadsSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Section {
            NavigationLink { DownloadsView() } label: { Label("Downloads", systemImage: "arrow.down.circle") }
        } footer: {
            Text("What's downloading, and the episodes and Google Drive books saved on this \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"). Remove them there to free up space.")
        }
    }
}

private struct GoalsSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Section {
            Picker("Daily listening goal", selection: Binding(get: { app.settings.dailyGoalMin }, set: { app.settings.dailyGoalMin = $0; app.sync.request("goal") })) {
                ForEach([15, 30, 45, 60, 90], id: \.self) { Text("\($0) min").tag($0) }
            }
            Picker("Books this year", selection: Binding(get: { app.settings.yearlyGoalBooks }, set: { app.settings.yearlyGoalBooks = $0; app.sync.request("goal") })) {
                ForEach([12, 24, 36, 52, 100], id: \.self) { Text("\($0)").tag($0) }
            }
        }
    }
}

private struct AppearanceSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var scheme
    @State private var hue: Double = 30

    var body: some View {
        @Bindable var s = app.settings
        Section {
            Picker("Light or dark", selection: $s.appearance) {
                Text("Match phone").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
        } footer: {
            Text("Match phone follows your iPhone's dark mode, including its night schedule. Every theme has a light and a dark version.")
        }
        Section("Theme") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 12)], spacing: 12) {
                ForEach(Themes.all) { t in
                    ThemeTile(theme: t, selected: s.themeID == t.id, trueBlack: s.trueBlack)
                        .onTapGesture {
                            s.themeID = t.id
                            ThemeColors.apply(theme: t.id, trueBlack: s.trueBlack)
                        }
                }
            }
            .padding(.vertical, 6)
            Toggle("True black in dark mode", isOn: Binding(get: { s.trueBlack }, set: {
                s.trueBlack = $0
                ThemeColors.apply(theme: s.themeID, trueBlack: $0)
            }))
        }
        Section {
            Picker("Accent color", selection: $s.accentMode) {
                Text("Match the book").tag("book")
                Text("The theme's").tag("theme")
                Text("Pick one").tag("fixed")
            }
            if s.accentMode == "fixed" {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 40), spacing: 8)], spacing: 10) {
                    ForEach(Themes.swatches, id: \.1) { name, c in
                        Circle().fill(Color(hex: c)).frame(width: 32, height: 32)
                            .overlay(Circle().stroke(.primary, lineWidth: s.accentColor == c ? 2 : 0).padding(-3))
                            .onTapGesture { s.accentColor = c }
                            .accessibilityLabel(name)
                            .accessibilityAddTraits(s.accentColor == c ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Your own").font(.subheadline)
                    Slider(value: $hue, in: 0...359, step: 1) { Text("Hue") } onEditingChanged: { editing in
                        if !editing { s.accentColor = Themes.fromHue(hue) }
                    }
                    .tint(Color(hex: Themes.fromHue(hue)))
                    .background(
                        LinearGradient(colors: stride(from: 0.0, through: 360, by: 30).map { Color(hex: Themes.fromHue($0)) }, startPoint: .leading, endPoint: .trailing)
                            .frame(height: 6).clipShape(Capsule())
                    )
                }
            }
        } header: {
            Text("Accent")
        } footer: {
            Text("Match the book takes the color of the cover that's playing.")
        }
    }
}

/// A theme's preview: its dark and light screens side by side, with the accent.
private struct ThemeTile: View {
    let theme: ThemeDef
    let selected: Bool
    let trueBlack: Bool

    var body: some View {
        let d = Themes.palette(theme: theme.id, trueBlack: trueBlack, dark: true)
        let l = Themes.palette(theme: theme.id, trueBlack: false, dark: false)
        VStack(spacing: 6) {
            HStack(spacing: 0) {
                half(bg: d.map { Color($0.background) } ?? .black, card: d.map { Color($0.surface) } ?? Color(white: 0.11), accent: Color(hex: theme.darkAccent))
                half(bg: l.map { Color($0.background) } ?? Color(white: 0.95), card: l.map { Color($0.surface) } ?? .white, accent: Color(hex: theme.lightAccent))
            }
            .frame(height: 64)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(selected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: selected ? 3 : 1))
            Text(theme.name).font(.caption.weight(selected ? .semibold : .regular))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(theme.name) theme")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private func half(bg: Color, card: Color, accent: Color) -> some View {
        ZStack(alignment: .topLeading) {
            bg
            VStack(alignment: .leading, spacing: 5) {
                RoundedRectangle(cornerRadius: 4).fill(card).frame(height: 18)
                HStack(spacing: 4) {
                    Circle().fill(accent).frame(width: 10, height: 10)
                    RoundedRectangle(cornerRadius: 2).fill(card).frame(height: 6)
                }
            }
            .padding(7)
        }
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
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
        Section {
            Toggle("Ask for a rating when a book ends", isOn: $s.rateOnFinish)
        } footer: {
            Text("Rate it, then log it on StoryGraph or Goodreads in one tap.")
        }
        Section {
            Picker("Clip length", selection: $s.clipLengthSec) {
                ForEach([15, 30, 45, 60, 90, 120], id: \.self) { Text("\($0) seconds").tag($0) }
            }
        } footer: {
            Text("A clip keeps the last stretch you heard, counting back from when you tap Clip. You can change each one before saving it.")
        }
    }
}

private struct SleepSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Section {
            Toggle("Fade out", isOn: $s.sleepFade)
        } footer: {
            Text("The volume goes down over the last ten seconds instead of stopping all at once. Shake your phone any time to start the timer over.")
        }
        Section {
            Toggle("Bedtime", isOn: $s.bedtimeOn)
            if s.bedtimeOn {
                DatePicker("From", selection: time($s.bedtimeFromMin), displayedComponents: .hourAndMinute)
                DatePicker("Until", selection: time($s.bedtimeToMin), displayedComponents: .hourAndMinute)
                Picker("Sleep timer", selection: $s.bedtimeTimerMin) {
                    ForEach([10, 15, 20, 30, 45, 60, 90], id: \.self) { Text("\($0) min").tag($0) }
                }
            }
        } footer: {
            Text("Pressing play between these times starts the sleep timer by itself. Turning the timer off leaves it off for that night.")
        }
        Section {
            Toggle("Flip to pause", isOn: Binding(get: { s.flipToPause }, set: { s.flipToPause = $0; app.player.updateFlip() }))
        } footer: {
            Text("Put your phone face down to pause; turn it back over within half an hour to keep listening.")
        }
    }

    private func time(_ minutes: Binding<Int>) -> Binding<Date> {
        Binding(get: {
            Calendar.current.date(bySettingHour: minutes.wrappedValue / 60, minute: minutes.wrappedValue % 60, second: 0, of: Date()) ?? Date()
        }, set: { d in
            let c = Calendar.current.dateComponents([.hour, .minute], from: d)
            minutes.wrappedValue = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        })
    }
}

private struct SoundSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        Section {
            Toggle("Equalizer", isOn: changed($s.eqOn))
            if s.eqOn {
                Picker("Sound", selection: changed($s.eqPreset)) {
                    ForEach(SoundPresets.presets) { Text($0.label).tag($0.key) }
                    Text("Custom").tag("custom")
                }
                EQBands()
            }
        } footer: {
            Text("Spoken word lifts voices and trims rumble. Drag a band to make your own.")
        }
        Section {
            Toggle("Trim silence", isOn: changed($s.trimSilence))
            Toggle("Voice boost", isOn: changed($s.voiceBoost))
        } footer: {
            let saved = UserDefaults.standard.double(forKey: "trimSavedMs")
            Text("Trim silence hurries through the quiet between sentences\(saved > 60_000 ? " (it has saved you \(Fmt.duration(Int64(saved))) so far)" : ""). Voice boost makes quiet narrators louder without distorting. A book can have its own: open its Sound from the player or the book's page.")
        }
    }

    private func changed<T>(_ b: Binding<T>) -> Binding<T> {
        Binding(get: { b.wrappedValue }, set: { b.wrappedValue = $0; app.player.soundChanged() })
    }
}

/// The five equalizer bands, as sliders. Moving one makes it your own (Custom).
private struct EQBands: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let s = app.settings
        let gains = SoundPresets.gains(s)
        HStack(alignment: .bottom, spacing: 0) {
            ForEach(0..<5, id: \.self) { i in
                VStack(spacing: 6) {
                    Text(gains[i] > 0 ? "+\(gains[i])" : "\(gains[i])").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    Slider(value: Binding(get: { Double(gains[i]) }, set: { v in
                        var g = SoundPresets.gains(s)
                        g[i] = Int(v.rounded())
                        s.eqCustom = g
                        s.eqPreset = "custom"
                        app.player.soundChanged()
                    }), in: Double(SoundPresets.minDB)...Double(SoundPresets.maxDB), step: 1)
                    .frame(width: 130)
                    .rotationEffect(.degrees(-90))
                    .frame(width: 44, height: 130)
                    .accessibilityLabel(SoundPresets.bandLabels[i])
                    Text(SoundPresets.bandLabels[i]).font(.caption2).foregroundStyle(.secondary).lineLimit(1).fixedSize()
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 6)
    }
}

private struct CarSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var s = app.settings
        let out = CarDetect.currentOutput()
        Section {
            Button {
                app.profileOpen = false
                Task {
                    try? await Task.sleep(for: .milliseconds(600))
                    app.carModeOpen = true
                }
            } label: { Label("Open car mode", systemImage: "car.fill") }
            Toggle("Open when you connect to your car", isOn: $s.carModeAuto)
        } footer: {
            Text("Car mode is the player made simple for a glance: big back, play and forward buttons, and a bookmark. Ember opens it when your iPhone connects to CarPlay or to a Bluetooth device you've marked as your car.")
        }
        Section("Your car") {
            if let out, out.bluetooth {
                Toggle(isOn: Binding(get: { s.carDevices.contains(out.uid) }, set: { on in
                    if on { if !s.carDevices.contains(out.uid) { s.carDevices.append(out.uid) } } else { s.carDevices.removeAll { $0 == out.uid } }
                })) {
                    VStack(alignment: .leading) {
                        Text("\(out.name) is my car")
                        Text("Connected now").font(.caption).foregroundStyle(.secondary)
                    }
                }
            } else {
                Text("Connect to your car's Bluetooth, then come back here to mark it.").foregroundStyle(.secondary)
            }
            if !s.carDevices.isEmpty {
                Button("Forget my cars (\(s.carDevices.count))", role: .destructive) { s.carDevices = [] }
            }
        }
    }
}

private struct LibrarySection: View {
    @Environment(AppModel.self) private var app
    @Query(sort: \LibraryFolder.name) private var folders: [LibraryFolder]
    @State private var picking = false
    @State private var pickingDrive = false

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
            Button {
                Task {
                    guard let drive = app.library.drive else { return }
                    var ok = drive.connected
                    if !ok { ok = await drive.connect() }
                    if ok { pickingDrive = true }
                }
            } label: { Label("Add a Google Drive folder", systemImage: "externaldrive.badge.plus") }
            if let e = app.google.lastError { Text(e).font(.caption).foregroundStyle(.red) }
            if !folders.isEmpty {
                Button { Task { await app.library.scanAll() } } label: { Label("Look for new books", systemImage: "arrow.clockwise") }
            }
        } header: {
            Text("Library folders")
        } footer: {
            Text("Folders from the Files app: On My iPhone, iCloud Drive or other places. Books in iCloud that aren't downloaded yet are fetched and added a little later. Google Drive folders stream (read-only), and any book can be downloaded from its page. Removing a folder only removes its books from Ember, never the files.")
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { r in
            if case .success(let url) = r { Task { await app.library.addFolder(url) } }
        }
        .sheet(isPresented: $pickingDrive) {
            NavigationStack { DriveFolderPicker(folderID: nil, name: "Google Drive") { pickingDrive = false } }
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
            Text("Ember also saves a backup each day in the Files app (On My iPhone › Ember › Backups) and keeps the last 14. The same backup file as Ember on Android: a backup from your Fold restores here, and the other way round. Restoring adds to your library rather than replacing it.")
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
        Section {
            LabeledContent("Version", value: "Ember for \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone") \(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1")")
        }
        Section("Tips and gestures") {
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
        .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Books, authors, shows")
        .overlay { if q.isEmpty { ContentUnavailableView("Search everything", systemImage: "magnifyingglass", description: Text("Books, authors, narrators, series, shows and episodes.")) } }
        .navigationDestination(for: BookRoute.self) { BookView(bookID: $0.id) }
        .navigationDestination(for: ShowRoute.self) { ShowView(podcastID: $0.id) }
    }

    private func fold(_ s: String) -> String { s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).trimmingCharacters(in: .whitespaces) }
}

/// Browse Google Drive folders and pick the one your books are in.
struct DriveFolderPicker: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    /// nil: the top (My Drive and Shared with me).
    let folderID: String?
    let name: String
    /// Closes the whole picker.
    let done: () -> Void
    @State private var folders: [DriveFiles.File]?
    @State private var failed = false

    var body: some View {
        List {
            if folderID == nil {
                NavigationLink { DriveFolderPicker(folderID: "root", name: "My Drive", done: done) } label: { Label("My Drive", systemImage: "externaldrive") }
                NavigationLink { DriveFolderPicker(folderID: "shared", name: "Shared with me", done: done) } label: { Label("Shared with me", systemImage: "person.2") }
            } else if let folders {
                if folders.isEmpty { Text("No folders in here.").foregroundStyle(.secondary) }
                ForEach(folders) { f in
                    NavigationLink { DriveFolderPicker(folderID: f.id, name: f.name, done: done) } label: { Label(f.name, systemImage: "folder") }
                }
            } else if failed {
                Text("Couldn't reach Google Drive. Check your connection and try again.").foregroundStyle(.secondary)
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if folderID == nil {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            } else if folderID != "shared" {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use this folder") {
                        guard let id = folderID else { return }
                        Task { await app.library.addDriveFolder(id: id, name: name) }
                        done()
                    }
                    .buttonStyle(.glassProminent)
                }
            }
        }
        .task {
            guard let folderID, folders == nil, let drive = app.library.drive else { return }
            do { folders = try await drive.list(folderID, foldersOnly: true) } catch { failed = true }
        }
    }
}
