package com.prism.launcher.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The banded vocabulary, and the mechanism that carries it past where the lists stop.
 *
 * The coverage checks here are the only thing standing between a hand-written `id=word` format and
 * a language quietly losing thirty entries to a typo. The band checks are what stop a beginner
 * being handed "assumption" before "because".
 */
class LexiconBandsTest {

    private val languages = Lexicon.supportedCodes()

    @Test
    fun `every language covers the abstract tier as well as the concrete one`() {
        val poor = languages.mapNotNull { code ->
            val coverage = Lexicon.coverage(code)
            if (coverage < 0.95) "$code=${"%.2f".format(coverage)}" else null
        }
        assertTrue(poor.isEmpty(), "languages missing vocabulary: $poor")
    }

    @Test
    fun `no abstract entry names a concept that does not exist`() {
        val known = Concepts.ALL.map { it.id }.toSet()
        val strays = languages.flatMap { code ->
            Lexicon.vocabulary(code).map { it.conceptId }.filter { it !in known }.map { "$code:$it" }
        }
        assertTrue(strays.isEmpty(), "lexicon ids with no matching concept: $strays")
    }

    @Test
    fun `every band from A0 to B2 has vocabulary in every language`() {
        val bands = listOf(
            Cefr.Level.A0, Cefr.Level.A1, Cefr.Level.A2, Cefr.Level.B1, Cefr.Level.B2,
        )
        languages.forEach { code ->
            bands.forEach { band ->
                val atBand = Lexicon.vocabulary(code, band).size -
                    (if (band == Cefr.Level.A0) 0 else Lexicon.vocabulary(code, Cefr.Level.entries[band.ordinal - 1]).size)
                assertTrue(atBand > 0, "$code has no vocabulary at ${band.code}")
            }
        }
    }

    @Test
    fun `a band filter is cumulative and never returns words above the level`() {
        val a0 = Lexicon.vocabulary("es", Cefr.Level.A0)
        val a1 = Lexicon.vocabulary("es", Cefr.Level.A1)
        val b2 = Lexicon.vocabulary("es", Cefr.Level.B2)

        assertTrue(a0.size < a1.size, "A1 must include A0")
        assertTrue(a1.size < b2.size, "B2 must include A1")

        val bands = Concepts.ALL.associate { it.id to it.band }
        assertTrue(
            a1.all { (bands[it.conceptId] ?: Cefr.Level.A0).ordinal <= Cefr.Level.A1.ordinal },
            "an A1 request must not return B1 words",
        )
    }

    @Test
    fun `an A0 learner is never offered an abstraction`() {
        val model = LearnerModel()
        val next = model.nextNewConcepts("es", 12, Cefr.Level.A0)
        val bands = Concepts.ALL.associate { it.id to it.band }

        assertTrue(next.isNotEmpty())
        assertTrue(
            next.all { bands[it.conceptId] == Cefr.Level.A0 },
            "A0 must stay depictable: ${next.map { it.conceptId }}",
        )
    }

    @Test
    fun `concrete words are taught before abstract ones`() {
        val order = Lexicon.vocabulary("fr")
        val bands = Concepts.ALL.associate { it.id to it.band }
        val firstAbstract = order.indexOfFirst { (bands[it.conceptId] ?: Cefr.Level.A0) != Cefr.Level.A0 }
        val lastConcrete = order.indexOfLast { (bands[it.conceptId] ?: Cefr.Level.A0) == Cefr.Level.A0 }

        assertTrue(firstAbstract > 0)
        assertTrue(
            lastConcrete < firstAbstract,
            "the depictable tier must come first: last concrete $lastConcrete, first abstract $firstAbstract",
        )
    }

    @Test
    fun `only the concrete tier is ever offered as a picture`() {
        assertTrue(
            Concepts.pictureable().all { it.band == Cefr.Level.A0 },
            "you cannot photograph 'however'",
        )
        assertTrue(Concepts.needingImages().all { it.band == Cefr.Level.A0 })
    }

    @Test
    fun `non-Latin languages romanise the abstract tier too`() {
        listOf("ja", "ko", "zh", "ar", "hi").forEach { code ->
            val abstract = Lexicon.vocabulary(code).filter {
                (Concepts.ALL.firstOrNull { c -> c.id == it.conceptId }?.band ?: Cefr.Level.A0) != Cefr.Level.A0
            }
            assertTrue(abstract.size > 100, "$code is missing abstract vocabulary")
            assertTrue(
                abstract.count { it.romanisation != null } > abstract.size * 0.95,
                "$code left the abstract tier unromanised, which a beginner cannot read",
            )
        }
    }

    @Test
    fun `Brazilian overrides apply to the abstract tier as well`() {
        assertEquals("oi", Lexicon.word("pt-BR", "hello")?.word)
        assertEquals("olá", Lexicon.word("pt", "hello")?.word)
        assertEquals(Lexicon.word("pt", "because")?.word, Lexicon.word("pt-BR", "because")?.word)
    }

    // -- Past where the lists stop --------------------------------------------

    @Test
    fun `a B2 lesson lets the tutor supply its own vocabulary and a B1 one does not`() {
        val b1 = spec(Cefr.Level.B1)
        val b2 = spec(Cefr.Level.B2)
        assertFalse(b1.usesOpenVocabulary)
        assertTrue(b2.usesOpenVocabulary)
    }

    @Test
    fun `the brief asks for declared terms only where the lists have run out`() {
        assertFalse(LessonPrompt.brief(spec(Cefr.Level.A2)).contains("NEW:"))
        assertTrue(LessonPrompt.brief(spec(Cefr.Level.C1)).contains("NEW:"))
    }

    @Test
    fun `declared terms are read back off the reply`() {
        val reply = LessonPrompt.parse(
            """
            SAY: Das wäre eine plausible Annahme.
            HINT: Agree or push back
            NOTE:
            DONE: no
            NEW: plausibel, die Annahme
            """.trimIndent()
        )
        assertEquals(listOf("plausibel", "die Annahme"), reply.newTerms)
    }

    @Test
    fun `an open term is scheduled by the same machinery as a listed word`() {
        val now = 1_700_000_000_000L
        val model = LearnerModel().record(LearnerModel.termId("Annahme"), Fsrs.Rating.HARD, now)

        assertTrue("annahme" in model.openTerms(), "stored lowercased: ${model.openTerms()}")
        assertTrue(model.seen(LearnerModel.termId("annahme")))
        // Same word, different capitalisation, must be one item and not two.
        assertEquals(LearnerModel.termId("Annahme"), LearnerModel.termId("  annahme "))
    }

    private fun spec(level: Cefr.Level) = LessonSpec(
        lessonId = "t", level = level, kind = Cefr.LessonKind.CONVERSATION,
        objective = "test", topic = null,
        targetCode = "de", nativeCode = "en", targetName = "German", nativeName = "English",
        newWords = emptyList(), newPatterns = emptyList(),
        reviewWords = emptyList(), reviewPatterns = emptyList(),
        allowedConcepts = emptySet(), pictures = emptyList(), scenario = null,
        mode = LessonSpec.Mode.SPEAKING, showRomanisation = false, turnTarget = 10,
        rubric = LessonSpec.Rubric(true, true, false, true, emptyList()),
        tutor = LessonSpec.TutorBrief("M", "a", "m", "Sam", false, false, "nervous"),
    )
}
