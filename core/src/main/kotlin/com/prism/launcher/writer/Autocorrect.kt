package com.prism.launcher.writer

import kotlin.math.min

/**
 * Corrects a typed word, and knows when not to.
 *
 * ## Edit distance weighted by the keyboard
 *
 * Plain Levenshtein treats every substitution as equally likely, which is wrong on a touchscreen:
 * `s` for `a` is a thumb landing a few millimetres off, while `p` for `a` is a different word. So a
 * substitution costs in proportion to how far apart the two keys actually sit, which lets the
 * obvious slips be corrected confidently without dragging distant words into range.
 *
 * ## Refusing to correct is a feature
 *
 * The failure everyone hates is a keyboard that "fixes" a word they meant. Three rules prevent it:
 * a word already in the dictionary is never touched, a correction must beat the typed word by a
 * clear margin rather than a hair, and nothing is corrected below a length where the alternatives
 * are all equally plausible. When in doubt this leaves the text alone.
 */
object Autocorrect {

    /** Words this short have too many near neighbours to correct safely. */
    private const val MIN_LENGTH = 3

    /** A candidate must be at least this much better than what was typed to replace it. */
    private const val REQUIRED_MARGIN = 0.35f

    /** Beyond this, it is a different word rather than a typo. */
    private const val MAX_DISTANCE = 2.6f

    data class Suggestion(val word: String, val cost: Float)

    /**
     * The best correction, or null to leave the word alone.
     *
     * Null is the common and correct answer.
     */
    fun correct(typed: String, layout: KeyboardLayout): String? {
        val word = typed.lowercase()
        if (word.length < MIN_LENGTH) return null
        if (!word.all { it in 'a'..'z' }) return null
        // Already a word. Somebody who typed it meant it.
        if (WriterDictionary.contains(word)) return null

        val best = suggestions(word, layout, limit = 1).firstOrNull() ?: return null
        if (best.cost > MAX_DISTANCE) return null
        if (best.cost > REQUIRED_MARGIN * word.length) return null

        return matchCase(typed, best.word)
    }

    /**
     * Ranked alternatives for a word, best first.
     *
     * Length is filtered before any distance is computed: a word cannot be more than two edits from
     * something two letters longer, and skipping the rest keeps this fast enough to run on every
     * keystroke.
     */
    fun suggestions(typed: String, layout: KeyboardLayout, limit: Int = 3): List<Suggestion> {
        val word = typed.lowercase()
        if (word.isEmpty()) return emptyList()

        val out = ArrayList<Suggestion>()
        for (length in (word.length - 2)..(word.length + 2)) {
            if (length < 1) continue
            for (candidate in WriterDictionary.wordsOfLength(length)) {
                val distance = weightedDistance(word, candidate, layout)
                if (distance > MAX_DISTANCE) continue
                // Frequency as a tiebreak, never as the main term: a common word should not
                // outrank a much closer rare one.
                out.add(Suggestion(candidate, distance + WriterDictionary.score(candidate) * 0.4f))
            }
        }
        return out.sortedBy { it.cost }.take(limit)
    }

    /**
     * Damerau-Levenshtein, with substitution priced by key distance.
     *
     * Transpositions are included because they are the second most common touch error after a
     * neighbouring key -- "teh" for "the" is one swap, and treating it as two edits would push the
     * right answer out of range.
     */
    fun weightedDistance(a: String, b: String, layout: KeyboardLayout): Float {
        if (a == b) return 0f
        if (a.isEmpty()) return b.length.toFloat()
        if (b.isEmpty()) return a.length.toFloat()

        val previous2 = FloatArray(b.length + 1)
        val previous = FloatArray(b.length + 1)
        val current = FloatArray(b.length + 1)

        for (j in 0..b.length) previous[j] = j.toFloat()

        for (i in 1..a.length) {
            current[0] = i.toFloat()
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0f
                    else 0.55f + layout.letterDistance(a[i - 1], b[j - 1]) * 1.6f

                var cost = min(
                    min(previous[j] + 1f, current[j - 1] + 1f),
                    previous[j - 1] + substitution,
                )

                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = min(cost, previous2[j - 2] + 0.7f)
                }
                current[j] = cost
            }
            System.arraycopy(previous, 0, previous2, 0, previous.size)
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return previous[b.length]
    }

    /** Carries the typed word's capitalisation onto the correction. */
    fun matchCase(typed: String, replacement: String): String = when {
        typed.all { it.isUpperCase() } && typed.length > 1 -> replacement.uppercase()
        typed.firstOrNull()?.isUpperCase() == true ->
            replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }
}
