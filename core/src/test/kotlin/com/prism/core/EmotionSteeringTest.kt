package com.prism.core

import com.prism.launcher.speech.EmotionSteering
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmotionSteeringTest {

    @Test
    fun `the emotion list covers the research categories and every one has a test line`() {
        val ids = EmotionSteering.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Cowen & Keltner's 27, plus the vocal-expression classics.
        listOf("admiration", "adoration", "aesthetic", "amusement", "anger", "anxiety", "awe", "awkwardness", "boredom",
            "calmness", "confusion", "craving", "disgust", "empathic", "entrancement", "excitement", "fear", "horror",
            "interest", "joy", "nostalgia", "relief", "romance", "sadness", "satisfaction", "desire", "surprise",
            "contempt", "pride", "shame", "guilt", "tenderness").forEach { assertTrue(it in ids, "missing $it") }
        EmotionSteering.ALL.forEach { e ->
            assertTrue(e.sample.startsWith("[${e.id}]"), "${e.id}'s test line must carry its indicator")
            assertEquals(e, EmotionSteering.segments(e.sample).single().first ?: EmotionSteering.find("neutral"))
        }
    }

    @Test
    fun `profiles follow the research`() {
        val anger = EmotionSteering.find("angry")!!.profile
        val sadness = EmotionSteering.find("sadly")!!.profile
        assertTrue(anger.pitch > 0 && anger.rate > 1 && anger.energy > 0 && anger.brightness > 1)
        assertTrue(sadness.pitch < 0 && sadness.rate < 1 && sadness.energy < 0 && sadness.range < 1)
        // Derived profiles follow arousal: excitement is livelier than calm.
        assertTrue(EmotionSteering.find("excited")!!.profile.rate > EmotionSteering.find("calm")!!.profile.rate)
    }

    @Test
    fun `indicators are found, carried and stripped`() {
        val parts = EmotionSteering.segments("Hello there. [angry] You broke it! (sadly) Now it is gone.")
        assertEquals(listOf(null, "anger", "sadness"), parts.map { it.first?.id })
        assertEquals("You broke it!", parts[1].second)
        assertEquals("Hello there. You broke it! Now it is gone.", EmotionSteering.strip("Hello there. [angry] You broke it! (sadly) Now it is gone."))
        // Brackets that name no emotion are left alone.
        assertEquals("See figure (3) and [citation].", EmotionSteering.strip("See figure (3) and [citation]."))
        assertNotNull(EmotionSteering.find("in an excited voice".removePrefix("in an ").removeSuffix(" voice")))
        assertNull(EmotionSteering.find("banana"))
    }

    @Test
    fun `pitch range pace loudness and brightness are measured from audio`() {
        val rate = 24_000
        fun tone(hz: Double, seconds: Double, amp: Double, vibrato: Double = 0.0) = FloatArray((rate * seconds).toInt()) { i ->
            val t = i.toDouble() / rate
            val f = hz * (1 + vibrato * sin(2 * PI * 3 * t))
            (amp * sin(2 * PI * f * t) + 0.3 * amp * sin(4 * PI * f * t)).toFloat()
        }
        val low = assertNotNull(EmotionSteering.measure(tone(150.0, 1.0, 0.2), rate))
        val high = assertNotNull(EmotionSteering.measure(tone(300.0, 0.6, 0.5, vibrato = 0.08), rate))
        assertTrue(abs((high.pitch - low.pitch) - 12.0) < 1.5, "an octave up is 12 semitones: ${high.pitch - low.pitch}")
        assertTrue(high.range > low.range, "vibrato widens the measured range")
        assertTrue(high.seconds < low.seconds && high.energy > low.energy + 6)
        assertTrue(abs(low.pitch - 12 * log2(150.0 / 55.0)) < 1.0)
    }

    private fun voiceLike(rate: Int, seconds: Double, hz: Double, amp: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { i ->
            val t = i.toDouble() / rate
            // Two syllables with a gap between them, so there is a pause to breathe in.
            val on = if ((t % 0.9) < 0.6) 1.0 else 0.0
            (on * amp * (sin(2 * PI * hz * t) + 0.4 * sin(4 * PI * hz * t) + 0.2 * sin(6 * PI * hz * t))).toFloat()
        }

    @Test
    fun `anger and rage are separate, and rage yells`() {
        val anger = EmotionSteering.find("anger")!!
        val rage = EmotionSteering.find("rage")!!
        assertTrue(anger.id != rage.id && !anger.yell && rage.yell)
        assertEquals(rage, EmotionSteering.find("furious"))
        assertEquals(rage, EmotionSteering.find("yelling"))
        assertTrue(rage.profile.energy > anger.profile.energy + 3 && rage.profile.pitch > anger.profile.pitch)

        // Applied to audio, rage is louder, higher and brighter than the same line said plainly.
        val rate = 24_000
        val plain = voiceLike(rate, 1.8, 160.0, 0.1)
        val yelled = EmotionSteering.acoustic(plain, rate, rage)
        val before = assertNotNull(EmotionSteering.measure(plain, rate))
        val after = assertNotNull(EmotionSteering.measure(yelled, rate))
        assertTrue(after.energy > before.energy + 6, "rage must be much louder: ${after.energy - before.energy} dB")
        assertTrue(after.pitch > before.pitch + 3, "and higher: ${after.pitch - before.pitch} semitones")
        assertTrue(after.brightness > before.brightness)
        assertTrue(yelled.all { abs(it) <= 1f }, "shouting saturates, it never clips past full scale")
    }

    @Test
    fun `the spectral term tells damage apart from a change of pitch`() {
        val rate = 24_000
        val voice = voiceLike(rate, 1.0, 160.0, 0.2)
        val higher = voiceLike(rate, 1.0, 175.0, 0.2)
        val rng = java.util.Random(3)
        val noisy = FloatArray(voice.size) { voice[it] + (rng.nextGaussian() * 0.08).toFloat() }
        val reference = com.prism.launcher.speech.Spectra.bands(voice)
        val same = com.prism.launcher.speech.Spectra.distance(com.prism.launcher.speech.Spectra.bands(voice), reference)
        val pitched = com.prism.launcher.speech.Spectra.distance(com.prism.launcher.speech.Spectra.bands(higher), reference)
        val damaged = com.prism.launcher.speech.Spectra.distance(com.prism.launcher.speech.Spectra.bands(noisy), reference)
        assertTrue(same < 1e-6)
        assertTrue(damaged > pitched, "noise ($damaged) must cost more than a small pitch change ($pitched)")
    }

    @Test
    fun `breaths fill pauses without adding time`() {
        val rate = 24_000
        val speech = voiceLike(rate, 2.7, 150.0, 0.15)
        val breathed = com.prism.launcher.speech.Breath.fillPauses(speech, rate)
        assertEquals(speech.size, breathed.size)
        // Something was added inside a pause (0.6-0.9 s), and it is quiet next to the speech.
        val pause = (rate * 0.62).toInt() until (rate * 0.88).toInt()
        val added = pause.maxOf { abs(breathed[it] - speech[it]) }
        assertTrue(added > 0.002f && added < 0.1f, "breath peak $added")
        val inhale = com.prism.launcher.speech.Breath.inhale(rate, 300, 0.02f)
        assertEquals(rate * 300 / 1000, inhale.size)
        assertTrue(abs(inhale.maxOf { abs(it) } - 0.02f) < 1e-4f)
    }

    @Test
    fun `both searches find the minimum of a known function`() {
        val target = doubleArrayOf(0.8, -1.2, 0.5, 0.1)
        fun f(x: DoubleArray) = x.indices.sumOf { (x[it] - target[it]) * (x[it] - target[it]) }
        val swarm = EmotionSteering.pso(4, ::f, particles = 12, iterations = 25)
        assertTrue(f(swarm) < 0.05, "swarm ended at ${swarm.toList()}")
        val descent = EmotionSteering.spsa(DoubleArray(4), 60, ::f)
        assertTrue(f(descent) < f(DoubleArray(4)) * 0.2, "descent ended at ${descent.toList()}")
    }
}
