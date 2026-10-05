package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tunnelling: what this machine can do, and the pieces that are not the adapter. PHASES 61, 62 and 63.
 *
 * ## The adapter itself moved out
 *
 * [TunDevice] creates it now, through JNA — WinTun on Windows, `/dev/net/tun` on Linux. What is left
 * here is everything AROUND a tunnel: generating a WireGuard configuration, recognising which protocol
 * a config file is, and recording which applications bypass the tunnel. Those are useful on their own
 * and none of them needs an adapter to exist.
 *
 * ## What IS done here
 *
 *   PHASE 61  WireGuard configuration. The keys are already portable ([WireGuardKeys] in :core, the same
 *             X25519 the phone uses), so a desktop can generate a working client config and the peer
 *             entry for the server today. Bringing the interface UP still needs PHASE 59 or an installed
 *             `wg-quick`, and [wireGuard] says which.
 *   PHASE 62  Protocol detection. Which of WireGuard, IKEv2 or L2TP a config describes is a property of
 *             the file, and reading it wrong is how a connection fails with a confusing error.
 *   PHASE 63  The split-tunnel policy. Which applications bypass the tunnel is a decision that can be
 *             recorded, edited and shown without a tunnel existing, and the list is what PHASE 59 will
 *             consume when it lands.
 */
object VpnStack {

    private const val TAG = "PrismVpn"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // ── PHASE 59: the virtual adapter ──────────────────────────────────────

    data class TunReport(
        val available: Boolean,
        val mechanism: String,
        val missing: List<String>,
        val detail: String,
    )

    /**
     * Whether a TUN interface could be created here, and what is missing if not.
     *
     * Checks the things that can be checked: the driver library's presence, the device node, and whether
     * this process is elevated. It does NOT attempt to create one — a failed attempt on Windows can leave
     * a half-registered adapter behind, which is worse than not trying.
     */
    /**
     * Whether a TUN interface could be created here, and what is missing if not.
     *
     * DELEGATES TO [TunDevice] NOW. This used to be a report with nothing behind it, because a JVM
     * could not call into WinTun at all; the desktop build carries JNA for exactly this, and the
     * adapter is really created by TunDevice.open(). What remains "missing" on a given machine is a
     * driver file and an elevated process, both of which are the user's to supply.
     */
    fun tun(): TunReport {
        val availability = TunDevice.availability()
        return TunReport(
            available = availability.usable,
            mechanism = availability.mechanism,
            missing = availability.missing,
            detail = availability.detail,
        )
    }

    /** Where WinTun is, if it is anywhere obvious. */
    fun wintunPath(): File? = listOf(
        File(System.getProperty("user.dir"), "wintun.dll"),
        File(PrismPlatform.host.dataDir(), "wintun.dll"),
        File("C:/Windows/System32/wintun.dll"),
    ).firstOrNull { it.isFile }

    /**
     * Whether this process could create a network interface.
     *
     * Probed by trying to write where only an administrator can, because Java has no portable way to ask.
     * A false answer is safe: the worst case is reporting a missing right that is present, which shows up
     * as a warning next to a feature that then works.
     */
    fun elevated(): Boolean = runCatching {
        val probe = if (windows) {
            File(System.getenv("SystemRoot") ?: "C:/Windows", "prism-elevation-probe.tmp")
        } else {
            File("/etc", "prism-elevation-probe.tmp")
        }
        val created = probe.createNewFile()
        if (created) probe.delete()
        created
    }.getOrDefault(false)

    // ── PHASE 61: WireGuard ────────────────────────────────────────────────

    data class WireGuardReport(
        val canConfigure: Boolean,
        val canConnect: Boolean,
        val tool: String,
        val detail: String,
    )

    /**
     * The program that can bring an interface up here, or null.
     *
     * WINDOWS AND EVERYTHING ELSE LOOK FOR DIFFERENT THINGS, and an earlier version of this looked only
     * for wg-quick -- which does not exist on Windows at all. wg-quick is a shell script; the Windows
     * distribution ships wireguard.exe instead, whose /installtunnelservice registers a config as a
     * Windows service. Searching for the wrong one reported "no WireGuard tool" on a machine with
     * WireGuard installed and working, which is the most misleading kind of wrong.
     *
     * The install directory is checked as well as the path, because the Windows installer does not add
     * itself to PATH.
     */
    fun wireGuardExecutable(): String? {
        if (!windows) return listOf("wg-quick", "wireguard-go").firstOrNull { which(it) != null }
        which("wireguard")?.let { return it }
        // Forward slashes: Windows accepts them everywhere a path is taken, and they keep a path that
        // is already awkward from also being a wall of escaped backslashes.
        return listOf(
            "C:/Program Files/WireGuard/wireguard.exe",
            "C:/Program Files (x86)/WireGuard/wireguard.exe",
        ).firstOrNull { File(it).isFile }
    }

    fun wireGuard(): WireGuardReport {
        val tool = wireGuardExecutable()
        // `wg` alone cannot create an interface, but its presence is worth reporting: it is what shows
        // the handshakes, which is the only trustworthy answer to "is anybody actually connected".
        val inspector = which("wg") != null

        return WireGuardReport(
            // Keys and configs are pure arithmetic and text, and :core already does the arithmetic.
            canConfigure = true,
            canConnect = tool != null,
            tool = tool?.let { File(it).name } ?: "none",
            detail = if (tool != null) {
                "Found " + File(tool).name + ", so a generated config can be brought up with it" +
                    (if (windows) " as a Windows tunnel service" else "") + ". Prism writes the config " +
                    "and hands it over rather than driving the interface itself -- the tool already " +
                    "has the privileges and the routing logic." +
                    (if (inspector) "" else " `wg` is not on the path, so handshakes cannot be read back.")
            } else if (windows) {
                "No WireGuard on this machine. Prism can still generate a key pair and a config for " +
                    "another device to use; bringing an interface up here needs WireGuard for Windows " +
                    "installed, and Prism running as administrator."
            } else {
                "No WireGuard tool on this machine. Prism can still generate a key pair and a config " +
                    "for another device to use; bringing an interface up here needs wg-quick installed."
            },
        )
    }

    /**
     * A client configuration for this machine against a Prism server.
     *
     * The private key never leaves this function's caller -- what goes to the server is the PUBLIC key,
     * in the peer block. That split is the whole security model of WireGuard and is worth not getting
     * casual about: a config generator that sent the private key to the server would be handing over the
     * identity it just created.
     */
    fun generateClientConfig(serverPublicKey: String, serverEndpoint: String, address: String): Pair<String, String> {
        val pair = com.prism.core.JdkWireGuardKeys.generate()
        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + pair.first)
            appendLine("Address = " + address)
            appendLine("DNS = 127.0.0.1")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + serverPublicKey)
            appendLine("Endpoint = " + serverEndpoint)
            appendLine("AllowedIPs = " + PrismSettings.getWgAllowedIps().ifBlank { "0.0.0.0/0" })
            appendLine("PersistentKeepalive = 25")
        }
        return config to pair.second
    }

    // ── PHASE 62: which protocol a config is ───────────────────────────────

    enum class Protocol { WIREGUARD, IKEV2, L2TP, OPENVPN, UNKNOWN }

    /**
     * Identifies a VPN configuration by its contents.
     *
     * By SHAPE rather than by file extension, because the extensions overlap and are frequently wrong:
     * a `.conf` is WireGuard or OpenVPN or strongSwan depending on what is inside it, and picking the
     * wrong engine produces an error about a malformed key rather than "this is not that kind of file".
     */
    fun detect(config: String): Protocol = when {
        config.contains("[Interface]") && config.contains("PrivateKey", ignoreCase = true) ->
            Protocol.WIREGUARD

        config.contains("conn ", ignoreCase = true) && config.contains("keyexchange=ikev2", ignoreCase = true) ->
            Protocol.IKEV2

        config.contains("[lac", ignoreCase = true) || config.contains("l2tp", ignoreCase = true) ->
            Protocol.L2TP

        config.contains("remote ", ignoreCase = true) && config.contains("dev tun", ignoreCase = true) ->
            Protocol.OPENVPN

        else -> Protocol.UNKNOWN
    }

    /** What it would take to actually connect with each, on this machine. */
    fun engineFor(protocol: Protocol): String = when (protocol) {
        Protocol.WIREGUARD -> wireGuard().let {
            if (it.canConnect) "ready, using " + it.tool else it.detail
        }

        Protocol.IKEV2 -> if (windows) {
            "Windows has a built-in IKEv2 client. Prism can add the connection with " +
                "`Add-VpnConnection -TunnelType Ikev2`, which needs an elevated PowerShell."
        } else {
            "Needs strongSwan (`ipsec`). Prism does not bundle it -- it is a system daemon with its own " +
                "configuration directory and privileges."
        }

        Protocol.L2TP -> "Needs xl2tpd with an IPsec layer underneath. Deprecated everywhere and " +
            "supported here only because existing configurations exist; nothing new should use it."

        Protocol.OPENVPN -> "Needs the openvpn binary. Recognised so a config is not mistaken for " +
            "WireGuard's, not because Prism drives it."

        Protocol.UNKNOWN -> "Not recognised as any VPN configuration Prism knows."
    }

    // ── PHASE 63: split tunnelling ─────────────────────────────────────────

    /**
     * Applications that bypass the tunnel.
     *
     * STORED AS EXECUTABLE NAMES rather than as Android package names, because that is what a desktop
     * has. The list is usable and editable now; ENFORCING it needs PHASE 59, because a packet can only
     * be routed around a tunnel that exists. Recording the policy first is not busywork -- it is the part
     * that survives whichever adapter mechanism ends up being used.
     *
     * ANDROID'S USAGE-BASED PART IS DROPPED, deliberately: `UsageStatsManager` ranks apps by screen time
     * to suggest what to whitelist, and a desktop has no equivalent that is not an invasive per-process
     * monitor. Manual is the whole feature here.
     */
    fun whitelist(): List<String> =
        PrismSettings.getAppWhitelist().sorted()

    fun addToWhitelist(executable: String) {
        val name = executable.trim().lowercase()
        if (name.isBlank()) return
        PrismSettings.setAppWhitelist(PrismSettings.getAppWhitelist() + name)
    }

    fun removeFromWhitelist(executable: String) {
        val name = executable.trim().lowercase()
        PrismSettings.setAppWhitelist(PrismSettings.getAppWhitelist().filterNot { it == name }.toSet())
    }

    /**
     * What is running now, so a whitelist can be chosen from a list rather than typed from memory.
     *
     * `ProcessHandle` rather than tasklist or ps: it is in the JDK, it needs no subprocess, and it gives
     * the command path directly. Processes whose command is not visible -- system ones, and anything
     * owned by another user -- are simply absent, which is correct: they are not the user's to whitelist.
     */
    fun runningApplications(): List<String> =
        runCatching {
            ProcessHandle.allProcesses()
                .map { it.info().command().orElse("") }
                .filter { it.isNotBlank() }
                .map { File(it).name.lowercase() }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())

    private fun which(tool: String): String? = runCatching {
        val command = if (windows) listOf("where", tool) else listOf("which", tool)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)
        output.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() && !it.contains("not find") }
    }.getOrNull()
}
