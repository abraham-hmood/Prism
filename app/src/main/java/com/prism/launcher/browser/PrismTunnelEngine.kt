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

    fun start() {
        if (routingActive) return
        routingActive = true
        
        if (!PrismSettings.getVpnTunnelingEnabled()) {
            Log.d("PrismTunnel", "Tunneling disabled in settings; Backbone idling.")
            return
        }
        
        currentMode = PrismSettings.getVpnMode()
        currentRole = PrismSettings.getPrismVpnRole()
        
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

    fun resumeTunnel() {
        if (!PrismSettings.getVpnTunnelingEnabled()) return
        routingActive = true
        when (currentMode) {
            PrismSettings.VPN_MODE_PRISM -> {
                if (currentRole == PrismSettings.PRISM_ROLE_SERVER) startP2pServerMode() else startP2pClientMode()
            }
            PrismSettings.VPN_MODE_EXTERNAL -> startExternalVpnTunnel()
        }
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
        
        hostingServer = PrismProxyServer(8080, "PrismHost", isProxyMode = false)
        hostingServer?.start()

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
