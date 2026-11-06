package com.prism.launcher.trusted

import com.prism.launcher.cloud.CloudVault
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Sealing for trusted devices, with the keys each pair agreed (see [DeviceKeys]).
 *
 * One place, so every kind of data -- shares, the SMS relay, Prism RCS, location, photos -- seals and
 * opens the same way and identifies the sender the same way: the key that opens a packet names the
 * device that sent it, which is stronger than trusting the address it came from.
 *
 * ## Legacy
 *
 * Each call takes the old wallet-derived key as `legacy`. It is used to SEAL only when the pair has
 * not exchanged device keys yet (a pairing made before link keys, or the exchange still in flight),
 * and it is always tried last when OPENING. Devices that happen to share a recovery phrase therefore
 * keep working through the upgrade; devices that do not, work as soon as their keys are exchanged.
 */
object TrustedSeal {

    /** A packet that opened, and the trusted device whose key opened it (null for the legacy key). */
    class Opened(val plain: ByteArray, val device: TrustedDevices.Trust?)

    /** The pair key for [device], or null until the two have exchanged device keys. */
    fun keyFor(device: TrustedDevices.Trust, purpose: DeviceKeys.Purpose): ByteArray? =
        device.peerKey.takeIf { it.isNotBlank() }?.let { DeviceKeys.pairKey(it, purpose) }

    /** Seals for one device: its pair key, or [legacy] while there is none. Null when neither exists. */
    fun sealFor(
        device: TrustedDevices.Trust,
        purpose: DeviceKeys.Purpose,
        plain: ByteArray,
        legacy: ByteArray?,
        aad: ByteArray? = null,
    ): ByteArray? {
        val key = keyFor(device, purpose) ?: legacy ?: return null
        return CloudVault.seal(plain, key, aad)
    }

    /** [sealFor] for whichever confirmed device is at [peerIp]; [legacy] when none is. */
    fun sealForIp(
        peerIp: String,
        purpose: DeviceKeys.Purpose,
        plain: ByteArray,
        legacy: ByteArray?,
        aad: ByteArray? = null,
    ): ByteArray? {
        val device = TrustedDevices.byIp(peerIp)?.takeIf { it.confirmed }
        if (device != null) return sealFor(device, purpose, plain, legacy, aad)
        return legacy?.let { CloudVault.seal(plain, it, aad) }
    }

    /**
     * Opens a packet from [peerIp]: that device's pair key first, then every other trusted device's
     * (an address is a lease and changes), then [legacy].
     */
    fun openFrom(
        peerIp: String?,
        sealed: ByteArray,
        purpose: DeviceKeys.Purpose,
        legacy: ByteArray?,
        aad: ByteArray? = null,
    ): Opened? {
        val first = peerIp?.let { TrustedDevices.byIp(it) }?.takeIf { it.confirmed }
        for (device in ordered(first)) {
            val key = keyFor(device, purpose) ?: continue
            CloudVault.open(sealed, key, aad)?.let { return Opened(it, device) }
        }
        legacy?.let { key -> CloudVault.open(sealed, key, aad)?.let { return Opened(it, first) } }
        return null
    }

    private fun ordered(first: TrustedDevices.Trust?): List<TrustedDevices.Trust> =
        listOfNotNull(first) + TrustedDevices.all().filter { it.confirmed && it.fingerprint != first?.fingerprint }

    // ── One packet for several devices ────────────────────────────────────

    private val MULTI = "PRM1".toByteArray(Charsets.US_ASCII)

    /**
     * Seals one packet that each of [recipients] -- and, through [legacy], devices on this wallet --
     * can open, for the features that broadcast rather than address one device (the SMS relay,
     * Prism RCS).
     *
     * The body is sealed once with a fresh content key, and that content key is sealed again with
     * each recipient's pair key. A peer that is not a recipient finds no wrap it can open.
     */
    fun sealForAll(
        recipients: List<TrustedDevices.Trust>,
        purpose: DeviceKeys.Purpose,
        plain: ByteArray,
        legacy: ByteArray?,
    ): ByteArray? {
        val wrapKeys = recipients.mapNotNull { keyFor(it, purpose) } + listOfNotNull(legacy)
        if (wrapKeys.isEmpty()) return null
        val content = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MULTI)
            val wraps = wrapKeys.take(255)
            data.writeByte(wraps.size)
            wraps.forEach { key ->
                val wrap = CloudVault.seal(content, key, MULTI)
                data.writeShort(wrap.size)
                data.write(wrap)
            }
            data.write(CloudVault.seal(plain, content))
        }
        return out.toByteArray()
    }

    /**
     * Opens a [sealForAll] packet with any key this device shares with a trusted device, or with
     * [legacy]. A packet in the old single-key format is opened with [legacy] as before.
     */
    fun openFromAny(sealed: ByteArray, purpose: DeviceKeys.Purpose, legacy: ByteArray?): Opened? {
        val isMulti = sealed.size > MULTI.size + 1 && sealed.copyOfRange(0, MULTI.size).contentEquals(MULTI)
        if (!isMulti) {
            return legacy?.let { key -> CloudVault.open(sealed, key)?.let { Opened(it, null) } }
        }
        val (wraps, body) = runCatching {
            val input = DataInputStream(sealed.inputStream())
            input.skipBytes(MULTI.size)
            val count = input.readUnsignedByte()
            val list = (0 until count).map {
                ByteArray(input.readUnsignedShort()).also { wrap -> input.readFully(wrap) }
            }
            list to input.readBytes()
        }.getOrNull() ?: return null

        val candidates = TrustedDevices.all().filter { it.confirmed }
            .mapNotNull { device -> keyFor(device, purpose)?.let { it to device } } +
            listOfNotNull(legacy?.let { it to null })
        for ((key, device) in candidates) {
            for (wrap in wraps) {
                val content = CloudVault.open(wrap, key, MULTI) ?: continue
                CloudVault.open(body, content)?.let { return Opened(it, device) }
            }
        }
        return null
    }
}
