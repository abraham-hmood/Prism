package com.prism.launcher.messaging

import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.WalletCipher
import com.prism.launcher.wallet.WalletVault
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE 39's envelope: seal, frame, unframe, open, and the inbox.
 *
 * ## Why this needs a real wallet and gets one
 *
 * The relay key is derived from the BIP-39 seed, so nothing about the envelope can be tested without a
 * phrase installed. A [WalletCipher] that does not encrypt is enough for that: what is under test is the
 * RELAY's cryptography, not the Keystore's, and a pass-through cipher isolates one from the other.
 *
 * ## What would be silent if it were wrong
 *
 * All of it. An envelope that seals but does not open produces a relay that looks healthy on the phone
 * and delivers nothing. Framing that is off by one corrupts the base64 and the failure surfaces as a
 * decryption error, pointing at the key rather than the wire. A wrong key that happened to decrypt would
 * be worse than either. So each of those is a test.
 */
class SmsRelayTest {

    /**
     * Stores the phrase as-is.
     *
     * NOT encrypting is the point: the Android cipher is the platform Keystore and the desktop one does
     * not exist yet (PHASE 81). Substituting a real cipher here would test that instead of this.
     */
    private object PassThroughCipher : WalletCipher {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String? = ciphertext
    }

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-relay-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private lateinit var phrase: List<String>

    @BeforeTest
    fun installWallet() {
        WalletVault.installCipher(PassThroughCipher)
        // A generated phrase rather than a hardcoded one: Bip39.validate checks the checksum, and a
        // handwritten word list would be rejected for a reason that has nothing to do with this feature.
        phrase = Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS)
        val outcome = WalletVault.import(phrase.joinToString(" "))
        assertTrue(outcome is WalletVault.Outcome.Created, "the fixture wallet did not install: $outcome")
        assertTrue(CloudVault.isReady(), "CloudVault should see the fixture wallet")
    }

    @AfterTest
    fun cleanUp() {
        WalletVault.wipe()
        scratch.deleteRecursively()
    }

    private fun sample(body: String = "hello from the phone", at: Long = 1_700_000_000_000L) =
        SmsRelay.Relayed(address = "+15550100", body = body, receivedAt = at)

    // ── The envelope ───────────────────────────────────────────────────────

    @Test
    fun `a batch survives seal and open unchanged`() {
        val messages = listOf(
            sample("first"),
            sample("second, with a comma and an apostrophe: don't", at = 1_700_000_001_000L),
            // Non-ASCII, because the payload goes through base64 and a charset assumption would mangle
            // exactly this and nothing else.
            sample("emoji and accents: éè 📡", at = 1_700_000_002_000L),
        )
        val sealed = assertNotNull(SmsRelay.seal(messages, "Pixel"))
        val opened = assertNotNull(SmsRelay.open(sealed))

        assertEquals(3, opened.size)
        assertEquals(messages.map { it.body }, opened.map { it.body })
        assertEquals(messages.map { it.address }, opened.map { it.address })
        assertEquals(messages.map { it.receivedAt }, opened.map { it.receivedAt })
        assertEquals("Pixel", opened.first().fromDevice, "the sending device should survive")
    }

    @Test
    fun `the sealed bytes do not contain the message text`() {
        // The whole point of the feature. A peer on the mesh sees this packet.
        val secret = "the-body-that-must-not-appear"
        val sealed = assertNotNull(SmsRelay.seal(listOf(sample(secret)), "Pixel"))
        assertFalse(
            String(sealed, Charsets.ISO_8859_1).contains(secret),
            "the plaintext appeared in the sealed payload",
        )
        assertFalse(
            String(sealed, Charsets.ISO_8859_1).contains("+15550100"),
            "the sender's number appeared in the sealed payload",
        )
    }

    @Test
    fun `the same batch seals differently every time`() {
        // A fresh nonce per seal. Without it, two identical texts produce identical packets and an
        // observer learns that the same message was sent twice.
        val messages = listOf(sample())
        val a = assertNotNull(SmsRelay.seal(messages, "Pixel"))
        val b = assertNotNull(SmsRelay.seal(messages, "Pixel"))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a different wallet cannot open the batch`() {
        val sealed = assertNotNull(SmsRelay.seal(listOf(sample("private")), "Pixel"))

        // The WRONG_WALLET case, which is the one users will hit: a desktop with its own fresh wallet.
        WalletVault.wipe()
        WalletVault.installCipher(PassThroughCipher)
        val other = Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS)
        WalletVault.import(other.joinToString(" "))

        assertNull(SmsRelay.open(sealed), "a different phrase must not decrypt somebody's texts")
    }

    @Test
    fun `the same phrase on another device does open the batch`() {
        val sealed = assertNotNull(SmsRelay.seal(listOf(sample("paired")), "Pixel"))

        // Restoring the phone's phrase is what pairs the two machines. Simulated by wiping and
        // re-importing the SAME words, which is exactly what a user does on a new device.
        WalletVault.wipe()
        WalletVault.installCipher(PassThroughCipher)
        WalletVault.import(phrase.joinToString(" "))

        val opened = assertNotNull(SmsRelay.open(sealed))
        assertEquals("paired", opened.single().body)
    }

    @Test
    fun `sealing without a wallet refuses rather than sending plaintext`() {
        WalletVault.wipe()
        assertNull(
            SmsRelay.seal(listOf(sample()), "Pixel"),
            "with no key it must refuse; a plaintext fallback is the one failure this cannot have",
        )
    }

    @Test
    fun `an empty batch is not sealed`() {
        assertNull(SmsRelay.seal(emptyList(), "Pixel"))
    }

    // ── The wire framing ───────────────────────────────────────────────────

    @Test
    fun `framing round-trips and carries the mesh header and opcode`() {
        val sealed = assertNotNull(SmsRelay.seal(listOf(sample("framed")), "Pixel"))
        val framed = SmsRelay.frame(sealed)

        assertEquals("PRISM", String(framed, 0, 5, Charsets.US_ASCII))
        assertEquals(SmsRelay.OPCODE_SMS, framed[5])

        val back = assertNotNull(SmsRelay.unframe(framed, framed.size))
        assertTrue(sealed.contentEquals(back), "the payload did not survive framing")
        assertEquals("framed", assertNotNull(SmsRelay.open(back)).single().body)
    }

    @Test
    fun `packets that are not relay packets are ignored`() {
        // This port carries the whole gossip protocol, so most of what arrives is somebody else's
        // opcode. Misreading one as a relay would be a decryption failure logged as a wrong wallet.
        val wrongOpcode = "PRISM".toByteArray() + byteArrayOf(0x20) + "payload".toByteArray()
        assertNull(SmsRelay.unframe(wrongOpcode, wrongOpcode.size))

        val wrongHeader = "OTHER".toByteArray() + byteArrayOf(SmsRelay.OPCODE_SMS) + "x".toByteArray()
        assertNull(SmsRelay.unframe(wrongHeader, wrongHeader.size))

        assertNull(SmsRelay.unframe(ByteArray(3), 3), "a runt packet must not be parsed")
        assertNull(SmsRelay.unframe("PRISM".toByteArray(), 5), "header with no opcode")
    }

    @Test
    fun `the payload string form matches the framed form`() {
        // Android sends through the mesh service, which takes a String payload; desktop listens to raw
        // datagrams. Both have to agree or the relay works in one direction only.
        val sealed = assertNotNull(SmsRelay.seal(listOf(sample("both ways")), "Pixel"))
        val viaString = assertNotNull(SmsRelay.fromPayloadString(SmsRelay.payloadString(sealed)))
        assertTrue(sealed.contentEquals(viaString))
        assertEquals("both ways", assertNotNull(SmsRelay.open(viaString)).single().body)
    }

    // ── The inbox ──────────────────────────────────────────────────────────

    @Test
    fun `stored messages persist and group into threads`() {
        val messages = listOf(
            sample("one", at = 1L),
            sample("two", at = 2L),
            SmsRelay.Relayed("+15550999", "different sender", 3L),
        )
        assertEquals(3, SmsRelay.store(scratch, messages))

        val loaded = SmsRelay.load(scratch)
        assertEquals(3, loaded.size)
        // Oldest first, so a reader appends at the bottom like every messaging app.
        assertEquals(listOf(1L, 2L, 3L), loaded.map { it.receivedAt })

        val threads = SmsRelay.threads(scratch)
        assertEquals(2, threads.size)
        assertEquals(2, threads["+15550100"]?.size)
        assertEquals(1, threads["+15550999"]?.size)
    }

    @Test
    fun `a duplicate is not stored twice`() {
        // UDP delivers duplicates, a restarting phone re-relays its backlog, and two phones on one mesh
        // both forward a group message. A reader seeing everything doubled concludes the relay is broken.
        val message = sample("exactly once", at = 42L)
        assertEquals(1, SmsRelay.store(scratch, listOf(message)))
        assertEquals(0, SmsRelay.store(scratch, listOf(message)), "the same message came back")
        assertEquals(0, SmsRelay.store(scratch, listOf(message, message)))
        assertEquals(1, SmsRelay.load(scratch).size)
    }

    @Test
    fun `two different texts at the same instant are both kept`() {
        // Deduplication keys on address, time AND body -- two messages can genuinely share a timestamp,
        // and keying on time alone would silently drop one of them.
        assertEquals(
            2,
            SmsRelay.store(scratch, listOf(sample("first", at = 99L), sample("second", at = 99L))),
        )
        assertEquals(2, SmsRelay.load(scratch).size)
    }

    @Test
    fun `an empty or absent inbox reads as empty rather than throwing`() {
        val empty = File(scratch, "nothing-here")
        assertTrue(SmsRelay.load(empty).isEmpty())
        assertTrue(SmsRelay.threads(empty).isEmpty())
        assertEquals(0, SmsRelay.store(empty, emptyList()))
    }

    @Test
    fun `clearing the inbox empties it`() {
        SmsRelay.store(scratch, listOf(sample()))
        assertEquals(1, SmsRelay.load(scratch).size)
        SmsRelay.clear(scratch)
        assertTrue(SmsRelay.load(scratch).isEmpty())
    }

    @Test
    fun `the status says no wallet when there is none`() {
        WalletVault.wipe()
        assertEquals(SmsRelay.Status.NO_WALLET, SmsRelay.status())
        assertTrue(SmsRelay.desktopStatus().contains("wallet", ignoreCase = true))
    }
}
