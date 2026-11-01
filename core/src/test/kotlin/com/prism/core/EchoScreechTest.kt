package com.prism.core

import com.prism.launcher.minigames.EchoScreech
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The jumpscare's voice is generated, so it can be checked without a speaker: long enough to cover
 * the whole lunge, loud enough to be startling, and actually noisy rather than a clean tone.
 */
class EchoScreechTest {

    @Test
    fun `scream is long, loud and inharmonic`() {
        val samples = EchoScreech.samples()
        assertEquals((EchoScreech.SAMPLE_RATE * EchoScreech.SECONDS).toInt(), samples.size)

        var peak = 0
        var sum = 0.0
        var crossings = 0
        for (i in samples.indices) {
            val value = samples[i].toInt()
            peak = maxOf(peak, abs(value))
            sum += (value / 32768.0) * (value / 32768.0)
            if (i > 0 && (samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        val rms = sqrt(sum / samples.size)
        assertTrue(peak > 28_000, "the scream peaks at only $peak")
        assertTrue(rms > 0.10, "the scream is too quiet: RMS $rms")
        assertTrue(
            crossings > samples.size / 20,
            "only $crossings zero crossings; this reads as a tone, not a screech",
        )
        assertTrue(abs(samples.last().toInt()) < 8_000, "the scream does not release at the end")
    }

    @Test
    fun `the scream is deterministic`() {
        val first = EchoScreech.samples()
        val second = EchoScreech.samples()
        assertTrue(first.contentEquals(second), "the scare changes between runs")
    }
}
