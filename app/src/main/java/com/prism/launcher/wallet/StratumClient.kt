package com.prism.launcher.wallet

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.ShareTarget
import com.prism.launcher.wallet.WalletCrypto
import java.io.BufferedReader
import java.io.BufferedWriter
import java.math.BigInteger
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * A Stratum V1 client -- the protocol every mining pool speaks.
 *
 * SOLO MINING IS NOT OFFERED, and the reason is arithmetic rather than preference. Mining alone
 * means finding a whole block or earning nothing; at a phone's hash rate the expected wait exceeds
 * the age of the universe by many orders of magnitude. A pool pays for SHARES -- proofs of work far
 * below the network's difficulty -- so a miner sees something happen. That is the only arrangement
 * where a phone produces a number that ever moves.
 *
 * THE PROTOCOL IS LINE-DELIMITED JSON-RPC over a raw socket, with the server pushing work
 * unprompted. So this reads in a loop rather than request/response, and a job can be replaced at
 * any moment -- `clean_jobs` means abandon what you are hashing immediately, because it is now
 * work on a block someone else already found.
 */
class StratumClient(
    private val host: String,
    private val port: Int,
    private val workerName: String,
    private val password: String = "x",
) {

    private companion object {
        const val TAG = "PrismMining"
    }

    /** Work handed down by the pool, already assembled into something hashable. */
    data class Job(
        val jobId: String,
        val previousHash: String,
        val coinbase1: String,
        val coinbase2: String,
        val merkleBranches: List<String>,
        val version: String,
        val nBits: String,
        val nTime: String,
        val cleanJobs: Boolean,
    )

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val nextId = AtomicLong(1)

    @Volatile var extraNonce1: String = ""
        private set
    @Volatile var extraNonce2Size: Int = 4
        private set
    @Volatile var difficulty: Double = 1.0
        private set
    @Volatile var currentJob: Job? = null
        private set
    @Volatile var lastError: String? = null
        private set

    var onJob: ((Job) -> Unit)? = null
    var onAccepted: ((Boolean) -> Unit)? = null

    fun connect(): Boolean = try {
        val s = Socket(host, port).apply { soTimeout = 120_000; keepAlive = true }
        socket = s
        reader = s.getInputStream().bufferedReader()
        writer = s.getOutputStream().bufferedWriter()

        send(JSONObject().put("id", nextId.getAndIncrement()).put("method", "mining.subscribe")
            .put("params", JSONArray(listOf("Prism/1.0"))))
        send(JSONObject().put("id", nextId.getAndIncrement()).put("method", "mining.authorize")
            .put("params", JSONArray(listOf(workerName, password))))
        lastError = null
        true
    } catch (e: Exception) {
        lastError = e.message ?: e::class.simpleName
        PrismLogger.logError(TAG, "Could not reach pool $host:$port", e)
        false
    }

    /** Blocks, dispatching messages until the socket closes or the thread is interrupted. */
    fun listen() {
        val r = reader ?: return
        try {
            while (!Thread.currentThread().isInterrupted) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                handle(line)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            PrismLogger.logError(TAG, "Pool connection dropped", e)
        }
    }

    private fun handle(line: String) {
        val message = runCatching { JSONObject(line) }.getOrNull() ?: return

        when (message.optString("method", "")) {
            "mining.notify" -> {
                val p = message.optJSONArray("params") ?: return
                val branches = p.optJSONArray(4) ?: JSONArray()
                val job = Job(
                    jobId = p.optString(0, ""),
                    previousHash = p.optString(1, ""),
                    coinbase1 = p.optString(2, ""),
                    coinbase2 = p.optString(3, ""),
                    merkleBranches = (0 until branches.length()).map { branches.optString(it, "") },
                    version = p.optString(5, ""),
                    nBits = p.optString(6, ""),
                    nTime = p.optString(7, ""),
                    cleanJobs = runCatching { p.getBoolean(8) }.getOrDefault(true),
                )
                currentJob = job
                onJob?.invoke(job)
            }
            "mining.set_difficulty" -> {
                val p = message.optJSONArray("params") ?: return
                difficulty = p.optDouble(0, 1.0).coerceAtLeast(0.0001)
            }
            else -> {
                // A response rather than a notification. The subscribe reply carries the extranonce
                // the pool assigns this connection, which every coinbase must embed -- without it
                // two miners would build identical work and duplicate each other's shares.
                val result = message.opt("result")
                if (result is JSONArray && result.length() >= 3 && extraNonce1.isEmpty()) {
                    extraNonce1 = result.optString(1, "")
                    extraNonce2Size = result.optInt(2, 4).coerceIn(1, 8)
                } else if (result is Boolean) {
                    onAccepted?.invoke(result)
                }
                message.optJSONObject("error")?.let {
                    lastError = it.optString("message", "pool returned an error")
                }
            }
        }
    }

    fun submit(jobId: String, extraNonce2: String, nTime: String, nonce: String): Boolean = try {
        send(
            JSONObject().put("id", nextId.getAndIncrement()).put("method", "mining.submit")
                .put("params", JSONArray(listOf(workerName, jobId, extraNonce2, nTime, nonce)))
        )
        true
    } catch (e: Exception) {
        lastError = e.message
        false
    }

    private fun send(message: JSONObject) {
        val w = writer ?: throw IllegalStateException("not connected")
        synchronized(w) {
            w.write(message.toString())
            w.write("\n")
            w.flush()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
}

/**
 * Turns a Stratum job plus a nonce into the 80 bytes that get hashed.
 *
 * ENDIANNESS IS THE ENTIRE DIFFICULTY HERE. Stratum sends most fields as hex strings that are
 * already in the byte order the header wants, but the previous-block hash arrives as eight 4-byte
 * words that each need reversing, and the merkle root comes out of a big-endian fold that then has
 * to go in little-endian. Every one of these is a silent failure: get one wrong and the miner
 * hashes valid-looking garbage forever, at full speed, finding nothing.
 */
object StratumWork {

    fun buildCoinbase(job: StratumClient.Job, extraNonce1: String, extraNonce2: String): ByteArray =
        WalletCrypto.fromHex(job.coinbase1 + extraNonce1 + extraNonce2 + job.coinbase2)

    /** Folds the coinbase hash through the pool's branch list to the merkle root. */
    fun merkleRoot(coinbase: ByteArray, branches: List<String>): ByteArray {
        var root = WalletCrypto.sha256d(coinbase)
        for (branch in branches) {
            if (branch.isBlank()) continue
            root = WalletCrypto.sha256d(root + WalletCrypto.fromHex(branch))
        }
        return root
    }

    /** Reverses each 4-byte word in place -- the form Stratum sends a previous-block hash in. */
    fun swapWords(hex: String): ByteArray {
        val bytes = WalletCrypto.fromHex(hex)
        val out = ByteArray(bytes.size)
        var i = 0
        while (i + 4 <= bytes.size) {
            for (j in 0..3) out[i + j] = bytes[i + 3 - j]
            i += 4
        }
        return out
    }

    /**
     * The header for one job and extranonce, with only the nonce left to fill in.
     *
     * BUILD THIS ONCE PER extraNonce2, NOT PER NONCE. Everything here is expensive and none of it
     * depends on the nonce: the coinbase is assembled from hex strings, its hash folded through
     * the merkle branches, and four more fields parsed out of hex. Doing that per attempt -- which
     * is what the miner used to do -- costs several times what the hash itself costs.
     */
    fun template(
        job: StratumClient.Job,
        extraNonce1: String,
        extraNonce2: String,
    ): com.prism.launcher.wallet.HeaderTemplate {
        val coinbase = buildCoinbase(job, extraNonce1, extraNonce2)
        val root = merkleRoot(coinbase, job.merkleBranches)

        val prefix = java.io.ByteArrayOutputStream(76).apply {
            write(WalletCrypto.fromHex(job.version).reversedArray())
            write(swapWords(job.previousHash))
            write(root)
            write(WalletCrypto.fromHex(job.nTime).reversedArray())
            write(WalletCrypto.fromHex(job.nBits).reversedArray())
        }.toByteArray()

        // The nonce is the last field of an 80-byte header, little-endian.
        return com.prism.launcher.wallet.HeaderTemplate.of(
            prefix, ByteArray(0), nonceSize = 4, littleEndian = true
        )
    }

    /** One-shot header, for callers outside a hashing loop. */
    fun header(
        job: StratumClient.Job,
        extraNonce1: String,
        extraNonce2: String,
        nonce: Long,
    ): ByteArray = template(job, extraNonce1, extraNonce2).snapshot(nonce)

    fun targetFor(difficulty: Double): BigInteger = ShareTarget.targetFor(difficulty)

    fun meets(algorithm: String, header: ByteArray, target: BigInteger): Boolean =
        ShareTarget.meetsTarget(MiningAlgorithms.hash(algorithm, header), target)
}
