package com.prism.launcher.mouse

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.core.MeshMembership
import com.prism.launcher.PrismSettings
import com.prism.launcher.mouse.MouseGlow
import com.prism.launcher.nora.IosUi
import com.prism.launcher.trusted.RemotePointer
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedDevicesActivity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pointer for another machine.
 *
 * ## A mouse, not a remote
 *
 * The controls are one drawn mouse: two buttons, a wheel and a shell. There are no L and R labels
 * because the shape says which is which, and because a remote with three pads is a different
 * object. Above it is the trackpad -- a finger is still the most precise way to move a cursor --
 * and the mouse body below is where the buttons and wheel actually live.
 *
 * ## Movement goes out as it happens
 *
 * Two sources feed one cursor. A drag contributes a displacement; the gravity sensor contributes
 * the change in the phone's tilt. Both move the cursor immediately and send it, rate-limited only
 * to eight milliseconds so a 120 Hz panel cannot outrun the receiver. An earlier version smoothed
 * movement through a frame-drained spring; that made the cursor trail the finger and keep drifting
 * after it stopped, which is exactly the lag a pointer must not have.
 *
 * ## It only listens to tilt while lying flat
 *
 * A phone picked up and waved around changes orientation constantly. Treating that as mouse input
 * is why the old page moved while somebody was simply holding it. The gravity vector must be close
 * to the table normal before tilt contributes anything, linear acceleration must be quiet, and the
 * gate stays closed until the device has been still for a moment. Lifting it closes the gate and
 * discards the accumulated tilt, so putting it down does not teleport the cursor.
 *
 * ## The trackpad is always available
 *
 * Gating a deliberate finger drag on the phone's orientation would make the page unusable in the
 * hand. Only the motion gesture is gated; the pad is the input that always works.
 */
class MousePageView(context: Context) : LinearLayout(context) {

    private val titleRow = LinearLayout(context)
    private val status = TextView(context)
    private val deviceRow = LinearLayout(context)
    private val trackpad = TrackpadView(context)
    private val mouseView = FlatMouseView(context)
    private val empty = LinearLayout(context)

    private var target: TrustedDevices.Trust? = null

    /** True when the user chose the glowing touchpad instead of the mouse body. */
    private var touchpadMode = false

    /** One transport question per visit. Re-entering the page asks again. */
    private var askedTransport = false

    private val dark = IosUi.isDark(context)
    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private var gravitySensor: Sensor? = null
    private var linearSensor: Sensor? = null
    private var usingRawAccelerometer = false

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    private var filteredX = 0f
    private var filteredY = 0f
    private var lastFilteredX = Float.NaN
    private var lastFilteredY = Float.NaN
    private var flat = false
    private var flatSince = 0L
    private var lastMotionAt = 0L

    private var cursorX = 0.5f
    private var cursorY = 0.5f
    private var sentX = 0.5f
    private var sentY = 0.5f

    /**
     * The newest input goes out immediately.
     *
     * A drag used to feed a pending pool that a frame loop drained through a 12 ms spring. That
     * smoothing turned every finger movement into a cursor that arrived late and kept travelling
     * after the finger stopped -- over Wi-Fi the delay was obvious, and the spring only added to
     * it. Direct positioning is what a pointer device is supposed to feel like: the cursor is
     * where the finger is, and the network is the only latency left.
     */
    private var lastMoveAt = 0L

    /**
     * At most one move per this many milliseconds.
     *
     * A 120 Hz panel produces more touch samples than a receiver can usefully redraw, and sending
     * each one would spend the network on positions nobody sees. Eight milliseconds is still
     * faster than any display refresh, so no visible frame is skipped.
     */
    private val moveIntervalMs = 8L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isShown || !hasWindowFocus()) return
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    if (usingRawAccelerometer) {
                        // An accelerometer includes the hand's own acceleration. The low-pass is
                        // what turns it into the gravity vector a tilt gesture needs.
                        gravityX += (x - gravityX) * 0.12f
                        gravityY += (y - gravityY) * 0.12f
                        gravityZ += (z - gravityZ) * 0.12f
                    } else {
                        gravityX = x
                        gravityY = y
                        gravityZ = z
                    }
                    updateFlatGate()
                    if (flat) accumulateTilt()
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val magnitude = sqrt(
                        event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2],
                    )
                    // A hand holding the phone is never this still; a phone on a table is. This is
                    // the second signal that closes the gate even when the screen still faces up.
                    if (magnitude > 2.2f) lastMotionAt = SystemClock.elapsedRealtime()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        orientation = VERTICAL
        setPadding(dp(16f), dp(12f), dp(16f), dp(10f))
        setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)

        titleRow.orientation = HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(TextView(context).apply {
            text = "Mouse"
            textSize = 24f
            setTextColor(IosUi.label(context))
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })
        titleRow.addView(View(context), LayoutParams(0, 1, 1f))
        titleRow.addView(pill("Recentre") {
            cursorX = 0.5f
            cursorY = 0.5f
            sentX = 0.5f
            sentY = 0.5f
            lastFilteredX = Float.NaN
            lastFilteredY = Float.NaN
            target?.let {
                RemotePointer.send(it, RemotePointer.Event(RemotePointer.Event.Kind.MOVE, 0.5f, 0.5f))
            }
            trackpad.invalidate()
            status.text = "Centred. Drag the pad, or tilt the phone while it lies flat."
        })
        addView(titleRow)

        status.textSize = 12f
        status.setTextColor(IosUi.secondaryLabel(context))
        status.setPadding(0, dp(5f), 0, dp(10f))
        addView(status)

        deviceRow.orientation = HORIZONTAL
        addView(deviceRow)

        // BOTH FILL THE PAGE, and only one is visible at a time. The old layout gave the pad a
        // fixed band and the mouse a fixed 268dp, which left most of a tablet or a tall phone
        // showing nothing but backdrop. The header below is the only thing above them.
        trackpad.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8f)
            bottomMargin = dp(6f)
        }
        addView(trackpad)

        // The mouse gets the larger share, and both stretch: a 268dp slice of a tall screen is a
        // control buried in empty space, which is what "the mouse should take up the whole view"
        // was asking to fix.
        mouseView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.55f).apply {
            topMargin = dp(6f)
            bottomMargin = dp(4f)
        }
        addView(mouseView)

        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER
        empty.visibility = View.GONE
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(TextView(context).apply {
            text = "Drag the pad to move. Tilt only works while the phone is lying flat. " +
                "Nothing is sent while this page is closed."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(4f), 0, 0)
            gravity = Gravity.CENTER
        })

        mouseView.onButtonClick = { right ->
            MouseAudio.tap(context)
            click(if (right) RemotePointer.Button.RIGHT else RemotePointer.Button.LEFT)
        }
        mouseView.onScrollNotch = { direction -> MouseAudio.tick(context, direction) }
        mouseView.onScroll = { notches -> scroll(notches) }

        loadSensors()
        refresh()
    }

    private fun loadSensors() {
        val manager = sensorManager
        gravitySensor = runCatching { manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) }.getOrNull()
        linearSensor = runCatching {
            manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        }.getOrNull()
        usingRawAccelerometer = gravitySensor == null
        if (gravitySensor == null) {
            gravitySensor = runCatching {
                manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            }.getOrNull()
        }
    }

    override fun onDetachedFromWindow() {
        askedTransport = false
        sensorManager?.unregisterListener(sensorListener)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        gravitySensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (!usingRawAccelerometer) {
            linearSensor?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        refresh()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            flat = false
            lastFilteredX = Float.NaN
            lastFilteredY = Float.NaN
        } else {
            // Coming back from Settings with a new palette or a flow just saved. The page is
            // attached the whole time, so onAttachedToWindow never runs again and the mouse
            // would otherwise keep the colours it was built with until Prism restarts.
            refresh()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // The launcher's window keeps this page attached while another Activity -- Settings, the
        // style editor -- is in front. Its window becoming visible again is the reliable signal
        // that the user has come back, even when the page itself never changed visibility.
        if (visibility == VISIBLE) refresh()
    }

    /** Rebuilds the page from the current mesh and pairing state. Cheap: a handful of views. */
    fun refresh() {
        val mesh = runCatching { MeshMembership.isOnMeshnet() }.getOrDefault(false)
        val targets = runCatching { RemotePointer.targets() }.getOrDefault(emptyList())
        val saved = PrismSettings.getPointerTargetFingerprint()
        target = targets.firstOrNull { it.fingerprint == saved }
            ?: targets.firstOrNull { it.online }
            ?: targets.firstOrNull()

        mouseView.setGlow(MouseGlow.selectedStyle(), MouseGlow.selectedFlow())
        touchpadMode = PrismSettings.getMouseTouchpad()
        trackpad.glow = touchpadMode

        deviceRow.removeAllViews()
        empty.removeAllViews()

        val ready = mesh && target != null
        trackpad.visibility = if (ready && touchpadMode) View.VISIBLE else View.GONE
        mouseView.visibility = if (ready && !touchpadMode) View.VISIBLE else View.GONE
        empty.visibility = if (ready) View.GONE else View.VISIBLE

        if (!ready) {
            titleRow.visibility = View.VISIBLE
            deviceRow.visibility = View.GONE
            status.text = if (!mesh) {
                "Not on a meshnet."
            } else {
                "No trusted device yet."
            }

            empty.addView(TextView(context).apply {
                text = if (!mesh) {
                    "The Mouse page drives another machine over the meshnet. Join or serve one, " +
                        "then pair the device you want to point at."
                } else {
                    "Pair the device you want to control. Both devices need Prism open, and the " +
                        "target must be a desktop to receive the pointer."
                }
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(12f), dp(12f), dp(18f))
            })
            empty.addView(button("Open trusted devices") {
                context.startActivity(Intent(context, TrustedDevicesActivity::class.java))
            })
            return
        }

        val link = if (PrismSettings.getPointerTransport() == PrismSettings.POINTER_TRANSPORT_USB) {
            "USB"
        } else {
            "Wi-Fi"
        }
        status.text = if (target!!.online) {
            "Driving ${target!!.name} · ${target!!.platform.ifBlank { "device" }} · $link"
        } else {
            "${target!!.name} is paired but not answering. Open Prism on it to drive it."
        }

        if (!askedTransport) {
            askedTransport = true
            post { askTransport(target!!) }
        }

        if (targets.size > 1) {
            deviceRow.visibility = View.VISIBLE
            deviceRow.removeAllViews()
            targets.forEach { device ->
                val selected = device.fingerprint == target!!.fingerprint
                deviceRow.addView(pill(
                    (if (device.online) "● " else "○ ") + device.name,
                    selected,
                ) {
                    PrismSettings.setPointerTargetFingerprint(device.fingerprint)
                    refresh()
                }.apply { setPadding(dp(11f), dp(7f), dp(11f), dp(7f)) })
            }
        } else {
            deviceRow.visibility = View.GONE
        }
    }

    /**
     * The transport question, once per visit.
     *
     * Wi-Fi is the default and needs no cable. USB is a real cable link through USB networking:
     * the receiver is reached at its USB address instead of its Wi-Fi one, so a phone with no
     * network in the room can still drive a desktop beside it. Choosing USB is remembered for
     * the target, not just this page.
     */
    private fun askTransport(device: TrustedDevices.Trust) {
        if (!isShown || !isAttachedToWindow) return
        val choices = arrayOf(
            "Control over Wi-Fi",
            "Control over USB (cable)",
        )
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Control " + device.name)
            .setItems(choices) { _, which ->
                val usb = which == 1
                PrismSettings.setPointerTransport(
                    if (usb) PrismSettings.POINTER_TRANSPORT_USB
                    else PrismSettings.POINTER_TRANSPORT_WIFI,
                )
                Toast.makeText(
                    context,
                    if (usb) {
                        "USB selected. Connect the cable and turn on USB tethering on the phone."
                    } else {
                        "Using Wi-Fi."
                    },
                    Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
            .setNegativeButton("Keep Wi-Fi", null)
            .show()
    }

    // ── Motion ─────────────────────────────────────────────────────────────

    /**
     * Decides whether the phone is on a surface rather than in a hand.
     *
     * The Z test is "screen is roughly up", not "screen is perfectly level": a hand on a table
     * still holds the phone at a small angle. The X/Y tests stop a phone held at 45 degrees from
     * being treated as flat, and the settle window keeps the act of putting it down from moving
     * the cursor.
     */
    private fun updateFlatGate() {
        val now = SystemClock.elapsedRealtime()
        val still = now - lastMotionAt > 320L
        val wasFlat = flat
        flat = gravityZ > 6.2f &&
            abs(gravityX) < 5.4f &&
            abs(gravityY) < 5.4f &&
            still

        if (flat && !wasFlat) {
            flatSince = now
            filteredX = gravityX
            filteredY = gravityY
            lastFilteredX = Float.NaN
            lastFilteredY = Float.NaN
        }
        if (!flat) {
            lastFilteredX = Float.NaN
            lastFilteredY = Float.NaN
        }
    }

    /**
     * Adds the CHANGE in tilt, not the tilt itself.
     *
     * This is the difference between a mouse and a joystick. A held angle should stop the cursor,
     * because the phone has stopped moving; only a change in angle is a movement. [lastFiltered]
     * makes that literal, and the low-pass makes the change smooth.
     */
    private fun accumulateTilt() {
        if (SystemClock.elapsedRealtime() - flatSince < 260L) return
        filteredX = filteredX * 0.78f + gravityX * 0.22f
        filteredY = filteredY * 0.78f + gravityY * 0.22f
        if (!lastFilteredX.isFinite()) {
            lastFilteredX = filteredX
            lastFilteredY = filteredY
            return
        }
        val dx = filteredX - lastFilteredX
        val dy = filteredY - lastFilteredY
        lastFilteredX = filteredX
        lastFilteredY = filteredY
        if (abs(dx) < 0.035f && abs(dy) < 0.035f) return
        // A few degrees should be a useful nudge, not a screen-crossing jump. The deadzone above
        // removes sensor chatter; the gain below keeps the useful part of the gesture.
        addMovement(dx * 0.055f, dy * 0.055f)
    }

    /**
     * Moves the cursor now, not on the next frame.
     *
     * [immediate] is used for pointer-up and for clicks, which must not wait behind the rate
     * limit: the last position of a drag is the one the user aimed at.
     */
    private fun addMovement(dx: Float, dy: Float, immediate: Boolean = false) {
        cursorX = (cursorX + dx).coerceIn(0f, 1f)
        cursorY = (cursorY + dy).coerceIn(0f, 1f)
        trackpad.invalidate()
        sendMoveIfNeeded(immediate)
    }

    private fun sendMoveIfNeeded(immediate: Boolean = false) {
        val device = target ?: return
        if (!isShown || !hasWindowFocus()) return
        if (abs(cursorX - sentX) < 0.00025f && abs(cursorY - sentY) < 0.00025f) return
        val now = SystemClock.elapsedRealtime()
        if (!immediate && now - lastMoveAt < moveIntervalMs) return
        lastMoveAt = now
        sentX = cursorX
        sentY = cursorY
        RemotePointer.send(device, RemotePointer.Event(RemotePointer.Event.Kind.MOVE, cursorX, cursorY))
    }

    private fun click(button: RemotePointer.Button) {
        val device = target ?: return
        val landed = RemotePointer.send(device, RemotePointer.Event(RemotePointer.Event.Kind.CLICK, button = button))
        if (!landed) status.text = "${device.name} is not answering."
    }

    private fun scroll(notches: Float) {
        val device = target ?: return
        val landed = RemotePointer.send(device, RemotePointer.Event(RemotePointer.Event.Kind.SCROLL, amount = notches))
        if (!landed) status.text = "${device.name} is not answering."
    }

    // ── Small views ────────────────────────────────────────────────────────

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun contrastFill(): Int = if (dark) 0xFFE9E9EE.toInt() else 0xFF1C1C1E.toInt()
    private fun contrastText(): Int = if (dark) 0xFF111114.toInt() else 0xFFF2F2F7.toInt()

    private fun pill(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(if (selected) Color.WHITE else contrastText())
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(if (selected) IosUi.accent(context) else contrastFill())
                setStroke(dp(1f), if (selected) IosUi.accent(context) else contrastText() and 0x33FFFFFF)
            }
            setOnClickListener { onClick() }
        }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(IosUi.accent(context))
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            setOnClickListener { onClick() }
        }

    /**
     * The pad.
     *
     * The card is intentionally the opposite value to the page -- light on black, dark on white --
     * because it is a control surface, and the request is that the children be visible rather than
     * blend into the backdrop. The little arrow is the remote cursor's position, so the pad shows
     * where a drag has put it without needing a second screen.
     */
    private inner class TrackpadView(context: Context) : View(context) {
        private var lastX = 0f
        private var lastY = 0f
        private var tracking = false

        /** Touchpad mode: the pad is the page, and it wears the palette's light. */
        var glow = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = contrastFill() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
            color = if (dark) 0x22000000 else 0x33FFFFFF
        }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = contrastText()
            setShadowLayer(dp(8f).toFloat(), 0f, 0f, Color.WHITE)
        }
        private val arrowPath = Path()
        private val boxPath = Path()
        private val glowFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val glowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val inset = dp(1f).toFloat()
            val box = RectF(inset, inset, width - inset, height - inset)
            val radius = dp(24f).toFloat()

            if (glow) {
                drawGlowPad(canvas, box, radius)
            } else {
                canvas.drawRoundRect(box, radius, radius, fill)
                canvas.drawRoundRect(box, radius, radius, stroke)
                // A quiet ring in the middle is the resting place; the arrow is the live
                // position.
                canvas.drawCircle(width / 2f, height / 2f, dp(3f).toFloat(), stroke)
            }

            val x = (cursorX.coerceIn(0f, 1f) * width)
            val y = (cursorY.coerceIn(0f, 1f) * height)
            val size = dp(9f).toFloat()
            arrowPath.reset()
            arrowPath.moveTo(x, y)
            arrowPath.lineTo(x, y + size * 1.9f)
            arrowPath.lineTo(x + size * 0.55f, y + size * 1.35f)
            arrowPath.lineTo(x + size * 1.35f, y + size * 1.3f)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrow)
        }

        /**
         * The palette, as a surface rather than a border only.
         *
         * The fill is a gradient of colours sampled from the SAME [MouseGlow] program the mouse
         * body uses, and three slow radial blooms move under it. The border is sampled around the
         * rounded rectangle with a [PathMeasure], because a single linear gradient would run left
         * to right and ignore the shape it is supposed to trace.
         */
        private fun drawGlowPad(canvas: Canvas, box: RectF, radius: Float) {
            val now = System.nanoTime() / 1_000_000_000.0
            val style = MouseGlow.selectedStyle()
            val flow = MouseGlow.selectedFlow()
            val phase = (now * 0.06) % 1.0

            val stops = IntArray(4) { index ->
                MouseGlow.sample(style, flow, (phase + index * 0.25) % 1.0, now).color
            }
            glowFill.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                stops.copyOf().also { colors ->
                    for (index in colors.indices) {
                        colors[index] = Color.argb(
                            92,
                            Color.red(colors[index]),
                            Color.green(colors[index]),
                            Color.blue(colors[index]),
                        )
                    }
                },
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(box, radius, radius, glowFill)

            for (index in 0 until 3) {
                val drift = now * (0.13 + index * 0.05) + index * 2.1
                val cx = width * (0.5f + 0.28f * cos(drift.toFloat()))
                val cy = height * (0.5f + 0.3f * sin((drift * 0.8f).toFloat()))
                val bloom = MouseGlow.sample(style, flow, (phase + index * 0.33) % 1.0, now).color
                glowFill.shader = RadialGradient(
                    cx, cy, maxOf(width, height) * 0.42f,
                    Color.argb(76, Color.red(bloom), Color.green(bloom), Color.blue(bloom)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRoundRect(box, radius, radius, glowFill)
            }

            boxPath.reset()
            boxPath.addRoundRect(box, radius, radius, Path.Direction.CW)
            val measure = PathMeasure(boxPath, false)
            val length = measure.length
            if (length > 0f) {
                val steps = 96
                glowHalo.strokeWidth = dp(15f).toFloat()
                glowEdge.strokeWidth = dp(2.6f).toFloat()
                val start = FloatArray(2)
                val end = FloatArray(2)
                for (index in 0 until steps) {
                    val u = index.toDouble() / steps
                    val next = (index + 1).toDouble() / steps
                    val sample = MouseGlow.sample(style, flow, u, now).color
                    measure.getPosTan((u * length).toFloat(), start, null)
                    measure.getPosTan((next * length).toFloat(), end, null)
                    glowHalo.color = Color.argb(
                        44, Color.red(sample), Color.green(sample), Color.blue(sample),
                    )
                    canvas.drawLine(start[0], start[1], end[0], end[1], glowHalo)
                    glowEdge.color = sample
                    canvas.drawLine(start[0], start[1], end[0], end[1], glowEdge)
                }
            }

            // Keep the flow moving. Only while attached: a detached view invalidating itself
            // would keep the launcher awake for a page nobody can see.
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    // The whole displacement since the last sample, applied at once. Historical
                    // points are deliberately NOT replayed: the receiver only needs where the
                    // finger is now, and sending every intermediate point queued positions the
                    // cursor then had to catch up with.
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    addMovement(
                        dx / width.coerceAtLeast(1) * 1.25f,
                        dy / height.coerceAtLeast(1) * 1.25f,
                    )
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // Land the final position even when the last move was rate-limited away.
                    sendMoveIfNeeded(immediate = true)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
