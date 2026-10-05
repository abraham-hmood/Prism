package com.prism.launcher.mesh

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform

import com.prism.core.MeshUtils
import com.prism.core.json.JSONObject
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

    /**
     * Platform tags, as they travel on the wire.
     *
     * Deliberately lowercase literal strings and not an enum ordinal: the mesh carries builds of
     * different ages, and an ordinal shifts the moment somebody inserts a value. A tag a peer does
     * not recognise stays a tag it does not recognise.
     */
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_WINDOWS = "windows"
    const val PLATFORM_LINUX = "linux"
    const val PLATFORM_MACOS = "macos"

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
        /**
         * What kind of machine this is: `android`, `windows`, `linux`, `macos`, or blank when the
         * peer is running a build from before this field existed.
         *
         * In the gossip rather than behind a probe because the Cloud page's gaming tab filters on it,
         * and filtering a twenty-device mesh by asking every device would be twenty round trips to
         * draw one list.
         */
        val platform: String = "",
        /** Whether this peer has Steam installed. Only ever true on a desktop. */
        val hasSteam: Boolean = false,
    ) {
        val score: Double get() = ComputePricing.score(
            ramTotalBytes, vramBytes, swapRamBytes, swapVramBytes, cpuIndex, hasNpu, npuIndex
        )

        /**
         * A PC, laptop or desktop rather than a phone or tablet.
         *
         * A blank platform counts as NOT a desktop. Older builds do not announce the field, and every
         * one of those is a phone — Prism desktop did not exist on the mesh before this. Treating
         * unknown as desktop would fill the gaming list with phones that cannot host.
         */
        val isDesktop: Boolean get() = platform == PLATFORM_WINDOWS ||
            platform == PLATFORM_LINUX ||
            platform == PLATFORM_MACOS

        /** The only peers that can host a game: a real computer with a Steam install. */
        val canHostGames: Boolean get() = isDesktop && hasSteam

        fun platformLabel(): String = when (platform) {
            PLATFORM_WINDOWS -> "Windows PC"
            PLATFORM_LINUX -> "Linux PC"
            PLATFORM_MACOS -> "Mac"
            PLATFORM_ANDROID -> "Android device"
            else -> "unknown device"
        }

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

    fun localCapacity(): DeviceProbe.Capacity =
        lastLocal ?: DeviceProbe.measure().also { lastLocal = it }

    /**
     * Puts this device on the market.
     *
     * Re-measures rather than reusing a cached figure: free RAM is the number most likely to have
     * changed since the last announce, and it is the one a buyer is choosing on.
     */
    fun announce() {
        val capacity = DeviceProbe.measure().also { lastLocal = it }
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
            put("payout", MeshBridges.payoutAddress())
            // FROM THE HOOKS, NOT A CONSTANT, AND THAT WAS A REAL BUG.
            //
            // This read `PLATFORM_ANDROID` and `false` with a comment saying the module only ever
            // ran on Android. It stopped being true the moment the registry moved to :core: the
            // desktop announced itself as an Android device with no Steam, so it never appeared in
            // anybody's cloud-gaming list -- which is the one list it is the only kind of device
            // eligible for.
            //
            // Defaults are still Android's, so a platform that does not install a tag announces
            // what it used to. See `platformTag`.
            put("platform", platformTag())
            put("steam", hasSteam())
        }.toString()

        MeshTransport.announce(OPCODE_COMPUTE_ANNOUNCE, payload)
        PrismPlatform.log.info(TAG, "Announced compute capacity: ${capacity.deviceName}")
    }

    /** Takes this device off the market. An empty name is the withdrawal marker. */
    /**
     * What kind of machine this is, for [Peer.isDesktop] at the other end.
     *
     * A HOOK because the answer is a build-time fact about the host, not something :core can probe:
     * `os.name` says "Linux" on an Android device too. Defaults to Android, which is what this
     * module announced before the desktop existed, so a platform that forgets to install one is
     * wrong in the direction that was already true rather than in a new one.
     */
    @Volatile
    var platformTag: () -> String = { PLATFORM_ANDROID }

    /**
     * Whether this machine has Steam, which with [Peer.isDesktop] is the whole of [Peer.canHostGames].
     *
     * Defaults to false. A phone has no Steam, and claiming otherwise would put a device in other
     * people's cloud-gaming lists as a host that refuses every session.
     */
    @Volatile
    var hasSteam: () -> Boolean = { false }

    fun withdraw() {
        MeshTransport.announce(OPCODE_COMPUTE_ANNOUNCE, JSONObject().apply {
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
                    platform = json.optString("platform"),
                    hasSteam = json.optBoolean("steam"),
                )
            }
            publish()
        }.onFailure { PrismPlatform.log.warn(TAG, "Bad capability announcement from $peerIp") }
    }

    /** A peer reporting that its RPC server is up. */
    fun ingestRpcReady(peerIp: String, payload: String) {
        runCatching {
            val port = JSONObject(payload).optInt("port")
            if (port > 0) {
                rpcPorts[peerIp] = port
                PrismPlatform.log.info(TAG, "$peerIp is serving compute on port $port")
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
