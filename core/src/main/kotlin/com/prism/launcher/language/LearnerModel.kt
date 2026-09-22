package com.prism.launcher.language

/**
 * What the learner knows, item by item.
 *
 * ## This is the product
 *
 * Everything else in the language feature is downstream of this object. It decides which words go
 * into the next lesson, which come back for review, what the tutor is allowed to say, and what
 * "you are B1" means. A conversation app without one is a chatbot with a nice voice: it cannot
 * introduce a word deliberately, cannot know whether the learner has met it, and cannot bring it
 * back at the moment it is about to be forgotten.
 *
 * ## Item ids
 *
 * Namespaced by kind so vocabulary and grammar share one store without colliding: `w:apple` is the
 * concept, `g:es_subjunctive` is the pattern. Anything else the app wants to schedule later — a
 * character, a phrase, a sound contrast — gets its own prefix and nothing here changes.
 *
 * ## Why "known" is a threshold and not a flag
 *
 * There is no moment at which a word becomes known. There is a probability of recall that rises
 * with practice and falls with time, and a line drawn across it. Drawing the line explicitly, in
 * one place, means the vocabulary gate and the progress display cannot disagree about what the
 * learner knows — which they would the moment either of them kept its own boolean.
 */
data class LearnerModel(
    val items: Map<String, Fsrs.State> = emptyMap(),
) {

    fun state(itemId: String): Fsrs.State = items[itemId] ?: Fsrs.State()

    fun seen(itemId: String): Boolean = items[itemId]?.isNew == false

    /** Folds one graded answer in and returns the updated model. */
    fun record(itemId: String, rating: Fsrs.Rating, now: Long): LearnerModel =
        copy(items = items + (itemId to Fsrs.review(state(itemId), rating, now)))

    fun recordAll(ratings: Map<String, Fsrs.Rating>, now: Long): LearnerModel {
        if (ratings.isEmpty()) return this
        val next = items.toMutableMap()
        ratings.forEach { (id, rating) -> next[id] = Fsrs.review(state(id), rating, now) }
        return copy(items = next)
    }

    /**
     * Items the learner can be assumed to have available while speaking.
     *
     * [KNOWN_MASTERY] rather than "has been seen once", because a word met in one lesson and never
     * again is not a word the tutor can build a sentence out of without explanation.
     */
    fun known(now: Long, threshold: Double = KNOWN_MASTERY): Set<String> =
        items.filter { (_, state) -> Fsrs.mastery(state, now) >= threshold }.keys

    /** Just the concept ids, which is what the vocabulary gate compares against. */
    fun knownConcepts(now: Long, threshold: Double = KNOWN_MASTERY): Set<String> =
        known(now, threshold).filter { it.startsWith(VOCAB_PREFIX) }
            .map { it.removePrefix(VOCAB_PREFIX) }
            .toSet()

    fun knownPatterns(now: Long, threshold: Double = KNOWN_MASTERY): Set<String> =
        known(now, threshold).filter { it.startsWith(GRAMMAR_PREFIX) }
            .map { it.removePrefix(GRAMMAR_PREFIX) }
            .toSet()

    /**
     * Everything the learner has ever been shown, mastered or not.
     *
     * Separate from [known] because the two answer different questions: the vocabulary gate asks
     * what the tutor may use freely, while "have they met this before" decides whether a word is
     * introduced or merely used.
     */
    fun encountered(): Set<String> = items.filterValues { !it.isNew }.keys

    fun encounteredConcepts(): Set<String> =
        encountered().filter { it.startsWith(VOCAB_PREFIX) }.map { it.removePrefix(VOCAB_PREFIX) }.toSet()

    /**
     * Items whose recall probability has fallen far enough to be worth revisiting, most urgent
     * first.
     *
     * Ordered by retrievability ascending rather than by due date: two items due the same day are
     * not equally at risk, and the one closer to being lost is the one worth the minute.
     */
    fun due(now: Long, limit: Int, retention: Double = 0.9): List<String> =
        items.asSequence()
            .filter { (_, state) -> Fsrs.isDue(state, now, retention) }
            .sortedBy { (_, state) -> Fsrs.retrievability(state, now) }
            .take(limit)
            .map { it.key }
            .toList()

    /**
     * The next [count] words this language teaches that the learner has not met.
     *
     * Taken in the lexicon's own order, which is the teaching sequence — see [Lexicon]. This is the
     * i+1 half of the spec: mostly known, a little new.
     */
    fun nextNewConcepts(
        code: String,
        count: Int,
        upTo: Cefr.Level = Cefr.Level.B2,
    ): List<Lexicon.Word> {
        val met = encounteredConcepts()
        return Lexicon.vocabulary(code, upTo).asSequence()
            .filter { it.conceptId !in met }
            .take(count)
            .toList()
    }

    /**
     * Words the tutor introduced that were never on any list.
     *
     * Above B2 this is the entire vocabulary mechanism — see [ConceptBands] for why no authored
     * list can honestly reach C2. A term is stored under [TERM_PREFIX] keyed by the word itself,
     * scheduled by exactly the same FSRS machinery as a lexicon entry, and shown in the word bank
     * beside them. The learner cannot tell which came from where, and has no reason to care.
     */
    fun openTerms(): Set<String> =
        items.keys.filter { it.startsWith(TERM_PREFIX) }
            .map { it.removePrefix(TERM_PREFIX) }
            .toSet()

    fun knownTerms(now: Long, threshold: Double = KNOWN_MASTERY): Set<String> =
        known(now, threshold).filter { it.startsWith(TERM_PREFIX) }
            .map { it.removePrefix(TERM_PREFIX) }
            .toSet()

    /** The next unmet grammar patterns at or below [level]. */
    fun nextNewPatterns(code: String, level: Cefr.Level, count: Int): List<GrammarSyllabus.Pattern> {
        val met = encountered().filter { it.startsWith(GRAMMAR_PREFIX) }
            .map { it.removePrefix(GRAMMAR_PREFIX) }.toSet()
        return GrammarSyllabus.through(code, level).asSequence()
            .filter { it.id !in met }
            .take(count)
            .toList()
    }

    // -- Progress, honestly ---------------------------------------------------

    /**
     * A CEFR-level estimate derived from what is actually held, not from lessons finished.
     *
     * A level is claimed when most of its vocabulary and most of its grammar are above the mastery
     * line. Deliberately strict and deliberately reversible: stop practising for three months and
     * this falls, which is true and which no lesson counter will ever tell you.
     */
    fun estimatedLevel(code: String, now: Long): Cefr.Level {
        val knownVocab = knownConcepts(now)
        val knownGrammar = knownPatterns(now)

        var best = Cefr.Level.A0
        for (level in Cefr.Level.entries) {
            val patterns = GrammarSyllabus.patterns(code, level).map { it.id }
            val grammarHeld = if (patterns.isEmpty()) 1.0
            else patterns.count { it in knownGrammar } / patterns.size.toDouble()

            // A0 is vocabulary only; it has no grammar to hold.
            val vocabNeeded = when (level) {
                Cefr.Level.A0 -> Concepts.ALL.size * 0.7
                Cefr.Level.A1 -> Concepts.ALL.size * 0.9
                else -> Concepts.ALL.size.toDouble()
            }
            val vocabHeld = if (vocabNeeded <= 0) 1.0
            else (knownVocab.size / vocabNeeded).coerceAtMost(1.0)

            val holds = grammarHeld >= LEVEL_THRESHOLD && vocabHeld >= LEVEL_THRESHOLD
            if (holds) best = level else break
        }
        return best
    }

    /**
     * Vocabulary size, in the only sense worth quoting: words above the mastery line right now.
     */
    fun vocabularySize(now: Long): Int = knownConcepts(now).size

    /** Everything the learner has met, with how well they are holding it. For the words screen. */
    fun masteryByConcept(now: Long): Map<String, Double> =
        items.filterKeys { it.startsWith(VOCAB_PREFIX) }
            .mapKeys { it.key.removePrefix(VOCAB_PREFIX) }
            .mapValues { Fsrs.mastery(it.value, now) }

    companion object {
        const val VOCAB_PREFIX = "w:"
        const val GRAMMAR_PREFIX = "g:"

        /** A word the tutor introduced that no authored list contains. See [openTerms]. */
        const val TERM_PREFIX = "t:"

        /**
         * Where "known" begins.
         *
         * 0.6 on [Fsrs.mastery], which needs both a live memory and some durability behind it — an
         * item crammed yesterday does not reach it, and one held for a month does even if it is a
         * little stale. Tuned to be generous enough that the tutor has something to work with by
         * lesson three and strict enough that it is not building sentences from words met once.
         */
        const val KNOWN_MASTERY = 0.6

        /** The share of a level's items that must be held before the level is claimed. */
        const val LEVEL_THRESHOLD = 0.8

        fun vocabId(conceptId: String) = "$VOCAB_PREFIX$conceptId"
        fun grammarId(patternId: String) = "$GRAMMAR_PREFIX$patternId"

        /**
         * A word the tutor introduced, keyed by the word itself.
         *
         * Lowercased and trimmed so the same term met twice in different sentences is one item
         * rather than two — without that, "Entscheidung" and "entscheidung" would be scheduled
         * separately and neither would ever reach mastery.
         */
        fun termId(word: String) = "$TERM_PREFIX${word.trim().lowercase()}"
    }
}
