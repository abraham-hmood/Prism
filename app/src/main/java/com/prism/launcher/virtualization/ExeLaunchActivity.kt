package com.prism.launcher.virtualization

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.SlotAssignment
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receives a `.exe` the user opened from somewhere else and hands it to the Windows runtime.
 *
 * ## Disabled in the manifest, enabled by the setting
 *
 * The component ships disabled and Settings turns it on with `setComponentEnabledSetting`. That is
 * what keeps Prism out of the "Open with" list until somebody has switched Windows mode on -- a
 * launcher that volunteers for file types it would then refuse to open is a nuisance.
 *
 * ## Why it copies the file
 *
 * A `content://` URI has no filesystem path, and the guest lives behind PRoot's remapped view of the
 * world where an Android path means nothing anyway. Copying the executable into the container's
 * `drive_c` is both the only thing that works and what a Windows user would have done: programs
 * belong on the C: drive.
 */
class ExeLaunchActivity : PrismBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildBusyView())

        val uri = intent?.data ?: intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null) {
            finishWith("No file was passed in")
            return
        }

        if (!PrismSettings.getWindowsMode()) {
            finishWith("Windows mode is off. Turn it on in Settings > OS Virtualization.")
            return
        }

        WineContainer.unavailableReason(this)?.let {
            finishWith(it)
            return
        }

        stageAndLaunch(uri)
    }

    private fun stageAndLaunch(uri: Uri) {
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val container = WineContainer.containers(this@ExeLaunchActivity).firstOrNull()
                        ?: WineContainer.createContainer(this@ExeLaunchActivity, "default")

                    val name = displayNameOf(uri)
                    contentResolver.openInputStream(uri)?.use { input ->
                        WineContainer.stageExecutable(container, name, input)
                    }
                }.getOrNull()
            }

            if (staged == null) {
                finishWith("Could not read that file")
                return@launch
            }

            PrismLogger.logInfo("PrismWine", "Staged ${staged.name} for the Windows runtime")
            handOffToVirtualizationPage(staged)
        }
    }

    /**
     * Sends the launcher to the Virtualization page with the executable to run.
     *
     * The page is where the surface, input handling and guest lifecycle already live; duplicating
     * any of that in a second activity would mean two things to keep in step.
     */
    private fun handOffToVirtualizationPage(exe: File) {
        val host = Intent(this, LauncherActivity::class.java)
            .setAction(LauncherActivity.ACTION_RUN_WINDOWS_EXE)
            .putExtra(LauncherActivity.EXTRA_EXE_PATH, exe.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val slotted = PrismSettings.let {
            com.prism.launcher.SlotPreferences().getAssignments()
                .any { assignment -> assignment is SlotAssignment.VirtualizationOs }
        }
        if (!slotted) {
            finishWith("Add the Virtualization page to a slot first, then open the file again.")
            return
        }

        startActivity(host)
        finish()
    }

    private fun displayNameOf(uri: Uri): String {
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.endsWith(".exe", true) }
            ?.let { return it }
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "program.exe"
    }

    private fun buildBusyView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(IosUi.groupedBackground(this@ExeLaunchActivity))
        val pad = IosUi.dp(this@ExeLaunchActivity, 28f)
        setPadding(pad, pad, pad, pad)
        addView(TextView(this@ExeLaunchActivity).apply {
            text = "Preparing the Windows runtime…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(this@ExeLaunchActivity))
        })
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
