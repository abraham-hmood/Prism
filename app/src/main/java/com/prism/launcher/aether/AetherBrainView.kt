package com.prism.launcher.aether

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.io.File

/**
 * Aether's live "brain view" -- a native in-app visualization of [AetherTelemetry], the
 * equivalent of Nora's `NoraBrainView`. Simpler by design: a plain [View] redrawn on a
 * `Handler.postDelayed` tick rather than Nora's dedicated `TextureView` + priority render
 * thread. That's a real scope reduction (no packet animation along the pathways, no rounded-card
 * clipping while scrolling), traded for a fraction of the code, since the goal here is "Aether
 * has a live diagnostic view at all," not matching Nora's own visualizer polish stroke for
 * stroke.
 *
 * Look-and-feel is deliberately ported from the Python CLI's dashboard (`diagnostics/web/index.html`):
 * each cortex has its own fixed color ([regionColor]) and glows brighter/wider the more active it
 * is (see the halo/glow math in [onDraw]), and the background is whatever brain image is
 * configured via [AetherTuning.dashboardBrainImage] (falling back to the bundled default asset). The exact pixel
 * formulas don't transfer 1:1 -- the CLI's numbers assume small un-normalized spike-rate values
 * against a large SVG viewBox, this view's `a` is already peak-normalized to 0..1 -- but the same
 * floor-plus-scale-capped shape is kept for each one, just recalibrated to that 0..1 domain.
 */
class AetherBrainView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // BlurMaskFilter (the node glow) and PorterDuff.Mode.SCREEN (the bloom blend) both require
        // software rendering -- neither is supported on a hardware-accelerated Canvas.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private data class Node(val region: AetherTelemetry.Region?, val label: String, val x: Float, val y: Float)

    // Roughly the same left-to-right sensory -> integration -> motor flow AetherConnectome.forward()
    // actually follows, laid out by hand (no algorithmic layout, same honesty as Nora's own view).
    private val nodes = listOf(
        Node(AetherTelemetry.Region.VISUAL, "Visual", 0.12f, 0.30f),
        Node(AetherTelemetry.Region.VWFA, "VWFA", 0.30f, 0.18f),
        Node(AetherTelemetry.Region.TEMPORAL, "Temporal", 0.30f, 0.55f),
        Node(AetherTelemetry.Region.PARIETAL, "Parietal", 0.48f, 0.38f),
        Node(AetherTelemetry.Region.AMYGDALA, "Amygdala", 0.48f, 0.72f),
        Node(AetherTelemetry.Region.HIPPOCAMPUS, "Hippocampus", 0.62f, 0.20f),
        Node(AetherTelemetry.Region.EXECUTIVE, "Executive", 0.68f, 0.42f),
        Node(AetherTelemetry.Region.BASAL_GANGLIA, "Basal ganglia", 0.68f, 0.65f),
        Node(AetherTelemetry.Region.BROCA, "Broca", 0.85f, 0.55f),
        Node(AetherTelemetry.Region.CEREBELLUM, "Cerebellum", 0.98f, 0.55f),
        Node(AetherTelemetry.Region.MOTOR_STRIP, "Motor strip", 0.85f, 0.22f)
    )
    private val nodeByRegion = nodes.filter { it.region != null }.associateBy { it.region!! }

    // Same per-cortex palette as the CLI dashboard's `regionColors` map (diagnostics/web/index.html),
    // extended with two colors of its own for BASAL_GANGLIA/AMYGDALA -- regions this Kotlin view
    // tracks that the Python side's simpler visualization doesn't have separate blobs for.
    private val regionColor: Map<AetherTelemetry.Region, Int> = mapOf(
        AetherTelemetry.Region.VISUAL to Color.parseColor("#FF00FF"),
        AetherTelemetry.Region.TEMPORAL to Color.parseColor("#00FF00"),
        AetherTelemetry.Region.PARIETAL to Color.parseColor("#00EEFF"),
        AetherTelemetry.Region.EXECUTIVE to Color.parseColor("#FFFF00"),
        AetherTelemetry.Region.BROCA to Color.parseColor("#FF6600"),
        AetherTelemetry.Region.HIPPOCAMPUS to Color.parseColor("#FFFFFF"),
        AetherTelemetry.Region.VWFA to Color.parseColor("#00FF88"),
        AetherTelemetry.Region.MOTOR_STRIP to Color.parseColor("#FF0088"),
        AetherTelemetry.Region.CEREBELLUM to Color.parseColor("#0066FF"),
        AetherTelemetry.Region.BASAL_GANGLIA to Color.parseColor("#AA00FF"),
        AetherTelemetry.Region.AMYGDALA to Color.parseColor("#FF3333")
    )

    private val activity = FloatArray(AetherTelemetry.Region.entries.size)
    private val traffic = FloatArray(AetherTelemetry.Pathway.entries.size)
    private val activityPeak = FloatArray(activity.size) { 0.01f }
    private val trafficPeak = FloatArray(traffic.size) { 0.01f }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) // additive bloom, mirrors the CSS `mix-blend-mode: screen` blobs
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; textAlign = Paint.Align.CENTER }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; textAlign = Paint.Align.LEFT }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // The configured/bundled brain-image background. Reloaded only when the configured path
    // actually changes (checked cheaply once per tick), not decoded on every frame.
    private var backgroundBitmap: Bitmap? = null
    private var backgroundBitmapPath: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var darkTheme = true

    /** Mirrors `NoraBrainView.setDarkTheme` -- called once, before the view is attached. */
    fun setDarkTheme(dark: Boolean) {
        darkTheme = dark
    }

    /**
     * The Log/Connectome tab switch in [AetherTrainingActivity] toggles this view's visibility
     * rather than detaching it, so [onAttachedToWindow]/[onDetachedFromWindow] alone would leave
     * the tick loop running the whole time the Log tab is showing. Mirrors Nora's
     * `renderEnabled` flag for the same reason: no point drawing thirty -- well, fifteen -- frames
     * a second of a view nobody can see, especially while training is competing for the CPU.
     */
    @Volatile
    private var renderEnabled = true

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRenderEnabled()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRenderEnabled()
    }

    private fun updateRenderEnabled() {
        renderEnabled = visibility == View.VISIBLE && windowVisibility == View.VISIBLE
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!renderEnabled) {
                handler.postDelayed(this, 200L) // cheap poll while hidden, same interval Nora's render thread uses
                return
            }
            refreshFromTelemetry()
            invalidate()
            handler.postDelayed(this, 66L) // ~15fps -- a diagnostic overlay, not an animation
        }
    }

    private fun refreshFromTelemetry() {
        AetherTelemetry.readActivity(activity)
        AetherTelemetry.readTraffic(traffic)
        for (i in activity.indices) activityPeak[i] = maxOf(activityPeak[i] * 0.98f, activity[i])
        for (i in traffic.indices) trafficPeak[i] = maxOf(trafficPeak[i] * 0.98f, traffic[i])
        ensureBackgroundBitmap()
    }

    /**
     * [AetherTuning.dashboardBrainImage] is normally an absolute path under this app's own
     * storage (set by the "browse" button next to that setting, which copies the picked image in)
     * -- but it can also still be Python's own bundled-relative default
     * (`diagnostics/web/assets/brain_lateral_view.png`, meaningless as an Android path) or blank,
     * or point at a file that's since been deleted. Any of those falls back to the same PNG
     * bundled as `R.drawable.brain_lateral_view` (a copy of that same default asset), so there's
     * always *something* to draw rather than a broken background.
     */
    private fun ensureBackgroundBitmap() {
        val configured = AetherTuning.dashboardBrainImage
        if (configured == backgroundBitmapPath) return
        backgroundBitmapPath = configured
        val file = File(configured)
        val bitmap = if (configured.isNotBlank() && file.isAbsolute && file.exists()) {
            try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
        } else null
        backgroundBitmap = bitmap ?: try {
            BitmapFactory.decodeResource(resources, com.prism.launcher.R.drawable.brain_lateral_view)
        } catch (_: Exception) { null }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private val bitmapSrcRect = Rect()
    private val bitmapDstRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(if (darkTheme) Color.parseColor("#0B0B10") else Color.parseColor("#F7F7FA"))
        drawBackgroundImage(canvas, w, h)

        val lineBase = if (darkTheme) 40 else 30
        val labelColor = if (darkTheme) Color.argb(230, 230, 230, 240) else Color.argb(230, 40, 40, 46)
        val captionColor = if (darkTheme) Color.argb(200, 200, 200, 210) else Color.argb(200, 70, 70, 78)

        for (pathway in AetherTelemetry.Pathway.entries) {
            val from = nodeByRegion[pathway.from] ?: continue
            val to = nodeByRegion[pathway.to] ?: continue
            val t = (traffic[pathway.ordinal] / trafficPeak[pathway.ordinal]).coerceIn(0f, 1f)
            linePaint.color = if (darkTheme) {
                Color.argb((lineBase + t * 180).toInt(), 120, 180, 255)
            } else {
                Color.argb((lineBase + t * 170).toInt(), 40, 100, 220)
            }
            linePaint.strokeWidth = 2f + t * 4f
            canvas.drawLine(from.x * w, from.y * h, to.x * w, to.y * h, linePaint)
        }

        for (node in nodes) {
            val region = node.region
            val a = region?.let { (activity[it.ordinal] / activityPeak[it.ordinal]).coerceIn(0f, 1f) } ?: 0f
            val cx = node.x * w
            val cy = node.y * h
            val baseColor = region?.let { regionColor[it] } ?: Color.argb(255, 255, 190, 60)
            val radius = 14f + a * 22f

            // Same floor-plus-scale-capped shape as the CLI's opacity/blur/brightness/glowRadius
            // formula (see the class doc), recalibrated to `a` already being 0..1 peak-normalized:
            // the more active a cortex is, the brighter, wider and more blurred its glow gets.
            val opacity = (0.12f + a * 0.88f).coerceIn(0f, 1f)
            val blurRadius = (6f + a * 46f).coerceAtMost(52f)
            val brightnessMul = 1f + a * 1.4f
            val haloRadius = radius + (a * 60f).coerceAtMost(60f)

            val glowColor = brighten(baseColor, brightnessMul)
            glowPaint.maskFilter = if (blurRadius > 0f) BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL) else null
            glowPaint.shader = RadialGradient(
                cx, cy, haloRadius,
                Color.argb((opacity * 255).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.argb(0, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, haloRadius, glowPaint)

            nodePaint.maskFilter = null
            nodePaint.shader = null
            nodePaint.color = Color.argb((160 + a * 95).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            canvas.drawCircle(cx, cy, radius, nodePaint)
            ringPaint.color = Color.argb(200, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            canvas.drawCircle(cx, cy, radius, ringPaint)

            textPaint.color = labelColor
            canvas.drawText(node.label, cx, cy + radius + 26f, textPaint)
        }

        captionPaint.color = captionColor
        val caption = if (!AetherTelemetry.live) "idle -- generate or train to see her think"
            else "${AetherTelemetry.phase} · dopamine %.2f".format(AetherTelemetry.dopamine)
        canvas.drawText(caption, 16f, h - 16f, captionPaint)
    }

    /** Center-crop the configured/bundled brain image to fill the view, then darken it towards
     * the theme's base color -- mirrors the CLI dashboard's radial-gradient vignette layered over
     * the same PNG, keeping node glow/text legible against a busy photo. */
    private fun drawBackgroundImage(canvas: Canvas, w: Float, h: Float) {
        val bmp = backgroundBitmap ?: return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        val viewAspect = w / h
        val bmpAspect = bw / bh
        if (bmpAspect > viewAspect) {
            val srcW = bh * viewAspect
            val left = ((bw - srcW) / 2f).toInt()
            bitmapSrcRect.set(left, 0, left + srcW.toInt(), bh.toInt())
        } else {
            val srcH = bw / viewAspect
            val top = ((bh - srcH) / 2f).toInt()
            bitmapSrcRect.set(0, top, bw.toInt(), top + srcH.toInt())
        }
        bitmapDstRect.set(0f, 0f, w, h)
        backgroundPaint.alpha = 255
        canvas.drawBitmap(bmp, bitmapSrcRect, bitmapDstRect, backgroundPaint)

        val vignette = if (darkTheme) Color.argb(150, 11, 11, 16) else Color.argb(150, 247, 247, 250)
        canvas.drawColor(vignette)
    }

    private fun brighten(color: Int, mul: Float): Int {
        val r = (Color.red(color) * mul).coerceAtMost(255f).toInt()
        val g = (Color.green(color) * mul).coerceAtMost(255f).toInt()
        val b = (Color.blue(color) * mul).coerceAtMost(255f).toInt()
        return Color.rgb(r, g, b)
    }
}
