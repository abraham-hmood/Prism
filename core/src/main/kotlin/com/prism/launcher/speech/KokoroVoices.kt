package com.prism.launcher.speech

/**
 * Kokoro-82M's voice catalogue, as published.
 *
 * ## Why this is a table and not a directory listing
 *
 * The voices live in the model repository as 54 separate tensor files, and a picker built by
 * listing whatever happens to be on disk would show nothing until they were downloaded and would
 * show raw filenames when they were. The catalogue is therefore fixed in code: the picker can offer
 * every voice before any of them exist locally, fetch the one that gets chosen, and label all of
 * them in a way a person can choose between.
 *
 * ## Reading a voice id
 *
 * The prefix is not decoration. The first letter is the language, the second is the gender, and the
 * rest is the name -- `bf_emma` is British English, female, Emma. That is Kokoro's own convention,
 * and it is what makes a flat list of 54 ids navigable once it is grouped by [Language].
 */
object KokoroVoices {

    enum class Gender { FEMALE, MALE }

    /**
     * The languages Kokoro ships voices for.
     *
     * [code] is the id prefix letter. [pack] is the language pack its phonemiser needs, which is
     * not always the same thing: American and British English are two voice sets over one language.
     */
    enum class Language(val code: Char, val display: String, val pack: String) {
        AMERICAN_ENGLISH('a', "American English", "en-us"),
        BRITISH_ENGLISH('b', "British English", "en-gb"),
        SPANISH('e', "Spanish", "es"),
        FRENCH('f', "French", "fr-fr"),
        HINDI('h', "Hindi", "hi"),
        ITALIAN('i', "Italian", "it"),
        BRAZILIAN_PORTUGUESE('p', "Brazilian Portuguese", "pt-br"),
        JAPANESE('j', "Japanese", "ja"),
        MANDARIN('z', "Mandarin Chinese", "zh");

        companion object {
            fun of(code: Char): Language? = entries.firstOrNull { it.code == code }
        }
    }

    /**
     * One voice.
     *
     * [quality] is Kokoro's own published grade for how much audio the voice was trained on, kept
     * because it is the difference between a voice that sounds finished and one that does not, and
     * a picker that hides it makes the user find that out by ear.
     */
    data class Voice(
        val id: String,
        val displayName: String,
        val language: Language,
        val gender: Gender,
        val quality: String,
    ) {
        /** What the picker shows: "Heart — female, A" reads better than "af_heart". */
        val label: String
            get() = "$displayName — ${if (gender == Gender.FEMALE) "female" else "male"}, $quality"
    }

    private fun voice(id: String, name: String, quality: String): Voice {
        val language = Language.of(id[0]) ?: Language.AMERICAN_ENGLISH
        val gender = if (id.getOrNull(1) == 'f') Gender.FEMALE else Gender.MALE
        return Voice(id, name, language, gender, quality)
    }

    /**
     * All 54 published voices, in the repository's own order.
     *
     * Grades are Kokoro's: A is the most training audio, D the least, and a voice below C is
     * recognisably thinner. They are recorded here rather than looked up so the picker can sort and
     * annotate without the network.
     */
    val ALL: List<Voice> = listOf(
        // American English
        voice("af_heart", "Heart", "A"),
        voice("af_alloy", "Alloy", "C"),
        voice("af_aoede", "Aoede", "C+"),
        voice("af_bella", "Bella", "A-"),
        voice("af_jessica", "Jessica", "D"),
        voice("af_kore", "Kore", "C+"),
        voice("af_nicole", "Nicole", "B-"),
        voice("af_nova", "Nova", "C"),
        voice("af_river", "River", "D"),
        voice("af_sarah", "Sarah", "C+"),
        voice("af_sky", "Sky", "C-"),
        voice("am_adam", "Adam", "F+"),
        voice("am_echo", "Echo", "D"),
        voice("am_eric", "Eric", "D"),
        voice("am_fenrir", "Fenrir", "C+"),
        voice("am_liam", "Liam", "D"),
        voice("am_michael", "Michael", "C+"),
        voice("am_onyx", "Onyx", "D"),
        voice("am_puck", "Puck", "C+"),
        voice("am_santa", "Santa", "D-"),
        // British English
        voice("bf_alice", "Alice", "D"),
        voice("bf_emma", "Emma", "B-"),
        voice("bf_isabella", "Isabella", "C"),
        voice("bf_lily", "Lily", "D"),
        voice("bm_daniel", "Daniel", "D"),
        voice("bm_fable", "Fable", "C"),
        voice("bm_george", "George", "C"),
        voice("bm_lewis", "Lewis", "D+"),
        // Spanish
        voice("ef_dora", "Dora", "C"),
        voice("em_alex", "Alex", "C"),
        voice("em_santa", "Santa", "C"),
        // French
        voice("ff_siwis", "Siwis", "B-"),
        // Hindi
        voice("hf_alpha", "Alpha", "C"),
        voice("hf_beta", "Beta", "C"),
        voice("hm_omega", "Omega", "C"),
        voice("hm_psi", "Psi", "C"),
        // Italian
        voice("if_sara", "Sara", "C"),
        voice("im_nicola", "Nicola", "C"),
        // Japanese
        voice("jf_alpha", "Alpha", "C+"),
        voice("jf_gongitsune", "Gongitsune", "C"),
        voice("jf_nezumi", "Nezumi", "C-"),
        voice("jf_tebukuro", "Tebukuro", "C"),
        voice("jm_kumo", "Kumo", "C-"),
        // Brazilian Portuguese
        voice("pf_dora", "Dora", "C"),
        voice("pm_alex", "Alex", "C"),
        voice("pm_santa", "Santa", "C"),
        // Mandarin Chinese
        voice("zf_xiaobei", "Xiaobei", "D"),
        voice("zf_xiaoni", "Xiaoni", "D"),
        voice("zf_xiaoxiao", "Xiaoxiao", "D"),
        voice("zf_xiaoyi", "Xiaoyi", "D"),
        voice("zm_yunjian", "Yunjian", "D"),
        voice("zm_yunxi", "Yunxi", "D"),
        voice("zm_yunxia", "Yunxia", "D"),
        voice("zm_yunyang", "Yunyang", "D"),
    )

    private val byId: Map<String, Voice> = ALL.associateBy { it.id }

    fun find(id: String): Voice? = byId[id]

    /** Grouped for a picker, in the catalogue's language order rather than alphabetically. */
    fun byLanguage(): Map<Language, List<Voice>> = ALL.groupBy { it.language }

    /** A voice's label, or the raw id if it is one this build does not know about. */
    fun labelOf(id: String): String = byId[id]?.let { "${it.displayName} (${it.language.display})" } ?: id
}
