package com.prism.launcher.vpn

import com.prism.core.MeshDns
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Federation between the servers in a Prism fleet, from a Bridge client.
 *
 * ## What "bridge" means here
 *
 * A client normally joins one mesh. A bridge keeps a chosen set of fleet servers and asks each of
 * them, over the same authenticated listener the tunnel uses, for the records that make its mesh
 * understandable: P2P DNS, hosted sites, mirrored sites and Lyke video records. The DNS records are
 * merged into this device's [MeshDns] index, so a name published on any bridged server resolves
 * here without the user retyping it. The other lists are kept in [lastLinks] so the fleet screen can
 * show what each server contributes.
 *
 * ## Why the servers' own listener
 *
 * The alternative was another HTTP service with another port, another certificate and another
 * authentication story. `PrismProxyServer` already accepts an authenticated connection on the VPN
 * port, and a bridge is exactly the kind of client that port was built for. `PRISM_BRIDGE` is a
 * one-line request answered with one JSON document and closed; it cannot be mistaken for a proxy
 * request, so it does not widen what an ordinary client can do.
 *
 * ## Failure is per server
 *
 * One server being down must not erase the names learned from the others. Each refresh merges what
 * it can and reports the failures beside the successes, which is what the fleet screen shows.
 */
object FleetBridge {

    private const val TAG = "PrismFleetBridge"
    private const val TIMEOUT_MS = 6_000

    /** What one bridged server contributed on the last refresh. */
    data class Link(
        val serverId: String,
        val name: String,
        val address: String,
        val reachable: Boolean,
        val message: String,
        val domains: Int = 0,
        val hostedSites: Int = 0,
        val mirroredSites: Int = 0,
        val videos: Int = 0,
    )

    @Volatile
    var lastLinks: List<Link> = emptyList()
        private set

    @Volatile
    private var running = false

    /** True while a refresh is walking the fleet; the caller can avoid starting a second one. */
    fun isRefreshing(): Boolean = running

    /**
     * Refreshes every bridged server, blocking the calling thread.
     *
     * Callers run this off the UI thread. A server that is not reachable is reported and skipped;
     * names that were already merged stay where they are.
     */
    fun refresh(): List<Link> {
        if (!PrismSettings.isPrismBridgeRole()) {
            lastLinks = emptyList()
            return emptyList()
        }
        if (running) return lastLinks
        running = true
        return try {
            val links = PrismSettings.getBridgedServers().map { server ->
                val result = runCatching { fetch(server) }.getOrElse { error ->
                    Fetch(null, "Could not reach ${server.address}: ${error.message ?: "no answer"}")
                }
                if (result.document != null) {
                    val domains = merge(server, result.document)
                    Link(
                        serverId = server.id,
                        name = server.name.ifBlank { server.address },
                        address = "${server.address}:${server.port}",
                        reachable = true,
                        message = "Bridged",
                        domains = domains,
                        hostedSites = result.document.optJSONArray("hosted")?.length() ?: 0,
                        mirroredSites = result.document.optJSONArray("mirrors")?.length() ?: 0,
                        videos = result.document.optJSONArray("videos")?.length() ?: 0,
                    )
                } else {
                    Link(
                        serverId = server.id,
                        name = server.name.ifBlank { server.address },
                        address = "${server.address}:${server.port}",
                        reachable = false,
                        message = result.message,
                    )
                }
            }
            lastLinks = links
            links
        } finally {
            running = false
        }
    }

    /** Refreshes on a daemon thread; used when the tunnel comes up in Bridge mode. */
    fun refreshAsync() {
        if (running || !PrismSettings.isPrismBridgeRole()) return
        Thread({
            runCatching { refresh() }.onFailure {
                PrismPlatform.log.warn(TAG, "Fleet refresh failed: ${it.message}")
            }
        }, "prism-fleet-bridge").apply { isDaemon = true; start() }
    }

    private data class Fetch(val document: JSONObject?, val message: String)

    private fun fetch(server: PrismSettings.PrismServer): Fetch {
        if (server.address.isBlank() || server.port !in 1..65535) {
            return Fetch(null, "No usable address or port.")
        }
        return Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(server.address, server.port), TIMEOUT_MS)
            val credential = Base64.getEncoder().encodeToString(
                "${server.username}:${server.password}".toByteArray(StandardCharsets.UTF_8),
            )
            // Offer this device's own names with the request, so the far server's peers learn
            // them too. Without the push a bridge would be a one-way reader.
            val push = JSONObject().apply {
                put("dns", runCatching { JSONArray(MeshDns.exportJson()) }.getOrElse { JSONArray() })
            }.toString()
            val request = "PRISM_BRIDGE $credential\r\n$push\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val first = reader.readLine().orEmpty()
            if (first.startsWith("HTTP/")) {
                return Fetch(null, "This server does not answer the bridge handshake (it said $first).")
            }
            val text = buildString {
                append(first)
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
            val document = runCatching { JSONObject(text) }.getOrNull()
            if (document == null || !document.optBoolean("ok", false)) {
                return Fetch(null, "The server refused the bridge request.")
            }
            Fetch(document, "Bridged")
        }
    }

    /**
     * Imports the server's DNS records. Returns how many were offered.
     *
     * The source address passed to [MeshDns.importJson] is the server's address, not a mesh IP:
     * records that arrive without an address still resolve to something this device can reach.
     */
    private fun merge(server: PrismSettings.PrismServer, document: JSONObject): Int {
        val dns = document.optJSONArray("dns") ?: return 0
        MeshDns.importJson(server.address, dns.toString())
        // Tell the mesh this device is currently on, so the names are not merely stored here:
        // every peer that asks for DNS gets them on its next sync.
        runCatching { com.prism.core.MeshCore.broadcastToOthers(MeshDns.OPCODE_DNS_WRITE, dns.toString(), null) }
        return dns.length()
    }

    /** A compact JSON document for the bridge screen and logs. */
    fun describe(): String {
        if (!PrismSettings.isPrismBridgeRole()) return "Bridge is off."
        val links = lastLinks
        if (links.isEmpty()) return "No bridged servers yet."
        return links.joinToString("\n") { link ->
            if (link.reachable) {
                "${link.name}: ${link.domains} name(s), ${link.hostedSites} site(s), " +
                    "${link.mirroredSites} mirror(s), ${link.videos} video(s)"
            } else {
                "${link.name}: ${link.message}"
            }
        }
    }

    /** The JSON the server answers with. Kept here so both ends describe the same shape. */
    fun snapshot(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("dns", runCatching { JSONArray(MeshDns.exportJson()) }.getOrElse { JSONArray() })
        put("hosted", hostedJson())
        put("mirrors", mirrorsJson())
        put("videos", videosJson())
    }

    private fun hostedJson(): JSONArray = JSONArray().also { array ->
        PrismSettings.getP2pHostedSites().forEach { site ->
            array.put(JSONObject().put("domain", site.domain).put("active", site.isActive))
        }
    }

    private fun mirrorsJson(): JSONArray = JSONArray().also { array ->
        PrismSettings.getP2pMirroredSites().forEach { site ->
            array.put(
                JSONObject()
                    .put("domain", site.domain)
                    .put("from", site.originalHost)
                    .put("active", site.isActive),
            )
        }
    }

    private fun videosJson(): JSONArray = JSONArray().also { array ->
        runCatching {
            com.prism.launcher.social.LykeStore.videos().forEach { video ->
                array.put(
                    JSONObject()
                        .put("id", video.id)
                        .put("author", video.authorId)
                        .put("caption", video.caption)
                        .put("hosts", JSONArray().also { hosts ->
                            com.prism.launcher.social.LykeStore.hostsFor(video.id).forEach { hosts.put(it) }
                        }),
                )
            }
        }
    }
}
