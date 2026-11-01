package com.prism.launcher.email

import android.os.Bundle
import android.widget.FrameLayout
import com.prism.launcher.PrismBaseActivity

/**
 * The mail server as a screen of its own.
 *
 * THE PAGE IS THE FEATURE; this exists because Settings is a separate activity and a settings row
 * cannot switch the launcher's pager. The same [EmailServerPageView] the pager builds is mounted
 * here, so there is one dashboard and not a reduced copy of it in Settings.
 */
class EmailServerActivity : PrismBaseActivity() {

    private var page: EmailServerPageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = EmailServerPageView(this)
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
