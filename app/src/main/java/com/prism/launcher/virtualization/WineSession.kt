package com.prism.launcher.virtualization

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * One running Windows program, and the two processes underneath it.
 *
 * ## Why there is an X server at all
 *
 * Wine draws through X11. Nothing on Android speaks X11, so something has to listen on `:0` and turn
 * what Wine draws into pixels on a Surface. Winlator ships a small X server written in Java for
 * exactly this. Prism already has a VNC client wired to a Surface -- [VncSurfaceRenderer], written
 * for the QEMU page -- so the cheaper path is to run `Xvfb` plus `x11vnc` inside the rootfs and
 * point that existing renderer at them. One display server, two processes, no new rendering code.
 *
 * The honest cost: this is a software framebuffer copied over a local socket, so it is slower than
 * Winlator's native X server handing buffers to the GPU. It is the right first version -- it reuses
 * code that already works -- and the place to optimise later is exactly here, by replacing these two
 * processes with a real X server that renders into the Surface directly.
 *
 * ## Process lifetime
 *
 * The display has to be up before Wine starts, or Wine exits immediately saying it cannot open a
 * display. So the session starts the server, waits for the socket, and only then launches the
 * program -- and tears both down together, because an orphaned Xvfb holds the display for the next
 * run and the second attempt fails for a reason that has nothing to do with the program.
 */
class WineSession(private val context: Context) {

    enum class State { IDLE, STARTING, RUNNING, STOPPED, FAILED }

    var state: State = State.IDLE
        private set

    var onStateChanged: ((State, String?) -> Unit)? = null

    private var display: Process? = null
    private var vnc: Process? = null
    private var program: Process? = null

    /** Where the VNC renderer should connect once [state] is RUNNING. */
    val vncPort: Int = 5901

    fun start(container: WineContainer.Container, exe: File?) {
        if (state == State.STARTING || state == State.RUNNING) return

        WineContainer.unavailableReason(context)?.let { reason ->
            fail(reason)
            return
        }

        update(State.STARTING, "Starting the display…")

        Thread({
            runCatching {
                startDisplay(container)
                if (!awaitDisplay()) {
                    fail("The X server did not come up. Check Diagnostics for its output.")
                    return@Thread
                }

                update(State.STARTING, "Starting Wine…")
                startProgram(container, exe)
                update(State.RUNNING, exe?.name ?: "Windows desktop")
            }.onFailure {
                PrismLogger.logError(TAG, "Could not start the Windows session", it)
                fail(it.message ?: it.javaClass.simpleName)
            }
        }, "wine-session").apply { isDaemon = true; start() }
    }

    /**
     * Xvfb for the display, x11vnc to expose it.
     *
     * Both run inside the rootfs through PRoot for the same reason everything else does: Android
     * will not execute anything under the app's data directory, and PRoot is the loader that gets
     * around that by never handing the kernel such a path.
     */
    private fun startDisplay(container: WineContainer.Container) {
        val proot = WineContainer.prootBinary(context) ?: error("PRoot is missing")
        val rootfs = WineContainer.imageFs(context)
        val (width, height) = container.screenSize.split("x")
            .let { (it.getOrNull(0)?.toIntOrNull() ?: 1280) to (it.getOrNull(1)?.toIntOrNull() ?: 720) }

        display = spawn(
            listOf(
                proot.absolutePath, "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/home/prism",
                "/usr/bin/Xvfb", ":0", "-screen", "0", "${width}x${height}x24", "-nolisten", "tcp",
            ),
            "xvfb",
        )

        vnc = spawn(
            listOf(
                proot.absolutePath, "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/home/prism",
                "/usr/bin/x11vnc", "-display", ":0", "-rfbport", vncPort.toString(),
                "-forever", "-shared", "-nopw", "-quiet",
            ),
            "x11vnc",
        )
    }

    private fun startProgram(container: WineContainer.Container, exe: File?) {
        val command = WineContainer.buildCommand(context, container, exe)
        if (command.isEmpty()) error("Could not assemble the Wine command")
        PrismLogger.logInfo(TAG, "wine: " + command.joinToString(" ").take(400))
        program = spawn(command, "wine")
    }

    private fun spawn(command: List<String>, tag: String): Process {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["HOME"] = context.filesDir.absolutePath
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        // PROOT_TMP_DIR is where PRoot puts its own working files; without it PRoot tries /tmp,
        // which an Android app cannot write to.
        builder.environment()["PROOT_TMP_DIR"] = File(context.cacheDir, "proot").apply { mkdirs() }.absolutePath
        builder.environment()["PROOT_LOADER"] =
            File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath

        val process = builder.start()
        drain(process, tag)
        return process
    }

    /**
     * Reads a child's output into diagnostics.
     *
     * Not optional even though nothing displays it: a process whose stdout nobody reads fills the
     * pipe buffer and blocks forever on its next write, which is indistinguishable from a hang. It
     * is also the only place a Wine or box64 error message will ever appear.
     */
    private fun drain(process: Process, tag: String) {
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    PrismLogger.logDebug(TAG, "[$tag] ${line.take(300)}")
                }
            }
        }, "wine-$tag-output").apply { isDaemon = true; start() }
    }

    /** Waits for the VNC port, which is the first moment there is anything to show. */
    private fun awaitDisplay(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (display?.isAlive != true) return false
            val ok = runCatching {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress("127.0.0.1", vncPort), 400)
                    true
                }
            }.getOrDefault(false)
            if (ok) return true
            try { Thread.sleep(300) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    fun stop() {
        // Program first, then the display it was drawing on: killing the display out from under
        // Wine produces a spray of X errors that look like the failure rather than the shutdown.
        runCatching { program?.destroy() }
        runCatching { vnc?.destroy() }
        runCatching { display?.destroy() }
        program = null; vnc = null; display = null
        update(State.STOPPED, null)
    }

    fun isRunning(): Boolean = program?.isAlive == true

    private fun update(next: State, detail: String?) {
        state = next
        onStateChanged?.invoke(next, detail)
    }

    private fun fail(message: String) {
        stopQuietly()
        state = State.FAILED
        onStateChanged?.invoke(State.FAILED, message)
    }

    private fun stopQuietly() {
        runCatching { program?.destroy() }
        runCatching { vnc?.destroy() }
        runCatching { display?.destroy() }
        program = null; vnc = null; display = null
    }

    private companion object {
        const val TAG = "PrismWine"
    }
}
