package com.prism.core.wg

import java.math.BigInteger
import java.security.SecureRandom

/**
 * The WireGuard handshake and transport crypto, without a dependency.
 *
 * ## Why this exists
 *
 * Prism's Android "WireGuard server" answered a handshake initiation with ninety-two near-zero
 * bytes and then injected any datagram carrying the transport type byte straight into the VPN. Two
 * things followed: no real WireGuard client could connect, and any device that could reach UDP
 * 51820 could put arbitrary IP packets inside the tunnel without a key. It looked like a server and
 * was an open door.
 *
 * A real one needs Noise_IKpsk2 over Curve25519 with ChaCha20-Poly1305 and BLAKE2s. Those are all
 * small, exactly-specified primitives, so they are implemented here rather than pulling in a native
 * library: :core also runs on the desktop JVM, where no WireGuard binding is installed, and the
 * crypto has to be testable without a device. Nothing here touches Android.
 *
 * ## What is here, and what is deliberately not
 *
 * Present: BLAKE2s, HMAC-BLAKE2s, the WireGuard KDF, X25519, ChaCha20-Poly1305, TAI64N, the
 * initiation and response messages, MAC1, transport keys and framing.
 *
 * Absent:
 *  - COOKIE replies (type 3). They defend against source-address spoofing under flood. Without
 *    them the protocol is still correct; MAC1 is checked first, which already drops every packet
 *    that does not know this server's public key.
 *  - A preshared key. The KDF is fed the empty key, which is exactly what "no PSK" means.
 *  - Rekey timers and keepalive scheduling. [Session] exposes its age and counter so the caller
 *    decides.
 *
 * ## Verification
 *
 * `WireGuardNoiseTest` checks BLAKE2s, X25519 and ChaCha20 against published vectors, runs a whole
 * client handshake against this server implementation, and passes transport packets both ways.
 * That does not prove interoperability with wireguard-go -- only a device can -- but it does prove
 * both ends derive the same keys from the same bytes, which is where hand-rolled Noise goes wrong.
 */
object WireGuardNoise {

    private const val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
    private const val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com"
    private const val LABEL_MAC1 = "mac1----"

    const val INITIATION_BYTES = 148
    const val RESPONSE_BYTES = 92
    const val TRANSPORT_HEADER_BYTES = 16

    /** 16-byte header plus the 16-byte Poly1305 tag. */
    const val TRANSPORT_OVERHEAD = 32

    const val TYPE_INITIATION: Byte = 1
    const val TYPE_RESPONSE: Byte = 2
    const val TYPE_COOKIE: Byte = 3
    const val TYPE_TRANSPORT: Byte = 4

    /** A handshake older than this is refused; WireGuard rekeys well before it. */
    private const val REJECT_AFTER_TIME_MS = 180_000L

    /** After this many packets a session must be replaced rather than keep counting. */
    private const val REKEY_AFTER_MESSAGES = 1L shl 60

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    // ── Keys ───────────────────────────────────────────────────────────────

    fun generatePrivateKey(): ByteArray = X25519.clamp(randomBytes(32))

    fun publicKey(privateKey: ByteArray): ByteArray =
        X25519.scalarMult(privateKey, X25519.BASE_POINT)

    fun encodeKey(key: ByteArray): String = java.util.Base64.getEncoder().encodeToString(key)

    fun decodeKey(text: String): ByteArray? = runCatching {
        java.util.Base64.getDecoder().decode(text.trim())
    }.getOrNull()?.takeIf { it.size == 32 }

    // ── Handshake ──────────────────────────────────────────────────────────

    /**
     * State between an accepted initiation and the response built from it.
     *
     * Not a [Session]: there is no transport key until the response exists, and the response also
     * carries the indices. Keeping them apart means a half-finished handshake can never be mistaken
     * for a usable pair.
     */
    class Handshake internal constructor(
        internal val chainingKey: ByteArray,
        internal val hash: ByteArray,
        internal val initiatorEphemeral: ByteArray,
        val remoteStatic: ByteArray,
        val senderIndex: Int,
    )

    /** A built response and the session that goes with it. */
    class Accepted(val response: ByteArray, val session: Session, val remoteStatic: ByteArray)

    /**
     * Processes an initiation through to the response.
     *
     * Returns null for anything that is not a well-formed initiation for this key: wrong type,
     * wrong length, a zero sender index, a bad MAC1, or a static key / timestamp that fails to
     * authenticate. The caller logs and drops, which is what a public UDP port should do with junk.
     */
    fun accept(message: ByteArray, privateKey: ByteArray, publicKey: ByteArray): Accepted? {
        if (message.size != INITIATION_BYTES) return null
        if (message[0] != TYPE_INITIATION) return null
        val senderIndex = le32(message, 4)
        if (senderIndex == 0) return null
        if (!mac1Valid(message, 0, INITIATION_BYTES - 32, publicKey)) return null

        val ephemeral = message.copyOfRange(8, 40)
        val encStatic = message.copyOfRange(40, 88)
        val encTimestamp = message.copyOfRange(88, 116)

        var hash = initialHash(publicKey)
        var ck = constructionHash()

        hash = hashOf(hash, ephemeral)
        ck = kdf1(ck, ephemeral)

        // es: the initiator's ephemeral against this server's static key.
        var pair = kdf2(ck, X25519.scalarMult(privateKey, ephemeral))
        ck = pair.first
        val remoteStatic = aeadOpen(pair.second, encStatic, hash) ?: return null
        hash = hashOf(hash, encStatic)

        // ss: the initiator's static key against this server's static key.
        pair = kdf2(ck, X25519.scalarMult(privateKey, remoteStatic))
        ck = pair.first
        val timestamp = aeadOpen(pair.second, encTimestamp, hash) ?: return null
        if (timestamp.size != 12) return null
        hash = hashOf(hash, encTimestamp)

        return buildResponse(
            Handshake(ck, hash, ephemeral, remoteStatic, senderIndex),
            publicKey,
        )
    }

    /** Builds the response for an initiation [accept] has already authenticated. */
    private fun buildResponse(handshake: Handshake, publicKey: ByteArray): Accepted {
        val ephemeralPrivate = generatePrivateKey()
        val ephemeralPublic = publicKey(ephemeralPrivate)
        val myIndex = randomIndex()

        var hash = hashOf(handshake.hash, ephemeralPublic)
        var ck = kdf1(handshake.chainingKey, ephemeralPublic)

        // ee, then se. The initiator's static key is already mixed in by the initiation's second
        // DH, so these are the two that remain in the IK pattern.
        ck = kdf1(ck, X25519.scalarMult(ephemeralPrivate, handshake.initiatorEphemeral))
        ck = kdf1(ck, X25519.scalarMult(ephemeralPrivate, handshake.remoteStatic))

        val pair = kdf2(ck, ByteArray(0))
        hash = hashOf(hash, pair.second)

        // The empty payload proves the responder holds the keys it is about to use.
        val encryptedEmpty = aeadSeal(pair.first, ByteArray(0), hash)
        hash = hashOf(hash, encryptedEmpty)

        val (sendKey, receiveKey) = kdf2(pair.first, ByteArray(0))

        val response = ByteArray(RESPONSE_BYTES)
        response[0] = TYPE_RESPONSE
        writeLe32(response, 4, myIndex)
        writeLe32(response, 8, handshake.senderIndex)
        ephemeralPublic.copyInto(response, 12)
        encryptedEmpty.copyInto(response, 44)
        mac1(response, RESPONSE_BYTES - 32, publicKey).copyInto(response, RESPONSE_BYTES - 32, 0, 16)

        return Accepted(
            response = response,
            session = Session(myIndex, handshake.senderIndex, sendKey, receiveKey),
            remoteStatic = handshake.remoteStatic,
        )
    }

    /**
     * The client's half of a handshake, kept between [initiate] and [complete].
     *
     * A class rather than a tuple because every field is a key or a chaining value derived from
     * one, and passing them positionally into [complete] is how the wrong one gets used.
     */
    class Pending internal constructor(
        val message: ByteArray,
        internal val chainingKey: ByteArray,
        internal val hash: ByteArray,
        internal val ephemeralPrivate: ByteArray,
        internal val ephemeralPublic: ByteArray,
        /** Kept because the response mixes a DH against it; see [complete]. */
        internal val staticPrivate: ByteArray,
        val localIndex: Int,
    )

    /** Builds a handshake initiation, as a client does. Used by tests and the desktop self-check. */
    fun initiate(
        initiatorPrivate: ByteArray,
        responderPublic: ByteArray,
        timestamp: ByteArray = tai64n(),
    ): Pending {
        val initiatorPublic = publicKey(initiatorPrivate)
        val ephemeralPrivate = generatePrivateKey()
        val ephemeralPublic = publicKey(ephemeralPrivate)
        val myIndex = randomIndex()

        var hash = initialHash(responderPublic)
        var ck = constructionHash()

        hash = hashOf(hash, ephemeralPublic)
        ck = kdf1(ck, ephemeralPublic)

        var pair = kdf2(ck, X25519.scalarMult(ephemeralPrivate, responderPublic))
        ck = pair.first
        val encryptedStatic = aeadSeal(pair.second, initiatorPublic, hash)
        hash = hashOf(hash, encryptedStatic)

        pair = kdf2(ck, X25519.scalarMult(initiatorPrivate, responderPublic))
        ck = pair.first
        val encryptedTimestamp = aeadSeal(pair.second, timestamp, hash)
        hash = hashOf(hash, encryptedTimestamp)

        val message = ByteArray(INITIATION_BYTES)
        message[0] = TYPE_INITIATION
        writeLe32(message, 4, myIndex)
        ephemeralPublic.copyInto(message, 8)
        encryptedStatic.copyInto(message, 40)
        encryptedTimestamp.copyInto(message, 88)
        mac1(message, INITIATION_BYTES - 32, responderPublic).copyInto(message, INITIATION_BYTES - 32, 0, 16)

        return Pending(message, ck, hash, ephemeralPrivate, ephemeralPublic, initiatorPrivate, myIndex)
    }

    /** Finishes the client's side from the responder's message. */
    fun complete(pending: Pending, response: ByteArray): Session? {
        if (response.size != RESPONSE_BYTES) return null
        if (response[0] != TYPE_RESPONSE) return null
        // The response must name the initiation it answers.
        if (le32(response, 8) != pending.localIndex) return null

        val responderEphemeral = response.copyOfRange(12, 44)
        val encryptedEmpty = response.copyOfRange(44, 60)

        // ee and se, from the client's side. Skipping the static DH here -- because the client
        // already knows its own static key -- is what makes this shorter than [accept], not what
        // makes it different.
        var hash = hashOf(pending.hash, responderEphemeral)
        var ck = kdf1(pending.chainingKey, responderEphemeral)
        ck = kdf1(ck, X25519.scalarMult(pending.ephemeralPrivate, responderEphemeral))
        ck = kdf1(ck, X25519.scalarMult(pending.staticPrivate, responderEphemeral))

        val pair = kdf2(ck, ByteArray(0))
        hash = hashOf(hash, pair.second)
        aeadOpen(pair.first, encryptedEmpty, hash) ?: return null

        val (receiveKey, sendKey) = kdf2(pair.first, ByteArray(0))
        return Session(
            localIndex = pending.localIndex,
            remoteIndex = le32(response, 4),
            sendKey = sendKey,
            receiveKey = receiveKey,
            createdAt = System.currentTimeMillis(),
        )
    }

    // ── MAC1 ───────────────────────────────────────────────────────────────

    /**
     * MAC1: a keyed BLAKE2s over everything but the two MAC fields.
     *
     * The key comes from the responder's PUBLIC key, so anyone who knows it can produce a valid
     * MAC1. It is not authentication, it is a cheap way to drop packets aimed at a server that does
     * not exist -- which is what a spoofed-source flood looks like on the wire.
     */
    internal fun mac1Key(publicKey: ByteArray): ByteArray =
        Blake2s.hash(LABEL_MAC1.toByteArray() + publicKey)

    internal fun mac1Valid(message: ByteArray, from: Int, length: Int, publicKey: ByteArray): Boolean {
        if (from + length + 16 > message.size) return false
        val expected = Blake2s.hash(mac1Key(publicKey), message.copyOfRange(from, from + length), 16)
        return constantTimeEquals(expected, message.copyOfRange(from + length, from + length + 16))
    }

    /** The 16-byte MAC1 tag for a message, over everything but the two MAC fields. */
    private fun mac1(message: ByteArray, length: Int, publicKey: ByteArray): ByteArray =
        Blake2s.hash(mac1Key(publicKey), message.copyOfRange(0, length), 16)

    // ── Transport ──────────────────────────────────────────────────────────

    /**
     * One established peer.
     *
     * Keys are directional: what this side sends with is what the other side receives with. The
     * receive counter must increase or a replayed packet is accepted, and in a VPN an old packet can
     * be a DNS answer the user has already seen.
     */
    class Session(
        val localIndex: Int,
        val remoteIndex: Int,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        private var sendCounter = 0L
        private var highestReceived = -1L

        @Volatile var lastReceivedAt: Long = createdAt
            private set

        fun expired(now: Long = System.currentTimeMillis()): Boolean =
            now - createdAt > REJECT_AFTER_TIME_MS

        fun needsRekey(): Boolean = sendCounter >= REKEY_AFTER_MESSAGES

        /** Wraps one IP packet for the wire. */
        fun seal(packet: ByteArray): ByteArray {
            val counter = sendCounter++
            val out = ByteArray(TRANSPORT_HEADER_BYTES + packet.size + 16)
            out[0] = TYPE_TRANSPORT
            writeLe32(out, 4, remoteIndex)
            writeLe64(out, 8, counter)
            aeadSeal(sendKey, nonce(counter), packet, ByteArray(0)).copyInto(out, TRANSPORT_HEADER_BYTES)
            return out
        }

        /** Unwraps one packet, or returns null for anything replayable, truncated or forged. */
        fun open(message: ByteArray): ByteArray? {
            if (message.size < TRANSPORT_HEADER_BYTES + 16) return null
            if (message[0] != TYPE_TRANSPORT) return null
            if (le32(message, 4) != localIndex) return null
            val counter = le64(message, 8)
            if (counter <= highestReceived) return null
            val plain = aeadOpen(
                receiveKey,
                nonce(counter),
                message.copyOfRange(TRANSPORT_HEADER_BYTES, message.size),
                ByteArray(0),
            ) ?: return null
            highestReceived = counter
            lastReceivedAt = System.currentTimeMillis()
            return plain
        }
    }

    // ── Message helpers ────────────────────────────────────────────────────

    internal fun nonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        writeLe64(nonce, 4, counter)
        return nonce
    }

    /** The index a message addresses, for looking a session up before decrypting it. */
    fun indexOf(message: ByteArray): Int? =
        if (message.size >= 8 && message[0] in TYPE_INITIATION..TYPE_TRANSPORT) le32(message, 4) else null

    /** Whether a handshake timestamp is close enough to now to be a live attempt. */
    fun isFresh(timestamp: ByteArray, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (timestamp.size != 12) return false
        var seconds = 0L
        for (i in 0 until 8) seconds = (seconds shl 8) or (timestamp[i].toLong() and 0xFF)
        seconds = seconds xor 0x4000000000000000L
        var nanos = 0L
        for (i in 8 until 12) nanos = (nanos shl 8) or (timestamp[i].toLong() and 0xFF)
        val then = seconds * 1000L + nanos / 1_000_000L
        return kotlin.math.abs(nowMillis - then) <= REJECT_AFTER_TIME_MS
    }

    /** TAI64N, twelve bytes: seconds with the TAI epoch marker, then nanoseconds. */
    fun tai64n(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val out = ByteArray(12)
        val seconds = nowMillis / 1000L + 0x400000000000000AL
        for (i in 0 until 8) out[i] = (seconds ushr (56 - 8 * i)).toByte()
        val nanos = ((nowMillis % 1000L) * 1_000_000L).toInt()
        for (i in 0 until 4) out[8 + i] = (nanos ushr (24 - 8 * i)).toByte()
        return out
    }

    // ── Primitives used by the handshake ───────────────────────────────────

    private fun constructionHash(): ByteArray = Blake2s.hash(CONSTRUCTION.toByteArray())

    private fun initialHash(responderPublic: ByteArray): ByteArray {
        var hash = hashOf(constructionHash(), IDENTIFIER.toByteArray())
        hash = hashOf(hash, responderPublic)
        return hash
    }

    private fun hashOf(vararg parts: ByteArray): ByteArray {
        val state = Blake2s()
        for (part in parts) state.update(part)
        return state.digest()
    }

    private fun kdf1(key: ByteArray, input: ByteArray): ByteArray =
        HmacBlake2s.mac(key, byteArrayOf(1) + input)

    private fun kdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val t0 = HmacBlake2s.mac(key, byteArrayOf(1) + input)
        val t1 = HmacBlake2s.mac(key, t0 + byteArrayOf(2) + input)
        return t0 to t1
    }

    private fun aeadSeal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        aeadSeal(key, ByteArray(12), plaintext, aad)

    private fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        // A zero-length handshake key would mean the KDF was fed nothing; refuse rather than
        // produce a packet that authenticates against an all-zero key.
        require(key.isNotEmpty()) { "refusing to seal with an empty key" }
        return ChaChaPoly.seal(key, nonce, plaintext, aad)
    }

    private fun aeadOpen(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        aeadOpen(key, ByteArray(12), sealed, aad)

    private fun aeadOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (key.isEmpty()) return null
        return ChaChaPoly.open(key, nonce, sealed, aad)
    }

    private fun randomIndex(): Int {
        while (true) {
            val candidate = random.nextInt()
            if (candidate != 0) return candidate
        }
    }

    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    internal fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    internal fun le64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun writeLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
}
