package com.prism.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.prism.core.PrismPlatform
import com.prism.launcher.calls.MeshVideo
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_videoio
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_videoio.VideoCapture
import javax.swing.SwingUtilities

/**
 * This computer's camera, for mesh video calls.
 *
 * ## The same frames the phone sends
 *
 * A call tile is about 320 pixels wide and the mesh carries independent JPEG frames (see
 * [MeshVideo]), so the camera is read at whatever the driver offers, shrunk to 320 wide and encoded
 * at the phone's quality, eight times a second. The self-view is decoded from those same bytes, so
 * what the user sees of themselves is exactly what the other side receives.
 *
 * ## Why OpenCV
 *
 * The JVM has no camera API. OpenCV's VideoCapture is one call on every desktop platform -- DirectShow
 * on Windows (opened explicitly: the default Media Foundation backend takes several seconds to open a
 * webcam), AVFoundation on macOS, V4L2 on Linux -- and its encoder produces the JPEG directly from the
 * captured matrix without a round trip through BufferedImage.
 *
 * ## Owned by the call, not by a screen
 *
 * [DesktopIncomingCalls] starts this when a video call goes live and stops it when the call ends, so
 * the camera light can never outlive the call that turned it on.
 */
object DesktopCamera {

    private const val TAG = "PrismDesktopCamera"
    private const val WIDTH = 320
    private const val JPEG_QUALITY = 60
    private const val FRAME_INTERVAL_MS = 125L

    /** The latest self-view frame, for the call panel. Null while the camera is off. */
    val selfFrame = mutableStateOf<ImageBitmap?>(null)

    /** Why the camera is not running, when it was asked to and could not. */
    val problem = mutableStateOf<String?>(null)

    @Volatile var running: Boolean = false
        private set

    @Volatile private var worker: Thread? = null

    /** Opens the chosen camera (camera 0 by default) and starts sending. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        problem.value = null
        val thread = Thread({ captureLoop() }, "prism-desktop-camera").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    /** Stops sending and releases the camera, so its light goes out. */
    @Synchronized
    fun stop() {
        running = false
        worker = null
        SwingUtilities.invokeLater { selfFrame.value = null }
    }

    private fun captureLoop() {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        // The camera chosen in Settings → Phone and Meshnet Calls; camera 0 when none is.
        val index = DesktopCameraDevices.chosenIndex()
        val capture = runCatching {
            if (windows) VideoCapture(index, opencv_videoio.CAP_DSHOW) else VideoCapture(index)
        }.getOrElse { error ->
            fail("The camera library could not load: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        try {
            if (!capture.isOpened) {
                fail("No camera could be opened. Check that one is connected and not in use by another app.")
                return
            }
            PrismPlatform.log.info(TAG, "Camera $index open")
            val frame = Mat()
            val small = Mat()
            val params = IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            var failures = 0
            while (running && worker === Thread.currentThread()) {
                val started = System.currentTimeMillis()
                if (!capture.read(frame) || frame.empty()) {
                    // A webcam often returns a few empty frames while it wakes up; a run of them
                    // means it has gone away.
                    if (++failures > 40) {
                        fail("The camera stopped sending pictures.")
                        break
                    }
                    Thread.sleep(50)
                    continue
                }
                failures = 0
                val height = (frame.rows() * WIDTH / frame.cols().coerceAtLeast(1)).coerceAtLeast(1)
                opencv_imgproc.resize(frame, small, Size(WIDTH, height))
                val buffer = BytePointer()
                if (opencv_imgcodecs.imencode(".jpg", small, buffer, params)) {
                    val jpeg = ByteArray(buffer.limit().toInt())
                    buffer.get(jpeg)
                    // Rotation 0: a desktop camera is mounted upright, unlike a phone's sensor.
                    MeshVideo.send(jpeg, 0)
                    val preview = runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(jpeg).toComposeImageBitmap()
                    }.getOrNull()
                    if (preview != null) SwingUtilities.invokeLater { if (running) selfFrame.value = preview }
                }
                buffer.deallocate()
                val wait = FRAME_INTERVAL_MS - (System.currentTimeMillis() - started)
                if (wait > 0) Thread.sleep(wait)
            }
            params.deallocate()
            small.release()
            frame.release()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            fail("The camera failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { capture.release() }
            PrismPlatform.log.info(TAG, "Camera released")
        }
    }

    private fun fail(reason: String) {
        PrismPlatform.log.warn(TAG, reason)
        running = false
        SwingUtilities.invokeLater {
            problem.value = reason
            selfFrame.value = null
        }
    }
}
