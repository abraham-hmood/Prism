package com.prism.launcher.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.prism.launcher.R

/**
 * The notification a display request falls back to when Prism is not on screen.
 *
 * ## Why this exists
 *
 * A long-press asks the other device to send its screen or its next page. On Android the
 * receiving device may be sitting on another app or with the screen off, and Android 10 onwards
 * blocks an activity launch from that background state. Starting the capture-consent activity
 * anyway does not throw: the launch is simply dropped, so the sender waits forever and looks
 * broken from the device that actually pressed something.
 *
 * A notification is the one surface a background app may still use. Tapping it opens the same
 * consent activity, and a pairing code can be shown in the notification itself so the person can
 * still complete the handshake without Prism having to steal the screen.
 *
 * Notifications are best-effort: POST_NOTIFICATIONS may be denied. The caller keeps its normal
 * activity launch as well, so a foreground request is unchanged.
 */
object DisplayRequestNotification {

    private const val CHANNEL_ID = "prism_display_requests"
    private const val SCREEN_ID = 4101
    private const val CODE_ID = 4102

    /** Asks the user to allow this screen to be shared with [from]. */
    fun screen(context: Context, from: String) {
        val open = PendingIntent.getActivity(
            context,
            SCREEN_ID,
            CapturePermissionActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context,
            SCREEN_ID,
            title = "Wireless display request",
            body = (from.ifBlank { "Another device" }) +
                " wants to show this screen. Tap to allow screen capture.",
            open = open,
        )
    }

    /**
     * Carries a pairing code that is waiting for the user.
     *
     * The code is on this device's own notification, not sent anywhere: it is the proof the
     * person holding the other device has to type, and the notification is only useful to the
     * person holding this one.
     */
    fun code(
        context: Context,
        code: String,
        from: String,
        sending: Boolean,
        extending: Boolean,
    ) {
        val target = DisplayReceiverActivity.codeIntent(
            context,
            code,
            from,
            sending,
            extending,
        )
        val open = PendingIntent.getActivity(
            context,
            CODE_ID,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val what = when {
            sending && extending -> "share its next page"
            sending -> "share its screen"
            extending -> "show a page here"
            else -> "show its screen here"
        }
        post(
            context,
            CODE_ID,
            title = "Pairing code: $code",
            body = (from.ifBlank { "Another device" }) + " wants to " + what +
                ". Tap and type this code there.",
            open = open,
        )
    }

    /** Removes a request that has been answered or abandoned. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(SCREEN_ID) }
        runCatching { NotificationManagerCompat.from(context).cancel(CODE_ID) }
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        body: String,
        open: PendingIntent,
    ) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mirror_24)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A display request is useless if it waits in the shade until somebody opens it.
            // This is the one surface Android still lets a background app use to ask its user
            // for consent, so it is allowed to come to the front like an incoming call.
            .setFullScreenIntent(open, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wireless display requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Asks before another Prism device shows a screen or a page here."
                setShowBadge(true)
            },
        )
    }
}
