import UIKit

/// Book and show covers, saved as JPEGs in Ember's own storage, with each cover's main color.
enum Covers {

    static var dir: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func url(_ file: String?) -> URL? {
        guard let file, !file.isEmpty else { return nil }
        return dir.appendingPathComponent(file)
    }

    private static let cache = NSCache<NSString, UIImage>()

    static func image(_ file: String?) -> UIImage? {
        guard let file, let url = url(file) else { return nil }
        if let hit = cache.object(forKey: file as NSString) { return hit }
        guard let img = UIImage(contentsOfFile: url.path) else { return nil }
        cache.setObject(img, forKey: file as NSString)
        return img
    }

    /// Saves image data (scaled to at most 1000 px) and returns (file name, main color).
    static func save(id: String, data: Data) -> (String, Int)? {
        guard let img = UIImage(data: data) else { return nil }
        let scaled = img.scaled(maxSide: 1000)
        guard let jpg = scaled.jpegData(compressionQuality: 0.88) else { return nil }
        let name = "\(id.replacingOccurrences(of: "/", with: "_"))-\(Int(Date().timeIntervalSince1970)).jpg"
        do {
            try jpg.write(to: dir.appendingPathComponent(name), options: .atomic)
        } catch {
            return nil
        }
        cache.removeAllObjects()
        return (name, scaled.mainColor())
    }

    static func remove(_ file: String?) {
        guard let url = url(file) else { return }
        try? FileManager.default.removeItem(at: url)
    }
}

extension UIImage {
    func scaled(maxSide: CGFloat) -> UIImage {
        let longest = max(size.width, size.height)
        guard longest > maxSide else { return self }
        let f = maxSide / longest
        let target = CGSize(width: (size.width * f).rounded(), height: (size.height * f).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in draw(in: CGRect(origin: .zero, size: target)) }
    }

    /// A strong color from the image (not the average gray): the most common saturated tone.
    func mainColor() -> Int {
        let side = 24
        guard let cg = self.cgImage else { return 0x3A3A3C }
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(data: &pixels, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 0x3A3A3C }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        var buckets: [Int: (n: Double, r: Double, g: Double, b: Double)] = [:]
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let r = Double(pixels[i]), g = Double(pixels[i + 1]), b = Double(pixels[i + 2])
            let mx = max(r, g, b), mn = min(r, g, b)
            let sat = mx == 0 ? 0 : (mx - mn) / mx
            let weight = 0.25 + sat * (mx / 255)
            let key = (Int(r) / 48) * 100 + (Int(g) / 48) * 10 + Int(b) / 48
            var e = buckets[key] ?? (0, 0, 0, 0)
            e.n += weight
            e.r += r * weight
            e.g += g * weight
            e.b += b * weight
            buckets[key] = e
        }
        guard let best = buckets.values.max(by: { $0.n < $1.n }), best.n > 0 else { return 0x3A3A3C }
        let r = Int(best.r / best.n), g = Int(best.g / best.n), b = Int(best.b / best.n)
        return (r << 16) | (g << 8) | b
    }
}
