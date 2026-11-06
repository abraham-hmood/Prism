package com.prism.launcher.trusted

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.cloud.CloudVault
import java.io.File
import java.util.Base64

/**
 * Where the user's trusted devices are, and the rules that stop anybody else learning it.
 *
 * ## The threat this is written against
 *
 * Location is the most dangerous thing Prism stores. A mesh is an unauthenticated UDP network on
 * whatever LAN it runs on, and a trusted-device list is a handful of fingerprints and last-seen
 * addresses -- both readable by anyone who can see the traffic. So the design is deliberately
 * narrow:
 *
 *  1. **Per-device consent, off by default.** A device receives this user's position only when the
 *     user switched sharing on FOR THAT FINGERPRINT. Trust as a whole does not imply it; a laptop
 *     paired for clipboard has no business knowing where the phone is.
 *  2. **Sealed for that one device.** Each fix is sealed with the location key this device agreed
 *     with the recipient from their two key pairs ([DeviceKeys.Purpose.LOCATION]), so only that
 *     device can open it, and a location cannot be opened with any other kind's key even if a
 *     packet is misrouted.
 *  3. **Accepted only from a confirmed device.** The key that opens the packet names the device
 *     that sent it, and that device must be confirmed. A forged sender address can therefore make
 *     the packet be dropped, never make it be believed.
 *  4. **Bounded in time.** A fix older than [MAX_AGE_MS] is refused on receipt, so a replay of an
 *     old packet cannot resurrect a stale position, and stored positions expire as well.
 *  5. **Encrypted at rest.** The cache is one sealed blob; a filesystem read on a stolen laptop
 *     yields no location history, and only the newest fix per device is kept.
 *
 * ## What it does not protect
 *
 * The mesh reveals that a packet of a certain size went from one address to another at a certain
 * time. Encryption does not hide that, and this file does not claim it does. It also cannot stop a
 * trusted device that legitimately receives a position from sharing it onwards; that is what
 * "trusted" means.
 */
object TrustedLocations {

    private const val TAG = "PrismLocations"

    /** A position report. [at] is the sender's clock; receivers bound it. */
    data class Fix(
        val fingerprint: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val accuracyMetres: Float,
        val at: Long,
    )

    const val OPCODE_LOCATION: Byte = 0x2D
    const val OPCODE_FIND: Byte = 0x2F
    private const val FILE = "trusted/locations.sealed"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    /** The platform's current position, installed at startup. Null means "this machine has none". */
    @Volatile
    var locator: (() -> Fix?)? = null

    /** Providers whose position can be published. Android installs this; a desktop usually cannot. */
    fun hasLocator(): Boolean = locator != null

    fun isSharingTo(fingerprint: String): Boolean =
        PrismSettings.getLocationSharingEnabled() &&
            PrismSettings.getLocationSharedFingerprints().contains(fingerprint)

    fun setSharingTo(fingerprint: String, enabled: Boolean) {
        val current = PrismSettings.getLocationSharedFingerprints().toMutableSet()
        if (enabled) current.add(fingerprint) else current.remove(fingerprint)
        PrismSettings.setLocationSharedFingerprints(current)
    }

    fun sharingEnabled(): Boolean = PrismSettings.getLocationSharingEnabled()

    fun setSharingEnabled(enabled: Boolean) = PrismSettings.setLocationSharingEnabled(enabled)

    // ── Sending ────────────────────────────────────────────────────────────

    /**
     * Publishes this device's position to every device that consented.
     *
     * Called on a timer by the host while sharing is on and the mesh is up. Silence is the correct
     * outcome for every failure: a location that could not be sent is not worth a notification, and
     * the next tick will try again.
     */
    fun publish(): Int {
        if (!sharingEnabled()) return 0
        if (!MeshTransport.isOnMesh()) return 0
        val fix = runCatching { locator?.invoke() }.getOrNull() ?: return 0
        val recipients = TrustedDevices.all().filter {
            it.confirmed && it.lastIp.isNotBlank() && isSharingTo(it.fingerprint)
        }
        if (recipients.isEmpty()) return 0

        // The old wallet key, only for a pair that has not exchanged device keys yet. See TrustedSeal.
        val legacy = CloudVault.keyFor(CloudVault.Purpose.LOCATION)
        val payload = JSONObject().apply {
            put("t", "loc")
            put("lat", fix.latitude)
            put("lon", fix.longitude)
            put("acc", fix.accuracyMetres.toDouble())
            put("at", System.currentTimeMillis())
            put("name", fix.name)
            put("fp", TrustedDevices.localFingerprint())
        }
        val plain = payload.toString().toByteArray()

        var sent = 0
        recipients.forEach { device ->
            // Sealed per device, with the key this pair agreed; nothing is sent without a key.
            val sealed = TrustedSeal.sealFor(device, DeviceKeys.Purpose.LOCATION, plain, legacy) ?: return@forEach
            runCatching {
                MeshTransport.sendToPeer(device.lastIp, OPCODE_LOCATION, Base64.getEncoder().encodeToString(sealed))
                sent++
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not send a location to ${device.name}: ${it.message}")
            }
        }
        return sent
    }

    /**
     * Asks one trusted device to play a sound.
     *
     * The request carries no position and is sealed like everything else; the receiving phone rings
     * at alarm volume so the user can find it. It is a deliberate, user-initiated action, not
     * something a page does on its own.
     */
    fun requestFind(fingerprint: String): Boolean {
        val device = TrustedDevices.byFingerprint(fingerprint) ?: return false
        if (!device.confirmed || device.lastIp.isBlank()) return false
        val sealed = TrustedSeal.sealFor(
            device,
            DeviceKeys.Purpose.LOCATION,
            JSONObject().apply {
                put("t", "find")
                put("at", System.currentTimeMillis())
                put("fp", TrustedDevices.localFingerprint())
            }.toString().toByteArray(),
            CloudVault.keyFor(CloudVault.Purpose.LOCATION),
        ) ?: return false
        return runCatching {
            MeshTransport.sendToPeer(device.lastIp, OPCODE_FIND, Base64.getEncoder().encodeToString(sealed))
            true
        }.getOrDefault(false)
    }

    // ── Receiving ──────────────────────────────────────────────────────────

    /** Registers both opcodes with the mesh. Called once at startup on every platform. */
    fun install() {
        // MeshCore handlers answer whether they consumed the packet; both of these only ever
        // consume or drop it, and neither has a reply to send.
        com.prism.core.MeshCore.register(OPCODE_LOCATION) { peerIp, payload ->
            accept(peerIp, payload)
            true
        }
        com.prism.core.MeshCore.register(OPCODE_FIND) { peerIp, payload ->
            acceptFind(peerIp, payload)
            true
        }
        PrismPlatform.log.info(TAG, "Location sharing opcodes registered")
    }

    /** Called by the host when a find request arrives, so it can ring. */
    @Volatile
    var onFindRequest: ((deviceName: String) -> Unit)? = null

    /**
     * Entry points for a platform transport that dispatches opcodes itself.
     *
     * Android's mesh service does, so [install]'s MeshCore handlers never see a packet there. Both
     * routes end in the same [accept], which is where every check lives -- a second, thinner parser
     * for one platform is how a security check ends up applied to only one of them.
     */
    fun receiveLocation(peerIp: String, payload: String) = accept(peerIp, payload)

    fun receiveFind(peerIp: String, payload: String) = acceptFind(peerIp, payload)

    /** Opens a location packet; the device is the one whose key opened it. */
    private fun openLocation(peerIp: String, payload: String): TrustedSeal.Opened? {
        val sealed = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        return TrustedSeal.openFrom(
            peerIp, sealed, DeviceKeys.Purpose.LOCATION, CloudVault.keyFor(CloudVault.Purpose.LOCATION),
        )
    }

    private fun accept(peerIp: String, payload: String) {
        val opened = openLocation(peerIp, payload) ?: run {
            PrismPlatform.log.warn(TAG, "A location from $peerIp would not open")
            TrustedDevices.byIp(peerIp)?.takeIf { it.confirmed }?.let { TrustedDevices.requestKey(it) }
            return
        }
        val device = opened.device ?: TrustedDevices.byIp(peerIp) ?: return
        if (!device.confirmed) {
            PrismPlatform.log.warn(TAG, "Refused a location from an unconfirmed device at $peerIp")
            return
        }
        val plain = opened.plain

        val json = runCatching { JSONObject(String(plain)) }.getOrNull() ?: return
        if (json.optString("t") != "loc") return

        val now = System.currentTimeMillis()
        val at = json.optLong("at", 0L)
        if (at <= 0L || now - at > MAX_AGE_MS || at > now + CLOCK_SKEW_MS) {
            PrismPlatform.log.warn(TAG, "Refused a location from ${device.name}: timestamp out of range")
            return
        }

        val lat = json.optDouble("lat", Double.NaN)
        val lon = json.optDouble("lon", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            PrismPlatform.log.warn(TAG, "Refused a location from ${device.name}: coordinates invalid")
            return
        }

        val fix = Fix(
            fingerprint = device.fingerprint,
            name = device.name,
            latitude = lat,
            longitude = lon,
            accuracyMetres = json.optDouble("acc", 0.0).toFloat(),
            at = at,
        )
        store(fix)
        onFix?.invoke(fix)
    }

    private fun acceptFind(peerIp: String, payload: String) {
        val opened = openLocation(peerIp, payload) ?: return
        val device = opened.device ?: TrustedDevices.byIp(peerIp) ?: return
        if (!device.confirmed) return
        // Only a device the user allowed to see this position may also make this one ring: the
        // find request is a physical action in the user's environment, not a network probe.
        if (!isSharingTo(device.fingerprint)) {
            PrismPlatform.log.warn(TAG, "Ignored a find request from ${device.name}: not allowed")
            return
        }
        val json = runCatching { JSONObject(String(opened.plain)) }.getOrNull() ?: return
        if (json.optString("t") != "find") return
        val at = json.optLong("at", 0L)
        if (at <= 0 || System.currentTimeMillis() - at > MAX_AGE_MS) return
        onFindRequest?.invoke(device.name)
    }

    /** Notified after a fix is stored, so an open map can redraw. */
    @Volatile
    var onFix: ((Fix) -> Unit)? = null

    // ── The sealed cache ───────────────────────────────────────────────────

    private fun file(): File? {
        val dir = runCatching { PrismPlatform.host.dataDir() }.getOrNull() ?: return null
        return File(dir, FILE).apply { parentFile?.mkdirs() }
    }

    /**
     * Every stored fix, newest first.
     *
     * A cache that cannot be opened returns nothing rather than throwing: a device that has just
     * changed its wallet cannot read positions sealed with the old one, and an empty map is a
     * better answer than a crash on the way to one.
     */
    fun all(): List<Fix> {
        val source = file() ?: return emptyList()
        if (!source.isFile) return emptyList()
        val key = CloudVault.keyFor(CloudVault.Purpose.LOCATION) ?: return emptyList()
        val plain = runCatching { CloudVault.open(source.readBytes(), key) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(String(plain))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val at = item.optLong("at")
                if (at <= 0 || System.currentTimeMillis() - at > MAX_AGE_MS) return@mapNotNull null
                Fix(
                    fingerprint = item.optString("fp"),
                    name = item.optString("name"),
                    latitude = item.optDouble("lat", 0.0),
                    longitude = item.optDouble("lon", 0.0),
                    accuracyMetres = item.optDouble("acc", 0.0).toFloat(),
                    at = at,
                )
            }.filter { it.fingerprint.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun store(fix: Fix) {
        val target = file() ?: return
        val key = CloudVault.keyFor(CloudVault.Purpose.LOCATION) ?: return

        // ONE ROW PER DEVICE. The last known position is what the map promises; keeping a history
        // would turn a convenience cache into a location log, which is a different and much worse
        // thing to leave on a disk.
        val merged = (all().filterNot { it.fingerprint == fix.fingerprint } + fix)
            .sortedByDescending { it.at }

        val array = JSONArray()
        merged.forEach { item ->
            array.put(JSONObject().apply {
                put("fp", item.fingerprint)
                put("name", item.name)
                put("lat", item.latitude)
                put("lon", item.longitude)
                put("acc", item.accuracyMetres.toDouble())
                put("at", item.at)
            })
        }

        runCatching {
            val sealed = CloudVault.seal(array.toString().toByteArray(), key)
            target.writeBytes(sealed)
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not save a location: ${it.message}")
        }
    }

    /** Forgets one device's position. */
    fun forget(fingerprint: String) {
        val target = file() ?: return
        val remaining = all().filterNot { it.fingerprint == fingerprint }
        val key = CloudVault.keyFor(CloudVault.Purpose.LOCATION) ?: return
        val array = JSONArray()
        remaining.forEach { item ->
            array.put(JSONObject().apply {
                put("fp", item.fingerprint)
                put("name", item.name)
                put("lat", item.latitude)
                put("lon", item.longitude)
                put("acc", item.accuracyMetres.toDouble())
                put("at", item.at)
            })
        }
        runCatching { target.writeBytes(CloudVault.seal(array.toString().toByteArray(), key)) }
    }
}
