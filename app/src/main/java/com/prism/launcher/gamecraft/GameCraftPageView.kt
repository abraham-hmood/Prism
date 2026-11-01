package com.prism.launcher.gamecraft

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.assetcraft.AssetCraft
import com.prism.launcher.assetcraft.GameCraft
import com.prism.launcher.assetcraft.MeshCamera
import com.prism.launcher.assetcraft.MeshView
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * GameCraft on Android: the library of games the user made, and the maker behind the plus button.
 *
 * The library is the resting place. The maker is a mode of the same page rather than a second
 * activity, because a generated game is meant to be seen immediately and coming back from an
 * activity would rebuild the library for no reason.
 *
 * Everything is styled from the active theme's palette. The viewport is the only fixed-dark surface:
 * a 3D scene is read against darkness, and a translucent sheet of the theme's colour would make the
 * mesh float instead of sit in a world.
 */
class GameCraftPageView(private val context: Context) : FrameLayout(context) {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private var wasVisible = false

    private lateinit var listLayer: ScrollView
    private lateinit var listColumn: LinearLayout
    private lateinit var makerLayer: LinearLayout
    private lateinit var viewportFrame: FrameLayout
    private lateinit var viewport: GameViewport
    private lateinit var viewSwitch: TextView
    private lateinit var prompt: EditText
    private lateinit var generate: TextView
    private lateinit var meshChip: TextView
    private lateinit var textChip: TextView
    private lateinit var imageChip: TextView
    private lateinit var gateView: TextView
    private lateinit var stage: TextView
    private lateinit var note: TextView
    private lateinit var back: TextView
    private lateinit var fab: TextView
    private lateinit var summaryRow: TextView

    private var textureBitmap: Bitmap? = null

    init {
        setBackgroundColor(palette.background.toInt())
        buildList()
        buildMaker()
        refresh()
        render(GameCraft.state.value)

        watch = scope.launch {
            GameCraft.state.collect { state -> render(state) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        if (watch?.isActive != true) {
            watch = scope.launch { GameCraft.state.collect { state -> render(state) } }
        }
        refresh()
        render(GameCraft.state.value)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !wasVisible && isAttachedToWindow) {
            refresh()
            render(GameCraft.state.value)
        }
        wasVisible = isVisible
    }

    override fun onDetachedFromWindow() {
        wasVisible = false
        watch?.cancel()
        watch = null
        textureBitmap?.recycle()
        textureBitmap = null
        scope.cancel()
        super.onDetachedFromWindow()
    }

    // ── The library ─────────────────────────────────────────────────────────────

    private fun buildList() {
        listColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(92f))
        }
        listColumn.addView(
            TextView(context).apply {
                text = "GameCraft"
                setTextColor(palette.text.toInt())
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        summaryRow = TextView(context).apply {
            setTextColor(palette.faint.toInt())
            textSize = 11.5f
            setPadding(0, dp(2f), 0, dp(10f))
        }
        listColumn.addView(summaryRow)
        listLayer = ScrollView(context).apply {
            isFillViewport = true
            addView(listColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(listLayer)

        fab = TextView(context).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 30f
            setTextColor(palette.onAccent.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent.toInt())
                setStroke(dp(1f), Color.argb(110, 255, 255, 255))
            }
            elevation = dp(8f).toFloat()
            contentDescription = "Make a new game or mesh"
            isClickable = true
            isFocusable = true
            setOnClickListener { newGame() }
        }
        addView(
            fab,
            LayoutParams(dp(58f), dp(58f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20f)
                bottomMargin = dp(22f)
            },
        )
    }

    private fun refresh() {
        val games = runCatching { GameCraft.games() }.getOrDefault(emptyList())
        val gate = GameCraft.gate()
        summaryRow.text = buildString {
            append(
                if (games.isEmpty()) "Your games and meshes live here."
                else "${games.size} game${if (games.size == 1) "" else "s"} made on this device.",
            )
            append(" Mesh model: ")
            append(if (gate.meshName.isBlank()) "none active" else gate.meshName)
            if (gate.canTexture) append(" · textures on")
            if (gate.canCode) append(" · code on")
        }
        gateView?.let {
            it.text = gate.reason
            it.visibility = if (gate.reason.isBlank()) View.GONE else View.VISIBLE
        }

        // Rebuild only the game rows, keeping the heading and the explanatory line at the top.
        while (listColumn.childCount > 2) listColumn.removeViewAt(2)
        if (games.isEmpty()) {
            listColumn.addView(
                TextView(context).apply {
                    text = "Nothing here yet. The plus button makes a game or a mesh. A mesh generator " +
                        "model has to be active first — GameCraft will show exactly which one is missing."
                    setTextColor(palette.faint.toInt())
                    textSize = 13f
                    setLineSpacing(dp(3f).toFloat(), 1f)
                    setPadding(0, dp(14f), 0, dp(8f))
                },
            )
            return
        }
        games.forEach { game -> listColumn.addView(gameRow(game)) }
    }

    private fun gameRow(game: GameCraft.GameSummary): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = rounded(palette.card.toInt(), 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener { GameCraft.open(game) }
            setOnLongClickListener {
                confirmDelete(game)
                true
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(9f) }
            layoutParams = params
        }
        card.addView(
            TextView(context).apply {
                text = game.name
                setTextColor(palette.text.toInt())
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        card.addView(
            TextView(context).apply {
                text = game.prompt
                setTextColor(palette.muted.toInt())
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, dp(5f))
            },
        )
        card.addView(
            TextView(context).apply {
                text = buildString {
                    append("${game.triangles} triangles")
                    if (game.isScene) append(" · scene")
                    if (game.hasTexture) append(" · 1024² texture")
                    if (game.hasCode) append(" · compiled script")
                    append(" · long press to delete")
                }
                setTextColor(palette.faint.toInt())
                textSize = 10.5f
            },
        )
        return card
    }

    private fun confirmDelete(game: GameCraft.GameSummary) {
        PrismDialogs.Builder(context)
            .setTitle("Delete ${game.name}?")
            .setMessage("This removes its mesh, texture and script from this device.")
            .setPositiveButton("Delete") { _, _ ->
                GameCraft.delete(game)
                refresh()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun newGame() {
        val gate = GameCraft.gate()
        if (!gate.ready) {
            PrismDialogs.Builder(context)
                .setTitle("Choose a mesh generator")
                .setMessage(
                    gate.reason.ifBlank {
                        "Set a mesh generator model as active before making a game."
                    },
                )
                .setPositiveButton("Choose model…") { _, _ -> pickMeshModel() }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        GameCraft.beginNew()
    }

    // ── The maker ───────────────────────────────────────────────────────────────

    private fun buildMaker() {
        makerLayer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        back = TextView(context).apply {
            text = "\u2039  Games"
            setTextColor(palette.accent.toInt())
            textSize = 14f
            setPadding(0, dp(6f), dp(12f), dp(6f))
            isClickable = true
            setOnClickListener { GameCraft.showList() }
        }
        header.addView(back)
        header.addView(
            TextView(context).apply {
                text = "Game maker"
                setTextColor(palette.text.toInt())
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        makerLayer.addView(header)

        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, dp(4f))
        }
        meshChip = chip()
        textChip = chip()
        imageChip = chip()
        chips.addView(meshChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        chips.addView(
            textChip,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                leftMargin = dp(5f)
            },
        )
        chips.addView(
            imageChip,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                leftMargin = dp(5f)
            },
        )
        makerLayer.addView(chips)
        gateView = TextView(context).apply {
            setTextColor(palette.destructive.toInt())
            textSize = 11f
            setPadding(0, dp(3f), 0, dp(3f))
        }
        makerLayer.addView(gateView)

        viewportFrame = FrameLayout(context).apply {
            background = rounded(Color.rgb(8, 11, 18), 18f)
            clipToOutline = true
            minimumHeight = dp(200f)
        }
        viewport = GameViewport()
        viewportFrame.addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        viewSwitch = TextView(context).apply {
            text = "3D"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
            background = GradientDrawable().apply {
                setColor(Color.argb(110, 0, 0, 0))
                cornerRadius = dp(13f).toFloat()
                setStroke(dp(1f), Color.argb(120, 255, 255, 255))
            }
            isClickable = true
            setOnClickListener {
                GameCraft.setView3d(!GameCraft.state.value.view3d)
            }
        }
        viewportFrame.addView(
            viewSwitch,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10f)
                rightMargin = dp(10f)
            },
        )
        makerLayer.addView(
            viewportFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(7f)
                bottomMargin = dp(4f)
            },
        )

        stage = TextView(context).apply {
            setTextColor(palette.muted.toInt())
            textSize = 11.5f
            setPadding(0, dp(5f), 0, 0)
        }
        note = TextView(context).apply {
            setTextColor(palette.faint.toInt())
            textSize = 10.5f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        }
        makerLayer.addView(stage)
        makerLayer.addView(note)

        prompt = EditText(context).apply {
            hint = "Describe a game, a scene, or a playable creature"
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 4
            background = rounded(palette.raised.toInt(), 13f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        makerLayer.addView(
            prompt,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7f) },
        )

        generate = TextView(context).apply {
            text = "Generate game"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(palette.onAccent.toInt())
            background = rounded(palette.accent.toInt(), 14f)
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val typed = prompt.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(context, "Describe what to make first", Toast.LENGTH_SHORT).show()
                } else {
                    GameCraft.generate(typed)
                }
            }
        }
        makerLayer.addView(
            generate,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7f) },
        )

        addView(makerLayer)

        meshChip.setOnClickListener { pickMeshModel() }
        textChip.setOnClickListener {
            showCapability(
                "Text model / compiled code",
                GameCraft.gate().textName,
                "A text model writes the C++ movement script. It only participates when Agentic " +
                    "Tools is enabled. Set the model active on Models or Cloud AI.",
            )
        }
        imageChip.setOnClickListener {
            showCapability(
                "Image model / textures",
                GameCraft.gate().imageName,
                "An image model paints the 1024 x 1024 texture. It only participates when Agentic " +
                    "Tools is enabled. Set the model active on Models or Cloud AI.",
            )
        }
    }

    private fun chip(): TextView = TextView(context).apply {
        textSize = 11f
        setTextColor(palette.text.toInt())
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = rounded(palette.raised.toInt(), 11f)
        setPadding(dp(9f), dp(8f), dp(9f), dp(8f))
        isClickable = true
        isFocusable = true
    }

    private fun render(state: GameCraft.State) {
        if (!::listLayer.isInitialized) return
        val maker = state.screen == GameCraft.Screen.MAKER
        listLayer.visibility = if (maker) View.GONE else View.VISIBLE
        fab.visibility = if (maker) View.GONE else View.VISIBLE
        makerLayer.visibility = if (maker) View.VISIBLE else View.GONE
        if (!maker) {
            refresh()
            return
        }
        val gate = GameCraft.gate()
        meshChip.text = "Mesh · " + (gate.meshName.ifBlank { "choose…" })
        textChip.text = "Text · " + (if (gate.canCode) gate.textName else "off")
        imageChip.text = "Image · " + (if (gate.canTexture) gate.imageName else "off")
        gateView.text = if (gate.ready) gate.informative else gate.reason
        gateView.setTextColor(
            if (gate.ready) palette.muted.toInt() else palette.destructive.toInt(),
        )
        if (state.prompt.isNotBlank() && prompt.text.toString() != state.prompt && state.generating) {
            prompt.setText(state.prompt)
        }
        stage.text = when {
            state.error != null -> state.error
            state.generating -> state.stage
            state.complete -> "Saved to GameCraft · ${state.gameName}"
            else -> gate.informative.ifBlank { "Set a prompt, then Generate." }
        }
        stage.setTextColor(
            if (state.error != null) palette.destructive.toInt() else palette.muted.toInt(),
        )
        note.text = state.note
        generate.text = if (state.generating) "Generating…" else "Generate game"
        generate.isEnabled = !state.generating
        generate.alpha = if (state.generating) 0.55f else 1f
        viewSwitch.text = if (state.view3d) "3D" else "2D"
        viewport.state = state
        viewport.invalidate()
        updateTextureBitmap(state)
    }

    private fun updateTextureBitmap(state: GameCraft.State) {
        val texture = state.texture
        if (texture == null) {
            textureBitmap?.recycle()
            textureBitmap = null
            return
        }
        val existing = textureBitmap
        if (existing != null && existing.width == texture.width && existing.height == texture.height) {
            return
        }
        textureBitmap?.recycle()
        textureBitmap = Bitmap.createBitmap(
            texture.width, texture.height, Bitmap.Config.ARGB_8888,
        ).apply {
            setPixels(texture.pixels, 0, texture.width, 0, 0, texture.width, texture.height)
        }
    }

    // ── Pickers ─────────────────────────────────────────────────────────────────

    private fun pickMeshModel() {
        val choices = runCatching { AssetCraft.gameChoices() }.getOrDefault(emptyList())
        val builder = PrismDialogs.Builder(context).setTitle("Mesh generator")
        if (choices.isEmpty()) {
            builder.setMessage(
                "No mesh generator yet. Train a Game model in ModelCraft, or import a ModelCraft " +
                    "game folder in Prism Settings.",
            )
        } else {
            val labels = choices.map { choice ->
                (if (choice.id == AssetCraft.activeGameId()) "✓ " else "") + choice.name + "\n" + choice.detail
            }
            builder.setItems(labels.toTypedArray()) { _, which ->
                AssetCraft.setActiveGame(choices.getOrNull(which))
                refresh()
                render(GameCraft.state.value)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showCapability(title: String, model: String, explanation: String) {
        PrismDialogs.Builder(context)
            .setTitle(title)
            .setMessage(
                (if (model.isBlank()) "No model is active for this job.\n\n" else "$model\n\n") +
                    explanation,
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Viewport ────────────────────────────────────────────────────────────────

    @SuppressLint("ViewConstructor")
    private inner class GameViewport : View(context) {

        var state: GameCraft.State = GameCraft.State()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
        }
        private val path = Path()
        private var raster: Bitmap? = null
        private var backdrop: Paint? = null
        private var backdropHeight = -1

        private val scale = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (detector.scaleFactor > 0f) GameCraft.zoom(1f / detector.scaleFactor)
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
                    if (scale.isInProgress) return false
                    GameCraft.orbit(-distanceX, -distanceY)
                    return true
                }
            },
        )

        private var movePointer = -1
        private var lookPointer = -1
        private var moveX = 0f
        private var moveY = 0f
        private var turn = 0f

        init {
            isClickable = true
            isFocusable = true
            contentDescription =
                "Game viewport. Drag to orbit, pinch to zoom. Joysticks control a compiled script."
        }

        override fun onDraw(canvas: Canvas) {
            drawBackdrop(canvas)
            val current = state
            if (current.scene.isEmpty()) {
                drawEmpty(canvas)
                return
            }
            if (!current.view3d && textureBitmap != null && current.generating.not()) {
                drawTextureMode(canvas, current)
            } else {
                drawScene(canvas, current)
            }
            if (current.controllable && !current.generating) {
                drawSticks(canvas)
            }
            if (current.generating) {
                drawProgress(canvas, current)
            }
        }

        private fun drawEmpty(canvas: Canvas) {
            fill.color = Color.argb(140, 255, 255, 255)
            fill.textSize = dp(12f).toFloat()
            fill.textAlign = Paint.Align.CENTER
            canvas.drawText("The viewport is empty.", width / 2f, height / 2f - dpf(8f), fill)
            fill.color = Color.argb(100, 255, 255, 255)
            fill.textSize = dp(10.5f).toFloat()
            canvas.drawText(
                "Generate a game, or pick a mesh generator to begin.",
                width / 2f,
                height / 2f + dpf(12f),
                fill,
            )
        }

        private fun drawScene(canvas: Canvas, current: GameCraft.State) {
            val camera = if (current.view3d) current.camera else MeshCamera(yaw = 0f, pitch = 0f, distance = 3.6f)
            val w = max(1, width)
            val h = max(1, height)
            // A FULL-SCREEN GAME IS 80,000+ TRIANGLES PER ASSET. Drawing one Path per face on the
            // UI thread is what made the phone hot and the viewport a slideshow; the shared
            // rasteriser fills a pixel buffer and this blits it once, so the cost is one bitmap per
            // frame instead of tens of thousands of draw calls.
            val pixels = MeshView.render(current.scene, w, h, camera, 0f, -1)
            val bitmap = raster?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { raster = it }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            if (!current.view3d) {
                fill.color = Color.argb(180, 0, 0, 0)
                canvas.drawRect(0f, height - dpf(25f), width.toFloat(), height.toFloat(), fill)
                fill.color = Color.WHITE
                fill.textSize = dp(10f).toFloat()
                fill.textAlign = Paint.Align.LEFT
                canvas.drawText("2D projection · front", dpf(10f), height - dpf(8f), fill)
            }
        }

        private fun drawTextureMode(canvas: Canvas, current: GameCraft.State) {
            val bitmap = textureBitmap ?: return
            // Texture first, then the shape as a smaller inset window. In 2D mode the texture is the
            // asset: a game that only has a textured mesh is judged by its material as much as its
            // silhouette.
            val padding = dpf(10f)
            val availableW = width - padding * 2
            val availableH = height - padding * 2
            val side = min(availableW, availableH)
            val left = (width - side) / 2f
            val top = padding + (availableH - side) / 2f
            val destination = android.graphics.RectF(left, top, left + side, top + side)
            canvas.drawBitmap(bitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            fill.color = Color.argb(180, 0, 0, 0)
            canvas.drawRect(left, top + side - dpf(22f), left + side, top + side, fill)
            fill.color = Color.WHITE
            fill.textSize = dp(10f).toFloat()
            fill.textAlign = Paint.Align.LEFT
            canvas.drawText(
                "${bitmap.width} x ${bitmap.height} albedo · ${current.mesh?.triangleCount ?: 0} triangles",
                left + dpf(7f),
                top + side - dpf(7f),
                fill,
            )
        }

        private fun drawProgress(canvas: Canvas, current: GameCraft.State) {
            fill.color = Color.argb(170, 0, 0, 0)
            canvas.drawRect(0f, height - dpf(29f), width.toFloat(), height.toFloat(), fill)
            fill.color = Color.argb(230, 255, 255, 255)
            fill.textSize = dp(10.5f).toFloat()
            fill.textAlign = Paint.Align.LEFT
            canvas.drawText(current.stage.take(64), dpf(9f), height - dpf(11f), fill)
            fill.color = palette.accent.toInt()
            canvas.drawRect(0f, height - dpf(3f), width * current.progress.coerceIn(0f, 1f), height.toFloat(), fill)
        }

        private fun drawBackdrop(canvas: Canvas) {
            val paint = backdrop ?: Paint(Paint.ANTI_ALIAS_FLAG).also { backdrop = it }
            if (backdropHeight != height) {
                backdropHeight = height
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f),
                    Color.rgb(8, 11, 18), Color.rgb(28, 38, 55), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        private fun drawSticks(canvas: Canvas) {
            val radius = min(width, height) * 0.11f
            val leftX = width * 0.17f
            val rightX = width * 0.83f
            val centerY = height - radius - dpf(18f)
            fill.style = Paint.Style.FILL
            fill.color = Color.argb(55, 255, 255, 255)
            canvas.drawCircle(leftX, centerY, radius, fill)
            canvas.drawCircle(rightX, centerY, radius, fill)
            stroke.color = Color.argb(120, 157, 255, 60)
            stroke.strokeWidth = dp(2f).toFloat()
            canvas.drawCircle(leftX + moveX * radius * 0.7f, centerY + moveY * radius * 0.7f, radius * 0.38f, stroke)
            stroke.color = Color.argb(120, 57, 231, 255)
            canvas.drawCircle(rightX + turn * radius * 0.7f, centerY, radius * 0.38f, stroke)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scale.onTouchEvent(event)
            if (scale.isInProgress) {
                gestures.onTouchEvent(event)
                return true
            }
            val radius = min(width, height) * 0.11f
            val leftX = width * 0.17f
            val rightX = width * 0.83f
            val centerY = height - radius - dpf(18f)
            val controlled = state.controllable && !state.generating
            if (!controlled) {
                gestures.onTouchEvent(event)
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hypot(event.x - leftX, event.y - centerY) < radius * 1.5f) {
                        movePointer = event.getPointerId(event.actionIndex)
                        updateMove(event.x, event.y, leftX, centerY, radius)
                    } else if (hypot(event.x - rightX, event.y - centerY) < radius * 1.5f) {
                        lookPointer = event.getPointerId(event.actionIndex)
                        updateLook(event.x, rightX, radius)
                    } else {
                        gestures.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (movePointer < 0 && hypot(event.x - leftX, event.y - centerY) < radius * 1.5f) {
                        movePointer = event.getPointerId(event.actionIndex)
                        updateMove(event.x, event.y, leftX, centerY, radius)
                    } else if (lookPointer < 0 && hypot(event.x - rightX, event.y - centerY) < radius * 1.5f) {
                        lookPointer = event.getPointerId(event.actionIndex)
                        updateLook(event.x, rightX, radius)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val moveIndex = event.findPointerIndex(movePointer)
                    val lookIndex = event.findPointerIndex(lookPointer)
                    if (moveIndex >= 0) updateMove(event.getX(moveIndex), event.getY(moveIndex), leftX, centerY, radius)
                    if (lookIndex >= 0) updateLook(event.getX(lookIndex), rightX, radius)
                    if (moveIndex >= 0 || lookIndex >= 0) {
                        GameCraft.control(moveX, -moveY, turn, 1f / 40f)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val lifted = event.getPointerId(event.actionIndex)
                    if (lifted == movePointer) { movePointer = -1; moveX = 0f; moveY = 0f }
                    if (lifted == lookPointer) { lookPointer = -1; turn = 0f }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    movePointer = -1
                    lookPointer = -1
                    moveX = 0f; moveY = 0f; turn = 0f
                }
            }
            return true
        }

        private fun updateMove(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float) {
            moveX = ((x - centerX) / (radius * 0.85f)).coerceIn(-1f, 1f)
            moveY = ((y - centerY) / (radius * 0.85f)).coerceIn(-1f, 1f)
        }

        private fun updateLook(x: Float, centerX: Float, radius: Float) {
            turn = ((x - centerX) / (radius * 0.85f)).coerceIn(-1f, 1f)
        }
    }

    // ── Small widgets ───────────────────────────────────────────────────────────

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1f), Color.argb(28, 255, 255, 255))
    }

    private fun dp(value: Float): Int = IosUi.dp(context, value)

    /** The same density conversion for Canvas coordinates, which are always Floats. */
    private fun dpf(value: Float): Float = dp(value).toFloat()

    /** The page's own back contract: maker -> library, otherwise leave the page. */
    fun onBack(): Boolean {
        if (GameCraft.state.value.screen == GameCraft.Screen.MAKER) {
            GameCraft.showList()
            return true
        }
        return false
    }
}
