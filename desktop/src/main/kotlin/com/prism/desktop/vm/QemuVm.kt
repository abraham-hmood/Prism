package com.prism.desktop.vm

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A guest operating system in a window. PHASE 68.
 *
 * ## QEMU here IS the port, unlike for Android apps
 *
 * Worth being precise, because the plan now says two opposite-sounding things. Running an Android APP
 * must NOT use an emulator — that is PHASE 111 and it uses the same technique the phone does. Running a
 * whole GUEST OS is a different feature: Android does it with the Android Virtualization Framework, a
 * desktop does it with QEMU, and neither is a port of the other. This is the second one.
 *
 * ## Why the VM is driven over VNC rather than QEMU's own window
 *
 * QEMU can open an SDL or GTK window itself. That window would be OUTSIDE Prism — a second thing in the
 * taskbar that Prism cannot lay out, theme or embed. `-vnc` makes the guest's display a socket, and a
 * socket can be drawn into a Compose canvas. It is also exactly what the Android build does, so the
 * protocol work is shared even though the drawing is not.
 *
 * ## Why KVM is asked for and not required
 *
 * On Linux with `/dev/kvm` readable, hardware acceleration is roughly an order of magnitude faster than
 * software emulation and turns an unusable VM into a usable one. Without it, TCG still works. So KVM is
 * tried and the fallback is silent in behaviour but not in the report — [describe] says which is in use,
 * because "the VM is slow" is otherwise unanswerable.
 */
object QemuVm {

    private const val TAG = "PrismVm"

    enum class State { STOPPED, BOOTING, RUNNING, PAUSED, ERROR }

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** Called when the state changes, so a page can follow it. */
    @Volatile
    var onStateChange: ((State) -> Unit)? = null

    @Volatile private var process: Process? = null
    @Volatile private var monitorPort: Int = 0

    /** The VNC display port. QEMU's `-vnc :N` listens on 5900 + N. */
    const val VNC_PORT = 5901

    private const val VNC_DISPLAY = 1

    /** Where QEMU's monitor listens, for pause and resume. */
    private const val MONITOR_PORT = 55555

    // ── What this machine can do ───────────────────────────────────────────

    data class Capability(
        val binary: File?,
        val accelerator: String,
        val detail: String,
    ) {
        val available: Boolean get() = binary != null
    }

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Finds QEMU and works out what it can use.
     *
     * NOT BUNDLED. QEMU is tens of megabytes per architecture and is packaged by every platform
     * already; shipping a copy would mean shipping one per OS and keeping them patched. Prism looks for
     * the system's, and says so when there is none rather than failing at start.
     */
    fun capability(): Capability {
        val binary = findBinary()
        if (binary == null) {
            return Capability(
                null, "none",
                "QEMU is not installed. Prism does not bundle it -- it is tens of megabytes per " +
                    "architecture, every platform packages it, and a bundled copy would be one more " +
                    "thing to keep patched. Install qemu-system-x86_64 and this page will find it.",
            )
        }

        val accelerator = when {
            windows -> if (whpxAvailable()) "whpx" else "tcg"
            File("/dev/kvm").canRead() -> "kvm"
            else -> "tcg"
        }

        return Capability(
            binary, accelerator,
            when (accelerator) {
                "kvm" -> "KVM is available, so the guest runs at close to native speed."
                "whpx" -> "Windows Hypervisor Platform is available, so the guest is hardware accelerated."
                else -> "No hardware acceleration available, so QEMU emulates in software. That works " +
                    "and it is slow -- roughly an order of magnitude, which is the difference between " +
                    "a usable VM and a demonstration."
            },
        )
    }

    private fun findBinary(): File? {
        val names = if (windows) {
            listOf("qemu-system-x86_64.exe", "qemu-system-aarch64.exe")
        } else {
            listOf("qemu-system-x86_64", "qemu-system-aarch64")
        }
        val extra = listOf(
            File("C:/Program Files/qemu"),
            File("/usr/bin"),
            File("/usr/local/bin"),
            File("/opt/homebrew/bin"),
            File(PrismSettings.getVmQemuPath()).parentFile ?: File("."),
        )

        names.forEach { name ->
            which(name)?.let { return File(it) }
            extra.forEach { dir ->
                val candidate = File(dir, name)
                if (candidate.isFile) return candidate
            }
        }
        val configured = File(PrismSettings.getVmQemuPath())
        return configured.takeIf { it.isFile }
    }

    /** Whether the Windows hypervisor platform is on. Cheap check, no elevation needed. */
    private fun whpxAvailable(): Boolean = runCatching {
        run("powershell", "-NoProfile", "-Command",
            "(Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform).State")
            ?.contains("Enabled", ignoreCase = true) == true
    }.getOrDefault(false)

    // ── Running one ────────────────────────────────────────────────────────

    /**
     * Boots [image]. Returns null on success, or what stopped it.
     *
     * An .iso is attached as CD-ROM media and anything else as a disk. That distinction matters: an
     * install ISO attached as a raw disk skips the El Torito boot catalog entirely and boots nothing,
     * which is the failure this exact line exists to prevent -- the Android build hit it first.
     */
    fun start(image: File, memoryMb: Int = 2048, cores: Int = 2): String? {
        if (state == State.RUNNING || state == State.BOOTING) return "A guest is already running."
        if (!image.isFile) return "There is no image at " + image.absolutePath

        val capability = capability()
        val binary = capability.binary ?: return capability.detail

        val command = mutableListOf(
            binary.absolutePath,
            "-m", memoryMb.toString(),
            "-smp", cores.toString(),
            "-accel", capability.accelerator,
            // The display is a socket rather than a window, so Prism can draw it. See the class note.
            "-vnc", ":" + VNC_DISPLAY,
            // The monitor, also a socket: pause and resume are monitor commands, and without it the
            // only control Prism would have over a running guest is killing the process.
            "-monitor", "tcp:127.0.0.1:" + MONITOR_PORT + ",server,nowait",
            // A guest with no network is a guest that cannot be updated or used. User-mode networking
            // needs no privileges and no bridge, which is the right default for something a launcher
            // starts.
            "-netdev", "user,id=net0",
            "-device", "virtio-net-pci,netdev=net0",
        )

        if (image.extension.equals("iso", ignoreCase = true)) {
            command += listOf("-cdrom", image.absolutePath, "-boot", "d")
        } else {
            command += listOf("-drive", "file=" + image.absolutePath + ",format=raw,if=virtio")
        }

        return runCatching {
            publish(State.BOOTING)
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            monitorPort = MONITOR_PORT

            // QEMU exits immediately on a bad argument, so the failure has to be caught here rather
            // than left as a display that never appears.
            Thread({
                val output = process?.inputStream?.bufferedReader()?.readText().orEmpty()
                val code = process?.waitFor() ?: -1
                if (code != 0 && state != State.STOPPED) {
                    lastError = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                        .ifBlank { "QEMU exited with code " + code }
                    PrismPlatform.log.error(TAG, "QEMU exited: " + lastError)
                    publish(State.ERROR)
                } else if (state != State.STOPPED) {
                    publish(State.STOPPED)
                }
            }, "qemu-watch").apply { isDaemon = true }.start()

            // Waiting for the display rather than assuming it: the page attaches a VNC client as soon
            // as this returns, and connecting before QEMU has bound is a failed first frame.
            Thread({
                repeat(60) {
                    if (isVncLive()) {
                        publish(State.RUNNING)
                        return@Thread
                    }
                    runCatching { Thread.sleep(500) }
                }
            }, "qemu-vnc-wait").apply { isDaemon = true }.start()

            PrismPlatform.log.info(TAG, "Started " + image.name + " with " + capability.accelerator)
            null
        }.getOrElse {
            publish(State.ERROR)
            lastError = it.message.orEmpty()
            "QEMU would not start: " + it.message
        }
    }

    fun pause(): String? = monitor("stop")?.also { publish(State.PAUSED) }.let { null }

    fun resume(): String? = monitor("cont")?.also { publish(State.RUNNING) }.let { null }

    fun stop() {
        publish(State.STOPPED)
        runCatching { monitor("quit") }
        runCatching { process?.destroy() }
        process = null
    }

    /** Whether the guest's display is accepting connections. */
    fun isVncLive(timeoutMs: Int = 300): Boolean = runCatching {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", VNC_PORT), timeoutMs)
            true
        }
    }.getOrDefault(false)

    fun describe(): String {
        val capability = capability()
        return buildString {
            append(if (capability.available) capability.binary?.name else "QEMU not found")
            if (capability.available) {
                append(" · ")
                append(capability.accelerator)
            }
        }
    }

    /**
     * Sends one command to QEMU's monitor.
     *
     * A socket rather than stdin, because stdin is already being drained by the watcher thread that
     * catches a failed start -- two readers on one stream lose each other's bytes.
     */
    private fun monitor(command: String): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", monitorPort), 1_000)
            socket.soTimeout = 1_000
            socket.getOutputStream().apply {
                write((command + "\n").toByteArray())
                flush()
            }
            runCatching { socket.getInputStream().readNBytes(256).toString(Charsets.UTF_8) }.getOrNull()
        }
    }.getOrNull()

    private fun publish(next: State) {
        state = next
        runCatching { onStateChange?.invoke(next) }
    }

    private fun which(tool: String): String? = runCatching {
        val command = if (windows) listOf("where", tool) else listOf("which", tool)
        run(*command.toTypedArray())?.lineSequence()?.firstOrNull()
            ?.takeIf { it.isNotBlank() && !it.contains("not find", ignoreCase = true) }
    }.getOrNull()

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        output
    }.getOrNull()
}
