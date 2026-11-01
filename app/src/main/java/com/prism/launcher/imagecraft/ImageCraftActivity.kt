package com.prism.launcher.imagecraft

import android.content.Intent
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismBaseActivity

/**
 * What the ImageCraft notification opens.
 *
 * A REASON TO HAVE AN ACTIVITY AT ALL: the page lives inside the launcher's pager, so there is no
 * "ImageCraft" screen for a notification to launch directly. This exists so the notification does not
 * have to point at whatever activity happens to be running -- it opens Prism, and the progress it was
 * reporting is on the page, exactly as the VideoCraft notification works.
 */
class ImageCraftActivity : PrismBaseActivity() {

    override fun onResume() {
        super.onResume()
        // The launcher is the page host; this activity is a doorway and closes behind itself rather
        // than sitting in the back stack as a blank screen.
        startActivity(
            Intent(this, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }
}
