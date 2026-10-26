package com.prism.core

import com.prism.launcher.search.WebSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parser tests for the drawer's web results.
 *
 * The fixtures are the parts of each engine's live markup the parser actually depends on. Keeping
 * them small means a change in the parser is checked against the contract (title, destination,
 * snippet) rather than against a 90 KB page whose CSS changes every few weeks.
 */
class WebSearchTest {

    @Test
    fun `duckduckgo unwraps redirect and pairs snippet`() {
        val html = """
            <div class="result results_links">
              <a rel="nofollow" class="result__a"
                 href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-overview.html&amp;rut=abc">
                Coroutines | Kotlin Documentation
              </a>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">
                <b>Kotlin</b> provides several ways for <b>coroutines</b> to communicate.
              </a>
            </div>
        """.trimIndent()

        val results = WebSearch.parseDuckDuckGo(html)

        assertEquals(1, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertEquals("Kotlin provides several ways for coroutines to communicate.", results[0].snippet)
    }

    @Test
    fun `duckduckgo lite view is parsed when the html endpoint challenges`() {
        val html = """
            <a rel="nofollow"
               href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-guide.html&amp;rut=abc"
               class='result-link'>Coroutines guide | Kotlin Documentation</a>
            <td class='result-snippet'><b>Kotlin</b> coroutines guide.</td>
        """.trimIndent()

        val results = WebSearch.parseDuckDuckGo(html)

        assertEquals(1, results.size)
        assertEquals("Coroutines guide | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", results[0].url)
        assertEquals("Kotlin coroutines guide.", results[0].snippet)
    }

    @Test
    fun `bing rss reads items and decodes entities`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <rss><channel>
              <item>
                <title>Coroutines guide | Kotlin Documentation</title>
                <link>https://kotlinlang.org/docs/coroutines-guide.html</link>
                <description>Kotlin &amp; coroutines &lt;guide&gt; &#39;quoted&#39;</description>
              </item>
              <item>
                <title><![CDATA[Kotlin coroutines on Android]]></title>
                <link>https://developer.android.com/kotlin/coroutines</link>
                <description>Structured concurrency.</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val results = WebSearch.parseBingRss(xml)

        assertEquals(2, results.size)
        assertEquals("Coroutines guide | Kotlin Documentation", results[0].title)
        assertEquals("Kotlin & coroutines <guide> 'quoted'", results[0].snippet)
        assertEquals("Kotlin coroutines on Android", results[1].title)
    }

    @Test
    fun `startpage pairs title with display url and reads snippet`() {
        val html = """
            <div class="result h css-a">
              <div class="result-url-container">
                <a class="display-url" href="/clev?event=x"
                   aria-label="https://kotlinlang.org/docs/coroutines-overview.html">kotlinlang.org</a>
              </div>
              <a class="result-title result-link" href="/clev?event=x">
                <style>.css-b{font-size:18px}</style><h2>Coroutines | Kotlin Documentation</h2>
              </a>
              <p>Kotlin provides several ways for <b>coroutines</b> to communicate.</p>
            </div>
        """.trimIndent()

        val results = WebSearch.parseStartpage(html)

        assertEquals(1, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertEquals("Kotlin provides several ways for coroutines to communicate.", results[0].snippet)
    }

    @Test
    fun `custom parser resolves relative links and skips engine chrome`() {
        val html = """
            <a href="/find?q=next">Next</a>
            <a href="https://search.example.com/preferences">Settings</a>
            <a href="/result/1"><h3>Example result</h3></a>
            <p>The summary paragraph.</p>
        """.trimIndent()

        val results = WebSearch.parseGeneric(
            html,
            baseUrl = "https://search.example.com/find?q=kotlin",
            skipHost = "search.example.com",
        )

        assertEquals(1, results.size)
        assertEquals("Example result", results[0].title)
        assertEquals("https://search.example.com/result/1", results[0].url)
        assertEquals("The summary paragraph.", results[0].snippet)
    }

    @Test
    fun `entity decoding is one pass and handles numeric references`() {
        assertEquals("&lt;", WebSearch.unescapeHtml("&amp;lt;"))
        assertEquals("'quoted'", WebSearch.unescapeHtml("&#39;quoted&#39;"))
        assertEquals("A B", WebSearch.unescapeHtml("A&nbsp;B"))
        assertTrue(WebSearch.cleanText("<b>Hello</b> <script>x()</script> world") == "Hello world")
    }
}
