package com.prism.launcher.search

import com.prism.core.MeshConnect
import com.prism.core.MeshDns
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Serving the search page to the mesh on a reserved name. PHASE 78.
 *
 * ## Why this is a proxy rather than a second server
 *
 * [PrismSearchServer] already answers HTTP on loopback — that is what makes "Prism" work as the
 * browser's search engine on this machine. Standing up a second copy of it for the mesh would mean two
 * servers over one index, two ports to keep straight, and two places for a query-parsing bug to live.
 * So a mesh connection for the reserved name is handed to the local server and the answer copied back.
 *
 * ## Why the name is registered rather than assumed
 *
 * A peer reaches this device because [MeshDns] holds a record for the name pointing at it. Registering
 * it is what makes `prism.com` resolve on the mesh at all, and it is the same mechanism any other
 * hosted site uses — the reserved name is a convention, not a special case in the transport.
 *
 * SHADOWING A PUBLIC NAME IS THE POINT HERE. `prism.com` exists on the public internet; on this mesh it
 * is the search page. That is the instruction about suffixes taken to its conclusion, and it is why
 * MeshWebHost.shadowsPublicName exists to say so in a UI.
 *
 * ## Why it falls back to loopback
 *
 * With the mesh off, the browser still has to be able to search. The engine's base URL already points
 * at the loopback listener, so nothing here is load-bearing for the local case — this is purely the
 * path that lets OTHER devices use this machine's index.
 */
object MeshSearchHost : MeshConnect.DomainHost {

    private const val TAG = "PrismSearchMesh"

    /** The name the search page answers on across the mesh. */
    const val DOMAIN = "prism.com"

    /** A mesh-only alias, for anyone who would rather not shadow a public name. */
    const val ALIAS = "search.p2p"

    @Volatile
    private var installed = false

    /**
     * Registers the reserved names and the DNS records behind them.
     *
     * Called after the search server is up, because a name that resolves to a device with nothing
     * listening is worse than one that does not resolve: the first looks like a broken site, the second
     * like a site that is not hosted here.
     */
    fun install() {
        if (installed) return
        installed = true

        MeshConnect.host(DOMAIN, this)
        MeshConnect.host(ALIAS, this)
        MeshDns.put(DOMAIN, "127.0.0.1", MeshDns.Source.LOCAL)
        MeshDns.put(ALIAS, "127.0.0.1", MeshDns.Source.LOCAL)
        MeshDns.announce(DOMAIN)
        MeshDns.announce(ALIAS)

        PrismPlatform.log.info(TAG, "Search is answering for $DOMAIN and $ALIAS on the mesh")
    }

    override fun serve(socket: Socket, domain: String, head: String?, input: InputStream) {
        val output = socket.getOutputStream()
        val request = head.orEmpty().ifBlank { "GET / HTTP/1.1\r\n\r\n" }

        val port = PrismSearchServer.boundPort()
        if (port <= 0) {
            runCatching {
                val body = ("Prism's search index is not listening on this device.").toByteArray()
                output.write(
                    ("HTTP/1.1 503 Unavailable\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray()
                )
                output.write(body)
                output.flush()
            }
            runCatching { socket.close() }
            return
        }

        runCatching {
            Socket().use { local ->
                local.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
                local.soTimeout = 30_000

                // The head the mesh already read is replayed verbatim, then whatever follows it. A
                // rewritten request line would break the query string, which is the only part that
                // matters here.
                local.getOutputStream().apply {
                    write(request.toByteArray())
                    if (!request.endsWith("\r\n\r\n")) write("\r\n".toByteArray())
                    flush()
                }
                local.getInputStream().copyTo(output, 32 * 1024)
                output.flush()
            }
        }.onFailure {
            PrismPlatform.log.info(TAG, "Could not reach the local search server: " + it.message)
        }
        runCatching { socket.close() }
    }

    /** Where a peer would point a browser to reach this device's index. */
    fun meshUrl(): String = "http://" + DOMAIN + "/"

    /** Whether this device is currently offering it. */
    fun isOffering(): Boolean = installed && PrismSearchServer.boundPort() > 0
}
