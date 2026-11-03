package com.prism.launcher.vpn

import com.prism.core.PrismPlatform
import com.prism.launcher.mesh.MeshBridges
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * What the VPN page is allowed to choose, and what choosing it costs.
 *
 * ## Countries come from the mesh, not from a list in the app
 *
 * A country is on this screen only because a device announced itself there. A hard-coded list would
 * put forty flags on screen with nothing behind most of them, and the user would tap one and wait for
 * a route that was never going to exist. The announce carries the country code (see
 * `PrismNodeWire`), so this is a view of who is actually reachable.
 *
 * ## Fastest is measured, not guessed
 *
 * [MeshBridges] peers already carry a round-trip time, measured from the heartbeat each peer answers.
 * Sorting on it is the whole of "fastest device": an exit on a phone across the same room beats a
 * server on the far side of a bad link, which is exactly the behaviour a VPN user expects and the
 * opposite of what a static preference list gives them.
 *
 * ## Price is demand, and it is visible before connecting
 *
 * The rate is per minute, in millionths of a Prism Coin, and rises with how many devices this device
 * can see offering exits in that country. That is the demand signal available without a central
 * book: a country with one exit and one user is cheap, and the same country with six exits and a
 * crowd is not. The screen prints the rate before the user commits, and the ledger records what was
 * actually used, so a charge is never a surprise.
 */
object VpnRoute {

    /** One device that could carry traffic. */
    data class Hop(
        val nodeId: String,
        val name: String,
        val address: String,
        val latencyMs: Long,
        val country: String,
        val payout: String,
        val serverPort: Int?,
    )

    /** A country with at least one exit on this mesh. */
    data class Country(
        val code: String,
        val devices: Int,
        val bestLatencyMs: Long,
        val demand: Int,
        /** Millionths of a coin per minute for the cheapest exit here. */
        val rateMicros: Long,
    )

    /** The floor rate: two millionths of a coin a minute, before demand. */
    private const val BASE_RATE_MICROS = 2L

    private val codePattern = Regex("[A-Z]{2}")

    private fun normalizeCountry(raw: String): String {
        val code = raw.trim().uppercase()
        return if (codePattern.matches(code)) code else ""
    }

    /** Every peer that said where it is. A peer without a country cannot be an exit. */
    fun hops(): List<Hop> = runCatching {
        MeshBridges.peers().mapNotNull { peer ->
            val country = normalizeCountry(peer.country)
            if (country.isEmpty() || peer.ip.isBlank()) return@mapNotNull null
            Hop(
                nodeId = peer.nodeId.ifBlank { peer.ip },
                name = peer.name.ifBlank { peer.ip },
                address = peer.ip,
                latencyMs = peer.latencyMs.coerceAtLeast(1L),
                country = country,
                payout = peer.payout,
                serverPort = peer.serverPort,
            )
        }
    }.getOrDefault(emptyList())

    fun countries(): List<Country> = hops()
        .groupBy { it.country }
        .map { (code, peers) ->
            val demand = peers.size.coerceAtLeast(1)
            Country(
                code = code,
                devices = peers.size,
                bestLatencyMs = peers.minOf { it.latencyMs },
                demand = demand,
                rateMicros = ratePerMinuteMicros(demand),
            )
        }
        .sortedWith(compareBy({ it.bestLatencyMs }, { it.code }))

    fun find(code: String): Country? = countries().firstOrNull { it.code == normalizeCountry(code) }

    /** Exits in [code], fastest first. */
    fun hopsIn(code: String): List<Hop> {
        val wanted = normalizeCountry(code)
        if (wanted.isEmpty()) return emptyList()
        return hops().filter { it.country == wanted }.sortedWith(compareBy({ it.latencyMs }, { it.name }))
    }

    /**
     * The path traffic takes: the exit, then the bounces.
     *
     * Bounce deliberately picks the *next* fastest devices rather than the fastest, because a bounce
     * hop that is also the exit adds a device to the path without adding a device to the route. The
     * path is capped at three so the page can name every device the user is paying.
     */
    fun plan(code: String, bounce: Boolean): List<Hop> {
        val inCountry = hopsIn(code)
        if (inCountry.isEmpty()) return emptyList()
        val exit = inCountry.first()
        if (!bounce) return listOf(exit)
        return listOf(exit) + inCountry.drop(1).take(2)
    }

    /**
     * Millionths of a coin per minute.
     *
     * Demand is a multiplier rather than a formula: two devices in a country doubles the floor, and
     * the cap stops a crowded country from producing a rate nobody would ever accept.
     */
    fun ratePerMinuteMicros(demand: Int): Long =
        BASE_RATE_MICROS * (1 + demand.coerceIn(0, 8))

    /** "0.000042" — six places is the precision every rate on this screen is quoted at. */
    fun formatMicros(micros: Long): String =
        BigDecimal.valueOf(micros).movePointLeft(6).stripTrailingZeros().toPlainString()

    /** A minute's worth, described for a row. */
    fun describeRate(demand: Int): String = "${formatMicros(ratePerMinuteMicros(demand))} PSC/min"
}

/**
 * What this device owes for VPN traffic, kept until it can be paid.
 *
 * ## Why a ledger instead of paying at disconnect
 *
 * The user's own rule is "pay when you can": the exit is paid in Prism Coin, which this device has
 * only while it is mining and only once a block credits the address. A payment attempted at
 * disconnect would fail for exactly the devices this feature is for -- the ones with no balance
 * yet -- and the debt would vanish with the session. Recording first means the amount survives the
 * app being closed, and [com.prism.launcher.vpn.VpnMesh.settle] clears it the moment mining has
 * produced something to spend.
 *
 * ## Why per device
 *
 * A bounced session pays every device it passed through, not only the exit, so the ledger is keyed
 * by node rather than by session. Two sessions that touched the same exit accumulate on one row,
 * which is also what stops a long session from writing a new row every minute.
 */
object VpnLedger {

    data class Debt(
        val nodeId: String,
        val name: String,
        val payout: String,
        val micros: Long,
        val updatedAt: Long,
    )

    private const val SEP = "\u001F"

    private fun file(): File = File(PrismPlatform.host.dataDir(), "vpn/ledger.txt")

    fun debts(): List<Debt> = runCatching {
        val f = file()
        if (!f.isFile) emptyList() else f.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split(SEP)
                val micros = p.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
                if (p.size < 3 || micros <= 0L) return@mapNotNull null
                Debt(
                    nodeId = p[0],
                    name = p.getOrNull(1).orEmpty().ifBlank { p[0] },
                    payout = p.getOrNull(2).orEmpty(),
                    micros = micros,
                    updatedAt = p.getOrNull(4)?.toLongOrNull() ?: 0L,
                )
            }
    }.getOrDefault(emptyList())

    private fun write(debts: List<Debt>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(
                debts.joinToString("\n") {
                    listOf(it.nodeId, it.name, it.payout, it.micros.toString(), it.updatedAt.toString())
                        .joinToString(SEP)
                },
                StandardCharsets.UTF_8,
            )
        }.onFailure { PrismPlatform.log.warn("PrismVpnLedger", "Could not write the ledger: ${it.message}") }
    }

    /** Adds [micros] to what is owed to one device. */
    fun owe(nodeId: String, name: String, payout: String, micros: Long): List<Debt> {
        if (nodeId.isBlank() || micros <= 0L) return debts()
        val now = System.currentTimeMillis()
        val existing = debts()
        val row = existing.firstOrNull { it.nodeId == nodeId }
        val updated = if (row == null) {
            existing + Debt(nodeId, name.ifBlank { nodeId }, payout, micros, now)
        } else {
            existing.map {
                if (it.nodeId != nodeId) it
                else it.copy(
                    name = name.ifBlank { it.name },
                    payout = payout.ifBlank { it.payout },
                    micros = it.micros + micros,
                    updatedAt = now,
                )
            }
        }
        write(updated)
        return updated
    }

    /** Forgets a device's debt once it has been paid. */
    fun clear(nodeIds: Collection<String>): List<Debt> {
        val remaining = debts().filterNot { it.nodeId in nodeIds }
        write(remaining)
        return remaining
    }

    fun totalMicros(): Long = debts().sumOf { it.micros }

    fun describe(): String {
        val debts = debts()
        if (debts.isEmpty()) return "Nothing owed."
        return "${VpnRoute.formatMicros(totalMicros())} PSC owed to ${debts.size} device(s)"
    }
}
