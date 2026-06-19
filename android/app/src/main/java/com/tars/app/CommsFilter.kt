package com.tars.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The "TARS over the spacecraft intercom" sound: a radio/comms effect applied to
 * the synthesized voice. It band-limits the audio to a walkie-talkie range and
 * adds a touch of saturation and hiss — the slightly raspy, not-quite-clean
 * quality of a voice heard over ship comms.
 *
 *   band-pass (~300–3200 Hz)  ->  soft saturation (rasp)  ->  faint static
 */
object CommsFilter {

    @Volatile var enabled = true
    // Live, tunable parameters (set from VoiceSettings).
    @Volatile var mix = 1.0f        // dry/wet: 0 = natural voice, 1 = full intercom
    @Volatile var drive = 2.2f      // saturation amount (rasp)
    @Volatile var hiss = 0.004f     // faint static level
    @Volatile var lowCut = 300.0    // band-pass low edge (Hz)
    @Volatile var highCut = 3200.0  // band-pass high edge (Hz)

    fun apply(samples: FloatArray, sampleRate: Int): FloatArray {
        if (!enabled || mix <= 0f || samples.isEmpty()) return samples
        val fs = sampleRate.toDouble()
        val hp = Biquad.highPass(lowCut, fs, 0.707)
        val lp = Biquad.lowPass(highCut, fs, 0.707)
        val norm = tanh(drive.toDouble()).toFloat()
        var seed = 0x2545F491
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val dry = samples[i]
            var x = lp.process(hp.process(dry))
            // soft saturation -> gritty radio rasp
            x = tanh((drive * x).toDouble()).toFloat() / norm
            // faint comms hiss
            seed = seed * 1103515245 + 12345
            x += (((seed ushr 16) and 0x7fff) / 32768f - 0.5f) * hiss
            x *= 0.9f
            // blend so a low Grit keeps the natural voice intact
            val y = dry * (1f - mix) + x * mix
            out[i] = if (y > 1f) 1f else if (y < -1f) -1f else y
        }
        return out
    }
}

/** Minimal RBJ biquad (direct form I). */
private class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        fun highPass(f0: Double, fs: Double, q: Double): Biquad {
            val w0 = 2.0 * Math.PI * f0 / fs
            val c = cos(w0); val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                (((1 + c) / 2) / a0).toFloat(),
                ((-(1 + c)) / a0).toFloat(),
                (((1 + c) / 2) / a0).toFloat(),
                ((-2 * c) / a0).toFloat(),
                ((1 - alpha) / a0).toFloat(),
            )
        }

        fun lowPass(f0: Double, fs: Double, q: Double): Biquad {
            val w0 = 2.0 * Math.PI * f0 / fs
            val c = cos(w0); val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                (((1 - c) / 2) / a0).toFloat(),
                ((1 - c) / a0).toFloat(),
                (((1 - c) / 2) / a0).toFloat(),
                ((-2 * c) / a0).toFloat(),
                ((1 - alpha) / a0).toFloat(),
            )
        }
    }
}
