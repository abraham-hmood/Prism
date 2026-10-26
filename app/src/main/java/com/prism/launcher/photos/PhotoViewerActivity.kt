package com.prism.launcher.photos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.core.PrismImage
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import com.prism.launcher.photopix.PhotoPixActivity
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeSkin
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One photo, full screen.
 *
 * ## The chrome starts hidden
 *
 * The request was explicit and it is also the right behaviour: opening a photo shows the photo, and
 * the first tap brings the controls. A viewer that opens with a toolbar over the top of the
 * picture is asking the user to dismiss it before they can look at what they came for.
 *
 * ## Eight edit actions and a pencil
 *
 * The bar is deliberately about EDITING, not sharing: crop, mark, rotate, adjust, filter, text,
 * export and share, with the pencil in the middle as the way into the full editor. Sharing and
 * information are one tap away rather than competing with the tools.
 *
 * ## A photo from another device
 *
 * A tile listed by a trusted peer has no local file. The viewer fetches its chunks through
 * [PhotoBackup] on a background thread and shows progress; the same loader handles a photo whose
 * original was deleted because the vault still has a copy.
 */
class PhotoViewerActivity : Activity() {

    companion object {
        private const val TAG = "PrismPhotos"
        private const val EXTRA_ID = "photo.id"
        private const val EXTRA_NAME = "photo.name"
        private const val EXTRA_MIME = "photo.mime"
        private const val EXTRA_KIND = "photo.kind"
        private const val EXTRA_URI = "photo.uri"
        private const val EXTRA_CHUNKS = "photo.chunks"
        private const val EXTRA_OWNER_IP = "photo.ownerIp"
        private const val EXTRA_OWNER_NAME = "photo.ownerName"
        private const val EXTRA_TAKEN = "photo.taken"
        private const val EXTRA_BYTES = "photo.bytes"

        /** Opens the viewer for one merged tile. */
        fun start(context: Context, entry: PhotoBackup.Entry) {
            val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_ID, entry.id)
                putExtra(EXTRA_NAME, entry.name)
                putExtra(EXTRA_MIME, entry.mime)
                putExtra(EXTRA_KIND, entry.kind.name)
                putExtra(EXTRA_URI, entry.localUri)
                putExtra(EXTRA_CHUNKS, entry.chunks)
                putExtra(EXTRA_OWNER_IP, entry.ownerIp)
                putExtra(EXTRA_OWNER_NAME, entry.ownerName)
                putExtra(EXTRA_TAKEN, entry.takenAt)
                putExtra(EXTRA_BYTES, entry.bytes)
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var picture: ImageView
    private lateinit var chrome: LinearLayout
    private lateinit var topBar: FrameLayout
    private lateinit var bottomBar: FrameLayout
    private lateinit var status: TextView
    private lateinit var playButton: TextView

    private var chromeVisible = false
    private var loaded: PrismImage? = null
    private var entry: PhotoBackup.Entry? = null
    private var rotation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = PrismThemes.modeFor(isDark()).palette

        entry = readEntry()
        root = FrameLayout(this).apply {
            setBackgroundColor(palette.background.toInt())
        }
        setContentView(root)

        picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // A tap on the photo is what reveals or hides the controls.
            setOnClickListener { toggleChrome() }
        }
        root.addView(picture)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.muted.toInt())
            gravity = Gravity.CENTER
            text = "Loading…"
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        playButton = TextView(this).apply {
            text = "▶"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(size(72), size(72), Gravity.CENTER)
            visibility = View.GONE
            contentDescription = "Play this video"
            setOnClickListener { playVideo() }
        }
        root.addView(playButton)

        buildChrome(palette)
        // Hidden at open. See the class comment.
        setChromeVisible(false)
        hideSystemBars()
        load()
    }

    // ── Chrome ────────────────────────────────────────────────────────────

    private fun buildChrome(palette: com.prism.launcher.theme.ThemePalette) {
        chrome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = FrameLayout(this).apply {
            background = verticalFade(0xCC000000.toInt(), 0x00000000)
            setPadding(size(12), size(22), size(12), size(12))
        }
        val back = iconButton(palette.text.toInt(), "‹", "Back") { finish() }
        topBar.addView(back, FrameLayout.LayoutParams(size(44), size(44), Gravity.START or Gravity.CENTER_VERTICAL))
        val name = TextView(this).apply {
            text = entry?.name ?: ""
            textSize = 14f
            setTextColor(palette.text.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
        }
        topBar.addView(name, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = size(56)
            rightMargin = size(56)
        })
        chrome.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        chrome.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        bottomBar = FrameLayout(this).apply {
            background = verticalFade(0x00000000, 0xE6000000.toInt())
            setPadding(size(6), size(14), size(6), size(24))
        }

        // Eight editing actions, four either side of the pencil. The counts are the layout: a row
        // of nine equals four + pencil + four, and the pencil is the tallest thing in the bar.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val leftActions = listOf(
            Action("⛶", "Crop") { openEditor(PhotoPixActivity.MODE_CROP) },
            Action("✎", "Mark") { openEditor(PhotoPixActivity.MODE_MARK) },
            Action("⟳", "Rotate") { openEditor(PhotoPixActivity.MODE_ROTATE) },
            Action("◑", "Adjust") { openEditor(PhotoPixActivity.MODE_ADJUST) },
        )
        val rightActions = listOf(
            Action("◍", "Filter") { openEditor(PhotoPixActivity.MODE_FILTER) },
            Action("T", "Text") { openEditor(PhotoPixActivity.MODE_TEXT) },
            Action("⇪", "Export") { exportImage() },
            Action("⤴", "Share") { shareImage() },
        )
        leftActions.forEach { action ->
            row.addView(actionView(palette, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // The pencil. Bigger, accent-filled, and centred: it is the way into PhotoPix, which is the
        // whole editing surface, and the other eight are shortcuts into it.
        val pencil = iconButton(
            color = palette.onAccent.toInt(),
            glyph = "✏",
            description = "Open PhotoPix",
            size = size(56),
        ) { openEditor(PhotoPixActivity.MODE_EDIT) }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent.toInt())
            }
        }
        row.addView(pencil, LinearLayout.LayoutParams(size(64), size(64)).apply {
            leftMargin = size(6)
            rightMargin = size(6)
        })
        rightActions.forEach { action ->
            row.addView(actionView(palette, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        bottomBar.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
        ))
        chrome.addView(bottomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(chrome, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private data class Action(val glyph: String, val label: String, val run: () -> Unit)

    private fun actionView(palette: com.prism.launcher.theme.ThemePalette, action: Action): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { action.run() }
            addView(iconButton(palette.text.toInt(), action.glyph, action.label) { action.run() })
            addView(TextView(this@PhotoViewerActivity).apply {
                text = action.label
                textSize = 10f
                setTextColor(palette.text.toInt())
                gravity = Gravity.CENTER
                setPadding(0, size(2), 0, 0)
            })
        }

    private fun iconButton(color: Int, glyph: String, description: String, size: Int = 0, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = if (glyph == "T") 18f else 20f
            gravity = Gravity.CENTER
            setTextColor(color)
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            if (size > 0) layoutParams = FrameLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }

    private fun toggleChrome() = setChromeVisible(!chromeVisible)

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) showSystemBars() else hideSystemBars()
    }

    private fun verticalFade(from: Int, to: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(from, to),
    )

    // ── Loading ───────────────────────────────────────────────────────────

    private fun readEntry(): PhotoBackup.Entry {
        val kind = runCatching {
            PhotoBackup.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "IMAGE")
        }.getOrDefault(PhotoBackup.Kind.IMAGE)
        return PhotoBackup.Entry(
            id = intent.getStringExtra(EXTRA_ID) ?: "",
            name = intent.getStringExtra(EXTRA_NAME) ?: "Photo",
            mime = intent.getStringExtra(EXTRA_MIME) ?: "image/jpeg",
            kind = kind,
            width = 0,
            height = 0,
            durationMs = 0,
            takenAt = intent.getLongExtra(EXTRA_TAKEN, 0L),
            bytes = intent.getLongExtra(EXTRA_BYTES, 0L),
            chunks = intent.getIntExtra(EXTRA_CHUNKS, 0),
            origin = PhotoBackup.Origin.LOCAL,
            localUri = intent.getStringExtra(EXTRA_URI) ?: "",
            ownerIp = intent.getStringExtra(EXTRA_OWNER_IP) ?: "",
            ownerName = intent.getStringExtra(EXTRA_OWNER_NAME) ?: "",
        )
    }

    private fun load() {
        val current = entry ?: return
        Thread({
            if (current.kind == PhotoBackup.Kind.VIDEO) {
                val bitmap = PhotoRepository.thumbnail(this, current, 1600)
                runOnUiThread {
                    status.visibility = View.GONE
                    if (bitmap != null) {
                        picture.setImageBitmap(bitmap)
                        playButton.visibility = View.VISIBLE
                    } else {
                        status.text = "This video could not be shown"
                    }
                }
                return@Thread
            }
            val image = PhotoRepository.decode(this, current, 4096)
            runOnUiThread {
                status.visibility = View.GONE
                if (image == null) {
                    status.text = "This photo is not available on any of your devices right now."
                    return@runOnUiThread
                }
                loaded = image
                show(image)
            }
        }, "photo-open").start()
    }

    private fun show(image: PrismImage) {
        val bitmap = AndroidImageCodec.toBitmap(image)
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        picture.setImageBitmap(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
        playButton.visibility = if (entry?.kind == PhotoBackup.Kind.VIDEO) View.VISIBLE else View.GONE
    }

    private fun playVideo() {
        val current = entry ?: return
        val uri = PhotoRepository.playableUri(this, current) ?: run {
            status.visibility = View.VISIBLE
            status.text = "The video is not available on this device yet."
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            status.visibility = View.VISIBLE
            status.text = "No video player on this device."
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    /**
     * Hands the current pixels to PhotoPix through a cache file.
     *
     * A file rather than an in-memory hand-off because the editor is a separate activity and a
     * 12-megapixel PrismImage is 48 MB of int array -- past the Binder transaction limit by an order
     * of magnitude. The file is deleted when the editor reports back.
     */
    private fun openEditor(mode: String) {
        val image = loaded
        if (image == null) {
            status.visibility = View.VISIBLE
            status.text = "Still loading this photo."
            return
        }
        val file = File(cacheDir, "photopix-open-${System.currentTimeMillis()}.png")
        Thread({
            val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)
            runOnUiThread {
                if (!ok) {
                    status.visibility = View.VISIBLE
                    status.text = "The photo could not be opened for editing."
                    return@runOnUiThread
                }
                PhotoPixActivity.start(this, file.absolutePath, mode, entry?.id ?: "")
            }
        }, "photo-export").start()
    }

    private fun exportImage() {
        val image = loaded ?: return
        Thread({
            val directory = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES,
            )
            val file = File(directory, "Prism-${System.currentTimeMillis()}.png")
            val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)
            runOnUiThread {
                status.visibility = View.VISIBLE
                status.text = if (ok) "Saved to ${file.name} in Pictures" else "Could not save a copy"
                status.postDelayed({ status.visibility = View.GONE }, 2600)
            }
        }, "photo-export").start()
    }

    private fun shareImage() {
        val current = entry ?: return
        val uri = current.localUri.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
        if (uri == null) {
            status.visibility = View.VISIBLE
            status.text = "Only a photo on this device can be shared directly."
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = current.mime.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(share, "Share photo")) }
    }

    // ── Window ────────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun showSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun size(dp: Int): Int = IosUi.dp(this, dp.toFloat())

    override fun onBackPressed() {
        if (chromeVisible) {
            setChromeVisible(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
