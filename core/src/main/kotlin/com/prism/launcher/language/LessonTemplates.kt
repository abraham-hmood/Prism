package com.prism.launcher.language

/**
 * The lesson that runs when the model cannot.
 *
 * ## Why a fallback is not optional
 *
 * Three things routinely stop the model: no model selected at all, a cloud backend with no signal,
 * and a small local model whose output fails [LessonValidator] three times running. The first two
 * are ordinary states on a phone. If any of them produce an error screen, the app is unusable on
 * the commute, which is exactly when somebody has fifteen minutes for a language lesson.
 *
 * So there is always a lesson. It is a drill rather than a conversation — the tutor shows a word
 * and waits, shows the next one — and it is genuinely worth doing: this is what a paper flashcard
 * deck does, and paper flashcards taught people languages for a century.
 *
 * ## What it can and cannot do
 *
 * It can teach and review vocabulary, run the whole of A0, and score the result, because all of
 * that comes from the lexicon and the learner model rather than from generation. It cannot hold a
 * conversation, so above A0 it says so plainly and drills the lesson's words instead of pretending
 * to improvise.
 */
object LessonTemplates {

    /**
     * The tutor's opening line without a model.
     *
     * At A0 this is indistinguishable from the generated version — the correct output for "show a
     * picture and say the word" is the word, and a model adds nothing.
     */
    fun opening(spec: LessonSpec): LessonPrompt.Reply {
        val first = spec.pictures.firstOrNull() ?: return drillOpening(spec)
        return LessonPrompt.Reply(
            // The drill speaks to the learner in their own language too. It is a fallback, not a
            // different product, and a bare word with no framing reads as a malfunction.
            say = "Here is the first one. Say it back.",
            teach = first.word,
            hint = null,
            note = null,
            done = false,
        )
    }

    private fun drillOpening(spec: LessonSpec): LessonPrompt.Reply {
        val word = spec.allWords().firstOrNull()
        return LessonPrompt.Reply(
            say = if (word != null) "Let's start here. Say it back." else spec.objective,
            teach = word?.word,
            hint = word?.let { "Say it back" },
            note = null,
            done = word == null,
        )
    }

    /**
     * The next line of a drill.
     *
     * [index] is how many exchanges have happened, which is all the state a drill has. Deliberately
     * not a conversation: a scripted "and what about this one?" pretending to be improvisation is
     * worse than an honest drill, because the learner tries to answer it.
     */
    fun next(spec: LessonSpec, index: Int): LessonPrompt.Reply {
        val items = if (spec.pictures.isNotEmpty()) {
            spec.pictures.map { it.word }
        } else {
            spec.allWords().map { it.word }
        }
        if (items.isEmpty() || index >= items.size) {
            return LessonPrompt.Reply(
                say = "That's the set. Well done.",
                teach = items.lastOrNull(),
                hint = null,
                note = null,
                done = true,
            )
        }
        return LessonPrompt.Reply(
            say = "Next one.",
            teach = items[index],
            hint = null,
            note = null,
            done = index >= items.size - 1,
        )
    }

    /**
     * The report when there was no model to write one.
     *
     * Scores nothing it cannot observe. The learner answered every prompt or they did not, and
     * saying "good work" on the strength of nothing is exactly the flattery the rest of this
     * feature avoids.
     */
    fun report(spec: LessonSpec, answered: Int): LessonPrompt.Report {
        val total = maxOf(1, spec.allWords().size)
        val share = (answered.toDouble() / total).coerceIn(0.0, 1.0)
        return LessonPrompt.Report(
            didWell = "You got through ${spec.newWords.size} new " +
                (if (spec.newWords.size == 1) "word" else "words") + ".",
            toWorkOn = "This one ran without an AI tutor, so there is no feedback on how you said " +
                "it. Connect a model, or pick one in Settings, for corrections.",
            score = (share * 100).toInt(),
            // Everything shown counts as seen but not as mastered: without grading, HARD is the
            // honest rating, and it schedules the word to come back soon rather than in a month.
            wordRatings = spec.newWords.associate { it.conceptId to Fsrs.Rating.HARD },
        )
    }

    /** Said once, at the top of a fallback lesson, so the learner knows why it feels different. */
    fun noModelNotice(spec: LessonSpec): String = when {
        spec.level == Cefr.Level.A0 ->
            "No AI tutor is loaded, so this is a straight picture drill — which is all A0 needs anyway."
        else ->
            "No AI tutor is loaded, so this is a vocabulary drill rather than a conversation. " +
                "Pick a model in Settings to get the full lesson."
    }
}
