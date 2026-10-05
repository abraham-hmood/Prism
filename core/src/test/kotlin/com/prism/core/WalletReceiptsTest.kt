package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.wallet.WalletReceipts
import java.io.File
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two receipt rules. PHASE 88.
 *
 * ## Why these two in particular
 *
 * Both fail in a way that looks like the feature working. Get the baseline wrong and every balance read
 * announces a receipt, which reads as an over-eager notification rather than a bug. Get the first-sighting
 * rule wrong and restoring a wallet announces its entire balance as a fresh payment -- once, convincingly,
 * to somebody who has just recovered a wallet and is already anxious about whether it worked.
 *
 * Neither would be caught by a compiler, and neither is visible without waiting for a real payment.
 */
class WalletReceiptsTest {

    private lateinit var dir: File
    private lateinit var previousHost: PlatformHost
    private val announced = mutableListOf<Triple<String, String, String>>()
    private lateinit var coin: CoinSpec

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-receipts-" + System.nanoTime())
        dir.mkdirs()
        PrismPlatform.host = object : PlatformHost by JvmHost() {
            override fun dataDir(): File = dir
        }
        coin = CoinRegistry.bySymbol("BTC") ?: CoinRegistry.all().first()
        announced.clear()
        // Recorded rather than shown: what matters is WHICH receipts were announced, and a tray balloon
        // would make that unobservable.
        WalletReceipts.presenter = { c, amountText, note ->
            announced += Triple(c.symbol, amountText, note)
        }
        PrismSettings.clearLastSeenBalance(coin.symbol)
    }

    @AfterTest
    fun close() {
        PrismPlatform.host = previousHost
        dir.deleteRecursively()
    }

    @Test
    fun `a first sighting records the baseline and announces nothing`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(500_000))

        assertTrue(
            announced.isEmpty(),
            "a wallet that already held funds must not announce them as a fresh payment, but " +
                announced.size + " receipt(s) were announced",
        )
        assertEquals(
            BigInteger.valueOf(500_000), PrismSettings.getLastSeenBalance(coin.symbol),
            "the baseline should have been written even though nothing was announced",
        )
    }

    @Test
    fun `an increase announces the difference and not the total`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(150_000))

        assertEquals(1, announced.size, "exactly one receipt")
        // The DIFFERENCE. Announcing the total would tell somebody with a full wallet that they had just
        // received everything in it.
        assertEquals(coin.format(BigInteger.valueOf(50_000)) + " " + coin.symbol, announced.first().second)
    }

    @Test
    fun `a decrease announces nothing`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(150_000))
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))

        assertTrue(announced.isEmpty(), "spending is not receiving")
        assertEquals(BigInteger.valueOf(100_000), PrismSettings.getLastSeenBalance(coin.symbol))
    }

    @Test
    fun `an unchanged balance announces nothing however often it is polled`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))
        repeat(5) { WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000)) }
        assertTrue(announced.isEmpty(), "a poll is not an event")
    }

    @Test
    fun `a payment that landed while the app was closed is still announced`() {
        // The whole point of a STORED baseline rather than an in-memory one: nothing observed the
        // increase happening, and it is still reported on the next look.
        PrismSettings.setLastSeenBalance(coin.symbol, BigInteger.valueOf(100_000))

        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(175_000))

        assertEquals(1, announced.size)
        assertEquals(coin.format(BigInteger.valueOf(75_000)) + " " + coin.symbol, announced.first().second)
    }

    @Test
    fun `a note is carried through when a chain has one`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))
        WalletReceipts.notifyReceived(coin, BigInteger.valueOf(20_000), "rent, March")

        assertEquals(1, announced.size)
        assertEquals("rent, March", announced.first().third)
    }

    @Test
    fun `a zero or negative amount is never announced`() {
        WalletReceipts.notifyReceived(coin, BigInteger.ZERO)
        WalletReceipts.notifyReceived(coin, BigInteger.valueOf(-5))
        assertTrue(announced.isEmpty())
    }

    @Test
    fun `clearing the baselines restores the first-sighting rule`() {
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))
        WalletReceipts.forgetBaselines()

        // A wiped wallet must behave like a new one. Without this, the next wallet's first balance would
        // be compared against the previous wallet's figures.
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(900_000))
        assertTrue(announced.isEmpty(), "after a wipe, the next reading is a first sighting again")
    }

    @Test
    fun `each coin keeps its own baseline`() {
        val other = CoinRegistry.all().firstOrNull { it.symbol != coin.symbol } ?: return
        PrismSettings.clearLastSeenBalance(other.symbol)

        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(100_000))
        WalletReceipts.onBalanceObserved(other, BigInteger.valueOf(100_000))
        WalletReceipts.onBalanceObserved(coin, BigInteger.valueOf(120_000))

        assertEquals(1, announced.size, "only the coin that changed")
        assertEquals(coin.symbol, announced.first().first)
    }

    @Test
    fun `two coins get different notification ids`() {
        val other = CoinRegistry.all().firstOrNull { it.symbol != coin.symbol } ?: return
        // A shared id would make a second coin's receipt REPLACE the first one's notification, which on a
        // phone means a payment silently disappearing from the shade.
        assertTrue(WalletReceipts.idFor(coin.symbol) != WalletReceipts.idFor(other.symbol))
    }
}
