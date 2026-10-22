package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Bringing a WireGuard interface up on this machine, and being a server on it. PHASE 61.
 *
 * ## Why this drives wg-quick rather than speaking WireGuard itself
 *
 * The obvious-looking alternative is to implement the protocol over [TunDevice]: Prism already has
 * X25519 through :core, and the adapter is already there. It would also be a mistake. WireGuard is
 * Noise_IK with its own handshake state machine, cookie replies under load, a nonce window, timers for
 * rekeying and keepalive, and a two-key rotation on each end. A hand-rolled version that ALMOST works is
 * the worst possible outcome for a VPN, because it looks connected and its failures are cryptographic.
 *
 * wg-quick and wireguard-go are the reference implementations, already installed on machines whose users
 * want WireGuard, already privileged, and already know how to write the routing table. Prism's job is to
 * produce a correct configuration and hand it over -- which is exactly the division of labour the
 * Android side has, where `com.wireguard.android:tunnel` does the protocol and Prism does the config.
 *
 * ## The server half, which is what the phase is actually measured by
 *
 * "A WireGuard client connects to the desktop server" needs this machine to LISTEN. That is not a
 * different piece of software: a WireGuard interface with a ListenPort and a peer block IS a server. So
 * [serverConfig] writes one, [up] starts it, and a phone given [clientConfigForPeer]'s output connects
 * to it. No component here is a client or a server; the config decides.
 *
 * ## The keys
 *
 * The server's key pair comes from `PrismSettings`, which generates it once and keeps it -- the same
 * pair the Android side uses, so a peer configured against one Prism device stays configured. Each
 * peer's pair is generated fresh per invitation, and THE PEER'S PRIVATE KEY IS WRITTEN ONLY INTO THE
 * FILE HANDED TO THAT PEER. What this machine keeps is the peer's public key, in its own config. Nothing
 * here ever stores or transmits somebody else's private key.
 */
object WireGuardInterface {

    private const val TAG = "PrismWireGuard"

    /** The interface name. Short because Linux caps an interface name at fifteen characters. */
    const val INTERFACE = "prism0"

    /** The subnet handed out to peers, matching the mesh subnet the tunnel already routes. */
    private const val SERVER_ADDRESS = "10.8.0.1/24"
    private const val SELF_CLIENT_NAME = "Prism local self-client"
    private const val SELF_CLIENT_INTERFACE = "prism-client"
    private const val SELF_CLIENT_ALLOWED_IPS = "10.8.0.1/32, 10.8.0.254/32"

    data class Peer(val name: String, val publicKey: String, val address: String)

    data class ConnectedPeer(val peer: Peer, val latestHandshakeEpochSeconds: Long)

    data class Result(val ok: Boolean, val message: String)

    /** Where configs live. Under the data directory, not a temp folder: these hold key material. */
    fun directory(): File = File(PrismPlatform.host.dataDir(), "wireguard").apply { mkdirs() }

    fun configFile(): File = File(directory(), INTERFACE + ".conf")

    private fun selfClientConfigFile(): File = File(directory(), SELF_CLIENT_INTERFACE + ".conf")

    // ── Peers ──────────────────────────────────────────────────────────────

    /**
     * Peers this machine will accept, kept as one line each in the settings store.
     *
     * A LINE FORMAT RATHER THAN JSON, because a WireGuard key is base64 with no commas or tabs in it and
     * the whole record is three fields. Something that can be read and repaired in a text editor is
     * worth more here than a parser.
     */
    fun peers(): List<Peer> = PrismSettings.getWgPeers()
        .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size != 3) null else Peer(parts[0], parts[1], parts[2])
        }

    /** Configured server peers with a recent real WireGuard handshake. */
    fun connectedPeers(): List<ConnectedPeer> {
        val inspector = wgExecutable() ?: return emptyList()
        val output = run(listOf(inspector, "show", INTERFACE, "latest-handshakes")) ?: return emptyList()
        val cutoff = System.currentTimeMillis() / 1000L - 180L
        val handshakes = output.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val epoch = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (epoch <= cutoff) return@mapNotNull null
            fields[0] to epoch
        }.toMap()
        return peers().mapNotNull { peer ->
            handshakes[peer.publicKey]?.let { ConnectedPeer(peer, it) }
        }.filterNot { it.peer.name == SELF_CLIENT_NAME }
            .sortedByDescending { it.latestHandshakeEpochSeconds }
    }

    fun kickPeer(publicKey: String): Result = removePeer(publicKey, permanent = false)

    fun banPeer(publicKey: String): Result {
        if (publicKey.isBlank()) return failed("Peer public key is empty.")
        PrismSettings.setBannedWgPeerPublicKeys(PrismSettings.getBannedWgPeerPublicKeys() + publicKey)
        return removePeer(publicKey, permanent = true)
    }

    private fun removePeer(publicKey: String, permanent: Boolean): Result {
        val peer = peers().firstOrNull { it.publicKey == publicKey }
            ?: return if (permanent) Result(true, "Peer public key is permanently banned.")
            else Result(false, "Peer is no longer configured.")
        val wg = wgExecutable() ?: return failed("Could not find the WireGuard wg command.")
        val output = run(listOf(wg, "set", INTERFACE, "peer", publicKey, "remove"))
            ?: return failed("Could not remove the peer from the live WireGuard interface.")
        if (output.startsWith("ERROR:", true) || refused(output)) {
            return failed("Could not remove ${peer.name} from the live interface: " + output.trim())
        }
        forget(publicKey)
        val result = if (permanent) {
            "${peer.name} was disconnected and its public key banned."
        } else {
            "${peer.name} was disconnected."
        }
        PrismPlatform.log.warn(TAG, result)
        return Result(true, result)
    }

    /**
     * Creates a configuration for a new peer and remembers its public key.
     *
     * @return the config text to hand to that device, which contains ITS private key and nothing of
     *   this machine's except the public key it needs to recognise the server.
     */
    fun invite(
        name: String,
        endpointHost: String,
        allowedIps: String = PrismSettings.getWgAllowedIps().ifBlank { "0.0.0.0/0" },
    ): Pair<String, Peer> {
        val pair = com.prism.core.JdkWireGuardKeys.generate()
        val address = nextAddress()
        val peer = Peer(name.ifBlank { "peer" }.replace("\t", " "), pair.second, address)

        PrismSettings.setWgPeers(
            PrismSettings.getWgPeers() + listOf(peer.name + "\t" + peer.publicKey + "\t" + peer.address),
        )

        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + pair.first)
            appendLine("Address = " + address + "/32")
            // The tunnel's own resolver, so a connected device resolves mesh names as well as
            // ordinary ones. PHASE 60 is what answers on it.
            appendLine("DNS = " + TunnelRuntime.DNS_PRIMARY)
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + PrismSettings.getWgServerPublicKey())
            appendLine("Endpoint = " + endpointHost + ":" + PrismSettings.getWgServerPort())
            appendLine("AllowedIPs = " + allowedIps)
            appendLine("PersistentKeepalive = 25")
        }
        return config to peer
    }

    fun forget(publicKey: String) {
        PrismSettings.setWgPeers(
            PrismSettings.getWgPeers().filterNot { it.split("\t").getOrNull(1) == publicKey },
        )
    }

    /** The next free address in the peer subnet. Starts at .2, because .1 is this machine. */
    private fun nextAddress(): String {
        val taken = peers().mapNotNull { it.address.substringAfterLast('.').toIntOrNull() }.toSet()
        val host = (2..254).firstOrNull { it !in taken } ?: 254
        return "10.8.0." + host
    }

    // ── The configuration this machine runs ────────────────────────────────

    /**
     * Writes the server configuration.
     *
     * NO AllowedIPs WIDER THAN EACH PEER'S OWN ADDRESS. On the server side AllowedIPs is not a
     * permission to route -- it is the cryptographic routing table, the statement of which key is
     * allowed to send from which address. A peer entry with 0.0.0.0/0 on a server would let any one peer
     * impersonate every other, which is the classic WireGuard misconfiguration.
     */
    fun serverConfig(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = " + PrismSettings.getWgServerPrivateKey())
        appendLine("Address = " + SERVER_ADDRESS)
        appendLine("ListenPort = " + PrismSettings.getWgServerPort())
        peers().forEach { peer ->
            appendLine()
            appendLine("# " + peer.name)
            appendLine("[Peer]")
            appendLine("PublicKey = " + peer.publicKey)
            appendLine("AllowedIPs = " + peer.address + "/32")
        }
    }

    /**
     * Writes the config to disk with the narrowest permissions the platform allows.
     *
     * A WIREGUARD CONFIG IS A PRIVATE KEY IN A TEXT FILE. wg-quick itself refuses to be quiet about a
     * world-readable one and it is right to. On Linux the mode is set directly; on Windows the file
     * inherits the data directory's ACL, which is the user's own profile, and there is no portable JVM
     * call that narrows it further -- so the file is written where only that user can reach it rather
     * than pretending a chmod happened.
     */
    fun writeConfig(): File {
        val file = configFile()
        file.writeText(serverConfig())
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return file
    }

    // ── Up and down ────────────────────────────────────────────────────────

    @Volatile
    var running = false
        private set

    /** Checks the OS interface instead of trusting this process's cached flag. */
    fun isRunning(): Boolean = isInterfaceRunning(INTERFACE)

    fun isPrismServerRunning(): Boolean =
        isRunning() && DesktopPrismProxyServer.isRunning()

    private fun isInterfaceRunning(interfaceName: String): Boolean {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val inspector = if (windows) {
            val wireguard = VpnStack.wireGuardExecutable()?.let(::File)
            listOfNotNull(
                wireguard?.parentFile?.let { File(it, "wg.exe").takeIf(File::isFile)?.absolutePath },
                "wg.exe".takeIf { which(it) != null },
                "wg".takeIf { which(it) != null },
            ).firstOrNull()
        } else {
            "wg".takeIf { which(it) != null }
        } ?: return false

        return runCatching {
            val process = ProcessBuilder(inspector, "show", interfaceName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                output.lineSequence().any { it.trim().startsWith("interface:", ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun wgExecutable(): String? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (!windows) return which("wg")
        val wireguard = VpnStack.wireGuardExecutable()?.let(::File)
        return wireguard?.parentFile?.let { File(it, "wg.exe").takeIf(File::isFile)?.absolutePath }
            ?: which("wg.exe")
            ?: which("wg")
    }

    /**
     * Brings the interface up.
     *
     * THE CONFIG IS REWRITTEN EVERY TIME, because peers may have been added since the last run and a
     * stale file would silently refuse the new one's handshake -- a failure that looks like a network
     * problem on the phone and leaves nothing in a log here.
     */
    fun up(): Result {
        val report = VpnStack.wireGuard()
        if (!report.canConnect) {
            return failed(report.detail)
        }
        val file = runCatching { writeConfig() }.getOrElse {
            return failed("Could not write the WireGuard server config: " + (it.message ?: it::class.java.simpleName))
        }
        val command = upCommand(file) ?: return failed(report.detail)
        val output = run(command) ?: return failed("Could not run " + command.first() + ".")

        if (refused(output)) {
            return failed(
                command.first() + " was refused: bringing an interface up needs root or " +
                    "administrator rights. On Windows, start Prism as administrator; on Linux, run it " +
                    "with sudo or give it CAP_NET_ADMIN.",
            )
        }
        if (output.contains("error", true) || output.contains("Cannot find", true)) {
            return failed(command.first() + " failed: " + output.trim().lines().firstOrNull())
        }

        running = true
        PrismPlatform.log.info(
            TAG,
            "The " + INTERFACE + " interface is up on port " + PrismSettings.getWgServerPort() +
                " with " + peers().size + " peer(s).",
        )
        return Result(
            true,
            "The interface is up on port " + PrismSettings.getWgServerPort() + " with " +
                peers().size + " peer(s). They can connect now.",
        )
    }

    fun upAndConnectToSelf(): Result {
        if (isRunning() || isInterfaceRunning(SELF_CLIENT_INTERFACE)) {
            val stopped = down()
            if (!stopped.ok) return stopped
        }

        val proxyResult = DesktopPrismProxyServer.start()
        if (!proxyResult.ok) return failed(proxyResult.message)

        val localClientFile = selfClientConfigFile()
        if (!localClientFile.isFile || peers().none { it.name == SELF_CLIENT_NAME }) {
            if (localClientFile.exists()) localClientFile.delete()
            peers().firstOrNull { it.name == SELF_CLIENT_NAME }?.let { forget(it.publicKey) }
            val (config, _) = invite(
                SELF_CLIENT_NAME,
                "127.0.0.1",
                SELF_CLIENT_ALLOWED_IPS,
            )
            val selfConfig = config.lineSequence()
                .filterNot { it.startsWith("DNS = ") }
                .joinToString(System.lineSeparator(), postfix = System.lineSeparator())
            runCatching {
                localClientFile.writeText(selfConfig)
                localClientFile.setReadable(false, false)
                localClientFile.setReadable(true, true)
                localClientFile.setWritable(false, false)
                localClientFile.setWritable(true, true)
            }.onFailure {
                DesktopPrismProxyServer.stop()
                return failed("Could not save the local WireGuard client config: " + (it.message ?: it::class.java.simpleName))
            }
        }

        val serverResult = up()
        if (!serverResult.ok) {
            DesktopPrismProxyServer.stop()
            return serverResult
        }

        val command = clientUpCommand() ?: return failedAndStopProxy("Could not locate the local WireGuard client startup command.")
        val output = run(command) ?: return failedAndStopProxy("Could not run " + command.first() + " for the local client.")
        if (output.startsWith("ERROR:", true) || refused(output) ||
            (output.contains("error", true) && !output.contains("already exists", true))
        ) {
            return failedAndStopProxy("The server is running, but its local client failed to connect: " + output.trim())
        }
        if (!awaitInterfaceRunning(SELF_CLIENT_INTERFACE)) {
            return failedAndStopProxy("The server is running, but the local WireGuard client interface did not become active.")
        }
        sendHandshakeProbe()
        if (!awaitHandshake(SELF_CLIENT_INTERFACE)) {
            return failed("The server and local client interfaces are up, but this PC did not complete a WireGuard handshake with its own server.")
        }

        val message = serverResult.message + " This PC is connected to its own VPN server on $SELF_CLIENT_ALLOWED_IPS."
        PrismPlatform.log.success(TAG, message)
        return Result(true, message)
    }

    fun down(): Result {
        val proxyResult = DesktopPrismProxyServer.stop()
        if (!proxyResult.ok) return failed(proxyResult.message)
        val selfClientFile = selfClientConfigFile()
        if (selfClientFile.isFile && isInterfaceRunning(SELF_CLIENT_INTERFACE)) {
            val clientDown = clientDownCommand(selfClientFile)
                ?: return failed("Could not locate the local WireGuard client shutdown command.")
            val clientOutput = run(clientDown)
                ?: return failed("Could not run " + clientDown.first() + " to stop the local client.")
            if (clientOutput.startsWith("ERROR:", true) || refused(clientOutput)) {
                return failed("Could not stop this PC's local WireGuard client: " + clientOutput.trim())
            }
        }
        val command = downCommand() ?: return failed("Could not locate the WireGuard shutdown command.")
        val output = run(command) ?: return failed("Could not run " + command.first() + " to stop the interface.")
        if (output.startsWith("ERROR:", ignoreCase = true)) {
            return failed("Could not stop the WireGuard interface: " + output.trim())
        }
        if (output.contains("access is denied", true) || output.contains("permission denied", true) ||
            output.contains("operation not permitted", true) || output.contains("must be run as root", true)
        ) return failed("Could not stop the WireGuard interface: " + output.trim())
        running = false
        if (isRunning()) return failed("The WireGuard command returned, but interface $INTERFACE is still running.")
        PrismPlatform.log.info(TAG, "The $INTERFACE interface is down.")
        return Result(true, "The interface is down." + (if (output.isNullOrBlank()) "" else " " + output.trim()))
    }

    /**
     * How this platform brings an interface up, which is not the same program on each.
     *
     * WINDOWS HAS NO wg-quick. It is a shell script and the Windows distribution does not ship one; what
     * it ships is wireguard.exe, whose /installtunnelservice registers the config as a Windows SERVICE
     * that survives a reboot and is managed by the service manager. That is the supported route on
     * Windows and the only one that works without hand-building the interface -- an earlier version of
     * this file looked for wg-quick on every platform and would have reported "no WireGuard tool" on a
     * machine with WireGuard installed.
     *
     * THE FILE NAME BECOMES THE TUNNEL NAME on Windows, which is why [configFile] is named after
     * [INTERFACE] rather than something descriptive.
     */
    private fun upCommand(file: File): List<String>? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (windows) {
            val tool = VpnStack.wireGuardExecutable() ?: return null
            return listOf(tool, "/installtunnelservice", file.absolutePath)
        }
        return listOf("wg-quick", "up", file.absolutePath)
    }

    private fun downCommand(): List<String>? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (windows) {
            val tool = VpnStack.wireGuardExecutable() ?: return null
            return listOf(tool, "/uninstalltunnelservice", INTERFACE)
        }
        return listOf("wg-quick", "down", configFile().absolutePath)
    }

    private fun clientUpCommand(): List<String>? {
        val file = selfClientConfigFile()
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (windows) {
            val tool = VpnStack.wireGuardExecutable() ?: return null
            return listOf(tool, "/installtunnelservice", file.absolutePath)
        }
        return listOf("wg-quick", "up", file.absolutePath)
    }

    private fun clientDownCommand(file: File): List<String>? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (windows) {
            val tool = VpnStack.wireGuardExecutable() ?: return null
            return listOf(tool, "/uninstalltunnelservice", SELF_CLIENT_INTERFACE)
        }
        return listOf("wg-quick", "down", file.absolutePath)
    }

    private fun awaitInterfaceRunning(interfaceName: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (isInterfaceRunning(interfaceName)) return true
            try {
                TimeUnit.MILLISECONDS.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isInterfaceRunning(interfaceName)
    }

    private fun awaitHandshake(interfaceName: String): Boolean {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val inspector = if (windows) {
            val wireguard = VpnStack.wireGuardExecutable()?.let(::File)
            listOfNotNull(
                wireguard?.parentFile?.let { File(it, "wg.exe").takeIf(File::isFile)?.absolutePath },
                "wg.exe".takeIf { which(it) != null },
                "wg".takeIf { which(it) != null },
            ).firstOrNull()
        } else {
            "wg".takeIf { which(it) != null }
        } ?: return false

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val output = run(listOf(inspector, "show", interfaceName, "latest-handshakes"))
            if (output != null && output.lineSequence().any { line ->
                    line.trim().split(Regex("\\s+")).lastOrNull()?.toLongOrNull()?.let { it > 0L } == true
                }
            ) return true
            try {
                TimeUnit.MILLISECONDS.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun sendHandshakeProbe() {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("10.8.0.254", 9), 1_000)
            }
        }
    }

    /**
     * What `wg show` says, which is the only trustworthy answer to "is anybody connected".
     *
     * A HANDSHAKE TIME IS THE PROOF, not the interface existing. An interface with peers configured and
     * no handshake means nobody has connected; reporting that as connected is the exact lie this file
     * exists to avoid.
     */
    fun status(): String {
        val report = VpnStack.wireGuard()
        if (!report.canConnect) return report.detail

        val output = run(listOf("wg", "show", INTERFACE)) ?: return "Could not run wg."
        // Each platform words this differently: Linux says "No such device", Windows says "Unable to
        // access interface". Both mean the same thing and neither is an error worth showing raw.
        if (output.contains("No such device", true) ||
            output.contains("Unable to access interface", true) ||
            output.isBlank()
        ) {
            return "The interface is not up. " + peers().size + " peer(s) configured."
        }
        val handshakes = output.lines().count { it.contains("latest handshake") }
        return output.trim() + "\n\n" + (
            if (handshakes > 0) {
                "" + handshakes + " peer(s) have completed a handshake, so they are connected."
            } else {
                "No peer has completed a handshake yet, so nobody is connected."
            }
            )
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching "ERROR: command timed out after 30 seconds"
        }
        output.get(2, TimeUnit.SECONDS)
    }.getOrNull()

    private fun which(name: String): String? = runCatching {
        val path = System.getenv("PATH").orEmpty()
        path.split(File.pathSeparatorChar).map { File(it, name) }
            .firstOrNull { it.isFile }?.absolutePath
    }.getOrNull()

    private fun refused(output: String): Boolean =
        output.contains("must be run as root", true) ||
            output.contains("access is denied", true) ||
            output.contains("permission denied", true) ||
            output.contains("operation not permitted", true) ||
            output.contains("requires elevation", true)

    private fun failed(message: String): Result {
        PrismPlatform.log.error(TAG, message)
        return Result(false, message)
    }

    private fun failedAndStopProxy(message: String): Result {
        DesktopPrismProxyServer.stop()
        return failed(message)
    }
}
