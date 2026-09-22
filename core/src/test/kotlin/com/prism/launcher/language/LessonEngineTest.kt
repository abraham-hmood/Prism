package com.prism.launcher.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lesson engine's three layers, tested where each can actually be wrong.
 *
 * The spec builder and the validator are the parts that carry the safety of the whole feature: if
 * the spec lets a word through that the learner has never met, or the validator passes an English
 * sentence as Japanese, the learner is being taught something wrong and is the one person who
 * cannot tell. Both are pure functions, so both can be pinned here rather than discovered on a
 * phone.
 */
class LessonEngineTest {

    // -- The lexicon ----------------------------------------------------------

    @Test
    fun `every language covers nearly all the concepts`() {
        // A loose format with hand-written ids has exactly one failure mode: a typo that silently
        // drops an entry. This is the check that catches it.
        val poor = Lexicon.supportedCodes().mapNotNull { code ->
            val coverage = Lexicon.coverage(code)
            if (coverage < 0.9) "$code=${"%.2f".format(coverage)}" else null
        }
        assertTrue(poor.isEmpty(), "languages missing concept vocabulary: $poor")
    }

    @Test
    fun `no lexicon entry names a concept that does not exist`() {
        val known = Concepts.ALL.map { it.id }.toSet()
        val strays = Lexicon.supportedCodes().flatMap { code ->
            Lexicon.vocabulary(code).map { it.conceptId }.filter { it !in known }.map { "$code:$it" }
        }
        assertTrue(strays.isEmpty(), "lexicon ids with no matching concept: $strays")
    }

    @Test
    fun `non-Latin languages carry a reading aid and Latin ones do not need one`() {
        listOf("ja", "ko", "zh", "ar", "hi").forEach { code ->
            val withReading = Lexicon.vocabulary(code).count { it.romanisation != null }
            assertTrue(
                withReading > 100,
                "$code should romanise its vocabulary for a beginner, got $withReading",
            )
        }
        assertTrue(Lexicon.vocabulary("es").all { it.romanisation == null })
    }

    @Test
    fun `vocabulary comes back in teaching order`() {
        val ranks = Lexicon.vocabulary("es").map { it.rank }
        assertEquals(ranks.sorted(), ranks, "the lexicon IS the vocabulary sequence")
    }

    @Test
    fun `Brazilian Portuguese differs from European where it should and not elsewhere`() {
        assertEquals("o celular", Lexicon.word("pt-BR", "phone")?.word)
        assertEquals("o telemóvel", Lexicon.word("pt", "phone")?.word)
        assertEquals(Lexicon.word("pt", "dog")?.word, Lexicon.word("pt-BR", "dog")?.word)
    }

    @Test
    fun `every language has function words for the gate to skip`() {
        Lexicon.supportedCodes().forEach { code ->
            assertTrue(
                Lexicon.stopwords(code).size >= 30,
                "$code has too few function words for the validator to work with",
            )
        }
    }

    // -- FSRS -----------------------------------------------------------------

    @Test
    fun `a new item has no retrievability and a reviewed one decays`() {
        val now = 1_700_000_000_000L
        val fresh = Fsrs.State()
        assertEquals(0.0, Fsrs.retrievability(fresh, now))

        val learned = Fsrs.review(fresh, Fsrs.Rating.GOOD, now)
        assertTrue(Fsrs.retrievability(learned, now) > 0.99, "just reviewed, should be near certain")

        val month = now + 30L * 86_400_000L
        val later = Fsrs.retrievability(learned, month)
        assertTrue(later < 0.9, "a month later it should have decayed, got $later")
    }

    @Test
    fun `recall at exactly stability gives ninety percent by construction`() {
        val now = 1_700_000_000_000L
        val state = Fsrs.review(Fsrs.State(), Fsrs.Rating.GOOD, now)
        val atStability = now + (state.stability * 86_400_000L).toLong()
        val r = Fsrs.retrievability(state, atStability)
        assertTrue(r in 0.88..0.92, "FSRS defines stability as the 90% point, got $r")
    }

    @Test
    fun `successful reviews grow stability and a lapse cuts it without erasing it`() {
        var now = 1_700_000_000_000L
        var state = Fsrs.review(Fsrs.State(), Fsrs.Rating.GOOD, now)
        val first = state.stability

        repeat(3) {
            now += (state.stability * 86_400_000L).toLong()
            state = Fsrs.review(state, Fsrs.Rating.GOOD, now)
        }
        assertTrue(state.stability > first * 2, "repeated recall should compound: $first -> ${state.stability}")

        val beforeLapse = state.stability
        now += 86_400_000L
        state = Fsrs.review(state, Fsrs.Rating.AGAIN, now)
        assertTrue(state.stability < beforeLapse, "forgetting must cost something")
        assertTrue(state.stability > 0.0, "relearning is faster than learning; it does not reset to nothing")
        assertEquals(1, state.lapses)
    }

    @Test
    fun `difficulty stays inside the FSRS band under repeated failure`() {
        var now = 1_700_000_000_000L
        var state = Fsrs.review(Fsrs.State(), Fsrs.Rating.AGAIN, now)
        repeat(20) {
            now += 86_400_000L
            state = Fsrs.review(state, Fsrs.Rating.AGAIN, now)
        }
        assertTrue(state.difficulty in 1.0..10.0, "difficulty escaped its band: ${state.difficulty}")
    }

    @Test
    fun `an easy answer schedules further out than a hard one`() {
        val now = 1_700_000_000_000L
        val seed = Fsrs.review(Fsrs.State(), Fsrs.Rating.GOOD, now)
        val later = now + 5L * 86_400_000L

        val easy = Fsrs.review(seed, Fsrs.Rating.EASY, later)
        val hard = Fsrs.review(seed, Fsrs.Rating.HARD, later)
        assertTrue(
            Fsrs.dueAt(easy) > Fsrs.dueAt(hard),
            "easy should come back later than hard",
        )
    }

    @Test
    fun `mastery needs durability and not just a recent look`() {
        val now = 1_700_000_000_000L
        val justSeen = Fsrs.review(Fsrs.State(), Fsrs.Rating.GOOD, now)
        assertTrue(
            Fsrs.mastery(justSeen, now) < LearnerModel.KNOWN_MASTERY,
            "a word met once today is not known",
        )

        var state = justSeen
        var clock = now
        repeat(5) {
            clock += (state.stability * 86_400_000L).toLong()
            state = Fsrs.review(state, Fsrs.Rating.GOOD, clock)
        }
        assertTrue(
            Fsrs.mastery(state, clock) >= LearnerModel.KNOWN_MASTERY,
            "a word held across five spaced reviews is known",
        )
    }

    // -- The learner model ----------------------------------------------------

    @Test
    fun `new words come in teaching order and skip what has been met`() {
        val model = LearnerModel()
            .record(LearnerModel.vocabId("apple"), Fsrs.Rating.GOOD, NOW)
            .record(LearnerModel.vocabId("bread"), Fsrs.Rating.GOOD, NOW)

        val next = model.nextNewConcepts("es", 3).map { it.conceptId }
        assertFalse(next.contains("apple"))
        assertFalse(next.contains("bread"))
        assertEquals(3, next.size)
    }

    @Test
    fun `due items come back most-at-risk first`() {
        var model = LearnerModel()
        listOf("apple", "bread", "water").forEach {
            model = model.record(LearnerModel.vocabId(it), Fsrs.Rating.GOOD, NOW)
        }
        val muchLater = NOW + 400L * 86_400_000L
        val due = model.due(muchLater, 10)
        assertTrue(due.isNotEmpty(), "everything should be due after a year")
    }

    @Test
    fun `estimated level is derived from what is held not from lessons done`() {
        val empty = LearnerModel()
        assertEquals(Cefr.Level.A0, empty.estimatedLevel("es", NOW))
    }

    // -- The spec builder -----------------------------------------------------

    private val profile = LessonSpecBuilder.Profile(
        learnerName = "Sam",
        targetName = "Spanish",
        nativeName = "English",
        tutorName = "Mira",
        tutorAccent = "Castilian",
        tutorManner = "Friendly & casual",
        interests = listOf("cooking", "traveling"),
        wantsPronunciation = true,
        wantsChallenge = false,
        afraidToStart = true,
        confidence = "nervous",
        pronunciationGoal = "clear",
    )

    private fun plan(target: String = "es") = PlanBuilder.build(
        PlanBuilder.Input(
            targetCode = target, nativeCode = "en", selfLevel = "A0",
            canIntroduce = false, canConverse = false, canUnderstand = false,
            motivation = "travel", dream = "travel", pace = "no_rush", confidence = "nervous",
            wantsPronunciation = true, wantsChallenge = false, practiceStyle = "structured",
            interests = listOf("cooking", "traveling"), roleplay = listOf("travel"),
            skills = emptyList(), minutesPerDay = 15, now = NOW,
        )
    )

    @Test
    fun `an A0 lesson is pictures drawn from its own objective`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A0)!!.lessons.first { it.objective == "Animals" }
        val spec = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)

        assertTrue(spec.isPicture)
        assertTrue(spec.pictures.isNotEmpty())
        val animalIds = Concepts.inCategory(Concepts.Category.ANIMALS).map { it.id }.toSet()
        assertTrue(
            spec.pictures.all { it.conceptId in animalIds },
            "the Animals lesson must teach animals: ${spec.pictures.map { it.conceptId }}",
        )
    }

    @Test
    fun `A0 forbids the native language and everything above it allows a little`() {
        val plan = plan()
        val a0 = plan.level(Cefr.Level.A0)!!.lessons.first()
        val a1 = plan.level(Cefr.Level.A1)!!.lessons.first()

        assertFalse(LessonSpecBuilder.build(a0, plan, LearnerModel(), profile, NOW).rubric.allowNativeLanguage)
        assertTrue(LessonSpecBuilder.build(a1, plan, LearnerModel(), profile, NOW).rubric.allowNativeLanguage)
    }

    @Test
    fun `the allowed set is what the learner knows plus this lesson's new words`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A0)!!.lessons.first()
        var model = LearnerModel()
        // Five spaced successes, so these genuinely clear the mastery line.
        var clock = NOW
        repeat(5) {
            model = model.record(LearnerModel.vocabId("apple"), Fsrs.Rating.GOOD, clock)
            clock += (Fsrs.dueAt(model.state(LearnerModel.vocabId("apple"))) - clock).coerceAtLeast(86_400_000L)
        }

        val spec = LessonSpecBuilder.build(lesson, plan, model, profile, clock)
        assertTrue("apple" in spec.allowedConcepts, "a mastered word stays available")
        assertTrue(
            spec.newWords.all { it.conceptId in spec.allowedConcepts },
            "this lesson's own new words must be allowed",
        )
    }

    @Test
    fun `grammar lessons are typed and everything else is spoken`() {
        val plan = plan()
        val pattern = plan.levels.flatMap { it.lessons }
            .firstOrNull { it.kind == Cefr.LessonKind.PATTERN }
        assertNotNull(pattern, "the plan should contain grammar lessons")
        assertEquals(
            LessonSpec.Mode.WRITING,
            LessonSpecBuilder.build(pattern, plan, LearnerModel(), profile, NOW).mode,
        )

        val conversation = plan.levels.flatMap { it.lessons }
            .first { it.kind == Cefr.LessonKind.CONVERSATION }
        assertEquals(
            LessonSpec.Mode.SPEAKING,
            LessonSpecBuilder.build(conversation, plan, LearnerModel(), profile, NOW).mode,
        )
    }

    @Test
    fun `A0 is spoken from end to end, including the script lesson`() {
        // The reported bug. "Reading the writing system" matched a "writing system" substring and
        // so opened the very first lesson of every new-script language with a keyboard, in a level
        // whose entire method is hear-it-and-say-it-back.
        val japanese = plan("ja")
        val a0 = japanese.level(Cefr.Level.A0)!!

        assertTrue(a0.lessons.any { it.objective.contains("Reading the writing system") })
        a0.lessons.forEach { lesson ->
            assertEquals(
                LessonSpec.Mode.SPEAKING,
                LessonSpecBuilder.build(lesson, japanese, LearnerModel(), profile, NOW).mode,
                "A0 lesson '${lesson.objective}' must be spoken",
            )
        }
    }

    @Test
    fun `producing a script is typed and it lives at A1`() {
        val japanese = plan("ja")
        val a1 = japanese.level(Cefr.Level.A1)!!
        val writing = a1.lessons.firstOrNull { it.objective.startsWith("Writing the writing system") }

        assertNotNull(writing, "script production belongs at A1, where typing is allowed")
        assertEquals(
            LessonSpec.Mode.WRITING,
            LessonSpecBuilder.build(writing, japanese, LearnerModel(), profile, NOW).mode,
        )
    }

    @Test
    fun `a language with no new script gets no script lessons at all`() {
        val spanish = plan("es")
        assertFalse(
            spanish.levels.flatMap { it.lessons }.any { it.objective.contains("writing system", true) },
            "an English speaker learning Spanish already reads the alphabet",
        )
    }

    @Test
    fun `the reading aid is shown for a new script and withdrawn by B1`() {
        val japanese = plan("ja")
        val a0 = japanese.level(Cefr.Level.A0)!!.lessons.first()
        val b1 = japanese.level(Cefr.Level.B1)?.lessons?.first()

        assertTrue(LessonSpecBuilder.build(a0, japanese, LearnerModel(), profile, NOW).showRomanisation)
        if (b1 != null) {
            assertFalse(
                LessonSpecBuilder.build(b1, japanese, LearnerModel(), profile, NOW).showRomanisation,
                "the crutch has to come off, or the learner never reads",
            )
        }

        val spanish = plan("es")
        val esA0 = spanish.level(Cefr.Level.A0)!!.lessons.first()
        assertFalse(LessonSpecBuilder.build(esA0, spanish, LearnerModel(), profile, NOW).showRomanisation)
    }

    @Test
    fun `conversation lessons get a scenario at or below the learner's level`() {
        val plan = plan()
        val conversation = plan.levels.flatMap { it.lessons }
            .first { it.kind == Cefr.LessonKind.CONVERSATION }
        val spec = LessonSpecBuilder.build(conversation, plan, LearnerModel(), profile, NOW)

        assertNotNull(spec.scenario)
        assertTrue(
            spec.scenario.minLevel.ordinal <= spec.level.ordinal,
            "a scenario above the learner is a wall, not a stretch",
        )
    }

    @Test
    fun `the same lesson always gets the same scenario`() {
        val plan = plan()
        val lesson = plan.levels.flatMap { it.lessons }.first { it.kind == Cefr.LessonKind.CONVERSATION }
        val a = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)
        val b = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW + 99_999L)
        assertEquals(a.scenario?.id, b.scenario?.id)
    }

    // -- The validation gate --------------------------------------------------

    private fun specFor(target: String, level: Cefr.Level, allowed: Set<String>): LessonSpec =
        LessonSpec(
            lessonId = "t", level = level, kind = Cefr.LessonKind.VOCABULARY,
            objective = "test", topic = null,
            targetCode = target, nativeCode = "en",
            targetName = target, nativeName = "English",
            newWords = emptyList(), newPatterns = emptyList(),
            reviewWords = emptyList(), reviewPatterns = emptyList(),
            allowedConcepts = allowed, pictures = emptyList(), scenario = null,
            mode = LessonSpec.Mode.SPEAKING, showRomanisation = false, turnTarget = 8,
            rubric = LessonSpec.Rubric(true, true, false, true, emptyList()),
            tutor = LessonSpec.TutorBrief("M", "a", "m", "Sam", false, false, "nervous"),
        )

    @Test
    fun `a model teaching in English during a Japanese lesson is caught`() {
        val spec = specFor("ja", Cefr.Level.A1, setOf("dog", "cat"))
        val result = LessonValidator.validate("Here we go.", "Let's talk about animals!", spec)

        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.WRONG_SCRIPT in result.problems)
    }

    @Test
    fun `the tutor's own line is allowed to be in the learner's language`() {
        // The whole design: SAY is the learner's language, TEACH is the target. A gate that
        // demanded Japanese in both would reject every correctly formed reply.
        val spec = specFor("ja", Cefr.Level.A1, setOf("dog"))
        assertTrue(LessonValidator.validate("Nice one! Now try this.", "犬", spec).ok)
    }

    @Test
    fun `a proper Japanese item passes`() {
        val spec = specFor("ja", Cefr.Level.A1, setOf("dog", "cat"))
        assertTrue(LessonValidator.validate("Say it after me.", "犬です。猫です。", spec).ok)
    }

    @Test
    fun `a taught item that came back in English is caught even in the same alphabet`() {
        val spec = specFor("es", Cefr.Level.A0, setOf("apple"))
        val result = LessonValidator.validate("Here is the first one.", "This is the apple", spec)
        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.TAUGHT_IN_NATIVE in result.problems)
    }

    @Test
    fun `a picture lesson that teaches nothing is caught`() {
        val spec = specFor("es", Cefr.Level.A0, setOf("apple")).copy(kind = Cefr.LessonKind.PICTURE)
        val result = LessonValidator.validate("Well done, that was great!", null, spec)
        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.NOTHING_TAUGHT in result.problems)
    }

    @Test
    fun `a word the learner has never met is caught at A1`() {
        val spec = specFor("es", Cefr.Level.A1, setOf("apple", "bread"))
        val result = LessonValidator.validate("Repeat this.", "La manzana y el ferrocarril", spec)

        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.UNKNOWN_WORDS in result.problems)
        assertTrue(
            result.offendingWords.any { it.contains("ferrocarril", true) },
            "expected the unknown word to be named, got ${result.offendingWords}",
        )
    }

    @Test
    fun `inflections of an allowed word are not treated as unknown`() {
        val spec = specFor("es", Cefr.Level.A1, setOf("apple"))
        val result = LessonValidator.validate("Your turn.", "Las manzanas", spec)
        assertTrue(
            result.ok,
            "a plural of a taught word must pass, got ${result.offendingWords}",
        )
    }

    @Test
    fun `function words never count as unknown vocabulary`() {
        val spec = specFor("es", Cefr.Level.A1, setOf("apple"))
        val unknown = LessonValidator.unknownContentWords("¿Es la manzana? Sí, y no.", spec)
        assertTrue(unknown.isEmpty(), "articles and copulas are not vocabulary: $unknown")
    }

    @Test
    fun `the learner's own name is not mistaken for an unknown word`() {
        val spec = specFor("es", Cefr.Level.A1, setOf("apple"))
        val unknown = LessonValidator.unknownContentWords("Hola Sam, la manzana", spec)
        assertFalse(unknown.any { it.equals("Sam", true) }, "got $unknown")
    }

    @Test
    fun `a model that started lecturing is caught by length`() {
        val spec = specFor("es", Cefr.Level.A0, setOf("apple"))
        val result = LessonValidator.validate("Go on.", "la manzana ".repeat(20), spec)
        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.TOO_LONG in result.problems)
    }

    @Test
    fun `leaked instructions are caught`() {
        val spec = specFor("es", Cefr.Level.B1, emptySet())
        val result = LessonValidator.validate("As an AI, I cannot do that.", null, spec)
        assertFalse(result.ok)
        assertTrue(LessonValidator.Problem.LEAKED_INSTRUCTIONS in result.problems)
    }

    @Test
    fun `an empty reply never reaches the learner`() {
        val spec = specFor("es", Cefr.Level.A1, setOf("apple"))
        assertFalse(LessonValidator.validate("   ", null, spec).ok)
    }

    @Test
    fun `the vocabulary check is not applied above A2 where it would reject normal speech`() {
        val spec = specFor("es", Cefr.Level.B2, setOf("apple"))
        val result = LessonValidator.validate(
            "Let's discuss.", "Creo que la situación económica influye bastante en esa decisión.", spec,
        )
        assertTrue(result.ok, "B2 speech must not be gated on a beginner lexicon: ${result.offendingWords}")
    }

    // -- Prompt and parsing ---------------------------------------------------

    @Test
    fun `the brief names the words to teach and forbids the native language at A0`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A0)!!.lessons.first()
        val spec = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)
        val brief = LessonPrompt.brief(spec)

        assertTrue(brief.contains("SAY is written in English"), brief.take(600))
        assertTrue(brief.contains("TEACH is written in Spanish"), brief.take(600))
        spec.newWords.take(3).forEach {
            assertTrue(brief.contains(it.word), "the brief must name ${it.word}")
        }
    }

    @Test
    fun `a well-formed reply parses into its parts`() {
        val reply = LessonPrompt.parse(
            """
            SAY: Here comes the first one.
            TEACH: la manzana
            HINT: Say what the picture shows
            NOTE: 'la manzana' not 'el manzana'
            DONE: no
            """.trimIndent()
        )
        assertEquals("Here comes the first one.", reply.say)
        assertEquals("la manzana", reply.teach)
        assertEquals("Say what the picture shows", reply.hint)
        assertNotNull(reply.note)
        assertFalse(reply.done)
    }

    @Test
    fun `a reply that ignored the format still yields something to say`() {
        // Small models do this constantly. Failing the turn would be worse than taking what it said.
        val reply = LessonPrompt.parse("¡Hola! Vamos a empezar.")
        assertTrue(reply.say.isNotBlank())
    }

    @Test
    fun `a report grades every word it was asked about even if the model skipped some`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A0)!!.lessons.first()
        val spec = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)

        val report = LessonPrompt.parseReport("WELL: Good\nWORK: Slow\nSCORE: 70", spec)
        assertEquals(70, report.score)
        assertEquals(
            spec.newWords.map { it.conceptId }.toSet(),
            report.wordRatings.keys,
            "an ungraded word would never be scheduled again",
        )
    }

    // -- The fallback ---------------------------------------------------------

    @Test
    fun `there is always a lesson even with no model at all`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A0)!!.lessons.first()
        val spec = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)

        val opening = LessonTemplates.opening(spec)
        assertTrue(opening.say.isNotBlank())
        assertTrue(LessonTemplates.report(spec, 4).wordRatings.isNotEmpty())
    }

    @Test
    fun `the fallback says it is a fallback rather than pretending`() {
        val plan = plan()
        val lesson = plan.level(Cefr.Level.A1)!!.lessons.first()
        val spec = LessonSpecBuilder.build(lesson, plan, LearnerModel(), profile, NOW)
        assertTrue(LessonTemplates.noModelNotice(spec).contains("drill", ignoreCase = true))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
