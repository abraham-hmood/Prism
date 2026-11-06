package com.prism.desktop.ar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.prism.core.PrismPlatform
import com.prism.launcher.spatial.ArRoomSession
import com.prism.launcher.spatial.Intrinsics
import com.prism.launcher.spatial.MonocularDepth
import com.prism.launcher.spatial.Pose
import com.prism.desktop.DesktopCameraDevices
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_videoio
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_videoio.VideoCapture
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.FloatBuffer
import javax.swing.SwingUtilities

/**
 * The desktop's eyes for the AR home screen: the webcam, and depth estimated from it.
 *
 * ## Parity with the phone and with Quest
 *
 * The room is found by the same pipeline everywhere -- depth fused into a model of the room, the
 * floor, ceiling and walls labelled in that model ([com.prism.launcher.spatial.RoomLayout]). What
 * differs is where depth comes from. Quest 3 has a depth sensor and a phone has ARCore's depth; a
 * webcam has neither, so depth is estimated from each picture by Depth Anything V2 (small,
 * int8, ~27 MB, downloaded the first time) and made metric and level by
 * [MonocularDepth]. The webcam does not move, so its pose is fixed and every frame adds to the same
 * view of the room, which averages the network's noise away.
 */
object DesktopArScanner {

    private const val TAG = "PrismDesktopAr"
    private const val MODEL_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx"
    private const val MODEL_INPUT = 518
    private const val GRID_W = 192

    /** The camera picture, for the background. */
    val frame = mutableStateOf<ImageBitmap?>(null)

    /** What the scanner is doing, for the hint line. */
    val status = mutableStateOf("Starting the camera…")

    /** The room being scanned. A new one each time the AR home opens. */
    @Volatile var room: ArRoomSession = ArRoomSession()
        private set

    /** The camera's pose and intrinsics in CAMERA IMAGE pixels, once depth has been solved. */
    @Volatile var camera: Pair<Pose, Intrinsics>? = null
        private set

    /** The latest metric depth, on the solving grid; for occlusion and for measuring where a page lands. */
    @Volatile var depth: com.prism.launcher.spatial.DepthFrame? = null
        private set

    /** The latest camera picture as a Skia image, for drawing real objects back over pages. */
    @Volatile var picture: org.jetbrains.skia.Image? = null
        private set

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null

    fun modelFile(): File = File(PrismPlatform.host.documentsDir(), "Models/depth-anything-v2-small-int8.onnx")

    @Synchronized
    fun start() {
        if (running) return
        running = true
        room = ArRoomSession()
        camera = null
        depth = null
        picture = null
        val thread = Thread({ loop() }, "prism-desktop-ar").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        running = false
        worker = null
    }

    private fun post(text: String) = SwingUtilities.invokeLater { status.value = text }

    private fun ensureModel(): File? {
        val file = modelFile()
        if (file.isFile && file.length() > 10_000_000) return file
        post("Downloading the depth model (27 MB, once)…")
        return runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            val connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it, 256 * 1024) } }
            check(partial.length() > 10_000_000) { "the download was too small" }
            partial.renameTo(file)
            file
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Depth model download failed: ${it.message}")
            post("Could not download the depth model: ${it.message}. Check the connection and reopen.")
        }.getOrNull()
    }

    private fun loop() {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val index = DesktopCameraDevices.chosenIndex()
        val capture = runCatching { if (windows) VideoCapture(index, opencv_videoio.CAP_DSHOW) else VideoCapture(index) }
            .getOrElse { post("The camera library could not load: ${it.message}"); running = false; return }
        val depthSession = java.util.concurrent.atomic.AtomicReference<OrtSession?>(null)
        try {
            if (!capture.isOpened) {
                post("No webcam could be opened. Connect one, or close the app using it.")
                running = false
                return
            }
            // The model loads beside the picture, so the room appears while it downloads.
            Thread({
                val file = ensureModel() ?: return@Thread
                val loaded = runCatching {
                    OrtEnvironment.getEnvironment().createSession(file.path, OrtSession.SessionOptions())
                }.onFailure { post("The depth model could not load: ${it.message}") }.getOrNull()
                depthSession.set(loaded)
                if (loaded != null) post("Look at the room: Prism is finding the floor, walls and ceiling…")
            }, "prism-desktop-ar-model").apply { isDaemon = true }.start()

            val image = Mat()
            val small = Mat()
            val params = IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 80)
            var smoothed: FloatArray? = null
            var lastDepth = 0L
            while (running && worker === Thread.currentThread()) {
                if (!capture.read(image) || image.empty()) { Thread.sleep(40); continue }
                // The background, about 20 times a second.
                val w = image.cols()
                val h = image.rows()
                val buffer = BytePointer()
                if (opencv_imgcodecs.imencode(".jpg", image, buffer, params)) {
                    val jpeg = ByteArray(buffer.limit().toInt()).also { buffer.get(it) }
                    runCatching { org.jetbrains.skia.Image.makeFromEncoded(jpeg) }.getOrNull()?.let { skia ->
                        picture = skia
                        val bmp = skia.toComposeImageBitmap()
                        SwingUtilities.invokeLater { if (running) frame.value = bmp }
                    }
                }
                buffer.deallocate()

                // Depth, about twice a second.
                val session = depthSession.get()
                val now = System.currentTimeMillis()
                if (session != null && now - lastDepth > 450) {
                    lastDepth = now
                    opencv_imgproc.resize(image, small, Size(MODEL_INPUT, MODEL_INPUT))
                    val prediction = runCatching { infer(session, small) }
                        .onFailure { PrismPlatform.log.warn(TAG, "Depth failed: ${it.message}") }.getOrNull()
                    if (prediction != null) {
                        val gw = GRID_W
                        val gh = (GRID_W * h / w.toFloat()).toInt().coerceAtLeast(8)
                        val grid = resample(prediction, MODEL_INPUT, MODEL_INPUT, gw, gh)
                        // The camera does not move: average the network's frames.
                        smoothed = smoothed?.takeIf { it.size == grid.size }?.let { prev -> FloatArray(grid.size) { prev[it] * 0.6f + grid[it] * 0.4f } } ?: grid
                        val solved = MonocularDepth.solve(smoothed!!, gw, gh)
                        if (solved != null) {
                            // One consistent model from the averaged depth, rather than layers of
                            // slightly different solves; the room's memory keeps surfaces steady.
                            room.scanner.clear()
                            repeat(2) { room.scanner.addDepth(solved.frame, stride = 1) }
                            room.rebuild()
                            depth = solved.frame
                            val k = solved.frame.intrinsics
                            val sx = w.toFloat() / gw
                            val sy = h.toFloat() / gh
                            camera = solved.frame.pose to Intrinsics(k.fx * sx, k.fy * sy, k.cx * sx, k.cy * sy, w, h)
                            post(if (room.layout.isEmpty) "Still looking for the floor and walls…" else "Found ${room.layout.describe()}")
                        }
                    }
                }
                Thread.sleep(30)
            }
            params.deallocate()
            image.release()
            small.release()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            post("The camera failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { capture.release() }
            runCatching { depthSession.get()?.close() }
            SwingUtilities.invokeLater { frame.value = null }
        }
    }

    /**
     * Where the real surface is under camera-image pixel ([u], [v]) of a [width] x [height] picture,
     * from the latest depth: what a drop measures, as ARCore's hit test does on a phone.
     */
    fun measure(u: Float, v: Float, width: Int, height: Int): com.prism.launcher.spatial.Vec3? {
        val frame = depth ?: return null
        val k = frame.intrinsics
        val gx = (u * k.width / width).toInt().coerceIn(0, k.width - 1)
        val gy = (v * k.height / height).toInt().coerceIn(0, k.height - 1)
        // The median of a small neighbourhood, so one noisy sample does not decide.
        val samples = ArrayList<Float>()
        for (dy in -2..2) for (dx in -2..2) {
            val x = (gx + dx).coerceIn(0, k.width - 1)
            val y = (gy + dy).coerceIn(0, k.height - 1)
            val d = frame.depth[y * k.width + x]
            if (d > 0f) samples += d
        }
        if (samples.isEmpty()) return null
        val d = samples.sorted()[samples.size / 2]
        return frame.pose.transform(k.ray(gx.toFloat(), gy.toFloat()) * d)
    }

    /** Runs Depth Anything on a 518 x 518 BGR picture; returns relative inverse depth, row-major. */
    private fun infer(session: OrtSession, bgr: Mat): FloatArray {
        val n = MODEL_INPUT * MODEL_INPUT
        val data = FloatArray(3 * n)
        val bytes = ByteArray(n * 3)
        bgr.data().get(bytes)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until n) {
            val b = (bytes[i * 3].toInt() and 0xFF) / 255f
            val g = (bytes[i * 3 + 1].toInt() and 0xFF) / 255f
            val r = (bytes[i * 3 + 2].toInt() and 0xFF) / 255f
            data[i] = (r - mean[0]) / std[0]
            data[n + i] = (g - mean[1]) / std[1]
            data[2 * n + i] = (b - mean[2]) / std[2]
        }
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, MODEL_INPUT.toLong(), MODEL_INPUT.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val out = tensor.floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }.let { if (it.size == n) it else it.copyOf(n) }
            }
        }
    }

    private fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray = FloatArray(dw * dh) { i ->
        val x = (i % dw + 0.5f) * sw / dw
        val y = (i / dw + 0.5f) * sh / dh
        src[(y.toInt().coerceIn(0, sh - 1)) * sw + x.toInt().coerceIn(0, sw - 1)]
    }
}
