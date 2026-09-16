package com.prism.launcher.wallet

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.CoinSpec
import java.io.File
import java.security.SecureRandom

/**
 * Runs a blockchain node on the device, so solo mining does not need somebody else's server.
 *
 * ## Storage is the entire problem, and pruning is only half a solution
 *
 * A full node stores every block ever mined: Bitcoin is past 600 GB, and that number only grows.
 * Pruned mode keeps the recent blocks and discards the rest once validated, which brings Bitcoin
 * down to a few gigabytes -- small enough for a phone. So when Prism hosts its own node, pruning is
 * FORCED on every chain that supports it; there is no reason to offer the unpruned option on a
 * device with 128 GB of shared storage.
 *
 * PRUNING DOES NOT REDUCE THE DOWNLOAD. This is the part that surprises people and it is worth
 * being blunt: a pruned node still fetches and validates the entire chain history once, then throws
 * the old blocks away. For Bitcoin that is several hundred gigabytes over the network and many
 * hours to days of CPU on a phone. Pruning saves disk, not bandwidth and not time.
 *
 * Chains that cannot prune -- Zcash is the example among the coins here, because its shielded pool
 * needs the full note-commitment history -- need the whole chain on disk, which on a phone means an
 * external drive over USB-C. [usbDrive] looks for one and [readiness] refuses to start without it.
 *
 * ## The node binaries are NOT bundled in this build
 *
 * Everything around the node is implemented: the pruning decision, the drive detection, the
 * configuration file, the process launch and the RPC handshake. What is missing is the node
 * executable itself. `bitcoind` and its forks are large C++ programs that must be cross-compiled
 * for arm64 and shipped in `jniLibs` (the same mechanism the virtualization page already uses for
 * QEMU), and that is a build-system change rather than a code change.
 *
 * [readiness] therefore reports [Readiness.BinaryMissing] rather than pretending. A node manager
 * that silently did nothing while claiming to sync would be worse than one that says what it needs.
 */
object PrismNodeManager {

    private const val TAG = "PrismNode"

    /**
     * A "1 TB" drive holds 1,000,000,000,000 bytes, which is 931 GiB -- so a threshold expressed
     * as 1 TiB would reject every 1 TB drive on the market. This is set below that on purpose.
     */
    const val MIN_DRIVE_BYTES = 900_000_000_000L

    /** Pruned target per chain, in MiB. 2 GiB leaves headroom over Bitcoin Core's 550 MiB floor. */
    private const val PRUNE_TARGET_MIB = 2048

    /**
     * Per-chain node details. In the Android module rather than :core for the same reason the
     * explorer endpoints are: this changes with releases and packaging, not with cryptography.
     */
    private data class NodeSpec(
        /** The executable's name inside jniLibs, which Android requires to look like a library. */
        val binary: String,
        val configFile: String,
        val rpcPort: Int,
        /** Rough on-disk size once synced, pruned where the chain allows it. */
        val approximateBytes: Long,
    )

    private val nodes = mapOf(
        "BTC" to NodeSpec("libbitcoind.so", "bitcoin.conf", 8332, 7L * 1024 * 1024 * 1024),
        "LTC" to NodeSpec("liblitecoind.so", "litecoin.conf", 9332, 5L * 1024 * 1024 * 1024),
        "DOGE" to NodeSpec("libdogecoind.so", "dogecoin.conf", 22555, 6L * 1024 * 1024 * 1024),
        "BCH" to NodeSpec("libbitcoind-bch.so", "bitcoin.conf", 8332, 7L * 1024 * 1024 * 1024),
        "DASH" to NodeSpec("libdashd.so", "dash.conf", 9998, 4L * 1024 * 1024 * 1024),
        "DGB" to NodeSpec("libdigibyted.so", "digibyte.conf", 14022, 6L * 1024 * 1024 * 1024),
        "VTC" to NodeSpec("libvertcoind.so", "vertcoin.conf", 5888, 3L * 1024 * 1024 * 1024),
        "RVN" to NodeSpec("libravend.so", "raven.conf", 8766, 8L * 1024 * 1024 * 1024),
        // Unpruneable, and therefore the drive case.
        "ZEC" to NodeSpec("libzcashd.so", "zcash.conf", 8232, 300L * 1024 * 1024 * 1024),
    )

    /** What is stopping this coin's node from starting, if anything. */
    sealed class Readiness {
        /** Good to go. [pruned] says whether the chain is being kept in pruned mode. */
        data class Ready(val dataDir: File, val pruned: Boolean) : Readiness()

        /** The chain cannot prune and no large enough drive is attached. */
        data class NeedsUsbDrive(val coin: CoinSpec) : Readiness()

        /** Everything else is in place; the node executable is not shipped in this build. */
        data class BinaryMissing(val binary: String) : Readiness()

        data class Unsupported(val reason: String) : Readiness()
    }

    // ── External storage ───────────────────────────────────────────────────

    data class Drive(val path: File, val totalBytes: Long, val freeBytes: Long) {
        val isLargeEnough: Boolean get() = totalBytes >= MIN_DRIVE_BYTES
        fun describe(): String = "%.1f TB".format(totalBytes / 1_000_000_000_000.0)
    }

    /**
     * The largest removable volume attached, or null.
     *
     * USES `getExternalFilesDirs` rather than `StorageManager` paths, because it is the only route
     * that yields a path this app can actually WRITE to on every version since API 26. A
     * `StorageVolume` tells you a drive exists; an app-specific directory on it is somewhere a node
     * can put a datadir without needing the all-files permission or a SAF document tree.
     *
     * Index 0 is always internal storage, so it is skipped -- everything after it is removable.
     */
    fun usbDrive(context: Context): Drive? {
        val candidates = context.getExternalFilesDirs(null)
            .filterNotNull()
            .drop(1)
            .filter { it.exists() || it.mkdirs() }

        val removable = candidates.filter { dir ->
            // Belt and braces: confirm the volume really is removable where the API can say so.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    sm.getStorageVolume(dir)?.isRemovable ?: true
                }.getOrDefault(true)
            } else true
        }

        return removable.mapNotNull { dir ->
            runCatching {
                val stat = StatFs(dir.absolutePath)
                Drive(dir, stat.totalBytes, stat.availableBytes)
            }.getOrNull()
        }.maxByOrNull { it.totalBytes }
    }

    fun hasLargeDrive(context: Context): Boolean = usbDrive(context)?.isLargeEnough == true

    // ── Readiness ──────────────────────────────────────────────────────────

    /**
     * Decides where this coin's chain would live and whether it can start.
     *
     * Pruning is not offered as a choice: if the chain supports it, it is used. On a phone the
     * alternative is filling the device, and a node that fills the user's storage while they are
     * not looking is not a feature.
     */
    fun readiness(context: Context, coin: CoinSpec): Readiness {
        val spec = nodes[coin.symbol.uppercase()]
            ?: return Readiness.Unsupported("Prism has no node for ${coin.symbol}.")

        val dataDir: File
        val pruned: Boolean

        if (coin.supportsPruning) {
            dataDir = File(context.filesDir, "nodes/${coin.symbol.lowercase()}")
            pruned = true
        } else {
            val drive = usbDrive(context)
            if (drive == null || !drive.isLargeEnough) return Readiness.NeedsUsbDrive(coin)
            dataDir = File(drive.path, "nodes/${coin.symbol.lowercase()}")
            pruned = false
        }

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            return Readiness.Unsupported("Could not create a data directory at $dataDir.")
        }
        if (!binaryFor(context, spec).exists()) return Readiness.BinaryMissing(spec.binary)

        return Readiness.Ready(dataDir, pruned)
    }

    /** The exact wording specified for a chain that cannot be pruned onto internal storage. */
    fun usbDriveMessage(coin: CoinSpec): String =
        "This crypto doesn't support pruned nodes, you need a 1-2 terabyte USB drive to mine " +
            coin.name

    fun prunableSymbols(): List<String> =
        nodes.keys.mapNotNull { CoinRegistry.bySymbol(it) }.filter { it.supportsPruning }
            .map { it.symbol }

    // ── Running the node ───────────────────────────────────────────────────

    @Volatile
    private var process: Process? = null

    @Volatile
    var runningCoin: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = process?.isAlive == true

    /** Where solo mining should point when Prism is hosting. Always loopback: see [start]. */
    fun rpcUrl(coin: CoinSpec): String {
        val port = nodes[coin.symbol.uppercase()]?.rpcPort ?: return ""
        return "http://127.0.0.1:$port"
    }

    /**
     * Credentials for the bundled node.
     *
     * Taken from the user's setting when they set one -- that field changes meaning under
     * self-hosting -- and otherwise generated once and kept, so the RPC port is never left open
     * with a blank or guessable password.
     */
    fun credentials(): String {
        val configured = PrismSettings.getSoloNodeCredentials()
        if (configured.contains(':') && configured.substringAfter(':').isNotBlank()) return configured

        val generated = "prism:" + SecureRandom().let { rng ->
            ByteArray(24).also { rng.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        }
        PrismSettings.setSoloNodeCredentials(generated)
        return generated
    }

    private fun binaryFor(context: Context, spec: NodeSpec): File =
        File(context.applicationInfo.nativeLibraryDir, spec.binary)

    /**
     * Writes the configuration and launches the node.
     *
     * BOUND TO LOOPBACK ONLY, and this is not negotiable: an RPC port reachable from the network
     * is a remote-control interface for a wallet. `rpcbind=127.0.0.1` plus `rpcallowip=127.0.0.1`
     * keeps it reachable only by this device.
     *
     * `server=1` enables the RPC interface at all, and `prune=` is written whenever the chain
     * allows it -- Bitcoin Core refuses to start with both `prune` and `txindex`, so nothing here
     * ever asks for both.
     */
    fun start(context: Context, coin: CoinSpec): Readiness {
        val state = readiness(context, coin)
        if (state !is Readiness.Ready) return state

        val spec = nodes.getValue(coin.symbol.uppercase())
        stop()

        val auth = credentials().split(":", limit = 2)
        val config = buildString {
            appendLine("server=1")
            appendLine("listen=1")
            appendLine("rpcbind=127.0.0.1")
            appendLine("rpcallowip=127.0.0.1")
            appendLine("rpcport=${spec.rpcPort}")
            appendLine("rpcuser=${auth.getOrElse(0) { "prism" }}")
            appendLine("rpcpassword=${auth.getOrElse(1) { "" }}")
            if (state.pruned) {
                appendLine("prune=$PRUNE_TARGET_MIB")
            }
            // Phones have far less memory than the defaults assume; an oversized cache is the
            // fastest way to get the node killed by the low-memory reaper mid-sync.
            appendLine("dbcache=256")
            appendLine("maxconnections=16")
        }

        return try {
            File(state.dataDir, spec.configFile).writeText(config)
            val builder = ProcessBuilder(
                binaryFor(context, spec).absolutePath,
                "-datadir=${state.dataDir.absolutePath}",
                "-conf=${File(state.dataDir, spec.configFile).absolutePath}",
            ).redirectErrorStream(true)

            process = builder.start()
            runningCoin = coin.symbol
            lastError = null
            PrismLogger.logSuccess(
                TAG,
                "Started ${coin.symbol} node, ${if (state.pruned) "pruned" else "full"}, at ${state.dataDir}"
            )
            state
        } catch (e: Exception) {
            lastError = e.message
            PrismLogger.logError(TAG, "Could not start the ${coin.symbol} node", e)
            Readiness.Unsupported(e.message ?: "The node process failed to start.")
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        runningCoin = ""
    }

    /** Human-readable size estimate, for warning before a multi-hour sync begins. */
    fun estimatedSize(coin: CoinSpec): String {
        val bytes = nodes[coin.symbol.uppercase()]?.approximateBytes ?: return "unknown"
        return if (bytes >= 1024L * 1024 * 1024 * 1024) "%.1f TB".format(bytes / 1024.0 / 1024 / 1024 / 1024)
        else "%.0f GB".format(bytes / 1024.0 / 1024 / 1024)
    }
}
