package com.prism.launcher.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The line between "this is a search" and "this is an address".
 *
 * Worth testing precisely because both mistakes are bad and neither raises: treating a search as a
 * URL seeds nonsense into the crawler, and treating a URL as a search leaves the user staring at no
 * results for a site they named exactly.
 */
class PrismUrlQueryTest {

    @Test
    fun `recognises addresses`() {
        assertEquals("https://example.com", PrismUrlQuery.urlIn("https://example.com"))
        assertEquals("http://example.com", PrismUrlQuery.urlIn("http://example.com"))
        assertEquals("https://www.example.com", PrismUrlQuery.urlIn("www.example.com"))
        assertEquals("https://example.com", PrismUrlQuery.urlIn("example.com"))
        assertEquals("https://sub.example.co.uk", PrismUrlQuery.urlIn("sub.example.co.uk"))
        assertEquals("https://example.com/path?q=1", PrismUrlQuery.urlIn("example.com/path?q=1"))
        assertEquals("https://kotlinlang.org", PrismUrlQuery.urlIn("  kotlinlang.org  "))
    }

    @Test
    fun `leaves ordinary searches alone`() {
        // Anything with a space is a search, whatever else it looks like.
        assertNull(PrismUrlQuery.urlIn("kotlin coroutines"))
        assertNull(PrismUrlQuery.urlIn("example.com and friends"))
        assertNull(PrismUrlQuery.urlIn("hello"))
        assertNull(PrismUrlQuery.urlIn(""))
        assertNull(PrismUrlQuery.urlIn("   "))
        // A decimal is not a domain, and ".5" is not a TLD.
        assertNull(PrismUrlQuery.urlIn("3.5"))
        // A sentence that happens to end in a full stop is not a host either.
        assertNull(PrismUrlQuery.urlIn("done."))
    }

    @Test
    fun `a filename is not treated as a website`() {
        // These are the false positives worth caring about: a user searching for a file should not
        // cause a crawl of a nonexistent host. Two-letter TLDs make this genuinely ambiguous --
        // `.io`, `.sh` and `.md` are all real TLDs -- so this documents what the rule actually does
        // rather than pretending it is smarter than it is.
        assertNull(PrismUrlQuery.urlIn("report.pdf"))
        assertNull(PrismUrlQuery.urlIn("photo.jpeg"))
    }
}
