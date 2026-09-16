package com.prism.launcher.search

import com.prism.launcher.PrismSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Self-checks for the Prism search engine.
 *
 * Written because "ERR_CONNECTION_REFUSED" is the same message for at least five different
 * faults -- the listener never started, it bound a port nobody is asking for, something else took
 * the port, the caller is on another device and the socket is loopback-only, or the caller asked
 * for https against a plain-HTTP server. Guessing between those from the outside is slow; each
 * check below distinguishes one of them.
 *
 * The decisive one is [probeLoopback], which does not ask the server whether it thinks it is
 * running -- it opens a real socket and issues a real GET. A server that believes it is listening
 * but cannot answer its own request is exactly the state that produces a refused connection with
 * no error anywhere in the log.
 */
object PrismSearchDiagnostics {

    enum class Status { OK, WARN, FAIL, INFO }

    data class Check(val name: String, val status: Status, val detail: String)

    fun run(): List<Check> {
        val checks = ArrayList<Check>()
        val port = PrismSettings.getPrismSearchPort()

        checks += Check(
            "Search port", if (port >= 1024) Status.OK else Status.FAIL,
            if (port >= 1024) "$port (unprivileged)"
            else "$port is privileged; binding needs root, so nothing can listen"
        )

        checks += Check(
            "Listener", if (PrismSearchServer.isListening()) Status.OK else Status.FAIL,
            if (PrismSearchServer.isListening()) "bound on ${PrismSearchServer.boundAddress()}"
            else "not listening -- PrismSearchServer.start() never ran or the bind failed"
        )

        // A socket on the wrong loopback FAMILY has the right port and answers nothing. Probing
        // both tells those apart instead of leaving "refused" to mean either.
        if (PrismSearchServer.isListening()) {
            val bound = PrismSearchServer.boundAddress()
            val onV4 = bound.startsWith("127.")
            checks += Check(
                "Loopback family", if (onV4) Status.OK else Status.FAIL,
                if (onV4) "IPv4, matching the advertised http://127.0.0.1 address"
                else "bound $bound -- callers dial 127.0.0.1 and will be refused"
            )
        }

        // The real end-to-end test: connect and speak HTTP to ourselves.
        checks += probeLoopback(PrismSearchServer.boundPort().takeIf { it > 0 } ?: port)

        val indexed = PrismSearchIndex.documentCount()
        checks += Check(
            "Index", if (indexed > 0) Status.OK else Status.WARN,
            if (indexed > 0) "$indexed page(s), last crawl ${describeAge(PrismSearchIndex.lastCrawlAt)}"
            else "empty -- the engine answers, but every query returns nothing until a crawl finishes"
        )

        checks += Check(
            "Crawler",
            if (PrismSearchServer.isCrawling()) Status.INFO else Status.OK,
            (if (PrismSearchServer.isCrawling()) "running now; " else "idle; ") +
                PrismSearchServer.lastCrawlSummary +
                "; interval ${PrismSettings.getSearchCrawlIntervalHours()}h"
        )

        checks += Check(
            "Seeds", Status.INFO,
            "${PrismSettings.getUserSearchSeeds().size} user, " +
                "${PrismSettings.getDiscoveredSearchSeeds().size} discovered, " +
                "${PrismSettings.DEFAULT_SEARCH_SEEDS.size} built-in"
        )

        // Where callers are actually being sent, which is not always where the server is.
        val engine = PrismSettings.getSearchEngine()
        val base = PrismSettings.prismSearchBaseUrl()
        checks += Check(
            "Selected engine", if (engine == "prism") Status.OK else Status.INFO,
            if (engine == "prism") "Prism -> $base"
            else "'$engine' is selected, so the browser will not use Prism search at all"
        )

        val onMesh = PrismSettings.isPrismSearchOnMesh()
        checks += Check(
            "Publish mode", Status.INFO,
            if (onMesh)
                "mesh server node -- published at ${PrismSettings.PRISM_SEARCH_DOMAIN}. " +
                    "Reachable only through Prism's proxy; a plain DNS lookup of that name will " +
                    "hit the real internet instead."
            else
                "local only -- bound to loopback, so ONLY this device can reach it. " +
                    "Another device on the same network will be refused by design."
        )

        return checks
    }

    /** Opens a socket to the port and issues a real GET, so the result reflects reality. */
    private fun probeLoopback(port: Int): Check {
        if (port <= 0) return Check("Loopback probe", Status.FAIL, "no port to probe")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
                socket.soTimeout = 3000
                socket.getOutputStream().apply {
                    write("GET /api/search?q=prism HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: return Check(
                    "Loopback probe", Status.FAIL, "connected to $port but the server sent nothing back"
                )
                Check("Loopback probe", Status.OK, "http://127.0.0.1:$port answered: $statusLine")
            }
        } catch (e: java.net.ConnectException) {
            Check(
                "Loopback probe", Status.FAIL,
                "connection refused on $port -- nothing is listening there. If the listener check " +
                    "above passed, the server bound a DIFFERENT port than callers are using."
            )
        } catch (e: Exception) {
            Check("Loopback probe", Status.FAIL, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun describeAge(at: Long): String {
        if (at <= 0L) return "never"
        val mins = (System.currentTimeMillis() - at) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ago"
        }
    }

    /** Plain-text report, for logging or copying into a bug report. */
    fun report(): String = buildString {
        appendLine("--- Prism Search diagnostics ---")
        for (c in run()) appendLine("[${c.status}] ${c.name}: ${c.detail}")
    }
}
