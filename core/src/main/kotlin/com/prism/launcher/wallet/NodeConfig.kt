package com.prism.launcher.wallet

import com.prism.launcher.PrismSettings
import java.security.SecureRandom

/**
 * What a blockchain node needs to be run, independent of who runs it. PHASE 86.
 *
 * ## Why this is shared and the launcher is not
 *
 * The decisions are identical on both platforms: which chains can prune, what the RPC port is, how
 * big the chain gets, and what goes in the config file. What differs is only whether the executable
 * can be run at all -- and that is the whole of the phase's point.
 *
 * ANDROID CANNOT RUN ONE, for two reasons that are both the operating system's: `bitcoind` is a large
 * C++ program that would have to be cross-compiled and shipped inside `jniLibs`, and even then the
 * kernel refuses to `execve` a file in an app's data directory. So `PrismNodeManager` reports
 * `BinaryMissing` and means it.
 *
 * A DESKTOP RUNS ONE AS AN ORDINARY SUBPROCESS. That inverts the situation: what is a documented gap
 * on the phone is a found-or-not-found check here, and the desktop becomes the node the phones point
 * at. See the desktop's own node host.
 *
 * ## PRUNING IS FORCED, AND PRUNING DOES NOT REDUCE THE DOWNLOAD
 *
 * This is the part that surprises people and it is worth being blunt everywhere it appears. A pruned
 * node still fetches and VALIDATES the entire chain history once, then throws the old blocks away.
 * For Bitcoin that is several hundred gigabytes over the network and many hours of CPU. Pruning saves
 * disk; it saves neither bandwidth nor time.
 *
 * Chains that cannot prune need the whole chain on disk. Zcash is the example here, because its
 * shielded pool needs the full note-commitment history.
 */
object NodeConfig {

    /** Pruned target in MiB. 2 GiB leaves headroom over Bitcoin Core's 550 MiB floor. */
    const val PRUNE_TARGET_MIB = 2048

    /**
     * One chain's node.
     *
     * @param binary the executable's name. The Android build needs it to look like a library,
     *   because `jniLibs` is the only directory an APK may ship an executable in; a desktop looks
     *   for [desktopBinary] on PATH instead.
     */
    data class Spec(
        val symbol: String,
        val name: String,
        val binary: String,
        val desktopBinary: String,
        val configFile: String,
        val rpcPort: Int,
        /** Rough on-disk size once synced, pruned where the chain allows it. */
        val approximateBytes: Long,
        /** False for a chain whose history cannot be discarded. */
        val prunable: Boolean,
    )

    val SPECS: List<Spec> = listOf(
        Spec("BTC", "Bitcoin", "libbitcoind.so", "bitcoind", "bitcoin.conf", 8332,
            7L * 1024 * 1024 * 1024, prunable = true),
        Spec("LTC", "Litecoin", "liblitecoind.so", "litecoind", "litecoin.conf", 9332,
            5L * 1024 * 1024 * 1024, prunable = true),
        Spec("DOGE", "Dogecoin", "libdogecoind.so", "dogecoind", "dogecoin.conf", 22555,
            6L * 1024 * 1024 * 1024, prunable = true),
        Spec("BCH", "Bitcoin Cash", "libbitcoind-bch.so", "bitcoind", "bitcoin.conf", 8332,
            7L * 1024 * 1024 * 1024, prunable = true),
        Spec("DASH", "Dash", "libdashd.so", "dashd", "dash.conf", 9998,
            4L * 1024 * 1024 * 1024, prunable = true),
        Spec("DGB", "DigiByte", "libdigibyted.so", "digibyted", "digibyte.conf", 14022,
            6L * 1024 * 1024 * 1024, prunable = true),
        Spec("VTC", "Vertcoin", "libvertcoind.so", "vertcoind", "vertcoin.conf", 5888,
            3L * 1024 * 1024 * 1024, prunable = true),
        Spec("RVN", "Ravencoin", "libravend.so", "ravend", "raven.conf", 8766,
            8L * 1024 * 1024 * 1024, prunable = true),
        // Unpruneable: the shielded pool needs the full note-commitment history.
        Spec("ZEC", "Zcash", "libzcashd.so", "zcashd", "zcash.conf", 8232,
            300L * 1024 * 1024 * 1024, prunable = false),
    )

    fun spec(symbol: String): Spec? = SPECS.firstOrNull { it.symbol.equals(symbol, true) }

    fun prunableSymbols(): List<String> = SPECS.filter { it.prunable }.map { it.symbol }

    /** Human-readable size estimate, for warning before a multi-hour sync begins. */
    fun estimatedSize(symbol: String): String {
        val bytes = spec(symbol)?.approximateBytes ?: return "unknown"
        return if (bytes >= 1024L * 1024 * 1024 * 1024) {
            String.format("%.1f TB", bytes / 1024.0 / 1024 / 1024 / 1024)
        } else {
            String.format("%.0f GB", bytes / 1024.0 / 1024 / 1024)
        }
    }

    /**
     * RPC credentials for a node Prism runs.
     *
     * Taken from the user's setting when they have one -- that field changes meaning under
     * self-hosting -- and otherwise GENERATED ONCE AND KEPT, so the RPC port is never left open with
     * a blank or guessable password. Twenty-four random bytes, which is more than the interface will
     * ever be brute-forced through.
     */
    fun credentials(): String {
        val configured = PrismSettings.getSoloNodeCredentials()
        if (configured.contains(':') && configured.substringAfter(':').isNotBlank()) return configured

        val generated = "prism:" + SecureRandom().let { rng ->
            ByteArray(24).also { rng.nextBytes(it) }
                .joinToString("") { String.format("%02x", it) }
        }
        PrismSettings.setSoloNodeCredentials(generated)
        return generated
    }

    /**
     * The node's configuration file.
     *
     * BOUND TO LOOPBACK ONLY, AND THAT IS NOT NEGOTIABLE: an RPC port reachable from the network is a
     * remote-control interface for a wallet. `rpcbind=127.0.0.1` plus `rpcallowip=127.0.0.1` keeps it
     * reachable only by this machine, and a mesh peer that wants to use it goes through Prism's own
     * authenticated relay rather than through an open port -- see the compute registry's RPC opcodes.
     *
     * `prune=` is written only when the chain allows it, because Bitcoin Core REFUSES TO START with
     * both `prune` and `txindex`, and nothing here ever asks for both.
     *
     * @param dbCacheMb the UTXO cache. A phone needs a small one or the low-memory reaper kills the
     *   node mid-sync; a desktop with gigabytes to spare syncs several times faster with a large one,
     *   which is the single biggest lever on how long the initial block download takes.
     */
    fun configFileContents(
        spec: Spec,
        pruned: Boolean,
        dbCacheMb: Int,
        maxConnections: Int,
    ): String {
        val auth = credentials().split(":", limit = 2)
        return buildString {
            appendLine("server=1")
            appendLine("listen=1")
            appendLine("rpcbind=127.0.0.1")
            appendLine("rpcallowip=127.0.0.1")
            appendLine("rpcport=" + spec.rpcPort)
            appendLine("rpcuser=" + auth.getOrElse(0) { "prism" })
            appendLine("rpcpassword=" + auth.getOrElse(1) { "" })
            if (pruned) appendLine("prune=" + PRUNE_TARGET_MIB)
            appendLine("dbcache=" + dbCacheMb)
            appendLine("maxconnections=" + maxConnections)
        }
    }

    /** Where solo mining should point when Prism is hosting. Always loopback: see [configFileContents]. */
    fun rpcUrl(symbol: String): String {
        val port = spec(symbol)?.rpcPort ?: return ""
        return "http://127.0.0.1:" + port
    }
}
