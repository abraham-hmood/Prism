package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
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

    data class Peer(val name: String, val publicKey: String, val address: String)

    data class Result(val ok: Boolean, val message: String)

    /** Where configs live. Under the data directory, not a temp folder: these hold key material. */
    fun directory(): File = File(PrismPlatform.host.dataDir(), "wireguard").apply { mkdirs() }

    fun configFile(): File = File(directory(), INTERFACE + ".conf")

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

    /**
     * Creates a configuration for a new peer and remembers its public key.
     *
     * @return the config text to hand to that device, which contains ITS private key and nothing of
     *   this machine's except the public key it needs to recognise the server.
     */
    fun invite(name: String, endpointHost: String): Pair<String, Peer> {
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
            appendLine("AllowedIPs = " + PrismSettings.getWgAllowedIps().ifBlank { "0.0.0.0/0" })
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
            return Result(false, report.detail)
        }
        if (peers().isEmpty()) {
            // Refused rather than started. An interface with no peers listens and drops every handshake,
            // which presents as "connected here, broken there" -- the least debuggable outcome.
            return Result(false, "No peers yet. Invite a device first, so its key is in the config.")
        }

        val file = writeConfig()
        val command = upCommand(file) ?: return Result(false, report.detail)
        val output = run(command) ?: return Result(false, "Could not run " + command.first() + ".")

        if (refused(output)) {
            return Result(
                false,
                command.first() + " was refused: bringing an interface up needs root or " +
                    "administrator rights. On Windows, start Prism as administrator; on Linux, run it " +
                    "with sudo or give it CAP_NET_ADMIN.",
            )
        }
        if (output.contains("error", true) || output.contains("Cannot find", true)) {
            return Result(false, command.first() + " failed: " + output.trim().lines().firstOrNull())
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

    fun down(): Result {
        val output = downCommand()?.let { run(it) }
        running = false
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
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        output
    }.getOrNull()

    private fun refused(output: String): Boolean =
        output.contains("must be run as root", true) ||
            output.contains("access is denied", true) ||
            output.contains("permission denied", true) ||
            output.contains("operation not permitted", true) ||
            output.contains("requires elevation", true)
}
