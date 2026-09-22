package com.prism.launcher.language

import com.prism.launcher.speech.KokoroVoices
import java.util.Locale

/**
 * Which voice says what, in a lesson that is deliberately bilingual.
 *
 * ## The bug this exists to fix
 *
 * A tutor is chosen once, in setup, and used to carry a single fixed Kokoro voice — Neve was
 * `af_heart`, American English, and that was that. The language being learned is chosen separately.
 * So a learner doing Mandarin with Neve had 苹果 handed to an American English voice, which either
 * mangles it or produces nothing at all. Worse,
 * [com.prism.launcher.speech.SystemTtsEngine] derives its *locale* from the Kokoro voice id, so the
 * system fallback was also being told the lesson was in English. That is why nothing spoke.
 *
 * The voice has to follow the LANGUAGE, not the tutor. The tutor's own voice still matters — it is
 * what they sound like when they talk to the learner in the learner's language — so a lesson uses
 * two voices, and which one depends on which half of the reply is being spoken.
 *
 * ## Whose voice, though
 *
 * Two facts about a tutor decide it, and both are now stated on [LanguageTutors.Tutor] rather than
 * guessed:
 *
 * - **Gender.** Matched always. It used to be inferred from whatever Kokoro voice the tutor had
 *   been assigned, which meant a tutor with no voice in her own language inherited the gender of
 *   her stand-in. Where an engine cannot honour the gender — Kokoro publishes exactly one French
 *   voice and it is female — this hands the line to the system engine with the right locale rather
 *   than returning the wrong gender, because the system engine usually has both.
 * - **Heritage.** The speech community the tutor comes from, as a tag with a region. It selects the
 *   accent whenever the engine offers one for the language being spoken: Amara reads English as
 *   `en-GB`, Rohan as `en-IN`, Bruno reads Portuguese as `pt-BR` and never as `pt-PT`. And when a
 *   tutor teaches their own language it selects the voice outright, which is the case that matters
 *   most — Wen teaching Mandarin should be a Mandarin speaker, not an American one reading pinyin.
 *
 * ## Kokoro does not cover everything Prism teaches
 *
 * Kokoro ships voices for nine languages; Prism teaches sixteen. German, Dutch, Russian, Polish,
 * Turkish, Korean, Arabic and European Portuguese have no Kokoro voice at all, so those fall to
 * Android's own engine with the right locale — which is a real downgrade in quality and a complete
 * one in availability, and is still enormously better than silence.
 */
object LanguageVoices {

    /**
     * Prism's language codes to Kokoro's, where one exists.
     *
     * Brazilian Portuguese is Kokoro's only Portuguese, so European Portuguese borrows it: a
     * Brazilian accent reading European Portuguese is wrong in a way a learner will notice, and
     * still far better than an American one.
     */
    private val KOKORO_LANGUAGE: Map<String, KokoroVoices.Language> = mapOf(
        "en" to KokoroVoices.Language.AMERICAN_ENGLISH,
        "es" to KokoroVoices.Language.SPANISH,
        "fr" to KokoroVoices.Language.FRENCH,
        "it" to KokoroVoices.Language.ITALIAN,
        "hi" to KokoroVoices.Language.HINDI,
        "pt-BR" to KokoroVoices.Language.BRAZILIAN_PORTUGUESE,
        "pt" to KokoroVoices.Language.BRAZILIAN_PORTUGUESE,
        "ja" to KokoroVoices.Language.JAPANESE,
        "zh" to KokoroVoices.Language.MANDARIN,
        "zh-TW" to KokoroVoices.Language.MANDARIN,
    )

    /**
     * BCP-47 for the system engine, for every language Prism teaches.
     *
     * Regions are given where the bare code is ambiguous or where the default is wrong for the
     * learner: `zh` alone is read as Mandarin or Cantonese depending on the engine, and `pt`
     * defaults to European Portuguese for somebody who chose Brazilian.
     */
    private val LOCALES: Map<String, Locale> = mapOf(
        "en" to Locale.US,
        "es" to Locale("es", "ES"),
        "de" to Locale.GERMANY,
        "it" to Locale.ITALY,
        "fr" to Locale.FRANCE,
        "ja" to Locale.JAPAN,
        "ko" to Locale.KOREA,
        "pt-BR" to Locale("pt", "BR"),
        "pt" to Locale("pt", "PT"),
        "ru" to Locale("ru", "RU"),
        "zh" to Locale.CHINA,
        "zh-TW" to Locale.TAIWAN,
        "ar" to Locale("ar", "SA"),
        "nl" to Locale("nl", "NL"),
        "pl" to Locale("pl", "PL"),
        "tr" to Locale("tr", "TR"),
        "hi" to Locale("hi", "IN"),
    )

    /** The bare language of a tag: "en-IN" and "en" both answer "en". */
    private fun baseOf(code: String): String = code.substringBefore('-')

    private fun kokoroFor(code: String): KokoroVoices.Language? =
        KOKORO_LANGUAGE[code] ?: KOKORO_LANGUAGE[baseOf(code)]

    /**
     * The English accent a heritage implies, where Kokoro distinguishes one.
     *
     * Only English gets this treatment, because English is the only language in the catalogue with
     * two published accents. A British tutor reading English stays British; everyone else, Indian
     * heritage included, gets American, because "American" is what Kokoro has and inventing an
     * accent it does not have would be worse than claiming one falsely.
     */
    private fun accentedEnglish(heritage: String): KokoroVoices.Language =
        if (heritage.equals("en-GB", ignoreCase = true)) KokoroVoices.Language.BRITISH_ENGLISH
        else KokoroVoices.Language.AMERICAN_ENGLISH

    /**
     * The best Kokoro voice in [language] for this tutor, or null if there is none of their gender.
     *
     * Null rather than a mismatch on purpose. Handing a male tutor the single female French voice
     * is a worse outcome than falling through to the system engine, which on a modern Android
     * device usually has both — and the caller treats null as exactly that instruction.
     */
    private fun pick(language: KokoroVoices.Language, tutor: LanguageTutors.Tutor): String? {
        val candidates = KokoroVoices.ALL.filter {
            it.language == language && it.gender == tutor.gender
        }
        if (candidates.isEmpty()) return null
        // Best published grade first, then a stable tiebreak, so a learner is never handed a
        // different speaker from one lesson to the next.
        return candidates.sortedWith(compareBy({ it.quality }, { it.id })).first().id
    }

    /**
     * The Kokoro voice for the language being TAUGHT, in the tutor's gender.
     *
     * Null when Kokoro has no voice for this language, or none of the tutor's gender, which is the
     * signal to fall back to the system engine with [localeTag].
     */
    fun targetVoice(targetCode: String, tutor: LanguageTutors.Tutor): String? {
        val language = kokoroFor(targetCode) ?: return null
        val resolved =
            if (language == KokoroVoices.Language.AMERICAN_ENGLISH && baseOf(targetCode) == "en") {
                accentedEnglish(tutor.heritage)
            } else {
                language
            }
        return pick(resolved, tutor) ?: pick(language, tutor)
    }

    /**
     * The voice the tutor uses when speaking to the learner in the LEARNER'S language.
     *
     * The tutor's own accent applies where the engine has one; their gender applies always. Null
     * when neither can be honoured with Kokoro, and the system engine takes it with
     * [tutorLocaleTag].
     */
    fun tutorVoice(nativeCode: String, tutor: LanguageTutors.Tutor): String? =
        targetVoice(nativeCode, tutor)

    /**
     * The locale the tutor's own line should be spoken in.
     *
     * The learner's language, but in the tutor's regional variety when the two are compatible —
     * Rohan reads English as `en-IN`, Bruno reads Portuguese as `pt-BR`. A tutor whose heritage is
     * a different language entirely gets the learner's plain locale: there is no Arabic-accented
     * English voice to ask for, and asking an `ar-SA` voice to read English produces noise.
     */
    fun tutorLocaleTag(nativeCode: String, tutor: LanguageTutors.Tutor): String {
        if (baseOf(tutor.heritage).equals(baseOf(nativeCode), ignoreCase = true)) {
            return tutor.heritage
        }
        return localeTag(nativeCode)
    }

    /** The locale the system engine should speak [code] in. */
    fun locale(code: String): Locale =
        LOCALES[code] ?: LOCALES[baseOf(code)] ?: Locale.US

    fun localeTag(code: String): String = locale(code).toLanguageTag()

    /** Whether the target language gets a real neural voice or the system fallback. */
    fun hasNeuralVoice(targetCode: String): Boolean = kokoroFor(targetCode) != null
}
