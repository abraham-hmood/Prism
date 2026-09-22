package com.prism.launcher.language

import com.prism.launcher.language.TutorPortrait.Hair
import com.prism.launcher.language.TutorPortrait.PortraitSpec
import com.prism.launcher.speech.KokoroVoices

/**
 * Prism's own tutors.
 *
 * ## Why a tutor is not just a system prompt
 *
 * The model behind every one of these is the same model. What differs is the prompt, the voice, and
 * the face — and that is enough, because the thing a learner is actually afraid of is being heard
 * making mistakes by someone. Picking a face and a manner is how a person decides whose patience
 * they are about to test, and a list of "Tutor 1..16" would give them nothing to decide with.
 *
 * ## The accent field is a promise about the VOICE, not about the person
 *
 * [accent] picks the Kokoro voice and tells the model which variety to model — "colour" or "color",
 * "film" or "movie". It is deliberately separate from the tutor's name and look: a British English
 * tutor named Amara is an ordinary fact about the world, and tying appearance to accent would build
 * something uglier than a language app should be.
 *
 * ## Names
 *
 * Original to Prism. Chosen to be short, easy to say aloud in a voice call, and unambiguous when
 * spoken by a synthesiser — a tutor whose name the TTS mangles is a bad first impression every
 * single session.
 */
object LanguageTutors {

    enum class Manner(val label: String) {
        WARM("Friendly & casual"),
        FORMAL("Formal & structured"),
        PLAYFUL("Playful & fun"),
    }

    /**
     * @param id stable across versions — it is what a saved profile stores.
     * @param voice the Kokoro voice id this tutor speaks with. See KokoroVoices.
     * @param gender what the tutor's voice reads as. Stated rather than inferred from [voice],
     *   because several tutors have no Kokoro voice in their own language and the inference from
     *   a substitute voice id was giving the wrong answer — Haneul, a Korean woman, was reaching
     *   the synthesiser as whatever gender her stand-in American voice happened to be.
     * @param heritage the speech community the tutor's own voice comes from, as a BCP-47 tag with
     *   a region. This is what makes Rohan sound Indian and Amara British instead of both of them
     *   sounding like Kansas. It picks the accent whenever the engine offers one for the language
     *   being spoken, and picks the whole voice when the tutor is teaching their own language.
     * @param blurb one line, shown under the name when a tutor is being chosen.
     */
    data class Tutor(
        val id: String,
        val name: String,
        val accent: String,
        val manner: Manner,
        val voice: String,
        val gender: KokoroVoices.Gender,
        val heritage: String,
        val blurb: String,
        val portrait: PortraitSpec,
    ) {
        val isFemale: Boolean get() = gender == KokoroVoices.Gender.FEMALE
    }

    // Skin, hair and garment palettes, named once so the roster below reads as art direction
    // rather than as a wall of hex.
    private const val PORCELAIN = 0xFFF7DCC9.toInt()
    private const val SAND = 0xFFEFC6A4.toInt()
    private const val AMBER = 0xFFDCA173.toInt()
    private const val CLAY = 0xFFB87A50.toInt()
    private const val UMBER = 0xFF8D5524.toInt()
    private const val ESPRESSO = 0xFF5E3A22.toInt()

    private const val JET = 0xFF2B2B33.toInt()
    private const val COCOA = 0xFF4A3524.toInt()
    private const val CHESTNUT = 0xFF8B5A2B.toInt()
    private const val WHEAT = 0xFFD9A441.toInt()
    private const val GINGER = 0xFFE0662B.toInt()
    private const val SILVER = 0xFFB0B4BD.toInt()
    private const val VIOLET = 0xFF7A4BD1.toInt()
    private const val TEAL_DYE = 0xFF2E8FA7.toInt()

    private const val SKY = 0xFF5B8DEF.toInt()
    private const val CORAL = 0xFFEF6F6C.toInt()
    private const val JADE = 0xFF3FB98C.toInt()
    private const val HONEY = 0xFFF2B33D.toInt()
    private const val LILAC = 0xFF8C6BE8.toInt()
    private const val SLATE = 0xFF3C4A63.toInt()
    private const val ROSE = 0xFFE87FA8.toInt()
    private const val AQUA = 0xFF4CC2D6.toInt()

    val ALL: List<Tutor> = listOf(
        Tutor(
            "mira", "Mira", "British English", Manner.WARM, "bf_emma", KokoroVoices.Gender.FEMALE, "en-GB",
            "Slows down without making it obvious",
            PortraitSpec(PORCELAIN, GINGER, Hair.LONG_WAVY, JADE, 0xFFD9F2E6.toInt(), earrings = true, freckles = true),
        ),
        Tutor(
            "otis", "Otis", "American English", Manner.WARM, "am_michael", KokoroVoices.Gender.MALE, "en-US",
            "Talks like a friend who happens to be patient",
            PortraitSpec(SAND, COCOA, Hair.SHAGGY, SKY, 0xFFDCE8FB.toInt(), glasses = true),
        ),
        Tutor(
            "neve", "Neve", "American English", Manner.PLAYFUL, "af_heart", KokoroVoices.Gender.FEMALE, "en-US",
            "Will absolutely make you do the silly role-play",
            PortraitSpec(PORCELAIN, VIOLET, Hair.BOB, CORAL, 0xFFFBE0E0.toInt(), earrings = true),
        ),
        Tutor(
            "amara", "Amara", "British English", Manner.FORMAL, "bf_isabella", KokoroVoices.Gender.FEMALE, "en-GB",
            "Exam prep, and she means it",
            PortraitSpec(UMBER, JET, Hair.CURLS, SLATE, 0xFFDDE2EC.toInt()),
        ),
        Tutor(
            "dante", "Dante", "Italian", Manner.PLAYFUL, "im_nicola", KokoroVoices.Gender.MALE, "it-IT",
            "Explains grammar with his hands",
            PortraitSpec(AMBER, JET, Hair.SHORT_CROP, HONEY, 0xFFFDF0D8.toInt()),
        ),
        Tutor(
            "lina", "Lina", "Spanish", Manner.WARM, "ef_dora", KokoroVoices.Gender.FEMALE, "es-ES",
            "Starts every lesson somewhere you actually go",
            PortraitSpec(SAND, CHESTNUT, Hair.PONYTAIL, ROSE, 0xFFFBE2EC.toInt(), earrings = true),
        ),
        Tutor(
            "yuki", "Yuki", "Japanese", Manner.FORMAL, "jf_alpha", KokoroVoices.Gender.FEMALE, "ja-JP",
            "Keigo, kanji, and no shortcuts",
            PortraitSpec(PORCELAIN, JET, Hair.LONG_STRAIGHT, LILAC, 0xFFE8E0FB.toInt()),
        ),
        Tutor(
            "kenta", "Kenta", "Japanese", Manner.WARM, "jm_kumo", KokoroVoices.Gender.MALE, "ja-JP",
            "Casual register, the way people really speak",
            PortraitSpec(SAND, JET, Hair.UNDERCUT, AQUA, 0xFFDDF2F6.toInt()),
        ),
        Tutor(
            "haneul", "Haneul", "Korean", Manner.PLAYFUL, "af_nicole", KokoroVoices.Gender.FEMALE, "ko-KR",
            "Drama dialogue as homework",
            PortraitSpec(PORCELAIN, TEAL_DYE, Hair.BOB, LILAC, 0xFFE6E2FA.toInt(), earrings = true),
        ),
        Tutor(
            "bruno", "Bruno", "Brazilian Portuguese", Manner.WARM, "pm_alex", KokoroVoices.Gender.MALE, "pt-BR",
            "Never lets a sentence die halfway",
            PortraitSpec(CLAY, COCOA, Hair.FADE, JADE, 0xFFD8F1E5.toInt()),
        ),
        Tutor(
            "elin", "Elin", "American English", Manner.FORMAL, "af_sarah", KokoroVoices.Gender.FEMALE, "en-US",
            "Structure, review, and a weekly plan",
            PortraitSpec(PORCELAIN, WHEAT, Hair.BUN, SLATE, 0xFFDFE4EE.toInt(), glasses = true),
        ),
        Tutor(
            "tarek", "Tarek", "Arabic", Manner.WARM, "am_adam", KokoroVoices.Gender.MALE, "ar-SA",
            "Modern Standard, with the dialect noted",
            PortraitSpec(AMBER, JET, Hair.SHORT_CROP, SKY, 0xFFDBE7FA.toInt()),
        ),
        Tutor(
            "wen", "Wen", "Mandarin Chinese", Manner.FORMAL, "zf_xiaobei", KokoroVoices.Gender.FEMALE, "zh-CN",
            "Tones first. Everything else follows",
            PortraitSpec(SAND, JET, Hair.LONG_STRAIGHT, CORAL, 0xFFFBE1E0.toInt()),
        ),
        Tutor(
            "greta", "Greta", "German", Manner.FORMAL, "af_kore", KokoroVoices.Gender.FEMALE, "de-DE",
            "Cases, clearly, one at a time",
            PortraitSpec(PORCELAIN, SILVER, Hair.BOB, SLATE, 0xFFE1E5ED.toInt(), glasses = true),
        ),
        Tutor(
            "marcel", "Marcel", "French", Manner.PLAYFUL, "ff_siwis", KokoroVoices.Gender.MALE, "fr-FR",
            "Corrects your accent by teasing it",
            PortraitSpec(SAND, CHESTNUT, Hair.SHAGGY, HONEY, 0xFFFCF0D9.toInt()),
        ),
        Tutor(
            "zofia", "Zofia", "Polish", Manner.WARM, "af_bella", KokoroVoices.Gender.FEMALE, "pl-PL",
            "Patient with consonant clusters",
            PortraitSpec(PORCELAIN, COCOA, Hair.LONG_WAVY, AQUA, 0xFFDEF2F6.toInt(), earrings = true),
        ),
        Tutor(
            "rohan", "Rohan", "Indian English", Manner.WARM, "am_liam", KokoroVoices.Gender.MALE, "en-IN",
            "Interview practice that feels like an interview",
            PortraitSpec(CLAY, JET, Hair.SHORT_CROP, LILAC, 0xFFE7E1FA.toInt(), glasses = true),
        ),
        Tutor(
            "ada", "Ada", "American English", Manner.PLAYFUL, "af_jessica", KokoroVoices.Gender.FEMALE, "en-US",
            "Turns every mistake into the next question",
            PortraitSpec(ESPRESSO, JET, Hair.CURLS, HONEY, 0xFFFCEFD6.toInt(), earrings = true),
        ),
    )

    fun byId(id: String?): Tutor? = ALL.firstOrNull { it.id == id }

    /**
     * The tutors worth showing first, given what the learner said in setup.
     *
     * Manner is the only real signal the questionnaire produces, so it is the only thing sorted on;
     * everything else keeps its authored order. Deliberately a SORT and not a filter — a learner who
     * asked for "formal" and is shown three faces has been given a worse choice, not a better one.
     */
    fun preferredOrder(manner: Manner?): List<Tutor> {
        if (manner == null) return ALL
        return ALL.sortedByDescending { it.manner == manner }
    }

    fun mannerFor(value: String?): Manner? = Manner.entries.firstOrNull { it.label == value }
}
