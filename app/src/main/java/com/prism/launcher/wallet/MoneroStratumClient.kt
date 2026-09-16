package com.prism.launcher.wallet

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.WalletCrypto
import java.io.BufferedReader
import java.io.BufferedWriter
import java.math.BigInteger
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Monero's mining protocol, which shares nothing with Bitcoin's Stratum but the socket.
 *
 * ## Four differences that each break the other client outright
 *
 *   LOGIN, NOT SUBSCRIBE. One `login` call authenticates and returns the first job together with a
 *   session id that every submission must carry. There is no `mining.subscribe`, no extranonce and
 *   no `mining.authorize`.
 *
 *   A BLOB, NOT A HEADER TO ASSEMBLE. Bitcoin pools send the pieces and the miner builds the
 *   header, folding a merkle tree. Monero pools send the finished hashing blob, and the miner's
 *   only edit is four bytes of nonce at [NONCE_OFFSET]. Much simpler, and completely incompatible.
 *
 *   THE TARGET IS COMPACT AND COMPARED DIFFERENTLY. It arrives as 4 or 8 little-endian bytes, and
 *   a share is decided on the LAST EIGHT BYTES of the hash read as a little-endian 64-bit integer
 *   -- not on the whole 256-bit value the way Bitcoin does it.
 *
 *   THE SEED ROTATES. RandomX is keyed on `seed_hash`, which the pool changes every couple of
 *   thousand blocks. When it moves, every VM must be rebuilt before the next hash.
 *
 * ## The nonce offset is a consensus constant
 *
 * 39 bytes into the blob: past the version bytes, the timestamp varint and the 32-byte previous
 * block id. Writing it anywhere else produces a hash for a block that does not exist, which the
 * pool rejects as an invalid share while the miner reports itself perfectly healthy.
 */
class MoneroStratumClient(
    private val host: String,
    private val port: Int,
    private val walletAddress: String,
    private val workerName: String = "prism",
) {

    companion object {
        private const val TAG = "PrismMonero"

        /** Where the 4-byte little-endian nonce lives inside a Monero hashing blob. */
        const val NONCE_OFFSET = 39

        private val U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val U32_MAX = BigInteger.valueOf(0xFFFFFFFFL)

        /**
         * Expands a compact pool target into the 64-bit value a share is compared against.
         *
         * A 4-byte target is a compressed difficulty, not a truncated target: it has to be
         * expanded as `2^64-1 / (2^32-1 / t)`, which is what every Monero miner does. Treating it
         * as the top four bytes of a 256-bit target instead makes every share look invalid.
         */
        fun parseTarget(hex: String): BigInteger? {
            val bytes = runCatching { WalletCrypto.fromHex(hex) }.getOrNull() ?: return null
            return when (bytes.size) {
                4 -> {
                    val t32 = BigInteger(1, bytes.reversedArray())
                    if (t32.signum() == 0) null
                    else U64_MAX.divide(U32_MAX.divide(t32).max(BigInteger.ONE))
                }
                8 -> BigInteger(1, bytes.reversedArray())
                else -> null
            }
        }

        /** The share test: the last 8 bytes of the hash, little-endian, below the target. */
        fun meetsTarget(hash: ByteArray, target: BigInteger): Boolean {
            if (hash.size < 32) return false
            val tail = BigInteger(1, hash.copyOfRange(24, 32).reversedArray())
            return tail < target
        }

        /** Roughly what difficulty a target represents, for display. */
        fun difficultyOf(target: BigInteger): Double =
            if (target.signum() <= 0) 0.0 else U64_MAX.divide(target).toDouble()
    }

    /** One unit of work from the pool. */
    data class Job(
        val jobId: String,
        val blob: ByteArray,
        val target: BigInteger,
        val seedHash: ByteArray,
        val height: Long,
        val algorithm: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Job && jobId == other.jobId
        override fun hashCode(): Int = jobId.hashCode()
    }

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val nextId = AtomicLong(1)

    @Volatile var sessionId: String = ""
        private set
    @Volatile var currentJob: Job? = null
        private set
    @Volatile var lastError: String? = null
        private set

    var onJob: ((Job) -> Unit)? = null
    var onAccepted: ((Boolean, String) -> Unit)? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(): Boolean = try {
        val s = Socket(host, port).apply { soTimeout = 180_000; keepAlive = true }
        socket = s
        reader = s.getInputStream().bufferedReader()
        writer = s.getOutputStream().bufferedWriter()

        send(
            JSONObject()
                .put("id", nextId.getAndIncrement())
                .put("jsonrpc", "2.0")
                .put("method", "login")
                .put(
                    "params",
                    JSONObject()
                        .put("login", walletAddress)
                        .put("pass", workerName)
                        .put("agent", "Prism/1.0")
                        // Asking for rx/0 explicitly: pools that serve several algorithms will
                        // otherwise hand out work Prism cannot hash.
                        .put("algo", com.prism.core.json.JSONArray(listOf("rx/0")))
                )
        )
        lastError = null
        true
    } catch (e: Exception) {
        lastError = e.message ?: e::class.simpleName
        PrismLogger.logError(TAG, "Could not reach $host:$port", e)
        false
    }

    /** Blocks, dispatching messages until the socket closes or the thread is interrupted. */
    fun listen() {
        val r = reader ?: return
        try {
            while (!Thread.currentThread().isInterrupted) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) handle(line)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e::class.simpleName
            PrismLogger.logError(TAG, "Pool connection dropped", e)
        }
    }

    private fun handle(line: String) {
        val message = runCatching { JSONObject(line) }.getOrNull() ?: return

        message.optJSONObject("error")?.let { error ->
            val text = error.optString("message", "pool returned an error")
            lastError = text
            // Rejections arrive as errors on a submit, which is a share result rather than a fault.
            onAccepted?.invoke(false, text)
            return
        }

        when (message.optString("method", "")) {
            // A pushed job.
            "job" -> message.optJSONObject("params")?.let { adoptJob(it) }
            else -> {
                val result = message.optJSONObject("result") ?: return
                if (result.has("id") && result.has("job")) {
                    // The login reply: session id plus the first job.
                    sessionId = result.optString("id", "")
                    result.optJSONObject("job")?.let { adoptJob(it) }
                    PrismLogger.logSuccess(TAG, "Logged in to $host:$port")
                } else if (result.optString("status", "") == "OK") {
                    onAccepted?.invoke(true, "OK")
                }
            }
        }
    }

    private fun adoptJob(params: JSONObject) {
        val job = runCatching {
            val blobHex = params.getString("blob")
            val blob = WalletCrypto.fromHex(blobHex)
            // A blob too short to hold the nonce would be silently corrupted by writing one.
            if (blob.size < NONCE_OFFSET + 4) return@runCatching null

            val target = parseTarget(params.getString("target")) ?: return@runCatching null
            Job(
                jobId = params.getString("job_id"),
                blob = blob,
                target = target,
                seedHash = WalletCrypto.fromHex(params.optString("seed_hash", "")),
                height = params.optLong("height", 0L),
                algorithm = params.optString("algo", "rx/0"),
            )
        }.getOrNull()

        if (job == null) {
            lastError = "The pool sent a job Prism could not read"
            return
        }
        if (job.seedHash.isEmpty()) {
            lastError = "The pool sent no seed hash; RandomX cannot be keyed"
            return
        }
        currentJob = job
        onJob?.invoke(job)
    }

    /**
     * Submits a share.
     *
     * The nonce goes as 8 hex characters of the LITTLE-ENDIAN four bytes, matching how it sits in
     * the blob, and the result is the full 32-byte hash. Pools validate both.
     */
    fun submit(jobId: String, nonce: Int, hash: ByteArray): Boolean = try {
        val nonceHex = WalletCrypto.toHex(
            byteArrayOf(
                nonce.toByte(), (nonce ushr 8).toByte(),
                (nonce ushr 16).toByte(), (nonce ushr 24).toByte()
            )
        )
        send(
            JSONObject()
                .put("id", nextId.getAndIncrement())
                .put("jsonrpc", "2.0")
                .put("method", "submit")
                .put(
                    "params",
                    JSONObject()
                        .put("id", sessionId)
                        .put("job_id", jobId)
                        .put("nonce", nonceHex)
                        .put("result", WalletCrypto.toHex(hash))
                )
        )
        true
    } catch (e: Exception) {
        lastError = e.message
        false
    }

    /** Monero pools drop idle connections; this is the standard no-op that keeps one alive. */
    fun keepAlive() {
        runCatching {
            send(
                JSONObject()
                    .put("id", nextId.getAndIncrement())
                    .put("jsonrpc", "2.0")
                    .put("method", "keepalived")
                    .put("params", JSONObject().put("id", sessionId))
            )
        }
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
}

/** Writes a nonce into a copy of a Monero blob, leaving the pool's original untouched. */
object MoneroWork {
    fun blobWithNonce(blob: ByteArray, nonce: Int): ByteArray {
        val out = blob.copyOf()
        writeNonce(out, nonce)
        return out
    }

    /** In-place, for a mining loop that reuses one buffer. */
    fun writeNonce(blob: ByteArray, nonce: Int) {
        val offset = MoneroStratumClient.NONCE_OFFSET
        blob[offset] = nonce.toByte()
        blob[offset + 1] = (nonce ushr 8).toByte()
        blob[offset + 2] = (nonce ushr 16).toByte()
        blob[offset + 3] = (nonce ushr 24).toByte()
    }
}
