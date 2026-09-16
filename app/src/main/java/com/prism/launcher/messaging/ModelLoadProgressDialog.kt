package com.prism.launcher.messaging

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.prism.launcher.PrismFontEngine
import com.prism.launcher.nora.IosUi

/**
 * A small centered iOS-style "loading sheet" -- a rounded card with a title, a thin iOS-style
 * progress track (see [IosUi.progressDrawable]), and an updatable status line underneath it.
 * Shown while a local model (text GGUF or image) is being imported and warmed up, reporting every
 * stage the loader actually goes through -- see [ModelDownloadManager]'s import flow (byte
 * progress while copying) and [GgufInferenceService.preload]/[LocalImageService.preload] (stage
 * text while the model itself is checked against available RAM and loaded).
 *
 * Replaces the plain [android.app.ProgressDialog] look used elsewhere in this file's neighborhood
 * (e.g. `SettingsActivity.startCaptionPreview`'s captioning dialog) with the same rounded-card
 * visual language `NoraCommandPopup`/`PrismSwapSettingsActivity` already use, rather than
 * Android's stock (and, on modern Android, visually dated) determinate dialog.
 *
 * All three public methods are safe to call from any thread -- every loading stage callback this
 * is wired to ([GgufInferenceService.preload]'s `onStage`, [LocalImageService.preload]'s
 * `onStage`, [ModelDownloadManager]'s copy-progress callback) fires from a background/IO thread.
 */
class ModelLoadProgressDialog(context: Context, title: String = "Loading Model") {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dialog: Dialog
    private val statusView: TextView
    private val progressBar: ProgressBar

    init {
        val ctx = context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(ctx))
                cornerRadius = IosUi.dp(ctx, 14f).toFloat()
            }
            val p = IosUi.dp(ctx, 24f)
            setPadding(p, p, p, p)
        }

        card.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(ctx))
        })

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            progressDrawable = IosUi.progressDrawable(ctx)
            layoutParams = LinearLayout.LayoutParams(IosUi.dp(ctx, 220f), IosUi.dp(ctx, 6f)).apply {
                topMargin = IosUi.dp(ctx, 18f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(progressBar)

        statusView = TextView(ctx).apply {
            text = "Starting…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 10f), 0, 0)
        }
        card.addView(statusView)

        PrismFontEngine.applyToView(card)

        dialog = Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            window?.setLayout(IosUi.dp(ctx, 270f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun show() {
        mainHandler.post { if (!dialog.isShowing) dialog.show() }
    }

    /** [percent] null means indeterminate (no numeric progress for this stage -- e.g. the native
     * model-load call itself has no finer-grained signal to report); 0..100 shows a real bar,
     * used for the byte-counted file-copy stage. */
    fun update(status: String, percent: Int? = null) {
        mainHandler.post {
            statusView.text = status
            if (percent == null) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.isIndeterminate = false
                progressBar.progress = percent.coerceIn(0, 100)
            }
        }
    }

    fun dismiss() {
        mainHandler.post { if (dialog.isShowing) dialog.dismiss() }
    }
}
