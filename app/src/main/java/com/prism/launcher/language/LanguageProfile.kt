package com.prism.launcher.language

import com.prism.core.PrismPlatform

/**
 * Everything setup asked, kept so it can be asked once.
 *
 * ## Why a free-form map and not thirty named fields
 *
 * The setup flow is data ([LanguageSetupFlow]): its steps are a list, and adding a question is one
 * entry in that list. If every answer needed a matching property here, a getter, a setter and a key
 * constant, adding a question would touch four places and the two would drift — which is exactly
 * how a questionnaire ends up with a screen whose answer is never read.
 *
 * So answers are stored by step id, and the named accessors below exist only for the handful of
 * values that other parts of Prism genuinely need: the language, the learner's name, the tutor, the
 * voice settings. Everything else is read back by id when the tutor prompt is built.
 *
 * ## What is NOT here
 *
 * Nothing leaves the device and nothing is sent anywhere. This is a SharedPreferences file like
 * every other Prism setting, which is worth stating because the equivalent screens in a commercial
 * language app exist largely to build an advertising profile.
 */
object LanguageProfile {

    private const val PREFS = "prism_language"

    private const val KEY_ANSWER_PREFIX = "answer_"
    private const val KEY_COMPLETED = "setup_completed_v1"

    /** Multi-select answers keep their order and are stored as one line. */
    private const val MULTI_SEPARATOR = ""

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    // -- Raw answers ----------------------------------------------------------

    fun answer(stepId: String): String? =
        prefs().getString(KEY_ANSWER_PREFIX + stepId, null)?.takeIf { it.isNotBlank() }

    fun setAnswer(stepId: String, value: String) {
        prefs().edit().putString(KEY_ANSWER_PREFIX + stepId, value).apply()
    }

    fun answers(stepId: String): List<String> =
        answer(stepId)?.split(MULTI_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    fun setAnswers(stepId: String, values: List<String>) {
        setAnswer(stepId, values.joinToString(MULTI_SEPARATOR))
    }

    // -- The values the rest of Prism reads -----------------------------------

    // -- Several languages at once --------------------------------------------

    private const val KEY_LEARNING = "learning_codes"
    private const val KEY_ACTIVE = "active_code"
    private const val LIST_SEPARATOR = ""

    /**
     * Every language the learner has taken on, in the order they added them.
     *
     * Setup chooses the first; the + button on the page adds the rest. Stored as its own list
     * rather than inferred from which plans exist, because a learner who removes a language should
     * lose it from the switcher without losing the evidence of what they knew — the learner model
     * is keyed per language and deliberately survives.
     */
    fun learningCodes(): List<String> {
        val stored = prefs().getString(KEY_LEARNING, null)
            ?.split(LIST_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        if (stored.isNotEmpty()) return stored

        // Nothing stored yet: the language setup chose is the only one there is.
        return listOfNotNull(answer(LanguageSetupFlow.STEP_TARGET))
    }

    /** The language lessons are currently about. */
    fun activeCode(): String =
        prefs().getString(KEY_ACTIVE, null)?.takeIf { it in learningCodes() }
            ?: learningCodes().firstOrNull()
            ?: answer(LanguageSetupFlow.STEP_TARGET).orEmpty()

    fun setActive(code: String) {
        if (code !in learningCodes()) addLanguage(code)
        prefs().edit().putString(KEY_ACTIVE, code).apply()
    }

    /**
     * Takes on another language.
     *
     * Everything the learner said about HOW they want to learn — tutor manner, interests, minutes,
     * confidence, whether they want corrections — carries over, because none of it is about the
     * language. What does not carry over is the level: somebody who reached B1 in Spanish is not B1
     * in Korean, so a new language starts at A0 unless they say otherwise.
     */
    fun addLanguage(code: String) {
        if (code.isBlank()) return
        val current = learningCodes()
        if (code in current) return
        prefs().edit()
            .putString(KEY_LEARNING, (current + code).joinToString(LIST_SEPARATOR))
            .putString(levelKeyFor(code), "A0")
            .apply()
    }

    fun removeLanguage(code: String) {
        val remaining = learningCodes().filterNot { it == code }
        prefs().edit()
            .putString(KEY_LEARNING, remaining.joinToString(LIST_SEPARATOR))
            .apply()
        if (activeCode() == code) {
            remaining.firstOrNull()?.let { setActive(it) }
        }
    }

    fun isLearningSeveral(): Boolean = learningCodes().size > 1

    private fun levelKeyFor(code: String) = "level_$code"

    /**
     * The starting level for one language.
     *
     * Falls back to what setup asked, which is right for the language setup was about and wrong for
     * every language added afterwards — hence the per-language override written by [addLanguage].
     */
    fun startingLevelFor(code: String): String? =
        prefs().getString(levelKeyFor(code), null) ?: answer(LanguageSetupFlow.STEP_LEVEL)

    /** The language being learned, or null when setup has not chosen one. */
    fun targetLanguage(): LanguageCatalog.Language? = LanguageCatalog.learnable(activeCode())

    /** The language hints and translations are written in. Falls back to English. */
    fun nativeLanguage(): LanguageCatalog.Language =
        LanguageCatalog.native(answer(LanguageSetupFlow.STEP_NATIVE))
            ?: LanguageCatalog.NATIVE.first()

    /** What the tutors call the learner. Blank until they say. */
    fun learnerName(): String = answer(LanguageSetupFlow.STEP_NAME).orEmpty().trim()

    /** CEFR band as the learner self-reported it, e.g. "A2". */
    fun level(): String = answer(LanguageSetupFlow.STEP_LEVEL) ?: "A0"

    fun tutor(): LanguageTutors.Tutor? = LanguageTutors.byId(answer(LanguageSetupFlow.STEP_TUTOR))

    /** Speech rate for the tutor's voice, as a Kokoro speed multiplier. */
    fun speakingSpeed(): Float = when (answer(LanguageSetupFlow.STEP_SPEED)) {
        "relaxed" -> 0.8f
        "fast" -> 1.2f
        else -> 1.0f
    }

    /** Minutes per day the learner committed to. Drives the reminder and the session length. */
    fun dailyMinutes(): Int = answer(LanguageSetupFlow.STEP_MINUTES)?.toIntOrNull() ?: 15

    fun wantsReminders(): Boolean = answer(LanguageSetupFlow.STEP_REMINDERS) == "yes"

    fun wantsPronunciationHelp(): Boolean = answer(LanguageSetupFlow.STEP_PRONUNCIATION) == "yes"

    fun preferredManner(): LanguageTutors.Manner? =
        LanguageTutors.mannerFor(answer(LanguageSetupFlow.STEP_TUTOR_STYLE))

    /** The first lesson runs in the target language unless the learner asked to ease in. */
    fun startsInNative(): Boolean = answer(LanguageSetupFlow.STEP_FIRST_LESSON) == "native"

    // -- Completion -----------------------------------------------------------

    fun isSetUp(): Boolean = prefs().getBoolean(KEY_COMPLETED, false)

    fun markSetUp() {
        prefs().edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /**
     * Forgets everything, including the answers.
     *
     * Exists because "start over" has to be a real option: a learner who picked the wrong target
     * language on the first screen would otherwise be stuck with it forever, and clearing app data
     * would take the rest of Prism with it.
     */
    fun reset() {
        val editor = prefs().edit()
        LanguageSetupFlow.steps().forEach { editor.remove(KEY_ANSWER_PREFIX + it.id) }
        editor.remove(KEY_COMPLETED)
        editor.apply()
    }
}
