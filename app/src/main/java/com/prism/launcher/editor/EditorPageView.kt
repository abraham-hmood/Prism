package com.prism.launcher.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
                context, EditorAssets.monacoDir(context)
            ))
            .build()
    }

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        buildEditorSurface()
        buildRail()
        buildTree()
        buildSetupPanel()
        renderSetup()
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun buildEditorSurface() {
        columns.orientation = LinearLayout.HORIZONTAL
        centre.orientation = LinearLayout.VERTICAL

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
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

        columns.addView(
            rail, 0,
            LinearLayout.LayoutParams(IosUi.dp(context, COLLAPSED_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        )
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
        if (EditorAssets.isInstalled(context)) {
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
                EditorAssets.install(context) { percent, message ->
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
                    "extension-failed" -> PrismLogger.logWarning(
                        "PrismEditor",
                        "Extension ${args.optString("id")} did not activate: ${args.optString("message")}"
                    )
                    "extension-message" -> toast(args.optString("message"))
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
            ExtensionStore.root(context).canonicalFile,
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
                ExtensionStore.installed(context).filter { it.enabled }
            }
            if (installed.isEmpty()) return@launch

            // Node takes a second or so to boot, and it has to be up before the page tries to
            // activate anything into it -- so it is started here, before the first activation, and
            // not lazily on the first message.
            if (installed.any { it.runtime == ExtensionStore.RUNTIME_NODE }) startNodeHost()

            js("PrismEditor.startExtensionHost()")
            var activated = 0
            installed.forEach { extension ->
                val runtime = extension.runtime ?: return@forEach
                val source = withContext(Dispatchers.IO) { ExtensionStore.entrySource(extension) }
                    ?: return@forEach
                js(
                    "PrismEditor.activateExtension(" +
                        jsString(extension.id) + ", " +
                        jsString(extension.directory.absolutePath) + ", " +
                        jsString(source) + ", " +
                        jsString(runtime) + ")"
                )
                activated++
            }
            if (activated > 0) {
                PrismLogger.logInfo("PrismEditor", "Activated $activated extension(s)")
            }
        }
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
        if (!NodeRuntime.isAvailable) return
        NodeRuntime.setListener { line ->
            scope.launch { js("PrismEditor.nodeMessage(" + jsString(line) + ")") }
        }
        if (!NodeRuntime.start(context)) {
            scope.launch { js("PrismEditor.nodeHostGone(" + jsString(NodeRuntime.unavailableReason()) + ")") }
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
            androidx.appcompat.app.AlertDialog.Builder(context)
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

        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
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
            androidx.appcompat.app.AlertDialog.Builder(context)
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
            androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
            androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Appearance")
            .setItems(themes) { _, which -> js("PrismEditor.setTheme('${ids[which]}')") }
            .setNeutralButton("Font size…") { _, _ -> showFontSize() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFontSize() {
        val sizes = (9..22).map { it.toString() }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Font size")
            .setItems(sizes) { _, which -> js("PrismEditor.setFontSize(${sizes[which]})") }
            .show()
    }

    private fun showEditorLayout() {
        androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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
        androidx.appcompat.app.AlertDialog.Builder(context)
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

    override fun onDetachedFromWindow() {
        scope.cancel()
        terminal.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val COLLAPSED_DP = 46f
        const val EXPANDED_DP = 190f

        /** Menu panel width. Fits beside the collapsed rail on a phone; a 290dp panel did not. */
        const val MENU_WIDTH_DP = 270f
        const val MAX_OPEN_BYTES = 8L * 1024 * 1024
    }
}
