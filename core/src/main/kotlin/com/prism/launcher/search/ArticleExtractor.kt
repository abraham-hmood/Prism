package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * The article on a page, without the page.
 *
 * ## Why stripping tags is not enough
 *
 * A news page is mostly not news. Before the first sentence of the story there is a menu, a cookie
 * banner, a headline, a byline and a share bar; beside it a "most read" list and a newsletter box;
 * after it related stories, comments and a footer. Turning the whole page into text -- what
 * [PrismCrawler.htmlToText] does, rightly, for a search index -- hands a model all of that, and a
 * podcast built on it reads out headlines and menu items instead of reporting the story.
 *
 * ## How the story is found
 *
 * 1. **Structured data first.** Most news sites and blogs embed the full story for search engines as
 *    JSON-LD (`NewsArticle`, `BlogPosting`, `articleBody`). When it is there it is exactly the text
 *    the publisher considers the article, so it is used as is.
 * 2. **Otherwise, a reader view.** The page is parsed into a tree; navigation, headers, footers,
 *    sidebars, forms, comments, share bars, promos and anything hidden are removed by tag, role,
 *    class and id; and every remaining paragraph scores its parent and grandparent by how much
 *    prose it holds, minus how much of it is links. The highest-scoring container is the article
 *    body -- the same idea Firefox's and Safari's reader modes are built on. Its paragraphs are
 *    kept, in order, with the headline, captions and boilerplate sentences filtered out.
 *
 * Pages are fetched politely: robots.txt is honoured for the `*` group and for Prism, and each
 * response is capped in size.
 */
object ArticleExtractor {

    private const val TAG = "ArticleExtractor"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Safari/537.36 PrismPodcasts/1.0"
    private const val MAX_BYTES = 3 * 1024 * 1024

    data class Article(
        val url: String,
        val title: String,
        /** The article body, paragraphs separated by blank lines. */
        val text: String,
        /** How the text was found: "structured data" or "reader view". */
        val method: String,
    )

    // ── Fetching ────────────────────────────────────────────────────────

    /** Fetches [url] and extracts its article, or null when the page cannot be read or has none. */
    fun fetch(url: String, maxChars: Int = 14_000): Article? {
        if (!robotsAllow(url)) {
            PrismPlatform.log.info(TAG, "robots.txt disallows $url")
            return null
        }
        val (finalUrl, html) = download(url) ?: return null
        return extract(html, finalUrl, maxChars)
    }

    /** The page's HTML and the URL it finally came from after redirects. */
    fun download(url: String, accept: String = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"): Pair<String, String>? {
        var current = url
        repeat(6) {
            var conn: HttpURLConnection? = null
            try {
                conn = (com.prism.core.Urls.of(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    current = PrismCrawler.absolutize(conn.getHeaderField("Location") ?: return null, current) ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = conn.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && "html" !in type && "xml" !in type && "json" !in type && "text" !in type) return null
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                conn.inputStream.use { input ->
                    while (bytes.size() < MAX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes.write(buffer, 0, n)
                    }
                }
                val raw = bytes.toByteArray()
                val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                    ?: Regex("(?i)<meta[^>]+charset=[\"']?([\\w-]+)").find(String(raw, 0, minOf(raw.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
                val text = runCatching { String(raw, java.nio.charset.Charset.forName(charset ?: "UTF-8")) }
                    .getOrElse { String(raw, Charsets.UTF_8) }
                return current to text
            } catch (e: Exception) {
                PrismPlatform.log.info(TAG, "Could not fetch ${current.take(120)}: ${e.message}")
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private val robots = ConcurrentHashMap<String, List<String>>()

    private fun robotsAllow(url: String): Boolean {
        val u = runCatching { com.prism.core.Urls.of(url) }.getOrNull() ?: return false
        val origin = "${u.protocol}://${u.authority}"
        val rules = robots.getOrPut(origin) {
            val body = download("$origin/robots.txt", "text/plain")?.second.orEmpty()
            val disallow = ArrayList<String>()
            var applies = false
            var sawAgent = false
            for (raw in body.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "user-agent" -> {
                        if (!sawAgent) applies = false
                        sawAgent = true
                        if (value == "*" || value.contains("prism", true)) applies = true
                    }
                    "disallow" -> { sawAgent = false; if (applies && value.isNotEmpty()) disallow += value }
                    else -> sawAgent = false
                }
            }
            disallow
        }
        val path = u.path.ifEmpty { "/" }
        return rules.none { path.startsWith(it) }
    }

    // ── Extraction ──────────────────────────────────────────────────────

    /** The article in [html]. Pure: no network, so it can be tested on saved pages. */
    fun extract(html: String, url: String = "", maxChars: Int = 14_000): Article? {
        val title = pageTitle(html)
        structured(html)?.let { body ->
            if (body.length >= 300) return Article(url, title, clean(body, title).take(maxChars), "structured data")
        }
        val root = HtmlTree.parse(html)
        prune(root)
        val body = readerText(root, title)
        if (body.length < 200) return null
        return Article(url, title, body.take(maxChars), "reader view")
    }

    /** The title the publisher gave the story, for filtering it out of the body. */
    private fun pageTitle(html: String): String {
        val og = Regex("(?is)<meta[^>]+property\\s*=\\s*[\"']og:title[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)").find(html)?.groupValues?.get(1)
        val raw = og ?: PrismCrawler.extractTitle(html)
        return decode(raw).trim()
    }

    /** `articleBody` (or a blog post's `text`) from the page's JSON-LD, when it carries one. */
    private fun structured(html: String): String? {
        val blocks = Regex("(?is)<script[^>]+type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>").findAll(html)
        for (block in blocks) {
            val json = block.groupValues[1].trim()
            val found = runCatching {
                when {
                    json.startsWith("[") -> bodyIn(JSONArray(json))
                    else -> bodyIn(JSONObject(json))
                }
            }.getOrNull()
            if (!found.isNullOrBlank()) return decode(found.replace(Regex("<[^>]+>"), " "))
        }
        return null
    }

    private fun bodyIn(value: Any?): String? = when (value) {
        is JSONObject -> {
            value.optString("articleBody").takeIf { it.length > 200 }
                ?: value.optJSONArray("@graph")?.let { bodyIn(it) }
                ?: value.optJSONObject("mainEntity")?.let { bodyIn(it) }
                ?: value.optString("text").takeIf { it.length > 300 && value.optString("@type").contains("Posting") }
        }
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { bodyIn(value.opt(it)) }
        else -> null
    }

    private val JUNK_TAGS = setOf(
        "nav", "header", "footer", "aside", "form", "button", "select", "textarea", "input", "label",
        "menu", "dialog", "figure", "figcaption", "picture", "video", "audio", "canvas", "noscript",
        "iframe", "svg", "template", "time", "address",
    )
    private val JUNK_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "search", "dialog", "alert", "menu", "menubar", "toolbar")
    private val JUNK_NAMES = Regex(
        "(?i)comment|sidebar|share|sharing|social|related|recommend|popular|most-read|trending|promo|newsletter|subscribe|" +
            "signup|sign-up|cookie|consent|gdpr|banner|advert|\\bad\\b|\\bads\\b|\\bad-|-ad\\b|adslot|sponsor|footer|header|masthead|" +
            "\\bnav|menu|breadcrumb|popup|modal|byline|author|\\btags?\\b|toolbar|outbrain|taboola|paywall|login|skip|" +
            "caption|credit|disclaimer|pagination|pager|widget|teaser|more-stories|read-more|print|tooltip|hidden",
    )
    private val CONTENT_NAMES = Regex("(?i)article|body|content|entry|main|post|story|text|prose|blog|rich-text")
    private val KEEP = setOf("html", "body", "main", "article")

    /** Removes everything that is certainly not the article. */
    private fun prune(node: HtmlTree.Node) {
        node.children.removeAll { child ->
            child is HtmlTree.Node && isJunk(child)
        }
        node.children.forEach { if (it is HtmlTree.Node) prune(it) }
    }

    private fun isJunk(node: HtmlTree.Node): Boolean {
        if (node.tag in JUNK_TAGS) return true
        val role = node.attr("role").lowercase()
        if (role in JUNK_ROLES) return true
        if (node.attr("aria-hidden") == "true" || Regex("(?i)(^|\\s)hidden(\\s|=|$)").containsMatchIn(node.attrs)) return true
        if (Regex("(?i)display\\s*:\\s*none").containsMatchIn(node.attr("style"))) return true
        if (node.tag in KEEP) return false
        val tokens = (node.attr("class") + " " + node.attr("id")).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val kinds = tokens.map { classify(it) }
        // Junk only when something names it junk and nothing names it content: "main-content
        // has-sidebar" is the article column of a page that also has a sidebar.
        return "junk" in kinds && "content" !in kinds
    }

    /**
     * One class or id: "junk", "content" or neither. A name that is both ("post-share",
     * "article-body") is decided by its last part, which is what the element actually is.
     */
    private fun classify(token: String): String {
        val junk = JUNK_NAMES.containsMatchIn(token)
        val content = CONTENT_NAMES.containsMatchIn(token)
        return when {
            junk && !content -> "junk"
            content && !junk -> "content"
            junk && content -> {
                val last = token.split('-', '_').lastOrNull { it.isNotBlank() }.orEmpty()
                if (JUNK_NAMES.containsMatchIn(last) && !CONTENT_NAMES.containsMatchIn(last)) "junk" else "content"
            }
            else -> ""
        }
    }

    private val PARAGRAPH_TAGS = setOf("p", "pre", "blockquote", "td", "li", "dd")
    private val BLOCK_TAGS = setOf("p", "div", "section", "article", "main", "ul", "ol", "li", "table", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "dl", "dd", "dt", "tr", "td", "figure", "header", "footer")

    /** Scores containers by the prose inside them and returns the best one's paragraphs. */
    private fun readerText(root: HtmlTree.Node, title: String): String {
        val scores = HashMap<HtmlTree.Node, Double>()
        fun visit(node: HtmlTree.Node) {
            val isParagraph = node.tag in PARAGRAPH_TAGS ||
                (node.tag == "div" && node.children.none { it is HtmlTree.Node && it.tag in BLOCK_TAGS })
            if (isParagraph) {
                val text = node.text()
                if (text.length >= 25) {
                    var score = 1.0 + text.count { it == ',' } + minOf(text.length / 100.0, 3.0)
                    score *= (1 - linkDensity(node))
                    node.parent?.let { scores[it] = (scores[it] ?: initial(it)) + score }
                    node.parent?.parent?.let { scores[it] = (scores[it] ?: initial(it)) + score / 2 }
                }
            }
            node.children.forEach { if (it is HtmlTree.Node) visit(it) }
        }
        visit(root)
        val best = scores.maxByOrNull { (node, score) -> score * (1 - linkDensity(node)) }?.key ?: return ""
        val threshold = maxOf(10.0, (scores[best] ?: 0.0) * 0.2)

        // The best container, plus siblings that clearly continue it (stories split across divs).
        val containers = best.parent?.children?.filterIsInstance<HtmlTree.Node>()
            ?.filter { it === best || (scores[it] ?: 0.0) >= threshold } ?: listOf(best)

        val paragraphs = ArrayList<String>()
        fun collect(node: HtmlTree.Node) {
            when {
                node.tag in setOf("p", "blockquote", "pre", "li", "dd") ||
                    (node.tag == "div" && node.children.none { it is HtmlTree.Node && it.tag in BLOCK_TAGS }) -> {
                    val text = node.text()
                    val minimum = if (node.tag == "li") 20 else 35
                    if (text.length >= minimum && linkDensity(node) < 0.5 && !boilerplate(text, title)) paragraphs += text
                }
                node.tag.length == 2 && node.tag[0] == 'h' -> Unit // headings are not the story
                else -> node.children.forEach { if (it is HtmlTree.Node) collect(it) }
            }
        }
        containers.forEach { collect(it) }
        return paragraphs.distinct().joinToString("\n\n")
    }

    private fun initial(node: HtmlTree.Node): Double {
        var score = when (node.tag) {
            "article" -> 10.0
            "div", "main", "section" -> 5.0
            "pre", "td", "blockquote" -> 3.0
            "ol", "ul", "dl", "form" -> -3.0
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5.0
            else -> 0.0
        }
        val kinds = (node.attr("class") + " " + node.attr("id")).split(Regex("\\s+")).filter { it.isNotBlank() }.map { classify(it) }
        if ("content" in kinds) score += 25
        else if ("junk" in kinds) score -= 25
        return score
    }

    private fun linkDensity(node: HtmlTree.Node): Double {
        val all = node.text().length
        if (all == 0) return 0.0
        var linked = 0
        fun walk(n: HtmlTree.Node) {
            if (n.tag == "a") linked += n.text().length
            else n.children.forEach { if (it is HtmlTree.Node) walk(it) }
        }
        walk(node)
        return linked.toDouble() / all
    }

    /** Prompts to pay, register or subscribe, which sit inside the article body on most news sites. */
    private val PAYWALL = Regex(
        "(?i)subscri(be|ption|bing|ber)|register (now|for free|to (read|continue))|free articles?|unlimited access|" +
            "cancel anytime|support(ing)? (our|independent) journalism|already a (member|subscriber)|log ?in to (read|continue)|" +
            "create (a|an|your) (free )?account|this (story|article) is (for|available to) (subscribers|members)|" +
            "sales tax may apply|no refunds|terms and conditions apply|enjoying this article",
    )

    private val BOILERPLATE = Regex(
        "(?i)^(advertisement|sponsored|related:|read more|see also|sign up|subscribe|follow us|share this|" +
            "click here|all rights reserved|copyright ©|© \\d{4}|this article (was|is) (originally|first)|" +
            "we use cookies|accept (all )?cookies|photo:|image:|getty images|reuters/|ap photo)",
    )

    private fun boilerplate(text: String, title: String): Boolean =
        BOILERPLATE.containsMatchIn(text.trim()) ||
            (text.length < 400 && PAYWALL.containsMatchIn(text)) ||
            (title.isNotBlank() && text.trim().equals(title.trim(), ignoreCase = true)) ||
            text.length < 120 && Regex("(?i)newsletter|subscribe|cookies|sign in|log in|download (our|the) app").containsMatchIn(text)

    private val NAMED = mapOf(
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "mdash" to "—",
        "ndash" to "–", "hellip" to "…", "nbsp" to " ", "amp" to "&", "quot" to "\"", "apos" to "'",
        "lt" to "<", "gt" to ">", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "oacute" to "ó", "uacute" to "ú",
        "iacute" to "í", "ntilde" to "ñ", "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö",
        "auml" to "ä", "szlig" to "ß", "euro" to "€", "pound" to "£", "times" to "×",
        "middot" to "·", "bull" to "•", "frac12" to "½", "shy" to "",
    )

    /**
     * Every HTML entity in [text], named or numeric. Publishers escape their structured article
     * bodies, sometimes twice, and a model reading "&rsquo;" mid-word reads noise.
     */
    internal fun decode(text: String): String {
        var out = text
        repeat(2) {
            out = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]{1,8});").replace(out) { m ->
                val body = m.groupValues[1]
                when {
                    body.startsWith("#x") || body.startsWith("#X") ->
                        body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                    body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                    else -> NAMED[body] ?: NAMED[body.lowercase()] ?: m.value
                }
            }
        }
        return out
    }

    /** Collapses whitespace and drops a repeated headline from structured text. */
    private fun clean(text: String, title: String): String =
        text.replace("\r", "")
            .split(Regex("\\n\\s*\\n|\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !boilerplate(it, title) }
            .joinToString("\n\n")
}

/**
 * Just enough of an HTML parser to find an article: elements, attributes and text, tolerant of the
 * unclosed paragraphs and stray end tags real pages are full of. Scripts, styles and comments are
 * skipped whole.
 */
object HtmlTree {

    class Node(val tag: String, val attrs: String, val parent: Node?) {
        val children = ArrayList<Any>()

        fun attr(name: String): String =
            Regex("(?is)(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").find(attrs)
                ?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] } }.orEmpty()

        private var cached: String? = null

        /** Visible text, whitespace collapsed. */
        fun text(): String = cached ?: buildString {
            fun walk(n: Node) {
                n.children.forEach { c ->
                    when (c) {
                        is String -> append(c)
                        is Node -> if (c.tag == "br") append(' ') else { walk(c); if (c.tag in BLOCKS) append(' ') }
                    }
                }
            }
            walk(this@Node)
        }.replace(Regex("\\s+"), " ").trim().also { cached = it }
    }

    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "source", "wbr", "area", "base", "col", "embed", "param", "track")
    private val RAW = setOf("script", "style", "noscript", "svg", "template", "iframe", "textarea", "math")
    private val BLOCKS = setOf("p", "div", "li", "tr", "td", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section", "article")
    private val CLOSES_P = setOf("p", "div", "ul", "ol", "table", "section", "article", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "header", "footer", "nav", "aside", "form", "figure", "main")

    fun parse(html: String): Node {
        val root = Node("#root", "", null)
        var current = root
        var i = 0
        val n = html.length
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                current.children += ArticleExtractor.decode(text.toString())
                text.setLength(0)
            }
        }
        while (i < n) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }
            val closing = i + 1 < n && html[i + 1] == '/'
            var j = i + if (closing) 2 else 1
            val nameStart = j
            while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            if (j == nameStart) { text.append(c); i++; continue }
            val tag = html.substring(nameStart, j).lowercase()
            // The end of the tag, skipping '>' inside quoted attribute values.
            var k = j
            var quote: Char? = null
            while (k < n) {
                val ch = html[k]
                if (quote != null) { if (ch == quote) quote = null }
                else if (ch == '"' || ch == '\'') quote = ch
                else if (ch == '>') break
                k++
            }
            val attrs = if (k > j) html.substring(j, k) else ""
            i = if (k < n) k + 1 else n
            flush()
            if (closing) {
                var walk: Node? = current
                while (walk != null && walk.tag != tag) walk = walk.parent
                if (walk != null && walk !== root) current = walk.parent ?: root
                continue
            }
            if (tag in RAW) {
                val end = html.indexOf("</$tag", i, ignoreCase = true)
                i = if (end < 0) n else (html.indexOf('>', end).takeIf { it >= 0 }?.plus(1) ?: n)
                continue
            }
            // Implied end tags: a new block closes an open paragraph, a new item closes the last one.
            if (tag in CLOSES_P && current.tag == "p") current = current.parent ?: root
            if (tag == "li") {
                var walk: Node? = current
                while (walk != null && walk.tag != "li" && walk.tag != "ul" && walk.tag != "ol") walk = walk.parent
                if (walk?.tag == "li") current = walk.parent ?: root
            }
            val node = Node(tag, attrs, current)
            current.children += node
            if (tag !in VOID && !attrs.trimEnd().endsWith("/")) current = node
        }
        flush()
        return root
    }
}
