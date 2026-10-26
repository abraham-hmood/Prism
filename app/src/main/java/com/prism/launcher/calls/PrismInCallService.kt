package com.prism.launcher.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.R

/**
 * Prism's connection to Android Telecom.
 *
 * ## Why this service is what makes Prism the phone app
 *
 * A carrier call is never actually carried by an ordinary app: the radio, the SIM and the call
 * audio belong to the platform's Telecom stack. What a phone app owns is the *call surface* --
 * answering, muting, the speaker route, ending the call and every screen the user sees. Until this
 * service existed, Prism could dial a number but Android kept showing the system phone app's
 * in-call screen, which is exactly what the dialer was meant to avoid.
 *
 * Declaring an InCallService is also one of the two requirements for the default-dialer role (the
 * other is handling `ACTION_DIAL`). Once the user grants that role, Telecom binds this service for
 * every carrier call and Prism is the app on screen for the whole call.
 *
 * ## One source of truth
 *
 * [snapshot] is built from the live [Call], not from a timer or from what the dialer thinks it
 * asked for. The call screen subscribes to it, so a call answered from the notification, a call
 * that fails, and a call ended by the network all look the same to the UI.
 */
class PrismInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        PrismTelecom.attach(this)
    }

    override fun onDestroy() {
        PrismTelecom.detach(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        PrismTelecom.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        PrismTelecom.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        PrismTelecom.onAudioState(audioState)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        PrismTelecom.bringCallToForeground()
    }
}

object PrismTelecom {

    private const val TAG = "PrismTelecom"
    private const val CHANNEL_ONGOING = "prism_ongoing_call"
    private const val CHANNEL_RINGING = "prism_incoming_pstn"
    private const val NOTIFICATION_ONGOING = 4712

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var service: PrismInCallService? = null
    @Volatile private var appContext: Context? = null

    /** Calls currently owned by Telecom, in the order they arrived. Main thread only. */
    private val calls = mutableListOf<Call>()

    /** True while CallActivity is on screen, so a state change does not open a second copy. */
    @Volatile var uiVisible: Boolean = false

    /** The call screen's subscription. Main thread. */
    @Volatile var listener: ((Snapshot?) -> Unit)? = null

    @Volatile private var audioState: CallAudioState? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    data class Snapshot(
        val number: String,
        val name: String,
        val state: Int,
        val incoming: Boolean,
        val connectTime: Long,
        val muted: Boolean,
        val speaker: Boolean,
    ) {
        val isRinging: Boolean get() = state == Call.STATE_RINGING
        val isActive: Boolean get() = state == Call.STATE_ACTIVE
        val isFinished: Boolean
            get() = state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING
    }

    fun attach(service: PrismInCallService) {
        this.service = service
        appContext = service.applicationContext
        ensureChannels(service.applicationContext)
        publish()
    }

    fun detach(service: PrismInCallService) {
        if (this.service === service) {
            this.service = null
            audioState = null
            calls.clear()
            listener?.invoke(null)
            cancelNotification()
        }
    }

    fun isAvailable(): Boolean = service != null

    fun onCallAdded(call: Call) {
        if (calls.none { it == call }) {
            calls += call
            call.registerCallback(callCallback, main)
        }
        publish()

        val snapshot = snapshot() ?: return
        if (uiVisible) return
        val context = appContext ?: return
        val intent = if (snapshot.incoming && snapshot.isRinging) {
            CallActivity.incomingPstnIntent(context, snapshot.number, snapshot.name)
        } else {
            CallActivity.attachIntent(context, snapshot.number, snapshot.name)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { PrismLogger.logWarning(TAG, "Could not open the call screen: ${it.message}") }
    }

    fun onCallRemoved(call: Call) {
        runCatching { call.unregisterCallback(callCallback) }
        calls.removeAll { it == call }
        if (calls.isEmpty()) {
            stopRecording()
            listener?.invoke(null)
            cancelNotification()
        } else {
            publish()
        }
    }

    fun onAudioState(state: CallAudioState?) {
        audioState = state
        publish()
    }

    fun publish() {
        val current = snapshot()
        listener?.invoke(current)
        updateNotification(current)
    }

    /** The call a screen should be showing: active first, then ringing, then dialing. */
    fun snapshot(): Snapshot? {
        val live = calls.filter { it.state != Call.STATE_DISCONNECTED }
        val call = live.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull()
            ?: return null

        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val name = runCatching { details.callerDisplayName?.toString() }.getOrNull().orEmpty()
        val connectTime = runCatching { details.connectTimeMillis }.getOrDefault(0L)
        val state = audioState
        return Snapshot(
            number = number,
            name = name,
            state = call.state,
            // A ringing Telecom call is an incoming call. The call-direction field is API 29+, and
            // the state is what actually decides whether the answer button belongs on screen.
            incoming = call.state == Call.STATE_RINGING,
            connectTime = connectTime,
            muted = state?.isMuted == true,
            speaker = state?.route == CallAudioState.ROUTE_SPEAKER,
        )
    }

    fun answer() {
        calls.firstOrNull { it.state == Call.STATE_RINGING }?.let { call ->
            runCatching {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }.onFailure { PrismLogger.logWarning(TAG, "Could not answer: ${it.message}") }
            publish()
        }
    }

    fun reject() {
        calls.firstOrNull { it.state == Call.STATE_RINGING }?.let { call ->
            runCatching { call.reject(false, null) }
            publish()
        }
    }

    fun disconnect() {
        calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }?.let { call ->
            runCatching { call.disconnect() }
                .onFailure { PrismLogger.logWarning(TAG, "Could not end the call: ${it.message}") }
            publish()
        }
    }

    fun setMuted(muted: Boolean) {
        runCatching { service?.setMuted(muted) }
        publish()
    }

    fun setSpeaker(on: Boolean) {
        val route = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        runCatching { service?.setAudioRoute(route) }
        publish()
    }

    fun bringCallToForeground() {
        val snapshot = snapshot() ?: return
        val context = appContext ?: return
        val intent = if (snapshot.incoming && snapshot.isRinging) {
            CallActivity.incomingPstnIntent(context, snapshot.number, snapshot.name)
        } else {
            CallActivity.attachIntent(context, snapshot.number, snapshot.name)
        }
        runCatching { context.startActivity(intent) }
    }

    // ── Recording hooks ────────────────────────────────────────────────────

    private var recorder: CallRecorder? = null

    fun startRecording(context: Context, number: String, name: String): CallRecorder.Start {
        stopRecording()
        val next = CallRecorder(context.applicationContext, number, name)
        val result = next.start()
        if (result.started) recorder = next
        return result
    }

    fun stopRecording(): CallRecordings.Recording? {
        val active = recorder ?: return null
        recorder = null
        return active.stop()
    }

    fun recordingSource(): String? = recorder?.sourceLabel

    fun isRecording(): Boolean = recorder != null

    // ── Default dialer ─────────────────────────────────────────────────────

    fun isDefaultDialer(context: Context): Boolean {
        val telecom = context.getSystemService(android.telecom.TelecomManager::class.java) ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    fun requestDefaultDialer(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                activity.startActivityForResult(
                    roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER),
                    REQUEST_DIALER_ROLE,
                )
                return
            }
        }
        @Suppress("DEPRECATION")
        val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra("android.telecom.extra.PACKAGE_NAME", activity.packageName)
        activity.startActivityForResult(intent, REQUEST_DIALER_ROLE)
    }

    const val REQUEST_DIALER_ROLE = 4713

    // ── Ongoing call notification ──────────────────────────────────────────

    private fun updateNotification(snapshot: Snapshot?) {
        val context = appContext ?: return
        if (snapshot == null || snapshot.isFinished) {
            cancelNotification()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val ringing = snapshot.incoming && snapshot.isRinging
        val channel = if (ringing) CHANNEL_RINGING else CHANNEL_ONGOING
        val who = snapshot.name.ifBlank { snapshot.number.ifBlank { "Call" } }
        val text = when {
            ringing -> "Incoming call"
            snapshot.isActive -> "In progress"
            else -> "Calling"
        }
        val intent = if (ringing) {
            CallActivity.incomingPstnIntent(context, snapshot.number, snapshot.name)
        } else {
            CallActivity.attachIntent(context, snapshot.number, snapshot.name)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ONGOING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_call_phone_24)
            .setContentTitle(who)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending)
        if (ringing) {
            // As the default dialer, Prism is the only app that can show the call screen. A
            // notification alone is too weak when the phone is locked or another app is in front,
            // so a ringing call asks the platform for the full-screen surface.
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(false)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }
        runCatching { manager.notify(NOTIFICATION_ONGOING, builder.build()) }
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Ongoing calls",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps Prism's call screen one tap away." }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_RINGING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RINGING,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Shows Prism's call screen for a carrier call."
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }
    }

    private fun cancelNotification() {
        val context = appContext ?: return
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ONGOING)
        }
    }
}
