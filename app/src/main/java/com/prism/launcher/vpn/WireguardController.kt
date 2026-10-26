package com.prism.launcher.vpn

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.InputStream

/**
 * The WireGuard client (external-VPN mode), on top of the official userspace backend.
 *
 * ## What changed here
 *
 * Every call used to swallow its failure: `setState` returns a `Backend.State`, `Config.parse`
 * throws on a bad profile, and `start()` had no return value at all, so a profile that failed to
 * parse, or a tunnel Android refused to bring up, produced a log line at best and a UI that still
 * said "connected". Each of these now returns whether it worked and says why when it did not.
 *
 * A refused establishment is a real and common case: another VPN is already up, the profile has no
 * `[Interface]` address, or the user denied the VPN consent dialog.
 */
object WireguardController {

    private const val TAG = "PrismWGClient"

    private var backend: GoBackend? = null
    private val tunnel = PrismTunnel()
    private var config: Config? = null

    @Volatile
    private var up = false

    fun init(context: Context) {
        if (backend == null) {
            backend = runCatching { GoBackend(context) }
                .onFailure { Log.e(TAG, "Could not create the WireGuard backend", it) }
                .getOrNull()
        }
    }

    /** Parses a `.conf`. Returns false, with the reason logged, when the profile is malformed. */
    fun loadProfile(inputStream: InputStream): Boolean = runCatching {
        config = Config.parse(inputStream)
        true
    }.onFailure {
        Log.e(TAG, "That WireGuard profile could not be parsed: ${it.message}", it)
    }.getOrDefault(false)

    fun start(): Boolean {
        val profile = config
        if (profile == null) {
            Log.w(TAG, "No WireGuard profile has been loaded; not starting the tunnel")
            return false
        }
        val engine = backend
        if (engine == null) {
            Log.w(TAG, "The WireGuard backend is not available on this device")
            return false
        }
        return runCatching {
            engine.setState(tunnel, Tunnel.State.UP, profile)
            up = true
            Log.i(TAG, "WireGuard tunnel up")
            true
        }.onFailure {
            up = false
            Log.e(TAG, "WireGuard refused to come up: ${it.message}", it)
        }.getOrDefault(false)
    }

    fun isUp(): Boolean = up

    fun stop() {
        // Down is idempotent, but a backend that is gone is not worth an exception in a teardown
        // path -- so failure is logged at debug and the state is cleared regardless.
        runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, config) }
            .onFailure { Log.d(TAG, "Stopping the WireGuard tunnel failed: ${it.message}") }
        up = false
    }

    private class PrismTunnel : Tunnel {
        override fun getName(): String = "PrismWG"
        override fun onStateChange(newState: Tunnel.State) {}
    }
}
