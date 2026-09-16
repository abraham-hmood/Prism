package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port the search engine listens on.
 *
 * This existed as a hardcoded 842, which is below 1024 -- a privileged port that Android and Linux
 * refuse to bind without root. The listener threw on startup, every request came back
 * connection-refused, and nothing else about the feature looked wrong. These tests pin the two
 * properties that would have caught it: the port is always in the unprivileged range, and it is
 * actually bindable.
 */
class SearchPortTest {

    @Test
    fun chosenPortIsUnprivilegedAndStable() {
        PrismSettings.setPrismSearchPort(0)          // force a fresh choice
        val port = PrismSettings.getPrismSearchPort()
        assertTrue(port >= 1024, "port $port is privileged; binding it needs root")
        assertTrue(port <= 65535, "port $port is out of range")
        // Remembered, not re-rolled: the port shows up in saved bookmarks and in the browser's
        // default-engine URL, so it must survive a second read.
        assertEquals(port, PrismSettings.getPrismSearchPort())
    }

    @Test
    fun aStoredPrivilegedPortIsMigratedAway() {
        PrismSettings.setPrismSearchPort(842)        // exactly the broken state shipped earlier
        val port = PrismSettings.getPrismSearchPort()
        assertTrue(port >= 1024, "a stored privileged port ($port) was handed back unchanged")
    }

    @Test
    fun theChosenPortCanActuallyBeBoundAndReached() {
        PrismSettings.setPrismSearchPort(0)
        val port = PrismSettings.getPrismSearchPort()

        // Binds the way the server does -- IPv4 127.0.0.1 explicitly -- then CONNECTS to the
        // address the app advertises. Binding alone is not the property that matters: the bug this
        // guards against bound successfully on the IPv6 loopback and refused every IPv4 caller.
        ServerSocket(port, 50, InetAddress.getByName(PrismSearchServer.LOOPBACK_V4)).use { server ->
            assertEquals(port, server.localPort)
            assertTrue(
                server.inetAddress is java.net.Inet4Address,
                "server bound ${server.inetAddress} -- callers dial IPv4 and would be refused"
            )
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", port), 2000)
                assertTrue(client.isConnected, "could not reach the port the app advertises")
            }
        }
    }

    /**
     * The regression itself: `InetAddress.getLoopbackAddress()` returns whichever loopback the
     * platform prefers, which on Android is often IPv6. A server bound there passes every
     * port-based health check and refuses every caller using the advertised 127.0.0.1 address.
     * The app must therefore name the family explicitly rather than let the platform choose.
     */
    @Test
    fun theAdvertisedAddressIsIpv4() {
        assertEquals("127.0.0.1", PrismSearchServer.LOOPBACK_V4)
        assertTrue(
            InetAddress.getByName(PrismSearchServer.LOOPBACK_V4) is java.net.Inet4Address,
            "the advertised loopback address is not IPv4"
        )
        PrismSettings.setMeshEnabled(false)
        assertTrue(
            PrismSettings.prismSearchBaseUrl().startsWith("http://127.0.0.1:"),
            "the advertised URL no longer matches the address the server binds"
        )
    }

    @Test
    fun baseUrlUsesThatPortWhenOffMesh() {
        PrismSettings.setMeshEnabled(false)
        val port = PrismSettings.getPrismSearchPort()
        assertEquals("http://127.0.0.1:$port", PrismSettings.prismSearchBaseUrl())
        assertTrue(PrismSettings.buildSearchUrlForEngine("prism", "hello").contains(":$port"))
    }
}

/** Test-only shim: exercises buildSearchUrl for a specific engine without disturbing the user's
 * saved choice, which other tests in this module also read. */
private fun PrismSettings.buildSearchUrlForEngine(engine: String, query: String): String {
    val previous = getSearchEngine()
    return try {
        setSearchEngine(engine)
        buildSearchUrl(query)
    } finally {
        setSearchEngine(previous)
    }
}
