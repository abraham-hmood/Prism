package com.prism.desktop.editor

import com.prism.core.PrismPlatform
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
 * The Node extension host, as a plain subprocess. PHASE 93.
 *
 * ## THE WIRE PROTOCOL IS THE SAME; EVERYTHING ELSE IS SIMPLER
 *
 * Android's `NodeRuntime` and this speak identically to `node-extension-host.js`: Node connects back
 * to a loopback `ServerSocket`, presents a token as its first line, and then exchanges
 * newline-delimited JSON. That is the contract, and it is unchanged -- so the host script is shared,
 * not forked.
 *
 * What is gone is the machinery around it. Android has to run Node inside PRoot with a staged Ubuntu
 * rootfs, rewrite the command into `proot -r <rootfs> ... /usr/local/bin/node`, and set up a guest
 * environment, because the kernel will not execute a file in an app's data directory. A desktop runs
 * the binary. `buildCommand`, `applyEnvironment` and `stageAssets` all collapse into "find node, pass
 * it a script path".
 *
 * ## The token is checked, and a wrong one is refused
 *
 * A loopback listener on an ephemeral port is reachable by every process on the machine, including
 * another user's. The first line Node sends is a 256-bit random token; anything else is closed. The
 * alternative -- trusting whoever connects first -- hands an arbitrary local program a channel into
 * the editor's extension host.
 *
 * ## Messages received before the page is listening are kept, bounded
 *
 * The page can be rebuilt (a tab change, a reload) while Node keeps running. A bounded backlog keeps
 * what arrived meanwhile and drops the OLDEST past the cap, because an extension's later messages are
 * the ones still worth delivering and an unbounded queue is a leak nobody notices until it matters.
 */
class NodeHost(private val onMessage: (String) -> Unit) {

    private companion object {
        const val TAG = "PrismNode"

        /**
         * How long to wait for Node to connect back.
         *
         * Fifteen seconds rather than Android's thirty: there is no ptrace tax here, so Node's own
         * bootstrap is a few hundred milliseconds and anything past this is a real failure that
         * should be reported rather than waited on.
         */
        const val CONNECT_TIMEOUT_MS = 15_000

        const val BACKLOG_LIMIT = 200
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    @Volatile
    var lastError: String? = null
        private set

    private val starting = AtomicBoolean(false)
    private val pending = ArrayDeque<String>()

    val isRunning: Boolean get() = writer != null && process?.isAlive == true

    /**
     * Where the host script is written before Node is pointed at it.
     *
     * STAGED OUT OF RESOURCES rather than referenced in place, because in a packaged build it is
     * inside a jar and Node cannot read a path into one. Rewritten on every start so a Prism update
     * cannot leave an old host script running against a new page.
     */
    private fun hostScript(): File? {
        val dir = File(PrismPlatform.host.dataDir(), "editor/host").apply { mkdirs() }
        val target = File(dir, "node-extension-host.js")
        val bytes = javaClass.classLoader
            .getResourceAsStream("editor/node-extension-host.js")?.use { it.readBytes() }
        if (bytes == null) {
            PrismPlatform.log.error(TAG, "node-extension-host.js is missing from the build")
            return null
        }
        return runCatching { target.writeBytes(bytes); target }.getOrNull()
    }

    fun unavailableReason(): String = when {
        !DesktopNode.isInstalled() ->
            "Node is not installed. The editor's web extensions still work; Node ones need it."
        isRunning -> ""
        else -> lastError ?: "Node is installed but the extension host is not running."
    }

    /**
     * Starts Node if it is not already running. Safe to call repeatedly.
     *
     * Returns false when there is nothing to start, so a caller that does not care about Node
     * extensions can ignore the result.
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val node = DesktopNode.executable() ?: run {
            lastError = null
            return false
        }
        if (!starting.compareAndSet(false, true)) return true

        return runCatching {
            val script = hostScript() ?: throw IllegalStateException("no host script")
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val token = newToken()

            val builder = ProcessBuilder(node.absolutePath, script.absolutePath)
                .redirectErrorStream(true)
            DesktopNode.prepare(builder, script.parentFile)
            builder.environment()["PRISM_EDITOR_PORT"] = server.localPort.toString()
            builder.environment()["PRISM_EDITOR_TOKEN"] = token
            builder.environment()["PRISM_EDITOR_ASSETS"] = script.parentFile.absolutePath

            // The listener is accepting BEFORE the process starts. The other order is a race the
            // fast path loses: a desktop Node can connect before `accept` is reached.
            acceptOn(server, token)

            val started = builder.start()
            process = started
            drainOutput(started)

            PrismPlatform.log.info(TAG, "Node starting on port " + server.localPort)
            starting.set(false)
            true
        }.getOrElse { error ->
            starting.set(false)
            lastError = error.message ?: error.javaClass.simpleName
            PrismPlatform.log.error(TAG, "Could not start the Node extension host", error)
            false
        }
    }

    fun stop() {
        closeChannel()
        val running = process
        process = null
        runCatching {
            running?.destroy()
            // A Node stuck in a tight loop ignores SIGTERM. Give it a moment, then take it.
            if (running?.waitFor(3, TimeUnit.SECONDS) == false) running.destroyForcibly()
        }
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    fun send(json: String) {
        val out = writer ?: return
        runCatching {
            synchronized(out) {
                out.write(json)
                out.write("\n")
                out.flush()
            }
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not reach the Node host: " + it.message)
        }
    }

    /** Anything Node said while nothing was listening, oldest first. Drains the backlog. */
    fun drainPending(): List<String> = synchronized(pending) {
        val copy = pending.toList()
        pending.clear()
        copy
    }

    private fun acceptOn(server: ServerSocket, token: String) {
        Thread({
            runCatching {
                server.use { listener ->
                    listener.soTimeout = CONNECT_TIMEOUT_MS
                    val client = listener.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val presented = reader.readLine()
                    if (presented != token) {
                        PrismPlatform.log.warn(TAG, "Rejected a connection with the wrong token")
                        runCatching { client.close() }
                        return@runCatching
                    }

                    socket = client
                    writer = OutputStreamWriter(client.getOutputStream())
                    lastError = null
                    PrismPlatform.log.success(TAG, "The Node extension host is connected")

                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        runCatching { onMessage(line) }.onFailure {
                            synchronized(pending) {
                                if (pending.size > BACKLOG_LIMIT) pending.removeFirst()
                                pending.addLast(line)
                            }
                        }
                    }
                }
            }.onFailure {
                lastError = it.message
                PrismPlatform.log.warn(TAG, "The Node channel closed: " + it.message)
            }
            closeChannel()
        }, "prism-node-channel").apply { isDaemon = true; start() }
    }

    /** Node's own stdout and stderr into Prism's log, so a crash is visible. */
    private fun drainOutput(process: Process) {
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                    if (line.isNotBlank()) PrismPlatform.log.debug(TAG, line.take(400))
                }
            }
        }, "prism-node-output").apply { isDaemon = true; start() }
    }

    private fun closeChannel() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
