package com.prism.launcher.minigames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The shared neon renderer for the thirteen short arcade games in [ArcadeGames].
 *
 * It owns no rules. A game produces a [ArcadeScene] of normalized sprites and this draws it: one
 * board, one palette, one row of controls. Adding a game with a new layout means adding a scene,
 * not another 700-line View.
 */
class ArcadeGameView(
    context: Context,
    gameId: String,
    private val meshPeer: String? = null,
    private val localSeat: Int = 0,
    seed: Long = System.nanoTime(),
) : View(context), TopInsetAware, MinigameFeedback {

    private val id = gameId
    private val game: ArcadeGame = if (gameId == "chutes" && meshPeer != null) {
        ChutesGame(players = 2, autoAi = false, seed = seed)
    } else {
        ArcadeGames.create(gameId, seed) ?: error("unknown arcade game $gameId")
    }

    private val colors: MinigameColors = MinigameTheme.of(context)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        color = colors.muted
        textSize = dp(11f)
    }

    private var topInset = 0
    private var lastFrame = 0L
    private var recorded = false
    private var downX = 0f
    private var downY = 0f
    private var swipeFired = false
    private var buttonDown = -1
    private val buttons = ArrayList<Pair<RectF, ArcadeAction>>()
    private var board = RectF()

    /** When the run ended, for the win/lose burst. Zero while it is still going. */
    private var outcomeAt = 0L
    private var outcomeWon = false

    private val palette = colors.sprites()

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016f
            else ((now - lastFrame) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
            lastFrame = now
            game.update(dt)
            if (game.over && !recorded) {
                recorded = true
                outcomeAt = now
                outcomeWon = game.won
                MinigameStore.recordArcade(id, game.score)
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private val onMove: (String, String) -> Unit = { peer, payload ->
        if (peer == meshPeer && game is ChutesGame) {
            post {
                (game as ChutesGame).applyLine(payload)
                invalidate()
            }
        }
    }

    init {
        ArcadeStyle.applyTheme(colors)
        setBackgroundColor(colors.background)
        isClickable = true
        if (meshPeer != null) MinigameMesh.listenForMoves(onMove)
    }

    override fun musicIntensity(): Float = game.intensity

    override fun outcome(): Boolean? = if (game.over) game.won else null

    override fun onAttachedToWindow() {
        lastFrame = 0L
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        if (meshPeer != null) MinigameMesh.stopListening()
    }

    override fun applyTopInset(pixels: Int) {
        topInset = pixels
        requestLayout()
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun layoutBoard(scene: ArcadeScene) {
        val top = topInset + dp(34f)
        val buttonRow = if (scene.buttons.isNotEmpty() || game.over) dp(62f) else dp(12f)
        val availableH = (height - top - buttonRow).coerceAtLeast(dp(80f))
        val availableW = (width - dp(24f)).coerceAtLeast(dp(80f))
        val aspect = scene.aspect.coerceAtLeast(0.25f)
        var w = availableW
        var h = w / aspect
        if (h > availableH) {
            h = availableH
            w = h * aspect
        }
        val left = (width - w) / 2f
        board = RectF(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = game.scene()
        layoutBoard(scene)

        hudPaint.textSize = dp(11f)
        canvas.drawText(scene.hud, dp(14f), topInset + dp(24f), hudPaint)

        // Board well: a faint frame in the theme's accent so the game sits inside the cabinet.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = alpha(colors.accent, 0x66)
        canvas.drawRoundRect(board, dp(10f), dp(10f), paint)

        if (scene.gridW > 0 && scene.gridH > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(0.6f)
            paint.color = alpha(colors.accent, 0x1A)
            for (i in 1 until scene.gridW) {
                val x = board.left + board.width() * i / scene.gridW
                canvas.drawLine(x, board.top, x, board.bottom, paint)
            }
            for (i in 1 until scene.gridH) {
                val y = board.top + board.height() * i / scene.gridH
                canvas.drawLine(board.left, y, board.right, y, paint)
            }
        }

        scene.sprites.forEach { sprite -> drawSprite(canvas, sprite) }
        if (game.over && outcomeAt > 0L) {
            drawOutcome(
                canvas,
                ((System.nanoTime() - outcomeAt) / 1_000_000_000.0).toFloat(),
                scene.message,
            )
        } else {
            scene.message?.let { drawMessage(canvas, it) }
        }
        drawButtons(canvas, scene)
    }

    private fun colour(index: Int): Int = palette[index.coerceIn(0, palette.size - 1)]

    private fun alpha(colour: Int, value: Int): Int =
        (colour and 0x00FFFFFF) or (value.coerceIn(0, 255) shl 24)

    private fun drawSprite(canvas: Canvas, sprite: ArcadeSprite) {
        val c = colour(sprite.colour)
        val left = board.left + sprite.x * board.width()
        val top = board.top + sprite.y * board.height()
        val right = left + sprite.w * board.width()
        val bottom = top + sprite.h * board.height()
        val rect = RectF(left, top, right, bottom)
        val alpha = (sprite.alpha * 255).toInt().coerceIn(0, 255)

        // Glow first, then the solid shape. Two draws read as neon without a blur layer.
        paint.style = Paint.Style.FILL
        paint.color = c
        paint.alpha = (alpha * 0.18f).toInt()
        when (sprite.kind) {
            1 -> canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.62f, paint)
            2 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.56f, paint)
            }
            else -> canvas.drawRoundRect(
                RectF(rect).apply { inset(-dp(2f), -dp(2f)) }, dp(4f), dp(4f), paint,
            )
        }

        paint.alpha = alpha
        when (sprite.kind) {
            1 -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.43f, paint)
            }
            2 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.46f, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
            }
        }

        sprite.label?.let { label ->
            paint.style = Paint.Style.FILL
            // Contrast follows the tile, not an assumption that every theme is dark.
            textPaint.color = if (luminance(c) < 140) 0xFFFFFFFF.toInt() else 0xFF101018.toInt()
            textPaint.textSize = (rect.height() * 0.44f).coerceIn(dp(7f), dp(22f))
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, textPaint)
        }
    }

    /**
     * The win/lose burst.
     *
     * One expanding ripple plus a ring of sparks in the result's colour, then a banner that stays
     * for as long as the result screen is up. Every value is derived from elapsed time, so the
     * animation costs no state beyond the timestamp and survives a frame drop without skipping.
     */
    private fun drawOutcome(canvas: Canvas, elapsed: Float, cause: String?) {
        val cx = board.centerX()
        val cy = board.centerY()
        val accent = if (outcomeWon) colors.success else colors.danger
        val progress = (elapsed / 1.4f).coerceIn(0f, 1f)
        val fade = 1f - progress

        repeat(3) { ring ->
            val radius = board.width() * (0.10f + progress * (0.30f + ring * 0.10f))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = alpha(accent, (150 * fade).toInt())
            canvas.drawCircle(cx, cy, radius, paint)
        }

        val sparks = kotlin.random.Random(if (outcomeWon) 0x5EEDL else 0xBADL)
        repeat(28) { index ->
            val angle = sparks.nextFloat() * 6.2832f
            val speed = 0.18f + sparks.nextFloat() * 0.55f
            val distance = board.width() * speed * progress
            val x = cx + kotlin.math.cos(angle) * distance
            val y = cy + kotlin.math.sin(angle) * distance
            paint.style = Paint.Style.FILL
            paint.color = alpha(if (outcomeWon) palette[index % 7] else accent, (210 * fade).toInt())
            canvas.drawCircle(x, y, dp(2.6f) * (1f - progress * 0.4f), paint)
        }

        val banner = if (outcomeWon) "VICTORY" else "DEFEAT"
        textPaint.color = accent
        textPaint.textSize = (board.width() * 0.115f).coerceIn(dp(20f), dp(46f))
        val baseline = cy + (textPaint.descent() - textPaint.ascent()) / 4f
        canvas.drawText(banner, cx, baseline, textPaint)

        if (!cause.isNullOrBlank()) {
            textPaint.color = colors.muted
            textPaint.textSize = dp(11f)
            canvas.drawText(cause.uppercase(), cx, baseline + dp(20f), textPaint)
        }
        if (!outcomeWon) {
            // A losing run gets one more cue than a winning one: the screen is red for a moment,
            // which is the difference between "the game ended" and "you were beaten".
            paint.style = Paint.Style.FILL
            paint.color = alpha(colors.danger, (60 * fade).toInt())
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private fun luminance(colour: Int): Int {
        val red = (colour shr 16) and 0xFF
        val green = (colour shr 8) and 0xFF
        val blue = colour and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun drawMessage(canvas: Canvas, message: String) {
        paint.style = Paint.Style.FILL
        paint.color = alpha(colors.board, 0xC0)
        val box = RectF(board.left, board.centerY() - dp(28f), board.right, board.centerY() + dp(28f))
        canvas.drawRoundRect(box, dp(8f), dp(8f), paint)
        textPaint.color = colors.accent
        textPaint.textSize = dp(15f)
        val baseline = box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(message.uppercase(), box.centerX(), baseline, textPaint)
    }

    private fun drawButtons(canvas: Canvas, scene: ArcadeScene) {
        buttons.clear()
        val labels = if (game.over) listOf(ArcadeButton("RESTART", ArcadeAction.ACTION))
        else scene.buttons
        if (labels.isEmpty()) return
        val gap = dp(8f)
        val pad = dp(14f)
        val top = height - dp(54f)
        val heightPx = dp(42f)
        val widthPx = (width - pad * 2 - gap * (labels.size - 1)) / labels.size
        labels.forEachIndexed { index, button ->
            val left = pad + index * (widthPx + gap)
            val rect = RectF(left, top, left + widthPx, top + heightPx)
            buttons.add(rect to button.action)

            val pressed = index == buttonDown
            paint.style = Paint.Style.FILL
            paint.color = if (pressed) colors.accent else alpha(colors.accent, 0x22)
            canvas.drawRoundRect(rect, dp(9f), dp(9f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.color = alpha(colors.accent, 0xCC)
            canvas.drawRoundRect(rect, dp(9f), dp(9f), paint)
            textPaint.color = if (pressed && luminance(colors.accent) > 150) {
                0xFF101018.toInt()
            } else {
                colors.accent
            }
            textPaint.textSize = dp(12f)
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(button.label, rect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                swipeFired = false
                buttonDown = -1
                buttons.forEachIndexed { index, (rect, _) ->
                    if (rect.contains(x, y)) buttonDown = index
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (buttonDown < 0 && game.id == "brick" && board.width() > 0f) {
                    game.input(ArcadeInput(dragX = ((x - board.left) / board.width()).coerceIn(0f, 1f)))
                }
                if (buttonDown < 0 && !swipeFired) {
                    val dx = x - downX
                    val dy = y - downY
                    val slop = dp(24f)
                    if (abs(dx) > slop || abs(dy) > slop) {
                        swipeFired = true
                        val action = if (abs(dx) > abs(dy)) {
                            if (dx > 0) ArcadeAction.RIGHT else ArcadeAction.LEFT
                        } else {
                            if (dy > 0) ArcadeAction.DOWN else ArcadeAction.UP
                        }
                        game.input(ArcadeInput(swipe = action))
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // The button row is rebuilt every frame: a run that ends while a finger is on the
                // d-pad replaces four controls with RESTART, and indexing the old list threw an
                // IndexOutOfBounds straight out of the touch dispatcher.
                val pressed = buttons.getOrNull(buttonDown)
                if (pressed != null) {
                    val (rect, action) = pressed
                    val inside = rect.contains(x, y)
                    buttonDown = -1
                    if (inside) {
                        if (game.over) restart() else press(action)
                    }
                    invalidate()
                    return true
                }
                if (!swipeFired) {
                    if (game.over) {
                        restart()
                    } else if (board.contains(x, y) && board.width() > 0f) {
                        game.input(ArcadeInput(
                            tapX = (x - board.left) / board.width(),
                            tapY = (y - board.top) / board.height(),
                        ))
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                buttonDown = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun press(action: ArcadeAction) {
        if (game is ChutesGame && meshPeer != null) {
            val chutes = game as ChutesGame
            if (chutes.turn != localSeat) return
            if (chutes.roll() > 0) {
                if (chutes.winner >= 0) MinigameStore.recordArcade("chutes", 1)
                MinigameMesh.sendMove(meshPeer, chutes.stateLine())
            }
            return
        }
        game.input(ArcadeInput(action = action))
    }

    private fun restart() {
        recorded = false
        outcomeAt = 0L
        outcomeWon = false
        game.reset(System.nanoTime())
        if (game is ChutesGame && meshPeer != null) {
            MinigameMesh.sendMove(meshPeer, (game as ChutesGame).stateLine())
        }
    }
}
