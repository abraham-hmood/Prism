package com.prism.launcher.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The "studio" stage around a neural voice.
 *
 * Pocket TTS reproduces the recording it is conditioned on -- the reference's bandwidth, hiss,
 * room tone and loudness all come back in the generated speech. A dataset recorded on a laptop
 * microphone therefore produces a laptop-microphone voice no matter how good the model is, and
 * the model's own output can arrive quiet and dull. This object fixes both ends:
 *
 *  - [conditionReference] restores a reference before it is encoded: removes DC and rumble,
 *    trims silence, adds presence, synthesises a little high-band air, evens the dynamics and
 *    brings the clip to a consistent level. The model then hears a close-microphone take.
 *  - [master] is the last stage after synthesis: gentle presence, compression, a soft ceiling
 *    and a loudness/peak target, so every generated clip arrives at the same comfortable level.
 *
 * All processing is mono, block-free and O(n): a handful of biquads, a tanh exciter and an
 * envelope follower. Nothing here changes wording or timing.
 */
object VoicePolish {

    /** Peak every finished clip is brought to (-1 dBFS). */
    const val TARGET_PEAK = 0.89f

    /** Reference loudness target before the model sees it (-18 dBFS RMS). */
    private const val REFERENCE_RMS = 0.126f

    /** Where a trimmed reference starts/stops, as absolute sample value. */
    private const val SILENCE = 0.0025f

    /**
     * A recording made fit to condition a voice model: trimmed, high-passed, presence-lifted,
     * air-excited, compressed and peak-normalised. Returns a copy; the input is not modified.
     */
    fun conditionReference(input: FloatArray, rate: Int): FloatArray {
        if (input.isEmpty() || rate <= 0) return input
        var x = trim(input, rate)
        x = removeDc(x)
        x = highPass(x, rate, 60f)
        // A close microphone is BODY-FIRST. Pocket TTS already reproduces the reference's
        // presence, so the old +5 dB at 3.2 kHz made a thin, telephonic reference thinner still.
        // A little chest and only a little presence are what keep the finished voice from
        // sounding like a headset.
        x = peaking(x, rate, 190f, 0.8f, 1.5f)
        // The reference is what the neural voice copies, so a boost that only exists in the
        // finished audio cannot make a dull recording sound close. Lift the same intelligibility
        // band here, a little more gently than on the output, so the model learns it.
        x = peaking(x, rate, 2_800f, 0.9f, 3.5f)
        x = peaking(x, rate, 3_800f, 1.0f, 4.0f)
        x = highShelf(x, rate, 6_000f, 3.5f)
        x = level(x, peak = 0.65f, rms = 0f)
        // The reference is 16 kHz in most datasets; the model cannot learn an upper octave that
        // was never recorded. A trace of excitation gives the encoder a hint of air without the
        // bright, hissy edge that reads as a cheap microphone.
        x = excite(x, rate, 0.09f)
        x = compress(x, rate, threshold = 0.09f, ratio = 2.2f, attackMs = 8f, releaseMs = 120f)
        x = level(x, peak = TARGET_PEAK, rms = REFERENCE_RMS)
        return x
    }

    /**
     * Mastering for a full-band studio voice (Kokoro's own output).
     *
     * [master] was tuned around Pocket TTS, whose output is dark above 4 kHz and needs a loud
     * presence lift to stop sounding like a laptop microphone. Kokoro is already a close-mic
     * recording: the same lift makes it honky, and the air exciter adds hiss on top of real air.
     * This chain only removes rumble, takes out a little boxiness, evens the level and leaves the
     * 4-12 kHz band the model produced intact -- that band is what makes a voice sound like it is
     * in the room rather than on a call.
     */
    fun masterStudio(input: FloatArray, rate: Int): FloatArray {
        if (input.isEmpty() || rate <= 0) return input
        var x = removeDc(input)
        x = highPass(x, rate, 55f)
        // A small chest lift and one boxiness cut; neither should be audible as an effect.
        x = peaking(x, rate, 170f, 0.7f, 1.5f)
        x = peaking(x, rate, 380f, 1.0f, -1.5f)
        // Kokoro's own top octave is uneven: keep it, trim only the sharpest sibilance.
        x = peaking(x, rate, 6_600f, 1.4f, -1.2f)
        x = highShelf(x, rate, 8_500f, 1.5f)
        x = compress(x, rate, threshold = 0.12f, ratio = 1.7f, attackMs = 8f, releaseMs = 120f)
        for (i in x.indices) {
            val v = x[i]
            if (abs(v) > 0.98f) x[i] = sign(v) * (0.98f + 0.019f * tanh((abs(v) - 0.98f) * 50f))
        }
        return level(x, peak = TARGET_PEAK, rms = 0.115f)
    }

    /**
     * The final stage on generated speech. Keeps the voice's own character; only evens the level
     * and removes the last of the model's tendency to leave headroom on the table.
     */
    fun master(input: FloatArray, rate: Int): FloatArray {
        if (input.isEmpty() || rate <= 0) return input
        var x = removeDc(input)
        // LOW END IS THE DIFFERENCE between "in the room" and "on a phone call". The previous
        // chain high-passed at 60 Hz and then compressed what was left, which removed another
        // 3 dB of chest and left the voice thin. Keep the fundamentals, trim only rumble, and
        // take a little boxiness out around 380 Hz.
        x = highPass(x, rate, 40f)
        // A broad chest lift and a narrow boxiness cut. The lift has to be broad because the
        // fundamental and its first harmonics are a single resonance, not one frequency.
        x = peaking(x, rate, 150f, 0.6f, 7f)
        x = peaking(x, rate, 420f, 1.0f, -2.5f)
        x = peaking(x, rate, 700f, 1.1f, -1.5f)
        // THE INTELLIGIBILITY BAND IS WHERE A CHEAP MICROPHONE IS MOST OBVIOUS. Pocket's output
        // measured about 7 dB below its reference between 2 and 4 kHz -- the band that carries
        // consonant detail and the sense of a mouth close to the listener. A broad lift here,
        // rather than more top-end hiss, is what removes the "laptop microphone" impression.
        x = peaking(x, rate, 2_300f, 0.9f, 4f)
        x = peaking(x, rate, 3_600f, 1.1f, 2f)
        // The air stage deliberately puts energy around 5 kHz; Pocket already over-emphasises
        // that band by comparison with 2-4 kHz, so trim the harsh part instead of adding to it.
        x = peaking(x, rate, 4_800f, 1.4f, -2.5f)
        // Pocket's own output is dark above 4 kHz (often 30 dB below the voice). It needs air,
        // but the old 0.32 mix plus a +2 dB shelf added roughly 25 dB and turned that air into
        // hiss. This is the smallest amount that stops the voice sounding muffled.
        x = air(x, rate, 0.10f)
        x = highShelf(x, rate, 5_500f, 1.0f)
        // Sibilance lives here. Taming it after the air stage keeps "s" from turning into a
        // whistle, which is the other half of the cheap-microphone impression.
        x = peaking(x, rate, 6_300f, 1.2f, -1.5f)
        x = compress(x, rate, threshold = 0.11f, ratio = 2.0f, attackMs = 6f, releaseMs = 110f)
        for (i in x.indices) {
            val v = x[i]
            if (abs(v) > 0.98f) x[i] = sign(v) * (0.98f + 0.019f * tanh((abs(v) - 0.98f) * 50f))
        }
        return level(x, peak = TARGET_PEAK, rms = 0.12f)
    }

    // -- Building blocks ------------------------------------------------------

    private fun trim(input: FloatArray, rate: Int): FloatArray {
        var first = -1
        var last = -1
        for (i in input.indices) {
            if (abs(input[i]) > SILENCE) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return input
        val pad = (rate * 0.02f).toInt()
        val start = (first - pad).coerceAtLeast(0)
        val end = (last + pad).coerceAtMost(input.lastIndex)
        if (end - start < rate / 20) return input
        return input.copyOfRange(start, end + 1)
    }

    private fun removeDc(input: FloatArray): FloatArray {
        var sum = 0.0
        for (v in input) sum += v
        val mean = (sum / input.size).toFloat()
        return FloatArray(input.size) { input[it] - mean }
    }

    private fun highPass(input: FloatArray, rate: Int, cutoff: Float): FloatArray {
        val w0 = 2.0 * PI * cutoff / rate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.7071)
        val a0 = 1.0 + alpha
        return biquad(
            input,
            ((1.0 + cosW) / 2.0 / a0).toFloat(),
            (-(1.0 + cosW) / a0).toFloat(),
            ((1.0 + cosW) / 2.0 / a0).toFloat(),
            (-2.0 * cosW / a0).toFloat(),
            ((1.0 - alpha) / a0).toFloat(),
        )
    }

    private fun lowPass(input: FloatArray, rate: Int, cutoff: Float): FloatArray {
        val w0 = 2.0 * PI * cutoff / rate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.7071)
        val a0 = 1.0 + alpha
        return biquad(
            input,
            ((1.0 - cosW) / 2.0 / a0).toFloat(),
            ((1.0 - cosW) / a0).toFloat(),
            ((1.0 - cosW) / 2.0 / a0).toFloat(),
            (-2.0 * cosW / a0).toFloat(),
            ((1.0 - alpha) / a0).toFloat(),
        )
    }

    /**
     * Spectral air: the speech band is ring-modulated upward and only the upper sideband is kept.
     *
     * Unlike a saturating exciter this does not need the source to contain distortion to work, so
     * it brightens a smooth four-step vocoder output as reliably as a noisy one-step one. The
     * result follows the speech envelope, which is what keeps it from sounding like a constant
     * hiss; [mix] is the upper sideband's peak relative to the voice.
     */
    private fun air(input: FloatArray, rate: Int, mix: Float): FloatArray {
        if (input.isEmpty() || rate <= 0) return input
        val band = lowPass(highPass(input, rate, 1_200f), rate, 3_400f)
        val step = 2.0 * PI * 4_000.0 / rate
        var phase = 0.0
        val shifted = FloatArray(band.size) {
            val value = (band[it] * cos(phase)).toFloat()
            phase += step
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            value
        }
        var upper = highPass(shifted, rate, 4_500f)
        var peak = 0f
        for (v in upper) peak = max(peak, abs(v))
        if (peak < 1e-7f) return input
        var voicePeak = 0f
        for (v in input) voicePeak = max(voicePeak, abs(v))
        val gain = voicePeak * mix / peak
        upper = FloatArray(upper.size) { upper[it] * gain }
        return FloatArray(input.size) { input[it] + upper[it] }
    }

    private fun peaking(input: FloatArray, rate: Int, freq: Float, q: Float, gainDb: Float): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / rate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return biquad(
            input,
            ((1.0 + alpha * a) / a0).toFloat(),
            ((-2.0 * cosW) / a0).toFloat(),
            ((1.0 - alpha * a) / a0).toFloat(),
            ((-2.0 * cosW) / a0).toFloat(),
            ((1.0 - alpha / a) / a0).toFloat(),
        )
    }

    private fun highShelf(input: FloatArray, rate: Int, freq: Float, gainDb: Float): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / rate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
        return biquad(
            input,
            (a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha) / a0).toFloat(),
            (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW) / a0).toFloat(),
            (a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat(),
            (2.0 * ((a - 1.0) - (a + 1.0) * cosW) / a0).toFloat(),
            (((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat(),
        )
    }

    /** Transposed direct-form II, coefficients already divided by a0. */
    private fun biquad(
        input: FloatArray,
        b0: Float,
        b1: Float,
        b2: Float,
        a1: Float,
        a2: Float,
    ): FloatArray {
        val out = FloatArray(input.size)
        var z1 = 0f
        var z2 = 0f
        for (i in input.indices) {
            val x = input[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            out[i] = y
        }
        return out
    }

    /**
     * Adds harmonics above the reference's own band by saturating a high-passed copy and keeping
     * only the resulting air. A pure EQ cannot brighten a 16 kHz dataset above 8 kHz; this can,
     * at a level low enough to sit under the voice rather than replace it.
     */
    private fun excite(input: FloatArray, rate: Int, amount: Float): FloatArray {
        if (input.isEmpty() || rate <= 0) return input
        val band = highPass(input, rate, 2_400f)
        val driven = FloatArray(band.size) { tanh(band[it] * 3.5f) }
        val residual = FloatArray(band.size) { driven[it] - band[it] }
        var air = highPass(residual, rate, 3_800f)
        var peak = 0f
        for (v in air) peak = max(peak, abs(v))
        if (peak < 1e-6f) return input
        var voicePeak = 0f
        for (v in input) voicePeak = max(voicePeak, abs(v))
        val mix = voicePeak * amount / peak
        air = FloatArray(air.size) { air[it] * mix }
        return FloatArray(input.size) { input[it] + air[it] }
    }

    private fun compress(
        input: FloatArray,
        rate: Int,
        threshold: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
    ): FloatArray {
        if (input.isEmpty()) return input
        val attack = exp((-1.0 / (rate * attackMs / 1_000.0)).toFloat())
        val release = exp((-1.0 / (rate * releaseMs / 1_000.0)).toFloat())
        val out = FloatArray(input.size)
        var envelope = 0f
        for (i in input.indices) {
            val value = input[i]
            val level = abs(value)
            val coeff = if (level > envelope) attack else release
            envelope = coeff * envelope + (1f - coeff) * level
            val gain = if (envelope > threshold) {
                (threshold + (envelope - threshold) / ratio) / envelope
            } else {
                1f
            }
            out[i] = value * gain
        }
        return out
    }

    /**
     * Brings a clip to [peak], and toward [rms] when that would not push it over the ceiling.
     * The softer of the two targets wins, so a naturally dynamic take is not squashed flat.
     */
    private fun level(input: FloatArray, peak: Float, rms: Float): FloatArray {
        var currentPeak = 0f
        var sum = 0.0
        for (v in input) {
            currentPeak = max(currentPeak, abs(v))
            sum += v.toDouble() * v
        }
        if (currentPeak < 1e-7f) return input
        var gain = peak / currentPeak
        if (rms > 0f) {
            val currentRms = sqrt(sum / input.size).toFloat()
            if (currentRms > 1e-7f) {
                val rmsGain = rms / currentRms
                gain = min(gain, rmsGain)
            }
        }
        return FloatArray(input.size) { (input[it] * gain).coerceIn(-1f, 1f) }
    }
}
