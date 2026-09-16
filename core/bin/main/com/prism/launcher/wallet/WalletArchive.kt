package com.prism.launcher.wallet

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A portable, encrypted backup of everything wallet- and mining-related.
 *
 * ## The recovery phrase is the key, which is the whole design
 *
 * There is no separate backup password to lose. The archive is encrypted with a key derived from
 * the same phrase that controls the coins, so anyone who can open the archive could already have
 * derived every key in it -- the encryption adds no new secret and removes none. That makes the
 * backup exactly as safe as the phrase and no safer, which is a property worth stating plainly:
 * AN ARCHIVE PLUS THE PHRASE IS THE WALLET. Store them apart.
 *
 * The corollary is that a lost phrase makes the archive permanently unreadable. Nothing here can
 * recover it, and no amount of the archive's contents helps -- there is no hint, no reset and no
 * recovery question by design.
 *
 * ## Format
 *
 * `magic ‖ version ‖ salt(16) ‖ iv(12) ‖ AES-256-GCM( GZIP( JSON ) )`
 *
 * GZIP BEFORE ENCRYPTING, never after: ciphertext is indistinguishable from noise and does not
 * compress, so the other order would produce a larger file and accomplish nothing.
 *
 * AES-GCM rather than CBC so that a wrong phrase, a truncated download or a tampered file all fail
 * the authentication tag and are reported as such, instead of decrypting into plausible-looking
 * garbage that gets parsed as a wallet.
 */
object WalletArchive {

    private val MAGIC = "PRISMWALLET".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * PBKDF2 rounds.
     *
     * Lower than one would choose for a human-chosen password, and deliberately so: the key here is
     * a BIP-39 phrase carrying at least 128 bits of entropy, which is not brute-forceable at any
     * iteration count. The stretching guards against a user who typed a short or non-standard
     * phrase; 200k keeps that meaningful while staying under a second on a phone.
     */
    private const val ITERATIONS = 200_000

    /** Everything the archive carries. Adding a field is backwards-compatible; removing one is not. */
    data class Payload(
        val phrase: List<String>,
        val enabledCoins: List<String>,
        val customCoins: List<CoinSpec>,
        val fiatCurrency: String,
        /** Per-coin accepted share count and the summed pool difficulty behind them. */
        val shares: Map<String, ShareRecord>,
        val miningMode: String,
        val miningDiscoveryUrl: String,
        val miningThreads: Int,
        val soloNodeUrl: String,
        val selfHostNode: Boolean,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class ShareRecord(val count: Long, val totalDifficulty: Double)

    sealed class Restore {
        data class Restored(val payload: Payload) : Restore()

        /** The phrase does not open this archive -- or the file has been altered since it was made. */
        data object WrongPhrase : Restore()

        data class Corrupt(val reason: String) : Restore()
    }

    // ── Writing ────────────────────────────────────────────────────────────

    fun pack(payload: Payload, phrase: List<String>): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(toJson(payload).toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(phrase, salt), "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val ciphertext = cipher.doFinal(compressed)

        return ByteArrayOutputStream().apply {
            write(MAGIC)
            write(VERSION)
            write(salt)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    // ── Reading ────────────────────────────────────────────────────────────

    fun unpack(archive: ByteArray, phrase: List<String>): Restore {
        val headerSize = MAGIC.size + 1 + SALT_BYTES + IV_BYTES
        if (archive.size <= headerSize) return Restore.Corrupt("The file is too small to be a backup.")
        if (!archive.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return Restore.Corrupt("This is not a Prism wallet backup.")
        }
        val version = archive[MAGIC.size].toInt()
        if (version > VERSION) {
            return Restore.Corrupt("This backup was written by a newer version of Prism (v$version).")
        }

        var offset = MAGIC.size + 1
        val salt = archive.copyOfRange(offset, offset + SALT_BYTES); offset += SALT_BYTES
        val iv = archive.copyOfRange(offset, offset + IV_BYTES); offset += IV_BYTES
        val ciphertext = archive.copyOfRange(offset, archive.size)

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(phrase, salt), "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // GCM cannot distinguish a wrong key from a tampered file, and it should not pretend to.
            return Restore.WrongPhrase
        }

        return try {
            val json = GZIPInputStream(plain.inputStream()).bufferedReader().readText()
            Restore.Restored(fromJson(JSONObject(json)))
        } catch (e: Exception) {
            Restore.Corrupt("The backup decrypted but its contents could not be read: ${e.message}")
        }
    }

    /** PBKDF2-HMAC-SHA256 over the normalised phrase. */
    private fun deriveKey(phrase: List<String>, salt: ByteArray): ByteArray {
        val normalized = Bip39.normalizeWords(phrase).joinToString(" ")
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(
            PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(normalized.toCharArray()),
            salt,
            ITERATIONS,
        )
        return (generator.generateDerivedParameters(256) as KeyParameter).key
    }

    // ── Serialisation ──────────────────────────────────────────────────────

    private fun toJson(p: Payload): String {
        val custom = JSONArray()
        for (c in p.customCoins) {
            val o = JSONObject()
                .put("symbol", c.symbol).put("name", c.name)
                .put("coinType", c.coinType).put("decimals", c.decimals)
                .put("chainId", c.chainId).put("supportsPruning", c.supportsPruning)
            when (val s = c.scheme) {
                is AddressScheme.SegWitV0 -> o.put("scheme", "segwit").put("hrp", s.hrp)
                is AddressScheme.Ethereum -> o.put("scheme", "evm")
                is AddressScheme.MoneroEd25519 -> o.put("scheme", "monero")
                is AddressScheme.P2PKH -> o.put("scheme", "p2pkh").put("version", s.version)
            }
            custom.put(o)
        }

        val shares = JSONObject()
        for ((symbol, record) in p.shares) {
            shares.put(
                symbol,
                JSONObject().put("count", record.count).put("difficulty", record.totalDifficulty)
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("createdAt", p.createdAt)
            .put("phrase", p.phrase.joinToString(" "))
            .put("fiat", p.fiatCurrency)
            .put("enabledCoins", JSONArray(p.enabledCoins))
            .put("customCoins", custom)
            .put("shares", shares)
            .put(
                "mining",
                JSONObject()
                    .put("mode", p.miningMode)
                    .put("discoveryUrl", p.miningDiscoveryUrl)
                    .put("threads", p.miningThreads)
                    .put("soloNodeUrl", p.soloNodeUrl)
                    .put("selfHost", p.selfHostNode)
            )
            .toString()
    }

    private fun fromJson(o: JSONObject): Payload {
        val enabled = o.optJSONArray("enabledCoins") ?: JSONArray()
        val customArray = o.optJSONArray("customCoins") ?: JSONArray()
        val sharesObject = o.optJSONObject("shares") ?: JSONObject()
        val mining = o.optJSONObject("mining") ?: JSONObject()

        val custom = (0 until customArray.length()).map { i ->
            val c = customArray.getJSONObject(i)
            CoinSpec(
                symbol = c.getString("symbol"),
                name = c.optString("name", c.getString("symbol")),
                coinType = c.optLong("coinType", 0L),
                scheme = when (c.optString("scheme", "p2pkh")) {
                    "segwit" -> AddressScheme.SegWitV0(c.optString("hrp", "bc"))
                    "evm" -> AddressScheme.Ethereum
                    "monero" -> AddressScheme.MoneroEd25519
                    else -> AddressScheme.P2PKH(c.optInt("version", 0))
                },
                decimals = c.optInt("decimals", 8),
                chainId = c.optLong("chainId", 0L),
                isCustom = true,
                supportsPruning = c.optBoolean("supportsPruning", false),
            )
        }

        val shares = HashMap<String, ShareRecord>()
        val keys = sharesObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = sharesObject.optJSONObject(key) ?: continue
            shares[key] = ShareRecord(entry.optLong("count", 0L), entry.optDouble("difficulty", 0.0))
        }

        return Payload(
            phrase = Bip39.splitPhrase(o.optString("phrase", "")),
            enabledCoins = (0 until enabled.length()).map { enabled.getString(it) },
            customCoins = custom,
            fiatCurrency = o.optString("fiat", "USD"),
            shares = shares,
            miningMode = mining.optString("mode", "pool"),
            miningDiscoveryUrl = mining.optString("discoveryUrl", ""),
            miningThreads = mining.optInt("threads", 0),
            soloNodeUrl = mining.optString("soloNodeUrl", ""),
            selfHostNode = mining.optBoolean("selfHost", false),
            createdAt = o.optLong("createdAt", 0L),
        )
    }

    /**
     * Combines an imported archive with what is already on the device.
     *
     * COIN LISTS UNION AND SHARE COUNTS TAKE THE MAXIMUM. Summing shares would double-count every
     * re-import of the same archive, which is the likeliest way this gets used -- restore, change
     * something, restore again.
     *
     * The PHRASE DOES NOT MERGE, because it cannot: one phrase derives every key, so two different
     * phrases are two different wallets and the archive's must win outright. Callers are expected
     * to warn first when [existing] holds a different phrase -- the coins under the old one become
     * unreachable without its own backup.
     */
    fun merge(imported: Payload, existing: Payload?): Payload {
        if (existing == null) return imported

        val shares = HashMap<String, ShareRecord>(existing.shares)
        for ((symbol, record) in imported.shares) {
            val current = shares[symbol]
            shares[symbol] = if (current == null) record else ShareRecord(
                maxOf(current.count, record.count),
                maxOf(current.totalDifficulty, record.totalDifficulty),
            )
        }

        val customBySymbol = LinkedHashMap<String, CoinSpec>()
        for (c in existing.customCoins) customBySymbol[c.symbol.uppercase()] = c
        for (c in imported.customCoins) customBySymbol[c.symbol.uppercase()] = c

        return imported.copy(
            enabledCoins = (existing.enabledCoins + imported.enabledCoins)
                .map { it.uppercase() }.distinct(),
            customCoins = customBySymbol.values.toList(),
            shares = shares,
        )
    }
}
