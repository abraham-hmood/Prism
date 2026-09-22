package com.prism.launcher.language

/**
 * How much work a given language is, for a given learner.
 *
 * ## Why the pair matters and the target alone does not
 *
 * "Japanese is hard" is only true of English speakers. A Korean speaker reaches conversational
 * Japanese in a fraction of the time, because the grammar is nearly isomorphic and a third of the
 * vocabulary is shared Sino-Xenic. A Spanish speaker learning Portuguese is doing something closer
 * to learning an accent than learning a language. A plan that priced every target the same for
 * everybody would hand the Korean learner three times the lessons they need and quietly tell the
 * English learner that Arabic is the same size as Dutch.
 *
 * So difficulty here is a function of BOTH languages. It comes out as a multiplier on
 * [Cefr.Level.guidedHours], which is quoted for a closely related pair.
 *
 * ## Where the numbers come from
 *
 * [intrinsic] is the US Foreign Service Institute's category system, which is the only large public
 * dataset of how long real adults actually took: FSI publishes class hours to reach professional
 * working proficiency (roughly CEFR B2/C1) per language, for English-speaking diplomats, taught
 * intensively by professionals. Category I is ~600–750 hours, Category V is ~2200. The multipliers
 * below are those figures normalised so Category I is 1.0.
 *
 * [relatedness] then discounts that for a learner whose own language is nearer the target than
 * English is. It is a coarse model — family, branch and writing system — because a fine one would
 * need per-pair data that does not exist. Coarse and directionally right beats precise and invented.
 *
 * ## What this deliberately does NOT model
 *
 * Aptitude, prior languages, motivation, or how much the learner reads on their own. All four
 * matter more than family relatedness, and none of them can be known from a setup questionnaire.
 * The plan reports an estimate and says it is an estimate.
 */
object LanguageDistance {

    /** Broad genetic grouping. Enough to tell "related" from "unrelated"; no finer. */
    enum class Family { GERMANIC, ROMANCE, SLAVIC, CELTIC, HELLENIC, INDO_ARYAN, IRANIAN, BALTIC,
        SEMITIC, TURKIC, URALIC, JAPONIC, KOREANIC, SINITIC, TAI_KADAI, AUSTROASIATIC,
        AUSTRONESIAN, NIGER_CONGO, DRAVIDIAN, KARTVELIAN, MONGOLIC, CREOLE, ISOLATE, OTHER }

    /**
     * The writing system, which is a real and separable cost.
     *
     * A learner moving between two Latin-script languages starts reading on day one. One facing an
     * abjad is inferring vowels that are not written; one facing Han characters has a second,
     * larger syllabus running alongside the language itself. That cost is independent of how
     * related the grammars are, which is why it multiplies separately.
     */
    enum class Script { LATIN, CYRILLIC, GREEK, ARABIC, HEBREW, DEVANAGARI, BENGALI, TAMIL, TELUGU,
        KANNADA, MALAYALAM, GURMUKHI, GUJARATI, SINHALA, THAI, LAO, KHMER, BURMESE, GEORGIAN,
        ARMENIAN, HAN, KANA_HAN, HANGUL, ETHIOPIC, OTHER }

    private data class Profile(val family: Family, val script: Script, val fsi: Int)

    /**
     * FSI category per language, and the family and script it belongs to.
     *
     * Only the languages Prism can currently teach need a category; every other entry exists so a
     * learner's NATIVE language can be placed for the relatedness discount.
     */
    private val PROFILES: Map<String, Profile> = mapOf(
        // Category I — closest to English
        "es" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "pt" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "pt-BR" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "fr" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "it" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "ro" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "ca" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "gl" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "co" to Profile(Family.ROMANCE, Script.LATIN, 1),
        "nl" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "af" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "sv" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "no" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "da" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "fy" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "lb" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "en" to Profile(Family.GERMANIC, Script.LATIN, 1),
        "ht" to Profile(Family.CREOLE, Script.LATIN, 1),
        "sw" to Profile(Family.NIGER_CONGO, Script.LATIN, 2),

        // Category II
        "de" to Profile(Family.GERMANIC, Script.LATIN, 2),
        "id" to Profile(Family.AUSTRONESIAN, Script.LATIN, 2),
        "ms" to Profile(Family.AUSTRONESIAN, Script.LATIN, 2),

        // Category III / IV — significant distance from English
        "ru" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "uk" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "be" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "bg" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "mk" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "sr" to Profile(Family.SLAVIC, Script.CYRILLIC, 4),
        "pl" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "cs" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "sk" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "hr" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "bs" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "sl" to Profile(Family.SLAVIC, Script.LATIN, 4),
        "el" to Profile(Family.HELLENIC, Script.GREEK, 4),
        "tr" to Profile(Family.TURKIC, Script.LATIN, 4),
        "az" to Profile(Family.TURKIC, Script.LATIN, 4),
        "kk" to Profile(Family.TURKIC, Script.CYRILLIC, 4),
        "ky" to Profile(Family.TURKIC, Script.CYRILLIC, 4),
        "uz" to Profile(Family.TURKIC, Script.LATIN, 4),
        "ug" to Profile(Family.TURKIC, Script.ARABIC, 4),
        "fi" to Profile(Family.URALIC, Script.LATIN, 4),
        "et" to Profile(Family.URALIC, Script.LATIN, 4),
        "hu" to Profile(Family.URALIC, Script.LATIN, 4),
        "lt" to Profile(Family.BALTIC, Script.LATIN, 4),
        "lv" to Profile(Family.BALTIC, Script.LATIN, 4),
        "is" to Profile(Family.GERMANIC, Script.LATIN, 4),
        "he" to Profile(Family.SEMITIC, Script.HEBREW, 4),
        "yi" to Profile(Family.GERMANIC, Script.HEBREW, 3),
        "am" to Profile(Family.SEMITIC, Script.ETHIOPIC, 4),
        "hi" to Profile(Family.INDO_ARYAN, Script.DEVANAGARI, 4),
        "mr" to Profile(Family.INDO_ARYAN, Script.DEVANAGARI, 4),
        "ne" to Profile(Family.INDO_ARYAN, Script.DEVANAGARI, 4),
        "bn" to Profile(Family.INDO_ARYAN, Script.BENGALI, 4),
        "gu" to Profile(Family.INDO_ARYAN, Script.GUJARATI, 4),
        "pa" to Profile(Family.INDO_ARYAN, Script.GURMUKHI, 4),
        "si" to Profile(Family.INDO_ARYAN, Script.SINHALA, 4),
        "ur" to Profile(Family.INDO_ARYAN, Script.ARABIC, 4),
        "sd" to Profile(Family.INDO_ARYAN, Script.ARABIC, 4),
        "fa" to Profile(Family.IRANIAN, Script.ARABIC, 4),
        "ps" to Profile(Family.IRANIAN, Script.ARABIC, 4),
        "ku" to Profile(Family.IRANIAN, Script.LATIN, 4),
        "ta" to Profile(Family.DRAVIDIAN, Script.TAMIL, 4),
        "te" to Profile(Family.DRAVIDIAN, Script.TELUGU, 4),
        "kn" to Profile(Family.DRAVIDIAN, Script.KANNADA, 4),
        "ml" to Profile(Family.DRAVIDIAN, Script.MALAYALAM, 4),
        "th" to Profile(Family.TAI_KADAI, Script.THAI, 4),
        "lo" to Profile(Family.TAI_KADAI, Script.LAO, 4),
        "vi" to Profile(Family.AUSTROASIATIC, Script.LATIN, 4),
        "km" to Profile(Family.AUSTROASIATIC, Script.KHMER, 4),
        "my" to Profile(Family.SINITIC, Script.BURMESE, 4),
        "ka" to Profile(Family.KARTVELIAN, Script.GEORGIAN, 4),
        "hy" to Profile(Family.OTHER, Script.ARMENIAN, 4),
        "mn" to Profile(Family.MONGOLIC, Script.CYRILLIC, 4),
        "tl" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "ceb" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "jv" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "su" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "mg" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "mi" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "sm" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "haw" to Profile(Family.AUSTRONESIAN, Script.LATIN, 3),
        "zu" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "xh" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "sn" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "st" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "ny" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "yo" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "ig" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "ha" to Profile(Family.NIGER_CONGO, Script.LATIN, 3),
        "so" to Profile(Family.SEMITIC, Script.LATIN, 4),
        "sq" to Profile(Family.OTHER, Script.LATIN, 4),
        "eu" to Profile(Family.ISOLATE, Script.LATIN, 4),
        "mt" to Profile(Family.SEMITIC, Script.LATIN, 4),
        "ga" to Profile(Family.CELTIC, Script.LATIN, 4),
        "gd" to Profile(Family.CELTIC, Script.LATIN, 4),
        "cy" to Profile(Family.CELTIC, Script.LATIN, 4),
        "tg" to Profile(Family.IRANIAN, Script.CYRILLIC, 4),
        "hmn" to Profile(Family.OTHER, Script.LATIN, 4),

        // Category V — the four FSI singles out as exceptionally difficult for English speakers
        "ar" to Profile(Family.SEMITIC, Script.ARABIC, 5),
        "zh" to Profile(Family.SINITIC, Script.HAN, 5),
        "zh-TW" to Profile(Family.SINITIC, Script.HAN, 5),
        "ja" to Profile(Family.JAPONIC, Script.KANA_HAN, 5),
        "ko" to Profile(Family.KOREANIC, Script.HANGUL, 5),
    )

    /** FSI class hours, normalised so a Category I language is 1.0. */
    private val FSI_MULTIPLIER = mapOf(
        1 to 1.00,
        2 to 1.25,
        3 to 1.45,
        4 to 1.80,
        5 to 2.40,
    )

    /**
     * Languages that are close enough that a speaker of one is most of the way into the other.
     *
     * Same family is not enough on its own — English and Hindi share a family and share almost
     * nothing usable. These are the pairs where mutual intelligibility or massive shared vocabulary
     * is a real, documented effect, listed in both directions by [areSiblings].
     */
    private val SIBLINGS: List<Set<String>> = listOf(
        setOf("es", "pt", "pt-BR", "gl", "ca", "it", "ro", "co"),
        setOf("nl", "af", "fy"),
        setOf("sv", "no", "da"),
        setOf("de", "lb", "yi"),
        setOf("ru", "uk", "be"),
        setOf("cs", "sk"),
        setOf("hr", "bs", "sr", "sl"),
        setOf("hi", "ur", "pa"),
        setOf("id", "ms", "jv", "su"),
        setOf("fa", "tg"),
        setOf("tr", "az"),
        setOf("kk", "ky", "uz", "ug"),
        setOf("zh", "zh-TW"),
        // Not a family relation, but the effect is enormous and well documented: shared Sino-Xenic
        // vocabulary plus near-identical word order between these three.
        setOf("ja", "ko"),
        setOf("zh", "ja"),
        setOf("zh", "ko"),
    )

    private fun profileOf(code: String?): Profile =
        PROFILES[code] ?: PROFILES[code?.substringBefore('-')] ?: Profile(Family.OTHER, Script.LATIN, 4)

    private fun areSiblings(a: String, b: String): Boolean =
        SIBLINGS.any { it.contains(a) && it.contains(b) }

    /** Difficulty of [target] for an English speaker, from the FSI categories. */
    fun intrinsic(target: String): Double =
        FSI_MULTIPLIER[profileOf(target).fsi] ?: 1.8

    /**
     * How much a learner's own language discounts the work. 1.0 means no help at all.
     *
     * The discounts compound deliberately: a Spanish speaker learning Portuguese gets the sibling
     * discount AND the shared-script discount, and should, because they genuinely start reading on
     * the first day with most of the vocabulary already half-known.
     */
    fun relatedness(nativeCode: String, targetCode: String): Double {
        if (nativeCode == targetCode) return 0.0

        val native = profileOf(nativeCode)
        val target = profileOf(targetCode)

        var factor = when {
            areSiblings(nativeCode, targetCode) -> 0.45
            native.family == target.family -> 0.80
            else -> 1.0
        }

        // The script is a separate syllabus. Sharing one is worth more the further apart the
        // languages otherwise are, but a flat discount is close enough and cannot misfire.
        if (native.script == target.script) factor *= 0.88

        return factor
    }

    /**
     * The multiplier to apply to [Cefr.Level.guidedHours] for this learner and this target.
     *
     * Clamped at both ends. The floor stops a Spanish speaker learning Portuguese being told the
     * whole of C2 takes a fortnight; the ceiling stops a pathological pair producing a plan with
     * more lessons than anyone will ever see.
     */
    fun difficulty(nativeCode: String, targetCode: String): Double {
        val raw = intrinsic(targetCode) * relatedness(nativeCode, targetCode)
        return raw.coerceIn(0.55, 2.60)
    }

    /** True when the learner has a whole writing system to learn as well as a language. */
    fun needsNewScript(nativeCode: String, targetCode: String): Boolean =
        profileOf(nativeCode).script != profileOf(targetCode).script

    /** The target's script, so the plan can say what it is the learner is taking on. */
    fun scriptOf(code: String): Script = profileOf(code).script
}
