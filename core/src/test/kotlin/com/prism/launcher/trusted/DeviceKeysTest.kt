package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.WalletCipher
import com.prism.launcher.wallet.WalletVault
import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-device keys: two devices on DIFFERENT recovery phrases agree a key, and use it.
 *
 * One process plays both devices by swapping the wallet and the stored key pair between them --
 * which is also the honest test of the claim, since each "device" can only use its own wallet.
 */
class DeviceKeysTest {

    private object PassThroughCipher : WalletCipher {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String? = ciphertext
    }

    /** One simulated device: its wallet phrase and its stored key pair. */
    private class Device(val phrase: String) {
        var pub = ""
        var priv = ""
        var at = 0L
    }

    private val scratch = File(System.getProperty("java.io.tmpdir"), "prism-keys-test-${System.nanoTime()}").apply { mkdirs() }
    private val sent = mutableListOf<Triple<String, Byte, String>>()
    private lateinit var a: Device
    private lateinit var b: Device

    private fun prefs() = PrismPlatform.host.prefs("prism_device_keys")

    /** Becomes [device]: its wallet, and its key pair if it has one. */
    private fun become(device: Device) {
        WalletVault.wipe()
        WalletVault.import(device.phrase)
        prefs().edit().putString("pub", device.pub).putString("priv", device.priv).putLong("at", device.at).apply()
        DeviceKeys.clearCache()
        val me = assertNotNull(DeviceKeys.identity())
        device.pub = me.publicKey
        device.priv = prefs().getString("priv", "") ?: ""
        device.at = me.createdAt
    }

    @BeforeTest
    fun setUp() {
        WalletVault.installCipher(PassThroughCipher)
        a = Device(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))
        b = Device(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))
        become(b)
        become(a)
        com.prism.core.MeshMembership.addressesOverride = { listOf("10.8.0.4") }
        TrustedDevices.install(scratch)
        TrustedDevices.all().forEach { TrustedDevices.revoke(it.fingerprint) }
        sent.clear()
        TrustedDevices.sender = { ip, opcode, payload -> sent += Triple(ip, opcode, payload) }
    }

    @AfterTest
    fun tearDown() {
        com.prism.core.MeshMembership.addressesOverride = null
        WalletVault.wipe()
        prefs().edit().putString("pub", "").putString("priv", "").apply()
        DeviceKeys.clearCache()
        scratch.deleteRecursively()
    }

    @Test
    fun `two devices on different wallets derive the same key, and only for their pair`() {
        assertFalse(a.phrase == b.phrase)
        become(a)
        val fromA = assertNotNull(DeviceKeys.pairKey(b.pub, DeviceKeys.Purpose.SHARE))
        become(b)
        val fromB = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.SHARE))
        assertContentEquals(fromA, fromB, "both ends of a pair have to arrive at the same key")

        val calls = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.CALLS))
        assertFalse(calls.contentEquals(fromB), "each kind of data gets its own key")

        val c = Device(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))
        become(c)
        val fromC = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.SHARE))
        assertFalse(fromC.contentEquals(fromA), "a third device cannot make the pair's key")
    }

    @Test
    fun `the private key is sealed with the wallet and unusable without it`() {
        become(a)
        val stored = Base64.getDecoder().decode(prefs().getString("priv", ""))
        assertNull(
            CloudVault.open(stored, ByteArray(32), "prism-device-private-key-v1".toByteArray()),
            "the stored private key must not open without this wallet's key",
        )
        WalletVault.wipe()
        DeviceKeys.clearCache()
        assertNull(DeviceKeys.identity(), "no wallet, no key pair")
        assertNull(DeviceKeys.pairKey(b.pub, DeviceKeys.Purpose.SHARE))
    }

    private fun trustB(link: ByteArray) = TrustedDevices.upsert(
        TrustedDevices.Trust(
            fingerprint = "bbbb0000bbbb0000bbbb0000bbbb0000",
            name = "Phone",
            platform = "android",
            lastIp = "10.8.0.7",
            lastSeen = System.currentTimeMillis(),
            outgoing = setOf(TrustedDevices.Kind.MESSAGES),
            incoming = setOf(TrustedDevices.Kind.MESSAGES),
            confirmed = true,
            linkKey = PairLink.encode(link),
        ),
    )

    private fun announcement(link: ByteArray, publicKey: String, at: Long, want: Boolean = false) =
        JSONObject().apply {
            put("fp", "bbbb0000bbbb0000bbbb0000bbbb0000")
            put("pub", publicKey)
            put("at", at)
            put(
                "tag",
                Base64.getEncoder().encodeToString(
                    DeviceKeys.announceTag(link, "bbbb0000bbbb0000bbbb0000bbbb0000", publicKey, at),
                ),
            )
            if (want) put("want", true)
        }.toString()

    @Test
    fun `a device key is learned only with the pair's proof, and an older one is refused`() {
        val link = ByteArray(32) { 9 }
        trustB(link)

        assertFalse(
            TrustedDevices.onKey("10.8.0.7", announcement(ByteArray(32) { 1 }, b.pub, b.at)),
            "a key proved with the wrong link key must be refused: that is a device in the middle",
        )
        assertTrue(TrustedDevices.byFingerprint("bbbb0000bbbb0000bbbb0000bbbb0000")!!.peerKey.isBlank())

        assertTrue(TrustedDevices.onKey("10.8.0.7", announcement(link, b.pub, b.at, want = true)))
        assertEquals(b.pub, TrustedDevices.byFingerprint("bbbb0000bbbb0000bbbb0000bbbb0000")!!.peerKey)
        assertTrue(sent.any { it.second == TrustedDevices.OPCODE_TRUST_KEY }, "asked for ours, it is sent back")

        val older = Device(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))
        become(older)
        become(a)
        assertFalse(
            TrustedDevices.onKey("10.8.0.7", announcement(link, older.pub, b.at - 1_000)),
            "a replayed older key must not replace the current one",
        )
        assertEquals(b.pub, TrustedDevices.byFingerprint("bbbb0000bbbb0000bbbb0000bbbb0000")!!.peerKey)
    }

    @Test
    fun `a share from a device on a different wallet opens with the pair key`() {
        val link = ByteArray(32) { 9 }
        trustB(link)
        assertTrue(TrustedDevices.onKey("10.8.0.7", announcement(link, b.pub, b.at)))

        // The phone seals with the key it derives towards this device, using only its own wallet.
        become(b)
        val key = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.SHARE))
        val envelope = JSONObject().apply {
            put("kind", "messages")
            put("from", "bbbb0000bbbb0000bbbb0000bbbb0000")
            put("name", "Phone")
            put("body", JSONObject().put("t", "hello"))
        }
        val sealed = Base64.getEncoder().encodeToString(CloudVault.seal(envelope.toString().toByteArray(), key))
        become(a)

        val received = mutableListOf<TrustedDevices.Received>()
        TrustedDevices.consume(TrustedDevices.Kind.MESSAGES) { received += it }
        assertTrue(TrustedDevices.onShare("10.8.0.7", sealed), "a different wallet is no longer a reason to fail")
        assertEquals("hello", received.last().body.optString("t"))
    }

    @Test
    fun `a broadcast sealed for several devices opens only for them`() {
        val link = ByteArray(32) { 9 }
        trustB(link)
        assertTrue(TrustedDevices.onKey("10.8.0.7", announcement(link, b.pub, b.at)))
        val device = TrustedDevices.byFingerprint("bbbb0000bbbb0000bbbb0000bbbb0000")!!

        val packet = assertNotNull(
            TrustedSeal.sealForAll(listOf(device), DeviceKeys.Purpose.SMS, "batch".toByteArray(), legacy = null),
        )
        // As the phone: it holds the pair key, so it opens; a wrong purpose does not.
        become(b)
        val pairKey = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.SMS))
        val wrapped = packet.copyOfRange(7, 7 + ((packet[5].toInt() and 0xFF) shl 8 or (packet[6].toInt() and 0xFF)))
        assertNotNull(CloudVault.open(wrapped, pairKey, "PRM1".toByteArray()), "the recipient can unwrap the content key")
        val other = assertNotNull(DeviceKeys.pairKey(a.pub, DeviceKeys.Purpose.MESSAGES))
        assertNull(CloudVault.open(wrapped, other, "PRM1".toByteArray()), "another kind's key cannot")
    }
}
