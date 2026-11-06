package com.prism.launcher.storage.remote

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted, mutually authenticated pipe every drive connection between devices runs inside.
 *
 * ## The key belongs to the pair
 *
 * The client holds the key it shares with the server's device, and the server holds one key per
 * trusted device it lets browse its drives -- each agreed from the two devices' key pairs (see
 * TrustedSeal and DeviceKeys). Nothing about them crosses the wire. A device that is not trusted for
 * drives has no key that fits, so it never reaches the drive server at all -- not the file list, not
 * the token, not even the HTTP request line.
 *
 * ## Handshake (before a single byte of HTTP)
 *
 *  1. client -> server: "PRDV2" ‖ client nonce (32) ‖ HMAC(key, "hello" ‖ client nonce)
 *  2. the server finds which of its keys made that hello -- that is WHICH device is calling, learned
 *     without either side naming itself on the wire -- or hangs up
 *  3. server -> client: server nonce (32) ‖ HMAC(key, "server" ‖ nonces)
 *  4. client checks the server's proof, then sends HMAC(key, "client" ‖ nonces)
 *  5. the server checks the client's proof; otherwise it hangs up
 *
 * A replayed hello gets a fresh server nonce and cannot produce step 4, and the fresh nonces make every
 * session's traffic keys unique.
 *
 * ## Traffic
 *
 * Each direction gets its own AES-256-GCM key, HMAC(key, direction ‖ nonces). Data travels in frames
 * of `length(4) ‖ ciphertext ‖ tag(16)`, with a per-direction frame counter as the GCM nonce: unique
 * because the key itself is unique to this session and direction. A modified, dropped, reordered or
 * injected frame fails its tag and the connection is closed.
 */
object SecureDriveChannel {

    private val MAGIC = "PRDV2".toByteArray(Charsets.US_ASCII)
    private const val NONCE = 32
    private const val MAX_FRAME = 64 * 1024
    private const val TAG_BITS = 128

    class Pipe(val input: InputStream, val output: OutputStream)

    /** The client side of the handshake. Throws when the server does not hold the same key. */
    fun connect(rawIn: InputStream, rawOut: OutputStream, key: ByteArray): Pipe {
        val input = DataInputStream(rawIn)
        val clientNonce = random(NONCE)
        rawOut.write(MAGIC + clientNonce + hmac(key, "hello".toByteArray() + clientNonce))
        rawOut.flush()
        val serverNonce = ByteArray(NONCE).also { input.readFully(it) }
        val serverProof = ByteArray(32).also { input.readFully(it) }
        val nonces = clientNonce + serverNonce
        if (!MessageDigest.isEqual(serverProof, hmac(key, "server".toByteArray() + nonces))) {
            throw IOException("That device does not hold this pair's key")
        }
        rawOut.write(hmac(key, "client".toByteArray() + nonces))
        rawOut.flush()
        return Pipe(
            FrameInput(input, hmac(key, "s2c".toByteArray() + nonces)),
            FrameOutput(rawOut, hmac(key, "c2s".toByteArray() + nonces)),
        )
    }

    /** A connection the server accepted, and the key (so the device) it was made with. */
    class Accepted(val pipe: Pipe, val key: ByteArray)

    /**
     * The server side, given every key it accepts. Throws, and the caller hangs up, when the client
     * holds none of them.
     */
    fun accept(rawIn: InputStream, rawOut: OutputStream, keys: List<ByteArray>): Accepted {
        val input = DataInputStream(rawIn)
        val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Prism drive connection")
        val clientNonce = ByteArray(NONCE).also { input.readFully(it) }
        val hello = ByteArray(32).also { input.readFully(it) }
        val key = keys.firstOrNull { MessageDigest.isEqual(hello, hmac(it, "hello".toByteArray() + clientNonce)) }
            ?: throw IOException("Not a device this one shares its drives with")
        val serverNonce = random(NONCE)
        val nonces = clientNonce + serverNonce
        rawOut.write(serverNonce + hmac(key, "server".toByteArray() + nonces))
        rawOut.flush()
        val clientProof = ByteArray(32).also { input.readFully(it) }
        if (!MessageDigest.isEqual(clientProof, hmac(key, "client".toByteArray() + nonces))) {
            throw IOException("The other device does not hold this pair's key")
        }
        return Accepted(
            Pipe(
                FrameInput(input, hmac(key, "c2s".toByteArray() + nonces)),
                FrameOutput(rawOut, hmac(key, "s2c".toByteArray() + nonces)),
            ),
            key,
        )
    }

    private fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun gcmNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[11 - i] = (counter ushr (8 * i)).toByte()
        return nonce
    }

    /** Buffers plaintext and sends it as sealed frames on flush, or when a frame is full. */
    private class FrameOutput(raw: OutputStream, private val key: ByteArray) : OutputStream() {
        private val out = DataOutputStream(raw)
        private val buffer = ByteArrayOutputStream(MAX_FRAME)
        private var counter = 0L

        override fun write(b: Int) {
            buffer.write(b)
            if (buffer.size() >= MAX_FRAME) sendFrame()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var left = len
            while (left > 0) {
                val take = minOf(left, MAX_FRAME - buffer.size())
                buffer.write(b, offset, take)
                offset += take
                left -= take
                if (buffer.size() >= MAX_FRAME) sendFrame()
            }
        }

        private fun sendFrame() {
            if (buffer.size() == 0) return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, gcmNonce(counter++)))
            val sealed = cipher.doFinal(buffer.toByteArray())
            buffer.reset()
            out.writeInt(sealed.size)
            out.write(sealed)
        }

        override fun flush() {
            sendFrame()
            out.flush()
        }

        override fun close() {
            flush()
            out.close()
        }
    }

    /** Reads and opens sealed frames; any tampering ends the stream with an error. */
    private class FrameInput(private val input: DataInputStream, private val key: ByteArray) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var counter = 0L
        private var ended = false

        private fun nextFrame(): Boolean {
            if (ended) return false
            val length = try {
                input.readInt()
            } catch (_: java.io.EOFException) {
                ended = true
                return false
            }
            if (length <= 16 || length > MAX_FRAME + 16) throw IOException("Bad frame")
            val sealed = ByteArray(length).also { input.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, gcmNonce(counter++)))
            plain = try {
                cipher.doFinal(sealed)
            } catch (_: Exception) {
                throw IOException("A frame failed authentication; the connection was tampered with")
            }
            position = 0
            return true
        }

        override fun read(): Int {
            while (position >= plain.size) if (!nextFrame()) return -1
            return plain[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= plain.size) if (!nextFrame()) return -1
            val take = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, take)
            position += take
            return take
        }

        override fun available(): Int = plain.size - position

        override fun close() = input.close()
    }
}
