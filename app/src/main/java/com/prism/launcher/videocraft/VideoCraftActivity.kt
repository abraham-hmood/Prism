package com.prism.launcher.videocraft

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import com.prism.launcher.PrismBaseActivity

/** Hosts VideoCraft outside the pager, which is what the render notification opens. */
class VideoCraftActivity : PrismBaseActivity() {

    companion object {
        const val EXTRA_ID = "videocraft.project"

        fun start(context: Context, projectId: String? = null) {
            val intent = Intent(context, VideoCraftActivity::class.java).apply {
                putExtra(EXTRA_ID, projectId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var page: VideoCraftPageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = VideoCraftPageView(this)
        setContentView(
            page,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        intent.getStringExtra(EXTRA_ID)?.let { page.openProject(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ID)?.let { page.openProject(it) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (page.onBack()) return
        super.onBackPressed()
    }
}
