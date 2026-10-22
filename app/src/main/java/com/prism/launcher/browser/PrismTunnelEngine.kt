package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import com.prism.launcher.PrismSettings
import com.prism.launcher.vpn.AppUsageMonitor
import com.prism.launcher.vpn.PrismProxyClient
import com.prism.launcher.vpn.PrismProxyServer
import com.prism.launcher.vpn.PrismSocket
import com.prism.launcher.vpn.VpnMultiplexer
import com.prism.launcher.vpn.WireguardController
import com.prism.core.MeshUtils
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * High-level router that acts as the backbone for VPN tunneling when enabled.
 * Takes the raw traffic captured by the VpnService (that isn't blocked by DNS/sink routes)
 * and pipes it into the corresponding P2P or External VPN protocol.
 */
class PrismTunnelEngine(private val context: Context) {

    private var routingActive: Boolean = false
    private var currentMode: String? = null
    private var currentRole: String? = null
    
    private var proxyServer: PrismProxyServer? = null
    private var hostingServer: PrismProxyServer? = null
    private var vpnMultiplexer: VpnMultiplexer? = null

    // Persistent mesh fetcher: custom DNS for P2P resolution + universal SSL for any TLD
    private val httpClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val noSniSslFactory = object : SSLSocketFactory() {
            private val delegate: SSLSocketFactory = sslContext.socketFactory

            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                return (delegate.createSocket(s, host, port, autoClose) as SSLSocket).apply {
                    suppressSni()
                }
            }

            override fun createSocket(host: String?, port: Int): Socket =
                (delegate.createSocket(host, port) as SSLSocket).apply { suppressSni() }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                (delegate.createSocket(host, port, localHost, localPort) as SSLSocket).apply { suppressSni() }

            override fun createSocket(host: InetAddress?, port: Int): Socket =
                (delegate.createSocket(host, port) as SSLSocket).apply { suppressSni() }

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                (delegate.createSocket(address, port, localAddress, localPort) as SSLSocket).apply { suppressSni() }

            private fun SSLSocket.suppressSni() {
                try {
                    sslParameters = sslParameters.also { it.serverNames = emptyList() }
                } catch (_: Throwable) { }
            }
        }

        OkHttpClient.Builder()
            .proxy(null) 
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val p2pIp = P2pDnsManager.resolve(hostname, onlyP2p = true)
                    if (p2pIp != null) {
                        // CRITICAL: Preserve the hostname string when creating the InetAddress.
                        // This ensures InetSocketAddress.hostName is populated, which PrismSocket needs.
                        val rawAddr = InetAddress.getByName(p2pIp)
                        return listOf(InetAddress.getByAddress(hostname, rawAddr.address))
                    }

                    return try {
                        val systemResult = Dns.SYSTEM.lookup(hostname)
                        if (systemResult.isNotEmpty()) {
                            val ip = systemResult[0].hostAddress
                            if (ip != null) {
                                P2pDnsManager.seedFromGlobal(context, hostname, ip)
                            }
                        }
                        systemResult
                    } catch (e: Exception) {
                        throw e
                    }
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val originalHost = request.url.host
                if (originalHost.endsWith(".remote", ignoreCase = true)) {
                    val cleanHost = originalHost.removeSuffix(".remote")
                    val newUrl = request.url.newBuilder().host(cleanHost).build()
                    val newRequest = request.newBuilder()
                        .url(newUrl)
                        .header("Host", cleanHost)
                        .build()
                    chain.proceed(newRequest)
                } else {
                    chain.proceed(request)
                }
            }
            .sslSocketFactory(noSniSslFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .socketFactory(object : SocketFactory() {
                private fun meshSocket(): PrismSocket = PrismSocket().also {
                    PrivateDnsVpnService.protectSocket(it)
                }
                override fun createSocket(): Socket = meshSocket()
                
                override fun createSocket(host: String, port: Int): Socket {
                    return meshSocket().apply {
                        setHostHint(host)
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    return meshSocket().apply {
                        setHostHint(host)
                        bind(InetSocketAddress(localHost, localPort))
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(host: InetAddress, port: Int): Socket {
                    return meshSocket().apply {
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    return meshSocket().apply {
                        bind(InetSocketAddress(localAddress, localPort))
                        connect(InetSocketAddress(address, port))
                    }
                }
            })
            .build()
    }

    /**
     * Brings the backbone up for whatever the settings currently say, reconfiguring if they have
     * changed since the last call.
     *
     * THE GUARD USED TO BE `if (routingActive) return`, AND IT MADE THE ENGINE A ONE-SHOT. This runs
     * from `PrismApp.onCreate`, so it fires once per process before the user has touched anything.
     * With tunnelling off at that moment it set `routingActive = true` and returned BEFORE recording
     * a mode -- and every later call, including the one the VPN service makes when the user finally
     * switches tunnelling on, hit the guard and returned immediately. The result was an engine that
     * reported itself active while holding no configuration at all: no proxy, no hosting listener,
     * no client proxy override, until the app was restarted. Changing role or mode at runtime was
     * equally inert for the same reason.
     *
     * So the question is no longer "has start() ever run" but "is the live configuration the one
     * that is running". Same-configuration calls are still cheap no-ops, which is what the original
     * guard was there for.
     */
    fun start() {
        if (!PrismSettings.getVpnTunnelingEnabled()) {
            // Say so honestly rather than sitting on routingActive = true. A flag claiming we route
            // while nothing is configured is what hid the bug above, and anything asking this
            // engine whether the tunnel is up deserves a truthful answer.
            if (routingActive) pauseTunnel()
            routingActive = false
            currentMode = null
            currentRole = null
            Log.d("PrismTunnel", "Tunneling disabled in settings; Backbone idling.")
            return
        }

        val mode = PrismSettings.getVpnMode()
        val role = PrismSettings.getPrismVpnRole()

        // Already running exactly this.
        if (routingActive && mode == currentMode && role == currentRole) return

        // Running something else: tear that down first, so switching role does not leave the old
        // role's listeners bound to their ports.
        if (routingActive) pauseTunnel()

        routingActive = true
        currentMode = mode
        currentRole = role

        com.prism.launcher.PrismLogger.logInfo("PrismTunnel", "Starting Mesh Backbone (Mode: $currentMode, Role: $currentRole)")
        
        // ALWAYS start the Local Hosting Listener regardless of Server/Client role.
        // This ensures the device can always serve its own .p2p websites locally.
        startHostingServer()

        when (currentMode) {
            PrismSettings.VPN_MODE_PRISM -> {
                if (currentRole == PrismSettings.PRISM_ROLE_SERVER) {
                    startP2pServerMode()
                } else {
                    startP2pClientMode()
                }
            }
            PrismSettings.VPN_MODE_EXTERNAL -> {
                startExternalVpnTunnel()
            }
        }
        
        AppUsageMonitor.start(context, this)
    }

    fun stop() {
        routingActive = false
        AppUsageMonitor.stop()
        hostingServer?.stop()
        hostingServer = null
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        PrismProxyClient.stop()
        WireguardController.stop()
    }

    fun pauseTunnel() {
        routingActive = false
        hostingServer?.stop()
        hostingServer = null
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        PrismProxyClient.stop()
        WireguardController.stop()
    }

    /**
     * Rebuilds the backbone after [pauseTunnel].
     *
     * DELEGATED TO [start] rather than re-dispatching on the remembered mode, which fixes two things
     * at once. A resume after the user had changed role or mode restored the OLD configuration,
     * because it read this engine's fields instead of the settings. And it never rebuilt the local
     * hosting listener for a client -- only the server path did that -- so a client that paused once
     * lost port 8080 for the rest of the process, and with it every peer request for a hosted or
     * cached site.
     *
     * The flag is cleared first so [start]'s same-configuration guard does not mistake a torn-down
     * engine for a running one and return without rebuilding anything.
     */
    fun resumeTunnel() {
        routingActive = false
        start()
    }

    /**
     * Whether the Prism P2P VPN is actually carrying traffic right now.
     *
     * ROLE-AGNOSTIC BY CONSTRUCTION: [start] sets [routingActive] for both branches of the
     * server/client split, so a device serving the tunnel and a device connected to somebody
     * else's both answer true. Anything gated on "is the user on the Prism VPN" wants exactly that
     * and should not have to ask which end it is.
     *
     * MODE AND ROLE ARE READ FROM SETTINGS, NOT FROM THIS ENGINE'S FIELDS. They are the user's
     * configuration and are always current, whereas [currentMode] is only as fresh as the last
     * [start] -- and this question gets asked from the settings screen, which is exactly where the
     * user is in the middle of changing them.
     *
     * Liveness is either the VPN service running or this engine routing, because both are real and
     * neither covers the other: a private-browsing tunnel or a persistent server brings the service
     * up, while a server node whose listeners were started at boot is serving peers whether or not
     * the VpnService itself was ever established.
     */
    fun isPrismVpnActive(): Boolean {
        if (!PrismSettings.getVpnTunnelingEnabled()) return false
        if (PrismSettings.getVpnMode() != PrismSettings.VPN_MODE_PRISM) return false

        val live = routingActive ||
            runCatching { PrivateDnsVpnService.isRunning() }.getOrDefault(false)
        if (!live) return false

        // A server IS the VPN once its listeners are up; a client is only on one if it has a server
        // to reach. Without that check a client that has never been given an address would report
        // itself connected, because PrismProxyClient.start() returns quietly when the list is empty.
        return PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER ||
            PrismSettings.getPrismServers().isNotEmpty()
    }

    fun routeOutboundPacket(packet: ByteArray): ByteArray? {
        if (!routingActive) return null
        
        if (packet.size >= 20 && (packet[0].toInt() ushr 4) == 4) {
            val d1 = packet[16].toInt() and 0xFF
            val d2 = packet[17].toInt() and 0xFF
            val d3 = packet[18].toInt() and 0xFF
            
            if (d1 == 10 && d2 == 8 && d3 == 0) {
                val lastOctet = packet[19].toInt() and 0xFF
                val peerIp = "10.8.0.$lastOctet"
                vpnMultiplexer?.wgServerEngine?.encapsulateAndSend(packet, peerIp)
                return null 
            }
        }
        
        return null
    }

    private fun startHostingServer() {
        hostingServer?.stop()
        hostingServer = PrismProxyServer(8080, "PrismHost", isProxyMode = false)
        hostingServer?.start()
    }

    private fun startP2pServerMode() {
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        
        try { Thread.sleep(200) } catch(e: InterruptedException) { }

        var vpnPortStr = PrismSettings.getPrismVpnPort()
        if (vpnPortStr == "8080" || vpnPortStr == "8081" || vpnPortStr == "") {
            val newPort = MeshUtils.findAvailablePort().toString()
            PrismSettings.setPrismVpnPort(newPort)
            vpnPortStr = newPort
        }
        
        val port = vpnPortStr.toIntOrNull() ?: MeshUtils.findAvailablePort()
        val user = PrismSettings.getPrismVpnUsername()
        val pass = PrismSettings.getPrismVpnPassword()
        val protoMode = PrismSettings.getVpnProtocolMode()
        
        // THROUGH startHostingServer, WHICH STOPS THE OLD ONE FIRST. This used to assign a new
        // PrismProxyServer straight over `hostingServer`, which leaked the previous listener: it
        // kept port 8080 bound while the only reference to it was overwritten, so nothing could
        // ever close it. The replacement then failed to bind with "address already in use", and no
        // amount of SO_REUSEADDR helps -- that option lets a socket rebind a port left in
        // TIME_WAIT, not share one with a listener that is still very much alive. With the host
        // listener down, every mesh request into 8080 -- model transfers and peer inference alike
        // -- had nothing to answer it.
        startHostingServer()

        if (protoMode == PrismSettings.VPN_PROTOCOL_AUTO || protoMode == PrismSettings.VPN_PROTOCOL_PROXY) {
            proxyServer = PrismProxyServer(port, "PrismVPN", isProxyMode = true)
            proxyServer?.setCredentials(user, pass)
            proxyServer?.start()
        }

        if (protoMode != PrismSettings.VPN_PROTOCOL_PROXY) {
            val udpPort = try { (PrismSettings.getMeshBootstrapPort()).toInt() } catch(e: Exception) { 8081 }
            vpnMultiplexer = com.prism.launcher.vpn.VpnMultiplexer(context, udpPort, pass, user, pass)
            vpnMultiplexer?.start()
        }
    }

    private fun startP2pClientMode() {
        PrismProxyClient.start(context)
    }

    private fun startExternalVpnTunnel() {
        WireguardController.init(context)
        val confData = PrismSettings.getExternalVpnProfile()
        if (confData.isNotEmpty()) {
            WireguardController.loadProfile(ByteArrayInputStream(confData.toByteArray()))
            WireguardController.start()
        }
    }

    fun fetchMeshContent(url: String, connectTimeoutMs: Long? = null, readTimeoutMs: Long? = null): Response? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return null

        val request = Request.Builder()
            .url(url)
            .header("Host", host) 
            .build()
        
        val callClient = if (connectTimeoutMs != null || readTimeoutMs != null) {
            httpClient.newBuilder()
                .connectTimeout(connectTimeoutMs ?: 8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(readTimeoutMs ?: 10, java.util.concurrent.TimeUnit.MILLISECONDS) // Oops, careful with units
                .build()
        } else {
            httpClient
        }

        return try {
            // Using a separate client builder occasionally is fine for specific overrides
            val actualClient = if (connectTimeoutMs != null || readTimeoutMs != null) {
                 httpClient.newBuilder()
                    .connectTimeout(connectTimeoutMs ?: 8000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs ?: 10000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            } else {
                httpClient
            }
            actualClient.newCall(request).execute()
        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logError("PrismTunnel", "Mesh fetch failed for $url", e)
            null
        }
    }
}
