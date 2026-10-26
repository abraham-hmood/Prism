package com.prism.launcher.agentic

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Asks for the runtime permission an agentic tool needs, then disappears.
 *
 * ## Why an activity and not a request from the tool
 *
 * `requestPermissions` is an Activity API, and `make_call` runs in the agent loop with no window
 * of its own. The consequence was that `CALL_PHONE` was declared in the manifest but never
 * requested, so the tool was permanently blocked with a message telling the user to grant it by
 * hand — in a screen most phones no longer offer for a permission this app never asked for.
 *
 * A transparent host rather than routing through Settings: one tap, the system prompt, done. The
 * activity finishes in `onRequestPermissionsResult`, so it never stays in the task stack.
 */
class AgentPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = intent.getStringExtra(EXTRA_PERMISSION) ?: Manifest.permission.CALL_PHONE

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The agent tool re-checks the grant on its next call; there is nothing to report back
        // into a conversation that is not attached to this window.
        finish()
    }

    companion object {
        const val EXTRA_PERMISSION = "permission"
        private const val REQUEST_CODE = 4401
    }
}
