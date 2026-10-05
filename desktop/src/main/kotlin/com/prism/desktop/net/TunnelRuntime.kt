package com.prism.desktop.net

import com.prism.core.IpPackets
import com.prism.core.MeshDnsServer
import com.prism.core.PrismPlatform
import com.prism.core.TunnelRouter
import com.prism.launcher.PrismSettings
import java.util.concurrent.TimeUnit

/**
 * The tunnel. Opens the adapter, gives it routes, and carries packets. PHASES 58, 59, 61, 63.
 *
 * ## What this is the missing half of
 *
 * [TunDevice] can create an adapter and [TunnelRouter] can decide what a packet deserves; neither of
 * them moves a byte. This is the loop between them, plus the part nobody enjoys: telling the operating
 * system which traffic should come down the adapter in the first place.
 *
 * ## The routes, and why there are only three
 *
 * The adapter is given an address, the two DNS addresses, and the mesh subnet. NOT the default route.
 * That matches `PrivateDnsVpnService` on Android exactly, and [TunnelRouter] explains at length why
 * claiming everything would be both slower and more dangerous. The practical consequence: bringing
 * this tunnel up cannot break the machine's internet access, because the machine's internet access
 * never enters it.
 *
 * ## Why nothing here throws when it is refused
 *
 * Every step needs administrator rights, and a user who has not granted them should get a clear
 * sentence rather than a stack trace. [start] returns a [Result] naming the step that failed and what
 * it needs, [status] says whether the tunnel is actually carrying anything, and neither ever reports
 * the tunnel as up because the adapter opened -- an adapter with no routes carries nothing, and saying
 * otherwise would tell somebody their private browsing was protected when it was not.
 */
object TunnelRuntime {

    private const val TAG = "PrismTunnel"

    /** The address the adapter takes, matching Android's VPN_ADDRESS so both ends agree. */
    const val ADDRESS = "10.7.0.2"
    private const val PREFIX = 30
    private const val GATEWAY = "10.7.0.1"

    /**
     * The two addresses the machine is told are its DNS servers.
     *
     * NOT 127.0.0.1, which is what the MeshDnsServer route uses and is right there. It cannot be used
     * here: a route for a loopback address cannot be given to an adapter, so a query to 127.0.0.1 would
     * never reach the tunnel. These two are inside the adapter subnet, so a query to them arrives as a
     * packet and [TunnelRouter] answers it.
     */
    const val DNS_PRIMARY = "10.7.0.1"
    const val DNS_SECONDARY = "10.7.0.3"

    data class Result(val ok: Boolean, val message: String)

    @Volatile
    var running = false
        private set

    @Volatile
    private var session: TunDevice.Session? = null

    private var loop: Thread? = null

    /** The interface whose DNS was pointed here, so it can be put back. */
    private var redirected: String? = null

    /** Whether routes were successfully added, which is what makes the tunnel real. */
    @Volatile
    var routed = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Brings the tunnel up.
     *
     * The order matters: the router is configured BEFORE the adapter opens. An adapter that is open and
     * reading with no blocklist loaded and no whitelist would, for the few milliseconds in between,
     * resolve names it should have refused.
     */
    fun start(): Result {
        if (running) return Result(true, "The tunnel is already up: " + TunnelRouter.counters())

        configureRouter()

        val adapter = TunDevice.open("Prism")
        if (adapter == null) {
            lastError = TunDevice.lastError
            return Result(
                false,
                "No adapter: " + TunDevice.lastError.ifEmpty { "unknown reason" } +
                    (if (!TunDevice.elevated()) ". Prism is not running as administrator." else ""),
            )
        }
        session = adapter

        val routing = applyRoutes(adapter.name)
        routed = routing == null
        if (!routed) {
            // The adapter is left OPEN rather than closed. It exists, it is named, and the routing
            // command that failed can be run by hand against it -- which is a far better position for
            // somebody debugging an elevation problem than an adapter that vanished.
            lastError = routing.orEmpty()
            PrismPlatform.log.warn(TAG, "The adapter is open but unrouted: " + routing)
        }

        running = true
        TunnelRouter.resetCounters()

        loop = Thread({ pump(adapter) }, "prism-tunnel").apply {
            isDaemon = true
            start()
        }

        PrismPlatform.log.info(
            TAG,
            "The tunnel is up on " + adapter.name + " at " + ADDRESS +
                (if (routed) " with routes" else " WITHOUT routes"),
        )

        return Result(
            routed,
            if (routed) {
                "The tunnel is up on " + adapter.name + ". DNS for this machine now goes through Prism."
            } else {
                "The adapter " + adapter.name + " opened, but its routes were refused: " + lastError
            },
        )
    }

    /**
     * Takes the tunnel down and puts the machine back as it was.
     *
     * THE DNS IS RESTORED FIRST, before the adapter closes. The other order leaves a window in which
     * the machine is pointed at an address that no longer answers, which looks exactly like the
     * internet being broken.
     */
    fun stop(): Result {
        if (!running && session == null) return Result(true, "The tunnel is not up.")

        redirected?.let { name ->
            NetworkControl.restoreDns(name)
            redirected = null
        }

        running = false
        loop?.interrupt()
        loop = null

        val name = session?.name
        TunDevice.close()
        session = null
        routed = false

        name?.let { removeRoutes(it) }

        PrismPlatform.log.info(TAG, "The tunnel is down. " + TunnelRouter.counters())
        return Result(true, "The tunnel is down. " + TunnelRouter.counters())
    }

    /** What the tunnel is doing, as one paragraph. */
    fun status(): String = buildString {
        if (!running) {
            append("Down.")
            if (lastError.isNotEmpty()) {
                append(" Last error: ")
                append(lastError)
            }
            return@buildString
        }
        append("Up on ")
        append(session?.name ?: "an adapter")
        append(" at ")
        append(ADDRESS)
        append(if (routed) ", routed" else ", NOT routed -- carrying nothing")
        append(". ")
        append(TunnelRouter.counters())
        append(". ")
        append(ProcessPorts.describe())
    }

    /** Whether private tabs can actually be given a tunnel on this machine right now. */
    fun available(): Boolean = TunDevice.availability().usable

    // ── The router's configuration ─────────────────────────────────────────

    /**
     * Points [TunnelRouter] at this machine's blocklist, whitelist and sink addresses.
     *
     * Called on every start rather than once, because all three are settings the user changes between
     * sessions and a tunnel that enforced the policy from last time would be worse than useless.
     */
    fun configureRouter() {
        // THE SHARED INSTANCE, not a new one. HostBlocklist loads several hundred thousand hostnames
        // and starts a download in its constructor; a second copy would double both and leave the
        // browser and the tunnel enforcing two lists that drift apart after the next edit.
        val blocklist = com.prism.launcher.browser.PrismBlocklist.get()
        TunnelRouter.blockedHost = { name -> runCatching { blocklist.shouldBlockHost(name) }.getOrDefault(false) }

        ProcessPorts.refreshWhitelist(VpnStack.whitelist())
        TunnelRouter.bypassOwner = { port ->
            if (ProcessPorts.hasWhitelist()) ProcessPorts.whitelistedOwner(port) else null
        }

        TunnelRouter.sinkAddresses = NetworkControl.blocked().mapNotNull { IpPackets.packed(it) }.toSet()

        MeshDnsServer.upstream = PrismSettings.getPrimaryDns().ifBlank { "1.1.1.1" }
    }

    // ── The loop ───────────────────────────────────────────────────────────

    /**
     * Reads, decides, writes.
     *
     * ONE THREAD, NOT A POOL. A TUN read is blocking and the work per packet is a table lookup and at
     * most one upstream DNS query, so a pool would add contention on the adapter for no throughput.
     * The upstream query is the one slow step, and it is bounded by MeshDnsServer's own timeout.
     *
     * A READ THAT RETURNS NULL IS NOT AN ERROR. WinTun's receive returns nothing when the ring is
     * empty; the loop yields rather than spinning, which keeps an idle tunnel off the processor.
     */
    private fun pump(adapter: TunDevice.Session) {
        var idle = 0
        while (running) {
            val packet = runCatching { adapter.read() }.getOrElse {
                if (running) {
                    lastError = it.message.orEmpty()
                    PrismPlatform.log.error(TAG, "The adapter stopped reading", it)
                }
                null
            }

            if (packet == null) {
                idle++
                // A short sleep after a burst of nothing. Park rather than sleep so an interrupt from
                // stop() ends the loop immediately.
                if (idle > 64) TimeUnit.MILLISECONDS.sleep(2) else Thread.onSpinWait()
                if (Thread.currentThread().isInterrupted) break
                continue
            }
            idle = 0

            when (val verdict = TunnelRouter.route(packet)) {
                is TunnelRouter.Verdict.Reply -> {
                    if (!adapter.write(verdict.packet)) {
                        PrismPlatform.log.warn(TAG, "Could not write a reply back: " + verdict.reason)
                    }
                }

                is TunnelRouter.Verdict.Drop -> Unit      // counted by the router; not logged per packet

                is TunnelRouter.Verdict.Bypass ->
                    PrismPlatform.log.info(TAG, "Bypassed " + verdict.executable + ": " + verdict.reason)

                is TunnelRouter.Verdict.Mesh -> Unit      // the sender hook has it; see meshSender
            }
        }
        PrismPlatform.log.info(TAG, "The packet loop has ended.")
    }

    // ── Routing ────────────────────────────────────────────────────────────

    /**
     * Gives the adapter its address and its three routes. Returns null on success, or what went wrong.
     *
     * WINDOWS AND LINUX DIFFER IN MORE THAN SYNTAX. netsh sets an address and a mask and derives the
     * interface route itself, then each additional route is a separate command against the interface by
     * name. Linux needs the address added to the link, the link brought up as a separate step -- a
     * fresh tun device is DOWN and routes against a down link are rejected -- and then the routes.
     */
    private fun applyRoutes(adapter: String): String? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        val commands: List<List<String>> = if (windows) {
            listOf(
                listOf(
                    "netsh", "interface", "ip", "set", "address",
                    "name=" + adapter, "static", ADDRESS, mask(PREFIX), GATEWAY, "1",
                ),
                route(adapter, DNS_PRIMARY, "255.255.255.255", windows = true),
                route(adapter, DNS_SECONDARY, "255.255.255.255", windows = true),
                route(adapter, "10.8.0.0", "255.255.255.0", windows = true),
            )
        } else {
            listOf(
                listOf("ip", "addr", "add", ADDRESS + "/" + PREFIX, "dev", adapter),
                listOf("ip", "link", "set", "dev", adapter, "up"),
                listOf("ip", "route", "add", DNS_PRIMARY + "/32", "dev", adapter),
                listOf("ip", "route", "add", DNS_SECONDARY + "/32", "dev", adapter),
                listOf("ip", "route", "add", "10.8.0.0/24", "dev", adapter),
            )
        }

        commands.forEach { command ->
            val output = run(command) ?: return "could not run " + command.first()
            if (refused(output)) {
                return command.joinToString(" ") + " was refused: administrator rights are needed"
            }
            // An address or route that is already there is a success, not a failure: it means a
            // previous run left it, which is exactly the state wanted.
            if (failed(output) && !alreadyThere(output)) {
                return command.joinToString(" ") + " failed: " + output.trim().lines().firstOrNull()
            }
        }

        // Point the machine's own resolver at the tunnel. Last, because until the routes exist an
        // interface pointed at DNS_PRIMARY would have nowhere to send a query.
        val primary = NetworkControl.interfaces().firstOrNull { !it.name.equals(adapter, true) }
        primary?.let { candidate ->
            val problem = NetworkControl.setDns(candidate.name, DNS_PRIMARY)
            if (problem == null) {
                redirected = candidate.name
                PrismPlatform.log.info(TAG, "DNS on " + candidate.name + " now points at the tunnel.")
            } else {
                // NOT a failure of the tunnel. The adapter and its routes are up; what is missing is
                // the machine VOLUNTEERING its queries, and an application pointed at DNS_PRIMARY
                // itself still gets the mesh. Reported rather than fatal.
                PrismPlatform.log.warn(TAG, "The tunnel is up but DNS was not redirected: " + problem)
            }
        }

        return null
    }

    private fun removeRoutes(adapter: String) {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        // Best effort, and quiet: the adapter is usually gone by now, which deletes its routes with it.
        // These run for the case where it is not, and a failure here has nothing left to break.
        if (windows) {
            listOf(DNS_PRIMARY, DNS_SECONDARY, "10.8.0.0").forEach {
                run(listOf("route", "delete", it))
            }
        } else {
            listOf(DNS_PRIMARY + "/32", DNS_SECONDARY + "/32", "10.8.0.0/24").forEach {
                run(listOf("ip", "route", "del", it, "dev", adapter))
            }
        }
    }

    private fun route(adapter: String, destination: String, mask: String, windows: Boolean): List<String> =
        if (windows) {
            listOf("route", "add", destination, "mask", mask, GATEWAY, "if", indexOf(adapter))
        } else {
            listOf("ip", "route", "add", destination, "dev", adapter)
        }

    /**
     * The interface index Windows knows the adapter by.
     *
     * NEEDED BECAUSE `route add` TAKES AN INDEX, not a name -- one of the few places Windows networking
     * still insists on it. Falls back to "1" only so the command is well-formed and fails with a
     * message about the index rather than a syntax error, which is the more useful failure.
     */
    private fun indexOf(adapter: String): String {
        val output = run(listOf("netsh", "interface", "ipv4", "show", "interfaces")).orEmpty()
        output.lines().forEach { line ->
            if (line.contains(adapter, ignoreCase = true)) {
                line.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.let { return it.toString() }
            }
        }
        return "1"
    }

    private fun mask(prefix: Int): String {
        val value = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
        return "" + ((value ushr 24) and 0xFF) + "." + ((value ushr 16) and 0xFF) + "." +
            ((value ushr 8) and 0xFF) + "." + (value and 0xFF)
    }

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)
        output
    }.getOrNull()

    private fun refused(output: String): Boolean =
        output.contains("access is denied", ignoreCase = true) ||
            output.contains("requires elevation", ignoreCase = true) ||
            output.contains("operation not permitted", ignoreCase = true) ||
            output.contains("permission denied", ignoreCase = true)

    private fun failed(output: String): Boolean =
        output.contains("failed", ignoreCase = true) ||
            output.contains("error", ignoreCase = true) ||
            output.contains("cannot", ignoreCase = true) ||
            output.contains("Element not found", ignoreCase = true)

    private fun alreadyThere(output: String): Boolean =
        output.contains("already exists", ignoreCase = true) ||
            output.contains("File exists", ignoreCase = true) ||
            output.contains("object already exists", ignoreCase = true)
}
