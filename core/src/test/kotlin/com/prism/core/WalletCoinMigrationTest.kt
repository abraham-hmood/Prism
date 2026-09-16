package com.prism.core

import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletCipher
import com.prism.launcher.wallet.WalletVault
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A coin added to the registry must actually reach wallets that already exist.
 *
 * THIS IS THE BUG THESE TESTS EXIST FOR. USDC was added to [CoinRegistry] and to the default coin
 * list, and it still appeared on nobody's device. `enableDefaultCoins` only fires when the stored
 * coin list is EMPTY, which it is exactly once in a wallet's life, so every wallet created before
 * USDC existed kept a list that simply did not mention it. The visible symptom was not "a coin is
 * missing" -- it was that PrismCoin could not be converted at all, because the only currency it is
 * permitted to convert against was not enabled anywhere.
 *
 * Adding a CoinSpec is therefore never enough on its own; it needs a version-gated migration, and
 * that is what is pinned here.
 */
class WalletCoinMigrationTest {

    private lateinit var tempDir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previousHost = PrismPlatform.host
        tempDir = File(System.getProperty("java.io.tmpdir"), "prism-wallet-${System.nanoTime()}")
        tempDir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
        // WalletVault refuses to store a phrase without a cipher, and the real one is an Android
        // keystore wrapper. Identity here: these tests are about which coins are enabled, not
        // about the encryption, and a null cipher would make every import silently fail.
        WalletVault.installCipher(object : WalletCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(ciphertext: String) = ciphertext
        })
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
        tempDir.deleteRecursively()
    }

    /** Reproduces a wallet made before USDC existed: a real stored list, no USDC, no version. */
    private fun legacyWallet(symbols: List<String>) {
        WalletVault.import(TEST_PHRASE)
        WalletVault.setEnabledSymbols(symbols)
        PrismPlatform.host.prefs("prism_wallet").edit().putInt("wallet_defaults_version", 0).apply()
    }

    @Test
    fun `USDC is registered at all`() {
        val usdc = CoinRegistry.bySymbol("USDC")
        assertNotNull(usdc, "USDC is not in the registry")
        // It is an ERC-20, not a chain of its own: no contract means no balance can ever be read.
        assertTrue(!usdc.tokenContract.isNullOrBlank(), "USDC has no token contract")
    }

    @Test
    fun `an existing wallet gains USDC`() {
        legacyWallet(listOf("PSC", "BTC", "ETH", "LTC", "DOGE"))
        assertTrue(WalletVault.enabledSymbols().none { it == "USDC" }, "precondition")

        WalletVault.migrateDefaults()

        assertTrue(
            WalletVault.enabledSymbols().any { it.equals("USDC", ignoreCase = true) },
            "USDC never reached an existing wallet: ${WalletVault.enabledSymbols()}",
        )
    }

    @Test
    fun `USDC lands next to PSC`() {
        // The two sides of the only supported conversion should not be at opposite ends of the list.
        legacyWallet(listOf("PSC", "BTC", "ETH"))
        WalletVault.migrateDefaults()
        val symbols = WalletVault.enabledSymbols().map { it.uppercase() }
        assertEquals("PSC", symbols[0])
        assertEquals("USDC", symbols[1])
    }

    @Test
    fun `a wallet with no PSC still gains USDC`() {
        legacyWallet(listOf("BTC", "ETH"))
        WalletVault.migrateDefaults()
        assertTrue(WalletVault.enabledSymbols().any { it.equals("USDC", ignoreCase = true) })
    }

    @Test
    fun `migrating twice does not duplicate USDC`() {
        legacyWallet(listOf("PSC", "BTC"))
        WalletVault.migrateDefaults()
        WalletVault.migrateDefaults()
        assertEquals(
            1, WalletVault.enabledSymbols().count { it.equals("USDC", ignoreCase = true) },
            "USDC was added more than once",
        )
    }

    @Test
    fun `a coin the user turned off stays off`() {
        // The migration is version-gated precisely so it does not undo a deliberate choice. Once it
        // has run, removing USDC must stick.
        legacyWallet(listOf("PSC", "BTC"))
        WalletVault.migrateDefaults()
        WalletVault.disableCoin("USDC")
        WalletVault.migrateDefaults()
        assertTrue(
            WalletVault.enabledSymbols().none { it.equals("USDC", ignoreCase = true) },
            "the migration re-enabled a coin the user removed",
        )
    }

    @Test
    fun `a brand new wallet has both sides of the conversion`() {
        WalletVault.import(TEST_PHRASE)
        val symbols = WalletVault.enabledSymbols().map { it.uppercase() }
        assertTrue(symbols.contains("PSC"), "new wallet is missing PSC: $symbols")
        assertTrue(symbols.contains("USDC"), "new wallet is missing USDC: $symbols")
    }

    private companion object {
        // A standard BIP-39 test vector; never used for real funds.
        const val TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
