package com.prism.launcher.trusted

import com.prism.launcher.cloud.CloudVault
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The short code one device shows and the other types, and the key it becomes.
 *
 * ## Why a code at all, when the offer already has to be accepted
 *
 * Accepting an offer proves somebody is at the receiving device. It does not prove the OFFER came from
 * the device in front of them. Anything on the network can send a trust offer naming itself "Kitchen
 * Laptop", and a user who was expecting one would accept it. The code closes that: it is generated on the
 * offering device, displayed there, and never travels — so answering correctly proves the person accepting
 * can SEE the screen of the device that offered.
 *
 * ## Why the code is also the encryption key
 *
 * Because of a bootstrapping problem that has no other clean answer. Everything Prism shares between
 * devices is sealed with a key derived from the wallet phrase — which works only once both devices HOLD
 * that phrase. Sending the phrase itself over that channel is circular: it would be encrypted with the key
 * it exists to establish.
 *
 * So the phrase travels sealed with a key derived from the code instead. The receiving device types six
 * characters that were never on the network, derives the same key, and opens it. After that both devices
 * share the wallet and everything else works normally.
 *
 * ## What that costs, said plainly
 *
 * Six characters from the 94 printable ASCII ones is about 39 bits. THAT IS NOT A PASSWORD, and it is not
 * pretending to be one. What makes it adequate here is the situation rather than the length:
 *
 *   * The sealed phrase is one datagram on a local network, sent once, in a window of seconds. An attacker
 *     has to be on the mesh, at that moment, and capture it.
 *   * [ITERATIONS] rounds of PBKDF2 put a real cost on each guess — roughly a tenth of a second — so even
 *     a captured packet is not a trivial offline break.
 *   * The code is used once and discarded. There is no second chance to try against a later packet.
 *
 * Where that is not enough, the length is a setting: [PrismSettings.getPairingCodeLength] goes up, and
 * each extra character multiplies the work by 94. The default is six because it has to be read off one
 * screen and typed on another without mistakes, and a code nobody will type correctly gets worked around
 * rather than used.
 *
 * ## Why the code is never sent, even hashed
 *
 * The receiver proves it knows the code by sealing a known phrase with the derived key. The sender opens
 * it, or does not. A hash of the code on the wire would be the same offline-guessable material as the code
 * with none of the timing constraints above — and would be capturable by anything on the network, not
 * only by something present at the moment of pairing.
 */
object PairingCode {

    /**
     * The alphabet. Upper case, lower case, digits and symbols, as specified.
     *
     * WITH THE AMBIGUOUS ONES LEFT IN, deliberately. Dropping O/0 and l/1 would make a code easier to
     * read at the cost of narrowing an already small space, and this one is read off a screen a few feet
     * away rather than from a label on a router.
     */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*?+-=<>"

    /**
     * PBKDF2 rounds.
     *
     * High on purpose: this is the only thing standing between a captured packet and a 39-bit secret. A
     * tenth of a second per guess is unnoticeable to the user typing once and expensive to anybody trying
     * millions.
     */
    private const val ITERATIONS = 200_000

    private const val KEY_BITS = 256

    /** Salt length. Sent in the clear with the offer, as a salt is meant to be. */
    private const val SALT_BYTES = 16

    /** What the receiver seals to prove it knows the code. Fixed, and never secret. */
    private const val PROOF_PLAINTEXT = "prism-pairing-proof"

    private val random = SecureRandom()

    /** A fresh code of [length] characters. */
    fun generate(length: Int): String {
        val size = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val builder = StringBuilder(size)
        repeat(size) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return builder.toString()
    }

    /** A fresh salt, sent with the offer so both sides derive the same key. */
    fun salt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /**
     * The key a code and salt derive to.
     *
     * Slow by design — see [ITERATIONS]. Call it off a UI thread; on a phone this is a visible pause.
     */
    fun keyFor(code: String, salt: ByteArray): ByteArray? = runCatching {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded
    }.getOrNull()

    /**
     * What the receiver sends to show it knows the code.
     *
     * The proof is bound to the salt through AES-GCM's associated data, so a proof captured from one
     * pairing cannot be replayed into another — a fresh salt makes it fail to open.
     */
    fun proofFor(key: ByteArray, salt: ByteArray): ByteArray =
        CloudVault.seal(PROOF_PLAINTEXT.toByteArray(), key, salt)

    /** Whether a proof was made with the same code. */
    fun verify(proof: ByteArray, key: ByteArray, salt: ByteArray): Boolean =
        CloudVault.open(proof, key, salt)?.toString(Charsets.UTF_8) == PROOF_PLAINTEXT

    /** Seals the wallet phrase for the one device that answered correctly. */
    fun sealPhrase(phrase: String, key: ByteArray, salt: ByteArray): ByteArray =
        CloudVault.seal(phrase.toByteArray(), key, salt)

    fun openPhrase(sealed: ByteArray, key: ByteArray, salt: ByteArray): String? =
        CloudVault.open(sealed, key, salt)?.toString(Charsets.UTF_8)

    /** Shortest a code may be set to. Below this the arithmetic stops meaning anything. */
    const val MIN_LENGTH = 4

    /** Longest. Past this nobody types it correctly, and they write it down instead. */
    const val MAX_LENGTH = 32

    /** Roughly how many bits a code of this length is worth, for a settings screen to show. */
    fun strengthBits(length: Int): Int =
        (length.coerceIn(MIN_LENGTH, MAX_LENGTH) * (Math.log(ALPHABET.length.toDouble()) / Math.log(2.0)))
            .toInt()
}
