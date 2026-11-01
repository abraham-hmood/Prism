package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings

/**
 * The messaging apps Prism can hand off to: WhatsApp, Facebook Messenger and Signal.
 *
 * RCS is deliberately NOT in this list. It used to be a handoff row that opened the carrier app,
 * which is not RCS support; Prism carries rich messages itself now. See
 * [com.prism.launcher.messaging.RcsChat].
 *
 * ## What "integration" means now
 *
 * Each service DOES have a public interface, but not the one a personal chat client has. WhatsApp
 * Cloud API is a business-number API, Messenger Platform is a Page API, and Signal is reached
 * through a signal-cli bridge. [MessagingApps] owns those credentials and [MessagingApi] speaks
 * them; this class is the narrower handoff layer that still opens the installed app when the user
 * wants the personal client, and keeps the switches shared between platforms.
 *
 * What this class describes is the three things a launcher owns regardless of API:
 *
 *  - **Presence in one place.** Enabled bridges appear in Messages and on the desktop, so the
 *    user does not have to remember which app a conversation lives in.
 *  - **A handoff that lands in the app, not a browser tab.** Each bridge carries the package name
 *    and a deep link, so tapping a row opens the installed application; the web address is the
 *    fallback for a machine that does not have it.
 *  - **Notification capture.** The Android notification listener already records what those apps
 *    post. An enabled bridge is listed in [notificationPackages], which is what the Messages page
 *    uses to decide whose captured notifications to show there.
 *
 * ## Why the flags live in PrismSettings
 *
 * They are shared with the desktop, which has no Android listener and does use the same three
 * rows. A bridge enabled on the phone therefore appears enabled on the laptop, and the laptop's
 * row opens the web client instead. One switch per service, not one per platform.
 */
object MessagingBridges {

    data class Bridge(
        val id: String,
        val label: String,
        val packageName: String,
        /** Deep link that opens the installed app, or the web client when there is one. */
        val appUri: String,
        /** The desktop/web fallback, or a download page for an app with no web client. */
        val webUrl: String,
        /** Where a composed message goes, when the app accepts one. Empty when it cannot. */
        val sendUriPrefix: String,
        val note: String,
    )

    val ALL: List<Bridge> = listOf(
        Bridge(
            id = "whatsapp",
            label = "WhatsApp",
            packageName = "com.whatsapp",
            appUri = "whatsapp://send",
            webUrl = "https://web.whatsapp.com/",
            sendUriPrefix = "https://wa.me/?text=",
            note = "Uses WhatsApp Cloud API for your business number, or opens the personal app. " +
                "Personal chats are not readable through the API.",
        ),
        Bridge(
            id = "messenger",
            label = "Facebook Messenger",
            packageName = "com.facebook.orca",
            appUri = "fb-messenger://",
            webUrl = "https://www.messenger.com/",
            sendUriPrefix = "",
            note = "Uses Messenger Platform for a Facebook Page, or opens the personal app. Page " +
                "conversations are the ones the API can read.",
        ),
        Bridge(
            id = "signal",
            label = "Signal",
            packageName = "org.thoughtcrime.securesms",
            appUri = "sgnl://",
            webUrl = "https://signal.org/download/",
            sendUriPrefix = "",
            note = "Uses signal-cli-rest-api as a linked device, or opens the installed app. " +
                "Signal has no first-party server API.",
        ),
    )

    fun byId(id: String): Bridge? = ALL.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = PrismSettings.isMessagingBridgeEnabled(id)

    fun setEnabled(id: String, enabled: Boolean) = PrismSettings.setMessagingBridgeEnabled(id, enabled)

    /** The bridges the user chose to bring into Prism. */
    fun enabled(): List<Bridge> = ALL.filter { isEnabled(it.id) }

    /**
     * Package names whose notifications belong in Prism's message history.
     *
     * Only enabled bridges, and only those. The listener records every notification the system
     * gives it -- that is what a history is -- but a history entry is not the same as surfacing
     * an app as connected, and a service nobody enabled must not appear in Messages as though it
     * had been linked.
     */
    fun notificationPackages(): Set<String> = enabled().map { it.packageName }.toSet()

    /** Whether anything at all is connected, for a one-line status in Settings. */
    fun summary(): String {
        val active = enabled()
        return when {
            active.isEmpty() -> "Not connected"
            active.size == 1 -> active.first().label + " connected"
            else -> active.size.toString() + " apps connected"
        }
    }
}
