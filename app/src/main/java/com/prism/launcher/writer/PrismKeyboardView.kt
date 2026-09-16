package com.prism.launcher.writer

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.writer.Key
import com.prism.launcher.writer.KeyboardLayout
import com.prism.launcher.writer.SwipeDecoder

/**
 * The keyboard surface: keys, glide trail, press popups and long-press alternates.
 *
 * Drawn rather than built from child views. A key is a rounded rect with a label; forty of them as
 * Views would cost a layout pass on every theme change and make the glide trail — which crosses
 * them all — a compositing problem instead of a line on a canvas.
 */
class PrismKeyboardView(context: Context) : View(context) {

    var onKey: ((Key) -> Unit)? = null
    var onGesture: ((List<SwipeDecoder.Point>) -> Unit)? = null
    /** A long press resolved to one of a key's alternates. */
    var onAlternate: ((String) -> Unit)? = null

    /**
     * A key going down and coming up, for the ones that repeat while held.
     *
     * Separate from [onKey], which fires on RELEASE and means "the user typed this". A held
     * backspace has to act while the finger is still down, so it needs the press itself.
     */
    var onHoldStart: ((Key) -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    var layout: KeyboardLayout = KeyboardLayout.qwerty()
        set(value) {
            field = value
            invalidate()
        }

    // ── Touch state ────────────────────────────────────────────────────────

    /**
     * The key the finger went down on, fixed for the whole stroke.
     *
     * SEPARATE FROM [pressedKey], AND THAT DISTINCTION IS THE SWIPE FIX. Gesture activation used to
     * test `pressedKey?.isLetter`, but ACTION_MOVE reassigns pressedKey to whatever is under the
     * finger — including null in the gaps between keys. So activation only fired if the finger
     * happened to be exactly over a letter on the sample where the path first grew long enough,
     * and glides silently did nothing the rest of the time. What starts a glide is where the stroke
     * BEGAN, which never changes mid-stroke.
     */
    private var startKey: Key? = null

    /** Purely visual: which key is lit and popped up right now. */
    private var pressedKey: Key? = null

    private val path = mutableListOf<SwipeDecoder.Point>()
    private var gestureActive = false

    /** The key whose alternates are showing, and which one the finger is over. */
    private var alternatesFor: Key? = null
    private var alternateIndex = 0

    private val longPress = Handler(Looper.getMainLooper())
    private var longPressPending: Runnable? = null

    // ── Paints ─────────────────────────────────────────────────────────────

    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * ASKED FOR EXPLICITLY, NOT INHERITED. Paint defaults to the system typeface, and on a phone
     * with a custom system font -- Samsung ships a font picker -- every key renders in a handwriting
     * face, which is what made this look nothing like a keyboard. Requesting sans-serif is the
     * closest a keyboard can get without shipping its own font file.
     */
    private val keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // A FONT THE APP SHIPS, because asking for "sans-serif" was not enough. Samsung's font
        // picker replaces the system faces, and a custom face captures the generic families too --
        // so every key kept rendering in handwriting no matter which family was requested. A
        // bundled file is the only thing the system cannot substitute.
        isFakeBoldText = false
        style = Paint.Style.FILL
    }
    private val popupFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Icons are stroked paths, so they cannot be affected by whatever font the system is using. */
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPath = Path()

    private val trailPath = Path()
    private var background: Drawable? = null
    private var backgroundKey = ""

    /**
     * A blurred copy of the background, sampled per key.
     *
     * WHY A SEPARATE BITMAP. With a picture behind the keyboard, a solid key colour hides the very
     * thing the user chose — but drawing the picture sharp inside each key makes the labels
     * unreadable. Blurring once and sampling the region under each key gives keys that are made of
     * their own patch of the image, which is what frosted glass actually is.
     */
    private var blurred: android.graphics.Bitmap? = null
    private val keyClip = Path()
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dark = false

    /**
     * The keyboard's own face, loaded once.
     *
     * Falls back to the system default only if the resource is somehow unavailable — at which
     * point the handwriting problem returns, but a missing font should not be a crash.
     */
    private val writerTypeface: android.graphics.Typeface? = runCatching {
        androidx.core.content.res.ResourcesCompat.getFont(
            context, com.prism.launcher.R.font.prism_writer_font
        )
    }.getOrNull()

    init {
        writerTypeface?.let {
            keyText.typeface = it
            popupText.typeface = it
        }
    }

    // ── Geometry helpers ───────────────────────────────────────────────────

    /**
     * Space above the keys, reserved for press balloons and the alternates strip.
     *
     * A View CANNOT DRAW OUTSIDE ITS OWN BOUNDS, so a balloon above the top row was simply clipped
     * — which is why popups kept getting cut off. The fix is not a flag; it is geometry. The view is
     * laid out taller than the keys need, keys are mapped into the region below this inset, and the
     * band above it exists for things that overhang.
     */
    private val popupHeadroom: Float get() = height * 0.20f

    private fun fx(v: Float) = v * width

    /** Fractional key space maps into the area BELOW the headroom, never over it. */
    private fun fy(v: Float) = popupHeadroom + v * (height - popupHeadroom)

    // ── Theme ──────────────────────────────────────────────────────────────

    /**
     * Resolves the palette, letting explicit settings override the light/dark defaults.
     *
     * A stored 0 means "unset" rather than transparent black — see PrismSettings — so each override
     * is applied only when non-zero. That is what lets a user set just the accent and keep
     * everything else following the system.
     */
    fun applyTheme(isDark: Boolean) {
        dark = isDark

        val panel = PrismSettings.getWriterPanelColor()
            .takeIf { it != 0 } ?: if (isDark) DARK_PANEL else LIGHT_PANEL
        val key = PrismSettings.getWriterKeyColor()
            .takeIf { it != 0 } ?: if (isDark) DARK_KEY else LIGHT_KEY
        val text = PrismSettings.getWriterKeyTextColor()
            .takeIf { it != 0 } ?: if (isDark) Color.WHITE else Color.BLACK
        val accent = PrismSettings.getWriterAccentColor()
            .takeIf { it != 0 } ?: if (isDark) DARK_ACTION else LIGHT_ACTION

        panelFill.color = panel
        keyFill.color = key
        actionFill.color = accent
        pressedFill.color = blend(key, if (isDark) Color.WHITE else Color.BLACK, 0.18f)
        keyText.color = text
        popupFill.color = key
        popupText.color = text

        // A key's lift comes from a soft shadow, not an outline: an outline reads as a box, and the
        // whole point of this shape language is that keys look like they sit ON the panel.
        keyShadow.color = Color.argb(if (isDark) 90 else 45, 0, 0, 0)
        keyShadow.maskFilter = BlurMaskFilter(dp(3f), BlurMaskFilter.Blur.NORMAL)

        hairline.color = if (isDark) 0x22FFFFFF else 0x22000000

        loadBackground()
        invalidate()
    }

    /**
     * Loads the user's background picture, downsampled to the view.
     *
     * DOWNSAMPLED DELIBERATELY. A keyboard is a few hundred pixels tall and a photo is not; decoding
     * one at full size costs tens of megabytes inside an IME process, which the system kills far
     * more readily than an app. Re-read only when the URI changes, because onDraw runs constantly.
     */
    private fun loadBackground() {
        val uri = PrismSettings.getWriterBackgroundImage()
        if (uri == backgroundKey) return
        backgroundKey = uri

        if (uri.isBlank()) {
            background = null
            return
        }
        background = runCatching {
            val target = (height.takeIf { it > 0 } ?: 480)
            context.contentResolver.openInputStream(Uri.parse(uri)).use { probe ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(probe, null, bounds)
                val sample = maxOf(1, bounds.outHeight / target.coerceAtLeast(1))
                context.contentResolver.openInputStream(Uri.parse(uri)).use { full ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(full, null, opts)
                        ?.let { BitmapDrawable(resources, it) }
                }
            }
        }.onFailure {
            PrismLogger.logWarning("PrismWriter", "Could not load the keyboard background: ${it.message}")
        }.getOrNull()

        blurred = background?.let { buildBlur(it) }
    }

    /**
     * Cheap blur: shrink, then let bilinear filtering smear it back up.
     *
     * RenderScript is deprecated and RenderEffect needs API 31, so neither is a general answer.
     * Downscaling to a fraction and drawing it back at size costs one small allocation and looks
     * like a blur because it is one -- the information is genuinely gone.
     */
    private fun buildBlur(source: Drawable): android.graphics.Bitmap? = runCatching {
        val w = width.takeIf { it > 0 } ?: 1080
        val h = height.takeIf { it > 0 } ?: 480
        val small = android.graphics.Bitmap.createBitmap(
            (w / 18).coerceAtLeast(2), (h / 18).coerceAtLeast(2),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        Canvas(small).also { c ->
            source.setBounds(0, 0, small.width, small.height)
            source.draw(c)
        }
        android.graphics.Bitmap.createScaledBitmap(small, w, h, true)
    }.getOrNull()

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Rebuilt lazily: the blur is sized to the view, which is unknown until the first layout.
        if (blurred == null && background != null && width > 0) blurred = buildBlur(background!!)

        val panelTop = popupHeadroom.toInt()
        val bg = background
        if (bg != null) {
            bg.setBounds(0, panelTop, width, height)
            bg.draw(canvas)
            // Dimmed, or light labels vanish over a bright photo. The amount is the user's call.
            val dim = PrismSettings.getWriterBackgroundDim()
            if (dim > 0) {
                dimPaint.color = Color.argb((dim * 255 / 100), 0, 0, 0)
                canvas.drawRect(0f, popupHeadroom, width.toFloat(), height.toFloat(), dimPaint)
            }
        } else {
            canvas.drawRect(0f, popupHeadroom, width.toFloat(), height.toFloat(), panelFill)
        }
        canvas.drawRect(0f, popupHeadroom, width.toFloat(), popupHeadroom + dp(0.5f), hairline)

        // iOS key corners are small relative to the key: ~5dp on a ~42dp key. A larger radius is
        // what makes a keyboard look like a row of pills instead of keys.
        val radius = dp(5f)
        val letterSize = height * 0.148f

        for (key in layout.keys) {
            val rect = RectF(
                fx(key.centerX - key.width / 2), fy(key.centerY - key.height / 2),
                fx(key.centerX + key.width / 2), fy(key.centerY + key.height / 2),
            )
            val fill = when {
                key === pressedKey -> pressedFill
                key.action == Key.Action.RETURN -> actionFill
                key.action == Key.Action.CHARACTER || key.action == Key.Action.SPACE -> keyFill
                else -> actionFill
            }

            canvas.drawRoundRect(
                RectF(rect.left, rect.top + dp(1f), rect.right, rect.bottom + dp(1f)),
                radius, radius, keyShadow,
            )

            val frost = blurred
            if (frost != null) {
                // The key is a window onto the blurred image, plus a faint tint so labels keep
                // their contrast over a bright or busy photo.
                canvas.save()
                keyClip.reset()
                keyClip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                canvas.clipPath(keyClip)
                canvas.drawBitmap(frost, 0f, 0f, blurPaint)
                frostPaint.color = if (dark) 0x40000000 else 0x40FFFFFF
                canvas.drawRect(rect, frostPaint)
                canvas.restore()
            } else {
                canvas.drawRoundRect(rect, radius, radius, fill)
            }

            // DRAWN, NOT TYPED. Every action key is a path, so none of them can overflow its key
            // the way the word labels did, and none of them depends on the system font.
            when (key.action) {
                Key.Action.CHARACTER -> drawFittedText(canvas, key.label, rect, letterSize)
                Key.Action.SPACE -> drawFittedText(canvas, "space", rect, letterSize * 0.62f)
                Key.Action.SYMBOLS -> drawFittedText(canvas, key.label, rect, letterSize * 0.72f)
                Key.Action.RETURN -> drawReturnIcon(canvas, rect)
                Key.Action.SHIFT -> drawShiftIcon(canvas, rect)
                Key.Action.BACKSPACE -> drawBackspaceIcon(canvas, rect)
                Key.Action.MIC -> drawMicIcon(canvas, rect)
                Key.Action.SEARCH -> drawSearchIcon(canvas, rect)
                Key.Action.TRANSLATE -> drawTranslateIcon(canvas, rect)
                Key.Action.THEME -> drawThemeIcon(canvas, rect)
                Key.Action.EMOJI -> drawEmojiIcon(canvas, rect)
                Key.Action.STICKERS -> drawStickerIcon(canvas, rect)
            }
        }

        drawTrail(canvas)
        drawPressPopup(canvas, radius)
        drawAlternates(canvas, radius)
    }

    /**
     * Draws text scaled down until it fits the key.
     *
     * A fixed text size is what let "translate" run past both edges of a key one eighth of the
     * screen wide. Measuring and shrinking means a label can never overflow, whatever the font
     * happens to be.
     */
    private fun drawFittedText(canvas: Canvas, text: String, rect: RectF, preferredSize: Float) {
        keyText.textSize = preferredSize
        val available = rect.width() - dp(6f)
        val measured = keyText.measureText(text)
        if (measured > available && measured > 0f) {
            keyText.textSize = preferredSize * (available / measured)
        }
        canvas.drawText(
            text, rect.centerX(),
            rect.centerY() - (keyText.descent() + keyText.ascent()) / 2, keyText,
        )
    }

    /** Shared setup for the stroked icons: colour from the key text, width from the key size. */
    private fun beginIcon(rect: RectF): Float {
        iconStroke.color = keyText.color
        iconFill.color = keyText.color
        val size = minOf(rect.width(), rect.height()) * 0.42f
        iconStroke.strokeWidth = size * 0.13f
        return size
    }

    private fun drawShiftIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        iconPath.reset()
        iconPath.moveTo(cx, cy - size * 0.62f)
        iconPath.lineTo(cx + size * 0.62f, cy + size * 0.02f)
        iconPath.lineTo(cx + size * 0.28f, cy + size * 0.02f)
        iconPath.lineTo(cx + size * 0.28f, cy + size * 0.6f)
        iconPath.lineTo(cx - size * 0.28f, cy + size * 0.6f)
        iconPath.lineTo(cx - size * 0.28f, cy + size * 0.02f)
        iconPath.lineTo(cx - size * 0.62f, cy + size * 0.02f)
        iconPath.close()
        canvas.drawPath(iconPath, iconFill)
    }

    private fun drawBackspaceIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        iconPath.reset()
        iconPath.moveTo(cx - size * 0.85f, cy)
        iconPath.lineTo(cx - size * 0.25f, cy - size * 0.55f)
        iconPath.lineTo(cx + size * 0.85f, cy - size * 0.55f)
        iconPath.lineTo(cx + size * 0.85f, cy + size * 0.55f)
        iconPath.lineTo(cx - size * 0.25f, cy + size * 0.55f)
        iconPath.close()
        canvas.drawPath(iconPath, iconFill)

        // The cross is punched out in the key's own colour, which is what makes it read as a hole.
        val cross = Paint(iconStroke).apply {
            color = if (dark) DARK_ACTION else LIGHT_ACTION
            strokeWidth = size * 0.16f
        }
        val arm = size * 0.24f
        canvas.drawLine(cx + size * 0.08f - arm, cy - arm, cx + size * 0.08f + arm, cy + arm, cross)
        canvas.drawLine(cx + size * 0.08f - arm, cy + arm, cx + size * 0.08f + arm, cy - arm, cross)
    }

    private fun drawReturnIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        // The return key is filled with the accent, so its glyph has to sit on that, not on the key.
        iconStroke.color = android.graphics.Color.WHITE
        val cx = rect.centerX()
        val cy = rect.centerY()
        iconPath.reset()
        iconPath.moveTo(cx + size * 0.7f, cy - size * 0.55f)
        iconPath.lineTo(cx + size * 0.7f, cy + size * 0.1f)
        iconPath.lineTo(cx - size * 0.6f, cy + size * 0.1f)
        canvas.drawPath(iconPath, iconStroke)
        iconPath.reset()
        iconPath.moveTo(cx - size * 0.2f, cy - size * 0.3f)
        iconPath.lineTo(cx - size * 0.7f, cy + size * 0.1f)
        iconPath.lineTo(cx - size * 0.2f, cy + size * 0.5f)
        canvas.drawPath(iconPath, iconStroke)
    }

    private fun drawMicIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val capsule = RectF(
            cx - size * 0.3f, cy - size * 0.75f, cx + size * 0.3f, cy + size * 0.12f
        )
        canvas.drawRoundRect(capsule, size * 0.3f, size * 0.3f, iconFill)
        iconPath.reset()
        iconPath.addArc(
            RectF(cx - size * 0.55f, cy - size * 0.35f, cx + size * 0.55f, cy + size * 0.5f),
            0f, 180f,
        )
        canvas.drawPath(iconPath, iconStroke)
        canvas.drawLine(cx, cy + size * 0.5f, cx, cy + size * 0.82f, iconStroke)
    }

    private fun drawSearchIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX() - size * 0.1f
        val cy = rect.centerY() - size * 0.1f
        canvas.drawCircle(cx, cy, size * 0.45f, iconStroke)
        canvas.drawLine(
            cx + size * 0.34f, cy + size * 0.34f,
            cx + size * 0.8f, cy + size * 0.8f, iconStroke,
        )
    }

    private fun drawTranslateIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        // A globe reads as "language" at this size far better than any pair of letters would.
        canvas.drawCircle(cx, cy, size * 0.62f, iconStroke)
        canvas.drawLine(cx - size * 0.62f, cy, cx + size * 0.62f, cy, iconStroke)
        iconPath.reset()
        iconPath.addOval(
            RectF(cx - size * 0.3f, cy - size * 0.62f, cx + size * 0.3f, cy + size * 0.62f),
            Path.Direction.CW,
        )
        canvas.drawPath(iconPath, iconStroke)
    }

    private fun drawEmojiIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.drawCircle(cx, cy, size * 0.62f, iconStroke)
        canvas.drawCircle(cx - size * 0.24f, cy - size * 0.18f, size * 0.08f, iconFill)
        canvas.drawCircle(cx + size * 0.24f, cy - size * 0.18f, size * 0.08f, iconFill)
        iconPath.reset()
        iconPath.addArc(
            RectF(cx - size * 0.36f, cy - size * 0.16f, cx + size * 0.36f, cy + size * 0.38f),
            20f, 140f,
        )
        canvas.drawPath(iconPath, iconStroke)
    }

    private fun drawStickerIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        // A square with one corner peeled back, which is the usual sticker mark.
        iconPath.reset()
        iconPath.moveTo(cx - size * 0.6f, cy - size * 0.6f)
        iconPath.lineTo(cx + size * 0.6f, cy - size * 0.6f)
        iconPath.lineTo(cx + size * 0.6f, cy + size * 0.12f)
        iconPath.lineTo(cx + size * 0.12f, cy + size * 0.6f)
        iconPath.lineTo(cx - size * 0.6f, cy + size * 0.6f)
        iconPath.close()
        canvas.drawPath(iconPath, iconStroke)
        iconPath.reset()
        iconPath.moveTo(cx + size * 0.6f, cy + size * 0.12f)
        iconPath.lineTo(cx + size * 0.12f, cy + size * 0.12f)
        iconPath.lineTo(cx + size * 0.12f, cy + size * 0.6f)
        canvas.drawPath(iconPath, iconStroke)
    }

    private fun drawThemeIcon(canvas: Canvas, rect: RectF) {
        val size = beginIcon(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.drawCircle(cx, cy, size * 0.6f, iconStroke)
        // Half filled: the key means "switch", and a half-lit circle says that without a label.
        iconPath.reset()
        iconPath.addArc(
            RectF(cx - size * 0.6f, cy - size * 0.6f, cx + size * 0.6f, cy + size * 0.6f),
            90f, 180f,
        )
        iconPath.close()
        canvas.drawPath(iconPath, iconFill)
    }

    /**
     * The glide trail: smoothed, tapered and optionally glowing.
     *
     * SMOOTHED WITH MIDPOINT CURVES rather than drawn as line segments. Raw touch samples are
     * jagged at speed, and a polyline through them looks like a seismograph; joining midpoints with
     * quadratic curves through the sample points gives a line that follows the finger without the
     * corners. The glow is the same path drawn underneath, blurred and wider.
     */
    private fun drawTrail(canvas: Canvas) {
        if (path.size < 2) return

        trailPath.reset()
        var previousX = fx(path[0].x)
        var previousY = fy(path[0].y)
        trailPath.moveTo(previousX, previousY)
        for (i in 1 until path.size) {
            val x = fx(path[i].x)
            val y = fy(path[i].y)
            trailPath.quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
            previousX = x
            previousY = y
        }
        trailPath.lineTo(previousX, previousY)

        val color = PrismSettings.getWriterTrailColor()
        val stroke = height * 0.013f

        if (PrismSettings.getWriterTrailGlow()) {
            glowPaint.color = color
            glowPaint.alpha = 110
            glowPaint.strokeWidth = stroke * 2.6f
            glowPaint.maskFilter = BlurMaskFilter(stroke * 1.6f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(trailPath, glowPaint)
        }

        // Fades from transparent at the start to solid at the finger, so the head of the stroke
        // reads as "where you are" rather than the whole path shouting equally.
        trailPaint.shader = LinearGradient(
            fx(path.first().x), fy(path.first().y),
            fx(path.last().x), fy(path.last().y),
            Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)),
            color,
            Shader.TileMode.CLAMP,
        )
        trailPaint.strokeWidth = stroke
        canvas.drawPath(trailPath, trailPaint)
        trailPaint.shader = null
    }

    /**
     * The balloon above a pressed letter.
     *
     * SHAPED, NOT A FLOATING RECTANGLE. The iOS press preview is one continuous piece: a wide head
     * above the key, a neck that tapers down to the key's own width, and the key itself as the
     * base. Drawing a detached rounded rect above the key -- which is what this did -- reads as a
     * tooltip rather than as the key stretching upward, and that difference is most of why the
     * effect looked wrong.
     */
    private fun drawPressPopup(canvas: Canvas, radius: Float) {
        val key = pressedKey ?: return
        if (alternatesFor != null) return                 // the alternates strip replaces it
        if (key.action != Key.Action.CHARACTER) return    // only characters pop; actions do not

        val keyLeft = fx(key.centerX - key.width / 2)
        val keyRight = fx(key.centerX + key.width / 2)
        val keyTop = fy(key.centerY - key.height / 2)
        val keyBottom = fy(key.centerY + key.height / 2)
        val keyWidth = keyRight - keyLeft
        val keyHeight = keyBottom - keyTop

        val headWidth = keyWidth * 1.42f
        val headHeight = keyHeight * 1.12f
        val neckHeight = keyHeight * 0.5f
        var headLeft = fx(key.centerX) - headWidth / 2
        // Clamped so an edge key's balloon stays on screen instead of being clipped in half.
        headLeft = headLeft.coerceIn(dp(2f), width - headWidth - dp(2f))
        val headRight = headLeft + headWidth
        val headTop = keyTop - neckHeight - headHeight
        val r = radius * 1.9f

        iconPath.reset()
        // Head, with rounded corners, then a neck that curves inward to meet the key's top edge.
        iconPath.moveTo(headLeft, headTop + r)
        iconPath.quadTo(headLeft, headTop, headLeft + r, headTop)
        iconPath.lineTo(headRight - r, headTop)
        iconPath.quadTo(headRight, headTop, headRight, headTop + r)
        iconPath.lineTo(headRight, headTop + headHeight)
        iconPath.quadTo(headRight, keyTop - neckHeight * 0.35f, keyRight, keyTop - neckHeight * 0.1f)
        iconPath.lineTo(keyRight, keyBottom - radius)
        iconPath.quadTo(keyRight, keyBottom, keyRight - radius, keyBottom)
        iconPath.lineTo(keyLeft + radius, keyBottom)
        iconPath.quadTo(keyLeft, keyBottom, keyLeft, keyBottom - radius)
        iconPath.lineTo(keyLeft, keyTop - neckHeight * 0.1f)
        iconPath.quadTo(headLeft, keyTop - neckHeight * 0.35f, headLeft, headTop + headHeight)
        iconPath.close()

        canvas.drawPath(iconPath, keyShadow)
        canvas.drawPath(iconPath, popupFill)

        popupText.textSize = headHeight * 0.62f
        canvas.drawText(
            key.label, (headLeft + headRight) / 2f,
            headTop + headHeight / 2f - (popupText.descent() + popupText.ascent()) / 2, popupText,
        )
    }

    /** The row of accents a long press opens. */
    private fun drawAlternates(canvas: Canvas, radius: Float) {
        val key = alternatesFor ?: return
        val options = key.alternates
        if (options.isEmpty()) return

        val cell = fx(key.width) * 1.15f
        val h = fy(key.height) * 1.25f
        val total = cell * options.size
        val left = (fx(key.centerX) - total / 2).coerceIn(dp(4f), width - total - dp(4f))
        val top = fy(key.centerY - key.height / 2) - h - dp(8f)
        val rect = RectF(left, top, left + total, top + h)

        canvas.drawRoundRect(
            RectF(rect.left, rect.top + dp(2f), rect.right, rect.bottom + dp(2f)),
            radius * 1.8f, radius * 1.8f, keyShadow,
        )
        canvas.drawRoundRect(rect, radius * 1.8f, radius * 1.8f, popupFill)

        popupText.textSize = h * 0.5f
        for ((i, option) in options.withIndex()) {
            val cellRect = RectF(left + cell * i, top, left + cell * (i + 1), top + h)
            val selected = i == alternateIndex
            if (selected) {
                // SELECTION BLUE, NOT THE MODIFIER GREY. The grey used here before was the same
                // colour as the shift and backspace keys, so the highlight did not read as a
                // selection at all -- it looked like one cell had simply gone a different shade.
                selectionFill.color = PrismSettings.getWriterAccentColor().takeIf { it != 0 }
                    ?: SELECTION_BLUE
                canvas.drawRoundRect(
                    RectF(cellRect.left + dp(3f), cellRect.top + dp(3f), cellRect.right - dp(3f), cellRect.bottom - dp(3f)),
                    radius * 1.2f, radius * 1.2f, selectionFill,
                )
            }
            val previous = popupText.color
            if (selected) popupText.color = android.graphics.Color.WHITE
            canvas.drawText(
                option, cellRect.centerX(),
                cellRect.centerY() - (popupText.descent() + popupText.ascent()) / 2, popupText,
            )
            popupText.color = previous
        }
    }

    private fun displayLabel(key: Key): String = when (key.action) {
        Key.Action.SPACE -> "space"
        Key.Action.RETURN -> "return"
        else -> key.label
    }

    // ── Touch ──────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / width.coerceAtLeast(1)
        // The inverse of fy: a finger at the top of the key area is 0, not the top of the view.
        val keyArea = (height - popupHeadroom).coerceAtLeast(1f)
        val y = (event.y - popupHeadroom) / keyArea

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.clear()
                gestureActive = false
                alternatesFor = null
                path.add(SwipeDecoder.Point(x, y))
                startKey = layout.keyAt(x, y)
                pressedKey = startKey
                startKey?.let { onHoldStart?.invoke(it) }
                scheduleLongPress()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                path.add(SwipeDecoder.Point(x, y))

                if (alternatesFor != null) {
                    trackAlternate(x)
                    return true
                }

                // Tested against startKey, not pressedKey: see the field's comment. This is what
                // makes a glide start reliably instead of only when the finger is over a letter at
                // the exact sample where the path first exceeds the threshold.
                if (!gestureActive &&
                    PrismSettings.getWriterSwipeEnabled() &&
                    startKey?.isLetter == true &&
                    SwipeDecoder.isGesture(path)
                ) {
                    gestureActive = true
                    cancelAlternatesTimer()
                }

                if (gestureActive) {
                    pressedKey = null
                    invalidate()
                } else {
                    val under = layout.keyAt(x, y)
                    if (under !== pressedKey) {
                        pressedKey = under
                        // Sliding onto another key is a different key, so the old timer is void.
                        cancelAlternatesTimer()
                        if (under != null) scheduleLongPress()
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelAlternatesTimer()
                onHoldEnd?.invoke()
                val alternates = alternatesFor
                when {
                    alternates != null ->
                        alternates.alternates.getOrNull(alternateIndex)?.let { onAlternate?.invoke(it) }
                    gestureActive -> onGesture?.invoke(path.toList())
                    else -> pressedKey?.let { onKey?.invoke(it) }
                }
                reset()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelAlternatesTimer()
                onHoldEnd?.invoke()
                reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun reset() {
        pressedKey = null
        startKey = null
        alternatesFor = null
        gestureActive = false
        path.clear()
        invalidate()
    }

    private fun scheduleLongPress() {
        cancelAlternatesTimer()
        val key = pressedKey ?: return
        if (key.alternates.isEmpty()) return

        val runnable = Runnable {
            // Only if the stroke is still a press: a glide that started in the meantime owns the
            // finger, and opening a popup under it would steal the word.
            if (!gestureActive) {
                alternatesFor = key
                alternateIndex = 0
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
        longPressPending = runnable
        longPress.postDelayed(runnable, LONG_PRESS_MS)
    }

    /** Cancels OUR alternates timer. Named apart from View.cancelLongPress(), which is unrelated. */
    private fun cancelAlternatesTimer() {
        longPressPending?.let { longPress.removeCallbacks(it) }
        longPressPending = null
    }

    /** Maps the finger's x to a cell in the open alternates strip. */
    private fun trackAlternate(x: Float) {
        val key = alternatesFor ?: return
        val options = key.alternates
        val cell = key.width * 1.15f
        val total = cell * options.size
        val left = (key.centerX - total / 2).coerceIn(0f, (1f - total).coerceAtLeast(0f))
        val index = (((x - left) / cell).toInt()).coerceIn(0, options.size - 1)
        if (index != alternateIndex) {
            alternateIndex = index
            invalidate()
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun blend(a: Int, b: Int, amount: Float): Int = Color.rgb(
        (Color.red(a) * (1 - amount) + Color.red(b) * amount).toInt(),
        (Color.green(a) * (1 - amount) + Color.green(b) * amount).toInt(),
        (Color.blue(a) * (1 - amount) + Color.blue(b) * amount).toInt(),
    )

    private companion object {
        // iOS proper: a cool grey panel, white letter keys, and noticeably darker modifier keys.
        // The contrast between the two greys is what makes the layout readable at a glance.
        const val LIGHT_PANEL = 0xFFD1D5DB.toInt()
        const val DARK_PANEL = 0xFF1B1B1D.toInt()
        const val LIGHT_KEY = 0xFFFFFFFF.toInt()
        const val DARK_KEY = 0xFF6B6B70.toInt()
        const val LIGHT_ACTION = 0xFFACB3BD.toInt()
        const val DARK_ACTION = 0xFF3A3A3D.toInt()
        const val LONG_PRESS_MS = 320L
        /** iOS system blue, used for the accent and for the alternates selection. */
        const val SELECTION_BLUE = 0xFF007AFF.toInt()
    }
}
