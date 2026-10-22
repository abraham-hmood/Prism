package com.prism.launcher.speech

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Moves synthesised speech toward the pitch of the person who was recorded -- before any engine
 * re-voices it.
 *
 * ## Why this helps every engine at once
 *
 * Kokoro speaks through a PUBLISHED voice: its pitch is that voice's, and an engine that carries
 * source pitch through (OpenVoice, FreeVC) therefore outputs the published voice's pitch,
 * not the speaker's. Pitch is one of the strongest identity cues there is, so the clone sits in
 * the uncanny middle -- recognisably being pushed toward the person, obviously not them. Shifting
 * the source to the speaker's measured median F0 before conversion lands the output on their pitch
 * without touching wording or pacing. Engines that rebuild pitch from the reference themselves
 * ([CloneEngine.preservesPitch] == false) skip this -- they already arrive at the right pitch.
 *
 * ## The shift, and why WSOLA
 *
 * Naive resampling moves pitch by changing duration, which would desynchronise everything the
 * model was about to do. The shift here is WSOLA: overlap-add frames of the source read at a
 * slightly faster/slower pointer, choosing each frame's position by correlation with what is
 * already written so waveform phase stays aligned (uncorrelated frames sound metallic). Output
 * length equals input length exactly.
 *
 * [match] only acts on a real difference: below ~4% the gap is jitter between two takes of the
 * same voice, and beyond ~1.5x either way it is a different character rather than a match, so the
 * ratio is clamped there.
 */
object VoicePitch {

    /** Below this ratio (either direction) two pitches are the same voice; skip the work. */
    private const val MIN_RATIO = 1.04f

    /** Clamp: a bigger jump is impersonation of somebody else, not matching the speaker. */
    private const val MIN_CLAMP = 0.7f
    private const val MAX_CLAMP = 1.5f

    private const val FRAME = 1024
    private const val HOP = 256

    /**
     * [samples] (mono, at [rate]) shifted so its median pitch lands on [targetHz].
     *
     * Returns the input untouched when the target is unknown, the source has no measurable pitch,
     * or the two already agree. Blocking DSP work; callers run it off the UI thread.
     */
    fun match(samples: FloatArray, rate: Int, targetHz: Float): FloatArray {
        if (samples.size < FRAME * 2 || targetHz <= 0f || rate <= 0) return samples

        val measured = medianPitch(samples, rate)
        if (measured <= 0f) return samples

        val ratio = (targetHz / measured).coerceIn(MIN_CLAMP, MAX_CLAMP)
        // Skip when the clamped ratio still means "same voice": between 1/MIN_RATIO and MIN_RATIO.
        if (ratio > 1f / MIN_RATIO && ratio < MIN_RATIO) return samples
        return shift(samples, rate, ratio)
    }

    /** Median F0 of [samples] in Hz (0 when there is no voiced speech to measure). */
    fun medianPitch(samples: FloatArray, rate: Int): Float {
        val mono = if (rate == VoiceCloner.WORK_RATE) samples else resample(samples, rate, VoiceCloner.WORK_RATE)
        return VoiceCloner.pitchOf(mono)
    }

    /**
     * Pitch by [ratio] (>1 higher, <1 lower) at unchanged length, in two stages.
     *
     * The pitch move itself is a resample: reading [samples] as if they had been recorded at
     * [rate] * [ratio] changes frequency without touching wording. That changes duration too, so
     * a standard WSOLA time-scale puts it back: frames are read [HOP] * m / n apart in the
     * resampled signal and written [HOP] apart in the output, each nudged by correlation with
     * what is already written so the overlap stays phase-coherent instead of buzzy.
     *
     * The two rates decide pitch and duration; correlation only stitches the seams. Searching
     * directly for a shifted read rate -- the obvious one-pass version -- does not work: on a
     * periodic signal the search keeps finding the stitch that restores the ORIGINAL rate and
     * quietly undoes the whole shift. Splitting the stages is what makes the shift stick.
     */
    fun shift(samples: FloatArray, rate: Int, ratio: Float): FloatArray {
        val n = samples.size
        if (n < FRAME * 2 || ratio == 1f || rate <= 0) return samples

        // Stage 1: the pitch change. Length becomes ~n / ratio.
        val pitched = resample(samples, (rate * ratio).roundToInt().coerceAtLeast(1), rate)
        val m = pitched.size
        if (m < FRAME || m == n) return samples

        // Stage 2: put the duration back, n samples, reading from `pitched`.
        val bound = 140
        val readStep = HOP.toDouble() * m / n

        val window = FloatArray(FRAME) { i ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / FRAME)).toFloat()
        }
        val acc = FloatArray(n)
        val norm = FloatArray(n)
        val lastStart = (m - FRAME).coerceAtLeast(0)

        var read = 0.0
        var write = 0
        var previous = -1

        while (write < n) {
            val nominal = read.toInt().coerceIn(0, lastStart)
            var best = nominal
            if (previous >= 0 && nominal < lastStart) {
                var bestScore = Float.NEGATIVE_INFINITY
                val low = (nominal - bound).coerceAtLeast(0)
                val high = (nominal + bound).coerceAtMost(lastStart)
                var candidate = low
                while (candidate <= high) {
                    var score = 0f
                    var i = 0
                    while (i < HOP && write + i < n) {
                        score += pitched[candidate + i] * acc[write + i]
                        i++
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate
                    }
                    candidate++
                }
            }

            val end = min(FRAME, n - write)
            var i = 0
            while (i < end) {
                acc[write + i] += window[i] * pitched[best + i]
                norm[write + i] += window[i]
                i++
            }

            previous = best
            read += readStep
            if (read > lastStart) read = lastStart.toDouble()
            write += HOP
        }

        return FloatArray(n) { i ->
            val accumulated = acc[i]
            val weight = norm[i]
            if (weight > 0.05f) accumulated / weight else accumulated
        }
    }

    /** Linear resample, used to bring arbitrary-rate audio to [VoiceCloner.WORK_RATE]. */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
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
}
