package com.prism.launcher.trusted

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.calls.PrismCalls
import com.prism.launcher.mesh.PrismMeshService

/**
 * The Android side of trusted-device location: a locator, a publisher, and the find sound.
 *
 * ## Publishing is opt-in twice
 *
 * [TrustedLocations.sharingEnabled] is the global switch, and each device's fingerprint has to be
 * on the sharing list as well. This object only asks the platform for a position when both are true;
 * there is no timer that reads the GPS for a feature that is off, because a background location read
 * that nothing needed is exactly the kind of thing a privacy indicator should be complaining about.
 *
 * ## Why the publisher is a plain thread
 *
 * It sleeps for a minute at a time and does nothing else. A WorkManager job would be woken even when
 * the mesh is down, and a foreground service would put a permanent notification on screen for a
 * feature that is meant to be quiet. The thread is a daemon, started once, and ends with the process.
 */
object PrismLocations {

    private const val TAG = "PrismLocations"
    private const val INTERVAL_MS = 60_000L

    @Volatile private var started = false

    fun install(context: Context) {
        val app = context.applicationContext

        // The dispatcher is Android's own mesh service; these registrations are for the desktop
        // path but are harmless here and keep one install call on every platform.
        runCatching { TrustedLocations.install() }

        TrustedLocations.locator = { latestFix(app) }

        // A find request rings the phone at alarm volume. It is the only action a peer can cause on
        // this device through the location feature, and TrustedLocations has already checked that
        // the device is confirmed AND allowed to see this user's position.
        TrustedLocations.onFindRequest = { name ->
            PrismLogger.logInfo(TAG, "Find request from $name")
            PrismCalls.playFindSound(app)
        }

        if (started) return
        started = true
        Thread({
            while (true) {
                runCatching { Thread.sleep(INTERVAL_MS) }
                if (!TrustedLocations.sharingEnabled()) continue
                if (!runCatching { PrismMeshService.isOnMesh() }.getOrDefault(false)) continue
                val sent = runCatching { TrustedLocations.publish() }.getOrDefault(0)
                if (sent > 0) PrismLogger.logInfo(TAG, "Published this device's position to $sent device(s)")
            }
        }, "prism-location-publisher").apply { isDaemon = true; start() }

        PrismLogger.logInfo(TAG, "Trusted-device locations installed")
    }

    /** Publishes immediately, for a page that has just been opened and wants current positions. */
    fun publishNow(context: Context): Int {
        if (!TrustedLocations.sharingEnabled()) return 0
        if (!runCatching { PrismMeshService.isOnMesh() }.getOrDefault(false)) return 0
        val fix = latestFix(context)
        TrustedLocations.locator = { fix }
        return runCatching { TrustedLocations.publish() }.getOrDefault(0)
    }

    /**
     * This device's position, from whatever is already known.
     *
     * Last-known rather than a fresh request: a publish runs every minute, and asking for a new GPS
     * fix every minute would keep the receiver warm all day. The cache is only as old as the last
     * time something else needed a location, and its timestamp travels with it so the receiving
     * device decides whether it is still worth showing.
     */
    @SuppressLint("MissingPermission")
    fun latestFix(context: Context): TrustedLocations.Fix? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null

        return TrustedLocations.Fix(
            fingerprint = TrustedDevices.localFingerprint(),
            name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device",
            latitude = best.latitude,
            longitude = best.longitude,
            accuracyMetres = if (best.hasAccuracy()) best.accuracy else 0f,
            at = best.time,
        )
    }

    /** A freshly requested fix, for a "share my current position now" action. Blocking, bounded. */
    @SuppressLint("MissingPermission")
    fun freshFix(context: Context, timeoutSeconds: Long = 8L): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        }
        val signal = android.os.CancellationSignal()
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val delivered = runCatching {
            manager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                signal,
                java.util.concurrent.Executor { it.run() },
            ) { location ->
                result.set(location)
                latch.countDown()
            }
            latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        signal.cancel()
        return if (delivered) result.get() else null
    }
}
