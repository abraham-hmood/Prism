package com.prism.launcher.search

import com.prism.launcher.PrismSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seed-list behaviour: the user's list and the crawler's discoveries share a purpose but must
 * never share storage. The interesting failures here are all "one side quietly destroyed the
 * other", so that is what these check.
 */
class SearchSeedsTest {

    @BeforeTest
    fun clean() {
        PrismSettings.setSearchSeeds("")
        PrismSettings.clearDiscoveredSearchSeeds()
        PrismSettings.setMaxDiscoveredSeeds(PrismSettings.MAX_DISCOVERED_SEEDS)
    }

    @AfterTest
    fun restore() = clean()

    @Test
    fun userSeedsRoundTripAndIgnoreJunk() {
        PrismSettings.setSearchSeeds("https://a.example\nnot-a-url\n\nhttps://b.example, https://c.example")
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            PrismSettings.getUserSearchSeeds()
        )
    }

    @Test
    fun addingASeedAppendsAndRejectsDuplicates() {
        assertTrue(PrismSettings.addUserSearchSeed("https://a.example"))
        assertTrue(PrismSettings.addUserSearchSeed("https://b.example"))
        assertFalse(PrismSettings.addUserSearchSeed("https://A.EXAMPLE"), "case-insensitive duplicate slipped through")
        assertFalse(PrismSettings.addUserSearchSeed("nonsense"), "a non-URL was accepted")
        assertEquals(listOf("https://a.example", "https://b.example"), PrismSettings.getUserSearchSeeds())
    }

    @Test
    fun discoveriesNeverTouchTheUsersList() {
        PrismSettings.setSearchSeeds("https://mine.example")
        PrismSettings.recordDiscoveredSeeds(listOf("https://found1.example", "https://found2.example"))

        // The whole point of two lists: the crawler cannot edit what the user typed.
        assertEquals(listOf("https://mine.example"), PrismSettings.getUserSearchSeeds())
        assertEquals(2, PrismSettings.getDiscoveredSearchSeeds().size)

        // ...and editing the user's list cannot delete what the crawler learned.
        PrismSettings.setSearchSeeds("https://mine.example\nhttps://also-mine.example")
        assertEquals(2, PrismSettings.getDiscoveredSearchSeeds().size)
    }

    @Test
    fun discoveriesDedupeByHost() {
        PrismSettings.recordDiscoveredSeeds(listOf("https://site.example"))
        // Same host, different paths and casing -- must not occupy three more slots.
        val added = PrismSettings.recordDiscoveredSeeds(
            listOf("https://site.example/deep/page", "https://SITE.example", "http://site.example")
        )
        assertEquals(0, added, "the same host was recorded more than once")
        assertEquals(1, PrismSettings.getDiscoveredSearchSeeds().size)
    }

    @Test
    fun aHostTheUserAlreadySeededIsNotReDiscovered() {
        PrismSettings.setSearchSeeds("https://known.example/start")
        val added = PrismSettings.recordDiscoveredSeeds(listOf("https://known.example/other"))
        assertEquals(0, added, "a host the user already listed was echoed back as a discovery")
        assertTrue(PrismSettings.getDiscoveredSearchSeeds().isEmpty())
    }

    @Test
    fun defaultHostsAreNotReDiscovered() {
        val defaultOrigin = PrismCrawler.originOf(PrismSettings.DEFAULT_SEARCH_SEEDS.first())
        assertTrue(defaultOrigin != null, "a default seed did not yield an origin")
        assertEquals(0, PrismSettings.recordDiscoveredSeeds(listOf(defaultOrigin!!)))
    }

    @Test
    fun discoveredListIsCapped() {
        val many = (1..PrismSettings.MAX_DISCOVERED_SEEDS + 50).map { "https://host$it.example" }
        PrismSettings.recordDiscoveredSeeds(many)
        assertEquals(PrismSettings.MAX_DISCOVERED_SEEDS, PrismSettings.getDiscoveredSearchSeeds().size)
        // Oldest dropped, so the most recent discoveries survive.
        assertTrue(PrismSettings.getDiscoveredSearchSeeds().last().contains("host${PrismSettings.MAX_DISCOVERED_SEEDS + 50}."))
    }

    @Test
    fun aNegativeCapKeepsEverything() {
        PrismSettings.setMaxDiscoveredSeeds(-1)
        val many = (1..PrismSettings.MAX_DISCOVERED_SEEDS + 25).map { "https://host$it.example" }
        PrismSettings.recordDiscoveredSeeds(many)
        assertEquals(many.size, PrismSettings.getDiscoveredSearchSeeds().size, "-1 should mean no limit")
    }

    @Test
    fun loweringTheCapTrimsImmediately() {
        PrismSettings.recordDiscoveredSeeds((1..40).map { "https://host$it.example" })
        PrismSettings.setMaxDiscoveredSeeds(10)
        assertEquals(10, PrismSettings.getDiscoveredSearchSeeds().size,
            "a smaller cap should apply now, not silently at the next crawl")
    }

    @Test
    fun crawlSeedsCombineAllThreeSourcesWithoutDuplicates() {
        PrismSettings.setSearchSeeds("https://mine.example")
        PrismSettings.recordDiscoveredSeeds(listOf("https://found.example"))
        val seeds = PrismSettings.getSearchSeeds()

        assertTrue(seeds.contains("https://mine.example"))
        assertTrue(seeds.contains("https://found.example"))
        assertTrue(seeds.containsAll(PrismSettings.DEFAULT_SEARCH_SEEDS), "defaults missing -- a crawl could be seedless")
        assertEquals(seeds.size, seeds.distinct().size, "duplicate seeds would re-crawl the same start twice")
    }

    @Test
    fun originOfStripsPathsAndKeepsPorts() {
        assertEquals("https://example.com", PrismCrawler.originOf("https://example.com/a/b?c=1#d"))
        assertEquals("http://example.com:8080", PrismCrawler.originOf("http://example.com:8080/x"))
        assertEquals(null, PrismCrawler.originOf("not a url"))
    }
}
