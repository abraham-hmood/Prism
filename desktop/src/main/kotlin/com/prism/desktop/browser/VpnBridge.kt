package com.prism.desktop.browser

import com.prism.core.PrismPlatform
import com.prism.desktop.net.TunnelRuntime
import com.prism.launcher.PrismSettings

/**
 * The per-tab VPN state machine, ported from `BrowserPageView.applyVpnForTab`. PHASE 58.
 *
 * ## What changed here, and why the old comment is worth remembering
 *
 * This file used to say the tunnel did not exist on desktop: [available] was a hard `false` and
 * [applyForTab] logged its decision instead of acting, because establishing a tunnel needs WinTun on
 * Windows and CAP_NET_ADMIN on Linux. That was the honest thing to write at the time, and the reason it
 * was written that way still matters: telling somebody their traffic is going over the mesh while it
 * goes out over their ordinary connection is worse than telling them it is not.
 *
 * IT NOW ACTS, THROUGH [TunnelRuntime], and the honesty has moved rather than gone away. [available] is
 * no longer a constant -- it asks whether an adapter can actually be created on THIS machine right now,
 * which on Windows means wintun.dll is present and Prism is elevated. When it cannot, the decision is
 * still made and still logged, and the browser still tells the user what private tabs do and do not get.
 *
 * ## Why the decision is separate from carrying it out
 *
 * [applyForTab] returns a [Decision] before anything is done with it, so the state machine can be
 * tested -- and read -- without a driver, an administrator prompt or a routing table. The guard that
 * matters is the first line: a tab switch between two tabs of the SAME kind must do nothing at all.
 * Without it, every switch tore the tunnel down and rebuilt it, which on a real adapter means the
 * routing table being rewritten several times a second.
 */
object VpnBridge {

    private const val TAG = "Prism/vpn"

    /**
     * Whether a tunnel can actually be established on this machine.
     *
     * ASKED EACH TIME rather than cached: on Windows this becomes true the moment somebody drops
     * wintun.dll beside Prism and restarts it elevated, and a cached false would mean the browser still
     * claiming a tunnel was impossible on a machine where it had just become possible.
     */
    val available: Boolean get() = TunnelRuntime.available()

    /** Set when the tunnel state was last decided, so a no-op switch does nothing. */
    private var lastWanted: Boolean? = null

    /** What the last attempt to act on a decision produced, for the browser to show. */
    @Volatile
    var lastOutcome: String = ""
        private set

    /**
     * Decides what the tunnel should be doing for a tab of this kind, and does it.
     *
     * @return what the state should be, so callers and tests can assert the decision independently of
     *   whether this machine could carry it out.
     */
    fun applyForTab(isPrivateTab: Boolean): Decision {
        if (isPrivateTab == lastWanted) return Decision.UNCHANGED
        lastWanted = isPrivateTab

        val decision = if (isPrivateTab) {
            if (PrismSettings.getVpnAutoStart()) Decision.START else Decision.SUPPRESSED_BY_SETTING
        } else {
            // Android keeps the tunnel up when the user has asked for it to be persistent, rather
            // than dropping it every time they leave a private tab.
            if (PrismSettings.getVpnServerAlwaysOn()) Decision.KEEP_ALIVE else Decision.STOP
        }

        carryOut(decision)
        return decision
    }

    /**
     * Acts on a decision.
     *
     * NOTHING HERE THROWS OR BLOCKS THE TAB SWITCH. Opening an adapter can take a moment and can be
     * refused; a private tab must open either way, with or without a tunnel, because the isolation that
     * does not need one -- an off-the-record context, no third-party cookies, DNT and Sec-GPC -- is
     * worth having on its own and is what the tab promises even when the tunnel is unavailable.
     */
    private fun carryOut(decision: Decision) {
        when (decision) {
            Decision.START, Decision.KEEP_ALIVE -> {
                if (TunnelRuntime.running) {
                    lastOutcome = "The tunnel was already up."
                    return
                }
                if (!available) {
                    lastOutcome = notAvailable()
                    PrismPlatform.log.info(TAG, lastOutcome)
                    return
                }
                val result = TunnelRuntime.start()
                lastOutcome = result.message
                if (result.ok) {
                    PrismPlatform.log.info(TAG, "A private tab brought the mesh tunnel up.")
                } else {
                    PrismPlatform.log.warn(TAG, "A private tab asked for the tunnel: " + result.message)
                }
            }

            Decision.STOP -> {
                if (!TunnelRuntime.running) {
                    lastOutcome = ""
                    return
                }
                lastOutcome = TunnelRuntime.stop().message
                PrismPlatform.log.info(TAG, "The last private tab closed, so the tunnel is down.")
            }

            Decision.SUPPRESSED_BY_SETTING -> {
                lastOutcome = "Private tabs are not starting the tunnel: auto-start is off in settings."
            }

            Decision.UNCHANGED -> Unit
        }
    }

    /** Why the tunnel cannot start here, in a sentence a user can act on. */
    fun notAvailable(): String {
        val availability = com.prism.desktop.net.TunDevice.availability()
        return "Private tabs still get isolated cookies and storage, ad blocking and DNT headers. " +
            "The mesh tunnel needs " + availability.missing.joinToString(" and ") + "."
    }

    /** One line for the browser's own status area. */
    fun describe(): String = when {
        TunnelRuntime.running -> TunnelRuntime.status()
        available -> "Ready. A private tab will bring the tunnel up."
        else -> notAvailable()
    }

    /** Forgets the last decision, so the next call acts unconditionally. */
    fun reset() {
        lastWanted = null
    }

    enum class Decision {
        /** The tab kind did not change; nothing to do. */
        UNCHANGED,

        /** Bring the tunnel up. */
        START,

        /** A private tab, but the user turned auto-start off. */
        SUPPRESSED_BY_SETTING,

        /** Leaving private browsing, but the tunnel is set to stay up. */
        KEEP_ALIVE,

        /** Leaving private browsing; take the tunnel down. */
        STOP,
    }
}
