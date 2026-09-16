package com.prism.launcher.wallet

import java.math.BigInteger

/**
 * How a coin turns a public key into an address.
 *
 * THESE THREE SCHEMES COVER ESSENTIALLY EVERY COIN A USER WILL ASK FOR, which is the reason custom
 * coins are possible at all. Almost every altcoin is a fork of Bitcoin (P2PKH with a different
 * version byte, or SegWit with a different prefix) or an EVM chain (identical addresses to
 * Ethereum). A coin nobody has added yet is therefore usually not new cryptography -- it is a new
 * row in a table, which is what [CoinRegistry.addCustom] writes.
 */
sealed class AddressScheme {

    /**
     * Legacy Base58Check, `version` being the prefix that fixes the leading character.
     *
     * ONE OR TWO BYTES. Most chains use a single byte (Bitcoin 0x00, Dogecoin 0x1e), but Zcash
     * transparent addresses use two (0x1CB8), which is why this is not a Byte -- truncating it
     * would produce a well-formed address on the wrong chain.
     */
    data class P2PKH(val version: Int) : AddressScheme() {
        fun versionBytes(): ByteArray =
            if (version > 0xff) byteArrayOf((version ushr 8).toByte(), version.toByte())
            else byteArrayOf(version.toByte())
    }

    /** Native SegWit v0, `hrp` being the human-readable prefix (`bc`, `ltc`). */
    data class SegWitV0(val hrp: String) : AddressScheme()

    /** Ethereum and every EVM chain: last 20 bytes of KECCAK256 of the uncompressed key. */
    data object Ethereum : AddressScheme()

    /**
     * Monero: two ed25519 keys, not one secp256k1 hash.
     *
     * The odd one out, and unavoidably so. Every other scheme here hashes a secp256k1 public key;
     * Monero uses a different curve entirely and an address carries a spend key AND a view key.
     * See [MoneroKeys] for how the pair is derived and, more importantly, for how the resulting
     * wallet stays recoverable in other Monero software.
     */
    data object MoneroEd25519 : AddressScheme()
}

/**
 * A coin this wallet can hold.
 *
 * [coinType] is the BIP-44 registered index (SLIP-44). Getting it wrong does not produce an
 * invalid wallet -- it produces a perfectly valid one at the wrong path, whose funds another
 * wallet restoring the same phrase will never find.
 */
data class CoinSpec(
    val symbol: String,
    val name: String,
    val coinType: Long,
    val scheme: AddressScheme,
    val decimals: Int,
    /** Smallest-unit name, for the UI: satoshi, wei, litoshi. */
    val unitName: String = "",
    /** Chain id for EVM chains; 0 for everything else. Signing is chain-specific via EIP-155. */
    val chainId: Long = 0,
    /** Set for coins added by the user rather than shipped. */
    val isCustom: Boolean = false,
    /**
     * Contract address when this is a TOKEN rather than a chain of its own. Empty for real coins.
     *
     * A token has no chain, no derivation path and no native balance. USDC lives on Ethereum, so it
     * carries Ethereum's [coinType] and [scheme] and therefore resolves to the SAME address as ETH
     * -- which is correct and is how every wallet shows it. What differs is where the balance comes
     * from: a native coin asks the node for the account's balance, a token asks the contract. See
     * WalletNetwork.balanceInfo.
     */
    val tokenContract: String = "",
    /** Whether Prism can currently mine it on a phone -- see MiningAlgorithms. */
    val miningAlgorithm: String = "",
    /**
     * Whether the chain's node can run PRUNED -- keeping only recent blocks on disk instead of the
     * entire history.
     *
     * This decides whether a phone can host the coin's node at all. Pruned, Bitcoin needs a few
     * gigabytes rather than hundreds; unpruned, it needs an external drive. Zcash is the clearest
     * counter-example among the coins here: `zcashd` cannot prune, because its shielded pool needs
     * the whole note-commitment history to stay verifiable.
     *
     * PRUNING LIMITS STORAGE, NOT BANDWIDTH. Every pruned node still downloads and validates the
     * full chain once; it just discards the old blocks afterwards. That distinction is the thing
     * people are surprised by, so it is stated where the flag lives.
     */
    val supportsPruning: Boolean = false,
) {
    /**
     * BIP-44 for legacy and EVM chains, BIP-84 for native SegWit.
     *
     * The purpose (44 vs 84) has to match the address type or the wallet silently diverges from
     * every other implementation: a SegWit address derived at the BIP-44 path is a wallet only
     * Prism can find.
     */
    fun derivationPath(accountIndex: Int = 0, addressIndex: Int = 0): String {
        val purpose = if (scheme is AddressScheme.SegWitV0) 84 else 44
        return "m/$purpose'/$coinType'/$accountIndex'/0/$addressIndex"
    }

    fun addressFor(privateKey: BigInteger): String = when (scheme) {
        is AddressScheme.P2PKH -> {
            val hash = WalletCrypto.hash160(WalletCrypto.compressedPublicKey(privateKey))
            Base58.encodeChecked(scheme.versionBytes() + hash)
        }
        is AddressScheme.SegWitV0 -> {
            val hash = WalletCrypto.hash160(WalletCrypto.compressedPublicKey(privateKey))
            Bech32.encodeSegwit(scheme.hrp, 0, hash)
        }
        is AddressScheme.Ethereum -> {
            val body = WalletCrypto.uncompressedPublicKeyBody(privateKey)
            val hashed = WalletCrypto.keccak256(body)
            toChecksumAddress(WalletCrypto.toHex(hashed.copyOfRange(12, 32)))
        }
        is AddressScheme.MoneroEd25519 -> MoneroKeys.fromPrivateKey(privateKey).address
    }

    /**
     * EIP-55 mixed-case checksum. Purely cosmetic to a node, but every wallet and explorer
     * validates it, and an all-lowercase address gets flagged as suspicious by some of them.
     */
    private fun toChecksumAddress(hexNoPrefix: String): String {
        val lower = hexNoPrefix.lowercase()
        val hash = WalletCrypto.keccak256(lower.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder("0x")
        for (i in lower.indices) {
            val c = lower[i]
            if (c in '0'..'9') {
                sb.append(c)
            } else {
                val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0xf
                sb.append(if (nibble >= 8) c.uppercaseChar() else c)
            }
        }
        return sb.toString()
    }

    fun isValidAddress(address: String): Boolean {
        val a = address.trim()
        if (a.isEmpty()) return false
        return when (scheme) {
            is AddressScheme.P2PKH ->
                // Accepts any Base58Check address on this chain, including P2SH (`3...`),
                // because sending TO a script address is normal even though this wallet does
                // not issue them.
                Base58.isValidChecked(a)
            is AddressScheme.SegWitV0 ->
                Bech32.isValidSegwit(a, scheme.hrp) || Base58.isValidChecked(a)
            is AddressScheme.Ethereum ->
                a.matches(Regex("^0x[0-9a-fA-F]{40}$"))
            is AddressScheme.MoneroEd25519 -> MoneroAddress.isValid(a)
        }
    }

    /** Formats a smallest-unit amount for display, trimming meaningless trailing zeros. */
    fun format(smallestUnits: BigInteger): String {
        val divisor = BigInteger.TEN.pow(decimals)
        val whole = smallestUnits.divide(divisor)
        val fraction = smallestUnits.mod(divisor).toString().padStart(decimals, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    /** Parses a user-typed decimal amount into smallest units, exactly -- no floating point. */
    fun parseAmount(text: String): BigInteger? {
        val clean = text.trim().replace(",", "")
        if (clean.isEmpty()) return null
        if (!clean.matches(Regex("^\\d*\\.?\\d*$")) || clean == ".") return null
        val parts = clean.split(".")
        val whole = parts[0].ifEmpty { "0" }
        val frac = (parts.getOrNull(1) ?: "").let {
            if (it.length > decimals) return null      // more precision than the coin has
            it.padEnd(decimals, '0')
        }
        return runCatching { BigInteger(whole + frac) }.getOrNull()
    }
}

/**
 * The coins the wallet knows, built in plus whatever the user added.
 *
 * The built-in list is not exhaustive and cannot be: new coins appear faster than any shipped
 * table can track. That is precisely why [addCustom] exists -- an unlisted coin needs its SLIP-44
 * index and address style, both of which are public facts, not a new release of Prism.
 */
object CoinRegistry {

    /**
     * SLIP-44 coin types. Bitcoin and Litecoin issue native SegWit (BIP-84) addresses because that
     * is what current wallets on those chains use and it lowers fees; the rest use the address form
     * their own ecosystem actually settled on.
     */
    val builtIn: List<CoinSpec> = listOf(
        // Prism's own chain. Coin type 0x50534300 is outside SLIP-44's registered range on
        // purpose -- PSC is not registered with anyone, and colliding with a real coin's path
        // would put two different currencies at the same derived keys.
        CoinSpec(
            "PSC", "PrismCoin", 1_347_310_848L,
            AddressScheme.P2PKH(0x37), 8, "prismite",
            miningAlgorithm = "sha256d",
        ),
        CoinSpec("BTC", "Bitcoin", 0, AddressScheme.SegWitV0("bc"), 8, "satoshi", miningAlgorithm = "sha256d", supportsPruning = true),
        CoinSpec("ETH", "Ethereum", 60, AddressScheme.Ethereum, 18, "wei", chainId = 1),
        // Same derivation as ETH by design: an ERC-20 balance sits at the owner's Ethereum
        // address, so USDC and ETH share one address and differ only in where the balance is read.
        CoinSpec(
            "USDC", "USD Coin", 60, AddressScheme.Ethereum, 6, "micro-USDC", chainId = 1,
            tokenContract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        ),
        CoinSpec("LTC", "Litecoin", 2, AddressScheme.SegWitV0("ltc"), 8, "litoshi", miningAlgorithm = "scrypt", supportsPruning = true),
        CoinSpec("DOGE", "Dogecoin", 3, AddressScheme.P2PKH(0x1e), 8, "koinu", miningAlgorithm = "scrypt", supportsPruning = true),
        CoinSpec("BCH", "Bitcoin Cash", 145, AddressScheme.P2PKH(0x00), 8, "satoshi", miningAlgorithm = "sha256d", supportsPruning = true),
        CoinSpec("DASH", "Dash", 5, AddressScheme.P2PKH(0x4c), 8, "duff", supportsPruning = true),
        // Zcash cannot prune: shielded transactions need the full note-commitment history.
        CoinSpec("ZEC", "Zcash", 133, AddressScheme.P2PKH(0x1cb8), 8, "zatoshi"),
        CoinSpec("ETC", "Ethereum Classic", 61, AddressScheme.Ethereum, 18, "wei", chainId = 61),
        CoinSpec("BNB", "BNB Smart Chain", 714, AddressScheme.Ethereum, 18, "wei", chainId = 56),
        CoinSpec("MATIC", "Polygon", 966, AddressScheme.Ethereum, 18, "wei", chainId = 137),
        CoinSpec("AVAX", "Avalanche C-Chain", 9000, AddressScheme.Ethereum, 18, "wei", chainId = 43114),
        CoinSpec("ARB", "Arbitrum One", 60, AddressScheme.Ethereum, 18, "wei", chainId = 42161),
        CoinSpec("OP", "Optimism", 60, AddressScheme.Ethereum, 18, "wei", chainId = 10),
        // Monero. Coin type 128 is its SLIP-44 index; the address scheme is ed25519 rather than
        // anything else here, and 12 decimals rather than 8.
        CoinSpec(
            "XMR", "Monero", 128, AddressScheme.MoneroEd25519, 12, "piconero",
            miningAlgorithm = "randomx",
        ),
        CoinSpec("DGB", "DigiByte", 20, AddressScheme.P2PKH(0x1e), 8, "satoshi", miningAlgorithm = "scrypt", supportsPruning = true),
        CoinSpec("VTC", "Vertcoin", 28, AddressScheme.P2PKH(0x47), 8, "satoshi", supportsPruning = true),
        CoinSpec("RVN", "Ravencoin", 175, AddressScheme.P2PKH(0x3c), 8, "satoshi", supportsPruning = true),
    )

    private val custom = mutableListOf<CoinSpec>()

    /** Built-ins first, then the user's own, deduplicated by symbol. */
    fun all(): List<CoinSpec> {
        val seen = HashSet<String>()
        return (builtIn + custom).filter { seen.add(it.symbol.uppercase()) }
    }

    fun bySymbol(symbol: String): CoinSpec? =
        all().firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    /**
     * Registers a coin Prism does not ship.
     *
     * Everything needed is public: the SLIP-44 coin type and how the chain writes addresses. A
     * fork of Bitcoin needs its version byte, an EVM chain needs its chain id, and nothing else
     * about the chain matters for holding and deriving keys.
     */
    fun addCustom(spec: CoinSpec) {
        custom.removeAll { it.symbol.equals(spec.symbol, ignoreCase = true) }
        custom.add(spec.copy(isCustom = true))
    }

    fun removeCustom(symbol: String) {
        custom.removeAll { it.symbol.equals(symbol, ignoreCase = true) && it.isCustom }
    }

    fun customCoins(): List<CoinSpec> = custom.toList()

    fun replaceCustom(specs: List<CoinSpec>) {
        custom.clear()
        custom.addAll(specs.map { it.copy(isCustom = true) })
    }
}
