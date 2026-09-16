package com.prism.launcher.aether

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.prism.launcher.PrismLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/**
 * Aether herself, standing behind her conversation.
 *
 * This sits BELOW the window rather than on top of it, which is the default placement for a
 * [GLSurfaceView] and the reason the whole thing works: the view punches a transparent hole through
 * the window where it sits, and the surface underneath shows through. Everything drawn after it in
 * the hierarchy -- the message list, the header, the compose row -- lands on top without any
 * blending or z-order tricks. The one thing that placement demands is that nothing opaque is drawn
 * over the hole, which is why [com.prism.launcher.messaging.ConversationActivity] clears the
 * conversation column's background for her thread.
 *
 * TWO MODES, and the difference between them is the whole point of the view:
 *
 *  - LOCKED (the default). She is scenery. [onTouchEvent] returns false so every touch falls
 *    through to the conversation, and the camera holds the framing in [applyFraming] exactly.
 *  - UNLOCKED. The conversation is hidden and she becomes the subject: drag to turn her, pinch to
 *    zoom, two fingers to slide her around. Re-locking animates the camera back to the framing so
 *    the background composition is always the one that was designed, never wherever the last
 *    person happened to leave her.
 *
 * SHE IS SKINNED AND ANIMATED. The mesh arrives from [AetherSkinnedModel] with four bone influences
 * per vertex, and a baked idle supplies one 3x4 matrix per bone per frame. Skinning happens in the
 * vertex shader against a uniform block holding the current pose, so the geometry is uploaded once
 * and only 48 bytes per bone change per frame.
 */
class AetherModelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = ModelRenderer(context.applicationContext)

    /** Whether touches turn her. False leaves the conversation in charge of every gesture. */
    var interactive: Boolean = false
        private set

    private var resetAnimator: ValueAnimator? = null

    // Gesture baselines. Only read and written on the UI thread.
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var pinching = false

    /**
     * Whether [activate] has run. Nothing GL-related exists before it: `setRenderer` spawns the
     * render thread the moment it is called, so doing any of this in `init` would leave a parked
     * GL thread behind every SMS conversation in the app, and would make [requestRender] -- which
     * dereferences that thread -- throw for any caller that ran before setup.
     */
    private var started = false

    /** The colour the model is composited over; match it to the page so the seam is invisible. */
    fun setBackgroundTint(color: Int) {
        renderer.setClearColour(color)
    }

    /** The rim light's colour, so she picks up the launcher's accent. */
    fun setAccent(color: Int) {
        renderer.setRimColour(color)
    }

    /**
     * Shows the view, builds the GL context, and starts reading the mesh.
     *
     * EXPLICIT RATHER THAN AUTOMATIC ON ATTACH, because this view is inflated into the layout every
     * conversation shares. Setting it all up on attach would spawn a render thread and parse 6 MB
     * of .obj every time somebody opened a text message thread, to feed a surface that thread
     * never shows.
     */
    fun activate() {
        visibility = VISIBLE
        if (!started) {
            started = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(MultisampleConfigChooser())
            setRenderer(renderer)
            // She breathes and sways on her own now, so every frame differs and the render loop has
            // to run. GLSurfaceView parks it on onPause, which is what keeps this from costing
            // anything once the conversation is off screen.
            renderMode = RENDERMODE_CONTINUOUSLY
            // The mesh is about 6 MB of vertex buffers and takes a beat to re-upload. Keeping the
            // context across a pause means coming back from the home screen does not pay for it.
            preserveEGLContextOnPause = true
        }
        loadModel()
    }

    /**
     * Reading the mesh takes a beat, so it happens on a plain background thread and the result is
     * picked up by the next frame. Until then the surface is just the clear colour, which is
     * indistinguishable from the page background -- so a slow load reads as "she has not faded in
     * yet" rather than as a blank rectangle.
     */
    /**
     * An imported character model to draw instead of Aether's own.
     *
     * Set before [activate]. Null keeps the bundled asset, which is what her own conversation uses.
     */
    private var modelFile: java.io.File? = null

    fun setModelFile(file: java.io.File?) {
        modelFile = file
    }

    private fun loadModel() {
        if (renderer.hasModel()) return
        Thread({
            try {
                val source = modelFile
                val model = if (source != null) {
                    AetherSkinnedModel.loadFrom(source)
                } else {
                    AetherSkinnedModel.load(context.applicationContext)
                }
                renderer.submit(model)
                requestRender()
            } catch (e: Throwable) {
                // A missing or malformed asset must not take the conversation down with it; she
                // simply does not appear.
                PrismLogger.logError("AetherModelView", "Could not load Aether's model", e)
            }
        }, "aether-model-load").start()
    }

    /**
     * @param animated true when a user pressed the button, so the return to the framing is a
     *        movement rather than a jump. False when restoring state, where there is no "from".
     */
    fun setInteractive(enabled: Boolean, animated: Boolean = true) {
        if (interactive == enabled) return
        interactive = enabled
        pinching = false
        resetAnimator?.cancel()
        resetAnimator = null
        if (!enabled) {
            if (animated) {
                animateToFraming()
            } else {
                renderer.resetCamera()
            }
        }
    }

    private fun animateToFraming() {
        val fromYaw = renderer.yaw
        val fromPitch = renderer.pitch
        val fromZoom = renderer.zoom
        val fromPanX = renderer.panX
        val fromPanY = renderer.panY
        if (fromYaw == 0f && fromPitch == 0f && fromZoom == 1f && fromPanX == 0f && fromPanY == 0f) return

        resetAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 340L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                renderer.yaw = fromYaw * t
                renderer.pitch = fromPitch * t
                renderer.zoom = 1f + (fromZoom - 1f) * t
                renderer.panX = fromPanX * t
                renderer.panY = fromPanY * t
                requestRender()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Falling through rather than consuming is what keeps the message list scrollable while
        // she is scenery -- the conversation sits above this view and would win anyway, but the
        // gaps between bubbles would otherwise swallow a scroll that started there.
        if (!interactive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetAnimator?.cancel()
                lastX = event.x
                lastY = event.y
                pinching = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                pinching = true
                lastSpan = spanOf(event)
                lastX = midXOf(event)
                lastY = midYOf(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val span = spanOf(event)
                    val midX = midXOf(event)
                    val midY = midYOf(event)
                    if (lastSpan > MIN_PINCH_SPAN_PX && span > MIN_PINCH_SPAN_PX) {
                        renderer.zoom = (renderer.zoom * (span / lastSpan))
                            .coerceIn(MIN_ZOOM, MAX_ZOOM)
                    }
                    // Panning moves the camera, so dragging right has to slide her right --
                    // hence the sign flip on X and not on Y (screen Y already grows downward).
                    val perPixel = renderer.worldPerPixel
                    renderer.panX = (renderer.panX - (midX - lastX) * perPixel)
                        .coerceIn(-renderer.panLimitX, renderer.panLimitX)
                    renderer.panY = (renderer.panY + (midY - lastY) * perPixel)
                        .coerceIn(-renderer.panLimitY, renderer.panLimitY)
                    lastSpan = span
                    lastX = midX
                    lastY = midY
                } else if (!pinching) {
                    renderer.yaw += (event.x - lastX) * DEGREES_PER_PIXEL
                    renderer.pitch = (renderer.pitch + (event.y - lastY) * DEGREES_PER_PIXEL)
                        .coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
                    lastX = event.x
                    lastY = event.y
                }
                requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Dropping to one finger without re-baselining makes her leap, because the
                // remaining finger is nowhere near the midpoint the pinch was tracking.
                if (event.pointerCount <= 2) {
                    pinching = false
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun spanOf(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midXOf(e: MotionEvent) = (e.getX(0) + e.getX(1)) * 0.5f
    private fun midYOf(e: MotionEvent) = (e.getY(0) + e.getY(1)) * 0.5f

    private companion object {
        const val DEGREES_PER_PIXEL = 0.32f
        const val MAX_PITCH_DEGREES = 32f
        const val MIN_ZOOM = 0.55f
        const val MAX_ZOOM = 5f
        const val MIN_PINCH_SPAN_PX = 12f
    }

    // -------------------------------------------------------------------------------------

    /**
     * A studio setup: key light from her front-upper-left, a weaker fill from the opposite side to
     * keep the shadowed half from going flat, and a rim in the launcher accent to lift her
     * silhouette off the page.
     *
     * There is no texturing stage at all, because [AetherModel] establishes there is nothing to
     * texture with -- the albedo arriving per vertex is the finished surface colour.
     */
    private class ModelRenderer(private val context: Context) : GLSurfaceView.Renderer {

        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        @Volatile var zoom = 1f
        @Volatile var panX = 0f
        @Volatile var panY = 0f

        /** World units covered by one screen pixel at the focus plane; the pan gesture's scale. */
        @Volatile var worldPerPixel = 0.002f
            private set
        @Volatile var panLimitX = 1f
            private set
        @Volatile var panLimitY = 1f
            private set

        @Volatile private var pending: AetherSkinnedModel? = null
        @Volatile private var model: AetherSkinnedModel? = null
        private var batches: List<GpuBatch> = emptyList()

        private var program = 0
        private var aPosition = 0
        private var aNormal = 0
        private var aTexcoord = 0
        private var aBoneIndex = 0
        private var aBoneWeight = 0
        private var uMvp = 0
        private var uModel = 0
        private var uNormalMatrix = 0
        private var uEye = 0
        private var uKeyDir = 0
        private var uFillDir = 0
        private var uRimColour = 0
        private var uTexture = 0
        private var uAlphaCutoff = 0

        private var boneUbo = 0
        private var boneScratch = FloatArray(0)
        private var boneBytes: ByteBuffer? = null
        private var startNanos = 0L

        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val scratch = FloatArray(16)
        private val mvp = FloatArray(16)
        private val normalMatrix = FloatArray(9)
        private val eye = FloatArray(3)

        private var focusY = 0f
        private var viewportWidth = 1
        private var viewportHeight = 1

        @Volatile private var clear = floatArrayOf(0.96f, 0.96f, 0.97f, 1f)
        @Volatile private var rim = floatArrayOf(0.49f, 0.62f, 1f)

        private class GpuBatch(
            val vbo: Int,
            val ibo: Int,
            val texture: Int,
            val indexCount: Int,
            val cutout: Boolean
        )

        fun hasModel() = model != null || pending != null

        fun submit(loaded: AetherSkinnedModel) { pending = loaded }

        fun resetCamera() { yaw = 0f; pitch = 0f; zoom = 1f; panX = 0f; panY = 0f }

        fun setClearColour(color: Int) {
            clear = floatArrayOf(
                ((color shr 16) and 0xFF) / 255f,
                ((color shr 8) and 0xFF) / 255f,
                (color and 0xFF) / 255f,
                1f
            )
        }

        fun setRimColour(color: Int) {
            rim = floatArrayOf(
                ((color shr 16) and 0xFF) / 255f,
                ((color shr 8) and 0xFF) / 255f,
                (color and 0xFF) / 255f
            )
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Everything below is rebuilt from the CPU-side mesh, which is retained for exactly
            // this reason: a context loss takes the program, the buffers and the textures with it,
            // and their old names belong to a context that no longer exists.
            batches = emptyList()
            program = 0
            boneUbo = 0
            model?.let { setUp(it) }
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            // No back-face culling. The hair is built from open cards with no interior, and half of
            // every strand faces away from any given camera angle -- culling them leaves her with
            // visible holes in her hair. The shader flips the normal on back faces instead.
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = max(1, width)
            viewportHeight = max(1, height)
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }

        override fun onDrawFrame(gl: GL10?) {
            pending?.let {
                pending = null
                model = it
                setUp(it)
            }

            val c = clear
            GLES30.glClearColor(c[0], c[1], c[2], c[3])
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

            val loaded = model ?: return
            if (batches.isEmpty() || program == 0) return

            applyFraming(loaded)
            uploadPose(loaded)

            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
            GLES30.glUniformMatrix3fv(uNormalMatrix, 1, false, normalMatrix, 0)
            GLES30.glUniform3f(uEye, eye[0], eye[1], eye[2])
            GLES30.glUniform3f(uKeyDir, KEY_DIR[0], KEY_DIR[1], KEY_DIR[2])
            GLES30.glUniform3f(uFillDir, FILL_DIR[0], FILL_DIR[1], FILL_DIR[2])
            val r = rim
            GLES30.glUniform3f(uRimColour, r[0], r[1], r[2])
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1i(uTexture, 0)

            GLES30.glEnableVertexAttribArray(aPosition)
            GLES30.glEnableVertexAttribArray(aNormal)
            GLES30.glEnableVertexAttribArray(aTexcoord)
            GLES30.glEnableVertexAttribArray(aBoneIndex)
            GLES30.glEnableVertexAttribArray(aBoneWeight)

            val stride = AetherSkinnedModel.VERTEX_STRIDE
            for (batch in batches) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, batch.texture)
                GLES30.glUniform1f(uAlphaCutoff, if (batch.cutout) ALPHA_CUTOFF else 0f)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, batch.vbo)
                GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, stride,
                    AetherSkinnedModel.OFFSET_POSITION)
                GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, stride,
                    AetherSkinnedModel.OFFSET_NORMAL)
                GLES30.glVertexAttribPointer(aTexcoord, 2, GLES30.GL_FLOAT, false, stride,
                    AetherSkinnedModel.OFFSET_TEXCOORD)
                // Indices ride as raw bytes read back as floats 0..255; weights are normalised to
                // 0..1 by the same call. Neither needs an integer vertex attribute.
                GLES30.glVertexAttribPointer(aBoneIndex, 4, GLES30.GL_UNSIGNED_BYTE, false, stride,
                    AetherSkinnedModel.OFFSET_BONE_INDEX)
                GLES30.glVertexAttribPointer(aBoneWeight, 4, GLES30.GL_UNSIGNED_BYTE, true, stride,
                    AetherSkinnedModel.OFFSET_BONE_WEIGHT)

                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, batch.ibo)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, batch.indexCount,
                    GLES30.GL_UNSIGNED_INT, 0)
            }

            GLES30.glDisableVertexAttribArray(aPosition)
            GLES30.glDisableVertexAttribArray(aNormal)
            GLES30.glDisableVertexAttribArray(aTexcoord)
            GLES30.glDisableVertexAttribArray(aBoneIndex)
            GLES30.glDisableVertexAttribArray(aBoneWeight)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }

        /**
         * Samples the idle at wall-clock time and hands the pose to the uniform block.
         *
         * Wall clock rather than a frame counter, so she keeps time with the world: a dropped frame
         * or a slower device changes how smoothly she moves, never how fast she breathes.
         */
        private fun uploadPose(loaded: AetherSkinnedModel) {
            if (startNanos == 0L) startNanos = System.nanoTime()
            val seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0f
            loaded.sample(seconds, boneScratch)

            val bytes = boneBytes ?: return
            bytes.clear()
            bytes.asFloatBuffer().put(boneScratch, 0, boneScratch.size)
            bytes.position(0)
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, boneUbo)
            GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, boneScratch.size * 4, bytes)
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)
        }

        /**
         * Frames her from roughly the hips up, which is what the idle made possible.
         *
         * THESE NUMBERS CHANGED WHEN SHE STOPPED BEING A STATUE. The mesh's bind pose is an unposed
         * figure whose arms run out to +/-0.72 on a body 1.73 tall, so the static model had to be
         * cropped hard around the head and let the arms run off both edges -- fitting that whole
         * bounding box onto a phone would have put a tiny T-shape behind the text. The idle poses her
         * arms down, which brings her real silhouette to +/-0.228, and a crop tuned for the T-pose
         * then clipped her elbows by about eight millimetres. The frame is wider now: 0.251 of
         * visible half-width against her 0.228, and a vertical span that reaches her clasped hands.
         *
         * The fit takes the LARGER of the two distances that box needs, so the framing survives
         * landscape and tablets: on a tall screen width is the binding constraint, on a wide one
         * height is, and taking the max means neither ever crops her.
         */
        private fun applyFraming(loaded: AetherSkinnedModel) {
            val aspect = viewportWidth.toFloat() / viewportHeight
            val halfTan = tan(Math.toRadians((FOV_Y_DEGREES / 2).toDouble())).toFloat()
            val halfWidth = FRAME_HALF_WIDTH_FRACTION * loaded.height
            val halfHeight = FRAME_HALF_HEIGHT_FRACTION * loaded.height
            val distance = max(halfWidth / (halfTan * aspect), halfHeight / halfTan) / zoom

            val visibleHalfHeight = distance * halfTan
            worldPerPixel = (visibleHalfHeight * 2f) / viewportHeight
            panLimitX = loaded.height * PAN_LIMIT_FRACTION
            panLimitY = loaded.height * PAN_LIMIT_FRACTION

            val centreY = focusY + panY
            eye[0] = panX
            eye[1] = centreY
            eye[2] = distance   // she faces +Z, so the camera belongs on that side
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], panX, centreY, 0f, 0f, 1f, 0f)
            Matrix.perspectiveM(
                projection, 0, FOV_Y_DEGREES, aspect,
                max(0.01f, distance * 0.02f), distance + loaded.height * 4f
            )

            // Turntable about the vertical axis through the focus point: spin first, then tilt.
            // Rotating about the origin instead would swing her head out of frame, because the
            // origin is at her feet.
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, focusY, 0f)
            Matrix.rotateM(modelMatrix, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, yaw, 0f, 1f, 0f)
            Matrix.translateM(modelMatrix, 0, 0f, -focusY, 0f)

            Matrix.multiplyMM(scratch, 0, view, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, scratch, 0)

            // Rotation only, so the upper-left 3x3 of the model matrix is already the correct normal
            // matrix -- no inverse-transpose needed, and none of the shear it guards against can
            // arise here.
            normalMatrix[0] = modelMatrix[0]; normalMatrix[1] = modelMatrix[1]; normalMatrix[2] = modelMatrix[2]
            normalMatrix[3] = modelMatrix[4]; normalMatrix[4] = modelMatrix[5]; normalMatrix[5] = modelMatrix[6]
            normalMatrix[6] = modelMatrix[8]; normalMatrix[7] = modelMatrix[9]; normalMatrix[8] = modelMatrix[10]
        }

        /** Builds the program, the bone uniform block and every GPU buffer for a freshly loaded mesh. */
        private fun setUp(loaded: AetherSkinnedModel) {
            program = buildProgram(loaded.boneCount)
            aPosition = GLES30.glGetAttribLocation(program, "aPosition")
            aNormal = GLES30.glGetAttribLocation(program, "aNormal")
            aTexcoord = GLES30.glGetAttribLocation(program, "aTexcoord")
            aBoneIndex = GLES30.glGetAttribLocation(program, "aBoneIndex")
            aBoneWeight = GLES30.glGetAttribLocation(program, "aBoneWeight")
            uMvp = GLES30.glGetUniformLocation(program, "uMvp")
            uModel = GLES30.glGetUniformLocation(program, "uModel")
            uNormalMatrix = GLES30.glGetUniformLocation(program, "uNormalMatrix")
            uEye = GLES30.glGetUniformLocation(program, "uEye")
            uKeyDir = GLES30.glGetUniformLocation(program, "uKeyDir")
            uFillDir = GLES30.glGetUniformLocation(program, "uFillDir")
            uRimColour = GLES30.glGetUniformLocation(program, "uRimColour")
            uTexture = GLES30.glGetUniformLocation(program, "uTexture")
            uAlphaCutoff = GLES30.glGetUniformLocation(program, "uAlphaCutoff")

            // A 3x4 matrix is exactly three vec4s, so std140's 16-byte array stride lands the twelve
            // floats back to back with no padding and the sampled pose uploads verbatim. At 160
            // bones that is 7680 bytes against the 16384 every ES 3.0 device must provide.
            val floats = loaded.boneCount * AetherSkinnedModel.MATRIX_FLOATS
            boneScratch = FloatArray(floats)
            boneBytes = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            boneUbo = ids[0]
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, boneUbo)
            GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, floats * 4, null, GLES30.GL_DYNAMIC_DRAW)
            val block = GLES30.glGetUniformBlockIndex(program, "Bones")
            if (block != GLES30.GL_INVALID_INDEX) {
                GLES30.glUniformBlockBinding(program, block, 0)
            }
            GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, 0, boneUbo)
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)

            focusY = loaded.minY + FOCUS_FRACTION * loaded.height
            startNanos = 0L
            batches = upload(loaded)
        }

        private fun upload(loaded: AetherSkinnedModel): List<GpuBatch> {
            val result = ArrayList<GpuBatch>(loaded.batches.size)
            val ids = IntArray(2)
            // One texture per distinct map, so a map shared between materials is uploaded once.
            val textures = HashMap<String, Int>()
            for (batch in loaded.batches) {
                if (batch.indexCount == 0) continue
                GLES30.glGenBuffers(2, ids, 0)

                batch.vertices.position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,
                    batch.vertexCount * AetherSkinnedModel.VERTEX_STRIDE,
                    batch.vertices, GLES30.GL_STATIC_DRAW)

                batch.indices.position(0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, batch.indexCount * 4,
                    batch.indices, GLES30.GL_STATIC_DRAW)

                val texture = textures.getOrPut(batch.texture) { uploadTexture(batch.texture) }
                result.add(GpuBatch(ids[0], ids[1], texture, batch.indexCount, batch.cutout))
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            // Opaque first. The cutout shader calls `discard`, which costs the GPU its early depth
            // rejection, so drawing the solid body ahead of the hair lets depth throw away most of
            // the strands behind it before they are ever shaded.
            result.sortBy { it.cutout }
            return result
        }

        /**
         * Decoding happens here on the GL thread rather than on the loader thread, one bitmap at a
         * time, so peak memory is a single decoded image rather than all thirteen at once.
         */
        private fun uploadTexture(assetPath: String): Int {
            val options = BitmapFactory.Options().apply {
                // Straight alpha, not premultiplied. The hair and eyelash maps are alpha cutouts,
                // and premultiplied RGB darkens the semi-transparent fringe of every strand before
                // the cutoff ever gets to decide whether to keep it.
                inPremultiplied = false
            }
            val bitmap = try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, options) }
            } catch (e: Throwable) {
                PrismLogger.logError("AetherModelView", "Missing texture $assetPath", e)
                null
            } ?: return 0

            val handle = IntArray(1)
            GLES30.glGenTextures(1, handle, 0)
            val id = handle[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
            )
            // GL_REPEAT IS LOAD-BEARING, not a default nobody thought about. Character Creator lays
            // this figure out across UDIM tiles: the head occupies U 0-1, the torso 1-2, the arms
            // 2-3, the legs 3-4, the nails 4-5 and the eyelashes 5-6, each material carrying the one
            // tile it sits in. Wrapping is what folds U 2.01 back onto 0.01 of the arm's own map.
            // Under GL_CLAMP_TO_EDGE every material but the head would sample a single edge pixel
            // and smear it across the whole surface.
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            // Without mipmaps the hair -- thousands of thin strands -- crawls with every movement.
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            bitmap.recycle()
            return id
        }

        private fun buildProgram(boneCount: Int): Int {
            val vertex = compile(GLES30.GL_VERTEX_SHADER,
                VERTEX_SHADER.replace("MAX_BONES_COUNT", max(1, boneCount).toString()))
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vertex)
            GLES30.glAttachShader(id, fragment)
            GLES30.glLinkProgram(id)
            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                PrismLogger.logError("AetherModelView", "Shader link failed: " + GLES30.glGetProgramInfoLog(id))
            }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            return id
        }

        private fun compile(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val status = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                PrismLogger.logError("AetherModelView", "Shader compile failed: " + GLES30.glGetShaderInfoLog(id))
            }
            return id
        }

        companion object {
            /**
             * Where the camera looks, as a fraction of her height. 0.762 of 1.73 lands at 1.32,
             * which puts her eyes a quarter of the way down the frame and her clasped hands just
             * inside the bottom of it.
             */
            const val FOCUS_FRACTION = 0.762f

            /**
             * Half-extents of the box the camera fits, as fractions of her height. Chosen so the
             * two constraints bind at almost exactly the same distance on a tall phone, which makes
             * the framing hold steady as the aspect ratio moves either side of it.
             */
            const val FRAME_HALF_WIDTH_FRACTION = 0.145f
            const val FRAME_HALF_HEIGHT_FRACTION = 0.313f

            const val FOV_Y_DEGREES = 35f
            const val PAN_LIMIT_FRACTION = 0.45f

            /**
             * Below this the hair texture's alpha is discarded. Set against the map's real
             * distribution rather than picked out of the air: over the 2048 opacity map a cutoff of
             * 0.1 keeps 24.2% of the atlas, 0.25 keeps 22.2%, 0.35 keeps 21.1% and 0.5 keeps 19.3%.
             * The alpha is feathered rather than binary, so the conventional 0.5 was discarding
             * roughly a tenth of the hair the artist actually painted.
             */
            const val ALPHA_CUTOFF = 0.3f

            val KEY_DIR = normalise(0.42f, 0.55f, 0.72f)
            val FILL_DIR = normalise(-0.6f, 0.1f, 0.45f)

            private fun normalise(x: Float, y: Float, z: Float): FloatArray {
                val length = kotlin.math.sqrt(x * x + y * y + z * z)
                return floatArrayOf(x / length, y / length, z / length)
            }

            /**
             * Skinning runs on the GPU, which is the only reason this is affordable: the pose is 48
             * bytes per bone per frame against four megabytes of vertices, so moving the bones costs
             * orders of magnitude less than moving the mesh would.
             *
             * The bone array is sized at compile time from the mesh's own bone count, which is why
             * the program cannot be built until the model has finished loading.
             */
            val VERTEX_SHADER = """
                #version 300 es
                #define MAX_BONES MAX_BONES_COUNT
                in vec3 aPosition;
                in vec3 aNormal;
                in vec2 aTexcoord;
                in vec4 aBoneIndex;
                in vec4 aBoneWeight;
                layout(std140) uniform Bones { vec4 uBones[MAX_BONES * 3]; };
                uniform mat4 uMvp;
                uniform mat4 uModel;
                uniform mat3 uNormalMatrix;
                out vec3 vNormal;
                out vec2 vUv;
                out vec3 vWorld;

                mat4 boneMatrix(int i) {
                    vec4 a = uBones[i * 3];
                    vec4 b = uBones[i * 3 + 1];
                    vec4 c = uBones[i * 3 + 2];
                    // Stored as three rows of four; a mat4 is built from columns.
                    return mat4(vec4(a.x, b.x, c.x, 0.0),
                                vec4(a.y, b.y, c.y, 0.0),
                                vec4(a.z, b.z, c.z, 0.0),
                                vec4(a.w, b.w, c.w, 1.0));
                }

                void main() {
                    vec4 skinned = vec4(0.0);
                    vec3 skinnedNormal = vec3(0.0);
                    // The weights were quantised to a byte apiece and no longer sum to exactly one;
                    // renormalising here keeps that rounding from showing up as shrunken geometry.
                    float total = aBoneWeight.x + aBoneWeight.y + aBoneWeight.z + aBoneWeight.w;
                    if (total <= 0.0) {
                        skinned = vec4(aPosition, 1.0);
                        skinnedNormal = aNormal;
                    } else {
                        for (int k = 0; k < 4; ++k) {
                            float w = aBoneWeight[k] / total;
                            if (w <= 0.0) continue;
                            mat4 m = boneMatrix(int(aBoneIndex[k] + 0.5));
                            skinned += w * (m * vec4(aPosition, 1.0));
                            skinnedNormal += w * (mat3(m) * aNormal);
                        }
                    }
                    vNormal = uNormalMatrix * skinnedNormal;
                    vUv = aTexcoord;
                    vWorld = (uModel * skinned).xyz;
                    gl_Position = uMvp * skinned;
                }
            """.trimIndent()

            /**
             * EXPOSURE. She is lit to sit just under the page rather than on top of it.
             *
             * ?attr/prismBackground is #F5F5F7, a luminance around 0.96, and there is no dark theme
             * to fall back to -- so a figure lit to full brightness would wash into the page she is
             * standing on. The terms below sum to 1.10, which puts fully lit skin (a texture
             * averaging about 0.85) near 0.93 with a little rolloff, and shadowed skin near 0.26.
             * What separates her from the page is hue rather than luminance: warm skin, brown hair
             * and dark eyes against a neutral grey-white.
             */
            val FRAGMENT_SHADER = """
                #version 300 es
                precision mediump float;
                in vec3 vNormal;
                in vec2 vUv;
                in vec3 vWorld;
                uniform sampler2D uTexture;
                uniform float uAlphaCutoff;
                uniform vec3 uEye;
                uniform vec3 uKeyDir;
                uniform vec3 uFillDir;
                uniform vec3 uRimColour;
                out vec4 outColour;
                void main() {
                    vec4 texel = texture(uTexture, vUv);
                    // Cutout rather than blending. Hair is thousands of overlapping cards, and
                    // blending them correctly needs a per-frame depth sort that her swaying would
                    // invalidate on every frame; a hard test needs no ordering at all.
                    if (texel.a < uAlphaCutoff) discard;

                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    vec3 v = normalize(uEye - vWorld);
                    float key = max(dot(n, uKeyDir), 0.0);
                    float fill = max(dot(n, uFillDir), 0.0);
                    float rim = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.0);
                    vec3 lit = texel.rgb * (0.30 + 0.60 * key + 0.20 * fill) + uRimColour * rim * 0.18;
                    outColour = vec4(lit, 1.0);
                }
            """.trimIndent()
        }
    }

    /**
     * Prefers a multisampled config, because her silhouette -- and especially the hair, which is
     * thousands of thin cards -- stair-steps badly without one. Falls back through 2x to no
     * multisampling at all rather than failing, since a device that cannot give us 4x samples
     * should still show her.
     */
    private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            for (samples in intArrayOf(4, 2, 0)) {
                val found = choose(egl, display, samples)
                if (found != null) return found
            }
            throw IllegalArgumentException("No usable EGL config for Aether's model")
        }

        private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
            val spec = ArrayList<Int>(20).apply {
                add(EGL10.EGL_RED_SIZE); add(8)
                add(EGL10.EGL_GREEN_SIZE); add(8)
                add(EGL10.EGL_BLUE_SIZE); add(8)
                add(EGL10.EGL_ALPHA_SIZE); add(8)
                add(EGL10.EGL_DEPTH_SIZE); add(16)
                add(EGL10.EGL_RENDERABLE_TYPE); add(EGL_OPENGL_ES3_BIT)
                if (samples > 0) {
                    add(EGL10.EGL_SAMPLE_BUFFERS); add(1)
                    add(EGL10.EGL_SAMPLES); add(samples)
                }
                add(EGL10.EGL_NONE)
            }.toIntArray()

            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) return null
            return configs.firstOrNull { it != null }
        }

        private companion object {
            /** EGL_OPENGL_ES3_BIT_KHR; not exposed by the EGL10 constants. */
            const val EGL_OPENGL_ES3_BIT = 0x0040
        }
    }
}
