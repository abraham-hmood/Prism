package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.core.ProxyBasicAuth
import com.prism.launcher.PrismSettings
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Authenticated TCP CONNECT endpoint used by Prism Server Fleet clients. */
object DesktopPrismProxyServer {
    data class Client(val address: String, val connectedSince: Long)

    private const val TAG = "PrismProxy"
    private const val PROBE_TARGET = "127.0.0.1:1"
    private const val MAX_HEADER_BYTES = 16 * 1024
    private val clients = ConcurrentHashMap<Socket, Client>()

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    @Synchronized
    fun start(): Result {
        if (isRunning()) return Result(true, "Prism HTTP proxy is already listening on TCP ${listener?.localPort}.")
        val port = PrismSettings.getPrismVpnPort().toIntOrNull()
            ?: return failed("Prism HTTP proxy port is not a number.")
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
        }.getOrElse { return failed("Could not bind Prism HTTP proxy to TCP $port: ${it.message}") }
        listener = socket
        acceptThread = thread(name = "prism-http-proxy-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "prism-http-proxy-client", isDaemon = true) { serve(client) }
            }
        }
        val message = "Prism HTTP CONNECT proxy is listening on TCP $port."
        PrismPlatform.log.success(TAG, message)
        return Result(true, message)
    }

    fun isRunning(): Boolean = listener?.let { !it.isClosed && it.isBound } == true

    fun port(): Int? = listener?.takeIf { !it.isClosed }?.localPort

    fun connectedClients(): List<Client> = clients.values.sortedByDescending { it.connectedSince }

    fun kick(address: String): Int {
        val targets = clients.filterValues { it.address == address }.keys.toList()
        targets.forEach { runCatching { it.close() } }
        return targets.size
    }

    fun ban(address: String): Int {
        if (address.isBlank()) return 0
        val id = bannedAddressId(address)
        PrismSettings.setBannedPrismNodeIds(PrismSettings.getBannedPrismNodeIds() + id)
        return kick(address)
    }

    private fun bannedAddressId(address: String): String = "desktop-proxy-client:$address"

    @Synchronized
    fun stop(): Result {
        runCatching { listener?.close() }
        listener = null
        clients.keys.toList().forEach { runCatching { it.close() } }
        clients.clear()
        PrismPlatform.log.info(TAG, "Prism HTTP CONNECT proxy stopped.")
        return Result(true, "Prism HTTP proxy stopped.")
    }

    private fun serve(client: Socket) {
        val remote = client.inetAddress?.hostAddress ?: "unknown"
        runCatching {
            if (bannedAddressId(remote) in PrismSettings.getBannedPrismNodeIds()) {
                writeResponse(
                    BufferedOutputStream(client.getOutputStream()),
                    "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n",
                )
                return@runCatching
            }
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val head = readHead(input) ?: return@runCatching
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(Regex("\\s+"))
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeResponse(output, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
                return@runCatching
            }

            if (!ProxyBasicAuth.isAuthorized(
                    head,
                    PrismSettings.getPrismVpnUsername(),
                    PrismSettings.getPrismVpnPassword(),
                )
            ) {
                writeResponse(
                    output,
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"Prism\"\r\nConnection: close\r\n\r\n",
                )
                return@runCatching
            }

            val target = parts[1]
            if (target == PROBE_TARGET) {
                writeResponse(output, "HTTP/1.1 200 Connection Established\r\nConnection: close\r\n\r\n")
                return@runCatching
            }

            val split = target.lastIndexOf(':')
            if (split <= 0 || split == target.lastIndex) {
                writeResponse(output, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                return@runCatching
            }
            val host = target.substring(0, split).removePrefix("[").removeSuffix("]")
            val port = target.substring(split + 1).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: run {
                    writeResponse(output, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    return@runCatching
                }

            Socket().use { upstream ->
                upstream.connect(InetSocketAddress(host, port), 8_000)
                writeResponse(output, "HTTP/1.1 200 Connection Established\r\n\r\n")
                clients[client] = Client(remote, System.currentTimeMillis())
                client.soTimeout = 0
                upstream.soTimeout = 0
                val forward = thread(name = "prism-proxy-client-to-target", isDaemon = true) {
                    input.copyTo(upstream.getOutputStream())
                    runCatching { upstream.shutdownOutput() }
                }
                val backward = thread(name = "prism-proxy-target-to-client", isDaemon = true) {
                    upstream.getInputStream().copyTo(output)
                    runCatching { output.flush() }
                    runCatching { client.shutdownOutput() }
                }
                forward.join()
                backward.join()
            }
        }.onFailure { failure ->
            PrismPlatform.log.warn(TAG, "Proxy request from $remote failed: ${failure.message}")
            runCatching {
                if (!client.isClosed && !client.isOutputShutdown) {
                    writeResponse(
                        BufferedOutputStream(client.getOutputStream()),
                        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n",
                    )
                }
            }
        }
        clients.remove(client)
        runCatching { client.close() }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            matched = when {
                matched == 0 && next == 13 -> 1
                matched == 1 && next == 10 -> 2
                matched == 2 && next == 13 -> 3
                matched == 3 && next == 10 -> return bytes.toByteArray().toString(Charsets.ISO_8859_1)
                next == 13 -> 1
                else -> 0
            }
        }
        return null
    }

    private fun writeResponse(output: BufferedOutputStream, response: String) {
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun failed(message: String): Result {
        PrismPlatform.log.error(TAG, message)
        return Result(false, message)
    }

    data class Result(val ok: Boolean, val message: String)

}
