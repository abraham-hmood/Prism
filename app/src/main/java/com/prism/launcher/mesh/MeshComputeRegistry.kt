package com.prism.launcher.mesh

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.json.JSONObject
import com.prism.launcher.ModelPayments
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigInteger

/**
 * Who on the mesh has compute to sell, and what it costs.
 *
 * Shaped like [P2pModelRegistry] -- a local map, gossiped mesh-wide, entries expiring if a peer
 * stops re-announcing -- because the same reasoning applies: a stale "this phone has 8 GB free"
 * outliving the phone is worse than not knowing. Nothing here is persisted.
 *
 * ## What is announced, and what is derived
 *
 * A peer announces its **capacity** -- memory, swap, cores, clock, whether it has an NPU -- and its
 * payout address. It does not announce a price. Every device computes every other device's price
 * locally with [ComputePricing], so the number shown next to a peer is one this device worked out
 * from that peer's declared hardware rather than one the peer asserted. See ComputePricing for why
 * that distinction matters.
 *
 * ## Announcing is opting in
 *
 * A device appears in other people's markets only once it has called [announce]. Turning the
 * compute market on is what does that; a device that has never opted in is invisible and cannot be
 * sent work.
 */
object MeshComputeRegistry {

    private const val TAG = "MeshCompute"

    /** Capability gossip. 0x20 onwards is unused by every other subsystem -- see PrismMeshService. */
    const val OPCODE_COMPUTE_ANNOUNCE: Byte = 0x20

    /** "Please stand up an RPC server for me." Sent to one peer, not broadcast. */
    const val OPCODE_RPC_REQUEST: Byte = 0x21

    /** "It is listening on this port." The reply to [OPCODE_RPC_REQUEST]. */
    const val OPCODE_RPC_READY: Byte = 0x22

    /** A device telling the mesh it owes for work already done. See [ComputeDebtLedger]. */
    const val OPCODE_DEBT_ANNOUNCE: Byte = 0x23

    /**
     * How long a peer's announcement is trusted.
     *
     * Longer than [P2pModelRegistry]'s ten minutes because capacity changes slowly and a peer
     * dropping out of the market mid-scroll is more annoying than a slightly stale RAM figure.
     */
    private const val STALE_MS = 15 * 60 * 1000L

    /** One peer as the market sees it. Prices are local derivations, not claims. */
    data class Peer(
        val peerIp: String,
        val deviceName: String,
        val ramTotalBytes: Long,
        val ramFreeBytes: Long,
        val vramBytes: Long,
        val swapRamBytes: Long,
        val swapVramBytes: Long,
        val hasNpu: Boolean,
        val npuIndex: Int,
        val cpuCores: Int,
        val cpuMaxKhz: Int,
        val cpuIndex: Int,
        val payoutAddress: String,
        val timestamp: Long,
    ) {
        val score: Double get() = ComputePricing.score(
            ramTotalBytes, vramBytes, swapRamBytes, swapVramBytes, cpuIndex, hasNpu, npuIndex
        )

        val pricePerRequest: BigInteger get() = ComputePricing.perRequest(score)
        val pricePerMillionTokens: BigInteger get() = ComputePricing.perMillionTokens(score)

        /** True once this peer has told us which port its RPC server is on. */
        val rpcEndpoint: String? get() = rpcPorts[peerIp]?.let { "$peerIp:$it" }
    }

    /** What the market list can be ordered by. The label is what the dropdown shows. */
    enum class SortKey(val label: String) {
        OVERALL("Overall"),
        RAM("RAM"),
        VRAM("VRAM"),
        SWAP_RAM("Swapfile RAM"),
        SWAP_VRAM("Swapfile VRAM"),
        CPU("Processor speed"),
        NPU_PRESENT("Has an NPU"),
        NPU_SPEED("NPU speed"),
    }

    private val peers = mutableMapOf<String, Peer>()
    private val rpcPorts = mutableMapOf<String, Int>()

    private val _market = MutableStateFlow<List<Peer>>(emptyList())
    val market: StateFlow<List<Peer>> = _market

    /** This device's own capacity, remeasured on each announce. */
    @Volatile private var lastLocal: DeviceProbe.Capacity? = null

    fun localCapacity(context: Context): DeviceProbe.Capacity =
        lastLocal ?: DeviceProbe.measure(context).also { lastLocal = it }

    /**
     * Puts this device on the market.
     *
     * Re-measures rather than reusing a cached figure: free RAM is the number most likely to have
     * changed since the last announce, and it is the one a buyer is choosing on.
     */
    fun announce(context: Context) {
        val capacity = DeviceProbe.measure(context).also { lastLocal = it }
        val payload = JSONObject().apply {
            put("name", capacity.deviceName)
            put("ram", capacity.ramTotalBytes)
            put("ram_free", capacity.ramFreeBytes)
            put("vram", capacity.vramBytes)
            put("swap_ram", capacity.swapRamBytes)
            put("swap_vram", capacity.swapVramBytes)
            put("npu", capacity.hasNpu)
            put("npu_index", capacity.npuIndex)
            put("cores", capacity.cpuCores)
            put("khz", capacity.cpuMaxKhz)
            put("cpu_index", capacity.cpuIndex)
            put("payout", ModelPayments.address().orEmpty())
        }.toString()

        PrismMeshService.broadcastToOthers(OPCODE_COMPUTE_ANNOUNCE, payload)
        PrismLogger.logInfo(TAG, "Announced compute capacity: ${capacity.deviceName}")
    }

    /** Takes this device off the market. An empty name is the withdrawal marker. */
    fun withdraw() {
        PrismMeshService.broadcastToOthers(OPCODE_COMPUTE_ANNOUNCE, JSONObject().apply {
            put("name", "")
        }.toString())
    }

    fun ingestFromPeer(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val name = json.optString("name")
            if (name.isBlank()) {
                peers.remove(peerIp)
                rpcPorts.remove(peerIp)
            } else {
                peers[peerIp] = Peer(
                    peerIp = peerIp,
                    deviceName = name,
                    ramTotalBytes = json.optLong("ram"),
                    ramFreeBytes = json.optLong("ram_free"),
                    vramBytes = json.optLong("vram"),
                    swapRamBytes = json.optLong("swap_ram"),
                    swapVramBytes = json.optLong("swap_vram"),
                    hasNpu = json.optBoolean("npu"),
                    npuIndex = json.optInt("npu_index"),
                    cpuCores = json.optInt("cores"),
                    cpuMaxKhz = json.optInt("khz"),
                    cpuIndex = json.optInt("cpu_index"),
                    payoutAddress = json.optString("payout"),
                    timestamp = System.currentTimeMillis(),
                )
            }
            publish()
        }.onFailure { PrismLogger.logWarning(TAG, "Bad capability announcement from $peerIp") }
    }

    /** A peer reporting that its RPC server is up. */
    fun ingestRpcReady(peerIp: String, payload: String) {
        runCatching {
            val port = JSONObject(payload).optInt("port")
            if (port > 0) {
                rpcPorts[peerIp] = port
                PrismLogger.logInfo(TAG, "$peerIp is serving compute on port $port")
                publish()
            }
        }
    }

    fun rpcPortOf(peerIp: String): Int? = rpcPorts[peerIp]

    /** Everyone currently on the market, freshest figures only. */
    fun all(): List<Peer> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        val fresh = peers.filterValues { it.timestamp >= cutoff }
        if (fresh.size != peers.size) {
            peers.keys.retainAll(fresh.keys)
            publish()
        }
        return fresh.values.toList()
    }

    /**
     * The market list, strongest first.
     *
     * Always descending: this is a list of what each device can offer, and "best" is unambiguous
     * for every key here. Ties fall through to the overall score so the order is stable rather than
     * shuffling between refreshes -- a list whose rows move when nothing changed is unusable for
     * picking something out of.
     */
    fun sorted(key: SortKey): List<Peer> {
        val comparator = when (key) {
            SortKey.OVERALL -> compareByDescending<Peer> { it.score }
            SortKey.RAM -> compareByDescending<Peer> { it.ramTotalBytes }
            SortKey.VRAM -> compareByDescending<Peer> { it.vramBytes }
            SortKey.SWAP_RAM -> compareByDescending<Peer> { it.swapRamBytes }
            SortKey.SWAP_VRAM -> compareByDescending<Peer> { it.swapVramBytes }
            SortKey.CPU -> compareByDescending<Peer> { it.cpuIndex }
            SortKey.NPU_PRESENT -> compareByDescending<Peer> { it.hasNpu }
            SortKey.NPU_SPEED -> compareByDescending<Peer> { if (it.hasNpu) it.npuIndex else -1 }
        }
        return all().sortedWith(comparator.thenByDescending { it.score }.thenBy { it.peerIp })
    }

    /** The single best peer for [key], for the market's automatic mode. */
    fun best(key: SortKey): Peer? = sorted(key).firstOrNull()

    private fun publish() {
        _market.value = peers.values.toList()
    }

    /** This device's mesh address, so the UI can tell "me" apart from a peer. */
    fun localIp(): String = MeshUtils.getLocalMeshIp()
}
