package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.lock.PrismLockScope
import com.prism.launcher.wallet.WalletVault
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * This device's own key pair, and the key it shares with each trusted device.
 *
 * ## Why not the recovery phrase itself
 *
 * Everything between trusted devices used to be sealed with keys derived from the wallet's recovery
 * phrase, which only works when both devices hold the SAME phrase. A recovery phrase is a wallet's
 * identity; making devices share one just so they could read each other's clipboard tied two
 * unrelated things together, and two devices with their own wallets -- the normal case -- were
 * "trusted" in both lists and could not open a single packet from each other.
 *
 * ## How it works now
 *
 *  - Each device makes its own P-256 key pair. The PRIVATE key is sealed at rest with a key derived
 *    from that device's own recovery phrase ([CloudVault.Purpose.DEVICE]), so it is unusable while
 *    the wallet is locked or missing, and a copy of the app's files without the wallet is useless.
 *  - Paired devices exchange PUBLIC keys. The exchange is authenticated with the pair's link key
 *    (see [PairLink]), which only the two devices that saw the pairing code have, so nothing in the
 *    middle can substitute its own key. See TrustedDevices.onKey.
 *  - For each kind of data ([Purpose]), the two devices agree a 32-byte key by ECDH between their key
 *    pairs. Both arrive at the same key; nobody else can. Messages, history, clipboard, apps, email,
 *    drives, calls, location, photos and the SMS relay are all sealed with these.
 *
 * Revoking a device is simply forgetting its public key: nothing is left that can make a key for it.
 */
object DeviceKeys {

    private const val TAG = "PrismDeviceKeys"
    private const val PREFS = "prism_device_keys"
    private const val CURVE = "secp256r1"
    private val SEAL_AAD = "prism-device-private-key-v1".toByteArray()
    private val PAIR_LABEL = "prism-device-pair-v1"
    private val ANNOUNCE_LABEL = "prism-device-key-announce-v1"
    private const val CACHE_MS = 10 * 60_000L

    /**
     * What a pair key seals. Each is a different key, so a packet of one kind can never be opened
     * -- or replayed -- as another.
     */
    enum class Purpose(val label: String) {
        SHARE("share"),
        SMS("sms"),
        MESSAGES("messages"),
        LOCATION("location"),
        CALLS("calls"),
        PHOTOS("photos"),
        DRIVES("drives"),
    }

    /** This device's key pair, as long as the wallet is unlocked. */
    class Identity(val publicKey: String, val createdAt: Long, internal val private: PrivateKey)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** Called with the new public key whenever this device makes a new key pair. */
    fun addChangeListener(listener: (String) -> Unit) { listeners += listener }

    /**
     * This device's key pair: made on first use, sealed with the wallet, and remade if the wallet
     * that sealed it is gone (a different phrase was imported). Null while there is no wallet or
     * it is locked.
     *
     * Not cheap -- it derives the wallet key -- so per-packet work goes through [pairKey], which
     * caches what it derives.
     */
    @Synchronized
    fun identity(): Identity? {
        val walletKey = CloudVault.keyFor(CloudVault.Purpose.DEVICE) ?: return null
        val prefs = PrismPlatform.host.prefs(PREFS)
        val storedPublic = prefs.getString("pub", null)
        val storedPrivate = prefs.getString("priv", null)
        if (!storedPublic.isNullOrBlank() && !storedPrivate.isNullOrBlank()) {
            val plain = runCatching {
                CloudVault.open(Base64.getDecoder().decode(storedPrivate), walletKey, SEAL_AAD)
            }.getOrNull()
            if (plain != null) {
                val private = runCatching {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(plain))
                }.getOrNull()
                if (private != null) return Identity(storedPublic, prefs.getLong("at", 0L), private)
            }
            PrismPlatform.log.warn(
                TAG,
                "This device's key was sealed with a different wallet; making a new one. " +
                    "Trusted devices learn it automatically.",
            )
        }

        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val createdAt = System.currentTimeMillis()
        val sealed = CloudVault.seal(pair.private.encoded, walletKey, SEAL_AAD)
        prefs.edit()
            .putString("pub", publicKey)
            .putString("priv", Base64.getEncoder().encodeToString(sealed))
            .putLong("at", createdAt)
            .apply()
        cache.clear()
        PrismPlatform.log.info(TAG, "Made this device's key pair")
        listeners.forEach { runCatching { it(publicKey) } }
        return Identity(publicKey, createdAt, pair.private)
    }

    /** Whether [publicKey] is a usable P-256 public key. */
    fun isValidPublic(publicKey: String): Boolean = runCatching { decodePublic(publicKey) }.isSuccess

    private fun decodePublic(publicKey: String) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))

    private class Cached(val key: ByteArray, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The key this device shares with the device whose public key is [peerPublic], for [purpose].
     *
     * Null while the wallet is locked or missing. Cached for a few minutes because calls ask for it
     * per audio frame; the cache is dropped the moment Prism locks or the wallet changes, so a lock
     * still stops everything at once.
     */
    fun pairKey(peerPublic: String, purpose: Purpose): ByteArray? {
        if (peerPublic.isBlank()) return null
        if (PrismLockScope.isLocked()) {
            cache.clear()
            return null
        }
        val revision = WalletVault.revision() ?: run {
            cache.clear()
            return null
        }
        val cacheKey = "$revision|$peerPublic|${purpose.label}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.takeIf { now - it.at < CACHE_MS }?.let { return it.key.copyOf() }

        val me = identity() ?: return null
        val derived = derive(me, peerPublic, purpose) ?: return null
        cache[cacheKey] = Cached(derived.copyOf(), now)
        return derived
    }

    private fun derive(me: Identity, peerPublic: String, purpose: Purpose): ByteArray? = runCatching {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(me.private)
            doPhase(decodePublic(peerPublic), true)
            generateSecret()
        }
        // Both public keys in a fixed order, so each side derives the same key and it is bound to
        // exactly this pair.
        val (first, second) = listOf(me.publicKey, peerPublic).sorted()
        val prk = hmac(ByteArray(32), shared)
        hmac(prk, "$PAIR_LABEL|${purpose.label}|$first|$second".toByteArray() + byteArrayOf(1))
    }.getOrNull()

    /**
     * The proof that goes with a public key when it is announced to a paired device: an HMAC under
     * the pair's [linkKey], which only the two devices that saw the pairing code have.
     */
    fun announceTag(linkKey: ByteArray, fingerprint: String, publicKey: String, createdAt: Long): ByteArray =
        hmac(linkKey, "$ANNOUNCE_LABEL|$fingerprint|$publicKey|$createdAt".toByteArray())

    fun verifyAnnounce(
        linkKey: ByteArray,
        fingerprint: String,
        publicKey: String,
        createdAt: Long,
        tag: ByteArray,
    ): Boolean = MessageDigest.isEqual(tag, announceTag(linkKey, fingerprint, publicKey, createdAt))

    /** Forgets every derived key, for tests and for a wallet being wiped. */
    fun clearCache() = cache.clear()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
