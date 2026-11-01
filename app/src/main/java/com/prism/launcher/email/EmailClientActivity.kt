package com.prism.launcher.email

import android.os.Bundle
import android.widget.FrameLayout
import com.prism.launcher.PrismBaseActivity

/**
 * The mail client as a screen of its own, opened from Settings.
 *
 * Same view the launcher page uses, for the same reason [EmailServerActivity] exists: the client is
 * one implementation and Settings is only a doorway to it.
 */
class EmailClientActivity : PrismBaseActivity() {

    private var page: EmailClientPageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = EmailClientPageView(this)
        page = view
        setContentView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onDestroy() {
        page = null
        super.onDestroy()
    }
}
