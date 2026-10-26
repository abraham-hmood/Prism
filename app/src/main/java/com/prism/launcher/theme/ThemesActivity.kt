package com.prism.launcher.theme

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlin.math.roundToInt

/**
 * The Prism theme gallery.
 *
 * The horizontal rail is the primary object: each card is a live miniature of a Prism surface,
 * drawn with the candidate's own palette and metrics, and the scene rotates every five seconds.
 * Tapping applies immediately. Long-pressing opens the deck of every layout that theme defines.
 */
class ThemesActivity : PrismBaseActivity() {

    private lateinit var root: FrameLayout
    private lateinit var cardsRow: LinearLayout
    private lateinit var selectedStatus: TextView
    private val previews = mutableListOf<ThemePreviewView>()
    private var scene = 0
    private var builtSignature: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val rotate = object : Runnable {
        override fun run() {
            // One step every five seconds. The old code drew a fresh random scene on every
            // refresh, and refresh runs on each resume -- so leaving a dialog and coming back
            // re-rolled the previews immediately, which read as a flicker rather than a
            // showcase. The card advances on a timer and nowhere else.
            scene = (scene + 1) % 4
            previews.forEach { it.scene = scene }
            handler.postDelayed(this, 5_000L)
        }
    }

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val theme = PrismThemes.active()
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write((PrismThemes.export(theme.id) ?: "").toByteArray())
            }
            toast("Backed up " + theme.name + ".")
        }.onFailure { toast("Could not write the backup: " + (it.message ?: "unknown error")) }
    }

    private val openImport = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("The file could not be read.")
            val imported = PrismThemes.importTheme(text).getOrThrow()
            refresh()
            toast("Imported " + imported.name + ".")
        }.onFailure { toast("Could not import: " + (it.message ?: "not a Prism theme")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PrismApp installs the store before any Activity exists. Re-installing here would reload
        // every JSON file on each recreation and bump the theme revision, which is part of this
        // screen's appearance key -- a reload loop rather than a refresh.
        root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@ThemesActivity))
        }
        root.addView(build(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(rotate)
        handler.postDelayed(rotate, 5_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(rotate)
        super.onPause()
    }

    private fun build(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, IosUi.dp(this@ThemesActivity, 34f))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@ThemesActivity, 14f),
                IosUi.dp(this@ThemesActivity, 14f),
                IosUi.dp(this@ThemesActivity, 16f),
                IosUi.dp(this@ThemesActivity, 4f),
            )
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(IosUi.label(this@ThemesActivity))
            setPadding(0, 0, IosUi.dp(this@ThemesActivity, 14f), 0)
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Themes"
            textSize = 28f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@ThemesActivity))
        })
        titles.addView(TextView(this).apply {
            text = "Prebuilt and yours. Every screen, immediately."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@ThemesActivity))
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bar)

        val rail = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(
                IosUi.dp(this@ThemesActivity, 16f),
                IosUi.dp(this@ThemesActivity, 10f),
                IosUi.dp(this@ThemesActivity, 16f),
                IosUi.dp(this@ThemesActivity, 6f),
            )
        }
        cardsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail.addView(cardsRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(rail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        selectedStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@ThemesActivity))
            setPadding(
                IosUi.dp(this@ThemesActivity, 18f),
                0,
                IosUi.dp(this@ThemesActivity, 18f),
                IosUi.dp(this@ThemesActivity, 8f),
            )
        }
        column.addView(selectedStatus)

        column.addView(actionRow(
            "Create theme" to { startActivity(ThemeEditorActivity.intent(this, null)) },
            "Edit" to { startActivity(ThemeEditorActivity.intent(this, PrismThemes.active().id)) },
        ))
        column.addView(actionRow(
            "Backup" to {
                val name = PrismThemes.active().name.replace(Regex("[^A-Za-z0-9._-]"), "-")
                createBackup.launch("prism-theme-" + name + ".json")
            },
            "Import" to { openImport.launch(arrayOf("application/json", "text/plain")) },
        ))

        column.addView(TextView(this).apply {
            text = "Tap a card to apply it everywhere. Long-press a card to fan out every layout " +
                "that theme carries; tap outside the fan to put it away."
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@ThemesActivity))
            setPadding(
                IosUi.dp(this@ThemesActivity, 18f),
                IosUi.dp(this@ThemesActivity, 10f),
                IosUi.dp(this@ThemesActivity, 18f),
                0,
            )
        })

        column.addView(TextView(this).apply {
            text = "THEME FILES"
            textSize = 11f
            letterSpacing = 0.08f
            paint.isFakeBoldText = true
            setTextColor(IosUi.tertiaryLabel(this@ThemesActivity))
            setPadding(
                IosUi.dp(this@ThemesActivity, 18f),
                IosUi.dp(this@ThemesActivity, 24f),
                IosUi.dp(this@ThemesActivity, 18f),
                IosUi.dp(this@ThemesActivity, 6f),
            )
        })
        column.addView(TextView(this).apply {
            text = PrismThemes.layoutNames().joinToString("  ·  ")
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@ThemesActivity))
            setPadding(
                IosUi.dp(this@ThemesActivity, 18f),
                0,
                IosUi.dp(this@ThemesActivity, 18f),
                IosUi.dp(this@ThemesActivity, 8f),
            )
        })

        scroll.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        return scroll
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                IosUi.dp(this@ThemesActivity, 16f),
                IosUi.dp(this@ThemesActivity, 5f),
                IosUi.dp(this@ThemesActivity, 16f),
                IosUi.dp(this@ThemesActivity, 5f),
            )
        }
        actions.forEach { (label, action) ->
            val button = IosUi.tintedButton(this, label)
            button.setOnClickListener { action() }
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = IosUi.dp(this@ThemesActivity, 4f)
                rightMargin = IosUi.dp(this@ThemesActivity, 4f)
            })
        }
        return row
    }

    private fun refresh() {
        if (!::cardsRow.isInitialized) return
        val active = PrismThemes.active()
        selectedStatus.text = "Applied: " + active.name + " · " +
            if (active.builtIn) "built in" else "your theme"
        val dark = IosUi.isDark(this)
        val themes = PrismThemes.all()
        // Rebuilding the rail on every resume replaced every card (and restarted its state) for
        // no visible reason. Only a real change -- a theme saved, imported, deleted, or the
        // light/dark switch -- needs new cards; otherwise the previews keep the scene they were
        // showing and simply continue their rotation.
        val signature = themes.joinToString("|") { it.id } + "#" + active.id + "#" + dark
        if (signature == builtSignature && cardsRow.childCount > 0) return
        builtSignature = signature
        val showing = scene
        cardsRow.removeAllViews()
        previews.clear()
        themes.forEachIndexed { index, theme ->
            cardsRow.addView(themeCard(theme, dark, index))
        }
        previews.forEach { it.scene = showing }
    }

    private fun themeCard(theme: PrismThemeSpec, dark: Boolean, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(this@ThemesActivity, 8f),
                IosUi.dp(this@ThemesActivity, 8f),
                IosUi.dp(this@ThemesActivity, 8f),
                IosUi.dp(this@ThemesActivity, 10f),
            )
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@ThemesActivity))
                cornerRadius = IosUi.dp(this@ThemesActivity, 18f).toFloat()
                val selected = theme.id == PrismThemes.active().id
                setStroke(
                    IosUi.dp(this@ThemesActivity, if (selected) 2f else 1f),
                    if (selected) IosUi.accent(this@ThemesActivity) else IosUi.separator(this@ThemesActivity),
                )
            }
            isClickable = true
            isFocusable = true
        }
        val preview = ThemePreviewView(this).apply {
            this.theme = theme
            this.dark = dark
            this.scene = scene
            layoutParams = LinearLayout.LayoutParams(
                IosUi.dp(this@ThemesActivity, 178f),
                IosUi.dp(this@ThemesActivity, 216f),
            )
        }
        previews.add(preview)
        card.addView(preview)
        card.addView(TextView(this).apply {
            text = theme.name
            textSize = 16f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@ThemesActivity))
            setPadding(0, IosUi.dp(this@ThemesActivity, 8f), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = theme.description
            textSize = 11f
            maxLines = 2
            setTextColor(IosUi.secondaryLabel(this@ThemesActivity))
            setPadding(0, IosUi.dp(this@ThemesActivity, 2f), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (theme.builtIn) "Built in · " + (theme.light.layouts.size) + " layouts" else "Yours · " + (theme.light.layouts.size) + " layouts"
            textSize = 10f
            setTextColor(IosUi.tertiaryLabel(this@ThemesActivity))
            setPadding(0, IosUi.dp(this@ThemesActivity, 5f), 0, 0)
        })
        val params = LinearLayout.LayoutParams(
            IosUi.dp(this@ThemesActivity, 194f),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            rightMargin = IosUi.dp(this@ThemesActivity, 10f)
        }
        card.layoutParams = params
        card.tag = theme.id
        card.setOnClickListener { apply(theme) }
        card.setOnLongClickListener {
            showFan(theme, dark, preview)
            true
        }
        if (theme.id == PrismThemes.active().id) {
            card.contentDescription = theme.name + ", applied"
        } else {
            card.contentDescription = theme.name
        }
        return card
    }

    private fun apply(theme: PrismThemeSpec) {
        if (theme.id == PrismThemes.active().id) {
            toast(theme.name + " is already applied.")
            return
        }
        if (PrismThemes.select(theme.id)) {
            // The application's theme listener rebuilds this screen on the new palette; calling
            // recreate() here as well would build it twice.
            toast(theme.name + " applied.")
        }
    }

    private fun showFan(theme: PrismThemeSpec, dark: Boolean, card: View) {
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val cardLocation = IntArray(2)
        card.getLocationOnScreen(cardLocation)
        val originX = cardLocation[0] - rootLocation[0] + card.width / 2f
        val originY = cardLocation[1] - rootLocation[1] + card.height / 2f
        val overlay = ThemeFanOverlay(this, theme, dark, originX, originY) { view ->
            root.removeView(view)
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ThemesActivity::class.java)
    }
}

/**
 * The deck a long press unfolds.
 *
 * A scrim, a fan of layout cards, and a collapse that returns to the card it came from. The
 * carousel can draw outside its own bounds, which is what lets the outer cards overlap nearby
 * content instead of being clipped into a neat little row.
 */
private class ThemeFanOverlay(
    context: Context,
    private val theme: PrismThemeSpec,
    private val dark: Boolean,
    private val originX: Float,
    private val originY: Float,
    private val onFinished: (ThemeFanOverlay) -> Unit,
) : FrameLayout(context) {

    private val scrim = View(context)
    private val carousel: FanCarousel
    private var collapsed = false

    init {
        clipChildren = false
        clipToPadding = false
        scrim.setBackgroundColor(((theme.dark.palette.overlay and 0x00FFFFFFL) or 0xB0000000L).toInt())
        scrim.setOnClickListener { collapse() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = TextView(context).apply {
            text = theme.name + " · all layouts"
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(if (dark) Color.WHITE else 0xFF111111.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 6)
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        })

        carousel = FanCarousel(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val cardWidth = IosUi.dp(context, 184f)
        val cardHeight = IosUi.dp(context, 262f)
        (theme.light.layouts.keys + theme.dark.layouts.keys).distinct().forEach { name ->
            carousel.addView(layoutCard(name, cardWidth, cardHeight))
        }
        addView(carousel, LayoutParams(
            LayoutParams.MATCH_PARENT,
            IosUi.dp(context, 300f),
        ).apply { gravity = Gravity.CENTER })

        alpha = 0f
        animate().alpha(1f).setDuration(140L).start()
        carousel.scaleX = 0.72f
        carousel.scaleY = 0.72f
        carousel.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
    }

    private fun layoutCard(name: String, width: Int, height: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 9)
            background = GradientDrawable().apply {
                setColor(if (dark) 0xFF1B1B22.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = IosUi.dp(context, 15f).toFloat()
                setStroke(IosUi.dp(context, 1f), if (dark) 0x33FFFFFF else 0x22000000)
            }
            elevation = IosUi.dp(context, 10f).toFloat()
        }
        card.addView(ThemePreviewView(context).apply {
            theme = this@ThemeFanOverlay.theme
            this.dark = this@ThemeFanOverlay.dark
            layoutName = name
        }, LinearLayout.LayoutParams(width - IosUi.dp(context, 12f), height - IosUi.dp(context, 42f)))
        card.addView(TextView(context).apply {
            text = name
            textSize = 11f
            setTextColor(if (dark) 0xFFE8E8F0.toInt() else 0xFF202024.toInt())
            setPadding(2, 7, 2, 0)
        })
        return card
    }

    private fun collapse() {
        if (collapsed) return
        collapsed = true
        scrim.animate().alpha(0f).setDuration(220L).start()
        val dx = originX - width / 2f
        val dy = originY - (height / 2f)
        carousel.animate()
            .scaleX(0.12f)
            .scaleY(0.12f)
            .translationX(dx)
            .translationY(dy)
            .alpha(0f)
            .setDuration(240L)
            .withEndAction { onFinished(this) }
            .start()
        animate().alpha(0f).setDuration(250L).start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) collapse()
        return true
    }
}

/** A horizontally draggable fan. Cards rotate and recede by their distance from the centre. */
private class FanCarousel(context: Context) : ViewGroup(context) {

    private var offset = 0f
    private var downX = 0f
    private var dragging = false

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val childWidth = IosUi.dp(context, 184f)
        val childHeight = IosUi.dp(context, 250f)
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childWidth = IosUi.dp(context, 184f)
        val childHeight = IosUi.dp(context, 250f)
        val gap = childWidth * 0.58f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val delta = index - offset
            val x = (width - childWidth) / 2f + delta * gap
            val y = (height - childHeight) / 2f + kotlin.math.abs(delta) * IosUi.dp(context, 12f)
            child.layout(
                x.toInt(),
                y.toInt(),
                x.toInt() + childWidth,
                y.toInt() + childHeight,
            )
            child.rotation = (delta * 6f).coerceIn(-22f, 22f)
            child.alpha = (1f - kotlin.math.abs(delta) * 0.15f).coerceIn(0.15f, 1f)
            child.scaleX = (1f - kotlin.math.abs(delta) * 0.04f).coerceIn(0.8f, 1f)
            child.scaleY = child.scaleX
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (kotlin.math.abs(dx) > 4f) dragging = true
                offset = (offset - dx / (IosUi.dp(context, 184f) * 0.58f))
                    .coerceIn(0f, (childCount - 1).coerceAtLeast(0).toFloat())
                downX = event.x
                requestLayout()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && childCount > 0) {
                    val index = offset.roundToInt().coerceIn(0, childCount - 1)
                    offset = index.toFloat()
                    requestLayout()
                } else {
                    settle()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun settle() {
        val target = offset.roundToInt().coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val start = offset
        android.animation.ValueAnimator.ofFloat(start, target.toFloat()).apply {
            duration = 160L
            addUpdateListener {
                offset = it.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }
}
