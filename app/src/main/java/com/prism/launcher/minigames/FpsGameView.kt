package com.prism.launcher.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.PrismSpeaker
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The phone's first-person surface.
 *
 * DOOM and Zombies differ in rules, not in presentation: both need a ray-cast corridor, billboarded
 * enemies, a gun in the corner, a touch stick, and a HUD that can say "downed" without a dialog.
 * That shared surface lives here. The subclasses supply a [FpsScene] and advance their own world;
 * this class owns the ticker, the renderer, the sticks, the effects and the session lock.
 *
 * ## Canvas rather than GL
 *
 * The launcher already has a page renderer and no GL context it can borrow for a cabinet. A
 * software ray caster at 240 columns is the right size for a phone screen and keeps DOOM from
 * becoming a second graphics stack. The walls are flat-shaded with a panel band, which is enough to
 * read distance and corners; the enemies are silhouettes, which is enough to read a threat.
 */
abstract class FpsGameView(context: Context) : View(context), TopInsetAware, MinigameFeedback {

    protected val colors = MinigameTheme.of(context)
    private val density = resources.displayMetrics.scaledDensity
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val audio = FpsAudio(context)

    private var running = false
    private var finished = false
    private var lastFrame = 0L
    private var lastStatus = 0
    private var lastScene: FpsScene? = null
    private var topInset = 0
    private var showHelp = true
    private var helpStartedAt = 0L
    private var lastSpeechAt = 0L
    private var speechReady: Boolean? = null
    private val speechVoices = KokoroVoices.ALL.filter {
        it.language == KokoroVoices.Language.AMERICAN_ENGLISH
    }

    private var movePointer = -1
    private var lookPointer = -1
    private var firePointer = -1
    private var moveOriginX = 0f
    private var moveOriginY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var turnPending = 0f
    private var lookPending = 0f
    private var fireHeld = false
    private var reloadRequested = false
    private var useRequested = false
    private var switchRequested = -1

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            try {
                tick(frameTimeNanos)
            } catch (t: Throwable) {
                com.prism.launcher.PrismLogger.logError("Minigames", "$sessionLabel frame failed", t)
                endSession(false, lastScene?.score ?: 0)
                return
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    protected abstract val sessionLabel: String
    protected abstract fun currentScene(): FpsScene
    protected abstract fun advance(dt: Float, input: FpsInput)
    protected abstract fun drainEvents(): List<FpsEvent>
    protected abstract fun drainSpeech(): List<FpsSpeech>
    protected abstract fun onGameFinished(won: Boolean, score: Int)

    protected open fun onStatusChanged(status: Int, score: Int) {}
    protected open fun onResultTapped() {}
    protected open fun musicIntensity(scene: FpsScene): Float = 0.3f
    protected open fun helpText(): String =
        "LEFT STICK  move\nRIGHT STICK look\nFIRE        shoot\nRELOAD      magazine\nSWAP        next weapon"
    protected open fun resultTitle(status: Int): String =
        if (status == 1) "MISSION COMPLETE" else "YOU DIED"

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setBackgroundColor(colors.background)
    }

    override fun applyTopInset(pixels: Int) {
        topInset = pixels
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSession()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        EchoSessionLock.end()
        audio.release()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow && !finished) {
            running = true
            lastFrame = 0L
            Choreographer.getInstance().removeFrameCallback(frame)
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }
    }

    protected fun startSession() {
        if (finished) return
        EchoSessionLock.begin(sessionLabel)
        running = true
        lastFrame = 0L
        helpStartedAt = SystemClock.elapsedRealtime()
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    protected fun endSession(won: Boolean, score: Int) {
        if (finished) return
        finished = true
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        EchoSessionLock.end()
        audio.release()
        onGameFinished(won, score)
    }

    /** A floor transition is not the end of the session; it must be able to unlock the ticker. */
    protected fun continueSession() {
        if (finished) return
        lastStatus = 0
        lastScene = null
        running = true
        lastFrame = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun musicIntensity(): Float {
        val scene = lastScene ?: return 0.3f
        return if (scene.status == 0) musicIntensity(scene) else 0f
    }

    override fun outcome(): Boolean? = when (lastScene?.status) {
        1 -> true
        2 -> false
        else -> null
    }

    private fun tick(now: Long) {
        if (!running) return
        if (lastFrame == 0L) lastFrame = now
        val dt = ((now - lastFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        lastFrame = now
        val input = buildInput()
        advance(dt, input)
        val scene = currentScene()
        lastScene = scene
        for (event in drainEvents()) audio.play(event, scene.cameraX, scene.cameraY)
        for (speech in drainSpeech()) speak(speech)
        if (scene.status != lastStatus) {
            lastStatus = scene.status
            if (scene.status != 0) showHelp = false
            onStatusChanged(scene.status, scene.score)
        }
        if (showHelp && SystemClock.elapsedRealtime() - helpStartedAt > 7000L) showHelp = false
        invalidate()
    }

    private fun buildInput(): FpsInput {
        val scene = lastScene
        val count = scene?.weaponCount ?: 1
        var swap = switchRequested
        if (swap >= count) swap = 0
        switchRequested = -1
        val input = FpsInput(
            forward = moveY,
            strafe = moveX,
            turn = turnPending,
            look = lookPending,
            fire = fireHeld,
            reload = reloadRequested,
            use = useRequested,
            switchWeapon = swap,
        )
        turnPending = 0f
        lookPending = 0f
        reloadRequested = false
        useRequested = false
        return input
    }

    private fun speak(speech: FpsSpeech) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpeechAt < 2_200L) return
        // The same "speech model is active" check the rest of Prism uses, so an imported voice
        // counts and an audio-generation model does not.
        val ready = speechReady ?: runCatching {
            PrismSettings.hasSpeechModel()
        }.getOrDefault(false).also { speechReady = it }
        if (!ready) return
        lastSpeechAt = now
        val voice = speechVoices[abs(speech.voiceKey) % speechVoices.size].id
        runCatching {
            PrismSpeaker.speakAs(
                text = speech.text,
                voiceId = voice,
                speed = 1.02f,
            )
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (lastScene?.status != 0) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onResultTapped()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    onMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                onUp(event.getPointerId(index))
            }
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        showHelp = false
        val w = width.toFloat()
        val h = height.toFloat()
        val fireX = w * 0.84f
        val fireY = h * 0.62f
        val fireRadius = w * 0.085f
        val reloadX = w * 0.70f
        val reloadY = h * 0.68f
        val useX = w * 0.55f
        val useY = h * 0.68f
        val swapX = w * 0.77f
        val swapY = h * 0.54f
        when {
            hypot(x - fireX, y - fireY) < fireRadius -> {
                firePointer = id
                fireHeld = true
            }
            hypot(x - reloadX, y - reloadY) < w * 0.052f -> reloadRequested = true
            hypot(x - useX, y - useY) < w * 0.052f -> useRequested = true
            hypot(x - swapX, y - swapY) < w * 0.05f -> switchRequested = ((lastScene?.weaponIndex ?: 0) + 1)
            x < w * 0.5f -> {
                movePointer = id
                moveOriginX = x
                moveOriginY = y
                moveX = 0f
                moveY = 0f
            }
            else -> {
                lookPointer = id
                lookLastX = x
                lookLastY = y
            }
        }
    }

    private var lookLastX = 0f
    private var lookLastY = 0f

    private fun onMove(id: Int, x: Float, y: Float) {
        if (id == movePointer) {
            val radius = min(width, height) * 0.16f
            val dx = x - moveOriginX
            val dy = y - moveOriginY
            val length = hypot(dx, dy)
            val scale = if (length > radius) radius / length else 1f
            moveX = (dx * scale / radius).coerceIn(-1f, 1f)
            moveY = (-dy * scale / radius).coerceIn(-1f, 1f)
        } else if (id == lookPointer) {
            val dx = x - lookLastX
            val dy = y - lookLastY
            lookLastX = x
            lookLastY = y
            turnPending = (turnPending + dx * 0.006f).coerceIn(-0.16f, 0.16f)
            lookPending = (lookPending - dy * 0.0024f).coerceIn(-0.09f, 0.09f)
        }
    }

    private fun onUp(id: Int) {
        if (id == movePointer) {
            movePointer = -1
            moveX = 0f
            moveY = 0f
        }
        if (id == lookPointer) lookPointer = -1
        if (id == firePointer) {
            firePointer = -1
            fireHeld = false
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = lastScene
        if (scene == null) {
            canvas.drawColor(colors.background)
            drawText(canvas, "LOADING…", width / 2f, height / 2f, 14f, colors.accent, Paint.Align.CENTER)
            return
        }
        drawWorld(canvas, scene)
        drawHud(canvas, scene)
        if (showHelp) drawHelp(canvas)
        if (scene.status != 0) drawResult(canvas, scene)
    }

    private fun drawWorld(canvas: Canvas, scene: FpsScene) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.44f + (scene.pitch.toFloat() * h * 0.55f)
        canvas.drawColor(colors.background)

        paint.style = Paint.Style.FILL
        paint.color = MinigameColors.blend(colors.background, colors.board, 0.44f)
        canvas.drawRect(0f, 0f, w, horizon, paint)
        paint.color = MinigameColors.blend(colors.background, colors.board, 0.70f)
        canvas.drawRect(0f, horizon, w, h, paint)

        // A few floor bands give the flat fill a sense of distance without a texture fetch per
        // pixel. They are spaced quadratically, which is what makes them rush when moving.
        paint.color = MinigameColors.blend(colors.board, colors.accent, 0.10f)
        paint.strokeWidth = max(1f, density * 0.6f)
        for (i in 1..14) {
            val t = i / 14f
            val y = horizon + (h - horizon) * t * t
            canvas.drawLine(0f, y, w, y, paint)
        }

        val fov = (Math.PI / 3.0).toFloat()
        val column = max(2f, w / 260f)
        var x = 0f
        val wallBase = MinigameColors.blend(colors.board, colors.accent, 0.13f)
        while (x < w) {
            val angle = scene.cameraAngle + ((x / w) - 0.5) * fov
            val hit = Fps.cast(scene.map, scene.cameraX, scene.cameraY, angle, 34.0)
            val corrected = max(0.12, hit.distance * cos(angle - scene.cameraAngle))
            val wallHeight = (h * 0.92 / corrected).coerceAtMost(h * 2.2).toFloat()
            val top = horizon - wallHeight * 0.5f
            val bottom = horizon + wallHeight * 0.5f
            val light = (1.0 / (1.0 + corrected * 0.17)).coerceIn(0.08, 1.0)
            val side = if (hit.side == 0) 1f else 0.82f
            val band = if (((hit.u * 8).toInt() + hit.cellX + hit.cellY) % 2 == 0) 1f else 0.92f
            val base = if (hit.tile == Fps.EXIT) {
                MinigameColors.blend(colors.accentAlt, colors.accent, 0.35f)
            } else {
                wallBase
            }
            paint.color = MinigameColors.blend(
                colors.background,
                base,
                (light * side * band).toFloat().coerceIn(0f, 1f),
            )
            canvas.drawRect(x, top, x + column + 0.6f, bottom, paint)
            if (hit.tile == Fps.EXIT) {
                paint.color = (0x66 shl 24) or (colors.accentAlt and 0x00FFFFFF)
                canvas.drawRect(x, horizon - wallHeight * 0.28f, x + column, horizon + wallHeight * 0.28f, paint)
            }
            x += column
        }

        drawSprites(canvas, scene, horizon)
        drawGun(canvas, scene)

        if (scene.hurt > 0.01f) {
            paint.color = ((scene.hurt * 0.34f * 255).toInt().coerceIn(0, 255) shl 24) or
                (colors.danger and 0x00FFFFFF)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        if (scene.muzzle > 0.01f) {
            paint.color = ((scene.muzzle * 0.16f * 255).toInt().coerceIn(0, 255) shl 24) or 0x00FFE2A0
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun drawSprites(canvas: Canvas, scene: FpsScene, horizon: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cosA = cos(scene.cameraAngle)
        val sinA = sin(scene.cameraAngle)
        data class Projected(val sprite: FpsSprite, val depth: Double, val screenX: Float)

        val projected = ArrayList<Projected>()
        for (sprite in scene.sprites) {
            val dx = sprite.x - scene.cameraX
            val dy = sprite.y - scene.cameraY
            val depth = dx * cosA + dy * sinA
            if (depth < 0.25) continue
            if (!Fps.visible(scene.map, scene.cameraX, scene.cameraY, sprite.x, sprite.y)) continue
            val side = -dx * sinA + dy * cosA
            val screenX = (w * 0.5 + side / depth * (w * 0.5) / tan(Math.PI / 6.0)).toFloat()
            if (screenX < -w * 0.4 || screenX > w * 1.4) continue
            projected.add(Projected(sprite, depth, screenX))
        }
        projected.sortByDescending { it.depth }

        for (item in projected) {
            val depth = item.depth.coerceAtLeast(0.35)
            val size = (h * item.sprite.size / depth).toFloat().coerceIn(8f, h * 1.8f)
            val bottom = horizon + (h * 0.44 / depth).toFloat().coerceAtMost(h * 0.95f)
            val cx = item.screenX
            when (item.sprite.kind) {
                FpsSprites.ZOMBIE -> drawZombie(canvas, cx, bottom, size, item.sprite, brute = false, spitter = false)
                FpsSprites.BRUTE -> drawZombie(canvas, cx, bottom, size, item.sprite, brute = true, spitter = false)
                FpsSprites.SPITTER -> drawZombie(canvas, cx, bottom, size, item.sprite, brute = false, spitter = true)
                FpsSprites.IMP -> drawImp(canvas, cx, bottom, size)
                FpsSprites.GRUNT -> drawGrunt(canvas, cx, bottom, size)
                FpsSprites.TEAMMATE -> drawTeammate(canvas, cx, bottom, size, item.sprite)
                FpsSprites.HEALTH -> drawPickup(canvas, cx, bottom, size, "＋", colors.success)
                FpsSprites.AMMO -> drawPickup(canvas, cx, bottom, size, "AMMO", colors.accent)
                FpsSprites.ARMOR -> drawPickup(canvas, cx, bottom, size, "ARM", colors.accentAlt)
                FpsSprites.WEAPON -> drawWeaponPickup(canvas, cx, bottom, size, item.sprite.label ?: "GUN")
                FpsSprites.EXIT -> drawExit(canvas, cx, bottom, size)
                FpsSprites.PROJECTILE -> drawProjectile(canvas, cx, bottom, size)
                else -> drawPickup(canvas, cx, bottom, size, "?", colors.ink)
            }
            if (item.sprite.health in 0f..0.999f && item.sprite.kind !in listOf(
                    FpsSprites.HEALTH, FpsSprites.AMMO, FpsSprites.ARMOR,
                    FpsSprites.WEAPON, FpsSprites.EXIT, FpsSprites.PROJECTILE,
                )
            ) {
                val barW = size * 0.62f
                val barY = bottom - size - max(6f, size * 0.08f)
                paint.color = 0xAA000000.toInt()
                canvas.drawRect(cx - barW / 2, barY, cx + barW / 2, barY + max(3f, size * 0.035f), paint)
                paint.color = if (item.sprite.health > 0.35f) colors.success else colors.danger
                canvas.drawRect(
                    cx - barW / 2, barY,
                    cx - barW / 2 + barW * item.sprite.health, barY + max(3f, size * 0.035f), paint,
                )
            }
        }
    }

    private val zombieArt = HashMap<String, Bitmap?>()

    /**
     * High-resolution zombie renders shipped in `assets/zombie/sprites`.
     *
     * The software renderer has no 3D pipeline, so the Blender-built, rigged
     * zombies are baked to 768x1536 transparent sprites. Brutes and spitters are
     * re-tinted from the same art instead of falling back to the old rectangles.
     */
    private fun zombieBitmap(spitter: Boolean): Bitmap? {
        val file = if (spitter) "zombie_female_stand.png" else "zombie_male_stand.png"
        return zombieArt.getOrPut(file) {
            runCatching {
                context.assets.open("zombie/sprites/$file").use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }

    private fun drawZombie(
        canvas: Canvas, cx: Float, bottom: Float, size: Float,
        sprite: FpsSprite, brute: Boolean, spitter: Boolean,
    ) {
        val alpha = (sprite.alpha * 255).toInt().coerceIn(0, 255)
        val bitmap = zombieBitmap(spitter)
        if (bitmap != null) {
            val targetHeight = size * if (brute) 1.16f else 1.02f
            val targetWidth = targetHeight * bitmap.width / bitmap.height
            paint.alpha = alpha
            paint.colorFilter = when {
                brute -> PorterDuffColorFilter(0xFF8B6A52.toInt(), PorterDuff.Mode.MULTIPLY)
                spitter -> PorterDuffColorFilter(0xFF6FBF6A.toInt(), PorterDuff.Mode.MULTIPLY)
                else -> null
            }
            canvas.drawBitmap(
                bitmap, null,
                RectF(cx - targetWidth / 2f, bottom - targetHeight,
                    cx + targetWidth / 2f, bottom),
                paint,
            )
            paint.alpha = 255
            paint.colorFilter = null
            return
        }
        val skin = when {
            spitter -> 0xFF6FBF6A.toInt()
            brute -> 0xFF6B5A4E.toInt()
            else -> 0xFF849C6D.toInt()
        }
        val body = (alpha shl 24) or (skin and 0x00FFFFFF)
        val dark = (alpha shl 24) or 0x003B2F28
        val width = size * if (brute) 0.58f else 0.38f
        val head = size * if (brute) 0.26f else 0.22f
        val sway = sin((sprite.x + sprite.y) * 2.0 + size * 0.012).toFloat() * size * 0.05f
        paint.style = Paint.Style.FILL
        paint.color = dark
        canvas.drawRect(cx - width * 0.42f, bottom - size * 0.48f, cx - width * 0.12f, bottom, paint)
        canvas.drawRect(cx + width * 0.12f, bottom - size * 0.48f, cx + width * 0.42f, bottom, paint)
        paint.color = body
        canvas.drawRoundRect(
            RectF(cx - width / 2 + sway, bottom - size * 0.88f, cx + width / 2 + sway, bottom - size * 0.40f),
            size * 0.08f, size * 0.08f, paint,
        )
        canvas.drawCircle(cx + sway, bottom - size * 0.94f, head, paint)
        paint.color = dark
        canvas.drawRect(cx - width * 0.62f + sway, bottom - size * 0.78f, cx - width * 0.36f, bottom - size * 0.30f, paint)
        canvas.drawRect(cx + width * 0.36f + sway, bottom - size * 0.78f, cx + width * 0.62f, bottom - size * 0.30f, paint)
        // Red eyes and a mouth; at sprite scale this is what reads as "it noticed you".
        paint.color = if (spitter) 0xFFFFD34D.toInt() else 0xFFFF3B4E.toInt()
        val eye = max(1.4f, size * 0.026f)
        canvas.drawCircle(cx + sway - head * 0.34f, bottom - size * 0.96f, eye, paint)
        canvas.drawCircle(cx + sway + head * 0.34f, bottom - size * 0.96f, eye, paint)
        paint.color = (alpha shl 24) or 0x00120A0C
        canvas.drawRect(cx + sway - head * 0.28f, bottom - size * 0.87f, cx + sway + head * 0.28f, bottom - size * 0.84f, paint)
        if (spitter) {
            paint.color = (alpha shl 24) or 0x005FBF3E
            canvas.drawCircle(cx + sway, bottom - size * 0.82f, size * 0.05f, paint)
        }
    }

    private fun drawImp(canvas: Canvas, cx: Float, bottom: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF8F3E2E.toInt()
        val width = size * 0.42f
        canvas.drawRoundRect(
            RectF(cx - width / 2, bottom - size * 0.84f, cx + width / 2, bottom - size * 0.42f),
            size * 0.08f, size * 0.08f, paint,
        )
        canvas.drawCircle(cx, bottom - size * 0.90f, size * 0.19f, paint)
        paint.color = 0xFF2B1710.toInt()
        path.reset()
        path.moveTo(cx - size * 0.18f, bottom - size * 1.04f)
        path.lineTo(cx - size * 0.10f, bottom - size * 1.18f)
        path.lineTo(cx - size * 0.01f, bottom - size * 1.04f)
        path.moveTo(cx + size * 0.18f, bottom - size * 1.04f)
        path.lineTo(cx + size * 0.10f, bottom - size * 1.18f)
        path.lineTo(cx + size * 0.01f, bottom - size * 1.04f)
        canvas.drawPath(path, paint)
        paint.color = 0xFFFFB347.toInt()
        canvas.drawCircle(cx - size * 0.07f, bottom - size * 0.92f, max(1.5f, size * 0.027f), paint)
        canvas.drawCircle(cx + size * 0.07f, bottom - size * 0.92f, max(1.5f, size * 0.027f), paint)
        paint.color = 0xCCFF7A18.toInt()
        canvas.drawCircle(cx + width * 0.72f, bottom - size * 0.58f, size * 0.10f, paint)
    }

    private fun drawGrunt(canvas: Canvas, cx: Float, bottom: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF5D5A4E.toInt()
        val width = size * 0.38f
        canvas.drawRect(cx - width / 2, bottom - size * 0.80f, cx + width / 2, bottom - size * 0.42f, paint)
        canvas.drawCircle(cx, bottom - size * 0.88f, size * 0.17f, paint)
        paint.color = 0xFF27251F.toInt()
        canvas.drawRect(cx - width * 0.60f, bottom - size * 0.72f, cx - width * 0.36f, bottom - size * 0.30f, paint)
        canvas.drawRect(cx + width * 0.36f, bottom - size * 0.72f, cx + width * 0.60f, bottom - size * 0.30f, paint)
        paint.color = 0xFFC7A85A.toInt()
        canvas.drawRect(cx - size * 0.20f, bottom - size * 1.02f, cx + size * 0.20f, bottom - size * 0.98f, paint)
    }

    private fun drawTeammate(canvas: Canvas, cx: Float, bottom: Float, size: Float, sprite: FpsSprite) {
        val alpha = (sprite.alpha * 255).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.color = (alpha shl 24) or 0x003E7A66
        val width = size * 0.34f
        canvas.drawRect(cx - width / 2, bottom - size * 0.78f, cx + width / 2, bottom - size * 0.38f, paint)
        canvas.drawCircle(cx, bottom - size * 0.87f, size * 0.16f, paint)
        paint.color = (alpha shl 24) or 0x007CFFCB
        canvas.drawRect(cx - width * 0.54f, bottom - size * 0.74f, cx - width * 0.36f, bottom - size * 0.28f, paint)
        canvas.drawRect(cx + width * 0.36f, bottom - size * 0.74f, cx + width * 0.54f, bottom - size * 0.28f, paint)
        val label = sprite.label
        if (!label.isNullOrBlank()) {
            drawText(canvas, label, cx, bottom - size * 1.08f, 9f, colors.accent, Paint.Align.CENTER)
        }
    }

    private fun drawPickup(canvas: Canvas, cx: Float, bottom: Float, size: Float, label: String, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = 0xCC000000.toInt()
        canvas.drawCircle(cx, bottom - size * 0.35f, size * 0.26f, paint)
        paint.color = color
        canvas.drawRoundRect(
            RectF(cx - size * 0.18f, bottom - size * 0.55f, cx + size * 0.18f, bottom - size * 0.15f),
            size * 0.04f, size * 0.04f, paint,
        )
        drawText(canvas, label, cx, bottom - size * 0.29f, 8f, colors.background, Paint.Align.CENTER)
    }

    private fun drawWeaponPickup(canvas: Canvas, cx: Float, bottom: Float, size: Float, label: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0xDD05050B.toInt()
        canvas.drawRoundRect(
            RectF(cx - size * 0.42f, bottom - size * 0.55f, cx + size * 0.42f, bottom - size * 0.22f),
            size * 0.05f, size * 0.05f, paint,
        )
        paint.color = colors.accent
        canvas.drawRect(cx - size * 0.34f, bottom - size * 0.42f, cx + size * 0.20f, bottom - size * 0.35f, paint)
        canvas.drawRect(cx + size * 0.18f, bottom - size * 0.46f, cx + size * 0.30f, bottom - size * 0.31f, paint)
        paint.color = colors.muted
        canvas.drawRect(cx - size * 0.11f, bottom - size * 0.35f, cx + size * 0.02f, bottom - size * 0.24f, paint)
        drawText(canvas, label, cx, bottom - size * 0.62f, 8f, colors.accent, Paint.Align.CENTER)
    }

    private fun drawExit(canvas: Canvas, cx: Float, bottom: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = (0x44 shl 24) or (colors.accentAlt and 0x00FFFFFF)
        canvas.drawCircle(cx, bottom - size * 0.55f, size * 0.36f, paint)
        paint.color = (0xAA shl 24) or (colors.accentAlt and 0x00FFFFFF)
        canvas.drawCircle(cx, bottom - size * 0.55f, size * 0.23f, paint)
        paint.color = colors.background
        canvas.drawCircle(cx, bottom - size * 0.55f, size * 0.12f, paint)
        drawText(canvas, "EXIT", cx, bottom - size * 0.96f, 9f, colors.accentAlt, Paint.Align.CENTER)
    }

    private fun drawProjectile(canvas: Canvas, cx: Float, bottom: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xAAFF7A18.toInt()
        canvas.drawCircle(cx, bottom - size * 0.5f, size * 0.28f, paint)
        paint.color = 0xFFFFE28A.toInt()
        canvas.drawCircle(cx, bottom - size * 0.5f, size * 0.11f, paint)
    }

    private fun drawGun(canvas: Canvas, scene: FpsScene) {
        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = h - h * 0.12f
        val cx = w * 0.62f
        val scale = min(w, h) * 0.16f
        paint.style = Paint.Style.FILL
        paint.color = 0xFF17171E.toInt()
        path.reset()
        path.moveTo(cx - scale * 1.45f, baseY + scale * 0.50f)
        path.lineTo(cx - scale * 1.05f, baseY - scale * 0.15f)
        path.lineTo(cx + scale * 1.10f, baseY - scale * 0.22f)
        path.lineTo(cx + scale * 1.44f, baseY + scale * 0.44f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = 0xFF2A2A34.toInt()
        canvas.drawRect(cx - scale * 0.34f, baseY - scale * 0.58f, cx + scale * 0.82f, baseY - scale * 0.30f, paint)
        paint.color = colors.accent
        canvas.drawRect(cx + scale * 0.20f, baseY - scale * 0.55f, cx + scale * 0.90f, baseY - scale * 0.50f, paint)
        paint.color = 0xFF0B0B10.toInt()
        canvas.drawRect(cx - scale * 0.55f, baseY + scale * 0.05f, cx + scale * 0.02f, baseY + scale * 0.55f, paint)
        if (scene.muzzle > 0.02f) {
            paint.color = ((scene.muzzle * 220).toInt().coerceIn(0, 255) shl 24) or 0x00FFE29A
            canvas.drawCircle(cx + scale * 1.20f, baseY - scale * 0.38f, scale * (0.25f + scene.muzzle * 0.20f), paint)
        }
        drawText(canvas, scene.weaponName, cx, baseY + scale * 0.9f, 8f, colors.muted, Paint.Align.CENTER)
    }

    private fun drawHud(canvas: Canvas, scene: FpsScene) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xB805050B.toInt()
        canvas.drawRect(0f, topInset.toFloat(), w, topInset + 38f * density, paint)
        canvas.drawRect(0f, h - 54f * density, w, h, paint)

        drawText(canvas, scene.waveLabel, 12f * density, topInset + 17f * density, 11f, colors.accent, Paint.Align.LEFT)
        drawText(canvas, scene.objective ?: "", w / 2f, topInset + 17f * density, 8.5f, colors.muted, Paint.Align.CENTER)
        drawText(canvas, "${scene.points} PTS", w - 12f * density, topInset + 17f * density, 10f, colors.accentAlt, Paint.Align.RIGHT)

        val healthColor = when {
            scene.health <= 25f -> colors.danger
            scene.health <= 50f -> 0xFFFFC14D.toInt()
            else -> colors.success
        }
        drawText(canvas, "HP ${scene.health.toInt()}", 12f * density, h - 33f * density, 14f, healthColor, Paint.Align.LEFT)
        if (scene.armor > 0f) {
            drawText(canvas, "ARM ${scene.armor.toInt()}", 96f * density, h - 33f * density, 9f, colors.accent, Paint.Align.LEFT)
        }
        if (scene.downed) {
            drawText(
                canvas,
                "DOWN — ${scene.bleedout.toInt()}s · a teammate can revive you",
                w / 2f, h - 35f * density, 9f, colors.danger, Paint.Align.CENTER,
            )
        } else if (scene.health <= 0f) {
            drawText(
                canvas,
                "SPECTATING — your team is still fighting",
                w / 2f, h - 35f * density, 9f, colors.muted, Paint.Align.CENTER,
            )
        } else {
            drawText(
                canvas,
                "${scene.ammoMag} / ${scene.ammoReserve}",
                w / 2f, h - 29f * density, 20f, colors.ink, Paint.Align.CENTER,
            )
            drawText(canvas, scene.weaponName, w / 2f, h - 14f * density, 8f, colors.muted, Paint.Align.CENTER)
        }
        drawText(canvas, "${scene.kills} KILLS", w - 12f * density, h - 33f * density, 9f, colors.muted, Paint.Align.RIGHT)
        if (scene.coOp) {
            drawText(canvas, "MESH CO-OP · ${scene.teammates + 1}/4", w - 12f * density, h - 20f * density, 7.5f, colors.accent, Paint.Align.RIGHT)
        }

        var subtitleY = h * 0.66f
        for (line in scene.subtitles.takeLast(2)) {
            drawText(canvas, line, 12f * density, subtitleY, 9f, colors.ink, Paint.Align.LEFT)
            subtitleY += 12f * density
        }
        val message = scene.message
        if (message != null) {
            paint.color = 0xCC000000.toInt()
            val messageWidth = paint.measureText(message) + 24f * density
            canvas.drawRoundRect(
                RectF(w / 2 - messageWidth / 2, h * 0.28f - 18f * density, w / 2 + messageWidth / 2, h * 0.28f + 8f * density),
                8f * density, 8f * density, paint,
            )
            drawText(canvas, message, w / 2f, h * 0.28f, 10f, colors.accent, Paint.Align.CENTER)
        }

        drawCrosshair(canvas, scene)
        if (showHelp) return
        drawSticks(canvas, scene)
    }

    private fun drawCrosshair(canvas: Canvas, scene: FpsScene) {
        if (!scene.crosshair || scene.status != 0) return
        val cx = width / 2f
        val cy = height * 0.44f + (scene.pitch.toFloat() * height * 0.55f)
        val gap = 4f * density
        val length = 8f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.5f, density * 0.7f)
        paint.color = (0xCC shl 24) or (colors.ink and 0x00FFFFFF)
        canvas.drawLine(cx - gap - length, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + gap + length, cy, paint)
        canvas.drawLine(cx, cy - gap - length, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + length, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawSticks(canvas: Canvas, scene: FpsScene) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(w, h) * 0.16f
        val leftX = w * 0.17f
        val leftY = h * 0.80f
        val rightX = w * 0.83f
        val rightY = h * 0.80f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, density)
        paint.color = (0x55 shl 24) or (colors.ink and 0x00FFFFFF)
        canvas.drawCircle(leftX, leftY, radius, paint)
        canvas.drawCircle(rightX, rightY, radius * 0.82f, paint)
        paint.style = Paint.Style.FILL
        paint.color = (0x99 shl 24) or (colors.accent and 0x00FFFFFF)
        canvas.drawCircle(leftX + moveX * radius, leftY - moveY * radius, radius * 0.34f, paint)
        paint.color = (0x88 shl 24) or (colors.ink and 0x00FFFFFF)
        canvas.drawCircle(rightX, rightY, radius * 0.22f, paint)

        val fireX = w * 0.84f
        val fireY = h * 0.62f
        paint.color = if (fireHeld) colors.accentAlt else (0xDD shl 24) or (colors.accent and 0x00FFFFFF)
        canvas.drawCircle(fireX, fireY, w * 0.085f, paint)
        drawText(canvas, "FIRE", fireX, fireY + 4f * density, 9f, colors.background, Paint.Align.CENTER)

        val reloadX = w * 0.70f
        val reloadY = h * 0.68f
        paint.color = 0xCC11111A.toInt()
        canvas.drawCircle(reloadX, reloadY, w * 0.052f, paint)
        drawText(canvas, "RELOAD", reloadX, reloadY + 3f * density, 6.5f, colors.ink, Paint.Align.CENTER)

        val useX = w * 0.55f
        val useY = h * 0.68f
        paint.color = 0xCC11111A.toInt()
        canvas.drawCircle(useX, useY, w * 0.052f, paint)
        drawText(canvas, "USE", useX, useY + 3f * density, 7f, colors.ink, Paint.Align.CENTER)

        val swapX = w * 0.77f
        val swapY = h * 0.54f
        paint.color = 0xCC11111A.toInt()
        canvas.drawCircle(swapX, swapY, w * 0.05f, paint)
        drawText(canvas, "SWAP", swapX, swapY + 3f * density, 6.5f, colors.ink, Paint.Align.CENTER)

        drawText(canvas, scene.objective ?: "", w / 2f, h * 0.74f, 8f, colors.muted, Paint.Align.CENTER)
    }

    private fun drawHelp(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xE605050B.toInt()
        val half = min(w, h) * 0.34f
        canvas.drawRoundRect(
            RectF(w / 2 - half, h * 0.30f, w / 2 + half, h * 0.62f),
            14f * density, 14f * density, paint,
        )
        drawText(canvas, sessionLabel.uppercase(), w / 2f, h * 0.36f, 18f, colors.accent, Paint.Align.CENTER)
        var y = h * 0.43f
        for (line in helpText().lines()) {
            drawText(canvas, line, w / 2f, y, 9f, colors.ink, Paint.Align.CENTER)
            y += 14f * density
        }
        drawText(canvas, "tap anywhere to begin", w / 2f, h * 0.58f, 8f, colors.muted, Paint.Align.CENTER)
    }

    private fun drawResult(canvas: Canvas, scene: FpsScene) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xE6050208.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val title = resultTitle(scene.status)
        drawText(canvas, title, w / 2f, h * 0.42f, 24f, if (scene.status == 1) colors.success else colors.danger, Paint.Align.CENTER)
        drawText(canvas, "${scene.waveLabel} · ${scene.score} points · ${scene.kills} kills", w / 2f, h * 0.49f, 10f, colors.ink, Paint.Align.CENTER)
        drawText(canvas, "TAP TO CONTINUE", w / 2f, h * 0.60f, 9f, colors.accent, Paint.Align.CENTER)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        if (text.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sizeSp * density
        paint.textAlign = align
        paint.typeface = android.graphics.Typeface.MONOSPACE
        canvas.drawText(text, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
