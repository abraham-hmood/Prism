package com.prism.core

import com.prism.launcher.wallet.AddressScheme
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The backup archive.
 *
 * WHAT MATTERS HERE IS THE FAILURE CASES. A backup that round-trips is the easy half; the half that
 * ruins someone is a wrong phrase quietly producing a wallet with no coins in it, or a corrupted
 * file being parsed as an empty one and overwriting what was there. Every negative path below
 * asserts that the archive REFUSES rather than degrades.
 */
class WalletArchiveTest {

    private val phrase = Bip39.generate(30)

    private fun samplePayload() = WalletArchive.Payload(
        phrase = phrase,
        enabledCoins = listOf("BTC", "ETH", "LTC"),
        customCoins = listOf(
            CoinSpec("XYZ", "Example", 1234, AddressScheme.P2PKH(0x1e), 8, isCustom = true)
        ),
        fiatCurrency = "GBP",
        shares = mapOf(
            "BTC" to WalletArchive.ShareRecord(42, 4096.0),
            "LTC" to WalletArchive.ShareRecord(7, 112.0),
        ),
        miningMode = "pool",
        miningDiscoveryUrl = "https://example.invalid/coins",
        miningThreads = 3,
        soloNodeUrl = "http://192.168.1.5:8332",
        selfHostNode = true,
    )

    @Test
    fun `archive round trips every field`() {
        val packed = WalletArchive.pack(samplePayload(), phrase)
        val result = WalletArchive.unpack(packed, phrase)
        assertTrue(result is WalletArchive.Restore.Restored, "a correct phrase must open the archive")

        val restored = (result as WalletArchive.Restore.Restored).payload
        assertEquals(phrase, restored.phrase)
        assertEquals(listOf("BTC", "ETH", "LTC"), restored.enabledCoins)
        assertEquals("GBP", restored.fiatCurrency)
        assertEquals(42L, restored.shares["BTC"]?.count)
        assertEquals(4096.0, restored.shares["BTC"]?.totalDifficulty)
        assertEquals("pool", restored.miningMode)
        assertEquals(3, restored.miningThreads)
        assertEquals("http://192.168.1.5:8332", restored.soloNodeUrl)
        assertTrue(restored.selfHostNode)

        assertEquals(1, restored.customCoins.size)
        assertEquals("XYZ", restored.customCoins[0].symbol)
        assertEquals(1234L, restored.customCoins[0].coinType)
        assertTrue(restored.customCoins[0].isCustom)
    }

    /** The whole security claim: a different phrase must not open it. */
    @Test
    fun `a wrong phrase is rejected, not silently emptied`() {
        val packed = WalletArchive.pack(samplePayload(), phrase)
        val other = Bip39.generate(30)
        assertEquals(WalletArchive.Restore.WrongPhrase, WalletArchive.unpack(packed, other))

        // One wrong word is enough.
        val nearly = phrase.toMutableList().also { it[5] = if (it[5] == "zoo") "abandon" else "zoo" }
        assertEquals(WalletArchive.Restore.WrongPhrase, WalletArchive.unpack(packed, nearly))
    }

    /** Tampering must fail the authentication tag rather than decrypt into something plausible. */
    @Test
    fun `a modified archive fails authentication`() {
        val packed = WalletArchive.pack(samplePayload(), phrase)
        val tampered = packed.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertEquals(WalletArchive.Restore.WrongPhrase, WalletArchive.unpack(tampered, phrase))
    }

    @Test
    fun `foreign and truncated files are reported as such`() {
        val notAnArchive = "just some text file contents here, definitely not a wallet".toByteArray()
        val foreign = WalletArchive.unpack(notAnArchive, phrase)
        assertTrue(foreign is WalletArchive.Restore.Corrupt)

        val packed = WalletArchive.pack(samplePayload(), phrase)
        val truncated = packed.copyOfRange(0, 20)
        assertTrue(WalletArchive.unpack(truncated, phrase) is WalletArchive.Restore.Corrupt)
    }

    /** Compression has to actually happen, or the archive is just a big blob. */
    @Test
    fun `payload is compressed before encryption`() {
        val many = (1..200).map { "COIN$it" }
        val padded = samplePayload().copy(enabledCoins = many)
        val packed = WalletArchive.pack(padded, phrase)
        // 200 repetitive symbols is well over 1 KB as JSON; gzip should crush it.
        val rawJsonSize = many.sumOf { it.length + 4 }
        assertTrue(
            packed.size < rawJsonSize,
            "expected compression: archive ${packed.size} bytes vs ~$rawJsonSize raw",
        )
    }

    /** Two archives of the same data must differ -- a fixed salt or IV would be a real weakness. */
    @Test
    fun `salt and iv are fresh for every archive`() {
        val a = WalletArchive.pack(samplePayload(), phrase)
        val b = WalletArchive.pack(samplePayload(), phrase)
        assertFalse(a.contentEquals(b), "identical ciphertext means a reused salt/IV")
        // Both still open.
        assertTrue(WalletArchive.unpack(a, phrase) is WalletArchive.Restore.Restored)
        assertTrue(WalletArchive.unpack(b, phrase) is WalletArchive.Restore.Restored)
    }

    /** Merging keeps both sides' coins and does not inflate share counts on re-import. */
    @Test
    fun `merge unions coins and takes the larger share count`() {
        val existing = samplePayload().copy(
            enabledCoins = listOf("BTC", "DOGE"),
            customCoins = listOf(
                CoinSpec("AAA", "Local Only", 11, AddressScheme.P2PKH(0), 8, isCustom = true)
            ),
            shares = mapOf("BTC" to WalletArchive.ShareRecord(100, 8000.0)),
        )
        val imported = samplePayload()

        val merged = WalletArchive.merge(imported, existing)

        assertTrue(merged.enabledCoins.containsAll(listOf("BTC", "ETH", "LTC", "DOGE")))
        assertEquals(4, merged.enabledCoins.size, "union, without duplicates")
        assertEquals(2, merged.customCoins.size, "both custom coins survive")

        // The device had more BTC shares than the archive; re-importing must not lose or double it.
        assertEquals(100L, merged.shares["BTC"]?.count)
        assertEquals(7L, merged.shares["LTC"]?.count, "a coin only in the archive is carried over")

        // Importing the same archive twice is idempotent for share counts.
        val twice = WalletArchive.merge(imported, merged)
        assertEquals(100L, twice.shares["BTC"]?.count)
    }

    @Test
    fun `merging into nothing keeps the archive as-is`() {
        val imported = samplePayload()
        assertEquals(imported, WalletArchive.merge(imported, null))
    }
}
