package app.chatlens.asr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Decoded audio track: mono, floating-point values from -1 to 1. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSec: Double get() = samples.size / sampleRate.toDouble()
}

object Pcm {
    const val TARGET_RATE = 16_000

    fun shortsToFloat(pcm: ShortArray, length: Int = pcm.size): FloatArray = FloatArray(length) { pcm[it] / 32768f }

    /** Multi-channel to mono (mean). Interleaved samples. */
    fun downmix(interleaved: ShortArray, frames: Int, channels: Int): FloatArray {
        if (channels == 1) return shortsToFloat(interleaved, frames)
        return FloatArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) s += interleaved[f * channels + c]
            s / (32768f * channels)
        }
    }

    /**
     * Sample-rate conversion to 16 kHz with a low pass (windowed sinc, cutoff just under 8 kHz).
     * Input already at 16 kHz is left unchanged.
     */
    fun resampleTo16k(x: FloatArray, rate: Int): FloatArray {
        if (rate == TARGET_RATE || x.isEmpty()) return x
        require(rate > 0)
        val ratio = rate.toDouble() / TARGET_RATE
        val outLen = (x.size / ratio).toInt()
        val out = FloatArray(outLen)
        val cutoff = if (ratio > 1.0) 0.45 / ratio * 1.0 else 0.45 // relative to the input rate, Nyquist = 0.5
        val radius = max(8.0, 8.0 * ratio).toInt()
        for (i in 0 until outLen) {
            val c = i * ratio
            val lo = max(0, (c - radius).toInt() + 1)
            val hi = min(x.size - 1, (c + radius).toInt())
            var acc = 0.0
            var norm = 0.0
            for (j in lo..hi) {
                val t = j - c
                val w = 0.5 * (1 + kotlin.math.cos(PI * t / radius)) // Hann window
                val sinc = if (abs(t) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                val k = w * sinc
                acc += x[j] * k
                norm += k
            }
            out[i] = (if (norm != 0.0) acc / norm else 0.0).toFloat()
        }
        return out
    }
}
