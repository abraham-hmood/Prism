package com.prism.launcher.calls

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.prism.launcher.PrismLogger
import com.prism.launcher.R

/**
 * What lets Prism record a carrier call at all.
 *
 * ## Why an accessibility service
 *
 * From Android 10, while a carrier call is active the audio policy hands SILENCE to every app that
 * opens a microphone -- except the system's own call stack, apps holding the privileged
 * CAPTURE_AUDIO_OUTPUT permission, and apps with an enabled accessibility service. Being the default
 * dialer is not on that list. So without this, every source [CallRecorder] tries is digital zeros,
 * which is exactly the "recording was empty" failure Prism had.
 *
 * The service does nothing with accessibility events: it asks for none and reads no windows. Its
 * only purpose is to be enabled, which is what puts Prism's uid on the audio policy's allowed list.
 * This is the same mechanism dedicated call recorders use on unrooted phones.
 */
class CallRecordingAccessService : AccessibilityService() {

    override fun onServiceConnected() {
        PrismLogger.logInfo(TAG, "Call recording access is on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "PrismCallAccess"

        /** Whether the user has switched the service on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val wanted = ComponentName(context, CallRecordingAccessService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == wanted
            }
        }

        /** Android's accessibility settings, where the user switches the service on. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** App info, where Android 13+ hides "Allow restricted settings" for apps not from a store. */
        fun appInfoIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Keeps a call recording alive while the call screen is not on top.
 *
 * Android only lets an app use the microphone while it is visible or running a foreground service
 * of the microphone type. Without this, switching to another app mid-call silenced the recording.
 * It holds no audio itself; [CallRecorder] does the capture.
 */
class CallRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Call recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
            )
            .setSmallIcon(R.drawable.ic_call_record_24)
            .setContentTitle("Recording this call")
            .setContentText(intent?.getStringExtra(EXTRA_NAME).orEmpty())
            .setOngoing(true)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        started.onFailure {
            PrismLogger.logWarning(TAG, "Could not keep the recording in the foreground: ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "PrismCallRecordingSvc"
        private const val CHANNEL = "prism_call_recording"
        private const val NOTIFICATION_ID = 0x7EC0
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            val intent = Intent(context, CallRecordingService::class.java).putExtra(EXTRA_NAME, name)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { PrismLogger.logWarning(TAG, "Could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallRecordingService::class.java)) }
        }
    }
}
