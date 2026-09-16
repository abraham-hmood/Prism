package com.prism.core

import com.prism.launcher.wallet.BlockHeader
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.ShareTarget
import com.prism.launcher.wallet.WalletCrypto
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mining maths, against Bitcoin's own genesis block.
 *
 * A MINER THAT IS SUBTLY WRONG LOOKS EXACTLY LIKE ONE THAT IS UNLUCKY. If the header is serialised
 * with a field in the wrong byte order, or the hash is compared big-endian, the miner runs at full
 * speed, reports a plausible hash rate, and submits nothing -- forever. There is no symptom to
 * notice. So the header assembly and the target comparison are pinned to a block whose hash has
 * been public since 2009.
 */
class MiningTest {

    /**
     * The genesis block. Its header fields are fixed constants of the network and its hash is the
     * most-quoted number in the industry, which makes it the ideal fixture: if this passes, the
     * header layout, the endianness and SHA-256d are all correct together.
     */
    private val genesis = BlockHeader(
        version = 1,
        previousHashBigEndian = "0".repeat(64),
        merkleRootBigEndian = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
        timestamp = 1231006505L,
        bits = 0x1d00ffffL,
        nonce = 2083236893L,
    )

    @Test
    fun `genesis header hashes to the known block id`() {
        assertEquals(80, genesis.serialize().size, "a block header is exactly 80 bytes")
        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            genesis.blockId(),
        )
    }

    /** The genesis nonce is the winning one; a different nonce must not satisfy the target. */
    @Test
    fun `only the correct nonce meets the target`() {
        val target = ShareTarget.targetFor(1.0)
        val winning = MiningAlgorithms.hash(MiningAlgorithms.SHA256D, genesis.serialize())
        assertTrue(ShareTarget.meetsTarget(winning, target), "the genesis nonce must pass difficulty 1")

        val wrong = MiningAlgorithms.hash(
            MiningAlgorithms.SHA256D,
            genesis.copy(nonce = 2083236894L).serialize(),
        )
        assertFalse(ShareTarget.meetsTarget(wrong, target), "an arbitrary nonce must not pass")
    }

    /**
     * The little-endian reading, isolated. This is the bug that makes a miner silently find
     * nothing, so it gets its own test rather than being implied by the one above.
     */
    @Test
    fun `hashes are compared little-endian`() {
        val hash = WalletCrypto.fromHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        // Read little-endian the trailing 01 becomes the TOP byte, so this is exactly 2^248.
        // Read big-endian it would be 1 -- the two readings differ by 248 orders of magnitude,
        // which is why getting it wrong makes every hash look hopeless.
        assertEquals(BigInteger.ONE.shiftLeft(248), ShareTarget.hashToInteger(hash))

        val leadingZerosLast = WalletCrypto.fromHex(
            "0100000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals(BigInteger.ONE, ShareTarget.hashToInteger(leadingZerosLast))
    }

    @Test
    fun `higher difficulty means a smaller target`() {
        val easy = ShareTarget.targetFor(1.0)
        val hard = ShareTarget.targetFor(1024.0)
        assertTrue(hard < easy, "difficulty and target move in opposite directions")
        assertEquals(ShareTarget.DIFF1_TARGET, easy)

        // A hash that clears difficulty 1 need not clear 1024.
        val hash = MiningAlgorithms.hash(MiningAlgorithms.SHA256D, genesis.serialize())
        assertTrue(ShareTarget.meetsTarget(hash, easy))
    }

    @Test
    fun `scrypt produces a stable 32-byte digest`() {
        val header = genesis.serialize()
        val a = MiningAlgorithms.hash(MiningAlgorithms.SCRYPT, header)
        val b = MiningAlgorithms.hash(MiningAlgorithms.SCRYPT, header)
        assertEquals(32, a.size)
        assertTrue(a.contentEquals(b), "scrypt must be deterministic")
        assertFalse(
            a.contentEquals(MiningAlgorithms.hash(MiningAlgorithms.SHA256D, header)),
            "scrypt and sha256d are different algorithms",
        )
    }

    /** An unimplementable algorithm must fail loudly rather than mine nothing quietly. */
    @Test
    fun `randomx is reported as unavailable rather than faked`() {
        assertFalse(MiningAlgorithms.supportsRandomX())
        assertFalse(MiningAlgorithms.isSupported(MiningAlgorithms.RANDOMX))
        assertFailsWith<IllegalArgumentException> {
            MiningAlgorithms.hash(MiningAlgorithms.RANDOMX, ByteArray(80))
        }
        assertTrue(MiningAlgorithms.isSupported(MiningAlgorithms.SHA256D))
        assertTrue(MiningAlgorithms.isSupported(MiningAlgorithms.SCRYPT))
    }

    /**
     * Pruning support decides whether a coin can be hosted on internal storage or needs an
     * external drive, so a wrong flag here is a user hitting a wall mid-sync rather than at the
     * moment they pick the coin.
     */
    @Test
    fun `pruning support is recorded per chain`() {
        val prunable = listOf("BTC", "LTC", "DOGE", "BCH", "DASH", "DGB", "VTC", "RVN")
        for (symbol in prunable) {
            val coin = com.prism.launcher.wallet.CoinRegistry.bySymbol(symbol)
            assertTrue(coin != null && coin.supportsPruning, "$symbol should support pruning")
        }

        // Zcash cannot prune: its shielded pool needs the whole note-commitment history.
        val zec = com.prism.launcher.wallet.CoinRegistry.bySymbol("ZEC")
        assertTrue(zec != null && !zec.supportsPruning, "ZEC must not be marked prunable")

        // EVM chains are not Bitcoin-style nodes and are not mineable here either.
        val eth = com.prism.launcher.wallet.CoinRegistry.bySymbol("ETH")
        assertFalse(eth!!.supportsPruning)
    }

    /** The cached digest must not carry state between calls or across threads. */
    @Test
    fun `cached digests stay correct under reuse and threading`() {
        val a = WalletCrypto.sha256d("prism".toByteArray())
        repeat(50) {
            assertTrue(WalletCrypto.sha256d("prism".toByteArray()).contentEquals(a))
            WalletCrypto.sha256("something else entirely".toByteArray())
        }
        assertTrue(WalletCrypto.sha256d("prism".toByteArray()).contentEquals(a))

        val results = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = (1..4).map {
            Thread {
                repeat(200) {
                    results.add(WalletCrypto.toHex(WalletCrypto.sha256d("prism".toByteArray())))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, results.toSet().size, "a shared digest would produce garbage across threads")
        assertEquals(WalletCrypto.toHex(a), results.first())
    }

    /** The slice overload must agree with hashing a copied sub-array. */
    @Test
    fun `sha256d over a slice matches a copy`() {
        val data = "0123456789abcdef".toByteArray()
        assertTrue(
            WalletCrypto.sha256d(data, 4, 8).contentEquals(
                WalletCrypto.sha256d(data.copyOfRange(4, 12))
            )
        )
    }

    @Test
    fun `share difficulty rises as a hash gets smaller`() {
        val small = WalletCrypto.fromHex("01" + "00".repeat(31))     // little-endian 1
        val large = WalletCrypto.fromHex("ff".repeat(32))
        assertTrue(ShareTarget.shareDifficulty(small) > ShareTarget.shareDifficulty(large))
    }
}
