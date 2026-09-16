package com.prism.launcher.editor

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Node.js runtime that runs VS Code extensions needing Node.
 *
 * ## Why Prism carries a Node at all
 *
 * The editor's first extension host was a Web Worker -- vscode.dev's model, no Node, no filesystem.
 * It works, and it covers a small minority of the marketplace: most extensions declare a `main`
 * entry point and call `require('fs')`, `child_process` or a native addon on the first line of
 * `activate`. There is no shim for that. So Prism links `libnode.so`, a full Node.js built as an
 * Android shared library, and runs those extensions in it.
 *
 * ## One runtime, for the life of the process
 *
 * `node::Start` initialises V8 and the platform for the whole process and does not return until the
 * script exits. Starting a second one is not supported, and neither is starting one again after the
 * first has exited -- so this starts at most once and then stays up, idle, until the process dies.
 * Nothing shuts it down when the editor page goes away: an idle event loop costs almost nothing,
 * and a user who returns to the page would otherwise find extensions permanently broken with no
 * explanation.
 *
 * ## The channel
 *
 * Newline-delimited JSON over a loopback socket. Loopback rather than a pipe because Node's `net`
 * already speaks it on both ends and a JNI pipe would need its own framing, error handling and
 * thread; and authenticated with a random token because a loopback listener is reachable by every
 * other app on the device, and this one can read and write files.
 *
 * ## What Node still cannot do here
 *
 * Android refuses to execute a binary stored in an app's data directory, so an extension that ships
 * its own CLI and spawns it fails at the `exec` -- the same kernel rule that forces PRoot on the
 * Windows runtime. `child_process` works for things already executable on the device; a bundled
 * executable is not one of them.
 */
object NodeRuntime {

    private const val TAG = "PrismNode"

    /** Assets copied out for Node to read. Node has a filesystem; the APK's asset table is not one. */
    private val ASSETS = listOf("node-extension-host.js", "extension-host.js")

    private val started = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null

    /** Lines that arrived before anyone was listening, and the listener itself. */
    @Volatile private var onMessage: ((String) -> Unit)? = null
    private val pending = ArrayDeque<String>()

    @Volatile private var loadFailure: String? = null

    private external fun nativeStart(args: Array<String>): Int
    private external fun nativeSetEnv(name: String, value: String)

    /**
     * Whether a Node runtime can run on this device.
     *
     * The library is only built for the ABIs it exists for (see CMakeLists.txt), and a checkout
     * without `libnode.so` produces an app with no bridge at all -- so this is a real question, not
     * a formality, and the answer decides whether a Node extension is installable.
     */
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("prism_node")
            true
        }.getOrElse { error ->
            loadFailure = error.message ?: error.javaClass.simpleName
            PrismLogger.logInfo(TAG, "No Node runtime on this device: $loadFailure")
            false
        }
    }

    /** Why Node is unavailable, for a message a user can act on. */
    fun unavailableReason(): String =
        "This build of Prism has no Node.js runtime for this device, so extensions that need Node " +
            "cannot run. Web extensions still work."

    /**
     * Starts Node if it is not already running.
     *
     * Returns false when there is no runtime to start. Safe to call repeatedly -- everything after
     * the first call is a no-op, which is what lets the editor page call it whenever it decides it
     * needs the host without tracking whether it asked before.
     */
    fun start(context: Context): Boolean {
        if (!isAvailable) return false
        if (!started.compareAndSet(false, true)) return true

        return runCatching {
            val appContext = context.applicationContext
            val home = stageAssets(appContext)

            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val token = newToken()

            // Everything Node reads during bootstrap has to be in the environment before it starts;
            // assigning process.env later is too late (see the runtime's own embedding notes).
            nativeSetEnv("PRISM_EDITOR_PORT", server.localPort.toString())
            nativeSetEnv("PRISM_EDITOR_TOKEN", token)
            nativeSetEnv("PRISM_EDITOR_ASSETS", home.absolutePath)
            // There is no /tmp on Android, and os.tmpdir() falls back to exactly that path -- so any
            // dependency writing a temp file fails with ENOENT until this is set.
            nativeSetEnv("TMPDIR", appContext.cacheDir.absolutePath)
            // A great deal of npm code calls os.homedir() unconditionally.
            nativeSetEnv("HOME", appContext.filesDir.absolutePath)
            // V8 sizes its old space from total device RAM, which on a phone is far more than a
            // background runtime should claim -- and exceeding the app's cgroup limit kills Prism
            // outright rather than throwing something catchable.
            nativeSetEnv("NODE_OPTIONS", "--max-old-space-size-percentage=25")

            acceptOn(server, token)

            Thread({
                val script = File(home, "node-extension-host.js").absolutePath
                val code = runCatching { nativeStart(arrayOf("node", script)) }
                    .getOrElse { error ->
                        PrismLogger.logError(TAG, "The Node runtime could not start", error)
                        -1
                    }
                PrismLogger.logInfo(TAG, "The Node runtime exited with code $code")
                closeChannel()
            }, "prism-node").apply { isDaemon = true; start() }

            PrismLogger.logInfo(TAG, "Node starting on port ${server.localPort}")
            true
        }.getOrElse { error ->
            started.set(false)
            PrismLogger.logError(TAG, "Could not start the Node runtime", error)
            false
        }
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

    val isRunning: Boolean get() = writer != null

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
                    // 20 seconds is generous: Node's own bootstrap is around a second on a phone.
                    // Beyond that something is wrong, and hanging this thread forever would hide it.
                    listener.soTimeout = 20_000
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
            }.onFailure { PrismLogger.logWarning(TAG, "The Node channel closed: ${it.message}") }
            closeChannel()
        }, "prism-node-channel").apply { isDaemon = true; start() }
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
