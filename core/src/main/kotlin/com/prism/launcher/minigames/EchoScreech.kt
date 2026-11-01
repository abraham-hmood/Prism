package com.prism.launcher.minigames

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The hunter's scream.
 *
 * ## Why it is synthesized
 *
 * Echo Chamber ships no recordings. This is a voice with too many throats: a glottal saw pushed
 * through three moving formants, an inharmonic shriek riding over it, a sub-bass growl, and a noise
 * rasp that keeps the whole thing wet. It is then distorted, reflected off a short room, and
 * normalized to a peak just under full scale.
 *
 * ## Why it is shared
 *
 * The desktop and the phone must play the same sound. Generating it here also means the scream can
 * be tested (length, loudness, zero-crossing rate) without an audio device.
 */
object EchoScreech {

    const val SAMPLE_RATE = 22_050
    const val SECONDS = 2.75

    /** 16-bit mono, little-endian when the platform puts it on a line. */
    fun samples(sampleRate: Int = SAMPLE_RATE): ShortArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val length = (sampleRate * SECONDS).toInt()
        val wet = DoubleArray(length)
        val combs = Array(3) { DoubleArray(4_096) }
        var phase = 0.0
        var subPhase = 0.0
        var shriekPhase = 0.0
        var lowPhase = 0.0
        var low1 = 0.0
        var band1 = 0.0
        var low2 = 0.0
        var band2 = 0.0
        var low3 = 0.0
        var band3 = 0.0
        var seed = 0x51C7E77
        var previous = 0.0
        var dc = 0.0

        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val p = (t / SECONDS).coerceIn(0.0, 1.0)
            val rise = smoothstep(0.0, 0.19, p)
            val fall = smoothstep(0.60, 1.0, p)

            seed = seed * 1664525 + 1013904223
            val white = (((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5) * 2.0

            // A human scream climbs before it breaks. This one starts below a human voice and
            // ends above it, with jitter so no two moments are the same pitch.
            val f0 = 185.0 + 690.0 * rise - 340.0 * fall +
                26.0 * sin(2 * PI * 9.3 * t) + 13.0 * sin(2 * PI * 24.7 * t)
            phase = (phase + f0 / sampleRate) % 1.0
            subPhase = (subPhase + f0 * 0.501 / sampleRate) % 1.0
            val saw = (phase * 2.0 - 1.0)
            val pulse = if (phase < 0.42) 1.0 else -1.0
            val sub = saw * 0.36 + pulse * 0.22 + sin(2 * PI * subPhase) * 0.20
            val voiceSource = sub + white * 0.20 + sin(2 * PI * phase * 3.0) * 0.10

            // Three formants, all moving. The upper pair is what makes it a shriek rather than a
            // shout; the first pair keeps a throat in the sound.
            val f1 = 640.0 + 270.0 * rise + 130.0 * sin(2 * PI * 5.2 * t)
            val f2 = 1540.0 + 930.0 * rise + 290.0 * sin(2 * PI * 7.7 * t)
            val f3 = 2860.0 + 1240.0 * rise + 210.0 * sin(2 * PI * 11.3 * t)
            val formant1 = bandPass(voiceSource, f1, sampleRate, low1, band1)
            low1 = formant1.first
            band1 = formant1.second
            val formant2 = bandPass(voiceSource, f2, sampleRate, low2, band2)
            low2 = formant2.first
            band2 = formant2.second
            val formant3 = bandPass(voiceSource, f3, sampleRate, low3, band3)
            low3 = formant3.first
            band3 = formant3.second
            var voice = band1 * 1.08 + band2 * 0.76 + band3 * 0.48

            // An inharmonic cluster on top. The ratios are deliberately not integer multiples, so
            // it beats and grates instead of turning into a musical note.
            val shriekFreq = 2_260.0 + 1_620.0 * rise + 240.0 * sin(2 * PI * 8.1 * t) +
                90.0 * sin(2 * PI * 27.0 * t)
            shriekPhase = (shriekPhase + shriekFreq / sampleRate) % 1.0
            val shriek = sin(2 * PI * shriekPhase) +
                0.56 * sin(2 * PI * shriekPhase * 1.47 + 1.7) +
                0.31 * sin(2 * PI * shriekPhase * 2.09 + 0.4)
            val shriekRattle = 0.42 + 0.58 * abs(sin(2 * PI * 31.0 * t))
            voice += shriek * shriekRattle * 0.30

            // The chest. This is the part a phone speaker cannot reproduce, but headphones and a
            // desk speaker can, and it is what makes the scare arrive physically.
            lowPhase = (lowPhase + (79.0 - 22.0 * fall) / sampleRate) % 1.0
            val growl = tanh(4.4 * sin(2 * PI * lowPhase) + 1.7 * sin(2 * PI * lowPhase * 0.5))
            voice += growl * 0.22 * (1.0 - 0.35 * rise)

            voice += white * (0.10 + 0.11 * abs(sin(2 * PI * 29.0 * t)))
            // A bright, short transient at the start: the moment the mouth opens.
            voice += white * 0.50 * (1.0 - p).pow(4.0) +
                sin(2 * PI * 4_300.0 * t) * 0.20 * (1.0 - p).pow(6.0)

            // Too clean is not frightening. This folds the peaks over instead of clipping them.
            var dry = tanh(voice * 2.35)
            val attack = smoothstep(0.0, 0.042, t)
            val release = if (p < 0.82) 1.0 else ((1.0 - p) / 0.18).pow(1.45)
            dry *= attack * release * (0.88 + 0.12 * sin(2 * PI * 13.0 * t))

            // A very short, very wet room. Three combs are enough: the scare should sound close
            // and huge at the same time, not like a cathedral.
            val d1 = (sampleRate * 0.029).toInt().coerceAtLeast(1)
            val d2 = (sampleRate * 0.037).toInt().coerceAtLeast(1)
            val d3 = (sampleRate * 0.043).toInt().coerceAtLeast(1)
            val echo1 = combs[0][i % d1]
            val echo2 = combs[1][i % d2]
            val echo3 = combs[2][i % d3]
            combs[0][i % d1] = dry + echo1 * 0.58
            combs[1][i % d2] = dry + echo2 * 0.52
            combs[2][i % d3] = dry + echo3 * 0.46
            val reverbed = dry * 0.68 + (echo1 + echo2 + echo3) * 0.16

            // DC blocker, so the normalized wave does not spend headroom on a constant offset.
            val blocked = reverbed - previous + 0.995 * dc
            previous = reverbed
            dc = blocked
            wet[i] = blocked
        }

        // Normalize to 0.95 peak. A scream is supposed to be startling, not to clip the phone's
        // mixer; the 0.05 leaves the transients somewhere to go.
        var peak = 0.0
        for (value in wet) peak = max(peak, abs(value))
        val gain = if (peak > 1e-9) 0.95 / peak else 1.0
        val out = ShortArray(length)
        for (i in wet.indices) {
            val value = (wet[i] * gain * 32767.0).toInt().coerceIn(-32768, 32767)
            out[i] = value.toShort()
        }
        return out
    }

    private fun bandPass(
        input: Double,
        cutoff: Double,
        sampleRate: Int,
        low: Double,
        band: Double,
    ): Pair<Double, Double> {
        val frequency = 2.0 * sin(PI * cutoff.coerceIn(40.0, sampleRate * 0.45) / sampleRate)
            .coerceIn(0.001, 1.35)
        // Q ~ 3.5. Higher rings longer and smears the syllables; lower turns the scream into a
        // low-passed roar.
        val damping = 0.28
        val high = input - low - damping * band
        val nextBand = band + frequency * high
        val nextLow = low + frequency * nextBand
        return nextLow to nextBand
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge1 <= edge0) return if (value >= edge1) 1.0 else 0.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

}
