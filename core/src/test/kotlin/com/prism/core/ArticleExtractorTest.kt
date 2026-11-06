package com.prism.core

import com.prism.launcher.podcasts.CastMember
import com.prism.launcher.podcasts.Podcast
import com.prism.launcher.podcasts.PodcastProducer
import com.prism.launcher.podcasts.PodcastResearch
import com.prism.launcher.search.ArticleExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The research reads stories, not pages: what reaches the model is the article's own paragraphs,
 * without the menus, headline, share bars, sidebars, comments and footer around it.
 */
class ArticleExtractorTest {

    private val story = listOf(
        "SpaceX launched its Starship rocket on Tuesday from Boca Chica, Texas, reaching orbit for the first time after four earlier attempts.",
        "The company said the booster returned to the launch tower and was caught by its mechanical arms, a manoeuvre it first tried last year.",
        "\"This is the flight we have been building toward,\" said the mission director, adding that the next test could carry real satellites.",
        "Analysts at Morgan Stanley estimate that a fully reusable Starship could cut launch costs to about 10 million dollars per flight.",
    )

    private fun newsPage(): String = """
        <!doctype html><html><head><title>Starship reaches orbit | Daily Space</title>
        <meta property="og:title" content="Starship reaches orbit">
        <script>window.tracking = { a: "<p>not text</p>" };</script>
        <style>p { color: red }</style></head>
        <body class="has-sidebar">
          <div class="cookie-banner"><p>We use cookies to improve your experience on this website, accept all cookies to continue.</p></div>
          <header class="site-header"><nav><ul><li><a href="/">Home</a></li><li><a href="/space">Space</a></li><li><a href="/science">Science news and features</a></li></ul></nav></header>
          <main>
            <article class="story">
              <h1>Starship reaches orbit</h1>
              <div class="byline">By A. Reporter, 6 November</div>
              <div class="share-bar"><a href="#">Share on X</a> <a href="#">Share on Facebook</a></div>
              <div class="article-body">
                <p>${story[0]}</p>
                <figure><img src="rocket.jpg"><figcaption>The rocket lifts off from the pad on Tuesday morning in Texas.</figcaption></figure>
                <p>${story[1]}
                <p>${story[2]}</p>
                <div class="ad-slot"><p>Advertisement: buy the best telescope deals this season with free shipping.</p></div>
                <p>${story[3]}</p>
                <div class="newsletter-signup"><p>Sign up for our space newsletter and never miss a launch again.</p></div>
              </div>
            </article>
            <aside class="sidebar"><h3>Most read</h3><ul><li><a href="/a">Ten moons you have never heard of, ranked by size</a></li></ul></aside>
            <section class="comments"><p>Great article, I watched the whole launch live with my kids this morning!</p></section>
          </main>
          <footer><p>Copyright © 2026 Daily Space. All rights reserved. Terms and privacy.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `the story is kept and everything around it is dropped`() {
        val article = assertNotNull(ArticleExtractor.extract(newsPage(), "https://daily.space/starship"))
        assertEquals("reader view", article.method)
        assertEquals("Starship reaches orbit", article.title)
        story.forEach { assertTrue(it.take(60) in article.text, "missing story paragraph: ${it.take(40)}") }
        listOf("cookies", "Home", "Share on", "By A. Reporter", "figcaption", "lifts off", "telescope", "newsletter",
            "Most read", "Ten moons", "Great article", "All rights reserved", "not text", "color: red").forEach {
            assertTrue(it !in article.text, "junk leaked into the article: $it\n${article.text}")
        }
        // The headline is not part of the body.
        assertTrue(!article.text.startsWith("Starship reaches orbit"))
        // Paragraphs stay in reading order.
        assertTrue(article.text.indexOf("Boca Chica") < article.text.indexOf("Morgan Stanley"))
    }

    @Test
    fun `structured article bodies are used when the publisher provides one`() {
        val body = story.joinToString("\\n\\n") { it.replace("\"", "\\\"") }
        val html = """
            <html><head><title>Starship</title>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},
              {"@type":"NewsArticle","headline":"Starship reaches orbit","articleBody":"$body"}]}</script>
            </head><body><div class="menu"><a href="/">Home</a></div><p>Short teaser only.</p></body></html>
        """.trimIndent()
        val article = assertNotNull(ArticleExtractor.extract(html))
        assertEquals("structured data", article.method)
        assertTrue("Morgan Stanley" in article.text && "Home" !in article.text && "teaser" !in article.text)
    }

    @Test
    fun `a blog post's own text is found among widgets`() {
        val html = """
            <html><body>
              <div id="sidebar-left" class="widget-area"><p>About me: I write about rockets, coffee and occasionally my cat.</p></div>
              <div class="entry-content post-body">
                <p>I have been following the Starship programme since the first hop, and this week finally felt different to me.</p>
                <p>Catching a booster with the tower arms is not just a stunt, it changes how quickly a rocket can fly again, which matters more than raw payload numbers.</p>
                <p>Still, I worry that the schedule promises are as optimistic as ever, and regulators have not finished their review of the launch site.</p>
              </div>
              <div class="related-posts"><p><a href="/x">You might also like: my review of the best home telescopes of the year</a></p></div>
            </body></html>
        """.trimIndent()
        val article = assertNotNull(ArticleExtractor.extract(html))
        assertTrue("booster with the tower arms" in article.text && "regulators" in article.text)
        assertTrue("About me" !in article.text && "You might also like" !in article.text)
    }

    @Test
    fun `entities are decoded and paywall prompts dropped`() {
        val html = """
            <html><body><article><div class="article-body">
              <p>The company&rsquo;s rocket &mdash; the largest ever built &#8212; reached orbit on its fourteenth flight, carrying 26 satellites.</p>
              <p>If you&#39;re enjoying this article, consider supporting our award-winning journalism by subscribing today.</p>
              <p>Engineers said one of the six engines shut down early, but the mission continued and the satellites were deployed on schedule.</p>
              <p>Register now to read 3 free articles this month.</p>
              <p>Analysts expect the next flight to attempt recovering both stages, which would make the whole vehicle reusable for the first time.</p>
            </div></article></body></html>
        """.trimIndent()
        val article = assertNotNull(ArticleExtractor.extract(html))
        assertTrue("company’s rocket — the largest ever built — reached" in article.text, article.text)
        assertTrue("subscribing" !in article.text && "Register now" !in article.text, article.text)
    }

    @Test
    fun `a page with no story yields nothing`() {
        val html = "<html><body><nav><a href='/'>Home</a> <a href='/b'>Blog</a></nav><h1>Welcome</h1><p>Hi.</p></body></html>"
        assertEquals(null, ArticleExtractor.extract(html))
    }

    @Test
    fun `news links point at the publisher, not the aggregator`() {
        assertEquals(
            "https://www.example.com/story?id=1",
            PodcastResearch.articleUrl("http://www.bing.com/news/apiclick.aspx?ref=FexRss&aid=&tid=x&url=https%3a%2f%2fwww.example.com%2fstory%3fid%3d1&c=1"),
        )
    }

    @Test
    fun `the script is sized to the episode length at the show's pace`() {
        fun show(minutes: Int, pace: String) = Podcast(
            "p", "T", "", listOf("x"), "Science", mapOf("pace" to pace), mapOf("jingle" to false), minutes, false,
            listOf(CastMember(null, "A", "", "")), 0, 0,
        )
        val ten = PodcastProducer.targetWords(show(10, "Normal"))
        assertTrue(ten in 1300..1500, "ten minutes at a normal pace is about 1,400 words, got $ten")
        assertTrue(PodcastProducer.targetWords(show(10, "Fast")) > ten)
        assertTrue(PodcastProducer.targetWords(show(60, "Normal")) > 5 * ten)
    }
}
