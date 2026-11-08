import Foundation
import CoreMotion

/// Flip to pause: the phone put face down pauses; turned back up, it plays on.
@MainActor
final class FlipDetector {
    private let motion = CMMotionManager()
    var onDown: (() -> Void)?
    var onUp: (() -> Void)?
    private var downSince: Date?
    private var fresh = true
    private(set) var isDown = false

    var running: Bool { motion.isDeviceMotionActive }

    func start() {
        guard motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
        fresh = true
        downSince = nil
        motion.deviceMotionUpdateInterval = 0.2
        motion.startDeviceMotionUpdates(to: .main) { [weak self] m, _ in
            guard let z = m?.gravity.z else { return }
            MainActor.assumeIsolated { self?.update(z) }
        }
    }

    func stop() {
        if motion.isDeviceMotionActive { motion.stopDeviceMotionUpdates() }
        downSince = nil
        isDown = false
    }

    private func update(_ z: Double) {
        // Already face down when it started (play pressed on headphones): that isn't a flip.
        if fresh {
            fresh = false
            if z > 0.85 {
                isDown = true
                return
            }
        }
        if z > 0.85 {
            if downSince == nil { downSince = Date() }
            if !isDown, let s = downSince, Date().timeIntervalSince(s) >= 1 {
                isDown = true
                onDown?()
            }
        } else {
            downSince = nil
            if isDown, z < 0.3 {
                isDown = false
                onUp?()
            }
        }
    }
}
