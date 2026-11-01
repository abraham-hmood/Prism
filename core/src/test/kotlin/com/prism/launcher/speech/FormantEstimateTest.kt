package com.prism.launcher.speech

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Formants, measured against a signal whose resonances are KNOWN.
 *
 * ## Why a resonator cascade
 *
 * A vowel is a pulse train driven through the vocal tract, and the tract's resonances are two-pole
 * filters. Building one from filters with declared centre frequencies and bandwidths is therefore a
 * signal whose formants are not an opinion: they are the numbers the filters were given. Anything the
 * estimator reports can be compared with them, which is the only way to say whether a column is a
 * measurement or a shape.
 *
 * The vowel is built TWICE, once from a pulse train and once from white noise. The noise version is a
 * pure autoregressive process -- no harmonics at all -- so if the two disagree the estimator is looking
 * at the pitch rather than at the tract.
 *
 * ## What went wrong before, and what this pins
 *
 * Both failures this file exists for were invisible without a signal whose answer was known:
 *
 *  * Levinson-Durbin returns the PREDICTOR coefficients, so the poles are the roots of
 *    `1 - a1·z^-1 - … - ap·z^-p`. Read with the other sign, every pole landed at a radius of 0.85
 *    with a bandwidth near 1 kHz, and the three "formants" of this cascade came back 500/863/1196 Hz.
 *  * Taking the three LOWEST resonances let a broad source pole stand in for F3: it read 1671 Hz for
 *    a resonance at 2600. Ranking by bandwidth first and naming by frequency afterwards fixes it.
 */
class FormantEstimateTest {

    /** The cascade the tests measure: the three principal resonances of a vowel, in Hz and Hz of width. */
    private val resonances = listOf(700.0 to 90.0, 1200.0 to 120.0, 2600.0 to 180.0)

    @Test
    fun `a pulse-driven cascade is measured at its resonances`() {
        val features = measure(pulses = true, f0 = 165.0)
        assertNear(features, "formant_1_hz", 700.0, 0.10)
        assertNear(features, "formant_2_hz", 1200.0, 0.08)
        assertNear(features, "formant_3_hz", 2600.0, 0.08)
        assertNear(features, "formant_dispersion_hz", 500.0, 0.12)
    }

    @Test
    fun `the estimate does not depend on the excitation`() {
        val pulses = measure(pulses = true, f0 = 165.0)
        val noise = measure(pulses = false, f0 = 165.0)
        listOf("formant_1_hz", "formant_2_hz", "formant_3_hz").forEach { column ->
            val one = pulses.value(column)
            val other = noise.value(column)
            assertTrue(
                one.isFinite() && other.isFinite(),
                "$column was not measured for both excitations: pulses=$one noise=$other",
            )
            assertTrue(
                kotlin.math.abs(one - other) / other <= 0.08,
                "$column differed between a pulse train and noise: pulses=$one Hz, noise=$other Hz — " +
                    "the estimator is reading the pitch, not the tract",
            )
        }
    }

    @Test
    fun `the estimate does not depend on the pitch`() {
        val low = measure(pulses = true, f0 = 120.0)
        val high = measure(pulses = true, f0 = 260.0)
        val lowF1 = low.value("formant_1_hz")
        val highF1 = high.value("formant_1_hz")
        assertTrue(
            kotlin.math.abs(lowF1 - highF1) / highF1 <= 0.08,
            "F1 moved from {$lowF1} Hz at 120 Hz to {$highF1} Hz at 260 Hz",
        )
        assertTrue(
            high.value("formant_3_hz") > 2300.0,
            "F3 was read at ${high.value("formant_3_hz")} Hz for a 2600 Hz resonance, so a broad " +
                "source pole has displaced the real third formant",
        )
    }

    @Test
    fun `a different vowel is measured at its own resonances`() {
        // A SECOND cascade, so the first test's answer cannot come back as a constant. A front vowel:
        // F1 low, F2 high, F3 high. Three resonances, like every real vowel -- the estimator reports
        // three formants and a two-resonance cascade would make F3 the space between them.
        val features = measure(
            pulses = true,
            f0 = 150.0,
            cascade = listOf(400.0 to 80.0, 1900.0 to 110.0, 2800.0 to 200.0),
        )
        assertNear(features, "formant_1_hz", 400.0, 0.12)
        assertNear(features, "formant_2_hz", 1900.0, 0.10)
        assertNear(features, "formant_3_hz", 2800.0, 0.10)
        assertTrue(
            features.value("formant_dispersion_hz") > 1200.0,
            "the dispersion of a front vowel was ${features.value("formant_dispersion_hz")} Hz",
        )
    }

    @Test
    fun `formants are ordered and inside the band the columns promise`() {
        val features = measure(pulses = true, f0 = 165.0)
        val first = features.value("formant_1_hz")
        val second = features.value("formant_2_hz")
        val third = features.value("formant_3_hz")
        assertTrue(first < second && second < third, "formants were not ordered: $first < $second < $third")
        assertTrue(first >= 150.0 && third <= 3500.0, "formants outside 150-3500 Hz: $first .. $third")
    }

    private fun assertNear(features: VocalFeatures, column: String, expected: Double, tolerance: Double) {
        val value = features.value(column)
        assertTrue(value.isFinite(), "$column was not measured at all")
        assertTrue(
            kotlin.math.abs(value - expected) / expected <= tolerance,
            "$column read $value Hz for a resonance at $expected Hz (±${(tolerance * 100).toInt()}%)",
        )
    }

    private fun measure(
        pulses: Boolean,
        f0: Double,
        cascade: List<Pair<Double, Double>> = resonances,
    ): VocalFeatures {
        val root = File(System.getProperty("java.io.tmpdir"), "prism-formants-${System.nanoTime()}")
        root.mkdirs()
        try {
            val file = File(root, "vowel.wav")
            write(file, pulses, f0, cascade)
            val clip = AudioDecoding.read(file)
            assertTrue(clip != null, "the synthetic vowel could not be read back")
            return VocalAnalysis.extract("vowel.wav", clip!!)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A pulse train (or white noise) driven through [cascade].
     *
     * The excitation is an impulse train because a glottal pulse is short compared with a period; what
     * matters for the tract is that it is broadband, and an impulse is the broadest thing there is.
     */
    private fun write(
        file: File,
        pulses: Boolean,
        f0: Double,
        cascade: List<Pair<Double, Double>>,
        rate: Int = 16_000,
        seconds: Double = 2.0,
    ) {
        val count = (seconds * rate).toInt()
        var signal = if (pulses) {
            val train = DoubleArray(count)
            var phase = 0.0
            for (index in 0 until count) {
                phase += 1.0
                if (phase >= rate / f0) {
                    phase -= rate / f0
                    train[index] = 1.0
                }
            }
            train
        } else {
            val random = java.util.Random(7L)
            DoubleArray(count) { random.nextGaussian() }
        }
        for ((frequency, bandwidth) in cascade) signal = resonate(signal, frequency, bandwidth, rate)
        var peak = 0.0
        for (value in signal) peak = maxOf(peak, kotlin.math.abs(value))
        val scale = if (peak > 0.0) 12_000.0 / peak else 1.0
        file.parentFile?.mkdirs()
        file.writeBytes(wav(ShortArray(count) { index ->
            (signal[index] * scale).toInt().coerceIn(-32_000, 32_000).toShort()
        }, rate))
    }

    /** One two-pole resonator, the way a formant is defined: a radius of `exp(-pi·bw/rate)`. */
    private fun resonate(input: DoubleArray, frequency: Double, bandwidth: Double, rate: Int): DoubleArray {
        val radius = Math.exp(-Math.PI * bandwidth / rate)
        val angle = 2.0 * Math.PI * frequency / rate
        val first = 2.0 * radius * Math.cos(angle)
        val second = -radius * radius
        val out = DoubleArray(input.size)
        for (index in input.indices) {
            val previous = if (index >= 1) out[index - 1] else 0.0
            val before = if (index >= 2) out[index - 2] else 0.0
            out[index] = input[index] + first * previous + second * before
        }
        return out
    }

    private fun wav(samples: ShortArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) = out.write(
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()),
        )
        val data = samples.size * 2
        ascii("RIFF"); int32(36 + data); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(data)
        samples.forEach { int16(it.toInt()) }
        return out.toByteArray()
    }
}
