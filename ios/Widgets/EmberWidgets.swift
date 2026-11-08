import WidgetKit
import SwiftUI

struct SnapEntry: TimelineEntry {
    let date: Date
    let snap: WidgetSnapshot
    let cover: UIImage?
}

struct SnapProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapEntry { SnapEntry(date: Date(), snap: .demo, cover: nil) }

    func getSnapshot(in context: Context, completion: @escaping (SnapEntry) -> Void) {
        let s = SharedStore.read()
        completion(SnapEntry(date: Date(), snap: s ?? .demo, cover: s == nil ? nil : SharedStore.cover()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapEntry>) -> Void) {
        var s = SharedStore.read() ?? WidgetSnapshot()
        // A new day starts at zero.
        if !Calendar.current.isDateInToday(s.updatedAt) { s.todayMs = 0 }
        let e = SnapEntry(date: Date(), snap: s, cover: SharedStore.cover())
        let midnight = Calendar.current.startOfDay(for: Date().addingTimeInterval(86_400))
        completion(Timeline(entries: [e], policy: .after(min(Date().addingTimeInterval(30 * 60), midnight))))
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NowPlaying", provider: SnapProvider()) { e in
            NowPlayingEntryView(entry: e)
        }
        .configurationDisplayName("Now playing")
        .description("Your book, time left, and play or pause.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular])
    }
}

struct NowPlayingEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapEntry

    var body: some View {
        switch family {
        case .accessoryRectangular:
            LockScreenWidgetView(snap: entry.snap, family: "rectangular").containerBackground(.clear, for: .widget)
        case .accessoryCircular:
            LockScreenWidgetView(snap: entry.snap, family: "circular").containerBackground(.clear, for: .widget)
        default:
            NowPlayingWidgetView(snap: entry.snap, image: entry.cover, family: family == .systemSmall ? "small" : "medium")
                .containerBackground(for: .widget) { NowPlayingWidgetView.background(entry.snap) }
        }
    }
}

struct ListeningWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Listening", provider: SnapProvider()) { e in
            ListeningWidgetView(snap: e.snap)
                .containerBackground(for: .widget) { Color(red: 0.07, green: 0.06, blue: 0.07) }
        }
        .configurationDisplayName("Daily goal")
        .description("Today's listening and your streak.")
        .supportedFamilies([.systemSmall])
    }
}

@main
struct EmberWidgetBundle: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
        ListeningWidget()
    }
}
