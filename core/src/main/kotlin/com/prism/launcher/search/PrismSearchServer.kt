package com.prism.launcher.search

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prism's own search engine: the HTTP surface, the loopback listener, and the crawl schedule.
 *
 * TWO WAYS IN, ONE HANDLER. A mesh SERVER node publishes the engine at the reserved domain
 * `prism.com`, dispatched by `PrismProxyServer` exactly the way Nebula and Aether's hosts are, so
 * every peer on the mesh can reach it by name. Any other device runs the identical engine on a
 * loopback [ServerSocket] for its own use. Both paths call [serveHttp], so there is one
 * implementation of what the engine answers, not two that can drift.
 *
 * LIVES IN :core SO BOTH PLATFORMS GET THE SAME ENGINE. The desktop browser page resolves its
 * search URL through the same [PrismSettings.buildSearchUrl], so if the engine only existed in
 * :app, selecting "Prism" on desktop would point the browser at a port with nothing behind it.
 * Only the mesh dispatch (which needs an Android Context) stays in :app.
 *
 * BOUND TO LOOPBACK, DELIBERATELY. The local listener uses `InetAddress.getLoopbackAddress()`
 * rather than binding every interface: a phone on a café network should not be quietly running an
 * open web service that anyone on that network can query, and the mesh path is the supported way
 * to expose it to other people.
 */
object PrismSearchServer {

    private const val TAG = "PrismSearch"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var crawlJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val crawling = AtomicBoolean(false)

    @Volatile var lastCrawlSummary: String = "never crawled"
        private set

    /** Where the engine is reachable, for the UI to display and copy. */
    fun address(): String = PrismSettings.prismSearchBaseUrl()

    /**
     * The port actually bound, or 0 if nothing is listening.
     *
     * Reported from the live socket rather than from settings: the two disagree exactly when
     * something has gone wrong -- a failed bind, or a fallback to a different port -- which is
     * precisely the case diagnostics exist to catch.
     */
    fun boundPort(): Int = serverSocket?.takeIf { !it.isClosed }?.localPort ?: 0

    fun isListening(): Boolean = boundPort() > 0

    /**
     * The address actually bound, e.g. `127.0.0.1:52166`, or "not listening".
     *
     * Reported separately from the port because a socket on the wrong INTERFACE has the right
     * port: the failure that motivated this looked identical to a healthy server in every check
     * that only compared port numbers.
     */
    fun boundAddress(): String {
        val socket = serverSocket ?: return "not listening"
        if (socket.isClosed) return "closed"
        val addr = socket.inetAddress?.hostAddress ?: "?"
        return "$addr:${socket.localPort}"
    }

    fun isCrawling(): Boolean = crawling.get()

    // -- Lifecycle -------------------------------------------------------------------------

    /** Starts the loopback listener and the crawl schedule. Safe to call repeatedly. */
    fun start() {
        if (listenerJob?.isActive != true) {
            listenerJob = scope.launch {
                // The index survives restarts; only a missing/corrupt one triggers a cold crawl.
                if (!PrismSearchIndex.load()) {
                    PrismPlatform.log.info(TAG, "No index on disk yet -- first crawl will build one.")
                }
                runListener()
            }
        }
        if (crawlJob?.isActive != true) {
            crawlJob = scope.launch { runCrawlSchedule() }
        }
    }

    fun stop() {
        listenerJob?.cancel(); crawlJob?.cancel()
        listenerJob = null; crawlJob = null
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    private suspend fun runListener() {
        // Two attempts: the remembered port, then a freshly chosen one. A port that was free when
        // it was picked can be taken by another program before the next launch, and silently
        // refusing every request from then on is exactly the failure this feature already had once.
        val socket = bind(PrismSettings.getPrismSearchPort()) ?: run {
            val fresh = com.prism.core.MeshUtils.findAvailablePort()
            bind(fresh)?.also {
                PrismSettings.setPrismSearchPort(fresh)
                PrismPlatform.log.warn(TAG, "Previous search port was taken; moved to $fresh")
            }
        }
        if (socket == null) {
            PrismPlatform.log.error(TAG, "Could not bind any port for Prism search", null)
            return
        }
        try {
            serverSocket = socket
            PrismPlatform.log.success(TAG, "Prism search listening on http://127.0.0.1:${socket.localPort}")
            while (scope.isActive) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                scope.launch {
                    try { serveHttp(client) } catch (e: Exception) {
                        PrismPlatform.log.error(TAG, "Request failed", e)
                        runCatching { client.close() }
                    }
                }
            }
        } catch (e: Exception) {
            PrismPlatform.log.error(TAG, "Search listener stopped", e)
        }
    }

    /**
     * Binds loopback only -- a phone on a shared network must not quietly expose a web service.
     *
     * BINDS IPv4 127.0.0.1 EXPLICITLY, not `InetAddress.getLoopbackAddress()`. That helper returns
     * whichever loopback the platform prefers, and on Android that is frequently the IPv6 `::1`.
     * Binding `[::1]:port` SUCCEEDS -- the socket is real, the port is right, every status check
     * passes -- and then every caller dialling `http://127.0.0.1:port` is refused, because nothing
     * is listening on the IPv4 loopback. A bind that works and a connection that is refused, with
     * no error anywhere, is exactly that mismatch.
     *
     * The address handed out by `PrismSettings.prismSearchBaseUrl()` is 127.0.0.1, so that is the
     * address bound here. One place decides, both agree.
     */
    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_V4))
    } catch (e: Exception) {
        PrismPlatform.log.warn(TAG, "Port $port unavailable: ${e.message}")
        null
    }

    const val LOOPBACK_V4 = "127.0.0.1"

    /**
     * Crawl on the user's schedule.
     *
     * The elapsed check is against the index's own recorded timestamp rather than a counter in
     * this process, so closing and reopening Prism does not restart the clock and re-crawl every
     * launch -- which would be both slow and rude to the sites involved.
     */
    private suspend fun runCrawlSchedule() {
        while (scope.isActive) {
            val hours = PrismSettings.getSearchCrawlIntervalHours()
            if (hours <= 0) {
                delay(60_000L)              // disabled: idle, but keep watching for a settings change
                continue
            }
            val intervalMs = hours * 3_600_000L
            val since = System.currentTimeMillis() - PrismSearchIndex.lastCrawlAt
            if (PrismSearchIndex.lastCrawlAt == 0L || since >= intervalMs) {
                crawlNow()
            }
            // Re-check often enough that shortening the interval in Settings takes effect promptly.
            delay(minOf(intervalMs, 15 * 60_000L))
        }
    }

    /** Runs one crawl immediately. No-op if one is already running. */
    fun crawlNow() {
        if (!crawling.compareAndSet(false, true)) return
        scope.launch {
            try {
                val seeds = PrismSettings.getSearchSeeds()
                PrismPlatform.log.info(TAG, "Crawl starting from ${seeds.size} seed(s)")
                val crawled = PrismCrawler.crawl(
                    PrismCrawler.Config(seeds = seeds),
                    shouldContinue = { scope.isActive }
                )
                if (crawled.pages.isNotEmpty()) {
                    // Swapped in atomically, so a query mid-crawl sees the previous index rather
                    // than a half-built one.
                    PrismSearchIndex.replaceAll(crawled.pages)
                    PrismSearchIndex.save()
                }
                // Every new host this crawl met becomes a starting point for the next one, so the
                // index widens instead of re-walking the same neighbourhood every two hours.
                val added = PrismSettings.recordDiscoveredSeeds(crawled.newOrigins)
                lastCrawlSummary =
                    "${crawled.pages.size} page(s) indexed" +
                        (if (added > 0) ", $added new site(s) found" else "")
                PrismPlatform.log.success(TAG, "Crawl finished -- $lastCrawlSummary")
            } catch (e: Exception) {
                lastCrawlSummary = "crawl failed: ${e.message}"
                PrismPlatform.log.error(TAG, "Crawl failed", e)
            } finally {
                crawling.set(false)
            }
        }
    }

    // -- HTTP ------------------------------------------------------------------------------

    /**
     * Answers one HTTP request.
     *
     * [preReadHeader] exists because the request may already have been consumed before it reaches
     * here. `PrismProxyServer`'s browser path reads the request line and headers itself in order
     * to find the `Host:` it dispatches on, and only THEN hands over the socket -- so reading the
     * stream again returns whatever follows the headers, which is nothing for a GET. That produced
     * a request line that parsed as neither a method nor a path, and the server answered every
     * mesh request with "405 Method Not Allowed" while the identical code served localhost fine.
     * `PrismWebHost` has taken a `preReadHeader` for exactly this reason all along.
     */
    fun serveHttp(socket: Socket, preReadHeader: String? = null) {
        socket.use { s ->
            s.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = preReadHeader
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: reader.readLine()
                ?: return
            // Only drain the stream when we were the ones who read the request line from it.
            if (preReadHeader == null) {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(s, 405, "text/plain", "Method Not Allowed".toByteArray()); return
            }
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = paramOf(target, "q")

            when (path) {
                "/", "/index.html" -> respond(s, 200, "text/html; charset=utf-8", page(query).toByteArray())
                "/api/search" -> respond(s, 200, "application/json", resultsJson(query).toByteArray())
                else -> respond(s, 404, "text/plain", "Not Found".toByteArray())
            }
        }
    }

    private fun paramOf(target: String, key: String): String {
        val q = target.substringAfter('?', "")
        for (pair in q.split('&')) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key) {
                return try { URLDecoder.decode(pair.substring(i + 1), "UTF-8") } catch (e: Exception) { "" }
            }
        }
        return ""
    }

    private fun resultsJson(query: String): String {
        val arr = JSONArray()
        if (query.isNotBlank()) {
            for (r in PrismSearchIndex.search(query, limit = 25)) {
                arr.put(
                    JSONObject().put("url", r.url).put("title", r.title)
                        .put("snippet", r.snippet).put("score", r.score).put("pageRank", r.pageRank)
                )
            }
        }
        return JSONObject()
            .put("query", query)
            .put("indexed", PrismSearchIndex.documentCount())
            .put("results", arr)
            .toString()
    }

    // -- The page --------------------------------------------------------------------------

    /**
     * Self-contained: no external CSS, fonts or scripts. A mesh peer may have no route to the open
     * internet at all, so anything fetched from outside would simply never load -- the same
     * constraint the Nebula page is built under.
     */
    private fun page(query: String): String {
        val results = if (query.isBlank()) emptyList() else PrismSearchIndex.search(query, limit = 25)
        val indexed = PrismSearchIndex.documentCount()

        val body = StringBuilder()
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                body.append("<div class=\"empty\">No results for <b>").append(esc(query))
                    .append("</b>.<br><span class=\"dim\">")
                    .append(if (indexed == 0) "The index is still empty — the first crawl has not finished."
                            else "$indexed pages indexed.")
                    .append("</span></div>")
            } else {
                for (r in results) {
                    body.append("<a class=\"result\" href=\"").append(esc(r.url)).append("\">")
                        .append("<div class=\"rtitle\">").append(esc(r.title)).append("</div>")
                        .append("<div class=\"rurl\">").append(esc(prettyUrl(r.url))).append("</div>")
                        .append("<div class=\"rsnip\">").append(esc(r.snippet)).append("</div>")
                        .append("</a>")
                }
            }
        }

        // Above the results and below the bar, as its own element -- it summarises the results
        // rather than replacing them, so the sources stay one glance away.
        val summary = if (query.isBlank()) null else PrismSearchSummary.summarise(query, results)
        val summaryBlock = if (summary.isNullOrBlank()) "" else
            "<section class=\"aisum\"><div class=\"aihead\">AI summary</div>" +
                "<div class=\"aibody\">" + esc(summary) + "</div>" +
                "<div class=\"ainote\">Written by your local model from the results below. " +
                "It can be wrong — check the sources.</div></section>"

        val resultsBlock = if (query.isBlank()) "" else "<section class=\"results\">$body</section>"
        val meta = if (query.isBlank()) "$indexed pages indexed" else
            "${results.size} result${if (results.size == 1) "" else "s"} · $indexed pages indexed"

        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${if (query.isBlank()) "Prism" else esc(query) + " — Prism"}</title><style>
*{box-sizing:border-box}
:root{
  --bg:#f7f8fa; --fg:#12141a; --dim:#697086; --card:#ffffff; --line:#e4e7ee;
  --accent:#5b6cff; --shadow:0 1px 2px rgba(16,20,40,.05),0 8px 28px rgba(16,20,40,.07);
}
@media(prefers-color-scheme:dark){:root{
  --bg:#0d0f14; --fg:#e8eaf2; --dim:#8b93a7; --card:#141821; --line:#232838;
  --accent:#7b8bff; --shadow:0 1px 2px rgba(0,0,0,.4),0 8px 28px rgba(0,0,0,.45);
}}
/* min-height, NOT height: a flex column pinned to exactly 100% cannot grow past the viewport,
   so a long result list was clipped with nothing to scroll. */
html{height:100%}
body{margin:0;min-height:100%;overflow-y:auto;-webkit-overflow-scrolling:touch;
  background:var(--bg);color:var(--fg);
  font:16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif;
  display:flex;flex-direction:column;align-items:center;
  padding:${if (query.isBlank()) "22vh" else "40px"} 20px 60px;transition:padding .25s ease}
.brand{font-size:${if (query.isBlank()) "44px" else "26px"};font-weight:600;letter-spacing:-.02em;
  margin:0 0 22px;background:linear-gradient(92deg,var(--accent),#a56bff 60%,#ff6bcb);
  -webkit-background-clip:text;background-clip:text;color:transparent;transition:font-size .25s ease}
form{width:100%;max-width:640px}
.bar{display:flex;align-items:center;gap:10px;background:var(--card);border:1px solid var(--line);
  border-radius:28px;padding:12px 20px;box-shadow:var(--shadow);transition:border-color .18s,box-shadow .18s}
.bar:focus-within{border-color:var(--accent);box-shadow:0 0 0 4px color-mix(in srgb,var(--accent) 18%,transparent),var(--shadow)}
input{flex:1;border:0;outline:0;background:transparent;color:var(--fg);font-size:16px;min-width:0}
input::placeholder{color:var(--dim)}
button{border:0;background:var(--accent);color:#fff;border-radius:18px;padding:8px 16px;
  font-size:14px;font-weight:600;cursor:pointer;transition:filter .15s}
button:hover{filter:brightness(1.08)}
.meta{max-width:640px;width:100%;color:var(--dim);font-size:13px;margin:14px 2px 8px}
.aisum{width:100%;max-width:640px;background:var(--card);border:1px solid var(--line);
border-radius:16px;padding:16px 18px;margin:0 0 14px;text-align:left;box-sizing:border-box}
.aihead{font-size:12px;letter-spacing:.08em;text-transform:uppercase;opacity:.55;margin-bottom:8px}
.aibody{font-size:15px;line-height:1.55;white-space:pre-wrap}
.ainote{font-size:11px;opacity:.5;margin-top:10px}
.results{width:100%;max-width:640px;background:var(--card);border:1px solid var(--line);
  border-radius:20px;box-shadow:var(--shadow);overflow:hidden;flex:0 0 auto}
.result{display:block;padding:16px 20px;border-bottom:1px solid var(--line);text-decoration:none;
  color:inherit;transition:background .15s}
.result:last-child{border-bottom:0}
.result:hover{background:color-mix(in srgb,var(--accent) 7%,transparent)}
.rtitle{font-size:16px;font-weight:600;color:var(--accent);margin-bottom:3px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rurl{font-size:12.5px;color:var(--dim);margin-bottom:6px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rsnip{font-size:14px;color:var(--fg);opacity:.85;overflow-wrap:anywhere}
.empty{padding:28px 20px;text-align:center}
.dim{color:var(--dim);font-size:14px}
</style></head><body>
<div class="brand">Prism</div>
<form action="/" method="get" role="search">
  <div class="bar">
    <input name="q" value="${esc(query)}" placeholder="Search the web" autofocus
           autocomplete="off" spellcheck="false" aria-label="Search">
    <button type="submit">Search</button>
  </div>
</form>
<div class="meta">$meta</div>
$summaryBlock
$resultsBlock
</body></html>"""
    }

    private fun prettyUrl(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(90)

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }
}
