package com.prism.desktop.science

import com.prism.core.PrismPlatform
import com.prism.launcher.science.RfSample
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The sensors a desktop does and does not have. PHASE 96.
 *
 * ## This is the file the phase was warning about
 *
 * "This is the one where desktop is genuinely the weaker platform and the phase should say which
 * parts do not port rather than porting a shell." So each capability below answers two questions, not
 * one: whether it works, and if not, why not -- in a sentence somebody can act on. A greyed-out
 * button with no explanation is the failure mode the phase was describing.
 */

/**
 * Whether this machine has a camera, and reading frames from it if it does.
 *
 * ## THE CAMERA IS OPTIONAL AND THE PAGE MUST NOT PRETEND OTHERWISE
 *
 * A laptop has a webcam; a desktop tower usually does not. The cosmic-ray detector is meaningless
 * without one -- it works by covering the sensor completely and counting the pixels a particle lights
 * up -- so on a machine with no camera the instrument is DISABLED with the reason, rather than shown
 * and then failing when it is started.
 *
 * ## Detection is per-OS, and neither way is a library call
 *
 * LINUX: `/dev/video*` nodes, which the V4L2 driver creates for every capture device. Present and
 * readable means a camera; present and unreadable means one the user's account cannot open, which is
 * a different message.
 *
 * WINDOWS: PnP devices in the Camera or Image setup class, through PowerShell's `Get-PnpDevice`.
 * There is no file to look for, and the alternatives are Media Foundation through JNA (a large
 * binding for a yes-or-no) or DirectShow (deprecated).
 *
 * ## Capture is NOT implemented, and that is stated rather than stubbed
 *
 * Detecting a camera is cheap; reading frames from one needs a platform capture API -- Media
 * Foundation or V4L2 ioctls through JNA, or an external process like `ffmpeg`. [captureCommand]
 * reports whether a usable external capture exists, and the instrument says what it is waiting for.
 * A detector that silently produced zero hits would be indistinguishable from a quiet night.
 */
object DesktopCamera {

    private const val TAG = "PrismScience"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** What was found, computed once -- enumerating PnP devices costs a PowerShell launch. */
    data class Presence(
        val available: Boolean,
        /** Device names, when the platform gives them. */
        val devices: List<String>,
        /** Why there is no usable camera, when [available] is false. */
        val reason: String,
    )

    @Volatile
    private var cached: Presence? = null

    fun presence(): Presence = cached ?: detect().also { cached = it }

    /** Forgets the cached answer, for a page offering a re-check after plugging one in. */
    fun recheck(): Presence = detect().also { cached = it }

    private fun detect(): Presence = runCatching {
        if (windows) detectWindows() else detectLinux()
    }.getOrElse {
        Presence(
            available = false,
            devices = emptyList(),
            reason = "Could not tell whether this machine has a camera: " + it.message,
        )
    }

    private fun detectLinux(): Presence {
        val nodes = File("/dev").listFiles { f: File -> f.name.startsWith("video") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (nodes.isEmpty()) {
            return Presence(
                available = false,
                devices = emptyList(),
                reason = "No /dev/video* device exists, so this machine has no camera the kernel " +
                    "knows about. The cosmic-ray detector needs one; everything else on this page " +
                    "works without it.",
            )
        }
        val readable = nodes.filter { it.canRead() }
        if (readable.isEmpty()) {
            return Presence(
                available = false,
                devices = nodes.map { it.absolutePath },
                reason = "A camera exists (" + nodes.first().absolutePath + ") but this account " +
                    "cannot open it. On most distributions that means adding your user to the " +
                    "`video` group.",
            )
        }
        return Presence(true, readable.map { it.absolutePath }, "")
    }

    private fun detectWindows(): Presence {
        // Both setup classes: modern webcams land in Camera, older ones in Image.
        val script = "Get-PnpDevice -Class Camera,Image -Status OK -ErrorAction SilentlyContinue | " +
            "Select-Object -ExpandProperty FriendlyName"
        val names = runPowerShell(script)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (names.isEmpty()) {
            return Presence(
                available = false,
                devices = emptyList(),
                reason = "Windows reports no working camera or imaging device. The cosmic-ray " +
                    "detector needs one; everything else on this page works without it.",
            )
        }
        return Presence(true, names, "")
    }

    /**
     * An external capture command, if one is installed.
     *
     * `ffmpeg` is the only thing both platforms are likely to have that can hand over raw frames
     * without a native binding. Null means the detector cannot run even on a machine WITH a camera,
     * and the page says which of the two is missing -- "no camera" and "a camera but nothing to read
     * it with" need different answers from the user.
     */
    fun captureCommand(): File? = onPath("ffmpeg")

    /**
     * The command that grabs greyscale frames from the camera into stdout.
     *
     * `gray` pixel format and a small size on purpose: the detector thresholds a luminance plane, so
     * colour is wasted bandwidth, and a 640x480 frame at a few per second is already more pixels
     * than the statistics need.
     */
    fun captureArgs(device: String, width: Int, height: Int, fps: Int): List<String>? {
        val ffmpeg = captureCommand() ?: return null
        return if (windows) {
            listOf(
                ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error",
                "-f", "dshow", "-framerate", fps.toString(),
                "-video_size", width.toString() + "x" + height.toString(),
                "-i", "video=" + device,
                "-pix_fmt", "gray", "-f", "rawvideo", "-",
            )
        } else {
            listOf(
                ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error",
                "-f", "v4l2", "-framerate", fps.toString(),
                "-video_size", width.toString() + "x" + height.toString(),
                "-i", device,
                "-pix_fmt", "gray", "-f", "rawvideo", "-",
            )
        }
    }

    /** One sentence for the page: what the detector can and cannot do here. */
    fun describe(): String {
        val found = presence()
        if (!found.available) return found.reason
        val capture = captureCommand()
            ?: return "A camera is present (" + found.devices.first() + ") but Prism has no way " +
                "to read frames from it on this machine. Installing ffmpeg and putting it on PATH " +
                "is what the detector uses; there is no native capture binding in this build."
        return found.devices.first() + ", read through " + capture.name + "."
    }

    fun isUsable(): Boolean = presence().available && captureCommand() != null

    private fun onPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        val candidates = if (windows) listOf(name + ".exe", name) else listOf(name)
        path.split(File.pathSeparatorChar).forEach { dir ->
            candidates.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file
            }
        }
        return null
    }

    /**
     * Runs a command and returns everything it wrote.
     *
     * ## THE OUTPUT IS DRAINED BEFORE THE WAIT, AND THAT ORDER IS THE WHOLE POINT
     *
     * `waitFor(timeout)` and then `readBytes()` is the obvious shape and it DEADLOCKS. A pipe has a
     * small OS buffer -- a few kilobytes; `netsh wlan show networks mode=bssid` writes about twelve.
     * The child fills the buffer, blocks writing, never exits, and `waitFor` times out. Nothing is
     * read, so the caller sees an empty result and concludes the machine has no wireless adapter.
     *
     * This cost real time to find: the symptom was a scan that returned zero access points while the
     * identical command in a terminal returned fifteen, and three wrong theories about netsh's
     * console handle came first. The giveaway was that the small-output caller (enumerating cameras)
     * worked and the large-output one did not.
     *
     * So the stream is drained on a thread, and the wait is on the drain finishing. Any caller here
     * that spawns a process and reads it afterwards has the same bug latent in it.
     */
    internal fun runPowerShell(script: String): String? = runCatching {
        val shell = onPath("powershell") ?: return null
        runWithTimeout(
            listOf(shell.absolutePath, "-NoProfile", "-NonInteractive", "-Command", script),
            seconds = 40,
        )
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "PowerShell failed: " + it.message)
        null
    }
}

/**
 * Wi-Fi scanning, which the phase correctly called out as two implementations.
 *
 * WINDOWS: `netsh wlan show networks mode=bssid`. The alternative is the WlanScan API through JNA,
 * which gives the same data and needs a binding for four structs; netsh is on every Windows that has
 * a wireless adapter and its output is stable across releases.
 *
 * LINUX: `nmcli` first, because NetworkManager is on every desktop distribution and `nmcli -t` emits
 * a parseable colon-delimited form with no locale dependence. `iw dev <if> scan` is the fallback for
 * a machine without NetworkManager, and it needs root on most kernels -- which is reported rather
 * than attempted.
 *
 * ## WHY THE OUTPUT IS PARSED RATHER THAN A LIBRARY USED
 *
 * There is no cross-platform Wi-Fi scan in the JDK and no pure-Java one that does not shell out
 * itself. Parsing two command outputs is honest about what it is; the risk -- a locale changing a
 * label -- is handled by matching on the structure (the BSSID's shape, the signal's unit) rather than
 * on English words wherever possible.
 */
object DesktopWifi {

    private const val TAG = "PrismScience"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Why a scan cannot be run here, or empty when it can. */
    fun unavailableReason(): String {
        if (windows) {
            return if (onPath("netsh") == null) {
                "netsh is not on PATH, so Prism cannot ask Windows for a Wi-Fi scan."
            } else ""
        }
        if (onPath("nmcli") != null) return ""
        if (onPath("iw") != null) {
            return "Only `iw` is available, and `iw dev scan` needs root on most kernels. " +
                "Installing NetworkManager (nmcli) is what makes this work unprivileged."
        }
        return "Neither nmcli nor iw is installed, so there is no way to run a Wi-Fi scan here."
    }

    fun isAvailable(): Boolean = unavailableReason().isEmpty()

    /**
     * Runs a scan. Blocking -- a scan takes a second or two and the caller runs it off the UI thread.
     *
     * [point] is the user's own label for where they are standing; it is carried into every sample so
     * a walked survey can be grouped afterwards.
     */
    fun scan(point: String): List<RfSample> = runCatching {
        val now = System.currentTimeMillis()
        lastRawOutput = null
        // THROUGH cmd, NOT netsh DIRECTLY, and this was a real and silent failure.
        //
        // `ProcessBuilder(listOf("netsh", "wlan", "show", "networks", "mode=bssid"))` starts, exits
        // cleanly and writes NOTHING -- zero bytes on both stdout and stderr, so it looks exactly
        // like a machine with no wireless adapter. netsh is a console-subsystem tool that writes
        // through the console handle it inherits, and a process started with pipes and no console
        // gets nowhere to write. `cmd /c` gives it one.
        //
        // Found by printing the raw output length, which is why lastRawOutput exists.
        //
        // `cmd /c netsh ...` and `powershell -Command "netsh ..."` both behave the same way. What
        // works is PIPING IT INSIDE POWERSHELL: `& netsh ... | Out-String` forces PowerShell to
        // capture the output into its own pipeline instead of letting netsh write past it, and the
        // captured string is then written to stdout where a pipe can read it. Verified: 0 chars
        // without the pipe, ~12,000 with it.
        if (windows) parseNetsh(
            runPowerShell(
                DOLLAR + "o = & netsh wlan show networks mode=bssid | Out-String; " +
                    "Write-Output " + DOLLAR + "o",
            ).also { lastRawOutput = it },
            now, point,
        )
        else parseNmcli(
            runCommand(
                listOf(
                    "nmcli", "-t",
                    "-f", "SSID,BSSID,SIGNAL,FREQ",
                    "device", "wifi", "list", "--rescan", "yes",
                ),
            ),
            now, point,
        )
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "The Wi-Fi scan failed: " + it.message)
        emptyList()
    }

    /**
     * `netsh` output, parsed by structure.
     *
     * The layout is an SSID block followed by indented BSSID entries, each with a percentage signal.
     * SSID and BSSID lines are found by their VALUE shape -- a MAC address and a percentage -- rather
     * than by the label text, so a non-English Windows still parses. The channel line is matched by
     * label because there is nothing else to go on, and a missing channel costs a band name rather
     * than the sample.
     */
    private fun parseNetsh(output: String?, now: Long, point: String): List<RfSample> {
        if (output == null) return emptyList()
        val samples = mutableListOf<RfSample>()
        var ssid = ""
        var bssid = ""
        var percent = -1
        var channel = -1

        fun flush() {
            if (bssid.isEmpty() || percent < 0) return
            samples.add(
                RfSample(
                    ssid = ssid.ifBlank { "(hidden)" },
                    bssid = bssid,
                    // netsh reports a percentage, not dBm. Mapped linearly onto -100..-40, which is
                    // the convention Windows' own indicator uses -- so it is comparable with a
                    // phone's dBm to within the slop of that mapping, and labelled as derived.
                    rssi = (-100 + (percent * 0.6)).toInt(),
                    frequencyMhz = channelToMhz(channel),
                    at = now,
                    point = point,
                ),
            )
            bssid = ""
            percent = -1
            channel = -1
        }

        output.lines().forEach { raw ->
            val line = raw.trim()
            val value = line.substringAfter(':', "").trim()
            when {
                MAC.matches(value) -> {
                    flush()
                    bssid = value.lowercase()
                }
                value.endsWith("%") -> percent = value.removeSuffix("%").trim().toIntOrNull() ?: -1
                line.startsWith("SSID", ignoreCase = true) && !line.contains("BSSID", true) -> {
                    flush()
                    ssid = value
                }
                // THE VALUE MUST BE A BARE INTEGER, and that is not pedantry. netsh emits
                // "Channel : 10" and then, a few lines later inside the Bss Load block,
                // "Channel Utilization: 15 (5 %)" -- which also contains "annel". A looser match
                // read the second one, failed to parse "15 (5 %)", and overwrote the real channel
                // with -1, so every sample came out at 0 MHz with no band.
                line.startsWith("Channel", ignoreCase = true) && value.toIntOrNull() != null ->
                    channel = value.toInt()
            }
        }
        flush()
        return samples
    }

    /**
     * `nmcli -t` output: colon-delimited, one network per line.
     *
     * The BSSID's own colons are ESCAPED by nmcli as `\:`, which is the detail that breaks a naive
     * `split(':')` -- a line splits into nine fields instead of four and every value lands in the
     * wrong place. Unescaped first, then split on the remaining separators.
     */
    private fun parseNmcli(output: String?, now: Long, point: String): List<RfSample> {
        if (output == null) return emptyList()
        return output.lines().mapNotNull { raw ->
            if (raw.isBlank()) return@mapNotNull null
            // Split on unescaped colons only, then unescape each field.
            val fields = splitUnescaped(raw).map { it.replace("\\:", ":").replace("\\\\", "\\") }
            if (fields.size < 4) return@mapNotNull null
            val ssid = fields[0]
            val bssid = fields[1].lowercase()
            val signal = fields[2].toIntOrNull() ?: return@mapNotNull null
            // nmcli's FREQ is "2412 MHz".
            val mhz = fields[3].filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            if (!MAC.matches(bssid)) return@mapNotNull null
            RfSample(
                ssid = ssid.ifBlank { "(hidden)" },
                bssid = bssid,
                // Same percentage-to-dBm mapping as the Windows path, for the same reason: nmcli's
                // SIGNAL is a quality percentage, not dBm.
                rssi = (-100 + (signal * 0.6)).toInt(),
                frequencyMhz = mhz,
                at = now,
                point = point,
            )
        }
    }

    private fun splitUnescaped(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && i + 1 < line.length -> {
                    current.append(ch).append(line[i + 1])
                    i += 2
                }
                ch == ':' -> {
                    out.add(current.toString())
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        out.add(current.toString())
        return out
    }

    /** A channel back to a centre frequency, for the samples netsh gives a channel for. */
    private fun channelToMhz(channel: Int): Int = when {
        channel <= 0 -> 0
        channel == 14 -> 2484
        channel in 1..13 -> 2407 + channel * 5
        channel in 32..177 -> 5000 + channel * 5
        else -> 0
    }

    private val MAC = Regex("^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")

    /** PowerShell's sigil, built rather than written, so no Kotlin template is involved. */
    private val DOLLAR = '$'.toString()

    /**
     * The last scan's raw output, for diagnosis.
     *
     * Kept because a scan that returns nothing has three indistinguishable causes -- the command did
     * not run, it ran and the adapter is off, or it ran and the parse missed -- and only the raw text
     * tells them apart. Nulled at the start of each scan so a stale one cannot be read as current.
     */
    @Volatile
    var lastRawOutput: String? = null
        private set

    private fun runCommand(command: List<String>): String? = runCatching {
        // A ceiling: a scan on a busy adapter can take several seconds, and a wedged one would
        // otherwise hold the caller open. Drained while it runs -- see runWithTimeout.
        runWithTimeout(command, seconds = 40).also { lastRawOutput = it }
    }.getOrElse {
        lastRawOutput = "[" + (it.message ?: it.javaClass.simpleName) + "]"
        null
    }

    /**
     * Runs a command through PowerShell and returns its output.
     *
     * Shared with the camera detection above, which is where it was proven: a bare
     * `ProcessBuilder("netsh", ...)` on Windows exits cleanly having written nothing, because netsh
     * writes through an inherited console handle and a piped process has none.
     */
    private fun runPowerShell(command: String): String? =
        DesktopCamera.runPowerShell(command)

    private fun onPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        val candidates = if (windows) listOf(name + ".exe", name) else listOf(name)
        path.split(File.pathSeparatorChar).forEach { dir ->
            candidates.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file
            }
        }
        return null
    }
}

/**
 * Reads a process's output to completion, then waits for it, with a ceiling on both.
 *
 * Shared because getting this wrong is silent. See the note above [runWithTimeout]'s callers: a
 * child that fills the pipe buffer blocks forever if nothing is reading, and `waitFor` then reports
 * a timeout for a process that was only waiting for somebody to listen.
 *
 * @return the output, or null if the process could not be started or did not finish in time.
 */
internal fun runWithTimeout(command: List<String>, seconds: Long): String? = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    // Drained on its own thread so the child is never blocked on a full pipe while this waits.
    val collected = StringBuilder()
    val reader = Thread({
        runCatching {
            process.inputStream.bufferedReader().forEachLine { collected.append(it).append('\n') }
        }
    }, "process-drain").apply { isDaemon = true; start() }

    val finished = process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        // Given a moment to notice, so whatever was already written is not thrown away.
        reader.join(500)
        return@runCatching null
    }
    // The process has exited but the drain may still be catching up on what is in the buffer.
    reader.join(2000)
    collected.toString()
}.getOrNull()
