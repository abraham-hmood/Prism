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
        val offerId = JSONObject(offer.payload).optString("id")
        val typed = code ?: TrustedDevices.displayedCode(peerIp)!!
        val key = PairingCode.keyFor(typed, salt)!!
        return JSONObject().apply {
            put("fp", fingerprint)
            put("name", "Their Device")
            put("platform", "windows")
            put("accepted", com.prism.core.json.JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
            if (offerId.isNotBlank()) put("replyTo", offerId)
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

    /**
     * Completes the RECEIVING side of a pairing the way the offering device does.
     *
     * Two steps, deliberately: answering the offer proves what this device typed, and only the
     * confirmation that comes back from the other device makes it trusted. A test that wrote the
     * trust row after the first step would be testing the vulnerability this exists to close.
     */
    private fun acceptOffer(
        fingerprint: String = "aabbccdd",
        peerIp: String = "10.8.0.7",
        accepted: Set<TrustedDevices.Kind> = setOf(TrustedDevices.Kind.MESSAGES),
        code: String? = null,
    ) {
        assertTrue(
            TrustedDevices.respondToOffer(fingerprint, accepted, code),
            "the answer should be accepted for sending",
        )
        val ack = JSONObject().apply {
            put("fp", fingerprint)
            put("name", "Their Phone")
            put("platform", "android")
            put("accepted", com.prism.core.json.JSONArray().also { a ->
                accepted.forEach { a.put(it.id) }
            })
        }.toString()
        assertTrue(
            TrustedDevices.onAck(peerIp, ack),
            "the offering device's confirmation should complete the pairing",
        )
    }

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
    fun `the machine name is a label, not part of the identity`() {
        // The name used to be hashed with the wallet namespace, so renaming a machine -- or a
        // hostname that resolved differently on another network -- moved its identity and orphaned
        // every pairing it had. It is pinned now; the name is what the other side displays.
        val mine = TrustedDevices.localFingerprint()
        TrustedDevices.localName = { "Another Machine" }
        assertEquals(mine, TrustedDevices.localFingerprint(), "renaming must not orphan a pairing")
    }

    @Test
    fun `importing a wallet does not change this device's fingerprint`() {
        val before = TrustedDevices.localFingerprint()

        // THE BUG THIS EXISTS FOR. A pairing can hand a wallet to the device that answered, and the
        // offering device writes that answerer's fingerprint down before the handoff. When the
        // identity was derived from the wallet namespace, the import changed it underneath a trust
        // record that already existed -- the phone said "trusted", the desktop said "trusted", and
        // every session between them was refused. The identity is pinned now.
        WalletVault.wipe()
        WalletVault.import(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))
        assertTrue(CloudVault.isReady(), "the replacement wallet has to be usable for this to mean anything")

        assertEquals(
            before,
            TrustedDevices.localFingerprint(),
            "a wallet appearing must not invalidate what the other device already stored",
        )
    }

    @Test
    fun `the pre-wallet fingerprint is still derivable from the stored name`() {
        // What a device was called before it had a wallet. It is matched against, never trusted: a
        // session still has to prove the pair secret.
        val legacy = TrustedDevices.legacyFingerprintOf("Their Phone")
        assertEquals(32, legacy.length)
        assertEquals(legacy, TrustedDevices.legacyFingerprintOf("Their Phone"), "it has to be deterministic")
        assertFalse(
            legacy == TrustedDevices.legacyFingerprintOf("Another Phone"),
            "two devices must not collapse into one record",
        )
    }

    // ── Offer and reply ────────────────────────────────────────────────────

    @Test
    fun `a resent reply after a completed pairing is answered with the confirmation again`() {
        val ip = "10.8.0.1"
        TrustedDevices.offer(ip, "Their Phone", "android", setOf(TrustedDevices.Kind.MESSAGES))
        val reply = replyProving(ip, "1111", listOf(TrustedDevices.Kind.MESSAGES))

        assertTrue(TrustedDevices.onReply(ip, reply))
        assertNotNull(TrustedDevices.byFingerprint("1111"))

        // The answering device resends when it hears no confirmation. That repeat is the only way
        // it can recover a lost packet, so it has to be answered rather than logged as unsolicited.
        sent.clear()
        assertTrue(TrustedDevices.onReply(ip, reply), "a repeat must not be refused")
        assertTrue(
            sent.any { it.opcode == TrustedDevices.OPCODE_TRUST_ACK },
            "the confirmation it missed should be sent again",
        )
    }

    @Test
    fun `a late answer to an earlier offer cannot cancel a new one`() {
        val ip = "10.8.0.1"
        TrustedDevices.offer(ip, "Their Phone", "android", setOf(TrustedDevices.Kind.MESSAGES))
        val stale = replyProving(ip, "1111", listOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(TrustedDevices.onReply(ip, stale))
        assertNotNull(TrustedDevices.byFingerprint("1111"))

        // Pairing the same device again, with the answer to the FIRST offer still in flight. The
        // answering side retries, so this is not hypothetical -- and a proof for the old code would
        // fail against the new one and cancel the pairing the user is doing.
        assertNotNull(TrustedDevices.offer(ip, "Their Phone", "android", setOf(TrustedDevices.Kind.MESSAGES)))
        assertFalse(
            TrustedDevices.onReply(ip, stale),
            "an answer addressed to an earlier offer is not an answer to this one",
        )
        assertTrue(TrustedDevices.isAwaitingCode(ip), "the new code has to stay outstanding")

        assertTrue(
            TrustedDevices.onReply(ip, replyProving(ip, "1111", listOf(TrustedDevices.Kind.MESSAGES))),
            "the answer to the offer actually on screen must still work",
        )
    }

    @Test
    fun `a repeated confirmation is accepted without a second pairing`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        acceptOffer()

        val ack = JSONObject().apply {
            put("fp", "aabbccdd")
            put("name", "Their Phone")
            put("platform", "android")
            put("accepted", com.prism.core.json.JSONArray().also { a -> a.put(TrustedDevices.Kind.MESSAGES.id) })
        }.toString()
        assertTrue(
            TrustedDevices.onAck("10.8.0.7", ack),
            "the confirmation is sent twice, so its repeat has to be a quiet success",
        )
        assertNotNull(TrustedDevices.byFingerprint("aabbccdd"))
    }

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
        acceptOffer(accepted = setOf(TrustedDevices.Kind.CLIPBOARD))

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
        acceptOffer(accepted = setOf(TrustedDevices.Kind.CLIPBOARD))

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
    fun `an offer from a build with no code can be answered but is never trusted blindly`() {
        // A phone that has not been updated sends no salt. It can still be answered, but an answer
        // with nothing to prove cannot be confirmed by the far side either -- so it pairs nothing.
        // That is the correct outcome: no code means no proof, and no proof means no trust.
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        assertFalse(TrustedDevices.pendingOffers().first().needsCode)
        assertTrue(TrustedDevices.respondToOffer("aabbccdd", setOf(TrustedDevices.Kind.MESSAGES)))
        assertNull(
            TrustedDevices.byFingerprint("aabbccdd"),
            "an offer nobody can confirm must not become a trusted device",
        )
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
    fun `declining everything leaves no device at all`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        TrustedDevices.respondToOffer("aabbccdd", emptySet())

        assertFalse(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
        assertNull(
            TrustedDevices.byFingerprint("aabbccdd"),
            "a decline is not a half-pairing: nothing may be written for a question answered No",
        )
        assertTrue(TrustedDevices.all().isEmpty())
    }

    @Test
    fun `answering an offer does not trust it until the far side confirms`() {
        // THE SECURITY PROPERTY THE WHOLE PROTOCOL EXISTS FOR. Accepting proves what this device
        // typed; the other device is the only one that can say the proof opened. A row written at
        // Accept would put a device in the list whose code was never checked.
        TrustedDevices.onOffer(
            "10.8.0.9",
            offerPayload(
                fingerprint = "11223344",
                kinds = listOf(TrustedDevices.Kind.CLIPBOARD),
                salt = PairingCode.salt(),
            ),
        )
        assertTrue(
            TrustedDevices.respondToOffer(
                "11223344",
                setOf(TrustedDevices.Kind.CLIPBOARD),
                code = "whatever-is-shown-there",
            ),
        )
        assertNull(
            TrustedDevices.byFingerprint("11223344"),
            "accepted but unconfirmed is not trusted, and must not appear in the list",
        )
        assertTrue(TrustedDevices.all().isEmpty())

        assertTrue(
            TrustedDevices.onAck(
                "10.8.0.9",
                replyPayload("11223344", listOf(TrustedDevices.Kind.CLIPBOARD)),
            ),
            "the offering device's confirmation is what finishes the pairing",
        )
        assertTrue(TrustedDevices.byFingerprint("11223344")!!.confirmed)
        assertTrue(TrustedDevices.accepts("10.8.0.9", TrustedDevices.Kind.CLIPBOARD))
    }

    @Test
    fun `a confirmation from anywhere else is refused`() {
        TrustedDevices.onOffer(
            "10.8.0.9",
            offerPayload(
                fingerprint = "11223344",
                kinds = listOf(TrustedDevices.Kind.CLIPBOARD),
                salt = PairingCode.salt(),
            ),
        )
        TrustedDevices.respondToOffer(
            "11223344",
            setOf(TrustedDevices.Kind.CLIPBOARD),
            code = "whatever-is-shown-there",
        )

        // Right fingerprint, wrong machine: a mesh peer must not be able to finish somebody else's
        // pairing by echoing its identity back.
        assertFalse(
            TrustedDevices.onAck(
                "10.8.0.8",
                replyPayload("11223344", listOf(TrustedDevices.Kind.CLIPBOARD)),
            ),
        )
        assertNull(TrustedDevices.byFingerprint("11223344"))
    }

    @Test
    fun `a confirmation cannot widen what was accepted`() {
        TrustedDevices.onOffer(
            "10.8.0.9",
            offerPayload(
                fingerprint = "11223344",
                kinds = listOf(TrustedDevices.Kind.CLIPBOARD, TrustedDevices.Kind.MESSAGES),
                salt = PairingCode.salt(),
            ),
        )
        TrustedDevices.respondToOffer(
            "11223344",
            setOf(TrustedDevices.Kind.CLIPBOARD),
            code = "whatever-is-shown-there",
        )
        TrustedDevices.onAck(
            "10.8.0.9",
            replyPayload(
                "11223344",
                listOf(TrustedDevices.Kind.CLIPBOARD, TrustedDevices.Kind.MESSAGES),
            ),
        )

        val device = assertNotNull(TrustedDevices.byFingerprint("11223344"))
        assertEquals(
            setOf(TrustedDevices.Kind.CLIPBOARD), device.incoming,
            "a confirmation is a yes to what was asked, not a fresh offer of its own",
        )
    }

    @Test
    fun `dismissing the code cancels the pairing`() {
        TrustedDevices.offer("10.8.0.9", "Laptop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD))
        val proof = replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.CLIPBOARD))

        // The user closed the dialog showing the code. A proof typed on the other device afterwards
        // must not pair anything, or Done would mean nothing beyond hiding the words.
        TrustedDevices.cancelOffer("10.8.0.9")
        assertFalse(TrustedDevices.onReply("10.8.0.9", proof))
        assertNull(TrustedDevices.byFingerprint("99887766"))
        assertTrue(TrustedDevices.all().isEmpty())
    }

    @Test
    fun `offering writes no row, and the answer adds exactly one`() {
        // The offering side used to store a provisional row keyed on the address before it knew the
        // other device's fingerprint. That row was the bug: a device appeared in the trusted list the
        // moment an offer left this machine, before anybody had typed anything anywhere.
        TrustedDevices.offer("10.8.0.9", "Their Desktop", "windows", setOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(
            TrustedDevices.all().isEmpty(),
            "an outstanding offer is in memory only -- it is not a trust record",
        )

        val reply = replyProving("10.8.0.9", "99887766", listOf(TrustedDevices.Kind.MESSAGES))
        assertTrue(TrustedDevices.onReply("10.8.0.9", reply))

        val devices = TrustedDevices.all()
        assertEquals(1, devices.size, "the proved reply adds one row, not two")
        assertEquals("99887766", devices[0].fingerprint)
        assertTrue(devices[0].confirmed)
        assertTrue(TrustedDevices.Kind.MESSAGES in devices[0].outgoing)
    }

    @Test
    fun `a confirmation without an acceptance in progress is dropped`() {
        // A packet that arrives on its own must not be able to grant itself anything, which is what
        // made every other check decorative when a reply could create a device directly.
        assertFalse(
            TrustedDevices.onAck(
                "10.8.0.9",
                replyPayload("99887766", listOf(TrustedDevices.Kind.MESSAGES)),
            ),
        )
        assertNull(TrustedDevices.byFingerprint("99887766"))
        assertTrue(TrustedDevices.all().isEmpty())
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
        acceptOffer()

        // A reconnect re-sends the offer. Prompting every time would train the user to dismiss it.
        assertTrue(TrustedDevices.onOffer("10.8.0.7", offerPayload()))
        assertTrue(TrustedDevices.pendingOffers().isEmpty())
        assertTrue(TrustedDevices.accepts("10.8.0.7", TrustedDevices.Kind.MESSAGES))
    }

    @Test
    fun `a repeat offer asking for MORE does prompt`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload(kinds = listOf(TrustedDevices.Kind.CLIPBOARD)))
        acceptOffer(accepted = setOf(TrustedDevices.Kind.CLIPBOARD))

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
        acceptOffer(accepted = setOf(TrustedDevices.Kind.CLIPBOARD))

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
        acceptOffer()

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
        acceptOffer(accepted = setOf(TrustedDevices.Kind.BROWSER_HISTORY))

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
        acceptOffer()
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
            replyProving("10.8.0.1", "1111", listOf(TrustedDevices.Kind.CLIPBOARD)),
        )
        assertEquals(1, TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD).size)
        WalletVault.wipe()
        assertEquals(
            0,
            TrustedDevices.share(TrustedDevices.Kind.CLIPBOARD, JSONObject().apply { put("text", "x") }),
            "with no key it must send to nobody",
        )
    }

    @Test
    fun `rows left by the old pre-verification flow are ignored and cleaned`() {
        // The build before this wrote a row as soon as an offer was SENT, keyed on the address with a
        // "pending:" prefix. Those rows can never be completed now -- nothing is trusted under an
        // address -- so they must not keep appearing as a device that is permanently half-paired.
        val file = File(scratch, "trusted-devices.json")
        file.writeText(
            "[{\"fp\":\"pending:10.8.0.9\",\"name\":\"Half paired\"," +
                "\"platform\":\"windows\",\"ip\":\"10.8.0.9\",\"seen\":1," +
                "\"out\":[\"messages\"],\"in\":[\"messages\"],\"confirmed\":false}," +
                "{\"fp\":\"cafebabe\",\"name\":\"Real\",\"platform\":\"android\"," +
                "\"ip\":\"10.8.0.7\",\"seen\":2,\"out\":[\"messages\"]," +
                "\"in\":[\"messages\"],\"confirmed\":true}]",
        )
        TrustedDevices.install(scratch)
        assertEquals(
            listOf("cafebabe"),
            TrustedDevices.all().map { it.fingerprint },
            "only the confirmed pairing is a device",
        )

        // And a write drops it, so the file does not keep carrying a row nothing can act on.
        TrustedDevices.revoke("cafebabe")
        assertFalse(file.readText().contains("pending:"))
    }

    @Test
    fun `the list survives a reload`() {
        TrustedDevices.onOffer("10.8.0.7", offerPayload())
        acceptOffer()

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

    // ── Identity drift ────────────────────────────────────────────────────

    /** One row in the shape [TrustedDevices] writes, so the tests read the real format. */
    private fun record(
        fingerprint: String,
        name: String = "Their Phone",
        ip: String = "10.8.0.7",
        outgoing: List<String> = listOf("messages"),
        aliases: List<String> = emptyList(),
    ) = "{\"fp\":\"$fingerprint\",\"name\":\"$name\",\"platform\":\"android\"," +
        "\"ip\":\"$ip\",\"seen\":1,\"out\":${outgoing.joinToString(",", "[\"", "\"]")}," +
        "\"in\":${outgoing.joinToString(",", "[\"", "\"]")},\"confirmed\":true," +
        "\"alias\":${aliases.joinToString(",", "[\"", "\"]")}}"

    private fun writeDevices(vararg rows: String) =
        File(scratch, "trusted-devices.json").writeText(rows.joinToString(",", "[", "]"))

    @Test
    fun `a record still answers to the identity it moved from`() {
        writeDevices(record("deadbeef"))
        TrustedDevices.rekey("deadbeef", "cafebabe")

        assertEquals(1, TrustedDevices.all().size, "a move must not add a row")
        assertEquals("cafebabe", TrustedDevices.all().single().fingerprint)
        assertEquals(
            setOf(TrustedDevices.Kind.MESSAGES),
            assertNotNull(TrustedDevices.byFingerprint("cafebabe")).outgoing,
            "a move keeps what was agreed",
        )
        assertNotNull(
            TrustedDevices.byFingerprint("deadbeef"),
            "the retired value has to keep finding the record, or the other side's copy is orphaned",
        )
    }

    @Test
    fun `rekey merges two rows for one device instead of leaving a ghost`() {
        // The shape after a half-finished migration: one row under the old identity and one under
        // the new. Refusing to merge left a record that could never authenticate again.
        writeDevices(
            record("deadbeef", outgoing = listOf("clipboard")),
            record("cafebabe", outgoing = listOf("messages")),
        )
        TrustedDevices.rekey("deadbeef", "cafebabe")

        val device = assertNotNull(TrustedDevices.byFingerprint("cafebabe"))
        assertEquals(1, TrustedDevices.all().size, "one physical device, one row")
        assertEquals(
            setOf(TrustedDevices.Kind.CLIPBOARD, TrustedDevices.Kind.MESSAGES),
            device.outgoing,
            "both rows describe the same device, so the agreement is the union",
        )
        assertNotNull(TrustedDevices.byFingerprint("deadbeef"))
    }

    @Test
    fun `a device is found by address however the socket spells it`() {
        writeDevices(record("cafebabe", ip = "10.8.0.7"))

        assertNotNull(TrustedDevices.byIp("10.8.0.7"))
        assertNotNull(
            TrustedDevices.byIp("::ffff:10.8.0.7"),
            "the same peer arrives mapped on a dual-stack socket",
        )
        assertNotNull(TrustedDevices.byIp("10.8.0.7%wlan0"), "and with a zone on Android")
        assertNull(TrustedDevices.byIp("10.8.0.8"), "a different address is a different device")
    }

    @Test
    fun `installing the same store twice keeps a pairing in progress`() {
        // The trusted-devices screen calls install() in onCreate. That used to wipe the offer the
        // user was in the middle of answering, which then looked like a pairing that had been
        // added but never became trusted.
        TrustedDevices.onOffer("10.8.0.9", offerPayload(fingerprint = "11223344"))
        assertEquals(1, TrustedDevices.pendingOffers().size, "the offer should be waiting")

        TrustedDevices.install(scratch)
        assertEquals(
            1,
            TrustedDevices.pendingOffers().size,
            "opening the list is not a restart and must not discard the offer",
        )
    }

    @Test
    fun `a record whose identity moved is still offered by name or address`() {
        writeDevices(record("deadbeef", name = "Their Phone", ip = "10.8.0.7"))

        assertNotNull(
            TrustedDevices.candidateFor("10.8.0.9", "Their Phone"),
            "a name that matches is enough to OFFER a phrase; the phrase is still the proof",
        )
        assertNotNull(TrustedDevices.candidateFor("10.8.0.7", "Something Else"))
        assertNull(TrustedDevices.candidateFor("10.8.0.9", "Someone Else"))
    }
}
