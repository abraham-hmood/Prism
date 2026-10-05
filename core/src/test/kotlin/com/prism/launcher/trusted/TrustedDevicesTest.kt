package com.prism.launcher.trusted

import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
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
 * The trust handshake, and the authorisation rule it exists to enforce.
 *
 * ## What would be silent if it were wrong
 *
 * The dangerous failures here are all permissive ones. A receiver that stored whatever arrived would make
 * its own consent meaningless; a pairing that left two rows for one device would show trust that could not
 * be revoked; trust keyed on an IP would transfer to whichever device inherited that address. None of those
 * throws — each just quietly shares somebody's private data with something they did not agree to. So each
 * is a test.
 */
class TrustedDevicesTest {

    private object PassThroughCipher : WalletCipher {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String? = ciphertext
    }

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-trust-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private lateinit var phrase: List<String>

    @BeforeTest
    fun setUp() {
        WalletVault.installCipher(PassThroughCipher)
        phrase = Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS)
        WalletVault.import(phrase.joinToString(" "))
        assertTrue(CloudVault.isReady())

        // ON THE OVERLAY. Pairing is refused off it (see MeshMembership), which is the point of that
        // change -- so a test of pairing has to be a test of a device that is actually on the meshnet.
        // The addresses below are all inside the mesh subnet for the same reason.
        com.prism.core.MeshMembership.addressesOverride = { listOf("10.8.0.4") }

        TrustedDevices.install(scratch)
        TrustedDevices.localName = { "Test Machine" }
        TrustedDevices.localPlatform = "test"
        sent.clear()
        TrustedDevices.sender = { ip, opcode, payload -> sent += Sent(ip, opcode, payload) }
        // Any offer left from a previous test would answer this one's questions.
        TrustedDevices.pendingOffers().forEach { TrustedDevices.dismissOffer(it.fingerprint) }
        TrustedDevices.all().forEach { TrustedDevices.revoke(it.fingerprint) }
    }

    @AfterTest
    fun tearDown() {
        com.prism.core.MeshMembership.addressesOverride = null
        TrustedDevices.pendingOffers().forEach { TrustedDevices.dismissOffer(it.fingerprint) }
        WalletVault.wipe()
        scratch.deleteRecursively()
    }

    private data class Sent(val ip: String, val opcode: Byte, val payload: String)

    private val sent = mutableListOf<Sent>()

    /**
     * Answers an offer the way the far side does: reads the salt off the wire, derives the key from the
     * code this device is DISPLAYING, and seals the proof with it.
     *
     * Going through the real handshake rather than hand-writing a reply is the point -- a test that
     * skipped the proof would pass while the check it exists to verify was broken.
     */
    private fun replyProving(
        peerIp: String,
        fingerprint: String,
        accepted: List<TrustedDevices.Kind>,
        code: String? = null,
    ): String {
        val offer = sent.last { it.opcode == TrustedDevices.OPCODE_TRUST_OFFER && it.ip == peerIp }
        val salt = java.util.Base64.getDecoder().decode(JSONObject(offer.payload).optString("salt"))
        val typed = code ?: TrustedDevices.displayedCode(peerIp)!!
        val key = PairingCode.keyFor(typed, salt)!!
        return JSONObject().apply {
            put("fp", fingerprint)
            put("name", "Their Device")
            put("platform", "windows")
            put("accepted", com.prism.core.json.JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
            put("proof", java.util.Base64.getEncoder().encodeToString(PairingCode.proofFor(key, salt)))
        }.toString()
    }

    private fun offerPayload(
        fingerprint: String = "aabbccdd",
        name: String = "Their Phone",
        kinds: List<TrustedDevices.Kind> = listOf(TrustedDevices.Kind.MESSAGES),
        salt: ByteArray? = null,
    ) = JSONObject().apply {
        put("fp", fingerprint)
        put("name", name)
        put("platform", "android")
        put("kinds", com.prism.core.json.JSONArray().also { a -> kinds.forEach { a.put(it.id) } })
        // Absent by default, which is what a build from before codes existed sends.
        if (salt != null) put("salt", java.util.Base64.getEncoder().encodeToString(salt))
    }.toString()

    private fun replyPayload(
        fingerprint: String,
        accepted: List<TrustedDevices.Kind>,
        name: String = "Their Laptop",
    ) = JSONObject().apply {
        put("fp", fingerprint)
        put("name", name)
        put("platform", "windows")
        put("accepted", com.prism.core.json.JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
    }.toString()

    // ── Identity ───────────────────────────────────────────────────────────

    @Test
    fun `the fingerprint is stable and not the wallet address`() {
        val first = TrustedDevices.localFingerprint()
        assertEquals(first, TrustedDevices.localFingerprint(), "it must not change between calls")
        assertEquals(32, first.length, "16 bytes as hex")

        // An address is a public payment identity; using it here would link a user's devices to their
        // balance for every peer that saw a trust offer go past.
        val address = com.prism.launcher.wallet.CoinRegistry.bySymbol("PSC")
            ?.let { WalletVault.addressFor(it) }
        if (address != null) assertFalse(first.contains(address, ignoreCase = true))
    }

    @Test
    fun `a different machine name gives a different fingerprint`() {
        val mine = TrustedDevices.localFingerprint()
        TrustedDevices.localName = { "Another Machine" }
        assertFalse(mine == TrustedDevices.localFingerprint(), "two devices must not share an identity")
    }

    // ── Offer and reply ────────────────────────────────────────────────────

    @Test
    fun `an incoming offer waits for an answer and shares nothing yet`() {
        assertTrue(TrustedDevices.onOffer("10.8.0.7", offerPayload()))

        val offers = TrustedDevices.pendingOffers()
        assertEquals(1, offers.size)
        assertEquals("Their Phone", offers[0].name)
        assertEquals(setOf(TrustedDevices.Kind.MESSAGES), offers[0].kinds)

        // NOTHING is trusted until the user answers. An offer that established trust by arriving would be
        // the sender deciding what the receiver stores.
        assertFalse(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
        assertTrue(TrustedDevices.all().none { it.confirmed })
    }

    @Test
    fun `accepting an offer permits exactly what was accepted`() {
        TrustedDevices.onOffer(
            "10.8.0.7",
            offerPayload(kinds = listOf(TrustedDevices.Kind.MESSAGES, TrustedDevices.Kind.CLIPBOARD)),
        )
        // A SUBSET, which is the point of listing the kinds separately: accepting a clipboard is a much
        // smaller thing than accepting somebody's texts.
        assertTrue(TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.CLIPBOARD)))

        assertTrue(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.CLIPBOARD))
        assertFalse(
            TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES),
            "a kind that was offered but not accepted must stay refused",
        )
        assertTrue(TrustedDevices.pendingOffers().isEmpty(), "the offer should be answered and gone")
    }

    @Test
    fun `accepting is also agreeing to send, because a pairing is mutual`() {
        TrustedDevices.onOffer(
            "10.8.0.7",
            offerPayload(kinds = listOf(TrustedDevices.Kind.MESSAGES, TrustedDevices.Kind.CLIPBOARD)),
        )
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.CLIPBOARD))

        val device = TrustedDevices.byFingerprint("aabbccdd")!!
        assertTrue(
            TrustedDevices.Kind.CLIPBOARD in device.outgoing,
            "a device we accept a clipboard from must also send it ours",
        )
        assertTrue(
            TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD).any { it.fingerprint == "aabbccdd" },
            "and it has to appear as a recipient, or nothing would actually be sent",
        )
        assertFalse(
            TrustedDevices.Kind.MESSAGES in device.outgoing,
            "a kind that was declined must not be sent either -- mutual is not a way to widen consent",
        )
    }

    @Test
    fun `offering is also agreeing to receive`() {
        TrustedDevices.offer(
            "10.8.0.9", "Laptop", "windows",
            setOf(TrustedDevices.Kind.BROWSER_HISTORY),
        )
        TrustedDevices.onReply(
            "10.8.0.9",
            replyProving("10.8.0.9", "ffee0011", listOf(TrustedDevices.Kind.BROWSER_HISTORY)),
        )

        assertTrue(
            TrustedDevices.accepts("10.8.0.9", TrustedDevices.Kind.BROWSER_HISTORY),
            "the device we offered history to must also be allowed to send us theirs",
        )
    }

    // ── The pairing code ───────────────────────────────────────────────────

    @Test
    fun `the offer shows a code and does not send it`() {
        val code = TrustedDevices.offer(
            "10.8.0.9", "Laptop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD),
        )
        assertNotNull(code, "the offering device has to be given a code to display")
        assertEquals(
            PrismSettings.getPairingCodeLength(), code.length,
            "the code should be the configured length",
        )

        val payload = sent.last { it.opcode == TrustedDevices.OPCODE_TRUST_OFFER }.payload
        assertFalse(
            payload.contains(code),
            "THE CODE MUST NOT BE ON THE WIRE -- it is what proves the far side can see this screen, " +
                "and a code anything on the network could read proves nothing at all",
        )
        assertTrue(
            JSONObject(payload).optString("salt").isNotBlank(),
            "the salt does travel, because both sides have to derive the same key from it",
        )
    }

    @Test
    fun `a wrong code does not pair`() {
        TrustedDevices.offer("10.8.0.9", "Laptop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD))
        val wrong = TrustedDevices.displayedCode("10.8.0.9")!! + "x"

        val answered = TrustedDevices.onReply(
            "10.8.0.9",
            replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.CLIPBOARD), code = wrong),
        )

        assertFalse(answered, "a reply that cannot prove the code must be refused")
        assertNull(
            TrustedDevices.byFingerprint("99887766"),
            "and must leave no device behind -- half a pairing is worse than none",
        )
        assertTrue(
            TrustedDevices.all().isEmpty(),
            "the provisional row goes too, or the offer would look accepted",
        )
    }

    @Test
    fun `the right code pairs`() {
        TrustedDevices.offer("10.8.0.9", "Laptop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD))
        assertTrue(
            TrustedDevices.onReply(
                "10.8.0.9",
                replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.CLIPBOARD)),
            )
        )
        assertTrue(TrustedDevices.byFingerprint("99887766")!!.confirmed)
    }

    @Test
    fun `answering an offer without the code it is showing is refused`() {
        // The receiving side: an offer that carries a salt is one that expects a code, and accepting it
        // with nothing typed must not quietly pair.
        val salt = PairingCode.salt()
        TrustedDevices.onOffer("10.8.0.7", offerPayload(salt = salt))

        assertFalse(
            TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES)),
            "accepting without a code must fail when the offer asked for one",
        )
        assertEquals(
            1, TrustedDevices.pendingOffers().size,
            "and the offer stays pending, so the user can try again rather than losing it",
        )
    }

    @Test
    fun `an offer from a build with no code still pairs`() {
        // A phone that has not been updated sends no salt. Refusing it would break pairing with a device
        // the user already has, and the old behaviour is what they had yesterday.
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        assertFalse(TrustedDevices.pendingOffers().first().needsCode)
        assertTrue(TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES)))
    }

    // ── The wallet handoff ─────────────────────────────────────────────────

    @Test
    fun `a device with no wallet is sent the phrase, sealed with the code`() {
        val mine = WalletVault.phrase()!!.joinToString(" ")

        TrustedDevices.offer(
            "10.8.0.9", "New Laptop", "windows",
            setOf(TrustedDevices.Kind.WALLET, TrustedDevices.Kind.MESSAGES),
        )
        val offer = sent.last { it.opcode == TrustedDevices.OPCODE_TRUST_OFFER }
        val salt = java.util.Base64.getDecoder().decode(JSONObject(offer.payload).optString("salt"))
        val code = TrustedDevices.displayedCode("10.8.0.9")!!
        val key = PairingCode.keyFor(code, salt)!!

        // The far side answers correctly AND says it has no wallet.
        TrustedDevices.onReply(
            "10.8.0.9",
            JSONObject().apply {
                put("fp", "99887766")
                put("name", "New Laptop")
                put(
                    "accepted",
                    com.prism.core.json.JSONArray().also { it.put("wallet"); it.put("messages") },
                )
                put("proof", java.util.Base64.getEncoder().encodeToString(PairingCode.proofFor(key, salt)))
                put("needsWallet", true)
            }.toString(),
        )

        val handoff = sent.lastOrNull { it.opcode == TrustedDevices.OPCODE_TRUST_WALLET }
        assertNotNull(handoff, "a device that asked for a wallet and proved the code should be sent one")

        val sealed = java.util.Base64.getDecoder().decode(JSONObject(handoff.payload).optString("seed"))
        assertFalse(
            handoff.payload.contains(mine.split(" ").first()),
            "THE PHRASE MUST NOT BE READABLE ON THE WIRE",
        )
        assertEquals(
            mine, PairingCode.openPhrase(sealed, key, salt),
            "and it must open with the key the code derives to -- that is the whole handoff",
        )
    }

    @Test
    fun `a device that already has a wallet is not sent one`() {
        TrustedDevices.offer(
            "10.8.0.9", "Other", "windows", setOf(TrustedDevices.Kind.WALLET),
        )
        TrustedDevices.onReply(
            "10.8.0.9",
            replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.WALLET)),
        )
        assertNull(
            sent.firstOrNull { it.opcode == TrustedDevices.OPCODE_TRUST_WALLET },
            "a reply that did not ask for a wallet must not be sent one -- overwriting an identity is " +
                "not something trust makes acceptable",
        )
    }

    @Test
    fun `a wallet is refused when this device already has one`() {
        // The receiving side of the same rule. This test's fixture always has a wallet.
        assertTrue(WalletVault.isInitialized())
        val before = WalletVault.phrase()!!.joinToString(" ")

        TrustedDevices.onOffer("10.8.0.7", offerPayload(salt = PairingCode.salt()))
        assertFalse(
            TrustedDevices.onWallet(
                "10.8.0.7",
                JSONObject().apply {
                    put("fp", "aabbccdd")
                    put("seed", "irrelevant")
                }.toString(),
            )
        )
        assertEquals(before, WalletVault.phrase()!!.joinToString(" "), "the wallet must be untouched")
    }

    @Test
    fun `declining everything leaves the device unconfirmed`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", emptySet())

        assertFalse(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
        assertFalse(TrustedDevices.byFingerprint("aabbccdd")!!.confirmed)
    }

    @Test
    fun `a reply replaces the provisional row rather than adding a second`() {
        // The offering side stores a row keyed on the address before it knows their fingerprint. When the
        // answer arrives with a real identity, there must be ONE row -- two would show trust that cannot
        // be fully revoked, because revoking one leaves the other.
        TrustedDevices.offer("10.8.0.9", "Their Desktop", "windows", setOf(TrustedDevices.Kind.MESSAGES))
        assertEquals(1, TrustedDevices.all().size)
        assertFalse(TrustedDevices.all()[0].confirmed)

        val reply = replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(TrustedDevices.onReply("10.8.0.9", reply))

        val devices = TrustedDevices.all()
        assertEquals(1, devices.size, "the provisional row should have been replaced, not duplicated")
        assertEquals("99887766", devices[0].fingerprint)
        assertTrue(devices[0].confirmed)
        assertTrue(TrustedDevices.Kind.MESSAGES in devices[0].outgoing)
    }

    @Test
    fun `a reply cannot grant a kind that was never offered`() {
        TrustedDevices.offer("10.8.0.9", "Their Desktop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD))
        // They claim to accept messages, which we never offered. Trusting that would let the RECEIVER
        // decide what the sender sends.
        val reply = replyProving(
            "10.8.0.9", "99887766",
            listOf(TrustedDevices.Kind.MESSAGES, TrustedDevices.Kind.CLIPBOARD),
        )
        TrustedDevices.onReply("10.8.0.9", reply)

        val device = assertNotNull(TrustedDevices.byFingerprint("99887766"))
        assertEquals(setOf(TrustedDevices.Kind.CLIPBOARD), device.outgoing)
    }

    @Test
    fun `a repeat offer from an already-trusted device does not prompt again`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES))

        // A reconnect re-sends the offer. Prompting every time would train the user to dismiss it.
        assertTrue(TrustedDevices.onOffer("10.8.0.7", offerPayload()))
        assertTrue(TrustedDevices.pendingOffers().isEmpty())
        assertTrue(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
    }

    @Test
    fun `a repeat offer asking for MORE does prompt`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload(kinds = listOf(TrustedDevices.Kind.CLIPBOARD)))
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.CLIPBOARD))

        // Now they want texts too. That is a new question and has to be asked.
        TrustedDevices.onOffer(
            "10.8.0.7",
            offerPayload(kinds = listOf(TrustedDevices.Kind.CLIPBOARD, TrustedDevices.Kind.MESSAGES)),
        )
        assertEquals(1, TrustedDevices.pendingOffers().size)
        assertFalse(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
    }

    // ── Authorisation on receive ───────────────────────────────────────────

    @Test
    fun `a share from an untrusted address is dropped`() {
        var consumed = false
        TrustedDevices.consume(TrustedDevices.Kind.CLIPBOARD) { consumed = true }

        val sealed = sealShare(TrustedDevices.Kind.CLIPBOARD, "secret")
        assertFalse(TrustedDevices.onShare("10.8.0.99", sealed))
        assertFalse(consumed, "a payload from a device we never trusted must not reach a consumer")
    }

    @Test
    fun `a share of a kind that was not accepted is dropped`() {
        TrustedDevices.onOffer(
            "10.8.0.7",
            offerPayload(kinds = listOf(TrustedDevices.Kind.CLIPBOARD, TrustedDevices.Kind.MESSAGES)),
        )
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.CLIPBOARD))

        var gotMessages = false
        var gotClipboard = false
        TrustedDevices.consume(TrustedDevices.Kind.MESSAGES) { gotMessages = true }
        TrustedDevices.consume(TrustedDevices.Kind.CLIPBOARD) { gotClipboard = true }

        assertFalse(TrustedDevices.onShare("10.8.0.7", sealShare(TrustedDevices.Kind.MESSAGES, "texts")))
        assertFalse(gotMessages, "the sender offered it, we declined it, it must not be stored")

        assertTrue(TrustedDevices.onShare("10.8.0.7", sealShare(TrustedDevices.Kind.CLIPBOARD, "copied")))
        assertTrue(gotClipboard)
    }

    @Test
    fun `a share that will not decrypt is dropped rather than half-read`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES))

        var consumed = false
        TrustedDevices.consume(TrustedDevices.Kind.MESSAGES) { consumed = true }

        val sealed = sealShare(TrustedDevices.Kind.MESSAGES, "from the other wallet")
        // The far device turns out to hold a different recovery phrase. Policy said yes; capability says no.
        WalletVault.wipe()
        WalletVault.installCipher(PassThroughCipher)
        WalletVault.import(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))

        assertFalse(TrustedDevices.onShare("10.8.0.7", sealed))
        assertFalse(consumed)
    }

    @Test
    fun `a share body survives the envelope`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload(kinds = listOf(TrustedDevices.Kind.BROWSER_HISTORY)))
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.BROWSER_HISTORY))

        var received: TrustedDevices.Received? = null
        TrustedDevices.consume(TrustedDevices.Kind.BROWSER_HISTORY) { received = it }

        val body = JSONObject().apply {
            put("url", "https://example.com/a?b=c&d=e")
            put("title", "A page with a comma, an apostrophe and emoji 🔗")
        }
        assertTrue(TrustedDevices.onShare("10.8.0.7", sealShare(TrustedDevices.Kind.BROWSER_HISTORY, body)))

        val got = assertNotNull(received)
        assertEquals("https://example.com/a?b=c&d=e", got.body.optString("url"))
        assertTrue(got.body.optString("title").contains("emoji"))
    }

    // ── Revocation and recipients ──────────────────────────────────────────

    @Test
    fun `revoking removes the device and stops accepting from it`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))

        // Must work with the other device off the network -- that is exactly when a user wants to revoke
        // a lost phone.
        TrustedDevices.revoke("aabbccdd")

        assertNull(TrustedDevices.byFingerprint("aabbccdd"))
        assertFalse(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
    }

    @Test
    fun `recipients only include confirmed devices for that kind`() {
        TrustedDevices.offer("10.8.0.1", "Unconfirmed", "android", setOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(
            TrustedDevices.recipientsFor(TrustedDevices.Kind.MESSAGES).isEmpty(),
            "an unanswered offer is not a recipient -- nothing may be sent until they accept",
        )

        TrustedDevices.onReply(
            "10.8.0.1",
            replyProving("10.8.0.1", "1111", listOf(TrustedDevices.Kind.MESSAGES)),
        )
        assertEquals(1, TrustedDevices.recipientsFor(TrustedDevices.Kind.MESSAGES).size)
        assertTrue(TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD).isEmpty())
    }

    @Test
    fun `sharing with no wallet refuses rather than sending plaintext`() {
        TrustedDevices.offer("10.8.0.1", "Someone", "android", setOf(TrustedDevices.Kind.CLIPBOARD))
        TrustedDevices.onReply(
            "10.8.0.1",
            JSONObject().apply {
                put("fp", "1111")
                put("accepted", com.prism.core.json.JSONArray().also { it.put("clipboard") })
            }.toString(),
        )
        WalletVault.wipe()
        assertEquals(
            0,
            TrustedDevices.share(TrustedDevices.Kind.CLIPBOARD, JSONObject().apply { put("text", "x") }),
            "with no key it must send to nobody",
        )
    }

    @Test
    fun `the list survives a reload`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES))

        // Re-reading from disk, which is what a restart does.
        TrustedDevices.install(scratch)
        val device = assertNotNull(TrustedDevices.byFingerprint("aabbccdd"))
        assertTrue(device.confirmed)
        assertEquals(setOf(TrustedDevices.Kind.MESSAGES), device.incoming)
    }

    /** Builds what [TrustedDevices.share] puts on the wire, so onShare is tested against the real thing. */
    private fun sealShare(kind: TrustedDevices.Kind, body: Any): String {
        val key = assertNotNull(CloudVault.keyFor(CloudVault.Purpose.MESSAGING))
        val envelope = JSONObject().apply {
            put("kind", kind.id)
            put("from", "aabbccdd")
            put("name", "Their Phone")
            put("at", System.currentTimeMillis())
            put("body", if (body is JSONObject) body else JSONObject().apply { put("text", body) })
        }
        return java.util.Base64.getEncoder()
            .encodeToString(CloudVault.seal(envelope.toString().toByteArray(), key))
    }
}
