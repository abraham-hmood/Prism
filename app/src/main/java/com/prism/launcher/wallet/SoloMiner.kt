package com.prism.launcher.wallet

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.BitcoinTransaction
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletCrypto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Solo mining: `getblocktemplate` against the user's own full node.
 *
 * ## Why this is a different program, not a checkbox
 *
 * Pool mining receives work and submits partial proofs. Solo mining has no server to hand out
 * work, so the miner has to do what a pool would otherwise do for it: ask a node for the current
 * template, BUILD ITS OWN COINBASE TRANSACTION paying itself, compute the merkle root over the
 * template's transactions, and submit a complete block. Nothing about that fits behind a flag on
 * the Stratum path.
 *
 * ## The economics, stated once and plainly
 *
 * There are no shares. A solo miner earns the entire block reward or, overwhelmingly likely,
 * nothing whatsoever -- there is no partial credit for work that did not find a block. At a phone's
 * few MH/s against Bitcoin's hundreds of EH/s, the expected time to find one block is on the order
 * of 10^13 years. This is implemented because it is the honest meaning of "mining alone" and
 * because it is what was asked for; it is not implemented under any pretence that it will pay.
 *
 * ## It requires a node, and there is no way around that
 *
 * A block template can only come from something with the full UTXO set and mempool. Without a
 * reachable node this mode cannot start, and [fetchTemplate] says so rather than falling back to a
 * pool behind the user's back -- which would silently turn "solo" into the thing they chose against.
 *
 * ## Not exercised against a live node
 *
 * The block assembly follows BIP-22/BIP-23 and BIP-141, and the pieces it rests on (header
 * serialisation, SHA-256d, the little-endian target comparison) are checked against Bitcoin's
 * genesis block in MiningTest. The end-to-end path -- template to accepted block -- cannot be
 * verified without a real node, so it is unproven rather than known-good.
 */
class SoloMiner(
    private val nodeUrl: String,
    private val credentials: String,
    private val coin: CoinSpec,
    private val payoutAddress: String,
) {

    private companion object {
        const val TAG = "PrismMining"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** A block to work on: the header fields, plus everything needed to rebuild the merkle root. */
    data class Template(
        val version: Int,
        val previousBlockHash: String,
        val target: BigInteger,
        val currentTime: Long,
        val bits: String,
        val height: Long,
        val coinbaseValue: Long,
        /** Transaction `data` blobs from the template, in order. */
        val transactions: List<String>,
        /** Transaction ids, already in the byte order the merkle fold wants. */
        val transactionIds: List<String>,
        /** The BIP-141 commitment scriptPubKey, empty on a chain without SegWit. */
        val witnessCommitment: String,
    )

    @Volatile
    var lastError: String? = null
        private set

    fun fetchTemplate(): Template? {
        if (nodeUrl.isBlank()) {
            lastError = "Solo mining needs the RPC URL of a full node you run. Set one in Settings."
            return null
        }

        val response = rpc(
            "getblocktemplate",
            JSONArray(listOf(JSONObject().put("rules", JSONArray(listOf("segwit")))))
        ) ?: return null

        return runCatching {
            val txs = response.optJSONArray("transactions") ?: JSONArray()
            Template(
                version = response.optInt("version", 536870912),
                previousBlockHash = response.optString("previousblockhash", ""),
                // The template's target is authoritative; `bits` is the same number compressed.
                target = BigInteger(response.optString("target", "").ifBlank { "f".repeat(64) }, 16),
                currentTime = response.optLong("curtime", System.currentTimeMillis() / 1000),
                bits = response.optString("bits", ""),
                height = response.optLong("height", 0L),
                coinbaseValue = response.optLong("coinbasevalue", 0L),
                transactions = (0 until txs.length()).map { txs.getJSONObject(it).optString("data", "") },
                transactionIds = (0 until txs.length()).map { txs.getJSONObject(it).optString("txid", "") },
                witnessCommitment = response.optString("default_witness_commitment", ""),
            )
        }.getOrElse {
            lastError = "The node's block template could not be read: ${it.message}"
            null
        }
    }

    /**
     * The coinbase transaction, paying the whole block reward to the user's own address.
     *
     * TWO THINGS HERE ARE CONSENSUS RULES, not conventions. The block height must be the first
     * item pushed in the scriptSig (BIP-34) or the block is invalid outright. And when the template
     * carries a witness commitment, the coinbase must include that exact output and a witness whose
     * single item is 32 zero bytes (BIP-141) -- omit either and every node rejects the block.
     */
    fun buildCoinbase(template: Template, extraNonce: Long): ByteArray {
        val payoutScript = BitcoinTransaction.scriptPubKeyFor(coin, payoutAddress)

        val heightBytes = encodeScriptNumber(template.height)
        val extra = encodeScriptNumber(extraNonce)
        val scriptSig = byteArrayOf(heightBytes.size.toByte()) + heightBytes +
            byteArrayOf(extra.size.toByte()) + extra

        val out = ByteArrayOutputStream()
        out.write(le32(1))                                   // version
        out.write(varInt(1))                                 // one input
        out.write(ByteArray(32))                             // no previous output
        out.write(le32(-1))                                  // index 0xffffffff
        out.write(varInt(scriptSig.size.toLong()))
        out.write(scriptSig)
        out.write(le32(-1))                                  // sequence

        val hasCommitment = template.witnessCommitment.isNotBlank()
        out.write(varInt(if (hasCommitment) 2 else 1))
        out.write(le64(template.coinbaseValue))
        out.write(varInt(payoutScript.size.toLong()))
        out.write(payoutScript)
        if (hasCommitment) {
            val commitment = WalletCrypto.fromHex(template.witnessCommitment)
            out.write(le64(0))
            out.write(varInt(commitment.size.toLong()))
            out.write(commitment)
        }
        out.write(le32(0))                                   // locktime
        return out.toByteArray()
    }

    /**
     * Folds the coinbase together with the template's transactions.
     *
     * The txid of the coinbase is the hash of its NON-witness serialisation, which is what
     * [buildCoinbase] returns -- the witness is added only when the block is serialised. Hashing
     * the witness form would give a merkle root no node agrees with.
     */
    fun merkleRoot(coinbase: ByteArray, transactionIds: List<String>): ByteArray {
        var layer = mutableListOf(WalletCrypto.sha256d(coinbase))
        for (id in transactionIds) {
            if (id.isNotBlank()) layer.add(WalletCrypto.fromHex(id).reversedArray())
        }
        while (layer.size > 1) {
            val next = mutableListOf<ByteArray>()
            var i = 0
            while (i < layer.size) {
                val left = layer[i]
                // An odd node is paired with itself -- the quirk that made CVE-2012-2459 possible
                // and is now simply how the rule reads.
                val right = if (i + 1 < layer.size) layer[i + 1] else left
                next.add(WalletCrypto.sha256d(left + right))
                i += 2
            }
            layer = next
        }
        return layer.first()
    }

    fun header(template: Template, merkleRoot: ByteArray, nTime: Long, nonce: Long): ByteArray {
        val out = ByteArrayOutputStream(80)
        out.write(le32(template.version))
        out.write(WalletCrypto.fromHex(template.previousBlockHash).reversedArray())
        out.write(merkleRoot)
        out.write(le32(nTime.toInt()))
        out.write(WalletCrypto.fromHex(template.bits).reversedArray())
        out.write(le32(nonce.toInt()))
        return out.toByteArray()
    }

    /**
     * Serialises the full block and hands it to the node.
     *
     * The coinbase is re-serialised here WITH its witness when the template asked for a
     * commitment; every other transaction goes in exactly as the node supplied it.
     */
    fun submitBlock(
        template: Template,
        coinbase: ByteArray,
        nTime: Long,
        nonce: Long,
        merkleRoot: ByteArray,
    ): Boolean {
        val out = ByteArrayOutputStream()
        out.write(header(template, merkleRoot, nTime, nonce))
        out.write(varInt((template.transactions.size + 1).toLong()))
        out.write(
            if (template.witnessCommitment.isNotBlank()) withWitness(coinbase) else coinbase
        )
        for (tx in template.transactions) out.write(WalletCrypto.fromHex(tx))

        val result = rpc("submitblock", JSONArray(listOf(WalletCrypto.toHex(out.toByteArray()))))
        // submitblock is inverted: a null result means ACCEPTED, a string is the rejection reason.
        val rejection = result?.optString("__raw__", "")
        return if (rejection.isNullOrBlank()) {
            PrismLogger.logSuccess(TAG, "Block submitted at height ${template.height}")
            true
        } else {
            val reason = "Block rejected: $rejection"
            lastError = reason
            PrismLogger.logError(TAG, reason, null)
            false
        }
    }

    /** Re-serialises the coinbase with the BIP-141 marker, flag and reserved witness value. */
    private fun withWitness(coinbase: ByteArray): ByteArray {
        // version(4) then the rest; the marker/flag go between version and the input count.
        val out = ByteArrayOutputStream()
        out.write(coinbase, 0, 4)
        out.write(byteArrayOf(0x00, 0x01))
        out.write(coinbase, 4, coinbase.size - 8)            // inputs + outputs, minus locktime
        out.write(varInt(1))                                 // one witness item
        out.write(varInt(32))
        out.write(ByteArray(32))                             // the reserved value, all zeroes
        out.write(coinbase, coinbase.size - 4, 4)            // locktime
        return out.toByteArray()
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private var callId = 0

    private fun rpc(method: String, params: JSONArray): JSONObject? {
        val payload = JSONObject()
            .put("jsonrpc", "1.0")
            .put("id", ++callId)
            .put("method", method)
            .put("params", params)
            .toString()

        val builder = Request.Builder().url(nodeUrl).post(payload.toRequestBody(JSON))
        if (credentials.isNotBlank()) {
            val parts = credentials.split(":", limit = 2)
            builder.header(
                "Authorization",
                okhttp3.Credentials.basic(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
            )
        }

        return runCatching {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                json.optJSONObject("error")?.let {
                    lastError = "Node error: ${it.optString("message")}"
                    return null
                }
                when (val result = json.opt("result")) {
                    is JSONObject -> result
                    // submitblock returns null on success and a string on rejection; both are
                    // wrapped so callers get one type back.
                    null -> JSONObject()
                    else -> JSONObject().put("__raw__", result.toString())
                }
            }
        }.getOrElse {
            lastError = "Could not reach the node at $nodeUrl: ${it.message}"
            null
        }
    }

    /** Minimal CScriptNum, as BIP-34 requires for the height push. */
    private fun encodeScriptNumber(value: Long): ByteArray {
        if (value == 0L) return ByteArray(0)
        val out = ArrayList<Byte>()
        var v = value
        while (v > 0) {
            out.add((v and 0xff).toByte())
            v = v shr 8
        }
        // A top bit set would read as negative, so a zero byte is appended to keep it positive.
        if (out.last().toInt() and 0x80 != 0) out.add(0)
        return out.toByteArray()
    }

    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun le64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xff).toByte() }

    private fun varInt(v: Long): ByteArray = when {
        v < 0xfd -> byteArrayOf(v.toByte())
        v <= 0xffff -> byteArrayOf(0xfd.toByte(), v.toByte(), (v ushr 8).toByte())
        else -> byteArrayOf(0xfe.toByte()) + le32(v.toInt())
    }
}
