package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.pdb.Pdb
import com.prism.launcher.pdb.ShellWords
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Makes trusted Prism Debug Bridge devices visible to the `adb` already on this machine.
 *
 * ## Why a socket server and not a wrapper script
 *
 * `adb` has a device transport protocol: a host process opens a TCP connection, exchanges a
 * `CNXN`, then opens named streams (`shell:...`, `sync:...`). A shell alias that forwards an
 * argument cannot satisfy that, and anything that parses `adb`'s command line is a second, worse
 * adb. This class speaks the transport. Every verified PDB device is registered automatically, so
 * an ordinary `adb devices` in Windows Terminal, PowerShell, a Linux shell or Android Studio
 * lists the phone without opening Prism's dialog first.
 *
 * ## What is carried
 *
 * `shell` and `exec` streams are carried over PDB, including the shell-v2 stdout/stderr/exit
 * framing. `sync:` push and pull are carried too, as is a streaming install (`cmd package
 * install -S`), so `adb install`, `adb push` and `adb pull` work from an external terminal. A
 * `sync` write to `/data/local/tmp` is filed in the target's PDB incoming folder because an
 * Android app cannot write adbd's private temp directory.
 *
 * ## Security
 *
 * The listener binds loopback only. It does not authenticate local callers because adb has no
 * such concept: any process that can reach 127.0.0.1 can run commands, exactly as it can against
 * a real adb server. The PDB session underneath is authenticated and encrypted by the pair secret
 * before any command is accepted.
 */
object AdbBridge {

    /** A bridge that is accepting connections. [serial] is what to pass to `adb -s`. */
    class Running internal constructor(
        val port: Int,
        val serial: String,
        val fingerprint: String,
        @Volatile var device: Pdb.Device,
        internal val pdbPort: Int,
        private val server: ServerSocket,
    ) : Closeable {
        private val clients = ConcurrentHashMap.newKeySet<Socket>()

        /** True once `adb devices` reports this bridge as a device. */
        @Volatile
        var connected: Boolean = false
            internal set

        /** What `adb connect` said, for the UI when [connected] is false. */
        @Volatile
        var message: String = ""
            internal set

        internal fun active(): Boolean = !server.isClosed

        internal fun track(socket: Socket) {
            clients.add(socket)
        }

        internal fun untrack(socket: Socket) {
            clients.remove(socket)
        }

        override fun close() {
            runCatching { server.close() }
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private val bridges = ConcurrentHashMap<String, Running>()
    private val suppressed = ConcurrentHashMap.newKeySet<String>()
    private val supervisorStarted = AtomicBoolean(false)

    /** True when an adb binary can be executed. Cached because it starts a process. */
    @Volatile
    private var cachedAdb: String? = null

    @Volatile
    private var lookedForAdb = false

    /**
     * The adb command to run, or null when platform-tools are not installed.
     *
     * `adb` is looked up on PATH first so a Homebrew or Android Studio installation is used as the
     * user configured it. When it is not on PATH, the conventional `ANDROID_HOME`/`ANDROID_SDK_ROOT`
     * locations are tried.
     */
    fun adb(): String? {
        if (lookedForAdb) return cachedAdb
        synchronized(this) {
            if (lookedForAdb) return cachedAdb
            val exe = if (isWindows()) "adb.exe" else "adb"
            val candidates = buildList {
                add("adb")
                System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let {
                    add(File(File(it, "platform-tools"), exe).path)
                }
                System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let {
                    add(File(File(it, "platform-tools"), exe).path)
                }
                System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
                    add(File(File(it, "Android/Sdk/platform-tools"), exe).path)
                }
                System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let {
                    add(File(File(it, "Library/Android/sdk/platform-tools"), exe).path)
                    add(File(File(it, "Android/Sdk/platform-tools"), exe).path)
                }
            }
            cachedAdb = candidates.firstOrNull { candidate ->
                runCatching {
                    val process = ProcessBuilder(candidate, "version")
                        .redirectErrorStream(true)
                        .start()
                    val finished = process.waitFor(4, TimeUnit.SECONDS)
                    if (finished) process.exitValue() == 0 else {
                        process.destroyForcibly()
                        false
                    }
                }.getOrDefault(false)
            }
            lookedForAdb = true
            return cachedAdb
        }
    }

    fun available(): Boolean = adb() != null

    /** The first line of `adb version`, for the UI to show what it will drive. */
    fun version(): String? {
        val binary = adb() ?: return null
        return runCatching {
            val process = ProcessBuilder(binary, "version").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().lineSequence().firstOrNull()
            process.waitFor(3, TimeUnit.SECONDS)
            text?.trim()
        }.getOrNull()
    }

    /**
     * Starts the bridge and registers it with the adb server.
     *
     * Idempotent per fingerprint: opening the dialog twice does not create two transports for one
     * phone. [manual] clears a previous explicit stop so the auto-supervisor may adopt it again.
     */
    fun start(
        device: Pdb.Device,
        manual: Boolean = false,
        port: Int = Pdb.PORT,
        log: (String) -> Unit = { PrismPlatform.log.info("PrismAdb", it) },
    ): Running {
        if (manual) suppressed.remove(device.fingerprint)
        synchronized(this) {
            bridges[device.fingerprint]?.takeIf { it.active() }?.let { existing ->
                existing.device = device
                if (!existing.connected) refresh(existing, log)
                return existing
            }
            adb() ?: error("adb is not installed")
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val serial = "127.0.0.1:" + server.localPort
            val running = Running(server.localPort, serial, device.fingerprint, device, port, server)
            bridges[device.fingerprint] = running
            thread(name = "prism-adb-accept", isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    if (!socket.inetAddress.isLoopbackAddress) {
                        log("Refused a non-loopback ADB client at " + socket.inetAddress)
                        runCatching { socket.close() }
                        continue
                    }
                    running.track(socket)
                    thread(name = "prism-adb-" + socket.port, isDaemon = true) {
                        runCatching { AdbConnection(socket, running.device, running.pdbPort, log).run() }
                            .onFailure { log("ADB connection closed: " + (it.message ?: "unknown error")) }
                            .also {
                                running.untrack(socket)
                                runCatching { socket.close() }
                            }
                    }
                }
            }
            refresh(running, log)
            return running
        }
    }

    /**
     * Starts the background supervisor. Call once at desktop startup.
     *
     * It is the answer to "why did `adb devices` not list my phone": the transport existed only
     * after somebody found and pressed a button in a dialog. Now every verified, online PDB device
     * is connected as soon as the app starts, and reconnected after an adb restart, an IP change,
     * or a laptop wake.
     */
    fun supervise(log: (String) -> Unit = { PrismPlatform.log.info("PrismAdb", it) }) {
        if (!supervisorStarted.compareAndSet(false, true)) return
        thread(name = "prism-adb-supervisor", isDaemon = true) {
            var warnedMissing = false
            while (true) {
                try {
                    if (adb() == null) {
                        if (!warnedMissing) {
                            log("No adb on PATH; PDB devices will not appear in a terminal adb.")
                            warnedMissing = true
                        }
                    } else {
                        warnedMissing = false
                        val devices = runCatching { Pdb.devices() }.getOrDefault(emptyList())
                        val fingerprints = devices.map { it.fingerprint }.toSet()
                        bridges.values.filter { it.fingerprint !in fingerprints }.forEach {
                            stopInternal(it, log)
                        }
                        if (Pdb.isAvailable()) {
                            devices.forEach { device ->
                                if (device.fingerprint in suppressed) return@forEach
                                val existing = bridges[device.fingerprint]
                                if (existing == null) {
                                    runCatching { start(device, log = log) }
                                        .onFailure { log("Could not bridge " + device.name + ": " + (it.message ?: "unknown")) }
                                } else if (existing.active()) {
                                    existing.device = device
                                    if (!existing.connected) refresh(existing, log)
                                }
                            }
                        }
                    }
                } catch (failure: Exception) {
                    log("ADB supervisor: " + (failure.message ?: "unknown error"))
                }
                Thread.sleep(5_000L)
            }
        }
    }

    /** Removes the bridge from the adb server and stops accepting. */
    fun stop(running: Running) {
        suppressed.add(running.fingerprint)
        stopInternal(running) { PrismPlatform.log.info("PrismAdb", it) }
    }

    /** Stops a bridge without suppressing automatic reconnection. */
    fun stopInternal(running: Running, log: (String) -> Unit = {}) {
        if (bridges.remove(running.fingerprint, running)) {
            adb()?.let { binary ->
                runCatching {
                    val process = ProcessBuilder(binary, "disconnect", running.serial).start()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
            }
            running.close()
            log("Stopped the adb bridge for " + running.device.name)
        }
    }

    fun control(args: List<String>): String? {
        val command = args.firstOrNull()?.lowercase() ?: return null
        return when (command) {
            "start-server" -> {
                val output = runAdb("start-server")
                bridges.values.forEach { refresh(it) { line -> PrismPlatform.log.info("PrismAdb", line) } }
                output.ifBlank { "adb server started." } + "\n"
            }
            "kill-server" -> {
                val output = runAdb("kill-server")
                bridges.values.forEach { it.connected = false }
                output.ifBlank { "adb server stopped." } + "\n"
            }
            "devices" -> runAdb("devices", "-l").ifBlank { "List of devices attached\n" }
            "connect" -> {
                val serial = args.getOrNull(1)
                val running = bridges.values.firstOrNull { it.serial == serial }
                    ?: return "No Prism bridge with serial $serial.\n"
                suppressed.remove(running.fingerprint)
                refresh(running) { PrismPlatform.log.info("PrismAdb", it) }
                running.message + "\n"
            }
            "disconnect" -> {
                val serial = args.getOrNull(1)
                val running = bridges.values.firstOrNull { it.serial == serial }
                    ?: return "No Prism bridge with serial $serial.\n"
                stop(running)
                "Disconnected $serial.\n"
            }
            "version" -> version()
            else -> null
        }
    }

    private fun refresh(running: Running, log: (String) -> Unit = {}): Boolean {
        val binary = adb() ?: return false
        val result = runAdb("connect", running.serial).trim()
        val state = runAdb("-s", running.serial, "get-state").trim()
        running.connected = state == "device"
        running.message = result.ifBlank { "adb connect " + running.serial }
        if (running.connected) {
            log("ADB bridge ready for " + running.device.name + " at " + running.serial)
        } else {
            log("adb did not attach to " + running.serial + "; is the adb server running?")
        }
        return running.connected
    }

    private fun runAdb(vararg args: String, timeoutSeconds: Long = 8): String {
        val binary = adb() ?: return ""
        return runCatching {
            val process = ProcessBuilder(listOf(binary) + args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ""
            } else {
                process.inputStream.bufferedReader().readText().trim()
            }
        }.getOrElse { "" }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * One host connection. adb keeps a transport connection open and multiplexes streams over it,
     * so this object owns exactly one [Pdb.Client] and serialises command execution on it.
     */
    private class AdbConnection(
        private val socket: Socket,
        private val device: Pdb.Device,
        private val pdbPort: Int,
        private val log: (String) -> Unit,
    ) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()
        private val commandLock = Any()
        private val nextLocal = AtomicInteger(1)
        private val nextCommand = AtomicInteger(1)
        private val streams = ConcurrentHashMap<Int, Stream>()
        private val pdbLock = Any()
        private var pdb: Pdb.Client? = null
        private var incomingPath: String? = null

        // Protocol 0x01000000 caps a packet at 4 KiB. Writes are chunked below it.
        private val maxData = 4096

        fun run() {
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            val first = readPacket() ?: return
            if (first.command != A_CNXN) {
                log("ADB bridge expected CNXN, received 0x" + Integer.toHexString(first.command))
                return
            }
            val model = device.name.ifBlank { "Prism device" }
                .filter { it != ';' && it != '\u0000' && it != '\r' && it != '\n' }.take(120)
            val banner = "device::ro.product.name=prism;ro.product.model=" + model +
                ";ro.product.device=prism;features=shell_v2;"
            writePacket(A_CNXN, VERSION, maxData, banner.toByteArray(Charsets.UTF_8))
            while (!socket.isClosed) {
                val packet = readPacket() ?: break
                when (packet.command) {
                    A_OPEN -> onOpen(packet)
                    A_WRTE -> {
                        val stream = streams[packet.arg1] ?: streams[packet.arg0]
                        if (stream != null) stream.onData(packet.data)
                        writePacket(A_OKAY, packet.arg1, packet.arg0, ByteArray(0))
                    }
                    A_CLSE -> (streams.remove(packet.arg1) ?: streams.remove(packet.arg0))?.onClose()
                    A_AUTH -> break
                }
            }
            streams.values.forEach { it.onClose() }
            closePdb()
            runCatching { socket.close() }
        }

        private fun onOpen(packet: Packet) {
            val service = String(packet.data, Charsets.UTF_8).trimEnd('\u0000')
            val local = nextLocal.getAndIncrement()
            val remote = packet.arg0
            if (remote <= 0) {
                writePacket(A_CLSE, local, remote, ByteArray(0))
                return
            }
            val stream: Stream? = when {
                service.startsWith("sync:") -> SyncStream(local, remote)
                else -> requestOf(service)?.let { ShellStream(local, remote, it) }
            }
            if (stream == null) {
                log("ADB refused " + service.take(60) + " (only shell, exec and sync are carried)")
                writePacket(A_CLSE, local, remote, ByteArray(0))
                return
            }
            streams[local] = stream
            log("ADB opened " + service.take(80))
            writePacket(A_OKAY, local, remote, ByteArray(0))
            thread(name = "prism-adb-stream-$local", isDaemon = true) {
                try {
                    stream.run()
                } catch (failure: Exception) {
                    log("ADB stream ended: " + (failure.message ?: "unknown error"))
                } finally {
                    streams.remove(local)
                    runCatching { writePacket(A_CLSE, local, remote, ByteArray(0)) }
                }
            }
        }

        private fun requestOf(service: String): ShellRequest? {
            if (service.startsWith("shell,")) {
                val colon = service.indexOf(':')
                if (colon < 0) return null
                val options = service.substring(6, colon).split(',')
                return ShellRequest(
                    command = service.substring(colon + 1),
                    v2 = options.contains("v2"),
                )
            }
            return when {
                service.startsWith("shell:") -> ShellRequest(service.removePrefix("shell:"), v2 = false)
                service.startsWith("exec:") -> ShellRequest(service.removePrefix("exec:"), v2 = false, exec = true)
                else -> null
            }
        }

        private fun openPdb(): Pdb.Client = synchronized(pdbLock) {
            pdb?.let { return it }
            val connection = Pdb.Client(device.target(), pdbPort)
            connection.connect()
            when (val hello = connection.hello()) {
                is Pdb.Hello.Trusted -> Unit
                is Pdb.Hello.Challenge -> {
                    connection.close()
                    error("the device needs to be verified again in the bridge list")
                }
                is Pdb.Hello.Denied -> {
                    connection.close()
                    error(hello.reason)
                }
            }
            connection.open().getOrThrow()
            pdb = connection
            connection
        }

        private fun closePdb() {
            synchronized(pdbLock) {
                runCatching { pdb?.bye() }
                runCatching { pdb?.close() }
                pdb = null
            }
        }

        private fun incoming(): String {
            incomingPath?.let { return it }
            val output = openPdb().execCapture("pdb:incoming").second
            val path = output.lineSequence().firstOrNull()?.trim().orEmpty()
            if (path.isNotBlank()) incomingPath = path
            return path
        }

        private fun mapTargetPath(path: String): String =
            if (path.startsWith("/data/local/tmp/")) {
                val incoming = incoming()
                if (incoming.isBlank()) path else incoming.trimEnd('/') + "/" + path.substringAfterLast('/')
            } else {
                path
            }

        private fun sendSync(stream: SyncStream, id: String, data: ByteArray) {
            if (data.isEmpty()) {
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put(id.toByteArray(Charsets.US_ASCII))
                    .putInt(0)
                    .array()
                stream.send(header)
                return
            }
            var offset = 0
            while (offset < data.size) {
                val size = minOf(4000, data.size - offset)
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put(id.toByteArray(Charsets.US_ASCII))
                    .putInt(size)
                    .array()
                stream.send(header + data.copyOfRange(offset, offset + size))
                offset += size
            }
        }

        private fun stat(path: String): SyncStat {
            val real = mapTargetPath(path)
            val output = openPdb().execCapture("pdb:stat \"" + real.replace("\"", "\\\"") + "\"").second
            val lines = output.lineSequence().toList()
            val type = lines.getOrNull(0)?.trim().orEmpty()
            val size = lines.getOrNull(1)?.toLongOrNull() ?: 0L
            val time = lines.getOrNull(2)?.toLongOrNull() ?: 0L
            val mode = when (type) {
                "dir" -> 0x41ED // 040755
                "file" -> 0x81A4 // 0100644
                else -> 0
            }
            return SyncStat(mode, size, time)
        }

        inner abstract class Stream(val local: Int, val remote: Int) {
            private val queue = LinkedBlockingQueue<ByteArray>()
            private val closed = AtomicBoolean(false)

            fun onData(data: ByteArray) {
                if (!closed.get()) queue.offer(data)
            }

            fun onClose() {
                if (closed.compareAndSet(false, true)) queue.offer(ByteArray(0))
            }

            fun send(data: ByteArray) {
                if (data.isEmpty()) return
                var offset = 0
                while (offset < data.size) {
                    val size = minOf(4000, data.size - offset)
                    writePacket(A_WRTE, local, remote, data.copyOfRange(offset, offset + size))
                    offset += size
                }
            }

            fun sendV2(streamId: Int, bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size) {
                    val size = minOf(4090, bytes.size - offset)
                    val payload = ByteBuffer.allocate(5 + size).order(ByteOrder.LITTLE_ENDIAN)
                        .put(streamId.toByte())
                        .putInt(size)
                        .put(bytes, offset, size)
                        .array()
                    send(payload)
                    offset += size
                }
            }

            fun sendText(text: String, v2: Boolean) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (v2) sendV2(1, bytes) else send(bytes)
            }

            fun sendError(text: String, v2: Boolean) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (v2) sendV2(2, bytes) else send(bytes)
            }

            fun sendExit(code: Int) {
                val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                    .put(3.toByte())
                    .putInt(1)
                    .put((code and 0xFF).toByte())
                    .array()
                send(payload)
            }

            fun take(): ByteArray? {
                val value = queue.take()
                return if (value.isEmpty() && closed.get()) null else value
            }

            abstract fun run()
        }

        inner class ShellStream(local: Int, remote: Int, private val request: ShellRequest) :
            Stream(local, remote) {

            override fun run() {
                synchronized(commandLock) {
                    if (request.command.isBlank()) {
                        sendText("Prism ADB bridge: run `adb shell <command>`.\n", request.v2)
                        if (request.v2) sendExit(0)
                        return
                    }
                    val install = streamingInstall()
                    if (install != null) {
                        runStreamingInstall(install)
                        return
                    }
                    val connection = try {
                        openPdb()
                    } catch (failure: Exception) {
                        sendError("Prism ADB bridge: " + (failure.message ?: "could not reach the device") + "\n", request.v2)
                        if (request.v2) sendExit(1)
                        return
                    }
                    val id = nextCommand.getAndIncrement()
                    var code = 0
                    try {
                        connection.exec(id, request.command, binary = request.exec)
                        while (true) {
                            val event = connection.readEvent() ?: break
                            if (event.id != id) continue
                            when (event.type) {
                                "out" -> {
                                    val bytes = if (event.binary) {
                                        runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                                    } else {
                                        event.data.toByteArray(Charsets.UTF_8)
                                    }
                                    if (bytes != null) {
                                        if (request.v2) sendV2(1, bytes) else send(bytes)
                                    }
                                }
                                "err" -> {
                                    val text = if (event.binary) {
                                        runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                                            ?.toString(Charsets.UTF_8).orEmpty()
                                    } else {
                                        event.data
                                    }
                                    sendError(text, request.v2)
                                }
                                "exit" -> {
                                    code = event.code
                                    break
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        sendError("\nPrism ADB bridge: " + (failure.message ?: "the command failed") + "\n", request.v2)
                        code = 1
                    } finally {
                        if (request.v2) sendExit(code)
                    }
                }
            }

            private fun streamingInstall(): StreamingInstall? {
                if (!request.exec) return null
                if (!request.command.contains("install", ignoreCase = true)) return null
                val size = Regex("-S\\s+(\\d+)").find(request.command)?.groupValues?.get(1)?.toLongOrNull()
                    ?: return null
                if (size <= 0 || size > 4L * 1024 * 1024 * 1024) return StreamingInstall(0, emptyList())
                val options = ShellWords.split(request.command)
                    .filter { it.startsWith("-") && it != "-S" && it.toLongOrNull() == null }
                return StreamingInstall(size, options)
            }

            private fun runStreamingInstall(request: StreamingInstall) {
                if (request.bytes <= 0) {
                    sendText("Failure [invalid package size]\n", false)
                    return
                }
                val temp = File.createTempFile("prism-adb-install", ".apk")
                try {
                    var received = 0L
                    FileOutputStream(temp).use { out ->
                        while (received < request.bytes) {
                            val chunk = take() ?: break
                            out.write(chunk)
                            received += chunk.size
                        }
                    }
                    if (received != request.bytes) {
                        sendText("Failure [short package: $received of " + request.bytes + " bytes]\n", false)
                        return
                    }
                    val connection = try {
                        openPdb()
                    } catch (failure: Exception) {
                        sendText("Failure [" + (failure.message ?: "could not reach the device") + "]\n", false)
                        return
                    }
                    val incoming = incoming()
                    if (incoming.isBlank()) {
                        sendText("Failure [the target did not name its incoming folder]\n", false)
                        return
                    }
                    val name = "adb-install-" + System.currentTimeMillis() + ".apk"
                    val remote = incoming.trimEnd('/') + "/" + name
                    connection.upload(temp, remote)
                    val command = "pdb:install \"" + remote.replace("\"", "\\\"") + "\"" +
                        (if (request.options.isEmpty()) "" else " " + request.options.joinToString(" "))
                    val result = connection.execCapture(command, timeoutMs = 60_000)
                    if (result.first == 0) {
                        sendText(result.second.ifBlank { "Success\n" }, false)
                    } else {
                        sendText("Failure [" + result.second.trim().ifBlank { "install failed" } + "]\n", false)
                    }
                } catch (failure: Exception) {
                    sendText("Failure [" + (failure.message ?: "install failed") + "]\n", false)
                } finally {
                    runCatching { temp.delete() }
                }
            }
        }

        inner class SyncStream(local: Int, remote: Int) : Stream(local, remote) {
            private val pending = ByteArrayOutputStream()
            private var sendPath: String? = null
            private var sendFile: File? = null
            private var sendOutput: FileOutputStream? = null

            private fun finishSend() {
                runCatching { sendOutput?.close() }
                sendOutput = null
                val localFile = sendFile
                val remotePath = sendPath
                sendFile = null
                sendPath = null
                if (localFile == null || remotePath == null) {
                    sendSync(this, "FAIL", "no active send".toByteArray())
                    return
                }
                val target = mapTargetPath(remotePath)
                log("ADB sync upload target " + target)
                val ok = runCatching {
                    openPdb().upload(localFile, target)
                    true
                }.onFailure {
                    log("ADB sync upload failed: " + (it.message ?: it.javaClass.simpleName))
                }.getOrDefault(false)
                sendSync(
                    this,
                    if (ok) "OKAY" else "FAIL",
                    if (ok) ByteArray(0) else "upload failed".toByteArray(),
                )
                log("ADB sync upload " + (if (ok) "finished" else "failed"))
                runCatching { localFile.delete() }
            }

            override fun run() {
                synchronized(commandLock) {
                    while (true) {
                        val chunk = take() ?: break
                        pending.write(chunk)
                        while (true) {
                            val bytes = pending.toByteArray()
                            if (bytes.size < 8) break
                            val id = String(bytes, 0, 4, Charsets.US_ASCII)
                            // In classic sync, SEND's DONE carries the mtime in the length field
                            // and no payload. Treating that number as a frame size is how a push
                            // waits forever for a file the sender already finished.
                            if (id == "DONE" && sendOutput != null) {
                                finishSend()
                                pending.reset()
                                if (bytes.size > 8) pending.write(bytes, 8, bytes.size - 8)
                                continue
                            }
                            val length = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            if (length < 0 || length > 8 * 1024 * 1024) {
                                sendSync(this, "FAIL", "invalid sync length".toByteArray())
                                return
                            }
                            if (bytes.size < 8 + length) break
                            val data = bytes.copyOfRange(8, 8 + length)
                            pending.reset()
                            if (bytes.size > 8 + length) pending.write(bytes, 8 + length, bytes.size - 8 - length)
                            log("ADB sync " + id + " (" + length + " bytes)")
                            when (id) {
                                "STAT", "LSTAT", "STA2", "LST2" -> {
                                    val stat = stat(String(data, Charsets.UTF_8).trimEnd('\u0000'))
                                    val response = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                                        .put("STAT".toByteArray(Charsets.US_ASCII))
                                        .putInt(stat.mode)
                                        .putInt(stat.size.toInt())
                                        .putInt((stat.time / 1000).toInt())
                                        .array()
                                    send(response)
                                }
                                "SEND" -> {
                                    val raw = String(data, Charsets.UTF_8).trimEnd('\u0000')
                                    // Classic sync appends ",<octal mode>" to the path. Splitting it
                                    // from the filename is what makes the following STAT/RCV agree
                                    // with what was written.
                                    val comma = raw.lastIndexOf(',')
                                    val path = if (comma > 0) {
                                        val mode = raw.substring(comma + 1)
                                        if (mode.isNotEmpty() && mode.all { it in '0'..'7' }) raw.substring(0, comma)
                                        else raw
                                    } else raw
                                    sendPath = path
                                    sendFile = File.createTempFile("prism-adb-push", ".part")
                                    sendOutput = FileOutputStream(sendFile!!)
                                }
                                "DATA" -> sendOutput?.write(data)
                                "DONE" -> finishSend()
                                "RECV" -> {
                                    val remotePath = String(data, Charsets.UTF_8).trimEnd('\u0000')
                                    val temp = File.createTempFile("prism-adb-pull", ".part")
                                    val ok = runCatching {
                                        openPdb().download(mapTargetPath(remotePath), temp)
                                        true
                                    }.getOrDefault(false)
                                    if (!ok) {
                                        sendSync(this, "FAIL", "download failed".toByteArray())
                                    } else {
                                        temp.inputStream().use { stream ->
                                            val buffer = ByteArray(4000)
                                            while (true) {
                                                val read = stream.read(buffer)
                                                if (read <= 0) break
                                                sendSync(this, "DATA", buffer.copyOf(read))
                                            }
                                        }
                                        sendSync(this, "DONE", ByteArray(0))
                                        runCatching { temp.delete() }
                                    }
                                }
                                "QUIT" -> return
                                else -> sendSync(this, "FAIL", "unsupported sync request".toByteArray())
                            }
                        }
                    }
                    sendOutput?.close()
                    sendFile?.delete()
                }
            }
        }

        private fun writePacket(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val buffer = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(command)
            buffer.putInt(arg0)
            buffer.putInt(arg1)
            buffer.putInt(data.size)
            buffer.putInt(checksum(data))
            buffer.putInt(command xor -1)
            buffer.put(data)
            synchronized(writeLock) {
                output.write(buffer.array())
                output.flush()
            }
        }

        private fun readPacket(): Packet? {
            val header = readFully(24) ?: return null
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val checksum = buffer.int
            val magic = buffer.int
            if (magic != (command xor -1)) return null
            if (length < 0 || length > maxData) return null
            val data = if (length == 0) ByteArray(0) else readFully(length) ?: return null
            if (checksum != checksum(data)) return null
            return Packet(command, arg0, arg1, data)
        }

        private fun readFully(size: Int): ByteArray? {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(bytes, offset, size - offset)
                if (read < 0) return null
                offset += read
            }
            return bytes
        }

        private fun checksum(data: ByteArray): Int {
            var sum = 0
            for (byte in data) sum += byte.toInt() and 0xFF
            return sum
        }

        private data class Packet(
            val command: Int,
            val arg0: Int,
            val arg1: Int,
            val data: ByteArray,
        )

        private data class ShellRequest(val command: String, val v2: Boolean, val exec: Boolean = false)

        private data class StreamingInstall(val bytes: Long, val options: List<String>)

        private data class SyncStat(val mode: Int, val size: Long, val time: Long)
    }

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val VERSION = 0x01000000
}
