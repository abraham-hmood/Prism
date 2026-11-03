package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import com.prism.launcher.PrismSettings
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

    /**
     * Whether a client's saved fleet actually answered during this run.
     *
     * A LIST IN SETTINGS IS NOT A CONNECTION. [startP2pClientMode] used to leave only
     * `routingActive = true` behind, so a device with one saved server that was switched off still
     * reported itself on the meshnet. Email then addressed mesh mail and every mesh feature
     * prepared for peers that did not exist. This is set by a real handshake, on a background
     * thread, because [com.prism.launcher.vpn.PrismServerClient.probe] can block for six seconds.
     */
    @Volatile
    private var clientServerReachable: Boolean = false

    /** Invalidates an in-flight probe when the tunnel is torn down or reconfigured. */
    @Volatile
    private var clientProbeGeneration: Int = 0
    
    private var proxyServer: PrismProxyServer? = null
    private var hostingServer: PrismProxyServer? = null
    private var vpnMultiplexer: VpnMultiplexer? = null

    // Persistent mesh fetcher: custom DNS for P2P resolution + universal SSL for any TLD
    private val httpClient: OkHttpClient by lazy {
        // Chain validation plus a per-host pin, instead of the previous empty trust manager.
        // "Trust every certificate" was not a simplification; it disabled the only check that
        // makes the mesh resistant to a peer answering for somebody else's name.
        val meshTrust = com.prism.launcher.vpn.MeshTrustStore.X509

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(meshTrust), java.security.SecureRandom())

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
                } catch (error: Throwable) {
                    // The connection still works, but the host name is now in the handshake in
                    // clear text. Logged because that is a privacy property the tunnel claims,
                    // not an implementation detail -- and a silent catch made the claim untrue
                    // on any platform whose SSLParameters refused the write.
                    com.prism.launcher.PrismLogger.logWarning(
                        "PrismTunnel",
                        "Could not suppress SNI; the host name will be visible to the network: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
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
                    // KEEP THE .remote HOST IN THE URL. Rewriting it here meant OkHttp's DNS lookup
                    // saw the clean name, found this device's own loopback mirror first, and a
                    // "fetch from a peer" request was answered by the device asking. The suffix
                    // is what makes P2pDnsManager.resolve exclude local candidates; the Host
                    // header carries the real site name to the peer.
                    val cleanHost = originalHost.removeSuffix(".remote")
                    chain.proceed(request.newBuilder().header("Host", cleanHost).build())
                } else {
                    chain.proceed(request)
                }
            }
            .sslSocketFactory(noSniSslFactory, meshTrust)
            .hostnameVerifier(com.prism.launcher.vpn.MeshTrustStore.Hostnames)
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
        
        // NO FOREGROUND-APP MONITOR. It used to pause this whole engine whenever an app on the
        // user's bypass list came to the front, which is the opposite of a per-app bypass: the
        // whitelisted app kept its traffic AND everything else lost the tunnel. The list is applied
        // where it belongs, with `addDisallowedApplication` when the VpnService is built
        // (PrivateDnsVpnService.applyAppWhitelist), and that needs no monitoring at all.
    }

    fun stop() {
        routingActive = false
        clientProbeGeneration++
        clientServerReachable = false
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
        clientProbeGeneration++
        clientServerReachable = false
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
     * ROLE-AWARE, BECAUSE THE TWO ENDS HAVE DIFFERENT PROOF. A server is live when a listener is
     * actually bound ([isPrismServerRunning]); a client is live when the VPN service has established
     * its TUN interface and it has a server to reach. [routingActive] is deliberately not enough:
     * it only means [start] ran with the current settings, which is also true for a client whose
     * saved fleet is unreachable.
     *
     * MODE AND ROLE ARE READ FROM SETTINGS, NOT FROM THIS ENGINE'S FIELDS. They are the user's
     * configuration and are always current, whereas [currentMode] is only as fresh as the last
     * [start] -- and this question gets asked from the settings screen, which is exactly where the
     * user is in the middle of changing them.
     */
    fun isPrismVpnActive(): Boolean {
        if (!PrismSettings.getVpnTunnelingEnabled()) return false
        if (PrismSettings.getVpnMode() != PrismSettings.VPN_MODE_PRISM) return false

        // A BOUND LISTENER IS NOT A CONNECTION. `routingActive` only says this engine was started
        // with the current settings; a client with a saved fleet that it cannot reach sets it too.
        // Treating that as membership made email choose mesh ports and advertise the mesh domain on
        // a device that was connected to nothing.
        val role = PrismSettings.getPrismVpnRole()
        if (role == PrismSettings.PRISM_ROLE_SERVER) return isPrismServerRunning()

        val live = runCatching { PrivateDnsVpnService.isRunning() }.getOrDefault(false)
        if (!live) return false
        // AND THE FLEET MUST HAVE ANSWERED. An entry in the server list is a wish, not a link:
        // [startP2pClientMode] probes every saved server once and only a successful CONNECT
        // handshake turns this true. A client whose server is off therefore reports off the mesh,
        // which is what "I am not connected to one" has to mean.
        return clientServerReachable
    }

    /** True only while a Prism server listener or VPN server socket is actually bound. */
    fun isPrismServerRunning(): Boolean =
        (PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM &&
            PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER) &&
            (proxyServer?.isRunning() == true || vpnMultiplexer?.isRunning() == true)

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
            val localServer = PrismProxyServer(port, "PrismVPN", isProxyMode = true)
            proxyServer = localServer
            localServer.setCredentials(user, pass)
            localServer.start()
            // THE SERVER IS NOW LISTENING, SO THE DIRECTORIES ARE TOLD. announceToDirectories
            // publishes the public address only after proving the port is forwarded; when it is
            // not, the user is told once, in a dialog, while the server keeps running.
            announceToDirectories()
            Thread({
                if (localServer.awaitListening() && routingActive && proxyServer === localServer) {
                    val boundPort = localServer.listeningPort()
                    if (boundPort != null) {
                        PrismProxyClient.connectToLocalServer(context, boundPort)
                        com.prism.launcher.PrismLogger.logSuccess(
                            "PrismTunnel",
                            "This device's local browser proxy is connected to its own Prism server at 127.0.0.1:$boundPort.",
                        )
                        return@Thread
                    }
                }
                com.prism.launcher.PrismLogger.logError(
                    "PrismTunnel",
                    "Could not connect this device to its own Prism proxy server: the local listener did not become ready.",
                )
            }, "prism-local-server-client").apply { isDaemon = true; start() }
        }

        if (protoMode != PrismSettings.VPN_PROTOCOL_PROXY) {
            val udpPort = try { (PrismSettings.getMeshBootstrapPort()).toInt() } catch(e: Exception) { 8081 }
            vpnMultiplexer = com.prism.launcher.vpn.VpnMultiplexer(context, udpPort, pass, user, pass)
            vpnMultiplexer?.start()
        }
    }

    /** Discovery runs once per engine, not once per reconnect. */
    @Volatile
    private var directorySynced = false

    /**
     * Tells the directories where this server is, once it is actually listening.
     *
     * IT WAITS FOR THE LISTENER. The check a directory depends on is "can the world reach this
     * port", and running it before the socket is bound would report a closed port on a server that
     * is about to work. [ServerDirectory.announceOnStart] probes the public address and publishes
     * only when the probe succeeds, which is the one failure worth a dialog: the server runs fine,
     * but nobody outside this network will find it.
     */
    private fun announceToDirectories() {
        if (PrismSettings.getPrismVpnRole() != PrismSettings.PRISM_ROLE_SERVER) return
        Thread({
            val local = proxyServer ?: return@Thread
            if (!local.awaitListening()) return@Thread
            val port = local.listeningPort() ?: PrismSettings.getPrismVpnPort().toIntOrNull() ?: 0
            val announcement = runCatching {
                com.prism.launcher.vpn.ServerDirectory.announceOnStart(port)
            }.getOrNull() ?: return@Thread
            // SHOWN WHEN THE PROBE FAILS OR A DIRECTORY REFUSED THE PUBLICATION. A phone whose
            // public port answers while the directory returns 401 used to say nothing at all, which
            // is the one case where the user is waiting for a row that will never appear.
            val failed = !announcement.reachable || announcement.reports.any { !it.ok }
            if (failed && announcement.message.isNotBlank()) {
                runCatching { com.prism.launcher.PrismApp.instance }
                    .getOrNull()
                    ?.presentDirectoryNotice(announcement.message)
            }
        }, "prism-directory-announce").apply { isDaemon = true; start() }
    }

    private fun startP2pClientMode() {
        PrismProxyClient.start(context)
        // DISCOVERY WITHOUT A BUTTON. A client, bridge or relay that trusts a directory should end
        // up with the servers published there on its own; the Server directory screen's button stays
        // for a manual refresh. Once per engine, because a reconnect is not a reason to re-ask every
        // directory for the same list.
        if (!directorySynced) {
            directorySynced = true
            Thread({
                runCatching { com.prism.launcher.vpn.ServerDirectory.sync() }
            }, "prism-directory-sync").apply { isDaemon = true; start() }
        }
        // A bridge's job starts where the tunnel does: ask every selected server for its names and
        // listings. Off the main thread, because a fleet can contain a server that is simply down.
        if (PrismSettings.isPrismBridgeRole()) {
            com.prism.launcher.vpn.FleetBridge.refreshAsync()
        }

        // PROVE THE CLIENT BEFORE CALLING THIS DEVICE A MEMBER. Probe every saved server, not just
        // the active one: [PrismProxyClient] installs fallbacks, so a reachable second server is a
        // working tunnel even when the first is down. The probe is off the main thread and guarded
        // by a generation so a reply from an old configuration cannot turn the new one green.
        clientServerReachable = false
        val generation = ++clientProbeGeneration
        val servers = PrismSettings.getPrismServers()
        if (servers.isEmpty()) {
            com.prism.launcher.PrismLogger.logWarning(
                "PrismTunnel",
                "Prism client mode has no server in the fleet; the device is not on a meshnet.",
            )
            return
        }
        Thread({
            val reachable = servers.any { server ->
                com.prism.launcher.vpn.PrismServerClient.probe(server) is
                    com.prism.launcher.vpn.PrismServerClient.Result.Connected
            }
            if (generation != clientProbeGeneration) return@Thread
            clientServerReachable = reachable
            if (reachable) {
                com.prism.launcher.PrismLogger.logInfo(
                    "PrismTunnel",
                    "A Prism server answered; this device is a mesh client.",
                )
            } else {
                com.prism.launcher.PrismLogger.logWarning(
                    "PrismTunnel",
                    "None of the saved Prism servers answered. The device is not on a meshnet.",
                )
            }
        }, "prism-client-probe").apply { isDaemon = true; start() }
    }

    private fun startExternalVpnTunnel() {
        WireguardController.init(context)
        val confData = PrismSettings.getExternalVpnProfile()
        if (confData.isEmpty()) {
            com.prism.launcher.PrismLogger.logError(
                "PrismTunnel",
                "External VPN mode is selected but no WireGuard profile has been pasted in Settings.",
            )
            return
        }
        // A refusal used to be invisible: the tunnel simply was not up while the UI said
        // otherwise. Say which of the two steps failed, because they need different fixes.
        if (!WireguardController.loadProfile(ByteArrayInputStream(confData.toByteArray()))) {
            com.prism.launcher.PrismLogger.logError(
                "PrismTunnel",
                "The saved WireGuard profile is not valid .conf text.",
            )
            return
        }
        if (!WireguardController.start()) {
            com.prism.launcher.PrismLogger.logError(
                "PrismTunnel",
                "Android refused to bring the WireGuard tunnel up. Usually another VPN is already " +
                    "active, or the profile has no interface address.",
            )
        }
    }

    fun fetchMeshContent(url: String, connectTimeoutMs: Long? = null, readTimeoutMs: Long? = null): Response? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return null

        val request = Request.Builder()
            .url(url)
            // `.remote` is a resolution hint, not part of the site name the peer serves.
            .header("Host", host.removeSuffix(".remote"))
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
