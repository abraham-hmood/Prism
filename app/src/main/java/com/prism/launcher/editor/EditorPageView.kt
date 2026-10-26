package com.prism.launcher.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Editor page.
 *
 * ## Shape
 *
 * An icon rail down the left that expands into the menu bar, Monaco in the middle, an optional file
 * tree on the right, and a shell below. The rail collapses when the editor is touched, which is the
 * behaviour a menu on a phone has to have -- there is no room for a permanent 220dp sidebar, and a
 * menu that stays open over the code is worse than no menu.
 *
 * ## Who owns what
 *
 * Monaco owns text: editing, selection, multi-cursor, folding, syntax, and every Selection/Go item
 * that names a command. This class owns everything Monaco cannot see -- files, the tree, the shell,
 * the marketplace -- and the bridge between them. The split is deliberate: it keeps the parts that
 * are genuinely VS Code from being reimplemented badly, and the parts that are genuinely Android
 * from being pushed into a WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
class EditorPageView(context: Context) : FrameLayout(context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val rail = LinearLayout(context)

    /**
     * Extension icons at the foot of the left rail, and the second rail they expand into.
     *
     * Up to [INLINE_EXTENSION_LIMIT] extensions are a column of icon buttons in the existing rail,
     * because that is the least machinery that makes them visible. Past that, the left rail carries
     * one Extensions button and the icons live in [extensionRail] -- a collapsible list between the
     * rail and the editor, so the code is pushed right rather than covered.
     */
    private val railExtensions = LinearLayout(context)
    private val extensionRail = LinearLayout(context)
    private val webView = WebView(context)
    private val tree = FileTreePanel(context)
    private val terminal = TerminalPanel(context)
    private val treeToggle = TextView(context)
    private val setupPanel = LinearLayout(context)
    private val setupStatus = TextView(context)
    private val setupDetail = TextView(context)
    private val setupProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val setupButton = IosUi.filledButton(context, "Install editor")

    private var railExpanded = false
    private var treeVisible = false
    private var editorReady = false
    private var busy = false

    /** Open folder, when there is one. Drives the tree, completion index and the shell's cwd. */
    private var openFolder: File? = null

    /** Path of the buffer on screen. A path under [scratchRoot] means "never saved anywhere". */
    private var activePath: String? = null

    private var autoSave = false
    private var wordWrap = false
    private var columnSelection = false

    /** Jump history for Go > Back / Forward. */
    private val backStack = ArrayDeque<Pair<String, Int>>()
    private val forwardStack = ArrayDeque<Pair<String, Int>>()
    private var lastEditLocation: Pair<String, Int>? = null
    private var cursorLine = 1
    private var cursorColumn = 1

    private val breakpoints = LinkedHashMap<String, MutableSet<Int>>()

    private val scratchRoot: File get() = File(context.filesDir, "editor/scratch").apply { mkdirs() }

    // ── Extensions UI state ────────────────────────────────────────────────
    private var installedExtensions: List<ExtensionStore.Installed> = emptyList()
    private val extensionDetails = mutableMapOf<String, ExtensionStore.Details>()
    private val activeExtensions = mutableSetOf<String>()
    private val liveCommands = mutableSetOf<String>()

    /**
     * Why an extension failed to activate, by id.
     *
     * Kept because the host reports it once and the popup is usually opened later. "Has not
     * finished activating" is the wrong thing to say about an extension that threw, and the
     * exception text is the only useful next step for whoever installed it.
     */
    private val extensionErrors = mutableMapOf<String, String>()
    private var extensionLoadJob: Job? = null
    private var extensionRailShown = false

    /** The floating extension view, if one is open. Only one at a time. */
    private var extensionPopup: View? = null
    private var extensionPopupHeader: LinearLayout? = null
    private var extensionPopupId: String? = null

    /**
     * The extension's own view, rendered inside the floating popup.
     *
     * A VS Code extension draws by assigning `webview.html`. That HTML crosses the bridge and is
     * loaded in a WebView here, so the popup shows the extension's real interface -- not a summary
     * of it. The maps hold what has arrived so reopening the popup shows the current view
     * immediately, and `panelId`/`renderedHtml` keep an idle refresh from reloading the document.
     */
    private var extensionPopupContent: FrameLayout? = null
    private var extensionPopupWebView: WebView? = null
    private var extensionPopupPanel: String? = null
    private var extensionPopupRenderedHtml: String? = null
    private val extensionHtml = mutableMapOf<String, String>()            // panel id -> HTML
    private val extensionPanelOf = mutableMapOf<String, String>()         // extension id -> panel id
    private val extensionWebviewState = mutableMapOf<String, String>()    // panel id -> saved state

    /**
     * The two things an extension popup can show.
     *
     * VS Code gives an extension two surfaces: the view it draws, and the commands it contributes.
     * The popup used to show whichever one happened to exist, which meant an extension with no view
     * yet showed a command list and never switched, and an extension with a view hid its commands
     * entirely. They are now tabs, in the order VS Code itself puts them: the view first, because
     * that is what "open this extension" means.
     */
    private enum class ExtensionPopupTab { VIEW, COMMANDS }

    private var extensionPopupTab = ExtensionPopupTab.VIEW
    private var extensionPopupTabs: com.prism.launcher.nora.IosSegmentedControl? = null

    /** The command list, built lazily and kept so switching tabs does not rebuild it. */
    private var extensionPopupCommands: View? = null

    /** What the View tab shows when the extension has not drawn anything. */
    private var extensionPopupPlaceholder: View? = null

    /** Which commands the command tab was built for, so it is rebuilt only when they change. */
    private var extensionPopupCommandSignature: String = ""

    /**
     * The two containers the page is laid out in.
     *
     * DECLARED HERE, WITH THE OTHER VIEWS, AND NOT FURTHER DOWN. Kotlin runs property initialisers
     * and `init` blocks in declaration order, so a property declared after `init` is still null
     * while `init` runs. These were below it, and `buildEditorSurface()` -- called from `init` --
     * dereferenced them: the page crashed with a NullPointerException the moment it was assigned to
     * a slot. The compiler cannot catch it because the access happens inside a function rather than
     * directly in the init block, which is exactly what makes this worth a comment.
     */
    private val columns = LinearLayout(context)
    private val centre = LinearLayout(context)

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            // Monaco is downloaded into filesDir, so it cannot be an asset handler; serving it
            // through the same origin is what lets the AMD loader and its workers load at all.
            .addPathHandler("/monaco/", WebViewAssetLoader.InternalStoragePathHandler(
                context, EditorAssets.monacoDir()
            ))
            .build()
    }

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        buildEditorSurface()
        buildRail()
        buildExtensionRail()
        buildTree()
        buildSetupPanel()
        renderSetup()
        refreshExtensionButtons()
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun buildEditorSurface() {
        columns.orientation = LinearLayout.HORIZONTAL
        centre.orientation = LinearLayout.VERTICAL

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = false
            setBackgroundColor(IosUi.groupedBackground(context))
            addJavascriptInterface(Bridge(), "Prism")
            // Monaco types into a hidden <textarea>, and the IME will only open for it if the
            // WebView itself holds Android focus. Inside the desktop pager an ancestor takes focus
            // on touch and the WebView never asks for it back, which is why tapping the code did
            // nothing at all: the caret appeared, the keyboard did not.
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?,
                ): WebResourceResponse? = request?.url?.let { assetLoader.shouldInterceptRequest(it) }
            }
            // Touching the code puts the menu away: on a phone the rail covers the text it is
            // meant to act on.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (railExpanded) collapseRail()
                    if (!view.hasFocus()) view.requestFocus()
                }
                false
            }
        }

        val editorArea = FrameLayout(context)
        editorArea.addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        centre.addView(editorArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        terminal.visibility = View.GONE
        centre.addView(
            terminal,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(context, 220f))
        )

        columns.addView(centre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(columns, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // The tree's handle, pinned to the right edge. Only meaningful with a folder open, so it
        // stays hidden until there is one -- an arrow that opens an empty panel is a dead control.
        treeToggle.apply {
            text = "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            setBackgroundColor(IosUi.cardBackground(context))
            visibility = View.GONE
            setOnClickListener { toggleTree() }
        }
        addView(
            treeToggle,
            LayoutParams(IosUi.dp(context, 26f), IosUi.dp(context, 54f), Gravity.END or Gravity.CENTER_VERTICAL)
        )
    }

    private fun buildTree() {
        tree.visibility = View.GONE
        tree.onFileChosen = { file -> openFile(file) }
        columns.addView(
            tree,
            LinearLayout.LayoutParams(IosUi.dp(context, 230f), LinearLayout.LayoutParams.MATCH_PARENT)
        )
    }

    // ── The rail ───────────────────────────────────────────────────────────

    private fun buildRail() {
        rail.orientation = LinearLayout.VERTICAL
        rail.setBackgroundColor(IosUi.cardBackground(context))

        val chevron = TextView(context).apply {
            text = "›"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            contentDescription = "Expand the menu"
            setPadding(0, IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
            setOnClickListener { if (railExpanded) collapseRail() else expandRail() }
        }
        rail.addView(chevron, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        EditorMenus.ALL.forEach { menu ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 11f), IosUi.dp(context, 8f), IosUi.dp(context, 11f))
                setOnClickListener { showMenu(this, menu) }
            }
            row.addView(TextView(context).apply {
                text = menu.glyph
                textSize = 17f
                setTextColor(IosUi.label(context))
                width = IosUi.dp(context, 26f)
                gravity = Gravity.CENTER
            })
            val label = TextView(context).apply {
                text = menu.title
                textSize = 15f
                setTextColor(IosUi.label(context))
                visibility = View.GONE
                setPadding(IosUi.dp(context, 8f), 0, 0, 0)
                tag = "label"
            }
            row.addView(label)
            rail.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Installed extensions live below the menus, so they are reachable whether the rail is
        // collapsed to glyphs or expanded to labels.
        railExtensions.orientation = LinearLayout.VERTICAL
        railExtensions.setPadding(0, IosUi.dp(context, 4f), 0, IosUi.dp(context, 10f))
        rail.addView(
            railExtensions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        columns.addView(
            rail, 0,
            LinearLayout.LayoutParams(IosUi.dp(context, COLLAPSED_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        )
    }

    // ── Extension rail and floating views ──────────────────────────────────

    /**
     * The second rail, hidden until the Extensions button in the left rail asks for it.
     *
     * Placed between the rail and the editor rather than on top of either, which is what makes it
     * push the code right instead of covering it. It starts hidden so a phone layout with four
     * extensions does not give up 46 dp of code width for a list the user has not opened.
     */
    private fun buildExtensionRail() {
        extensionRail.orientation = LinearLayout.VERTICAL
        extensionRail.setBackgroundColor(IosUi.cardBackground(context))
        extensionRail.visibility = View.GONE
        columns.addView(
            extensionRail, 1,
            LinearLayout.LayoutParams(
                IosUi.dp(context, COLLAPSED_DP), LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * Re-reads the installed extensions and rebuilds both places their icons can appear.
     *
     * Called on attach, on window focus (which is how returning from the marketplace is noticed),
     * and when the rail is opened, because an extension installed in another screen has to show up
     * without restarting the editor.
     */
    private fun refreshExtensionButtons() {
        extensionLoadJob?.cancel()
        extensionLoadJob = scope.launch {
            val installed = withContext(Dispatchers.IO) {
                runCatching { ExtensionStore.installed() }.getOrDefault(emptyList())
            }
            installedExtensions = installed
            extensionDetails.clear()
            installed.forEach { extension ->
                val details = withContext(Dispatchers.IO) {
                    runCatching { ExtensionStore.details(extension) }.getOrNull()
                }
                if (details != null) extensionDetails[extension.id] = details
            }

            railExtensions.removeAllViews()
            extensionRail.removeAllViews()
            if (installed.isEmpty()) {
                extensionRail.visibility = View.GONE
                extensionRailShown = false
                return@launch
            }

            if (installed.size <= INLINE_EXTENSION_LIMIT) {
                installed.forEach { extension ->
                    railExtensions.addView(extensionIconButton(extension, 38f) { openExtensionPopup(extension) })
                }
                extensionRail.visibility = View.GONE
                extensionRailShown = false
            } else {
                railExtensions.addView(extensionsToggleButton())
                renderExtensionRail()
                if (extensionRailShown) extensionRail.visibility = View.VISIBLE
            }
        }
    }

    /** The single button that opens the second rail when there are more than three extensions. */
    private fun extensionsToggleButton(): View {
        val button = TextView(context).apply {
            text = "\u29C9"      // ⧉ -- two squares, the closest plain glyph to "many panels"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            contentDescription = "Extensions"
            isClickable = true
            setOnClickListener {
                extensionRailShown = !extensionRailShown
                if (extensionRailShown) {
                    renderExtensionRail()
                    extensionRail.visibility = View.VISIBLE
                } else {
                    extensionRail.visibility = View.GONE
                }
            }
        }
        button.background = GradientDrawable().apply {
            setColor(IosUi.groupedBackground(context))
            cornerRadius = IosUi.dp(context, 10f).toFloat()
        }
        button.layoutParams = LinearLayout.LayoutParams(IosUi.dp(context, 40f), IosUi.dp(context, 40f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = IosUi.dp(context, 6f)
        }
        return button
    }

    private fun renderExtensionRail() {
        // The first child is the header; keep it and rebuild only the icons below it.
        if (extensionRail.childCount == 0) {
            extensionRail.addView(
                TextView(context).apply {
                    text = "EXT"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    letterSpacing = 0.1f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, IosUi.dp(context, 8f), 0, IosUi.dp(context, 4f))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        while (extensionRail.childCount > 1) extensionRail.removeViewAt(1)
        installedExtensions.forEach { extension ->
            extensionRail.addView(extensionIconButton(extension, 38f) { openExtensionPopup(extension) })
        }
    }

    /**
     * One extension as a square icon button.
     *
     * The manifest's `icon` when it has a readable bitmap, and its initial otherwise: plenty of
     * published extensions ship an SVG or no icon at all, and an invisible button would be worse
     * than a letter. Disabled extensions are dimmed but still open, because their view is where
     * somebody finds out why the extension is not doing anything.
     */
    private fun extensionIconButton(
        extension: ExtensionStore.Installed,
        sizeDp: Float,
        onClick: () -> Unit,
    ): View {
        val size = IosUi.dp(context, sizeDp)
        val icon = extensionIcon(extension)
        val view: View = if (icon != null) {
            ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = IosUi.dp(context, 7f)
                setPadding(pad, pad, pad, pad)
            }
        } else {
            TextView(context).apply {
                text = extension.displayName.firstOrNull()?.uppercase() ?: "E"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(IosUi.label(context))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
        view.background = GradientDrawable().apply {
            setColor(IosUi.groupedBackground(context))
            cornerRadius = IosUi.dp(context, 10f).toFloat()
            setStroke(1, IosUi.separator(context))
        }
        view.alpha = if (extension.enabled) 1f else 0.4f
        view.contentDescription = extension.displayName
        view.isClickable = true
        view.setOnClickListener { onClick() }
        view.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = IosUi.dp(context, 6f)
        }
        return view
    }

    private fun extensionIcon(extension: ExtensionStore.Installed): android.graphics.drawable.Drawable? {
        val file = extensionDetails[extension.id]?.iconFile ?: return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.let { BitmapDrawable(resources, it) }
        }.getOrNull()
    }

    /**
     * Opens an extension as a floating, movable view.
     *
     * The popup is the extension's own surface: when it has contributed a webview -- a panel, or a
     * sidebar view resolved on demand -- that HTML is rendered here, and a later `webview.html`
     * assignment replaces it, so whatever view the extension changes to is the one on screen. Only
     * an extension that has not drawn anything falls back to the metadata panel (host, state,
     * description, commands), whose command rows are live and can open the extension's first view.
     */
    private fun openExtensionPopup(extension: ExtensionStore.Installed) {
        closeExtensionPopup()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 16f).toFloat()
                setStroke(1, IosUi.separator(context))
            }
            elevation = IosUi.dp(context, 12f).toFloat()
            clipToOutline = true
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 10f), IosUi.dp(context, 6f), IosUi.dp(context, 8f))
            setBackgroundColor(IosUi.groupedBackground(context))
        }
        extensionIcon(extension)?.let { drawable ->
            header.addView(ImageView(context).apply { setImageDrawable(drawable) }, LinearLayout.LayoutParams(
                IosUi.dp(context, 20f), IosUi.dp(context, 20f),
            ).apply { marginEnd = IosUi.dp(context, 8f) })
        }
        header.addView(
            TextView(context).apply {
                text = extension.displayName
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(IosUi.label(context))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(context).apply {
                text = "\u00D7"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                isClickable = true
                setPadding(IosUi.dp(context, 10f), 0, IosUi.dp(context, 6f), 0)
                contentDescription = "Close"
                setOnClickListener { closeExtensionPopup() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // One container for everything the popup can show. The two tabs only toggle visibility,
        // so switching to Commands and back does not tear down the extension's view -- a WebView
        // that is destroyed loses whatever the extension was in the middle of doing, including a
        // sign-in flow.
        val content = FrameLayout(context)

        val tabs = com.prism.launcher.nora.IosSegmentedControl(context).apply {
            setSegments(listOf("View", "Commands"), 0)
        }
        val tabHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(context, 10f), IosUi.dp(context, 4f),
                IosUi.dp(context, 10f), IosUi.dp(context, 8f),
            )
        }
        tabHolder.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(context, 30f),
        ))

        card.addView(header)
        card.addView(tabHolder)
        card.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val params = LayoutParams(IosUi.dp(context, 310f), IosUi.dp(context, 400f)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = IosUi.dp(context, 80f)
            rightMargin = IosUi.dp(context, 12f)
        }
        addView(card, params)
        card.bringToFront()

        extensionPopup = card
        extensionPopupContent = content
        extensionPopupHeader = header
        extensionPopupId = extension.id
        extensionPopupTabs = tabs
        extensionPopupTab = ExtensionPopupTab.VIEW
        extensionPopupCommands = null
        extensionPopupPlaceholder = null
        extensionPopupCommandSignature = ""

        tabs.onSelected = { index ->
            extensionPopupTab = if (index == 0) ExtensionPopupTab.VIEW
                else ExtensionPopupTab.COMMANDS
            if (extensionPopupTab == ExtensionPopupTab.VIEW) requestExtensionView(extension.id)
            renderExtensionPopup(extension)
        }

        makePopupDraggable(card, header)
        renderExtensionPopup(extension)

        // A Node-only extension on a device without the runtime cannot activate, and asking it
        // for a view would only produce the same nothing. Start the host instead: that is what
        // surfaces the install offer and, once installed, activates the extension properly.
        if (extension.enabled && extension.nodeEntry != null && !extension.nodeReady) {
            startNodeHost()
        }

        // An extension that was never activated has nothing to show and no commands to run, and
        // that is not a reason to present an empty window: activate it, then ask what it draws.
        if (extension.enabled && extension.id !in activeExtensions) {
            activateExtension(extension)
        } else {
            requestExtensionView(extension.id)
        }
    }

    /** Drags [card] by its [handle], keeping it inside the editor page. */
    private fun makePopupDraggable(card: View, handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        handle.isClickable = true
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = card.translationX
                    startY = card.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (width - card.left - card.width).toFloat().coerceAtLeast(0f)
                    val maxY = (height - card.top - card.height).toFloat().coerceAtLeast(0f)
                    card.translationX = (startX + (event.rawX - downX))
                        .coerceIn((-card.left).toFloat(), maxX)
                    card.translationY = (startY + (event.rawY - downY))
                        .coerceIn((-card.top).toFloat(), maxY)
                    true
                }
                else -> true
            }
        }
    }

    private fun renderExtensionPopup(extension: ExtensionStore.Installed) {
        val content = extensionPopupContent ?: return
        extensionPopupTabs?.select(
            if (extensionPopupTab == ExtensionPopupTab.VIEW) 0 else 1,
            animate = false,
            notify = false,
        )
        when (extensionPopupTab) {
            ExtensionPopupTab.VIEW -> renderExtensionViewTab(extension, content)
            ExtensionPopupTab.COMMANDS -> renderExtensionCommandsTab(extension, content)
        }
    }

    /**
     * The View tab: the extension's own surface, or an explanation of why there isn't one.
     *
     * The commands are deliberately NOT folded in here. Showing a command list in place of a view
     * is what made the popup look like an extension browser rather than the extension itself; a
     * view that has not been drawn yet gets a sentence and a way to the Commands tab.
     */
    private fun renderExtensionViewTab(
        extension: ExtensionStore.Installed,
        content: FrameLayout,
    ) {
        extensionPopupCommands?.visibility = View.GONE
        val panelId = extensionPanelOf[extension.id]
        val html = panelId?.let { extensionHtml[it] }
        if (panelId != null && !html.isNullOrBlank()) {
            showExtensionView(extension, content, panelId, html)
        } else {
            showExtensionPlaceholder(extension, content)
        }
    }

    /**
     * Loads the extension's HTML into the popup.
     *
     * ONLY A CHANGED DOCUMENT IS RELOADED. The popup refreshes on activation and command events
     * too, and reloading a webview for those would throw away whatever the user had typed into it.
     * When the extension assigns `webview.html` again -- or switches to a different panel -- the
     * panel id or HTML differs and the new document replaces the old, which is the behaviour the
     * user asked for: whatever view the extension changes to is the one on screen.
     */
    private fun showExtensionView(
        extension: ExtensionStore.Installed,
        content: FrameLayout,
        panelId: String,
        html: String,
    ) {
        extensionPopupCommands?.visibility = View.GONE
        extensionPopupPlaceholder?.let { placeholder ->
            if (placeholder.parent === content) content.removeView(placeholder)
        }
        extensionPopupPlaceholder = null

        // A DIFFERENT PANEL MEANS A DIFFERENT DOCUMENT, but the same panel must keep its WebView:
        // an extension that updates its view by reassigning `webview.html` is re-rendered here,
        // and reloading into a fresh WebView on every keystroke of a chat view would be unusable.
        if (extensionPopupWebView == null || extensionPopupPanel != panelId) {
            extensionPopupWebView?.let { old ->
                (old.parent as? android.view.ViewGroup)?.removeView(old)
                old.destroy()
            }
            val view = buildExtensionWebview()
            extensionPopupWebView = view
            extensionPopupRenderedHtml = null
            content.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            val view = extensionPopupWebView ?: return
            if (view.parent == null) {
                content.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            view.visibility = View.VISIBLE
        }

        val view = extensionPopupWebView ?: return
        view.visibility = View.VISIBLE
        if (extensionPopupPanel == panelId && extensionPopupRenderedHtml == html) return

        extensionPopupPanel = panelId
        extensionPopupRenderedHtml = html

        // Relative resources in an extension's HTML resolve against its own directory, and its
        // absolute `file://` references (from `webview.asWebviewUri`) resolve anywhere in app
        // storage. The trailing slash matters: without it the last path segment is treated as a
        // file and every relative reference resolves one level too high.
        val base = "file://" + extension.directory.absolutePath.trimEnd('/') + "/"
        extensionPopupWebView?.loadDataWithBaseURL(
            base, prepareExtensionHtml(html, panelId), "text/html", "utf-8", null
        )
    }

    /**
     * What the View tab shows before the extension has drawn anything.
     *
     * The point of this tab is to be the extension, so an empty one gets an explanation and a way
     * to the commands rather than a command list dressed up as a view. Rebuilt only when the state
     * behind it changes, so a view that arrives a moment later does not flash it away and back.
     */
    private fun showExtensionPlaceholder(
        extension: ExtensionStore.Installed,
        content: FrameLayout,
    ) {
        extensionPopupWebView?.visibility = View.GONE
        extensionPopupCommands?.visibility = View.GONE

        val details = extensionDetails[extension.id] ?: ExtensionStore.details(extension)
        // A Node-only extension on a device without the runtime is not "still activating": it
        // cannot activate at all. That state gets its own explanation and an install button,
        // instead of a sentence promising a view that will never arrive.
        val needsNode = extension.nodeEntry != null && !extension.nodeReady
        val failure = extensionErrors[extension.id]
        val signature = extension.id + "|" + extension.enabled + "|" +
            (extension.id in activeExtensions) + "|" + needsNode + "|" + failure + "|" +
            details.commands.size

        val existing = extensionPopupPlaceholder
        if (existing != null && existing.tag == signature && existing.parent === content) {
            existing.visibility = View.VISIBLE
            return
        }
        existing?.let { if (it.parent === content) content.removeView(it) }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(context, 16f), IosUi.dp(context, 16f),
                IosUi.dp(context, 16f), IosUi.dp(context, 16f),
            )
        }
        body.addView(TextView(context).apply {
            text = if (needsNode) "Node.js is needed" else "No view yet"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
        })
        body.addView(TextView(context).apply {
            text = when {
                !extension.enabled ->
                    "This extension is disabled, so it has not drawn a view."
                needsNode ->
                    "${extension.displayName} draws its interface from Node.js code, and the " +
                        "Node.js runtime is not installed. Install it once and this view appears " +
                        "here — no restart needed."
                failure != null ->
                    "${extension.displayName} could not activate: $failure"
                extension.id !in activeExtensions ->
                    "${extension.displayName} has not finished activating, so it has no view to " +
                        "show yet. This screen will show it as soon as the extension draws one."
                details.commands.isNotEmpty() ->
                    "${extension.displayName} has not drawn a view. Commands that open one are on " +
                        "the Commands tab — a view it creates appears here."
                else ->
                    "${extension.displayName} does not draw a view of its own. It contributes " +
                        "languages, commands or settings instead."
            }
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setLineSpacing(IosUi.dp(context, 2f).toFloat(), 1f)
            setPadding(0, IosUi.dp(context, 8f), 0, 0)
        })

        if (needsNode) {
            // The one button that can turn this placeholder into a view. Installing is slow and
            // large, so it is offered, never started silently from here.
            body.addView(IosUi.tintedButton(context, "Install Node.js runtime").apply {
                setOnClickListener { installNodeRuntime() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = IosUi.dp(context, 14f) })
            body.addView(TextView(context).apply {
                text = "About ${NodeInstaller.APPROXIMATE_DOWNLOAD_MB} MB, and it stays on this " +
                    "device until you remove it."
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, IosUi.dp(context, 6f), 0, 0)
            })
        }

        if (details.commands.isNotEmpty()) {
            body.addView(IosUi.tintedButton(context, "Open Commands").apply {
                setOnClickListener {
                    extensionPopupTab = ExtensionPopupTab.COMMANDS
                    renderExtensionPopup(extension)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = IosUi.dp(context, 14f) })
        }

        val scroll = ScrollView(context).apply {
            addView(body)
            tag = signature
        }
        extensionPopupPlaceholder = scroll
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    /**
     * The Commands tab.
     *
     * Commands are executable here, which is the whole difference from the old fallback panel:
     * they used to announce that they had run and leave the popup where it was. Running one now
     * switches to the View tab and asks the extension for its view afterwards, because a command
     * that opens a panel has already opened it by the time the host answers.
     */
    private fun renderExtensionCommandsTab(
        extension: ExtensionStore.Installed,
        content: FrameLayout,
    ) {
        extensionPopupWebView?.visibility = View.GONE
        extensionPopupPlaceholder?.visibility = View.GONE

        val details = extensionDetails[extension.id] ?: ExtensionStore.details(extension)
        val signature = buildString {
            append(extension.id).append('|').append(extension.enabled).append('|')
            append(extension.id in activeExtensions).append('|')
            append(details.description).append('|')
            details.commands.forEach { command ->
                append(command.id).append(':').append(command.id in liveCommands).append(',')
            }
        }

        val existing = extensionPopupCommands
        if (existing != null && existing.tag == signature && existing.parent === content) {
            existing.visibility = View.VISIBLE
            return
        }
        existing?.let { if (it.parent === content) content.removeView(it) }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(context, 14f), IosUi.dp(context, 10f),
                IosUi.dp(context, 14f), IosUi.dp(context, 14f),
            )
        }
        if (details.description.isNotBlank()) {
            body.addView(TextView(context).apply {
                text = details.description
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setLineSpacing(IosUi.dp(context, 2f).toFloat(), 1f)
            })
            body.addView(View(context), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(context, 10f),
            ))
        }

        body.addView(popupSectionLabel("STATE"))
        body.addView(TextView(context).apply {
            text = when {
                !extension.enabled -> "Disabled -- not loaded by the editor"
                extension.id in activeExtensions -> "Active in ${hostName(extension)}"
                extension.runtime == null -> "Declarative -- contributes through its manifest"
                else -> "Waiting for ${hostName(extension)} to activate it"
            }
            textSize = 12f
            setTextColor(IosUi.label(context))
        })

        body.addView(popupSectionLabel("COMMANDS"))
        if (details.commands.isEmpty()) {
            body.addView(TextView(context).apply {
                text = "This extension declares no commands."
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
            })
        } else {
            details.commands.forEach { command ->
                body.addView(commandRow(extension, command))
            }
        }

        body.addView(TextView(context).apply {
            text = if (details.commands.isEmpty()) {
                "This extension draws no view of its own."
            } else {
                "Tap a command to run it. A view it opens appears on the View tab."
            }
            textSize = 10f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        })

        val scroll = ScrollView(context).apply {
            addView(body)
            tag = signature
        }
        extensionPopupCommands = scroll
        extensionPopupCommandSignature = signature
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    /**
     * The WebView an extension draws in.
     *
     * File access is on because extension resources are unpacked into Prism's own storage and the
     * HTML refers to them by absolute path; content access stays off because nothing here needs a
     * `content://` provider. `acquireVsCodeApi` is supplied by [prepareExtensionHtml], not by the
     * extension, so a view that talks to its extension works with no extra setup.
     */
    @Suppress("DEPRECATION")   // the file-URL XHR switch has no replacement and extensions use it
    private fun buildExtensionWebview(): WebView = WebView(context).apply {
        setBackgroundColor(IosUi.cardBackground(context))
        isFocusable = true
        isFocusableInTouchMode = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        // File-to-file XHR, which is how an extension view reads a file next to itself. Universal
        // access is deliberately NOT granted: it would also let that document reach any origin.
        settings.allowFileAccessFromFileURLs = true
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        addJavascriptInterface(WebviewBridge(), "PrismWebview")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                // A link in an extension view belongs in a browser, not in a 310dp panel.
                if (url.scheme == "file" || url.scheme == "data" || url.scheme == "about") return false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }
    }

    /**
     * The HTML an extension supplies, plus the small shim VS Code's own webviews provide.
     *
     * Injected into `<head>` rather than prepended so the document keeps its doctype and therefore
     * its standards mode -- prepending a script before `<!DOCTYPE html>` puts a page into quirks
     * mode, where an extension's CSS lays out differently from how its author tested it.
     */
    private fun prepareExtensionHtml(html: String, panelId: String): String {
        val background = hexColour(IosUi.cardBackground(context))
        val label = hexColour(IosUi.label(context))
        val state = extensionWebviewState[panelId]
        val stateLiteral = state
            ?.let { "JSON.parse(${jsString(it.replace("<", "\\u003c"))})" }
            ?: "undefined"

        // Extension webviews ship a CSP that allows their own scripts by nonce. The shim below is
        // inline, so it needs that same nonce; without one -- hashes, or `script-src` naming only
        // their bundle -- `'unsafe-inline'` is added to `script-src` alone. The rest of the policy
        // (`default-src 'none'`, `connect-src`, `img-src`) is left exactly as the extension wrote
        // it, because those are the directives that actually contain a compromised view.
        val cspTag = Regex(
            "<meta[^>]+http-equiv\\s*=\\s*[\"']?content-security-policy[\"']?[^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html)
        var nonce: String? = null
        var adjusted = html
        if (cspTag != null) {
            val contentRegex = Regex(
                "content\\s*=\\s*([\"'])(.*?)\\1",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            val contentMatch = contentRegex.find(cspTag.value)
            val content = contentMatch?.groupValues?.get(2)
            if (content != null) {
                val scriptSource = Regex("script-src([^;]*)", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
                nonce = scriptSource?.let {
                    Regex("'nonce-([^']+)'").find(it)?.groupValues?.get(1)
                } ?: Regex("'nonce-([^']+)'").find(content)?.groupValues?.get(1)
                if (nonce == null) {
                    val directive = Regex("script-src[^;]*", RegexOption.IGNORE_CASE).find(content)
                    val relaxed = when {
                        // A hash source makes browsers ignore 'unsafe-inline', so a hashed policy
                        // is replaced outright instead of extended.
                        directive != null && Regex("'(sha256|sha384|sha512)-", RegexOption.IGNORE_CASE)
                            .containsMatchIn(directive.value) ->
                            content.replace(directive.value, "script-src 'unsafe-inline' file:")
                        directive != null ->
                            content.replace(directive.value, directive.value + " 'unsafe-inline'")
                        else -> "$content; script-src 'unsafe-inline'"
                    }
                    adjusted = adjusted.replace(cspTag.value, cspTag.value.replace(content, relaxed))
                }
            }
        }
        val nonceAttribute = nonce
            ?.filter { ch -> ch.isLetterOrDigit() || ch in "+/=_- " }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { " nonce=\"$it\"" }
            .orEmpty()

        val snippet = """
            <style$nonceAttribute>
              html, body { background: $background; color: $label; font-family: -apple-system, Roboto, sans-serif; }
              ::-webkit-scrollbar { width: 6px; height: 6px; }
              ::-webkit-scrollbar-thumb { background: rgba(128,128,128,0.45); border-radius: 3px; }
            </style>
            <script$nonceAttribute>
            (function () {
              window.__prismState = $stateLiteral;
              window.acquireVsCodeApi = function () {
                var api = {
                  postMessage: function (message) {
                    try { PrismWebview.postMessage(JSON.stringify(message)); } catch (e) {}
                  },
                  getState: function () { return window.__prismState; },
                  setState: function (value) {
                    window.__prismState = value;
                    try { PrismWebview.setState(JSON.stringify(value)); } catch (e) {}
                    return value;
                  }
                };
                window.__prismApi = api;
                return api;
              };
              window.__prismReceive = function (json) {
                var value;
                try { value = JSON.parse(json); } catch (e) { value = json; }
                var event;
                try { event = new MessageEvent('message', { data: value }); }
                catch (e) { event = { data: value }; }
                window.dispatchEvent(event);
              };
            })();
            </script>
        """.trimIndent()

        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(adjusted)
        if (head != null) {
            val end = head.range.last + 1
            return adjusted.substring(0, end) + snippet + adjusted.substring(end)
        }
        val root = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(adjusted)
        if (root != null) {
            val end = root.range.last + 1
            return "<head>$snippet</head>" + adjusted.substring(0, end) + adjusted.substring(end)
        }
        return "<head>$snippet</head>" + adjusted
    }

    private fun hexColour(color: Int): String =
        String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and color)

    /**
     * The bridge from an extension's rendered view back into the extension.
     *
     * `acquireVsCodeApi().postMessage(...)` in the popup calls [postMessage]. [setState] keeps the
     * `getState`/`setState` pair alive across a reload of the view's document, which happens every
     * time the extension replaces its HTML; without it a view would silently forget its state.
     */
    private inner class WebviewBridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val panelId = extensionPopupPanel ?: return
            scope.launch {
                js("PrismEditor.webviewMessage(${jsString(panelId)}, ${jsString(json)})")
            }
        }

        @JavascriptInterface
        fun setState(json: String) {
            val panelId = extensionPopupPanel ?: return
            extensionWebviewState[panelId] = json
        }
    }

    private fun popupSectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(0, IosUi.dp(context, 14f), 0, IosUi.dp(context, 5f))
    }

    private fun commandRow(extension: ExtensionStore.Installed, command: ExtensionStore.Command): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(0, IosUi.dp(context, 7f), 0, IosUi.dp(context, 7f))
            setOnClickListener { runExtensionCommand(extension, command) }
        }
        row.addView(TextView(context).apply {
            text = (if (command.id in liveCommands) "\u25CF " else "\u25CB ") + command.title
            textSize = 12f
            setTextColor(IosUi.label(context))
        })
        row.addView(TextView(context).apply {
            text = command.id
            textSize = 9f
            setTextColor(IosUi.secondaryLabel(context))
        })
        return row
    }

    private fun hostName(extension: ExtensionStore.Installed): String =
        if (extension.runtime == ExtensionStore.RUNTIME_NODE) "the Node host" else "the web extension host"

    /**
     * Runs one of an extension's commands, and follows wherever it takes the extension.
     *
     * A command is not a button that opens a menu in VS Code: it is code that often creates or
     * reveals a panel. So the popup switches to View before running it (that is where the result
     * will be) and asks for the view again a few times afterwards. Three requests rather than one
     * because the host may still be activating, and a command that awaits its own activation
     * would otherwise be observed only before it did anything.
     */
    private fun runExtensionCommand(
        extension: ExtensionStore.Installed,
        command: ExtensionStore.Command,
    ) {
        extensionPopupTab = ExtensionPopupTab.VIEW
        renderExtensionPopup(extension)
        if (extension.enabled && extension.id !in activeExtensions) activateExtension(extension)
        js("PrismEditor.runExtensionCommand(${jsString(command.id)})")
        toast("Running ${command.title}")
        scheduleViewRequests(extension.id)
    }

    /** Asks the extension for whatever it is showing, once the editor is still open on it. */
    private fun requestExtensionView(extensionId: String) {
        if (extensionId.isBlank()) return
        js("PrismEditor.openExtensionView(${jsString(extensionId)})")
    }

    private fun scheduleViewRequests(extensionId: String) {
        listOf(500L, 1500L, 3000L).forEach { wait ->
            postDelayed({
                if (extensionPopupId == extensionId && isAttachedToWindow) {
                    requestExtensionView(extensionId)
                }
            }, wait)
        }
    }

    /**
     * Activates one extension in its host and, if its popup is the one open, asks for its view
     * when the host has had time to build one.
     *
     * Activation is asynchronous in both hosts -- the Node host has to receive a message and run
     * `activate` -- so asking immediately reliably arrives before a view provider is registered.
     */
    private fun activateExtension(extension: ExtensionStore.Installed) {
        scope.launch {
            sendActivation(extension)
            // Activation is not one moment. A Node extension may spend seconds loading, resolving
            // modules or waiting on its own network calls before it registers a provider. Three
            // requests cover that; the host also remembers the request and resolves a provider
            // that arrives later, so a slow extension no longer ends at "No view yet".
            scheduleViewRequests(extension.id)
        }
    }

    private suspend fun sendActivation(extension: ExtensionStore.Installed) {
        val runtime = extension.runtime ?: return
        val source = withContext(Dispatchers.IO) { ExtensionStore.entrySource(extension) } ?: return
        val extra = withContext(Dispatchers.IO) { extensionContextJson(extension) }
        js(
            "PrismEditor.activateExtension(" +
                jsString(extension.id) + ", " +
                jsString(extension.directory.absolutePath) + ", " +
                jsString(source) + ", " +
                jsString(runtime) + ", " +
                jsString(extra) + ")"
        )
    }

    /** Re-draws the open popup after the extension reports a change. No-op when none is open. */
    private fun refreshExtensionPopup() {
        val id = extensionPopupId ?: return
        val extension = installedExtensions.firstOrNull { it.id == id } ?: return
        renderExtensionPopup(extension)
        extensionPopupHeader?.let { header ->
            // The name does not change, but the row is rebuilt so a newly available icon appears.
            if (header.childCount > 0) header.getChildAt(0).alpha = if (extension.enabled) 1f else 0.4f
        }
    }

    private fun closeExtensionPopup() {
        extensionPopup?.let { popup -> removeView(popup) }
        // Destroyed, not merely detached: an extension view left alive would keep its timers and
        // its bridge into a popup nobody can see, once per extension the user ever opened.
        extensionPopupWebView?.destroy()
        extensionPopupWebView = null
        extensionPopupPanel = null
        extensionPopupRenderedHtml = null
        extensionPopup = null
        extensionPopupContent = null
        extensionPopupHeader = null
        extensionPopupId = null
        extensionPopupTabs = null
        extensionPopupCommands = null
        extensionPopupPlaceholder = null
        extensionPopupCommandSignature = ""
        extensionPopupTab = ExtensionPopupTab.VIEW
    }

    private fun expandRail() {
        railExpanded = true
        setRailWidth(EXPANDED_DP)
        forEachRailLabel { it.visibility = View.VISIBLE }
    }

    private fun collapseRail() {
        railExpanded = false
        setRailWidth(COLLAPSED_DP)
        forEachRailLabel { it.visibility = View.GONE }
    }

    private fun setRailWidth(dp: Float) {
        val params = rail.layoutParams as LinearLayout.LayoutParams
        params.width = IosUi.dp(context, dp)
        rail.layoutParams = params
    }

    private inline fun forEachRailLabel(apply: (View) -> Unit) {
        for (i in 0 until rail.childCount) {
            (rail.getChildAt(i) as? LinearLayout)?.findViewWithTag<View>("label")?.let(apply)
        }
    }

    /**
     * Renders one menu as a popup.
     *
     * Built by hand rather than as a `PopupMenu` because these rows carry three things a platform
     * menu item cannot: a right-aligned shortcut, a check state, and a greyed row that still explains
     * itself when tapped.
     */
    private fun showMenu(anchor: View, menu: EditorMenus.Menu) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // OPAQUE, deliberately. This used to be IosUi.fieldBackground(), which is the 24%-alpha
            // grey iOS uses for inset text fields -- correct on top of a solid card, wrong for a
            // menu, because the expanded rail underneath showed straight through it and every row
            // was printed over a rail label. A menu has to hide what it covers.
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 12f).toFloat()
                setStroke(1, IosUi.separator(context))
            }
            val pad = IosUi.dp(context, 6f)
            setPadding(0, pad, 0, pad)
        }

        val popup = android.widget.PopupWindow(
            ScrollView(context).apply { addView(content) },
            IosUi.dp(context, MENU_WIDTH_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = IosUi.dp(context, 8f).toFloat()
            isOutsideTouchable = true
        }

        menu.items.forEach { item ->
            val enabled = item.unavailable == null
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                alpha = if (enabled) 1f else 0.45f
                setPadding(IosUi.dp(context, 16f), IosUi.dp(context, 9f), IosUi.dp(context, 14f), IosUi.dp(context, 9f))
                setOnClickListener {
                    if (!enabled) {
                        Toast.makeText(context, item.unavailable, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    popup.dismiss()
                    collapseRail()
                    perform(item)
                }
            }

            val checkMark = if (item.checkable && isChecked(item)) "✓ " else if (item.checkable) "   " else ""
            row.addView(TextView(context).apply {
                text = checkMark + item.label
                textSize = 14f
                setTextColor(IosUi.label(context))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (item.shortcut.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = item.shortcut
                    textSize = 12f
                    setTextColor(IosUi.secondaryLabel(context))
                })
            }
            content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            if (item.separatorAfter) {
                content.addView(View(context).apply {
                    setBackgroundColor(IosUi.separator(context))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = IosUi.dp(context, 5f)
                    bottomMargin = IosUi.dp(context, 5f)
                })
            }
        }

        showBesideRail(popup, anchor)
    }

    /**
     * Puts a menu to the right of the rail, never on top of it.
     *
     * The rail is collapsed to icons first. `showAsDropDown` anchored on the row put a 290dp panel
     * over a 220dp expanded rail, so the menu and the rail occupied the same pixels; and even placed
     * correctly, 220 + 290 is wider than a phone. Collapsing leaves the icons visible -- so the user
     * can still see which menu is open -- and gives the panel the room it needs.
     *
     * The vertical position is the row's own, clamped so a long menu near the bottom of the screen
     * opens upward instead of running off it.
     */
    private fun showBesideRail(popup: android.widget.PopupWindow, anchor: View) {
        val anchorLocation = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val rootLocation = IntArray(2).also { getLocationOnScreen(it) }
        collapseRail()

        val content = popup.contentView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(IosUi.dp(context, MENU_WIDTH_DP), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        val menuHeight = content.measuredHeight.coerceAtMost(height - IosUi.dp(context, 24f))
        popup.height = menuHeight

        val x = rootLocation[0] + IosUi.dp(context, COLLAPSED_DP + 6f)
        val top = anchorLocation[1]
        val lowest = rootLocation[1] + height - menuHeight - IosUi.dp(context, 12f)
        val y = top.coerceAtMost(lowest).coerceAtLeast(rootLocation[1] + IosUi.dp(context, 8f))

        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    private fun isChecked(item: EditorMenus.Item): Boolean = when (item.action) {
        EditorMenus.A_AUTOSAVE -> autoSave
        EditorMenus.A_WORD_WRAP -> wordWrap
        EditorMenus.A_COLUMN_SELECTION -> columnSelection
        else -> false
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    private fun buildSetupPanel() {
        setupPanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(IosUi.groupedBackground(context))
            val pad = IosUi.dp(context, 28f)
            setPadding(pad, pad, pad, pad)
        }
        setupPanel.addView(TextView(context).apply {
            text = "Editor"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
        })
        setupStatus.apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        }
        setupPanel.addView(setupStatus)
        setupDetail.apply {
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 8f), 0, IosUi.dp(context, 18f))
        }
        setupPanel.addView(setupDetail)
        setupProgress.max = 100
        setupProgress.visibility = View.GONE
        setupPanel.addView(setupProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = IosUi.dp(context, 16f) })
        setupButton.setOnClickListener { installAssets() }
        setupPanel.addView(setupButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(setupPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun renderSetup() {
        if (EditorAssets.isInstalled()) {
            setupPanel.visibility = View.GONE
            if (!editorReady) loadEditor()
            return
        }
        setupPanel.visibility = View.VISIBLE
        setupProgress.visibility = if (busy) View.VISIBLE else View.GONE
        setupButton.isEnabled = !busy
        setupButton.alpha = if (busy) 0.4f else 1f
        if (!busy) {
            setupStatus.text = "The editor needs its engine"
            setupDetail.text =
                "Downloads Monaco ${EditorAssets.MONACO_VERSION} -- the editor component from VS " +
                    "Code -- once, so the editor works offline afterwards."
            setupButton.text = "Install editor"
        }
    }

    private fun installAssets() {
        if (busy) return
        busy = true
        renderSetup()
        setupStatus.text = "Installing"

        scope.launch {
            val error = withContext(Dispatchers.IO) {
                EditorAssets.install { percent, message ->
                    scope.launch {
                        setupProgress.isIndeterminate = percent <= 0
                        setupProgress.progress = percent
                        setupDetail.text = message
                    }
                }
            }
            busy = false
            if (error != null) {
                setupStatus.text = "Install failed"
                setupDetail.text = error
                renderSetup()
            } else {
                renderSetup()
            }
        }
    }

    private fun loadEditor() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
    }

    // ── The JS bridge ──────────────────────────────────────────────────────

    private inner class Bridge {

        /**
         * A request from the page that needs an answer. Replies asynchronously through
         * `PrismEditor.resolve`, so nothing blocks the WebView's JS thread.
         */
        @JavascriptInterface
        fun request(id: Int, method: String, payload: String) {
            scope.launch {
                val args = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
                val reply: String = when {
                    method == "complete" -> withContext(Dispatchers.Default) { completionReply(args) }
                    method == "executeCommand" -> "{}"
                    // Anything an extension asks that needs the device rather than the editor. The
                    // page forwards these under a `host:` prefix precisely so they are recognisable
                    // here and cannot collide with the page's own calls.
                    method.startsWith("host:") ->
                        withContext(Dispatchers.IO) { extensionRequest(method.removePrefix("host:"), args) }
                    else -> "{}"
                }
                webView.evaluateJavascript(
                    "window.PrismEditor && PrismEditor.resolve($id, ${jsString(reply)});", null
                )
            }
        }

        /** One message from the page to the Node extension host. */
        @JavascriptInterface
        fun nodeSend(json: String) {
            NodeRuntime.send(json)
        }

        /** Fire-and-forget notifications from the page. */
        @JavascriptInterface
        fun notify(event: String, payload: String) {
            scope.launch {
                val args = runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
                when (event) {
                    "ready" -> {
                        editorReady = true
                        applyPendingState()
                        loadExtensions()
                    }
                    "extension-failed" -> {
                        val id = args.optString("id")
                        val message = args.optString("message").ifBlank { "unknown error" }
                        if (id.isNotBlank()) extensionErrors[id] = message
                        PrismLogger.logWarning(
                            "PrismEditor",
                            "Extension $id did not activate: $message"
                        )
                        refreshExtensionPopup()
                    }
                    // The extension host's view of an extension changes as it activates and
                    // registers commands. The floating popup re-renders from these two events, so
                    // it shows the extension's current state rather than a snapshot from open time.
                    "extension-activated" -> {
                        val id = args.optString("id")
                        activeExtensions.add(id)
                        extensionErrors.remove(id)
                        // The popup may have been opened before the host had this extension's view
                        // (activation is asynchronous). Asking again here is what makes a view
                        // that appeared with activation replace the fallback without a reopen.
                        if (id.isNotBlank() && id == extensionPopupId) {
                            js("PrismEditor.openExtensionView(${jsString(id)})")
                        }
                        refreshExtensionPopup()
                    }
                    // The Node host finishing its boot is the moment activations sent before it was
                    // ready become deliverable. If the popup is waiting on an extension that has
                    // not activated, this is the second chance.
                    "host-ready" -> {
                        val id = extensionPopupId ?: return@launch
                        val extension = installedExtensions.firstOrNull { it.id == id }
                        if (extension != null && extension.enabled && id !in activeExtensions) {
                            activateExtension(extension)
                        } else if (id in activeExtensions) {
                            requestExtensionView(id)
                        }
                    }
                    "extension-commands" -> {
                        liveCommands.clear()
                        val commands = args.optJSONArray("commands")
                        if (commands != null) {
                            for (i in 0 until commands.length()) {
                                commands.optString(i).takeIf { it.isNotBlank() }?.let(liveCommands::add)
                            }
                        }
                        refreshExtensionPopup()
                    }
                    // A command the popup offered does not exist in the host. That used to be
                    // silent -- the toast said "Running" and nothing happened -- which is the
                    // most confusing possible answer. Say what happened, and ask the extension
                    // to activate in case that is the reason its command is missing.
                    "command-missing" -> {
                        val id = args.optString("id")
                        PrismLogger.logWarning(
                            "PrismEditor", "Command $id was not registered by any running extension"
                        )
                        toast("${id.substringAfterLast('.')} is not available — the extension has not registered it")
                        extensionPopupId?.let { owner ->
                            installedExtensions.firstOrNull { it.id == owner }?.let { extension ->
                                if (extension.id !in activeExtensions) {
                                    activateExtension(extension)
                                    scheduleViewRequests(extension.id)
                                }
                            }
                        }
                    }
                    // The extension ran the command. It may have opened or replaced a view, so
                    // ask what it is showing now rather than assuming anything happened.
                    "command-done" -> extensionPopupId?.let { owner -> scheduleViewRequests(owner) }
                    // An extension's view: its HTML on creation and on every replacement, and
                    // `none` when it has not contributed one. Stored per panel so the popup can
                    // open straight into the current view, and re-rendered on every change so a
                    // view the extension swaps while the popup is open is the one shown.
                    "extension-webview" -> {
                        val panelId = args.optString("panelId")
                        val owner = args.optString("extensionId")
                        val action = args.optString("action")
                        // The host is still waiting for a provider. Ask again rather than
                        // concluding there is no view: this is the window in which a view
                        // provider registered after an await becomes visible.
                        if (action == "waiting" && owner.isNotBlank() && owner == extensionPopupId &&
                            owner in activeExtensions
                        ) {
                            scheduleViewRequests(owner)
                        }
                        when (action) {
                            // A panel that exists but has not drawn anything yet must not become
                            // "the view this extension is showing": doing that replaced a view
                            // that DID have content with an empty one, which is exactly how the
                            // popup ended up falling back to the command list. The mapping moves
                            // only when there is HTML to show.
                            "open", "html", "title" -> if (panelId.isNotBlank()) {
                                val html = args.optString("html")
                                if (html.isNotBlank()) {
                                    extensionHtml[panelId] = html
                                    if (owner.isNotBlank()) extensionPanelOf[owner] = panelId
                                }
                            }
                            "dispose" -> if (panelId.isNotBlank()) {
                                extensionHtml.remove(panelId)
                                extensionWebviewState.remove(panelId)
                                if (owner.isNotBlank() && extensionPanelOf[owner] == panelId) {
                                    extensionPanelOf.remove(owner)
                                }
                            }
                        }
                        refreshExtensionPopup()
                    }
                    // A message the extension sent into its own view (`webview.postMessage`). It
                    // is delivered as a `message` event, which is the event VS Code's webviews
                    // fire and the one extension views listen for.
                    "extension-webview-message" -> {
                        val panelId = args.optString("panelId")
                        if (panelId.isNotBlank() && panelId == extensionPopupPanel) {
                            extensionPopupWebView?.evaluateJavascript(
                                "window.__prismReceive && window.__prismReceive(" +
                                    jsString(args.optString("payload")) + ")",
                                null,
                            )
                        }
                    }
                    "extension-message" -> toast(args.optString("message"))
                    "extension-state" -> withContext(Dispatchers.IO) {
                        saveExtensionState(args.optString("scope"), args.optString("state"))
                    }
                    "node-host-wanted" -> startNodeHost()
                    "host-error" -> PrismLogger.logWarning(
                        "PrismEditor", "Extension host: ${args.optString("message")}"
                    )
                    // Monaco tells us the moment its hidden textarea takes focus. Asking for the
                    // IME here rather than on touch is what makes it reliable: by this point there
                    // is definitely an editable element behind the WebView's input connection, so
                    // showSoftInput has something to attach to instead of being ignored.
                    "focus" -> showKeyboard()
                    "blur" -> hideKeyboard()
                    "cursor" -> {
                        cursorLine = args.optInt("line", 1)
                        cursorColumn = args.optInt("column", 1)
                    }
                    "changed" -> {
                        lastEditLocation = activePath?.let { it to cursorLine }
                        if (autoSave) scheduleAutoSave()
                    }
                }
            }
        }
    }

    /**
     * Answers the parts of the VS Code API that need a device.
     *
     * Everything here is scoped to what the editor already has open -- the workspace folder, or the
     * extension's own directory. An extension asking for a path outside that is answered with an
     * error rather than the file: the host runs with Prism's full storage access, and an extension
     * is not something that should inherit it wholesale.
     */
    private fun extensionRequest(method: String, args: JSONObject): String {
        fun fail(message: String) = JSONObject().put("error", message).toString()

        return runCatching {
            when (method) {
                "readFile" -> {
                    val file = permittedFile(args.optString("path")) ?: return fail("Not allowed")
                    if (!file.isFile) return fail("No such file")
                    if (file.length() > MAX_OPEN_BYTES) return fail("That file is too large")
                    JSONObject().put("content", file.readText()).toString()
                }

                "writeFile" -> {
                    val file = permittedFile(args.optString("path")) ?: return fail("Not allowed")
                    file.parentFile?.mkdirs()
                    file.writeText(args.optString("content"))
                    JSONObject().put("ok", true).toString()
                }

                "readDirectory" -> {
                    val dir = permittedFile(args.optString("path")) ?: return fail("Not allowed")
                    val items = JSONArray()
                    dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child ->
                        items.put(JSONArray().put(child.name).put(if (child.isDirectory) 2 else 1))
                    }
                    items.toString()
                }

                "findFiles" -> {
                    val root = openFolder ?: return JSONArray().toString()
                    val pattern = args.optString("pattern").substringAfterLast('/')
                    val regex = globToRegex(pattern)
                    val hits = JSONArray()
                    root.walkTopDown()
                        .onEnter { it.name != ".git" && it.name != "node_modules" }
                        .filter { it.isFile && regex.matches(it.name) }
                        .take(500)
                        .forEach { hits.put(JSONObject().put("fsPath", it.absolutePath).put("path", it.absolutePath)) }
                    hits.toString()
                }

                "openDocument" -> {
                    val file = permittedFile(args.optString("target")) ?: return fail("Not allowed")
                    if (!file.isFile) return fail("No such file")
                    JSONObject()
                        .put("fileName", file.absolutePath)
                        .put("languageId", languageIdFor(file.name))
                        .put("getText", file.readText())
                        .toString()
                }

                "showDocument" -> {
                    val file = permittedFile(args.optString("uri")) ?: return fail("Not allowed")
                    if (file.isFile) scope.launch { openFile(file) }
                    JSONObject().put("ok", true).toString()
                }

                "clipboardWrite" -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Prism Editor", args.optString("text"))
                    )
                    JSONObject().put("ok", true).toString()
                }

                "openExternal" -> {
                    val uri = android.net.Uri.parse(args.optString("uri"))
                    scope.launch {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    JSONObject().put("ok", true).toString()
                }

                else -> "{}"
            }
        }.getOrElse { fail(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Resolves a path an extension named, or null if it is out of bounds.
     *
     * The bound is the open folder, the open file's own directory, and the extensions directory --
     * which is where an extension's bundled data lives. Canonicalised first, because `..` in a path
     * an extension supplied is exactly how this check would otherwise be walked past.
     */
    private fun permittedFile(path: String): File? {
        if (path.isBlank()) return null
        val file = runCatching { File(path.removePrefix("file://")).canonicalFile }.getOrNull() ?: return null
        val roots = listOfNotNull(
            openFolder?.canonicalFile,
            activePath?.let { File(it).parentFile?.canonicalFile },
            scratchRoot.canonicalFile,
            ExtensionStore.root().canonicalFile,
        )
        return if (roots.any { file.path == it.path || file.path.startsWith(it.path + File.separator) }) file else null
    }

    /** Monaco's language id for a filename. Only what an extension is likely to branch on. */
    private fun languageIdFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "java" -> "java"
        "kt", "kts" -> "kotlin"
        "py" -> "python"
        "js", "mjs", "cjs" -> "javascript"
        "ts" -> "typescript"
        "json" -> "json"
        "html", "htm" -> "html"
        "css" -> "css"
        "md" -> "markdown"
        "xml" -> "xml"
        "sh" -> "shell"
        else -> "plaintext"
    }

    /** The subset of glob syntax `workspace.findFiles` patterns actually use. */
    private fun globToRegex(pattern: String): Regex {
        val escaped = StringBuilder()
        pattern.forEach { ch ->
            when (ch) {
                '*' -> escaped.append(".*")
                '?' -> escaped.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> escaped.append('\\').append(ch)
                else -> escaped.append(ch)
            }
        }
        return Regex(escaped.toString(), RegexOption.IGNORE_CASE)
    }

    private fun showKeyboard() {
        if (!webView.hasFocus()) webView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    private fun completionReply(args: JSONObject): String {
        val items = CompletionEngine.complete(
            language = args.optString("language"),
            prefix = args.optString("prefix"),
            lineToCursor = args.optString("line"),
            text = args.optString("text"),
        )
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().apply {
                put("label", it.label)
                put("kind", it.kind)
                put("insert", it.insert)
                put("detail", it.detail)
                put("doc", it.doc)
                put("sort", it.sort)
            })
        }
        return JSONObject().put("items", array).toString()
    }

    /**
     * Hands every enabled extension to the host it was built for.
     *
     * A web extension is read here and shipped across as text, because the Worker has no
     * filesystem. A Node extension travels as a path, because Node does -- and only the path gives
     * it working relative requires and its own `node_modules`. Declarative extensions -- themes,
     * grammars, snippets -- have no entry point and need nothing loaded; they contribute through
     * their manifest alone, so they are counted but not activated.
     */
    private fun loadExtensions() {
        scope.launch {
            val installed = withContext(Dispatchers.IO) {
                ExtensionStore.installed().filter { it.enabled }
            }
            if (installed.isEmpty()) return@launch

            // Node takes a second or so to boot, and it has to be up before the page tries to
            // activate anything into it -- so it is started here, before the first activation, and
            // not lazily on the first message.
            //
            // ANY extension declaring `main` counts, not just ones already classified as Node.
            // Classification depends on the runtime being installed, so the old check was false on
            // exactly the device that needed the offer: every Node-only extension looked
            // declarative, nothing started the host, and the install dialog never appeared. The
            // extension then sat there with "has not finished activating" forever.
            if (installed.any { it.nodeEntry != null }) {
                startNodeHost()
                installMissingDependencies(installed.filter { it.runtime == ExtensionStore.RUNTIME_NODE })
            }

            js("PrismEditor.startExtensionHost()")
            var activated = 0
            installed.forEach { extension ->
                if (extension.runtime == null) return@forEach
                sendActivation(extension)
                activated++
            }
            if (activated > 0) {
                PrismLogger.logInfo("PrismEditor", "Activated $activated extension(s)")
            }
        }
    }

    /**
     * Runs `npm install` for any Node extension that declares dependencies and has none unpacked.
     *
     * ## Why this is rare, and why it has to exist anyway
     *
     * A `.vsix` is supposed to be self-contained: almost every published extension either bundles
     * its dependencies into one file or ships `node_modules` inside the package, and for those this
     * does nothing at all. The exceptions are the ones the user is most likely to have sideloaded —
     * an extension built locally, or one whose author expected a desktop install to run npm — and
     * before there was a guest with npm in it there was no way to help them.
     *
     * In the background and never blocking: it needs the network, it can take minutes, and the
     * extension it is for is either already working without its optional dependencies or already
     * failing. Neither case is improved by making the editor wait.
     */
    private fun installMissingDependencies(installed: List<ExtensionStore.Installed>) {
        val needing = installed.filter { extension ->
            extension.runtime == ExtensionStore.RUNTIME_NODE &&
                !File(extension.directory, "node_modules").isDirectory &&
                declaresDependencies(extension.directory)
        }
        if (needing.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            needing.forEach { extension ->
                PrismLogger.logInfo("PrismEditor", "Installing dependencies for ${extension.id}")
                val result = NodeRuntime.installDependencies(context, extension.directory)
                if (result.ok) {
                    PrismLogger.logSuccess("PrismEditor", "${extension.id}: dependencies installed")
                    withContext(Dispatchers.Main) { toast("${extension.displayName} is ready") }
                } else {
                    PrismLogger.logWarning(
                        "PrismEditor",
                        "${extension.id}: npm install failed — ${result.output.takeLast(400)}"
                    )
                }
            }
        }
    }

    private fun declaresDependencies(directory: File): Boolean = runCatching {
        val json = org.json.JSONObject(File(directory, "package.json").readText())
        (json.optJSONObject("dependencies")?.length() ?: 0) > 0
    }.getOrDefault(false)

    /**
     * The directories and saved state an extension's `ExtensionContext` is built from.
     *
     * Created here, not in the host: the host may be a Web Worker with no filesystem at all, and
     * an extension that finds `globalStorageUri` pointing at a directory that does not exist fails
     * on its first write just as surely as one that finds it undefined.
     *
     * State lives beside the storage directories rather than inside them, because an extension is
     * entitled to delete everything in its own storage and should not be able to lose the editor's
     * record of its settings by doing so.
     */
    private fun extensionContextJson(extension: ExtensionStore.Installed): String {
        val base = File(context.filesDir, "editor")
        val storage = File(base, "storage/${extension.id}").apply { mkdirs() }
        val globalStorage = File(base, "global-storage/${extension.id}").apply { mkdirs() }
        val logs = File(base, "logs/${extension.id}").apply { mkdirs() }

        val manifest = runCatching {
            org.json.JSONObject(File(extension.directory, "package.json").readText())
        }.getOrElse { org.json.JSONObject() }

        return org.json.JSONObject()
            .put("storage", storage.absolutePath)
            .put("globalStorage", globalStorage.absolutePath)
            .put("logs", logs.absolutePath)
            .put("manifest", manifest)
            .put("state", readExtensionState(extension.id))
            .toString()
    }

    private fun extensionStateFile(id: String): File =
        File(context.filesDir, "editor/state/$id.json")

    private fun readExtensionState(id: String): org.json.JSONObject =
        runCatching { org.json.JSONObject(extensionStateFile(id).readText()) }
            .getOrElse { org.json.JSONObject().put("global", org.json.JSONObject()).put("workspace", org.json.JSONObject()) }

    /**
     * Records one memento write.
     *
     * The host sends the whole store rather than the one key that changed, which looks wasteful and
     * is the right call: a memento is a handful of small values, and a protocol that sent deltas
     * would need the two sides to agree about ordering across an async channel that does not
     * guarantee any.
     */
    private fun saveExtensionState(scope: String, state: String) {
        // The scope is "global-<id>" or "workspace-<id>"; the id is everything after the first dash.
        val kind = scope.substringBefore('-')
        val id = scope.substringAfter('-')
        if (id.isBlank() || (kind != "global" && kind != "workspace")) return

        val file = extensionStateFile(id)
        file.parentFile?.mkdirs()
        val current = readExtensionState(id)
        current.put(kind, runCatching { org.json.JSONObject(state) }.getOrElse { org.json.JSONObject() })
        runCatching { file.writeText(current.toString()) }
    }

    /**
     * Brings up the Node runtime and points its output at this page.
     *
     * The runtime outlives the page -- `node::Start` runs once per process and cannot be restarted
     * -- so this re-points the listener rather than starting anything a second time. A page that is
     * rebuilt (rotation, a pager recycle) picks up the same running Node, and the backlog it queued
     * while nobody was listening arrives immediately.
     */
    private fun startNodeHost() {
        NodeRuntime.setListener { line ->
            scope.launch { js("PrismEditor.nodeMessage(" + jsString(line) + ")") }
        }
        if (!NodeRuntime.start(context)) {
            val reason = NodeRuntime.unavailableReason(context)
            scope.launch { js("PrismEditor.nodeHostGone(" + jsString(reason) + ")") }
            // An offer, not just a complaint. The runtime is a download away and the user is
            // sitting in front of the one screen where that matters.
            if (!NodeRuntime.isInstalled(context) && NodeRuntime.canInstall(context)) {
                offerNodeInstall(reason)
            }
        }
    }

    /**
     * Asks whether to install the Node runtime, once per page.
     *
     * Once, because a user who said no to a sixty-megabyte download should not be asked again every
     * time an extension activates -- and extensions activate constantly.
     */
    private var offeredNode = false

    private fun offerNodeInstall(reason: String) {
        if (offeredNode) return
        offeredNode = true
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Install the Node.js runtime?")
            .setMessage(
                reason + "\n\nIt installs a small Linux guest with Node.js and npm inside it, " +
                    "which is what lets desktop extensions run language servers, formatters and " +
                    "their own bundled tools."
            )
            .setPositiveButton("Install") { _, _ -> installNodeRuntime() }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Downloads and unpacks the runtime, with the progress on screen.
     *
     * The dialog is not cancellable and the install is not resumable: a half-unpacked rootfs is
     * worse than none, so [NodeInstaller] deletes and starts over rather than trying to continue,
     * and letting the user dismiss the dialog would hide a job that is still writing.
     */
    private fun installNodeRuntime() {
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(context).apply {
            text = "Starting…"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(IosUi.dp(context, 4f), IosUi.dp(context, 10f), 0, 0)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(context, 20f)
            setPadding(p, p, p, p)
            addView(progress)
            addView(label)
        }
        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Installing Node.js")
            .setView(body)
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                NodeInstaller.install(context) { percent, message ->
                    scope.launch {
                        progress.progress = percent
                        label.text = message
                    }
                }
            }
            runCatching { dialog.dismiss() }
            if (failure != null) {
                com.prism.launcher.theme.PrismDialogs.Builder(context)
                    .setTitle("Node.js did not install")
                    .setMessage(failure)
                    .setPositiveButton("Close", null)
                    .show()
                return@launch
            }
            // Extensions were classified against a device with no Node, so the whole set is
            // reloaded rather than only the ones that failed: an extension that was running in the
            // Worker may now belong in Node.
            toast("Node.js is ready")
            // Re-scan so `nodeReady` is true for the extensions that were waiting on it, then
            // activate the ones that are now Node extensions. Without the rescan the popup still
            // believed every Node-only extension was declarative.
            refreshExtensionButtons()
            loadExtensions()
        }
    }

    /**
     * The runtime's own screen: what is installed, whether it runs, and the two buttons.
     */
    private fun showNodeRuntimeSheet() {
        scope.launch {
            val installed = withContext(Dispatchers.IO) { NodeInstaller.isInstalled(context) }
            val blocked = withContext(Dispatchers.IO) { NodeInstaller.unavailableReason(context) }
            val version = withContext(Dispatchers.IO) { NodeInstaller.installedVersion(context) }
            val bytes = withContext(Dispatchers.IO) { NodeInstaller.installedBytes(context) }

            val builder = com.prism.launcher.theme.PrismDialogs.Builder(context)
                .setTitle("Node.js runtime")

            when {
                blocked != null -> builder
                    .setMessage(blocked)
                    .setPositiveButton("Close", null)

                !installed -> builder
                    .setMessage(
                        "Not installed.\n\nDesktop extensions — the ones that declare `main` — " +
                            "need a real Node.js with a filesystem and the ability to start other " +
                            "programs. Installing it downloads about " +
                            "${NodeInstaller.APPROXIMATE_DOWNLOAD_MB} MB and unpacks a small Linux " +
                            "guest that Prism runs through PRoot."
                    )
                    .setPositiveButton("Install") { _, _ -> installNodeRuntime() }
                    .setNegativeButton("Close", null)

                else -> builder
                    .setMessage(
                        "Installed: ${version ?: "unknown version"}\n" +
                            "On disk: ${bytes / (1024 * 1024)} MB\n" +
                            "Host: ${if (NodeRuntime.isRunning) "running" else "not running"}"
                    )
                    .setPositiveButton("Restart host") { _, _ ->
                        scope.launch {
                            withContext(Dispatchers.IO) { NodeRuntime.restart(context) }
                            toast(if (NodeRuntime.isRunning) "The Node host restarted" else "The Node host did not start")
                            loadExtensions()
                        }
                    }
                    .setNeutralButton("Check") { _, _ ->
                        scope.launch {
                            val reported = withContext(Dispatchers.IO) { NodeRuntime.version(context) }
                            toast(reported?.let { "node $it" } ?: "Node did not answer — see diagnostics")
                        }
                    }
                    .setNegativeButton("Remove") { _, _ ->
                        scope.launch {
                            withContext(Dispatchers.IO) { NodeInstaller.uninstall(context) }
                            toast("The Node runtime was removed")
                            loadExtensions()
                        }
                    }
            }
            builder.show()
        }
    }

    private fun applyPendingState() {
        // The code surface follows the device's appearance, like the rest of Prism's chrome.
        val theme = if (IosUi.isDark(context)) "prism-ios-dark" else "prism-ios-light"
        js("PrismEditor.setTheme(" + jsString(theme) + ")")
        js("PrismEditor.setOption('wordWrap', ${if (wordWrap) "'on'" else "'off'"})")
        activePath?.let { path ->
            val file = File(path)
            if (file.isFile) openFile(file)
        }
    }

    /** Runs a snippet in the page. Everything the menus do in Monaco goes through here. */
    private fun js(script: String) {
        if (!editorReady) return
        webView.evaluateJavascript(script, null)
    }

    private fun jsAsk(script: String, onResult: (String) -> Unit) {
        if (!editorReady) { onResult(""); return }
        webView.evaluateJavascript(script) { raw ->
            // evaluateJavascript hands back a JSON value, so a string arrives quoted and escaped.
            val decoded = runCatching { JSONArray("[$raw]").getString(0) }.getOrElse { "" }
            onResult(decoded)
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun perform(item: EditorMenus.Item) {
        item.command?.let { js("PrismEditor.runCommand(${jsString(it)})"); return }
        when (item.action) {
            EditorMenus.A_NEW_TEXT_FILE -> newFile("txt", "")
            EditorMenus.A_NEW_FILE -> chooseNewFileType()
            EditorMenus.A_OPEN_FILE -> chooseFile()
            EditorMenus.A_OPEN_FOLDER -> chooseFolder()
            EditorMenus.A_SAVE -> save()
            EditorMenus.A_SAVE_AS -> saveAs()
            EditorMenus.A_AUTOSAVE -> {
                autoSave = !autoSave
                toast(if (autoSave) "Auto save on" else "Auto save off")
            }

            EditorMenus.A_CUT -> js("PrismEditor.runCommand('editor.action.clipboardCutAction')")
            EditorMenus.A_COPY -> js("PrismEditor.runCommand('editor.action.clipboardCopyAction')")
            EditorMenus.A_PASTE -> pasteFromClipboard()
            EditorMenus.A_FIND -> showFindDialog(replace = false, inFiles = false)
            EditorMenus.A_REPLACE -> showFindDialog(replace = true, inFiles = false)
            EditorMenus.A_FIND_IN_FILES -> showFindDialog(replace = false, inFiles = true)
            EditorMenus.A_REPLACE_IN_FILES -> showFindDialog(replace = true, inFiles = true)

            EditorMenus.A_COLUMN_SELECTION -> {
                columnSelection = !columnSelection
                js("PrismEditor.setOption('columnSelection', $columnSelection)")
                toast(if (columnSelection) "Column selection on" else "Column selection off")
            }
            EditorMenus.A_MULTICURSOR_MODIFIER ->
                toast("Multi-cursor uses Ctrl+Click. On a touch screen, use Add Cursor Above/Below.")

            EditorMenus.A_TOGGLE_EXPLORER -> toggleTree()
            EditorMenus.A_COMMAND_PALETTE -> js("PrismEditor.runCommand('editor.action.quickCommand')")
            EditorMenus.A_OPEN_VIEW -> showViewList()
            EditorMenus.A_APPEARANCE -> showAppearance()
            EditorMenus.A_EDITOR_LAYOUT -> showEditorLayout()
            EditorMenus.A_SEARCH_VIEW -> showFindDialog(replace = false, inFiles = openFolder != null)
            EditorMenus.A_SOURCE_CONTROL -> showSourceControl()
            EditorMenus.A_RUN_VIEW, EditorMenus.A_TESTING -> showRunView()
            EditorMenus.A_EXTENSIONS, EditorMenus.A_MARKETPLACE -> openMarketplace()
            EditorMenus.A_NODE_RUNTIME -> showNodeRuntimeSheet()
            EditorMenus.A_BROWSER -> openInPrismBrowser()
            EditorMenus.A_PROBLEMS -> showProblems()
            EditorMenus.A_OUTPUT, EditorMenus.A_DEBUG_CONSOLE -> showTerminal(focusOutput = true)
            EditorMenus.A_TERMINAL, EditorMenus.A_NEW_TERMINAL -> showTerminal()
            EditorMenus.A_WORD_WRAP -> {
                wordWrap = !wordWrap
                js("PrismEditor.setOption('wordWrap', ${if (wordWrap) "'on'" else "'off'"})")
            }

            EditorMenus.A_GO_BACK -> goBack()
            EditorMenus.A_GO_FORWARD -> goForward()
            EditorMenus.A_LAST_EDIT -> lastEditLocation?.let { (path, line) ->
                if (path != activePath) File(path).takeIf { it.isFile }?.let(::openFile)
                js("PrismEditor.revealPosition($line, 1)")
            } ?: toast("No edits yet")
            EditorMenus.A_SWITCH_EDITOR, EditorMenus.A_SWITCH_GROUP, EditorMenus.A_GOTO_FILE -> showOpenFiles()
            EditorMenus.A_SYMBOL_WORKSPACE -> showWorkspaceSymbols()
            EditorMenus.A_GOTO_LINE -> showGoToLine()
            EditorMenus.A_NEXT_PROBLEM -> js("PrismEditor.runCommand('editor.action.marker.next')")
            EditorMenus.A_PREV_PROBLEM -> js("PrismEditor.runCommand('editor.action.marker.prev')")
            EditorMenus.A_NEXT_CHANGE -> js("PrismEditor.runCommand('editor.action.dirtydiff.next')")
            EditorMenus.A_PREV_CHANGE -> js("PrismEditor.runCommand('editor.action.dirtydiff.previous')")

            EditorMenus.A_RUN_FILE, EditorMenus.A_RUN_ACTIVE_FILE -> runActiveFile()
            EditorMenus.A_STOP_RUN -> { terminal.run("kill %1 2>/dev/null; true"); toast("Sent stop to the shell") }
            EditorMenus.A_RESTART_RUN -> runActiveFile()
            EditorMenus.A_OPEN_CONFIG, EditorMenus.A_ADD_CONFIG -> openLaunchConfig()
            EditorMenus.A_TOGGLE_BREAKPOINT -> toggleBreakpoint()
            EditorMenus.A_NEW_BREAKPOINT -> toggleBreakpoint()
            EditorMenus.A_ENABLE_BREAKPOINTS -> toast("${breakpointCount()} breakpoint(s) enabled")
            EditorMenus.A_DISABLE_BREAKPOINTS -> toast("${breakpointCount()} breakpoint(s) disabled")
            EditorMenus.A_REMOVE_BREAKPOINTS -> { breakpoints.clear(); toast("Breakpoints removed") }
            EditorMenus.A_INSTALL_DEBUGGERS -> openMarketplace("debug")

            EditorMenus.A_SPLIT_TERMINAL, EditorMenus.A_NEW_TERMINAL_WINDOW -> {
                showTerminal()
                toast("This editor runs one shell at a time")
            }
            EditorMenus.A_RUN_TASK, EditorMenus.A_RUN_BUILD_TASK -> runTask()
            EditorMenus.A_RUN_SELECTED -> runSelection()
            EditorMenus.A_SHOW_TASKS -> { showTerminal(); terminal.run("jobs") }
            EditorMenus.A_RESTART_TASK -> runTask()
            EditorMenus.A_TERMINATE_TASK -> { showTerminal(); terminal.run("kill %1 2>/dev/null; true") }
            EditorMenus.A_CONFIGURE_TASKS, EditorMenus.A_CONFIGURE_BUILD_TASK -> openTasksConfig()

            else -> toast("${item.label} is not wired up")
        }
    }

    // ── Files ──────────────────────────────────────────────────────────────

    private fun newFile(extension: String, starter: String) {
        val name = "untitled-${System.currentTimeMillis().toString().takeLast(5)}.$extension"
        val path = File(scratchRoot, name).absolutePath
        activePath = path
        js("PrismEditor.newFile(${jsString(path)}, null, ${jsString(starter)})")
        setupPanel.visibility = View.GONE
    }

    private fun chooseNewFileType() {
        val labels = EditorMenus.NEW_FILE_TYPES.map { "${it.first}  (.${it.second})" }.toTypedArray()
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("New file")
            .setItems(labels) { _, which ->
                val (_, extension, starter) = EditorMenus.NEW_FILE_TYPES[which]
                newFile(extension, starter)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFile(file: File) {
        if (!file.isFile) { toast("${file.name} is not a file"); return }
        if (file.length() > MAX_OPEN_BYTES) {
            toast("${file.name} is larger than ${MAX_OPEN_BYTES / (1024 * 1024)} MB")
            return
        }
        val text = runCatching { file.readText() }.getOrElse {
            toast("Could not read ${file.name}: ${it.message}")
            return
        }
        activePath?.let { previous -> backStack.addLast(previous to cursorLine) }
        forwardStack.clear()
        activePath = file.absolutePath
        setupPanel.visibility = View.GONE
        js("PrismEditor.openFile(${jsString(file.absolutePath)}, ${jsString(text)})")
        com.prism.launcher.history.PrismHistory.record(
            kind = com.prism.launcher.history.PrismHistory.Kind.FILE,
            title = file.name,
            uri = file.absolutePath,
            text = file.parent.orEmpty(),
            source = "Editor",
        )
    }

    private fun chooseFile() {
        FileChooser.pick(context, startAt = openFolder ?: defaultRoot(), directories = false) { chosen ->
            openFile(chosen)
        }
    }

    private fun chooseFolder() {
        FileChooser.pick(context, startAt = openFolder ?: defaultRoot(), directories = true) { chosen ->
            openFolder = chosen
            tree.open(chosen)
            treeToggle.visibility = View.VISIBLE
            if (!treeVisible) toggleTree()
            scope.launch(Dispatchers.Default) { CompletionEngine.indexFolder(chosen) }
            toast("Opened ${chosen.name}")
        }
    }

    private fun defaultRoot(): File =
        android.os.Environment.getExternalStorageDirectory().takeIf { it.isDirectory } ?: context.filesDir

    private fun save() {
        val path = activePath ?: run { toast("Nothing open"); return }
        jsAsk("PrismEditor.contentOf(${jsString(path)})") { content ->
            val file = File(path)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }.onSuccess {
                js("PrismEditor.markSaved(${jsString(path)})")
                if (openFolder != null) tree.refresh()
                toast("Saved ${file.name}")
            }.onFailure {
                toast("Could not save: ${it.message}")
            }
        }
    }

    private fun saveAs() {
        val path = activePath ?: run { toast("Nothing open"); return }
        FileChooser.pick(context, startAt = openFolder ?: defaultRoot(), directories = true) { folder ->
            val nameField = EditText(context).apply {
                setText(File(path).name)
                setSingleLine()
            }
            com.prism.launcher.theme.PrismDialogs.Builder(context)
                .setTitle("Save as")
                .setMessage(folder.absolutePath)
                .setView(nameField)
                .setPositiveButton("Save") { _, _ ->
                    val target = File(folder, nameField.text.toString().ifBlank { "untitled.txt" })
                    jsAsk("PrismEditor.contentOf(${jsString(path)})") { content ->
                        runCatching { target.writeText(content) }
                            .onSuccess {
                                activePath = target.absolutePath
                                js("PrismEditor.openFile(${jsString(target.absolutePath)}, ${jsString(content)})")
                                if (openFolder != null) tree.refresh()
                                toast("Saved ${target.name}")
                            }
                            .onFailure { toast("Could not save: ${it.message}") }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private var autoSaveScheduled = false

    /** Debounced: saving on every keystroke would write a file per character. */
    private fun scheduleAutoSave() {
        if (autoSaveScheduled) return
        autoSaveScheduled = true
        scope.launch {
            delay(1_200)
            autoSaveScheduled = false
            if (autoSave && activePath != null && !activePath!!.startsWith(scratchRoot.absolutePath)) {
                save()
            }
        }
    }

    // ── Find and replace ───────────────────────────────────────────────────

    /**
     * One dialog for all four commands.
     *
     * The toggles are transparent icon buttons that latch, as they are in VS Code -- `Aa` for case,
     * `ab` for whole word, `.*` for regex -- because that is what people already know how to read.
     */
    private fun showFindDialog(replace: Boolean, inFiles: Boolean) {
        if (inFiles && openFolder == null) {
            toast("Open a folder first")
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(context, 18f)
            setPadding(pad, pad, pad, pad)
        }

        val searchField = EditText(context).apply { hint = "Find"; setSingleLine() }
        root.addView(searchField)

        val toggles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        var matchCase = false
        var wholeWord = false
        var useRegex = false

        fun toggle(label: String, tip: String, onChange: (Boolean) -> Boolean) {
            val button = TextView(context).apply {
                text = label
                textSize = 13f
                contentDescription = tip
                setPadding(IosUi.dp(context, 10f), IosUi.dp(context, 6f), IosUi.dp(context, 10f), IosUi.dp(context, 6f))
                setTextColor(0xFF9A9A9A.toInt())
                background = null           // transparent, per the request
                setOnClickListener {
                    val now = onChange(true)
                    setTextColor(if (now) IosUi.accent(context) else 0xFF9A9A9A.toInt())
                }
            }
            toggles.addView(button)
        }
        toggle("Aa", "Match case") { matchCase = !matchCase; matchCase }
        toggle("ab", "Match whole word") { wholeWord = !wholeWord; wholeWord }
        toggle(".*", "Use regular expression") { useRegex = !useRegex; useRegex }
        root.addView(toggles)

        val replaceField = EditText(context).apply { hint = "Replace with"; setSingleLine() }
        if (replace) root.addView(replaceField)

        val builder = com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle(
                when {
                    replace && inFiles -> "Replace in Files"
                    replace -> "Replace"
                    inFiles -> "Find in Files"
                    else -> "Find"
                }
            )
            .setView(root)
            .setNegativeButton("Close", null)

        if (inFiles) {
            builder.setPositiveButton(if (replace) "Replace all" else "Search") { _, _ ->
                val query = searchField.text.toString()
                if (query.isEmpty()) return@setPositiveButton
                searchInFiles(query, matchCase, wholeWord, useRegex,
                    if (replace) replaceField.text.toString() else null)
            }
        } else {
            builder.setPositiveButton(if (replace) "Replace all" else "Find") { _, _ ->
                val query = searchField.text.toString()
                if (query.isEmpty()) return@setPositiveButton
                val replacement = if (replace) jsString(replaceField.text.toString()) else "null"
                jsAsk(
                    "PrismEditor.find(${jsString(query)}, $useRegex, $matchCase, $wholeWord, " +
                        "$replacement, ${replace})"
                ) { result ->
                    val count = runCatching { JSONObject(result).optInt("matches") }.getOrDefault(0)
                    toast(if (replace) "$count replaced" else "$count match(es)")
                }
            }
        }
        builder.show()
    }

    /** Runs a search across the open folder, off the main thread, and shows the hits. */
    private fun searchInFiles(
        query: String, matchCase: Boolean, wholeWord: Boolean, useRegex: Boolean, replacement: String?,
    ) {
        val folder = openFolder ?: return
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                val pattern = buildPattern(query, matchCase, wholeWord, useRegex) ?: return@withContext emptyList()
                val out = ArrayList<Pair<File, Int>>()
                var replaced = 0
                folder.walkTopDown()
                    .onEnter { it.name !in setOf(".git", "build", "node_modules", ".gradle", "__pycache__") }
                    .filter { it.isFile && it.length() < 2L * 1024 * 1024 }
                    .take(2_000)
                    .forEach { file ->
                        val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                        val count = pattern.findAll(text).count()
                        if (count == 0) return@forEach
                        out.add(file to count)
                        if (replacement != null) {
                            runCatching { file.writeText(pattern.replace(text, Regex.escapeReplacement(replacement))) }
                                .onSuccess { replaced += count }
                        }
                    }
                out
            }

            if (hits.isEmpty()) { toast("No matches in ${folder.name}"); return@launch }
            if (replacement != null) {
                tree.refresh()
                toast("Replaced in ${hits.size} file(s)")
                return@launch
            }

            val labels = hits.map { "${it.first.name}  (${it.second})" }.toTypedArray()
            com.prism.launcher.theme.PrismDialogs.Builder(context)
                .setTitle("${hits.size} file(s) matched")
                .setItems(labels) { _, which -> openFile(hits[which].first) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun buildPattern(
        query: String, matchCase: Boolean, wholeWord: Boolean, useRegex: Boolean,
    ): Regex? = runCatching {
        var source = if (useRegex) query else Regex.escape(query)
        if (wholeWord) source = "\\b(?:$source)\\b"
        val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(source, options)
    }.onFailure { toast("Bad pattern: ${it.message}") }.getOrNull()

    // ── Views and navigation ───────────────────────────────────────────────

    private fun toggleTree() {
        if (openFolder == null) { toast("Open a folder first"); return }
        treeVisible = !treeVisible
        tree.visibility = if (treeVisible) View.VISIBLE else View.GONE
        treeToggle.text = if (treeVisible) "›" else "‹"
    }

    private fun showTerminal(focusOutput: Boolean = false) {
        if (terminal.visibility == View.VISIBLE && !focusOutput) {
            terminal.visibility = View.GONE
            return
        }
        terminal.visibility = View.VISIBLE
        if (!terminal.isRunning()) {
            terminal.start(terminalDirectory())
        }
    }

    /** The shell opens where the user is working: the folder, else the open file's folder. */
    private fun terminalDirectory(): File =
        openFolder
            ?: activePath?.let { File(it).parentFile }?.takeIf { it.isDirectory }
            ?: context.filesDir

    private fun showOpenFiles() {
        jsAsk("PrismEditor.activeFile()") { active ->
            val label = if (active.isBlank()) "Nothing open" else File(active).name
            toast("Open: $label")
        }
    }

    private fun showWorkspaceSymbols() {
        jsAsk("PrismEditor.symbols()") { json ->
            val list = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
            if (list.length() == 0) { toast("No symbols found"); return@jsAsk }
            val labels = (0 until list.length()).map {
                val o = list.getJSONObject(it)
                "${o.optString("name")}   ${o.optString("kind")}  :${o.optInt("line")}"
            }.toTypedArray()
            com.prism.launcher.theme.PrismDialogs.Builder(context)
                .setTitle("Symbols")
                .setItems(labels) { _, which ->
                    js("PrismEditor.revealPosition(${list.getJSONObject(which).optInt("line")}, 1)")
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showGoToLine() {
        val field = EditText(context).apply {
            hint = "line or line:column"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Go to Line/Column")
            .setView(field)
            .setPositiveButton("Go") { _, _ ->
                val parts = field.text.toString().split(":", ",")
                val line = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@setPositiveButton
                val column = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                js("PrismEditor.revealPosition($line, $column)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goBack() {
        val target = backStack.removeLastOrNull() ?: run { toast("Nowhere to go back to"); return }
        activePath?.let { forwardStack.addLast(it to cursorLine) }
        File(target.first).takeIf { it.isFile }?.let {
            activePath = null      // openFile pushes onto the back stack; this jump already did
            openFile(it)
            js("PrismEditor.revealPosition(${target.second}, 1)")
        }
    }

    private fun goForward() {
        val target = forwardStack.removeLastOrNull() ?: run { toast("Nowhere to go forward to"); return }
        File(target.first).takeIf { it.isFile }?.let {
            openFile(it)
            js("PrismEditor.revealPosition(${target.second}, 1)")
        }
    }

    private fun showProblems() {
        jsAsk("PrismEditor.markers()") { json ->
            val list = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
            if (list.length() == 0) { toast("No problems reported"); return@jsAsk }
            val labels = (0 until list.length()).map {
                val o = list.getJSONObject(it)
                "line ${o.optInt("line")}: ${o.optString("message")}"
            }.toTypedArray()
            com.prism.launcher.theme.PrismDialogs.Builder(context)
                .setTitle("Problems")
                .setItems(labels) { _, which ->
                    js("PrismEditor.revealPosition(${list.getJSONObject(which).optInt("line")}, 1)")
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showAppearance() {
        val themes = arrayOf("Match device", "Dark", "Light", "High contrast")
        val ids = arrayOf(
            if (IosUi.isDark(context)) "prism-ios-dark" else "prism-ios-light",
            "prism-ios-dark", "prism-ios-light", "hc-black",
        )
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Appearance")
            .setItems(themes) { _, which -> js("PrismEditor.setTheme('${ids[which]}')") }
            .setNeutralButton("Font size…") { _, _ -> showFontSize() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFontSize() {
        val sizes = (9..22).map { it.toString() }.toTypedArray()
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Font size")
            .setItems(sizes) { _, which -> js("PrismEditor.setFontSize(${sizes[which]})") }
            .show()
    }

    private fun showEditorLayout() {
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Editor Layout")
            .setItems(arrayOf(
                if (treeVisible) "Hide file tree" else "Show file tree",
                if (terminal.visibility == View.VISIBLE) "Hide terminal" else "Show terminal",
                if (wordWrap) "Word wrap off" else "Word wrap on",
            )) { _, which ->
                when (which) {
                    0 -> toggleTree()
                    1 -> showTerminal()
                    else -> {
                        wordWrap = !wordWrap
                        js("PrismEditor.setOption('wordWrap', ${if (wordWrap) "'on'" else "'off'"})")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showViewList() {
        val names = arrayOf("Explorer", "Search", "Extensions", "Problems", "Terminal", "Source Control")
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Open View")
            .setItems(names) { _, which ->
                when (which) {
                    0 -> toggleTree()
                    1 -> showFindDialog(replace = false, inFiles = openFolder != null)
                    2 -> openMarketplace()
                    3 -> showProblems()
                    4 -> showTerminal()
                    else -> showSourceControl()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Source control is the shell running git.
     *
     * Honest about what it is rather than drawing a diff view over a git implementation that does not
     * exist: if the device has `git`, these work; if not, the shell says so, which is the truth.
     */
    private fun showSourceControl() {
        if (openFolder == null) { toast("Open a folder first"); return }
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Source Control")
            .setItems(arrayOf("git status", "git diff", "git log --oneline -20", "git add -A", "git commit")) { _, which ->
                showTerminal()
                when (which) {
                    0 -> terminal.run("git status")
                    1 -> terminal.run("git diff")
                    2 -> terminal.run("git log --oneline -20")
                    3 -> terminal.run("git add -A && git status --short")
                    else -> promptCommit()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptCommit() {
        val field = EditText(context).apply { hint = "Commit message"; setSingleLine() }
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("git commit")
            .setView(field)
            .setPositiveButton("Commit") { _, _ ->
                val message = field.text.toString().replace("\"", "\\\"")
                terminal.run("git commit -m \"$message\"")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRunView() {
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Run")
            .setItems(arrayOf("Run active file", "Run selected text", "Breakpoints (${breakpointCount()})")) { _, which ->
                when (which) {
                    0 -> runActiveFile()
                    1 -> runSelection()
                    else -> toast("${breakpointCount()} breakpoint(s) set. No debugger is installed to stop at them.")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Running ────────────────────────────────────────────────────────────

    private fun runActiveFile() {
        val path = activePath ?: run { toast("Nothing open"); return }
        val file = File(path)
        val command = EditorMenus.runCommandFor(file.name)
        if (command == null) {
            toast("Prism has no on-device runtime for .${file.extension} files")
            return
        }
        save()
        showTerminal()
        terminal.run("cd \"${file.parent}\" && $command")
    }

    private fun runSelection() {
        jsAsk("PrismEditor.selectedText()") { selected ->
            if (selected.isBlank()) { toast("Nothing selected"); return@jsAsk }
            showTerminal()
            terminal.run(selected.trim().replace("\n", "; "))
        }
    }

    private fun runTask() {
        val tasks = File(openFolder ?: return, ".vscode/tasks.json")
        if (!tasks.isFile) {
            toast("No .vscode/tasks.json in this folder")
            return
        }
        openFile(tasks)
    }

    private fun openTasksConfig() {
        val folder = openFolder ?: run { toast("Open a folder first"); return }
        val file = File(folder, ".vscode/tasks.json")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            runCatching {
                file.writeText(
                    "{\n  \"version\": \"2.0.0\",\n  \"tasks\": [\n    {\n      \"label\": \"build\",\n" +
                        "      \"type\": \"shell\",\n      \"command\": \"echo build\"\n    }\n  ]\n}\n"
                )
            }
        }
        openFile(file)
    }

    private fun openLaunchConfig() {
        val folder = openFolder ?: run { toast("Open a folder first"); return }
        val file = File(folder, ".vscode/launch.json")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            runCatching {
                file.writeText("{\n  \"version\": \"0.2.0\",\n  \"configurations\": []\n}\n")
            }
        }
        openFile(file)
    }

    private fun toggleBreakpoint() {
        val path = activePath ?: run { toast("Nothing open"); return }
        val lines = breakpoints.getOrPut(path) { linkedSetOf() }
        if (!lines.add(cursorLine)) lines.remove(cursorLine)
        toast(if (cursorLine in lines) "Breakpoint at line $cursorLine" else "Breakpoint cleared")
    }

    private fun breakpointCount(): Int = breakpoints.values.sumOf { it.size }

    // ── Elsewhere in Prism ─────────────────────────────────────────────────

    private fun openMarketplace(query: String = "") {
        context.startActivity(
            Intent(context, MarketplaceActivity::class.java)
                .putExtra(MarketplaceActivity.EXTRA_QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openInPrismBrowser() {
        val host = context as? com.prism.launcher.LauncherActivity ?: return
        val position = host.slotPreferences.getAssignments()
            .indexOfFirst { it is com.prism.launcher.SlotAssignment.Browser }
        if (position < 0) { toast("Add the Browser page to a slot first"); return }
        host.goToPage(position)
    }

    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) { toast("Clipboard is empty"); return }
        js("PrismEditor.insertText(${jsString(text)})")
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Back closes what is open, innermost first, before the launcher sees it. */
    fun handleBack(): Boolean {
        if (extensionPopup != null) { closeExtensionPopup(); return true }
        if (railExpanded) { collapseRail(); return true }
        if (terminal.visibility == View.VISIBLE) { terminal.visibility = View.GONE; return true }
        if (treeVisible) { toggleTree(); return true }
        return false
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Deliberately does no work here: rebuilding on every visibility callback is what made the
        // models page loop, and this page has nothing that needs refreshing on a swipe.
    }

    /**
     * Returning from the marketplace is a window-focus change, not an activity result -- the editor
     * is a page view inside the launcher. Re-reading here is what makes a just-installed extension
     * appear in the rail without leaving and re-entering the page.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshExtensionButtons()
    }

    override fun onDetachedFromWindow() {
        closeExtensionPopup()
        extensionLoadJob?.cancel()
        scope.cancel()
        terminal.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val COLLAPSED_DP = 46f
        const val EXPANDED_DP = 190f

        /** Menu panel width. Fits beside the collapsed rail on a phone; a 290dp panel did not. */
        const val MENU_WIDTH_DP = 270f

        /** At most this many extensions get an inline icon; more than this get their own rail. */
        const val INLINE_EXTENSION_LIMIT = 3
        const val MAX_OPEN_BYTES = 8L * 1024 * 1024
    }
}
