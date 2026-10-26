package com.prism.launcher

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object PrismDialogFactory {

    fun show(
        context: Context,
        title: String,
        message: String,
        positiveText: String? = "OK",
        negativeText: String? = "Cancel",
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        showProgress: Boolean = false,
        customView: View? = null
    ): AlertDialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveAttr(context, R.attr.prismSurface))
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p)
        }

        if (message.isNotEmpty()) {
            val tv = TextView(context).apply {
                text = message
                setTextColor(resolveAttr(context, R.attr.prismTextPrimary))
                textSize = 16f
            }
            root.addView(tv)
        }

        if (showProgress) {
            val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = R.id.tag_prism_launcher_app_target // Reuse a known ID for finding it later or use a custom one
                isIndeterminate = false
                max = 100
                progress = 0
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (16 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            // Use a specific tag instead of a random ID for reliable finding
            pb.tag = "prism_progress_bar"
            root.addView(pb)
        }

        if (customView != null) {
            val container = FrameLayout(context).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (16 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            container.addView(customView)
            root.addView(container)
        }

        val builder = com.prism.launcher.theme.PrismDialogs.Builder(context, R.style.Theme_PrismSettings)
            .setTitle(title)
            .setView(root)

        if (positiveText != null) {
            builder.setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
        }
        if (negativeText != null) {
            builder.setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
        }

        val dialog = builder.create()
        dialog.show()
        // A dialog owns its own window, so the screen's tree walk cannot reach the card it floats
        // on. Apply the theme after it is attached, when that window exists.
        com.prism.launcher.theme.ThemeSkin.apply(dialog)
        return dialog
    }

    fun updateProgress(dialog: AlertDialog, percentage: Int) {
        val pb = dialog.window?.decorView?.findViewWithTag<ProgressBar>("prism_progress_bar")
        pb?.progress = percentage
    }

    private fun resolveAttr(context: Context, attr: Int): Int {
        com.prism.launcher.theme.ThemeSkin.resolveAttr(context, attr)?.let { return it }
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
