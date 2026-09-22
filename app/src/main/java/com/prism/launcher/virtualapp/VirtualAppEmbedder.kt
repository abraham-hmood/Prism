package com.prism.launcher.virtualapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import com.prism.launcher.PrismLogger

/**
 * The launcher's side of embedding a virtualized app into a page.
 *
 * Binds [VirtualAppHostService] in `:virtualapp`, asks it to run an app, and attaches the
 * `SurfacePackage` it returns to a [SurfaceView]. The app's code never enters this process; what
 * arrives is a surface and an input channel.
 *
 * ## The host token
 *
 * An embedded hierarchy has to be created against the host window's token, or the window manager has
 * nothing to attach it to and no reason to route input into it. That token comes from
 * `SurfaceView.getHostToken()`, which is not public API below Android 15 -- hence the reflection,
 * and hence [HiddenApi] having to run in this process too. Without a token the app would render and
 * ignore every touch.
 */
class VirtualAppEmbedder(
    private val context: Context,
    private val surfaceView: SurfaceView,
) {

    private val main = Handler(Looper.getMainLooper())

    init {
        // The app is laid out against a size THIS side chooses, so every change to the surface's
        // bounds has to be forwarded. Without it a rotation, an inset change, or simply the page
        // settling after it was swiped to leaves the app rendering at its original size inside a
        // differently sized hole.
        surfaceView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (current != null) resize(view.width, view.height)
        }
    }

    private var service: Messenger? = null
    private var bound = false
    private var pending: String? = null

    /**
     * Held as a field, NOT created per request.
     *
     * A Messenger is a Binder stub, and the only strong reference to it was a local in the method
     * that sent the request. Once that returned, nothing held it: the stub could be collected before
     * the reply arrived, the reply was dropped, and the page sat on "Opening app…" forever while the
     * other process had already logged that it was showing. Intermittent exactly in proportion to
     * when the collector ran.
     */
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            VirtualAppHostService.WHAT_HOST -> onReply(message.data)
            VirtualAppHostService.WHAT_BACK ->
                if (message.data?.getBoolean(VirtualAppHostService.KEY_OK) != true) {
                    // Nothing left to go back to, so going back means leaving the app.
                    onExhausted?.invoke()
                }
        }
        true
    })

    /** The package currently embedded, for the page to label and for teardown. */
    var current: String? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            pending?.let { requested ->
                pending = null
                whenSized { sendHost(requested) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            current = null
            onFailure?.invoke("The virtualized app's process stopped")
        }
    }

    /** Called on the main thread when the app is showing. */
    var onShown: ((label: String) -> Unit)? = null

    /** Called on the main thread when it could not be. */
    var onFailure: ((reason: String) -> Unit)? = null

    /** Called when BACK was pressed and the app had no screen left to return to. */
    var onExhausted: (() -> Unit)? = null

    /** Asks the app to go back one screen. The answer arrives on [onExhausted] if it cannot. */
    fun goBack() {
        runCatching {
            service?.send(Message.obtain(null, VirtualAppHostService.WHAT_BACK).apply {
                replyTo = replies
            })
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Shows [packageName] in the surface.
     *
     * Binding is asynchronous, so a request made before the service has connected is remembered and
     * sent once it does, rather than dropped.
     */
    fun show(packageName: String) {
        if (!isSupported()) {
            onFailure?.invoke("Embedding a virtualized app needs Android 11 or newer")
            return
        }

        // Lifted in THIS process too, and only for this: `SurfaceView.getHostToken()` has no public
        // equivalent below Android 15. Note what is NOT done here -- SystemServiceHook is never
        // installed in the launcher's process, because rewriting package names in Prism's own
        // system calls would be a genuine hazard. Lifting reflection is not the same thing.
        HiddenApi.unlock()

        current = packageName

        // Visible FIRST. A SurfaceView has neither a Surface nor a host token while it is gone, and
        // both are needed before the app is asked for: the token is sent with the request, and
        // without it the window manager has nothing to route touches to.
        surfaceView.visibility = android.view.View.VISIBLE

        // Only once the surface has real bounds. Asking earlier is what made the app render into
        // a band across the top of the page: the view had just been made visible and still carried
        // its stale measured size, so the app was told it had a screen shorter than the one it got.
        whenSized {
            val connected = service
            if (connected != null) sendHost(packageName) else pending = packageName
        }

        if (bound) return

        bound = runCatching {
            context.bindService(
                Intent(context, VirtualAppHostService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!bound) {
            pending = null
            current = null
            onFailure?.invoke("Could not start the virtualized app's process")
        }
    }

    /** Runs [action] once the surface has been laid out, or immediately if it already has. */
    private fun whenSized(action: () -> Unit) {
        if (surfaceView.width > 0 && surfaceView.height > 0) {
            action()
            return
        }
        surfaceView.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: android.view.View,
                left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (view.width <= 0 || view.height <= 0) return
                view.removeOnLayoutChangeListener(this)
                action()
            }
        })
        surfaceView.requestLayout()
    }

    private fun sendHost(packageName: String) {
        val target = service ?: return

        val width = surfaceView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = surfaceView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

        val sent = runCatching {
            target.send(Message.obtain(null, VirtualAppHostService.WHAT_HOST).apply {
                data = Bundle().apply {
                    putString(VirtualAppHostService.KEY_PACKAGE, packageName)
                    putInt(VirtualAppHostService.KEY_WIDTH, width)
                    putInt(VirtualAppHostService.KEY_HEIGHT, height)
                    putInt(VirtualAppHostService.KEY_DISPLAY_ID, surfaceView.display?.displayId ?: 0)
                    putBinder(VirtualAppHostService.KEY_HOST_TOKEN, hostToken())
                }
                replyTo = replies
            })
        }.isSuccess

        if (!sent) onFailure?.invoke("Could not reach the virtualized app's process")
    }

    private fun onReply(data: Bundle?) {
        data ?: return
        data.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader

        if (!data.getBoolean(VirtualAppHostService.KEY_OK)) {
            current = null
            onFailure?.invoke(data.getString(VirtualAppHostService.KEY_REASON) ?: "Unknown failure")
            return
        }

        @Suppress("DEPRECATION")
        val surfacePackage = data.getParcelable<SurfaceControlViewHost.SurfacePackage>(
            VirtualAppHostService.KEY_SURFACE_PACKAGE,
        )

        if (surfacePackage == null) {
            current = null
            onFailure?.invoke("The app produced no surface")
            return
        }

        main.post {
            runCatching {
                // NOTE: the z-order is set once when the view is built, NOT here. Calling
                // setZOrderOnTop on a SurfaceView recreates its surface, and doing that in the same
                // breath as attaching the child orphaned the package -- the app went black and its
                // window disappeared from `dumpsys input` altogether.
                surfaceView.setChildSurfacePackage(surfacePackage)
            }.onFailure {
                PrismLogger.logError(TAG, "Could not attach the app's surface", it)
                onFailure?.invoke("The app's window could not be attached")
                return@post
            }
            onShown?.invoke(data.getString(VirtualAppHostService.KEY_LABEL).orEmpty())
        }
    }

    /**
     * Hands a touch the launcher received to the app it belongs to.
     *
     * The coordinates in [event] are already relative to the surface, which is the same origin the
     * embedded hierarchy uses, so they are forwarded unchanged. See
     * VirtualAppHostService.dispatchTouch for why input is forwarded rather than routed by the
     * window manager.
     */
    fun dispatchTouch(event: android.view.MotionEvent): Boolean {
        val target = service ?: return false
        return runCatching {
            target.send(Message.obtain(null, VirtualAppHostService.WHAT_TOUCH).apply {
                data = Bundle().apply {
                    putParcelable(VirtualAppHostService.KEY_EVENT, event)
                }
            })
            true
        }.getOrDefault(false)
    }

    /** Tells the host the surface changed size, so the app relays out instead of stretching. */
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        runCatching {
            service?.send(Message.obtain(null, VirtualAppHostService.WHAT_RESIZE).apply {
                data = Bundle().apply {
                    putInt(VirtualAppHostService.KEY_WIDTH, width)
                    putInt(VirtualAppHostService.KEY_HEIGHT, height)
                }
            })
        }
    }

    /** Stops the app and releases the binding, which is also what seals its data. */
    fun stop() {
        runCatching { service?.send(Message.obtain(null, VirtualAppHostService.WHAT_STOP)) }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
        current = null
        pending = null
        runCatching { surfaceView.visibility = android.view.View.GONE }
    }

    /**
     * `SurfaceView.getHostToken()`, which has no public equivalent on this API level.
     *
     * Returns null rather than throwing: the embedded app then renders without receiving input,
     * which is worth reporting but not worth refusing to show anything over.
     */
    private fun hostToken(): IBinder? = runCatching {
        (SurfaceView::class.java.getMethod("getHostToken").invoke(surfaceView) as? IBinder)
            .also { PrismLogger.logInfo(TAG, "Host token: ${if (it == null) "none" else "present"}") }
    }.getOrElse {
        PrismLogger.logWarning(TAG, "No host token; the app will not receive touches: ${it.message}")
        null
    }

    private companion object {
        const val TAG = "PrismVirtualApp"
    }
}
