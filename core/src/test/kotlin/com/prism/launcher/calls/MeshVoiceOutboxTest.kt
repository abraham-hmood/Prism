package com.prism.launcher.calls

import com.prism.core.JvmHost
import com.prism.core.MeshMembership
import com.prism.core.MeshTransport
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.WalletCipher
import com.prism.launcher.wallet.WalletVault
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A voicemail left for a device that is away is HELD, not fired into the dark.
 *
 * ## What would be silent if this were wrong
 *
 * The sender's own list would still show an outgoing voicemail, so nothing would look broken -- the
 * message would simply never arrive. That is the failure this guards against: the recording must
 * stay on disk while the target is offline, go out when it is reachable, and only be removed by a
 * receipt from the right device. The receipt check matters because a trusted device that knows an
 * id must not be able to clear somebody else's delivery.
 */
class MeshVoiceOutboxTest {

    private object PassThroughCipher : WalletCipher {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String? = ciphertext
    }

    private data class Wire(val ip: String, val opcode: Byte, val payload: String)

    private lateinit var previousHost: PlatformHost
    private lateinit var previousSend: (String, Byte, String) -> Unit
    private lateinit var previousPeerCheck: (String) -> Boolean
    private lateinit var scratch: File

    private val wire = Collections.synchronizedList(ArrayList<Wire>())

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        previousSend = MeshTransport.sendToPeer
        previousPeerCheck = TrustedDevices.peerCheck

        scratch = File(System.getProperty("java.io.tmpdir"), "prism-meshvoice-${System.nanoTime()}")
        scratch.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = scratch)

        WalletVault.installCipher(PassThroughCipher)
        val phrase = Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS)
        WalletVault.import(phrase.joinToString(" "))
        assertTrue(CloudVault.isReady(), "the fixture wallet must be usable")

        MeshMembership.addressesOverride = { listOf(PEER_IP) }
        TrustedDevices.install(File(scratch, "trust"))
        TrustedDevices.localName = { "Sender" }
        TrustedDevices.localPlatform = "test"
        TrustedDevices.sender = { _, _, _ -> Unit }
        TrustedDevices.peerCheck = { false }

        wire.clear()
        MeshTransport.sendToPeer = { ip, opcode, payload -> wire += Wire(ip, opcode, payload) }

        val offer = JSONObject().apply {
            put("fp", PEER_FP)
            put("name", "Their Phone")
            put("platform", "android")
            put("kinds", JSONArray().also { it.put(TrustedDevices.Kind.MESSAGES.id) })
        }
        assertTrue(
            TrustedDevices.onOffer(PEER_IP, offer.toString()),
            "the fixture peer could not be trusted",
        )
        assertTrue(
            TrustedDevices.respondToOffer(PEER_FP, setOf(TrustedDevices.Kind.MESSAGES)),
            "the fixture peer could not be answered",
        )
        // Pairing completes on the confirmation from the other device, not on the answer above: the
        // accepting side cannot know whether its proof opened. See TrustedDevices.onAck.
        assertTrue(
            TrustedDevices.onAck(
                PEER_IP,
                JSONObject().apply {
                    put("fp", PEER_FP)
                    put("name", "Their Phone")
                    put("platform", "android")
                    put("accepted", JSONArray().also { it.put(TrustedDevices.Kind.MESSAGES.id) })
                }.toString(),
            ),
            "the fixture peer could not be confirmed",
        )
    }

    @AfterTest
    fun tearDown() {
        MeshCalls.MeshVoice.stopOutbox()
        MeshTransport.sendToPeer = previousSend
        TrustedDevices.peerCheck = previousPeerCheck
        MeshMembership.addressesOverride = null
        WalletVault.wipe()
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `a voicemail for an away device is held and survives until it can be sent`() {
        MeshCalls.MeshVoice.send(PEER_IP, MeshCalls.wav(ByteArray(3_200)), "Sender")

        val outgoing = assertNotNull(
            MeshCalls.MeshVoice.all().firstOrNull { it.outgoing },
            "the sender's own copy should exist immediately",
        )
        assertTrue(MeshCalls.MeshVoice.isPending(outgoing.id), "the voicemail was not held")

        // The target comes back. One retry pass is enough to put it on the wire; no receipt has
        // arrived yet, so it must still be held.
        TrustedDevices.peerCheck = { true }
        val before = wire.size
        assertTrue(
            MeshCalls.MeshVoice.deliverHeldNow() > 0,
            "the retry pass did not deliver the held voicemail",
        )
        assertTrue(wire.size > before, "nothing was sent after the device came back")
        assertTrue(
            MeshCalls.MeshVoice.isPending(outgoing.id),
            "it must stay held until the far side confirms, or a lost packet becomes a lost message",
        )
    }

    @Test
    fun `a receipt from the recipient releases the held copy`() {
        MeshCalls.MeshVoice.send(PEER_IP, MeshCalls.wav(ByteArray(3_200)), "Sender")
        val outgoing = assertNotNull(MeshCalls.MeshVoice.all().firstOrNull { it.outgoing })
        assertTrue(MeshCalls.MeshVoice.isPending(outgoing.id))

        val ack = JSONObject().apply {
            put("t", "voice_ack")
            put("id", outgoing.id)
            put("fp", PEER_FP)
        }
        MeshCalls.MeshVoice.accept(PEER_IP, ack.toString())

        assertFalse(
            MeshCalls.MeshVoice.isPending(outgoing.id),
            "a valid receipt must release the held files",
        )
        // The sender's copy is history, not the outbox: it remains in the list.
        assertTrue(MeshCalls.MeshVoice.all().any { it.id == outgoing.id && it.outgoing })
    }

    @Test
    fun `a receipt naming the wrong device does not release the voicemail`() {
        MeshCalls.MeshVoice.send(PEER_IP, MeshCalls.wav(ByteArray(3_200)), "Sender")
        val outgoing = assertNotNull(MeshCalls.MeshVoice.all().firstOrNull { it.outgoing })

        val forged = JSONObject().apply {
            put("t", "voice_ack")
            put("id", outgoing.id)
            put("fp", "not-the-device")
        }
        MeshCalls.MeshVoice.accept(PEER_IP, forged.toString())

        assertTrue(
            MeshCalls.MeshVoice.isPending(outgoing.id),
            "a receipt that does not match the recipient must leave the delivery held",
        )
    }

    private companion object {
        const val PEER_IP = "10.8.0.7"
        const val PEER_FP = "aabbccdd"
    }
}
