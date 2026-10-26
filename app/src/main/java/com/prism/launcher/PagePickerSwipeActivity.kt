package com.prism.launcher

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import com.prism.launcher.onboarding.SwipeTriggerSetupView

/**
 * Change the gesture that opens the page picker.
 *
 * Deliberately small and separate from the tour: the tour uses the same capture view inline, and
 * this screen is simply the place to change or reset what was chosen there. Reset restores the
 * built-in overscroll rather than inventing a replacement, so "default" still means the behaviour
 * every existing install had.
 */
class PagePickerSwipeActivity : PrismBaseActivity() {

    private lateinit var status: TextView
    private lateinit var setup: SwipeTriggerSetupView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@PagePickerSwipeActivity))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@PagePickerSwipeActivity, 20f)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(column)
        setContentView(root)

        column.addView(TextView(this).apply {
            text = "Page picker swipe"
            textSize = 26f
            setTextColor(IosUi.label(this@PagePickerSwipeActivity))
        })

        column.addView(TextView(this).apply {
            text = "This is the gesture that replaces the current page. It works anywhere the page " +
                "cannot scroll in that direction; left and right swipes are accepted from the " +
                "screen edge only, so normal paging keeps working."
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@PagePickerSwipeActivity))
            setPadding(0, IosUi.dp(this@PagePickerSwipeActivity, 8f), 0, IosUi.dp(this@PagePickerSwipeActivity, 16f))
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(IosUi.tertiaryLabel(this@PagePickerSwipeActivity))
        }
        column.addView(status)
        renderStatus()

        setup = SwipeTriggerSetupView(this)
        PrismSettings.getPagePickerSwipe()?.let { setup.prefill(it) }
        setup.onCaptured = { trigger ->
            PrismSettings.setPagePickerSwipe(trigger)
            Toast.makeText(this, "Page picker: ${trigger.direction.label.lowercase()}", Toast.LENGTH_SHORT).show()
            renderStatus()
        }
        column.addView(setup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(this@PagePickerSwipeActivity, 14f) })

        column.addView(IosUi.tintedButton(this, "Reset to the built-in swipe").apply {
            setOnClickListener {
                PrismSettings.setPagePickerSwipe(null)
                setup.reset()
                renderStatus()
                Toast.makeText(this@PagePickerSwipeActivity, "Using the built-in overscroll", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(this@PagePickerSwipeActivity, 18f) })
    }

    private fun renderStatus() {
        val trigger = PrismSettings.getPagePickerSwipe()
        status.text = if (trigger == null) {
            "Currently using the built-in vertical overscroll."
        } else {
            "Current trigger: ${trigger.direction.label.lowercase()} — at least ${trigger.distanceDp} dp."
        }
    }
}
