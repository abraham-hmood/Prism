package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.util.concurrent.TimeUnit

/**
 * What a desktop can do to the network its hotspot clients are on. PHASES 65 and 66.
 *
 * ## PHASE 65: making clients resolve through Prism
 *
 * A device that joins this machine's hotspot is handed DNS by whatever is sharing the connection. To
 * make it resolve mesh names, that has to point at [com.prism.core.MeshDnsServer] instead. On Windows
 * the shared adapter's DNS is a registry-backed setting that `netsh` writes; on Linux NetworkManager
 * owns it and `nmcli` writes it.
 *
 * WHICH IS TO SAY: this is not a protocol to implement, it is a setting to set on an interface, and the
 * interesting part is naming the interface correctly and putting it back afterwards.
 *
 * ## PHASE 66: blocking a device
 *
 * Windows has a packet filter behind `netsh advfirewall`, and Linux has nftables or iptables. Both take
 * a rule naming an address. Bandwidth limiting is a different mechanism on each -- QoS policies on
 * Windows, `tc` on Linux -- and is NOT pretended here: a rule that claims to throttle and does not is
 * worse than a missing feature, so [limitBandwidth] reports what it would take instead.
 *
 * ## Why everything here reports rather than assumes
 *
 * Every call is a subprocess that can fail for reasons this code cannot see: no elevation, a policy
 * that forbids it, a firewall service that is off. Each returns the reason rather than a boolean, so a
 * UI can say what happened instead of showing a switch that silently does nothing.
 */
object NetworkControl {

    private const val TAG = "PrismNetwork"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The rule name Prism gives its own firewall rules, so it can find and remove them again. */
    private const val RULE_PREFIX = "PrismBlock-"

    // ── PHASE 65: DNS for clients ──────────────────────────────────────────

    data class Interface(val name: String, val description: String)

    /** Network interfaces this machine has, as the OS names them. */
    fun interfaces(): List<Interface> = runCatching {
        if (windows) {
            // `netsh interface show interface` gives the names netsh itself accepts, which is what
            // matters: the friendly name from Java's NetworkInterface is not always the same string.
            run("netsh", "interface", "show", "interface")
                .orEmpty()
                .lineSequence()
                .drop(3)
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s{2,}"))
                    if (parts.size < 4) null else Interface(parts.last(), parts.getOrElse(2) { "" })
                }
                .filter { it.name.isNotBlank() }
                .toList()
        } else {
            java.net.NetworkInterface.getNetworkInterfaces().toList().map {
                Interface(it.name, it.displayName.orEmpty())
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Points an interface's DNS at Prism's resolver.
     *
     * Returns null on success, or what stopped it. THE PREVIOUS SETTING IS NOT SAVED, deliberately:
     * both platforms have a first-class "go back to automatic" that is more reliable than restoring a
     * value this code guessed at, and [restoreDns] uses it.
     */
    fun useePrismDns(interfaceName: String): String? = setDns(interfaceName, "127.0.0.1")

    fun setDns(interfaceName: String, server: String): String? {
        if (interfaceName.isBlank()) return "Name an interface."
        val output = if (windows) {
            run("netsh", "interface", "ip", "set", "dns", "name=$interfaceName", "static", server)
        } else {
            run("nmcli", "connection", "modify", interfaceName, "ipv4.dns", server)
        } ?: return "The command would not run."

        return if (refused(output)) {
            "Refused: this needs an elevated prompt. The command is: " +
                (if (windows) {
                    "netsh interface ip set dns name=\"$interfaceName\" static $server"
                } else {
                    "nmcli connection modify $interfaceName ipv4.dns $server"
                })
        } else {
            PrismPlatform.log.info(TAG, "DNS on $interfaceName now points at $server")
            null
        }
    }

    /** Puts DNS back to whatever the network hands out. */
    fun restoreDns(interfaceName: String): String? {
        val output = if (windows) {
            run("netsh", "interface", "ip", "set", "dns", "name=$interfaceName", "dhcp")
        } else {
            run("nmcli", "connection", "modify", interfaceName, "ipv4.ignore-auto-dns", "no")
        } ?: return "The command would not run."
        return if (refused(output)) "Refused: this needs an elevated prompt." else null
    }

    // ── PHASE 66: isolation ────────────────────────────────────────────────

    data class Rule(val address: String, val blocked: Boolean)

    /**
     * Stops a device reaching anything through this machine.
     *
     * TWO RULES, not one: a firewall filters per direction, and blocking only inbound leaves the device
     * able to send. Both are named with the same prefix so [unblock] can find them.
     */
    fun block(address: String): String? {
        if (address.isBlank()) return "Name an address."
        val name = RULE_PREFIX + address

        val output = if (windows) {
            run(
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=$name-in", "dir=in", "action=block", "remoteip=$address",
            )
            run(
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=$name-out", "dir=out", "action=block", "remoteip=$address",
            )
        } else {
            // nft first, iptables as the fallback: nftables is the current mechanism and iptables is
            // what older systems still run.
            run("nft", "add", "rule", "inet", "filter", "forward", "ip", "saddr", address, "drop")
                ?: run("iptables", "-I", "FORWARD", "-s", address, "-j", "DROP")
        } ?: return "The firewall command would not run."

        return if (refused(output)) {
            "Refused: blocking a device needs administrator rights."
        } else {
            saveBlocked(blocked() + address)
            PrismPlatform.log.info(TAG, "Blocked $address")
            null
        }
    }

    fun unblock(address: String): String? {
        val name = RULE_PREFIX + address
        val output = if (windows) {
            run("netsh", "advfirewall", "firewall", "delete", "rule", "name=$name-in")
            run("netsh", "advfirewall", "firewall", "delete", "rule", "name=$name-out")
        } else {
            run("nft", "flush", "chain", "inet", "filter", "forward")
                ?: run("iptables", "-D", "FORWARD", "-s", address, "-j", "DROP")
        } ?: return "The firewall command would not run."

        return if (refused(output)) {
            "Refused: this needs administrator rights."
        } else {
            saveBlocked(blocked() - address)
            null
        }
    }

    /** Addresses Prism has blocked, as it recorded them. */
    fun blocked(): Set<String> =
        PrismSettings.getBlockedMeshDevices().filter { it.isNotBlank() }.toSet()

    private fun saveBlocked(addresses: Set<String>) {
        PrismSettings.setBlockedMeshDevices(addresses.toList())
    }

    /**
     * Bandwidth limiting, which is NOT implemented and says so.
     *
     * Both platforms can do it and neither can do it the same way: Windows wants a QoS policy created
     * through Group Policy or PowerShell's NetQos cmdlets, and Linux wants a `tc` qdisc on the
     * interface. Neither is a one-line command, both are easy to get wrong in ways that silently
     * throttle the wrong traffic, and a switch that claimed to limit a device while doing nothing
     * would be worse than no switch.
     */
    fun limitBandwidth(address: String, kilobitsPerSecond: Int): String =
        if (windows) {
            "Not implemented. Windows does this with a QoS policy — `New-NetQosPolicy` — which applies " +
                "per application or per port rather than per remote address, so limiting one device " +
                "means a policy plus a firewall rule to match it. Prism will not pretend to do it."
        } else {
            "Not implemented. Linux does this with `tc qdisc` and a class per address, which has to be " +
                "torn down correctly or it survives a reboot and throttles something nobody remembers " +
                "configuring. Prism will not pretend to do it."
        }

    // ── Plumbing ───────────────────────────────────────────────────────────

    /** Whether the output says the command was refused rather than that it failed. */
    private fun refused(output: String): Boolean =
        output.contains("access is denied", ignoreCase = true) ||
            output.contains("requires elevation", ignoreCase = true) ||
            output.contains("not authorized", ignoreCase = true) ||
            output.contains("permission denied", ignoreCase = true) ||
            output.contains("operation not permitted", ignoreCase = true)

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)
        output
    }.getOrNull()
}
