package com.prism.launcher.speech

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Jitter and shimmer, measured against a signal whose perturbation is KNOWN.
 *
 * ## Why this file exists
 *
 * Perturbation measures are the ones a reader is most likely to be misled by: any number in percent
 * looks like a measurement, and the previous implementation compared one 10 ms frame with the next --
 * which at 200 Hz folds five cycles into one figure and reports a smaller number for the same voice.
 * The replacement walks the individual cycles, so it needs a signal whose cycles are known: a pulse
 * train where every cycle's period and amplitude are set from a deterministic wobble, and nothing else
 * varies. Then "the jitter this reports" is a statement about the estimator rather than about the
 * signal.
 *
 * The clip built for the other tests is deliberately NOT used here: its 5 Hz syllable envelope moves
 * the amplitude by ten percent per cycle at 200 Hz, so it measures large shimmer for a reason that has
 * nothing to do with the estimator.
 */
class VocalPerturbationTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "prism-perturbation-${System.nanoTime()}")

    @Test
    fun `a steady pulse train reports almost no perturbation`() {
        val file = write("steady.wav", f0 = 200.0, periodWobblePercent = 0.0, amplitudeWobblePercent = 0.0)
        val features = measure(file)

        val jitter = features.value("jitter_local_percent")
        val shimmer = features.value("shimmer_local_percent")
        assertTrue(jitter.isFinite(), "jitter was not measured at all")
        assertTrue(shimmer.isFinite(), "shimmer was not measured at all")
        assertTrue(jitter < 0.6, "a perfectly steady train reported $jitter% jitter")
        assertTrue(shimmer < 1.5, "a perfectly steady train reported $shimmer% shimmer")
    }

    @Test
    fun `the wobble that was built in is the wobble that is reported`() {
        // Two percent period wobble and five percent amplitude wobble, both spread over seven and five
        // cycles so that neighbouring cycles differ by a known fraction of the wobble itself.
        val jittered = measure(
            write("jitter.wav", f0 = 200.0, periodWobblePercent = 2.0, amplitudeWobblePercent = 0.0),
        )
        val shimmered = measure(
            write("shimmer.wav", f0 = 200.0, periodWobblePercent = 0.0, amplitudeWobblePercent = 5.0),
        )

        val jitter = jittered.value("jitter_local_percent")
        assertTrue(
            jitter in 1.0..3.4,
            "a two percent period wobble was reported as $jitter% (expected the same order)",
        )
        val shimmer = shimmered.value("shimmer_local_percent")
        assertTrue(
            shimmer in 2.5..7.5,
            "a five percent amplitude wobble was reported as $shimmer% (expected the same order)",
        )

        // AND THE MEASURES ARE SEPARATE, which is what makes each of them worth having: a signal with
        // only amplitude variation must not report period variation, and the other way round.
        val jitterFromAmplitude = shimmered.value("jitter_local_percent")
        assertTrue(
            jitterFromAmplitude < 1.0,
            "amplitude wobble was reported as period jitter: $jitterFromAmplitude%",
        )
        val shimmerFromPeriod = jittered.value("shimmer_local_percent")
        assertTrue(
            shimmerFromPeriod < 3.0,
            "period wobble was reported as amplitude shimmer: $shimmerFromPeriod%",
        )
    }

    @Test
    fun `a breathy voice measures a larger open quotient proxy than a pressed one`() {
        // H1 - H2 is the acoustic open-quotient proxy, and the direction it moves is the whole point:
        // a glottal pulse that stays open longer is more SINUSOIDAL, so its second harmonic sits
        // further below the first and the difference in dB goes UP. A pulse that closes sharply is
        // rich in high harmonics, so H2 stays near H1 and the difference is SMALL. The two clips below
        // differ only in the second harmonic's level, which is that comparison made audible.
        //
        // The measured direction is the substance here, not the arithmetic: a proxy computed from the
        // waveform's own skewness would track pulse asymmetry instead and could not be checked against
        // the harmonic balance at all.
        val pressed = measure(write("pressed.wav", 200.0, 0.0, 0.0, secondHarmonicDb = -3.0))
        val breathy = measure(write("breathy.wav", 200.0, 0.0, 0.0, secondHarmonicDb = -14.0))

        val pressedValue = pressed.value("glottal_open_quotient_proxy")
        val breathyValue = breathy.value("glottal_open_quotient_proxy")
        assertTrue(pressedValue.isFinite() && breathyValue.isFinite(), "the proxy was not measured")
        assertTrue(
            breathyValue > pressedValue + 4.0,
            "an 11 dB weaker second harmonic did not raise the proxy: pressed=$pressedValue dB, " +
                "breathy=$breathyValue dB",
        )
        // And the proxy is what its column claims: a level difference in dB, positive for both of
        // these, not a fraction of anything.
        assertTrue(
            pressedValue > 0.0,
            "a source whose second harmonic is 3 dB below its first measured $pressedValue dB",
        )
    }

    private fun measure(file: File): VocalFeatures {
        val clip = AudioDecoding.read(file)
        assertTrue(clip != null, "the synthetic WAV could not be read back: ${file.name}")
        return VocalAnalysis.extract(file.name, clip!!)
    }

    /**
     * A pulse train with a known wobble: harmonics of [f0] shaped through a fixed formant envelope,
     * where cycle n's period is multiplied by `1 + periodWobble·sin(2π·n/7)` and its amplitude by
     * `1 + amplitudeWobble·sin(2π·n/5)`.
     */
    private fun write(
        name: String,
        f0: Double,
        periodWobblePercent: Double,
        amplitudeWobblePercent: Double,
        secondHarmonicDb: Double = -6.0,
        rate: Int = 16_000,
        seconds: Double = 1.6,
    ): File {
        val samples = ArrayList<Float>((seconds * rate).toInt())
        val cycles = (seconds * f0).toInt()
        for (cycle in 0 until cycles) {
            val wobble = 1.0 + periodWobblePercent / 100.0 * Math.sin(2.0 * Math.PI * cycle / 7.0)
            val amplitude = 1.0 + amplitudeWobblePercent / 100.0 * Math.sin(2.0 * Math.PI * cycle / 5.0)
            val period = rate / f0 * wobble
            val count = period.toInt().coerceAtLeast(4)
            for (n in 0 until count) {
                val phase = n / period
                var value = 0.0
                var harmonic = 1
                while (harmonic <= 10) {
                    val frequency = f0 * harmonic
                    if (frequency > rate / 2.0) break
                    val gain = when (harmonic) {
                        1 -> 1.0
                        2 -> Math.pow(10.0, secondHarmonicDb / 20.0)
                        else -> 0.7 / harmonic * Math.exp(-Math.pow((frequency - 900.0) / 700.0, 2.0))
                    }
                    // A pulse-shaped phase rather than a sine: every cycle starts at its own phase 0,
                    // which is what makes the cycles separable.
                    value += gain * Math.sin(2 * Math.PI * harmonic * phase) * Math.exp(-2.0 * phase)
                    harmonic++
                }
                samples += (value * amplitude * 6000.0).toFloat()
            }
        }
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeBytes(wav(samples, rate))
        return file
    }

    /** A PCM WAV around [samples], written the way the reader expects to find it. */
    private fun wav(samples: List<Float>, rate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) = out.write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
        val data = samples.size * 2
        ascii("RIFF"); int32(36 + data); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(data)
        samples.forEach { int16((it.coerceIn(-32_000f, 32_000f)).toInt()) }
        return out.toByteArray()
    }
}
