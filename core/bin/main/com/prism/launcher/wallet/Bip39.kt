package com.prism.launcher.wallet

import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.text.Normalizer

/**
 * BIP-39 recovery phrases.
 *
 * ## The 30-word phrases are an EXTENSION, not standard BIP-39, and it matters
 *
 * BIP-39 defines word counts of 12, 15, 18, 21 and 24 -- 128 to 256 bits of entropy. Its formula
 * (checksum = entropy/32 bits, 11 bits per word) generalises cleanly past that, and 30 words is
 * exactly 320 bits of entropy with a 10-bit checksum, so a phrase this wallet generates is
 * internally consistent and re-imports here perfectly.
 *
 * IT WILL NOT RESTORE IN ANOTHER WALLET. Electrum, MetaMask, Ledger, Trezor and effectively every
 * other implementation hard-reject anything longer than 24 words, because the standard stops
 * there. A 30-word phrase therefore binds those coins to Prism: if this app is lost and no other
 * software accepts the phrase, the only recovery path is the per-wallet private key export.
 *
 * That is a deliberate choice made by whoever sets the word count -- 30 is the floor for a NEW
 * phrase here, as specified. It is not a trade-off the code can make quietly, which is why
 * [isStandardWordCount] exists and the UI is expected to say so at generation time.
 *
 * IMPORT IS DELIBERATELY MORE PERMISSIVE than generation, accepting any valid length from 12
 * words up. Refusing a standard 24-word phrase would mean a user could not bring an existing
 * wallet in, which is most of the point of an import button.
 *
 * ## Entropy
 *
 * [SecureRandom] with no seeding of our own. Seeding it "for extra randomness" is the classic way
 * to make it worse: the platform instance is already seeded from the OS entropy pool, and mixing
 * in something guessable (time, device id) can only reduce the search space.
 */
object Bip39 {

    /** The smallest phrase this wallet will GENERATE. Import accepts fewer -- see the class note. */
    const val MIN_NEW_WALLET_WORDS = 30

    /** Above this, no other wallet software will accept the phrase. */
    const val MAX_STANDARD_WORDS = 24

    const val MIN_IMPORT_WORDS = 12
    const val MAX_WORDS = 60

    private const val WORDLIST_RESOURCE = "/bip39-english.txt"

    /**
     * The official English wordlist, loaded once.
     *
     * Shipped as a resource rather than a generated Kotlin file: it is a fixed, externally
     * specified artifact, and a 2048-entry string array in source is a file nobody can review
     * against the spec by reading it.
     */
    val words: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream(WORDLIST_RESOURCE)
            ?: error("BIP-39 wordlist missing from the build ($WORDLIST_RESOURCE)")
        val loaded = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        check(loaded.size == 2048) { "BIP-39 wordlist must hold exactly 2048 words, found ${loaded.size}" }
        loaded
    }

    private val wordIndex: Map<String, Int> by lazy {
        words.withIndex().associate { (i, w) -> w to i }
    }

    /** Word counts that satisfy BIP-39's arithmetic: multiples of 3 in the supported range. */
    fun supportedWordCounts(min: Int = MIN_IMPORT_WORDS, max: Int = MAX_WORDS): List<Int> =
        (min..max).filter { it % 3 == 0 }

    fun isStandardWordCount(count: Int): Boolean = count in 12..MAX_STANDARD_WORDS && count % 3 == 0

    /**
     * Entropy bits for a word count, or null if the count does not divide cleanly.
     *
     * From BIP-39: words * 11 = ENT + ENT/32, so ENT = words * 32 / 3. Any count that is not a
     * multiple of 3 leaves a fractional checksum and cannot be encoded at all.
     */
    fun entropyBitsFor(wordCount: Int): Int? {
        if (wordCount < 3 || wordCount % 3 != 0) return null
        return wordCount * 32 / 3
    }

    fun generate(wordCount: Int, random: SecureRandom = SecureRandom()): List<String> {
        val bits = entropyBitsFor(wordCount)
            ?: throw IllegalArgumentException("$wordCount words cannot encode a BIP-39 phrase")
        require(wordCount >= MIN_IMPORT_WORDS) { "phrases shorter than $MIN_IMPORT_WORDS words are not safe" }
        val entropy = ByteArray(bits / 8)
        random.nextBytes(entropy)
        return fromEntropy(entropy)
    }

    /**
     * Encodes entropy as a phrase: entropy bits, then ENT/32 checksum bits from its SHA-256,
     * split into 11-bit words.
     */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size % 4 == 0 && entropy.isNotEmpty()) {
            "entropy must be a non-zero multiple of 4 bytes, got ${entropy.size}"
        }
        val checksumBits = entropy.size * 8 / 32
        val hash = WalletCrypto.sha256(entropy)

        val bits = StringBuilder(entropy.size * 8 + checksumBits)
        for (b in entropy) bits.append(bitsOf(b))
        bits.append(bitsOf(hash[0]).substring(0, minOf(checksumBits, 8)))
        // Checksums past 8 bits (phrases of 27 words and up) spill into the second hash byte.
        var remaining = checksumBits - 8
        var hashIndex = 1
        while (remaining > 0) {
            bits.append(bitsOf(hash[hashIndex]).substring(0, minOf(remaining, 8)))
            remaining -= 8
            hashIndex++
        }

        return (0 until bits.length / 11).map { i ->
            words[bits.substring(i * 11, i * 11 + 11).toInt(2)]
        }
    }

    private fun bitsOf(b: Byte): String =
        Integer.toBinaryString((b.toInt() and 0xff) or 0x100).substring(1)

    sealed class Validation {
        data object Valid : Validation()
        data class Invalid(val reason: String) : Validation()
    }

    /**
     * Checks a phrase the user typed.
     *
     * The checksum is the point of this: without it, a mistyped word that happens to be in the
     * list silently produces a DIFFERENT, empty wallet rather than an error, and the user concludes
     * their coins are gone. Reporting the unknown word by name matters for the same reason -- the
     * common failure is one wrong word out of thirty.
     */
    fun validate(mnemonic: List<String>): Validation {
        val clean = normalizeWords(mnemonic)
        if (clean.size < MIN_IMPORT_WORDS) {
            return Validation.Invalid("A phrase needs at least $MIN_IMPORT_WORDS words; this has ${clean.size}.")
        }
        if (clean.size % 3 != 0) {
            return Validation.Invalid("A phrase must be a multiple of 3 words; this has ${clean.size}.")
        }
        if (clean.size > MAX_WORDS) {
            return Validation.Invalid("Phrases longer than $MAX_WORDS words are not supported.")
        }
        val unknown = clean.filter { it !in wordIndex }
        if (unknown.isNotEmpty()) {
            return Validation.Invalid(
                "Not in the BIP-39 word list: " + unknown.distinct().take(4).joinToString(", ")
            )
        }

        val bits = StringBuilder(clean.size * 11)
        for (w in clean) {
            bits.append(Integer.toBinaryString(wordIndex.getValue(w) or 0x800).substring(1))
        }
        val entropyBits = clean.size * 32 / 3
        val checksumBits = bits.length - entropyBits
        val entropy = ByteArray(entropyBits / 8) {
            bits.substring(it * 8, it * 8 + 8).toInt(2).toByte()
        }
        val expected = fromEntropy(entropy)
        return if (expected == clean) Validation.Valid
        else Validation.Invalid(
            "Checksum failed ($checksumBits-bit) -- one of the words is wrong or out of order."
        )
    }

    /**
     * The 64-byte master seed: PBKDF2-HMAC-SHA512 over the phrase, 2048 rounds, salted with
     * "mnemonic" plus the optional passphrase.
     *
     * NOTE THE PASSPHRASE IS NOT A PASSWORD in the usual sense -- there is no "wrong passphrase"
     * error, because every passphrase produces a valid, different wallet. A typo silently opens an
     * empty one.
     */
    fun toSeed(mnemonic: List<String>, passphrase: String = ""): ByteArray {
        val phrase = normalizeWords(mnemonic).joinToString(" ")
        val salt = "mnemonic" + Normalizer.normalize(passphrase, Normalizer.Form.NFKD)

        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(
            PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(phrase.toCharArray()),
            salt.toByteArray(Charsets.UTF_8),
            2048
        )
        return (generator.generateDerivedParameters(512) as KeyParameter).key
    }

    /** Lower-cases, NFKD-normalises and collapses whitespace, so paste artefacts do not fail. */
    fun normalizeWords(mnemonic: List<String>): List<String> =
        mnemonic.flatMap { it.split(Regex("\\s+")) }
            .map { Normalizer.normalize(it.trim().lowercase(), Normalizer.Form.NFKD) }
            .filter { it.isNotEmpty() }

    fun splitPhrase(raw: String): List<String> = normalizeWords(listOf(raw))
}
