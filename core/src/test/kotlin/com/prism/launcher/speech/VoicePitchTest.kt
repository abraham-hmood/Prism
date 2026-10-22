package com.prism.launcher.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VoicePitchTest {

    private val rate = VoiceCloner.WORK_RATE

    private fun sine(hz: Double, seconds: Double = 1.0): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (0.5 * sin(2.0 * PI * hz * i / rate)).toFloat() }
    }

    /**
     * Frequency from zero crossings -- a ground truth the pitch DETECTOR cannot distort, so
     * output assertions do not inherit its octave/bias quirks. Valid for tonal signals.
     */
    private fun zcrHz(x: FloatArray): Float {
        var crossings = 0
        for (i in 1 until x.size) if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
        return crossings / 2f / (x.size.toFloat() / rate)
    }

    @Test
    fun medianPitchFindsASinesFrequency() {
        val f0 = VoicePitch.medianPitch(sine(220.0), rate)
        assertTrue(abs(f0 - 220f) < 0.06f * 220f, "medianPitch=$f0 want ~220")
    }

    @Test
    fun matchMovesThePitchTowardTheSpeaker() {
        val source = sine(130.0)
        val matched = VoicePitch.match(source, rate, 176f)
        assertNotSame(source, matched, "ratio ~1.28 is a real difference and should shift")
        val got = zcrHz(matched)
        assertTrue(abs(got - 176f) < 0.15f * 176f, "matched F0=$got want ~176")
        assertTrue(abs(got - 176f) < abs(zcrHz(source) - 176f), "must be closer to the target")
    }

    @Test
    fun matchLeavesAGoodEnoughMatchAlone() {
        val source = sine(220.0)
        val matched = VoicePitch.match(source, rate, 224f) // ratio ~1.02, inside the skip window
        assertSame(source, matched)
    }

    @Test
    fun matchClampsImplausibleTargets() {
        val source = sine(200.0)
        // 40 Hz would be a different character, not a match: clamped to 0.7x = ~140 Hz.
        val matched = VoicePitch.match(source, rate, 40f)
        val got = zcrHz(matched)
        assertTrue(abs(got - 140f) < 0.15f * 140f, "matched F0=$got want ~140 (clamped)")
    }

    @Test
    fun shiftKeepsDurationAndScalesPitch() {
        val source = sine(100.0, seconds = 1.5)
        val shifted = VoicePitch.shift(source, rate, 1.5f)
        assertTrue(shifted.size == source.size, "duration must not move")
        val got = zcrHz(shifted)
        assertTrue(abs(got - 150f) < 0.10f * 150f, "shifted F0=$got want ~150")
        assertTrue(zcrHz(shifted) > zcrHz(source) * 1.3f, "1.5x must audibly raise the pitch")
    }

    @Test
    fun matchIgnoresUnmeasurableAudio() {
        val noise = FloatArray(rate) { ((it * 7919 % 1000) / 1000f - 0.5f) * 1e-4f }
        assertSame(noise, VoicePitch.match(noise, rate, 180f))
    }

    @Test
    fun resampleChangesLengthWithTheRate() {
        val input = FloatArray(22_050) { it.toFloat() / 22_050f }
        val out = VoicePitch.resample(input, 22_050, 16_000)
        assertTrue(out.size == 16_000, "got ${out.size} want 16000")
        assertTrue(abs(out[8_000] - 8_000f / 16_000f) < 1e-3f)
    }
}
