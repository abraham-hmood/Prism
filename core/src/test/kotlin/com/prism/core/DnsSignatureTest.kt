package com.prism.core

import com.prism.launcher.wallet.Ed25519
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DnsSignatureTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * RFC 8032, section 7.1, test vector 1.
     *
     * A known-answer test rather than only a round trip, because a sign and a verify that agree
     * with each other can still both be wrong -- and this keypair is what binds a name to an
     * identity, so "it verifies on the device that made it" is not the property that matters.
     */
    @Test
    fun `rfc 8032 test vector one`() {
        val seed = unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = unhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = unhex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )

        assertEquals(hex(publicKey), hex(Ed25519.publicKeyFromSeed(seed)))
        assertEquals(hex(signature), hex(Ed25519.sign(seed, ByteArray(0))))
        assertTrue(Ed25519.verify(publicKey, ByteArray(0), signature))
    }

    @Test
    fun `a changed message does not verify`() {
        val seed = ByteArray(32) { it.toByte() }
        val publicKey = Ed25519.publicKeyFromSeed(seed)
        val signature = Ed25519.sign(seed, "hello".toByteArray())

        assertTrue(Ed25519.verify(publicKey, "hello".toByteArray(), signature))
        assertFalse(Ed25519.verify(publicKey, "hell0".toByteArray(), signature))
    }

    @Test
    fun `another key does not verify the signature`() {
        val first = Ed25519.publicKeyFromSeed(ByteArray(32) { 1 })
        val second = Ed25519.publicKeyFromSeed(ByteArray(32) { 2 })
        val signature = Ed25519.sign(ByteArray(32) { 2 }, "prism".toByteArray())

        assertFalse(Ed25519.verify(first, "prism".toByteArray(), signature))
        assertTrue(Ed25519.verify(second, "prism".toByteArray(), signature))
    }

    @Test
    fun `a malleated S value is refused`() {
        val seed = ByteArray(32) { 7 }
        val publicKey = Ed25519.publicKeyFromSeed(seed)
        val signature = Ed25519.sign(seed, "record".toByteArray())

        // S + L is the same scalar modulo the group order, and is the classic malleated form.
        val s = Ed25519.fromLittleEndian(signature.copyOfRange(32, 64)).add(Ed25519.L)
        val mutated = signature.copyOf()
        Ed25519.littleEndian(s).copyInto(mutated, 32)

        assertFalse(Ed25519.verify(publicKey, "record".toByteArray(), mutated))
    }

    @Test
    fun `canonical form ignores alternate order and case`() {
        val first = DnsSignature.canonical("Bank.P2P", "10.0.0.2", 5, listOf("10.0.0.3", "10.0.0.2"))
        val second = DnsSignature.canonical("bank.p2p", "10.0.0.2", 5, listOf("10.0.0.2", "10.0.0.3"))
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `a record signature verifies and a redirected record does not`() {
        val seed = ByteArray(32) { 9 }
        val publicKey = Ed25519.publicKeyFromSeed(seed)
        val alternates = listOf("10.0.0.4")
        val signature = Ed25519.sign(
            seed,
            DnsSignature.canonical("site.p2p", "10.0.0.3", 1_700_000_000_000, alternates),
        )

        val encoded = Base64.getEncoder().encodeToString(signature)
        val key = hex(publicKey)

        assertTrue(
            DnsSignature.verify(key, encoded, "site.p2p", "10.0.0.3", 1_700_000_000_000, alternates)
        )
        assertFalse(
            DnsSignature.verify(key, encoded, "site.p2p", "10.0.0.9", 1_700_000_000_000, alternates)
        )
        assertFalse(
            DnsSignature.verify(key, encoded, "site.p2p", "10.0.0.3", 1_700_000_000_001, alternates)
        )
        assertFalse(
            DnsSignature.verify(key, encoded, "site.p2p", "10.0.0.3", 1_700_000_000_000, emptyList())
        )
    }

    @Test
    fun `a malformed key is refused rather than throwing`() {
        assertFalse(DnsSignature.verify("not-hex", "AAAA", "a.p2p", "1.2.3.4", 0, emptyList()))
        assertFalse(DnsSignature.verify("ab".repeat(32), "not base64!", "a.p2p", "1.2.3.4", 0, emptyList()))
    }
}
