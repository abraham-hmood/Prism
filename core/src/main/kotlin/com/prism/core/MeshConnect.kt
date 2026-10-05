package com.prism.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The PRISM_CONNECT handshake, both ends, on any JVM. PHASE 50.
 *
 * ## The protocol, which is three lines long
 *
 * A client opens TCP 8080 on a peer and writes `PRISM_CONNECT <domain>\n`. The server answers
 * `PRISM_ACK\n`. From there the socket carries whatever the named domain's host speaks — plain HTTP for
 * Prism's own services, or TLS if the next byte is 0x16.
 *
 * The domain is the whole point of the handshake. One port on one peer serves many things — a hosted
 * website, a model transfer, an AI generation, protein work, the cloud routes — and the domain is how the
 * server knows which. Without it every service would need its own port and its own firewall hole.
 *
 * ## Why UDP was not enough and this exists alongside [MeshCore]
 *
 * [MeshCore] is gossip: small, unreliable, fire-and-forget, and correct for "I exist" and "here is my
 * price list". It is wrong for anything that must arrive and wrong for anything larger than a datagram —
 * a model file, an APK, a browser history export. Those need a stream, so they get TCP.
 *
 * Keeping the two separate rather than building reliability on UDP is deliberate: TCP already solves
 * ordering, retransmission and flow control, and a hand-rolled reliable layer over datagrams would be a
 * worse TCP with the same wire cost.
 *
 * ## What is NOT here
 *
 * TLS. The Android server upgrades a connection whose first byte is 0x16 using a per-domain certificate,
 * which is PHASE 51 — it needs a certificate authority this build does not have. The sniff is performed
 * anyway and a TLS connection is REFUSED with a reason rather than being read as plain HTTP, because
 * feeding a TLS ClientHello to an HTTP parser produces a confusing failure in the wrong component.
 */
object MeshConnect {

    private const val TAG = "PrismMeshConnect"

    const val PORT = 8080
    private const val HANDSHAKE = "PRISM_CONNECT"
    private const val ACK = "PRISM_ACK"

    /** The TLS handshake record type. A first byte of 0x16 means the client wants TLS. */
    private const val TLS_RECORD = 0x16.toByte()

    /**
     * Serves one connection for one domain.
     *
     * [head] carries whatever the server already read off the socket while deciding what this was — the
     * request line and headers, when it sniffed plain HTTP. THAT IS NOT OPTIONAL: those bytes are gone
     * from the stream, and a host that read the socket from the start would block forever waiting for a
     * request that has already been consumed. The Android build learned this the hard way; the comment on
     * `PrismAiHost.serve` records it.
     */
    fun interface DomainHost {
        fun serve(socket: Socket, domain: String, head: String?, input: InputStream)
    }

    private val hosts = ConcurrentHashMap<String, DomainHost>()

    @Volatile private var fallback: DomainHost? = null

    /**
     * Stops serving a domain.
     *
     * Needed because hosting is a thing users turn off, and a registration that outlived the site would
     * keep answering for a name whose folder has gone -- with a 404 from a host that thinks it owns the
     * name, rather than the honest "nothing here" from the dispatcher.
     */
    fun unhost(domain: String) {
        hosts.remove(domain.lowercase().trim())
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    val isListening: Boolean get() = running && server?.isClosed == false

    /**
     * Registers a host for a domain.
     *
     * Matched case-insensitively and by SUFFIX as well as exactly, because the mesh's own reserved
     * domains are markers rather than real names — the model host is a constant string the client sets as
     * a hint, and a hosted website is `something.p2p`. A suffix match lets one host claim `.p2p` without
     * enumerating every site somebody publishes.
     */
    fun host(domain: String, host: DomainHost) {
        hosts[domain.lowercase()] = host
    }

    /** Catches domains nobody claimed. Without one, an unknown domain gets a refusal. */
    fun fallbackHost(host: DomainHost?) {
        fallback = host
    }

    fun registeredDomains(): List<String> = hosts.keys.sorted()

    private fun hostFor(domain: String): DomainHost? {
        val key = domain.lowercase()
        hosts[key]?.let { return it }
        // Longest suffix wins, so a specific `models.prism` beats a general `.prism`.
        return hosts.entries
            .filter { key.endsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
            ?: fallback
    }

    /**
     * Why the listener is not up, or empty when it is.
     *
     * KEPT RATHER THAN ONLY LOGGED. "Not listening" on a diagnostics screen is a dead end; "port 8080 is
     * already in use" is something the reader can act on, and the commonest cause by far is a previous
     * Prism process that has not exited. The exception message is the only place that distinction exists.
     */
    @Volatile
    var lastBindError: String = ""
        private set

    // ── Server ─────────────────────────────────────────────────────────────

    /**
     * Accepts PRISM_CONNECT on [PORT].
     *
     * One thread per connection. A thread pool would be tidier and is the wrong shape here: a connection
     * can be a multi-gigabyte model transfer or a streaming AI generation that lasts minutes, so a bounded
     * pool would let a few long transfers starve every other service on the port.
     */
    @Synchronized
    fun startServer(): Boolean {
        if (running) return true
        val bound = runCatching { ServerSocket(PORT) }.getOrElse {
            lastBindError = (it.message ?: it::class.java.simpleName) +
                " -- if this says the address is in use, another process holds TCP " + PORT +
                ", most often an earlier Prism that is still running"
            PrismPlatform.log.error(TAG, "Could not bind TCP $PORT", it)
            return false
        }
        lastBindError = ""
        server = bound
        running = true
        PrismPlatform.log.info(TAG, "PRISM_CONNECT listening on TCP $PORT")

        thread(name = "mesh-connect-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    bound.accept()
                } catch (e: Exception) {
                    if (running) PrismPlatform.log.error(TAG, "Accept failed", e)
                    break
                }
                thread(name = "mesh-connect-serve", isDaemon = true) { serve(client) }
            }
            running = false
        }
        return true
    }

    @Synchronized
    fun stopServer() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) {
        runCatching {
            // Bounded, so a peer that opens a socket and says nothing does not hold a thread forever.
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val first = readLine(input)
            if (first == null || !first.startsWith(HANDSHAKE)) {
                // Not one of ours. Closed without a reply rather than answered: this port is reachable
                // from the local network and an HTTP error page would make it look like a web server.
                client.close()
                return@runCatching
            }

            val domain = first.split(" ").getOrNull(1)?.trim().orEmpty()
            if (domain.isEmpty()) {
                client.close()
                return@runCatching
            }

            output.write("$ACK\n".toByteArray())
            output.flush()

            // Sniff one byte to tell TLS from plain HTTP, then put it back. PushbackInputStream is what
            // makes "look without consuming" possible on a plain socket.
            val pushback = PushbackInputStream(input, 8)
            val peek = pushback.read()
            if (peek < 0) {
                client.close()
                return@runCatching
            }
            pushback.unread(peek)

            // PHASE 51. A client speaking TLS is upgraded with a certificate minted for the domain it
            // asked for, rather than refused. The socket handed to the host is then the TLS socket, so a
            // host serves the same bytes either way and does not know which it is on.
            val (stream, socket) = if (peek.toByte() == TLS_RECORD) {
                val secure = MeshTls.serverSocket(client, pushback, domain)
                if (secure == null) {
                    // No CA, so no certificate can be presented. Refused with a reason rather than read
                    // as plain HTTP: handing a ClientHello to an HTTP parser produces a failure inside
                    // the wrong component, with a message about a malformed request line.
                    PrismPlatform.log.info(TAG, "No certificate authority, so TLS for $domain was refused")
                    client.close()
                    return@runCatching
                }
                runCatching { secure.startHandshake() }.onFailure {
                    // Almost always the client rejecting the certificate, which is what happens until
                    // the user installs the root CA. Logged as information, not as an error.
                    PrismPlatform.log.info(TAG, "TLS handshake for $domain ended: " + it.message)
                    runCatching { client.close() }
                    return@runCatching
                }
                secure.inputStream to (secure as Socket)
            } else {
                (pushback as java.io.InputStream) to client
            }

            // The head is read HERE so the domain dispatch can happen, which means the host must be
            // given it back — see DomainHost.
            val head = readHead(stream)
            val target = hostFor(domain)
            if (target == null) {
                PrismPlatform.log.info(TAG, "No host registered for $domain")
                runCatching {
                    output.write(
                        ("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n" +
                            "Connection: close\r\n\r\nNo Prism service is serving $domain here.\n")
                            .toByteArray()
                    )
                    output.flush()
                }
                client.close()
                return@runCatching
            }

            // The timeout is cleared before handing over: the handshake needed a bound, and a generation
            // or a large transfer does not. Leaving it on is what made peer inference time out on the
            // Android build.
            socket.soTimeout = 0
            target.serve(socket, domain, head, stream)
        }.onFailure {
            PrismPlatform.log.error(TAG, "Serving a PRISM_CONNECT failed", it)
            runCatching { client.close() }
        }
    }

    // ── Client ─────────────────────────────────────────────────────────────

    /**
     * Opens a connection to a peer and completes the handshake.
     *
     * Returns a connected socket positioned immediately after `PRISM_ACK`, or null. The caller writes its
     * own request and closes the socket.
     *
     * [domain] is what the peer will dispatch on, so it is required rather than derived from the address:
     * the address identifies the MACHINE and the domain identifies the SERVICE, and one machine serves
     * several.
     */
    fun connect(peerIp: String, domain: String, timeoutMs: Int = 15_000): Socket? {
        val target = if (MeshUtils.getAllLocalIps().contains(peerIp)) "127.0.0.1" else peerIp

        return runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getByName(target), PORT), timeoutMs)
            socket.soTimeout = timeoutMs

            socket.getOutputStream().apply {
                write("$HANDSHAKE $domain\n".toByteArray())
                flush()
            }

            val ack = readLine(socket.getInputStream())
            if (ack == null || !ack.startsWith(ACK)) {
                runCatching { socket.close() }
                PrismPlatform.log.info(TAG, "$peerIp did not acknowledge PRISM_CONNECT (got: $ack)")
                return null
            }
            socket
        }.getOrElse {
            PrismPlatform.log.info(TAG, "Could not reach $peerIp:$PORT — ${it.message}")
            null
        }
    }

    /**
     * A whole request-and-response over the handshake, for a caller that just wants bytes.
     *
     * The common case by a wide margin: every mesh service except a streaming generation is one request
     * and one answer. Writing that out at each call site is where the timeout-after-connect mistake keeps
     * getting made.
     */
    /**
     * Fetches a path from a peer over the tunnel. Blocking.
     *
     * The GET half of [request]. Separate rather than a verb parameter because the two differ in more
     * than the word: a GET has no body to write, and it is the one used for files, so it streams into a
     * file instead of building a byte array of whatever arrives.
     *
     * [into] is written only on success, through a `.part` file, so a transfer that dies halfway leaves
     * nothing that looks like a finished download.
     */
    fun get(
        peerIp: String,
        domain: String,
        path: String,
        into: File? = null,
        timeoutMs: Int = 60_000,
        maxResponseBytes: Long = 512L * 1024 * 1024,
    ): ByteArray? {
        val socket = connect(peerIp, domain, timeoutMs = 15_000) ?: return null
        var partial: File? = null
        return try {
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            skipHeaders(input)

            if (into == null) return readAll(input, maxResponseBytes)

            into.parentFile?.mkdirs()
            val temporary = File(into.absolutePath + ".part")
            partial = temporary
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxResponseBytes) throw java.io.IOException("Response too large")
                    output.write(buffer, 0, read)
                }
            }
            if (into.exists()) into.delete()
            if (!temporary.renameTo(into)) throw java.io.IOException("Could not finish " + into.name)
            partial = null
            ByteArray(0)
        } catch (e: Exception) {
            PrismPlatform.log.info(TAG, "GET $path from $peerIp failed: ${e.message}")
            null
        } finally {
            runCatching { partial?.delete() }
            runCatching { socket.close() }
        }
    }

    fun request(
        peerIp: String,
        domain: String,
        path: String,
        body: ByteArray,
        timeoutMs: Int = 60_000,
        maxResponseBytes: Long = 64L * 1024 * 1024,
    ): ByteArray? {
        val socket = connect(peerIp, domain, timeoutMs = 15_000) ?: return null
        return try {
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(
                    ("POST $path HTTP/1.1\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(body)
                flush()
            }
            val input = socket.getInputStream()
            skipHeaders(input)
            readAll(input, maxResponseBytes)
        } catch (e: Exception) {
            PrismPlatform.log.info(TAG, "Request to $peerIp$path failed: ${e.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Stream helpers ─────────────────────────────────────────────────────

    /**
     * Reads one CRLF- or LF-terminated line, byte by byte.
     *
     * NOT a BufferedReader. A reader would buffer ahead past the line and those bytes would be lost to
     * whatever reads the socket next — which is the body, or a TLS handshake. Reading one byte at a time
     * is slower and is the only way to stop exactly at the newline.
     */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
            if (out.length > MAX_LINE) return out.toString()
        }
    }

    /** Everything up to and including the blank line that ends an HTTP head. */
    private fun readHead(input: InputStream): String {
        val out = StringBuilder()
        var consecutiveNewlines = 0
        while (out.length < MAX_HEAD) {
            val c = input.read()
            if (c == -1) break
            out.append(c.toChar())
            when (c) {
                '\n'.code -> {
                    consecutiveNewlines++
                    if (consecutiveNewlines == 2) break
                }
                '\r'.code -> Unit
                else -> consecutiveNewlines = 0
            }
        }
        return out.toString()
    }

    private fun skipHeaders(input: InputStream) {
        var matched = 0
        while (matched < 4) {
            val c = input.read()
            if (c == -1) return
            matched = when {
                matched == 0 && c == '\r'.code -> 1
                matched == 1 && c == '\n'.code -> 2
                matched == 2 && c == '\r'.code -> 3
                matched == 3 && c == '\n'.code -> 4
                c == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun readAll(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            total += n
            // The length is not known in advance -- the peer streams until close -- so an unbounded read
            // from the network into the heap is an OOM waiting for a bad peer.
            if (total > limit) break
        }
        return out.toByteArray()
    }

    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEAD = 64 * 1024
}
