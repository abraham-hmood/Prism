package com.prism.launcher.wallet

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.math.BigInteger

/**
 * Encrypts the recovery phrase at rest.
 *
 * AN INTERFACE RATHER THAN AN IMPLEMENTATION because the only encryption worth having here is
 * hardware-backed, and that is platform-specific: on Android the key lives in the Keystore and
 * never enters app memory. :core cannot reach that, and inventing a portable substitute would mean
 * shipping a key derived from something on disk -- which protects against nothing, while looking
 * like it protects against everything.
 */
interface WalletCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

/**
 * The wallet's persistent state: one recovery phrase, and the coins derived from it.
 *
 * ONE SEED FOR EVERY COIN. Each wallet in the list is a derivation path, not an independent secret,
 * so adding a coin years later needs no new backup -- the phrase written down on day one already
 * contains it. This is the entire reason BIP-39/BIP-32 are worth implementing rather than
 * generating a random key per coin.
 *
 * REFUSES TO STORE ANYTHING WITHOUT A CIPHER. If no [WalletCipher] has been installed, [create] and
 * [import] fail loudly rather than writing a recovery phrase to shared preferences in the clear.
 * A wallet that silently degrades its own security is worse than one that does not start.
 */
object WalletVault {

    private const val KEY_PHRASE = "wallet_phrase_encrypted"
    private const val KEY_COINS = "wallet_coins"
    private const val KEY_CUSTOM = "wallet_custom_coins"
    private const val KEY_FIAT = "wallet_fiat_currency"
    private const val KEY_WORD_COUNT = "wallet_word_count"
    private const val KEY_DEFAULTS_VERSION = "wallet_defaults_version"

    /** Bumped whenever a coin is added that existing wallets should also get. */
    private const val DEFAULTS_VERSION = 2

    // USDC IS NOT OPTIONAL THE WAY THE OTHERS ARE. It is the only asset PrismCoin converts
    // against, so a wallet without it cannot convert at all -- the Convert button opens onto
    // an empty list of counter-currencies. It ships enabled for that reason, not because a
    // stablecoin belongs in a list of "the majors".
    private val DEFAULT_COINS = listOf("PSC", "USDC", "BTC", "ETH", "LTC", "DOGE")

    private fun prefs() = PrismPlatform.host.prefs("prism_wallet")

    @Volatile
    private var cipher: WalletCipher? = null

    /** Installed once at startup, before any wallet screen can be reached. */
    fun installCipher(c: WalletCipher) {
        cipher = c
    }

    fun hasCipher(): Boolean = cipher != null

    fun isInitialized(): Boolean = !prefs().getString(KEY_PHRASE, null).isNullOrBlank()

    // ── Creation and import ────────────────────────────────────────────────

    sealed class Outcome {
        data class Created(val phrase: List<String>) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Generates a phrase and stores it. [wordCount] is the user's choice, floored at
     * [Bip39.MIN_NEW_WALLET_WORDS] -- see the note in [Bip39] about what phrases that long mean
     * for portability to other wallets.
     */
    fun create(wordCount: Int = Bip39.MIN_NEW_WALLET_WORDS): Outcome {
        val c = cipher ?: return Outcome.Failed("Secure storage is unavailable on this device.")
        if (wordCount < Bip39.MIN_NEW_WALLET_WORDS) {
            return Outcome.Failed("New phrases must be at least ${Bip39.MIN_NEW_WALLET_WORDS} words.")
        }
        if (Bip39.entropyBitsFor(wordCount) == null) {
            return Outcome.Failed("$wordCount words cannot encode a recovery phrase; use a multiple of 3.")
        }
        val phrase = Bip39.generate(wordCount)
        prefs().edit()
            .putString(KEY_PHRASE, c.encrypt(phrase.joinToString(" ")))
            .putInt(KEY_WORD_COUNT, phrase.size)
            .apply()
        enableDefaultCoins()
        return Outcome.Created(phrase)
    }

    /** Restores from a phrase the user typed. Accepts standard lengths as well as Prism's own. */
    fun import(rawPhrase: String): Outcome {
        val c = cipher ?: return Outcome.Failed("Secure storage is unavailable on this device.")
        val words = Bip39.splitPhrase(rawPhrase)
        when (val v = Bip39.validate(words)) {
            is Bip39.Validation.Invalid -> return Outcome.Failed(v.reason)
            is Bip39.Validation.Valid -> Unit
        }
        prefs().edit()
            .putString(KEY_PHRASE, c.encrypt(words.joinToString(" ")))
            .putInt(KEY_WORD_COUNT, words.size)
            .apply()
        enableDefaultCoins()
        return Outcome.Created(words)
    }

    /**
     * Deletes everything. The phrase is the wallet -- without a written copy this is irreversible,
     * so callers are expected to have said so before getting here.
     */
    fun wipe() {
        prefs().edit()
            .remove(KEY_PHRASE).remove(KEY_COINS).remove(KEY_CUSTOM).remove(KEY_WORD_COUNT)
            .apply()
    }

    fun phrase(): List<String>? {
        val stored = prefs().getString(KEY_PHRASE, null) ?: return null
        val plain = cipher?.decrypt(stored) ?: return null
        return Bip39.splitPhrase(plain)
    }

    fun wordCount(): Int = prefs().getInt(KEY_WORD_COUNT, 0)

    /**
     * The master seed, derived on demand and never stored.
     *
     * Recomputed from the phrase each time rather than cached: PBKDF2 at 2048 rounds costs a few
     * milliseconds, and a cached seed is a second copy of the wallet sitting in memory for the
     * lifetime of the process.
     */
    fun seed(): ByteArray? = phrase()?.let { Bip39.toSeed(it) }

    // ── Which coins the user holds ─────────────────────────────────────────

    fun enabledSymbols(): List<String> {
        val raw = prefs().getString(KEY_COINS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setEnabledSymbols(symbols: List<String>) {
        prefs().edit().putString(KEY_COINS, symbols.distinct().joinToString(",")).apply()
    }

    fun enableCoin(symbol: String) {
        setEnabledSymbols(enabledSymbols() + symbol.uppercase())
    }

    fun disableCoin(symbol: String) {
        setEnabledSymbols(enabledSymbols().filterNot { it.equals(symbol, ignoreCase = true) })
    }

    /** A new wallet starts with the majors rather than all sixteen, which would be a wall of dust. */
    private fun enableDefaultCoins() {
        if (enabledSymbols().isEmpty()) {
            setEnabledSymbols(DEFAULT_COINS)
        }
        migrateDefaults()
    }

    /**
     * Adds coins introduced after a wallet was created.
     *
     * NEEDED BECAUSE [enableDefaultCoins] ONLY RUNS ONCE, at creation or import. A wallet made
     * before PrismCoin existed has a stored coin list that simply does not mention it, so PSC
     * would never appear no matter how many times the page was opened.
     *
     * Version-gated rather than "add anything missing", so a coin the user deliberately turned off
     * stays off. Each migration runs exactly once.
     */
    fun migrateDefaults() {
        val version = prefs().getInt(KEY_DEFAULTS_VERSION, 0)
        if (version >= DEFAULTS_VERSION) return

        if (version < 1 && isInitialized()) {
            // v1 introduced PrismCoin.
            if (enabledSymbols().none { it.equals("PSC", ignoreCase = true) }) {
                setEnabledSymbols(listOf("PSC") + enabledSymbols())
            }
        }
        if (version < 2 && isInitialized()) {
            // v2 introduced USDC, and it was missed the first time: adding the CoinSpec to the
            // registry made USDC *representable*, but every existing wallet already had a stored
            // coin list, so enableDefaultCoins() -- which only fires on an empty list -- never ran
            // again and no wallet ever gained it. USDC then appeared nowhere in the wallet list and
            // PSC could not be converted, because the only currency it is allowed to convert
            // against was not enabled on a single device.
            if (enabledSymbols().none { it.equals("USDC", ignoreCase = true) }) {
                // After PSC, so the two sides of the only supported conversion sit together.
                val current = enabledSymbols()
                val at = current.indexOfFirst { it.equals("PSC", ignoreCase = true) }
                setEnabledSymbols(
                    if (at >= 0) current.subList(0, at + 1) + "USDC" + current.subList(at + 1, current.size)
                    else listOf("USDC") + current
                )
            }
        }
        prefs().edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION).apply()
    }

    // ── Derived accounts ───────────────────────────────────────────────────

    /**
     * One coin's account: its address, and the key that can spend from it.
     *
     * The private key is passed around as a [BigInteger] and never persisted -- it is derivable
     * from the phrase at any time, so storing it would add a second thing to leak for no benefit.
     */
    data class Account(val coin: CoinSpec, val address: String, val privateKey: BigInteger) {
        /** WIF, for importing a single coin into another wallet without exposing the whole phrase. */
        fun exportPrivateKeyWif(compressed: Boolean = true): String {
            val prefix = byteArrayOf(0x80.toByte())
            val body = prefix + WalletCrypto.toFixedBytes(privateKey, 32) +
                if (compressed) byteArrayOf(0x01) else ByteArray(0)
            return Base58.encodeChecked(body)
        }

        fun exportPrivateKeyHex(): String = "0x" + WalletCrypto.toHex(WalletCrypto.toFixedBytes(privateKey, 32))

        override fun toString(): String = "Account(${coin.symbol}, $address)"
    }

    fun account(coin: CoinSpec, seed: ByteArray, accountIndex: Int = 0): Account {
        val key = Bip32.deriveFromSeed(seed, coin.derivationPath(accountIndex))
        return Account(coin, coin.addressFor(key.privateKey), key.privateKey)
    }

    /** Every enabled coin's account, in the order the user's list holds them. */
    fun accounts(): List<Account> {
        val seed = seed() ?: return emptyList()
        return enabledSymbols().mapNotNull { CoinRegistry.bySymbol(it) }.map { account(it, seed) }
    }

    /** Just the address, for showing a coin the user has not enabled yet. */
    fun addressFor(coin: CoinSpec): String? = seed()?.let { account(coin, it).address }

    // ── Custom coins ───────────────────────────────────────────────────────

    fun loadCustomCoins() {
        val raw = prefs().getString(KEY_CUSTOM, null) ?: return
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoinSpec(
                    symbol = o.getString("symbol"),
                    name = o.getString("name"),
                    coinType = o.optLong("coinType", 0L),
                    scheme = when (o.optString("scheme", "p2pkh")) {
                        "segwit" -> AddressScheme.SegWitV0(o.optString("hrp", "bc"))
                        "evm" -> AddressScheme.Ethereum
                        "monero" -> AddressScheme.MoneroEd25519
                        else -> AddressScheme.P2PKH(o.optInt("version", 0))
                    },
                    decimals = o.optInt("decimals", 8),
                    chainId = o.optLong("chainId", 0L),
                    isCustom = true,
                )
            }
        }.getOrElse { emptyList() }
        CoinRegistry.replaceCustom(parsed)
    }

    fun saveCustomCoins() {
        val arr = JSONArray()
        for (c in CoinRegistry.customCoins()) {
            val o = JSONObject()
                .put("symbol", c.symbol).put("name", c.name)
                .put("coinType", c.coinType).put("decimals", c.decimals)
                .put("chainId", c.chainId)
            when (val s = c.scheme) {
                is AddressScheme.SegWitV0 -> o.put("scheme", "segwit").put("hrp", s.hrp)
                is AddressScheme.Ethereum -> o.put("scheme", "evm")
                is AddressScheme.MoneroEd25519 -> o.put("scheme", "monero")
                is AddressScheme.P2PKH -> o.put("scheme", "p2pkh").put("version", s.version)
            }
            arr.put(o)
        }
        prefs().edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    // ── Display currency ───────────────────────────────────────────────────

    fun fiatCurrency(): String = prefs().getString(KEY_FIAT, "USD") ?: "USD"

    fun setFiatCurrency(code: String) {
        prefs().edit().putString(KEY_FIAT, code.uppercase()).apply()
    }
}
