package com.prism.launcher.minigames

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GeoTag: live AR, real surfaces, artwork.
 *
 * ## The interaction
 *
 * The camera starts in a scanning state. Tapping a detected wall, floor or ceiling asks ARCore for
 * a plane hit and creates an anchor there; that opens the Library / Shop / Places popup. Picking a
 * piece in the Library then attaches the artwork to that anchor. Nothing is placed on a screen
 * coordinate any more, so walking around the surface keeps the picture where it belongs.
 *
 * ## The shop draws its art
 *
 * One hundred pieces cannot be a hundred bundled PNGs in a launcher. Each shop piece is a seed and
 * [ArtworkView] paints it at the size of the cell, so it is sharp on every screen and costs the APK
 * nothing. Imported PNGs are drawn from their real file.
 */
class GeoTagActivity : PrismBaseActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GeoTagArRenderer
    private lateinit var panel: LinearLayout
    private lateinit var xpLabel: TextView
    private lateinit var scanHint: TextView
    private lateinit var statusChip: TextView
    private var permissionChip: TextView? = null

    private var session: Session? = null
    private var sessionRunning = false
    private var resumed = false
    private var cameraAsked = false
    private var locationAsked = false
    private var installRequested = false
    private var availabilityTries = 0

    private var selected: GeoTagStore.Artwork? = null
    private var tab = TAB_LIBRARY

    private var pendingAnchor: Anchor? = null
    private var pendingSurface: GeoTagStore.Surface? = null
    private var pendingBasis: FloatArray? = null
    private var pendingPlane: GeoTagPlane? = null
    private var pendingX = 0.5f
    private var pendingY = 0.5f
    private var lastPlacementAt = 0L

    private val importArt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionChip?.visibility = View.GONE
            startAr()
            askForLocation()
        } else if (permissionChip != null) {
            permissionChip?.text = "Camera permission is needed. Tap to grant."
            permissionChip?.visibility = View.VISIBLE
            scanHint.visibility = View.GONE
        } else {
            showArMessage("GeoTag needs the camera to find surfaces. Tap here to grant it.")
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A fix is not required to place on a surface; it is what puts the tag on the Map.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        renderer.onSurfacePicked = { hit -> runOnUiThread { surfacePicked(hit) } }
        renderer.onSurfaceMissing = {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "No surface there yet. Move the phone slowly and tap the wall, floor or ceiling.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        renderer.onPlanesChanged = { counts -> runOnUiThread { updateScanHint(counts) } }
        renderer.onError = { message ->
            runOnUiThread {
                runCatching { session?.pause() }
                sessionRunning = false
                showArMessage(message)
            }
        }

        GeoTagNet.onReceived = {
            runOnUiThread {
                xpLabel.text = "${GeoTagStore.xp()} XP"
                if (panel.visibility == View.VISIBLE) renderPanel()
            }
        }
        xpLabel.text = "${GeoTagStore.xp()} XP"
        renderPanel()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
        when {
            hasCamera() -> {
                permissionChip?.visibility = View.GONE
                startAr()
                askForLocation()
            }
            !cameraAsked -> {
                cameraAsked = true
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onPause() {
        resumed = false
        renderer.paused = true
        renderer.session?.let {
            runCatching { it.pause() }
            sessionRunning = false
        }
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        GeoTagNet.onReceived = null
        renderer.onSurfacePicked = null
        renderer.onSurfaceMissing = null
        renderer.onPlanesChanged = null
        renderer.onError = null
        pendingAnchor?.let { renderer.releaseAnchor(it) }
        pendingAnchor = null
        pendingPlane = null
        session?.let { runCatching { it.close() } }
        session = null
        sessionRunning = false
        renderer.session = null
        super.onDestroy()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun displayRotation(): Int = runCatching {
        windowManager.defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        renderer = GeoTagArRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isClickable = true
            setOnTouchListener { _, event -> handleArTouch(event) }
        }
        root.addView(glView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        scanHint = TextView(this).apply {
            text = "Scanning for walls, floors and ceilings…\nMove the phone slowly."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.argb(190, 10, 12, 18))
                setStroke(dp(1f), Color.argb(90, 255, 255, 255))
            }
        }
        root.addView(scanHint, FrameLayout.LayoutParams(
            dp(300f), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(108f) })

        statusChip = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.argb(225, 12, 14, 22))
                setStroke(dp(1f), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener {
                if (!hasCamera()) {
                    cameraAsked = true
                    cameraPermission.launch(Manifest.permission.CAMERA)
                    return@setOnClickListener
                }
                installRequested = false
                availabilityTries = 0
                cameraAsked = false
                startAr()
            }
        }
        root.addView(statusChip, FrameLayout.LayoutParams(
            dp(300f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.argb(210, 8, 8, 12), Color.argb(90, 8, 8, 12))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(12f), 0)
            setOnClickListener { finish() }
        })
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@GeoTagActivity).apply {
                text = "GeoTag"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@GeoTagActivity).apply {
                text = "Tap a wall, floor or ceiling to open your tags"
                textSize = 11f
                setTextColor(Color.parseColor("#BBFFFFFF"))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        xpLabel = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#FFD76B"))
        }
        top.addView(xpLabel)
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
        ))

        if (!hasCamera()) {
            val chip = TextView(this).apply {
                text = "Tap to enable the camera"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(130, 0, 0, 0))
                setOnClickListener {
                    cameraAsked = true
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            permissionChip = chip
            root.addView(chip, FrameLayout.LayoutParams(dp(220f), dp(54f), Gravity.CENTER))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // The sheet floats over the GLSurfaceView. Without consuming touches in its empty
            // areas, a tap on the panel background fell through and started a new AR surface hit,
            // which replaced the anchor the panel was about to place on.
            isClickable = true
            setOnTouchListener { _, _ -> true }
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#F20C0C12"))
                setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        }
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(320f),
            Gravity.BOTTOM,
        ).apply { leftMargin = dp(8f); rightMargin = dp(8f); bottomMargin = dp(8f) })

        return root
    }

    private fun handleArTouch(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_UP) {
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDownX = event.x
            touchDownY = event.y
            return true
        }
        if (abs(event.x - touchDownX) > dp(14f) || abs(event.y - touchDownY) > dp(14f)) return true
        if (session == null) {
            showArMessage("AR is not running yet. Tap here to start it.")
            return true
        }
        renderer.requestHit(event.x, event.y)
        return true
    }

    private var touchDownX = 0f
    private var touchDownY = 0f

    /**
     * Asks ARCore whether this phone can run it, and installs the Play Services for AR runtime if
     * it is missing. A device without AR gets an explanation rather than a fake camera placement.
     */
    private fun startAr() {
        if (!resumed || !hasCamera()) return
        if (session != null) {
            renderer.paused = false
            renderer.displayRotation = displayRotation()
            if (!sessionRunning) {
                runCatching { session?.resume() }
                    .onSuccess { sessionRunning = true }
                    .onFailure { showArMessage("The AR camera did not resume: ${it.message}") }
            }
            if (sessionRunning) {
                statusChip.visibility = View.GONE
                scanHint.visibility = View.VISIBLE
            }
            return
        }

        val availability = ArCoreApk.getInstance().checkAvailability(this)
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                availabilityTries = 0
                createSession()
            }
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            -> {
                availabilityTries = 0
                if (installRequested) {
                    showArMessage("Google Play Services for AR is still installing. Tap here when it finishes.")
                    return
                }
                installRequested = true
                runCatching { ArCoreApk.getInstance().requestInstall(this, true) }
                    .onSuccess { status ->
                        if (status == ArCoreApk.InstallStatus.INSTALLED) createSession()
                        else showArMessage("ARCore is installing. Return to GeoTag when it finishes.")
                    }
                    .onFailure { showArMessage("ARCore could not be installed: ${it.message}") }
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                showArMessage("This device cannot run ARCore, so GeoTag cannot detect physical surfaces here.")
            }
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            -> {
                if (availabilityTries++ < 8) {
                    showArMessage("Checking whether this device supports AR…")
                    scanHint.postDelayed({ startAr() }, 600)
                } else {
                    showArMessage("ARCore did not answer. Check that Google Play Services for AR is installed.")
                }
            }
            else -> showArMessage("ARCore is unavailable on this device: $availability")
        }
    }

    private fun createSession() {
        if (session != null) return
        val created = runCatching { Session(this) }.getOrElse {
            showArMessage("The AR session could not start: ${it.message}")
            return
        }
        runCatching {
            created.configure(
                Config(created).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                },
            )
        }.onFailure {
            created.close()
            showArMessage("ARCore rejected the session settings: ${it.message}")
            return
        }

        session = created
        renderer.session = created
        renderer.displayRotation = displayRotation()
        renderer.paused = false
        statusChip.visibility = View.GONE
        scanHint.visibility = View.VISIBLE
        scanHint.text = "Scanning for walls, floors and ceilings…\nMove the phone slowly."
        if (!resumed) return
        runCatching { created.resume() }
            .onSuccess { sessionRunning = true }
            .onFailure {
                session = null
                sessionRunning = false
                renderer.session = null
                created.close()
                val reason = if (it is CameraNotAvailableException) {
                    "The AR camera could not start. Close other camera apps and tap here."
                } else {
                    "ARCore could not start on this device: ${it.message}"
                }
                showArMessage(reason)
            }
    }

    private fun showArMessage(message: String) {
        statusChip.text = message
        statusChip.visibility = View.VISIBLE
        scanHint.visibility = View.GONE
    }

    /**
     * The wall key is rounded coordinates, so without a fix every tag in the world would share one
     * wall. Asking here, once, is cheaper than silently degrading the only rule the game has.
     */
    private fun askForLocation() {
        if (locationAsked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            locationAsked = true
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun surfacePicked(hit: GeoTagSurfaceHit) {
        if (!resumed) {
            renderer.releaseAnchor(hit.anchor)
            return
        }
        pendingAnchor?.let { renderer.releaseAnchor(it) }
        pendingAnchor = hit.anchor
        pendingSurface = hit.surface
        pendingBasis = hit.basis
        pendingPlane = hit.plane
        pendingX = hit.screenX
        pendingY = hit.screenY
        tab = TAB_LIBRARY
        panel.visibility = View.VISIBLE
        scanHint.visibility = View.GONE
        renderPanel()
        PrismPlatform.log.info(
            TAG,
            "Surface picked: ${hit.surface.key} at ${hit.screenX},${hit.screenY}",
        )
        Toast.makeText(
            this,
            "${hit.surface.label} found. Tap a piece to place it.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateScanHint(counts: GeoTagPlaneCounts) {
        if (panel.visibility == View.VISIBLE || statusChip.visibility == View.VISIBLE) return
        scanHint.visibility = View.VISIBLE
        scanHint.text = if (counts.total == 0) {
            "Scanning for walls, floors and ceilings…\nMove the phone slowly."
        } else {
            "Walls ${counts.walls} · Floors ${counts.floors} · Ceilings ${counts.ceilings}\nTap a surface to open your tags."
        }
    }

    private fun renderPanel() {
        panel.removeAllViews()
        xpLabel.text = "${GeoTagStore.xp()} XP"

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        head.addView(TextView(this).apply {
            text = (pendingSurface?.label ?: "Surface").uppercase() + " SELECTED"
            textSize = 10f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#99FFFFFF"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setPadding(dp(10f), dp(2f), dp(4f), dp(2f))
            setOnClickListener { dismissPanel() }
        })
        panel.addView(head)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(4f))
        }
        listOf(TAB_LIBRARY to "Library", TAB_SHOP to "Shop", TAB_PLACES to "Places").forEach { (id, label) ->
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                setTextColor(if (tab == id) Color.parseColor("#0B0B10") else Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    if (tab == id) setColor(IosUi.accent(this@GeoTagActivity))
                }
                setOnClickListener { tab = id; renderPanel() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(tabs)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        when (tab) {
            TAB_LIBRARY -> renderLibrary(body)
            TAB_SHOP -> renderShop(body)
            else -> renderPlaces(body)
        }
    }

    private fun renderLibrary(body: LinearLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "YOUR ARTWORK"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#99FFFFFF"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "＋ Import PNG"
            textSize = 12f
            setTextColor(IosUi.accent(this@GeoTagActivity))
            setOnClickListener { importArt.launch(arrayOf("image/png", "image/*")) }
        })
        body.addView(header)

        pendingSurface?.let { surface ->
            body.addView(TextView(this).apply {
                text = "Tap a piece to place it on this ${surface.label.lowercase()}."
                textSize = 11f
                setTextColor(Color.parseColor("#99FFFFFF"))
                setPadding(0, dp(4f), 0, dp(2f))
            })
        }

        val grid = grid()
        val items = GeoTagStore.library()
        if (items.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "No artwork yet. Import a PNG to start."
                textSize = 12f
                setTextColor(Color.parseColor("#99FFFFFF"))
                setPadding(0, dp(12f), 0, 0)
            })
        }
        for (art in items) {
            grid.addView(artCell(art), cellParams())
        }
        body.addView(grid)
    }

    private fun renderShop(body: LinearLayout) {
        body.addView(TextView(this).apply {
            text = "BUY WITH XP · ${GeoTagStore.shop().size} PIECES"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#99FFFFFF"))
            setPadding(0, dp(6f), 0, dp(4f))
        })
        val grid = grid()
        for (art in GeoTagStore.shop()) {
            val cell = artCell(art)
            cell.addView(TextView(this).apply {
                text = if (art.owned) "OWNED" else "${art.price} XP"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (art.owned) Color.parseColor("#8BE38B") else Color.parseColor("#FFD76B"))
            })
            grid.addView(cell, cellParams())
        }
        body.addView(grid)
    }

    private fun renderPlaces(body: LinearLayout) {
        val places = GeoTagStore.places()
        body.addView(TextView(this).apply {
            text = "PLACES YOU HAVE TAGGED · ${places.size}"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#99FFFFFF"))
            setPadding(0, dp(6f), 0, dp(4f))
        })
        if (places.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "No places yet. Tap a surface, pick a piece, and place it."
                textSize = 12f
                setTextColor(Color.parseColor("#99FFFFFF"))
            })
        }
        for (tag in places) {
            val art = GeoTagStore.artwork(tag.artId)
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, dp(6f))
                addView(ArtworkView(this@GeoTagActivity, artId = tag.artId, size = dp(46f)),
                    LinearLayout.LayoutParams(dp(46f), dp(46f)))
                addView(LinearLayout(this@GeoTagActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10f), 0, 0, 0)
                    addView(TextView(this@GeoTagActivity).apply {
                        text = art?.title ?: "Shared piece"
                        textSize = 13f
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(this@GeoTagActivity).apply {
                        text = "${tag.author} · ${tag.surface.label} · " +
                            String.format(java.util.Locale.US, "%.4f, %.4f", tag.lat, tag.lng)
                        textSize = 10f
                        setTextColor(Color.parseColor("#99FFFFFF"))
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun grid(): GridLayout = GridLayout(this).apply {
        columnCount = 4
        setPadding(0, dp(6f), 0, 0)
    }

    private fun cellParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = dp(74f)
        height = dp(92f)
        setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
    }

    private fun artCell(art: GeoTagStore.Artwork): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(if (art.id == selected?.id) Color.argb(70, 255, 255, 255) else Color.argb(25, 255, 255, 255))
            if (art.id == selected?.id) setStroke(dp(2f), IosUi.accent(this@GeoTagActivity))
        }
        addView(ArtworkView(this@GeoTagActivity, art = art, size = dp(66f)))
        addView(TextView(this@GeoTagActivity).apply {
            text = art.title.take(12)
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        setOnClickListener {
            if (!art.owned) {
                val message = GeoTagStore.buy(art.id)
                Toast.makeText(this@GeoTagActivity, message, Toast.LENGTH_SHORT).show()
                // Buying and placing are one decision: if the purchase succeeded, this same tap
                // applies the piece to the surface the player picked instead of making them tap
                // the same tile a second time.
                val bought = GeoTagStore.artwork(art.id)
                if (bought?.owned != true) {
                    renderPanel()
                    return@setOnClickListener
                }
                selected = bought
                placeOnSelectedSurface(bought)
            } else {
                selected = art
                placeOnSelectedSurface(art)
            }
        }
        setOnLongClickListener {
            if (art.importedPath.isBlank()) return@setOnLongClickListener false
            android.app.AlertDialog.Builder(this@GeoTagActivity)
                .setItems(arrayOf("Delete", "Sell to shop (120 XP)")) { _, which ->
                    val message = if (which == 0) {
                        if (GeoTagStore.delete(art.id)) "Artwork deleted." else "Could not delete it."
                    } else {
                        GeoTagStore.sell(art.id, 120)
                    }
                    Toast.makeText(this@GeoTagActivity, message, Toast.LENGTH_SHORT).show()
                    renderPanel()
                }
                .show()
            true
        }
    }

    /** Applies an artwork to the surface that opened the sheet, or explains why it cannot. */
    private fun placeOnSelectedSurface(art: GeoTagStore.Artwork) {
        val anchor = pendingAnchor
        val surface = pendingSurface
        val basis = pendingBasis
        if (anchor == null || surface == null || basis == null) {
            Toast.makeText(
                this,
                "Tap a wall, floor or ceiling first.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        placeSelected(art, anchor, surface, basis, pendingPlane)
    }

    private fun placeSelected(
        art: GeoTagStore.Artwork,
        anchor: Anchor,
        surface: GeoTagStore.Surface,
        basis: FloatArray,
        plane: GeoTagPlane?,
    ) {
        // A tap that lands while the panel is rebuilding used to reach this twice.
        val now = System.currentTimeMillis()
        if (now - lastPlacementAt < 600L) return
        lastPlacementAt = now

        val (lat, lng) = lastLocation()
        val tag = GeoTagStore.place(
            artId = art.id,
            x = pendingX,
            y = pendingY,
            lat = lat,
            lng = lng,
            author = PrismSettings.getLykeUserName(),
            surface = surface,
        )
        if (tag == null) {
            Toast.makeText(this, "That artwork is not in your library.", Toast.LENGTH_SHORT).show()
            return
        }

        panel.visibility = View.GONE
        scanHint.visibility = View.VISIBLE
        renderer.place(art.id, anchor, surface, basis, plane)
        GeoTagNet.announce(tag, art)
        PrismPlatform.log.info(TAG, "Placed ${art.id} on ${surface.key}")
        pendingAnchor = null
        pendingSurface = null
        pendingBasis = null
        pendingPlane = null
        xpLabel.text = "${GeoTagStore.xp()} XP"
        Toast.makeText(
            this,
            "${art.title} tagged on the ${surface.label.lowercase()}. +12 XP",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Closes the surface sheet. The anchor was only borrowed while the player chose a piece; if
     * they back out, releasing it keeps an invisible, tracked pose from accumulating in ARCore.
     */
    private fun dismissPanel() {
        panel.visibility = View.GONE
        scanHint.visibility = View.VISIBLE
        pendingAnchor?.let { renderer.releaseAnchor(it) }
        pendingAnchor = null
        pendingSurface = null
        pendingBasis = null
        pendingPlane = null
        selected = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) {
            dismissPanel()
            return
        }
        super.onBackPressed()
    }

    private fun lastLocation(): Pair<Double, Double> {
        val location = runCatching {
            val manager = getSystemService(LocationManager::class.java)
            manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return (location?.latitude ?: 0.0) to (location?.longitude ?: 0.0)
    }

    private fun importFrom(uri: Uri) {
        val temp = File(cacheDir, "geotag-import-${System.currentTimeMillis()}.png")
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!copied) {
            Toast.makeText(this, "That file could not be read.", Toast.LENGTH_SHORT).show()
            return
        }
        val name = queryName(uri) ?: "My artwork"
        val art = GeoTagStore.importPng(temp, name)
        temp.delete()
        if (art == null) {
            Toast.makeText(this, "The PNG could not be imported.", Toast.LENGTH_SHORT).show()
        } else {
            selected = art
            tab = TAB_LIBRARY
            renderPanel()
            Toast.makeText(this, "Imported ${art.title}. Tap a surface, then tap the piece.", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.substringBeforeLast('.') else null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "PrismGeoTag"
        private const val TAB_LIBRARY = 0
        private const val TAB_SHOP = 1
        private const val TAB_PLACES = 2

        fun intent(context: android.content.Context): Intent =
            Intent(context, GeoTagActivity::class.java)
    }
}

/**
 * Paints one artwork. The seed drives every choice, so the same piece is identical on every device
 * and after a restart, and a shared tag can be drawn from its id alone.
 */
class ArtworkView(
    context: android.content.Context,
    private val art: GeoTagStore.Artwork? = null,
    private val artId: String = art?.id.orEmpty(),
    private val size: Int = 120,
) : View(context) {

    private var bitmap: Bitmap? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: bitmapFor(context, art ?: GeoTagStore.artwork(artId), size)
            .also { bitmap = it }
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    companion object {
        // The panel asks for these on the UI thread while the AR renderer asks on its GL thread.
        // A plain HashMap here was a real data race once GeoTag started rendering artwork in 3D.
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

        fun bitmapFor(context: android.content.Context, art: GeoTagStore.Artwork?, size: Int): Bitmap {
            val key = (art?.id ?: "none") + "@" + size
            cache[key]?.let { return it }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (art?.importedPath?.isNotBlank() == true) {
                val decoded = runCatching { BitmapFactory.decodeFile(art.importedPath) }.getOrNull()
                if (decoded != null) {
                    canvas.drawBitmap(
                        decoded, null,
                        RectF(0f, 0f, size.toFloat(), size.toFloat()),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    cache[key] = bitmap
                    return bitmap
                }
            }
            val random = java.util.Random(art?.seed ?: 7L)
            val hue = random.nextInt(360).toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.85f)),
                Color.HSVToColor(floatArrayOf((hue + 70f) % 360f, 0.82f, 0.32f)),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null
            repeat(9) {
                paint.color = Color.HSVToColor(
                    floatArrayOf((hue + random.nextInt(120)) % 360f, 0.65f, 0.95f)
                ).let { c -> Color.argb(70 + random.nextInt(120), Color.red(c), Color.green(c), Color.blue(c)) }
                when (random.nextInt(3)) {
                    0 -> canvas.drawCircle(
                        random.nextFloat() * size, random.nextFloat() * size,
                        size * (0.05f + random.nextFloat() * 0.22f), paint,
                    )
                    1 -> canvas.drawRect(
                        random.nextFloat() * size * 0.6f, random.nextFloat() * size * 0.6f,
                        random.nextFloat() * size, random.nextFloat() * size, paint,
                    )
                    else -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = size * (0.01f + random.nextFloat() * 0.03f)
                        canvas.drawCircle(size / 2f, size / 2f, size * (0.15f + random.nextFloat() * 0.35f), paint)
                        paint.style = Paint.Style.FILL
                    }
                }
            }
            cache[key] = bitmap
            return bitmap
        }
    }
}
