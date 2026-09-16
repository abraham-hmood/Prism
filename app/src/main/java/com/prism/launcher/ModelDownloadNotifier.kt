package com.prism.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * A progress notification for a model transfer.
 *
 * ## Why a notification and not just the checkout screen
 *
 * A model is gigabytes over a phone-to-phone mesh link, which is minutes at best. Nobody watches a
 * checkout screen for that long, and leaving it is exactly what a user should be able to do -- so
 * the only honest place for the progress is the shade, where it survives the activity being
 * backgrounded.
 *
 * ## Ongoing, then gone
 *
 * The notification is ONGOING while bytes are moving, so it cannot be swiped away and mistaken for
 * a finished download. On success it is cancelled outright rather than replaced with "done": the
 * file is on the device and the download tab shows it, so a lingering receipt is one more thing to
 * dismiss. A FAILURE is the opposite case and stays, dismissible, because that is the outcome a
 * user needs to know about after they have stopped looking.
 */
object ModelDownloadNotifier {

    private const val CHANNEL_ID = "prism_model_downloads"

    /** Distinct per model, so two concurrent transfers do not overwrite each other's progress. */
    private fun idFor(modelName: String): Int = "dl-$modelName".hashCode() and 0x7FFFFFFF

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager(context).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while a purchased model transfers from its seller"
                setShowBadge(false)
            }
        )
    }

    private fun tapIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Updates the running transfer.
     *
     * [total] of zero means the size is not known yet, which shows an indeterminate bar rather than
     * a bar frozen at 0% -- the seller may still be opening a very large file, and a stalled-looking
     * bar reads as a failure.
     */
    fun onProgress(context: Context, modelName: String, done: Long, total: Long) {
        ensureChannel(context)
        val indeterminate = total <= 0L
        val percent = if (indeterminate) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

        val text = if (indeterminate) "Starting…"
        else "%,.1f of %,.1f MB".format(done / 1_048_576.0, total / 1_048_576.0)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $modelName")
            .setContentText(text)
            .setSubText(if (indeterminate) null else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent(context))
            .build()

        runCatching { manager(context).notify(idFor(modelName), notification) }
    }

    /** The file arrived. Nothing to say that the presence of the model does not already say. */
    fun onComplete(context: Context, modelName: String) {
        runCatching { manager(context).cancel(idFor(modelName)) }
    }

    /** The transfer died. This one stays, because the user has probably looked away by now. */
    fun onFailed(context: Context, modelName: String, reason: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed: $modelName")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context))
            .build()
        runCatching { manager(context).notify(idFor(modelName), notification) }
    }
}
