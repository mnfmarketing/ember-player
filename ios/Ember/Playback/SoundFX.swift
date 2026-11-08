import Foundation
import AVFoundation

/// Equalizer presets on five bands, the same as Ember on Android.
enum SoundPresets {
    static let bandLabels = ["60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"]
    static let minDB = -12
    static let maxDB = 12

    struct Preset: Identifiable {
        let key: String
        let label: String
        let gains: [Int]
        var id: String { key }
    }

    static let presets = [
        Preset(key: "spoken", label: "Spoken word", gains: [-6, -2, 1, 4, 1]),
        Preset(key: "clarity", label: "Clarity", gains: [-3, -1, 0, 3, 4]),
        Preset(key: "warm", label: "Warm", gains: [3, 2, 0, -1, -2]),
        Preset(key: "night", label: "Late night", gains: [-4, -1, 1, 2, -2]),
        Preset(key: "bass", label: "Bass boost", gains: [6, 3, 0, 0, 0]),
        Preset(key: "treble", label: "Treble boost", gains: [0, 0, 0, 3, 6]),
        Preset(key: "flat", label: "Flat", gains: [0, 0, 0, 0, 0]),
    ]

    static func gains(_ s: Settings) -> [Int] {
        if s.eqPreset == "custom", s.eqCustom.count == 5 { return s.eqCustom }
        return presets.first { $0.key == s.eqPreset }?.gains ?? presets[0].gains
    }

    static func label(_ s: Settings) -> String {
        s.eqPreset == "custom" ? "Custom" : (presets.first { $0.key == s.eqPreset }?.label ?? "Spoken word")
    }
}

/// The sound settings in force for what's playing, and the tap that applies them.
@MainActor
final class SoundFX {
    let fx: UnsafeMutablePointer<EmberFX> = EmberFXCreate()
    private(set) var anyOn = false
    private(set) var trimming = false
    private var lastGains: [Int] = []

    func set(eq: Bool, gains: [Int], boost: Bool, trim: Bool) {
        if gains != lastGains {
            lastGains = gains
            let g = gains.map(Float.init) + [0, 0, 0, 0, 0]
            fx.pointee.gains = (g[0], g[1], g[2], g[3], g[4])
            fx.pointee.version &+= 1
        }
        fx.pointee.eq = eq ? 1 : 0
        fx.pointee.boost = boost ? 1 : 0
        fx.pointee.detect = trim ? 1 : 0
        trimming = trim
        anyOn = eq || boost || trim
    }

    var silent: Bool { fx.pointee.silent != 0 }

    /// Puts the tap on an item's audio track (once its tracks are known).
    func attach(_ item: AVPlayerItem) {
        let asset = item.asset
        let fx = fx
        Task { @MainActor in
            guard let tracks = try? await asset.loadTracks(withMediaType: .audio), let track = tracks.first else { return }
            guard let tap = EmberTapCreate(fx) else { return }
            let p = AVMutableAudioMixInputParameters(track: track)
            p.audioTapProcessor = tap
            let mix = AVMutableAudioMix()
            mix.inputParameters = [p]
            item.audioMix = mix
        }
    }
}
