package com.prism.launcher.writer

/**
 * The three words offered while a word is still being typed.
 *
 * ## Not the same problem autocorrect solves
 *
 * [Autocorrect] answers "this word is finished and looks wrong, what did they mean" — it compares
 * whole words. A suggestion strip answers "they are three letters in, where is this going", which
 * is prefix completion. Reusing the correction path here would offer replacements for a word the
 * user has not finished writing, and rank "cat" above "catalogue" while they are typing "cata".
 *
 * Both matter, so both are consulted: completions first, then near-misses for the case where the
 * prefix itself contains a typo and nothing completes it.
 */
object WriterSuggestions {

    data class Suggestion(val word: String, val confidence: Float)

    /**
     * Up to [limit] candidates for a partly typed word, most confident first.
     *
     * The caller decides where to put them; [arrangeForStrip] exists for the usual layout.
     */
    fun forPrefix(prefix: String, layout: KeyboardLayout, limit: Int = 3): List<Suggestion> {
        val typed = prefix.trim()
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()

        val seen = LinkedHashSet<String>()
        val out = ArrayList<Suggestion>(limit)

        // 1. The user's own words win outright. Somebody who added a name to a dictionary should
        //    see it before anything the shipped list offers, at any prefix length.
        for (dictionary in WriterUserDictionary.dictionaries()) {
            for (word in dictionary.words) {
                if (word.lowercase().startsWith(lower) && seen.add(word.lowercase())) {
                    out.add(Suggestion(Autocorrect.matchCase(typed, word), 1f))
                }
            }
        }

        // 2. A redefinition is what they will actually get if they finish the word, so offering
        //    anything else at that point would be a lie about what pressing space does.
        WriterUserDictionary.redefine(typed)?.let {
            if (seen.add(it.lowercase())) out.add(Suggestion(it, 0.99f))
        }

        // 3. Completions from the shipped list, ranked by how common the word is and how much of
        //    it is already typed -- a long prefix is far stronger evidence than a short one.
        val completions = WriterDictionary.allWords()
            .filter { it.startsWith(lower) && it != lower }
            .take(400)                       // bounded: the strip needs three, not a scan of 10k
            .map { word ->
                // INVERTED, BECAUSE score() IS A COST. It returns rank/size with rank 0 as the most
                // common word, so using it directly as a confidence ranks the RAREST match highest
                // -- which is how "hel" offered helmet, helicopter and helena instead of hello and
                // help. Subtracting from one turns the cost back into a confidence.
                val commonness = 1f - WriterDictionary.score(word)
                val covered = lower.length.toFloat() / word.length
                Suggestion(Autocorrect.matchCase(typed, word), commonness * 0.7f + covered * 0.3f)
            }
            .sortedByDescending { it.confidence }
            .toList()

        for (candidate in completions) {
            if (out.size >= limit + 1) break
            if (seen.add(candidate.word.lowercase())) out.add(candidate)
        }

        // 4. Only if completion found nothing: the prefix itself may be misspelled.
        if (out.isEmpty()) {
            for (near in Autocorrect.suggestions(typed, layout, limit)) {
                if (seen.add(near.word.lowercase())) out.add(Suggestion(near.word, 0.3f))
            }
        }

        // The word as typed is always worth keeping reachable, so a real word is never corrected
        // away just because something more common shares its prefix.
        if (WriterDictionary.contains(lower) || WriterUserDictionary.knowsWord(lower)) {
            if (seen.add(lower)) out.add(Suggestion(typed, 0.5f))
        }

        return out.sortedByDescending { it.confidence }.take(limit)
    }

    /**
     * Reorders [suggestions] so the most confident sits in the MIDDLE.
     *
     * A strip is read centre-first and the thumb rests there, so the best guess belongs in the
     * middle rather than at the left where a list would naturally put it. Second best goes left,
     * third right, which keeps the ordering predictable rather than merely symmetrical.
     */
    fun arrangeForStrip(suggestions: List<Suggestion>): List<Suggestion> = when (suggestions.size) {
        0, 1 -> suggestions
        2 -> listOf(suggestions[1], suggestions[0])
        else -> listOf(suggestions[1], suggestions[0], suggestions[2])
    }
}
