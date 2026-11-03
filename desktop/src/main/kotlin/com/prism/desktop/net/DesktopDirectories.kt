package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.vpn.ServerDirectory
import kotlin.concurrent.thread

/**
 * Directory discovery for the desktop, running without being asked.
 *
 * ## What it does that the dialog cannot
 *
 * The meshnet servers dialog is opened when somebody wants a server, which is exactly when a slow
 * lookup is least welcome. This asks the directories at startup instead, so a client, bridge or relay
 * usually finds the fleet already populated -- and a machine that was asleep when a server was
 * published picks it up on the next refresh without anyone noticing the mechanism exists.
 *
 * ## Why it is not a scheduler
 *
 * One thread, one fixed interval, no queue: directory rows change rarely and a directory that is down
 * is retried a quarter of an hour later rather than hammered. A server does not query at all -- it
 * publishes, which [DesktopPrismProxyServer] does when its listener comes up -- because a list of
 * other servers is not something a server's own fleet screen can dial.
 *
 * Every failure is logged and swallowed: discovery is an ADDITION to typing an address in and to the
 * LAN scan, and none of those should stop working because a directory is offline.
 */
object DesktopDirectories {

    private const val TAG = "PrismDirectory"
    private const val REFRESH_MS = 15L * 60 * 1000

    @Volatile
    private var started = false

    /** Starts the background loop once; calling again is harmless. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        thread(name = "prism-directory-discovery", isDaemon = true) {
            while (true) {
                refresh()
                // A sleep interrupted by shutdown ends the loop rather than spinning.
                if (runCatching { Thread.sleep(REFRESH_MS) }.isFailure) return@thread
            }
        }
    }

    /**
     * Asks every directory once, right now.
     *
     * Called by the loop and by the servers dialog, which runs it on a background dispatcher and
     * shows the count. Empty for a server: it advertises instead of querying.
     */
    fun refresh(): List<ServerDirectory.Report> {
        if (!PrismSettings.isPrismClientRole()) return emptyList()
        return runCatching { ServerDirectory.sync() }.getOrElse { failure ->
            PrismPlatform.log.warn(TAG, "Directory discovery failed: " + failure.message)
            emptyList()
        }.also { reports ->
            if (reports.isNotEmpty()) {
                PrismPlatform.log.info(
                    TAG,
                    reports.count { it.ok }.toString() + " of " + reports.size +
                        " directory(ies) answered during discovery.",
                )
            }
        }
    }
}
