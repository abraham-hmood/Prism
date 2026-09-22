package com.prism.launcher.editor

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.virtualization.WineContainer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Node.js runtime that runs VS Code extensions needing Node.
 *
 * ## Why Prism carries a Node at all
 *
 * The editor's first extension host was a Web Worker — vscode.dev's model, no Node, no filesystem.
 * It works, and it covers a small minority of the marketplace: most extensions declare a `main`
 * entry point and call `require('fs')`, `child_process` or a native addon on the first line of
 * `activate`. There is no shim for that.
 *
 * ## Why it is a process and not a library
 *
 * The obvious embedding — link `libnode.so`, call `node::Start` over JNI — was what this file used
 * to describe, and it is a dead end for two independent reasons:
 *
 * 1. `node::Start` initialises V8 for the process and does not return. One runtime per app launch,
 *    ever, with no restart. A crashed extension host stayed crashed until the user killed Prism.
 * 2. Android refuses to `execve` a file stored in an app's data directory. So `child_process`
 *    could reach things already on the device and nothing an extension shipped — which is most of
 *    what extensions spawn: language servers, `tsc`, `esbuild`, `ripgrep`, formatters.
 *
 * Running Node as a child process under PRoot fixes both. PRoot is the loader, so the kernel is
 * never handed an app-data path to execute and the second restriction simply does not apply
 * anywhere inside the guest. And a process can be stopped and started as often as it likes.
 *
 * ## Paths are the same inside and out
 *
 * The app's own storage is bound into the guest at *its own absolute path* — `/data/.../files` is
 * `/data/.../files` on both sides — and so is the user's external storage. That is a deliberate
 * choice over the tidier `/opt/extensions` style mapping: it means an extension directory, a
 * workspace file and an error message all name the same path in Kotlin, in Node and in the editor
 * UI. Every translation layer that is not written is a class of bug that cannot happen.
 *
 * ## The channel
 *
 * Newline-delimited JSON over a loopback socket. PRoot does not create a network namespace, so
 * `127.0.0.1` inside the guest is the same loopback as outside and no forwarding is needed. It is
 * authenticated with a random token because a loopback listener is reachable by every other app on
 * the device, and this one can read and write files.
 */
object NodeRuntime {

    private const val TAG = "PrismNode"

    /** Assets copied out for Node to read. Node has a filesystem; the APK's asset table is not one. */
    private val ASSETS = listOf("node-extension-host.js", "extension-host.js")

    private val starting = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null

    /** Lines that arrived before anyone was listening, and the listener itself. */
    @Volatile private var onMessage: ((String) -> Unit)? = null
    private val pending = ArrayDeque<String>()

    @Volatile private var lastError: String? = null

    /**
     * Whether a Node runtime can run on this device *and has been installed*.
     *
     * Two separate questions that used to be one. [canInstall] is about the device; this is about
     * whether the guest is actually on disk. Everything that decides how to treat an extension asks
     * this one, because an uninstalled runtime and an unsupported device look identical to an
     * extension and completely different to the user.
     */
    fun isInstalled(context: Context): Boolean = NodeInstaller.isInstalled(context)

    fun canInstall(context: Context): Boolean = NodeInstaller.unavailableReason(context) == null

    /**
     * Why Node is not available, phrased so it names the next step.
     *
     * A device that could run it and simply has not downloaded it yet is the common case by a wide
     * margin, and telling that user their device is unsupported would be wrong and unhelpful.
     */
    fun unavailableReason(context: Context): String =
        NodeInstaller.unavailableReason(context)
            ?: if (!isInstalled(context)) {
                "The Node.js runtime is not installed yet. Extensions that need Node cannot run " +
                    "until it is — about ${NodeInstaller.APPROXIMATE_DOWNLOAD_MB} MB to download. " +
                    "Web extensions work without it."
            } else {
                lastError ?: "The Node runtime is installed but is not running."
            }

    val isRunning: Boolean get() = writer != null && process?.isAlive == true

    /**
     * Starts Node if it is not already running.
     *
     * Returns false when there is nothing to start. Safe to call repeatedly — everything after the
     * first successful call is a no-op, which is what lets the editor page call it whenever it
     * decides it needs the host without tracking whether it asked before.
     */
    fun start(context: Context): Boolean {
        if (isRunning) return true
        if (!isInstalled(context)) {
            lastError = null
            return false
        }
        if (!starting.compareAndSet(false, true)) return true

        return runCatching {
            val appContext = context.applicationContext
            val assets = stageAssets(appContext)

            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val token = newToken()

            val command = buildCommand(
                appContext,
                listOf("/usr/local/bin/node", File(assets, "node-extension-host.js").absolutePath),
            )
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            applyEnvironment(appContext, builder)
            builder.environment()["PRISM_EDITOR_PORT"] = server.localPort.toString()
            builder.environment()["PRISM_EDITOR_TOKEN"] = token
            builder.environment()["PRISM_EDITOR_ASSETS"] = assets.absolutePath

            acceptOn(server, token)

            val started = builder.start()
            process = started
            drainOutput(started)

            PrismLogger.logInfo(TAG, "Node starting on port ${server.localPort}")
            starting.set(false)
            true
        }.getOrElse { error ->
            starting.set(false)
            lastError = error.message ?: error.javaClass.simpleName
            PrismLogger.logError(TAG, "Could not start the Node runtime", error)
            false
        }
    }

    /**
     * Stops Node.
     *
     * Unlike the embedded runtime this replaced, stopping is a real option: the editor page can
     * shut the host down when it goes away and bring it back when it returns, and a wedged
     * extension host can be restarted without restarting Prism. [restart] is what the UI offers.
     */
    fun stop() {
        closeChannel()
        val running = process
        process = null
        runCatching {
            running?.destroy()
            // PRoot forwards the signal to the guest, but a Node stuck in a tight loop can ignore
            // SIGTERM. Give it a moment, then take it.
            if (running?.waitFor(3, TimeUnit.SECONDS) == false) running.destroyForcibly()
        }
    }

    fun restart(context: Context): Boolean {
        stop()
        return start(context)
    }

    /** Registers the reader of lines coming back from Node, and flushes anything already queued. */
    fun setListener(listener: ((String) -> Unit)?) {
        onMessage = listener
        if (listener == null) return
        synchronized(pending) {
            while (pending.isNotEmpty()) listener(pending.removeFirst())
        }
    }

    /** Sends one message. Silently dropped if Node is not up: the page retries nothing either way. */
    fun send(json: String) {
        val out = writer ?: return
        runCatching {
            synchronized(out) {
                out.write(json)
                out.write("\n")
                out.flush()
            }
        }.onFailure { PrismLogger.logWarning(TAG, "Could not reach the Node host: ${it.message}") }
    }

    // ── Running things in the guest ────────────────────────────────────────

    data class GuestResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Runs one command inside the guest and waits for it.
     *
     * This is what makes the runtime *useful* beyond the extension host: `npm install` for an
     * extension's dependencies, `node --version` for a diagnostic, an extension's own CLI for a
     * task. All of it blocking, all of it off the main thread by the caller's arrangement.
     */
    fun runInGuest(
        context: Context,
        command: List<String>,
        workingDirectory: String = context.filesDir.absolutePath,
        timeoutMinutes: Long = 10,
        onLine: ((String) -> Unit)? = null,
    ): GuestResult {
        if (!isInstalled(context)) return GuestResult(-1, "The Node runtime is not installed.")

        val builder = ProcessBuilder(buildCommand(context, command, workingDirectory))
            .redirectErrorStream(true)
        applyEnvironment(context, builder)

        return runCatching {
            val started = builder.start()
            val output = StringBuilder()
            // Read as it comes. A child whose output nobody drains fills the pipe buffer and blocks
            // on its next write, which is indistinguishable from a hang — and npm writes a lot.
            started.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (output.length < 256 * 1024) output.append(line).append('\n')
                    onLine?.invoke(line)
                }
            }
            if (!started.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                started.destroyForcibly()
                return GuestResult(-1, "Timed out after $timeoutMinutes minutes.\n$output")
            }
            GuestResult(started.exitValue(), output.toString())
        }.getOrElse { error ->
            GuestResult(-1, error.message ?: "The command could not be run.")
        }
    }

    /**
     * Installs an extension's dependencies.
     *
     * Most `.vsix` packages bundle everything and need nothing here. The ones that do not are
     * exactly the "desktop" extensions — the ones whose `main` build expects a real Node with a
     * real npm — and before there was a guest there was no way to give them one.
     */
    fun installDependencies(
        context: Context,
        extensionDirectory: File,
        onLine: ((String) -> Unit)? = null,
    ): GuestResult {
        if (!File(extensionDirectory, "package.json").isFile) {
            return GuestResult(0, "No package.json; nothing to install.")
        }
        if (File(extensionDirectory, "node_modules").isDirectory) {
            return GuestResult(0, "Dependencies are already present.")
        }
        return runInGuest(
            context,
            listOf("/usr/local/bin/npm", "install", "--omit=dev", "--no-audit", "--no-fund"),
            workingDirectory = extensionDirectory.absolutePath,
            timeoutMinutes = 20,
            onLine = onLine,
        )
    }

    /** `node --version` from the guest, as a health check the user can read. */
    fun version(context: Context): String? {
        if (!isInstalled(context)) return null
        val result = runInGuest(context, listOf("/usr/local/bin/node", "--version"), "/", 1)
        return result.output.trim().takeIf { result.ok && it.startsWith("v") }
    }

    // ── The guest command line ─────────────────────────────────────────────

    /**
     * Wraps a command so it runs inside the guest.
     *
     * Each `-b` is a bind mount. `/dev`, `/proc` and `/sys` are what any libc expects to find;
     * the app's own files and the user's storage are bound at their own absolute paths so nothing
     * has to translate between what Kotlin calls a file and what Node does.
     */
    private fun buildCommand(
        context: Context,
        command: List<String>,
        workingDirectory: String = context.filesDir.absolutePath,
    ): List<String> {
        val proot = WineContainer.prootBinary(context)
            ?: error("PRoot is missing from this build")
        val rootfs = NodeInstaller.rootfs(context)
        val files = context.filesDir.absolutePath
        val external = android.os.Environment.getExternalStorageDirectory().absolutePath

        return listOf(
            proot.absolutePath,
            // Kills the whole guest when PRoot exits, so a Node that spawned a language server does
            // not leave it running after the editor page has gone.
            "--kill-on-exit",
            // Report uid 0 inside. npm refuses some operations as an unknown uid, and nothing
            // outside the guest gains any privilege — it only changes what getuid() answers.
            "-0",
            // Hard links become symlinks. npm makes hard links while linking packages, and an app
            // data directory on Android does not reliably allow them.
            "-l",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            // Same path inside as out. See the class comment.
            "-b", "$files:$files",
            "-b", "$external:$external",
            "-w", workingDirectory,
            "/usr/bin/env",
            "HOME=/root",
            "USER=root",
            "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "npm_config_prefix=/usr/local",
            // V8 sizes its old space from total device RAM, which on a phone is far more than a
            // background runtime should claim — and exceeding the app's cgroup limit kills Prism
            // outright rather than throwing something catchable.
            "NODE_OPTIONS=--max-old-space-size=512",
        ) + command
    }

    /**
     * An interactive shell inside the guest, for the editor's terminal panel.
     *
     * `bash -l` rather than `sh -c`: the login shell reads `/etc/profile.d`, which is where the
     * install puts Node on the PATH, so a user typing `node` gets Node without exporting anything.
     */
    fun guestShellCommand(context: Context, workingDirectory: String?): List<String> =
        buildCommand(
            context,
            listOf("/bin/bash", "-l"),
            workingDirectory = workingDirectory?.takeIf { File(it).isDirectory }
                ?: context.filesDir.absolutePath,
        )

    /**
     * PRoot's own environment, which is not optional.
     *
     * PROOT_LOADER is the one that is not obvious. PRoot cannot execute a binary out of the rootfs
     * directly — that is the whole restriction it exists to work around — so it substitutes a tiny
     * static loader from the native library directory, which then maps the real ELF. Without this
     * variable every exec inside the guest fails.
     */
    private fun applyEnvironment(context: Context, builder: ProcessBuilder) {
        WineContainer.applyProotEnvironment(context, builder)
    }

    /** The same, for callers outside this file that assemble their own guest command. */
    fun applyGuestEnvironment(context: Context, builder: ProcessBuilder) =
        applyEnvironment(context, builder)

    // ── Plumbing ───────────────────────────────────────────────────────────

    /**
     * Waits for Node to call back, checks its token, then reads lines forever.
     *
     * The token check is the whole reason this is not just `server.accept()`: any app on the device
     * can connect to a loopback port, and whoever is on the other end of this one can drive the
     * editor's filesystem access. A connection that does not present the token is closed without a
     * reply.
     */
    private fun acceptOn(server: ServerSocket, token: String) {
        Thread({
            runCatching {
                server.use { listener ->
                    // 30 seconds: PRoot adds a ptrace-shaped tax to Node's own bootstrap, which on
                    // a phone is a few seconds. Beyond that something is wrong, and hanging this
                    // thread forever would hide it.
                    listener.soTimeout = 30_000
                    val client = listener.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val presented = reader.readLine()
                    if (presented != token) {
                        PrismLogger.logWarning(TAG, "Rejected a connection with the wrong token")
                        runCatching { client.close() }
                        return@runCatching
                    }

                    socket = client
                    writer = OutputStreamWriter(client.getOutputStream())
                    lastError = null
                    PrismLogger.logSuccess(TAG, "The Node extension host is connected")

                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val listenerRef = onMessage
                        if (listenerRef != null) listenerRef(line)
                        else synchronized(pending) {
                            // The page can be rebuilt while Node keeps running. Keep a bounded
                            // backlog so nothing important is lost, and drop the oldest rather than
                            // grow without limit if nobody ever comes back.
                            if (pending.size > 200) pending.removeFirst()
                            pending.addLast(line)
                        }
                    }
                }
            }.onFailure {
                lastError = it.message
                PrismLogger.logWarning(TAG, "The Node channel closed: ${it.message}")
            }
            closeChannel()
        }, "prism-node-channel").apply { isDaemon = true; start() }
    }

    /**
     * Reads Node's stdout and stderr into the log.
     *
     * Not decoration: a Node that fails during bootstrap — a missing shared library, a rootfs that
     * did not finish unpacking — says so on stderr and then exits, and without this the only
     * symptom would be a socket that never connects.
     */
    private fun drainOutput(started: Process) {
        Thread({
            runCatching {
                started.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) PrismLogger.logInfo(TAG, "node: $line")
                    }
                }
            }
            val code = runCatching { started.waitFor() }.getOrDefault(-1)
            PrismLogger.logInfo(TAG, "The Node runtime exited with code $code")
            if (process === started) {
                process = null
                lastError = "The Node runtime exited with code $code."
            }
            closeChannel()
        }, "prism-node-output").apply { isDaemon = true; start() }
    }

    private fun closeChannel() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    /**
     * Copies the host scripts out of assets.
     *
     * Rewritten every start rather than only when missing: the scripts ship with the APK, so after
     * an update the staged copy is the old one, and an extension host a version behind the editor
     * it talks to fails in ways nobody would think to look for.
     */
    private fun stageAssets(context: Context): File {
        val home = File(context.filesDir, "editor/node").apply { mkdirs() }
        ASSETS.forEach { name ->
            context.assets.open("editor/$name").use { input ->
                File(home, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return home
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
