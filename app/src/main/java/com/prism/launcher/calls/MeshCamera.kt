package com.prism.launcher.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import java.io.ByteArrayOutputStream

/**
 * The camera for a mesh video call: JPEG frames at call-tile size, a few per second.
 *
 * ## Camera2 straight into an ImageReader
 *
 * No preview surface and no CameraX: the call needs encoded frames, not a viewfinder, and the local
 * preview is drawn from the same frames that are sent -- so what the user sees of themselves is
 * exactly what the other side receives. YUV is read rather than asking the camera for JPEG because a
 * repeating JPEG request is slow or unsupported on many devices, while every camera produces
 * YUV_420_888 and YuvImage encodes a 320x240 frame in a couple of milliseconds.
 *
 * ## Rotation travels with the frame
 *
 * The sensor is mounted sideways. The clockwise turn that stands the picture up is computed here,
 * from the sensor orientation and the screen's current rotation, and sent alongside the JPEG; the
 * receiver turns the decoded bitmap. Rotating here would cost a decode and re-encode per frame on
 * the device that is already the busiest.
 */
class MeshCamera(
    private val context: Context,
    /** The display's rotation in degrees (0, 90, 180, 270), read each frame. */
    private val displayRotation: () -> Int,
    /** One encoded frame, on the camera thread. */
    private val onFrame: (jpeg: ByteArray, rotation: Int, front: Boolean) -> Unit,
) {

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var sensorOrientation = 0

    /** Which way it is looking. Starts on the front camera, which is what a call means. */
    @Volatile var front: Boolean = true
        private set

    @Volatile var running: Boolean = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the camera. False when there is no permission or no camera facing that way. */
    @SuppressLint("MissingPermission")
    fun start(useFront: Boolean = front, useChosen: Boolean = true): Boolean {
        if (!hasPermission()) return false
        stop()
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        val wanted = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        // The camera chosen in Settings → Phone and Meshnet Calls, when it still exists; a flip
        // during the call deliberately ignores it and takes the other side's camera instead.
        val chosen = if (useChosen) CallDevices.preferredCamera() else null
        val id = runCatching {
            chosen?.takeIf { it in manager.cameraIdList }
                ?: manager.cameraIdList.firstOrNull { cameraId ->
                    manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == wanted
                }
                ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return false
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return false
        front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val size = chooseSize(characteristics)

        val worker = HandlerThread("prism-call-camera").apply { start() }
        thread = worker
        val cameraHandler = Handler(worker.looper)
        handler = cameraHandler

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                val jpeg = encode(image) ?: return@setOnImageAvailableListener
                onFrame(jpeg, rotation(), front)
            } finally {
                image.close()
            }
        }, cameraHandler)
        reader = imageReader

        return runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startSession(camera, imageReader, cameraHandler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    if (device === camera) device = null
                    running = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    PrismLogger.logWarning(TAG, "Camera error $error")
                    runCatching { camera.close() }
                    if (device === camera) device = null
                    running = false
                }
            }, cameraHandler)
            running = true
            true
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Could not open the camera: ${it.message}")
            stop()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice, imageReader: ImageReader, cameraHandler: Handler) {
        runCatching {
            // The deprecated list overload is the one available at minSdk 26; the SessionConfiguration
            // form needs API 28.
            camera.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        runCatching {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(imageReader.surface)
                            }.build()
                            captureSession.setRepeatingRequest(request, null, cameraHandler)
                        }.onFailure { PrismLogger.logWarning(TAG, "Camera request failed: ${it.message}") }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        PrismLogger.logWarning(TAG, "The camera session could not be configured")
                    }
                },
                cameraHandler,
            )
        }.onFailure { PrismLogger.logWarning(TAG, "Could not start the camera session: ${it.message}") }
    }

    /** Switches between the front and back cameras. */
    fun flip(): Boolean = start(!front, useChosen = false)

    fun stop() {
        running = false
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /**
     * The clockwise turn that stands this frame up for a viewer.
     *
     * The standard Camera2 relationship: a front camera's sensor rotation adds to the display's, a
     * back camera's subtracts. The front image is NOT mirrored for the far side -- they should see
     * the user as a camera would, not as a mirror would; only the local preview is flipped.
     */
    private fun rotation(): Int {
        val display = displayRotation()
        return if (front) (sensorOrientation + display) % 360 else (sensorOrientation - display + 360) % 360
    }

    /** The smallest supported size at least [TARGET_WIDTH] wide, or the smallest there is. */
    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.sortedBy { it.width * it.height }
            .orEmpty()
        return sizes.firstOrNull { it.width >= TARGET_WIDTH && it.height >= TARGET_HEIGHT && it.width <= 800 }
            ?: sizes.firstOrNull()
            ?: Size(TARGET_WIDTH, TARGET_HEIGHT)
    }

    /**
     * YUV_420_888 to JPEG, honouring each plane's row and pixel stride.
     *
     * The planes are not guaranteed to be tightly packed -- many devices pad rows, and the chroma
     * planes are commonly interleaved with a pixel stride of 2 -- so they are copied byte by byte
     * into NV21 rather than assumed to be in that layout already.
     */
    private fun encode(image: Image): ByteArray? = runCatching {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        var offset = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            if (yPlane.pixelStride == 1) {
                yBuffer.get(nv21, offset, width)
                offset += width
            } else {
                for (col in 0 until width) {
                    nv21[offset++] = yBuffer.get(row * yPlane.rowStride + col * yPlane.pixelStride)
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }

        val out = ByteArrayOutputStream(16 * 1024)
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        out.toByteArray()
    }.getOrNull()

    private companion object {
        const val TAG = "PrismCallCamera"
        const val TARGET_WIDTH = 320
        const val TARGET_HEIGHT = 240
        const val JPEG_QUALITY = 55
        /** Eight frames a second: smooth enough for a face, light enough for a phone's uplink. */
        const val FRAME_INTERVAL_MS = 125L
    }
}
