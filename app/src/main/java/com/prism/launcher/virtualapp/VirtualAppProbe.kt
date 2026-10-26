package com.prism.launcher.virtualapp

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import com.prism.launcher.PrismLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers "would this app run inside Prism?" without launching it for the user to see.
 *
 * ## Why it exists
 *
 * The only way to know whether an app virtualizes is to virtualize it. Doing that by hand, app by
 * app, is what produced the list of failures this exists to replace -- so the check does the same
 * thing the real host does, in a throwaway surface nobody is looking at, and reports what happened.
 *
 * It uses [VirtualPackage.loadInstalled], so it reads the installed app in place rather than backing
 * it up: checking three hundred apps must not cost three hundred megabytes each, and an app the user
 * never opens should not be copied to the vault just to be tested.
 *
 * ## What counts as success
 *
 * A FIRST FRAME. Not "onCreate returned" -- an app whose `onCreate` returns and then sits on its
 * splash screen forever has not virtualized successfully, and that distinction is the whole reason
 * this reports three outcomes instead of a boolean:
 *
 *  - [Verdict.OK] the app drew;
 *  - [Verdict.STUCK] it started but never painted, with the main thread's stack;
 *  - [Verdict.FAILED] it threw, with the app's own exception.
 *
 * ## The throwaway screen
 *
 * An app's activity needs a real window to draw into, and a window needs a display. A private
 * [VirtualDisplay] with a [Presentation] on it is exactly that: it exists, it is owned by Prism, it
 * is not visible to anyone, and -- unlike an overlay window -- it needs no permission.
 *
 * The first attempt at this used a `SurfaceControlViewHost` with a null host token, which cannot
 * work: the windowless window manager still asks the system for an input channel, and the system
 * refuses without `INTERNAL_SYSTEM_WINDOW`. A probe needing a permission that only system apps can
 * hold would only ever measure system apps.
 *
 * ## Threading
 *
 * Building a view hierarchy has to happen on this process's main thread, and drawing needs that
 * looper free to run. So the probe is posted to the main thread and the CALLING thread waits for
 * either a first frame or a deadline. If the guest blocks the main thread, the deadline still fires
 * -- the caller is not on that thread -- and the report says where the main thread was stuck.
 */
object VirtualAppProbe {

    private const val TAG = "PrismVirtualApp"

    enum class Verdict { OK, STUCK, FAILED }

    /** A phone-shaped screen, so layout code takes the same branches it would on the real one. */
    private const val SCREEN_WIDTH = 360
    private const val SCREEN_HEIGHT = 800
    private const val SCREEN_DPI = 160

    /**
     * [detail] is one sentence for the report; [launchActivity] is what was actually started, which
     * is worth recording separately because picking the wrong entry point is its own class of bug.
     */
    data class Outcome(
        val verdict: Verdict,
        val detail: String,
        val launchActivity: String? = null,
        val millis: Long = 0,
    )

    /**
     * Probes [packageName]. **Not** for the main thread -- it waits for the result.
     *
     * Leaves nothing running: the surface is released and the hosted activity destroyed before this
     * returns. When the main thread is wedged that cleanup cannot happen, which is exactly when the
     * process is worth discarding -- see [VirtualAppCheckService].
     */
    fun probe(context: Context, packageName: String, timeoutMs: Long = 12_000): Outcome {
        val started = System.currentTimeMillis()
        val result = AtomicReference<Outcome?>(null)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())

        val released = AtomicBoolean(false)
        val displayRef = AtomicReference<VirtualDisplay?>(null)
        val presentationRef = AtomicReference<Presentation?>(null)
        val readerRef = AtomicReference<ImageReader?>(null)
        val runner = AtomicReference<VirtualAppRunner?>(null)

        main.post {
            var presentation: Presentation? = null
            try {
                if (!HiddenApi.unlock()) {
                    result.set(Outcome(Verdict.FAILED, "This device does not allow the framework access virtualization needs"))
                    done.countDown()
                    return@post
                }

                VirtualAppDiagnostics.installCrashHandler(context)
                VirtualAppDiagnostics.clear(context, packageName)
                VirtualAppDiagnostics.stage(context, packageName, "check: loading its APKs")

                val virtual = VirtualPackage.loadInstalled(context, packageName)
                if (virtual == null) {
                    result.set(Outcome(Verdict.FAILED, "$packageName could not be read from its installed APKs"))
                    done.countDown()
                    return@post
                }

                val activityName = virtual.launchActivity
                if (activityName.isNullOrBlank()) {
                    result.set(Outcome(Verdict.FAILED, "${virtual.packageName} declares no launchable activity"))
                    done.countDown()
                    return@post
                }

                SystemServiceHook.install(packageName, context.packageName)

                VirtualAppDiagnostics.stage(context, packageName, "check: building its Application")
                val application = VirtualAppRunner(context).createApplication(virtual)

                // A private display of Prism's own, never shown. Its surface is an ImageReader so
                // the display has something valid to render into without a window on the phone's
                // real screen.
                val manager = context.getSystemService(DisplayManager::class.java)
                    ?: throw IllegalStateException("No display service")

                val reader = ImageReader.newInstance(SCREEN_WIDTH, SCREEN_HEIGHT, android.graphics.PixelFormat.RGBA_8888, 2)
                readerRef.set(reader)

                val display = manager.createVirtualDisplay(
                    "prism-virtual-check",
                    SCREEN_WIDTH,
                    SCREEN_HEIGHT,
                    SCREEN_DPI,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                ) ?: throw IllegalStateException("Could not create a virtual display")
                displayRef.set(display)

                val displayContext = context.createDisplayContext(display.display)
                presentation = Presentation(displayContext, display.display).apply {
                    setContentView(FrameLayout(displayContext))
                }
                presentationRef.set(presentation)
                presentation.show()

                val container = presentation.findViewById<FrameLayout>(android.R.id.content)
                val windowManager = displayContext.getSystemService(WindowManager::class.java)
                    ?: throw IllegalStateException("No window manager on the probe display")

                val live = VirtualAppRunner(context)
                runner.set(live)

                VirtualAppDiagnostics.guestCode(context, packageName, "check: running $activityName")
                val decor = live.createActivity(
                    virtual,
                    activityName,
                    application,
                    windowManager,
                    // The throwaway window's own token, so anything the app opens itself -- a
                    // dialog, a popup -- has a real window to attach to.
                    runCatching { presentation.window?.attributes?.token }.getOrNull(),
                )

                // Armed BEFORE the view is added, so a first frame that happens during the layout
                // pass is still seen.
                decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (result.compareAndSet(
                                null,
                                Outcome(
                                    Verdict.OK,
                                    virtual.startupTrouble?.let { "drew, but $it" } ?: "drew its first frame",
                                    activityName,
                                    System.currentTimeMillis() - started,
                                ),
                            )
                        ) {
                            done.countDown()
                        }
                        return true
                    }
                })

                container.addView(
                    decor,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            } catch (e: Throwable) {
                PrismLogger.logError(TAG, "Probe of $packageName failed", e)
                if (result.compareAndSet(null, Outcome(Verdict.FAILED, describe(e), null, System.currentTimeMillis() - started))) {
                    done.countDown()
                }
                runCatching { presentation?.dismiss() }
            }
        }

        val finished = runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

        if (!finished) {
            // The main thread never got to a first frame. Read its stack from THIS thread, which is
            // the only thread still running.
            val frames = runCatching { Looper.getMainLooper().thread.stackTrace }.getOrNull()
            val where = frames?.take(8)?.joinToString("\n") { "  at $it" }.orEmpty()
            result.compareAndSet(
                null,
                Outcome(
                    Verdict.STUCK,
                    "started but never drew within ${timeoutMs / 1000}s" +
                        if (where.isBlank()) "" else " -- its main thread was in:\n$where",
                    millis = System.currentTimeMillis() - started,
                ),
            )
            PrismLogger.logError(TAG, "Probe of $packageName never drew:\n$where")
        }

        main.post {
            if (released.compareAndSet(false, true)) {
                runCatching { runner.get()?.destroy() }
                runCatching { presentationRef.get()?.dismiss() }
                runCatching { displayRef.get()?.release() }
                runCatching { readerRef.get()?.close() }
            }
        }

        return result.get() ?: Outcome(Verdict.FAILED, "the probe ended without a result")
    }
}
