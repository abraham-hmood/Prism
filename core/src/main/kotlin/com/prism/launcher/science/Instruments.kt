package com.prism.launcher.science

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The maths behind the instruments. PHASE 96.
 *
 * ## Why this file exists
 *
 * The phase said desktop is "genuinely the weaker platform" here, and that is true of the SENSORS --
 * a desktop may have no camera, and Wi-Fi scanning is a different system call on every OS. It is not
 * true of the analysis. A forced-exhale envelope is read the same way whatever recorded it, a
 * pure-tone ladder is the same procedure in a clinic and on a laptop, and a frame of dark-sensor
 * pixels is thresholded the same way regardless of which camera API produced it.
 *
 * So the analysis moved here and the capture stayed per-platform. Keeping two copies of an
 * audiometry ladder would have been two chances for the two platforms to disagree about what a
 * hearing threshold is, which is worse than almost any other kind of divergence on this list.
 */

// ── Spirometry ──────────────────────────────────────────────────────────────

/**
 * Reading a forced exhale off a microphone envelope.
 *
 * ## What this measures, and what it does not
 *
 * A microphone measures SOUND PRESSURE, not air flow. The two are related for a forced exhale into a
 * phone -- louder is faster -- but not by a calibrated constant, and no amount of maths here turns
 * one into litres per second. So every figure below is RELATIVE, and the one number that is
 * meaningful across devices is the RATIO: FEV1 over FVC is a quotient of two integrals of the same
 * uncalibrated signal, so the calibration cancels.
 *
 * That is why the ratio is the headline and the volumes are labelled "relative". A number in litres
 * would be a fabrication, and a plausible-looking fabrication about somebody's lungs is the worst
 * possible output for this instrument.
 */
object Spirometry {

    /** Envelope frames per second. Both platforms' capture is written to produce this. */
    const val FRAMES_PER_SECOND = 50

    /** Below this many frames there is not enough of a manoeuvre to read. */
    private const val MINIMUM_FRAMES = 50

    sealed interface Result {
        /** Not a usable recording, with the reason in the user's terms. */
        data class Unusable(val reason: String) : Result

        data class Reading(
            /** Forced expiratory volume in one second, in relative units. */
            val fev1: Double,
            /** Forced vital capacity, in the same relative units. */
            val fvc: Double,
            /** The quotient, which IS comparable across devices. See the class comment. */
            val ratio: Double,
            val peak: Double,
            val seconds: Double,
            /** The envelope from the start of the manoeuvre, for the curve. */
            val curve: List<Double>,
            val interpretation: String,
        ) : Result
    }

    fun analyse(envelope: List<Double>): Result {
        if (envelope.size < MINIMUM_FRAMES) {
            return Result.Unusable("That recording was too short to read.")
        }

        // The first five frames are the room, taken before anybody has started blowing.
        val noiseFloor = envelope.take(5).average().coerceAtLeast(1.0)
        val peak = envelope.max()
        if (peak < noiseFloor * 4) {
            return Result.Unusable(
                "Nothing loud enough to be a forced exhale. Blow harder, and closer.",
            )
        }

        // The manoeuvre starts where flow first passes a fifth of its peak, which is the
        // conventional back-extrapolation point and keeps the run-up out of the timing.
        val start = envelope.indexOfFirst { it > peak * 0.2 }.coerceAtLeast(0)
        val tail = envelope.drop(start)
        val oneSecond = min(FRAMES_PER_SECOND, tail.size)

        val fev1 = tail.take(oneSecond).sum()
        val fvc = tail.sum()
        val ratio = if (fvc > 0) fev1 / fvc else 0.0

        return Result.Reading(
            fev1 = fev1,
            fvc = fvc,
            ratio = ratio,
            peak = peak,
            seconds = tail.size / FRAMES_PER_SECOND.toDouble(),
            curve = tail,
            interpretation = interpret(ratio),
        )
    }

    /**
     * What a ratio means, hedged exactly as much as it deserves.
     *
     * The 0.70 cutoff is the one clinicians use, and it is quoted because quoting it is more honest
     * than inventing a gentler one -- but the sentence that follows it matters more than the number.
     * This is a microphone.
     */
    private fun interpret(ratio: Double): String = when {
        ratio >= 0.75 ->
            "A ratio in this range is what an unobstructed airway usually looks like."
        ratio >= 0.70 ->
            "Borderline. Repeat it — technique moves this number more than physiology does."
        else ->
            "Below 0.70 is the threshold clinicians treat as obstructive. This is a microphone, " +
                "not a spirometer: repeat it, and if it stays low, that is a reason to see " +
                "someone with a real one, not a diagnosis."
    }
}

// ── Audiometry ──────────────────────────────────────────────────────────────

/**
 * A pure-tone hearing test, as a state machine.
 *
 * ## The procedure is the Hughson-Westlake ladder, and it is not simplified
 *
 * Down in 10 dB steps while the tone is heard, up in 5 while it is not, and a threshold is recorded
 * at the quietest level heard on two of three ascending presentations. Both the asymmetric step
 * sizes and the two-of-three rule exist because a single descending run finds a level somebody
 * guessed at rather than one they heard -- which is the whole reason clinical audiometry is done this
 * way rather than by sliding a volume control down.
 *
 * ## WHAT THE NUMBERS ARE NOT
 *
 * dB HL. A clinical audiogram is referenced to a calibrated transducer at a known output; this is
 * attenuation below the device's own full scale through whatever headphones are plugged in. The SHAPE
 * of the curve across frequencies is informative -- a notch at 4 kHz is a notch at 4 kHz on any
 * hardware -- and the absolute values are not. Stated here because an audiogram-shaped chart invites
 * exactly the wrong reading.
 */
class Audiometry {

    companion object {
        /** The standard sweep, in the order a clinic runs it: mid first, then out either way. */
        val FREQUENCIES = listOf(1000, 2000, 4000, 8000, 500, 250)

        /** dB below full scale. 0 is as loud as the device goes. */
        const val LOUDEST = 0
        const val QUIETEST = 90
        const val STARTING_LEVEL = 40

        private const val DOWN_STEP = 10
        private const val UP_STEP = 5

        /** Heard at the same level this many times out of three ascents fixes the threshold. */
        private const val CONFIRMATIONS = 2
    }

    data class Presentation(val frequencyHz: Int, val attenuationDb: Int, val rightEar: Boolean)

    /** Attenuation at threshold, per frequency, per ear. Lower is better hearing. */
    val rightThresholds = LinkedHashMap<Int, Int>()
    val leftThresholds = LinkedHashMap<Int, Int>()

    private var frequencyIndex = 0
    private var rightEar = true
    private var level = STARTING_LEVEL
    private var ascending = false
    private val heardAt = HashMap<Int, Int>()

    var finished: Boolean = false
        private set

    /** What to play now, or null when the test is over. */
    fun current(): Presentation? {
        if (finished) return null
        val frequency = FREQUENCIES.getOrNull(frequencyIndex) ?: return null
        return Presentation(frequency, level, rightEar)
    }

    /**
     * Records an answer and advances.
     *
     * [heard] false includes "no response", which is the same thing for the ladder's purposes -- a
     * tone that was presented and not reported is a tone that was not heard.
     */
    fun respond(heard: Boolean) {
        val frequency = FREQUENCIES.getOrNull(frequencyIndex) ?: return

        if (heard) {
            if (ascending) {
                // An ascent that found the tone. Count it; two of these at the same level is a
                // threshold.
                val count = (heardAt[level] ?: 0) + 1
                heardAt[level] = count
                if (count >= CONFIRMATIONS) {
                    record(frequency, level)
                    nextFrequency()
                    return
                }
                // Back down below it and come up again, which is what makes the next ascent
                // independent of this one rather than a continuation.
                level = min(QUIETEST, level + DOWN_STEP)
                ascending = false
                return
            }
            // Descending phase: go quieter.
            val next = level + DOWN_STEP
            if (next > QUIETEST) {
                // Heard even at the quietest the device can present. Threshold is at or below the
                // floor, recorded as the floor rather than extrapolated.
                record(frequency, QUIETEST)
                nextFrequency()
                return
            }
            level = next
            return
        }

        // Not heard: come up in the smaller step. The asymmetry is the point of the ladder.
        ascending = true
        val next = level - UP_STEP
        if (next < LOUDEST) {
            // Not heard at full scale. No threshold is recorded rather than one invented at 0 --
            // "we could not find it" and "it is exactly 0" are different findings.
            nextFrequency()
            return
        }
        level = next
    }

    private fun record(frequency: Int, attenuation: Int) {
        if (rightEar) rightThresholds[frequency] = attenuation
        else leftThresholds[frequency] = attenuation
    }

    private fun nextFrequency() {
        heardAt.clear()
        level = STARTING_LEVEL
        ascending = false
        frequencyIndex++
        if (frequencyIndex >= FREQUENCIES.size) {
            if (rightEar) {
                // Second ear, same sweep.
                rightEar = false
                frequencyIndex = 0
            } else {
                finished = true
            }
        }
    }

    fun reset() {
        rightThresholds.clear()
        leftThresholds.clear()
        heardAt.clear()
        frequencyIndex = 0
        rightEar = true
        level = STARTING_LEVEL
        ascending = false
        finished = false
    }

    /**
     * One sentence about the result, about the SHAPE rather than the level.
     *
     * See the class comment for why the absolute numbers are not interpreted: they are attenuation
     * below this machine's full scale, and saying anything about them would be saying something
     * about the headphones.
     */
    fun summary(): String {
        val all = rightThresholds + leftThresholds
        if (all.isEmpty()) return "No thresholds were found."
        val high = listOf(4000, 8000).mapNotNull { all[it] }
        val low = listOf(250, 500, 1000).mapNotNull { all[it] }
        if (high.isEmpty() || low.isEmpty()) {
            return "Partial sweep: " + all.size + " threshold(s) found."
        }
        // A smaller attenuation at threshold means the tone had to be LOUDER to be heard.
        val highLoss = low.average() - high.average()
        val asymmetry = run {
            val r = rightThresholds.values.takeIf { it.isNotEmpty() }?.average()
            val l = leftThresholds.values.takeIf { it.isNotEmpty() }?.average()
            if (r == null || l == null) 0.0 else r - l
        }
        return buildString {
            when {
                highLoss > 15 -> append(
                    "The high frequencies needed noticeably more level than the low ones, which " +
                        "is the shape age and noise exposure both produce.",
                )
                highLoss < -15 -> append(
                    "The low frequencies needed more level than the high ones, which is the less " +
                        "common direction and worth repeating before reading anything into it.",
                )
                else -> append("The curve is fairly flat across the sweep.")
            }
            if (kotlin.math.abs(asymmetry) > 15) {
                append(
                    "  The two ears differ by more than 15 dB, which a real audiologist would " +
                        "want to look at rather than a laptop.",
                )
            }
            append(
                "  These are attenuations below this machine's own full scale, not dB HL — the " +
                    "shape means something, the numbers do not.",
            )
        }
    }
}

// ── Cosmic rays ─────────────────────────────────────────────────────────────

/**
 * Deciding whether a frame of a covered camera sensor contains a particle track.
 *
 * ## Three rules, each of which stops a specific false positive
 *
 *  1. HOT PIXELS ARE LEARNED AND EXCLUDED. A sensor has broken pixels that read bright every frame.
 *     Anything bright in more than a fifth of the calibration frames is a defect, not a particle --
 *     particles do not repeat at the same coordinate.
 *  2. A FRAME THAT IS NOT DARK IS DISCARDED. If the mean level is above the dark floor, light is
 *     reaching the sensor, and counting those bright pixels as hits is how this kind of detector
 *     produces spectacular and completely false results.
 *  3. A HIT IS A FEW BRIGHT PIXELS, NOT MANY. A genuine track is a handful of pixels; hundreds at
 *     once is a light leak, a thermal event or a camera gain change.
 *
 * All of it is arithmetic over a luminance plane, which is why it is here rather than beside either
 * platform's camera API.
 */
class CosmicDetector(
    /** Luma above which a pixel is a candidate. */
    private val threshold: Int = 60,
    /** Mean luma above which the frame is lit rather than dark. */
    private val darkFloor: Double = 8.0,
    /** More bright pixels than this is not a particle. */
    private val maxPixelsPerHit: Int = 24,
    /** Frames spent learning the sensor's own defects before counting anything. */
    private val calibrationFrames: Int = 60,
) {

    sealed interface Outcome {
        /** Still learning the sensor. [remaining] frames to go. */
        data class Calibrating(val remaining: Int) : Outcome

        /** Light is reaching the sensor, so the frame was discarded. */
        data class Lit(val meanLuma: Double) : Outcome

        /** A dark frame with nothing in it. */
        data object Quiet : Outcome

        /** A candidate particle. */
        data class Hit(val brightPixels: Int, val peak: Int) : Outcome
    }

    private val hotPixels = HashSet<Int>()
    private val brightCounts = HashMap<Int, Int>()
    private var calibrated = 0

    var framesAnalysed: Long = 0L
        private set
    var hits: Long = 0L
        private set
    var lastMeanLuma: Double = 0.0
        private set

    val hotPixelCount: Int get() = hotPixels.size
    val isCalibrating: Boolean get() = calibrated < calibrationFrames

    /**
     * Analyses one luminance plane.
     *
     * @param luma one byte per pixel, row-major, [rowStride] bytes per row. Both platforms' capture
     *   produces exactly this -- Android from `ImageReader`'s Y plane, a desktop from a greyscale
     *   conversion of whatever the webcam handed over.
     */
    fun analyse(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Outcome {
        var sum = 0L
        var brightest = 0
        var brightPixels = 0
        val candidates = ArrayList<Int>(8)

        for (y in 0 until height) {
            val base = y * rowStride
            if (base + width > luma.size) break
            for (x in 0 until width) {
                val value = luma[base + x].toInt() and 0xFF
                sum += value
                if (value < threshold) continue
                val index = y * width + x
                if (index in hotPixels) continue
                brightPixels++
                if (value > brightest) brightest = value
                if (candidates.size < 8) candidates.add(index)
            }
        }

        val pixels = max(1, width * height)
        lastMeanLuma = sum.toDouble() / pixels
        framesAnalysed++

        if (calibrated < calibrationFrames) {
            candidates.forEach { brightCounts[it] = (brightCounts[it] ?: 0) + 1 }
            calibrated++
            if (calibrated == calibrationFrames) {
                brightCounts.forEach { (index, count) ->
                    if (count > calibrationFrames / 5) hotPixels.add(index)
                }
                brightCounts.clear()
            }
            return Outcome.Calibrating(calibrationFrames - calibrated)
        }

        if (lastMeanLuma > darkFloor) return Outcome.Lit(lastMeanLuma)

        if (brightPixels in 1..maxPixelsPerHit) {
            hits++
            return Outcome.Hit(brightPixels, brightest)
        }
        return Outcome.Quiet
    }

    /**
     * What the count means, which at these rates is mostly "not enough yet".
     *
     * Honest about the statistics: sea-level muon flux through a sensor a few millimetres across is
     * on the order of one event a minute, so a five-minute run has single digits and any rate quoted
     * from it has an enormous relative error. Saying so is the difference between an instrument and
     * a toy that prints numbers.
     */
    fun verdict(coincidences: Int, elapsedSeconds: Double): String {
        if (isCalibrating) return "Still learning which pixels are simply broken."
        if (elapsedSeconds < 30) return "Too early to say anything. Leave it covered and running."
        val perMinute = hits / (elapsedSeconds / 60.0)
        return buildString {
            append(String.format("%.1f", perMinute)).append(" events a minute over ")
            append(String.format("%.0f", elapsedSeconds)).append(" s. ")
            when {
                hits < 5 -> append(
                    "With fewer than five events the rate means very little — the error bar is " +
                        "wider than the number.",
                )
                perMinute > 30 -> append(
                    "That is far above the background a sensor this size should see. Something is " +
                        "reaching it that is not a cosmic ray: check the cover.",
                )
                else -> append(
                    "That is the right order of magnitude for sea-level background through a " +
                        "sensor this size.",
                )
            }
            if (coincidences > 0) {
                append("  ").append(coincidences)
                append(
                    " of them coincided with another device on the mesh, which is the only " +
                        "evidence here that distinguishes a shower from sensor noise.",
                )
            }
        }
    }
}

// ── RF survey ───────────────────────────────────────────────────────────────

/**
 * One access point, as seen at one place and time.
 *
 * Kept in `:core` so that a survey walked with a phone and one walked with a laptop merge -- which is
 * the point of the mesh half of this instrument.
 */
data class RfSample(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val at: Long,
    /** Free text: where the user said they were standing. */
    val point: String,
)

/** Reading an RSSI and a channel, which is the same arithmetic on every platform. */
object RfSurvey {

    fun quality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "good"
        rssi >= -70 -> "fair"
        rssi >= -80 -> "weak"
        else -> "barely there"
    }

    fun band(mhz: Int): String = when {
        mhz in 2400..2500 -> "2.4 GHz"
        mhz in 4900..5900 -> "5 GHz"
        mhz in 5925..7125 -> "6 GHz"
        // Zero means the platform did not report a channel for this access point, which happens;
        // "0 MHz" would be a claim about the radio rather than an admission about the scan.
        mhz <= 0 -> "band unknown"
        else -> mhz.toString() + " MHz"
    }

    /**
     * The channel number for a frequency.
     *
     * Worth computing rather than displaying the megahertz: congestion is a per-channel question, and
     * "channel 6" is what anybody comparing two access points actually wants.
     */
    fun channel(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz in 5160..5885 -> (mhz - 5000) / 5
        mhz in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }

    /**
     * A very rough distance from the signal, by the log-distance path loss model.
     *
     * LABELLED AS ROUGH EVERYWHERE IT IS SHOWN, because the model assumes free space and a known
     * transmit power, and an indoor survey has neither -- a wall costs more than ten metres of air.
     * It is here because relative distances along a walk are useful even when the absolute ones are
     * not.
     */
    fun approximateMetres(rssi: Int, mhz: Int): Double {
        // -40 dBm at one metre is a reasonable indoor reference for consumer access points.
        val exponent = (-40.0 - rssi) / (10.0 * 2.7)
        val base = 10.0.pow(exponent)
        // Scaled by frequency, since a 5 GHz signal loses more over the same distance.
        val scale = if (mhz > 4000) 0.55 else 1.0
        return (base * scale).coerceIn(0.1, 500.0)
    }

    /** dBm to a 0..1 bar, the way every Wi-Fi indicator does it. */
    fun fraction(rssi: Int): Float =
        ((rssi + 100).coerceIn(0, 60) / 60f)

    /**
     * How crowded each channel is in a set of samples.
     *
     * Summed as linear power rather than averaged in dBm, because dBm is logarithmic and averaging
     * logarithms understates a channel with one strong neighbour -- which is exactly the case
     * somebody runs a survey to find.
     */
    fun congestionByChannel(samples: List<RfSample>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        samples.forEach { sample ->
            val channel = channel(sample.frequencyMhz) ?: return@forEach
            val milliwatts = 10.0.pow(sample.rssi / 10.0)
            out[channel] = (out[channel] ?: 0.0) + milliwatts
        }
        // Back to dBm for display, which is what anybody reading a survey expects to see.
        return out.mapValues { 10.0 * log10(it.value) }
    }
}
