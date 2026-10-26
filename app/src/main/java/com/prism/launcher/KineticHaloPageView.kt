package com.prism.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Kinetic Halo: a circular, physics-ish app picker.
 *
 * ## Geometry is in dp and measured from the view, never fixed pixels
 *
 * The first version placed 120-pixel icons on a 300-pixel ring. On a 3x phone that is a ring a
 * third of the width with icons that look like specks; on a tablet the ring does not fit at all.
 * Everything here is derived from the view's own size and [resources.displayMetrics]: the ring is
 * a fraction of the shorter side, the icons are a fixed dp size, and the centre is the view's
 * centre rather than wherever the finger happened to land. That last part matters on a phone --
 * summoning the halo near an edge used to push most of it off screen.
 *
 * Touch still decides the ANGLE the ring starts at, so the gesture feels directly manipulated,
 * but not the centre.
 */
class KineticHaloPageView(
    context: Context,
    private val onLaunch: (android.content.ComponentName) -> Unit,
) : FrameLayout(context) {

    private var allApps: List<DrawerAppEntry> = emptyList()
    private var observeJob: Job? = null
    private val activeIcons = mutableListOf<AppIconView>()

    // Interaction state
    private var currentRotation = 0f // degrees
    private var lastTouchAngle = 0f
    private var summonProgress = 0f // 0..1
    private var velocity = 0f // angular velocity, degrees per frame
    private var lastHapticRotation = 0f
    private var inertia: ValueAnimator? = null
    private var summon: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val iconSizePx = (56f * density).toInt()
    private val ringRadiusFraction = 0.33f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        alpha = 110
    }

    private val centerPoint = PointF(0f, 0f)

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observeJob?.isActive == true) return
        val lifecycleOwner = context as? LifecycleOwner ?: return
        observeJob = lifecycleOwner.lifecycleScope.launch {
            AppDatabase.get().installedAppDao().observeAll().collectLatest { entities ->
                allApps = withContext(Dispatchers.IO) { resolveDrawerEntries(entities) }
                setupIcons()
            }
        }
    }

    override fun onDetachedFromWindow() {
        observeJob?.cancel()
        observeJob = null
        summon?.cancel()
        inertia?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerPoint.set(w / 2f, h / 2f)
        layoutIcons()
    }

    /** Radius the ring reaches at full summon, in pixels. */
    private fun ringRadius(): Float =
        min(width, height).coerceAtLeast(1) * ringRadiusFraction

    private fun setupIcons() {
        removeAllViews()
        activeIcons.clear()

        allApps.take(MAX_APPS).forEach { entry ->
            val iconView = AppIconView(context, entry)
            iconView.setOnClickListener { onLaunch(entry.component) }
            addView(iconView, LayoutParams(iconSizePx, iconSizePx))
            activeIcons.add(iconView)
            iconView.alpha = 0f
            iconView.scaleX = 0f
            iconView.scaleY = 0f
        }
        layoutIcons()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (summonProgress <= 0f) return
        ringPaint.alpha = (110 * summonProgress).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerPoint.x, centerPoint.y, ringRadius() * summonProgress, ringPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                centerPoint.set(width / 2f, height / 2f)
                animateSummon(true)
                lastTouchAngle = calculateAngle(event.x, event.y)
                velocity = 0f
                inertia?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val currentAngle = calculateAngle(event.x, event.y)
                val delta = normalizeAngle(currentAngle - lastTouchAngle)
                currentRotation += delta
                velocity = delta
                lastTouchAngle = currentAngle

                checkHapticFeedback()
                layoutIcons()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animateSummon(false)
                applyInertia()
            }
        }
        return true
    }

    private fun animateSummon(show: Boolean) {
        summon?.cancel()
        summon = ValueAnimator.ofFloat(summonProgress, if (show) 1f else 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                summonProgress = it.animatedValue as Float
                layoutIcons()
                invalidate()
            }
            start()
        }
    }

    private fun layoutIcons() {
        val radius = ringRadius() * summonProgress
        val iconCount = activeIcons.size
        if (iconCount == 0) return

        val step = 360f / iconCount
        activeIcons.forEachIndexed { index, view ->
            val angleRad = Math.toRadians((currentRotation + (index * step)).toDouble())
            val x = centerPoint.x + radius * cos(angleRad).toFloat() - iconSizePx / 2f
            val y = centerPoint.y + radius * sin(angleRad).toFloat() - iconSizePx / 2f
            view.x = x
            view.y = y
            view.alpha = summonProgress * 0.9f
            view.scaleX = summonProgress
            view.scaleY = summonProgress
        }
    }

    private fun applyInertia() {
        if (abs(velocity) < 0.5f) return
        inertia?.cancel()
        inertia = ValueAnimator.ofFloat(velocity, 0f).apply {
            duration = (abs(velocity) * 100).toLong().coerceIn(200, 1000)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentRotation += it.animatedValue as Float
                checkHapticFeedback()
                layoutIcons()
            }
            start()
        }
    }

    /**
     * One tick per icon that passes the finger.
     *
     * Tracked on accumulated rotation rather than the raw angle: the angle wraps at +/-180, so the
     * old comparison fired once per revolution more or less by accident and not at all in some
     * directions.
     */
    private fun checkHapticFeedback() {
        val step = 360f / activeIcons.size.coerceAtLeast(1)
        if (abs(currentRotation - lastHapticRotation) >= step) {
            lastHapticRotation = currentRotation
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun calculateAngle(x: Float, y: Float): Float {
        val dx = x - centerPoint.x
        val dy = y - centerPoint.y
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a < -180) a += 360
        while (a > 180) a -= 360
        return a
    }

    private fun resolveDrawerEntries(entities: List<InstalledAppEntity>): List<DrawerAppEntry> {
        val pm = context.packageManager
        return entities.mapNotNull { entity ->
            try {
                val cn = android.content.ComponentName(entity.packageName, entity.activityClass)
                val ai = pm.getActivityInfo(cn, 0)
                DrawerAppEntry(
                    component = cn,
                    label = ai.loadLabel(pm).toString(),
                    icon = try { ai.loadIcon(pm) } catch (_: Throwable) { null },
                )
            } catch (_: Throwable) { null }
        }.sortedBy { it.label.lowercase() }
    }

    private class AppIconView(context: Context, entry: DrawerAppEntry) : FrameLayout(context) {
        init {
            contentDescription = entry.label
            val iv = ImageView(context)
            iv.setImageDrawable(entry.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private companion object {
        /**
         * More than this on one ring and the icons overlap at any radius a phone can hold. The
         * drawer is where the full list lives; the halo is a quick picker.
         */
        const val MAX_APPS = 24
    }
}
