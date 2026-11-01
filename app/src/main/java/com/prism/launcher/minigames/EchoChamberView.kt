package com.prism.launcher.minigames

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismSettings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Echo Chamber on Android.
 *
 * ## What is on screen is what has been heard
 *
 * There is no map draw here. Walls come from the ray caster; the minimap comes from the cells the
 * sound rays actually crossed; enemies and the exit are only drawn while their `visibleUntil` is in
 * the future. A player who stands still in the dark sees black, which is the point of the game.
 *
 * ## The microphone is the primary control
 *
 * A shout in the room becomes a pulse. The virtual button exists only when the microphone is
 * unavailable (no permission, no capture device), and says so. That keeps the rule intact while
 * leaving the game playable on an emulator.
 *
 * ## Software 2.5D, not a 3D engine
 *
 * Walls are vertical strips sorted by the ray that found them. The hunter is a generated 1024²
 * bitmap body with live tentacle ribbons around it; the peer and exit still use the generated meshes
 * in [EchoAssets], projected and painted as polygons. That is enough for a dark maze and it costs no
 * GL context, which matters because this page lives inside the launcher.
 */
@SuppressLint("ViewConstructor")
class EchoChamberView(
    context: Context,
    private val match: MinigameMesh.Match?,
    private val options: Echo.StartOptions,
    private val onFinished: (won: Boolean, score: Int) -> Unit,
) : View(context), TopInsetAware, MinigameFeedback {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val session = Echo.Session(match, options)
    private val world get() = session.state

    @Volatile private var pack: EchoAssets.Pack? = null
    @Volatile private var monster: Bitmap? = null
    @Volatile private var scream: ShortArray? = null
    @Volatile private var running = true
    private var visible = true
    private var lastFrame = 0L
    private var carry = 0.0

    private var topInset = 0
    private var reported = false
    private var resultPosted = false
    private var scarePlayed = false

    /**
     * The soundtrack follows the hunters, not a clock: a knife-edge run is fast and a quiet
     * corridor is slow, and the tempo only settles once the session is over.
     */
    override fun musicIntensity(): Float {
        if (world.status != Echo.Status.RUNNING) return 0f
        val nearest = world.enemies.filter { it.alive }
            .minOfOrNull { kotlin.math.hypot(it.x - world.playerX, it.y - world.playerY) }
            ?: 30.0
        val danger = (1.0 - nearest / 20.0).coerceIn(0.0, 1.0).toFloat()
        val hurt = 1f - (world.playerHealth / 100f).coerceIn(0f, 1f)
        return (0.18f + danger * 0.62f + hurt * 0.2f).coerceIn(0f, 1f)
    }

    override fun outcome(): Boolean? = when (world.status) {
        Echo.Status.WON -> true
        Echo.Status.LOST -> false
        Echo.Status.RUNNING -> null
    }

    // Input. The left stick is movement, the right stick is looking.
    private var movePointer = -1
    private var lookPointer = -1
    private var movePointerId = -1
    private var lookPointerId = -1
    private var moveX = 0f
    private var moveY = 0f
    private var turnX = 0f
    private var strikePressed = false
    private var shoutPressed = false

    // Microphone.
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var micUnavailable = false
    private var micRequested = false
    private var lastMicPing = 0L
    @Volatile private var micLevel = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tentaclePath = Path()
    private val centerlinePath = Path()
    private val mouthPath = Path()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f * scaled
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(0xFF030108.toInt())
        // Deliberately hardware accelerated. A software layer made every shadowed mark, face and
        // HUD bar a CPU bitmap pass, which is what dragged the whole game down to a crawl once a
        // shout lit up a corridor. Glow is drawn as wider translucent strokes instead.
        if (EchoAssets.imageLoader == null) {
            EchoAssets.imageLoader = EchoAssets.EchoImageLoader { file ->
                runCatching {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@runCatching null
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    bitmap.recycle()
                    pixels
                }.getOrNull()
            }
        }
        loadAssets()
        startMicrophone()
        // A live maze is a session, not a menu: the launcher and the Minigames page both read this
        // and stay put until the run reports a result or this view is torn down.
        EchoSessionLock.begin()
    }

    private fun loadAssets() {
        Thread({
            val loaded = runCatching { EchoAssets.pack }.getOrElse {
                com.prism.core.PrismPlatform.log.error("PrismEcho", "Could not build Echo Chamber art", it)
                null
            }
            // The body is one bitmap, reused for every hunter and every frame. Building it here
            // keeps a 4 MB allocation and the procedural paint out of the first jumpscare.
            val face = loaded?.let { art ->
                runCatching {
                    Bitmap.createBitmap(
                        art.enemySprite, EchoMonster.SPRITE_SIZE, EchoMonster.SPRITE_SIZE,
                        Bitmap.Config.ARGB_8888,
                    )
                }.getOrNull()
            }
            val voice = runCatching { EchoScreech.samples() }.getOrNull()
            post {
                pack = loaded
                monster = face
                scream = voice
                invalidate()
            }
        }, "echo-assets").apply { isDaemon = true }.start()
    }

    override fun applyTopInset(pixels: Int) {
        topInset = pixels
        invalidate()
    }

    // -- Lifecycle ------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrame = 0L
        startMicrophone()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        stopMicrophone()
        session.close()
        EchoSessionLock.end()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visible = visibility == VISIBLE
        if (visible) {
            lastFrame = 0L
            postInvalidateOnAnimation()
        }
    }

    // -- Simulation -----------------------------------------------------------

    private fun advance() {
        val now = System.nanoTime()
        if (lastFrame == 0L) {
            lastFrame = now
            return
        }
        val elapsed = ((now - lastFrame) / 1_000_000_000.0).coerceAtMost(0.25)
        lastFrame = now
        carry += elapsed
        var steps = 0
        while (carry >= Echo.STEP_SECONDS && steps < 8) {
            val shout = shoutPressed || (micLevel > PrismSettings.getEchoChamberMicSensitivity() &&
                System.currentTimeMillis() - lastMicPing > 700)
            if (micLevel > PrismSettings.getEchoChamberMicSensitivity()) {
                lastMicPing = System.currentTimeMillis()
            }
            val input = Echo.Input(
                forward = -moveY.toDouble(),
                strafe = moveX.toDouble(),
                turn = turnX.toDouble(),
                shout = shout,
                strike = strikePressed,
            )
            shoutPressed = false
            strikePressed = false
            val before = world.lastPing
            session.update(Echo.STEP_SECONDS, input)
            val after = world.lastPing
            if (after != null && after !== before) playPing(after.maxRadius / Echo.MAX_PING)
            // A guest's kill happens in its local copy of the world; the host owns the real one, so
            // every strike is reported and the session decides whether there was a target in range.
            if (input.strike) session.reportStrike()
            carry -= Echo.STEP_SECONDS
            steps++
        }
        if (world.dying && !scarePlayed) {
            scarePlayed = true
            playScare()
        }
        if (world.status != Echo.Status.RUNNING && !reported) {
            reported = true
            MinigameStore.recordEcho(world.status == Echo.Status.WON, world.score)
            if (!resultPosted) {
                resultPosted = true
                // Long enough to read the result after the jumpscare, not just flash it.
                postDelayed({ onFinished(world.status == Echo.Status.WON, world.score) }, 2400)
            }
        }
    }

    // -- Touch ----------------------------------------------------------------

    private fun moveCenter(): Pair<Float, Float> = width * 0.18f to height - dp(112f)
    private fun lookCenter(): Pair<Float, Float> = width * 0.82f to height - dp(112f)
    private fun shoutCenter(): Pair<Float, Float> = width / 2f to height - dp(104f)
    private fun strikeCenter(): Pair<Float, Float> = width - dp(58f) to height - dp(218f)
    private fun stickRadius() = min(width, height) * 0.12f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (resultPosted || world.dying) return true
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val shout = shoutCenter()
                val strike = strikeCenter()
                when {
                    hypot(x - shout.first, y - shout.second) < dp(34f) -> {
                        // Always a shout, not only when the microphone is missing: a quiet room
                        // should not make the one control that reveals the maze do nothing.
                        shoutPressed = true
                        playPing(0.8)
                        return true
                    }
                    hypot(x - strike.first, y - strike.second) < dp(34f) -> {
                        strikePressed = true
                        return true
                    }
                    x < width / 2f -> {
                        movePointer = event.getPointerId(event.actionIndex)
                        activeMove(event)
                        return true
                    }
                    else -> {
                        lookPointer = event.getPointerId(event.actionIndex)
                        activeLook(event)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val px = event.getX(index)
                if (px < width / 2f && movePointer < 0) {
                    movePointer = id
                    activeMove(event, index)
                } else if (px >= width / 2f && lookPointer < 0) {
                    lookPointer = id
                    activeLook(event, index)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (id == movePointer) activeMove(event, index)
                    else if (id == lookPointer) activeLook(event, index)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == movePointer) { movePointer = -1; moveX = 0f; moveY = 0f }
                if (id == lookPointer) { lookPointer = -1; turnX = 0f }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                movePointer = -1
                lookPointer = -1
                moveX = 0f
                moveY = 0f
                turnX = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun activeMove(event: MotionEvent, index: Int = 0) {
        val (cx, cy) = moveCenter()
        val radius = stickRadius()
        var dx = event.getX(index) - cx
        var dy = event.getY(index) - cy
        val length = hypot(dx, dy)
        if (length > radius && length > 0f) {
            dx = dx / length * radius
            dy = dy / length * radius
        }
        moveX = (dx / radius).coerceIn(-1f, 1f)
        moveY = (dy / radius).coerceIn(-1f, 1f)
    }

    private fun activeLook(event: MotionEvent, index: Int = 0) {
        val (cx, _) = lookCenter()
        val radius = stickRadius()
        turnX = ((event.getX(index) - cx) / radius).coerceIn(-1f, 1f)
    }

    // -- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (running && visible) advance()
        val art = pack
        if (art == null) {
            canvas.drawColor(0xFF030108.toInt())
            drawLoading(canvas)
            if (running) postInvalidateOnAnimation()
            return
        }
        drawScene(canvas, art)
        drawHud(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawLoading(canvas: Canvas) {
        text.color = 0xFF39E7FF.toInt()
        text.textSize = 14f * scaled
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("ECHO CHAMBER", width / 2f, height * 0.44f, text)
        text.color = 0xFF7F7F9A.toInt()
        text.textSize = 11f * scaled
        canvas.drawText("calibrating 1024² surfaces…", width / 2f, height * 0.44f + dp(22f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawScene(canvas: Canvas, art: EchoAssets.Pack) {
        val w = width
        val h = height
        val horizon = h * 0.47f
        // PITCH BLACK, BY CONSTRUCTION. There is no wall or floor pass: geometry only exists on
        // screen where a wavefront has already touched it. Drawing the maze and then dimming it
        // was the old bug -- a phone in a dark room still showed every corridor.
        canvas.drawColor(0xFF020106.toInt())

        drawRipples(canvas, horizon, w, h)
        drawMarks(canvas, art, horizon, w, h)
        drawEntities(canvas, art, horizon, w, h)
        drawDamage(canvas, w, h)
        if (world.dying) drawJumpscare(canvas, w, h)
    }

    /**
     * The hunter arriving, all at once.
     *
     * The body is a billboard of wet flesh with too many mouths and eyes; the tentacles are live
     * geometry, so they reach for the camera instead of posing in a texture. The giant maw in the
     * centre opens as the scream peaks. A few torn scanlines and a red strobe keep it from looking
     * like a clean cutscene in a game that has spent the whole round in the dark.
     */
    private fun drawJumpscare(canvas: Canvas, w: Int, h: Int) {
        val progress = (1.0 - world.jumpscare / Echo.JUMPSCARE_SECONDS).coerceIn(0.0, 1.0)
        val flicker = (progress * 90).toInt()
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = if (flicker % 2 == 0) 0xF2390012.toInt() else 0xF2080006.toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val enemy = world.enemies.firstOrNull { it.id == world.jumpscareEnemy }
            ?: world.enemies.firstOrNull { it.alive }
        val seed = enemy?.id ?: 0
        val shake = (1f - progress.toFloat() * 0.35f) * dp(7f)
        val cx = w / 2f + sin(progress * 31.0).toFloat() * shake
        val cy = h * 0.50f + cos(progress * 37.0).toFloat() * shake * 0.65f
        // The body is 29.5% of the sprite's radius, so a side of 1.02..1.95 screen-min puts a
        // 0.60..1.15 screen-min body in front of the camera: close at first, consuming at last.
        val side = min(w, h) * (1.02f + progress.toFloat() * 0.93f)
        val body = EchoMonster.Billboard(
            cx, cy,
            side * EchoMonster.BODY_FRACTION,
            1.0,
        )
        val saved = canvas.save()
        canvas.rotate((sin(progress * 9.0) * 2.7).toFloat(), cx, cy)
        drawHunterArt(canvas, body, gait = progress * 26.0, clock = world.clock, seed = seed, count = 13)
        if (progress > 0.08) {
            val mawRadius = side * (0.12f + progress.toFloat() * 0.10f)
            drawGapingMaw(
                canvas, cx, cy + side * 0.035f, mawRadius,
                mawRadius * (0.72f + progress.toFloat() * 0.66f), progress,
            )
        }
        canvas.restoreToCount(saved)

        // A few torn scanlines, because a clean monster is less frightening than a broken one.
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0x66000000
        var y = (progress * 7).toInt()
        val step = dp(19f).toInt().coerceAtLeast(1)
        val thickness = dp(3f).coerceAtLeast(1f)
        while (y < h) {
            canvas.drawRect(0f, y.toFloat(), w.toFloat(), y + thickness, paint)
            y += step
        }

        if (progress > 0.86) {
            val fade = ((progress - 0.86) / 0.14).toFloat().coerceIn(0f, 1f)
            paint.color = ((fade * 255).toInt() shl 24)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    /** One hunter in the maze, or the full-screen version at death. */
    private fun drawHunterArt(
        canvas: Canvas,
        body: EchoMonster.Billboard,
        gait: Double,
        clock: Double,
        seed: Int,
        count: Int = EchoMonster.TENTACLES,
        alpha: Int = 255,
    ) {
        val bitmap = monster ?: return
        // The body is carried by the limbs, so the whole creature bobs with the gait instead of
        // sliding along a fixed horizon line.
        val bob = sin(gait * 1.35 + seed * 0.71).toFloat() * body.radius * 0.014f +
            sin(clock * 2.4 + seed).toFloat() * body.radius * 0.006f
        val shown = body.copy(centerY = body.centerY + bob)
        drawTentacles(canvas, shown, gait, clock, seed, count, alpha)

        val side = EchoMonster.spriteSide(shown.radius)
        val half = side / 2f
        val destination = RectF(
            shown.centerX - half, shown.centerY - half,
            shown.centerX + half, shown.centerY + half,
        )
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = alpha
        paint.isFilterBitmap = true
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawBitmap(bitmap, null, destination, paint)
        paint.alpha = 255
        paint.isFilterBitmap = false
    }

    /** Ribbons of muscle with suckers down one side, drawn behind the body. */
    private fun drawTentacles(
        canvas: Canvas,
        body: EchoMonster.Billboard,
        gait: Double,
        clock: Double,
        seed: Int,
        count: Int,
        alpha: Int,
    ) {
        val tendons = EchoMonster.tentacles(body, gait, clock, seed, count)
        val edge = max(dp(1f), body.radius * 0.014f)
        tendons.forEach { tentacle ->
            tentaclePath.reset()
            tentaclePath.moveTo(tentacle.leftX[0], tentacle.leftY[0])
            for (i in 1 until tentacle.leftX.size) {
                tentaclePath.lineTo(tentacle.leftX[i], tentacle.leftY[i])
            }
            for (i in tentacle.rightX.indices.reversed()) {
                tentaclePath.lineTo(tentacle.rightX[i], tentacle.rightY[i])
            }
            tentaclePath.close()

            paint.style = Paint.Style.FILL
            paint.alpha = alpha
            paint.color = if (tentacle.accent) 0xFF52632A.toInt() else 0xFF4A2468.toInt()
            canvas.drawPath(tentaclePath, paint)

            // The dark outline separates two crossing limbs. Without it the tentacles merge into
            // one silhouette and the gait stops reading.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = edge
            paint.color = if (tentacle.accent) 0xFF2E3414.toInt() else 0xFF24102E.toInt()
            canvas.drawPath(tentaclePath, paint)

            // A wet highlight along the middle of the limb.
            centerlinePath.reset()
            centerlinePath.moveTo(tentacle.centerX[0], tentacle.centerY[0])
            for (i in 1 until tentacle.centerX.size) {
                centerlinePath.lineTo(tentacle.centerX[i], tentacle.centerY[i])
            }
            paint.strokeWidth = max(dp(1f), tentacle.width[tentacle.width.size / 2] * 0.20f)
            paint.color = if (tentacle.accent) 0xFFB3C66A.toInt() else 0xFFB77BD8.toInt()
            paint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
            canvas.drawPath(centerlinePath, paint)

            // Suckers. A tentacle without them is just a noodle.
            paint.style = Paint.Style.FILL
            for (i in 2..6 step 2) {
                val cx = tentacle.centerX[i]
                val cy = tentacle.centerY[i]
                val radius = max(dp(0.8f), tentacle.width[i] * 0.24f)
                paint.alpha = alpha
                paint.color = if (tentacle.accent) 0xFFD7E28E.toInt() else 0xFFE0A2EB.toInt()
                canvas.drawCircle(cx, cy, radius, paint)
                paint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
                paint.color = 0xFF2A1238.toInt()
                canvas.drawCircle(cx, cy, radius * 0.43f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.strokeWidth = 1f
    }

    /** A round, needle-toothed maw drawn over the body's central mouth. */
    private fun drawGapingMaw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        verticalRadius: Float,
        progress: Double,
    ) {
        val saved = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, (verticalRadius / radius).coerceAtLeast(0.4f))

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0xFF0B0209.toInt()
        canvas.drawCircle(0f, 0f, radius, paint)
        // The back of the throat, a little warmer than the lip.
        paint.color = 0xFF240718.toInt()
        canvas.drawCircle(0f, radius * 0.06f, radius * 0.74f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(dp(2f), radius * 0.045f)
        paint.color = 0xFF7E3D75.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.94f, paint)

        val teeth = 28
        mouthPath.reset()
        for (i in 0 until teeth) {
            val angle = Math.PI * 2 * i / teeth
            val half = Math.PI / teeth * 0.52
            val base = radius * 0.94f
            val tip = radius * (0.53f + 0.06f * sin(progress * 8.0 + i * 1.7))
            mouthPath.moveTo((cos(angle - half) * base).toFloat(), (sin(angle - half) * base).toFloat())
            mouthPath.lineTo((cos(angle) * tip).toFloat(), (sin(angle) * tip).toFloat())
            mouthPath.lineTo((cos(angle + half) * base).toFloat(), (sin(angle + half) * base).toFloat())
            mouthPath.close()
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE5D7B8.toInt()
        canvas.drawPath(mouthPath, paint)
        // A thin dark line under each tooth gives the ring depth at this scale.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(dp(0.8f), radius * 0.012f)
        paint.color = 0x88301224.toInt()
        canvas.drawPath(mouthPath, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(saved)
    }

    private fun drawFloorGrid(canvas: Canvas, art: EchoAssets.Pack, horizon: Float, w: Int, h: Int) {
        line.strokeWidth = 1f
        for (i in 1..16) {
            val depth = i * 0.9
            val y = horizon + (h * 0.42f) / depth.toFloat()
            if (y > h) continue
            val u = (i * 0.073) % 1.0
            val color = art.sample(art.floor, u, 0.5)
            val alpha = (110 - i * 6).coerceIn(16, 110)
            line.color = (alpha shl 24) or (color and 0x00FFFFFF)
            canvas.drawLine(0f, y, w.toFloat(), y, line)
        }
        for (a in -14..14) {
            val angle = world.playerAngle + a * Echo.FOV / 28.0
            val color = art.sample(art.floor, ((a + 14) / 28.0), 0.72)
            line.color = (42 shl 24) or (color and 0x00FFFFFF)
            val x = w / 2f + tan(a * Echo.FOV / 28.0).toFloat() * (w / 2f)
            canvas.drawLine(w / 2f, h.toFloat(), x, horizon, line)
        }
    }

    private fun drawWalls(canvas: Canvas, art: EchoAssets.Pack, horizon: Float, w: Int, h: Int) {
        val step = 2
        val focal = (w / 2.0) / tan(Echo.FOV / 2.0)
        var x = 0
        while (x < w) {
            val relative = (x.toDouble() / w - 0.5) * Echo.FOV
            val angle = world.playerAngle + relative
            val hit = world.maze.cast(world.playerX, world.playerY, angle, 28.0)
            if (hit == null) {
                paint.color = 0xFF030108.toInt()
                canvas.drawRect(x.toFloat(), 0f, (x + step).toFloat(), h.toFloat(), paint)
                x += step
                continue
            }
            val corrected = (hit.dist * cos(relative)).coerceAtLeast(0.08)
            val wallHeight = (h * 0.92 / corrected).toFloat()
            val top = horizon - wallHeight * 0.47f
            val bottom = horizon + wallHeight * 0.53f
            val u = hit.u
            // Four vertical samples per strip: the texture's own bands, fogged by distance.
            val bands = 4
            for (band in 0 until bands) {
                val v = (band + 0.5) / bands
                val color = art.sample(art.wall, u, v)
                paint.color = EchoAssets.fogged(color, corrected, 22.0)
                val topBand = top + wallHeight * band / bands
                val bottomBand = top + wallHeight * (band + 1) / bands
                canvas.drawRect(x.toFloat(), topBand, (x + step).toFloat(), bottomBand, paint)
            }
            // A rim light on the top and bottom of every wall panel gives the corridor edges
            // without a second geometry pass.
            paint.color = (34 shl 24) or 0x0039E7FF
            canvas.drawRect(x.toFloat(), top, (x + step).toFloat(), top + dp(1.2f), paint)
            paint.color = (30 shl 24) or 0x00FF3BD6
            canvas.drawRect(x.toFloat(), bottom - dp(1.2f), (x + step).toFloat(), bottom, paint)
            x += step
        }
    }

    private fun drawRipples(canvas: Canvas, horizon: Float, w: Int, h: Int) {
        world.ripples.forEach { ripple ->
            val life = ripple.maxRadius / ripple.speed
            val t = (ripple.age / life).coerceIn(0.0, 1.0)
            if (t >= 1.0) return@forEach
            val radius = (w * 0.46f * t + dp(10f)).toFloat()
            val alpha = ((1 - t) * 100).toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = (alpha shl 24) or (EchoAssets.hsl(ripple.hue.toDouble(), 0.9, 0.6) and 0x00FFFFFF)
            canvas.drawCircle(w / 2f, horizon, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawMarks(canvas: Canvas, art: EchoAssets.Pack, horizon: Float, w: Int, h: Int) {
        // Only the buckets around the player are visited, and only a bounded number of marks are
        // drawn, so a corridor painted by a hundred shouts costs the same as one with ten. The
        // marks never fade: they are the permanent map of everything the sound has touched.
        var drawn = 0
        world.forEachNearbyMark(world.playerX, world.playerY, 8, 2400) { mark ->
            if (drawn >= 150) return@forEachNearbyMark false
            val dx = mark.x - world.playerX
            val dy = mark.y - world.playerY
            val distance = hypot(dx, dy)
            if (distance < 0.2 || distance > 25.0) return@forEachNearbyMark true
            var relative = atan2(dy, dx) - world.playerAngle
            while (relative > Math.PI) relative -= Math.PI * 2
            while (relative < -Math.PI) relative += Math.PI * 2
            if (abs(relative) > Echo.FOV / 2 + 0.12) return@forEachNearbyMark true
            // A mark behind the wall in front of it is not visible.
            val wall = world.maze.cast(world.playerX, world.playerY, atan2(dy, dx), 28.0)
            if (wall != null && wall.dist < distance - 0.12) return@forEachNearbyMark true
            val sx = (w / 2.0 + tan(relative) / tan(Echo.FOV / 2) * (w / 2)).toFloat()
            val sy = horizon - (h * 0.10f / distance.toFloat())
            val radius = (h / distance * 0.075).toFloat().coerceIn(dp(2f), dp(22f))
            val color = EchoAssets.hsl(mark.hue.toDouble(), 0.95, 0.62)
            val alpha = 225
            // The mark is a piece of the wavefront, not a dot. Its arc is centred on the shout
            // that produced it, so the curvature on the wall reads as sound that travelled here.
            val towardSource = atan2(mark.originY - mark.y, mark.originX - mark.x)
            val rotation = Math.toDegrees(towardSource).toFloat()
            val swept = radius
            // Cheap halo: one wider, dimmer stroke instead of a per-mark shadow layer pass.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (radius * 1.15f).coerceAtLeast(dp(3.4f))
            paint.color = (70 shl 24) or (color and 0x00FFFFFF)
            canvas.drawArc(
                RectF(sx - swept, sy - swept, sx + swept, sy + swept),
                rotation + 55f, 250f, false, paint,
            )
            paint.strokeWidth = (radius * 0.55f).coerceAtLeast(dp(1.6f))
            paint.color = (alpha shl 24) or (color and 0x00FFFFFF)
            // The bloom is the patch of wall the wave actually touched. Without it the arcs read
            // as floating symbols instead of as lit surfaces in a black room.
            paint.style = Paint.Style.FILL
            canvas.drawCircle(sx, sy, radius * 1.15f, paint)
            paint.style = Paint.Style.STROKE
            // 250 degrees of an arc, opening toward the source: a crescent of the wave itself.
            canvas.drawArc(
                RectF(sx - swept, sy - swept, sx + swept, sy + swept),
                rotation + 55f, 250f, false, paint,
            )
            // A cooler echo a little further out, in the neighbouring hue, so the mark reads as
            // a rainbow ripple instead of a painted sticker.
            val echoColor = EchoAssets.hsl((mark.hue + 42) % 360.0, 0.95, 0.68)
            paint.strokeWidth = (radius * 0.32f).coerceAtLeast(dp(1f))
            paint.color = (alpha / 2 shl 24) or (echoColor and 0x00FFFFFF)
            val echoR = swept * 1.9f
            canvas.drawArc(
                RectF(sx - echoR, sy - echoR, sx + echoR, sy + echoR),
                rotation - 18f, 130f, false, paint,
            )
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
            drawn++
            true
        }
    }

    private fun drawEntities(canvas: Canvas, art: EchoAssets.Pack, horizon: Float, w: Int, h: Int) {
        val entities = ArrayList<Pair<Double, () -> Unit>>()
        world.enemies.forEach { enemy ->
            if (!enemy.alive || world.clock > enemy.visibleUntil) return@forEach
            val dx = enemy.x - world.playerX
            val dy = enemy.y - world.playerY
            val distance = hypot(dx, dy)
            if (distance > 20.0) return@forEach
            // A hunter that has walked behind the corner it was seen around is gone again.
            val wall = world.maze.cast(world.playerX, world.playerY, atan2(dy, dx), 28.0)
            if (wall != null && wall.dist < distance - 0.16) return@forEach
            entities.add(distance to {
                val body = EchoMonster.billboard(
                    enemy.x, enemy.y, world.playerX, world.playerY, world.playerAngle,
                    Echo.FOV, w, h, scale = 0.92,
                )
                val fog = (255.0 * (1.0 - distance / 26.0)).toInt().coerceIn(105, 255)
                if (body != null && monster != null) {
                    drawHunterArt(canvas, body, enemy.gait, world.clock, enemy.id, alpha = fog)
                } else {
                    drawFaces(
                        canvas,
                        EchoAssets.project(
                            art.enemyMesh, enemy.x, enemy.y, 0.18,
                            world.playerX, world.playerY, world.playerAngle,
                            Echo.FOV, w, h, scale = 0.86,
                        ),
                        ambient = 0xFF39E7FF.toInt(),
                    )
                }
            })
        }
        world.peer?.let { peer ->
            if (peer.alive && world.clock <= peer.visibleUntil) {
                val distance = hypot(peer.x - world.playerX, peer.y - world.playerY)
                entities.add(distance to {
                    val faces = EchoAssets.project(
                        art.enemyMesh, peer.x, peer.y, 0.16,
                        world.playerX, world.playerY, world.playerAngle,
                        Echo.FOV, w, h, scale = 0.92,
                    )
                    drawFaces(canvas, faces, ambient = 0xFF9DFF3C.toInt())
                })
            }
        }
        if (world.clock <= world.exitVisibleUntil) {
            val distance = hypot(world.maze.exitX - world.playerX, world.maze.exitY - world.playerY)
            entities.add(distance to {
                val faces = EchoAssets.project(
                    art.exitMesh, world.maze.exitX, world.maze.exitY, 0.62,
                    world.playerX, world.playerY, world.playerAngle,
                    Echo.FOV, w, h, scale = 1.0,
                )
                drawFaces(canvas, faces, ambient = 0xFFFF2BD6.toInt())
            })
        }
        entities.sortByDescending { it.first }
        entities.forEach { it.second.invoke() }
    }

    private fun drawFaces(canvas: Canvas, faces: List<EchoAssets.ProjectedFace>, ambient: Int) {
        val path = Path()
        faces.forEach { face ->
            path.reset()
            path.moveTo(face.x[0], face.y[0])
            path.lineTo(face.x[1], face.y[1])
            path.lineTo(face.x[2], face.y[2])
            path.close()
            val solid = if (face.emissive) face.color else blend(face.color, ambient, 0.35f)
            // Emissive faces get a soft outline in the same hue; that reads as glow without the
            // software bitmap a shadow layer would force on every frame.
            if (face.emissive) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(6f)
                paint.color = (60 shl 24) or (face.color and 0x00FFFFFF)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = solid
            canvas.drawPath(path, paint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val w = width
        val h = height
        val top = topInset + dp(10f)

        // Health: a bar, because a number in the dark is unreadable.
        val barLeft = dp(16f)
        val barTop = top + dp(4f)
        val barWidth = w * 0.34f
        paint.color = 0x77000000
        canvas.drawRoundRect(RectF(barLeft, barTop, barLeft + barWidth, barTop + dp(7f)), dp(4f), dp(4f), paint)
        val health = world.playerHealth.coerceIn(0, 100) / 100f
        paint.color = when {
            health < 0.3 -> 0xFFFF3B5C.toInt()
            health < 0.6 -> 0xFFFFB000.toInt()
            else -> 0xFF39E7FF.toInt()
        }
        val fill = RectF(barLeft, barTop, barLeft + barWidth * health, barTop + dp(7f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(5f)
        paint.alpha = 70
        canvas.drawRoundRect(fill, dp(4f), dp(4f), paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(fill, dp(4f), dp(4f), paint)

        text.textAlign = Paint.Align.LEFT
        text.textSize = 10f * scaled
        text.color = 0xFFB8B8D8.toInt()
        canvas.drawText("HULL ${world.playerHealth.coerceIn(0, 100)}", barLeft, barTop + dp(20f), text)
        text.color = 0xFF39E7FF.toInt()
        canvas.drawText("SCORE ${world.score}", barLeft, barTop + dp(34f), text)
        text.color = 0xFFFF8FE3.toInt()
        canvas.drawText("HUNTERS ${world.enemies.count { it.alive }}", barLeft + barWidth + dp(14f), barTop + dp(20f), text)
        text.color = 0xFF9DFF3C.toInt()
        canvas.drawText("PING ${if (world.shoutCooldown <= 0.0) "READY" else "…"}", barLeft + barWidth + dp(14f), barTop + dp(34f), text)

        val hint = when {
            world.status == Echo.Status.WON -> "EXIT REACHED"
            world.status == Echo.Status.LOST -> "SIGNAL LOST"
            world.clock <= world.exitVisibleUntil -> "EXIT ECHO — MOVE TOWARD THE MAGENTA"
            world.shoutCooldown > 0.0 -> "LISTEN"
            else -> "SHOUT TO SEE"
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f * scaled
        text.color = if (world.status == Echo.Status.RUNNING) 0xFF8A8AB0.toInt() else 0xFFFFB000.toInt()
        canvas.drawText(hint, w / 2f, top + dp(50f), text)

        drawMinimap(canvas, w)
        drawSticks(canvas)
        if (micUnavailable) {
            val center = shoutCenter()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = 0x88FFB000.toInt()
            canvas.drawCircle(center.first, center.second, dp(28f), paint)
            paint.style = Paint.Style.FILL
            text.textAlign = Paint.Align.CENTER
            text.textSize = 8f * scaled
            text.color = 0xFFFFB000.toInt()
            canvas.drawText("TAP", center.first, center.second + dp(3f), text)
        } else {
            val center = shoutCenter()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = 0x8839E7FF.toInt()
            canvas.drawCircle(center.first, center.second, dp(28f) + micLevel * dp(28f), paint)
            paint.style = Paint.Style.FILL
            text.textAlign = Paint.Align.CENTER
            text.textSize = 8f * scaled
            text.color = 0xFF39E7FF.toInt()
            canvas.drawText("SHOUT", center.first, center.second + dp(3f), text)
        }

        val strike = strikeCenter()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = if (world.strikeCooldown <= 0.0) 0xCCFF3BD6.toInt() else 0x44FF3BD6
        canvas.drawCircle(strike.first, strike.second, dp(28f), paint)
        paint.style = Paint.Style.FILL
        text.textAlign = Paint.Align.CENTER
        text.textSize = 8f * scaled
        text.color = if (world.strikeCooldown <= 0.0) 0xFFFF8FE3.toInt() else 0x66FF8FE3
        canvas.drawText("STRIKE", strike.first, strike.second + dp(3f), text)

        if (world.status != Echo.Status.RUNNING) {
            paint.color = 0xAA020107.toInt()
            canvas.drawRect(0f, h * 0.36f, w.toFloat(), h * 0.64f, paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 24f * scaled
            text.color = if (world.status == Echo.Status.WON) 0xFF9DFF3C.toInt() else 0xFFFF3B5C.toInt()
            canvas.drawText(if (world.status == Echo.Status.WON) "YOU FOUND THE EXIT" else "THE DARK TOOK YOU", w / 2f, h * 0.47f, text)
            text.textSize = 11f * scaled
            text.color = 0xFFB8B8D8.toInt()
            canvas.drawText(
                "score ${world.score} · ${world.kills} killed · ${world.tags} tagged",
                w / 2f, h * 0.47f + dp(24f), text,
            )
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawMinimap(canvas: Canvas, w: Int) {
        val size = dp(104f)
        val left = w - size - dp(14f)
        val top = topInset + dp(10f)
        paint.color = 0x99000000.toInt()
        canvas.drawRoundRect(RectF(left, top, left + size, top + size), dp(8f), dp(8f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x5539E7FF
        canvas.drawRoundRect(RectF(left, top, left + size, top + size), dp(8f), dp(8f), paint)
        paint.style = Paint.Style.FILL
        val cell = size / world.maze.size
        for (y in 0 until world.maze.size) {
            for (x in 0 until world.maze.size) {
                if (!world.isRevealed(x, y)) continue
                paint.color = if (world.maze.wall(x, y)) 0xAA1B6F8A.toInt() else 0x55206080.toInt()
                canvas.drawRect(
                    left + x * cell, top + y * cell,
                    left + (x + 1) * cell, top + (y + 1) * cell, paint,
                )
            }
        }
        if (world.clock <= world.exitVisibleUntil) {
            paint.color = 0xFFFF2BD6.toInt()
            canvas.drawCircle(
                left + (world.maze.exitX).toFloat() * cell,
                top + (world.maze.exitY).toFloat() * cell,
                cell * 1.4f, paint,
            )
        }
        world.enemies.forEach { enemy ->
            if (!enemy.alive || world.clock > enemy.visibleUntil) return@forEach
            paint.color = 0xFFFF3B5C.toInt()
            canvas.drawCircle(left + (enemy.x).toFloat() * cell, top + (enemy.y).toFloat() * cell, cell * 1.3f, paint)
        }
        world.peer?.let { peer ->
            if (world.clock <= peer.visibleUntil) {
                paint.color = 0xFF9DFF3C.toInt()
                canvas.drawCircle(left + peer.x.toFloat() * cell, top + peer.y.toFloat() * cell, cell * 1.3f, paint)
            }
        }
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(
            left + world.playerX.toFloat() * cell, top + world.playerY.toFloat() * cell,
            cell * 1.3f, paint,
        )
        // A short cone rather than a dart: orientation matters more than a perfect triangle.
        line.strokeWidth = dp(1f)
        line.color = 0xCC39E7FF.toInt()
        val cx = left + world.playerX.toFloat() * cell
        val cy = top + world.playerY.toFloat() * cell
        val spread = Echo.FOV / 2
        for (a in listOf(-spread, 0.0, spread)) {
            canvas.drawLine(
                cx, cy,
                cx + cos(world.playerAngle + a).toFloat() * cell * 3.2f,
                cy + sin(world.playerAngle + a).toFloat() * cell * 3.2f,
                line,
            )
        }
    }

    private fun drawSticks(canvas: Canvas) {
        val radius = stickRadius()
        val move = moveCenter()
        val look = lookCenter()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = 0x5539E7FF
        canvas.drawCircle(move.first, move.second, radius, paint)
        canvas.drawCircle(look.first, look.second, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x3339E7FF
        canvas.drawCircle(move.first + moveX * radius, move.second + moveY * radius, radius * 0.36f, paint)
        paint.color = 0x33FF3BD6
        canvas.drawCircle(look.first + turnX * radius, look.second, radius * 0.36f, paint)
        canvas.drawCircle(move.first, move.second, dp(3f), paint)
        canvas.drawCircle(look.first, look.second, dp(3f), paint)
    }

    private fun drawDamage(canvas: Canvas, w: Int, h: Int) {
        if (world.damageFlash <= 0.0) return
        val alpha = (world.damageFlash * 150).toInt().coerceIn(0, 150)
        paint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.72f,
            intArrayOf(0x00000000, (alpha shl 24) or 0x00FF1744),
            floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun blend(base: Int, other: Int, amount: Float): Int {
        val r = ((base ushr 16 and 0xFF) * (1 - amount) + (other ushr 16 and 0xFF) * amount).toInt()
        val g = ((base ushr 8 and 0xFF) * (1 - amount) + (other ushr 8 and 0xFF) * amount).toInt()
        val b = ((base and 0xFF) * (1 - amount) + (other and 0xFF) * amount).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // -- Microphone and sound -------------------------------------------------

    private fun startMicrophone() {
        if (micRunning || micUnavailable) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (!micRequested) {
                micRequested = true
                runCatching { (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0xEC40) }
            }
            micUnavailable = true
            return
        }
        micRunning = true
        micThread = Thread({
            var record: AudioRecord? = null
            try {
                val rate = 16000
                val minimum = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(2048)
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 2,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    micUnavailable = true
                    return@Thread
                }
                mic = record
                record.startRecording()
                val buffer = ShortArray(minimum / 2)
                while (micRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) {
                        val value = buffer[i] / 32768.0
                        sum += value * value
                    }
                    micLevel = sqrt(sum / read).toFloat()
                }
            } catch (failure: Throwable) {
                com.prism.core.PrismPlatform.log.warn("PrismEcho", "Microphone unavailable: ${failure.message}")
                micUnavailable = true
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                mic = null
            }
        }, "echo-mic").apply { isDaemon = true }
        micThread?.start()
    }

    private fun stopMicrophone() {
        micRunning = false
        runCatching { mic?.stop() }
        micThread?.interrupt()
        micThread = null
    }

    /**
     * The jumpscare. A layered scream is pre-generated with the art so the first frame of the
     * scare is the monster, not a main-thread synthesizer hitching the frame.
     */
    private fun playScare() {
        runCatching {
            val rate = EchoScreech.SAMPLE_RATE
            val samples = scream ?: EchoScreech.samples(rate)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            runCatching { track.setVolume(1f) }
            track.play()
            postDelayed({ runCatching { track.release() } }, 3_000L)
        }.onFailure { com.prism.core.PrismPlatform.log.warn("PrismEcho", "Could not play the scare: ${it.message}") }
    }

    private fun playPing(loudness: Double) {
        runCatching {
            val rate = 22050
            val length = (rate * 0.42).toInt()
            val samples = ShortArray(length)
            var seed = 0x5EED1234
            for (i in 0 until length) {
                val t = i.toDouble() / rate
                val envelope = exp(-t * 7.5) * (1 - t / 0.42).coerceAtLeast(0.0)
                seed = seed * 1664525 + 1013904223
                val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
                val freq = 880.0 - t * 1450.0
                val tone = sin(2 * Math.PI * freq * t)
                val echo = sin(2 * Math.PI * (freq * 0.5) * (t - 0.10).coerceAtLeast(0.0)) *
                    exp(-max(0.0, t - 0.10) * 6.0) * if (t > 0.10) 0.35 else 0.0
                val value = ((tone * 0.62 + noise * 0.10 + echo) * envelope * loudness.coerceIn(0.2, 1.0) * 11000)
                samples[i] = value.toInt().coerceIn(-32768, 32767).toShort()
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            postDelayed({ runCatching { track.release() } }, 520)
        }.onFailure { com.prism.core.PrismPlatform.log.warn("PrismEcho", "Could not play a ping: ${it.message}") }
    }

    @Suppress("unused")
    private fun unusedFormattingGuard() {
        // Keeps the imports that document the audio format honest if this file is edited later.
        val unused = AudioFormat.ENCODING_PCM_16BIT
    }
}
