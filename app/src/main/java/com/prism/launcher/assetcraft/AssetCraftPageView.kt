package com.prism.launcher.assetcraft

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.media.DocumentImport
import com.prism.launcher.media.ImportedText
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AssetCraft on Android: the shared 3D scene, a prompt, and the two models that must be active.
 *
 * ## The viewport is not a picture of the scene
 *
 * Drag orbits, pinch zooms, a tap selects the node under the finger, and Animate advances the same
 * clock the desktop page uses. All of that runs on [AssetCraft]'s state and [MeshView]'s projection,
 * so a scene is not re-implemented for the phone: the page only paints the frame it is handed. The
 * clock is driven from the view's own frame loop rather than a background ticker, because a page
 * nobody is looking at has no reason to animate.
 *
 * ## Both models, before anything is generated
 *
 * The page reads its choices from [AssetCraft], which combines trained ModelCraft game models with
 * imported meshes and trained or imported text models. It does not offer Generate until both are
 * active: a half-configured asset editor otherwise fails at the end of a long run with a sentence
 * that should have been on screen at the start. The wand rewrites what is in the field, on request,
 * and never behind the user's back.
 */
class AssetCraftPageView(private val context: Context) : FrameLayout(context) {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private var enhancing = false
    private var importing = false
    private var wasVisible = false

    private val importer = DocumentImport(context)

    private var gameChoices: List<AssetCraft.Choice> = emptyList()
    private var textChoices: List<AssetCraft.Choice> = emptyList()
    private var gate: AssetCraft.Gate = AssetCraft.gate()

    private val gameChip = chip()
    private val textChip = chip()
    private val gateView = TextView(context).apply {
        setTextColor(palette.destructive.toInt())
        textSize = 11.5f
        setPadding(0, dp(6f), 0, 0)
    }
    private val placeholder = TextView(context).apply {
        text = "The scene is empty.\nChoose both models, then Generate."
        setTextColor(Color.argb(170, 255, 255, 255))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(18f), 0, dp(18f), 0)
    }
    private val viewport = MeshViewport()
    private val stage = TextView(context).apply {
        setTextColor(palette.muted.toInt())
        textSize = 12.5f
        setPadding(0, dp(6f), 0, 0)
    }
    private val note = TextView(context).apply {
        setTextColor(palette.faint.toInt())
        textSize = 11f
        setPadding(0, dp(2f), 0, 0)
    }
    private val progress = ProgressBar(
        context, null, android.R.attr.progressBarStyleHorizontal,
    ).apply {
        max = 100
        visibility = View.GONE
        progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
        progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(palette.divider.toInt())
    }
    private val prompt = EditText(context).apply {
        hint = "Describe the game asset you want"
        setTextColor(palette.text.toInt())
        setHintTextColor(palette.faint.toInt())
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        maxLines = 4
        background = rounded(palette.raised.toInt(), 12f)
        setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
    }
    private val enhance = TextView(context).apply {
        gravity = Gravity.CENTER
        background = rounded(palette.raised.toInt(), 12f)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        val icon = androidx.core.content.ContextCompat.getDrawable(
            context, com.prism.launcher.R.drawable.ic_wand_24,
        )
        setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(palette.accent.toInt())
        contentDescription = "Rewrite the prompt with the active text model"
    }
    private val generate = pill("Generate", accent = true)
    private val animate = pill("Animate")
    private val remove = ghost("Delete")
    private val clear = ghost("Clear scene")
    private val resetView = ghost("Reset view")

    init {
        setBackgroundColor(palette.background.toInt())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
        }

        column.addView(
            TextView(context).apply {
                text = "AssetCraft"
                setTextColor(palette.text.toInt())
                textSize = 26f
            },
        )
        column.addView(
            TextView(context).apply {
                text = "A game asset model shapes the mesh; a text model powers the wand. " +
                    "Drag to orbit, pinch to zoom, tap to select."
                setTextColor(palette.faint.toInt())
                textSize = 11.5f
                setPadding(0, dp(2f), 0, dp(8f))
            },
        )

        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chips.addView(
            gameChip,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        chips.addView(
            textChip,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6f)
            },
        )
        column.addView(chips)
        column.addView(gateView)

        // THE VIEWPORT TAKES THE SPACE THAT IS LEFT. A fixed-height box would either waste the tall
        // half of a phone or squeeze the prompt off the bottom of a short one; weight does neither.
        val frame = FrameLayout(context).apply {
            background = rounded(Color.rgb(13, 17, 25), 16f)
            clipToOutline = true
            minimumHeight = dp(170f)
            addView(
                viewport,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            addView(
                placeholder,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        column.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(2f)
            },
        )

        column.addView(progress)
        column.addView(stage)
        column.addView(note)

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        inputRow.addView(
            prompt,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        inputRow.addView(
            enhance,
            LinearLayout.LayoutParams(dp(50f), dp(50f)).apply { leftMargin = dp(7f) },
        )
        column.addView(
            inputRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8f) },
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            generate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            animate,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8f) },
        )
        column.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(9f) },
        )

        val secondary = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        arrayOf(remove, clear, resetView).forEachIndexed { index, button ->
            secondary.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) leftMargin = dp(6f) },
            )
        }
        column.addView(
            secondary,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f) },
        )

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        gameChip.setOnClickListener { pickGameModel() }
        textChip.setOnClickListener { pickTextModel() }
        enhance.setOnClickListener { enhancePrompt() }
        generate.setOnClickListener { generateAsset() }
        animate.setOnClickListener { AssetCraft.togglePlay() }
        remove.setOnClickListener { AssetCraft.removeSelected() }
        clear.setOnClickListener { AssetCraft.clearScene() }
        resetView.setOnClickListener { AssetCraft.resetCamera() }

        refreshChoices()
        render(AssetCraft.state.value)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A page rebuilt by the launcher starts with fresh state, and a model may have been trained
        // while this instance was detached. Rebuilding the list here is what keeps the chips from
        // being a snapshot of whenever the page was first inflated.
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        refreshChoices()
        render(AssetCraft.state.value)
        watch?.cancel()
        watch = scope.launch {
            AssetCraft.state.collect { state -> render(state) }
        }
    }

    /**
     * Re-reads the library when the page comes back on screen.
     *
     * TRAINING HAPPENS ON ANOTHER PAGE. A game model can be trained in ModelCraft and an imported
     * text model selected on Models while this page sits in the pager; without a refresh on the
     * visibility transition the chips would still name the old choices and Generate would stay
     * disabled over models that now exist. Only transitions are acted on, because Android calls this
     * far more often than the page actually appears.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !wasVisible && isAttachedToWindow) {
            refreshChoices()
            render(AssetCraft.state.value)
        }
        wasVisible = isVisible
    }

    override fun onDetachedFromWindow() {
        wasVisible = false
        watch?.cancel()
        watch = null
        importer.release()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    // ── The library ─────────────────────────────────────────────────────────────────────────────

    /** Re-reads both lists, their active ids and the gate. Cheap enough to do on attach and import. */
    private fun refreshChoices() {
        gameChoices = runCatching { AssetCraft.gameChoices() }.getOrDefault(emptyList())
        textChoices = runCatching { AssetCraft.textChoices() }.getOrDefault(emptyList())
        gate = AssetCraft.gate()
        setText(
            gameChip,
            "Game · " + (
                gameChoices.firstOrNull { it.id == AssetCraft.activeGameId() }?.name ?: "choose…"
                ),
        )
        setText(
            textChip,
            "Text · " + (
                textChoices.firstOrNull { it.id == AssetCraft.activeTextId() }?.name ?: "choose…"
                ),
        )
    }

    private fun pickGameModel() {
        // Opening a picker is also the natural point to re-read what ModelCraft has trained since
        // this page was last shown.
        refreshChoices()
        val labels = gameChoices.map { choice ->
            (if (choice.id == AssetCraft.activeGameId()) "✓ " else "") +
                choice.name + "\n" + choice.detail
        }
        val builder = PrismDialogs.Builder(context).setTitle("Game asset model")
        if (labels.isEmpty()) {
            builder.setMessage(
                "No game model yet. Train one in ModelCraft's Game tab, or import an .obj, .ply " +
                    "or .glb mesh here.",
            )
        } else {
            builder.setItems(labels.toTypedArray()) { _, which ->
                AssetCraft.setActiveGame(gameChoices.getOrNull(which))
                refreshChoices()
                render(AssetCraft.state.value)
            }
        }
        builder.setNeutralButton("Import…") { _, _ -> importGame() }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun pickTextModel() {
        refreshChoices()
        val labels = textChoices.map { choice ->
            (if (choice.id == AssetCraft.activeTextId()) "✓ " else "") +
                choice.name + "\n" + choice.detail
        }
        val builder = PrismDialogs.Builder(context).setTitle("Text model for the wand")
        if (labels.isEmpty()) {
            builder.setMessage(
                "No text model yet. Import a GGUF on the Models page, or train one in ModelCraft.",
            )
        } else {
            builder.setItems(labels.toTypedArray()) { _, which ->
                AssetCraft.setActiveText(textChoices.getOrNull(which))
                refreshChoices()
                render(AssetCraft.state.value)
            }
        }
        builder.setNeutralButton("Import…") { _, _ -> importText() }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // ── Importing ────────────────────────────────────────────────────────────────────────────────

    private fun importGame() {
        if (importing) return
        importer.open(arrayOf("*/*")) { uris ->
            if (uris.isEmpty()) return@open
            val files = copyIntoStaging(uris)
            if (files.isEmpty()) {
                Toast.makeText(context, "Nothing could be read from those files", Toast.LENGTH_SHORT)
                    .show()
                return@open
            }
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { AssetCraft.importGame(files) }
                importing = false
                refreshChoices()
                Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                render(AssetCraft.state.value)
            }
        }
    }

    private fun importText() {
        if (importing) return
        importer.open(arrayOf("*/*")) { uris ->
            if (uris.isEmpty()) return@open
            val files = copyIntoStaging(uris)
            if (files.isEmpty()) {
                Toast.makeText(context, "Nothing could be read from those files", Toast.LENGTH_SHORT)
                    .show()
                return@open
            }
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { AssetCraft.importText(files) }
                importing = false
                refreshChoices()
                Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                render(AssetCraft.state.value)
            }
        }
    }

    /**
     * Copies the picked documents into one cache folder.
     *
     * The game importer classifies files by extension, and a picked URI is not a normal file path,
     * so the bytes are staged and handed on as real files. The staging folder is removed after the
     * import; the importer has already copied what it keeps into AssetCraft's own directory.
     */
    private fun copyIntoStaging(uris: List<Uri>): List<File> {
        val staging = File(context.cacheDir, "assetcraft-import").apply {
            deleteRecursively()
            mkdirs()
        }
        return uris.mapNotNull { uri -> copyDocument(uri, staging) }
    }

    private fun copyDocument(uri: Uri, target: File): File? = runCatching {
        val name = ImportedText.name(context, uri)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(target, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    // ── Actions ──────────────────────────────────────────────────────────────────────────────────

    private fun generateAsset() {
        val typed = prompt.text.toString().trim()
        if (typed.isEmpty()) {
            stage.text = "Describe the asset first."
            return
        }
        if (!gate.ready) {
            gateView.visibility = View.VISIBLE
            return
        }
        if (!AssetCraft.start(typed)) {
            val error = AssetCraft.state.value.error
            if (!error.isNullOrBlank()) stage.text = error
        }
    }

    /**
     * Rewrites the field, not the scene. The user asked for better words, so the words are what
     * changes, and Generate still has to be pressed -- which is the whole reason the wand is a
     * separate control. An empty or missing reply leaves the typed words alone and says so.
     */
    private fun enhancePrompt() {
        if (enhancing) return
        val typed = prompt.text.toString().trim()
        if (typed.isEmpty()) {
            stage.text = "Type a prompt, then press the wand to have it rewritten."
            return
        }
        if (!gate.textReady) {
            Toast.makeText(
                context,
                "No text model is active, so there is nothing to enhance with",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        enhancing = true
        enhance.alpha = 0.4f
        stage.text = "Rewriting your prompt…"
        scope.launch {
            val rewritten = runCatching { AssetCraft.enhance(typed) }.getOrNull()
            enhancing = false
            enhance.alpha = 1f
            if (rewritten.isNullOrBlank()) {
                stage.text = "The text model had nothing to add — your words are unchanged."
                return@launch
            }
            prompt.setText(rewritten)
            prompt.setSelection(prompt.text.length)
            stage.text = "Rewritten. Edit it, or press Generate."
        }
    }

    /** Paints one [AssetCraft.State]. Idempotent: a state emission may happen sixty times a second. */
    private fun render(state: AssetCraft.State) {
        generate.isEnabled = !state.running && gate.ready
        generate.alpha = if (generate.isEnabled) 1f else 0.4f
        animate.isEnabled = !state.isEmptyScene
        animate.alpha = if (animate.isEnabled) 1f else 0.4f
        setText(animate, if (state.playing) "Pause" else "Animate")
        prompt.isEnabled = !state.running
        enhance.isEnabled = gate.textReady && !state.running && !enhancing
        enhance.alpha = if (enhance.isEnabled) 1f else 0.4f
        remove.isEnabled = state.selected >= 0
        remove.alpha = if (remove.isEnabled) 1f else 0.4f
        clear.isEnabled = !state.isEmptyScene
        clear.alpha = if (clear.isEnabled) 1f else 0.4f

        val gateText = if (gate.ready) "" else gate.reason
        setText(gateView, gateText)
        gateView.visibility = if (gateText.isBlank()) View.GONE else View.VISIBLE

        progress.visibility = if (state.running) View.VISIBLE else View.GONE
        if (state.running) {
            if (state.progress <= 0f) {
                progress.isIndeterminate = true
            } else {
                progress.isIndeterminate = false
                progress.progress = (state.progress.coerceIn(0f, 1f) * 100).toInt()
            }
        }

        setText(
            stage,
            when {
                state.running -> state.stage
                state.error != null -> "Failed: ${state.error}"
                state.complete -> buildString {
                    append("Generated ${state.generated} asset")
                    if (state.generated != 1) append("s")
                    append(" in ")
                    append("%.1fs".format(state.millis / 1000.0))
                    if (state.selectedNode != null) {
                        append(" · ${state.scene.nodes.size} in the scene")
                    }
                }
                state.stage.isNotEmpty() -> state.stage
                gate.ready -> "Choose a node by tapping it, then orbit or animate it."
                else -> ""
            },
        )
        setText(note, state.note)
        note.visibility = if (state.note.isBlank()) View.GONE else View.VISIBLE
        placeholder.visibility = if (state.isEmptyScene) View.VISIBLE else View.GONE
        viewport.invalidate()
    }

    private fun setText(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    // ── The viewport ─────────────────────────────────────────────────────────────────────────────

    /**
     * Draws the shared [MeshFrame] and translates touches into camera moves and picks.
     *
     * This is a plain [View] rather than a SurfaceView or GLSurfaceView on purpose: the frame list
     * the shared projection produces is small, the rest of the page is ordinary Android views, and a
     * separate surface would put a hole in the page's rounded card on some devices.
     */
    private inner class MeshViewport : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
        }
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.accent.toInt()
        }
        private val path = Path()
        private var backdrop: Paint? = null
        private var backdropHeight = -1

        private val scale = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (detector.scaleFactor > 0f) AssetCraft.zoom(1f / detector.scaleFactor)
                    return true
                }
            },
        )

        private val gestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    // A two-finger gesture belongs to the scaler; orbiting under it would fight the
                    // zoom and make both feel broken.
                    if (scale.isInProgress) return false
                    AssetCraft.orbit(-distanceX, -distanceY)
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    AssetCraft.pick(e.x, e.y, width, height)
                    return true
                }
            },
        )

        init {
            isClickable = true
            isFocusable = true
            contentDescription =
                "3D asset viewport. Drag to orbit, pinch to zoom, tap a part to select it."
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scale.onTouchEvent(event)
            if (!scale.isInProgress) gestures.onTouchEvent(event)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val before = AssetCraft.state.value
            if (before.playing) {
                // The page owns the clock. Advancing it here ties animation cost to being on screen,
                // and a background page that stopped drawing also stops changing state.
                AssetCraft.tick(1f / 30f)
                postInvalidateDelayed(33)
            }
            val state = AssetCraft.state.value
            drawBackdrop(canvas)
            if (width <= 0 || height <= 0) return

            val frame: MeshFrame = MeshView.frame(
                state.scene,
                state.camera,
                width,
                height,
                state.time,
                state.selected,
            )
            frame.grid.forEach { segment ->
                line.color = segment.color
                line.strokeWidth = segment.width.coerceAtLeast(1f)
                canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, line)
            }
            fill.style = Paint.Style.FILL
            for (face in frame.faces) {
                path.reset()
                path.moveTo(face.xs[0], face.ys[0])
                path.lineTo(face.xs[1], face.ys[1])
                path.lineTo(face.xs[2], face.ys[2])
                path.close()
                fill.color = face.color
                canvas.drawPath(path, fill)
            }
            line.strokeWidth = dp(1.1f).toFloat()
            for (edge in frame.edges) {
                line.color = edge.color
                canvas.drawLine(edge.x1, edge.y1, edge.x2, edge.y2, line)
            }
            // The selected marker is drawn over the geometry so a tap on a thin prop still says
            // exactly which node was chosen.
            frame.centers.firstOrNull { it.node == state.selected }?.let { center ->
                canvas.drawCircle(center.x, center.y, dp(4.5f).toFloat(), marker)
                fill.color = Color.WHITE
                canvas.drawCircle(center.x, center.y, dp(1.6f).toFloat(), fill)
            }
        }

        private fun drawBackdrop(canvas: Canvas) {
            val paint = backdrop ?: Paint(Paint.ANTI_ALIAS_FLAG).also { backdrop = it }
            if (backdropHeight != height) {
                backdropHeight = height
                paint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    height.toFloat().coerceAtLeast(1f),
                    Color.rgb(13, 17, 25),
                    Color.rgb(32, 44, 61),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    // ── Small widgets ────────────────────────────────────────────────────────────────────────────

    private fun chip(): TextView = TextView(context).apply {
        textSize = 11.5f
        setTextColor(palette.text.toInt())
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = rounded(palette.raised.toInt(), 10f)
        setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
        isClickable = true
        isFocusable = true
    }

    private fun pill(text: String, accent: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        setTextColor(if (accent) palette.onAccent.toInt() else palette.text.toInt())
        background = rounded(
            if (accent) palette.accent.toInt() else palette.raised.toInt(), 12f,
        )
        isClickable = true
        isFocusable = true
    }

    private fun ghost(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        setTextColor(palette.muted.toInt())
        background = rounded(palette.raised.toInt(), 9f)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Float): Int = IosUi.dp(context, value)
}
