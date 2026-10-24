package app.ember.sound

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import app.ember.data.Settings
import kotlin.math.ln

/** Equalizer presets, defined on five bands so they work on any phone. */
object SoundPresets {
    /** Band centers in Hz (Android's standard five-band layout). */
    val bands = listOf(60, 230, 910, 3600, 14000)
    val bandLabels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
    const val MIN_DB = -12
    const val MAX_DB = 12

    data class Preset(val key: String, val label: String, val gains: List<Int>)

    val presets = listOf(
        Preset("spoken", "Spoken word", listOf(-6, -2, 1, 4, 1)),
        Preset("clarity", "Clarity", listOf(-3, -1, 0, 3, 4)),
        Preset("warm", "Warm", listOf(3, 2, 0, -1, -2)),
        Preset("night", "Late night", listOf(-4, -1, 1, 2, -2)),
        Preset("bass", "Bass boost", listOf(6, 3, 0, 0, 0)),
        Preset("treble", "Treble boost", listOf(0, 0, 0, 3, 6)),
        Preset("flat", "Flat", listOf(0, 0, 0, 0, 0)),
    )

    fun gainsFor(s: Settings): List<Int> =
        if (s.eqPreset == "custom") s.eqCustom else presets.firstOrNull { it.key == s.eqPreset }?.gains ?: presets[0].gains

    /** Gain (dB) at any frequency, interpolated on a log scale between the five bands. */
    fun gainAt(freqHz: Double, gains: List<Int>): Double {
        if (freqHz <= bands.first()) return gains.first().toDouble()
        if (freqHz >= bands.last()) return gains.last().toDouble()
        for (i in 0 until bands.size - 1) {
            val a = bands[i].toDouble()
            val b = bands[i + 1].toDouble()
            if (freqHz in a..b) {
                val t = (ln(freqHz) - ln(a)) / (ln(b) - ln(a))
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0.0
    }
}

/** Applies the equalizer and narration boost to the phone's audio output. */
class SoundEngine {
    private var eq: Equalizer? = null
    private var boost: LoudnessEnhancer? = null
    private var sessionId = 0

    fun attach(audioSessionId: Int, settings: Settings) {
        if (audioSessionId == sessionId && eq != null) return
        release()
        sessionId = audioSessionId
        eq = try {
            Equalizer(0, audioSessionId)
        } catch (e: Exception) {
            null
        }
        boost = try {
            LoudnessEnhancer(audioSessionId)
        } catch (e: Exception) {
            null
        }
        apply(settings)
    }

    fun apply(s: Settings) {
        eq?.let { e ->
            try {
                if (s.eqEnabled) {
                    val gains = SoundPresets.gainsFor(s)
                    val range = e.bandLevelRange
                    for (b in 0 until e.numberOfBands) {
                        val hz = e.getCenterFreq(b.toShort()) / 1000.0
                        val mb = (SoundPresets.gainAt(hz, gains) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(b.toShort(), mb.toShort())
                    }
                }
                e.enabled = s.eqEnabled
            } catch (ex: Exception) {
            }
        }
        boost?.let { l ->
            try {
                l.setTargetGain(if (s.narrationBoost) 700 else 0)
                l.enabled = s.narrationBoost
            } catch (ex: Exception) {
            }
        }
    }

    fun release() {
        try {
            eq?.release()
        } catch (e: Exception) {
        }
        try {
            boost?.release()
        } catch (e: Exception) {
        }
        eq = null
        boost = null
    }
}
