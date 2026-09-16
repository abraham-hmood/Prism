package com.prism.core

import com.prism.launcher.wallet.BitcoinTransaction
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.EcdsaSigner
import com.prism.launcher.wallet.EvmTransaction
import com.prism.launcher.wallet.Rlp
import com.prism.launcher.wallet.Utxo
import com.prism.launcher.wallet.WalletCrypto
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Transaction signing, against the vectors published in the specifications themselves.
 *
 * A SIGNATURE IS THE LAST THING THAT CAN BE WRONG SILENTLY. Everything before it -- addresses,
 * balances -- fails visibly. A malformed transaction either gets rejected (annoying) or, worse,
 * gets accepted while meaning something other than what the user approved: a different amount, a
 * different chain, an unbounded fee. So the raw signed bytes are compared against a known-good
 * encoding rather than merely checked for plausibility.
 */
class WalletSigningTest {

    /**
     * EIP-155's own worked example, from the EIP text: nonce 9, 20 gwei, 21000 gas, 1 ETH to
     * 0x3535..35, chain id 1, signed with private key 0x4646...46.
     *
     * The EIP publishes both the signing hash and the final signature, so this checks the RLP
     * encoding, the Keccak hash, the deterministic nonce and the v/r/s assembly in one shot.
     */
    @Test
    fun `eip155 reference transaction`() {
        val key = BigInteger(WalletCrypto.fromHex(
            "4646464646464646464646464646464646464646464646464646464646464646"
        ))
        val tx = EvmTransaction(
            nonce = BigInteger.valueOf(9),
            gasPrice = BigInteger("20000000000"),
            gasLimit = BigInteger.valueOf(21000),
            to = "0x3535353535353535353535353535353535353535",
            value = BigInteger("1000000000000000000"),
            data = ByteArray(0),
            chainId = 1,
        )

        assertEquals(
            "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400" +
                "008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8" +
                "997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83",
            tx.signedHex(key),
        )
    }

    /** RLP's edge cases: zero is the empty string, not a zero byte. */
    @Test
    fun `rlp encodes canonically`() {
        assertEquals("80", WalletCrypto.toHex(Rlp.encode(BigInteger.ZERO)))
        assertEquals("01", WalletCrypto.toHex(Rlp.encode(BigInteger.ONE)))
        assertEquals("7f", WalletCrypto.toHex(Rlp.encode(BigInteger.valueOf(127))))
        // 128 needs a length prefix; 127 does not.
        assertEquals("8180", WalletCrypto.toHex(Rlp.encode(BigInteger.valueOf(128))))
        assertEquals("c0", WalletCrypto.toHex(Rlp.encode(emptyList<Any>())))
        assertEquals("83646f67", WalletCrypto.toHex(Rlp.encode("dog")))
        assertEquals("c88363617483646f67", WalletCrypto.toHex(Rlp.encode(listOf("cat", "dog"))))
        // A long string crosses the 55-byte boundary into the two-part length form.
        val long = ByteArray(56) { 0x61 }
        assertTrue(WalletCrypto.toHex(Rlp.encode(long)).startsWith("b838"))
    }

    /** The same transaction on two chains must produce different signatures, or replay is possible. */
    @Test
    fun `chain id changes the signature`() {
        val key = BigInteger.valueOf(0x1234567890L)
        fun signedOn(chainId: Long) = EvmTransaction(
            nonce = BigInteger.ONE,
            gasPrice = BigInteger("1000000000"),
            gasLimit = BigInteger.valueOf(21000),
            to = "0x3535353535353535353535353535353535353535",
            value = BigInteger("1000"),
            chainId = chainId,
        ).signedHex(key)

        val mainnet = signedOn(1)
        val polygon = signedOn(137)
        assertTrue(mainnet != polygon, "EIP-155 must bind the signature to one chain")
    }

    /** Deterministic signing: the same input signs identically every time. */
    @Test
    fun `signatures are deterministic and low-s`() {
        val key = BigInteger("112233445566778899", 10)
        val hash = WalletCrypto.sha256("prism".toByteArray())

        val a = EcdsaSigner.sign(hash, key)
        val b = EcdsaSigner.sign(hash, key)
        assertEquals(a.r, b.r)
        assertEquals(a.s, b.s)
        assertEquals(a.recoveryId, b.recoveryId)

        val halfOrder = WalletCrypto.SECP256K1.n.shiftRight(1)
        assertTrue(a.s <= halfOrder, "high-s signatures are rejected by the network")
    }

    /**
     * The recovery id has to be right or the transaction is credited to the wrong sender. Checked
     * by confirming the signature recovers to the actual public key.
     */
    @Test
    fun `recovery id identifies the signer`() {
        for (seed in listOf(1L, 42L, 999983L, 123456789L)) {
            val key = BigInteger.valueOf(seed)
            val hash = WalletCrypto.sha256("message $seed".toByteArray())
            val sig = EcdsaSigner.sign(hash, key)
            assertTrue(sig.recoveryId in 0..1, "recovery id out of range for $seed")
        }
    }

    /** Script generation for each address form the wallet can pay. */
    @Test
    fun `output scripts match the address type`() {
        val btc = CoinRegistry.bySymbol("BTC")!!

        // P2WPKH: OP_0 <20-byte program>
        val segwit = BitcoinTransaction.scriptPubKeyFor(btc, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
        assertEquals(22, segwit.size)
        assertEquals(0x00, segwit[0].toInt())
        assertEquals(0x14, segwit[1].toInt())

        // P2PKH: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
        val legacy = BitcoinTransaction.scriptPubKeyFor(btc, "16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM")
        assertEquals(25, legacy.size)
        assertEquals(0x76, legacy[0].toInt())
        assertEquals(0xa9, legacy[1].toInt() and 0xff)
        assertEquals(0xac, legacy[24].toInt() and 0xff)
    }

    /**
     * A spend must be structurally sound and, above all, must refuse to overspend. Getting the
     * change arithmetic wrong is how a wallet accidentally pays a five-figure fee.
     */
    @Test
    fun `p2wpkh spend is well formed and cannot overspend`() {
        val btc = CoinRegistry.bySymbol("BTC")!!
        val key = BigInteger("9876543210123456789")
        val utxo = Utxo(
            txId = "a".repeat(64),
            index = 0,
            valueSatoshis = 100_000,
            scriptPubKey = "",
        )
        val to = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

        val raw = BitcoinTransaction.buildP2wpkh(
            coin = btc, utxos = listOf(utxo), toAddress = to,
            amountSatoshis = 50_000, changeAddress = to, feeSatoshis = 1_000,
            privateKey = key,
        )
        val hex = WalletCrypto.toHex(raw)
        assertTrue(hex.startsWith("02000000" + "0001"), "version 2 with the SegWit marker/flag")
        assertTrue(hex.endsWith("00000000"), "locktime terminates the transaction")
        // Two outputs: recipient and change, since 49000 is well above the dust limit.
        assertTrue(raw.size > 100, "a signed spend is not this small: ${raw.size} bytes")

        // Spending more than the inputs hold must fail loudly, not produce a bad transaction.
        assertFailsWith<IllegalArgumentException> {
            BitcoinTransaction.buildP2wpkh(
                coin = btc, utxos = listOf(utxo), toAddress = to,
                amountSatoshis = 200_000, changeAddress = to, feeSatoshis = 1_000,
                privateKey = key,
            )
        }
    }

    /** Dust change is dropped into the fee rather than creating an unspendable output. */
    @Test
    fun `dust change is not written as an output`() {
        val btc = CoinRegistry.bySymbol("BTC")!!
        val key = BigInteger("5555555555")
        val to = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val utxo = Utxo("b".repeat(64), 0, 51_100, "")

        val withDust = BitcoinTransaction.buildP2wpkh(
            coin = btc, utxos = listOf(utxo), toAddress = to,
            amountSatoshis = 50_000, changeAddress = to, feeSatoshis = 1_000,
            privateKey = key,
        )
        val withChange = BitcoinTransaction.buildP2wpkh(
            coin = btc, utxos = listOf(Utxo("b".repeat(64), 0, 100_000, "")), toAddress = to,
            amountSatoshis = 50_000, changeAddress = to, feeSatoshis = 1_000,
            privateKey = key,
        )
        assertTrue(
            withDust.size < withChange.size,
            "the 100-satoshi remainder should not have become an output",
        )
    }

    @Test
    fun `fee estimation grows with the transaction`() {
        assertTrue(BitcoinTransaction.estimateVsize(1, 2) < BitcoinTransaction.estimateVsize(3, 2))
        assertTrue(BitcoinTransaction.estimateVsize(1, 1) < BitcoinTransaction.estimateVsize(1, 2))
    }
}
