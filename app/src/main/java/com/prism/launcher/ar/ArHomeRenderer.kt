package com.prism.launcher.ar

import android.app.Activity
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.prism.launcher.spatial.ArHome
import com.prism.launcher.spatial.ArRoomSession
import com.prism.launcher.spatial.DepthFrame
import com.prism.launcher.spatial.Intrinsics
import com.prism.launcher.spatial.Pose
import com.prism.launcher.spatial.RoomLayout
import com.prism.launcher.spatial.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws the AR home screen: the camera, the room's surfaces while pages are being placed, and every
 * placed page on its wall, floor or ceiling -- with real objects in front of a page drawn in front
 * of it.
 *
 * ## The room model
 *
 * ARCore's RAW depth (each pixel measured, with a confidence) and ARCore's own planes go into
 * [ArRoomSession.scanner] off the GL thread, and the layout is rebuilt about once a second. Depth
 * pixels become 3D points through the camera TEXTURE intrinsics scaled to the depth image, as
 * ARCore's raw-depth sample does: the depth image is aligned with the camera texture, not with the
 * CPU image. Using the CPU image's intrinsics (a different shape) stretched every point, which is
 * what put walls -- and the pages on them -- tens of centimetres from where they really were.
 *
 * ## Occlusion
 *
 * The smoothed depth image is uploaded as a texture every frame. Each page fragment compares its own
 * distance with the real distance at that pixel; where something real is clearly nearer (a chair,
 * a person, a hand), the page fades out there and the camera image -- the object -- shows through.
 * The page sits on its wall, so the wall itself is never mistaken for an occluder: only what is
 * more than [OCCLUDER_MARGIN] in front of the page counts.
 *
 * ## Pinning
 *
 * Every shown page is held by an ARCore anchor at its centre, so it rides ARCore's corrections as
 * tracking improves -- and stays where it was put however the room model changes.
 */
class ArHomeRenderer(private val activity: Activity, val room: ArRoomSession) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    @Volatile var paused = false
    @Volatile var displayRotation: Int = Surface.ROTATION_0

    /** Draw the room's surfaces: while pages are being placed. */
    @Volatile var showSurfaces = false

    /** The surface under a page being dragged in from the grid. */
    @Volatile var hoverSurface: RoomLayout.Surface? = null

    /** The page being resized or moved. */
    @Volatile var selectedKey: String? = null

    /** Pages by key. */
    val panels = ConcurrentHashMap<String, ArPagePanel>()

    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    private val viewProjection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val inverse = FloatArray(16)
    @Volatile var cameraPosition: Vec3 = Vec3.ZERO
        private set
    @Volatile private var haveCamera = false
    @Volatile var tracking = false
        private set

    /** The pages as last drawn (anchor-corrected), for touches. */
    @Volatile private var drawn: List<ArHome.Placement> = emptyList()

    private var width = 0
    private var height = 0
    private var geometryDirty = true
    private var cameraTexture = 0
    private var depthTexture = 0
    private var depthReady = false
    private var cameraTextureSession: Session? = null
    private var texReady = false

    private var backgroundProgram = 0
    private var colorProgram = 0
    private var pageProgram = 0

    private val quad = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = buffer(FloatArray(8))
    private val quadTexArray = FloatArray(8)
    private val corners = ByteBuffer.allocateDirect(4 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pageTex = buffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private var depthUpload: ByteBuffer? = null

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "prism-ar-scan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val scanBusy = AtomicBoolean(false)
    private var lastDepthAt = 0L
    private var lastPlanesAt = 0L
    private var lastRebuildAt = 0L
    private var depthSupported = true

    private class Pinned(val anchor: Anchor, val center: Vec3)
    private val anchors = HashMap<String, Pinned>()

    /** Where ARCore itself measures the surface under a view pixel; answered on the GL thread. */
    private val measureRequests = ConcurrentLinkedQueue<Triple<Float, Float, (Vec3?) -> Unit>>()

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        runCatching {
            backgroundProgram = program(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
            colorProgram = program(COLOR_VERTEX, COLOR_FRAGMENT)
            pageProgram = program(PAGE_VERTEX, PAGE_FRAGMENT)
            val ids = IntArray(2)
            GLES20.glGenTextures(2, ids, 0)
            cameraTexture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            depthTexture = ids[1]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
            // NEAREST: the two bytes of a depth sample must never be blended with a neighbour's.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            depthReady = false
            cameraTextureSession = null
            texReady = false
        }.onFailure { onError?.invoke("The AR renderer could not start: ${it.message}") }
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
        width = w
        height = h
        geometryDirty = true
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (paused || width == 0 || backgroundProgram == 0) return
        if (cameraTextureSession !== session) {
            cameraTextureSession = session
            session.setCameraTextureName(cameraTexture)
        }
        if (geometryDirty) {
            session.setDisplayGeometry(displayRotation, width, height)
            geometryDirty = false
            texReady = false
        }
        val frame = try {
            session.update()
        } catch (error: Exception) {
            paused = true
            onError?.invoke("AR tracking stopped: ${error.message}")
            return
        }
        if (frame.hasDisplayGeometryChanged() || !texReady) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD, Coordinates2d.TEXTURE_NORMALIZED, quadTexArray)
            quadTex.position(0)
            quadTex.put(quadTexArray).position(0)
            texReady = true
        }
        drawBackground()

        val camera = frame.camera
        tracking = camera.trackingState == TrackingState.TRACKING
        if (!tracking) {
            onStatus?.invoke("Hold still for a moment: the camera lost its place and is finding it again. Your pages have not moved.")
            return
        }
        val projection = FloatArray(16)
        val view = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, 0.05f, 50f)
        camera.getViewMatrix(view, 0)
        synchronized(viewProjection) {
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            Matrix.invertM(inverse, 0, viewProjection, 0)
            System.arraycopy(view, 0, viewMatrix, 0, 16)
            val pose = camera.pose
            cameraPosition = Vec3(pose.tx(), pose.ty(), pose.tz())
            haveCamera = true
        }

        answerMeasurements(frame)
        uploadOcclusionDepth(frame)
        scan(frame, session)

        val vp = synchronized(viewProjection) { viewProjection.copyOf() }
        if (showSurfaces) drawSurfaces(vp)
        drawPages(session, vp, view)
    }

    // ── The room model ─────────────────────────────────────────────────────

    private fun scan(frame: Frame, session: Session) {
        val now = System.currentTimeMillis()
        if (depthSupported && now - lastDepthAt > 250 && !scanBusy.get()) {
            lastDepthAt = now
            val depth = runCatching { rawDepthFrame(frame) }.getOrElse { error ->
                if (error !is com.google.ar.core.exceptions.NotYetAvailableException) depthSupported = false
                null
            }
            if (depth != null) {
                scanBusy.set(true)
                // Raw depth is trusted to 4 m; beyond that its error grows faster than its use.
                worker.execute { try { room.scanner.addDepth(depth, stride = 1, far = 4f) } finally { scanBusy.set(false) } }
            }
        }
        if (now - lastPlanesAt > 1000) {
            lastPlanesAt = now
            val planes = session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                .map { samplePlane(it) }
            // ARCore's planes are fitted to many tracked features and are the steadiest evidence
            // of where a surface is, so they count for more than a single depth sample.
            worker.execute { planes.forEach { (points, normal) -> points.forEach { room.scanner.addSample(it, normal, 2f) } } }
        }
        if (now - lastRebuildAt > 1000) {
            lastRebuildAt = now
            worker.execute {
                runCatching { room.rebuild() }
                onStatus?.invoke(status())
            }
        }
    }

    private fun status(): String {
        val layout = room.layout
        return when {
            layout.isEmpty -> "Look around slowly so Prism can find your walls, floor and ceiling"
            room.alignment == null -> "Found ${layout.describe()}. Keep looking around to find where your pages were…"
            !room.confident -> "Found ${layout.describe()}. Pages are shown where Prism thinks they were; look at more walls to settle them."
            else -> "Found ${layout.describe()}"
        }
    }

    /** The texture intrinsics, scaled to an image of [w] x [h]: the depth images' camera. */
    private fun depthIntrinsics(frame: Frame, w: Int, h: Int): Intrinsics {
        val k = frame.camera.textureIntrinsics
        val dims = k.imageDimensions
        val sx = w.toFloat() / dims[0]
        val sy = h.toFloat() / dims[1]
        return Intrinsics(k.focalLength[0] * sx, k.focalLength[1] * sy, k.principalPoint[0] * sx, k.principalPoint[1] * sy, w, h)
    }

    /** This frame's raw depth, in metres, with its confidence (0..1) and the camera that took it. */
    private fun rawDepthFrame(frame: Frame): DepthFrame? {
        val depthImage: Image = frame.acquireRawDepthImage16Bits()
        val confidenceImage: Image = try {
            frame.acquireRawDepthConfidenceImage()
        } catch (error: Exception) {
            depthImage.close()
            throw error
        }
        try {
            val w = depthImage.width
            val h = depthImage.height
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val cPlane = confidenceImage.planes[0]
            val cBuffer = cPlane.buffer
            val depth = FloatArray(w * h)
            val confidence = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                depth[y * w + x] = (buffer.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFFFF) / 1000f
                confidence[y * w + x] = (cBuffer.get(y * cPlane.rowStride + x * cPlane.pixelStride).toInt() and 0xFF) / 255f
            }
            val m = FloatArray(16)
            frame.camera.pose.toMatrix(m, 0)
            return DepthFrame(depth, depthIntrinsics(frame, w, h), Pose.fromColumnMajor(m), confidence)
        } finally {
            depthImage.close()
            confidenceImage.close()
        }
    }

    /** Uploads the smoothed depth image for occlusion: low byte in L, high byte in A. */
    private fun uploadOcclusionDepth(frame: Frame) {
        if (!depthSupported) return
        val image = runCatching { frame.acquireDepthImage16Bits() }.getOrNull() ?: return
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val src = plane.buffer
            val out = depthUpload?.takeIf { it.capacity() == w * h * 2 } ?: ByteBuffer.allocateDirect(w * h * 2).also { depthUpload = it }
            out.clear()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val at = y * plane.rowStride + x * plane.pixelStride
                    out.put(src.get(at)); out.put(src.get(at + 1))
                }
            }
            out.flip()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, out)
            depthReady = true
        } finally {
            image.close()
        }
    }

    /** ARCore's own plane, as surface samples a few centimetres apart. */
    private fun samplePlane(plane: Plane): Pair<List<Vec3>, Vec3> {
        val pose = plane.centerPose
        val normalArray = pose.yAxis
        val normal = Vec3(normalArray[0], normalArray[1], normalArray[2])
        val polygon = plane.polygon
        val xs = FloatArray(polygon.limit() / 2) { polygon.get(it * 2) }
        val zs = FloatArray(polygon.limit() / 2) { polygon.get(it * 2 + 1) }
        val points = ArrayList<Vec3>()
        if (xs.size < 3) return points to normal
        val step = 0.05f
        var x = xs.min()
        while (x <= xs.max()) {
            var z = zs.min()
            while (z <= zs.max()) {
                if (inside(xs, zs, x, z)) {
                    val p = pose.transformPoint(floatArrayOf(x, 0f, z))
                    points += Vec3(p[0], p[1], p[2])
                }
                z += step
            }
            x += step
        }
        return points to normal
    }

    private fun inside(xs: FloatArray, zs: FloatArray, x: Float, z: Float): Boolean {
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside
            j = i
        }
        return inside
    }

    // ── Measuring and touching ─────────────────────────────────────────────

    /**
     * Asks ARCore where the real surface under view pixel ([x], [y]) is -- a plane hit inside the
     * plane, then a depth hit, then a feature point. [done] is called on the GL thread.
     */
    fun measure(x: Float, y: Float, done: (Vec3?) -> Unit) { measureRequests += Triple(x, y, done) }

    private fun answerMeasurements(frame: Frame) {
        while (true) {
            val (x, y, done) = measureRequests.poll() ?: return
            val hits = runCatching { frame.hitTest(x, y) }.getOrDefault(emptyList())
            val best = hits.firstOrNull { h -> (h.trackable as? Plane)?.isPoseInPolygon(h.hitPose) == true }
                ?: hits.firstOrNull { it.trackable is DepthPoint }
                ?: hits.firstOrNull { it.trackable is Point }
            done(best?.hitPose?.let { Vec3(it.tx(), it.ty(), it.tz()) })
        }
    }

    /** The world ray through view pixel ([x], [y]), or null before the camera is tracking. */
    fun ray(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Pair<Vec3, Vec3>? {
        if (!haveCamera || viewWidth == 0) return null
        val ndcX = x / viewWidth * 2 - 1
        val ndcY = 1 - y / viewHeight * 2
        val m = synchronized(viewProjection) { inverse.copyOf() }
        fun unproject(z: Float): Vec3? {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, m, 0, floatArrayOf(ndcX, ndcY, z, 1f), 0)
            if (kotlin.math.abs(out[3]) < 1e-9f) return null
            return Vec3(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        val near = unproject(-1f) ?: return null
        val far = unproject(1f) ?: return null
        return near to (far - near).normalized()
    }

    /** The page under a ray, as it is drawn now. */
    fun pageAt(origin: Vec3, direction: Vec3): ArHome.PageHit? = ArHome.pageAt(drawn, origin, direction)

    /** A page as drawn now. */
    fun drawnPlacement(key: String): ArHome.Placement? = drawn.firstOrNull { it.key == key }

    // ── Drawing ─────────────────────────────────────────────────────────────

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram, "u_Texture"), 0)
        val position = GLES20.glGetAttribLocation(backgroundProgram, "a_Position")
        val tex = GLES20.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        quadTex.position(0)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(tex)
        GLES20.glDepthMask(true)
    }

    private fun putCorners(points: List<Vec3>) {
        // Triangle-strip order: top-left, top-right, bottom-left, bottom-right.
        corners.position(0)
        listOf(points[0], points[1], points[3], points[2]).forEach { corners.put(it.x); corners.put(it.y); corners.put(it.z) }
        corners.position(0)
    }

    private fun drawColored(points: List<Vec3>, vp: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(colorProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "u_Mvp"), 1, false, vp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "u_Color"), r, g, b, a)
        val position = GLES20.glGetAttribLocation(colorProgram, "a_Position")
        putCorners(points)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, corners)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun surfaceCorners(s: RoomLayout.Surface): List<Vec3> {
        val u = s.u * s.halfU
        val v = s.v * s.halfV
        val lift = s.normal * 0.003f
        return listOf(s.center - u + v + lift, s.center + u + v + lift, s.center + u - v + lift, s.center - u - v + lift)
    }

    private fun drawSurfaces(vp: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        val hover = hoverSurface
        room.layout.placeable.forEach { s ->
            val strong = hover != null && s.center == hover.center && s.normal == hover.normal
            val a = if (strong) 0.35f else 0.12f
            when (s.kind) {
                RoomLayout.Kind.WALL -> drawColored(surfaceCorners(s), vp, 0.35f, 0.6f, 1f, a)
                RoomLayout.Kind.FLOOR -> drawColored(surfaceCorners(s), vp, 0.3f, 0.95f, 0.55f, a)
                RoomLayout.Kind.CEILING -> drawColored(surfaceCorners(s), vp, 1f, 0.7f, 0.3f, a)
                RoomLayout.Kind.TABLE -> Unit
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** [p], corrected by its anchor: where ARCore now says that spot in the room is. */
    private fun anchored(session: Session, p: ArHome.Placement): ArHome.Placement {
        val pinned = anchors[p.key]
        if (pinned == null || pinned.center != p.center || pinned.anchor.trackingState == TrackingState.STOPPED) {
            pinned?.anchor?.let { runCatching { it.detach() } }
            val anchor = runCatching {
                session.createAnchor(com.google.ar.core.Pose.makeTranslation(p.center.x, p.center.y, p.center.z))
            }.getOrNull() ?: return p
            anchors[p.key] = Pinned(anchor, p.center)
            return p
        }
        if (pinned.anchor.trackingState != TrackingState.TRACKING) return p
        val t = pinned.anchor.pose
        return p.copy(center = Vec3(t.tx(), t.ty(), t.tz()))
    }

    private fun drawPages(session: Session, vp: FloatArray, view: FloatArray) {
        val placed = room.placed()
        val keys = placed.map { it.key }.toSet()
        anchors.keys.filter { it !in keys }.forEach { key -> anchors.remove(key)?.anchor?.let { runCatching { it.detach() } } }
        val shown = placed.map { anchored(session, it) }
        drawn = shown

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        shown.forEach { p ->
            if (p.key == selectedKey) {
                GLES20.glDisable(GLES20.GL_BLEND)
                val grown = p.copy(width = p.width * 1.04f, height = p.height + p.width * 0.04f, center = p.center - p.normal * 0.002f)
                drawColored(ArHome.corners(grown), vp, 0.45f, 0.7f, 1f, 1f)
            }
        }
        // Pages blend, so the camera shows through wherever something real stands in front.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        shown.forEach { p ->
            val panel = panels[p.key] ?: return@forEach
            if (panel.textureId == 0) panel.createTexture(activity)
            panel.latch()
            drawPage(p, panel, vp, view)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawPage(p: ArHome.Placement, panel: ArPagePanel, vp: FloatArray, view: FloatArray) {
        if (panel.textureId == 0) return
        val program = pageProgram
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_Mvp"), 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_View"), 1, false, view, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_TexMatrix"), 1, false, panel.transform, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_Dim"), if (panel.poppedOut) 0.35f else 1f)
        // Screen position -> depth texture coordinates: the same mapping the camera image uses.
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_Viewport"), width.toFloat(), height.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_T0"), quadTexArray[0], quadTexArray[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_T1"), quadTexArray[2] - quadTexArray[0], quadTexArray[3] - quadTexArray[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_T2"), quadTexArray[4] - quadTexArray[0], quadTexArray[5] - quadTexArray[1])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_UseDepth"), if (depthReady) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_Margin"), OCCLUDER_MARGIN)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, panel.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_Texture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_Depth"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val position = GLES20.glGetAttribLocation(program, "a_Position")
        val tex = GLES20.glGetAttribLocation(program, "a_TexCoord")
        putCorners(ArHome.corners(p))
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, corners)
        pageTex.position(0)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, pageTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(tex)
    }

    fun shutdown() {
        worker.shutdownNow()
        anchors.values.forEach { runCatching { it.anchor.detach() } }
        anchors.clear()
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        /** How far in front of a page something real must be to hide it: more than depth noise. */
        const val OCCLUDER_MARGIN = 0.10f

        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        fun buffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values).apply { position(0) }

        const val BACKGROUND_VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        const val BACKGROUND_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
        """
        const val COLOR_VERTEX = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() { gl_Position = u_Mvp * a_Position; }
        """
        const val COLOR_FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """
        const val PAGE_VERTEX = """
            uniform mat4 u_Mvp;
            uniform mat4 u_View;
            uniform mat4 u_TexMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            varying float v_Depth;
            void main() {
                gl_Position = u_Mvp * a_Position;
                v_TexCoord = (u_TexMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;
                v_Depth = -(u_View * a_Position).z;
            }
        """
        // highp: a depth sample is rebuilt as low + high * 256 in millimetres, past mediump's range.
        const val PAGE_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 v_TexCoord;
            varying float v_Depth;
            uniform samplerExternalOES u_Texture;
            uniform sampler2D u_Depth;
            uniform float u_Dim;
            uniform float u_UseDepth;
            uniform float u_Margin;
            uniform vec2 u_Viewport;
            uniform vec2 u_T0;
            uniform vec2 u_T1;
            uniform vec2 u_T2;
            void main() {
                vec4 c = texture2D(u_Texture, v_TexCoord);
                float visible = 1.0;
                if (u_UseDepth > 0.5) {
                    vec2 s = gl_FragCoord.xy / u_Viewport;
                    vec2 uv = u_T0 + s.x * u_T1 + s.y * u_T2;
                    vec4 d = texture2D(u_Depth, uv);
                    float real = (d.r * 255.0 + d.a * 255.0 * 256.0) / 1000.0;
                    if (real > 0.0) visible = clamp((real - (v_Depth - u_Margin)) / 0.06, 0.0, 1.0);
                }
                gl_FragColor = vec4(c.rgb * u_Dim, visible);
            }
        """
    }
}
