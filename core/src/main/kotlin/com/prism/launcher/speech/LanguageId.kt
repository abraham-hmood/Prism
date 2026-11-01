package com.prism.launcher.speech

import java.util.Locale

/**
 * What language a clip is spoken in.
 *
 * ## Why it is not measured from the waveform
 *
 * Every acoustic feature in [VocalFeatures] is a property of a VOICE; language is not. Two speakers
 * of different languages can share a pitch range, a speaking rate and a formant set, and two speakers
 * of the same language can share none of them -- so a classifier over this file's own features would
 * be guessing. What does identify language is a model of language, and this build already has one:
 * whisper, installed for dictation, transcribes speech and prints it in the language it was spoken.
 *
 * ## So the pipeline is: hear words, then read them
 *
 * [transcriber] is set by the platform to whatever transcription engine is installed (see
 * [SpeechDataset]'s callers). The transcript is then scored against per-language profiles here --
 * script ranges first, which is decisive where a script is used by one language, and stop words for
 * the Latin-alphabet languages, which is what the literature does and what works at a sentence of
 * input. Models that return the language themselves are not required: the words are enough, and this
 * keeps the dependency one method wide.
 *
 * With no transcriber at all the answer is [UNKNOWN], written as an empty cell. That is deliberate
 * rather than a gap: a wrong language in a training CSV teaches a model a wrong association, and
 * "not known" teaches it nothing, which is recoverable.
 */
object LanguageId {

    /** Whisper's rate, and what every engine here is fed. */
    const val SAMPLE_RATE = 16_000

    /** Written into the CSV when the language could not be established. */
    const val UNKNOWN = ""

    /**
     * Set by the platform: the clip's words, or null when this build cannot hear any.
     *
     * Assigning a transcriber directly -- a test, or a platform's own engine -- makes it a trusted
     * override: [unavailableReason] then stops asking the dictation registry, because the registry
     * is not what answers. [installFromDictation] sets the flag again after assigning its own hook.
     */
    @Volatile
    var transcriber: ((SpeechClip) -> String?)? = null
        set(value) {
            field = value
            fromDictation = false
        }

    /** True when [transcriber] is the dictation hook, rather than a test or platform override. */
    @Volatile
    private var fromDictation = false

    /**
     * Wires [transcriber] to whatever dictation engine this build actually has.
     *
     * Called from each platform's startup, after the engines are registered, because the engine list
     * is the platform's: Android and desktop both register whisper.cpp, and a build with no whisper
     * model chosen answers [UNKNOWN] for every clip until one is -- which is the honest answer, and
     * the one the CSV shows as an empty cell.
     *
     * The engine is resolved PER CLIP rather than captured once. A model chosen in settings after
     * startup has to be picked up without a restart, and a dataset prepared while the first model was
     * still downloading should start detecting languages when that download finishes, not only after
     * the next launch.
     */
    fun installFromDictation(): Boolean {
        transcriber = { clip -> transcribe(clip) }
        fromDictation = true
        return com.prism.launcher.messaging.Dictation.preferred() != null
    }

    /**
     * Why nothing can be transcribed right now, or null when something can.
     *
     * Auto-transcription must say WHICH thing is missing -- no engine registered, no whisper model
     * chosen, no cloud key -- rather than report every clip as silent. A transcriber installed as a
     * test or platform override is trusted as it is; only the dictation hook can be unavailable
     * because its engine list says so.
     */
    fun unavailableReason(): String? {
        if (transcriber == null) return "No transcription is attached to this build."
        if (!fromDictation) return null
        val dictation = com.prism.launcher.messaging.Dictation
        if (dictation.all().isEmpty()) {
            return "Auto-transcription needs a transcription engine: none is registered in this build."
        }
        if (dictation.available().isNotEmpty()) return null
        return "Auto-transcription needs a transcription engine: " +
            dictation.unavailableReason().ifBlank { "none is available" }
    }

    /** The clip's words through the best available dictation engine, at whisper's own rate. */
    private fun transcribe(clip: SpeechClip): String? {
        val engine = com.prism.launcher.messaging.Dictation.preferred() ?: return null
        val audio = clip.resampled(SAMPLE_RATE)
        // No language hint: detecting the language is the whole point, and a hint of "en" would make
        // whisper write English words over Spanish speech and this file report English with
        // confidence. The engine's own detection is the measurement.
        return engine.transcribe(audio.pcm16(), SAMPLE_RATE, null)
    }

    /** One answer, with how it was reached -- recorded in the features CSV's own metadata. */
    data class Detected(val code: String, val confidence: Double, val source: String)

    /**
     * What the transcriber heard in one clip: the words, and the language those words are in.
     *
     * The words are kept rather than thrown away after detection because the dataset creator needs
     * both, and transcribing a clip twice would cost the user the same wait twice. [text] is null
     * for the same reasons [detect] can answer [UNKNOWN]: no engine, a clip too short to hold a
     * sentence, or nothing intelligible.
     */
    data class Spoken(val text: String?, val language: Detected)

    /**
     * The language of [clip].
     *
     * A clip shorter than [MIN_SECONDS] is not sent to the transcriber at all: an isolated syllable
     * has no words to score, and whisper's answer on one is a guess this would write down as data.
     */
    fun detect(clip: SpeechClip): Detected = listen(clip).language

    /**
     * Transcribes [clip] once and scores the resulting words.
     *
     * [detect] is the narrow answer used by the feature pass; this is the whole answer, for callers
     * that also need the words themselves (DatasetCraft's auto-fill writes both columns).
     */
    fun listen(clip: SpeechClip): Spoken {
        val transcribe = transcriber ?: return Spoken(null, Detected(UNKNOWN, 0.0, "no transcriber"))
        if (clip.seconds < MIN_SECONDS) {
            return Spoken(null, Detected(UNKNOWN, 0.0, "clip shorter than ${MIN_SECONDS}s"))
        }
        val text = runCatching { transcribe(clip.resampled(SAMPLE_RATE)) }.getOrNull()
        if (text.isNullOrBlank()) return Spoken(null, Detected(UNKNOWN, 0.0, "no transcription"))
        val guessed = guessFromText(text)
        val detected = if (guessed.code == UNKNOWN) {
            Detected(UNKNOWN, 0.0, "transcribed, language not recognised")
        } else {
            Detected(guessed.code, guessed.confidence, "transcript")
        }
        return Spoken(text, detected)
    }

    /**
     * The most likely language of [text], with the margin over the runner-up as its confidence.
     *
     * A wide margin (English text against German stop words, say) reports near 1.0; text that scores
     * evenly between two related languages reports near 0, which is the honest reading of it.
     */
    fun guessFromText(text: String): Detected {
        val lower = text.lowercase(Locale.ROOT)
        scriptLanguage(lower)?.let { return Detected(it, 0.95, "script") }

        val scores = PROFILES.mapValues { (_, words) ->
            words.count { word -> containsWord(lower, word) }.toDouble()
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return Detected(UNKNOWN, 0.0, "none")
        if (best.value <= 0.0) return Detected(UNKNOWN, 0.0, "no profile matched")
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val confidence = if (best.value > 0.0) (best.value - second) / best.value else 0.0
        return Detected(best.key, confidence.coerceIn(0.0, 1.0), "profile")
    }

    /** Whether [word] appears in [text] as a whole word, not inside a longer one. */
    private fun containsWord(text: String, word: String): Boolean {
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = if (index == 0) ' ' else text[index - 1]
            val afterIndex = index + word.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetter() && !after.isLetter()) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }

    /**
     * Languages decided by the alphabet they are written in.
     *
     * Only used where the answer is nearly unambiguous: Japanese kana appear in no other language's
     * text, Hangul is Korean, Greek is Greek. Han characters alone go to Chinese, which is the
     * majority case and the one whisper's own output matches -- Japanese text that reaches here
     * without kana is unusual and is reported as Chinese rather than guessed at.
     */
    private fun scriptLanguage(text: String): String? = when {
        text.any { it in '\u3040'..'\u30FF' } -> "ja"
        text.any { it in '\uAC00'..'\uD7AF' } -> "ko"
        text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
        text.any { it in '\u0600'..'\u06FF' } -> if (containsWord(text, "است")) "fa" else "ar"
        text.any { it in '\u0590'..'\u05FF' } -> "he"
        text.any { it in '\u0900'..'\u097F' } -> "hi"
        text.any { it in '\u0980'..'\u09FF' } -> "bn"
        text.any { it in '\u0B80'..'\u0BFF' } -> "ta"
        text.any { it in '\u0E00'..'\u0E7F' } -> "th"
        text.any { it in '\u0370'..'\u03FF' } -> "el"
        text.any { it in '\u0400'..'\u04FF' } ->
            if (containsWord(text, "це") || containsWord(text, "та")) "uk" else "ru"
        else -> null
    }

    private const val MIN_SECONDS = 1.0

    /**
     * The stop words each language is recognised by.
     *
     * The most frequent words of each language, which is what makes this work at all: a sentence of
     * any length contains several of them, and they are the words a language never lends out. Chosen
     * to be distinctive rather than exhaustive -- "in", "on" and "no" are shared by so many languages
     * that scoring them separates nothing.
     */
    private val PROFILES: Map<String, List<String>> = mapOf(
        "en" to listOf("the", "and", "of", "to", "that", "with", "this", "have", "from", "your", "what", "there", "which", "about"),
        "es" to listOf("que", "de", "no", "una", "para", "con", "los", "las", "pero", "muy", "esto", "como", "también", "porque"),
        "fr" to listOf("les", "des", "une", "est", "pas", "pour", "dans", "avec", "vous", "nous", "mais", "cette", "tout", "plus"),
        "de" to listOf("der", "die", "und", "nicht", "ist", "mit", "sich", "auch", "auf", "eine", "aber", "wie", "wir", "noch"),
        "it" to listOf("che", "non", "una", "sono", "per", "con", "questo", "anche", "della", "più", "come", "gli", "molto", "perché"),
        "pt" to listOf("que", "não", "uma", "para", "com", "os", "mais", "como", "isso", "você", "muito", "também", "porque", "este"),
        "nl" to listOf("het", "een", "niet", "van", "dat", "met", "zijn", "voor", "maar", "ook", "deze", "nog", "wel", "aan"),
        "sv" to listOf("och", "att", "det", "som", "inte", "med", "för", "jag", "den", "har", "men", "var", "han", "hon"),
        "no" to listOf("og", "ikke", "det", "som", "jeg", "med", "til", "er", "den", "har", "men", "han", "hun", "også"),
        "da" to listOf("og", "ikke", "det", "som", "jeg", "med", "til", "er", "den", "har", "men", "hun", "også", "der"),
        "fi" to listOf("ja", "on", "ei", "että", "se", "kun", "mutta", "myös", "tämä", "joka", "hän", "oli", "niin", "vain"),
        "pl" to listOf("nie", "się", "jest", "tak", "jak", "ale", "tego", "tylko", "może", "bardzo", "które", "oraz", "przez", "kiedy"),
        "cs" to listOf("není", "jsem", "tak", "jako", "ale", "která", "který", "velmi", "může", "protože", "ještě", "také", "jen", "když"),
        "ro" to listOf("este", "sunt", "care", "dar", "pentru", "acest", "foarte", "dar", "sau", "când", "numai", "între", "fost", "mai"),
        "hu" to listOf("hogy", "nem", "egy", "csak", "mint", "még", "ezt", "vagy", "nagyon", "lehet", "azonban", "már", "így", "mert"),
        "tr" to listOf("bir", "ama", "çok", "daha", "için", "gibi", "değil", "sonra", "kendi", "bunu", "olan", "ile", "her", "yani"),
        "id" to listOf("yang", "dan", "ini", "itu", "dengan", "tidak", "untuk", "dari", "akan", "juga", "saya", "kamu", "adalah", "bisa"),
        "vi" to listOf("của", "và", "không", "được", "trong", "những", "người", "này", "cho", "một", "với", "cũng", "như", "rất"),
        "sw" to listOf("na", "kwa", "hii", "yake", "sana", "lakini", "kama", "hata", "watu", "kila", "bado", "ndiyo"),
    )
}
