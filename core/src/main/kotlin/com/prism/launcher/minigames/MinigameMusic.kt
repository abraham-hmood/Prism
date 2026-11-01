package com.prism.launcher.minigames

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The arcade's soundtrack, one bar at a time.
 *
 * ## Why generated, and why per bar
 *
 * Nineteen cabinets do not each get a music file, and shipping loops would make the APK bigger for
 * music nobody can vary. A bar of 16-bit mono PCM is a few hundred kilobytes and is thrown away
 * after it is played, so the soundtrack follows the run instead of repeating at one tempo: the page
 * asks for the next bar with the current intensity, and [renderBar] turns that into tempo, note
 * density and how many layers are audible.
 *
 * ## The mapping
 *
 * Intensity 0..1 raises the tempo from 78 to 168 BPM. The bass and the arpeggio are always there;
 * hats arrive around a third of the way up, a second kick and sixteenth-note shimmer near the top.
 * The harmony is a minor pentatonic rooted on the game id, so Snake and Prism Split are different
 * keys rather than the same tune at different speeds.
 *
 * Every bar is deterministic from (game, bar, seed), which makes the output testable and means a
 * dropped frame cannot desynchronise the music from itself.
 */
object MinigameMusic {

    const val SAMPLE_RATE = 22050

    private val PENTATONIC = intArrayOf(0, 3, 5, 7, 10)
    private val BASE_HZ = floatArrayOf(110f, 123.47f, 130.81f, 146.83f, 164.81f, 174.61f, 196f)

    /** One bar of mono 16-bit PCM at [SAMPLE_RATE]. */
    fun renderBar(gameId: String, intensity: Float, bar: Int, seed: Long = 7L): ShortArray {
        val heat = intensity.coerceIn(0f, 1f)
        val bpm = 78f + 90f * heat
        val beat = 60f / bpm
        val barSeconds = beat * 4f
        val samples = (barSeconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val mix = FloatArray(samples)

        val rootIndex = (gameId.hashCode().let { if (it < 0) -it else it } + seed.toInt()) % BASE_HZ.size
        val root = BASE_HZ[rootIndex]
        val random = Random(gameId.hashCode().toLong() * 31 + bar * 7919L + seed)

        // ── Bass ────────────────────────────────────────────────────────────
        // Two half-bars, the second a fourth up: enough movement to feel like music without
        // implying a melody the game is not playing.
        addBass(mix, 0f, barSeconds * 0.5f, root * 0.5f, 0.34f)
        addBass(mix, barSeconds * 0.5f, barSeconds * 0.5f, root * 0.5f * 1.335f, 0.30f)

        // ── Arpeggio ────────────────────────────────────────────────────────
        val steps = 8
        for (step in 0 until steps) {
            val at = step * beat / 2f
            val degree = PENTATONIC[(step + bar) % PENTATONIC.size]
            val octave = if (step % 4 == 2) 2f else 1f
            val frequency = root * Math.pow(2.0, degree / 12.0).toFloat() * octave
            addPluck(mix, at, beat * 0.55f, frequency, 0.16f + 0.05f * heat)
        }

        // ── Percussion ──────────────────────────────────────────────────────
        addKick(mix, 0f, 0.32f)
        addKick(mix, barSeconds * 0.5f, 0.30f)
        if (heat > 0.55f) addKick(mix, barSeconds * 0.75f, 0.22f)

        if (heat > 0.3f) {
            val hatEvery = if (heat > 0.72f) beat / 4f else beat / 2f
            var at = 0f
            while (at < barSeconds) {
                addHat(mix, at, 0.07f + 0.03f * heat, random.nextInt())
                at += hatEvery
            }
        }

        // ── Shimmer at the top of the range ─────────────────────────────────
        if (heat > 0.72f) {
            val frequency = root * 4f
            for (half in 0 until 2) {
                addPad(mix, half * barSeconds / 2f, barSeconds / 2f, frequency, 0.05f * (heat - 0.7f) / 0.3f)
            }
        }

        return toPcm(mix)
    }

    /**
     * Several bars in one buffer, for a platform whose audio backend wants a loop rather than a
     * callback per bar (the generated Godot project's WAV tracks). The tempo and layers still move
     * with [intensity]; only the scheduling differs.
     */
    fun renderLoop(gameId: String, intensity: Float, bars: Int, seed: Long = 7L): ShortArray {
        val count = bars.coerceAtLeast(1)
        val rendered = (0 until count).map { bar -> renderBar(gameId, intensity, bar, seed) }
        val total = rendered.sumOf { it.size }
        val out = ShortArray(total)
        var at = 0
        for (bar in rendered) {
            bar.copyInto(out, at)
            at += bar.size
        }
        return out
    }

    private fun addBass(mix: FloatArray, at: Float, length: Float, frequency: Float, gain: Float) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt().coerceAtMost(mix.size - start)
        if (start < 0 || count <= 0) return
        val fade = 0.02f * SAMPLE_RATE
        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            val envelope = exp(-2.2f * t / length) * fadeIn(i, fade, count)
            val value = sin(2.0 * PI * frequency * t).toFloat() * 0.85f +
                sin(2.0 * PI * frequency * 2.0 * t).toFloat() * 0.15f
            mix[start + i] += value * envelope * gain
        }
    }

    private fun addPluck(mix: FloatArray, at: Float, length: Float, frequency: Float, gain: Float) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt().coerceAtMost(mix.size - start)
        if (start < 0 || count <= 0) return
        val fade = 0.008f * SAMPLE_RATE
        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            // A touch of the fifth keeps it from sounding like a test tone.
            val value = sin(2.0 * PI * frequency * t).toFloat() +
                0.35f * sin(2.0 * PI * frequency * 1.5 * t).toFloat()
            val envelope = exp(-7.5f * t / length) * fadeIn(i, fade, count)
            mix[start + i] += value * envelope * gain
        }
    }

    private fun addKick(mix: FloatArray, at: Float, gain: Float) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (0.16f * SAMPLE_RATE).toInt().coerceAtMost(mix.size - start)
        if (start < 0 || count <= 0) return
        var phase = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            val frequency = 120f - 70f * (t / 0.16f).coerceIn(0f, 1f)
            phase += (2.0 * PI * frequency / SAMPLE_RATE).toFloat()
            val envelope = exp(-16f * t)
            mix[start + i] += sin(phase.toDouble()).toFloat() * envelope * gain
        }
    }

    private fun addHat(mix: FloatArray, at: Float, gain: Float, noiseSeed: Int) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (0.045f * SAMPLE_RATE).toInt().coerceAtMost(mix.size - start)
        if (start < 0 || count <= 0) return
        val noise = Random(noiseSeed)
        var previous = 0f
        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            val white = noise.nextFloat() * 2f - 1f
            // A one-pole high pass, which is what turns noise into a hat.
            val filtered = white - previous * 0.72f
            previous = white
            mix[start + i] += filtered * exp(-70f * t) * gain
        }
    }

    private fun addPad(mix: FloatArray, at: Float, length: Float, frequency: Float, gain: Float) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt().coerceAtMost(mix.size - start)
        if (start < 0 || count <= 0) return
        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            val swell = (t / length).coerceIn(0f, 1f)
            val envelope = (if (swell < 0.2f) swell / 0.2f else (1f - swell) / 0.8f).coerceIn(0f, 1f)
            val value = sin(2.0 * PI * frequency * t).toFloat() * 0.6f +
                sin(2.0 * PI * frequency * 1.5 * t).toFloat() * 0.4f
            mix[start + i] += value * envelope * gain
        }
    }

    private fun fadeIn(index: Int, fade: Float, count: Int): Float {
        val edge = fade.coerceAtMost(count / 4f).coerceAtLeast(1f)
        return if (index < edge) index / edge else 1f
    }

    private fun toPcm(mix: FloatArray): ShortArray {
        var peak = 0f
        for (value in mix) {
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        val scale = if (peak > 0.92f) 0.92f / peak else 1f
        // Five milliseconds at each end: a bar is queued next to another, and a hard cut clicks.
        val edge = (0.005f * SAMPLE_RATE).toInt().coerceAtMost(mix.size / 4)
        return ShortArray(mix.size) { index ->
            var value = mix[index] * scale
            if (index < edge) value *= index.toFloat() / edge
            val fromEnd = mix.size - 1 - index
            if (fromEnd < edge) value *= fromEnd.toFloat() / edge
            (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
