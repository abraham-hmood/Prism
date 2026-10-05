package com.prism.launcher.cloud

import com.prism.launcher.wallet.WalletVault
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key that everything in the mesh cloud is encrypted with, derived from the wallet's seed.
 *
 * ## Why the recovery phrase, and not a password
 *
 * Because the alternative is losing the data. Mesh cloud storage puts a user's files on other people's
 * phones; the only thing standing between those files and the people holding them is this key. A key
 * stored only on this device dies with the device, and the files — which are still out there, still
 * paid for — become permanently unreadable. A key the user has to invent and remember is a key that
 * gets forgotten, and a weak one gets brute-forced offline by whoever is holding the ciphertext.
 *
 * The BIP-39 recovery phrase is the one secret the user has already been forced to write down and keep,
 * because it is the only way back to their coins. Deriving from it means the phrase that restores the
 * wallet also restores the cloud, on any device, with nothing else needed.
 *
 * ## Domain separation is not optional here
 *
 * The cloud key is HKDF-derived from the seed with its own label, NOT the seed and not any key on the
 * wallet's derivation path. If the same bytes did both jobs, then handing a peer a file encrypted with
 * it would be handing them an oracle against the thing that holds the money — and worse, any future
 * bug that leaked a cloud key would be a bug that drained the wallet. They are separated so that the
 * worst case of a cloud compromise is a compromise of the cloud.
 *
 * Each purpose then gets its own subkey, again by label: storage, compute and gaming derive different
 * keys from the same root. A peer that is decrypting a game stream cannot use what it learns to read
 * stored files.
 *
 * ## What the peers can and cannot see
 *
 * Cannot: file contents, file names, directory structure, or how big any individual file is beyond the
 * size of the chunks they hold. All of that is inside the encryption or inside the manifest, and the
 * manifest is encrypted too.
 *
 * Can: that somebody is storing *something*, how much of it, and when it is read. Traffic analysis is
 * not solved by encryption and this does not claim to solve it. A peer holding chunk 7 of a file knows
 * that chunk 7 exists and is being fetched, and padding every chunk to a fixed size — which this does —
 * only hides the exact length, not the existence.
 */
object CloudVault {

    /**
     * The root label. Versioned, because changing the derivation later must produce a different key
     * rather than silently producing a key that cannot read what the old one wrote.
     */
    private const val ROOT_LABEL = "prism-cloud-root-v1"

    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32

    /**
     * Chunk size for stored files.
     *
     * A megabyte. Small enough that one chunk fits comfortably in a phone's memory and can be re-sent
     * cheaply when a peer drops, large enough that the per-chunk overhead — 12 bytes of nonce, 16 of
     * tag, one round trip — is noise. IPFS's default is 256 KB, which is tuned for a network of servers
     * with fast links; on a phone mesh the round trips hurt more than the bytes.
     */
    const val CHUNK_BYTES = 1 shl 20

    /** What a key is for. Different purposes get different keys from the same seed. */
    enum class Purpose(val label: String) {
        STORAGE("storage"),
        COMPUTE("compute"),
        GAMING("gaming"),
        MANIFEST("manifest"),

        /**
         * Text messages relayed from a phone to its owner's other devices. See
         * [com.prism.launcher.messaging.SmsRelay].
         *
         * Its own key, like everything else here, and the reason is sharper for this one than for most:
         * a relayed text passes through the mesh, so peers that are not the owner see the ciphertext.
         * Deriving it from the recovery phrase is what makes the relay readable on the owner's OTHER
         * devices and nowhere else -- the phrase is the shared secret, and it is already the one thing
         * the user has been made to write down.
         */
        MESSAGING("messaging"),
    }

    // ── Derivation ─────────────────────────────────────────────────────────

    /**
     * True when there is a wallet to derive from.
     *
     * Every cloud feature checks this. Not as a paywall — as a statement of fact: without a seed there
     * is no key, without a key there is nothing safe to put on somebody else's phone, and without an
     * address there is no way to pay them for holding it.
     */
    fun isReady(): Boolean = WalletVault.isInitialized() && WalletVault.seed() != null

    fun unavailableReason(): String = when {
        !WalletVault.isInitialized() ->
            "The mesh cloud needs a wallet. Your recovery phrase is what the encryption key is " +
                "derived from — without it there is no way to encrypt what goes onto other people's " +
                "devices, and no way to pay them. Create a wallet on the Wallet page."
        WalletVault.seed() == null ->
            "The wallet is locked, so the encryption key cannot be derived. Unlock it and come back."
        else -> ""
    }

    /**
     * The key for one purpose.
     *
     * Recomputed on each call rather than cached in a field. Deriving is two HMACs and takes
     * microseconds, and a 32-byte key sitting in a singleton's field for the life of the process is a
     * 32-byte key sitting in every heap dump for the life of the process.
     */
    fun keyFor(purpose: Purpose): ByteArray? {
        val seed = WalletVault.seed() ?: return null
        val root = hkdf(seed, ROOT_LABEL.toByteArray(), KEY_BYTES)
        return hkdf(root, "prism-cloud-${purpose.label}-v1".toByteArray(), KEY_BYTES)
    }

    /**
     * A public identity for this user's cloud data, safe to put on the wire.
     *
     * Derived from the root key so it is stable across devices and recoverable from the phrase, and
     * hashed so it reveals nothing about the key. It is what a peer stores chunks *under*, and it
     * deliberately is not the wallet address: an address is a payment identity that anybody can look up
     * on the chain, and using it here would link every stored chunk to a public balance.
     */
    fun namespace(): String? {
        // Cached, unlike the keys. Deriving it needs the seed, and turning a recovery phrase into a
        // seed is PBKDF2 with 2048 iterations of HMAC-SHA512 — deliberately slow, because that is what
        // BIP-39 is for. The namespace is shown in the Cloud page's header, which re-renders on every
        // tab change, so deriving it each time put a few milliseconds of key stretching on the main
        // thread for a string that cannot change while the wallet does not.
        //
        // Safe to cache where a key would not be: this is a public identifier, hashed, and it is
        // already sent to peers. The cache is keyed on a fingerprint of the phrase, so importing or
        // wiping a wallet invalidates it — and computing that fingerprint costs one AES decrypt, not
        // a key derivation.
        val words = WalletVault.phrase() ?: return null
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(words.joinToString(" ").toByteArray())
            .joinToString("") { "%02x".format(it) }

        cachedNamespace?.let { (forPhrase, value) -> if (forPhrase == fingerprint) return value }

        val seed = WalletVault.seed() ?: return null
        val root = hkdf(seed, ROOT_LABEL.toByteArray(), KEY_BYTES)
        val id = hkdf(root, "prism-cloud-namespace-v1".toByteArray(), 16)
        val value = id.joinToString("") { "%02x".format(it) }
        cachedNamespace = fingerprint to value
        return value
    }

    @Volatile
    private var cachedNamespace: Pair<String, String>? = null

    /**
     * HKDF-SHA256, extract and expand.
     *
     * HKDF rather than a plain hash of seed-plus-label. A hash concatenation is vulnerable to length
     * extension and, more practically, has no defined behaviour when the input is not uniformly random
     * — and while a BIP-39 seed is close to uniform, the whole point of an extract step is not having
     * to argue about that. This is RFC 5869 with an empty salt, which is the standard choice when the
     * input keying material is already a seed.
     */
    private fun hkdf(input: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        }
        val prk = extract.doFinal(input)

        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            expand.reset()
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val take = minOf(previous.size, length - produced)
            previous.copyInto(out, produced, 0, take)
            produced += take
            counter++
        }
        return out
    }

    // ── Encryption ─────────────────────────────────────────────────────────

    /**
     * Encrypts one chunk: `nonce(12) ‖ ciphertext ‖ tag(16)`.
     *
     * A fresh random nonce per chunk, never a counter. A counter would be smaller and would also mean
     * that re-encrypting a modified file with the same key reused nonces, and a repeated nonce under
     * AES-GCM does not degrade gracefully — it leaks the XOR of the two plaintexts and, worse, allows
     * the authentication key to be recovered. 96 random bits per megabyte chunk is nowhere near the
     * birthday bound for any amount of data a phone will store.
     *
     * [associatedData] binds the chunk to its position. Without it, a peer holding chunks 3 and 7 could
     * swap them and both would still authenticate — each is a valid chunk under the right key, just not
     * in that place. Passing the index as AAD makes chunk 3 fail to decrypt as chunk 7.
     */
    fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        associatedData?.let { cipher.updateAAD(it) }
        val body = cipher.doFinal(plaintext)
        return iv + body
    }

    /** Returns null on any failure, which under GCM covers a wrong key and a tampered chunk alike. */
    fun open(sealed: ByteArray, key: ByteArray, associatedData: ByteArray? = null): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)),
            )
            associatedData?.let { cipher.updateAAD(it) }
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()
    }

    fun sealText(plaintext: String, purpose: Purpose): ByteArray? {
        val key = keyFor(purpose) ?: return null
        return seal(plaintext.toByteArray(), key)
    }

    fun openText(sealed: ByteArray, purpose: Purpose): String? {
        val key = keyFor(purpose) ?: return null
        return open(sealed, key)?.let { String(it) }
    }

    // ── Files ──────────────────────────────────────────────────────────────

    /**
     * One encrypted piece of a file, as it will sit on a peer.
     *
     * [id] is the hash of the CIPHERTEXT, not of the plaintext. That is a deliberate departure from
     * IPFS, where the address IS the plaintext hash, and the reason is that plaintext addressing is a
     * confirmation oracle: a peer holding chunk `Qm…` can test any file it suspects you have by
     * hashing it and comparing. Addressing the ciphertext gives up cross-user deduplication — two
     * people storing the same film store it twice — and buys the property that nobody can tell what
     * you are storing by looking at the address.
     */
    data class Chunk(val id: String, val index: Int, val sealed: ByteArray) {
        val bytes: Int get() = sealed.size

        override fun equals(other: Any?): Boolean = other is Chunk && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    /**
     * Splits and encrypts a file into chunks.
     *
     * Streamed a chunk at a time: the files this is for are the ones too big to want on one device, so
     * reading one into memory to encrypt it would fail on exactly the cases that matter.
     */
    fun sealFile(source: File, key: ByteArray, onChunk: (Chunk) -> Boolean): Int {
        var index = 0
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = readFully(input, buffer)
                if (read <= 0) break
                val plain = if (read == buffer.size) buffer else buffer.copyOfRange(0, read)
                val sealed = seal(plain, key, aadFor(index))
                if (!onChunk(Chunk(hashOf(sealed), index, sealed))) break
                index++
                if (read < buffer.size) break
            }
        }
        return index
    }

    /**
     * Writes chunks back out in order.
     *
     * Returns false as soon as one chunk fails rather than writing a file with a hole in it. A
     * partially-recovered file that looks complete is worse than a failure, because the user will
     * keep it and the missing megabyte will surface later as a corrupt video or a truncated archive.
     */
    fun openFile(chunks: List<Chunk>, key: ByteArray, destination: OutputStream): Boolean {
        chunks.sortedBy { it.index }.forEachIndexed { position, chunk ->
            if (chunk.index != position) return false
            val plain = open(chunk.sealed, key, aadFor(chunk.index)) ?: return false
            destination.write(plain)
        }
        destination.flush()
        return true
    }

    /** The chunk's index, bound into its authentication so chunks cannot be reordered. */
    private fun aadFor(index: Int): ByteArray = "chunk:$index".toByteArray()

    fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Fills the buffer or reaches the end.
     *
     * `InputStream.read` is allowed to return fewer bytes than asked for at any time, and a chunker
     * that trusted one read would produce short chunks at arbitrary boundaries — which still decrypt,
     * so the file would reassemble in the wrong order and nothing would report an error.
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }
}
