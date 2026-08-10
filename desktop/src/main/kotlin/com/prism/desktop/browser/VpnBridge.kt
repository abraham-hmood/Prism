package com.prism.desktop.browser

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * The per-tab VPN state machine, ported from `BrowserPageView.applyVpnForTab`.
 *
 * THE LOGIC IS THE PORT; THE TUNNEL IS NOT. On Android, selecting a private tab starts
 * `PrivateDnsVpnService` -- a real system VPN carrying DNS and traffic over the P2P mesh -- and
 * deselecting it stops the tunnel unless the user asked for it to stay up. That decision tree is
 * reproduced here exactly, including the "only act when the desired state actually changes" guard
 * that stops a tab switch from tearing the tunnel down and immediately rebuilding it.
 *
 * What is NOT here is the tunnel itself. Establishing one needs WinTun on Windows and
 * CAP_NET_ADMIN plus /dev/net/tun on Linux, which is Phase 7 of the plan and involves an elevated
 * installer. So [available] is false and [applyForTab] logs its decision rather than acting.
 *
 * THIS REPORTS THE GAP RATHER THAN HIDING IT, deliberately. The alternative -- letting a private
 * tab look identical to Android's while no tunnel exists -- would tell a user their traffic is
 * routed over the mesh when it is going out over their ordinary connection. Private tabs on
 * desktop still get isolated cookies and storage, ad blocking and DNT headers, all of which are
 * real; they do not yet get the tunnel, and the UI says so.
 */
object VpnBridge {

    /** Whether a tunnel can actually be established on this platform. */
    val available: Boolean = false

    /** Set when the tunnel state was last decided, so a no-op switch does nothing. */
    private var lastWanted: Boolean? = null

    /**
     * Decides what the tunnel should be doing for a tab of this kind.
     *
     * @return what the state WOULD be on a platform with a tunnel, so callers and tests can
     *   assert the decision independently of whether it can be carried out.
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

        if (!available && (decision == Decision.START || decision == Decision.KEEP_ALIVE)) {
            PrismPlatform.log.info(
                "Prism/vpn",
                "Private tab wants the mesh tunnel, which is not available on desktop yet " +
                    "(needs WinTun on Windows, CAP_NET_ADMIN on Linux). Cookie isolation, ad " +
                    "blocking and DNT headers are still applied."
            )
        }
        return decision
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
