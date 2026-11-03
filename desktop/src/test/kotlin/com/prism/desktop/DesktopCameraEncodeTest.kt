package com.prism.desktop

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The half of [DesktopCamera] that does not need a camera.
 *
 * Opening a webcam in a test would switch on somebody's camera light, so this checks the rest of the
 * path instead: that OpenCV's natives load on this machine at all, and that a captured-sized frame
 * shrinks and encodes to a JPEG small enough for the mesh. Both failures would otherwise only show
 * up as a video call with no picture.
 */
class DesktopCameraEncodeTest {

    @Test
    fun `a webcam-sized frame shrinks and encodes to a call-sized JPEG`() {
        val frame = Mat(480, 640, opencv_core.CV_8UC3, Scalar(40.0, 120.0, 200.0, 0.0))
        val small = Mat()
        opencv_imgproc.resize(frame, small, Size(320, 240))
        val buffer = BytePointer()
        val params = IntPointer(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 60)
        assertTrue(opencv_imgcodecs.imencode(".jpg", small, buffer, params), "the frame did not encode")
        val jpeg = ByteArray(buffer.limit().toInt())
        buffer.get(jpeg)
        assertTrue(jpeg.size in 200..64_000, "unexpected JPEG size ${jpeg.size}")
        assertTrue(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte(), "the output is not a JPEG")
    }
}
