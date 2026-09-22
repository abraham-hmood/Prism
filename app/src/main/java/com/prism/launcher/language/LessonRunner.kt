package com.prism.launcher.language

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.messaging.AiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one lesson: the loop between the learner, the model, and the gate.
 *
 * ## The loop
 *
 * ```
 *   spec  ──►  brief + transcript  ──►  model  ──►  parse  ──►  VALIDATE
 *                     ▲                                            │
 *                     └────── retry, naming what was wrong ◄───────┤ fail
 *                                                                  │ pass
 *                            learner hears it, speaks back  ◄──────┘
 * ```
 *
 * Nothing reaches the learner that has not passed [LessonValidator]. After [MAX_ATTEMPTS] failures
 * the turn falls back to [LessonTemplates] — a drill line built from the lexicon, which is always
 * correct because no model wrote it.
 *
 * ## Why the retry names the problem
 *
 * Telling a model "try again" gets the same output. Telling it "you used *ferrocarril*, which the
 * student has never seen — say it again using only the words listed above" gets a different one,
 * and usually a correct one on the first retry. The failed attempt is quoted back deliberately.
 *
 * ## Backend indifference
 *
 * Everything goes through [AiManager], which is already the thing that decides between the local
 * GGUF, a cloud API and Ollama based on the user's settings. The lesson does not know or care
 * which — it hands over a string and gets one back. That is also why the whole transcript is
 * re-sent each turn rather than relying on any backend's own memory: see [LessonPrompt].
 */
class LessonRunner(
    private val context: Context,
    val spec: LessonSpec,
) {

    /** One thing the tutor says, after it has been checked. */
    data class TutorTurn(
        /** What the tutor says, in the learner's own language. */
        val say: String,
        /** The word or sentence being taught, in the target language. Null on a pure reaction. */
        val teach: String?,
        val hint: String?,
        val note: String?,
        val done: Boolean,
        /** True when a model could not produce a usable line and the drill stood in. */
        val fromTemplate: Boolean,
        /** Which picture this turn is about, at A0. */
        val picture: LessonSpec.PictureItem?,
    )

    /**
     * The turn as one line for the transcript.
     *
     * The model needs to see both halves of what it said last time or it loses the thread — a
     * transcript of only the native-language chatter reads as a conversation in which nothing was
     * ever taught.
     */
    private fun TutorTurn.transcriptLine(): String =
        listOfNotNull(say.takeIf { it.isNotBlank() }, teach?.let { "[$it]" }).joinToString(" ")

    /** How the lesson ended, and everything the learner model and the stats need from it. */
    data class Outcome(
        val report: LessonPrompt.Report,
        val transcript: List<LessonPrompt.Turn>,
        val ratings: Map<String, Fsrs.Rating>,
        val usedTemplate: Boolean,
        /** Points awarded for this lesson. See LanguageStats.xpForLesson. */
        val xpEarned: Int,
    )

    private val transcript = mutableListOf<LessonPrompt.Turn>()

    /**
     * Words the tutor said it introduced, above B2 where no authored list exists.
     *
     * Collected across the whole lesson and scheduled at the end like any other item — a word met
     * once in a C1 conversation and never scheduled is a word the learner will not have next week.
     */
    private val declaredTerms = linkedSetOf<String>()
    private var pictureIndex = 0
    private var templateTurns = 0
    private var everFellBack = false

    /** Set once the tutor or the learner has said the lesson is finished. */
    var finished: Boolean = false
        private set

    val turns: List<LessonPrompt.Turn> get() = transcript.toList()

    /** The picture currently on screen, or null above A0. */
    fun currentPicture(): LessonSpec.PictureItem? =
        spec.pictures.getOrNull((pictureIndex - 1).coerceAtLeast(0))

    // -- The loop -------------------------------------------------------------

    /** The tutor's opening line. */
    suspend fun open(): TutorTurn {
        val picture = spec.pictures.firstOrNull()
        if (picture != null) pictureIndex = 1

        val turn = ask(LessonPrompt.opening(spec), attempt = 0)
        record(fromTutor = true, turn.transcriptLine())
        return turn.copy(picture = picture)
    }

    /** Sends what the learner said and returns what the tutor says back. */
    suspend fun reply(learnerSaid: String): TutorTurn {
        record(fromTutor = false, learnerSaid)

        val prompt = LessonPrompt.turn(spec, transcript.dropLast(1), learnerSaid)
        var turn = ask(prompt, attempt = 0)

        // A0 walks a fixed list of pictures, so the lesson's shape is owned here rather than by the
        // model: it advances one picture per exchange and finishes when the list runs out.
        if (spec.isPicture) {
            val next = spec.pictures.getOrNull(pictureIndex)
            pictureIndex++
            turn = if (next != null) {
                // The picture list owns the sequence at A0; the tutor's own line is kept, and only
                // the taught word is replaced with the one matching the picture now on screen.
                turn.copy(teach = next.word, picture = next, done = false)
            } else {
                turn.copy(done = true, picture = currentPicture())
            }
        }

        if (transcript.count { it.fromTutor } >= spec.turnTarget) {
            turn = turn.copy(done = true)
        }

        record(fromTutor = true, turn.transcriptLine())
        if (turn.done) finished = true
        return turn
    }

    /**
     * Asks the model, checks what comes back, and retries with the problem named.
     *
     * Recursive on [attempt] rather than looping so the retry prompt can be built from the failed
     * attempt it is responding to.
     */
    private suspend fun ask(prompt: String, attempt: Int): TutorTurn {
        if (attempt >= MAX_ATTEMPTS) return fallback()

        val raw = generate(prompt) ?: return fallback()
        val reply = LessonPrompt.parse(raw)
        val verdict = LessonValidator.validate(reply.say, reply.teach, spec)

        if (verdict.ok) {
            if (spec.usesOpenVocabulary) declaredTerms.addAll(reply.newTerms)
            return TutorTurn(
                say = reply.say,
                teach = reply.teach,
                hint = reply.hint,
                note = reply.note?.takeIf { spec.rubric.correctGrammar || spec.rubric.correctPronunciation },
                done = reply.done,
                fromTemplate = false,
                picture = null,
            )
        }

        PrismLogger.logWarning(
            TAG,
            "Lesson ${spec.lessonId} attempt ${attempt + 1} rejected: ${verdict.problems} " +
                if (verdict.offendingWords.isEmpty()) "" else "words=${verdict.offendingWords}",
        )
        val rejected = listOfNotNull(
            reply.say.takeIf { it.isNotBlank() }?.let { "SAY: $it" },
            reply.teach?.let { "TEACH: $it" },
        ).joinToString("\n")
        return ask(retryPrompt(prompt, rejected, verdict), attempt + 1)
    }

    /** The original brief, plus exactly what was wrong with the last attempt. */
    private fun retryPrompt(
        original: String,
        rejected: String,
        verdict: LessonValidator.Result,
    ): String = buildString {
        append(original)
        appendLine()
        appendLine("YOUR LAST ANSWER WAS REJECTED. You wrote:")
        appendLine(rejected)
        appendLine()
        appendLine("Why it was rejected:")
        verdict.problems.forEach { problem ->
            appendLine(
                when (problem) {
                    LessonValidator.Problem.WRONG_SCRIPT ->
                        "- TEACH was not written in ${spec.targetName}. Use ${spec.targetName} there."
                    LessonValidator.Problem.TAUGHT_IN_NATIVE ->
                        "- TEACH came back in ${spec.nativeName}. TEACH must be ${spec.targetName}."
                    LessonValidator.Problem.NOTHING_TAUGHT ->
                        "- You taught nothing. TEACH must contain the ${spec.targetName} word."
                    LessonValidator.Problem.UNKNOWN_WORDS ->
                        "- You used words the student has never met: " +
                            verdict.offendingWords.joinToString(", ") +
                            ". Use only the words listed in this brief."
                    LessonValidator.Problem.TOO_LONG ->
                        "- Far too long. One short line, sayable in a single breath."
                    LessonValidator.Problem.LEAKED_INSTRUCTIONS ->
                        "- You quoted your instructions or mentioned being an AI. Never do that."
                    LessonValidator.Problem.EMPTY -> "- You wrote nothing."
                }
            )
        }
        appendLine()
        appendLine("Write it again, correctly, in the reply format.")
    }

    /** The drill line. Always correct, because the lexicon wrote it rather than a model. */
    private fun fallback(): TutorTurn {
        everFellBack = true
        val reply = if (templateTurns == 0) {
            LessonTemplates.opening(spec)
        } else {
            LessonTemplates.next(spec, templateTurns)
        }
        templateTurns++
        return TutorTurn(
            say = reply.say,
            teach = reply.teach,
            hint = reply.hint,
            note = null,
            done = reply.done,
            fromTemplate = true,
            picture = null,
        )
    }

    private suspend fun generate(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            // plainText: a lesson wants a completion, not Sam. Whichever engine the user chose --
            // on-device GGUF, a cloud endpoint, or an Ollama server on their network -- is the only
            // thing that should decide where this goes.
            val (text, _) = AiManager.getResponse(
                context, prompt, allowOffload = false, plainText = true,
            )
            text.takeIf { it.isNotBlank() && !it.startsWith("Error:") && !it.startsWith("Local AI Error") }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The learner left the lesson. That is not a model failure and must not be treated as
            // one: catching it here would spend a template turn and mark the lesson as having
            // fallen back, on a screen nobody is looking at any more.
            throw cancelled
        } catch (failure: Throwable) {
            PrismLogger.logWarning(TAG, "Lesson generation failed: ${failure.message}")
            null
        }
    }

    private fun record(fromTutor: Boolean, text: String) {
        if (text.isNotBlank()) transcript.add(LessonPrompt.Turn(fromTutor, text))
    }

    // -- Ending ---------------------------------------------------------------

    /**
     * Grades the lesson and writes the result into the learner model and the stats.
     *
     * The grading pass is a separate request on purpose — a tutor asked to converse and mark at the
     * same time does neither well. When it fails, [LessonTemplates] produces an honest report that
     * scores only what can be observed without a model.
     */
    suspend fun finish(minutesSpent: Int): Outcome {
        finished = true

        val learnerTurns = transcript.count { !it.fromTutor }
        val report = if (everFellBack && transcript.none { it.fromTutor && !it.text.isBlank() }) {
            LessonTemplates.report(spec, learnerTurns)
        } else {
            generate(LessonPrompt.report(spec, transcript))
                ?.let { LessonPrompt.parseReport(it, spec) }
                ?: LessonTemplates.report(spec, learnerTurns)
        }

        // Concept ratings arrive keyed by concept; the learner model keys by namespaced item id.
        val ratings = mutableMapOf<String, Fsrs.Rating>()
        report.wordRatings.forEach { (conceptId, rating) ->
            ratings[LearnerModel.vocabId(conceptId)] = rating
        }
        // Review words the lesson brought back were graded by being used. Absent an explicit rating
        // for them, a successful lesson counts as GOOD — they were met and nothing went wrong.
        spec.reviewWords.forEach { ratings.putIfAbsent(LearnerModel.vocabId(it.conceptId), Fsrs.Rating.GOOD) }
        spec.newPatterns.forEach { ratings.putIfAbsent(LearnerModel.grammarId(it.id), Fsrs.Rating.GOOD) }
        // Open terms are new by definition, so they enter at HARD: seen once, worth seeing again
        // soon. Grading them GOOD would schedule them a month out on the strength of one exposure.
        declaredTerms.forEach { ratings.putIfAbsent(LearnerModel.termId(it), Fsrs.Rating.HARD) }
        spec.reviewPatterns.forEach { ratings.putIfAbsent(LearnerModel.grammarId(it.id), Fsrs.Rating.GOOD) }

        val xp = LanguageStats.xpForLesson(spec.level, report.score, ratings.size)

        LanguageLearnerStore.recordLesson(spec.targetCode, ratings)
        LanguagePlanStore.markComplete(spec.lessonId)
        LanguageStats.recordLesson(minutesSpent, xp)

        return Outcome(report, transcript.toList(), ratings, everFellBack, xp)
    }

    companion object {
        private const val TAG = "PrismLanguage"

        /**
         * How many times a rejected turn is re-asked before the drill takes over.
         *
         * Three. A model that has failed the gate three times with the problem spelled out each
         * time is not going to succeed on the fourth, and every attempt is a wait the learner is
         * sitting through — on a local model, several seconds of it.
         */
        private const val MAX_ATTEMPTS = 3

        /**
         * Builds the runner for a lesson, assembling the spec from everything stored.
         *
         * Returns null only when there is no plan, which means setup has not been completed and
         * there is nothing to run.
         */
        fun forLesson(context: Context, lessonId: String): LessonRunner? {
            val plan = LanguagePlanStore.plan() ?: return null
            val lesson = plan.levels.flatMap { it.lessons }.firstOrNull { it.id == lessonId } ?: return null
            val tutor = LanguageProfile.tutor() ?: LanguageTutors.ALL.first()

            val spec = LessonSpecBuilder.build(
                lesson = lesson,
                plan = plan,
                model = LanguageLearnerStore.load(plan.targetCode),
                profile = LessonSpecBuilder.Profile(
                    learnerName = LanguageProfile.learnerName(),
                    targetName = LanguageCatalog.learnable(plan.targetCode)?.english ?: plan.targetCode,
                    nativeName = LanguageProfile.nativeLanguage().english,
                    tutorName = tutor.name,
                    tutorAccent = tutor.accent,
                    tutorManner = tutor.manner.label,
                    interests = LanguageProfile.answers("interests") + LanguageProfile.answers("roleplay"),
                    wantsPronunciation = LanguageProfile.wantsPronunciationHelp(),
                    wantsChallenge = LanguageProfile.answer("challenge") == "yes",
                    afraidToStart = LanguageProfile.answer("afraid_to_start") == "yes",
                    confidence = LanguageProfile.answer("confidence").orEmpty(),
                    pronunciationGoal = LanguageProfile.answer("pronunciation_goal").orEmpty(),
                ),
                now = System.currentTimeMillis(),
            )
            return LessonRunner(context, spec)
        }
    }
}
