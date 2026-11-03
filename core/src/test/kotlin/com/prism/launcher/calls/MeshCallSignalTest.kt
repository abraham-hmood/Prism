package com.prism.launcher.calls

import com.prism.core.JvmHost
import com.prism.core.MeshTransport
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call signalling that decides whether a mesh call ever connects.
 *
 * ## What would be silent if this were wrong
 *
 * Every failure here looks the same from the outside: the caller hears ringing and the other device
 * shows nothing, or shows a call that never connects. That is what "phone-to-desktop calls do not
 * show up" was, and each test below pins one of the reasons it happened:
 *
 * - an untrusted call could never be answered, because the answer was sealed with a key the caller
 *   did not have (or not sent at all without a wallet);
 * - the invitation was one datagram, and a repeat of it was refused as "busy";
 * - refusals and hang-ups notified a slot nothing listened to;
 * - a late `end` from a previous call could end the next one.
 *
 * Untrusted peers are used throughout: their signals are plain JSON, so the wire can be read
 * directly and no wallet is needed.
 */
class MeshCallSignalTest {

    private data class Wire(val ip: String, val opcode: Byte, val payload: String) {
        val json: JSONObject? get() = runCatching { JSONObject(payload) }.getOrNull()
    }

    /** An audio device that opens and does nothing, so the engine's state machine can run. */
    private object SilentAudio : MeshCalls.Audio {
        override fun startCapture(onFrame: (ByteArray) -> Unit): Boolean = true
        override fun startPlayback(): Boolean = true
        override fun write(frame: ByteArray) = Unit
        override fun stopPlayback() = Unit
        override fun stopCapture() = Unit
    }

    private lateinit var previousHost: PlatformHost
    private lateinit var previousSend: (String, Byte, String) -> Unit
    private lateinit var previousOnMesh: () -> Boolean
    private lateinit var previousPeerCount: () -> Int
    private var previousAudio: MeshCalls.Audio? = null
    private lateinit var scratch: File

    private val wire = Collections.synchronizedList(ArrayList<Wire>())
    private val states = CopyOnWriteArrayList<MeshCalls.Call?>()
    private val stateListener: (MeshCalls.Call?) -> Unit = { states += it }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        previousSend = MeshTransport.sendToPeer
        previousOnMesh = MeshTransport.isOnMesh
        previousPeerCount = MeshTransport.peerCount
        previousAudio = MeshCalls.audio

        scratch = File(System.getProperty("java.io.tmpdir"), "prism-meshcall-${System.nanoTime()}")
        scratch.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        TrustedDevices.install(File(scratch, "trust"))
        TrustedDevices.localName = { "Caller" }

        wire.clear()
        states.clear()
        MeshTransport.sendToPeer = { ip, opcode, payload -> wire += Wire(ip, opcode, payload) }
        MeshTransport.isOnMesh = { true }
        MeshTransport.peerCount = { 3 }
        MeshCalls.audio = SilentAudio
        MeshCalls.addStateListener(stateListener)
    }

    @AfterTest
    fun tearDown() {
        MeshCalls.removeStateListener(stateListener)
        MeshCalls.end(notify = false)
        MeshGroupCalls.leave()
        MeshVideo.reset()
        MeshCalls.audio = previousAudio
        MeshTransport.sendToPeer = previousSend
        MeshTransport.isOnMesh = previousOnMesh
        MeshTransport.peerCount = previousPeerCount
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun signals(type: String, to: String? = null): List<JSONObject> = synchronized(wire) {
        wire.filter { it.opcode == MeshCalls.OPCODE_SIGNAL && (to == null || it.ip == to) }
            .mapNotNull { it.json }
            .filter { it.optString("t") == type }
    }

    private fun invite(id: String, video: Boolean = false) = JSONObject().apply {
        put("t", "invite")
        put("id", id)
        put("name", "Their Phone")
        put("secure", false)
        put("video", video)
    }.toString()

    // ── One-to-one ─────────────────────────────────────────────────────────

    @Test
    fun `an untrusted call can be answered, in clear, with the call id`() {
        MeshCalls.receiveSignal(PEER, invite("call-1"))
        val ringing = assertNotNull(MeshCalls.active, "the invitation did not ring")
        assertEquals(MeshCalls.State.RINGING, ringing.state)

        MeshCalls.accept()

        val answer = assertNotNull(
            signals("answer", to = PEER).singleOrNull(),
            "answering an untrusted call sent no plain answer -- the caller would ring forever",
        )
        assertEquals("call-1", answer.optString("id"))
        assertEquals(MeshCalls.State.ACTIVE, MeshCalls.active?.state)
    }

    @Test
    fun `a repeated invitation keeps ringing instead of being refused as busy`() {
        MeshCalls.receiveSignal(PEER, invite("call-2"))
        MeshCalls.receiveSignal(PEER, invite("call-2"))
        MeshCalls.receiveSignal(PEER, invite("call-2"))

        assertTrue(signals("busy").isEmpty(), "a repeat of the ringing call was answered with busy")
        assertEquals("call-2", MeshCalls.active?.id)
    }

    @Test
    fun `a repeat that arrives after answering re-sends the lost answer`() {
        MeshCalls.receiveSignal(PEER, invite("call-3"))
        MeshCalls.accept()
        MeshCalls.receiveSignal(PEER, invite("call-3"))

        assertEquals(2, signals("answer", to = PEER).size, "the lost answer was not repeated")
        assertTrue(signals("busy").isEmpty())
    }

    @Test
    fun `a video invitation is marked as video`() {
        MeshCalls.receiveSignal(PEER, invite("call-4", video = true))
        assertTrue(MeshCalls.active?.video == true)
    }

    @Test
    fun `the caller connects on an answer for its own call, and only its own`() {
        val call = MeshCalls.call(PEER, "Their Phone", trusted = false)
        val sent = assertNotNull(signals("invite", to = PEER).firstOrNull(), "no invitation was sent")
        assertEquals(call.id, sent.optString("id"))

        MeshCalls.receiveSignal(PEER, JSONObject().apply {
            put("t", "answer")
            put("id", "some-other-call")
        }.toString())
        assertEquals(MeshCalls.State.DIALING, call.state, "an answer to another call connected this one")

        MeshCalls.receiveSignal(PEER, JSONObject().apply {
            put("t", "answer")
            put("id", call.id)
        }.toString())
        assertEquals(MeshCalls.State.ACTIVE, call.state)
    }

    @Test
    fun `a late end from a previous call does not hang up the current one`() {
        val call = MeshCalls.call(PEER, "Their Phone", trusted = false)
        MeshCalls.receiveSignal(PEER, JSONObject().apply {
            put("t", "answer")
            put("id", call.id)
        }.toString())

        MeshCalls.receiveSignal(PEER, JSONObject().apply {
            put("t", "end")
            put("id", "the-call-before")
        }.toString())
        assertEquals(MeshCalls.State.ACTIVE, call.state, "a stale end ended the live call")

        states.clear()
        MeshCalls.receiveSignal(PEER, JSONObject().apply {
            put("t", "end")
            put("id", call.id)
        }.toString())
        assertEquals(MeshCalls.State.ENDED, call.state)
        assertTrue(
            states.any { it === call && it.state == MeshCalls.State.ENDED },
            "the far side hanging up reached no listener -- the call screen would keep counting",
        )
    }

    @Test
    fun `a refused call reaches the listeners with its reason`() {
        states.clear()
        MeshCalls.call("", "Nowhere", trusted = false)

        assertTrue(states.contains(null), "a refusal was reported to no listener")
        assertNotNull(MeshCalls.lastError)
        assertNull(MeshCalls.active)
    }

    // ── Groups ─────────────────────────────────────────────────────────────

    @Test
    fun `a group host invites each member with everyone else as the roster`() {
        val group = assertNotNull(
            MeshGroupCalls.start(
                listOf(
                    MeshGroupCalls.Target(PEER, "Phone", trusted = false),
                    MeshGroupCalls.Target(OTHER, "Desktop", trusted = false),
                ),
                video = false,
            ),
            MeshGroupCalls.lastError ?: "the group call did not start",
        )

        val toPeer = assertNotNull(signals("ginvite", to = PEER).firstOrNull())
        assertEquals(group.id, toPeer.optString("gid"))
        val roster = toPeer.optJSONArray("roster") ?: JSONArray()
        assertEquals(1, roster.length())
        assertEquals(OTHER, roster.optJSONObject(0)?.optString("ip"))

        // A join is acknowledged, and makes the member part of the call.
        MeshGroupCalls.onSignal(PEER, JSONObject().apply {
            put("t", "gjoin")
            put("gid", group.id)
            put("name", "Phone")
        }, trustedPeer = false)
        assertEquals(MeshGroupCalls.MemberState.JOINED, group.member(PEER)?.state)
        assertTrue(signals("gjoin", to = PEER).any { it.optBoolean("ack") }, "the join was not acknowledged")
    }

    @Test
    fun `an invited member rings, then joins everyone on answering`() {
        val incoming = CopyOnWriteArrayList<MeshGroupCalls.Group>()
        val listener: (MeshGroupCalls.Group) -> Unit = { incoming += it }
        MeshGroupCalls.addIncomingListener(listener)
        try {
            MeshCalls.receiveSignal(PEER, JSONObject().apply {
                put("t", "ginvite")
                put("gid", "group-1")
                put("name", "Host Phone")
                put("video", false)
                put("roster", JSONArray().put(JSONObject().apply {
                    put("ip", OTHER)
                    put("name", "Desktop")
                }))
            }.toString())

            val group = assertNotNull(incoming.singleOrNull(), "the group invitation reached no listener")
            assertEquals(MeshGroupCalls.GroupState.RINGING, group.state)
            assertEquals(2, group.members.size)

            MeshGroupCalls.accept()
            assertEquals(MeshGroupCalls.GroupState.ACTIVE, group.state)
            assertTrue(signals("gjoin", to = PEER).isNotEmpty(), "the host was not told about the join")
            assertTrue(signals("gjoin", to = OTHER).isNotEmpty(), "the other member was not told about the join")
        } finally {
            MeshGroupCalls.removeIncomingListener(listener)
        }
    }

    @Test
    fun `a one-to-one invitation during a group call is refused as busy`() {
        MeshGroupCalls.start(listOf(MeshGroupCalls.Target(OTHER, "Desktop", trusted = false)), video = false)
        MeshCalls.receiveSignal(PEER, invite("call-5"))

        assertNull(MeshCalls.active, "a second call rang over the group call")
        assertTrue(signals("busy", to = PEER).isNotEmpty())
    }

    // ── Trusted devices on different wallets ───────────────────────────────

    @Test
    fun `both ends of a pairing derive the same link key, and a wrong code derives another`() {
        val salt = ByteArray(16) { it.toByte() }
        val codeKey = ByteArray(32) { (it * 7).toByte() }
        val offerer = com.prism.launcher.trusted.PairLink.newKeyPair()
        val answerer = com.prism.launcher.trusted.PairLink.newKeyPair()

        val atOfferer = assertNotNull(
            com.prism.launcher.trusted.PairLink.derive(
                offerer, com.prism.launcher.trusted.PairLink.publicOf(answerer), codeKey, salt,
            ),
        )
        val atAnswerer = assertNotNull(
            com.prism.launcher.trusted.PairLink.derive(
                answerer, com.prism.launcher.trusted.PairLink.publicOf(offerer), codeKey, salt,
            ),
        )
        assertTrue(atOfferer.contentEquals(atAnswerer), "the two sides would not share a key")
        assertEquals(32, atOfferer.size)

        val wrongCode = com.prism.launcher.trusted.PairLink.derive(
            answerer, com.prism.launcher.trusted.PairLink.publicOf(offerer), ByteArray(32), salt,
        )
        assertTrue(wrongCode?.contentEquals(atOfferer) == false, "the code must be part of the key")
    }

    @Test
    fun `a trusted call connects on the pair's link key with no shared wallet at all`() {
        // No wallet is imported in this fixture: the only key the two devices share is the link
        // key their pairing produced. That is the state of two devices on different wallets.
        val link = ByteArray(32) { (it + 1).toByte() }
        TrustedDevices.upsert(
            TrustedDevices.Trust(
                fingerprint = "peer-fp",
                name = "Desktop",
                platform = "desktop",
                lastIp = PEER,
                lastSeen = System.currentTimeMillis(),
                outgoing = setOf(TrustedDevices.Kind.MESSAGES),
                incoming = setOf(TrustedDevices.Kind.MESSAGES),
                confirmed = true,
                linkKey = com.prism.launcher.trusted.PairLink.encode(link),
            ),
        )

        val sealedInvite = java.util.Base64.getEncoder().encodeToString(
            com.prism.launcher.cloud.CloudVault.seal(
                JSONObject().apply {
                    put("t", "invite")
                    put("id", "sealed-1")
                    put("name", "Desktop")
                    put("secure", true)
                }.toString().toByteArray(),
                link,
            ),
        )
        MeshCalls.receiveSignal(PEER, sealedInvite)

        val ringing = assertNotNull(MeshCalls.active, "a sealed invitation from a trusted device did not ring")
        assertTrue(ringing.trusted, "the call was not treated as encrypted")

        MeshCalls.accept()
        val answer = synchronized(wire) {
            wire.lastOrNull { it.ip == PEER && it.opcode == MeshCalls.OPCODE_SIGNAL }
        }
        val opened = answer?.let {
            com.prism.launcher.cloud.CloudVault.open(java.util.Base64.getDecoder().decode(it.payload), link)
        }
        assertNotNull(opened, "the answer was not sealed with the pair's link key")
        assertEquals("answer", JSONObject(String(opened)).optString("t"))
        assertEquals(MeshCalls.State.ACTIVE, MeshCalls.active?.state)
    }

    @Test
    fun `a trusted device's greeting arrives sealed on the link key, is stored and acknowledged`() {
        val link = ByteArray(32) { (it * 3 + 5).toByte() }
        TrustedDevices.upsert(
            TrustedDevices.Trust(
                fingerprint = "greeter-fp",
                name = "Desktop",
                platform = "desktop",
                lastIp = PEER,
                lastSeen = System.currentTimeMillis(),
                outgoing = setOf(TrustedDevices.Kind.MESSAGES),
                incoming = setOf(TrustedDevices.Kind.MESSAGES),
                confirmed = true,
                linkKey = com.prism.launcher.trusted.PairLink.encode(link),
            ),
        )
        val wav = MeshGreeting.fromPcm(ByteArray(MeshCalls.SAMPLE_RATE * 2) { (it % 97).toByte() })
        val sealed = java.util.Base64.getEncoder().encodeToString(com.prism.launcher.cloud.CloudVault.seal(wav, link))
        val pieces = sealed.chunked(8 * 1024)
        // Out of order, as UDP may deliver them.
        pieces.indices.reversed().forEach { index ->
            MeshCalls.MeshVoice.accept(PEER, JSONObject().apply {
                put("t", "greet")
                put("v", "v1")
                put("i", index)
                put("n", pieces.size)
                put("fp", "greeter-fp")
                put("data", pieces[index])
            }.toString())
        }

        val stored = assertNotNull(MeshGreeting.greetingOf("greeter-fp"), "the greeting was not stored")
        assertTrue(stored.contentEquals(wav), "the stored greeting differs from the one sent")
        val ack = synchronized(wire) {
            wire.mapNotNull { if (it.ip == PEER && it.opcode == MeshCalls.OPCODE_VOICE) it.json else null }
                .firstOrNull { it.optString("t") == "greet_ack" }
        }
        assertEquals("v1", ack?.optString("v"), "the sender was not told its greeting arrived")
    }

    // ── Video ──────────────────────────────────────────────────────────────

    @Test
    fun `a video frame is chunked, reassembled and only accepted from the call's peer`() {
        MeshCalls.receiveSignal(PEER, invite("call-6", video = true))
        MeshCalls.accept()

        val received = CopyOnWriteArrayList<MeshVideo.Frame>()
        val listener: (MeshVideo.Frame) -> Unit = { received += it }
        MeshVideo.addListener(listener)
        try {
            // Large enough to need several datagrams.
            val jpeg = ByteArray(30_000) { (it * 31).toByte() }
            assertEquals(1, MeshVideo.send(jpeg, rotation = 270))
            val chunks = synchronized(wire) { wire.filter { it.opcode == MeshVideo.OPCODE_VIDEO } }
            assertTrue(chunks.size > 1, "a 30 KB frame should not fit one datagram")
            assertTrue(chunks.all { it.payload.length < 16 * 1024 }, "a chunk would overflow the receive buffer")

            // From a stranger: ignored. From the peer, out of order: reassembled.
            chunks.forEach { MeshVideo.receive(STRANGER, it.payload) }
            chunks.reversed().forEach { MeshVideo.receive(PEER, it.payload) }

            val deadline = System.currentTimeMillis() + 3_000
            while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val frame = assertNotNull(received.singleOrNull(), "the frame was not reassembled")
            assertEquals(PEER, frame.peerIp)
            assertEquals(270, frame.rotation)
            assertTrue(frame.jpeg.contentEquals(jpeg), "the reassembled frame differs from what was sent")
        } finally {
            MeshVideo.removeListener(listener)
        }
    }

    private companion object {
        const val PEER = "10.99.0.2"
        const val OTHER = "10.99.0.3"
        const val STRANGER = "10.99.0.9"
    }
}
