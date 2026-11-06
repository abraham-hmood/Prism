package com.prism.launcher.ar

import android.app.Activity
import android.app.Presentation
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.prism.launcher.SlotAssignment
import com.prism.launcher.display.DisplayPageFactory

/**
 * One Prism page, alive and drawn onto a texture so the AR home screen can put it on a wall.
 *
 * The page is the real page -- the same View the pager shows -- hosted in a [Presentation] on a
 * private [VirtualDisplay] whose surface is a [SurfaceTexture] the renderer samples. This is how
 * Flutter hosts Android views inside its own GL scene: the view hierarchy is attached to a real
 * window, so it lays out, animates, scrolls and plays video exactly as it does in the pager, and
 * touches are delivered to it as ordinary [MotionEvent]s in its own pixel coordinates.
 *
 * The display uses the phone's own density and the phone's portrait shape, so a page placed on a
 * wall looks like the phone's page, not a stretched one.
 *
 * Main thread: [attach], [detachView], [reattachView], [dispatch], [release].
 * GL thread: [createTexture], [latch], [textureId], [transform].
 */
class ArPagePanel(
    val key: String,
    val slot: SlotAssignment,
    val index: Int,
    val title: String,
    val widthPx: Int,
    val heightPx: Int,
    private val densityDpi: Int,
) {
    @Volatile var textureId: Int = 0
        private set
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var frameAvailable = false
    val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    var page: View? = null
        private set

    /** True while the page is popped up out of the room into a window. */
    @Volatile var poppedOut: Boolean = false
        private set

    val aspect: Float get() = widthPx.toFloat() / heightPx

    private val main = Handler(Looper.getMainLooper())

    /** GL thread: makes the texture the page is drawn into, then attaches the page on the main thread. */
    fun createTexture(activity: Activity) {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textureId = ids[0]
        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(widthPx, heightPx)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        surfaceTexture = texture
        val target = Surface(texture)
        surface = target
        main.post { attach(activity, target) }
    }

    /** GL thread: takes the page's newest frame, if it drew one. */
    fun latch() {
        val texture = surfaceTexture ?: return
        if (!frameAvailable) return
        frameAvailable = false
        runCatching {
            texture.updateTexImage()
            texture.getTransformMatrix(transform)
        }
    }

    private fun attach(activity: Activity, target: Surface) {
        if (activity.isFinishing || activity.isDestroyed) return
        val manager = activity.getSystemService(DisplayManager::class.java) ?: return
        val virtual = runCatching {
            manager.createVirtualDisplay(
                "prism-ar-$key", widthPx, heightPx, densityDpi, target,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        }.getOrNull() ?: return
        display = virtual
        val holder = FrameLayout(activity)
        container = holder
        val view = DisplayPageFactory.buildPage(activity, slot, index)
        page = view
        holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        presentation = object : Presentation(activity, virtual.display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(holder)
            }
        }.also { p -> runCatching { p.show() } }
    }

    /**
     * Delivers a touch to the page where it is, as if a finger were on it. [x] and [y] are
     * fractions across and down the page; [action] is a MotionEvent action.
     */
    fun dispatch(action: Int, x: Float, y: Float, downTime: Long) {
        val holder = container ?: return
        if (poppedOut) return
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x * widthPx, y * heightPx, 0)
        runCatching { holder.dispatchTouchEvent(event) }
        event.recycle()
    }

    /** Takes the live page out of the room, to show it in a window. */
    fun detachView(): View? {
        val view = page ?: return null
        container?.removeView(view)
        poppedOut = true
        return view
    }

    /** Puts the page back on its wall. */
    fun reattachView() {
        val view = page ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        container?.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        poppedOut = false
    }

    /** Main thread. The texture itself is freed with the GL context. */
    fun release() {
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { display?.release() }
        display = null
        runCatching { surface?.release() }
        surface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        container = null
        page = null
    }
}
