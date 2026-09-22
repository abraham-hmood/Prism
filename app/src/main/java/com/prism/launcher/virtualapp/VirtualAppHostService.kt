package com.prism.launcher.virtualapp

import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.prism.launcher.PrismLogger

/**
 * Runs a virtualized app and hands its rendered window to the launcher to display.
 *
 * ## Why this is not simply hosted in the launcher's own process
 *
 * Showing a virtualized app ON the virtualization page means showing it inside `LauncherActivity`,
 * which lives in Prism's MAIN process -- and that is exactly where [VaultService] holds the vault
 * key. Loading another app's code there would hand that code an address space containing the
 * passphrase, undoing the one security property this feature was asked to have. It would also make
 * [SystemServiceHook]'s package-name rewriting unsafe, because it would start rewriting arguments
 * belonging to Prism's own launcher code.
 *
 * `SurfaceControlViewHost` is the way to have both. The app keeps running here, in `:virtualapp`,
 * where it can reach neither the key nor Prism's data; what crosses to the launcher is a
 * `SurfacePackage` -- a handle to a composited surface plus an input channel -- which the page
 * embeds in a `SurfaceView`. The pixels appear on the page; the code never leaves this process.
 *
 * Input is routed by the window manager rather than forwarded by hand: an embedded hierarchy created
 * against the host's token receives touches directly, which is what makes this different from
 * rendering to a `VirtualDisplay` (where input would need system-level injection permission).
 *
 * ## Requires API 30
 *
 * `SurfaceControlViewHost` arrived in Android 11. Below that the launcher falls back to
 * [VirtualAppActivity], which shows the same app in a window of its own.
 */
class VirtualAppHostService : Service() {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { handle(it) }) }

    /** One live embedding at a time; the page shows one app. */
    private var session: Session? = null

    /**
     * One embedded app, and the activities it has stacked up inside this page.
     *
     * The stack is what makes an app navigable. A virtualized app starting its own second activity
     * cannot go through the system -- see SystemServiceHook.onStartVirtualActivity -- so the target
     * is built here and pushed on top of the one showing, and BACK pops it. Same window, same
     * surface, same process: only the view in the container changes.
     */
    private class Session(
        val packageName: String,
        val label: String,
        val virtual: VirtualPackage,
        val viewHost: SurfaceControlViewHost,
        val container: FrameLayout,
        val application: android.app.Application?,
        val hostToken: IBinder?,
    ) {
        val stack = ArrayDeque<Pair<VirtualAppRunner, android.view.View>>()
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        handler.post { release() }
        return false
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        // EVERYTHING is read out of the Message here, synchronously.
        //
        // A Message is recycled as soon as the handler returns, so holding on to one across a thread
        // hop and reading `replyTo` afterwards reads a field that has since been cleared and reused.
        // The symptom was silent and one-sided: the service logged that it had embedded the app, and
        // the launcher sat on "Opening app…" forever because the reply went to nobody.
        val what = message.what
        val replyTo = message.replyTo
        val data = message.data

        when (what) {
            WHAT_HOST -> host(data, replyTo)
            WHAT_RESIZE -> resize(data)
            WHAT_TOUCH -> dispatchTouch(data)
            WHAT_BACK -> reply(replyTo, what, Bundle().apply { putBoolean(KEY_OK, pop()) })
            WHAT_STOP -> {
                release()
                // Fire-and-forget: the launcher unbinds straight after asking, so there is
                // usually nobody left to answer and that is not a problem.
                reply(replyTo, what, Bundle().apply { putBoolean(KEY_OK, true) }, expected = false)
            }
        }
        return true
    }

    /**
     * Loads the app and puts its window into a `SurfaceControlViewHost`.
     *
     * The load happens on a worker thread and the hosting on the main thread, for the same reasons
     * as everywhere else in this feature: the unseal is a cross-process round trip that must not
     * block the thread the reply arrives on, and an Activity's `onCreate` expects to be on the main
     * thread of its process.
     */
    private fun host(data: Bundle?, replyTo: Messenger?) {
        data ?: return
        val packageName = data.getString(KEY_PACKAGE) ?: return
        val width = data.getInt(KEY_WIDTH).coerceAtLeast(1)
        val height = data.getInt(KEY_HEIGHT).coerceAtLeast(1)
        val hostToken = data.getBinder(KEY_HOST_TOKEN)
        val displayId = data.getInt(KEY_DISPLAY_ID, 0)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reply(replyTo, WHAT_HOST, failure("Embedding needs Android 11 or newer"))
            return
        }

        // Already showing it: hand back the surface it is already rendering into, rather than
        // tearing the app down and reloading it. The page view is recycled whenever the user swipes
        // away and back, and reloading on every return would lose whatever the app was doing.
        session?.takeIf { it.packageName == packageName }?.let { live ->
            reply(replyTo, WHAT_HOST, Bundle().apply {
                putBoolean(KEY_OK, true)
                putParcelable(KEY_SURFACE_PACKAGE, live.viewHost.surfacePackage)
                putString(KEY_LABEL, live.label)
            })
            runCatching { live.viewHost.relayout(width, height) }
            return
        }

        if (!HiddenApi.unlock()) {
            reply(replyTo, WHAT_HOST, failure("This device will not allow the framework access virtualization needs"))
            return
        }
        SystemServiceHook.install(packageName, getPackageName())

        Thread({
            val runner = VirtualAppRunner(this)
            val loaded = runner.load(packageName)

            handler.post {
                val virtual = loaded.getOrNull()
                if (virtual == null) {
                    val reason = loaded.exceptionOrNull()
                    PrismLogger.logError(TAG, "Could not load $packageName", reason)
                    reply(replyTo, WHAT_HOST, failure(reason?.message ?: "$packageName could not be loaded"))
                    return@post
                }

                val activityName = virtual.launchActivity
                if (activityName.isNullOrBlank()) {
                    reply(replyTo, WHAT_HOST, failure("${virtual.packageName} declares no launchable activity"))
                    return@post
                }

                runCatching { embed(virtual, activityName, width, height, hostToken, displayId) }
                    .onSuccess { reply(replyTo, WHAT_HOST, it) }
                    .onFailure {
                        PrismLogger.logError(TAG, "Could not host $packageName/$activityName", it)
                        reply(replyTo, WHAT_HOST, failure("${it.javaClass.simpleName}: ${it.message}"))
                    }
            }
        }, "virtualapp-host-$packageName").apply { isDaemon = true; start() }
    }

    private fun embed(
        virtual: VirtualPackage,
        activityName: String,
        width: Int,
        height: Int,
        hostToken: IBinder?,
        displayId: Int,
    ): Bundle {
        release()

        val display = getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: throw IllegalStateException("No display $displayId")

        val viewHost = SurfaceControlViewHost(this, display, hostToken)

        // The activity's decor goes INTO a container rather than being handed to the view host
        // directly. A DecorView expects to be the child of something that behaves like a window's
        // content frame, and this is the same shape that already works in VirtualAppActivity --
        // there is no reason to make the embedded path the one that is different.
        val container = FrameLayout(this)
        viewHost.setView(container, width, height)

        val application = VirtualAppRunner(this).createApplication(virtual)
        val label = virtual.applicationInfo.loadLabel(packageManager).toString()

        session = Session(
            packageName = virtual.packageName,
            label = label,
            virtual = virtual,
            viewHost = viewHost,
            container = container,
            application = application,
            hostToken = hostToken,
        )

        // From here on, in-app navigation comes back through this.
        SystemServiceHook.onStartVirtualActivity = { intent -> startInPlace(intent) }

        push(activityName)

        PrismLogger.logInfo(TAG, "Embedded ${virtual.packageName}/$activityName at ${width}x$height")
        return Bundle().apply {
            putBoolean(KEY_OK, true)
            putParcelable(KEY_SURFACE_PACKAGE, viewHost.surfacePackage)
            putString(KEY_LABEL, label)
        }
    }

    /**
     * Builds [activityName] and puts it on top of whatever is showing.
     *
     * The one below is detached rather than destroyed, so going back does not rebuild it.
     */
    private fun push(activityName: String) {
        val live = session ?: return
        val runner = VirtualAppRunner(this)
        val decor = runner.createActivity(
            live.virtual,
            activityName,
            live.application,
            getSystemService(WindowManager::class.java),
            live.hostToken,
        )

        live.stack.lastOrNull()?.let { (_, view) -> live.container.removeView(view) }
        live.container.addView(
            decor,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        live.stack.addLast(runner to decor)
    }

    /**
     * Goes back one activity. Returns false when there is nothing left to go back to, which is the
     * launcher's cue to close the app entirely.
     */
    private fun pop(): Boolean {
        val live = session ?: return false
        if (live.stack.size <= 1) return false

        val (runner, view) = live.stack.removeLast()
        live.container.removeView(view)
        runCatching { runner.destroy() }

        live.stack.lastOrNull()?.let { (_, previous) ->
            live.container.addView(
                previous,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return true
    }

    /**
     * Hosts an activity the app asked the system to start.
     *
     * Only ever its own: the intercept upstream checks that the component belongs to a virtualized
     * package before getting here. Anything else -- a URL, a share, another app -- goes to the
     * system untouched, which is what should happen.
     */
    private fun startInPlace(intent: Intent): Boolean {
        val live = session ?: return false
        val target = intent.component?.className ?: return false
        if (live.virtual.packageName != intent.component?.packageName) return false

        handler.post {
            runCatching { push(target) }.onFailure {
                PrismLogger.logError(TAG, "Could not host $target in place", it)
            }
        }
        return true
    }

    /**
     * Delivers a touch the launcher received on the app's behalf.
     *
     * ## Why input is forwarded rather than routed
     *
     * The window manager is supposed to route touches into an embedded hierarchy created against the
     * host's token, and on this device it does not, in two different ways depending on the surface's
     * z-order. With the surface at its default depth -- behind the host window, which punches a hole
     * to show it -- `dumpsys input` lists the embedded window BELOW the launcher's, whose touchable
     * region covers the whole screen, so the launcher swallows every tap. Raised above the host
     * window instead, the embedded window stops being registered for input at all.
     *
     * So the launcher takes the touch, which it was going to get anyway, and hands it over. The
     * coordinates already arrive relative to the SurfaceView, which is the same origin as this
     * hierarchy, so nothing has to be transformed.
     *
     * The limit worth naming: this carries pointers, not focus. Taps, drags and scrolls work;
     * keyboard focus and the IME are not wired through it.
     */
    private fun dispatchTouch(data: Bundle?) {
        data ?: return
        data.classLoader = android.view.MotionEvent::class.java.classLoader
        @Suppress("DEPRECATION")
        val event = data.getParcelable<android.view.MotionEvent>(KEY_EVENT) ?: return

        // Already on the main looper -- the Messenger was built with it -- so the hosted view
        // hierarchy is touched from the thread it expects.
        runCatching { session?.container?.dispatchTouchEvent(event) }
        event.recycle()
    }

    /** Follows the page's size, so a rotation or an inset change does not leave the app stretched. */
    private fun resize(data: Bundle?) {
        data ?: return
        val width = data.getInt(KEY_WIDTH).coerceAtLeast(1)
        val height = data.getInt(KEY_HEIGHT).coerceAtLeast(1)
        handler.post {
            runCatching { session?.viewHost?.relayout(width, height) }
        }
    }

    private fun release() {
        val current = session ?: return
        session = null
        SystemServiceHook.onStartVirtualActivity = null
        current.stack.forEach { (runner, _) -> runCatching { runner.destroy() } }
        current.stack.clear()
        runCatching { current.viewHost.release() }
        // Sealed on the way out, so the plaintext lives no longer than the app does. VaultService
        // also seals on unbind, so a process killed outright is still covered.
        VaultBridge.sealAsync(this, current.packageName)
        PrismLogger.logInfo(TAG, "Released ${current.packageName}")
    }

    private fun failure(reason: String) = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_REASON, reason)
    }

    private fun reply(replyTo: Messenger?, what: Int, payload: Bundle, expected: Boolean = true) {
        if (replyTo == null) {
            if (expected) PrismLogger.logWarning(TAG, "Nobody to reply to for message $what")
            return
        }
        runCatching {
            replyTo.send(Message.obtain(null, what).apply { data = payload })
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not deliver the reply for $what: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "PrismVirtualApp"

        const val WHAT_HOST = 1
        const val WHAT_RESIZE = 2
        const val WHAT_STOP = 3
        const val WHAT_TOUCH = 4
        const val WHAT_BACK = 5

        const val KEY_PACKAGE = "package"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_HOST_TOKEN = "host_token"
        const val KEY_DISPLAY_ID = "display_id"
        const val KEY_OK = "ok"
        const val KEY_REASON = "reason"
        const val KEY_SURFACE_PACKAGE = "surface_package"
        const val KEY_LABEL = "label"
        const val KEY_EVENT = "event"
    }
}
