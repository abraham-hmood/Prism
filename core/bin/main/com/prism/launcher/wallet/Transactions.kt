package com.prism.launcher.wallet

import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Deterministic ECDSA, shared by every chain here.
 *
 * RFC-6979 (the [HMacDSAKCalculator]) rather than a random nonce, and this is not a style
 * preference. ECDSA leaks the private key outright if the same nonce is ever reused across two
 * signatures, and it has happened in shipped software repeatedly -- the PlayStation 3, several
 * Android wallets in 2013 -- because the platform's random source was weaker than assumed.
 * Deriving the nonce from the key and message removes the failure mode instead of hoping.
 *
 * LOW-S IS ENFORCED. Both halves of a signature are valid mathematically, but Bitcoin's relay
 * rules reject the high one as non-standard and Ethereum's EIP-2 rejects it outright, so a
 * signature that ignores this produces a transaction the network silently refuses to propagate.
 */
object EcdsaSigner {

    data class Signature(val r: BigInteger, val s: BigInteger, val recoveryId: Int)

    fun sign(messageHash: ByteArray, privateKey: BigInteger): Signature {
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, WalletCrypto.SECP256K1))
        val components = signer.generateSignature(messageHash)
        val r = components[0]
        var s = components[1]

        val n = WalletCrypto.SECP256K1.n
        val halfN = n.shiftRight(1)
        var flipped = false
        if (s > halfN) {
            s = n.subtract(s)
            flipped = true
        }

        return Signature(r, s, recoveryId(messageHash, r, s, privateKey, flipped))
    }

    /**
     * Which of the candidate public keys the signature recovers to.
     *
     * Ethereum needs this -- it carries no public key in a transaction, deriving the sender from
     * the signature instead, so the wrong recovery id yields a transaction that spends from an
     * address the user does not control and is simply rejected. Found by trying the two
     * possibilities rather than deriving it, because trying is two point multiplications and
     * cannot be subtly wrong.
     */
    private fun recoveryId(
        hash: ByteArray,
        r: BigInteger,
        s: BigInteger,
        privateKey: BigInteger,
        @Suppress("UNUSED_PARAMETER") flipped: Boolean,
    ): Int {
        val expected = WalletCrypto.publicKeyPoint(privateKey).getEncoded(false)
        for (id in 0..1) {
            val recovered = recoverPublicKey(hash, r, s, id) ?: continue
            if (recovered.contentEquals(expected)) return id
        }
        return 0
    }

    private fun recoverPublicKey(hash: ByteArray, r: BigInteger, s: BigInteger, recId: Int): ByteArray? {
        return runCatching {
            val curve = WalletCrypto.SECP256K1
            val n = curve.n
            val prime = (curve.curve as org.bouncycastle.math.ec.custom.sec.SecP256K1Curve).q
            val x = r
            if (x >= prime) return null

            // Decompress the point with x = r; the recovery id picks which of the two y values.
            val compressed = ByteArray(33)
            compressed[0] = (0x02 + (recId and 1)).toByte()
            System.arraycopy(WalletCrypto.toFixedBytes(x, 32), 0, compressed, 1, 32)
            val R = curve.curve.decodePoint(compressed)
            if (!R.multiply(n).isInfinity) return null

            val e = BigInteger(1, hash)
            val rInv = r.modInverse(n)
            val srInv = rInv.multiply(s).mod(n)
            val eInvrInv = rInv.multiply(n.subtract(e.mod(n))).mod(n)
            org.bouncycastle.math.ec.ECAlgorithms
                .sumOfTwoMultiplies(curve.g, eInvrInv, R, srInv)
                .normalize()
                .getEncoded(false)
        }.getOrNull()
    }
}

/**
 * Recursive Length Prefix -- Ethereum's only serialisation format.
 *
 * Everything an EVM chain hashes or signs is RLP, so an error here is not a formatting bug: it
 * changes the hash, which changes the signature, which produces a transaction for a different
 * amount or a different recipient than the user approved.
 */
object Rlp {

    fun encode(item: Any?): ByteArray = when (item) {
        is ByteArray -> encodeBytes(item)
        is List<*> -> {
            val body = ByteArrayOutputStream()
            for (child in item) body.write(encode(child))
            val payload = body.toByteArray()
            encodeLength(payload.size, 0xc0) + payload
        }
        is BigInteger -> encodeBytes(minimalBytes(item))
        is Long -> encodeBytes(minimalBytes(BigInteger.valueOf(item)))
        is Int -> encodeBytes(minimalBytes(BigInteger.valueOf(item.toLong())))
        is String -> encodeBytes(item.toByteArray(Charsets.UTF_8))
        null -> encodeBytes(ByteArray(0))
        else -> throw IllegalArgumentException("cannot RLP-encode ${item::class}")
    }

    /**
     * Integers are big-endian with NO leading zeros, and zero itself is the EMPTY string.
     *
     * This trips everyone: encoding zero as a single 0x00 byte is a different value to Ethereum,
     * and a transaction with nonce 0 encoded that way is rejected by every node.
     */
    private fun minimalBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return ByteArray(0)
        val raw = value.toByteArray()
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        return raw.copyOfRange(start, raw.size)
    }

    private fun encodeBytes(data: ByteArray): ByteArray {
        // A single byte below 0x80 is its own encoding, with no length prefix at all.
        if (data.size == 1 && (data[0].toInt() and 0xff) < 0x80) return data
        return encodeLength(data.size, 0x80) + data
    }

    private fun encodeLength(length: Int, offset: Int): ByteArray {
        if (length < 56) return byteArrayOf((length + offset).toByte())
        val lengthBytes = minimalBytes(BigInteger.valueOf(length.toLong()))
        return byteArrayOf((lengthBytes.size + offset + 55).toByte()) + lengthBytes
    }
}

/**
 * An EVM value transfer, signed per EIP-155.
 *
 * LEGACY (TYPE 0) TRANSACTIONS, not EIP-1559. Deliberate: type-0 is accepted by every EVM chain
 * including the many forks that never adopted 1559, whereas a 1559 transaction is rejected outright
 * by those. The cost is a slightly less precise fee; the benefit is that one code path works on
 * Ethereum, BNB Chain, Polygon, Avalanche and any custom chain a user adds.
 *
 * THE CHAIN ID IS PART OF THE SIGNATURE, which is what EIP-155 added and why it matters here: it
 * makes a transaction valid on exactly one chain. Without it, a signed Polygon transfer could be
 * replayed by anyone on Ethereum mainnet, spending the same nonce from the same address for real.
 */
data class EvmTransaction(
    val nonce: BigInteger,
    val gasPrice: BigInteger,
    val gasLimit: BigInteger,
    val to: String,
    val value: BigInteger,
    val data: ByteArray = ByteArray(0),
    val chainId: Long,
) {
    fun sign(privateKey: BigInteger): ByteArray {
        val toBytes = if (to.isBlank()) ByteArray(0) else WalletCrypto.fromHex(to)
        require(toBytes.isEmpty() || toBytes.size == 20) { "recipient must be a 20-byte address" }

        // EIP-155: hash over the fields plus (chainId, 0, 0) where the signature will go.
        val forSigning = Rlp.encode(
            listOf(nonce, gasPrice, gasLimit, toBytes, value, data, BigInteger.valueOf(chainId), BigInteger.ZERO, BigInteger.ZERO)
        )
        val hash = WalletCrypto.keccak256(forSigning)
        val sig = EcdsaSigner.sign(hash, privateKey)

        val v = BigInteger.valueOf(sig.recoveryId.toLong() + 35 + chainId * 2)
        return Rlp.encode(listOf(nonce, gasPrice, gasLimit, toBytes, value, data, v, sig.r, sig.s))
    }

    fun signedHex(privateKey: BigInteger): String = "0x" + WalletCrypto.toHex(sign(privateKey))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvmTransaction) return false
        return nonce == other.nonce && gasPrice == other.gasPrice && gasLimit == other.gasLimit &&
            to == other.to && value == other.value && data.contentEquals(other.data) &&
            chainId == other.chainId
    }

    override fun hashCode(): Int =
        listOf(nonce, gasPrice, gasLimit, to, value, chainId).hashCode() * 31 + data.contentHashCode()
}

/**
 * A Bitcoin-family P2WPKH spend, signed per BIP-143.
 *
 * BIP-143 replaced the original signature hash for SegWit inputs, and the change that matters here
 * is that the amount being spent is committed to. Under the old scheme a wallet could be lied to
 * about an input's value and sign away an unbounded fee; hardware wallets could not verify what
 * they were signing. Anything spending a witness output must use this algorithm -- the legacy one
 * produces a signature the network rejects.
 */
data class Utxo(
    val txId: String,
    val index: Int,
    val valueSatoshis: Long,
    /** The scriptPubKey being spent, hex. */
    val scriptPubKey: String,
)

object BitcoinTransaction {

    private const val SIGHASH_ALL = 1

    /**
     * Builds and signs a single-key P2WPKH spend with an optional change output.
     *
     * @param utxos inputs to spend, all belonging to [privateKey]
     * @param toAddress recipient
     * @param amountSatoshis what the recipient receives
     * @param changeAddress where the remainder goes; must belong to the sender
     * @param feeSatoshis the miner fee, taken from the change
     */
    fun buildP2wpkh(
        coin: CoinSpec,
        utxos: List<Utxo>,
        toAddress: String,
        amountSatoshis: Long,
        changeAddress: String,
        feeSatoshis: Long,
        privateKey: BigInteger,
    ): ByteArray {
        require(utxos.isNotEmpty()) { "nothing to spend" }
        val total = utxos.sumOf { it.valueSatoshis }
        val change = total - amountSatoshis - feeSatoshis
        require(change >= 0) {
            "insufficient funds: have $total, need ${amountSatoshis + feeSatoshis}"
        }

        val outputs = ArrayList<Pair<Long, ByteArray>>()
        outputs.add(amountSatoshis to scriptPubKeyFor(coin, toAddress))
        // Below the dust threshold an output costs more to spend than it holds, and nodes reject
        // the transaction outright -- so the remainder goes to the miner instead of being stranded.
        if (change > 546) outputs.add(change to scriptPubKeyFor(coin, changeAddress))

        val pubKey = WalletCrypto.compressedPublicKey(privateKey)
        val pubKeyHash = WalletCrypto.hash160(pubKey)

        // BIP-143's three committed digests, computed once and shared across every input.
        val hashPrevouts = WalletCrypto.sha256d(
            ByteArrayOutputStream().apply {
                for (u in utxos) {
                    write(WalletCrypto.fromHex(u.txId).reversedArray())
                    write(le32(u.index))
                }
            }.toByteArray()
        )
        val hashSequence = WalletCrypto.sha256d(
            ByteArrayOutputStream().apply { repeat(utxos.size) { write(le32(0xffffffff.toInt())) } }.toByteArray()
        )
        val hashOutputs = WalletCrypto.sha256d(
            ByteArrayOutputStream().apply {
                for ((value, script) in outputs) {
                    write(le64(value))
                    write(varInt(script.size.toLong()))
                    write(script)
                }
            }.toByteArray()
        )

        val witnesses = utxos.map { u ->
            val preimage = ByteArrayOutputStream().apply {
                write(le32(2))                                        // version
                write(hashPrevouts)
                write(hashSequence)
                write(WalletCrypto.fromHex(u.txId).reversedArray())   // this outpoint
                write(le32(u.index))
                // scriptCode for P2WPKH is the equivalent P2PKH script, per BIP-143.
                val scriptCode = byteArrayOf(0x76, 0xa9.toByte(), 0x14) + pubKeyHash + byteArrayOf(0x88.toByte(), 0xac.toByte())
                write(varInt(scriptCode.size.toLong()))
                write(scriptCode)
                write(le64(u.valueSatoshis))                          // the amount, the point of BIP-143
                write(le32(0xffffffff.toInt()))                       // sequence
                write(hashOutputs)
                write(le32(0))                                        // locktime
                write(le32(SIGHASH_ALL))
            }.toByteArray()

            val sig = EcdsaSigner.sign(WalletCrypto.sha256d(preimage), privateKey)
            derEncode(sig.r, sig.s) + byteArrayOf(SIGHASH_ALL.toByte()) to pubKey
        }

        val out = ByteArrayOutputStream()
        out.write(le32(2))                       // version
        out.write(byteArrayOf(0x00, 0x01))       // SegWit marker and flag
        out.write(varInt(utxos.size.toLong()))
        for (u in utxos) {
            out.write(WalletCrypto.fromHex(u.txId).reversedArray())
            out.write(le32(u.index))
            out.write(varInt(0))                 // empty scriptSig: the signature lives in the witness
            out.write(le32(0xffffffff.toInt()))
        }
        out.write(varInt(outputs.size.toLong()))
        for ((value, script) in outputs) {
            out.write(le64(value))
            out.write(varInt(script.size.toLong()))
            out.write(script)
        }
        for ((sig, pub) in witnesses) {
            out.write(varInt(2))                 // two stack items: signature, public key
            out.write(varInt(sig.size.toLong()))
            out.write(sig)
            out.write(varInt(pub.size.toLong()))
            out.write(pub)
        }
        out.write(le32(0))                       // locktime
        return out.toByteArray()
    }

    /** The locking script for an address, in whichever form that chain writes them. */
    fun scriptPubKeyFor(coin: CoinSpec, address: String): ByteArray {
        Bech32.decodeSegwit(address)?.let { decoded ->
            require(decoded.witnessVersion == 0 || decoded.witnessVersion in 1..16)
            val opVersion = if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion
            return byteArrayOf(opVersion.toByte(), decoded.program.size.toByte()) + decoded.program
        }
        require(coin.scheme !is AddressScheme.MoneroEd25519) {
            "Monero does not use Bitcoin scripts; it has its own transaction format"
        }
        val payload = Base58.decodeChecked(address)
        val scheme = coin.scheme
        val versionLength = if (scheme is AddressScheme.P2PKH && scheme.version > 0xff) 2 else 1
        val hash = payload.copyOfRange(versionLength, payload.size)
        require(hash.size == 20) { "unexpected address payload length for $address" }
        // OP_DUP OP_HASH160 <20> ... OP_EQUALVERIFY OP_CHECKSIG
        return byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
    }

    /** Conservative virtual size for a P2WPKH spend, for fee estimation. */
    fun estimateVsize(inputs: Int, outputs: Int): Int = (inputs * 68) + (outputs * 31) + 11

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun le64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xff).toByte() }

    private fun varInt(v: Long): ByteArray = when {
        v < 0xfd -> byteArrayOf(v.toByte())
        v <= 0xffff -> byteArrayOf(0xfd.toByte(), v.toByte(), (v ushr 8).toByte())
        v <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le32(v.toInt())
        else -> byteArrayOf(0xff.toByte()) + le64(v)
    }

    /** DER, as Bitcoin scripts require -- BouncyCastle hands back the raw pair. */
    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        fun integer(v: BigInteger): ByteArray {
            var b = v.toByteArray()          // already sign-correct, which DER also requires
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = integer(r) + integer(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
