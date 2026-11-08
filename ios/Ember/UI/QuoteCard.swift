import SwiftUI
import Photos

/// What a quote card shows: the quote, and the book it's from.
struct CardSource: Hashable, Identifiable {
    var title: String
    var author: String
    var detail: String
    var coverFile: String?
    var color: Int?
    var quote: String
    var id: String { title + detail + quote }

    init(clip: Clip) {
        title = Names.shortTitle(clip.title)
        author = clip.subtitle
        detail = [clip.chapterTitle.isEmpty ? nil : clip.chapterTitle, Fmt.clock(clip.startMs)].compactMap { $0 }.joined(separator: " · ")
        coverFile = clip.coverFile
        color = clip.color
        quote = clip.note
    }

    init(bookmark m: Bookmark, book: Book?) {
        title = book?.displayTitle ?? ""
        author = book?.author ?? ""
        detail = [m.chapterTitle.isEmpty ? nil : m.chapterTitle, Fmt.clock(m.positionMs)].compactMap { $0 }.joined(separator: " · ")
        coverFile = book?.coverFile
        color = book?.coverColor
        quote = m.note
    }
}

enum CardFormat: String, CaseIterable, Identifiable {
    case post = "Post 4:5", square = "Square", story = "Story"
    var id: String { rawValue }
    var size: CGSize {
        switch self {
        case .post: return CGSize(width: 1080, height: 1350)
        case .square: return CGSize(width: 1080, height: 1080)
        case .story: return CGSize(width: 1080, height: 1920)
        }
    }
}

enum CardStyle: String, CaseIterable, Identifiable {
    case cover = "Cover", dark = "Dark", light = "Light"
    var id: String { rawValue }
}

/// The card itself, drawn at full size (1080 pixels wide, sized for Instagram).
struct QuoteCardArt: View {
    let source: CardSource
    let quote: String
    let format: CardFormat
    let style: CardStyle

    var body: some View {
        let size = format.size
        let light = style == .light
        let ink: Color = light ? Color(hex: 0x1E1A17) : .white
        let accent = Color(hex: source.color.map(CardColors.tune) ?? 0xD9774A)
        let pop = light ? accent : Color(hex: CardColors.lighten(source.color.map(CardColors.tune) ?? 0xD9774A))
        let thumb: CGFloat = format == .story ? 260 : 210
        let cover = Covers.image(source.coverFile)
        VStack(alignment: .leading, spacing: 0) {
                if !quote.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("\u{201C}").font(.system(size: 240, design: .serif)).foregroundStyle(pop).frame(height: 150, alignment: .top).padding(.leading, -10)
                    Text(quote.trimmingCharacters(in: .whitespacesAndNewlines))
                        .font(.system(size: format == .story ? 84 : 76, design: .serif))
                        .lineSpacing(14)
                        .foregroundStyle(ink)
                        .minimumScaleFactor(0.42)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: format == .post ? .topLeading : .leading)
                        .padding(.bottom, 60)
                } else if cover != nil {
                    CoverView(file: source.coverFile, title: source.title, color: source.color, corner: 18)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(.vertical, 40)
                } else {
                    Spacer()
                }
                HStack(spacing: 40) {
                    CoverView(file: source.coverFile, title: source.title, color: source.color, corner: 16).frame(width: thumb, height: thumb)
                        .shadow(color: .black.opacity(0.3), radius: 16, y: 8)
                    VStack(alignment: .leading, spacing: 10) {
                        Text(source.title).font(.system(size: 46, weight: .semibold)).foregroundStyle(ink).lineLimit(2)
                        if !source.author.isEmpty { Text(source.author).font(.system(size: 36)).foregroundStyle(ink.opacity(0.75)).lineLimit(1) }
                        if !source.detail.isEmpty { Text(source.detail).font(.system(size: 30, weight: .semibold)).foregroundStyle(pop).lineLimit(1).padding(.top, 4) }
                    }
                    Spacer(minLength: 0)
                }
                HStack(spacing: 14) {
                    Spacer()
                    Circle().fill(accent).frame(width: 16, height: 16)
                    Text("EMBER").font(.system(size: 26)).kerning(3).foregroundStyle(ink.opacity(0.55))
                }
                .padding(.top, 30)
        }
        .padding(96)
        .frame(width: size.width, height: size.height)
        .background { background(cover: cover, accent: accent).frame(width: size.width, height: size.height).clipped() }
        .clipped()
        .environment(\.colorScheme, light ? .light : .dark)
    }

    @ViewBuilder
    private func background(cover: UIImage?, accent: Color) -> some View {
        switch style {
        case .cover:
            ZStack {
                Color(hex: 0x141216)
                if let cover {
                    Image(uiImage: cover).resizable().scaledToFill().blur(radius: 70).scaleEffect(1.2)
                } else {
                    RadialGradient(colors: [accent, Color(hex: 0x141216)], center: UnitPoint(x: 0.3, y: 0.2), startRadius: 0, endRadius: 1300)
                }
                LinearGradient(colors: [.black.opacity(0.55), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
            }
            .clipped()
        case .dark:
            ZStack {
                Color(hex: 0x141216)
                RadialGradient(colors: [accent.opacity(0.4), .clear], center: UnitPoint(x: 0.15, y: 0.05), startRadius: 0, endRadius: 1000)
            }
        case .light:
            ZStack(alignment: .top) {
                Color(hex: 0xF6F1EA)
                accent.opacity(0.12).frame(height: 18)
            }
        }
    }
}

enum CardColors {
    /// A cover color made lively enough to be an accent.
    static func tune(_ hex: Int) -> Int {
        var (h, s, b) = Theme.hsb(hex)
        s = min(max(s, 0.45), 0.85)
        b = min(max(b, 0.55), 0.85)
        return Themes.hex(UIColor(hue: h, saturation: s, brightness: b, alpha: 1))
    }

    static func lighten(_ hex: Int) -> Int {
        let r = (hex >> 16) & 0xFF, g = (hex >> 8) & 0xFF, b = hex & 0xFF
        func up(_ v: Int) -> Int { v + Int(Double(255 - v) * 0.35) }
        return (up(r) << 16) | (up(g) << 8) | up(b)
    }
}

enum CardImage {
    @MainActor static func render<V: View>(_ v: V, size: CGSize) -> UIImage? {
        let r = ImageRenderer(content: v.frame(width: size.width, height: size.height))
        r.scale = 1
        r.proposedSize = ProposedViewSize(size)
        return r.uiImage
    }

    /// Saves to the photo library (asks the first time).
    static func save(_ img: UIImage) async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAsset(from: img) }
            return true
        } catch {
            return false
        }
    }
}

/// Make a quote card: edit the words, pick a size and a look, then share or save it.
struct QuoteCardView: View {
    let source: CardSource
    @State private var quote = ""
    @State private var format: CardFormat = .post
    @State private var style: CardStyle = .cover
    @State private var image: UIImage?
    @State private var saved: String?
    @FocusState private var typing: Bool

    var body: some View {
        Form {
            Section {
                Group {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
                    } else {
                        ProgressView().frame(height: 300)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(maxHeight: 440)
                .listRowBackground(Color.clear)
            }
            Section("Quote") {
                TextField("Type the line you want to share", text: $quote, axis: .vertical).lineLimit(2...8).focused($typing)
            }
            Section {
                Picker("Size", selection: $format) { ForEach(CardFormat.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                Picker("Look", selection: $style) { ForEach(CardStyle.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
            }
            if let image {
                Section {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview(source.title, image: Image(uiImage: image))) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        Task { saved = await CardImage.save(image) ? "Saved to Photos" : "Ember can't save to Photos (allow it in Settings)" }
                    } label: { Label("Save to Photos", systemImage: "photo.badge.arrow.down") }
                    if let saved { Text(saved).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
        .navigationTitle("Quote card")
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { typing = false }
            }
        }
        .onAppear { if quote.isEmpty { quote = source.quote } }
        .task(id: "\(quote)|\(format.rawValue)|\(style.rawValue)") {
            try? await Task.sleep(for: .milliseconds(250))
            image = CardImage.render(QuoteCardArt(source: source, quote: quote, format: format, style: style), size: format.size)
            saved = nil
        }
    }
}
