package com.prism.launcher.writer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriterSuggestionsTest {

    private val layout = KeyboardLayout.qwerty()

    @Test
    fun `completes a prefix`() {
        val out = WriterSuggestions.forPrefix("hel", layout)
        println("hel -> ${out.map { it.word }}")
        assertTrue(out.isNotEmpty(), "no completions for 'hel'")
        assertTrue(
            out.any { it.word.lowercase().startsWith("hel") },
            "completions did not start with the prefix: ${out.map { it.word }}",
        )
    }

    @Test
    fun `prefers common words over rare ones`() {
        // The regression this guards: score() is a cost, and reading it as a confidence offered
        // "helmet" and "helicopter" ahead of "hello" and "help".
        val out = WriterSuggestions.forPrefix("hel", layout).map { it.word.lowercase() }
        println("hel -> $out")
        assertTrue(
            out.any { it == "hello" || it == "help" || it == "held" },
            "expected a common 'hel' word, got $out",
        )
    }

    @Test
    fun `keeps the typed casing`() {
        val out = WriterSuggestions.forPrefix("Hel", layout)
        assertTrue(
            out.all { it.word.firstOrNull()?.isUpperCase() == true },
            "casing was lost: ${out.map { it.word }}",
        )
    }

    @Test
    fun `most confident goes in the middle`() {
        val three = listOf(
            WriterSuggestions.Suggestion("best", 1f),
            WriterSuggestions.Suggestion("second", 0.5f),
            WriterSuggestions.Suggestion("third", 0.1f),
        )
        // Second, best, third: the strongest under the thumb, the runner-up to its left.
        assertEquals(
            listOf("second", "best", "third"),
            WriterSuggestions.arrangeForStrip(three).map { it.word },
        )
    }

    @Test
    fun `handles one and two suggestions without dropping any`() {
        val one = listOf(WriterSuggestions.Suggestion("only", 1f))
        assertEquals(listOf("only"), WriterSuggestions.arrangeForStrip(one).map { it.word })

        val two = listOf(
            WriterSuggestions.Suggestion("best", 1f),
            WriterSuggestions.Suggestion("other", 0.4f),
        )
        assertEquals(
            listOf("other", "best"),
            WriterSuggestions.arrangeForStrip(two).map { it.word },
        )
    }

    @Test
    fun `empty prefix offers nothing`() {
        assertTrue(WriterSuggestions.forPrefix("", layout).isEmpty())
        assertTrue(WriterSuggestions.forPrefix("   ", layout).isEmpty())
    }
}
