package com.prism.launcher.virtualization

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.Surface
import com.prism.launcher.PrismLogger
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Manages the lifecycle of a virtualized OS.
 *
 * Two backends:
 *  - AVF (Android Virtualization Framework, API 34+): runs PrismOS as a Microdroid pVM.
 *  - QEMU: launches a bundled qemu-system-aarch64 binary for arbitrary ISO images or
 *    as a fallback on devices without AVF hardware support.
 *
 * The VM display is streamed to whatever [Surface] is passed to [start] / [resume].
 * On AVF the surface is wired via VirtualDisplay; on QEMU it connects to an in-process
 * VNC server over localhost.
 */
class VmController private constructor(private val context: Context) {

    enum class Mode { PRISM_OS, CUSTOM_ISO }

    enum class State { STOPPED, BOOTING, RUNNING, PAUSED, ERROR }

    companion object {
        private const val TAG = "VmController"

        /** QEMU's VNC server. One constant, because four call sites used to hard-code 5900. */
        const val VNC_PORT = 5900

        /**
         * Whether something is already serving VNC on the loopback port.
         *
         * A plain connect attempt, because that is the only question worth asking: if a socket is
         * accepted then a VM is running and reachable, whoever started it and whichever app
         * process it belonged to.
         */
        fun isVncPortLive(timeoutMs: Int = 300): Boolean = try {
            java.net.Socket().use {
                it.connect(java.net.InetSocketAddress("127.0.0.1", VNC_PORT), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }

        @Volatile
        private var instance: VmController? = null

        /**
         * The one controller for this process.
         *
         * A SINGLETON BECAUSE THE VM OUTLIVES ANY VIEW. QEMU is a subprocess and the VNC renderer
         * is a thread; both keep running while the desktop pager recycles the virtualization page,
         * which it does as soon as the user swipes to another page and back.
         *
         * Constructing one controller per page view meant the rebuilt page got a FRESH controller:
         * state STOPPED, no process handle, and -- the symptom that exposed it -- a null VNC
         * renderer, so every keystroke was dropped with "no VNC connection is attached" while the
         * old renderer carried on drawing the guest perfectly. Anything owning a subprocess cannot
         * be scoped to a view that is thrown away and rebuilt.
         *
         * Holds the APPLICATION context: a singleton keeping an Activity alive would leak it for
         * the life of the process.
         */
        fun get(context: Context): VmController =
            instance ?: synchronized(this) {
                instance ?: VmController(context.applicationContext).also { instance = it }
            }
    }

    var state: State = State.STOPPED
        private set(value) {
            field = value
            if (value == State.ERROR) {
                PrismLogger.logError(TAG, "State -> ERROR: ${lastError ?: "(no detail)"}")
            } else {
                PrismLogger.logInfo(TAG, "State -> $value")
            }
            onStateChanged?.invoke(value)
        }

    /** Human-readable detail for the last [State.ERROR] transition — surfaced in the UI so a
     * failed boot says *why* instead of just "Error". Cleared on every fresh [start]. */
    var lastError: String? = null
        private set

    var onStateChanged: ((State) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VmController")
    }

    // Queued component to launch once the VM reaches RUNNING
    private var pendingApp: ComponentName? = null

    // QEMU process handle (QEMU path only)
    private var qemuProcess: Process? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(mode: Mode, isoPath: String? = null, surface: Surface) {
        if (state == State.RUNNING || state == State.BOOTING) {
            PrismLogger.logWarning(TAG, "start($mode) ignored — already $state")
            return
        }
        PrismLogger.logInfo(TAG, "start(mode=$mode, isoPath=$isoPath) — avfSupported=${isAvfSupported()}")
        lastError = null
        state = State.BOOTING
        executor.execute {
            try {
                // A VM MAY ALREADY BE RUNNING, and not just because a page was recycled: QEMU is a
                // subprocess, so it outlives the app process entirely. Android restarting the app
                // (which it did between PIDs 8670 and 12504 in the field) leaves an orphan holding
                // the VNC port -- and a second QEMU then fails to bind it and exits immediately,
                // which is exactly the "QEMU exited immediately" that was being reported.
                //
                // So look before launching. If something is serving VNC, that IS the VM: attach to
                // it and show it, rather than spawning a rival that cannot start.
                if (isVncPortLive()) {
                    PrismLogger.logInfo(TAG, "start(): a VM is already serving VNC — attaching instead of booting a second one")
                    if (connectVncToSurface(VNC_PORT, surface)) {
                        state = State.RUNNING
                        return@execute
                    }
                    PrismLogger.logWarning(TAG, "start(): the port answered but the VNC handshake failed; booting fresh")
                }

                if (mode == Mode.PRISM_OS && isAvfSupported()) {
                    PrismLogger.logInfo(TAG, "Booting via AVF backend")
                    startAvf(surface)
                } else if (mode == Mode.PRISM_OS) {
                    PrismLogger.logInfo(TAG, "Booting PrismOS via QEMU backend (no AVF support)")
                    startQemu(defaultPrismOsQemuImage(), isIso = false, surface)
                } else {
                    val iso = isoPath ?: throw IllegalStateException("No ISO selected — pick one in Settings > OS Virtualization first.")
                    PrismLogger.logInfo(TAG, "Booting custom ISO via QEMU backend: $iso")
                    startQemu(iso, isIso = true, surface)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "Boot failed", e)
                state = State.ERROR
            }
        }
    }

    fun pause() {
        if (state != State.RUNNING) return
        PrismLogger.logInfo(TAG, "pause()")
        executor.execute {
            try {
                pauseQemu()
                state = State.PAUSED
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "pause() failed", e)
                state = State.ERROR
            }
        }
    }

    fun resume(surface: Surface) {
        if (state != State.PAUSED) return
        PrismLogger.logInfo(TAG, "resume()")
        executor.execute {
            try {
                resumeQemu(surface)
                state = State.RUNNING
                pendingApp?.let { sendAppIntent(it); pendingApp = null }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "resume() failed", e)
                state = State.ERROR
            }
        }
    }

    fun stop() {
        vncRenderer?.stopRenderer()
        vncRenderer = null
        PrismLogger.logInfo(TAG, "stop()")
        executor.execute {
            try {
                qemuProcess?.destroy()
                qemuProcess = null
            } catch (e: Exception) {
                PrismLogger.logWarning(TAG, "stop(): failed to destroy QEMU process — ${e.message}")
            }
            state = State.STOPPED
        }
    }

    /**
     * Sends a request to PrismOS to open [cn].
     * If the VM is still booting the request is queued and replayed once RUNNING.
     */
    fun sendAppIntent(cn: ComponentName) {
        when (state) {
            State.RUNNING -> executor.execute { sendViaAdb(cn) }
            State.BOOTING, State.PAUSED -> {
                PrismLogger.logInfo(TAG, "sendAppIntent(${cn.flattenToShortString()}) queued — VM is $state")
                pendingApp = cn
            }
            else -> PrismLogger.logWarning(TAG, "sendAppIntent(${cn.flattenToShortString()}) dropped — VM is $state")
        }
    }

    fun isAvfSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false // API 34
        return try {
            val svc = context.getSystemService("virtualization_service")
            svc != null
        } catch (_: Exception) {
            false
        }
    }

    // ── AVF backend ───────────────────────────────────────────────────────────

    private fun startAvf(surface: Surface) {
        // Android Virtualization Framework path.
        // Requires android.permission.MANAGE_VIRTUAL_MACHINE and API 34+.
        // The PrismOS Microdroid payload must be present at filesDir/prism_os/vm_config.json.
        //
        // Reflection is used so the code compiles on older SDK targets.
        PrismLogger.logInfo(TAG, "startAvf(): resolving VirtualMachineManager via reflection")
        val vmMgrClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val vmMgr = context.getSystemService(vmMgrClass)
            ?: throw UnsupportedOperationException("VirtualMachineManager unavailable")

        val configDir = File(context.filesDir, PrismOsConfig.PAYLOAD_DIR)
        val configFile = File(configDir, PrismOsConfig.MICRODROID_CONFIG)
        if (!configFile.exists()) throw IllegalStateException("PrismOS image not found at ${configFile.path}")

        val configBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val configBuilder = configBuilderClass.getConstructor(Context::class.java).newInstance(context)
        configBuilderClass.getMethod("setPayloadConfigPath", String::class.java)
            .invoke(configBuilder, configFile.absolutePath)
        configBuilderClass.getMethod("setMemoryBytes", Long::class.javaPrimitiveType)
            .invoke(configBuilder, (PrismOsConfig.DEFAULT_RAM_MB * 1024L * 1024L))
        val config = configBuilderClass.getMethod("build").invoke(configBuilder)

        val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
        val getOrCreate = vmMgrClass.getMethod(
            "getOrCreate",
            String::class.java,
            Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        )
        val vm = getOrCreate.invoke(vmMgr, "prism_os", config)

        // Wire the surface as the VM display via VirtualDisplay
        try {
            vmClass.getMethod("setSurface", Surface::class.java).invoke(vm, surface)
        } catch (_: NoSuchMethodException) { /* older AVF build without display API */ }

        val callbackClass = Class.forName("android.system.virtualmachine.VirtualMachineCallback")
        val runMethod = vmClass.getMethod("run")
        runMethod.invoke(vm)

        PrismLogger.logInfo(TAG, "startAvf(): vm.run() returned — AVF VM launched")
        state = State.RUNNING
        pendingApp?.let { sendAppIntent(it); pendingApp = null }
    }

    // ── QEMU backend ──────────────────────────────────────────────────────────

    /**
     * The aarch64 UEFI firmware, unpacked from assets on first use.
     *
     * WITHOUT THIS NOTHING BOOTS. QEMU's "virt" machine deliberately has no boot ROM of its own --
     * it is a paravirtual board, not a PC -- so with no firmware there is nothing to read the ISO's
     * boot catalog and hand off to a bootloader. QEMU starts, shows a blank framebuffer, and looks
     * like it hung. Alpine and every other aarch64 install image expect UEFI.
     *
     * A USER-SUPPLIED FILE STILL WINS. Anyone who drops their own build at the payload path gets
     * theirs rather than the bundled one -- a firmware is exactly the sort of thing someone
     * debugging a boot problem will want to swap.
     */
    private fun prepareUefiFirmware(): File? {
        val userSupplied = File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/${PrismOsConfig.UEFI_FIRMWARE}")
        if (userSupplied.exists() && userSupplied.length() > 0) {
            PrismLogger.logInfo(TAG, "Using user-supplied firmware at ${userSupplied.absolutePath}")
            return userSupplied
        }

        return try {
            userSupplied.parentFile?.mkdirs()
            context.assets.open("qemu/${PrismOsConfig.UEFI_FIRMWARE}").use { input ->
                userSupplied.outputStream().use { input.copyTo(it) }
            }
            PrismLogger.logInfo(TAG, "Unpacked bundled UEFI firmware (${userSupplied.length()} bytes)")
            userSupplied
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Could not unpack the bundled UEFI firmware", e)
            null
        }
    }

    /**
     * Unpacks the parts of QEMU's data directory Prism ships, and returns it.
     *
     * ONLY THE KEYMAPS. A full QEMU install carries firmware blobs, ROMs and device trees, and
     * bundling all of that would add tens of megabytes for things the "virt" machine never asks
     * for. The keymap is different: it is required unconditionally by the VNC display, it is one
     * self-contained 27 KB text file, and without it QEMU refuses to start at all.
     *
     * Copied on every launch only when missing or empty -- an interrupted first run would
     * otherwise leave a truncated file that fails in a far more confusing way than an absent one.
     */
    private fun prepareQemuDataDir(): File? = try {
        val dataDir = File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/qemu-data")
        val keymaps = File(dataDir, "keymaps").apply { mkdirs() }

        val names = context.assets.list("qemu/keymaps").orEmpty()
        for (name in names) {
            val target = File(keymaps, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("qemu/keymaps/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            PrismLogger.logInfo(TAG, "Unpacked QEMU keymap $name (${target.length()} bytes)")
        }
        if (names.isEmpty()) null else dataDir
    } catch (e: Exception) {
        PrismLogger.logError(TAG, "Could not prepare QEMU's data directory", e)
        null
    }

    /**
     * The live VNC connection, kept so keystrokes can reach the guest.
     *
     * Held here rather than in the page because the connection outlives the view: a page can be
     * recycled and re-attached while the VM keeps running, and a renderer owned by the view would
     * be lost with it.
     */
    @Volatile
    private var vncRenderer: VncSurfaceRenderer? = null

    /**
     * Types a key into the guest.
     *
     * Returns false when nothing is connected, so the caller can leave the character in its own
     * input field rather than silently swallowing what the user typed.
     */
    fun sendKey(keysym: Int, shift: Boolean = false): Boolean {
        val renderer = vncRenderer ?: return false
        val sent = renderer.typeKey(keysym, shift)
        if (!sent && !renderer.isUsable) {
            // The socket died under it. Clearing the handle turns the next ensureVncConnected()
            // into a real reconnect rather than an early return on a corpse.
            PrismLogger.logWarning(TAG, "sendKey(): the VNC connection is dead; dropping it so it can reconnect")
            vncRenderer = null
        }
        return sent
    }

    /**
     * A single press or release, for modifiers that must stay HELD across another key.
     *
     * [sendKey] sends a press and a release together, which is right for a character and wrong for
     * Ctrl -- a Ctrl that releases before the key it modifies produces a plain keystroke, so Ctrl-C
     * arrives as the letter c.
     */
    fun sendKeyRaw(keysym: Int, pressed: Boolean): Boolean {
        val renderer = vncRenderer ?: return false
        val sent = renderer.sendKeyEvent(keysym, pressed)
        if (!sent && !renderer.isUsable) vncRenderer = null
        return sent
    }

    /** Whether a VNC connection is currently attached, for diagnostics. */
    fun hasVncConnection(): Boolean = vncRenderer != null

    /**
     * Re-attaches to a running VM whose renderer has been lost.
     *
     * Called when the page comes back and finds the VM notionally running with nothing connected --
     * after an app process restart, a recycled page, or a dropped socket. Cheap and idempotent:
     * it does nothing when a connection already exists.
     */
    fun ensureVncConnected(surface: Surface): Boolean {
        // REBIND, do not just report success. The Surface passed here is a NEW one -- the old was
        // destroyed when the page was recycled -- so an existing renderer is still drawing into a
        // dead target and silently rendering nothing. Returning true without rebinding was why the
        // VM looked frozen after the first swipe away and back.
        vncRenderer?.let {
            if (!it.isUsable) {
                PrismLogger.logWarning(TAG, "ensureVncConnected(): the existing renderer is dead; reconnecting")
                it.stopRenderer()
                vncRenderer = null
                return@let
            }
            it.rebind(surface)
            PrismLogger.logInfo(TAG, "ensureVncConnected(): rebound the existing renderer to the new surface")
            return true
        }
        if (!isVncPortLive()) return false
        PrismLogger.logInfo(TAG, "ensureVncConnected(): reattaching to the running VM")
        val ok = connectVncToSurface(VNC_PORT, surface)
        if (ok) state = State.RUNNING
        return ok
    }

    private fun startQemu(isoPath: String, isIso: Boolean, surface: Surface) {
        val qemuBin = resolveQemuBinary()
            ?: throw IllegalStateException(
                "QEMU binary not found. It must be bundled at " +
                "app/src/main/jniLibs/arm64-v8a/${PrismOsConfig.QEMU_BINARY} (installed into " +
                "${context.applicationInfo.nativeLibraryDir}) — Android won't execute a binary " +
                "placed anywhere else (e.g. assets or filesDir) on this API level."
            )
        PrismLogger.logInfo(TAG, "startQemu(): resolved binary at ${qemuBin.absolutePath}")

        // VNC on localhost:5900 so we can render to the SurfaceView
        val vncPort = VNC_PORT
        val cmd = mutableListOf(
            qemuBin.absolutePath,
            "-machine", "virt",
            // "-cpu max" asks the accelerator for every feature it supports, which is a known
            // crash trigger on several mobile/embedded QEMU builds' TCG (software) backend —
            // a fixed, well-tested aarch64 core model is far more conservative and portable
            // across whatever build a user bundles.
            "-cpu", "cortex-a72",
            // A sandboxed app has no access to /dev/kvm (that needs root), so KVM is never a real
            // option here — force TCG explicitly rather than trusting this binary's own
            // accelerator auto-probe, since a build that tries KVM and mishandles the resulting
            // permission failure is a very plausible cause of an instant, silent SIGSEGV.
            "-accel", "tcg",
            "-m", "${PrismOsConfig.DEFAULT_RAM_MB}"
        )
        // An .iso is CD-ROM media (ISO9660 + El Torito boot catalog) — attaching it as a raw
        // virtio block device (as if it were a disk image) skips that boot path entirely on most
        // Linux install media, which is why nothing appeared to boot.
        if (isIso) {
            cmd += listOf("-cdrom", isoPath)
        } else {
            cmd += listOf("-drive", "if=virtio,format=raw,file=$isoPath")
        }
        // The "virt" machine has no boot ROM of its own — without UEFI firmware handing off to
        // the disk's bootloader, QEMU just sits at a black framebuffer forever. Optional because
        // we can't bundle a firmware binary ourselves; if present, use it.
        val firmware = prepareUefiFirmware()
        if (firmware != null && firmware.exists()) {
            cmd += listOf("-bios", firmware.absolutePath)
            PrismLogger.logInfo(TAG, "startQemu(): using UEFI firmware ${firmware.absolutePath}")
        } else {
            PrismLogger.logWarning(TAG, "startQemu(): no UEFI firmware available — the virt machine has no boot ROM without one, so QEMU may run with nothing to display")
        }
        // QEMU'S DATA DIRECTORY. The VNC display ALWAYS initialises a keyboard layout -- it
        // defaults to "en-us" and there is no flag to skip it -- and it finds one by looking for
        // <datadir>/keymaps/<name>. A bare binary lifted out of a distro package has no datadir,
        // so the lookup fails and QEMU exits immediately with
        //   "could not read keymap file: 'en-us'"
        // which is exactly the code-1 exit this hit. -L points it at a directory Prism populates
        // from its own assets.
        val qemuData = prepareQemuDataDir()
        if (qemuData != null) {
            cmd += listOf("-L", qemuData.absolutePath)
            PrismLogger.logInfo(TAG, "startQemu(): QEMU data dir at ${qemuData.absolutePath}")
        } else {
            PrismLogger.logWarning(TAG, "startQemu(): no QEMU data dir — the VNC display will fail to load its keymap")
        }

        cmd += listOf(
            "-display", "vnc=127.0.0.1:${vncPort - 5900}",
            "-device", "virtio-gpu-pci",
            // INPUT DEVICES, WITHOUT WHICH THE GUEST HAS NO KEYBOARD.
            //
            // "virt" is a paravirtual board, not a PC: it has no PS/2 controller and QEMU adds no
            // input hardware to it by default. VNC key events were therefore being accepted and
            // then discarded, because there was no device to deliver them to -- the miner-style
            // failure where every layer reports success and nothing happens. The client was fine
            // the whole time; the machine simply had no keyboard plugged in.
            //
            // The tablet is an ABSOLUTE pointer rather than a relative mouse, which is what a
            // touchscreen needs: a tap reports where it is, instead of a delta from where the
            // cursor used to be.
            "-device", "virtio-keyboard-pci",
            "-device", "virtio-tablet-pci",
            // SERIAL CONSOLE ONTO STDOUT, which is already being drained into PrismLogger.
            //
            // Everything the firmware and the kernel say has been going to a device nobody reads.
            // That is why a guest that boots, fails to log in, and re-prompts looks identical to a
            // guest that ignores input: the one place that says which it is was discarded. With
            // this, Alpine's boot messages and its login failures land in logcat next to the
            // keysyms Prism sent, so the two can finally be compared.
            "-serial", "stdio",
            "-net", "none"
        )
        // Deliberately no "-nographic" — it disables the video/display devices entirely and
        // conflicts with "-display vnc=...", which is the only thing actually feeding the
        // SurfaceView.

        // Android launches app subprocesses with a near-empty environment — no HOME, no TMPDIR.
        // QEMU is built on glib, which resolves both during early startup (scratch files,
        // locking) via g_get_home_dir()/g_get_tmp_dir(); a build that doesn't defensively handle
        // a null/inaccessible result there is a very plausible cause of a crash this early, before
        // any of QEMU's own logging runs. Point both at real, writable, app-owned directories —
        // this is exactly what Termux's launcher does for you automatically, which is why the
        // same binary and flags can work there and crash here.
        val qemuHome = File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/home").apply { mkdirs() }
        val qemuTmp = File(context.cacheDir, "qemu_tmp").apply { mkdirs() }

        // A ProcessBuilder-spawned binary is a plain execve() through the system linker, not a
        // zygote/app_process fork — it never gets the automatic linker namespace that makes
        // System.loadLibrary() transparently find nativeLibraryDir. The Termux .so's RPATH was
        // also stripped (it pointed at a Termux path that doesn't exist here), so without an
        // explicit LD_LIBRARY_PATH the linker has no way to locate ANY of QEMU's ~30 sibling
        // dependencies, no matter how correctly they're named/renamed.
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        PrismLogger.logInfo(TAG, "startQemu(): launching: ${cmd.joinToString(" ")}")
        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(true)
        processBuilder.environment()["HOME"] = qemuHome.absolutePath
        processBuilder.environment()["TMPDIR"] = qemuTmp.absolutePath
        processBuilder.environment()["LD_LIBRARY_PATH"] = nativeLibDir
        val process = processBuilder.start()
        qemuProcess = process

        // A Process's stdout pipe has a small OS buffer — if nothing ever reads it, QEMU can
        // block on write() and hang forever the moment it logs enough output, which looks
        // identical to "stuck booting" from the UI. Drain it continuously on its own thread,
        // and mirror every line into PrismLogger so QEMU's own diagnostics show up in Diagnostics.
        val output = StringBuilder()
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > 8000) output.delete(0, output.length - 8000)
                    }
                    PrismLogger.logDebug("$TAG-qemu", line)
                }
            } catch (_: Exception) { /* stream closed on process exit */ }
        }, "VmController-qemu-output").apply { isDaemon = true; start() }

        // Give QEMU a moment to start, then confirm it's actually still alive before declaring
        // success — previously this was unconditional, so a QEMU that exited immediately (bad
        // flags, missing shared libs, unsupported CPU) still got reported as RUNNING.
        Thread.sleep(1500)
        if (!process.isAlive) {
            val tail = synchronized(output) { output.toString() }.takeLast(500)
            logCrashDetailsFromLogcat(process.exitValue())
            throw IllegalStateException("QEMU exited immediately (code ${process.exitValue()}): ${tail.ifBlank { "no output" }}")
        }
        PrismLogger.logInfo(TAG, "startQemu(): process alive after 1.5s (pid unknown on this API), connecting VNC on port $vncPort")

        val vncConnected = connectVncToSurface(vncPort, surface)
        if (!vncConnected) {
            // NOT "RUNNING". A state of RUNNING with no VNC connection is a lie the whole UI then
            // repeats: the control bar appears, the boot overlay hides, and the user is left
            // looking at a blank surface typing into nothing -- which is precisely how this
            // presented. Report what is actually true.
            PrismLogger.logWarning(TAG, "startQemu(): VNC never connected — nothing can render or receive input")
            runCatching { process.destroy() }
            qemuProcess = null
            throw IllegalStateException(
                "QEMU started but its VNC server never accepted a connection on port $vncPort. " +
                    "If a previous VM is still running, stop it and try again."
            )
        }

        // Nothing watches for the VM dying *after* this point otherwise — if QEMU crashes a few
        // seconds into boot rather than immediately, the app would just sit on RUNNING forever
        // with a dead process and a frozen/blank surface, no error surfaced anywhere.
        Thread({
            val exitCode = process.waitFor()
            if (qemuProcess === process) {
                PrismLogger.logWarning(TAG, "startQemu(): QEMU process exited (code $exitCode)")
                logCrashDetailsFromLogcat(exitCode)
                if (state == State.RUNNING || state == State.PAUSED) {
                    lastError = "QEMU process terminated unexpectedly (code $exitCode)"
                    state = State.ERROR
                }
            }
        }, "VmController-qemu-watchdog").apply { isDaemon = true; start() }

        state = State.RUNNING
        pendingApp?.let { sendAppIntent(it); pendingApp = null }
    }

    /**
     * When the QEMU subprocess dies from a signal (SIGSEGV etc. — a Unix exit code >= 128 encodes
     * 128+signal), the actual detail behind it can come from two completely different places:
     *
     * 1. A genuine native crash (bad pointer dereference, etc.) is handled by Android's own crash
     *    handler (debuggerd), which writes a full tombstone/backtrace to logcat's dedicated
     *    "crash" buffer.
     * 2. A dynamic-linker failure ("CANNOT LINK EXECUTABLE" — a missing .so dependency) is a
     *    DIFFERENT class of death entirely: it never reaches debuggerd at all, so the crash buffer
     *    is legitimately empty for it. The linker reports it through its own fatal-error path
     *    (tag "linker") into the general logcat buffer instead — this is the class of failure this
     *    exact binary kept hitting (missing libz.so.1, then further missing dependencies), and the
     *    crash-buffer-only check used to report it as empty/found-nothing even though the real
     *    cause was sitting right there in the main buffer the whole time.
     *
     * Neither flows through the process's own stdout/stderr, so the line-by-line capture in
     * [startQemu] can never show either one. Both are readable for the app's own UID without any
     * special permission (the subprocess shares Prism's UID) — check both, best-effort, so a
     * failure's actual cause shows up in Diagnostics automatically either way.
     */
    private fun logCrashDetailsFromLogcat(exitCode: Int) {
        if (exitCode < 128) return // not a signal death — nothing extra to find
        try {
            Thread.sleep(300) // debuggerd/the linker need a moment to finish writing to logcat

            val crashLog = readLogcatBuffer("crash", 200)
            if (crashLog.isNotBlank()) {
                PrismLogger.logError("$TAG-crash", crashLog)
                return
            }

            val mainLog = readLogcatBuffer("main", 300)
            val linkerLines = mainLog.lineSequence()
                .filter { it.contains("linker", ignoreCase = true) }
                .filter { it.contains("CANNOT LINK", ignoreCase = true) || it.contains("not found", ignoreCase = true) }
                .toList()
            if (linkerLines.isNotEmpty()) {
                PrismLogger.logError("$TAG-linker", linkerLines.joinToString("\n"))
            } else {
                PrismLogger.logWarning(TAG, "logCrashDetailsFromLogcat(): found nothing in the crash or main logcat buffers for signal ${exitCode - 128} — may have rotated out of both by the time this ran")
            }
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "logCrashDetailsFromLogcat(): couldn't read logcat — ${e.message}")
        }
    }

    private fun readLogcatBuffer(buffer: String, tailLines: Int): String {
        val logcatProcess = ProcessBuilder("logcat", "-d", "-b", buffer, "-t", "$tailLines")
            .redirectErrorStream(true)
            .start()
        val text = logcatProcess.inputStream.bufferedReader().readText().trim()
        logcatProcess.waitFor()
        return text
    }

    /**
     * Android 10+ mounts app-private storage `noexec` — a binary copied to filesDir can never be
     * exec()'d there regardless of chmod bits. The only guaranteed-executable location is the
     * app's nativeLibraryDir, populated from the lib-prefixed .so files under jniLibs/<abi> at
     * install time. So the QEMU binary must ship as
     * app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so (see [PrismOsConfig.QEMU_BINARY])
     * rather than as an asset.
     */
    private fun resolveQemuBinary(): File? {
        val fromNativeLibDir = File(context.applicationInfo.nativeLibraryDir, PrismOsConfig.QEMU_BINARY)
        if (fromNativeLibDir.exists()) return fromNativeLibDir
        PrismLogger.logWarning(TAG, "resolveQemuBinary(): not found at ${fromNativeLibDir.absolutePath}")
        return null
    }

    private fun pauseQemu() {
        // Send QEMU monitor command via stdin (if monitor pipe is wired) — no-op for now
    }

    private fun resumeQemu(surface: Surface) {
        // Re-connect VNC renderer to new surface after page re-attach
        connectVncToSurface(VNC_PORT, surface)
    }

    /**
     * Minimal VNC-to-Surface bridge: connects to the QEMU VNC server and pushes
     * frame updates to [surface] via [android.graphics.Canvas].
     * A full implementation would use a VNC client library; this wires up the socket
     * and hands off to [VncSurfaceRenderer].
     *
     * QEMU's VNC server isn't necessarily listening the instant the process starts, so this
     * retries with a short backoff instead of a single silent attempt — the previous version
     * tried once, swallowed any failure, and claimed in a comment that "the renderer will retry
     * on its own thread," which wasn't actually true; nothing retried, so a VNC server that took
     * slightly too long to come up meant no frames ever rendered, with no error anywhere.
     * Returns whether a connection was actually established.
     */
    /**
     * Points the VNC renderer at any display server on [port].
     *
     * Public because the Windows runtime needs it too: [WineSession] starts Xvfb and x11vnc rather
     * than QEMU, but what reaches the screen afterwards is the same VNC stream into the same
     * Surface, and duplicating the renderer for the second caller would mean two of them to fix.
     */
    fun attachVnc(port: Int, surface: Surface): Boolean = connectVncToSurface(port, surface)

    private fun connectVncToSurface(port: Int, surface: Surface): Boolean {
        val maxAttempts = 6
        repeat(maxAttempts) { attempt ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                // A second connection to the same server is wasteful and confusing -- it is why
                // "VNC ready" was appearing twice -- so an existing live one is rebound instead.
                val existing = vncRenderer
                if (existing != null && existing.isUsable) {
                    runCatching { socket.close() }
                    existing.rebind(surface)
                    PrismLogger.logInfo(TAG, "connectVncToSurface(): already connected; rebound instead")
                    return true
                }

                val renderer = VncSurfaceRenderer(socket, surface)
                vncRenderer?.stopRenderer()
                vncRenderer = renderer
                renderer.start()
                PrismLogger.logInfo(TAG, "connectVncToSurface(): connected on attempt ${attempt + 1}")
                return true
            } catch (e: Exception) {
                PrismLogger.logWarning(TAG, "connectVncToSurface(): attempt ${attempt + 1}/$maxAttempts failed — ${e.message}")
                if (attempt < maxAttempts - 1) Thread.sleep(500)
            }
        }
        return false
    }

    // ── ADB intent bridge ─────────────────────────────────────────────────────

    /**
     * Sends an ADB shell command to the PrismOS guest to start [cn] via am start.
     * For AVF guests this uses vsock; for QEMU guests it uses ADB over localhost TCP.
     */
    private fun sendViaAdb(cn: ComponentName) {
        try {
            val adbCmd = "am start -n ${cn.flattenToString()}"
            if (isAvfSupported()) {
                sendVsock(adbCmd)
            } else {
                sendAdbTcp(adbCmd)
            }
        } catch (_: Exception) { /* guest may not be ready yet */ }
    }

    private fun sendVsock(cmd: String) {
        // vsock requires android.system.VsockSocketAddress (API 33+)
        // Reflection used to keep compatibility with older targets
        try {
            val addrClass = Class.forName("android.system.VsockSocketAddress")
            val addr = addrClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(PrismOsConfig.VSOCK_CID, PrismOsConfig.VSOCK_PORT)
            val socket = Class.forName("android.net.LocalSocket")
                .getConstructor().newInstance()
            // Full vsock path requires NDK; placeholder for native implementation
            @Suppress("UNUSED_VARIABLE") val unused = listOf(addr, socket, cmd)
        } catch (_: Exception) { }
    }

    private fun sendAdbTcp(cmd: String) {
        Socket("127.0.0.1", 5555).use { s ->
            s.getOutputStream().write("$cmd\n".toByteArray())
        }
    }

    private fun defaultPrismOsQemuImage(): String =
        File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/prism_os.img").absolutePath
}
