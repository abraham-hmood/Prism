package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mouths that move when their character speaks.
 *
 * ## What this is, and what it is not
 *
 * It is **amplitude-driven jaw motion**: the dialogue each character actually speaks is measured frame
 * by frame, and the chin of that character's figure is opened and closed by how loud they are at that
 * instant. It is *not* phoneme lip sync. Phoneme sync means a viseme model, forced alignment against the
 * transcript, and a face with enough resolution to show an "oo" -- three models this app does not have,
 * and on a 432-pixel-tall generated figure the difference between a jaw and a viseme is under two
 * pixels. What it fixes is the thing that reads as wrong: a character standing perfectly still while
 * their line plays, and a mouth that is open during a pause.
 *
 * ## The two halves
 *
 * [MouthTrack] turns one character's synthesized lines into a per-instant openness, and [applyJaw] moves
 * the pixels. The track is built from the same `SpeechClip`s the soundtrack is mixed from -- literally
 * the same objects, because the engine memoizes the speech engine for the length of a render -- so the
 * mouth is driven by the audio that plays, not by a re-synthesis of it that could differ.
 *
 * ## Why the jaw, and not the whole face
 *
 * A jaw drop is a vertical displacement of everything below the mouth line, by a few percent of the
 * figure's height, with the interior of the mouth darkened where the jaw left a gap. That is cheap
 * (one box, no model), it survives being interpolated between keyframes, and a viewer reads all three of
 * close / half-open / open from it. A head bob is deliberately not included: it would move the
 * silhouette, and the silhouette is what the keyframes were matched on.
 */
object VideoCraftLipSync {

    /**
     * One character's dialogue as a function of time.
     *
     * The samples are the character's own lines, placed at the times the soundtrack will place them, so
     * the mouth and the sound cannot drift apart -- they are indexed by the same clock.
     */
    class MouthTrack(
        private val samples: FloatArray,
        private val sampleRate: Int,
    ) {

        /** Openness at [seconds], from 0 (shut) to 1 (wide), carrying [previous] forward for release. */
        fun opennessAt(seconds: Double, previous: Float): Float {
            val raw = loudness(seconds)
            // FAST TO OPEN, SLOW TO CLOSE. A mouth that follows the waveform in both directions
            // chatters at the syllable rate and reads as a twitch; letting it close over a few frames
            // is what makes it look like a jaw with mass. 0.72 per frame at 24 fps is a close in about
            // five frames, and the opening is immediate because consonants start the movement.
            val smoothed = max(raw, previous * RELEASE)
            return smoothed.coerceIn(0f, 1f)
        }

        private fun loudness(seconds: Double): Float {
            if (samples.isEmpty()) return 0f
            val centre = (seconds * sampleRate).toInt()
            val window = max(1, (WINDOW_SECONDS * sampleRate).toInt())
            val from = (centre - window).coerceIn(0, samples.size - 1)
            val to = (centre + window).coerceIn(0, samples.size)
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            val rms = sqrt(sum / (to - from))
            // A FLOOR AND A CEILING, because speech RMS is never near zero and never near one. The floor
            // is the room between words; the ceiling is a stressed vowel. Anything below the floor is a
            // shut mouth rather than a tiny movement.
            val normalized = (rms - FLOOR) / (CEILING - FLOOR)
            return normalized.coerceIn(0.0, 1.0).toFloat()
        }

        /** True when this character has any lines at all. */
        fun hasSpeech(): Boolean = samples.any { abs(it) > 1e-4f }

        companion object {
            private const val WINDOW_SECONDS = 0.022
            private const val FLOOR = 0.02
            private const val CEILING = 0.22
            private const val RELEASE = 0.72f
        }
    }

    /**
     * A track that never speaks, for a call site that has to hand one over.
     *
     * A character with no lines gets this rather than a null check at every frame: "does not speak" is
     * the same answer as "is silent now", and the frame loop stays a straight line.
     */
    val silent = MouthTrack(FloatArray(0), VideoCraftAudio.SAMPLE_RATE)

    /**
     * Opens [box]'s jaw by [amount], in the frame [frame].
     *
     * The head is the top fifth of a standing figure, the mouth line sits just below the middle of the
     * head, and everything below it is displaced downward by up to [MAX_OPEN] of the figure's height.
     * The rows the jaw vacates are filled with the mouth's interior -- the mouth line's own pixels,
     * darkened -- because an open mouth shows something dark, not the scene behind the character.
     *
     * The displacement is weighted by a smooth falloff away from the mouth's centre in x and downwards
     * from the mouth line, so the movement is inside the face instead of being a slice through the whole
     * figure. Nothing outside [box] is touched.
     */
    fun applyJaw(frame: PrismImage, box: VideoCraftCharacterMatte.FigureBox, amount: Float): PrismImage {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0.01f || box.width < 6 || box.height < 12) return frame
        val maxOpen = max(1.0, box.height * MAX_OPEN).toDouble()
        val displacement = strength * maxOpen

        val mouthY = box.y + (box.height * MOUTH_LINE).toInt()
        val chinY = box.y + (box.height * CHIN_LINE).toInt()
        val centreX = box.x + box.width / 2.0
        val halfWidth = box.width * 0.34

        val out = frame.pixels.copyOf()
        val top = max(box.y, mouthY)
        val bottom = min(frame.height - 1, chinY + maxOpen.toInt() + 1)
        for (y in top..bottom) {
            // How much of the displacement reaches this row: full at the mouth, none by the chin.
            val depth = ((y - mouthY).toDouble() / max(1.0, (chinY - mouthY).toDouble())).coerceIn(0.0, 1.0)
            val rowAmount = displacement * (1.0 - depth * 0.35)
            if (rowAmount < 0.35) continue
            for (x in max(0, box.x)..min(frame.width - 1, box.x + box.width - 1)) {
                val sideways = ((x - centreX) / halfWidth)
                val weight = (1.0 - sideways * sideways).coerceIn(0.0, 1.0)
                val move = rowAmount * weight
                if (move < 0.35) continue
                val source = y - move
                val index = y * frame.width + x
                out[index] = if (source <= mouthY) {
                    // Inside the opening: the mouth's own interior, darkened, so the gap reads as an open
                    // mouth rather than as stretched skin.
                    darken(sample(frame, x, mouthY.toDouble()), 0.45)
                } else {
                    sample(frame, x, source)
                }
            }
        }
        return PrismImage(frame.width, frame.height, out)
    }

    /** Bilinear sample down a column, clamped to the frame. */
    private fun sample(frame: PrismImage, x: Int, y: Double): Int {
        val y0 = floor(y).toInt().coerceIn(0, frame.height - 1)
        val y1 = min(frame.height - 1, y0 + 1)
        val t = (y - y0).coerceIn(0.0, 1.0)
        val a = frame.pixels[y0 * frame.width + x]
        val b = frame.pixels[y1 * frame.width + x]
        fun channel(shift: Int): Int {
            val va = (a shr shift) and 0xFF
            val vb = (b shr shift) and 0xFF
            return (va + (vb - va) * t).toInt().coerceIn(0, 255)
        }
        return PrismImage.argb(channel(16), channel(8), channel(0))
    }

    private fun darken(pixel: Int, factor: Double): Int = PrismImage.argb(
        (((pixel shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255),
        (((pixel shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255),
        ((pixel and 0xFF) * factor).toInt().coerceIn(0, 255),
    )

    /** Where the mouth sits in a standing figure, and how far it can open, in fractions of its height. */
    private const val MOUTH_LINE = 0.115
    private const val CHIN_LINE = 0.20
    private const val MAX_OPEN = 0.035
}
