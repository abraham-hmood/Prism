package com.prism.launcher.accesspoint

import com.prism.launcher.accesspoint.AccessPointStore
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages WiFi Access Point creation using WiFi Direct (API 29+) or
 * LocalOnlyHotspot (legacy). WiFi Direct groups are visible to other
 * devices as "DIRECT-<ssid>" networks.
 */
class AccessPointManager(private val context: Context) {

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val p2pChannel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            com.prism.launcher.PrismLogger.coroutineHandler("AccessPoint")
    )

    private val activeAccessPoints = ConcurrentHashMap<Long, AccessPointConfig>()
    private val connectedDevices = ConcurrentHashMap<String, ConnectedDevice>()
    private val apStats = ConcurrentHashMap<Long, AccessPointStats>()
    private val legacyReservations = ConcurrentHashMap<Long, WifiManager.LocalOnlyHotspotReservation>()

    companion object {
        private const val TAG = "PrismAccessPoint"
        const val P2P_PREFIX = "DIRECT-"
        private const val FALLBACK_PASS = "prism00000"
    }

    /**
     * Start a Prism Access Point.
     * On API 29+: creates a WiFi Direct group visible to other devices as "DIRECT-<ssid>".
     * On API 26-28: falls back to local-only hotspot (device-to-device only).
     */
    suspend fun startAccessPoint(config: AccessPointConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (config.ssidName.isEmpty() || config.ssidName.length > 24) {
                return@withContext Result.failure(Exception("SSID must be 1–24 characters"))
            }
            if (config.authType != AuthType.OPEN && config.password.length < 8) {
                return@withContext Result.failure(Exception("Password must be at least 8 characters"))
            }

            PrismLogger.logInfo(TAG, "Starting access point: ${config.ssidName}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startP2pGroup(config)
            } else {
                startLocalOnlyHotspot(config)
            }
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Failed to start access point", e)
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun startP2pGroup(config: AccessPointConfig): Result<String> =
        suspendCoroutine { cont ->
            val networkName = "$P2P_PREFIX${config.ssidName}"
            val passphrase = if (config.password.length >= 8) config.password else FALLBACK_PASS

            // Always remove any stale group first — error 0 (WifiP2pManager.ERROR) is caused
            // almost exclusively by a leftover group from a previous session.
            wifiP2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = doCreateGroup(networkName, passphrase, config, cont)
                override fun onFailure(reason: Int) = doCreateGroup(networkName, passphrase, config, cont)
            })
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun doCreateGroup(
        networkName: String,
        passphrase: String,
        config: AccessPointConfig,
        cont: kotlin.coroutines.Continuation<Result<String>>
    ) {
        try {
            val p2pConfig = WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .build()

            wifiP2pManager.createGroup(p2pChannel, p2pConfig, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    PrismLogger.logSuccess(TAG, "P2P group broadcasting: $networkName")
                    onHotspotStarted(config)
                    cont.resume(Result.success("Broadcasting as $networkName — connect with your password"))
                }

                override fun onFailure(reason: Int) {
                    val msg = p2pError(reason)
                    PrismLogger.logError(TAG, "P2P group failed: $msg", null)
                    cont.resume(Result.failure(Exception(msg)))
                }
            })
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    private suspend fun startLocalOnlyHotspot(config: AccessPointConfig): Result<String> =
        suspendCoroutine { cont ->
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(reservation)
                        legacyReservations[config.id] = reservation
                        onHotspotStarted(config)
                        PrismLogger.logSuccess(TAG, "Local-only hotspot started: ${config.ssidName}")
                        cont.resume(Result.success("Local hotspot ${config.ssidName} started (only nearby app-to-app)"))
                    }

                    override fun onStopped() {
                        super.onStopped()
                        onHotspotStopped(config.id)
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        val msg = "Hotspot failed (code $reason)"
                        PrismLogger.logError(TAG, msg, null)
                        cont.resume(Result.failure(Exception(msg)))
                    }
                }, null)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    private fun onHotspotStarted(config: AccessPointConfig) {
        val active = config.copy(isActive = true)
        activeAccessPoints[config.id] = active
        apStats[config.id] = AccessPointStats(accessPointId = config.id)
        AccessPointStore.saveAccessPoint(active)
        scope.launch { monitorConnectedDevices(config.id) }
    }

    private fun onHotspotStopped(apId: Long) {
        val config = activeAccessPoints.remove(apId) ?: return
        legacyReservations.remove(apId)
        apStats.remove(apId)
        connectedDevices.entries.removeAll { it.value.accessPointId == apId }
        AccessPointStore.saveAccessPoint(config.copy(isActive = false))
    }

    /** Stop an access point and tear down its network group. */
    suspend fun stopAccessPoint(apId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = activeAccessPoints[apId]
                ?: return@withContext Result.failure(Exception("Access point $apId is not active"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                removeP2pGroup(config)
            } else {
                legacyReservations.remove(apId)?.close()
                onHotspotStopped(apId)
            }

            PrismLogger.logSuccess(TAG, "Access point ${config.ssidName} stopped")
            Result.success("Access point stopped")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun removeP2pGroup(config: AccessPointConfig): Unit =
        suspendCoroutine { cont ->
            wifiP2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onHotspotStopped(config.id)
                    cont.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    onHotspotStopped(config.id) // clean up in-memory state regardless
                    cont.resume(Unit)
                }
            })
        }

    private suspend fun monitorConnectedDevices(apId: Long) {
        while (activeAccessPoints.containsKey(apId)) {
            try {
                val count = connectedDevices.values.count { it.accessPointId == apId }
                apStats[apId] = apStats[apId]?.copy(
                    currentConnectedDevices = count,
                    lastUpdated = System.currentTimeMillis()
                ) ?: AccessPointStats(apId)
                delay(5000)
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Error monitoring devices", e)
                delay(10000)
            }
        }
    }

    fun addDevice(device: ConnectedDevice) { connectedDevices[device.macAddress] = device }
    fun removeDevice(mac: String) { connectedDevices.remove(mac) }

    fun getAccessPointStats(apId: Long): AccessPointStats? = apStats[apId]
    fun getActiveAccessPoints(): List<AccessPointConfig> = activeAccessPoints.values.toList()
    fun isAccessPointActive(apId: Long): Boolean = activeAccessPoints.containsKey(apId)

    private fun p2pError(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "Internal WiFi Direct error: " + reason
        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported on this device"
        WifiP2pManager.BUSY -> "WiFi Direct is busy — disable hotspot or try again"
        else -> "WiFi Direct error (code $reason)"
    }
}
