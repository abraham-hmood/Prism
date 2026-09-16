package com.prism.launcher.writer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone, on behalf of the keyboard.
 *
 * ## Why a whole Activity exists for one permission
 *
 * `InputMethodService` is a Service, not an Activity, and `requestPermissions` is an Activity API.
 * A keyboard therefore CANNOT ask for anything itself — it can only declare the permission in the
 * manifest, which since Android 6 grants nothing on its own. So dictation started, the recogniser
 * checked the caller's grants, and failed with:
 *
 *     SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS  (code 9)
 *
 * which is what "speech recognition keeps failing with code 9" was. The manifest entry was present
 * and correct the whole time; nothing had ever granted it at runtime because nothing could.
 *
 * This activity is the standard way out: transparent, no layout, shows the system dialog, finishes.
 * From the user's side the keyboard asks for the microphone once and then dictation works.
 */
class WriterPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 0x9111

        /** Whether the microphone is actually usable right now. */
        fun hasMicrophone(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts the request.
         *
         * NEW_TASK because the caller is a Service with no task of its own; without it this throws
         * rather than showing anything. NO_ANIMATION because a keyboard key should not appear to
         * launch an app.
         */
        fun request(context: Context) {
            val intent = Intent(context, WriterPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { context.startActivity(intent) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasMicrophone(this)) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Granted at install time on these; nothing to ask for.
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(this, "Microphone enabled — tap the mic again", Toast.LENGTH_SHORT).show()
        } else {
            // SAID PLAINLY, because a denied permission otherwise looks like a broken key: the mic
            // does nothing, forever, with no indication that a choice was made.
            Toast.makeText(
                this,
                "Dictation needs the microphone. Enable it in Settings › Apps › Prism › Permissions.",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
