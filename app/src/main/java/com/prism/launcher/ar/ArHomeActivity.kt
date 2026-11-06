package com.prism.launcher.ar

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.prism.launcher.PageCatalog
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import com.prism.launcher.nora.IosUi
import com.prism.launcher.spatial.ArHome
import com.prism.launcher.spatial.ArRoomSession
import com.prism.launcher.spatial.angleDifference
import com.prism.launcher.spatial.headingOf
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The home screen in augmented reality: Settings → Other → "Augmented reality for all of Prism".
 *
 * Pages live on the room's walls, floor and ceiling, where the user put them:
 *
 *  - **The first time**, a horizontal grid of every page sits at the bottom. Dragging one onto a
 *    wall, the floor or the ceiling (which light up while dragging) places it there, and the place
 *    is saved -- relative to the room, so it is found again next time (see [ArHome]).
 *  - **While placing**, tapping a placed page selects it: pinching out grows it, pinching in shrinks
 *    it, dragging moves it along the room, and a long press takes it down.
 *  - **Done** (the grid's top right, or the Edit button later): taps open pages. A tap lifts the
 *    page off the wall into a movable window; with "Interact live" on, the page is used where it is.
 *
 * The room is found the way Quest 3 finds it: depth fused into a model of the room, then the floor,
 * ceiling and walls labelled in that model (see [com.prism.launcher.spatial.RoomScanner]).
 */
class ArHomeActivity : PrismBaseActivity(), SensorEventListener {

    companion object {
        /**
         * Set when the user chose to see the ordinary pages; cleared by the next Home press. The
         * launcher reads it on resume to decide whether to come back here.
         */
        @Volatile var flat = false

        fun shouldShow(): Boolean = PrismSettings.getArEverywhere() && !flat

        fun open(context: Context) {
            context.startActivity(
                Intent(context, ArHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    private var headingOffset = 0f
    @Volatile private var headingSamples = 0
    private val room = ArRoomSession(headingOffset = { headingOffset })
    private lateinit var renderer: ArHomeRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var hint: TextView
    private lateinit var tray: LinearLayout
    private lateinit var trayRow: LinearLayout
    private lateinit var editButton: TextView
    private lateinit var popupLayer: FrameLayout
    private var session: Session? = null
    private var running = false
    private var resumed = false
    private var installAsked = false
    private var popped: ArPagePanel? = null

    private val slots: List<Pair<String, SlotAssignment>> by lazy {
        SlotPreferences().getAssignments().mapIndexed { i, s -> ArHome.pageKey(s.serialize(), i) to s }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAr() else message("Prism needs the camera to see your room. Turn on the camera permission, or switch augmented reality off in Settings.")
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = ArHomeRenderer(this, room)
        setContentView(build())
        renderer.onStatus = { line -> runOnUiThread { if (popped == null) hint.text = line } }
        renderer.onError = { line -> runOnUiThread { message(line) } }
        createPanels()
        setEditing(!room.setupDone)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAr()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        resumed = false
        renderer.paused = true
        runCatching { session?.pause() }
        running = false
        glView.onPause()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        popped?.reattachView()
        renderer.panels.values.forEach { it.release() }
        renderer.shutdown()
        runCatching { session?.close() }
        session = null
        super.onDestroy()
    }

    @Deprecated("Back closes a popped-up page first, then leaves for the flat pages.")
    override fun onBackPressed() {
        if (popped != null) { closePopup(); return }
        flat = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ── AR ──────────────────────────────────────────────────────────────────

    private fun startAr() {
        if (!resumed) return
        session?.let { s ->
            renderer.paused = false
            if (!running) runCatching { s.resume() }.onSuccess { running = true }.onFailure { message("The camera could not start: ${it.message}") }
            return
        }
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> createSession()
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                if (installAsked) { message("Google Play Services for AR is installing. Come back when it finishes."); return }
                installAsked = true
                runCatching { ArCoreApk.getInstance().requestInstall(this, true) }
                    .onSuccess { if (it == ArCoreApk.InstallStatus.INSTALLED) createSession() }
                    .onFailure { message("ARCore could not be installed: ${it.message}") }
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                message("This phone cannot run ARCore, so Prism cannot find your walls here. Switch augmented reality off in Settings → Other.")
            else -> hint.postDelayed({ startAr() }, 700)
        }
    }

    private fun createSession() {
        val created = runCatching { Session(this) }.getOrElse { message("AR could not start: ${it.message}"); return }
        runCatching {
            created.configure(Config(created).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                // Depth is what the room model is built from, on every phone that has it; ARCore's
                // planes alone are the fallback.
                depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            })
        }.onFailure { created.close(); message("ARCore refused the settings: ${it.message}"); return }
        session = created
        renderer.session = created
        @Suppress("DEPRECATION")
        renderer.displayRotation = windowManager.defaultDisplay.rotation
        renderer.paused = false
        runCatching { created.resume() }.onSuccess { running = true }.onFailure { message("The camera could not start: ${it.message}") }
    }

    /**
     * The compass, which turns ARCore's arbitrary heading into north, so the room frame a page is
     * saved in is the same frame tomorrow. Averaged while the phone is held upright enough for
     * the camera's heading to mean anything, then held still.
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (headingSamples > 120 || !renderer.tracking) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // The back camera looks down the device's -Z; in east-north-up that is -(column 2).
        val east = -r[2]
        val north = -r[5]
        val up = -r[8]
        if (kotlin.math.abs(up) > 0.8f) return
        val compass = atan2(east, north)
        val ray = renderer.ray(glView.width / 2f, glView.height / 2f, glView.width, glView.height) ?: return
        val offset = angleDifference(compass, headingOf(ray.second.flat()))
        headingOffset = if (headingSamples == 0) offset else {
            val w = 1f / (headingSamples + 1)
            atan2(sin(headingOffset) * (1 - w) + sin(offset) * w, cos(headingOffset) * (1 - w) + cos(offset) * w)
        }
        headingSamples++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Pages ───────────────────────────────────────────────────────────────

    private fun createPanels() {
        val metrics = resources.displayMetrics
        // Phone-shaped, at a width that keeps every page readable on a wall without a full-size
        // buffer per page; the density scales with it so layouts match the phone's in dp.
        val width = 720
        val height = (width * metrics.heightPixels.toFloat() / metrics.widthPixels).roundToInt().coerceIn(900, 1700)
        val dpi = (metrics.densityDpi * width / metrics.widthPixels.toFloat()).roundToInt().coerceAtLeast(120)
        slots.forEach { (key, slot) ->
            val index = key.substringAfterLast('#').toIntOrNull() ?: 0
            renderer.panels[key] = ArPagePanel(key, slot, index, PageCatalog.label(slot, this), width, height, dpi)
        }
    }

    private fun titleOf(key: String) = renderer.panels[key]?.title ?: "Page"

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        background = GradientDrawable().apply { setColor(Color.argb(150, 0, 0, 0)); cornerRadius = dp(18f).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, e -> onRoomTouch(e) }
            setOnDragListener { _, e -> onRoomDrag(e) }
        }
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(36f), dp(12f), dp(8f))
        }
        top.addView(pill("2D") { flat = true; finish() })
        hint = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            text = "Look around slowly so Prism can find your walls, floor and ceiling"
        }
        top.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        editButton = pill("Edit") { setEditing(true) }
        top.addView(editButton)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        tray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(18f))
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@ArHomeActivity))
                cornerRadii = FloatArray(8) { i -> if (i < 4) dp(18f).toFloat() else 0f }
                alpha = 235
            }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Drag a page onto a wall, the floor or the ceiling"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@ArHomeActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Done"
            textSize = 15f
            setTextColor(IosUi.accent(this@ArHomeActivity))
            setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { setEditing(false) }
        })
        tray.addView(header)
        trayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tray.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(trayRow) })
        root.addView(tray, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        popupLayer = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(popupLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun refreshTray() {
        trayRow.removeAllViews()
        val placed = room.placedKeys()
        slots.forEach { (key, _) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(IosUi.groupedBackground(this@ArHomeActivity))
                    cornerRadius = dp(12f).toFloat()
                    if (key in placed) setStroke(dp(2f), IosUi.accent(this@ArHomeActivity))
                }
            }
            // A page-shaped tile, so it reads as the thing being placed.
            card.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(IosUi.accent(this@ArHomeActivity)); alpha = 70; cornerRadius = dp(6f).toFloat() }
            }, LinearLayout.LayoutParams(dp(44f), dp(78f)))
            card.addView(TextView(this).apply {
                text = titleOf(key) + if (key in placed) " ✓" else ""
                textSize = 12f
                maxLines = 1
                setTextColor(IosUi.label(this@ArHomeActivity))
                setPadding(0, dp(6f), 0, 0)
            })
            card.setOnLongClickListener { v ->
                v.startDragAndDrop(ClipData.newPlainText("prism-page", key), View.DragShadowBuilder(v), key, 0)
                true
            }
            card.setOnClickListener { Toast.makeText(this, "Press and hold, then drag it onto a wall, the floor or the ceiling", Toast.LENGTH_SHORT).show() }
            trayRow.addView(card, LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) })
        }
    }

    private var editing = false

    private fun setEditing(on: Boolean) {
        editing = on
        if (on) room.reopenSetup() else room.finishSetup()
        renderer.showSurfaces = on
        renderer.selectedKey = null
        tray.visibility = if (on) View.VISIBLE else View.GONE
        editButton.visibility = if (on) View.GONE else View.VISIBLE
        if (on) refreshTray()
    }

    private fun message(text: String) {
        hint.text = text
    }

    // ── Dragging pages in from the grid ───────────────────────────────────

    private fun onRoomDrag(event: DragEvent): Boolean {
        val key = event.localState as? String ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> {
                renderer.hoverSurface = surfaceHit(event.x, event.y)?.surface
            }
            DragEvent.ACTION_DROP -> {
                renderer.hoverSurface = null
                placeAt(key, event.x, event.y)
            }
            DragEvent.ACTION_DRAG_ENDED -> renderer.hoverSurface = null
        }
        return true
    }

    private fun surfaceHit(x: Float, y: Float) = renderer.ray(x, y, glView.width, glView.height)?.let { (o, d) -> room.layout.raycast(o, d) }

    private fun placeAt(key: String, x: Float, y: Float) {
        val hit = surfaceHit(x, y)
        if (hit == null) {
            Toast.makeText(this, "Prism has not found a wall, floor or ceiling there yet. Look at it for a moment, then try again.", Toast.LENGTH_LONG).show()
            return
        }
        val panel = renderer.panels[key] ?: return
        // ARCore measures the real surface under the finger; the page goes there, on the room's
        // surface orientation, so it sits ON the wall rather than where the model thinks it is.
        renderer.measure(x, y) { measured ->
            runOnUiThread {
                if (room.place(key, hit, renderer.cameraPosition, panel.aspect, measured)) {
                    renderer.selectedKey = key
                    refreshTray()
                } else {
                    Toast.makeText(this, "Still finding the room. Look around a little more.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Touching placed pages ──────────────────────────────────────────────

    private val scaler by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!editing) return false
                // The page under the fingers, or the one already selected.
                pageAt(detector.focusX, detector.focusY)?.let { renderer.selectedKey = it.placement.key }
                return renderer.selectedKey != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val key = renderer.selectedKey ?: return false
                room.resize(key, detector.scaleFactor)
                return true
            }
        })
    }

    private fun pageAt(x: Float, y: Float) = renderer.ray(x, y, glView.width, glView.height)?.let { (o, d) -> renderer.pageAt(o, d) }

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var liveTarget: ArPagePanel? = null
    private var liveDown = 0L

    private fun onRoomTouch(e: MotionEvent): Boolean {
        if (!editing && PrismSettings.getArInteractLive()) return liveTouch(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis(); downX = e.x; downY = e.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaler.isInProgress && (kotlin.math.abs(e.x - downX) + kotlin.math.abs(e.y - downY)) > dp(12f)) {
                    moved = true
                    // Dragging the selected page moves it across the room.
                    val key = renderer.selectedKey
                    if (editing && key != null) {
                        surfaceHit(e.x, e.y)?.let { hit -> renderer.panels[key]?.let { room.place(key, hit, renderer.cameraPosition, it.aspect) } }
                    }
                }
                if (e.pointerCount > 1) moved = true
            }
            MotionEvent.ACTION_UP -> {
                val held = SystemClock.uptimeMillis() - downAt
                if (!moved && !scaler.isInProgress) {
                    val hit = pageAt(e.x, e.y)
                    when {
                        editing && hit != null && held > 600 -> {
                            room.remove(hit.placement.key)
                            renderer.selectedKey = null
                            refreshTray()
                            Toast.makeText(this, "Took ${titleOf(hit.placement.key)} down", Toast.LENGTH_SHORT).show()
                        }
                        editing -> renderer.selectedKey = hit?.placement?.key
                        hit != null -> renderer.panels[hit.placement.key]?.let(::popUp)
                    }
                }
            }
        }
        return true
    }

    /** Interact live: the touch goes to the page it lands on, where it is. */
    private fun liveTouch(e: MotionEvent): Boolean {
        val hit = pageAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                liveTarget = hit?.let { renderer.panels[it.placement.key] }
                liveDown = SystemClock.uptimeMillis()
                if (hit != null) liveTarget?.dispatch(MotionEvent.ACTION_DOWN, hit.x, hit.y, liveDown)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = liveTarget ?: return true
                // Keep following the finger on the page's plane even past its edge, so a scroll
                // that leaves the page still ends cleanly.
                val ray = renderer.ray(e.x, e.y, glView.width, glView.height)
                val placement = renderer.drawnPlacement(target.key)
                val onPage = if (ray != null && placement != null) ArHome.hit(placement, ray.first, ray.second, margin = 3f) else null
                if (onPage != null) target.dispatch(e.actionMasked, onPage.x, onPage.y, liveDown)
                else if (e.actionMasked != MotionEvent.ACTION_MOVE) target.dispatch(MotionEvent.ACTION_CANCEL, 0f, 0f, liveDown)
                if (e.actionMasked != MotionEvent.ACTION_MOVE) liveTarget = null
            }
        }
        return true
    }

    // ── The movable popup ─────────────────────────────────────────────────

    private fun popUp(panel: ArPagePanel) {
        if (popped != null) closePopup()
        val view = panel.detachView() ?: return
        popped = panel
        popupLayer.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(IosUi.cardBackground(this@ArHomeActivity)); cornerRadius = dp(16f).toFloat() }
            clipToOutline = true
            elevation = dp(12f).toFloat()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(8f), dp(10f))
        }
        bar.addView(TextView(this).apply {
            text = panel.title
            textSize = 15f
            setTextColor(IosUi.label(this@ArHomeActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(IosUi.secondaryLabel(this@ArHomeActivity))
            setPadding(dp(12f), 0, dp(8f), 0)
            setOnClickListener { closePopup() }
        })
        card.addView(bar)
        card.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val w = (glView.width * 0.92f).roundToInt()
        val h = (glView.height * 0.74f).roundToInt()
        popupLayer.addView(card, FrameLayout.LayoutParams(w, h).apply {
            leftMargin = (glView.width - w) / 2
            topMargin = (glView.height - h) / 2
        })
        // Moved by its title bar.
        var lastX = 0f
        var lastY = 0f
        bar.setOnTouchListener { _, e ->
            val lp = card.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (lp.leftMargin + (e.rawX - lastX)).roundToInt().coerceIn(-w / 2, glView.width - w / 2)
                    lp.topMargin = (lp.topMargin + (e.rawY - lastY)).roundToInt().coerceIn(0, glView.height - dp(48f))
                    lastX = e.rawX; lastY = e.rawY
                    card.layoutParams = lp
                }
            }
            true
        }
        popupLayer.visibility = View.VISIBLE
        hint.text = panel.title
    }

    private fun closePopup() {
        val panel = popped ?: return
        popupLayer.removeAllViews()
        popupLayer.visibility = View.GONE
        panel.reattachView()
        popped = null
    }
}
