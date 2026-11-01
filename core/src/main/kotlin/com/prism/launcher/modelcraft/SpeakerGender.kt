package com.prism.launcher.modelcraft

import kotlin.math.ln

/**
 * Estimates a speaker's gender from measurements, without trusting a missing CSV column.
 *
 * The old rule was two hard pitch cuts: below 150 Hz male, above 170 Hz female, neutral in between.
 * That is a guess about a population, not a measurement of this voice. A low-tenor woman and a
 * high-tenor man both land in the gap, and every dataset without a gender column ended up with a
 * large neutral bucket that the voice picker could not filter.
 *
 * Pitch is still the strongest single cue, so it leads. Formants are the second: a shorter vocal
 * tract raises F1, F2 and F3 together, and those ratios survive a change of sentence better than a
 * frame-level pitch estimate. The two are combined into a score around zero; the edges are labelled,
 * and the overlap is left neutral rather than forced into a binary answer.
 */
object SpeakerGender {

    /**
     * `female`, `male` or `neutral`.
     *
     * Any argument may be null or non-finite. With no pitch there is no estimate to make, so the
     * answer is neutral: a formant ratio alone is too weak to label a person.
     */
    fun label(
        pitchHz: Double?,
        formant1Hz: Double? = null,
        formant2Hz: Double? = null,
        formant3Hz: Double? = null,
    ): String {
        if (pitchHz == null || !pitchHz.isFinite() || pitchHz <= 0.0) return "neutral"
        // ln(165) is the centre of the adult speaking range; the divisor is about one standard
        // deviation in log pitch, so 120 Hz is roughly -1 and 225 Hz roughly +1.
        val pitchScore = ln(pitchHz / 165.0) / 0.23
        val formantScore = formantScore(formant1Hz, formant2Hz, formant3Hz)
        val score = if (formantScore == null) pitchScore else pitchScore * 0.72 + formantScore * 0.28
        return when {
            // The edges are deliberately wider than the old 20 Hz gap: a wrong label is worse than
            // no label, because it changes which recording the engine selects for a voice.
            score >= 0.42 -> "female"
            score <= -0.42 -> "male"
            else -> "neutral"
        }
    }

    /**
     * A positive score means a shorter vocal tract than the reference speaker.
     *
     * The references are adult midpoints, not thresholds: the average of the three ratios is robust
     * to one formant being mis-tracked on a noisy clip.
     */
    private fun formantScore(f1: Double?, f2: Double?, f3: Double?): Double? {
        val ratios = buildList {
            f1?.takeIf { it.isFinite() && it > 0.0 }?.let { add(it / 520.0) }
            f2?.takeIf { it.isFinite() && it > 0.0 }?.let { add(it / 1_850.0) }
            f3?.takeIf { it.isFinite() && it > 0.0 }?.let { add(it / 2_900.0) }
        }
        if (ratios.isEmpty()) return null
        // A ratio of 1.10 is a 10% higher formant set, roughly a 10% shorter tract. Scaling by 5
        // puts that at +0.5, which is the edge of the labelled band when pitch agrees.
        return (ratios.average() - 1.0) * 5.0
    }
}
