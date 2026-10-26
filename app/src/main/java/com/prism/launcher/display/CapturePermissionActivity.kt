package com.prism.launcher.display

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.prism.launcher.display.mirror.ScreenMirrorService

/**
 * Asks for screen-capture consent when ANOTHER device asked this one to show its screen.
 *
 * A handshake can only be completed on a socket thread, and Android will not start a
 * `MediaProjection` from there: the consent dialog belongs to an activity, and Android 14 requires
 * the projection to be claimed by a foreground service that is already running. This activity is
 * the smallest thing that can ask.
 *
 * It draws nothing of its own. The system's own consent dialog is the entire screen, so the
 * window behind it is translucent; finishing immediately after the answer keeps it out of the
 * back stack.
 *
 * When the phone is the one asking for a capture, the picker already has a foreground activity and
 * launches the consent dialog itself. This path exists for the reverse case -- a desktop
 * long-pressed this device in its own list.
 */
class CapturePermissionActivity : ComponentActivity() {

    private val permission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenMirrorService::class.java)
                .setAction(ScreenMirrorService.ACTION_START)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, "Screen capture permission was declined.", Toast.LENGTH_LONG).show()
            // The other device is already waiting for frames. Leaving the link open would make it
            // look connected and black rather than declined.
            WirelessDisplay.disconnect()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The request is being answered now. A notification that stays in the shade after the
        // consent dialog appears reads like a second, unanswered request.
        DisplayRequestNotification.clear(this)
        if (ScreenMirrorService.isRunning()) {
            // A capture from a previous link is still running. It only needs its address resent.
            ScreenMirrorService.announce()
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { permission.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                WirelessDisplay.disconnect()
                finish()
            }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
