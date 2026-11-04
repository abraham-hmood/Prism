package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The cameras a meshnet video call can use, for Settings → Phone and Meshnet Calls.
 *
 * ## Why this is not one OpenCV call
 *
 * OpenCV opens a camera by index and has no way to list them or say what they are called. So:
 *
 *  - **Linux** reads `/sys/class/video4linux`, which names every V4L2 node without opening any.
 *    Only capture nodes count (`index` 0); a webcam also exposes a metadata node that would
 *    otherwise appear as a second, useless camera.
 *  - **Windows and macOS** try the first few indices and keep the ones that open. That briefly
 *    wakes each camera, so it only happens when the settings section asks for the list, never at
 *    startup. On Windows the names come from Windows' own device list when the counts agree.
 *
 * The choice is stored as `index:N`, which is what [DesktopCamera] opens. Blank is camera 0.
 */
object DesktopCameraDevices {

    private const val TAG = "PrismDesktopCamera"
    private const val PROBE_LIMIT = 5

    data class Device(val key: String, val label: String)

    private val os = System.getProperty("os.name").orEmpty().lowercase()

    /** Every camera, the automatic choice first. Slow on Windows and macOS: call it off the UI thread. */
    fun cameras(): List<Device> {
        val found = runCatching { if (os.contains("linux")) linuxCameras() else probedCameras() }
            .getOrElse {
                PrismPlatform.log.warn(TAG, "Could not list cameras: ${it.message}")
                emptyList()
            }
        return listOf(Device("", "Automatic (first camera)")) + found
    }

    /** The index the chosen camera opens at; 0 when none is chosen or the choice is malformed. */
    fun chosenIndex(): Int =
        PrismSettings.getCallCamera().removePrefix("index:").toIntOrNull()?.takeIf { it >= 0 } ?: 0

    private fun linuxCameras(): List<Device> {
        val nodes = File("/sys/class/video4linux").listFiles().orEmpty()
            .filter { it.name.startsWith("video") }
            .mapNotNull { node -> node.name.removePrefix("video").toIntOrNull()?.let { it to node } }
            .sortedBy { it.first }
        return nodes.mapNotNull { (n, node) ->
            // A capture node is index 0; the same device's metadata node is 1.
            val index = runCatching { File(node, "index").readText().trim().toInt() }.getOrDefault(0)
            if (index != 0) return@mapNotNull null
            val name = runCatching { File(node, "name").readText().trim() }.getOrDefault("").ifBlank { "Camera" }
            Device("index:$n", "$name (/dev/video$n)")
        }
    }

    private fun probedCameras(): List<Device> {
        val windows = os.contains("win")
        val open = (0 until PROBE_LIMIT).filter { index ->
            runCatching {
                val capture = if (windows) {
                    org.bytedeco.opencv.opencv_videoio.VideoCapture(index, org.bytedeco.opencv.global.opencv_videoio.CAP_DSHOW)
                } else {
                    org.bytedeco.opencv.opencv_videoio.VideoCapture(index)
                }
                val ok = capture.isOpened
                capture.release()
                ok
            }.getOrDefault(false)
        }
        val names = if (windows) windowsCameraNames() else emptyList()
        return open.mapIndexed { position, index ->
            val name = names.takeIf { it.size == open.size }?.getOrNull(position)
            Device("index:$index", name ?: "Camera ${index + 1}")
        }
    }

    /** Windows' own names for its cameras, in device order. Empty when PowerShell will not say. */
    private fun windowsCameraNames(): List<String> = runCatching {
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "Get-CimInstance Win32_PnPEntity | Where-Object { \$_.PNPClass -eq 'Camera' -or \$_.PNPClass -eq 'Image' } | " +
                "ForEach-Object { \$_.Name }",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}
