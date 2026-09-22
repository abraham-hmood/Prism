package com.prism.launcher.language

/**
 * Layer 2: what this lesson is, decided by code before any model is asked anything.
 *
 * ## The whole point
 *
 * The model's job is to write the sentences. Deciding WHICH WORDS may appear in them is not a
 * writing job, it is a curriculum job, and a model cannot do it because it does not know what the
 * learner already has. Worse, it cannot be told to stay at A1 — every model drifts upward within
 * three sentences, which is the single best-observed failure of LLM language teaching.
 *
 * So this is computed first, deterministically, from the plan and the learner model:
 *
 * - the objective, from the plan
 * - N new words, in frequency order, that the learner has not met
 * - M grammar patterns, in level order, likewise
 * - whatever FSRS says is due for review right now
 * - a topic from the learner's own interests
 * - and [allowedConcepts] — the explicit set of things that may be said
 *
 * [LessonPrompt] turns it into a brief. [LessonValidator] checks the output against it. The model
 * sits in the middle and writes copy.
 *
 * ## Why a spec is not stored
 *
 * It is recomputed when the lesson opens, on purpose. Review items due *now* are not the ones that
 * were due when the plan was built, and a learner who has done forty lessons since should get a
 * different selection. The plan is fixed; the spec is live.
 */
data class LessonSpec(
    val lessonId: String,
    val level: Cefr.Level,
    val kind: Cefr.LessonKind,
    val objective: String,
    val topic: String?,

    val targetCode: String,
    val nativeCode: String,
    val targetName: String,
    val nativeName: String,

    val newWords: List<Lexicon.Word>,
    val newPatterns: List<GrammarSyllabus.Pattern>,
    val reviewWords: List<Lexicon.Word>,
    val reviewPatterns: List<GrammarSyllabus.Pattern>,

    /**
     * Every concept the tutor may use. Known words plus this lesson's new ones, and nothing else.
     *
     * This is what makes the gate possible: without an explicit set there is no way to tell an
     * intentional new word from a model wandering off, and both look like "a word the learner has
     * not seen".
     */
    val allowedConcepts: Set<String>,

    /** The pictures for an A0 lesson, in the order they are shown. Empty above A0. */
    val pictures: List<PictureItem>,

    val scenario: ScenarioBank.Scenario?,

    /** How the learner answers: out loud, or typed. */
    val mode: Mode,

    /** Reading aid for non-Latin scripts. Faded out by level; see [showRomanisation]. */
    val showRomanisation: Boolean,

    /** How many exchanges before the lesson is done. */
    val turnTarget: Int,

    val rubric: Rubric,
    val tutor: TutorBrief,
) {
    /**
     * How the learner replies.
     *
     * Speaking is the default because speaking is the point. Writing exists for the lessons where
     * saying it aloud would teach the wrong thing: a grammar pattern needs to be seen assembled,
     * and a script lesson is about the hand, not the mouth.
     */
    enum class Mode { SPEAKING, WRITING }

    /** One picture step of an A0 lesson. */
    data class PictureItem(
        val conceptId: String,
        val word: String,
        val romanisation: String?,
        val emoji: String?,
        val imageQuery: String?,
    )

    /**
     * What the tutor marks, and what they let go.
     *
     * Carried into the prompt so that a learner who said they only want to be understood is not
     * corrected on their accent, and one preparing for an exam is. The rubric is also what the
     * post-lesson report is built from, which is why it is a list of named criteria rather than
     * free text.
     */
    data class Rubric(
        val correctPronunciation: Boolean,
        val correctGrammar: Boolean,
        val pushForLonger: Boolean,
        val allowNativeLanguage: Boolean,
        val criteria: List<String>,
    )

    /** Everything the tutor needs to be themselves, flattened out of the profile. */
    data class TutorBrief(
        val name: String,
        val accent: String,
        val manner: String,
        val learnerName: String,
        val opensFirst: Boolean,
        val challenges: Boolean,
        val confidence: String,
    )

    val isPicture: Boolean get() = kind == Cefr.LessonKind.PICTURE

    /**
     * Whether the tutor supplies its own vocabulary this lesson.
     *
     * Above B2 the authored lists stop — see [ConceptBands] — so the tutor introduces words from
     * the learner's own topic and declares them, and they are scheduled as open terms. Below that
     * the list is the syllabus and the tutor may not invent additions to it.
     */
    val usesOpenVocabulary: Boolean get() = level.ordinal >= Cefr.Level.B2.ordinal

    /** Every word the lesson will actually put in front of the learner. */
    fun allWords(): List<Lexicon.Word> = newWords + reviewWords

    /** The item ids this lesson grades, for the learner model to be updated against. */
    fun gradedItems(): List<String> =
        (newWords + reviewWords).map { LearnerModel.vocabId(it.conceptId) } +
            (newPatterns + reviewPatterns).map { LearnerModel.grammarId(it.id) }
}

/**
 * Builds a [LessonSpec] from the plan, the learner model and the profile.
 *
 * Pure and deterministic given the same inputs and clock, which is what lets it be tested without a
 * device and without a model.
 */
object LessonSpecBuilder {

    /** New words per lesson, by level. */
    private fun newWordBudget(kind: Cefr.LessonKind, level: Cefr.Level): Int = when {
        kind == Cefr.LessonKind.PICTURE -> 8
        kind == Cefr.LessonKind.REVIEW -> 0
        kind == Cefr.LessonKind.VOCABULARY -> 6
        level.ordinal <= Cefr.Level.A2.ordinal -> 4
        else -> 3
    }

    private fun reviewBudget(kind: Cefr.LessonKind): Int =
        if (kind == Cefr.LessonKind.REVIEW) 12 else 4

    /**
     * How many exchanges a lesson runs for.
     *
     * Short at the bottom, because eight turns of single words is plenty and a beginner runs out of
     * language before they run out of willingness. Longer higher up, where the whole skill being
     * practised is sustaining something.
     */
    private fun turnTarget(kind: Cefr.LessonKind, level: Cefr.Level): Int = when (kind) {
        Cefr.LessonKind.PICTURE -> 8
        Cefr.LessonKind.PRONUNCIATION -> 8
        Cefr.LessonKind.VOCABULARY -> 8
        Cefr.LessonKind.REVIEW -> 10
        Cefr.LessonKind.PATTERN -> 6
        Cefr.LessonKind.LISTENING -> 8
        Cefr.LessonKind.ASSESSMENT -> 12
        else -> if (level.ordinal <= Cefr.Level.A2.ordinal) 10 else 14
    }

    /**
     * Whether the learner answers out loud or with the keyboard.
     *
     * Speaking is the default and the overwhelming majority, because speaking is the entire point
     * of the feature. Three cases genuinely need the keyboard, and only three:
     *
     * - **A0 is never one of them.** The whole level is see-a-picture, hear-a-word, say-it-back.
     *   An earlier version matched "writing system" as a substring and so caught *Reading* the
     *   writing system too, which made the first lesson of every new-script language a typing
     *   exercise. That was the bug.
     * - **Producing a script.** Forming a character is a thing the hand learns. Saying it aloud
     *   teaches the sound, which is a different lesson and already exists.
     * - **Grammar patterns.** A pattern has to be seen assembled to be understood; dictating a
     *   sentence you cannot see does not teach word order.
     * - **A written exam task**, because that is what the learner is being prepared for. An
     *   interview checkpoint stays spoken.
     */
    private fun modeOf(
        kind: Cefr.LessonKind,
        level: Cefr.Level,
        objective: String,
        examGoal: Boolean,
    ): LessonSpec.Mode = when {
        level == Cefr.Level.A0 -> LessonSpec.Mode.SPEAKING
        objective.startsWith("Writing", ignoreCase = true) -> LessonSpec.Mode.WRITING
        objective.startsWith("Write", ignoreCase = true) -> LessonSpec.Mode.WRITING
        kind == Cefr.LessonKind.PATTERN -> LessonSpec.Mode.WRITING
        kind == Cefr.LessonKind.ASSESSMENT && examGoal -> LessonSpec.Mode.WRITING
        else -> LessonSpec.Mode.SPEAKING
    }

    /**
     * When the reading aid comes off.
     *
     * A0 and A1 get it, A2 keeps it only for the genuinely opaque scripts, B1 and above never. A
     * course that never withdraws it produces learners who cannot read the language, which is the
     * commonest complaint about Japanese and Chinese apps and is entirely self-inflicted.
     */
    private fun romanisationFor(code: String, level: Cefr.Level): Boolean {
        val script = LanguageDistance.scriptOf(code)
        val latin = script == LanguageDistance.Script.LATIN
        if (latin) return false
        return when (level) {
            Cefr.Level.A0, Cefr.Level.A1 -> true
            Cefr.Level.A2 -> script in setOf(
                LanguageDistance.Script.HAN,
                LanguageDistance.Script.KANA_HAN,
                LanguageDistance.Script.ARABIC,
            )
            else -> false
        }
    }

    fun build(
        lesson: PlannedLesson,
        plan: LearningPlan,
        model: LearnerModel,
        profile: Profile,
        now: Long,
    ): LessonSpec {
        val code = plan.targetCode
        val level = lesson.level

        val newWordCount = newWordBudget(lesson.kind, level)
        val newWords = if (lesson.kind == Cefr.LessonKind.PICTURE) {
            // A picture lesson draws from its own objective's category, so the lesson called
            // "Animals" teaches animals rather than the next eight words in the global order.
            val fromCategory = Lexicon.conceptsFor(code, lesson.objective)
                .map { it.second }
                .filter { it.conceptId !in model.encounteredConcepts() }
            fromCategory.take(newWordCount)
                .ifEmpty { model.nextNewConcepts(code, newWordCount, level) }
        } else {
            // At or below the learner's level, never above: handing a B1 learner "assumption"
            // before they have "because" is how a syllabus stops being a sequence.
            model.nextNewConcepts(code, newWordCount, level)
        }

        val newPatterns = if (lesson.kind == Cefr.LessonKind.PATTERN) {
            model.nextNewPatterns(code, level, 1)
        } else {
            emptyList()
        }

        val dueIds = model.due(now, reviewBudget(lesson.kind))
        val reviewWords = dueIds.filter { it.startsWith(LearnerModel.VOCAB_PREFIX) }
            .mapNotNull { Lexicon.word(code, it.removePrefix(LearnerModel.VOCAB_PREFIX)) }
        val reviewPatterns = dueIds.filter { it.startsWith(LearnerModel.GRAMMAR_PREFIX) }
            .mapNotNull { GrammarSyllabus.byId(it.removePrefix(LearnerModel.GRAMMAR_PREFIX)) }

        val allowed = model.knownConcepts(now) +
            newWords.map { it.conceptId } +
            reviewWords.map { it.conceptId }

        val pictures = if (lesson.kind == Cefr.LessonKind.PICTURE) {
            newWords.mapNotNull { word ->
                Concepts.of(word.conceptId)?.let { concept ->
                    LessonSpec.PictureItem(
                        conceptId = concept.id,
                        word = word.word,
                        romanisation = word.romanisation,
                        emoji = concept.emoji,
                        imageQuery = concept.imageQuery,
                    )
                }
            }
        } else {
            emptyList()
        }

        val scenario = when (lesson.kind) {
            Cefr.LessonKind.ROLEPLAY, Cefr.LessonKind.CONVERSATION, Cefr.LessonKind.ASSESSMENT ->
                ScenarioBank.forLesson(lesson.id, level, profile.interests)
            else -> null
        }

        return LessonSpec(
            lessonId = lesson.id,
            level = level,
            kind = lesson.kind,
            objective = lesson.objective,
            topic = lesson.topic,
            targetCode = code,
            nativeCode = plan.nativeCode,
            targetName = profile.targetName,
            nativeName = profile.nativeName,
            newWords = newWords,
            newPatterns = newPatterns,
            reviewWords = reviewWords,
            reviewPatterns = reviewPatterns,
            allowedConcepts = allowed,
            pictures = pictures,
            scenario = scenario,
            mode = modeOf(lesson.kind, level, lesson.objective, profile.examGoal),
            showRomanisation = romanisationFor(code, level),
            turnTarget = turnTarget(lesson.kind, level),
            rubric = rubricFor(profile, lesson.kind, level),
            tutor = LessonSpec.TutorBrief(
                name = profile.tutorName,
                accent = profile.tutorAccent,
                manner = profile.tutorManner,
                learnerName = profile.learnerName,
                opensFirst = profile.afraidToStart,
                challenges = profile.wantsChallenge,
                confidence = profile.confidence,
            ),
        )
    }

    private fun rubricFor(
        profile: Profile,
        kind: Cefr.LessonKind,
        level: Cefr.Level,
    ): LessonSpec.Rubric {
        val criteria = buildList {
            add("Did they achieve what the lesson asked?")
            if (profile.wantsPronunciation) add("Was it clear enough to be understood?")
            if (level.ordinal >= Cefr.Level.A2.ordinal) add("Was the grammar right for the level?")
            if (level.ordinal >= Cefr.Level.B1.ordinal) add("Did they say enough, not just enough words?")
            if (profile.pronunciationGoal == "native") add("How close to a native speaker did it sound?")
        }
        return LessonSpec.Rubric(
            correctPronunciation = profile.wantsPronunciation && kind != Cefr.LessonKind.CONVERSATION,
            // Below A2 there is not enough grammar to correct, and correcting a beginner's word
            // order is how you stop them speaking.
            correctGrammar = level.ordinal >= Cefr.Level.A2.ordinal,
            pushForLonger = profile.wantsChallenge && level.ordinal >= Cefr.Level.B1.ordinal,
            // A0 never allows the native language. That is the entire level.
            allowNativeLanguage = level != Cefr.Level.A0,
            criteria = criteria,
        )
    }

    /**
     * The parts of the setup answers the spec needs, flattened.
     *
     * A plain value so this whole object stays Android-free and testable; the app fills it from
     * LanguageProfile.
     */
    data class Profile(
        val learnerName: String,
        val targetName: String,
        val nativeName: String,
        val tutorName: String,
        val tutorAccent: String,
        val tutorManner: String,
        val interests: List<String>,
        val wantsPronunciation: Boolean,
        val wantsChallenge: Boolean,
        val afraidToStart: Boolean,
        val confidence: String,
        val pronunciationGoal: String,
        /** True when the learner is preparing for a written exam; makes checkpoints typed. */
        val examGoal: Boolean = false,
    )
}
