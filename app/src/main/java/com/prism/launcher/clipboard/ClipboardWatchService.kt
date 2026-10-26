package com.prism.launcher.clipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings

/**
 * Keeps Prism able to read the clipboard while it is in the background, so a copy made in any
 * app reaches the paired devices within a second instead of whenever the launcher is next opened.
 *
 * ## Why a foreground service is enough -- and why nothing here polls
 *
 * The system clipboard service decides per read and per listener callback whether a caller may
 * look (see [ClipboardWatchPolicy]). A package that IS the default input method passes that
 * check from any process, focused or not. So the only thing missing while Prism sits in the
 * background was a live process: the listener registered by [ClipboardCapture] exists, but if
 * Android has killed the process it cannot be called. A foreground service is what guarantees
 * the process stays alive, and the listener then does all the work.
 *
 * NO TIMER, NO POLLING. The first version of this idea is always a one-second read loop; that is
 * a needless wakeup every second for something the platform already reports. The service starts,
 * the listener fires on each copy, the read succeeds because of the keyboard, and the share goes
 * out. [ClipboardCapture] still re-reads on resume as a safety net for the case where the
 * listener was somehow missed.
 *
 * ## The notification deliberately says nothing about the content
 *
 * It is a persistent notification the user can stop from, not a preview of what was copied.
 * Putting the copied text there would put a password the user copied from their password manager
 * on their lock screen.
 *
 * ## When the keyboard is not Prism's
 *
 * The service stops itself. A persistent notification claiming live sharing while Android is
 * refusing every read is worse than no notification, and there is nothing useful to do in the
 * background until the user switches keyboards.
 */
class ClipboardWatchService : Service() {

    private var imeReceiverRegistered = false

    /**
     * Android tells running listeners when the default keyboard changes. Without this the
     * notification would keep claiming live sharing after the user switched to another keyboard,
     * because nothing in Prism would run again until the launcher came back to the foreground.
     */
    private val imeWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = revalidate()
    }

    override fun onCreate() {
        super.onCreate()
        imeReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                imeWatcher,
                IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not watch for keyboard changes: " + it.message)
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's own button is a user decision, so it is persisted: otherwise the
            // next resume would start the watcher straight back up.
            PrismSettings.setClipboardBackgroundWatch(false)
            standDown()
            return START_NOT_STICKY
        }

        if (state(this) != ClipboardWatchPolicy.State.LIVE) {
            standDown()
            return START_NOT_STICKY
        }

        // Idempotent: the listener is registered once per process by ClipboardCapture.install,
        // and this call is here so the service works even if it is somehow started first.
        ClipboardCapture.install(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // STICKY: this costs nothing to restart and is exactly the process the user asked to keep
        // watching. A restart with a null intent re-runs the check above, so a keyboard change
        // while the process was dead ends in a clean shutdown rather than a stale notification.
        return START_STICKY
    }

    override fun onDestroy() {
        if (imeReceiverRegistered) {
            runCatching { unregisterReceiver(imeWatcher) }
            imeReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun revalidate() {
        if (state(this) == ClipboardWatchPolicy.State.LIVE) {
            ClipboardCapture.install(this)
        } else {
            standDown()
        }
    }

    private fun standDown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ClipboardHistoryActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this,
            1,
            Intent(this, ClipboardWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Clipboard sharing is live")
            .setContentText("Copies go straight to your paired devices")
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live clipboard",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while copies are being sent to your trusted devices"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PrismClipboard"
        private const val CHANNEL_ID = "prism_clipboard_live"
        private const val NOTIFICATION_ID = 8842
        const val ACTION_STOP = "com.prism.launcher.CLIPBOARD_WATCH_STOP"

        /** `Settings.Secure.DEFAULT_INPUT_METHOD`, or null when no keyboard is selected. */
        fun defaultImeComponent(context: Context): String? = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()

        /** The current state of live sharing, as the settings screen shows it. */
        fun state(context: Context): ClipboardWatchPolicy.State =
            ClipboardWatchPolicy.state(
                PrismSettings.getClipboardBackgroundWatch(),
                defaultImeComponent(context),
                context.packageName,
            )

        /**
         * Turns live sharing on or off. Turning it on while the keyboard is not Prism's persists
         * the choice but starts nothing -- [sync] is what the settings screen calls once the user
         * has picked the keyboard, so the decision survives the trip through Settings.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            PrismSettings.setClipboardBackgroundWatch(enabled)
            if (enabled) sync(context) else stop(context)
        }

        /**
         * Brings the service in line with the setting and the current keyboard. Safe to call from
         * anywhere, including a background process start: Android may refuse to launch a
         * foreground service from the background, which is logged rather than thrown, and the
         * next resume tries again.
         */
        fun sync(context: Context) {
            when (state(context)) {
                ClipboardWatchPolicy.State.LIVE -> start(context)
                else -> stop(context)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ClipboardWatchService::class.java))
            }.onFailure { PrismLogger.logWarning(TAG, "Could not stop the clipboard watcher: " + it.message) }
        }

        private fun start(context: Context) {
            val intent = Intent(context, ClipboardWatchService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not start the clipboard watcher: " + it.message)
            }
        }
    }
}
