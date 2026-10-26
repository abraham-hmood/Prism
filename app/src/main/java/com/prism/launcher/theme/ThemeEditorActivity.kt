package com.prism.launcher.theme

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * The Prism theme creator.
 *
 * Two nested rows of tabs: Light/Dark choose which appearance is being edited, and
 * Drag/XML/Code choose how. The second row sits ABOVE all three panels, not inside one, so
 * switching to XML or Code cannot hide the way back.
 *
 * The three panels are three views of the same layout tree, and each one is refreshed from the
 * last committed version when it is opened: the drag canvas, the layout XML, and the Kotlin
 * source the renderers' data model is built with (see [ThemeKotlin]).
 *
 * Three horizontal rails behave like the editor's menu rail: an arrow strip folded against the
 * edge, which expands over the panel to show a list. Layout files come in from the left, the
 * drag-and-drop elements from the right while the canvas is showing, and the views inside the
 * selected layout from the right while the code is showing; only one is open at a time. The
 * preview stays a small movable window, because it is the one thing that is useful next to any
 * panel.
 */
class ThemeEditorActivity : PrismBaseActivity() {

    private enum class Panel { DRAG, XML, CODE }

    private lateinit var draft: PrismThemeSpec
    private var darkMode = false
    private var panel = Panel.DRAG
    private var selectedLayout = "settings.root"

    private lateinit var root: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var dragPanel: LinearLayout
    private lateinit var xmlPanel: LinearLayout
    private lateinit var xmlEditor: EditText
    private lateinit var codePanel: LinearLayout
    private lateinit var codeTarget: TextView
    private lateinit var codeView: TextView
    private lateinit var canvas: ThemeDragCanvas
    private lateinit var deleteZone: TextView
    private lateinit var previewPopup: FrameLayout
    private lateinit var previewBody: FrameLayout
    private lateinit var nameInput: EditText
    private lateinit var wallpaperToggle: CheckBox
    private lateinit var modeTabs: LinearLayout
    private lateinit var panelTabs: LinearLayout

    // The two horizontal rails. Collapsed they are an arrow strip; expanded they show a list and
    // lie over the panel they belong to.
    private lateinit var filesRail: LinearLayout
    private lateinit var filesArrow: TextView
    private lateinit var filesTitle: TextView
    private lateinit var filesList: LinearLayout
    private lateinit var elementsRail: LinearLayout
    private lateinit var elementsArrow: TextView
    private lateinit var elementsTitle: TextView
    private lateinit var elementsList: LinearLayout
    private var filesOpen = false
    private var elementsOpen = false

    // The code panel works off a parsed copy of the committed layout. The selected node is an
    // object from that copy, so a new tree (a layout change, an edit made elsewhere) clears it.
    private lateinit var viewsRail: LinearLayout
    private lateinit var viewsArrow: TextView
    private lateinit var viewsTitle: TextView
    private lateinit var viewsList: LinearLayout
    private var viewsOpen = false
    private var codeTree: ThemeNode? = null
    private var codeSelection: ThemeNode? = null

    // OFF BY DEFAULT. The creator always opens on drag and drop, where the canvas is the preview;
    // a floating window over it was covering the thing the user came to arrange.
    private var previewOpen = false
    private var updatingXml = false
    private var draggingOverDelete = false
    private val handler = Handler(Looper.getMainLooper())
    private val previewRefresh = Runnable { updatePreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_ID)?.let { PrismThemes.find(it) }
            ?: PrismThemes.create()
        draft = source
        selectedLayout = draft.light.layouts.keys.firstOrNull() ?: PrismThemes.layoutNames().first()
        darkMode = IosUi.isDark(this)

        root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@ThemeEditorActivity))
        }
        root.addView(build(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
        refreshAll()
    }

    private fun build(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 14f),
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 14f),
                0,
            )
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            setPadding(0, 0, IosUi.dp(this@ThemeEditorActivity, 14f), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = if (intent.hasExtra(EXTRA_ID)) "Edit theme" else "Create theme"
            textSize = 22f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@ThemeEditorActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✓"
            textSize = 26f
            paint.isFakeBoldText = true
            setTextColor(IosUi.accent(this@ThemeEditorActivity))
            gravity = Gravity.CENTER
            contentDescription = "Save theme"
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 16f),
                IosUi.dp(this@ThemeEditorActivity, 4f),
                IosUi.dp(this@ThemeEditorActivity, 4f),
                IosUi.dp(this@ThemeEditorActivity, 4f),
            )
            setOnClickListener { save() }
        })
        column.addView(header)

        nameInput = EditText(this).apply {
            hint = "Theme name"
            setSingleLine(true)
            textSize = 17f
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
            background = IosUi.fieldBackground(this@ThemeEditorActivity)
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 13f),
                IosUi.dp(this@ThemeEditorActivity, 10f),
                IosUi.dp(this@ThemeEditorActivity, 13f),
                IosUi.dp(this@ThemeEditorActivity, 10f),
            )
            setText(sourceName())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        column.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = IosUi.dp(this@ThemeEditorActivity, 16f)
            rightMargin = IosUi.dp(this@ThemeEditorActivity, 16f)
        })

        // The wallpaper belongs to the theme, not to the launcher: a theme with a strong
        // background of its own should not have a photograph behind it, while every other theme
        // leaves the desktop pages transparent. Defaults to showing the wallpaper.
        wallpaperToggle = CheckBox(this).apply {
            text = "Show wallpaper on desktop pages"
            textSize = 14f
            isChecked = draft.showWallpaper
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            buttonTintList = ColorStateList.valueOf(IosUi.accent(this@ThemeEditorActivity))
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                draft = draft.copy(showWallpaper = checked)
            }
        }
        column.addView(wallpaperToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = IosUi.dp(this@ThemeEditorActivity, 12f)
            rightMargin = IosUi.dp(this@ThemeEditorActivity, 16f)
        })

        modeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 16f),
                IosUi.dp(this@ThemeEditorActivity, 10f),
                IosUi.dp(this@ThemeEditorActivity, 16f),
                0,
            )
        }
        modeTabs.addView(tab("Light", true))
        modeTabs.addView(tab("Dark", false))
        column.addView(modeTabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // OUTSIDE the two panels, and that placement is the point: the subtabs choose a panel and
        // a panel cannot contain the control that hides it. They used to live in the drag panel,
        // so switching to XML hid the row and left no way back.
        panelTabs = buildPanelTabs()
        column.addView(panelTabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        contentHost = FrameLayout(this)
        contentHost.clipChildren = false
        contentHost.clipToPadding = false

        dragPanel = buildDragPanel()
        xmlPanel = buildXmlPanel()
        codePanel = buildCodePanel()
        listOf(dragPanel, xmlPanel, codePanel).forEach { panelView ->
            contentHost.addView(panelView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        // Added after the panels so they lie OVER whichever one is showing, as the editor's rail
        // does. The preview is added after them, so a window the user is dragging floats above.
        elementsRail = buildElementsRail()
        contentHost.addView(elementsRail, FrameLayout.LayoutParams(
            IosUi.dp(this@ThemeEditorActivity, RAIL_COLLAPSED_DP),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.TOP,
        ))
        // Shares the right edge with the elements rail. Only one of the two is ever showing,
        // because the canvas and the code panel never are.
        viewsRail = buildViewsRail()
        contentHost.addView(viewsRail, FrameLayout.LayoutParams(
            IosUi.dp(this@ThemeEditorActivity, RAIL_COLLAPSED_DP),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.TOP,
        ))
        filesRail = buildFilesRail()
        contentHost.addView(filesRail, FrameLayout.LayoutParams(
            IosUi.dp(this@ThemeEditorActivity, RAIL_COLLAPSED_DP),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.TOP,
        ))

        // Last, so the preview floats above both rails while staying over the panel.
        previewPopup = buildPreviewPopup()
        contentHost.addView(previewPopup, FrameLayout.LayoutParams(
            IosUi.dp(this@ThemeEditorActivity, 230f),
            IosUi.dp(this@ThemeEditorActivity, 300f),
            Gravity.END or Gravity.BOTTOM,
        ).apply {
            // Clear of the element rail's folded strip, so the preview is not tucked under an
            // arrow the user is about to press.
            rightMargin = dp(RAIL_COLLAPSED_DP + 10f)
            bottomMargin = dp(12f)
        })

        column.addView(contentHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        column.setPadding(0, 0, 0, IosUi.dp(this@ThemeEditorActivity, 10f))
        return column
    }

    private fun sourceName(): String = draft.name.ifBlank { "My theme" }

    private fun tab(label: String, light: Boolean): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(this@ThemeEditorActivity, 9f), 0, IosUi.dp(this@ThemeEditorActivity, 9f))
            setOnClickListener {
                if (darkMode == !light) return@setOnClickListener
                if (!commitCurrent()) return@setOnClickListener
                darkMode = !light
                refreshAll()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = IosUi.dp(this@ThemeEditorActivity, 3f)
                rightMargin = IosUi.dp(this@ThemeEditorActivity, 3f)
            }
            tag = if (light) "light" else "dark"
            paintTab(this, light)
        }

    private fun paintTab(view: TextView, light: Boolean) {
        val selected = (light && !darkMode) || (!light && darkMode)
        view.background = GradientDrawable().apply {
            setColor(if (selected) IosUi.accent(this@ThemeEditorActivity) else IosUi.fill(this@ThemeEditorActivity))
            cornerRadius = IosUi.dp(this@ThemeEditorActivity, 11f).toFloat()
        }
        view.setTextColor(if (selected) IosUi.palette(this@ThemeEditorActivity).onAccent.toInt() else IosUi.secondaryLabel(this@ThemeEditorActivity))
        view.paint.isFakeBoldText = selected
    }

    private fun buildPanelTabs(): LinearLayout {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 16f),
                IosUi.dp(this@ThemeEditorActivity, 4f),
                IosUi.dp(this@ThemeEditorActivity, 16f),
                IosUi.dp(this@ThemeEditorActivity, 4f),
            )
        }
        tabs.addView(panelButton("Drag & drop", Panel.DRAG))
        tabs.addView(panelButton("XML", Panel.XML))
        tabs.addView(panelButton("Code", Panel.CODE))
        return tabs
    }

    private fun buildDragPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val canvasWrap = FrameLayout(this)
        canvas = ThemeDragCanvas(this).apply {
            mode = currentMode()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            onTreeChanged = {
                if (this@ThemeEditorActivity.panel == Panel.DRAG) updateXmlFromCanvas()
                schedulePreview()
            }
            onSelectionChanged = { schedulePreview() }
            onDragStateChanged = { node, rawX, rawY, moving ->
                if (moving && deleteZone.visibility != View.VISIBLE) deleteZone.visibility = View.VISIBLE
                val over = overDeleteZone(rawX, rawY)
                if (over != draggingOverDelete) {
                    draggingOverDelete = over
                    paintDeleteZone()
                }
                if (moving) {
                    false
                } else {
                    if (over) {
                        canvas.removeNode(node)
                        updateXmlFromCanvas()
                    }
                    deleteZone.visibility = View.INVISIBLE
                    draggingOverDelete = false
                    paintDeleteZone()
                    over
                }
            }
        }
        canvasWrap.addView(canvas)
        panel.addView(canvasWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            // Clear of the two folded rails: their arrows must not sit on the thing being edited.
            leftMargin = dp(RAIL_COLLAPSED_DP + 6f)
            rightMargin = dp(RAIL_COLLAPSED_DP + 6f)
            topMargin = dp(6f)
            bottomMargin = dp(6f)
        })

        deleteZone = TextView(this).apply {
            text = "Drop an element here to delete it and its children"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.destructive(this@ThemeEditorActivity))
            setPadding(0, IosUi.dp(this@ThemeEditorActivity, 13f), 0, IosUi.dp(this@ThemeEditorActivity, 13f))
            setOnDragListener { _, event ->
                // Existing elements are dragged on the canvas itself, where their raw finger
                // position is known. A palette element dropped here has not been added yet, so
                // there is nothing to delete -- consume the event rather than deleting whatever
                // happened to be selected.
                event.action == DragEvent.ACTION_DROP
            }
            visibility = View.INVISIBLE
            paintDeleteZone()
        }
        panel.addView(deleteZone, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = IosUi.dp(this@ThemeEditorActivity, 12f)
            rightMargin = IosUi.dp(this@ThemeEditorActivity, 12f)
            bottomMargin = IosUi.dp(this@ThemeEditorActivity, 8f)
        })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 14f),
                0,
                IosUi.dp(this@ThemeEditorActivity, 14f),
                IosUi.dp(this@ThemeEditorActivity, 6f),
            )
        }
        addRow.addView(TextView(this).apply {
            text = "Elements are on the right ›"
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(TextView(this).apply {
            text = "Preview"
            textSize = 12f
            setTextColor(IosUi.accent(this@ThemeEditorActivity))
            setOnClickListener {
                previewOpen = !previewOpen
                previewPopup.visibility = if (previewOpen) View.VISIBLE else View.GONE
            }
        })
        panel.addView(addRow)
        return panel
    }

    private fun panelButton(label: String, which: Panel): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 7f),
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 7f),
            )
            setOnClickListener {
                if (panel == which) return@setOnClickListener
                if (!commitCurrent()) return@setOnClickListener
                panel = which
                // Neither the canvas nor the code view is live while another panel covers it, so
                // both are rebuilt from the XML that was just committed.
                loadSelectedLayout()
                refreshPanelVisibility()
            }
            tag = which
            paintPanelButton(this, which)
        }

    private fun paintPanelButton(view: TextView, which: Panel) {
        val selected = panel == which
        view.background = GradientDrawable().apply {
            setColor(if (selected) IosUi.fill(this@ThemeEditorActivity) else 0x00000000)
            cornerRadius = IosUi.dp(this@ThemeEditorActivity, 8f).toFloat()
        }
        view.setTextColor(if (selected) IosUi.label(this@ThemeEditorActivity) else IosUi.secondaryLabel(this@ThemeEditorActivity))
        view.paint.isFakeBoldText = selected
    }

    private fun buildXmlPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        xmlEditor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
            hint = "Layout XML"
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@ThemeEditorActivity))
                cornerRadius = IosUi.dp(this@ThemeEditorActivity, 10f).toFloat()
            }
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 12f),
                IosUi.dp(this@ThemeEditorActivity, 12f),
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (updatingXml) return
                    schedulePreview()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        panel.addView(xmlEditor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            leftMargin = dp(RAIL_COLLAPSED_DP + 6f)
            rightMargin = dp(RAIL_COLLAPSED_DP + 6f)
            topMargin = dp(8f)
            bottomMargin = dp(8f)
        })
        return panel
    }

    /**
     * The Kotlin panel: the committed layout, written as the `ThemeNode` tree the renderers use.
     *
     * It is READ-ONLY. The XML panel is the editable one because XML is what a theme is stored
     * as; editing generated Kotlin and converting it back would need a parser for a language far
     * larger than the layout format, and a theme saved from a half-typed expression would be
     * worse than one that cannot be typed here at all. What this panel is for is reading the
     * layout in the language of the code around it, and copying a node into a plugin or a test.
     *
     * Tapping a view in the right-hand list shows that view and its children instead of the whole
     * layout; tapping it again goes back to the layout.
     */
    private fun buildCodePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        codeTarget = TextView(this).apply {
            textSize = 10f
            paint.isFakeBoldText = true
            letterSpacing = 0.06f
            setTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // With a view selected this line turns into the way back to the whole layout: the
            // code view itself is selectable text, so it cannot also be the button.
            setOnClickListener {
                if (codeSelection == null) return@setOnClickListener
                codeSelection = null
                buildViewsList()
                renderCode()
            }
        }
        panel.addView(codeTarget, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(RAIL_COLLAPSED_DP + 12f)
            rightMargin = dp(RAIL_COLLAPSED_DP + 12f)
            topMargin = dp(8f)
            bottomMargin = dp(4f)
        })

        codeView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            setTextIsSelectable(true)
            setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@ThemeEditorActivity))
                cornerRadius = dp(10f).toFloat()
            }
        }
        val scroll = ScrollView(this)
        scroll.addView(codeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            leftMargin = dp(RAIL_COLLAPSED_DP + 6f)
            rightMargin = dp(RAIL_COLLAPSED_DP + 6f)
            topMargin = dp(2f)
            bottomMargin = dp(8f)
        })
        return panel
    }

    // ── The two rails ───────────────────────────────────────────────────────

    private fun dp(value: Float): Int = IosUi.dp(this, value)

    /**
     * A rail header's arrow.
     *
     * It points the way the list will move: a closed left rail opens rightwards (›) and a closed
     * right rail opens leftwards (‹). Once open it points back at the edge it folds into.
     */
    private fun railArrow(glyph: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 21f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setTextColor(IosUi.accent(this@ThemeEditorActivity))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun railTitle(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            paint.isFakeBoldText = true
            letterSpacing = 0.08f
            setTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
            visibility = View.GONE
            maxLines = 1
        }

    private fun railBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(IosUi.cardBackground(this@ThemeEditorActivity))
        setStroke(dp(1f), IosUi.separator(this@ThemeEditorActivity))
    }

    /**
     * The elements, on the right and only while the drag panel is the one in use.
     *
     * It used to be a floating window that could be dragged anywhere, which on a phone meant it
     * was usually covering the canvas. A rail has one position, folds to an arrow's width, and is
     * where the editor's own rail taught the user to look.
     */
    private fun buildElementsRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = railBackground()
            elevation = dp(12f).toFloat()
            clipChildren = true
            visibility = View.GONE
        }
        elementsArrow = railArrow("‹", "Show the elements") { toggleElements() }
        elementsTitle = railTitle("ELEMENTS · drag or tap")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(elementsArrow, LinearLayout.LayoutParams(dp(RAIL_COLLAPSED_DP), dp(46f)))
        header.addView(elementsTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rail.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        elementsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5f), 0, dp(6f), dp(10f))
        }
        paletteTags().forEach { tag -> elementsList.addView(paletteChip(tag), chipParams()) }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(elementsList)
        rail.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return rail
    }

    /**
     * The views inside the layout being shown as code, on the right.
     *
     * A whole layout is usually longer than the panel, and the one thing a code view cannot do is
     * point at a node. This list can: it is the same tree written out as tags, indented by depth,
     * and choosing a row narrows the code to that node and its children.
     */
    private fun buildViewsRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = railBackground()
            elevation = dp(12f).toFloat()
            clipChildren = true
            visibility = View.GONE
        }
        viewsArrow = railArrow("‹", "Show the views") { toggleViews() }
        viewsTitle = railTitle("VIEWS")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(viewsArrow, LinearLayout.LayoutParams(dp(RAIL_COLLAPSED_DP), dp(46f)))
        header.addView(viewsTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rail.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        viewsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(10f))
        }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(viewsList)
        rail.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return rail
    }

    /**
     * The layout files, on the left.
     *
     * Every layout the theme carries, in one vertical list, over the panel rather than beside it:
     * the canvas is what the user is editing, and a permanent column on a phone leaves too little
     * of it. This list is what the old FILES toggle opened -- it now has the same arrow as the
     * element rail, so both sides of the editor behave the same way.
     */
    private fun buildFilesRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = railBackground()
            elevation = dp(12f).toFloat()
            clipChildren = true
        }
        filesArrow = railArrow("›", "Show the layout files") { toggleFiles() }
        filesTitle = railTitle("LAYOUTS")
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(filesArrow, LinearLayout.LayoutParams(dp(RAIL_COLLAPSED_DP), dp(46f)))
        header.addView(filesTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rail.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        filesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(10f))
        }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(filesList)
        rail.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return rail
    }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4f) }

    private fun toggleFiles() {
        filesOpen = !filesOpen
        if (filesOpen) {
            elementsOpen = false
            viewsOpen = false
        }
        refreshRails()
    }

    private fun toggleElements() {
        elementsOpen = !elementsOpen
        if (elementsOpen) {
            filesOpen = false
            viewsOpen = false
        }
        refreshRails()
    }

    private fun toggleViews() {
        viewsOpen = !viewsOpen
        if (viewsOpen) {
            filesOpen = false
            elementsOpen = false
        }
        refreshRails()
    }

    /**
     * Applies both rails' state in one place.
     *
     * Only one can be open at a time: they lie over the same panel from opposite edges, and two
     * expanded rails on a phone would leave no canvas between them.
     */
    private fun refreshRails() {
        if (!::filesRail.isInitialized || !::viewsRail.isInitialized) return
        applyRail(filesRail, filesOpen, filesArrow, filesTitle, filesList, "›", "‹", 210f)
        // The two right-hand rails belong to different panels and share an edge, so each is only
        // built while its panel is the one showing.
        val dragPanelShown = panel == Panel.DRAG
        elementsRail.visibility = if (dragPanelShown) View.VISIBLE else View.GONE
        applyRail(elementsRail, elementsOpen && dragPanelShown, elementsArrow, elementsTitle, elementsList, "‹", "›", 186f)
        val codePanelShown = panel == Panel.CODE
        viewsRail.visibility = if (codePanelShown) View.VISIBLE else View.GONE
        applyRail(viewsRail, viewsOpen && codePanelShown, viewsArrow, viewsTitle, viewsList, "‹", "›", 186f)
    }

    private fun applyRail(
        rail: LinearLayout,
        open: Boolean,
        arrow: TextView,
        title: TextView,
        list: LinearLayout,
        closedGlyph: String,
        openGlyph: String,
        expandedDp: Float,
    ) {
        val params = rail.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = dp(if (open) expandedDp else RAIL_COLLAPSED_DP)
        rail.layoutParams = params
        arrow.text = if (open) openGlyph else closedGlyph
        arrow.contentDescription = if (open) "Collapse this list" else "Expand this list"
        title.visibility = if (open) View.VISIBLE else View.GONE
        (list.parent as? View)?.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun paletteTags(): List<String> = listOf(
        "column", "row", "card", "grid", "text", "title", "label", "button", "field",
        "image", "icon", "spacer", "divider", "toggle", "slider", "progress", "avatar",
        "app", "chip", "toolbar", "tabbar", "navrail", "fab", "list", "listItem", "webview",
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun paletteChip(tag: String): TextView =
        TextView(this).apply {
            text = tag
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(IosUi.label(this@ThemeEditorActivity))
            setPadding(dp(9f), dp(8f), dp(6f), dp(8f))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@ThemeEditorActivity))
                cornerRadius = dp(8f).toFloat()
            }
            isClickable = true
            setOnClickListener { canvas.add(tag) }
            setOnLongClickListener {
                startDragAndDrop(
                    ClipData.newPlainText("prism-theme-element", tag),
                    DragShadowBuilder(this),
                    tag,
                    0,
                )
                true
            }
        }

    private fun buildPreviewPopup(): FrameLayout {
        val popup = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@ThemeEditorActivity))
                cornerRadius = IosUi.dp(this@ThemeEditorActivity, 13f).toFloat()
                setStroke(IosUi.dp(this@ThemeEditorActivity, 1f), IosUi.separator(this@ThemeEditorActivity))
            }
            elevation = IosUi.dp(this@ThemeEditorActivity, 16f).toFloat()
            clipChildren = false
        }
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 10f),
                IosUi.dp(this@ThemeEditorActivity, 5f),
                IosUi.dp(this@ThemeEditorActivity, 5f),
                0,
            )
            setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0f
                private var startY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = popup.translationX
                            startY = popup.translationY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            popup.translationX = (startX + event.rawX - downRawX)
                                .coerceIn(-popup.left.toFloat(), (contentHost.width - popup.width - popup.left).coerceAtLeast(0).toFloat())
                            popup.translationY = (startY + event.rawY - downRawY)
                                .coerceIn(-popup.top.toFloat(), (contentHost.height - popup.height - popup.top).coerceAtLeast(0).toFloat())
                        }
                    }
                    return true
                }
            })
        }
        bar.addView(TextView(this).apply {
            text = "PREVIEW"
            textSize = 9f
            paint.isFakeBoldText = true
            letterSpacing = 0.08f
            setTextColor(IosUi.tertiaryLabel(this@ThemeEditorActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(IosUi.secondaryLabel(this@ThemeEditorActivity))
            setPadding(
                IosUi.dp(this@ThemeEditorActivity, 8f),
                0,
                IosUi.dp(this@ThemeEditorActivity, 6f),
                0,
            )
            setOnClickListener {
                previewOpen = false
                previewPopup.visibility = View.GONE
            }
        })
        wrapper.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        previewBody = FrameLayout(this)
        wrapper.addView(previewBody, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        popup.addView(wrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return popup
    }

    private fun refreshAll() {
        if (!::modeTabs.isInitialized) return
        modeTabs.children().forEach { child ->
            (child as? TextView)?.let { paintTab(it, (it.tag as? String) == "light") }
        }
        panelTabs.children().forEach { child ->
            (child as? TextView)?.let { paintPanelButton(it, it.tag as? Panel ?: return@forEach) }
        }
        refreshPanelVisibility()
        loadSelectedLayout()
        buildFileList()
        updatePreview()
        paintDeleteZone()
    }

    private fun refreshPanelVisibility() {
        dragPanel.visibility = if (panel == Panel.DRAG) View.VISIBLE else View.GONE
        xmlPanel.visibility = if (panel == Panel.XML) View.VISIBLE else View.GONE
        codePanel.visibility = if (panel == Panel.CODE) View.VISIBLE else View.GONE
        if (panel == Panel.CODE) refreshCodePanel()
        previewPopup.visibility = if (previewOpen) View.VISIBLE else View.GONE
        // The tab row stays where it is whichever panel is showing, which is the point of it
        // living above all of them instead of inside one.
        refreshRails()
        panelTabs.children().forEach { child ->
            (child as? TextView)?.let { paintPanelButton(it, it.tag as? Panel ?: return@forEach) }
        }
    }

    private fun currentMode(): ThemeModeSpec = if (darkMode) draft.dark else draft.light

    private fun layoutsFor(dark: Boolean): LinkedHashMap<String, String> {
        val mode = if (dark) draft.dark else draft.light
        val result = LinkedHashMap<String, String>()
        PrismThemes.layoutNames().forEach { name ->
            result[name] = mode.layouts[name] ?: PrismThemes.defaultLayout(name, mode)
        }
        mode.layouts.forEach { (name, xml) -> if (name !in result) result[name] = xml }
        return result
    }

    private fun currentXml(): String = layoutsFor(darkMode)[selectedLayout]
        ?: "<layout name=\"$selectedLayout\"><text text=\"$selectedLayout\" /></layout>"

    private fun loadSelectedLayout() {
        val xml = currentXml()
        val tree = ThemeXml.parse(xml).getOrElse {
            ThemeNode("layout", linkedMapOf("name" to selectedLayout), mutableListOf())
        }
        canvas.mode = currentMode()
        canvas.setTree(tree)
        updatingXml = true
        xmlEditor.setText(xml)
        updatingXml = false
        if (panel == Panel.CODE) refreshCodePanel()
        schedulePreview()
    }

    private fun updateXmlFromCanvas() {
        if (!::xmlEditor.isInitialized) return
        updatingXml = true
        xmlEditor.setText(canvas.xml())
        updatingXml = false
    }

    /**
     * Rebuilds the code panel from the committed layout.
     *
     * A new tree object on every refresh is what keeps the code honest: the panel never shows a
     * node the XML no longer has. It also means a selection made against the previous tree cannot
     * survive, which is why the selected node is dropped when it is not in the new tree.
     */
    private fun refreshCodePanel() {
        if (!::codeView.isInitialized) return
        val tree = ThemeXml.parse(currentXml()).getOrElse {
            ThemeNode("layout", linkedMapOf("name" to selectedLayout), mutableListOf())
        }
        codeTree = tree
        val selection = codeSelection
        if (selection != null && ThemeKotlin.flatten(tree).none { it.first === selection }) {
            codeSelection = null
        }
        buildViewsList()
        renderCode()
    }

    private fun buildViewsList() {
        if (!::viewsList.isInitialized) return
        viewsList.removeAllViews()
        val tree = codeTree ?: return
        ThemeKotlin.flatten(tree).forEach { (node, depth) ->
            val selected = node === codeSelection
            viewsList.addView(TextView(this).apply {
                text = ThemeKotlin.label(node)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Indentation is what makes the list read as a tree rather than a bag of tags.
                setPadding(dp(6f) + dp(depth * 9f), dp(7f), dp(4f), dp(7f))
                setTextColor(
                    if (selected) IosUi.accent(this@ThemeEditorActivity)
                    else IosUi.secondaryLabel(this@ThemeEditorActivity),
                )
                paint.isFakeBoldText = selected
                background = if (selected) GradientDrawable().apply {
                    setColor(IosUi.fill(this@ThemeEditorActivity))
                    cornerRadius = dp(7f).toFloat()
                } else null
                setOnClickListener {
                    codeSelection = if (codeSelection === node) null else node
                    buildViewsList()
                    renderCode()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(1f) })
        }
    }

    private fun renderCode() {
        if (!::codeView.isInitialized) return
        val tree = codeTree
        if (tree == null) {
            codeTarget.text = "NO LAYOUT"
            codeView.text = "// Nothing to show."
            return
        }
        val selected = codeSelection
        codeView.text = if (selected != null) ThemeKotlin.expression(selected) else ThemeKotlin.layout(tree)
        val count = ThemeKotlin.size(selected ?: tree)
        val views = if (count == 1) "VIEW" else "VIEWS"
        codeTarget.text = if (selected != null) {
            "‹ " + selectedLayout.uppercase() + " · " + selected.tag.uppercase() + " · " + count + " " + views
        } else {
            selectedLayout.uppercase() + " · " + count + " " + views
        }
        codeTarget.setTextColor(
            if (selected != null) IosUi.accent(this@ThemeEditorActivity)
            else IosUi.tertiaryLabel(this@ThemeEditorActivity),
        )
    }

    private fun commitCurrent(): Boolean {
        val name = nameInput.text.toString().trim()
        if (name.isNotEmpty()) draft = draft.copy(name = name)
        val xml = if (panel == Panel.XML) xmlEditor.text.toString() else canvas.xml()
        val parsed = ThemeXml.parse(xml).getOrElse {
            toast("That XML cannot be read: " + (it.message ?: "unknown error"))
            return false
        }
        if (parsed.tag.lowercase() != "layout") {
            toast("A layout file must start with <layout>.")
            return false
        }
        setLayout(xml)
        return true
    }

    private fun setLayout(xml: String) {
        val mode = if (darkMode) draft.dark else draft.light
        val layouts = LinkedHashMap(mode.layouts)
        layouts[selectedLayout] = xml
        draft = if (darkMode) draft.copy(dark = mode.copy(layouts = layouts))
        else draft.copy(light = mode.copy(layouts = layouts))
    }

    private fun buildFileList() {
        if (!::filesList.isInitialized) return
        filesList.removeAllViews()
        layoutsFor(darkMode).keys.forEach { name ->
            filesList.addView(TextView(this).apply {
                text = name
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(
                    dp(8f),
                    dp(9f),
                    dp(6f),
                    dp(9f),
                )
                setTextColor(if (name == selectedLayout) IosUi.accent(this@ThemeEditorActivity) else IosUi.secondaryLabel(this@ThemeEditorActivity))
                paint.isFakeBoldText = name == selectedLayout
                background = if (name == selectedLayout) GradientDrawable().apply {
                    setColor(IosUi.fill(this@ThemeEditorActivity))
                    cornerRadius = dp(8f).toFloat()
                } else null
                setOnClickListener {
                    if (!commitCurrent()) return@setOnClickListener
                    selectedLayout = name
                    codeSelection = null
                    loadSelectedLayout()
                    buildFileList()
                    if (panel == Panel.CODE) refreshCodePanel()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(2f) })
        }
    }

    private fun paintDeleteZone() {
        if (!::deleteZone.isInitialized) return
        deleteZone.background = GradientDrawable().apply {
            setColor(
                if (draggingOverDelete) (IosUi.destructive(this@ThemeEditorActivity) and 0x00FFFFFF) or 0x33FF0000
                else (IosUi.destructive(this@ThemeEditorActivity) and 0x00FFFFFF) or 0x12000000
            )
            cornerRadius = IosUi.dp(this@ThemeEditorActivity, 10f).toFloat()
            setStroke(
                IosUi.dp(this@ThemeEditorActivity, 1f),
                (IosUi.destructive(this@ThemeEditorActivity) and 0x00FFFFFF) or 0x66FF0000,
            )
        }
    }

    private fun overDeleteZone(rawX: Float, rawY: Float): Boolean {
        if (!::deleteZone.isInitialized) return false
        val location = IntArray(2)
        deleteZone.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + deleteZone.width &&
            rawY >= location[1] && rawY <= location[1] + deleteZone.height
    }

    private fun schedulePreview() {
        handler.removeCallbacks(previewRefresh)
        handler.postDelayed(previewRefresh, 220L)
    }

    private fun updatePreview() {
        if (!::previewBody.isInitialized) return
        previewBody.removeAllViews()
        val xml = if (panel == Panel.XML) xmlEditor.text.toString() else canvas.xml()
        val view = runCatching {
            AndroidThemeRenderer.render(this, xml, currentMode())
        }.getOrElse {
            TextView(this).apply {
                text = it.message ?: "The layout could not be read."
                textSize = 11f
                setTextColor(IosUi.destructive(this@ThemeEditorActivity))
                setPadding(
                    IosUi.dp(this@ThemeEditorActivity, 10f),
                    IosUi.dp(this@ThemeEditorActivity, 10f),
                    IosUi.dp(this@ThemeEditorActivity, 10f),
                    IosUi.dp(this@ThemeEditorActivity, 10f),
                )
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(view)
        }
        previewBody.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            setMargins(
                IosUi.dp(this@ThemeEditorActivity, 8f),
                0,
                IosUi.dp(this@ThemeEditorActivity, 8f),
                IosUi.dp(this@ThemeEditorActivity, 8f),
            )
        })
    }

    private fun save() {
        if (!commitCurrent()) return
        if (nameInput.text.isBlank()) {
            toast("Give the theme a name.")
            return
        }
        val saved = PrismThemes.save(draft).getOrElse {
            toast(it.message ?: "The theme could not be saved.")
            return
        }
        PrismThemes.select(saved.id)
        toast("Saved and applied " + saved.name + ".")
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    override fun onDestroy() {
        handler.removeCallbacks(previewRefresh)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "theme_id"

        /** The arrow strip a rail folds down to. Wide enough for a thumb, narrow enough to edit past. */
        private const val RAIL_COLLAPSED_DP = 34f

        fun intent(context: Context, id: String?): Intent =
            Intent(context, ThemeEditorActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            }
    }
}
