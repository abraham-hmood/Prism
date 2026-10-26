package com.prism.launcher.messaging

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * Connect WhatsApp, Facebook Messenger and Signal.
 *
 * The switches used to live in an AlertDialog opened from Settings, which set the view with no
 * padding of its own: the rows touched the dialog's edges and the explanation was squeezed into
 * the same column as the labels. A setup screen with three switches and a permission is a screen,
 * not a confirmation, so it gets the same page treatment as the other Settings destinations.
 *
 * The rows themselves are still [MessagingBridgesView], shared with onboarding. Only the frame is
 * new: a grouped-background page, a back link, and the title.
 */
class MessagingBridgesActivity : PrismBaseActivity() {

    private lateinit var bridges: MessagingBridgesView

    private fun d(value: Int): Int = IosUi.dp(this, value.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Message apps"

        val root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@MessagingBridgesActivity))
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(8), d(16), d(36))
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(scroll)
        setContentView(root)

        column.addView(TextView(this).apply {
            text = "‹ Settings"
            textSize = 17f
            setTextColor(IosUi.accent(this@MessagingBridgesActivity))
            isClickable = true
            isFocusable = true
            setPadding(0, d(6), d(16), d(6))
            setOnClickListener { finish() }
        })

        column.addView(TextView(this).apply {
            text = "Message apps"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@MessagingBridgesActivity))
            setPadding(0, 0, 0, d(12))
        })

        bridges = MessagingBridgesView(this, showHeading = false)
        column.addView(bridges, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    /**
     * Rebuilds the rows when the screen comes back from Android's notification-access settings,
     * which is where "Allow notification access" sends the user. Without this the card would keep
     * saying access is off after they granted it.
     */
    override fun onResume() {
        super.onResume()
        if (::bridges.isInitialized) bridges.render()
    }
}
