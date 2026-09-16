package com.prism.launcher.search

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PageRank and ranking behaviour, checked against hand-computable cases.
 *
 * The point of PageRank here is that authority comes from the link graph and nothing else, so
 * these tests are written as claims about the graph rather than about any particular page: rank
 * sums to one, a page nobody links to ranks below one that everybody links to, and adding an
 * inbound link can only help. If any of those stops holding, the "unbiased" claim stops being
 * true regardless of what the numbers look like.
 */
class PrismSearchTest {

    private fun page(url: String, links: List<String>, title: String = "", text: String = "") =
        PrismSearchIndex.Page(url, title, text, links)

    @Test
    fun pageRankSumsToOne() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("http://a/", listOf("http://b/", "http://c/")),
                page("http://b/", listOf("http://c/")),
                page("http://c/", listOf("http://a/")),
                page("http://d/", emptyList())          // dangling: must not leak rank away
            )
        )
        val total = listOf("http://a/", "http://b/", "http://c/", "http://d/")
            .sumOf { PrismSearchIndex.rankOf(it) }
        assertTrue(abs(total - 1.0) < 1e-3, "ranks summed to $total, expected 1.0")
    }

    @Test
    fun moreInboundLinksOutranksFewer() {
        // b and c both point at "popular"; nobody points at "ignored".
        PrismSearchIndex.replaceAll(
            listOf(
                page("http://b/", listOf("http://popular/")),
                page("http://c/", listOf("http://popular/")),
                page("http://popular/", listOf("http://b/")),
                page("http://ignored/", listOf("http://b/"))
            )
        )
        val popular = PrismSearchIndex.rankOf("http://popular/")
        val ignored = PrismSearchIndex.rankOf("http://ignored/")
        assertTrue(popular > ignored, "popular=$popular should outrank ignored=$ignored")
    }

    @Test
    fun rankingPrefersTheAuthoritativePageAmongEqualMatches() {
        // Both pages match "kotlin coroutines" identically; only the link graph separates them.
        val authoritative = page(
            "http://docs/", listOf("http://blog/"),
            title = "Kotlin coroutines", text = "kotlin coroutines guide"
        )
        val obscure = page(
            "http://obscure/", emptyList(),
            title = "Kotlin coroutines", text = "kotlin coroutines guide"
        )
        PrismSearchIndex.replaceAll(
            listOf(
                authoritative, obscure,
                page("http://blog/", listOf("http://docs/")),
                page("http://news/", listOf("http://docs/")),
                page("http://wiki/", listOf("http://docs/"))
            )
        )
        val results = PrismSearchIndex.search("kotlin coroutines")
        assertTrue(results.size >= 2, "expected both pages to match, got ${results.size}")
        assertEquals("http://docs/", results.first().url, "the linked-to page should rank first")
    }

    @Test
    fun everyQueryTermMustMatch() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("http://one/", emptyList(), text = "alpha beta"),
                page("http://two/", emptyList(), text = "alpha only")
            )
        )
        // "beta" appears in only one document, so an AND search must not return the other.
        val urls = PrismSearchIndex.search("alpha beta").map { it.url }
        assertEquals(listOf("http://one/"), urls)
    }

    @Test
    fun titleMatchesOutweighBodyMentions() {
        PrismSearchIndex.replaceAll(
            listOf(
                page("http://titled/", emptyList(), title = "Widgets", text = "an article"),
                page("http://buried/", emptyList(), title = "Something else", text = "widgets mentioned once")
            )
        )
        val results = PrismSearchIndex.search("widgets")
        assertEquals("http://titled/", results.first().url)
    }

    @Test
    fun urlNormalizationCollapsesDuplicates() {
        // Same page, three spellings -- if these counted separately their inbound links would be
        // split three ways and each copy would get a third of the rank it deserves.
        val a = PrismSearchIndex.normalize("http://Example.com/a/")
        val b = PrismSearchIndex.normalize("http://example.com/a#section")
        val c = PrismSearchIndex.normalize("http://example.com:80/a")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun emptyIndexAndEmptyQueryAreSafe() {
        PrismSearchIndex.replaceAll(emptyList())
        assertTrue(PrismSearchIndex.search("anything").isEmpty())
        PrismSearchIndex.replaceAll(listOf(page("http://a/", emptyList(), text = "hello")))
        assertTrue(PrismSearchIndex.search("").isEmpty())
        assertTrue(PrismSearchIndex.search("   ").isEmpty())
    }

    @Test
    fun crawlerExtractsLinksAndTitleAndResolvesRelativeUrls() {
        val html = """
            <html><head><title> Example  Page </title></head>
            <body><script>ignore()</script>
            <a href="/about">About</a>
            <a href='https://other.example/x'>Other</a>
            <a href="javascript:void(0)">Bad</a>
            <p>Hello world</p></body></html>
        """.trimIndent()
        assertEquals("Example Page", PrismCrawler.extractTitle(html))

        val links = PrismCrawler.extractLinks(html, "http://site.example/dir/page.html")
        assertTrue(links.contains("http://site.example/about"), "relative link not resolved: $links")
        assertTrue(links.contains("https://other.example/x"))
        assertTrue(links.none { it.startsWith("javascript") }, "javascript: link was kept")

        val text = PrismCrawler.htmlToText(html)
        assertTrue(text.contains("Hello world"))
        assertTrue(!text.contains("ignore()"), "script body leaked into indexed text")
    }
}
