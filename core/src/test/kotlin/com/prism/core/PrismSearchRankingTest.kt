package com.prism.core

import com.prism.launcher.search.PrismSearchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ranking behaviour of the Prism search engine.
 *
 * These are the properties that motivated replacing TF-IDF with BM25F: each test names a failure
 * the old scoring actually had, so a regression here means a user-visible one rather than a
 * cosmetic score change.
 */
class PrismSearchRankingTest {

    private fun page(
        url: String,
        title: String,
        body: String,
        links: List<String> = emptyList(),
        anchors: Map<String, String> = emptyMap(),
    ) = PrismSearchIndex.Page(url = url, title = title, text = body, outLinks = links, anchors = anchors)

    @Test
    fun `a short focused page beats a long page that merely repeats the term`() {
        val long = (1..200).joinToString(" ") { "noise$it" }
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/focused", "Kotlin coroutines", "Kotlin coroutines explained in full with examples."),
                page("https://b.com/padded", "Notes", "$long kotlin $long kotlin $long kotlin $long"),
            )
        )
        val results = PrismSearchIndex.search("kotlin coroutines", limit = 5)
        assertEquals("https://a.com/focused", results.first().url)
    }

    @Test
    fun `a title phrase outranks the same words scattered through a body`() {
        val scattered = "port " + (1..120).joinToString(" ") { "word$it" } + " forwarding " +
            (1..120).joinToString(" ") { "other$it" } + " router"
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/phrase", "Port forwarding on a router", "How to do it."),
                page("https://b.com/scattered", "Networking notes", scattered),
            )
        )
        val results = PrismSearchIndex.search("port forwarding router", limit = 5)
        assertEquals("https://a.com/phrase", results.first().url)
    }

    @Test
    fun `a misspelling is corrected when the real term is in the index`() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/mail", "How to receive mail", "You receive mail over IMAP."),
                page("https://b.com/mail2", "Receiving mail", "Receiving mail needs a server."),
                page("https://c.com/other", "Unrelated", "Nothing to see."),
            )
        )
        val outcome = PrismSearchIndex.searchOutcome("recieve mail", limit = 5)
        assertEquals("receive mail", outcome.corrected)
        assertTrue(outcome.results.isNotEmpty(), "the corrected query found nothing")
    }

    @Test
    fun `synonyms find a page that never uses the typed word`() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/repair", "Repair a bug", "Steps to repair the bug in your program."),
                page("https://b.com/unrelated", "Gardening", "Tomatoes need water."),
            )
        )
        val results = PrismSearchIndex.search("fix error", limit = 5)
        assertTrue(
            results.any { it.url == "https://a.com/repair" },
            "the repair page was not reachable through synonyms: ${results.map { it.url }}",
        )
    }

    @Test
    fun `anchor text makes a page findable by what other pages call it`() {
        PrismSearchIndex.replaceAll(
            listOf(
                // The target never says "port forwarding guide" itself.
                page("https://b.com/target", "Networking", "Open the settings and change the mapping."),
                page(
                    "https://a.com/index",
                    "Home",
                    "Read this page.",
                    links = listOf("https://b.com/target"),
                    anchors = mapOf("https://b.com/target" to "port forwarding guide"),
                ),
                page("https://c.com/other", "Random", "port something forwarding something router"),
            )
        )
        val results = PrismSearchIndex.search("port forwarding guide", limit = 5)
        assertEquals("https://b.com/target", results.first().url)
    }

    @Test
    fun `one host cannot fill the result list`() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/1", "Notes one", "alpha beta gamma"),
                page("https://a.com/2", "Notes two", "alpha beta gamma"),
                page("https://a.com/3", "Notes three", "alpha beta gamma"),
                page("https://b.com/1", "Other", "alpha beta gamma"),
                page("https://c.com/1", "Third", "alpha beta gamma"),
            )
        )
        val results = PrismSearchIndex.search("alpha beta gamma", limit = 5)
        val fromA = results.take(3).count { it.url.contains("a.com") }
        assertTrue(fromA <= 2, "host diversity let one site take $fromA of the first three")
    }

    @Test
    fun `authority breaks ties between equally relevant pages`() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/plain", "Widget guide", "widget guide"),
                page("https://b.com/linked", "Widget guide", "widget guide"),
                page("https://c.com/x", "Cites b", "read this", links = listOf("https://b.com/linked")),
                page("https://d.com/x", "Cites b again", "read this too", links = listOf("https://b.com/linked")),
                page("https://e.com/x", "Cites b too", "and this", links = listOf("https://b.com/linked")),
            )
        )
        val results = PrismSearchIndex.search("widget guide", limit = 5)
        assertEquals("https://b.com/linked", results.first().url)
    }

    @Test
    fun `a query with no matches reports the correction rather than silently returning nothing`() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("https://a.com/one", "Bicycle repair", "repair a bicycle"),
                page("https://b.com/two", "Bicycle shop", "buy a bicycle"),
            )
        )
        val outcome = PrismSearchIndex.searchOutcome("bicycl", limit = 5)
        assertTrue(outcome.results.isNotEmpty() || outcome.corrected != null)
    }
}
