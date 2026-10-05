package com.prism.core

import java.net.URI
import java.net.URL

/**
 * Building a [URL] without the deprecated constructors.
 *
 * ## Why this exists rather than a find-and-replace
 *
 * Java deprecated `new URL(String)` in 20 and points at `URI.create(s).toURL()` instead. The two are
 * NOT interchangeable, which is the whole reason for this file. `URI` implements RFC 3986 strictly and
 * rejects a great deal that `URL` accepted: a space in a path, a raw `|` or `[` in a query, a stray
 * `%` that is not an escape. Every one of those appears in real links on real pages.
 *
 * A blind replacement would therefore have swapped a compiler warning for a behaviour change in the two
 * places least able to afford one -- [com.prism.launcher.search.PrismCrawler] and
 * [com.prism.launcher.browser.PrismSiteDownloader], which exist precisely to be pointed at whatever
 * HTML the web hands them. Pages that used to be crawled would start being skipped, and nothing would
 * report it: the exception type would change from MalformedURLException to IllegalArgumentException and
 * be swallowed by the same catch.
 *
 * ## What it does instead
 *
 * The strict, non-deprecated path first, and the old constructor only for input `URI` refuses. That
 * keeps the modern API for everything well-formed while leaving Prism's tolerance for messy real-world
 * links exactly where it was. The deprecation is suppressed HERE, in one place, with the reason written
 * down -- rather than at thirty call sites, where it would become noise nobody reads.
 */
object Urls {

    /** A URL from a string, accepting what the old constructor accepted. */
    @Suppress("DEPRECATION")
    fun of(spec: String): URL = try {
        URI(spec).toURL()
    } catch (_: Exception) {
        // URI is stricter than URL. See the class comment: this is the tolerance the crawler relies on.
        URL(spec)
    }

    /**
     * Resolves a possibly-relative reference against a base, as `URL(URL, String)` did.
     *
     * RESOLUTION IS WHERE THE TWO APIS DIFFER MOST. `URI.resolve` follows RFC 3986 and returns the
     * reference unchanged when it is absolute, which is what is wanted; but it throws on a reference
     * containing characters a page author did not escape, and an href is user input from a stranger.
     * So the same two-step applies, and a reference that cannot be parsed either way is refused by the
     * caller rather than guessed at.
     */
    @Suppress("DEPRECATION")
    fun resolve(base: String, reference: String): URL = try {
        URI(base).resolve(reference).toURL()
    } catch (_: Exception) {
        URL(URL(base), reference)
    }

    /** The host of a URL in lower case, or null when there is not one. */
    fun hostOf(spec: String): String? =
        runCatching { of(spec).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
}
