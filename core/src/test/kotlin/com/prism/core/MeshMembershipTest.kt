package com.prism.core

import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the rule that pairing needs the meshnet rather than the Wi-Fi.
 *
 * ## Why this is worth more than most tests
 *
 * The failure it guards against is invisible. Pairing works, the pairing code is checked, and nothing
 * anywhere says that the picker is empty because the app decided the device was not on a meshnet. The
 * first version of this gate also asked for a 10.8.0.x WireGuard address on both ends, which is true
 * for a WireGuard tunnel and false for the phone serving Prism's mesh at its LAN address -- so a working
 * phone/PC mesh produced "not on a meshnet" on both screens.
 *
 * The mesh itself is the meshnet. The overlay address is still enough on its own, because a device on
 * that overlay is reachable before a heartbeat has been exchanged. Pairing still needs the code shown on
 * the other device; the mesh being unauthenticated is a documented property of the product.
 */
class MeshMembershipTest {

    private lateinit var dir: File
    private lateinit var previousHost: PlatformHost
    private lateinit var previousOnMesh: () -> Boolean
    private lateinit var previousPeers: () -> List<String>
    private val sent = mutableListOf<Triple<String, Byte, String>>()

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        previousOnMesh = MeshTransport.isOnMesh
        previousPeers = MeshTransport.peers
        dir = File(System.getProperty("java.io.tmpdir"), "prism-mesh-member-" + System.nanoTime())
        dir.mkdirs()
        PrismPlatform.host = object : PlatformHost by JvmHost() {
            override fun dataDir(): File = dir
        }
        TrustedDevices.install(File(dir, "trust"))
        // Recorded rather than broadcast: the point is which offers are ATTEMPTED, and a real socket
        // would make that unobservable.
        TrustedDevices.sender = { ip, opcode, payload -> sent.add(Triple(ip, opcode, payload)); true }
        sent.clear()

        // Most tests are about a live mesh. The refusals turn it off themselves.
        MeshTransport.isOnMesh = { true }
        MeshTransport.peers = { emptyList() }
    }

    @AfterTest
    fun close() {
        MeshMembership.addressesOverride = null
        MeshTransport.isOnMesh = previousOnMesh
        MeshTransport.peers = previousPeers
        PrismPlatform.host = previousHost
        dir.deleteRecursively()
    }

    private fun onOverlayAs(address: String) {
        MeshMembership.addressesOverride = { listOf("192.168.4.41", address) }
    }

    private fun offOverlay() {
        MeshMembership.addressesOverride = { listOf("192.168.4.41", "169.254.7.7") }
    }

    private fun meshReaches(vararg peers: String) {
        MeshTransport.peers = { peers.toList() }
    }

    // ── The address test ───────────────────────────────────────────────────

    @Test
    fun `only the mesh subnet counts as the overlay`() {
        assertTrue(MeshMembership.isOverlayAddress("10.8.0.1"), "the server's own address")
        assertTrue(MeshMembership.isOverlayAddress("10.8.0.254"), "the last host")

        assertFalse(MeshMembership.isOverlayAddress("192.168.4.33"), "an ordinary LAN address")
        assertFalse(MeshMembership.isOverlayAddress("10.8.1.5"), "the next subnet along")
        assertFalse(MeshMembership.isOverlayAddress("10.9.0.5"), "a different second octet")
        assertFalse(MeshMembership.isOverlayAddress("110.8.0.5"), "a prefix that merely looks similar")
        assertFalse(MeshMembership.isOverlayAddress("10.8.0"), "not an address at all")
        assertFalse(MeshMembership.isOverlayAddress(""), "empty")
        assertFalse(MeshMembership.isOverlayAddress("10.8.0.x"), "a non-numeric host part")
    }

    // ── Being on the meshnet ───────────────────────────────────────────────

    @Test
    fun `this device is on the meshnet when the mesh is running or it holds a mesh address`() {
        offOverlay()
        assertTrue(MeshMembership.isOnMeshnet(), "the live mesh is the meshnet")

        MeshTransport.isOnMesh = { false }
        assertFalse(MeshMembership.isOnMeshnet(), "no transport and no overlay address")
        assertNull(MeshMembership.overlayAddress())

        onOverlayAs("10.8.0.4")
        assertTrue(MeshMembership.isOnMeshnet(), "the overlay alone is enough")
        assertEquals("10.8.0.4", MeshMembership.overlayAddress())
    }

    @Test
    fun `a device with the mesh off is not on the meshnet`() {
        offOverlay()
        MeshTransport.isOnMesh = { false }

        assertFalse(MeshMembership.isOnMeshnet())
        assertTrue(
            MeshMembership.explain().contains("not on the Prism meshnet"),
            "a device with no mesh should be told that it is",
        )
    }

    // ── The gate, through the real handshake ───────────────────────────────

    @Test
    fun `pairing needs both ends on the meshnet`() {
        onOverlayAs("10.8.0.4")
        assertTrue(MeshMembership.mayPairWith("10.8.0.9"), "both on the overlay")
        assertFalse(MeshMembership.mayPairWith("192.168.4.33"), "the peer is only on the Wi-Fi")

        offOverlay()
        MeshTransport.isOnMesh = { false }
        assertFalse(
            MeshMembership.mayPairWith("10.8.0.9"),
            "this device is off the meshnet, so it must not pair even with a device that is on it",
        )
    }

    @Test
    fun `a peer the live mesh reaches can be paired with`() {
        offOverlay()
        meshReaches("192.168.4.33")

        assertTrue(MeshMembership.mayPairWith("192.168.4.33"), "the phone on this mesh")
        assertFalse(MeshMembership.mayPairWith("192.168.4.99"), "a LAN device the mesh cannot reach")

        val code = TrustedDevices.offer(
            "192.168.4.33", "My PC", "windows", setOf(TrustedDevices.Kind.CLIPBOARD),
        )
        assertTrue(!code.isNullOrBlank(), "the offer should be allowed")
        assertEquals("192.168.4.33", sent.single().first)
    }

    @Test
    fun `an offer to a LAN peer that is not on the mesh is never sent`() {
        onOverlayAs("10.8.0.4")

        val code = TrustedDevices.offer(
            "192.168.4.33", "Somebody's laptop", "windows", setOf(TrustedDevices.Kind.CLIPBOARD),
        )
        assertNull(code, "no code should be generated for a peer that cannot be paired with")
        assertTrue(sent.isEmpty(), "nothing should have gone on the wire, but " + sent.size + " did")
    }

    @Test
    fun `an offer to an overlay peer is sent`() {
        onOverlayAs("10.8.0.4")

        val code = TrustedDevices.offer(
            "10.8.0.9", "My phone", "android", setOf(TrustedDevices.Kind.CLIPBOARD),
        )
        assertTrue(!code.isNullOrBlank(), "a pairing code should have been generated")
        assertEquals(1, sent.size, "exactly one offer should have gone out")
        assertEquals("10.8.0.9", sent.first().first)
    }

    @Test
    fun `an inbound offer from a LAN peer that is not on the mesh is dropped`() {
        onOverlayAs("10.8.0.4")
        val before = TrustedDevices.all().size

        // A well-formed offer, refused on where it came from rather than on its contents. That is the
        // whole point: before this gate the payload was the only thing examined.
        val payload = com.prism.core.json.JSONObject().apply {
            put("fp", "0123456789abcdef")
            put("name", "Not my device")
            put("platform", "windows")
            put("kinds", com.prism.core.json.JSONArray().also { it.put("clipboard") })
            put("salt", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
        }.toString()

        assertFalse(TrustedDevices.onOffer("192.168.4.33", payload), "the offer should be refused")
        assertEquals(before, TrustedDevices.all().size, "nothing should have been recorded")
    }

    @Test
    fun `an inbound offer from an overlay peer is considered`() {
        onOverlayAs("10.8.0.4")

        val payload = com.prism.core.json.JSONObject().apply {
            put("fp", "fedcba9876543210")
            put("name", "My other phone")
            put("platform", "android")
            put("kinds", com.prism.core.json.JSONArray().also { it.put("clipboard") })
            put("salt", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
        }.toString()

        assertTrue(
            TrustedDevices.onOffer("10.8.0.9", payload),
            "an offer from the overlay should be accepted for the user to answer",
        )
    }

    @Test
    fun `a device that is off the meshnet accepts nothing`() {
        offOverlay()
        MeshTransport.isOnMesh = { false }

        val payload = com.prism.core.json.JSONObject().apply {
            put("fp", "aaaabbbbccccdddd")
            put("name", "A device on the mesh")
            put("platform", "android")
            put("kinds", com.prism.core.json.JSONArray().also { it.put("clipboard") })
            put("salt", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
        }.toString()

        // Both directions refused. A device not on the mesh has no business completing a pairing, even
        // with a peer that is -- otherwise the hole simply moves to the other end.
        assertFalse(TrustedDevices.onOffer("10.8.0.9", payload))
        assertNull(TrustedDevices.offer("10.8.0.9", "x", "android", setOf(TrustedDevices.Kind.CLIPBOARD)))
    }

    @Test
    fun `the peer list is empty until this device is on a meshnet`() {
        offOverlay()
        MeshTransport.isOnMesh = { false }
        assertEquals(emptyList(), MeshMembership.meshPeers(listOf("10.8.0.9", "192.168.4.33")))
    }

    @Test
    fun `the peer list is filtered to the meshnet`() {
        offOverlay()
        meshReaches("192.168.4.33")

        val discovered = listOf("10.8.0.9", "192.168.4.33", "192.168.4.99", "169.254.1.1")
        assertEquals(
            listOf("10.8.0.9", "192.168.4.33"),
            MeshMembership.meshPeers(discovered),
            "an overlay address and the peer the mesh reaches; not every device on the Wi-Fi",
        )
    }

    @Test
    fun `the explanation names what is wrong`() {
        offOverlay()
        MeshTransport.isOnMesh = { false }
        assertTrue(
            MeshMembership.explain().contains("not on the Prism meshnet"),
            "a device off the meshnet should be told that it is",
        )

        MeshTransport.isOnMesh = { true }
        assertTrue(
            MeshMembership.explain("192.168.4.33").contains("not on your meshnet"),
            "a LAN peer the mesh cannot reach should be named as the problem",
        )
        assertEquals("", MeshMembership.explain("10.8.0.9"), "a valid pairing has nothing to explain")
    }
}
