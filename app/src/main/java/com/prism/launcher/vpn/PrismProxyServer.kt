package com.prism.launcher.vpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import com.prism.launcher.browser.P2pDnsManager
import com.prism.launcher.browser.PrismWebHost
import java.nio.channels.ServerSocketChannel
import java.net.StandardSocketOptions
import java.net.InetSocketAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.prism.launcher.PrismApp
import com.prism.core.MeshUtils
import com.prism.launcher.PrismLogger

class PrismProxyServer(
    private var port: Int, 
    private val serverName: String = "PrismProxy",
    private val isProxyMode: Boolean = true
) {
    private var serverChannel: ServerSocketChannel? = null
    private val proxyScope = CoroutineScope(
        Dispatchers.IO + Job() + com.prism.launcher.PrismLogger.coroutineHandler("ProxyServer")
    )
    private val startMutex = Mutex()
    
    private var authHeaderExpected: String? = null

    fun setCredentials(username: String, pass: String) {
        if (pass.isEmpty() && username.isEmpty()) {
            authHeaderExpected = null
            return
        }
        val encoded = android.util.Base64.encodeToString(
            "${username}:${pass}".toByteArray(),
            android.util.Base64.NO_WRAP
        )
        authHeaderExpected = "Proxy-Authorization: Basic $encoded"
    }

    fun start() {
        proxyScope.launch {
            startMutex.withLock {
                if (serverChannel?.isOpen == true) return@launch
                
                var attempts = 0
                val maxAttempts = 3
                
                while (attempts < maxAttempts && isActive) {
                    var channel: ServerSocketChannel? = null
                    try {
                        channel = ServerSocketChannel.open()
                        channel.configureBlocking(true)
                        
                        runCatching { channel.setOption(StandardSocketOptions.SO_REUSEADDR, true) }
                        runCatching { 
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                channel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
                            }
                        }
                        
                        // --- VPN PROTECTION ---
                        // We must "protect" the server socket so it can receive connections 
                        // from the mesh. However, if it's the Hosting listener, we only protect 
                        // if we want it strictly off-tunnel.
                        if (isProxyMode) {
                            com.prism.launcher.browser.PrivateDnsVpnService.protectSocket(channel.socket())
                        }
                        
                        // PORT PROTECTION: Only the general VPN Proxy should "jump" ports.
                        // The Hosting listener MUST stay on its assigned port (8080) to be reachable.
                        while (isProxyMode && (port == 8080 || port == 8081)) {
                            port = MeshUtils.findAvailablePort()
                        }
                        
                        channel.socket().bind(InetSocketAddress("0.0.0.0", port))
                        serverChannel = channel
                        
                        val roleName = if (isProxyMode) "VPN Proxy Engine" else "Mesh Hosting Listener"
                        val localAddr = channel.socket().localSocketAddress
                        com.prism.launcher.PrismLogger.logSuccess(serverName, "$roleName online at $localAddr ($port)")
                        break 
                    } catch (e: Exception) {
                        runCatching { channel?.close() }
                        attempts++
                        if (attempts >= maxAttempts) {
                            com.prism.launcher.PrismLogger.logError(serverName, "FATAL: Failed to start $serverName on port $port: ${e.message}", e)
                        } else {
                            com.prism.launcher.PrismLogger.logWarning(serverName, "Port $port busy. Retrying in 1s...")
                            delay(1000)
                        }
                    }
                }
            }

            serverChannel?.let { channel ->
                while (isActive && channel.isOpen) {
                    try {
                        val client = channel.socket().accept() ?: break
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        PrismLogger.logError(serverName, "Accept loop error on port $port", e)
                        if (isActive) delay(500) // Back off slightly on repeated errors
                    }
                }
            }
        }
    }

    fun stop() {
        proxyScope.cancel()
        runCatching { serverChannel?.close() }
        serverChannel = null
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val firstLine = readLine(input) ?: return@withContext

            // 1. Mesh Node Handshake (PRISM_CONNECT)
            if (firstLine.startsWith("PRISM_CONNECT")) {
                if (isProxyMode) {
                    com.prism.launcher.PrismLogger.logWarning(serverName, "Blocking Mesh connection on Proxy port.")
                    clientSocket.close()
                    return@withContext
                }

                val parts = firstLine.split(" ")
                if (parts.size >= 2) {
                    val domain = parts[1].trim()
                    output.write("PRISM_ACK\n".toByteArray())
                    output.flush()

                    val bis = java.io.PushbackInputStream(input, 5)
                    val buffer = ByteArray(5)
                    val read = bis.read(buffer)
                    
                    var finalSocket = clientSocket
                    if (read > 0) {
                        bis.unread(buffer, 0, read)
                        
                        // 0x16 is the TLS Handshake record type
                        if (buffer[0] == 0x16.toByte() || buffer[0] == 0x16.toLong().toByte()) {
                            try {
                                PrismLogger.logInfo(serverName, "Upgrading Mesh connection for $domain to dynamic SSL/TLS")
                                val sslContext = PrismSslManager.getSslContextForDomain(PrismApp.instance, domain)
                                
                                val peekingSocket = object : java.net.Socket() {
                                    override fun getInputStream(): java.io.InputStream = bis
                                    override fun getOutputStream(): java.io.OutputStream = clientSocket.getOutputStream()
                                    override fun close() = clientSocket.close()
                                    override fun getInetAddress() = clientSocket.inetAddress
                                    override fun getPort() = clientSocket.port
                                    override fun isConnected() = true
                                    override fun isBound() = true
                                }
                                
                                val sslSocket = sslContext.socketFactory.createSocket(peekingSocket, "localhost", clientSocket.port, true) as javax.net.ssl.SSLSocket
                                sslSocket.useClientMode = false
                                finalSocket = sslSocket
                            } catch (e: Exception) {
                                PrismLogger.logError(serverName, "SSL Upgrade failed for $domain. Dropping connection.", e)
                                clientSocket.close()
                                return@withContext
                            }
                        }
                    }
                    if (domain.equals(com.prism.launcher.mesh.P2pModelRegistry.MODEL_HOST_DOMAIN, ignoreCase = true)) {
                        PrismAiHost.serve(PrismApp.instance, finalSocket)
                    } else {
                        PrismWebHost.serve(PrismApp.instance, finalSocket, domain)
                    }
                }
                return@withContext
            }

            // 2. Standard Browser / Proxy Handling
            if (isProxyMode) {
                val header = if (firstLine.contains(" HTTP/")) firstLine else firstLine + "\n" + readHeader(input)
                if (authHeaderExpected != null) {
                    if (!header.contains(authHeaderExpected!!)) {
                        output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Prism\"\r\n\r\n".toByteArray())
                        output.flush()
                        clientSocket.close()
                        return@withContext
                    }
                }

                if (header.startsWith("CONNECT")) {
                    val parts = header.split(" ")
                    if (parts.size >= 2) {
                        val hostPort = parts[1].split(":")
                        if (hostPort.size == 2) {
                            connectToTarget(hostPort[0], hostPort[1].toIntOrNull() ?: 443, clientSocket, input, output)
                            return@withContext
                        }
                    }
                }
                if (header.contains("Host:", ignoreCase = true)) {
                    // Loopback hosting via standard browser GET + Host header
                    val fullHeader = header + "\n" + readHeader(input)
                    val hostLine = fullHeader.lines().find { it.startsWith("Host:", ignoreCase = true) }
                    val domain = hostLine?.substringAfter(":")?.trim()?.substringBefore(":") ?: ""
                    if (domain.isNotEmpty()) {
                        PrismWebHost.serve(PrismApp.instance, clientSocket, domain, preReadHeader = fullHeader)
                        return@withContext
                    }
                }
            }

            clientSocket.close()
        } catch (e: Exception) {
            PrismLogger.logError(serverName, "Client handling failed", e)
            runCatching { clientSocket.close() }
        }
    }
    
    private suspend fun connectToTarget(host: String, targetPort: Int, clientSocket: Socket, clientIn: InputStream, clientOut: OutputStream) = withContext(Dispatchers.IO) {
        var targetSocket: Socket? = null
        try {
            val resolvedIp = P2pDnsManager.resolve(host)
            
            // Aggressive Mesh Detection
            // If DNS resolved it, or it contains our peer naming pattern, it's Mesh.
            val isP2p = resolvedIp != null || host.contains("-s-") || host.endsWith(".p2p")
            
            val targetHost = resolvedIp ?: host
            val finalPort = if (isP2p) 8080 else targetPort // Mesh sites always on 8080 (Force override 443/80)
            
            // --- Local Loop Fix ---
            val myMeshIp = MeshUtils.getLocalMeshIp()
            val isLocal = targetHost == "127.0.0.1" || targetHost == "localhost" || targetHost == myMeshIp || targetHost == "::1"
            
            if (isP2p && isLocal) {
                com.prism.launcher.PrismLogger.logInfo("PrismProxy", "Domestic Mesh Request: Serving $host directly from local host.")
                PrismWebHost.serve(com.prism.launcher.PrismApp.instance, clientSocket, host)
                return@withContext
            }
            // ----------------------
            targetSocket = if (isP2p) com.prism.launcher.vpn.PrismSocket() else Socket()

            // Connect with timeout - if PrismSocket, this triggers the handshake automatically
            targetSocket.connect(InetSocketAddress(targetHost, finalPort), 10000)

            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()


            // Send connection established back to browser
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            clientOut.write(response.toByteArray())
            clientOut.flush()

            val job1 = launch { relay(clientIn, targetOut) }
            val job2 = launch { relay(targetIn, clientOut) }
            
            joinAll(job1, job2)
        } catch (e: Exception) {
            PrismLogger.logError("PrismProxy", "Failed to bridge to $host:$targetPort", e)
        } finally {
            runCatching { clientSocket.close() }
            runCatching { targetSocket?.close() }
        }
    }

    private fun readHeader(input: InputStream): String {
        val sb = java.lang.StringBuilder()
        var lastChar = 0
        while (true) {
            val c = input.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (lastChar == '\n'.code && c == '\r'.code) {
                input.read() // read trailing \n
                break
            }
            if (c != '\r'.code) lastChar = c
        }
        return sb.toString()
    }

    private fun relay(inStream: InputStream, outStream: OutputStream) {
        val buffer = ByteArray(32 * 1024) // Larger buffer for web traffic
        try {
            while (true) {
                val bytesRead = inStream.read(buffer)
                if (bytesRead <= 0) break
                outStream.write(buffer, 0, bytesRead)
                outStream.flush()
            }
        } catch (e: Exception) {
            // Ignored, socket closed
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        val line = sb.toString().trim()
        return if (line.isEmpty()) null else line
    }
}
