package com.prism.launcher.vpn

import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.MeshBridges
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletNetwork
import com.prism.launcher.wallet.WalletVault
import java.math.BigInteger

/**
 * A VPN session over the mesh, and what it costs the devices that carry it.
 *
 * ## What "routing through a country" means here
 *
 * The exit is a real peer: a device in that country announcing itself, with a measured round-trip
 * time, that this device can reach over the mesh. Choosing a country picks the fastest of those and
 * makes the session point at it. Traffic leaves through the mesh path this device already has
 * (`PrismTunnelEngine`), with the exit as the peer it terminates on -- which is what makes the exit's
 * country the country the traffic appears to come from.
 *
 * ## Bounce
 *
 * With bounce on, the path is the exit plus up to two more devices in the same country, taken in
 * latency order. Each of those is a device the user is paying, so the screen names them; a bounce
 * hop is charged half the exit's rate, because it forwards packets it did not originate and does not
 * see plaintext.
 *
 * ## Money
 *
 * Prism Coin only, because mining is what produces it and this feature requires mining. The rate is
 * quoted per minute before connecting (see [VpnRoute.ratePerMinuteMicros]) and the ledger accrues the
 * exact elapsed time, so "pay when you can" settles what was used rather than an estimate. Nothing is
 * paid up front: a session that fails to start costs nothing.
 */
object VpnMesh {

    data class Session(
        val country: String,
        val path: List<VpnRoute.Hop>,
        val startedAt: Long,
        val bounce: Boolean,
    ) {
        val exit: VpnRoute.Hop? get() = path.firstOrNull()
    }

    @Volatile
    var session: Session? = null
        private set

    fun isRunning(): Boolean = session != null

    /** Why the page cannot start a session, or null when it can. */
    fun gate(): String? {
        if (!PrismSettings.getVpnTunnelingEnabled()) {
            return "Turn on VPN tunnelling in Prism Settings first — the route rides that tunnel."
        }
        if (PrismSettings.getVpnMode() != PrismSettings.VPN_MODE_PRISM) {
            return "Prism Settings is on an external VPN profile. Switch the VPN mode to Prism."
        }
        if (!PrismSettings.getMeshEnabled()) {
            return "The mesh is off, so there are no devices to route through."
        }
        if (!PrismSettings.isPrismClientRole()) {
            return "This device is set up as the server. A server is what clients route through, " +
                "so switch the role to client, bridge or relay to use an exit."
        }
        if (!MeshBridges.isMining()) {
            return "Prism Coin mining has to be on: carrying traffic is paid in Prism Coin, and " +
                "this device only has it while it mines. Start mining on the Wallet page."
        }
        return null
    }

    /** Millionths of a coin per minute for the country's cheapest exit. */
    fun rateMicros(country: String): Long {
        val demand = VpnRoute.find(country)?.demand ?: 1
        return VpnRoute.ratePerMinuteMicros(demand)
    }

    /**
     * Starts a session. Returns null on success, or the reason it did not start.
     *
     * Nothing is written to the ledger until traffic has actually been carried, so a refusal here
     * leaves the wallet exactly as it was.
     */
    fun start(country: String, bounce: Boolean): String? {
        gate()?.let { return it }
        val path = VpnRoute.plan(country, bounce)
        if (path.isEmpty()) {
            return "No device in ${country.ifBlank { "that country" }} is offering an exit right now. " +
                "Countries appear here when a device in one joins this mesh."
        }
        val exit = path.first()
        val startedAt = System.currentTimeMillis()
        session = Session(country = exit.country, path = path, startedAt = startedAt, bounce = bounce)

        PrismSettings.setVpnCountry(exit.country)
        PrismSettings.setVpnExitNode(exit.nodeId)
        PrismSettings.setVpnSessionPath(path.joinToString(",") { it.nodeId })
        PrismSettings.setVpnSessionStartedAt(startedAt)
        // The exit's own listener, when it advertises one, is what the tunnel terminates on. Without
        // an advertised port the session still holds: the peer is reachable over the mesh, and the
        // engine's ordinary path is what carries the traffic.
        exit.serverPort?.let { port ->
            runCatching { PrismSettings.setPrismVpnPort(port.toString()) }
        }
        return null
    }

    /**
     * Ends the session, charges what was used, and settles if the user asked to pay when it can.
     *
     * Charging before clearing is deliberate: the elapsed time is read from the session that is about
     * to be forgotten, and a crash between the two would otherwise lose the charge entirely.
     */
    fun stop(): String {
        val live = session ?: return "No session was running."
        val elapsedMs = (System.currentTimeMillis() - live.startedAt).coerceAtLeast(0L)
        val charged = charge(live, elapsedMs)

        session = null
        PrismSettings.setVpnExitNode("")
        PrismSettings.setVpnSessionPath("")
        PrismSettings.setVpnSessionStartedAt(0L)

        val paid = if (PrismSettings.getVpnPayWhenYouCan()) settle() else ""
        val summary = if (charged.isEmpty()) {
            "Session ended — under a minute, so nothing was charged."
        } else {
            "Session ended — charged ${VpnRoute.formatMicros(charged.sumOf { it.second })} PSC to " +
                "${charged.size} device(s)."
        }
        return if (paid.isBlank()) summary else "$summary $paid"
    }

    /** Accrues the elapsed time against every device on the path. Returns nodeId to micros. */
    private fun charge(live: Session, elapsedMs: Long): List<Pair<String, Long>> {
        if (elapsedMs <= 0L) return emptyList()
        val exitRate = rateMicros(live.country)
        val written = ArrayList<Pair<String, Long>>()
        live.path.forEachIndexed { index, hop ->
            // The exit is paid the quoted rate; a bounce hop forwards for half of it.
            val rate = if (index == 0) exitRate else exitRate / 2
            val micros = rate * elapsedMs / 60_000L
            if (micros <= 0L) return@forEachIndexed
            VpnLedger.owe(hop.nodeId, hop.name, hop.payout, micros)
            written += hop.nodeId to micros
        }
        return written
    }

    /**
     * Pays what the ledger holds, as far as Prism Coin allows.
     *
     * Three things can stop a row, and each is named rather than silently skipped: no wallet, no
     * payout address announced by the peer, and not enough coin. The row stays in the ledger in all
     * three cases, so the next attempt after mining credits the address picks it up.
     */
    fun settle(): String {
        val debts = VpnLedger.debts()
        if (debts.isEmpty()) return "Nothing owed."
        val coin = CoinRegistry.bySymbol("PSC") ?: return "Prism Coin is not registered on this device."
        val seed = WalletVault.seed() ?: return "No wallet on this device, so nothing can be paid yet."
        val account = runCatching { WalletVault.account(coin, seed) }.getOrNull()
            ?: return "The wallet could not be opened."
        if (!MeshBridges.isMining()) {
            return "Mining is off — the coins to pay with are earned by mining, so the debt stays queued."
        }

        val payable = debts.filter { it.payout.isNotBlank() && runCatching { coin.isValidAddress(it.payout) }.getOrDefault(false) }
        if (payable.isEmpty()) {
            return "The devices this session used have not announced a payout address, so the debt stays queued."
        }
        val balance = runCatching { WalletNetwork.balanceInfo(coin, account.address)?.total }
            .getOrNull() ?: BigInteger.ZERO

        var left = balance
        val cleared = ArrayList<String>()
        for (debt in payable) {
            val minor = toMinor(coin.decimals, debt.micros)
            if (minor <= BigInteger.ZERO) {
                cleared += debt.nodeId
                continue
            }
            if (minor > left) break
            val sent = runCatching {
                WalletNetwork.send(coin, account.address, account.privateKey, debt.payout, minor)
            }.getOrNull()
            if (sent is WalletNetwork.SendResult.Broadcast) {
                left -= minor
                cleared += debt.nodeId
            }
        }
        if (cleared.isEmpty()) {
            return "Not enough Prism Coin mined yet — ${VpnLedger.describe()}."
        }
        VpnLedger.clear(cleared)
        return "Paid ${cleared.size} device(s); ${VpnLedger.describe()}."
    }

    /** Converts millionths of a coin to the coin's own smallest unit. */
    private fun toMinor(decimals: Int, micros: Long): BigInteger {
        val value = BigInteger.valueOf(micros)
        return if (decimals >= 6) value.multiply(BigInteger.TEN.pow(decimals - 6))
        else value.divide(BigInteger.TEN.pow(6 - decimals))
    }

    /** "4 min · 0.000012 PSC/min" for the live panel. */
    fun statusLine(): String {
        val live = session ?: return "Not connected."
        val minutes = ((System.currentTimeMillis() - live.startedAt) / 60_000L).coerceAtLeast(0L)
        return "$minutes min on ${live.exit?.name ?: live.country} · " +
            "${VpnRoute.formatMicros(rateMicros(live.country))} PSC/min"
    }
}
