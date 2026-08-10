package com.prism.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.*

/**
 * Kinetic Halo Page: A circular, physics-based app drawer.
 * Interaction: Touch and hold to summon the ring, slide in circles to rotate apps.
 */
class KineticHaloPageView(
    context: Context,
    private val onLaunch: (android.content.ComponentName) -> Unit,
) : FrameLayout(context) {

    private var allApps: List<DrawerAppEntry> = emptyList()
    private var observeJob: Job? = null
    private val activeIcons = mutableListOf<AppIconView>()
    
    // Interaction state
    private var isSummoned = false
    private var centerPoint = PointF(0f, 0f)
    private var currentRotation = 0f // In degrees
    private var lastTouchAngle = 0f
    
    // Visuals
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 100
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private var summonProgress = 0f // 0 to 1
    private var velocity = 0f // Angular velocity
    private var lastHapticAngle = 0f

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observeJob?.isActive == true) return
        val lifecycleOwner = context as? LifecycleOwner ?: return
        // Live-observes the DB so a newly installed/removed app is reflected immediately,
        // matching the same fix applied to DrawerPageView (was previously a one-shot load).
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
        super.onDetachedFromWindow()
    }

    private fun setupIcons() {
        removeAllViews()
        activeIcons.clear()
        // We only show a subset or all depending on layers.
        // For the V1, let's map the top 24 apps.
        allApps.take(32).forEach { entry ->
            val iconView = AppIconView(context, entry)
            iconView.setOnClickListener { onLaunch(entry.component) }
            addView(iconView, LayoutParams(120, 120))
            activeIcons.add(iconView)
            iconView.alpha = 0f
            iconView.scaleX = 0f
            iconView.scaleY = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (summonProgress > 0) {
            val radius = 300f * summonProgress
            ringPaint.alpha = (100 * summonProgress).toInt()
            canvas.drawCircle(centerPoint.x, centerPoint.y, radius, ringPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                centerPoint.set(event.x, event.y)
                animateSummon(true)
                lastTouchAngle = calculateAngle(event.x, event.y)
                velocity = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val currentAngle = calculateAngle(event.x, event.y)
                val delta = normalizeAngle(currentAngle - lastTouchAngle)
                currentRotation += delta
                velocity = delta // Simple velocity tracking
                lastTouchAngle = currentAngle
                
                checkHapticFeedback(currentRotation)
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

    private fun animateSummon(summon: Boolean) {
        isSummoned = summon
        ValueAnimator.ofFloat(summonProgress, if (summon) 1f else 0f).apply {
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
        val radius = 300f * summonProgress
        val iconCount = activeIcons.size
        if (iconCount == 0) return

        activeIcons.forEachIndexed { index, view ->
            val angleDeg = (currentRotation + (index * (360f / iconCount)))
            val angleRad = Math.toRadians(angleDeg.toDouble())
            
            val x = centerPoint.x + radius * cos(angleRad).toFloat() - view.width / 2
            val y = centerPoint.y + radius * sin(angleRad).toFloat() - view.height / 2
            
            view.x = x
            view.y = y
            
            // Visual polish: fade in/scale as they expand
            view.alpha = summonProgress * 0.9f
            view.scaleX = summonProgress
            view.scaleY = summonProgress
            
            // Adjust depth based on "speed" (velocity) if needed in future V2
        }
    }

    private fun applyInertia() {
        if (abs(velocity) < 0.5f) return
        
        ValueAnimator.ofFloat(velocity, 0f).apply {
            duration = (abs(velocity) * 100).toLong().coerceIn(200, 1000)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                currentRotation += v
                checkHapticFeedback(currentRotation)
                layoutIcons()
            }
            start()
        }
    }

    private fun checkHapticFeedback(angle: Float) {
        val step = 360f / activeIcons.size.coerceAtLeast(1)
        if (abs(angle - lastHapticAngle) >= step) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticAngle = angle
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
            val iv = ImageView(context)
            iv.setImageDrawable(entry.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            
            val bg = View(context)
            bg.background = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL).let { null } // Placeholder logic
            // Add a subtle bloom effect?
        }
    }
}
