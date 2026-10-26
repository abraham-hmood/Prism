package com.prism.launcher.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.prism.launcher.display.mirror.MirrorStreamServer
import com.prism.launcher.display.mirror.newMirrorToken
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * EXTEND, rendered where the page actually lives.
 *
 * A page slot is not a page. The sender's desktop has its own icons, its Files page its own
 * folders, and its wallet its own balance; telling a display "show a Files page" made it open the
 * display's Files page instead. This renders the page on the SENDER with [DisplayPageFactory],
 * turns it into JPEG frames, and publishes those through the same [MirrorStreamServer] a mimic
 * uses. The receiver does not need to know the difference: it is looking at a picture either way.
 *
 * ## Why a still, refreshed
 *
 * A page that is extended is not the page the sender is touching, so there is no scrolling or
 * typing to follow. It is redrawn once a second and whenever the sender swipes to another page,
 * which keeps clocks and widget counters current without shipping a capture service or asking for
 * a second screen-capture consent. Taps on the display are inert, as they were before: this shows
 * the page, it does not hand the receiver control of the sender.
 *
 * ## Lifecycle
 *
 * Tied to the control link, not to an activity. The launcher can be left -- the sender's next page
 * is still the page the display asked for -- and the stream stops as soon as this end is no longer
 * the sender of a live link. [stop] is idempotent.
 */
object PageMirror {

    private const val TAG = "PrismDisplay"
    private const val MAX_LONG_EDGE = 1280
    private const val JPEG_QUALITY = 72
    private const val REFRESH_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())

    private var server: MirrorStreamServer? = null
    private var token = ""
    private var running = false

    private var page: View? = null
    private var pageKey: String? = null
    private var width = 0
    private var height = 0

    fun isRunning(): Boolean = running

    /** Starts publishing the current next page. Safe to call more than once. */
    fun start(context: Context) {
        if (running) return
        if (!WirelessDisplay.isSending()) {
            WirelessDisplay.reportError("The display link ended before this page could be shared.")
            return
        }
        val app = context.applicationContext
        val metrics = app.resources.displayMetrics
        val scale = min(1.0, MAX_LONG_EDGE.toDouble() / max(metrics.widthPixels, metrics.heightPixels))
        width = (metrics.widthPixels * scale).toInt().coerceAtLeast(320)
        height = (metrics.heightPixels * scale).toInt().coerceAtLeast(320)

        val stream = MirrorStreamServer(
            token = newMirrorToken().also { token = it },
            onError = { message -> Log.w(TAG, "Page stream: $message") },
        )
        val port = try {
            stream.start()
        } catch (error: Exception) {
            Log.w(TAG, "Could not open the page stream: " + (error.message ?: "no port"))
            WirelessDisplay.reportError("The other device could not open a stream for its next page.")
            return
        }

        server = stream
        running = true
        page = null
        pageKey = null
        // Announce before the first frame: a viewer that connects immediately gets the stream's
        // address without waiting a second for a page to be drawn.
        WirelessDisplay.announceMirror(port, token, width, height)
        handler.post(draw)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { server?.close() }
        server = null
        page = null
        pageKey = null
    }

    private val draw = object : Runnable {
        override fun run() {
            if (!running) return
            if (!WirelessDisplay.isConnected() || !WirelessDisplay.isSending()) {
                stop()
                return
            }

            val show = currentShow()
            if (show != null) {
                val key = show.slot + ":" + show.index
                if (key != pageKey || page == null) {
                    pageKey = key
                    // THE PAGE THE USER IS ACTUALLY LOOKING AT, when the launcher still owns
                    // it. The pager keeps its neighbours alive, so the next page is normally
                    // already built. Drawing that view gives the display the sender's own
                    // scroll position, icons and live widgets, and it avoids inflating the
                    // drawer's XML at all -- which is the path that produced the binary XML
                    // error on a non-Activity context.
                    page = livePage(show.index) ?: runCatching {
                        DisplayPageFactory.build(pageContext(), show)
                    }.getOrNull()
                }
                page?.let { publish(it) }
            }

            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * The page to publish, with a layout-based fallback for a launcher that has not reported yet.
     *
     * The normal source is the live pager, whose index is the one the user actually swiped to.
     * A phone whose launcher process is alive but whose pager has not drawn must still answer a
     * desktop's request with a real page rather than an empty stream. The saved slot layout is
     * the same arrangement the pager will show, so the first two slots are the honest guess.
     */
    private fun currentShow(): WirelessDisplay.Show? {
        WirelessDisplay.displayPage()?.let { return it }
        return runCatching {
            val slots = com.prism.launcher.SlotPreferences()
                .getAssignments()
                .map { it.serialize() }
            if (slots.isEmpty()) {
                null
            } else {
                val index = if (slots.size > 1) 1 else 0
                WirelessDisplay.Show(
                    slot = slots[index],
                    index = index,
                    from = runCatching { WirelessDisplay.deviceName() }
                        .getOrDefault("Prism device"),
                )
            }
        }.getOrNull()
    }

    /**
     * The launcher's own view for [index], or null when it is not alive/already created.
     *
     * The LAUNCHER, not the most recent activity. The picker is the most recent activity when a
     * display is opened from it, and looking there returned null for every extended page -- which
     * sent the renderer down the XML-inflation fallback that produced the Binary XML error.
     */
    private fun livePage(index: Int): View? {
        val activity = com.prism.launcher.PrismApp.instance.launcherActivity()
            ?: runCatching {
                com.prism.launcher.PrismApp.instance.recentActivity()
                    as? com.prism.launcher.LauncherActivity
            }.getOrNull()
            ?: return null
        return runCatching { activity.findPageViewAt(index) }.getOrNull()
    }

    /**
     * The context a page is built with.
     *
     * NOT the raw Application. A page's layout is full of Material components -- the app drawer's
     * `MaterialCardView` is the first one -- and those inflate against the context's theme. The
     * application theme has no Material Components attributes, so every attempt failed with
     * "Binary XML file ... Error inflating class <unknown>" and the display was shown that error
     * instead of the page.
     *
     * An Activity is preferred over a themed Application wrapper because Activity is what these
     * layouts normally live in: it carries the AppCompat factory, the window theme and the
     * configuration a Material view expects. The wrapper remains only for a process whose last
     * Activity has already been destroyed.
     */
    private fun pageContext(): Context {
        val launcher = com.prism.launcher.PrismApp.instance.launcherActivity()
        val recent = com.prism.launcher.PrismApp.instance.recentActivity()
        val base = launcher ?: recent ?: com.prism.launcher.PrismApp.instance
        return if (base is android.app.Activity) base
        else androidx.appcompat.view.ContextThemeWrapper(
            base,
            com.prism.launcher.R.style.Theme_PrismLauncher,
        )
    }

    private fun publish(view: View) {
        if (width <= 0 || height <= 0) return
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (error: Exception) {
            Log.w(TAG, "Could not allocate the page frame: " + error.message)
            return
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)

            if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
                // A LIVE PAGE MUST NOT BE RE-LAID OUT. Calling measure/layout on the launcher's
                // attached view at display dimensions would rearrange the sender's own screen
                // behind the display. Draw it where it is and scale the canvas instead.
                val save = canvas.save()
                canvas.scale(
                    width / view.width.toFloat(),
                    height / view.height.toFloat(),
                )
                view.draw(canvas)
                canvas.restoreToCount(save)
            } else {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
                view.draw(canvas)
            }

            val jpeg = ByteArrayOutputStream(128 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            val bytes = jpeg.toByteArray()
            if (bytes.isEmpty()) return
            server?.sendFrame(width, height, bytes)
        } catch (error: Exception) {
            Log.w(TAG, "Skipped a page frame: " + error.message)
        } finally {
            bitmap.recycle()
        }
    }
}
