package com.prism.launcher.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

/**
 * One surface ARCore found under the player's finger.
 *
 * [basis] is the world-space orientation of the artwork: the first three floats are the image's
 * right axis, the next three are its up axis, and the last three are its surface normal. It is
 * captured at the tap, not recomputed every frame, so a picture does not spin as the phone moves.
 */
data class GeoTagSurfaceHit(
    val anchor: Anchor,
    val surface: GeoTagStore.Surface,
    val screenX: Float,
    val screenY: Float,
    val basis: FloatArray,
    /** The plane's polygon at the moment of the tap, used to keep the picture on the surface. */
    val plane: GeoTagPlane?,
)

/** How many planes of each kind are currently tracked, for the scanning hint. */
data class GeoTagPlaneCounts(
    val walls: Int = 0,
    val floors: Int = 0,
    val ceilings: Int = 0,
) {
    val total: Int get() = walls + floors + ceilings
}

/**
 * ARCore's camera, planes and artwork.
 *
 * ## Why this is a GLSurfaceView rather than a camera preview
 *
 * A SurfaceView camera preview shows pixels, but it cannot say what those pixels are. ARCore's
 * session supplies the camera texture together with plane polygons and anchors, so the renderer
 * draws the camera, paints the detected surfaces, and puts each picture on the exact pose the
 * player tapped. That is the difference between this and the old markerless overlay.
 *
 * ## Threads
 *
 * `onDrawFrame` is the only place ARCore is updated. Taps and placements arrive from the UI thread
 * as small requests and are consumed on the GL thread, because calling [Session.update] from two
 * threads is not allowed.
 */
class GeoTagArRenderer(context: Context) : GLSurfaceView.Renderer {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var session: Session? = null
        set(value) {
            field = value
            reportedError = false
        }

    @Volatile
    var paused: Boolean = false

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    var onSurfacePicked: ((GeoTagSurfaceHit) -> Unit)? = null

    @Volatile
    var onSurfaceMissing: (() -> Unit)? = null

    @Volatile
    var onPlanesChanged: ((GeoTagPlaneCounts) -> Unit)? = null

    @Volatile
    var onError: ((String) -> Unit)? = null

    @Volatile
    private var pendingHit: Pair<Float, Float>? = null

    @Volatile
    private var reportedError: Boolean = false

    private val placements = ConcurrentLinkedQueue<PlacementRequest>()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayGeometryDirty = true
    private var cameraTextureId = 0
    private var cameraTextureSession: Session? = null
    private var textureCoordsReady = false

    private var backgroundProgram = 0
    private var planeProgram = 0
    private var artworkProgram = 0
    private var backgroundPosition = 0
    private var backgroundTexCoord = 0
    private var planeMvp = 0
    private var planeColor = 0
    private var planePosition = 0
    private var artworkMvp = 0
    private var artworkTexture = 0
    private var artworkPosition = 0
    private var artworkTexCoord = 0

    private val quadPositions = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val quadTexCoords = floatBuffer(FloatArray(8) { 0f })
    private val quadTexArray = FloatArray(8)

    private val textures = HashMap<String, Int>()
    private val aspects = HashMap<String, Float>()
    private val placed = ArrayList<PlacedTag>()
    private var lastCounts = GeoTagPlaneCounts()

    /** Queues a tap. The coordinates are in view pixels, as ARCore's hit test expects. */
    fun requestHit(x: Float, y: Float) {
        pendingHit = x to y
    }

    /**
     * Queues an artwork for the GL thread. GL textures must be created on the thread that owns the
     * context, so this deliberately does not touch OpenGL here.
     */
    fun place(
        artId: String,
        anchor: Anchor,
        surface: GeoTagStore.Surface,
        basis: FloatArray,
        plane: GeoTagPlane?,
    ) {
        placements.add(PlacementRequest(artId, anchor, surface, basis.copyOf(), plane))
    }

    fun releaseAnchor(anchor: Anchor) {
        runCatching { anchor.detach() }
    }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        runCatching {
            backgroundProgram = program(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
            backgroundPosition = GLES20.glGetAttribLocation(backgroundProgram, "a_Position")
            backgroundTexCoord = GLES20.glGetAttribLocation(backgroundProgram, "a_TexCoord")

            planeProgram = program(PLANE_VERTEX, PLANE_FRAGMENT)
            planeMvp = GLES20.glGetUniformLocation(planeProgram, "u_Mvp")
            planePosition = GLES20.glGetAttribLocation(planeProgram, "a_Position")
            planeColor = GLES20.glGetUniformLocation(planeProgram, "u_Color")

            artworkProgram = program(ARTWORK_VERTEX, ARTWORK_FRAGMENT)
            artworkMvp = GLES20.glGetUniformLocation(artworkProgram, "u_Mvp")
            artworkTexture = GLES20.glGetUniformLocation(artworkProgram, "u_Texture")
            artworkPosition = GLES20.glGetAttribLocation(artworkProgram, "a_Position")
            artworkTexCoord = GLES20.glGetAttribLocation(artworkProgram, "a_TexCoord")

            cameraTextureId = externalTexture()
            // A new GL context means a new camera texture. The session still points at the old
            // name, so force the next frame to hand it the replacement.
            cameraTextureSession = null
            textureCoordsReady = false
            restoreArtworkTextures()
        }.onFailure { report("The AR renderer could not start: ${it.message}") }
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        displayGeometryDirty = true
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (paused || viewportWidth <= 0 || viewportHeight <= 0) return
        if (backgroundProgram == 0) return

        if (cameraTextureId == 0) cameraTextureId = externalTexture()
        if (cameraTextureSession !== session) {
            cameraTextureSession = session
            session.setCameraTextureName(cameraTextureId)
        }
        if (displayGeometryDirty) {
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            displayGeometryDirty = false
            textureCoordsReady = false
        }

        val frame = try {
            session.update()
        } catch (error: CameraNotAvailableException) {
            paused = true
            report("The AR camera is not available right now: ${error.message}")
            return
        } catch (error: Exception) {
            paused = true
            report("AR tracking stopped: ${error.message}")
            return
        }

        if (frame.hasDisplayGeometryChanged() || !textureCoordsReady) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                QUAD_COORDS,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexArray,
            )
            quadTexCoords.position(0)
            quadTexCoords.put(quadTexArray).position(0)
            textureCoordsReady = true
        }

        consumeTap(frame)
        consumePlacements()

        val projection = FloatArray(16)
        val view = FloatArray(16)
        val viewProjection = FloatArray(16)
        val camera = frame.camera
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        drawBackground()
        drawPlanes(session, viewProjection)
        drawArtwork(viewProjection)
    }

    private fun consumeTap(frame: Frame) {
        val tap = pendingHit ?: return
        pendingHit = null
        val x = tap.first
        val y = tap.second
        val target = planeTarget(frame, x, y)
        if (target == null) {
            main.post { onSurfaceMissing?.invoke() }
            return
        }
        val plane = target.plane
        if (plane.trackingState != TrackingState.TRACKING) {
            main.post { onSurfaceMissing?.invoke() }
            return
        }

        val surface = surfaceFor(plane)
        val cameraPose = frame.camera.pose
        val cameraForward = cameraPose.zAxis
        cameraForward[0] = -cameraForward[0]
        cameraForward[1] = -cameraForward[1]
        cameraForward[2] = -cameraForward[2]
        val basis = GeoTagGeometry.basisFor(
            surface = surface,
            planeNormal = plane.centerPose.yAxis,
            cameraPosition = cameraPose.translation,
            cameraForward = cameraForward,
            cameraUp = cameraPose.yAxis,
            anchorPosition = target.point,
        )
        val anchor = target.anchor ?: runCatching {
            session?.createAnchor(
                Pose.makeTranslation(target.point[0], target.point[1], target.point[2]),
            )
        }.getOrNull()
        if (anchor == null) {
            // A plane that was subsumed between the hit test and this call cannot own an anchor.
            main.post { onSurfaceMissing?.invoke() }
            return
        }
        val result = GeoTagSurfaceHit(
            anchor = anchor,
            surface = surface,
            screenX = (x / viewportWidth.toFloat()).coerceIn(0f, 1f),
            screenY = (y / viewportHeight.toFloat()).coerceIn(0f, 1f),
            basis = basis,
            plane = snapshot(plane),
        )
        main.post { onSurfacePicked?.invoke(result) }
    }

    /**
     * Finds the surface under the finger, preferring ARCore's own hit result.
     *
     * ARCore's hit test only answers when it has enough depth or plane confidence under that exact
     * pixel. A large wall is often tracked for many seconds before that is true everywhere, so a
     * tap just off the well-measured patch would report "no surface" even though the wall was
     * outlined on screen. The fallback casts the same screen ray against every tracked plane's
     * polygon, which is exactly the surface the player can see.
     */
    private fun planeTarget(frame: Frame, x: Float, y: Float): PlaneTarget? {
        val hit = frame.hitTest(x, y).firstOrNull { result -> result.planeOrNull() != null }
        if (hit != null) {
            val plane = hit.planeOrNull() ?: return null
            val anchor = runCatching { hit.createAnchor() }.getOrNull()
            return PlaneTarget(plane, hit.hitPose.translation.copyOf(), anchor)
        }
        return rayPlaneTarget(frame, x, y)
    }

    private fun rayPlaneTarget(frame: Frame, x: Float, y: Float): PlaneTarget? {
        val activeSession = session ?: return null
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        if (frame.camera.trackingState != TrackingState.TRACKING) return null

        val projection = FloatArray(16)
        val view = FloatArray(16)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        frame.camera.getViewMatrix(view, 0)
        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        val inverse = FloatArray(16)
        if (!Matrix.invertM(inverse, 0, viewProjection, 0)) return null

        val ndcX = x / viewportWidth.toFloat() * 2f - 1f
        val ndcY = 1f - y / viewportHeight.toFloat() * 2f
        val near = unproject(inverse, ndcX, ndcY, -1f) ?: return null
        val far = unproject(inverse, ndcX, ndcY, 1f) ?: return null
        var direction = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        val length = kotlin.math.sqrt(
            direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2],
        )
        if (length < 0.0001f) return null
        direction = floatArrayOf(direction[0] / length, direction[1] / length, direction[2] / length)

        var best: PlaneTarget? = null
        var bestDistance = Float.MAX_VALUE
        for (plane in activeSession.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            val polygon = plane.polygon ?: continue
            val pose = plane.centerPose
            val axisX = pose.xAxis
            val axisY = pose.yAxis
            val axisZ = pose.zAxis
            val offset = floatArrayOf(
                near[0] - pose.translation[0],
                near[1] - pose.translation[1],
                near[2] - pose.translation[2],
            )
            val denominator = dot(direction, axisY)
            if (abs(denominator) < 0.00001f) continue
            val distance = -dot(offset, axisY) / denominator
            if (distance <= 0f || distance >= bestDistance) continue

            val point = floatArrayOf(
                near[0] + direction[0] * distance,
                near[1] + direction[1] * distance,
                near[2] + direction[2] * distance,
            )
            val local = floatArrayOf(
                point[0] - pose.translation[0],
                point[1] - pose.translation[1],
                point[2] - pose.translation[2],
            )
            if (!insidePolygon(polygon, dot(local, axisX), dot(local, axisZ))) continue
            bestDistance = distance
            best = PlaneTarget(plane, point, null)
        }
        return best
    }

    private fun unproject(inverse: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val input = floatArrayOf(x, y, z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inverse, 0, input, 0)
        if (abs(out[3]) < 0.000001f) return null
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    /** Point-in-polygon in the plane's own x/z coordinates. */
    private fun insidePolygon(polygon: FloatBuffer, x: Float, z: Float): Boolean {
        polygon.rewind()
        val count = polygon.remaining() / 2
        if (count < 3) return false
        var inside = false
        var previous = count - 1
        for (i in 0 until count) {
            val xi = polygon.get(i * 2)
            val zi = polygon.get(i * 2 + 1)
            val xj = polygon.get(previous * 2)
            val zj = polygon.get(previous * 2 + 1)
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside
            }
            previous = i
        }
        return inside
    }

    private fun surfaceFor(plane: Plane): GeoTagStore.Surface = when (plane.type) {
        Plane.Type.VERTICAL -> GeoTagStore.Surface.WALL
        Plane.Type.HORIZONTAL_UPWARD_FACING -> GeoTagStore.Surface.FLOOR
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> GeoTagStore.Surface.CEILING
        else -> GeoTagStore.Surface.WALL
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun HitResult.planeOrNull(): Plane? {
        val trackable = trackable as? Plane ?: return null
        // A subsumed plane is still a real, tracked surface; ARCore only stops drawing it because
        // its parent covers it. Ignoring it here made taps near the seam between two patches fail
        // even though the ray had hit the wall the player was looking at.
        return if (trackable.trackingState == TrackingState.TRACKING) trackable else null
    }

    private fun consumePlacements() {
        while (true) {
            val request = placements.poll() ?: break
            val texture = textureFor(request.artId)
            if (texture == null) {
                releaseAnchor(request.anchor)
                continue
            }
            val aspect = aspects[request.artId] ?: 1f
            val maxEdge = when (request.surface) {
                GeoTagStore.Surface.WALL -> 1.0f
                GeoTagStore.Surface.FLOOR, GeoTagStore.Surface.CEILING -> 1.25f
            }
            val width = if (aspect >= 1f) maxEdge else maxEdge * aspect
            val height = if (aspect >= 1f) maxEdge / aspect else maxEdge
            val anchor = fittedAnchor(request.anchor, request.basis, width, height, request.plane)
            placed.add(PlacedTag(request.artId, anchor, request.surface, request.basis, texture, width, height))
        }
    }

    /** The plane snapshot has to be taken on the GL thread, where ARCore is being updated. */
    private fun snapshot(plane: Plane): GeoTagPlane {
        val pose = plane.centerPose
        val polygon = plane.polygon?.let { buffer ->
            val copy = buffer.duplicate()
            copy.rewind()
            FloatArray(copy.remaining()).also { copy.get(it) }
        }
        return GeoTagPlane(
            center = pose.translation.copyOf(),
            axisX = pose.xAxis.copyOf(),
            axisZ = pose.zAxis.copyOf(),
            polygon = polygon,
        )
    }

    /**
     * Moves the anchor inward when the tapped point is too close to the edge of its plane.
     *
     * The replacement anchor is a plain world anchor at the fitted point; the artwork's orientation
     * still comes from the basis captured at the tap, so it stays flat against the same surface.
     */
    private fun fittedAnchor(
        original: Anchor,
        basis: FloatArray,
        width: Float,
        height: Float,
        plane: GeoTagPlane?,
    ): Anchor {
        // A tap on the sheet's close button can release the borrowed anchor while this placement
        // is still queued on the GL thread. Treat a detached anchor as "nothing to fit".
        val from = runCatching { original.pose.translation }.getOrNull() ?: return original
        val fitted = GeoTagGeometry.fitCenter(from, basis, width, height, plane)
        if (abs(from[0] - fitted[0]) < 1e-4f &&
            abs(from[1] - fitted[1]) < 1e-4f &&
            abs(from[2] - fitted[2]) < 1e-4f
        ) {
            return original
        }
        val created = session?.let { active ->
            runCatching {
                active.createAnchor(Pose.makeTranslation(fitted[0], fitted[1], fitted[2]))
            }.getOrNull()
        } ?: return original
        runCatching { original.detach() }
        return created
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram, "u_Texture"), 0)

        quadPositions.position(0)
        GLES20.glEnableVertexAttribArray(backgroundPosition)
        GLES20.glVertexAttribPointer(backgroundPosition, 2, GLES20.GL_FLOAT, false, 0, quadPositions)
        quadTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(backgroundTexCoord)
        GLES20.glVertexAttribPointer(backgroundTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(backgroundPosition)
        GLES20.glDisableVertexAttribArray(backgroundTexCoord)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun drawPlanes(session: Session, viewProjection: FloatArray) {
        GLES20.glUseProgram(planeProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        var walls = 0
        var floors = 0
        var ceilings = 0
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val surface = when (plane.type) {
                Plane.Type.VERTICAL -> GeoTagStore.Surface.WALL.also { walls++ }
                Plane.Type.HORIZONTAL_UPWARD_FACING -> GeoTagStore.Surface.FLOOR.also { floors++ }
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> GeoTagStore.Surface.CEILING.also { ceilings++ }
                else -> continue
            }
            drawPlane(plane, surface, viewProjection)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        val counts = GeoTagPlaneCounts(walls, floors, ceilings)
        if (counts != lastCounts) {
            lastCounts = counts
            main.post { onPlanesChanged?.invoke(counts) }
        }
    }

    private fun drawPlane(plane: Plane, surface: GeoTagStore.Surface, viewProjection: FloatArray) {
        val polygon = plane.polygon ?: return
        polygon.rewind()
        val count = polygon.remaining() / 2
        if (count < 3) return

        val pose = FloatArray(16)
        plane.centerPose.toMatrix(pose, 0)
        // The polygon is converted to world space below, so the only matrix the shader applies is
        // the camera. Multiplying the plane pose in here as well drew every outline at twice the
        // plane's distance from the session origin.
        GLES20.glUniformMatrix4fv(planeMvp, 1, false, viewProjection, 0)

        val vertices = FloatArray(count * 3)
        for (i in 0 until count) {
            val x = polygon.get(i * 2)
            val z = polygon.get(i * 2 + 1)
            vertices[i * 3] = pose[0] * x + pose[4] * 0f + pose[8] * z + pose[12]
            vertices[i * 3 + 1] = pose[1] * x + pose[5] * 0f + pose[9] * z + pose[13]
            vertices[i * 3 + 2] = pose[2] * x + pose[6] * 0f + pose[10] * z + pose[14]
        }
        val buffer = floatBuffer(vertices)

        val (fill, edge) = when (surface) {
            GeoTagStore.Surface.WALL -> floatArrayOf(0.24f, 0.78f, 1f, 0.10f) to floatArrayOf(0.35f, 0.88f, 1f, 0.72f)
            GeoTagStore.Surface.FLOOR -> floatArrayOf(0.36f, 1f, 0.60f, 0.10f) to floatArrayOf(0.50f, 1f, 0.70f, 0.72f)
            GeoTagStore.Surface.CEILING -> floatArrayOf(1f, 0.75f, 0.28f, 0.10f) to floatArrayOf(1f, 0.84f, 0.45f, 0.72f)
        }

        GLES20.glEnableVertexAttribArray(planePosition)
        GLES20.glVertexAttribPointer(planePosition, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glUniform4fv(planeColor, 1, fill, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)
        GLES20.glUniform4fv(planeColor, 1, edge, 0)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, count)
        GLES20.glDisableVertexAttribArray(planePosition)
    }

    private fun drawArtwork(viewProjection: FloatArray) {
        if (placed.isEmpty()) return
        GLES20.glUseProgram(artworkProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUniform1i(artworkTexture, 0)

        val translation = FloatArray(3)
        val model = FloatArray(16)
        val mvp = FloatArray(16)
        for (tag in placed) {
            val tracking = runCatching { tag.anchor.trackingState }.getOrNull() ?: TrackingState.STOPPED
            if (tracking != TrackingState.TRACKING || tag.texture == 0) continue
            val pose = runCatching { tag.anchor.pose }.getOrNull() ?: continue
            pose.getTranslation(translation, 0)
            model[0] = tag.basis[0]
            model[1] = tag.basis[1]
            model[2] = tag.basis[2]
            model[3] = 0f
            model[4] = tag.basis[3]
            model[5] = tag.basis[4]
            model[6] = tag.basis[5]
            model[7] = 0f
            model[8] = tag.basis[6]
            model[9] = tag.basis[7]
            model[10] = tag.basis[8]
            model[11] = 0f
            model[12] = translation[0]
            model[13] = translation[1]
            model[14] = translation[2]
            model[15] = 1f
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            GLES20.glUniformMatrix4fv(artworkMvp, 1, false, mvp, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tag.texture)
            tag.vertices.position(0)
            GLES20.glEnableVertexAttribArray(artworkPosition)
            GLES20.glVertexAttribPointer(artworkPosition, 3, GLES20.GL_FLOAT, false, 0, tag.vertices)
            tag.texCoords.position(0)
            GLES20.glEnableVertexAttribArray(artworkTexCoord)
            GLES20.glVertexAttribPointer(artworkTexCoord, 2, GLES20.GL_FLOAT, false, 0, tag.texCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(artworkPosition)
            GLES20.glDisableVertexAttribArray(artworkTexCoord)
        }
    }

    private fun textureFor(artId: String): Int? {
        textures[artId]?.let { return it }
        val bitmap = artworkBitmap(artId) ?: return null
        aspects[artId] = bitmap.width.toFloat() / max(1, bitmap.height).toFloat()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[artId] = ids[0]
        return ids[0]
    }

    /**
     * A lost EGL context invalidates every texture name, including the ones already attached to
     * placed pictures. Re-uploading by art id keeps the tags where they are instead of leaving
     * black rectangles on the wall.
     */
    private fun restoreArtworkTextures() {
        textures.clear()
        for (tag in placed) {
            tag.texture = textureFor(tag.artId) ?: 0
        }
    }

    private fun artworkBitmap(artId: String): Bitmap? {
        val art = GeoTagStore.artwork(artId) ?: return null
        if (art.importedPath.isNotBlank()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(art.importedPath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            return BitmapFactory.decodeFile(art.importedPath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        return ArtworkView.bitmapFor(appContext, art, 512)
    }

    private fun report(message: String) {
        if (reportedError) return
        reportedError = true
        main.post { onError?.invoke(message) }
    }

    private data class PlacementRequest(
        val artId: String,
        val anchor: Anchor,
        val surface: GeoTagStore.Surface,
        val basis: FloatArray,
        val plane: GeoTagPlane?,
    )

    /** A surface under the finger, with the ARCore anchor when the hit test could make one. */
    private data class PlaneTarget(
        val plane: Plane,
        val point: FloatArray,
        val anchor: Anchor?,
    )

    private class PlacedTag(
        val artId: String,
        val anchor: Anchor,
        val surface: GeoTagStore.Surface,
        val basis: FloatArray,
        var texture: Int,
        width: Float,
        height: Float,
    ) {
        val vertices: FloatBuffer = floatBuffer(
            floatArrayOf(
                -width / 2f, -height / 2f, 0f,
                width / 2f, -height / 2f, 0f,
                -width / 2f, height / 2f, 0f,
                width / 2f, height / 2f, 0f,
            ),
        )

        // Bitmap row 0 is the top row, while OpenGL's texture origin is bottom-left, hence the
        // flipped V coordinates. Without this every picture would appear upside down. Ceilings are
        // also mirrored by their upright basis, so their U axis is reversed to stay readable.
        val texCoords: FloatBuffer = floatBuffer(
            if (surface.flipsTextureU) {
                floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
            } else {
                floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            },
        )
    }

    private fun externalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("link failed: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return id
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw IllegalStateException("compile failed: $log")
        }
        return id
    }

    companion object {
        private val QUAD_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }

        private const val BACKGROUND_VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val BACKGROUND_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        private const val PLANE_VERTEX = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_Mvp * a_Position;
            }
        """

        private const val PLANE_FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """

        private const val ARTWORK_VERTEX = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_Mvp * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val ARTWORK_FRAGMENT = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}
