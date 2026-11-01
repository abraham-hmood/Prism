package com.prism.core

import com.prism.launcher.speech.VoicePolish
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class VoicePolishTest {

    private fun voice(rate: Int, seconds: Double = 2.0, f0: Double = 150.0): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            var k = 1
            while (f0 * k < minOf(rate / 2.0, 7_000.0)) {
                v += sin(2.0 * PI * f0 * k * t) / k
                k++
            }
            (0.15 * v).toFloat()
        }
    }

    /**
     * First-difference energy over total energy. The difference filter tilts the spectrum up, so
     * a brighter signal always scores higher; unlike a hand-rolled Goertzel sweep this is stable
     * over a long clip and needs no FFT.
     */
    private fun highTilt(samples: FloatArray): Double {
        var diff = 0.0
        var total = 0.0
        for (i in 1 until samples.size) {
            val d = samples[i] - samples[i - 1]
            diff += d.toDouble() * d
            total += samples[i].toDouble() * samples[i]
        }
        return if (total <= 0.0) 0.0 else sqrt(diff / total)
    }

    @Test
    fun `conditioning restores high band and respects the ceiling`() {
        val rate = 22_050
        val input = voice(rate)
        val output = VoicePolish.conditionReference(input, rate)

        assertTrue(output.isNotEmpty())
        assertTrue(output.all { it.isFinite() })
        assertTrue(output.maxOf { kotlin.math.abs(it) } <= 0.9f)
        val before = highTilt(input)
        val after = highTilt(output)
        assertTrue(
            after > before * 1.3,
            "conditioning must add high-band energy (before $before, after $after)",
        )
        assertTrue(output.size > input.size * 0.8, "trimming must not eat the take")
    }

    @Test
    fun `mastering leaves a loud, unclipped take`() {
        val rate = 24_000
        val quiet = FloatArray(rate) { i ->
            (0.03 * sin(2.0 * PI * 180.0 * i / rate)).toFloat()
        }
        val output = VoicePolish.master(quiet, rate)
        val peak = output.maxOf { kotlin.math.abs(it) }
        val rms = sqrt(output.map { it.toDouble() * it }.average())

        assertTrue(output.all { it.isFinite() })
        assertTrue(peak <= 0.9f, "peak $peak")
        assertTrue(rms > 0.05, "mastered speech should not stay quiet (rms $rms)")
    }

    @Test
    fun `silence stays silent`() {
        val rate = 22_050
        val silence = FloatArray(rate)
        assertTrue(VoicePolish.conditionReference(silence, rate).all { it == 0f })
        assertTrue(VoicePolish.master(silence, rate).all { it == 0f })
    }
}
