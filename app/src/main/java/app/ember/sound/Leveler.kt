package app.ember.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Evens out loudness between books (and within them): a slow automatic gain that brings
 * speech toward one level, between -6 dB and +12 dB, with a limiter so nothing clips.
 * It ignores pauses, so silence never gets pumped up. Turned on and off while playing.
 */
class Leveler : BaseAudioProcessor() {
    @Volatile var enabled = false

    private var channels = 1
    private var rate = 44_100
    private var gain = 1f
    private var level = TARGET // smoothed speech level (RMS, 0..1)
    private var blockSum = 0.0
    private var blockCount = 0
    private var blockSize = 2205
    private var levelCoef = 0.0
    private var gainCoef = 0f
    private var samples = FloatArray(2)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = input.channelCount
        samples = FloatArray(channels)
        rate = input.sampleRate
        blockSize = maxOf(1, rate / 20) // 50 ms of frames
        levelCoef = exp(-0.05 / LEVEL_SECONDS) // per block
        gainCoef = exp(-1.0 / (GAIN_SECONDS * rate)).toFloat() // per frame
        return input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (!enabled) {
            out.put(src)
            out.flip()
            gain += (1f - gain) * 0.01f
            return
        }
        val frames = remaining / (2 * channels)
        repeat(frames) {
            var sumSq = 0.0
            for (ch in 0 until channels) {
                val v = src.short / 32768f
                samples[ch] = v
                sumSq += (v * v).toDouble()
            }
            blockSum += sumSq / channels
            if (++blockCount >= blockSize) {
                val rms = sqrt(blockSum / blockCount)
                // Only speech moves the estimate; pauses and room tone are left alone.
                if (rms > SILENCE) level = level * levelCoef + rms * (1 - levelCoef)
                blockSum = 0.0
                blockCount = 0
            }
            val want = (TARGET / level.coerceAtLeast(1e-4)).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
            gain = want + (gain - want) * gainCoef
            for (ch in 0 until channels) {
                out.putShort((limit(samples[ch] * gain) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        blockSum = 0.0
        blockCount = 0
    }

    override fun onReset() {
        gain = 1f
        level = TARGET
    }

    companion object {
        /** About -20 dBFS RMS: a comfortable speech level with room for peaks. */
        const val TARGET = 0.1
        private const val SILENCE = 0.006 // about -44 dBFS
        private const val MIN_GAIN = 0.5f // -6 dB
        private const val MAX_GAIN = 4f // +12 dB
        private const val LEVEL_SECONDS = 3.0
        private const val GAIN_SECONDS = 1.5

        /** Soft limiter: untouched below 0.8, then eases toward 1.0 so peaks never clip. */
        fun limit(x: Float): Float {
            val a = kotlin.math.abs(x)
            if (a <= 0.8f) return x
            val over = (a - 0.8f) / 0.2f
            val y = 0.8f + 0.2f * (over / (1f + over))
            return if (x < 0) -y else y
        }
    }
}
