package com.prism.launcher.vpn

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Connecting to a Prism meshnet server, and finding out whether it worked.
 *
 * ## Why "set it as active" needs a connection first
 *
 * Marking a server active is a promise: everything that asks "which server are we on" believes it. A UI
 * that set the flag on a click and left the connecting to something else would show a green server that
 * nothing could reach, and the user's next question -- why is the mesh empty -- would have no answer
 * anywhere on screen. So the flag is set by [connect], after a real exchange, and not before.
 *
 * ## What it actually does
 *
 * Speaks the handshake `PrismProxyServer` already answers, which is an HTTP proxy with Basic
 * authentication: open TCP, send a CONNECT, read the status line.
 *
 *   - `200` means connected and authenticated.
 *   - `407` means the server is there and the credentials are wrong, WHICH IS THE MOST USEFUL FAILURE
 *     THERE IS and is worth distinguishing: it says the address and port are right.
 *   - anything else, or a timeout, means the server did not answer as a Prism server.
 *
 * ## Why CONNECT to a loopback target
 *
 * The probe has to ask for something, and asking for a real host would make this a test of the server's
 * onward connectivity rather than of the server. `127.0.0.1:1` is a target the server will fail to reach
 * -- but it fails AFTER authenticating, and the status line for the authentication is what this reads. A
 * 200 here means the credentials were accepted.
 */
object PrismServerClient {

    private const val TAG = "PrismServerClient"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    /** What happened, in a form a UI can act on rather than a boolean. */
    sealed interface Result {
        val message: String

        data class Connected(override val message: String) : Result
        data class BadCredentials(override val message: String) : Result
        data class Unreachable(override val message: String) : Result
    }

    /**
     * Tries a server without changing anything.
     *
     * Separate from [connect] so an edit dialog can offer a test button, and so [connect] has one place
     * that decides what counts as success.
     */
    fun probe(server: PrismSettings.PrismServer): Result {
        if (server.address.isBlank()) return Result.Unreachable("No address.")
        if (server.port !in 1..65535) return Result.Unreachable("Port " + server.port + " is not a port.")

        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(server.address, server.port), CONNECT_TIMEOUT_MS)

                val request = buildString {
                    append("CONNECT 127.0.0.1:1 HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:1\r\n")
                    if (server.username.isNotBlank() || server.password.isNotBlank()) {
                        val token = Base64.getEncoder()
                            .encodeToString((server.username + ":" + server.password).toByteArray())
                        append("Proxy-Authorization: Basic ").append(token).append("\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().apply { write(request.toByteArray()); flush() }

                val status = BufferedReader(InputStreamReader(socket.getInputStream()))
                    .readLine()
                    .orEmpty()

                when {
                    status.isBlank() ->
                        Result.Unreachable(
                            server.address + ":" + server.port + " accepted the connection and said " +
                                "nothing. That is a listener, but not a Prism server."
                        )

                    status.contains(" 407") ->
                        Result.BadCredentials(
                            "The server answered, so the address and port are right -- it refused the " +
                                "username and password."
                        )

                    status.contains(" 200") ->
                        Result.Connected("Connected to " + server.name.ifBlank { server.address } + ".")

                    else ->
                        Result.Unreachable("The server answered with: " + status.trim())
                }
            }
        }.getOrElse { failure ->
            Result.Unreachable(
                "Could not reach " + server.address + ":" + server.port + " -- " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        }
    }

    /**
     * Connects, and on success makes this the active server.
     *
     * EXACTLY ONE SERVER IS ACTIVE. The flag is cleared on every other entry in the same write, because
     * two active servers is a state nothing downstream knows how to read and the list would show two
     * green rows.
     *
     * A FAILURE CHANGES NOTHING AT ALL -- not the flag, not the previously active server. Somebody
     * trying a server that turns out to be down should end up where they started, still connected to
     * whatever they were connected to, rather than disconnected from everything.
     */
    fun connect(server: PrismSettings.PrismServer): Result {
        val result = probe(server)
        if (result !is Result.Connected) {
            PrismPlatform.log.warn(TAG, "Not activating " + server.name + ": " + result.message)
            return result
        }

        val updated = PrismSettings.getPrismServers().map {
            it.copy(isActive = it.id == server.id)
        }
        PrismSettings.setPrismServers(updated)

        // The mesh reads its bootstrap address from settings, so the active server IS the bootstrap. A
        // server that was connected to but never became the bootstrap would work until the next restart
        // and then quietly stop.
        PrismSettings.setMeshBootstrapAddress(server.address)
        PrismPlatform.log.info(TAG, "Active server is now " + server.name + " (" + server.address + ")")
        return result
    }

    /** The active server, or null. */
    fun active(): PrismSettings.PrismServer? =
        PrismSettings.getPrismServers().firstOrNull { it.isActive }

    /** Adds or replaces a server by id, leaving the active flag alone. */
    fun save(server: PrismSettings.PrismServer) {
        val existing = PrismSettings.getPrismServers()
        val updated = if (existing.any { it.id == server.id }) {
            existing.map { if (it.id == server.id) server.copy(isActive = it.isActive) else it }
        } else {
            existing + server
        }
        PrismSettings.setPrismServers(updated)
    }

    /**
     * Removes a server.
     *
     * Removing the ACTIVE one leaves nothing active rather than promoting another, because promoting one
     * would connect this device to a server the user did not choose.
     */
    fun delete(id: String) {
        PrismSettings.setPrismServers(PrismSettings.getPrismServers().filterNot { it.id == id })
    }
}
