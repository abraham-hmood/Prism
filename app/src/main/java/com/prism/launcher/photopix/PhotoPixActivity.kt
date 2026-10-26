package com.prism.launcher.photopix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.prism.core.PrismImage
import com.prism.launcher.nora.IosUi
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import java.io.File

/**
 * PhotoPix, full screen.
 *
 * ## The same editor the desktop page shows
 *
 * This activity and [PhotoPixPageView] both host [PhotoPixView]; nothing about the editor itself
 * lives here. The activity adds the things an activity adds -- an entry point, a loading state for
 * the decode, back handling that lets the editor consume the press first -- and nothing else.
 *
 * ## Modes are entry points, not separate screens
 *
 * The viewer's crop, mark, text and filter buttons all open this same activity with a different
 * initial tool. That is why the mode is a string passed to the view rather than seven activities:
 * the tools share a canvas, a history and a document, and splitting them would mean six copies of
 * the state machinery.
 */
class PhotoPixActivity : Activity() {

    companion object {
        const val MODE_EDIT = "edit"
        const val MODE_CROP = "crop"
        const val MODE_MARK = "mark"
        const val MODE_ROTATE = "rotate"
        const val MODE_ADJUST = "adjust"
        const val MODE_FILTER = "filter"
        const val MODE_TEXT = "text"

        private const val EXTRA_PATH = "photopix.path"
        private const val EXTRA_MODE = "photopix.mode"
        private const val EXTRA_SOURCE = "photopix.source"

        /** Opens the editor on a PNG already written to cache. */
        fun start(context: Context, path: String, mode: String = MODE_EDIT, sourceId: String = "") {
            val intent = Intent(context, PhotoPixActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_SOURCE, sourceId)
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var editor: PhotoPixView? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = PrismThemes.modeFor(IosUi.isDark(this)).palette

        root = FrameLayout(this).apply { setBackgroundColor(palette.background.toInt()) }
        setContentView(root)

        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.muted.toInt())
            text = "Loading…"
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        goFullScreen()
        load(intent.getStringExtra(EXTRA_PATH).orEmpty())
    }

    private fun load(path: String) {
        if (path.isBlank()) {
            status.text = "No photo to edit."
            return
        }
        val file = File(path)
        if (!file.exists()) {
            status.text = "That photo is no longer on disk."
            return
        }
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_EDIT
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        Thread({
            val image: PrismImage? = AndroidImageCodec.decode(file, 4096)
            runOnUiThread {
                if (image == null) {
                    status.text = "That photo could not be decoded."
                    return@runOnUiThread
                }
                show(image, mode, source)
            }
        }, "photopix-decode").start()
    }

    private fun show(image: PrismImage, mode: String, source: String) {
        if (isFinishing) return
        loaded = true
        status.visibility = View.GONE
        val view = PhotoPixView(this, image, mode, source)
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        editor = view
    }

    private fun goFullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    @Deprecated("Android still calls this on this activity's targetSdk")
    override fun onBackPressed() {
        val view = editor
        // The editor gets the press first: an open crop or selection is what back should clear,
        // not the whole document.
        if (view != null && view.onBack()) return
        if (view != null && view.dirty) {
            PrismDialogs.Builder(this)
                .setTitle("Discard changes?")
                .setMessage("This edit has not been exported.")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep editing", null)
                .show()
            return
        }
        if (!loaded && editor == null) {
            finish()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
