package com.prism.launcher.speech

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Prepares a few seconds of somebody's voice for [VoiceConverter], and answers the two questions
 * the save dialog asks about a recording: how long the speech in it is, and whether it sounds
 * male or female.
 *
 * ## What a clone is, now
 *
 * Kokoro has no speaker encoder -- there is no published operation that maps audio to its style
 * tensor -- so a clone is not a tensor. Kokoro speaks the words with a published voice, and
 * [VoiceConverter] re-voices that audio to the recorded speaker. What this class contributes to
 * that is the cleaned-up recording itself: silence trimmed, levelled, capped at thirty seconds,
 * at the 22.05 kHz the converter wants. [VoiceConverter.embedding] turns those samples into the
 * 256-value direction that gets stored.
 *
 * The acoustic analysis that remains -- framing, mel bands, pitch by normalised autocorrelation --
 * exists for [guessGender] and the save dialog's "N seconds" line, not for encoding.
 */
object VoiceCloner {

    /** The published layout, for reading voice files that still exist in the old format. */
    const val ROWS = 510

    const val WIDTH = 256

    /** Everything is measured at the converter's rate, so a recording needs no resample twice. */
    const val WORK_RATE = VoiceConverter.RATE

    private const val FRAME = 512
    private const val HOP = 160
    private const val MEL_BANDS = 40
    private const val MIN_SPEECH_SECONDS = 0.75f
    private const val MAX_SPEECH_SECONDS = 30f
    private const val PITCH_MIN_LAG = WORK_RATE / 400
    private const val PITCH_MAX_LAG = WORK_RATE / 50
    private const val VOICING_THRESHOLD = 0.35f

    /**
     * What the analysis found in one take: carried from the recording to the save dialog.
     *
     * [region] is the speech itself -- silence trimmed, levelled, at [WORK_RATE]. It is what
     * [VoiceConverter.embedding] encodes, so the tone direction describes the speaker rather than
     * the room they were in.
     */
    data class SpeakerProfile(
        val region: FloatArray,
        val pitchHz: Float,
        val pitchSpreadHz: Float,
        val voicedRatio: Float,
        val seconds: Float,
    )

    /**
     * Analyses [samples], or throws with a message worth showing a user.
     *
     * [onStage] is called with short progress lines on the calling thread, so a caller that hops to
     * the main thread for each one gets a live transcript of what is happening.
     */
    fun profile(
        samples: FloatArray,
        sampleRate: Int,
        onStage: (String) -> Unit = {},
    ): SpeakerProfile {
        if (samples.isEmpty()) throw IllegalStateException("There is no audio to clone")

        onStage("Cleaning up the audio…")
        val mono = if (sampleRate == WORK_RATE) samples else resample(samples, sampleRate, WORK_RATE)
        val region = speechRegion(cutLeadingAndTrailingQuiet(mono))
            ?: throw IllegalStateException(
                "Almost no speech in that -- hold the microphone for a few seconds of talking.",
            )
        val seconds = region.size.toFloat() / WORK_RATE
        if (seconds < MIN_SPEECH_SECONDS) {
            throw IllegalStateException(
                "Only ${"%.1f".format(seconds)} s of speech -- hold the microphone a little longer.",
            )
        }

        onStage("Listening to the spectrum…")
        val frames = analyse(region)

        onStage("Tracking pitch…")
        return SpeakerProfile(
            region = region,
            pitchHz = frames.medianF0,
            pitchSpreadHz = frames.pitchSpread,
            voicedRatio = frames.voicedRatio,
            seconds = seconds,
        )
    }

    /**
     * Median pitch of [samples] in Hz -- already at [WORK_RATE] -- or 0 when there is no voiced
     * speech to measure. Never throws: silence and noise are answers (0), not failures, because
     * [VoicePitch] asks this about freshly synthesised audio that may be anything.
     */
    fun pitchOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        return runCatching { analyse(samples).medianF0 }.getOrElse { 0f }
    }

    /**
     * Which published-gender voice a recording like [profile] most likely wants.
     *
     * Adult male speech sits around 85-180 Hz and adult female around 165-255 Hz, with the usual
     * overlap. 160 Hz is the customary split; anything unvoiced or degenerate falls back to female,
     * which matches the picker's own default.
     */
    fun guessGender(profile: SpeakerProfile): KokoroVoices.Gender =
        if (profile.pitchHz <= 0f || profile.pitchHz >= 160f) {
            KokoroVoices.Gender.FEMALE
        } else {
            KokoroVoices.Gender.MALE
        }

    /** The published voice a clone of [language] and [gender] is built on, preferred grade first. */
    fun baseVoiceFor(
        language: KokoroVoices.Language,
        gender: KokoroVoices.Gender,
    ): KokoroVoices.Voice? =
        KokoroVoices.ALL
            .filter { it.language == language }
            .sortedWith(compareBy({ it.gender != gender }, { it.quality }, { it.id }))
            .firstOrNull()

    // -- Acoustics -------------------------------------------------------------

    private class Frames(
        val medianF0: Float,
        val pitchSpread: Float,
        val voicedRatio: Float,
    )

    private fun analyse(region: FloatArray): Frames {
        val count = max(1, (region.size - FRAME) / HOP + 1)
        val f0 = FloatArray(count)
        val voicing = FloatArray(count)
        val rms = FloatArray(count)

        val window = FloatArray(FRAME) { hann(it) }
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val power = FloatArray(FRAME / 2 + 1)

        for (f in 0 until count) {
            val at = f * HOP
            var energy = 0f
            for (i in 0 until FRAME) {
                val raw = region.getOrNull(at + i) ?: 0f
                re[i] = raw * window[i]
                im[i] = 0f
                energy += raw * raw
            }
            rms[f] = sqrt(energy / FRAME)

            fft(re, im, inverse = false)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]

            // The inverse transform of the power spectrum is the autocorrelation, which is what
            // pitch detection needs and the reason there is one FFT per frame rather than two.
            for (k in 0..FRAME / 2) {
                re[k] = power[k]
                im[k] = 0f
            }
            for (k in FRAME / 2 + 1 until FRAME) {
                re[k] = power[FRAME - k]
                im[k] = 0f
            }
            fft(re, im, inverse = true)

            val floor = re[0]
            var peak = 0f
            var peakLag = 0
            // The circular autocorrelation is symmetric: lag L and FRAME-L are exactly tied,
            // so scanning past FRAME/2 would report the mirror of the period (a 50 Hz-reading
            // octave artefact) whenever float noise won the tie. Stay in the valid half.
            val scanMax = min(PITCH_MAX_LAG, FRAME / 2)
            if (floor > 1e-6f) {
                for (lag in PITCH_MIN_LAG..scanMax) {
                    val value = re[lag] / floor
                    if (value > peak) {
                        peak = value
                        peakLag = lag
                    }
                }
            }
            if (peak >= VOICING_THRESHOLD && peakLag > 0) {
                // Among all lags tied (within 2%) with the peak, take the earliest: that is the
                // fundamental period, where the later ones are its multiples.
                val target = peak * 0.98f
                var chosen = peakLag
                for (lag in PITCH_MIN_LAG until peakLag) {
                    if (re[lag] / floor >= target) {
                        chosen = lag
                        break
                    }
                }
                voicing[f] = min(1f, peak)
                f0[f] = WORK_RATE.toFloat() / chosen
            }
        }

        val loudest = rms.maxOrNull() ?: 0f
        val gate = max(loudest, 1e-6f) * 0.10f
        val active = (0 until count).filter { rms[it] >= gate }
        if (active.isEmpty()) throw IllegalStateException("That recording is all background noise")

        val voiced = active.filter { voicing[it] > 0f }.map { f0[it] }.sorted()
        val mean = if (voiced.isEmpty()) 0f else voiced.average().toFloat()
        val variance = if (voiced.size > 1) {
            voiced.sumOf { ((it - mean) * (it - mean)).toDouble() } / voiced.size
        } else {
            0.0
        }

        return Frames(
            medianF0 = if (voiced.isEmpty()) 0f else voiced[voiced.size / 2],
            pitchSpread = sqrt(variance).toFloat(),
            voicedRatio = voiced.size.toFloat() / active.size,
        )
    }

    // -- Signal helpers --------------------------------------------------------

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from <= 0 || to <= 0 || from == to || input.isEmpty()) return input
        val length = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(length)
        val step = from.toDouble() / to
        for (i in 0 until length) {
            val position = i * step
            val at = position.toInt().coerceIn(0, input.lastIndex)
            val next = min(at + 1, input.lastIndex)
            val fraction = (position - at).toFloat()
            out[i] = input[at] * (1f - fraction) + input[next] * fraction
        }
        return out
    }

    private fun cutLeadingAndTrailingQuiet(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        var dc = 0f
        for (sample in input) dc += sample
        dc /= input.size

        val window = max(1, WORK_RATE / 20)
        val gate = 0.02f
        var start = 0
        var end = input.size
        while (start + window < end) {
            var energy = 0f
            for (i in start until start + window) energy += abs(input[i] - dc)
            if (energy / window > gate) break
            start += window
        }
        while (end - start > window) {
            var energy = 0f
            for (i in end - window until end) energy += abs(input[i] - dc)
            if (energy / window > gate) break
            end -= window
        }
        if (end - start < window) return input

        val trimmed = input.copyOfRange(start, end)
        for (i in trimmed.indices) trimmed[i] -= dc
        return trimmed
    }

    private fun speechRegion(input: FloatArray): FloatArray? {
        if (input.isEmpty()) return null
        // The tail, not the head: what the user just finished saying is what they mean to clone.
        val longest = (MAX_SPEECH_SECONDS * WORK_RATE).toInt()
        val capped = if (input.size > longest) input.copyOfRange(input.size - longest, input.size)
        else input

        var peak = 0f
        for (sample in capped) peak = max(peak, abs(sample))
        if (peak < 1e-4f) return null
        val scale = 0.9f / peak
        return FloatArray(capped.size) { capped[it] * scale }
    }

    private fun hann(index: Int): Float =
        (0.5 - 0.5 * cos(2.0 * Math.PI * index / (FRAME - 1))).toFloat()

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var swap = re[i]; re[i] = re[j]; re[j] = swap
                swap = im[i]; im[i] = im[j]; im[j] = swap
            }
        }

        var length = 2
        while (length <= n) {
            val angle = 2.0 * Math.PI / length * (if (inverse) 1.0 else -1.0)
            val stepR = cos(angle).toFloat()
            val stepI = kotlin.math.sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var currentR = 1f
                var currentI = 0f
                for (k in 0 until length / 2) {
                    val left = start + k
                    val right = left + length / 2
                    val productR = re[right] * currentR - im[right] * currentI
                    val productI = re[right] * currentI + im[right] * currentR
                    re[right] = re[left] - productR
                    im[right] = im[left] - productI
                    re[left] += productR
                    im[left] += productI
                    val nextR = currentR * stepR - currentI * stepI
                    currentI = currentR * stepI + currentI * stepR
                    currentR = nextR
                }
                start += length
            }
            length = length shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
