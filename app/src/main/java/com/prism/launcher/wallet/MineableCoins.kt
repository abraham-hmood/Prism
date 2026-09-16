package com.prism.launcher.wallet

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.AddressScheme
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.NativeBuildPlan
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What can be mined, on this device, right now.
 *
 * ## Three different questions, and conflating them is how a mining UI lies
 *
 *   1. Is the coin mineable AT ALL? Ethereum is not, and has not been since the Merge in September
 *      2022 -- it is proof-of-stake, and no amount of hashing produces an ETH block. This is worth
 *      being blunt about because "mine Ethereum on Android" apps still exist, and every one of them
 *      is either mining something else or mining nothing.
 *   2. Can PRISM mine it? Only the algorithms in [MiningAlgorithms.supported] have an
 *      implementation here. RandomX -- the one algorithm phones are genuinely competitive at --
 *      needs a native library this build does not ship.
 *   3. Is it WORTH mining on a phone? Essentially never, and the estimate says so per coin rather
 *      than leaving the user to discover it over a week of hot battery.
 *
 * A coin is offered only when 1 and 2 both hold, and 3 is reported honestly alongside it.
 *
 * ## Discovery
 *
 * The built-in list is the fallback, not the source of truth: which coins use which algorithm
 * changes as chains fork and retarget. [refresh] pulls the current picture from a public mining
 * data API and merges anything new whose algorithm Prism can actually execute. Offline, or when
 * the endpoint is unreachable, the built-in list stands in and [lastRefreshFailed] says so instead
 * of silently showing stale data as though it were fresh.
 */
object MineableCoins {

    private const val TAG = "PrismMining"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * A coin that can be mined, with somewhere to mine it.
     *
     * [poolHost]/[poolPort] are a working public Stratum endpoint. A pool is required rather than
     * optional -- see [StratumClient] for why solo mining a phone is not a real option.
     */
    /** One Stratum endpoint. */
    data class Pool(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"
    }

    data class Mineable(
        val symbol: String,
        val name: String,
        val algorithm: String,
        val poolHost: String,
        val poolPort: Int,
        /** False when the algorithm has no implementation in this build. */
        val runnable: Boolean,
        val note: String = "",
        /**
         * Endpoints to try after [poolHost] fails, in order.
         *
         * TWO REASONS THIS EXISTS, and neither is hypothetical. Pools close -- digihash.co, which
         * this shipped as DigiByte's only endpoint, stopped answering and took DGB mining with it.
         * And plenty of ISPs block mining pools outright, so a device that reaches one operator may
         * not reach another from the same network. A single hardcoded host turns either into "mining
         * is broken" with nothing the user can do.
         *
         * These are not verified from here: whether a given pool is up, still free to join, or
         * reachable on a particular network can only be discovered by trying it, which is what the
         * failover does.
         */
        val fallbacks: List<Pool> = emptyList(),
    ) {
        /** Every endpoint to try, primary first. */
        fun pools(): List<Pool> =
            (listOf(Pool(poolHost, poolPort)) + fallbacks).filter { it.host.isNotBlank() }
    }

    @Volatile
    var lastRefreshFailed: Boolean = false
        private set

    @Volatile
    private var discovered: List<Mineable> = emptyList()

    /**
     * The shipped list.
     *
     * Pools chosen for having open, no-registration Stratum ports that accept a wallet address as
     * the username, because a mining tab that first requires signing up somewhere is not a mining
     * tab a phone user will ever finish setting up.
     */
    private val builtIn: List<Mineable> = listOf(
        Mineable(
            "BTC", "Bitcoin", MiningAlgorithms.SHA256D, "solo.ckpool.org", 3333, true,
            "A phone reaches a few MH/s against a network of hundreds of EH/s. This works and " +
                "will realistically never find a share.",
            fallbacks = listOf(Pool("stratum.braiins.com", 3333), Pool("btc.viabtc.io", 3333)),
        ),
        Mineable(
            "LTC", "Litecoin", MiningAlgorithms.SCRYPT, "litecoinpool.org", 3333, true,
            "Scrypt is memory-hard, so the ASIC gap is smaller than Bitcoin's -- still around " +
                "10^5. Shares are conceivable over very long periods.",
            fallbacks = listOf(Pool("ltc.viabtc.io", 3333), Pool("stratum.aikapool.com", 7951)),
        ),
        Mineable(
            "DOGE", "Dogecoin", MiningAlgorithms.SCRYPT, "stratum.aikapool.com", 7915, true,
            "Merge-mined with Litecoin on most pools; the same scrypt work counts for both.",
            fallbacks = listOf(Pool("doge.viabtc.io", 3333), Pool("stratum.zergpool.com", 4233)),
        ),
        Mineable(
            "BCH", "Bitcoin Cash", MiningAlgorithms.SHA256D, "solo.ckpool.org", 3333, true,
            "Same algorithm and the same futility as Bitcoin.",
            fallbacks = listOf(Pool("bch.viabtc.io", 3333)),
        ),
        Mineable(
            // digihash.co stopped answering, which is what prompted the fallback list. The
            // official pool moved to digihash.digibyte.io on the same scrypt port.
            "DGB", "DigiByte", MiningAlgorithms.SCRYPT, "digihash.digibyte.io", 3012, true,
            "Multi-algorithm chain; Prism uses its scrypt branch.",
            fallbacks = listOf(
                Pool("stratum.zergpool.com", 4233),
                Pool("dgb-scrypt.mining-dutch.nl", 3253),
            ),
        ),
        Mineable(
            "XMR", "Monero", MiningAlgorithms.RANDOMX, "pool.supportxmr.com", 3333, false,
            "THE one coin phones are actually competitive at -- RandomX is deliberately " +
                "CPU-friendly, and Prism ships it compiled for every Android architecture.",
            fallbacks = listOf(Pool("xmr.2miners.com", 2222), Pool("pool.hashvault.pro", 3333)),
        ),
        Mineable(
            PrismCoinConsensus.SYMBOL, PrismCoinConsensus.NAME, MiningAlgorithms.SHA256D,
            "", 0, true,
            "Prism's own chain, mined over the mesh rather than through a pool. One PSC is " +
                "minted per hour of work by construction — the block reward is derived from the " +
                "difficulty it was mined at.",
        ),
    )

    /**
     * Everything known, discovery merged over the built-ins, with runnability resolved LAST.
     *
     * Runnability is not a fixed property of a coin -- it depends on what this device can do right
     * now. RandomX has no bundled library, but it stops being a dead end the moment on-device
     * compilation is switched on, because Prism can then build it. So the flag is recomputed on
     * every read rather than baked into the table.
     */
    fun all(): List<Mineable> {
        val bySymbol = LinkedHashMap<String, Mineable>()
        for (m in builtIn) bySymbol[m.symbol] = m
        for (m in discovered) bySymbol.putIfAbsent(m.symbol, m)
        return bySymbol.values.map { it.copy(runnable = resolveRunnable(it)) }
    }

    /**
     * Whether this device can mine [m] as things currently stand.
     *
     * Three ways to be runnable: the algorithm is implemented in the JVM already; Prism has already
     * built the library for it; or the user has enabled on-device compilation and a build plan
     * exists, in which case tapping it starts a build rather than showing a refusal.
     */
    private fun resolveRunnable(m: Mineable): Boolean {
        // A built-in implementation wins: RandomX now ships compiled for every ABI, so the
        // on-device compiler is no longer part of this answer for it.
        if (MiningAlgorithms.isSupported(m.algorithm)) return true
        if (PrismSettings.getCompiledLibrary(m.symbol).isNotBlank()) return true
        return PrismSettings.getExperimentalCompiler() &&
            NativeBuildPlan.forCoin(m.symbol)?.feasible == true
    }

    /**
     * True when tapping this coin should start a BUILD rather than start mining.
     *
     * False for anything with a shipped implementation, which is why enabling the experimental
     * compiler no longer offers to rebuild RandomX -- there is nothing to build.
     */
    fun needsCompilation(m: Mineable): Boolean =
        !MiningAlgorithms.isSupported(m.algorithm) &&
            PrismSettings.getCompiledLibrary(m.symbol).isBlank()

    /** Only what this build can actually execute -- what the mining tab offers as startable. */
    fun runnable(): List<Mineable> = all().filter { it.runnable }

    /**
     * Refreshes the algorithm-to-coin picture from a public mining API.
     *
     * Merges rather than replaces: a live list is better information about what EXISTS, but the
     * built-in entries carry the pool endpoints and the honesty notes, which no API provides.
     * Anything discovered whose algorithm Prism cannot run is still surfaced -- marked
     * unrunnable -- because "this coin exists but Prism can't mine it" is useful, and quietly
     * dropping it looks like the coin does not exist.
     */
    fun refresh(): List<Mineable> {
        lastRefreshFailed = false
        val url = PrismSettings.getMiningDiscoveryUrl()
        if (url.isBlank()) return all()

        val body = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Prism-Wallet").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()

        if (body.isNullOrBlank()) {
            lastRefreshFailed = true
            PrismLogger.logError(TAG, "Mineable-coin discovery failed; using the built-in list", null)
            return all()
        }

        val found = runCatching {
            val json = JSONObject(body)
            val out = ArrayList<Mineable>()
            for (key in json.keys()) {
                val entry = json.optJSONObject(key) ?: continue
                val algorithm = entry.optString("algorithm", "").lowercase()
                if (algorithm.isBlank()) continue
                val symbol = entry.optString("coin", key).uppercase()
                out.add(
                    Mineable(
                        symbol = symbol,
                        name = entry.optString("name", symbol),
                        algorithm = algorithm,
                        // Discovery gives the algorithm, not a pool. Reusing a built-in pool for a
                        // coin it does not serve would just produce rejected shares.
                        poolHost = builtIn.firstOrNull { it.symbol == symbol }?.poolHost ?: "",
                        poolPort = builtIn.firstOrNull { it.symbol == symbol }?.poolPort ?: 0,
                        runnable = MiningAlgorithms.isSupported(algorithm) &&
                            builtIn.any { it.symbol == symbol && it.poolHost.isNotEmpty() },
                        note = if (MiningAlgorithms.isSupported(algorithm)) ""
                        else "Prism has no implementation of $algorithm.",
                    )
                )
            }
            out
        }.getOrElse {
            lastRefreshFailed = true
            PrismLogger.logError(TAG, "Discovery response could not be parsed", it)
            emptyList()
        }

        discovered = found
        return all()
    }

    /**
     * Makes sure every mineable coin has somewhere for its rewards to land, as specified.
     *
     * A pool pays to the address used as the Stratum username, so mining a coin with no wallet for
     * it means mining into nothing. Coins Prism has no built-in spec for are registered as custom
     * ones so they still get a real derived address rather than being skipped.
     */
    fun ensureWalletsExist() {
        for (m in all()) {
            if (CoinRegistry.bySymbol(m.symbol) == null) {
                // Unknown coin: assume a Bitcoin-style fork, which nearly all mineable coins are.
                CoinRegistry.addCustom(
                    CoinSpec(
                        symbol = m.symbol,
                        name = m.name,
                        coinType = 0,
                        scheme = AddressScheme.P2PKH(0x00),
                        decimals = 8,
                        isCustom = true,
                        miningAlgorithm = m.algorithm,
                    )
                )
            }
            if (WalletVault.enabledSymbols().none { it.equals(m.symbol, ignoreCase = true) }) {
                WalletVault.enableCoin(m.symbol)
            }
        }
        WalletVault.saveCustomCoins()
    }
}
