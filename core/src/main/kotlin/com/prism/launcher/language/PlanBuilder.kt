package com.prism.launcher.language

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the setup answers into a syllabus.
 *
 * ## The four decisions this makes
 *
 * 1. **Where to start.** From the self-reported level and the three ability questions, resolved
 *    conservatively — see [placement].
 * 2. **Where to stop.** From the goal the learner named. Someone preparing for an exam needs C1;
 *    someone who wants to order dinner in Lisbon does not, and putting C2 on their path is how a
 *    plan stops looking achievable.
 * 3. **How many lessons each level takes.** The framework's objectives, plus extra practice
 *    proportional to how far the target is from what the learner already speaks.
 * 4. **What each lesson is about.** The objective comes from CEFR; the topic comes from them.
 *
 * ## Deterministic, on purpose
 *
 * Same answers, same plan, every time. A syllabus that reshuffled itself on regeneration would make
 * "lesson 9" meaningless and would quietly invalidate any progress recorded against it.
 */
object PlanBuilder {

    /**
     * Bumped whenever a change here should reach learners who already have a plan.
     *
     * 2 — the pace note was rewritten. The first version quoted a multi-year estimate and called
     * it "still fast", and a note is stored inside the plan rather than recomputed, so existing
     * plans would have kept saying it.
     */
    const val VERSION = 2

    /**
     * Everything setup collected, flattened.
     *
     * A plain value rather than a reference to the settings store, so the whole of this file is
     * testable without Android and a plan can be computed for a hypothetical learner.
     */
    data class Input(
        val targetCode: String,
        val nativeCode: String,
        val selfLevel: String?,
        val canIntroduce: Boolean?,
        val canConverse: Boolean?,
        val canUnderstand: Boolean?,
        val motivation: String?,
        val dream: String?,
        val pace: String?,
        val confidence: String?,
        val wantsPronunciation: Boolean,
        val wantsChallenge: Boolean,
        val practiceStyle: String?,
        val interests: List<String>,
        val roleplay: List<String>,
        val skills: List<String>,
        val minutesPerDay: Int,
        val now: Long = System.currentTimeMillis(),
    )

    fun build(input: Input): LearningPlan {
        val difficulty = LanguageDistance.difficulty(input.nativeCode, input.targetCode)
        val newScript = LanguageDistance.needsNewScript(input.nativeCode, input.targetCode)
        val start = placement(input)
        val goal = goal(input, start)

        val levels = Cefr.Level.entries
            .filter { it.ordinal <= goal.ordinal }
            .map { level -> buildLevel(level, input, difficulty, newScript, knownAlready = level.ordinal < start.ordinal) }

        val plan = LearningPlan(
            version = VERSION,
            generatedAt = input.now,
            targetCode = input.targetCode,
            nativeCode = input.nativeCode,
            placement = start,
            goal = goal,
            levels = levels,
            minutesPerDay = input.minutesPerDay.coerceAtLeast(1),
            difficulty = difficulty,
            newScript = newScript,
            paceNote = "",
        )
        return plan.copy(paceNote = paceNote(input, plan))
    }

    // -- Where to start -------------------------------------------------------

    /**
     * The level to open at.
     *
     * Two signals, averaged and rounded DOWN. The self-report is what the learner thinks; the three
     * ability questions are what they can actually account for. They disagree constantly, in both
     * directions — people who have studied for years say A1 out of modesty, and people who did a
     * fortnight of an app say B1.
     *
     * Rounding down is not pessimism, it is the cheaper error. Starting a level too low costs a few
     * easy lessons that can be skipped in an afternoon and feels like momentum. Starting too high
     * means a first lesson the learner cannot follow, and that is where people conclude the language
     * is beyond them and stop. Placement tests in every serious institution err the same way.
     */
    fun placement(input: Input): Cefr.Level {
        val claimed = Cefr.Level.of(input.selfLevel)

        val affirmed = listOfNotNull(input.canIntroduce, input.canConverse, input.canUnderstand)
            .count { it }
        val demonstrated = when (affirmed) {
            0 -> Cefr.Level.A0
            1 -> Cefr.Level.A1
            2 -> Cefr.Level.B1
            else -> Cefr.Level.B2
        }

        // A learner who answered none of the ability questions at all gave one signal, not two.
        val everAnswered = input.canIntroduce != null || input.canConverse != null || input.canUnderstand != null
        if (!everAnswered) return claimed

        val averaged = (claimed.ordinal + demonstrated.ordinal) / 2
        return Cefr.Level.entries[averaged.coerceIn(0, Cefr.Level.entries.lastIndex)]
    }

    /**
     * The level the plan aims at.
     *
     * Taken from what the learner said they want to DO, because that is what a level is for. B2 is
     * the point at which someone works and lives in a language comfortably; C1 is what exams and
     * professional settings actually ask for; B1 is enough to travel and make friends. C2 is a
     * genuine specialism and is only ever the goal when the learner is already close to it.
     */
    fun goal(input: Input, start: Cefr.Level): Cefr.Level {
        val fromDream = when (input.dream) {
            "exam", "interview" -> Cefr.Level.C1
            "move" -> Cefr.Level.B2
            "media" -> Cefr.Level.B2
            "travel", "meet" -> Cefr.Level.B1
            else -> null
        }
        val fromMotivation = when (input.motivation) {
            "career", "study" -> Cefr.Level.C1
            "living" -> Cefr.Level.B2
            "travel" -> Cefr.Level.B1
            else -> Cefr.Level.B2
        }
        val wanted = maxOf(fromDream ?: fromMotivation, fromMotivation)

        // Always at least one level of headroom: a plan whose goal is where you already are has
        // nothing in it.
        val floor = Cefr.Level.entries[min(start.ordinal + 1, Cefr.Level.entries.lastIndex)]
        return maxOf(wanted, floor)
    }

    // -- Building a level -----------------------------------------------------

    private fun buildLevel(
        level: Cefr.Level,
        input: Input,
        difficulty: Double,
        newScript: Boolean,
        knownAlready: Boolean,
    ): PlannedLevel {
        val objectives = Cefr.objectives(level).toMutableList()

        // The writing system is a syllabus of its own, and pretending otherwise is why people who
        // "learned some Japanese" cannot read a menu. It goes in at the bottom, where it belongs.
        // Recognition at A0, production at A1. Reading a character is a thing you answer out
        // loud; writing one is a thing you answer with your hand, and A0 is entirely spoken.
        // Putting both at A0 made the very first lesson of every new-script language a typing
        // exercise, which is not what that level is.
        if (newScript && level == Cefr.Level.A0) {
            objectives.add(0, "Reading the writing system")
        }
        if (newScript && level == Cefr.Level.A1) {
            objectives.add(0, "Writing the writing system")
        }

        // Goal-relevant objectives first. Nothing is dropped — the level would stop being the level
        // — but a learner heading for a job interview should meet the interview objective in week
        // three, not week thirty.
        val ordered = prioritise(objectives, input)

        val extras = extraPractice(ordered.size, difficulty)
        val lessons = layOutLessons(level, ordered, extras, input)

        val estimatedMinutes = (level.guidedHours * 60 * difficulty).roundToInt()
        val perLesson = if (lessons.isEmpty()) 0 else estimatedMinutes / lessons.size
        val sessions = max(1, ceil(perLesson / input.minutesPerDay.coerceAtLeast(1).toDouble()).toInt())

        return PlannedLevel(
            level = level,
            lessons = lessons.map { it.copy(sessions = sessions) },
            alreadyKnown = knownAlready,
            estimatedMinutes = estimatedMinutes,
        )
    }

    /**
     * How many objectives get a second pass.
     *
     * A harder language does not have more to say at A2 — the can-do statements are the same. What
     * it has is more machinery between the learner and saying it: a case system, a tone, a script,
     * four thousand characters. That shows up as needing the same objective twice, not as new
     * objectives, which is why this scales the list rather than extending it.
     */
    fun extraPractice(objectiveCount: Int, difficulty: Double): Int =
        (objectiveCount * (difficulty - 1.0) * 0.6).roundToInt().coerceIn(0, objectiveCount)

    /**
     * Reorders objectives so the ones matching the learner's goal come first.
     *
     * Keyword matching, which is crude and is the right amount of machinery for the job: the
     * objective strings are fixed and written here, so a substring test is exact in practice and
     * cannot produce the nonsense a similarity score would.
     */
    private fun prioritise(objectives: List<String>, input: Input): List<String> {
        val keywords = buildList {
            when (input.dream) {
                "exam" -> addAll(listOf("write", "report", "argu", "present", "essay"))
                "interview" -> addAll(listOf("interview", "job", "work", "present", "field"))
                "travel" -> addAll(listOf("travel", "direction", "order", "ticket", "hotel", "cost", "booking"))
                "move" -> addAll(listOf("live", "doctor", "form", "town", "arrange", "complain"))
                "meet" -> addAll(listOf("introduce", "social", "invit", "feel", "conversation", "friend"))
                "media" -> addAll(listOf("film", "news", "television", "follow", "listen", "read"))
            }
            when (input.motivation) {
                "career" -> addAll(listOf("work", "job", "present", "report", "negoti"))
                "study" -> addAll(listOf("lecture", "note", "write", "argu", "read"))
            }
        }
        if (keywords.isEmpty()) return objectives

        // A stable partition, so within each group the framework's own teaching order survives.
        val (relevant, rest) = objectives.partition { objective ->
            keywords.any { objective.contains(it, ignoreCase = true) }
        }
        return relevant + rest
    }

    /**
     * Turns objectives into lessons: assigns a kind to each, interleaves review, and attaches a
     * topic the learner chose.
     */
    private fun layOutLessons(
        level: Cefr.Level,
        objectives: List<String>,
        extras: Int,
        input: Input,
    ): List<PlannedLesson> {
        val kinds = kindSequence(level, input)
        val topics = topicPool(input)

        val out = mutableListOf<PlannedLesson>()
        var kindCursor = 0
        var topicCursor = 0

        fun nextKind(): Cefr.LessonKind {
            val kind = kinds[kindCursor % kinds.size]
            kindCursor++
            return kind
        }

        objectives.forEachIndexed { i, objective ->
            val kind = if (level.isPictureBased) Cefr.LessonKind.PICTURE else nextKind()
            val topic = if (kind.usesTopic() && topics.isNotEmpty()) {
                topics[topicCursor++ % topics.size]
            } else null

            out.add(
                PlannedLesson(
                    id = "${level.code}-${out.size + 1}",
                    level = level,
                    index = out.size + 1,
                    kind = kind,
                    title = objective,
                    objective = objective,
                    topic = topic,
                    sessions = 1,
                )
            )

            // Review on a fixed cadence rather than at the end. Spacing recall through a level is
            // the single best-evidenced thing in the whole of language pedagogy; a revision lesson
            // bolted on at the end is revision of things already forgotten.
            val dueForReview = (i + 1) % REVIEW_EVERY == 0 && i != objectives.lastIndex
            if (dueForReview) {
                out.add(
                    PlannedLesson(
                        id = "${level.code}-${out.size + 1}",
                        level = level,
                        index = out.size + 1,
                        kind = Cefr.LessonKind.REVIEW,
                        title = "Review: everything so far",
                        objective = "Recall and reuse what this level has covered up to here",
                        topic = null,
                        sessions = 1,
                    )
                )
            }
        }

        // The extra practice a harder language needs, taken over the objectives the learner will
        // have found hardest — which, absent evidence, means the ones furthest into the level.
        repeat(extras) { n ->
            val source = objectives.getOrNull(objectives.size - 1 - (n % objectives.size)) ?: return@repeat
            out.add(
                PlannedLesson(
                    id = "${level.code}-${out.size + 1}",
                    level = level,
                    index = out.size + 1,
                    kind = if (level.isPictureBased) Cefr.LessonKind.PICTURE else Cefr.LessonKind.CONVERSATION,
                    title = "Again, without help: $source",
                    objective = source,
                    topic = if (topics.isNotEmpty()) topics[topicCursor++ % topics.size] else null,
                    sessions = 1,
                )
            )
        }

        // A checkpoint at the end of a level, but only for the goals that are actually assessed.
        // Everyone else gets a conversation, which is a better use of the time.
        if (input.dream in setOf("exam", "interview") && level.ordinal >= Cefr.Level.B1.ordinal) {
            out.add(
                PlannedLesson(
                    id = "${level.code}-${out.size + 1}",
                    level = level,
                    index = out.size + 1,
                    kind = Cefr.LessonKind.ASSESSMENT,
                    title = if (input.dream == "exam") "Mock exam task" else "Mock interview",
                    objective = "Perform under the conditions of the real thing, timed and unaided",
                    topic = null,
                    sessions = 1,
                )
            )
        }

        return out
    }

    /**
     * The order lesson kinds are handed out in, expanded from [Cefr.lessonMix] and then adjusted
     * for what the learner asked for.
     */
    private fun kindSequence(level: Cefr.Level, input: Input): List<Cefr.LessonKind> {
        val mix = Cefr.lessonMix(level).toMutableMap()

        // Asked for no pronunciation correction: those lessons become conversation rather than
        // vanishing, because the time was allocated to speaking either way.
        if (!input.wantsPronunciation) {
            val moved = mix.remove(Cefr.LessonKind.PRONUNCIATION) ?: 0
            if (moved > 0) mix[Cefr.LessonKind.CONVERSATION] = (mix[Cefr.LessonKind.CONVERSATION] ?: 0) + moved
        }

        // "Casual conversations" over "a structured plan" shifts weight from patterns and
        // vocabulary drills into talking. It does not remove them: a learner who never meets a
        // pattern plateaus, whatever they said they preferred.
        if (input.practiceStyle == "casual") {
            val borrowed = (mix[Cefr.LessonKind.PATTERN] ?: 0) / 2
            if (borrowed > 0) {
                mix[Cefr.LessonKind.PATTERN] = (mix[Cefr.LessonKind.PATTERN] ?: 0) - borrowed
                mix[Cefr.LessonKind.CONVERSATION] = (mix[Cefr.LessonKind.CONVERSATION] ?: 0) + borrowed
            }
        }

        // Wanting to be challenged means more role-play, which is the only kind where the learner
        // has to hold a position under pressure.
        if (input.wantsChallenge) {
            mix[Cefr.LessonKind.ROLEPLAY] = (mix[Cefr.LessonKind.ROLEPLAY] ?: 0) + 2
        }

        // Interleaved rather than blocked: three listening lessons in a row is a worse use of the
        // same three lessons than one every third. Round-robin over the weights does that for free.
        val expanded = mutableListOf<Cefr.LessonKind>()
        var remaining = mix.filterValues { it > 0 }.toMutableMap()
        while (remaining.isNotEmpty()) {
            val pass = remaining.keys.sortedBy { it.ordinal }
            for (kind in pass) {
                expanded.add(kind)
                val left = (remaining[kind] ?: 0) - 1
                if (left <= 0) remaining.remove(kind) else remaining[kind] = left
            }
        }
        return if (expanded.isEmpty()) listOf(Cefr.LessonKind.CONVERSATION) else expanded
    }

    /**
     * What lessons are ABOUT.
     *
     * The interests the learner picked, then the role-play settings, then a small neutral fallback
     * so a learner who selected nothing still gets lessons with a subject rather than lessons about
     * language in the abstract.
     */
    private fun topicPool(input: Input): List<String> {
        val pool = (input.interests + input.roleplay).distinct()
        return pool.ifEmpty { listOf("everyday life", "work", "travel", "food", "family") }
    }

    // -- Telling the truth about time -----------------------------------------

    /**
     * What to say about the pace they asked for.
     *
     * The honest version. Someone starting Japanese from nothing at ten minutes a day who selected
     * "1 month" is going to discover the discrepancy on their own eventually, and discovering it
     * alone is what makes people conclude they are bad at languages. Said up front, with the number
     * of minutes that WOULD do it, it is a decision instead of a failure.
     */
    private fun paceNote(input: Input, plan: LearningPlan): String {
        // The near horizon, always. A learner starting Mandarin is told the whole road is years
        // long -- which is true, and on its own it is the most discouraging sentence the app could
        // possibly open with. The next level is weeks or months away, it is the thing they will
        // actually reach, and it belongs in the same breath as the total.
        val next = plan.levels.firstOrNull { !it.alreadyKnown }
        val nextClause = next?.let {
            val days = ceil(it.estimatedMinutes / plan.minutesPerDay.toDouble()).toInt()
            "${it.level.code} is about ${monthsText(days)} away."
        }.orEmpty()

        val wantedDays = when (input.pace) {
            "one_month" -> 30
            "three_months" -> 90
            "six_months" -> 180
            "one_year" -> 365
            else -> null
        } ?: return ("At ${plan.minutesPerDay} minutes a day, the whole way to ${plan.goal.code} " +
            "is about ${monthsText(plan.estimatedDays)} of practice. $nextClause " +
            "There is no clock on it.").trim()

        val days = plan.estimatedDays
        if (days <= wantedDays) {
            return ("At ${plan.minutesPerDay} minutes a day you reach ${plan.goal.code} in about " +
                "${monthsText(days)} — inside the time you asked for. $nextClause").trim()
        }

        val neededMinutes = ceil(plan.totalMinutes / wantedDays.toDouble()).toInt()
        return if (neededMinutes <= 120) {
            ("Reaching ${plan.goal.code} in that time would take about $neededMinutes minutes a " +
                "day. At ${plan.minutesPerDay} it is closer to ${monthsText(days)} — both are " +
                "fine, but that is the trade. $nextClause").trim()
        } else {
            // NOT "and still fast". An earlier version said that about an eight-year estimate,
            // which is the kind of cheerfulness that costs an app its credibility the moment the
            // learner does the arithmetic themselves.
            ("${plan.goal.code} in that time would mean $neededMinutes minutes every day, which " +
                "is not a real plan. At ${plan.minutesPerDay} minutes, all the way to " +
                "${plan.goal.code} is a ${monthsText(days)} project — this is one of the harder " +
                "languages to get there in, and that is simply what it costs. $nextClause " +
                "Hold that one.").trim()
        }
    }

    private fun monthsText(days: Int): String {
        val months = days / 30.0
        return when {
            days < 45 -> "$days days"
            months < 18 -> "${months.roundToInt()} months"
            else -> "${(months / 12).roundToInt()} years"
        }
    }

    private const val REVIEW_EVERY = 5
}

/** Kinds whose content is built around a subject the learner chose, rather than around form. */
private fun Cefr.LessonKind.usesTopic(): Boolean = when (this) {
    Cefr.LessonKind.CONVERSATION, Cefr.LessonKind.ROLEPLAY, Cefr.LessonKind.LISTENING,
    Cefr.LessonKind.VOCABULARY -> true
    else -> false
}
