package com.prism.launcher.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The plan is the only part of the language feature that can be wrong in a way nobody notices.
 *
 * A broken path view is obvious the moment it renders. A plan that quietly puts a beginner at B2,
 * or gives a Spanish speaker learning Portuguese the same syllabus as an English speaker learning
 * Japanese, looks perfectly fine and is useless. These tests pin the decisions that carry the
 * pedagogy.
 */
class PlanBuilderTest {

    private fun input(
        target: String = "es",
        native: String = "en",
        selfLevel: String? = "A0",
        introduce: Boolean? = false,
        converse: Boolean? = false,
        understand: Boolean? = false,
        dream: String? = "travel",
        motivation: String? = "travel",
        pace: String? = "no_rush",
        minutes: Int = 15,
        interests: List<String> = listOf("cooking", "music"),
    ) = PlanBuilder.Input(
        targetCode = target,
        nativeCode = native,
        selfLevel = selfLevel,
        canIntroduce = introduce,
        canConverse = converse,
        canUnderstand = understand,
        motivation = motivation,
        dream = dream,
        pace = pace,
        confidence = "nervous",
        wantsPronunciation = true,
        wantsChallenge = false,
        practiceStyle = "structured",
        interests = interests,
        roleplay = listOf("travel"),
        skills = listOf("media"),
        minutesPerDay = minutes,
        now = 1_700_000_000_000L,
    )

    // -- Placement ------------------------------------------------------------

    @Test
    fun `a complete beginner starts at A0`() {
        assertEquals(Cefr.Level.A0, PlanBuilder.placement(input()))
    }

    @Test
    fun `placement rounds down when the learner claims more than they can account for`() {
        // Says B2, but cannot introduce themselves or hold a conversation. Averaging B2 with the
        // A1 the answers demonstrate lands at A2 -- the cheaper error, by design.
        val placed = PlanBuilder.placement(
            input(selfLevel = "B2", introduce = true, converse = false, understand = false)
        )
        assertTrue(
            placed.ordinal < Cefr.Level.B2.ordinal,
            "a claim the answers do not support must not be taken at face value, got $placed",
        )
    }

    @Test
    fun `a learner who can do all three is not held at the bottom`() {
        val placed = PlanBuilder.placement(
            input(selfLevel = "B1", introduce = true, converse = true, understand = true)
        )
        assertTrue(placed.ordinal >= Cefr.Level.A2.ordinal, "got $placed")
    }

    @Test
    fun `an unanswered self-assessment leaves the claim alone`() {
        val placed = PlanBuilder.placement(
            input(selfLevel = "B1", introduce = null, converse = null, understand = null)
        )
        assertEquals(Cefr.Level.B1, placed)
    }

    // -- Goal -----------------------------------------------------------------

    @Test
    fun `an exam goal aims at C1 and a holiday goal does not`() {
        val exam = PlanBuilder.build(input(dream = "exam", motivation = "career"))
        val holiday = PlanBuilder.build(input(dream = "travel", motivation = "travel"))

        assertEquals(Cefr.Level.C1, exam.goal)
        assertTrue(
            holiday.goal.ordinal < Cefr.Level.C1.ordinal,
            "someone who wants to order dinner should not be shown a path to C1, got ${holiday.goal}",
        )
    }

    @Test
    fun `the goal always leaves at least one level of headroom`() {
        val plan = PlanBuilder.build(
            input(selfLevel = "C2", introduce = true, converse = true, understand = true, dream = "travel")
        )
        assertTrue(plan.goal.ordinal > plan.placement.ordinal || plan.placement == Cefr.Level.C2)
    }

    // -- Difficulty -----------------------------------------------------------

    @Test
    fun `a harder language for this learner means more lessons for the same level`() {
        val spanish = PlanBuilder.build(input(target = "es", native = "en"))
        val japanese = PlanBuilder.build(input(target = "ja", native = "en"))

        val spanishA1 = spanish.level(Cefr.Level.A1)!!.lessons.size
        val japaneseA1 = japanese.level(Cefr.Level.A1)!!.lessons.size

        assertTrue(
            japaneseA1 > spanishA1,
            "Japanese A1 ($japaneseA1) should need more practice than Spanish A1 ($spanishA1) for " +
                "an English speaker",
        )
    }

    @Test
    fun `relatedness discounts the work and is not ignored`() {
        val fromEnglish = LanguageDistance.difficulty("en", "pt")
        val fromSpanish = LanguageDistance.difficulty("es", "pt")

        assertTrue(
            fromSpanish < fromEnglish,
            "Portuguese is less work for a Spanish speaker ($fromSpanish) than for an English one " +
                "($fromEnglish)",
        )
    }

    @Test
    fun `Japanese is cheaper for a Korean speaker than for an English speaker`() {
        assertTrue(LanguageDistance.difficulty("ko", "ja") < LanguageDistance.difficulty("en", "ja"))
    }

    @Test
    fun `a new writing system is recognised and taught`() {
        val plan = PlanBuilder.build(input(target = "ja", native = "en"))
        assertTrue(plan.newScript)

        val a0 = plan.level(Cefr.Level.A0)!!
        assertTrue(
            a0.lessons.any { it.objective.contains("writing system", ignoreCase = true) },
            "a learner facing a new script must be taught it, not left to absorb it",
        )
    }

    @Test
    fun `sharing a script is not mistaken for needing a new one`() {
        assertFalse(PlanBuilder.build(input(target = "es", native = "en")).newScript)
    }

    // -- Level content --------------------------------------------------------

    @Test
    fun `A0 is entirely picture based`() {
        val a0 = PlanBuilder.build(input()).level(Cefr.Level.A0)!!
        assertTrue(a0.lessons.isNotEmpty())
        assertTrue(
            a0.lessons.all { it.kind == Cefr.LessonKind.PICTURE || it.kind == Cefr.LessonKind.REVIEW },
            "A0 is picture association and nothing else: ${a0.lessons.map { it.kind }.distinct()}",
        )
    }

    @Test
    fun `levels above A0 are not picture based`() {
        val b1 = PlanBuilder.build(input(selfLevel = "A2")).level(Cefr.Level.B1)!!
        assertFalse(b1.lessons.any { it.kind == Cefr.LessonKind.PICTURE })
    }

    @Test
    fun `review is spaced through a level rather than piled at the end`() {
        val a1 = PlanBuilder.build(input()).level(Cefr.Level.A1)!!
        val reviewPositions = a1.lessons.withIndex()
            .filter { it.value.kind == Cefr.LessonKind.REVIEW }
            .map { it.index }

        assertTrue(reviewPositions.size >= 2, "expected several reviews, got $reviewPositions")
        assertTrue(
            reviewPositions.first() < a1.lessons.size / 2,
            "the first review must come early, not after everything is forgotten",
        )
    }

    @Test
    fun `lessons are given topics the learner actually chose`() {
        val plan = PlanBuilder.build(input(interests = listOf("gardening", "cinema")))
        val topics = plan.levels.flatMap { it.lessons }.mapNotNull { it.topic }.distinct()

        assertTrue(topics.isNotEmpty())
        assertTrue(
            topics.all { it == "gardening" || it == "cinema" || it == "travel" },
            "topics must come from the learner's own answers, got $topics",
        )
    }

    @Test
    fun `a goal-relevant objective is brought forward`() {
        val interview = PlanBuilder.build(input(dream = "interview", motivation = "career"))
        val b1 = interview.level(Cefr.Level.B1)!!
        val position = b1.lessons.indexOfFirst { it.objective.contains("interview", ignoreCase = true) }

        assertTrue(position >= 0, "the interview objective should still be present")
        assertTrue(
            position < b1.lessons.size / 2,
            "someone preparing for an interview should meet it early, found it at $position",
        )
    }

    @Test
    fun `an exam goal adds a checkpoint and a holiday goal does not`() {
        val exam = PlanBuilder.build(input(dream = "exam", motivation = "study"))
        val holiday = PlanBuilder.build(input(dream = "travel", motivation = "travel"))

        assertTrue(exam.levels.any { it.lessons.any { l -> l.kind == Cefr.LessonKind.ASSESSMENT } })
        assertFalse(holiday.levels.any { it.lessons.any { l -> l.kind == Cefr.LessonKind.ASSESSMENT } })
    }

    @Test
    fun `declining pronunciation help removes those lessons without losing the time`() {
        val wants = PlanBuilder.build(input()).level(Cefr.Level.A1)!!
        val declines = PlanBuilder.build(
            input().copy(wantsPronunciation = false)
        ).level(Cefr.Level.A1)!!

        assertTrue(wants.lessons.any { it.kind == Cefr.LessonKind.PRONUNCIATION })
        assertFalse(declines.lessons.any { it.kind == Cefr.LessonKind.PRONUNCIATION })
        assertEquals(
            wants.lessons.size, declines.lessons.size,
            "the lessons should be re-pointed, not deleted",
        )
    }

    // -- Levels below placement ----------------------------------------------

    @Test
    fun `levels below the placement are kept and marked known`() {
        val plan = PlanBuilder.build(
            input(selfLevel = "B1", introduce = true, converse = true, understand = true)
        )
        val known = plan.levels.filter { it.alreadyKnown }

        assertTrue(known.isNotEmpty(), "a learner placed above A0 should have levels behind them")
        assertTrue(
            known.all { it.lessons.isNotEmpty() },
            "a known level still has to be openable -- placement is a guess, not a verdict",
        )
    }

    // -- Honesty about time ---------------------------------------------------

    @Test
    fun `the estimate follows the framework's hours and not the lesson count`() {
        val plan = PlanBuilder.build(input(target = "es", native = "en", minutes = 15))
        val a1 = plan.level(Cefr.Level.A1)!!

        // 90 guided hours for A1 at roughly difficulty 1.0 for en->es.
        assertTrue(
            a1.estimatedMinutes in 4_000..7_000,
            "A1 should be somewhere near the published 90 hours, got ${a1.estimatedMinutes} minutes",
        )
    }

    @Test
    fun `an impossible pace is said out loud rather than flattered`() {
        val plan = PlanBuilder.build(
            input(target = "ja", native = "en", pace = "one_month", minutes = 10, dream = "exam")
        )
        assertTrue(plan.paceNote.isNotBlank())
        assertTrue(
            Regex("\\d+ minutes").containsMatchIn(plan.paceNote),
            "the note must name the daily minutes the asked-for pace would actually need: " +
                plan.paceNote,
        )
    }

    @Test
    fun `a multi-year estimate is never described as fast`() {
        // Mandarin to B2 for an English speaker at half an hour a day is genuinely years of work.
        // A first version of this copy quoted eight years and called it "still fast", which is the
        // kind of encouragement that costs the app its credibility the moment somebody checks.
        val plan = PlanBuilder.build(
            input(target = "zh", native = "en", pace = "one_year", minutes = 30, dream = "move")
        )
        assertTrue(plan.estimatedDays > 365 * 3, "expected a long estimate, got ${plan.estimatedDays} days")
        assertFalse(
            plan.paceNote.contains("fast", ignoreCase = true),
            "a years-long road must not be sold as quick: ${plan.paceNote}",
        )
    }

    @Test
    fun `the pace note anchors on the next level and not only the final goal`() {
        // The whole road may be years. The next level is weeks or months, it is the thing the
        // learner will actually reach, and leading with the far horizon alone is the single most
        // discouraging thing this screen could say.
        val plan = PlanBuilder.build(
            input(target = "zh", native = "en", pace = "one_year", minutes = 30)
        )
        val next = plan.levels.first { !it.alreadyKnown }
        assertTrue(
            plan.paceNote.contains(next.level.code),
            "expected ${next.level.code} named as the near milestone in: ${plan.paceNote}",
        )
    }

    @Test
    fun `a comfortable pace is confirmed`() {
        val plan = PlanBuilder.build(
            input(target = "es", native = "es", pace = "one_year", minutes = 60)
        )
        assertTrue(plan.paceNote.isNotBlank())
    }

    // -- Determinism ----------------------------------------------------------

    @Test
    fun `the same answers always produce the same plan`() {
        val a = PlanBuilder.build(input())
        val b = PlanBuilder.build(input())

        assertEquals(a.totalLessons, b.totalLessons)
        assertEquals(
            a.levels.flatMap { it.lessons }.map { it.id to it.title },
            b.levels.flatMap { it.lessons }.map { it.id to it.title },
        )
    }

    @Test
    fun `lesson ids are unique across the whole plan`() {
        val ids = PlanBuilder.build(input()).levels.flatMap { it.lessons }.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "progress is keyed on these")
    }

    @Test
    fun `the first unfinished lesson skips levels the learner already has`() {
        val plan = PlanBuilder.build(
            input(selfLevel = "A2", introduce = true, converse = true, understand = false)
        )
        val next = plan.firstUnfinished(emptySet())

        assertNotNull(next)
        assertTrue(
            next.level.ordinal >= plan.placement.ordinal,
            "opened at ${next.level} for a learner placed at ${plan.placement}",
        )
    }
}
