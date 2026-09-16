package com.prism.launcher.wallet

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.BitcoinTransaction
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.EvmTransaction
import com.prism.launcher.wallet.Utxo
import com.prism.launcher.wallet.WalletCrypto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/**
 * Everything the wallet needs from the outside world: balances, prices, fees and broadcast.
 *
 * PUBLIC INFRASTRUCTURE, NO ACCOUNTS, NO KEYS LEAVE THE DEVICE. Every call here sends an address or
 * an already-signed transaction. Signing happens locally in :core; a private key is never
 * serialised into a request, which is what "locally run" has to mean for a wallet to be worth
 * anything.
 *
 * THE PRIVACY COST IS REAL AND WORTH STATING: asking a public explorer for an address's balance
 * tells that explorer the address interests whoever is asking. This is how effectively every
 * light wallet works -- the alternative is downloading the chain -- but it is not nothing.
 */
object WalletNetwork {

    private const val TAG = "PrismWallet"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Where each coin is looked up.
     *
     * KEPT HERE, NOT IN :core, on purpose: endpoints rot far faster than the cryptography does,
     * and :core's job is the part that must never change. A dead URL should be a one-line edit in
     * the Android module, not a change to the module that derives keys.
     */
    private data class Endpoints(
        val blockchairChain: String = "",
        val evmRpc: String = "",
        val coingeckoId: String = "",
        /** A mempool.space-style REST base. Preferred: reports pending funds and is not gated. */
        val mempoolApi: String = "",
    )

    private val endpoints = mapOf(
        "BTC" to Endpoints(
            blockchairChain = "bitcoin", coingeckoId = "bitcoin",
            mempoolApi = "https://mempool.space/api",
        ),
        "LTC" to Endpoints(
            blockchairChain = "litecoin", coingeckoId = "litecoin",
            // A mempool.space instance for Litecoin. Handles ltc1 bech32 addresses and
            // reports unconfirmed funds, which Blockchair does not.
            mempoolApi = "https://litecoinspace.org/api",
        ),
        "DOGE" to Endpoints(blockchairChain = "dogecoin", coingeckoId = "dogecoin"),
        "BCH" to Endpoints(blockchairChain = "bitcoin-cash", coingeckoId = "bitcoin-cash"),
        "DASH" to Endpoints(blockchairChain = "dash", coingeckoId = "dash"),
        "ZEC" to Endpoints(blockchairChain = "zcash", coingeckoId = "zcash"),
        "DGB" to Endpoints(coingeckoId = "digibyte"),
        "VTC" to Endpoints(coingeckoId = "vertcoin"),
        "RVN" to Endpoints(coingeckoId = "ravencoin"),
        "ETH" to Endpoints(evmRpc = "https://eth.llamarpc.com", coingeckoId = "ethereum"),
        "USDC" to Endpoints(evmRpc = "https://eth.llamarpc.com", coingeckoId = "usd-coin"),
        "ETC" to Endpoints(evmRpc = "https://etc.rivet.link", coingeckoId = "ethereum-classic"),
        "BNB" to Endpoints(evmRpc = "https://bsc-dataseed.binance.org", coingeckoId = "binancecoin"),
        "MATIC" to Endpoints(evmRpc = "https://polygon-rpc.com", coingeckoId = "matic-network"),
        "AVAX" to Endpoints(evmRpc = "https://api.avax.network/ext/bc/C/rpc", coingeckoId = "avalanche-2"),
        "ARB" to Endpoints(evmRpc = "https://arb1.arbitrum.io/rpc", coingeckoId = "ethereum"),
        "OP" to Endpoints(evmRpc = "https://mainnet.optimism.io", coingeckoId = "ethereum"),
    )

    /** A coin Prism has no endpoint for still holds keys; it just cannot show a balance. */
    fun isQueryable(coin: CoinSpec): Boolean {
        val e = endpoints[coin.symbol.uppercase()] ?: return false
        return e.blockchairChain.isNotEmpty() || e.evmRpc.isNotEmpty()
    }

    // ── Balances ───────────────────────────────────────────────────────────

    /**
     * A balance, and enough context to explain it.
     *
     * [unconfirmed] MATTERS MORE THAN IT LOOKS. A payment that has been broadcast but not yet mined
     * is invisible to a confirmed-only balance, so a wallet that reports only confirmed funds tells
     * a user their money has not arrived when in fact it has and is waiting. That is exactly the
     * "I sent it and nothing showed up" experience, and it is a reporting bug rather than a lost
     * payment.
     */
    data class BalanceInfo(
        val confirmed: BigInteger,
        val unconfirmed: BigInteger,
        val source: String,
    ) {
        val total: BigInteger get() = confirmed.add(unconfirmed)
        val hasPending: Boolean get() = unconfirmed.signum() != 0
    }

    /** Balance in smallest units, or null if it could not be determined. */
    fun balance(coin: CoinSpec, address: String): BigInteger? = balanceInfo(coin, address)?.total

    /**
     * Looks a balance up, trying more than one explorer.
     *
     * TWO PROVIDERS, ORDERED, because the free tier of any single one rate-limits and then returns
     * nothing at all. A wallet whose balance depends on winning that lottery shows numbers that
     * come and go. The mempool.space-style API is tried first: it reports unconfirmed funds and
     * does not gate on a key.
     */
    fun balanceInfo(coin: CoinSpec, address: String): BalanceInfo? {
        val e = endpoints[coin.symbol.uppercase()] ?: return null
        return try {
            when {
                // A token's balance lives in a contract, not in the account, so it is read with a
                // call rather than eth_getBalance. Checked first because a token also carries an
                // evmRpc -- its host chain's -- and would otherwise be asked for its ETH balance.
                coin.tokenContract.isNotEmpty() && e.evmRpc.isNotEmpty() ->
                    erc20Balance(e.evmRpc, coin.tokenContract, address)

                e.evmRpc.isNotEmpty() -> {
                    val result = rpc(e.evmRpc, "eth_getBalance", listOf(address, "latest"))
                        ?: return null
                    BalanceInfo(
                        BigInteger(result.removePrefix("0x").ifEmpty { "0" }, 16),
                        BigInteger.ZERO,
                        "rpc",
                    )
                }
                else -> mempoolStyleBalance(e, address) ?: blockchairBalance(e, address)
            }
        } catch (ex: Exception) {
            PrismLogger.logError(TAG, "Balance lookup failed for ${coin.symbol}", ex)
            null
        }
    }

    /**
     * An ERC-20 balance, via `balanceOf(address)` on the token's contract.
     *
     * The call data is the ABI encoding by hand rather than through a library: a four-byte selector
     * -- the first bytes of keccak("balanceOf(address)") -- followed by the address left-padded to
     * a 32-byte word. One function with one fixed argument does not justify pulling in an ABI
     * encoder, and writing it out makes the wire format visible.
     *
     * There is no pending half. A token transfer is only visible once its transaction is mined, so
     * unlike the UTXO explorers there is nothing unconfirmed to report.
     */
    private fun erc20Balance(rpcUrl: String, contract: String, address: String): BalanceInfo? {
        val padded = address.removePrefix("0x").lowercase().padStart(64, '0')
        val data = BALANCE_OF_SELECTOR + padded
        val call = JSONObject().put("to", contract).put("data", data)
        val result = rpc(rpcUrl, "eth_call", listOf(call, "latest")) ?: return null
        val hex = result.removePrefix("0x").ifEmpty { "0" }
        val value = runCatching { BigInteger(hex, 16) }.getOrNull() ?: return null
        return BalanceInfo(value, BigInteger.ZERO, "erc20")
    }

    /** First four bytes of keccak256("balanceOf(address)"). */
    private const val BALANCE_OF_SELECTOR = "0x70a08231"

    /** mempool.space and its Litecoin sibling: funded minus spent, confirmed and pending apart. */
    private fun mempoolStyleBalance(e: Endpoints, address: String): BalanceInfo? {
        if (e.mempoolApi.isEmpty()) return null
        return runCatching {
            val body = get("${e.mempoolApi}/address/$address") ?: return null
            val json = JSONObject(body)
            val chain = json.optJSONObject("chain_stats") ?: return null
            val mempool = json.optJSONObject("mempool_stats")

            val confirmed = BigInteger.valueOf(chain.optLong("funded_txo_sum", 0L))
                .subtract(BigInteger.valueOf(chain.optLong("spent_txo_sum", 0L)))
            val pending = if (mempool == null) BigInteger.ZERO else
                BigInteger.valueOf(mempool.optLong("funded_txo_sum", 0L))
                    .subtract(BigInteger.valueOf(mempool.optLong("spent_txo_sum", 0L)))

            BalanceInfo(confirmed, pending, "mempool")
        }.getOrNull()
    }

    private fun blockchairBalance(e: Endpoints, address: String): BalanceInfo? {
        if (e.blockchairChain.isEmpty()) return null
        return runCatching {
            val body = get("https://api.blockchair.com/${e.blockchairChain}/dashboards/address/$address")
                ?: return null
            val data = JSONObject(body).optJSONObject("data") ?: return null
            // Blockchair keys the result by address, but normalises the form it echoes back. An
            // exact-key lookup silently returned null whenever it did -- so fall back to the only
            // entry present, which is all a single-address dashboard ever contains.
            val entry = data.optJSONObject(address)
                ?: data.keys().asSequence().firstNotNullOfOrNull { data.optJSONObject(it) }
                ?: return null
            val info = entry.optJSONObject("address") ?: return null
            BalanceInfo(
                BigInteger.valueOf(info.optLong("balance", 0L)),
                BigInteger.ZERO,
                "blockchair",
            )
        }.getOrNull()
    }

    /**
     * Fiat prices for several coins at once.
     *
     * One batched request rather than one per coin: the free price API rate-limits per call, and a
     * wallet list with a dozen coins would otherwise start by tripping the limit and showing no
     * prices at all.
     */
    fun prices(symbols: List<String>, fiat: String): Map<String, BigDecimal> {
        val ids = symbols.mapNotNull { endpoints[it.uppercase()]?.coingeckoId?.ifEmpty { null } }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            val body = get(
                "https://api.coingecko.com/api/v3/simple/price" +
                    "?ids=${ids.joinToString(",")}&vs_currencies=${fiat.lowercase()}"
            ) ?: return emptyMap()
            val json = JSONObject(body)
            val out = HashMap<String, BigDecimal>()
            for (symbol in symbols) {
                val id = endpoints[symbol.uppercase()]?.coingeckoId ?: continue
                val entry = json.optJSONObject(id) ?: continue
                val price = entry.optDouble(fiat.lowercase(), -1.0)
                if (price >= 0) out[symbol.uppercase()] = BigDecimal.valueOf(price)
            }
            out
        } catch (ex: Exception) {
            PrismLogger.logError(TAG, "Price lookup failed", ex)
            emptyMap()
        }
    }

    /**
     * USD to another currency.
     *
     * Needed by PrismCoin, whose valuations are computed in USD from world income and electricity
     * figures and then shown in the user's currency. Returns null rather than 1.0 on failure, so
     * callers can say which currency they are actually displaying instead of silently labelling
     * dollars as something else.
     */
    fun usdRateFor(currency: String): Double? {
        if (currency.equals("USD", ignoreCase = true)) return 1.0
        return runCatching {
            val body = get(
                "https://api.coingecko.com/api/v3/simple/price" +
                    "?ids=usd-coin&vs_currencies=${currency.lowercase()}"
            ) ?: return null
            // A dollar stablecoin's price in the target currency is the cleanest free proxy for a
            // USD rate on an API that has no forex endpoint.
            val rate = JSONObject(body).optJSONObject("usd-coin")
                ?.optDouble(currency.lowercase(), -1.0) ?: -1.0
            if (rate > 0) rate else null
        }.getOrNull()
    }

    /** Converts a smallest-unit balance to fiat, or null when no price is known. */
    fun toFiat(coin: CoinSpec, amount: BigInteger, unitPrice: BigDecimal?): BigDecimal? {
        if (unitPrice == null) return null
        return BigDecimal(amount)
            .divide(BigDecimal.TEN.pow(coin.decimals))
            .multiply(unitPrice)
            .setScale(2, RoundingMode.HALF_UP)
    }

    // ── Mining yield ───────────────────────────────────────────────────────

    /** What a share is worth on this chain right now. */
    data class ChainStats(val difficulty: Double, val blockReward: BigDecimal, val height: Long)

    /**
     * Network difficulty and the current block reward, for turning shares into an estimate.
     *
     * THE REWARD IS COMPUTED FROM THE HALVING SCHEDULE rather than read from an API, because no
     * free endpoint reports it reliably and the schedule is a fixed, public property of each chain.
     * Chains whose schedule is not encoded here return null, and the UI shows a share count with no
     * conversion rather than a number invented from a guess.
     */
    /** How long a cached figure is used before a refresh is attempted. Difficulty moves slowly. */
    private const val STATS_FRESH_MILLIS = 6 * 60 * 60 * 1000L

    /**
     * Network difficulty and block reward.
     *
     * CACHE FIRST, NETWORK SECOND, STALE RATHER THAN NOTHING. The free explorer APIs rate-limit,
     * and a burst of balance lookups on app start is enough to use the budget up -- which is why
     * the mining estimate used to appear on one launch and be replaced by "unavailable" on the
     * next. Difficulty retargets every couple of weeks, so a value from hours or even days ago is
     * a far better answer than no answer at all.
     */
    fun chainStats(coin: CoinSpec): ChainStats? {
        val symbol = coin.symbol.uppercase()

        // PrismCoin needs no network at all: this device carries the chain.
        if (symbol == com.prism.launcher.wallet.psc.PrismCoinConsensus.SYMBOL) return prismCoinStats()

        val cached = PrismSettings.getCachedChainStats(symbol)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.third < STATS_FRESH_MILLIS) {
            return ChainStats(cached.first, BigDecimal(cached.second), 0L)
        }

        val fetched = fetchChainStats(coin, symbol)
        if (fetched != null) {
            PrismSettings.setCachedChainStats(
                symbol, fetched.difficulty, fetched.blockReward.toPlainString(), now
            )
            return fetched
        }

        // The fetch failed. A stale figure beats telling the user the coin cannot be converted.
        return cached?.let { ChainStats(it.first, BigDecimal(it.second), 0L) }
    }

    private fun fetchChainStats(coin: CoinSpec, symbol: String): ChainStats? {
        val chain = endpoints[symbol]?.blockchairChain ?: return null
        if (chain.isEmpty()) return null

        return runCatching {
            val body = get("https://api.blockchair.com/$chain/stats") ?: return null
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val difficulty = data.optDouble("difficulty", 0.0)
            val height = data.optLong("blocks", 0L)
            if (difficulty <= 0 || height <= 0) return null

            val reward = blockReward(symbol, height) ?: return null
            ChainStats(difficulty, reward, height)
        }.getOrNull()
    }

    /**
     * PrismCoin's own numbers, straight off the local chain.
     *
     * No explorer, no rate limit, no cache: this device validates every block, so the difficulty
     * of the next block and the reward it will pay are both known exactly rather than reported by
     * somebody else. The reward comes from the same formula consensus uses, which is what makes
     * one PSC one hour of work.
     */
    private fun prismCoinStats(): ChainStats? = runCatching {
        val node = com.prism.launcher.wallet.psc.PrismCoinNode
        val consensus = com.prism.launcher.wallet.psc.PrismCoinConsensus
        val target = node.chain.targetForNext(node.chain.tip)

        val difficulty = consensus.difficultyOf(target)
        if (difficulty <= 0.0) return null

        val reward = BigDecimal(consensus.blockReward(target))
            .divide(BigDecimal(consensus.ONE_PSC))

        ChainStats(difficulty, reward, node.chain.height())
    }.getOrNull()

    /** Halving schedules, as published by each chain. Null where Prism does not encode one. */
    private fun blockReward(symbol: String, height: Long): BigDecimal? = when (symbol) {
        // 50 BTC, halving every 210,000 blocks.
        "BTC", "BCH" -> halving(BigDecimal("50"), height, 210_000L)
        // Litecoin: 50 LTC, halving every 840,000 blocks.
        "LTC" -> halving(BigDecimal("50"), height, 840_000L)
        // Dogecoin has been a flat 10,000 DOGE per block since block 600,000.
        "DOGE" -> if (height >= 600_000L) BigDecimal("10000") else null
        else -> null
    }

    private fun halving(initial: BigDecimal, height: Long, interval: Long): BigDecimal? {
        val eras = (height / interval).toInt()
        if (eras > 64) return BigDecimal.ZERO
        return initial.divide(BigDecimal(2).pow(eras))
    }

    /**
     * Expected earnings from a set of shares, in smallest units.
     *
     * AN ESTIMATE, AND NOT A BALANCE. The formula is the standard pay-per-share expectation --
     * `summed share difficulty x block reward / network difficulty` -- which is what the work is
     * worth on average. What lands in the wallet differs from it for reasons no local calculation
     * can see: the pool's fee, its payout scheme (PPLNS pays for shares near a found block, not for
     * all of them), its minimum payout threshold, and plain variance. Callers must present this as
     * an estimate.
     */
    fun estimatedEarnings(coin: CoinSpec, totalShareDifficulty: Double, stats: ChainStats): BigInteger? {
        if (stats.difficulty <= 0 || totalShareDifficulty <= 0) return null
        return runCatching {
            BigDecimal(totalShareDifficulty)
                .multiply(stats.blockReward)
                .divide(BigDecimal(stats.difficulty), 24, RoundingMode.HALF_UP)
                .multiply(BigDecimal.TEN.pow(coin.decimals))
                .toBigInteger()
        }.getOrNull()
    }

    // ── Sending ────────────────────────────────────────────────────────────

    sealed class SendResult {
        data class Broadcast(val txId: String) : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    /**
     * Signs and broadcasts a transfer.
     *
     * THE SIGNING IS LOCAL AND VERIFIED (see WalletSigningTest, which checks the EIP-155 reference
     * transaction byte-for-byte). What cannot be verified without a live chain is the surrounding
     * arithmetic -- fee estimates, nonce handling, whether an explorer's UTXO set is current -- so
     * the honest advice is to send a small amount first on any coin, once.
     */
    fun send(
        coin: CoinSpec,
        fromAddress: String,
        privateKey: BigInteger,
        toAddress: String,
        amount: BigInteger,
    ): SendResult {
        if (!coin.isValidAddress(toAddress)) {
            return SendResult.Failed("That is not a valid ${coin.symbol} address.")
        }
        val e = endpoints[coin.symbol.uppercase()]
            ?: return SendResult.Failed("Prism has no network endpoint for ${coin.symbol}.")

        return try {
            when {
                e.evmRpc.isNotEmpty() -> sendEvm(coin, e.evmRpc, fromAddress, privateKey, toAddress, amount)
                e.blockchairChain.isNotEmpty() ->
                    sendUtxo(coin, e.blockchairChain, fromAddress, privateKey, toAddress, amount)
                else -> SendResult.Failed("Sending ${coin.symbol} is not supported yet.")
            }
        } catch (ex: Exception) {
            PrismLogger.logError(TAG, "Send failed for ${coin.symbol}", ex)
            SendResult.Failed(ex.message ?: "The transaction could not be sent.")
        }
    }

    private fun sendEvm(
        coin: CoinSpec,
        rpcUrl: String,
        from: String,
        privateKey: BigInteger,
        to: String,
        amount: BigInteger,
    ): SendResult {
        val nonceHex = rpc(rpcUrl, "eth_getTransactionCount", listOf(from, "pending"))
            ?: return SendResult.Failed("Could not read the account nonce.")
        val gasPriceHex = rpc(rpcUrl, "eth_gasPrice", emptyList())
            ?: return SendResult.Failed("Could not read the current gas price.")

        val nonce = BigInteger(nonceHex.removePrefix("0x").ifEmpty { "0" }, 16)
        val gasPrice = BigInteger(gasPriceHex.removePrefix("0x").ifEmpty { "0" }, 16)
        // 21000 is the exact cost of a plain value transfer; anything more would only be needed
        // for contract calls, which this wallet does not make.
        val gasLimit = BigInteger.valueOf(21_000)

        val tx = EvmTransaction(nonce, gasPrice, gasLimit, to, amount, ByteArray(0), coin.chainId)
        val raw = tx.signedHex(privateKey)

        val hash = rpc(rpcUrl, "eth_sendRawTransaction", listOf(raw))
            ?: return SendResult.Failed("The network rejected the transaction.")
        return SendResult.Broadcast(hash)
    }

    private fun sendUtxo(
        coin: CoinSpec,
        chain: String,
        from: String,
        privateKey: BigInteger,
        to: String,
        amount: BigInteger,
    ): SendResult {
        val utxos = fetchUtxos(chain, from)
        if (utxos.isEmpty()) return SendResult.Failed("No spendable outputs at this address.")

        val feeRate = feeRatePerVbyte(chain)
        // Largest-first selection: fewest inputs, therefore the smallest fee. It leaks slightly
        // more about the wallet than a random selection would, and consolidates dust more slowly,
        // but on a phone the fee is what the user notices.
        val sorted = utxos.sortedByDescending { it.valueSatoshis }
        val chosen = ArrayList<Utxo>()
        var gathered = 0L
        var fee = 0L
        val target = amount.toLong()

        for (u in sorted) {
            chosen.add(u)
            gathered += u.valueSatoshis
            fee = BitcoinTransaction.estimateVsize(chosen.size, 2) * feeRate
            if (gathered >= target + fee) break
        }
        if (gathered < target + fee) {
            return SendResult.Failed(
                "Not enough funds: ${coin.format(BigInteger.valueOf(gathered))} available, " +
                    "${coin.format(BigInteger.valueOf(target + fee))} needed including fee."
            )
        }

        val raw = BitcoinTransaction.buildP2wpkh(
            coin = coin, utxos = chosen, toAddress = to,
            amountSatoshis = target, changeAddress = from, feeSatoshis = fee,
            privateKey = privateKey,
        )

        val response = post(
            "https://api.blockchair.com/$chain/push/transaction",
            JSONObject().put("data", WalletCrypto.toHex(raw)).toString()
        ) ?: return SendResult.Failed("The broadcast request failed.")

        val txId = runCatching {
            JSONObject(response).optJSONObject("data")?.optString("transaction_hash", "")
        }.getOrNull()
        return if (txId.isNullOrBlank()) SendResult.Failed("The network rejected the transaction: $response")
        else SendResult.Broadcast(txId)
    }

    private fun fetchUtxos(chain: String, address: String): List<Utxo> {
        val body = get("https://api.blockchair.com/$chain/dashboards/address/$address?limit=100")
            ?: return emptyList()
        return runCatching {
            val entry = JSONObject(body).optJSONObject("data")?.optJSONObject(address)
            val arr = entry?.optJSONArray("utxo") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Utxo(
                    txId = o.optString("transaction_hash", ""),
                    index = o.optInt("index", 0),
                    valueSatoshis = o.optLong("value", 0L),
                    scriptPubKey = "",
                )
            }.filter { it.txId.isNotBlank() && it.valueSatoshis > 0 }
        }.getOrElse { emptyList() }
    }

    /** Satoshis per virtual byte. Falls back to a value that confirms rather than one that is cheap. */
    private fun feeRatePerVbyte(chain: String): Long {
        if (chain != "bitcoin") return 2
        return runCatching {
            val body = get("https://api.blockchair.com/bitcoin/stats") ?: return 12
            val fee = JSONObject(body).optJSONObject("data")
                ?.optLong("suggested_transaction_fee_per_byte_sat", 12L) ?: 12L
            fee.coerceIn(1L, 500L)
        }.getOrDefault(12L)
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private var rpcId = 0

    /** One JSON-RPC call, returning the `result` as a string, or null on any error. */
    private fun rpc(url: String, method: String, params: List<Any?>): String? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ++rpcId)
            .put("method", method)
            .put("params", JSONArray(params))
            .toString()
        val body = post(url, payload) ?: return null
        return runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.let {
                PrismLogger.logError(TAG, "RPC $method: ${it.optString("message")}", null)
                return null
            }
            json.optString("result", "").ifBlank { null }
        }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Prism-Wallet").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun post(url: String, json: String): String? = runCatching {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Prism-Wallet")
                .post(json.toRequestBody(JSON))
                .build()
        ).execute().use { it.body?.string() }
    }.getOrNull()
}
