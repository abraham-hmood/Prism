package com.prism.launcher.vpn

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * This device answering the directory question for other devices.
 *
 * ## What it is
 *
 * The serving half of [ServerDirectory]. A Prism server that switches this on tells every device
 * that asks which Prism servers its operator knows about, and accepts their advertisements. It is
 * the same thing the fleet list already was, published at an address a device that has never seen
 * this mesh can be given.
 *
 * ## Why it is gated on the role
 *
 * A directory is a statement about a fleet, and only a device whose job is to serve one has a fleet
 * to state. A client that hosted a directory would be publishing the servers it happens to dial,
 * including whichever one the user's employer runs, from a phone on a coffee-shop network. The
 * checkbox exists in Settings only when the role is Server, and [refresh] re-checks the role rather
 * than trusting that the toggle was stored honestly -- a role change must close the listener, not
 * leave it up until the next restart.
 *
 * ## Why credentials are required rather than optional
 *
 * An unauthenticated directory is an open list that anyone on the network can append to, which is
 * exactly the thing a fleet operator would be most damaged by: their directory would start
 * advertising somebody else's server to their own clients. When no username or password is set, the
 * server refuses every request and says so in the log rather than silently running open.
 *
 * ## Bounds
 *
 * One listener, at most eight concurrent requests, a ten-second socket timeout, and hard limits on
 * both request lines. A directory request is one short line; anything longer is not a request this
 * server has any use for.
 */
object DirectoryServer {

    private const val TAG = "PrismDirectoryServer"

    /** Where a directory listens when nothing else is using it. */
    const val DEFAULT_PORT = 4747

    /** How many ports above [DEFAULT_PORT] to try before giving up. */
    private const val PORT_ATTEMPTS = 10

    private const val TIMEOUT_MS = 10_000
    private const val MAX_LINE = 512

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = server != null

    private val workers = ThreadPoolExecutor(
        1, 8, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(32),
        { runnable -> Thread(runnable, "prism-directory-client").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /**
     * Brings the listener in line with the setting and the role.
     *
     * Called when the checkbox changes, when the role changes, and once at startup. Idempotent:
     * starting a running server leaves it alone, and stopping a stopped one does nothing.
     */
    fun refresh() {
        val wanted = PrismSettings.getHostDirectoryServer() &&
            PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER &&
            PrismSettings.getMeshEnabled()
        if (wanted) {
            if (!isRunning) start()
        } else {
            stop()
        }
    }

    /** Starts listening. Returns the bound port, or 0 when it could not. */
    fun start(preferred: Int = DEFAULT_PORT): Int {
        if (isRunning) return port
        val bind = MeshUtils.getLocalMeshIp().ifBlank { "127.0.0.1" }
        val address = runCatching { InetAddress.getByName(bind) }.getOrNull() ?: return 0

        // A DIRECTORY IS NOT AN OPEN RESOLVER AND MUST NOT BE ONE. It binds the mesh address, so
        // only devices already on this tunnel can reach it, and it authenticates every request on
        // top of that.
        var bound: ServerSocket? = null
        for (offset in 0 until PORT_ATTEMPTS) {
            bound = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, preferred + offset))
                }
            }.getOrNull()
            if (bound != null) break
        }
        if (bound == null) {
            PrismPlatform.log.warn(TAG, "Could not bind a directory port on $bind")
            return 0
        }

        server = bound
        port = bound.localPort
        PrismPlatform.log.info(TAG, "Directory server listening on $bind:$port")

        thread(name = "prism-directory", isDaemon = true) { acceptLoop(bound) }
        return port
    }

    fun stop() {
        val current = server ?: return
        server = null
        port = 0
        runCatching { current.close() }
        PrismPlatform.log.info(TAG, "Directory server stopped")
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (server != null && !listener.isClosed) {
            val client = runCatching { listener.accept() }.getOrNull() ?: continue
            workers.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val first = readLine(input) ?: return
                val tokens = first.trim().split(" ")
                if (tokens.size < 2 || tokens[0] != ServerDirectory.HANDSHAKE) {
                    write(output, "PRISM_DIRECTORY_UNKNOWN")
                    return
                }
                val push = if (tokens[1].uppercase() == "ADD") readLine(input) else null
                val credential = tokens.getOrNull(2).orEmpty()
                write(output, ServerDirectory.serve(first, push, ::authorized))
            }
        }.onFailure {
            PrismPlatform.log.warn(TAG, "A directory request failed: ${it.message}")
        }
    }

    /** A line, or null when it is longer than any directory request could legitimately be. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < MAX_LINE) {
            val next = input.read()
            if (next < 0) break
            if (next == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(next.toChar())
        }
        return if (buffer.length >= MAX_LINE) null else buffer.toString().trimEnd('\r')
    }

    private fun write(output: OutputStream, text: String) {
        runCatching {
            output.write((text + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    /**
     * Whether [credential] is this server's own Base64 `user:password` pair.
     *
     * Compared with [MessageDigest.isEqual] rather than `==` so the check does not leak how much of
     * the credential was right. An unset password refuses everything: see the class comment.
     */
    private fun authorized(credential: String): Boolean {
        val password = PrismSettings.getPrismVpnPassword()
        if (password.isBlank()) {
            PrismPlatform.log.warn(
                TAG,
                "A directory request arrived, but no proxy password is set -- refusing it. " +
                    "Set a Proxy Auth Password before hosting a directory.",
            )
            return false
        }
        val expected = Base64.getEncoder().encodeToString(
            (PrismSettings.getPrismVpnUsername() + ":" + password).toByteArray(Charsets.UTF_8),
        )
        return MessageDigest.isEqual(
            credential.trim().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }
}
