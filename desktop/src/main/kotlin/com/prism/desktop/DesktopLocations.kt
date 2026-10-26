package com.prism.desktop

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedLocations

/**
 * Location sharing from a desktop.
 *
 * ## The position is found, not typed in
 *
 * A PC has no GPS, but [DesktopGeolocation] turns the Wi-Fi networks in range into a latitude and
 * longitude, which means sharing this machine's position with a trusted device works the way it
 * does on a phone: turn sharing on and it is there. The place a user picks on the map is kept as an
 * explicit OVERRIDE -- useful for a wired desktop with no Wi-Fi, or for somebody who wants their
 * devices to see "Home" rather than a street corner -- and clearing it goes back to the detected
 * position instead of to nothing.
 *
 * ## What is automatic and what is not
 *
 * Detection runs once shortly after startup, so the map has a blue dot, and then on the publisher's
 * minute tick while sharing is on (the lookup itself is rate-limited by [STALE_AFTER_MS]). Nothing
 * is sent to a trusted device unless sharing is enabled AND that device was individually allowed,
 * which is enforced in [TrustedLocations.publish]. The only outbound request detection makes is the
 * access-point lookup described in [DesktopGeolocation].
 *
 * The publisher is a plain daemon thread for the same reason the Android one is: it sleeps for a
 * minute at a time and does nothing else, which is cheaper and quieter than a scheduler waking the
 * process when the feature is off or the mesh is down.
 */
object DesktopLocations {

    private const val TAG = "PrismDesktopLocations"
    private const val INTERVAL_MS = 60_000L

    /** How long a detected position is trusted before the scanner is asked again. */
    private const val STALE_AFTER_MS = 5 * 60_000L

    /**
     * How old a detection may be and still be PUBLISHED.
     *
     * Longer than the refresh window because a scan can fail transiently, but bounded because
     * sending yesterday's position as today's is worse than sending nothing: the receiving device
     * has no way to tell a stale fix from a fresh one.
     */
    private const val MAX_PUBLISH_AGE_MS = 30 * 60_000L

    /**
     * A place this machine is at.
     *
     * [automatic] distinguishes the detected position from one the user pinned: the map page shows
     * both, but only the manual one survives a restart, and a user who set a place should not see
     * it silently replaced by a scan.
     */
    data class Position(
        val latitude: Double,
        val longitude: Double,
        val label: String,
        val accuracyMetres: Float = 0f,
        val automatic: Boolean = false,
    )

    @Volatile private var started = false
    @Volatile private var detected: DesktopGeolocation.Estimate? = null
    @Volatile private var detectedAt = 0L

    /** The place the user pinned, if any. Preferring it is the override. */
    fun manualPosition(): Position? {
        val lat = PrismSettings.getDesktopLatitude() ?: return null
        val lon = PrismSettings.getDesktopLongitude() ?: return null
        return Position(lat, lon, PrismSettings.getDesktopLocationLabel())
    }

    /**
     * Where this machine is now: the pinned place if there is one, otherwise the last detection.
     *
     * Reads cached state only. The map page calls this every few seconds, and a method that could
     * block on a Wi-Fi scan or an HTTP request there would freeze the window.
     */
    fun position(): Position? = manualPosition() ?: detected?.let { estimate ->
        Position(
            latitude = estimate.latitude,
            longitude = estimate.longitude,
            label = if (estimate.accuracyMetres > 0f) {
                "Detected · ±" + estimate.accuracyMetres.toInt() + " m"
            } else {
                "Detected automatically"
            },
            accuracyMetres = estimate.accuracyMetres,
            automatic = true,
        )
    }

    fun hasPosition(): Boolean = position() != null

    /** A sentence for the map and settings pages, without blocking on a scan. */
    fun status(): String {
        val current = position()
        return when {
            current != null && !current.automatic -> "Using the position you set"
            current != null -> current.label
            DesktopGeolocation.lastError.isNotBlank() -> DesktopGeolocation.lastError
            else -> "Finding this machine's position…"
        }
    }

    fun set(latitude: Double, longitude: Double, label: String) {
        PrismSettings.setDesktopLocation(latitude, longitude, label)
        // A position picked while sharing is on should go out now, not up to a minute later.
        publishNow()
    }

    /** Drops the pinned place. Detection takes over again on the next refresh. */
    fun clear() {
        PrismSettings.setDesktopLocation(null, null, "")
    }

    /**
     * The fix form the shared trusted-location engine consumes.
     *
     * The label identifies the place on this machine's own map; the name the recipient sees is the
     * device's trusted name, which the receiver fills in from its own trust store.
     */
    fun fix(): TrustedLocations.Fix? {
        val place = position() ?: return null
        val detectedTimestamp = detectedAt
        if (place.automatic) {
            val age = System.currentTimeMillis() - detectedTimestamp
            if (detectedTimestamp <= 0L || age > MAX_PUBLISH_AGE_MS) {
                PrismPlatform.log.info(TAG, "Not publishing: the last detected position is too old")
                return null
            }
        }
        return TrustedLocations.Fix(
            fingerprint = TrustedDevices.localFingerprint(),
            name = TrustedDevices.localName(),
            latitude = place.latitude,
            longitude = place.longitude,
            accuracyMetres = place.accuracyMetres,
            // The time the position was worked out, not the time it is sent, so a receiver can
            // bound its age instead of being told every fix is brand new.
            at = if (place.automatic) detectedTimestamp else System.currentTimeMillis(),
        )
    }

    /**
     * Detects the position if the cached one is old.
     *
     * Blocking, and called only from the publisher thread, the startup probe and [publishNow]'s
     * caller. [force] is for a user action ("find me now") rather than the minute tick.
     */
    @Synchronized
    fun locate(force: Boolean = false): DesktopGeolocation.Estimate? {
        val current = detected
        val fresh = current != null && System.currentTimeMillis() - detectedAt < STALE_AFTER_MS
        if (fresh && !force) return current
        val estimate = DesktopGeolocation.detect() ?: return current
        detected = estimate
        detectedAt = System.currentTimeMillis()
        PrismPlatform.log.info(
            TAG,
            "Detected this machine's position within " + estimate.accuracyMetres.toInt() + " m",
        )
        return estimate
    }

    fun install() {
        // Receiving is already registered by Main; this is the sending half.
        TrustedLocations.locator = { fix() }

        if (started) return
        started = true
        Thread({
            // A few seconds after startup: long enough that a scan does not compete with the
            // window opening, early enough that the map has something to draw.
            runCatching { Thread.sleep(4_000L) }
            var nextDetection = 0L
            while (true) {
                runCatching {
                    val due = System.currentTimeMillis() >= nextDetection
                    // While sharing is on, refresh so a laptop that moved publishes where it is
                    // now. With sharing off there is no reason to keep scanning; the startup fix
                    // is enough for the map, and an explicit one can be requested by a page.
                    if (due && (TrustedLocations.sharingEnabled() || detected == null)) {
                        locate(force = false)
                        nextDetection = System.currentTimeMillis() + STALE_AFTER_MS
                    }
                    if (TrustedLocations.sharingEnabled()) publishNow()
                }
                runCatching { Thread.sleep(INTERVAL_MS) }
            }
        }, "prism-desktop-location-publisher").apply { isDaemon = true; start() }

        PrismPlatform.log.info(TAG, "Desktop location publisher installed")
    }

    /**
     * Publishes immediately if sharing is on, a position is known (or can be found), and the mesh
     * is up.
     *
     * A missing position is not a reason to give up: on a machine that has never been located this
     * performs the detection, which is what makes sharing work without the user setting a place
     * first.
     */
    fun publishNow(): Int {
        if (!TrustedLocations.sharingEnabled()) return 0
        if (!runCatching { MeshTransport.isOnMesh() }.getOrDefault(false)) return 0
        if (position() == null) locate(force = false)
        if (position() == null) return 0
        return runCatching { TrustedLocations.publish() }.getOrDefault(0)
    }
}
