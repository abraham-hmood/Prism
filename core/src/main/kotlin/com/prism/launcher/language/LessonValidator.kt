package com.prism.launcher.language

/**
 * The gate. Nothing the model writes reaches the learner without passing through here.
 *
 * ## Why this exists at all
 *
 * A wrong answer from a coding assistant fails loudly. A wrong sentence from a language tutor gets
 * memorised. The learner is, by definition, the one person in the room who cannot tell that the
 * particle is wrong or that the word does not exist — that is why they are here.
 *
 * Prism makes this worse than it is for a cloud app, and deliberately: its whole point is running
 * on a small local model, and a quantised 1B generating Japanese will invent compounds, drop
 * particles and drift to English within three turns. The gate is what makes that configuration
 * safe to ship rather than something to apologise for.
 *
 * ## What it checks, and what it cannot
 *
 * - **Script.** Is this actually written in the target language's writing system? Catches the
 *   commonest small-model failure by far: silently answering in English.
 * - **Vocabulary.** Is every content word one the learner knows or one this lesson teaches? Catches
 *   level drift, which is the second commonest.
 * - **Length.** Is it sayable in one breath at this level?
 * - **Leakage.** Did it quote the brief, mention the level, or announce itself as an AI?
 *
 * It cannot check grammar, and does not pretend to. A sentence built from allowed words in the
 * right script with a wrong case ending will pass. That is a real limit, and it is why the
 * vocabulary constraint is tight — the smaller the set of words, the fewer ways there are to be
 * wrong with them.
 *
 * ## Fuzzy on purpose
 *
 * The lexicon holds citation forms; real sentences hold inflections. `manzanas` must match
 * `la manzana`, `食べます` must match `食べる`. So matching is by stem, and the threshold is
 * deliberately loose: a false accept costs one odd word, a false reject costs a retry and, if it
 * keeps happening, a lesson that never starts.
 */
object LessonValidator {

    /** What was wrong, in the order the gate checks. */
    enum class Problem {
        EMPTY,
        WRONG_SCRIPT,
        UNKNOWN_WORDS,
        TOO_LONG,
        LEAKED_INSTRUCTIONS,
        /** The tutor talked ABOUT the language instead of producing any of it. */
        NOTHING_TAUGHT,
        /** The taught item came back in the learner's own language. */
        TAUGHT_IN_NATIVE,
    }

    data class Result(
        val ok: Boolean,
        val problems: List<Problem>,
        /** The content words that were not in the allowed set. For the retry prompt and for logs. */
        val offendingWords: List<String>,
    ) {
        companion object {
            val PASS = Result(true, emptyList(), emptyList())
        }
    }

    /** Phrases that mean the model is talking about the lesson instead of giving it. */
    private val LEAK_MARKERS = listOf(
        "as an ai", "language model", "i cannot", "system prompt", "instructions",
        "cefr", "a1 level", "a2 level", "b1 level", "b2 level",
        "SAY:", "HINT:", "NOTE:", "DONE:",
    )

    /**
     * How long a tutor line may be, by level, in characters.
     *
     * Characters and not words because word counting is meaningless in Chinese and Japanese. The
     * ceilings are generous — this catches a model that has started lecturing, not one that wrote a
     * slightly long sentence.
     */
    private fun maxLength(level: Cefr.Level): Int = when (level) {
        Cefr.Level.A0 -> 40
        Cefr.Level.A1 -> 120
        Cefr.Level.A2 -> 200
        Cefr.Level.B1 -> 320
        else -> 500
    }

    /**
     * Checks a reply, whose two halves are held to completely different standards.
     *
     * [say] is the tutor talking to the learner in the learner's own language. There is nothing to
     * check about its vocabulary — it is meant to be ordinary speech in a language the learner
     * already has — so it is only checked for length and for leaked instructions.
     *
     * [teach] is the thing being taught. It carries the entire pedagogical risk, so it takes the
     * full gate: right script, right level of vocabulary, short enough to repeat. A lesson whose
     * SAY is perfect and whose TEACH is hallucinated has taught the learner something wrong.
     */
    fun validate(say: String, teach: String?, spec: LessonSpec): Result {
        val spoken = say.trim()
        if (spoken.isBlank() && teach.isNullOrBlank()) {
            return Result(false, listOf(Problem.EMPTY), emptyList())
        }

        val problems = mutableListOf<Problem>()
        val offending = mutableListOf<String>()

        // The tutor's own line is allowed to be conversational, so its ceiling is generous; it is
        // only catching a model that has started lecturing.
        if (spoken.length > maxLength(spec.level) * 3) problems.add(Problem.TOO_LONG)

        val lower = (spoken + " " + teach.orEmpty()).lowercase()
        if (LEAK_MARKERS.any { lower.contains(it.lowercase()) }) {
            problems.add(Problem.LEAKED_INSTRUCTIONS)
        }

        val item = teach?.trim().orEmpty()
        if (item.isBlank()) {
            // Only a fault where the lesson is supposed to be producing language every turn. A
            // conversation turn that is purely a reaction is legitimate; a picture lesson that
            // teaches nothing is not.
            if (spec.isPicture) problems.add(Problem.NOTHING_TAUGHT)
        } else {
            if (item.length > maxLength(spec.level)) problems.add(Problem.TOO_LONG)

            val script = LanguageDistance.scriptOf(spec.targetCode)
            if (!looksLikeScript(item, script)) {
                problems.add(Problem.WRONG_SCRIPT)
            } else if (speaksNativeLanguage(item, spec)) {
                // Same-alphabet pairs slip past the script check entirely: English inside a Spanish
                // TEACH field is written in the Latin alphabet and looks fine to every character
                // test, while being exactly the thing that must not happen.
                problems.add(Problem.TAUGHT_IN_NATIVE)
            }

            // Vocabulary is only checkable where there is a lexicon and a meaningful allowed set.
            // Above A2 the set is large enough that the check stops discriminating and starts
            // rejecting ordinary speech, so it is not applied there.
            if (spec.level.ordinal <= Cefr.Level.A2.ordinal && Lexicon.supports(spec.targetCode)) {
                val unknown = unknownContentWords(item, spec)
                if (unknown.isNotEmpty()) {
                    problems.add(Problem.UNKNOWN_WORDS)
                    offending.addAll(unknown)
                }
            }
        }

        return if (problems.isEmpty()) Result.PASS
        else Result(false, problems.distinct(), offending)
    }

    /**
     * Content words in [text] that the learner has no business meeting yet.
     *
     * Function words are skipped via [Lexicon.stopwords] — no natural sentence avoids them, so
     * including them in the check would reject everything. Numerals, names (capitalised mid-
     * sentence in a cased script) and anything very short are skipped too: all three are
     * overwhelmingly likely to be legitimate and none of them is a vocabulary item.
     */
    fun unknownContentWords(text: String, spec: LessonSpec): List<String> {
        val allowedForms = spec.allowedConcepts
            .mapNotNull { Lexicon.word(spec.targetCode, it)?.word }
            .flatMap { stems(it) }
            .toSet()
        if (allowedForms.isEmpty()) return emptyList()

        val stop = Lexicon.stopwords(spec.targetCode)
        val script = LanguageDistance.scriptOf(spec.targetCode)

        return tokenise(text, script)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_CHECKED_LENGTH }
            .filter { it.lowercase() !in stop }
            .filterNot { it.all { ch -> ch.isDigit() } }
            .filterNot { isProperNoun(it, text) }
            .filterNot { token -> allowedForms.any { stemMatch(token, it) } }
            .distinct()
            .take(MAX_REPORTED)
            .toList()
    }

    /**
     * Splits a line into candidate words.
     *
     * Chinese and Japanese do not put spaces between words, so a space split returns the whole
     * sentence as one token and the check becomes meaningless. For those scripts the unit is a run
     * of same-script characters, which over-splits — but over-splitting only costs precision, while
     * not splitting at all costs the entire check.
     */
    fun tokenise(text: String, script: LanguageDistance.Script): List<String> {
        val spaceless = script == LanguageDistance.Script.HAN ||
            script == LanguageDistance.Script.KANA_HAN ||
            script == LanguageDistance.Script.THAI ||
            script == LanguageDistance.Script.LAO ||
            script == LanguageDistance.Script.KHMER ||
            script == LanguageDistance.Script.BURMESE

        if (!spaceless) {
            // Split on anything that is not a letter or a combining mark. `\p{Punct}` was the
            // obvious choice and is ASCII-only in Java, so Spanish `¿Es` survived as a single token
            // and got reported as an unknown word on every inverted question in the language.
            return text.split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotBlank() }
        }

        // Runs of two to four characters, which is where nearly all Chinese and Japanese words
        // live. Every run is tested; a real word inside a longer run still matches by stem.
        val cleaned = text.filter { it.isLetter() }
        val out = mutableListOf<String>()
        for (size in 1..4) {
            for (i in 0..cleaned.length - size) {
                out.add(cleaned.substring(i, i + size))
            }
        }
        return out
    }

    /**
     * Candidate stems of a citation form.
     *
     * The article is stripped, because `la manzana` must match `manzanas`. Endings are NOT stripped
     * by rule — that needs a morphology engine per language, and getting it wrong in either
     * direction is worse than a prefix comparison.
     */
    fun stems(word: String): List<String> {
        val bare = word.trim()
        val parts = bare.split(' ').filter { it.isNotBlank() }
        val withoutArticle = if (parts.size > 1) parts.drop(1).joinToString(" ") else bare
        return listOf(bare, withoutArticle)
            .flatMap { it.split(' ') }
            .filter { it.length >= 2 }
            .map { it.lowercase().trim('\'', '’', '-') }
            .distinct()
    }

    /**
     * Whether a token is plausibly a form of an allowed word.
     *
     * A shared prefix of [STEM_CHARS] characters, in either direction. Catches inflection
     * (`manzana`/`manzanas`, `essen`/`esse`, `食べる`/`食べます`) without a dictionary, and is
     * loose enough that it will occasionally accept an unrelated word that happens to start the
     * same way — which costs one odd word in one sentence, against a retry loop if it were tighter.
     */
    fun stemMatch(token: String, allowed: String): Boolean {
        val a = token.lowercase()
        val b = allowed.lowercase()
        if (a == b) return true
        val n = minOf(STEM_CHARS, a.length, b.length)
        if (n < 2) return false
        return a.regionMatches(0, b, 0, n)
    }

    /**
     * Whether the text is written in the script it should be.
     *
     * A share rather than a rule: real sentences contain digits, punctuation, and loanwords in
     * Latin script even in Japanese. [SCRIPT_SHARE] of the LETTERS being right is what separates
     * "a Japanese sentence with a brand name in it" from "an English sentence".
     */
    fun looksLikeScript(text: String, script: LanguageDistance.Script): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val matching = letters.count { inScript(it, script) }
        return matching.toDouble() / letters.length >= SCRIPT_SHARE
    }

    private fun inScript(ch: Char, script: LanguageDistance.Script): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return when (script) {
            LanguageDistance.Script.LATIN ->
                block == Character.UnicodeBlock.BASIC_LATIN ||
                    block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
                    block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
                    block == Character.UnicodeBlock.LATIN_EXTENDED_B ||
                    block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
            LanguageDistance.Script.CYRILLIC ->
                block == Character.UnicodeBlock.CYRILLIC ||
                    block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
            LanguageDistance.Script.GREEK -> block == Character.UnicodeBlock.GREEK
            LanguageDistance.Script.ARABIC ->
                block == Character.UnicodeBlock.ARABIC ||
                    block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                    block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
            LanguageDistance.Script.HEBREW -> block == Character.UnicodeBlock.HEBREW
            LanguageDistance.Script.DEVANAGARI -> block == Character.UnicodeBlock.DEVANAGARI
            LanguageDistance.Script.BENGALI -> block == Character.UnicodeBlock.BENGALI
            LanguageDistance.Script.TAMIL -> block == Character.UnicodeBlock.TAMIL
            LanguageDistance.Script.TELUGU -> block == Character.UnicodeBlock.TELUGU
            LanguageDistance.Script.KANNADA -> block == Character.UnicodeBlock.KANNADA
            LanguageDistance.Script.MALAYALAM -> block == Character.UnicodeBlock.MALAYALAM
            LanguageDistance.Script.GURMUKHI -> block == Character.UnicodeBlock.GURMUKHI
            LanguageDistance.Script.GUJARATI -> block == Character.UnicodeBlock.GUJARATI
            LanguageDistance.Script.SINHALA -> block == Character.UnicodeBlock.SINHALA
            LanguageDistance.Script.THAI -> block == Character.UnicodeBlock.THAI
            LanguageDistance.Script.LAO -> block == Character.UnicodeBlock.LAO
            LanguageDistance.Script.KHMER -> block == Character.UnicodeBlock.KHMER
            LanguageDistance.Script.BURMESE -> block == Character.UnicodeBlock.MYANMAR
            LanguageDistance.Script.GEORGIAN -> block == Character.UnicodeBlock.GEORGIAN
            LanguageDistance.Script.ARMENIAN -> block == Character.UnicodeBlock.ARMENIAN
            LanguageDistance.Script.ETHIOPIC -> block == Character.UnicodeBlock.ETHIOPIC
            LanguageDistance.Script.HANGUL ->
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
            LanguageDistance.Script.HAN ->
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            // Japanese is all three at once, and a sentence with no kanji in it is still Japanese.
            LanguageDistance.Script.KANA_HAN ->
                block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            LanguageDistance.Script.OTHER -> true
        }
    }

    /**
     * A capitalised word that is not at the start of a sentence: almost always a name.
     *
     * Names are not vocabulary and rejecting them would fail every lesson where the tutor uses the
     * learner's own name — which, given setup asks for it, is most of them.
     */
    private fun isProperNoun(token: String, full: String): Boolean {
        if (token.isEmpty() || !token[0].isUpperCase()) return false
        val at = full.indexOf(token)
        if (at <= 0) return false
        val before = full.substring(0, at).trimEnd()
        return before.isNotEmpty() && before.last() !in ".!?。！？"
    }

    /**
     * Whether a line is written in the learner's own language rather than the target.
     *
     * Counts function words belonging to the native language and NOT to the target, which is what
     * makes it safe for close pairs: Spanish and Portuguese share half a stoplist, and a check that
     * ignored the overlap would flag every correct Portuguese sentence a Spanish speaker was shown.
     *
     * Two hits is the threshold. One is a loanword or a coincidence; two in a short line is a
     * sentence in the wrong language.
     */
    fun speaksNativeLanguage(text: String, spec: LessonSpec): Boolean {
        if (spec.nativeCode == spec.targetCode) return false
        val native = Lexicon.stopwords(spec.nativeCode)
        if (native.isEmpty()) return false
        val distinctive = native - Lexicon.stopwords(spec.targetCode)
        if (distinctive.isEmpty()) return false

        val hits = tokenise(text, LanguageDistance.scriptOf(spec.nativeCode))
            .count { it.lowercase() in distinctive }
        return hits >= 2
    }

    private const val STEM_CHARS = 4
    private const val MIN_CHECKED_LENGTH = 2
    private const val MAX_REPORTED = 8
    private const val SCRIPT_SHARE = 0.6
}
