package com.prism.launcher.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.prism.launcher.PrismLogger
import com.prism.launcher.notifications.NotificationHistory

/**
 * Watches the notification shade and writes what it sees into [NotificationHistory].
 *
 * ## The permission is unusual and the UI has to account for it
 *
 * Notification access is not a runtime permission -- there is no dialog an app can raise. The user
 * has to enable Prism by hand in Settings > Notifications > Device & app notifications, and an app
 * can only send them to that screen. So [isEnabled] exists for the page to ask before it shows an
 * empty list, and [requestAccess] opens the right screen rather than leaving someone to find it.
 *
 * Nothing here asks whether the permission is granted before recording: if it is not, Android never
 * binds this service and no callback arrives, which is the same outcome without the extra state.
 *
 * ## What is deliberately not recorded
 *
 * Group summaries, because they duplicate the children Android also delivers -- recording both means
 * every grouped conversation appears twice. And ongoing notifications are recorded but flagged, since
 * a navigation prompt or a media player rewrites itself constantly and is a poor thing to find in a
 * search a month later; the page and the search can then decide what to do with them, which is a
 * better place for that judgement than here.
 */
class PrismNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        PrismLogger.logInfo(TAG, "notification listener connected")

        // The backlog. Everything currently in the shade was posted before this service was bound --
        // on first enable that is the user's whole current set, and without this it would be
        // invisible until each one happened to be reposted.
        runCatching { activeNotifications }.getOrNull()?.forEach { record(it) }
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { record(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Nothing to do. A dismissal is precisely the moment the platform forgets a notification and
        // this feature exists to not forget it, so the record stays exactly as it was.
    }

    private fun record(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return

        // A group summary restates its children, both of which Android delivers separately.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.charSequenceText(Notification.EXTRA_TITLE)
            ?: extras.charSequenceText(Notification.EXTRA_TITLE_BIG)
            ?: ""
        // BIG_TEXT before TEXT: an expanded notification carries the full message in the former and
        // an elided version in the latter, and the full one is what makes a search work later.
        val text = extras.charSequenceText(Notification.EXTRA_BIG_TEXT)
            ?: extras.charSequenceText(Notification.EXTRA_TEXT)
            ?: extras.charSequenceText(Notification.EXTRA_SUMMARY_TEXT)
            ?: ""

        NotificationHistory.record(
            packageName = sbn.packageName ?: return,
            appLabel = appLabel(this, sbn.packageName),
            title = title,
            text = text,
            at = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
            key = runCatching { sbn.key }.getOrNull().orEmpty(),
            ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    private fun android.os.Bundle.charSequenceText(key: String): String? =
        runCatching { getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    companion object {
        private const val TAG = "PrismNotifications"

        /**
         * Whether Android currently has this service bound.
         *
         * Only meaningful while the process is alive, which is why [isEnabled] asks the system
         * instead. Kept because a page that is open when access is granted can then notice.
         */
        @Volatile
        var connected = false
            private set

        /**
         * Whether the user has granted notification access.
         *
         * Read from the secure setting rather than from [connected]: the service is bound lazily and
         * a freshly started process has not been bound yet even when the permission has been granted
         * for months, so [connected] would say no and be wrong.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = runCatching {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            }.getOrNull() ?: return false
            val mine = ComponentName(context, PrismNotificationListener::class.java)
            return flat.split(":").any { entry ->
                val parsed = ComponentName.unflattenFromString(entry)
                parsed != null && parsed.packageName == mine.packageName &&
                    parsed.className == mine.className
            }
        }

        /** Opens the system screen where notification access is granted. */
        fun requestAccess(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                // Some builds do not expose that screen; the general settings page is better than
                // nothing and better than a crash.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        /** The user-visible name of an app, falling back to its package name. */
        fun appLabel(context: Context, packageName: String?): String {
            if (packageName.isNullOrBlank()) return ""
            return runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull() ?: packageName
        }
    }
}
