import Foundation
import AVFoundation

/// Is the phone connected to the car? CarPlay says so; a car's Bluetooth looks like any headphones,
/// so you mark it once (Settings › Car mode).
enum CarDetect {
    struct Output {
        let name: String
        let uid: String
        let bluetooth: Bool
        let carPlay: Bool
    }

    static func currentOutput() -> Output? {
        guard let o = AVAudioSession.sharedInstance().currentRoute.outputs.first else { return nil }
        let bt: Set<AVAudioSession.Port> = [.bluetoothA2DP, .bluetoothHFP, .bluetoothLE]
        return Output(name: o.portName, uid: o.uid, bluetooth: bt.contains(o.portType), carPlay: o.portType == .carAudio)
    }

    static func inCar(_ settings: Settings) -> Bool {
        guard let o = currentOutput() else { return false }
        return o.carPlay || (o.bluetooth && settings.carDevices.contains(o.uid))
    }
}
