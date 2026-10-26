package com.prism.launcher.photopix

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.core.PrismImage
import com.prism.launcher.nora.IosUi
import com.prism.launcher.photos.PhotoBackup
import com.prism.launcher.photos.PhotoRepository
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import java.io.File

/**
 * PhotoPix as a desktop page.
 *
 * The editor is the same [PhotoPixView] the activity shows -- the page only adds the way to open a
 * photo on it. Starting on a blank white canvas rather than an empty frame means every tool works
 * immediately: a page that says "open a photo first" is a page you cannot use.
 */
class PhotoPixPageView(context: Context) : FrameLayout(context) {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var editor: PhotoPixView

    init {
        setBackgroundColor(palette.background.toInt())
        editor = makeEditor(PrismImage.blank(1280, 960, 0xFFFFFFFF.toInt()))
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val open = TextView(context).apply {
            text = "Open photo"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent.toInt())
            setPadding(size(14), size(8), size(14), size(8))
            background = GradientDrawable().apply {
                cornerRadius = size(16).toFloat()
                setColor(palette.accent.toInt())
            }
            contentDescription = "Open a photo on the PhotoPix canvas"
            setOnClickListener { pickPhoto() }
        }
        addView(open, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = size(54)
            rightMargin = size(14)
        })
    }

    private fun makeEditor(image: PrismImage): PhotoPixView =
        PhotoPixView(context, image, PhotoPixActivity.MODE_EDIT)

    private fun pickPhoto() {
        val entries = runCatching { PhotoRepository.scan(context) }.getOrDefault(emptyList())
        val images = entries.filter { it.kind == PhotoBackup.Kind.IMAGE }.take(40)
        if (images.isEmpty()) {
            Toast.makeText(context, "No photos on this device yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = images.map { it.name }.toTypedArray()
        PrismDialogs.Builder(context)
            .setTitle("Open on the canvas")
            .setItems(names) { _, which ->
                val entry = images[which]
                Toast.makeText(context, "Opening ${entry.name}", Toast.LENGTH_SHORT).show()
                Thread({
                    val image = PhotoRepository.decode(context, entry, 4096)
                    post {
                        if (image == null) {
                            Toast.makeText(context, "That photo could not be decoded", Toast.LENGTH_SHORT).show()
                        } else {
                            replaceEditor(image)
                        }
                    }
                }, "photopix-page-open").start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun replaceEditor(image: PrismImage) {
        removeView(editor)
        editor = makeEditor(image)
        addView(editor, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Saves the current document to cache so the full-screen activity can be opened on it. */
    fun openInActivity() {
        val file = File(context.cacheDir, "photopix-page-${System.currentTimeMillis()}.png")
        val image = editor.doc().flatten()
        if (com.prism.launcher.platform.AndroidImageCodec.encodePng(image, file)) {
            PhotoPixActivity.start(context, file.absolutePath, PhotoPixActivity.MODE_EDIT)
        }
    }

    fun onBack(): Boolean = editor.onBack()

    private fun size(dp: Int): Int = IosUi.dp(context, dp.toFloat())
}
