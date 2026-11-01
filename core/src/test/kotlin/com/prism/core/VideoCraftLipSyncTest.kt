package com.prism.launcher.videocraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mouths, driven by the dialogue.
 *
 * The failure this guards against is subtle and visible: a character standing perfectly still while
 * their line plays, or a jaw that twitches at the syllable rate. So the tests are about *when* the mouth
 * is open -- loud frames open, silent frames shut, and closing taking longer than opening -- and about
 * the pixels moving inside the figure rather than a slice being taken out of the frame.
 */
class VideoCraftLipSyncTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-lipsync-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    /** A tone at [amplitude] for [seconds], which is what a spoken vowel looks like to an RMS. */
    private fun tone(seconds: Double, amplitude: Double, rate: Int = VideoCraftAudio.SAMPLE_RATE): FloatArray {
        val samples = FloatArray((seconds * rate).toInt())
        for (i in samples.indices) {
            samples[i] = (sin(2.0 * Math.PI * 220.0 * i / rate) * amplitude).toFloat()
        }
        return samples
    }

    @Test
    fun `a loud moment opens the mouth and silence closes it`() {
        val track = VideoCraftLipSync.MouthTrack(tone(1.0, 0.4).also { java.util.Arrays.fill(it, 0, it.size / 2, 0f) }, 24_000)

        val during = track.opennessAt(0.75, previous = 0f)
        val before = track.opennessAt(0.2, previous = 0f)

        assertTrue(during > 0.9f, "a loud vowel should open the mouth fully, got $during")
        assertTrue(before < 0.05f, "silence before the line should leave the mouth shut, got $before")
    }

    @Test
    fun `closing is slower than opening`() {
        val samples = tone(1.0, 0.4).copyOf()
        java.util.Arrays.fill(samples, samples.size / 2, samples.size, 0f)
        val track = VideoCraftLipSync.MouthTrack(samples, 24_000)

        var openness = track.opennessAt(0.4, previous = 0f)
        assertTrue(openness > 0.9f, "expected the line to have opened the mouth, got $openness")
        // Sampled a tenth of a second into the silence rather than exactly on its edge: the loudness
        // window is 22 ms wide, so a sample taken AT the boundary still hears the tone the frame before
        // carries, which is honest behaviour of the window and not of the mouth.
        val oneFrameLater = track.opennessAt(0.6, previous = openness)
        assertTrue(oneFrameLater > 0.5f, "the jaw snapped shut in one frame: $oneFrameLater")
        var decaying = oneFrameLater
        repeat(7) { decaying = track.opennessAt(0.6, previous = decaying) }
        assertTrue(decaying < 0.15f, "the jaw never closed: $decaying")
        assertEquals(openness, openness.coerceIn(0f, 1f))
    }

    @Test
    fun `a character with no lines has no track to open`() {
        val empty = VideoCraftLipSync.MouthTrack(FloatArray(24_000), 24_000)
        assertTrue(!empty.hasSpeech())
        assertEquals(0f, empty.opennessAt(0.4, previous = 0f))
    }

    @Test
    fun `the jaw moves pixels inside the figure and nowhere else`() {
        val frame = PrismImage(120, 200, IntArray(120 * 200) { PrismImage.argb(90, 90, 90) })
        // A "face" band inside the box so the darkening of the mouth can be seen.
        for (y in 20 until 60) for (x in 40 until 80) frame.pixels[y * 120 + x] = PrismImage.argb(220, 200, 190)
        val placement = VideoCraftCharacterMatte.Placement(x = 0.5, y = 0.5, heightFraction = 0.6)
        val box = VideoCraftCharacterMatte.boxFor(frame.width, frame.height, 60, 120, placement)

        val closed = VideoCraftLipSync.applyJaw(frame, box, amount = 0f)
        val open = VideoCraftLipSync.applyJaw(frame, box, amount = 1f)

        // Closed is a no-op -- an idle mouth does not touch the frame at all, which is what keeps a film
        // with no dialogue at exactly the frames the model drew.
        assertEquals(frame.pixel(60, 40), closed.pixel(60, 40))

        var changed = 0
        var outside = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                if (open.pixel(x, y) == frame.pixel(x, y)) continue
                val inBox = x >= box.x && x < box.x + box.width && y >= box.y && y < box.y + box.height
                if (inBox) changed++ else outside++
            }
        }
        assertTrue(changed > 8, "the jaw moved nothing, changed $changed pixels")
        assertEquals(0, outside, "the jaw reached outside the figure's box")
    }

    @Test
    fun `the mouth opening is darker than the skin around it`() {
        val frame = PrismImage(120, 200, IntArray(120 * 200) { PrismImage.argb(200, 190, 180) })
        val placement = VideoCraftCharacterMatte.Placement(x = 0.5, y = 0.5, heightFraction = 0.6)
        val box = VideoCraftCharacterMatte.boxFor(frame.width, frame.height, 60, 120, placement)
        val open = VideoCraftLipSync.applyJaw(frame, box, amount = 1f)

        val mouthY = box.y + (box.height * 0.115).toInt()
        val centre = open.pixel(box.x + box.width / 2, mouthY + 1)
        val luma = (centre shr 16 and 0xFF) + (centre shr 8 and 0xFF) + (centre and 0xFF)
        assertTrue(luma < 480, "expected a dark mouth interior, got ${Integer.toHexString(centre)}")
        assertTrue(abs((open.pixel(2, 2) shr 16 and 0xFF) - 200) < 2, "the frame's corner changed")
    }
}
