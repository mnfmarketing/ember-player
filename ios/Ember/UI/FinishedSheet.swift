import SwiftUI

/// You finished a book: rate it, then log it on StoryGraph or Goodreads in one tap.
struct FinishedSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var scheme
    let bookID: String

    var body: some View {
        if let b = app.library.book(bookID) {
            let _ = app.library.version
            VStack(spacing: 0) {
                CoverView(file: b.coverFile, title: b.displayTitle, color: b.coverColor, corner: 12)
                    .frame(width: 120, height: 120)
                    .shadow(color: .black.opacity(0.25), radius: 14, y: 6)
                    .padding(.top, 28)
                Text("You finished it").font(.subheadline.weight(.semibold)).foregroundStyle(.tint).padding(.top, 14)
                Text(b.displayTitle).font(.title2.weight(.bold)).multilineTextAlignment(.center).lineLimit(2).padding(.horizontal, 24)
                Text("How was it?").foregroundStyle(.secondary).padding(.top, 16)
                HStack(spacing: 8) {
                    ForEach(1...5, id: \.self) { n in
                        Button {
                            b.rating = b.rating == n ? 0 : n
                            app.library.changed()
                            app.sync.request("rating")
                        } label: {
                            Image(systemName: n <= b.rating ? "star.fill" : "star").font(.system(size: 34))
                                .foregroundStyle(n <= b.rating ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(n) star\(n == 1 ? "" : "s")")
                    }
                }
                .padding(.top, 6)
                Text("Log it where you track your reading").font(.subheadline).foregroundStyle(.secondary).padding(.top, 20)
                HStack(spacing: 12) {
                    site("StoryGraph", ReadingSites.storyGraph(b))
                    site("Goodreads", ReadingSites.goodreads(b))
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
                HStack {
                    Button("Don't ask again") {
                        app.settings.rateOnFinish = false
                        dismiss()
                    }
                    Spacer()
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
                .padding(.horizontal, 24)
                .padding(.top, 18)
                Spacer(minLength: 8)
            }
            .tint(Theme.accent(settings: app.settings, bookColor: b.coverColor, dark: scheme == .dark))
        }
    }

    private func site(_ name: String, _ url: URL) -> some View {
        Button { openURL(url) } label: {
            Label(name, systemImage: "arrow.up.right").frame(maxWidth: .infinity, minHeight: 34)
        }
        .buttonStyle(.bordered)
    }
}

enum ReadingSites {
    private static func q(_ b: Book) -> String {
        let author = b.author.split(separator: ",").first.map(String.init) ?? ""
        return [BookInfo.cleanTitle(b.title), author].filter { !$0.isEmpty }.joined(separator: " ")
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
    }

    static func storyGraph(_ b: Book) -> URL { URL(string: "https://app.thestorygraph.com/browse?search_term=\(q(b))")! }
    static func goodreads(_ b: Book) -> URL { URL(string: "https://www.goodreads.com/search?q=\(q(b))")! }
}

struct FinishedItem: Identifiable {
    let id: String
}
