package com.prism.launcher.aether

import com.prism.core.PrismImage
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Ported from `tokenizer/motor_decoder.py::MotorDecoder`. All deterministic reshape/threshold math -- no learned weights. */
class AetherMotorDecoder(val decodeC: Int = 3, val decodeH: Int = 128, val decodeW: Int = 128) {

    /**
     * Per-pixel FIRING RATE over the window -- a graded value in [0, 1], not a maximum.
     *
     * The motor strip emits BINARY spikes, so a max over time asks only "did this subpixel fire
     * at any point", i.e. one bit per channel. Three one-bit channels is an 8-colour image, which
     * is exactly what this produced: saturated red/green/blue/cyan/magenta/yellow/white confetti
     * regardless of the prompt, with consecutive hallucination frames coming out byte-identical
     * because a subpixel that fires once is pinned at 255 forever.
     *
     * The mean is also the quantity the trainer actually optimises -- AetherTrainer's visual loss
     * compares the time-averaged motor output against the time-averaged input, so training tuned
     * a firing rate that this function then discarded in favour of one bit. Rate in, rate out.
     *
     * `gain` defaults to 1.0 for the same reason: under that objective the rate already IS the
     * intensity, and a higher gain just re-saturates what this change stopped saturating.
     */
    fun decodeToImage(spikes: SpikeSequence, gain: Float = 1.0f): PrismImage {
        val plane = decodeH * decodeW
        val rates = FloatArray(decodeC * plane)
        if (spikes.steps > 0) {
            for (t in 0 until spikes.steps) {
                val frame = spikes[t]
                for (i in rates.indices) if (i < frame.data.size) rates[i] += frame.data[i]
            }
            for (i in rates.indices) rates[i] /= spikes.steps
        }
        val pixels = IntArray(plane)
        for (y in 0 until decodeH) {
            for (x in 0 until decodeW) {
                val base = y * decodeW + x
                val r = ((rates[base] * gain).coerceIn(0f, 1f) * 255).toInt()
                val g = ((rates[plane + base] * gain).coerceIn(0f, 1f) * 255).toInt()
                val b = ((rates[2 * plane + base] * gain).coerceIn(0f, 1f) * 255).toInt()
                pixels[base] = PrismImage.argb(r, g, b)
            }
        }
        return PrismImage(decodeW, decodeH, pixels)
    }

    /**
     * One frame per biological timestep, each upscaled and blended into a decaying phosphor
     * trace (`cv2.addWeighted(persistent, 0.75, upscaled, 0.8, 0)`) -- retinal persistence, so a
     * momentary all-zero timestep doesn't flicker to black. All-zero frames are skipped entirely
     * (source: "Skip physiological empty void").
     */
    fun decodeToVideoFrames(spikes: SpikeSequence, upscale: Int = 256): List<PrismImage> {
        val plane = decodeH * decodeW
        val frames = ArrayList<PrismImage>()
        var persistent = IntArray(upscale * upscale)
        for (t in 0 until spikes.steps) {
            val frame = spikes[t]
            var maxV = 0f
            for (v in frame.data) if (v > maxV) maxV = v
            if (maxV == 0f) continue

            val smallPixels = IntArray(plane)
            for (y in 0 until decodeH) for (x in 0 until decodeW) {
                val base = y * decodeW + x
                val r = ((if (base < frame.data.size) frame.data[base] else 0f) * 255).toInt().coerceIn(0, 255)
                val g = ((if (plane + base < frame.data.size) frame.data[plane + base] else 0f) * 255).toInt().coerceIn(0, 255)
                val b = ((if (2 * plane + base < frame.data.size) frame.data[2 * plane + base] else 0f) * 255).toInt().coerceIn(0, 255)
                smallPixels[base] = PrismImage.argb(r, g, b)
            }
            val upscaled = PrismImage(decodeW, decodeH, smallPixels).scaledTo(upscale, upscale)

            val blended = IntArray(upscale * upscale)
            for (i in blended.indices) {
                val pr = PrismImage.red(persistent[i]); val ur = PrismImage.red(upscaled.pixels[i])
                val pg = PrismImage.green(persistent[i]); val ug = PrismImage.green(upscaled.pixels[i])
                val pb = PrismImage.blue(persistent[i]); val ub = PrismImage.blue(upscaled.pixels[i])
                val r = (pr * 0.75f + ur * 0.8f).toInt().coerceIn(0, 255)
                val g = (pg * 0.75f + ug * 0.8f).toInt().coerceIn(0, 255)
                val b = (pb * 0.75f + ub * 0.8f).toInt().coerceIn(0, 255)
                blended[i] = PrismImage.argb(r, g, b)
            }
            persistent = blended
            frames.add(PrismImage(upscale, upscale, persistent.copyOf()))
        }
        return frames
    }

    /**
     * SLOT RATE READER -- spike COUNT per character neuron within each slot, which is the code the
     * targets are written in (see [AetherTextCoding]) and the loss is scored on.
     *
     * The old per-timestep winner-take-all read a single BINARY frame, where every firing neuron
     * holds the identical value 1.0f. Scanning for `> maxRate` therefore returned the
     * LOWEST-INDEX firing neuron and the "decoded" character was an artifact of array order rather
     * than anything the network computed. Its burst fallback ranked neurons by overall mean rate
     * and emitted them in dominance order, which discards sequence entirely.
     */
    fun decodeToText(spikes: SpikeSequence): String {
        val steps = spikes.steps
        val n = spikes.c
        val printableLo = AetherTextCoding.ASCII_LOW
        val printableHi = min(AetherTextCoding.ASCII_HIGH, n)
        if (printableLo >= printableHi || steps <= 0) return ""

        val slotSteps = maxOf(1, AetherTextCoding.SLOT_STEPS)
        val slots = minOf(AetherTextCoding.slotCount(steps), steps / slotSteps)
        val sb = StringBuilder()

        for (s in 0 until maxOf(1, slots)) {
            var bestIdx = printableLo
            var bestCount = -1f
            for (i in printableLo until printableHi) {
                var count = 0f
                for (t in s * slotSteps until minOf(steps, (s + 1) * slotSteps)) count += spikes[t].data[i]
                if (count > bestCount) { bestCount = count; bestIdx = i }
            }
            val rate = bestCount / slotSteps
            // Below the floor there is no evidence for any character; emitting the argmax of noise
            // is what produced strings of random punctuation.
            sb.append(if (rate >= AetherTextCoding.SLOT_MIN_RATE) bestIdx.toChar() else ' ')
            if (sb.length >= 32) break
        }
        return sb.toString().trimEnd()
    }

    /** Mean firing rate over time per neuron, rescaled to [-1, 1] and tiled to fill one second, then written as a 16-bit PCM WAV. */
    fun decodeToAudio(spikes: SpikeSequence, sampleRate: Int = 44100): FloatArray {
        val n = spikes.c
        val meanRates = FloatArray(n)
        for (t in 0 until spikes.steps) { val f = spikes[t]; for (i in 0 until n) meanRates[i] += f.data[i] }
        for (i in 0 until n) meanRates[i] = (meanRates[i] / spikes.steps) * 2f - 1f

        if (n == 0) return FloatArray(sampleRate)
        val repeats = max(1, sampleRate / n)
        val wave = FloatArray(sampleRate)
        for (i in wave.indices) wave[i] = meanRates[(i / repeats).coerceIn(0, n - 1)]
        return wave
    }

    /** Self-contained 16-bit PCM mono WAV writer -- no platform audio API needed (Android has none for encoding raw PCM to a file). */
    fun writeWav(samples: FloatArray, sampleRate: Int, file: File) {
        val dataSize = samples.size * 2
        file.outputStream().buffered().use { out ->
            fun writeIntLE(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun writeShortLE(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(16); writeShortLE(1); writeShortLE(1)
            writeIntLE(sampleRate); writeIntLE(sampleRate * 2); writeShortLE(2); writeShortLE(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(dataSize)
            for (s in samples) {
                val clamped = (s.coerceIn(-1f, 1f) * 32767).toInt()
                writeShortLE(clamped)
            }
        }
    }
}
