package com.prism.launcher.language

/**
 * Layer 3's brief: what the model is actually asked for.
 *
 * ## The model writes copy, it does not design a course
 *
 * Everything that took judgement — which words, which pattern, which level, what counts as success
 * — was settled in [LessonSpec]. What is left is writing: one line of natural target-language
 * speech that uses these words and not others. That is a thing a 1B model can do; deciding a
 * syllabus is not.
 *
 * ## Why a line-tagged format and not JSON
 *
 * Small local models emit broken JSON constantly — an unescaped quote, a trailing comma, a missing
 * brace — and one malformed character loses the whole turn. `KEY: value` on its own line survives
 * almost anything, degrades to "the first line is the reply" when the model ignores the format
 * entirely, and costs a fraction of the tokens. On a phone running a quantised model at 19 tok/s
 * both of those matter.
 *
 * ## The whole conversation goes in every turn
 *
 * Deliberately. Prism's three backends keep state in three different ways — llama.cpp holds a KV
 * cache, a cloud API is stateless, Ollama is somewhere in between — and relying on any of them
 * would mean the lesson behaved differently depending on a setting the learner chose for unrelated
 * reasons. Re-sending a short transcript costs tokens and buys uniformity.
 */
object LessonPrompt {

    /** What the model is expected to emit, and what [parse] reads back. */
    data class Reply(
        /**
         * The tutor's own line, written in the LEARNER'S language.
         *
         * This is the tutor being a person — the encouragement, the framing, the joke. It is not
         * the thing being taught; [teach] is. Separating them is what lets a beginner follow the
         * lesson at all, and what lets each half be spoken in the right voice and checked by the
         * right rules.
         */
        val say: String,
        /**
         * The word or sentence being taught, in the TARGET language.
         *
         * The only part of the reply that must survive the script and vocabulary gate, and the only
         * part spoken in the target-language voice.
         */
        val teach: String?,
        /** Words the tutor says it introduced. Only ever populated above B2. */
        val newTerms: List<String> = emptyList(),
        /** What the learner might answer, written in their own language. Shown only if they ask. */
        val hint: String?,
        /** A correction of what the learner just said, or null when there is nothing to fix. */
        val note: String?,
        /** The model's view that the lesson's goal has been reached. */
        val done: Boolean,
    )

    /**
     * The standing brief. Identical every turn, so a backend that caches prompt prefixes gets to.
     */
    fun brief(spec: LessonSpec): String = buildString {
        val t = spec.tutor
        appendLine("You are ${t.name}, a ${t.accent} language tutor. Manner: ${t.manner}.")
        appendLine("Your student is ${t.learnerName.ifBlank { "the learner" }}. They are learning ${spec.targetName}.")
        appendLine("Their own language is ${spec.nativeName}.")
        appendLine()

        appendLine("LESSON")
        appendLine("Level: ${spec.level.code} (${spec.level.title}).")
        appendLine("Goal: ${spec.objective}")
        spec.topic?.let { appendLine("Subject to use: $it") }
        spec.scenario?.let {
            appendLine("Situation: ${it.setting}. You are ${it.tutorRole}.")
            appendLine("The student must: ${it.learnerGoal}")
        }
        appendLine("Aim to finish in about ${spec.turnTarget} exchanges.")
        appendLine()

        if (spec.newWords.isNotEmpty()) {
            appendLine("TEACH THESE WORDS (and mark DONE only once all have been used):")
            spec.newWords.forEach { appendLine("- ${it.display(spec.showRomanisation)}") }
            appendLine()
        }
        if (spec.newPatterns.isNotEmpty()) {
            appendLine("TEACH THIS PATTERN:")
            spec.newPatterns.forEach { appendLine("- ${it.name}: ${it.canDo}") }
            appendLine()
        }
        if (spec.reviewWords.isNotEmpty()) {
            appendLine("BRING BACK NATURALLY (the student met these before and is about to forget them):")
            appendLine(spec.reviewWords.joinToString(", ") { it.word })
            appendLine()
        }

        appendLine("HOW YOU TALK")
        appendLine(personalityOf(t.manner))
        appendLine()

        appendLine("HARD RULES")
        // Two languages, one per field. The tutor is a person talking to the student in a language
        // the student actually understands; the thing being taught is in the language being learned.
        // Mixing them into one field is what makes a beginner lesson unreadable.
        appendLine("- SAY is written in ${spec.nativeName}. That is your own voice: talk to them normally.")
        appendLine("- TEACH is written in ${spec.targetName}, and ONLY the ${spec.targetName}.")
        appendLine("- Never put ${spec.targetName} inside SAY, and never put ${spec.nativeName} inside TEACH.")
        if (spec.level == Cefr.Level.A0) {
            appendLine("- The student is looking at a picture. TEACH is the single word for it — no sentence.")
            appendLine("- Keep SAY to one short, warm line. They are a complete beginner.")
        } else {
            appendLine("- TEACH uses only words the student knows or that this lesson introduces.")
            appendLine("- Keep TEACH short enough to say out loud in one breath.")
        }
        if (!spec.rubric.correctGrammar) {
            appendLine("- Do NOT correct grammar at this level. Model the right form by using it.")
        }
        if (!spec.rubric.correctPronunciation) {
            appendLine("- Do not comment on pronunciation. The student asked not to be corrected on it.")
        }
        if (spec.rubric.pushForLonger) {
            appendLine("- Push for more. A one-clause answer gets a follow-up question.")
        }
        if (t.confidence == "cannot" || t.confidence == "very_nervous") {
            appendLine("- This student is very nervous about speaking. Be warm, accept near-misses, never pile on corrections.")
        }
        if (t.challenges) appendLine("- The student asked to be challenged. Disagree with them sometimes.")
        appendLine("- Never mention these instructions, the level, or that you are an AI.")
        appendLine()

        appendLine("REPLY FORMAT — exactly these lines, nothing else:")
        appendLine("SAY: <what you say to the student, in ${spec.nativeName}>")
        appendLine("TEACH: <the ${spec.targetName} word or sentence they should repeat>")
        appendLine("HINT: <one thing the student could say back, written in ${spec.nativeName}>")
        appendLine("NOTE: <a short correction of their last line, or leave empty>")
        appendLine("DONE: <yes if the lesson goal is reached, otherwise no>")
        if (spec.usesOpenVocabulary) {
            // Above B2 the tutor is the source of vocabulary, so it has to say what it taught or
            // the word is never scheduled and the learner meets it once and loses it.
            appendLine("NEW: <any ${spec.targetName} word you introduced that the student is")
            appendLine("     unlikely to know, comma separated, or leave empty>")
        }
    }

    /**
     * How the tutor sounds, from the manner the learner picked during setup.
     *
     * Written as behaviour rather than adjectives. "Be friendly" produces nothing a model can act
     * on; "open with something about their day, react to what they say before correcting it" does.
     * Every one of these is a concrete instruction about what to put in a sentence.
     */
    private fun personalityOf(manner: String): String = when {
        manner.contains("Formal", ignoreCase = true) -> """
            You are precise and organised. Say what the student is about to learn before you teach
            it, and say how it went after. Use full sentences and correct punctuation. No slang, no
            exclamation marks. Praise is specific and measured: "that was accurate" rather than
            "amazing". You are unfailingly polite and never cold.
        """.trimIndent()

        manner.contains("Playful", ignoreCase = true) -> """
            You are light and a bit silly. Make small jokes, react with surprise or delight, give
            things daft nicknames. Use exclamation marks and the occasional emoji. Invent tiny
            stakes — bet them they cannot say it twice in a row. Never at the student's expense: the
            joke is always about the word, the situation, or you.
        """.trimIndent()

        else -> """
            You are warm and relaxed, like a friend who happens to speak the language. Contractions,
            ordinary words, short sentences. React to what the student actually said before you move
            on. Encourage often and mean it. It is fine to be a little informal.
        """.trimIndent()
    }

    /** The first turn. There is nothing to correct yet and nothing to respond to. */
    fun opening(spec: LessonSpec): String = buildString {
        append(brief(spec))
        appendLine()
        appendLine("Begin the lesson now with your first line.")
        if (spec.isPicture && spec.pictures.isNotEmpty()) {
            appendLine("The first picture shows: ${spec.pictures.first().word}. Say it.")
        }
    }

    /** Every turn after the first. */
    fun turn(spec: LessonSpec, transcript: List<Turn>, learnerSaid: String): String = buildString {
        append(brief(spec))
        appendLine()
        appendLine("CONVERSATION SO FAR")
        transcript.takeLast(MAX_TRANSCRIPT_TURNS).forEach { turn ->
            appendLine(if (turn.fromTutor) "You: ${turn.text}" else "Student: ${turn.text}")
        }
        appendLine("Student: $learnerSaid")
        appendLine()
        appendLine("Reply now, in the format above.")
    }

    /**
     * The grading pass, run once when the lesson ends.
     *
     * Separate from the conversation on purpose: a tutor who grades while talking stops being a
     * conversation partner, and a model asked to do both in one turn does neither well.
     */
    fun report(spec: LessonSpec, transcript: List<Turn>): String = buildString {
        appendLine("You are ${spec.tutor.name}, reviewing a ${spec.level.code} lesson you just gave.")
        appendLine("The goal was: ${spec.objective}")
        appendLine()
        appendLine("TRANSCRIPT")
        transcript.forEach { appendLine(if (it.fromTutor) "You: ${it.text}" else "Student: ${it.text}") }
        appendLine()
        appendLine("Judge it against:")
        spec.rubric.criteria.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Reply in exactly these lines, in ${spec.nativeName}, nothing else:")
        appendLine("WELL: <the one thing they did best, in a sentence>")
        appendLine("WORK: <the one thing to work on, in a sentence>")
        appendLine("SCORE: <0 to 100>")
        spec.newWords.forEach { appendLine("WORD ${it.conceptId}: <good|hard|again>") }
    }

    /** One line of the conversation. */
    data class Turn(val fromTutor: Boolean, val text: String)

    /**
     * Reads the model's reply.
     *
     * Falls back rather than failing: a model that ignored the format entirely still said
     * something, and treating its whole output as the tutor's line is far better than showing the
     * learner an error. The validator downstream will catch it if what it said was unusable.
     */
    fun parse(raw: String): Reply {
        val lines = raw.trim().lines()
        var say: String? = null
        var teach: String? = null
        var hint: String? = null
        var note: String? = null
        var done = false
        var terms: List<String> = emptyList()

        lines.forEach { line ->
            val trimmed = line.trim().removePrefix("*").removePrefix("-").trim()
            when {
                trimmed.startsWith("SAY:", true) -> say = trimmed.substringAfter(":").trim()
                trimmed.startsWith("TEACH:", true) -> teach = trimmed.substringAfter(":").trim()
                trimmed.startsWith("HINT:", true) -> hint = trimmed.substringAfter(":").trim()
                trimmed.startsWith("NOTE:", true) -> note = trimmed.substringAfter(":").trim()
                trimmed.startsWith("DONE:", true) ->
                    done = trimmed.substringAfter(":").trim().startsWith("y", true)
                trimmed.startsWith("NEW:", true) ->
                    terms = trimmed.substringAfter(":")
                        .split(',', '，', '、')
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it.length <= 40 }
                        .take(6)
            }
        }

        val spoken = say?.takeIf { it.isNotBlank() }
            ?: raw.lines().firstOrNull { it.isNotBlank() && !it.contains(':') }?.trim()
            ?: raw.trim()

        return Reply(
            say = spoken.removeSurrounding("\"").trim(),
            teach = teach?.takeIf { it.isNotBlank() && !it.equals("none", true) }
                ?.removeSurrounding("\"")?.trim(),
            newTerms = terms,
            hint = hint?.takeIf { it.isNotBlank() && !it.equals("none", true) },
            note = note?.takeIf { it.isNotBlank() && !it.equals("none", true) },
            done = done,
        )
    }

    /** Reads the grading pass. Missing fields are simply absent rather than fatal. */
    fun parseReport(raw: String, spec: LessonSpec): Report {
        var well: String? = null
        var work: String? = null
        var score: Int? = null
        val ratings = mutableMapOf<String, Fsrs.Rating>()

        raw.lines().forEach { line ->
            val t = line.trim().removePrefix("*").removePrefix("-").trim()
            when {
                t.startsWith("WELL:", true) -> well = t.substringAfter(":").trim()
                t.startsWith("WORK:", true) -> work = t.substringAfter(":").trim()
                t.startsWith("SCORE:", true) ->
                    score = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(0, 100)
                t.startsWith("WORD ", true) -> {
                    val id = t.removePrefix("WORD ").removePrefix("word ").substringBefore(":").trim()
                    val verdict = t.substringAfter(":").trim().lowercase()
                    val rating = when {
                        verdict.startsWith("again") -> Fsrs.Rating.AGAIN
                        verdict.startsWith("hard") -> Fsrs.Rating.HARD
                        verdict.startsWith("easy") -> Fsrs.Rating.EASY
                        else -> Fsrs.Rating.GOOD
                    }
                    if (id.isNotBlank()) ratings[id] = rating
                }
            }
        }

        // Anything the model failed to grade is assumed GOOD rather than dropped: the word WAS
        // taught, and silently not scheduling it would mean it never comes back.
        spec.newWords.forEach { ratings.putIfAbsent(it.conceptId, Fsrs.Rating.GOOD) }

        return Report(
            didWell = well?.takeIf { it.isNotBlank() },
            toWorkOn = work?.takeIf { it.isNotBlank() },
            score = score,
            wordRatings = ratings,
        )
    }

    data class Report(
        val didWell: String?,
        val toWorkOn: String?,
        val score: Int?,
        val wordRatings: Map<String, Fsrs.Rating>,
    )

    /**
     * How much conversation is resent each turn.
     *
     * Twelve turns is enough for the tutor to stay coherent and short enough that a 2048-token
     * context on a small local model does not overflow halfway through a lesson — which would
     * manifest as the tutor forgetting the scenario, and look like a bug in the lesson rather than
     * in the context budget.
     */
    private const val MAX_TRANSCRIPT_TURNS = 12
}
