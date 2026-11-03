package com.prism.launcher.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The ringtone for an incoming call, chosen in Settings → Phone and Meshnet Calls.
 *
 * ## Why Prism rings at all
 *
 * As the default phone app, Prism is the one that must ring for a carrier call -- Android leaves
 * that to the dialer -- and a meshnet call has no platform ringer whatsoever. Both incoming-call
 * notifications are deliberately silent (a notification sound plays once, not until answered), so
 * without this nothing rang for either.
 *
 * ## Honouring the phone's own sound mode
 *
 * Silent mode rings nothing and vibrates nothing; vibrate mode vibrates only; normal mode plays
 * the ringtone on the ringtone stream (so its volume is the ringer volume the user already set)
 * and vibrates too. Playback loops until [stop].
 */
object CallRinger {

    private const val TAG = "PrismRinger"

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var vibrating = false

    /** Which call is ringing ("carrier", "mesh", "group"), so ending one never silences another. */
    @Volatile private var owner: String? = null

    /** Starts ringing for a carrier call ([mesh] false) or a meshnet voice or video call. */
    @Synchronized
    fun start(context: Context, mesh: Boolean, owner: String = if (mesh) "mesh" else "carrier") {
        stop(context)
        this.owner = owner
        val app = context.applicationContext
        val manager = app.getSystemService(AudioManager::class.java)
        val mode = manager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        vibrate(app)
        if (mode == AudioManager.RINGER_MODE_VIBRATE) return

        val uri = uriFor(app, PrismSettings.ringtoneFor(mesh))
        val media = MediaPlayer()
        val ok = runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            media.setDataSource(app, uri)
            media.isLooping = true
            media.prepare()
            media.start()
            true
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not play $uri: ${it.message}")
            false
        }
        if (ok) {
            player = media
        } else {
            runCatching { media.release() }
            // A ringtone that will not play (a deleted import) must not mean a silent call.
            if (uri != systemDefault(app)) playFallback(app)
        }
    }

    /** Stops ringing only if [owner] is what is ringing. */
    @Synchronized
    fun stopIf(context: Context?, owner: String) {
        if (this.owner == owner) stop(context)
    }

    /** Stops ringing. Safe to call when nothing is ringing. */
    @Synchronized
    fun stop(context: Context? = null) {
        owner = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (vibrating) {
            vibrating = false
            context?.let { vibrator(it.applicationContext)?.cancel() }
        }
    }

    /** The URI a stored choice plays: an imported file, a system ringtone, or the default. */
    fun uriFor(context: Context, stored: String): Uri {
        if (stored.isBlank()) return systemDefault(context)
        if (stored.startsWith("/")) {
            val file = File(stored)
            return if (file.isFile) Uri.fromFile(file) else systemDefault(context)
        }
        return runCatching { Uri.parse(stored) }.getOrNull() ?: systemDefault(context)
    }

    private fun systemDefault(context: Context): Uri =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun playFallback(context: Context) {
        val media = MediaPlayer()
        runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            media.setDataSource(context, systemDefault(context))
            media.isLooping = true
            media.prepare()
            media.start()
            player = media
        }.onFailure { runCatching { media.release() } }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun vibrate(context: Context) {
        val device = vibrator(context) ?: return
        if (!device.hasVibrator()) return
        // A ring pattern: one second on, one second off, repeating from the start.
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
            vibrating = true
        }
    }
}
