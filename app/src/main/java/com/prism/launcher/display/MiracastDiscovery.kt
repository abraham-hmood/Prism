package com.prism.launcher.display

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Miracast sinks found through Wi-Fi Direct, which is how Android's own cast sheet finds them.
 *
 * ## The gap SSDP leaves
 *
 * A Samsung UN40H5203 is a 2014 Miracast set. It answers Wi-Fi Direct probe requests and appears in
 * Android's cast picker, but it does not answer the DIAL or MediaRenderer M-SEARCH that
 * [TvDiscovery] sends, and it is not a presentation display until something has negotiated the
 * link. That is the exact "it shows up in Settings but not in Prism" symptom.
 *
 * `WifiP2pManager.discoverPeers` is the public API the system UI itself uses, and it needs no
 * Google Play services. Discovery lists the set; [connect] asks it to form the Wi-Fi Display
 * group, after which the platform exposes the mirror as a presentation display and
 * [SystemDisplayActivity] can put a picture on it.
 *
 * ## Permission and hardware are both optional
 *
 * Wi-Fi Direct is absent on some devices and location/nearby-Wi-Fi permission may be denied on
 * Android 13+. Every failure returns an empty list rather than throwing into the settings page: a
 * machine without Wi-Fi Direct simply has one fewer section, not a broken screen.
 */
object MiracastDiscovery {

    data class Sink(
        val name: String,
        val address: String,
        val status: Int,
        /** The UPnP device type string, e.g. `10-0050F204-5` for a Wi-Fi Display sink. */
        val primaryType: String,
    ) {
        val available: Boolean get() = status == WifiP2pDevice.AVAILABLE
        val isDisplay: Boolean
            get() = primaryType.startsWith("10-0050F204-5") ||
                primaryType.startsWith("10-0050F204") ||
                looksLikeTelevision(name)
    }

    private fun looksLikeTelevision(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("tv", "samsung", "lg ", "sony", "bravia", "philips", "hisense", "tcl",
            "sharp", "panasonic", "display", "miracast").any { lower.contains(it) }
    }

    private fun permitted(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else PackageManager.PERMISSION_GRANTED
        return fine == PackageManager.PERMISSION_GRANTED || nearby == PackageManager.PERMISSION_GRANTED
    }

    private fun channel(context: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return null
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return null
        val channel = runCatching {
            manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
        }.getOrNull() ?: return null
        return manager to channel
    }

    /**
     * Blocks for at most [timeoutMs] while the framework's probe requests are answered.
     *
     * The callback is delivered on the main looper, so this must be called from a worker; the
     * latches make the wait explicit rather than sleeping a fixed amount and hoping.
     */
    fun discover(context: Context, timeoutMs: Long = 6_000L): List<Sink> {
        if (!permitted(context)) return emptyList()
        val (manager, channel) = channel(context) ?: return emptyList()
        val latch = CountDownLatch(1)
        var peers: Collection<WifiP2pDevice> = emptyList()
        runCatching {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    manager.requestPeers(channel) { list ->
                        peers = list?.deviceList.orEmpty()
                        latch.countDown()
                    }
                }

                override fun onFailure(reason: Int) {
                    latch.countDown()
                }
            })
        }.onFailure { latch.countDown() }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return peers.mapNotNull { device ->
            val name = device.deviceName?.trim().orEmpty()
            val address = device.deviceAddress?.trim().orEmpty()
            if (address.isEmpty()) return@mapNotNull null
            Sink(
                name = name.ifBlank { address },
                address = address,
                status = device.status,
                primaryType = device.primaryDeviceType.orEmpty(),
            )
        }.filter { it.isDisplay }
            .distinctBy { it.address }
            .sortedBy { it.name.lowercase() }
    }

    /** A Wi-Fi Display info block, when the peer advertised one. Null when it did not. */
    fun wfdInfo(context: Context, address: String): WifiP2pWfdInfo? {
        val (manager, channel) = channel(context) ?: return null
        var found: WifiP2pDevice? = null
        val latch = CountDownLatch(1)
        runCatching {
            manager.requestPeers(channel) { list ->
                found = list?.deviceList?.firstOrNull { it.deviceAddress == address }
                latch.countDown()
            }
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return found?.wfdInfo
    }

    /**
     * Asks the sink to form the group.
     *
     * The callback reports only whether the request was accepted; the actual Miracast negotiation
     * is asynchronous and is not exposed to applications. The caller therefore re-checks
     * `DisplayManager` a few seconds later, which is the only source of truth for whether a screen
     * exists.
     */
    fun connect(context: Context, address: String, onResult: (String) -> Unit) {
        if (!permitted(context)) {
            onResult("Wi-Fi permission is needed to reach a Miracast display. Grant Nearby devices.")
            return
        }
        val pair = channel(context)
        if (pair == null) {
            onResult("This device has no Wi-Fi Direct, so it cannot start a Miracast link.")
            return
        }
        val manager = pair.first
        val channel = pair.second
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = android.net.wifi.WpsInfo.PBC
            groupOwnerIntent = 0
        }
        runCatching {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onResult("Asked the display to accept. It can take ten seconds; if it does, the set appears under System displays.")
                }

                override fun onFailure(reason: Int) {
                    onResult(
                        when (reason) {
                            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Close the cast settings, then try again."
                            WifiP2pManager.ERROR -> "The display refused the Wi-Fi Direct request."
                            WifiP2pManager.P2P_UNSUPPORTED -> "This device does not support Wi-Fi Direct."
                            else -> "Could not start the Miracast link (reason $reason)."
                        }
                    )
                }
            })
        }.onFailure { onResult("Could not start the Miracast link: ${it.message}") }
    }
}
