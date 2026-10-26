package com.prism.core

import com.prism.core.wg.Blake2s
import com.prism.core.wg.ChaChaPoly
import com.prism.core.wg.WireGuardNoise
import com.prism.core.wg.X25519
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The handshake crypto, checked against published vectors and then against itself.
 *
 * The vectors are the part that matters: a self-consistent implementation of the wrong hash still
 * round-trips. BLAKE2s, X25519 and ChaCha20 each have a published answer below, so a mistake in any
 * of them fails here rather than on a phone that silently cannot connect.
 */
class WireGuardNoiseTest {

    private fun hex(text: String): ByteArray =
        text.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `blake2s matches the empty-string vector`() {
        assertEquals(
            "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            Blake2s.hash(ByteArray(0)).hex(),
        )
    }

    @Test
    fun `blake2s matches the abc vector`() {
        assertEquals(
            "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            Blake2s.hash("abc".toByteArray()).hex(),
        )
    }

    @Test
    fun `blake2s handles input longer than one block`() {
        // 64 bytes is exactly one block, which is where a naive implementation compresses the last
        // block twice or flags it non-final. 65 crosses the boundary.
        val input = ByteArray(65) { it.toByte() }
        val oneGo = Blake2s.hash(input)
        val piecewise = Blake2s().apply {
            update(input.copyOfRange(0, 17))
            update(input.copyOfRange(17, 64))
            update(input.copyOfRange(64, 65))
        }.digest()
        assertContentEquals(oneGo, piecewise)
    }

    @Test
    fun `x25519 matches the RFC 7748 vector`() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519.scalarMult(scalar, u).hex(),
        )
    }

    @Test
    fun `x25519 agrees from both sides`() {
        val alice = WireGuardNoise.generatePrivateKey()
        val bob = WireGuardNoise.generatePrivateKey()
        val alicePublic = WireGuardNoise.publicKey(alice)
        val bobPublic = WireGuardNoise.publicKey(bob)
        assertContentEquals(
            X25519.scalarMult(alice, bobPublic),
            X25519.scalarMult(bob, alicePublic),
        )
    }

    @Test
    fun `chacha20 block matches the RFC 7539 all-zero vector`() {
        val block = ChaChaPoly.block(ByteArray(32), ByteArray(12), 0)
        assertEquals(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
                "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
            block.hex(),
        )
    }

    @Test
    fun `aead round-trips and rejects a flipped tag`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plain = "the quick brown fox".toByteArray()
        val sealed = ChaChaPoly.seal(key, nonce, plain, "aad".toByteArray())
        assertContentEquals(plain, ChaChaPoly.open(key, nonce, sealed, "aad".toByteArray()))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(ChaChaPoly.open(key, nonce, sealed, "aad".toByteArray()))
    }

    @Test
    fun `a handshake completes and both sides agree on transport keys`() {
        val serverPrivate = WireGuardNoise.generatePrivateKey()
        val serverPublic = WireGuardNoise.publicKey(serverPrivate)
        val clientPrivate = WireGuardNoise.generatePrivateKey()

        val pending = WireGuardNoise.initiate(clientPrivate, serverPublic)
        val accepted = assertNotNull(
            WireGuardNoise.accept(pending.message, serverPrivate, serverPublic),
            "the server should accept a well-formed initiation",
        )
        assertEquals(WireGuardNoise.RESPONSE_BYTES, accepted.response.size)
        assertEquals(WireGuardNoise.TYPE_RESPONSE, accepted.response[0])

        val client = assertNotNull(
            WireGuardNoise.complete(pending, accepted.response),
            "the client should accept the response",
        )

        // What the client sends is what the server receives, and the other way round.
        val outbound = "hello from the client".toByteArray()
        val opened = assertNotNull(accepted.session.open(client.seal(outbound)))
        assertContentEquals(outbound, opened)

        val reply = "hello from the server".toByteArray()
        val received = assertNotNull(client.open(accepted.session.seal(reply)))
        assertContentEquals(reply, received)
    }

    @Test
    fun `a replayed transport packet is rejected`() {
        val serverPrivate = WireGuardNoise.generatePrivateKey()
        val serverPublic = WireGuardNoise.publicKey(serverPrivate)
        val clientPrivate = WireGuardNoise.generatePrivateKey()

        val pending = WireGuardNoise.initiate(clientPrivate, serverPublic)
        val accepted = assertNotNull(WireGuardNoise.accept(pending.message, serverPrivate, serverPublic))
        val client = assertNotNull(WireGuardNoise.complete(pending, accepted.response))

        val packet = client.seal("one".toByteArray())
        assertNotNull(accepted.session.open(packet))
        assertNull(accepted.session.open(packet), "the same counter must not be accepted twice")
    }

    @Test
    fun `an initiation with a broken mac1 is refused`() {
        val serverPrivate = WireGuardNoise.generatePrivateKey()
        val serverPublic = WireGuardNoise.publicKey(serverPrivate)
        val clientPrivate = WireGuardNoise.generatePrivateKey()

        val pending = WireGuardNoise.initiate(clientPrivate, serverPublic)
        pending.message[WireGuardNoise.INITIATION_BYTES - 20] =
            (pending.message[WireGuardNoise.INITIATION_BYTES - 20].toInt() xor 1).toByte()

        assertNull(WireGuardNoise.accept(pending.message, serverPrivate, serverPublic))
    }

    @Test
    fun `an initiation for a different server key is refused`() {
        val serverPrivate = WireGuardNoise.generatePrivateKey()
        val serverPublic = WireGuardNoise.publicKey(serverPrivate)
        val otherPrivate = WireGuardNoise.generatePrivateKey()
        val otherPublic = WireGuardNoise.publicKey(otherPrivate)

        // Built for `otherPublic`, presented to a server that answers to `serverPublic`.
        val pending = WireGuardNoise.initiate(WireGuardNoise.generatePrivateKey(), otherPublic)
        assertNull(WireGuardNoise.accept(pending.message, serverPrivate, serverPublic))
    }

    @Test
    fun `tai64n timestamps read back as fresh`() {
        val now = 1_760_000_000_000L
        val stamp = WireGuardNoise.tai64n(now)
        assertEquals(12, stamp.size)
        assertTrue(WireGuardNoise.isFresh(stamp, now))
        assertFalse(WireGuardNoise.isFresh(stamp, now + 600_000L))
        assertFalse(WireGuardNoise.isFresh(ByteArray(3), now))
    }
}
