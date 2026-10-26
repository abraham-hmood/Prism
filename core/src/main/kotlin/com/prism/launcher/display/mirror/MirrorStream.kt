package com.prism.launcher.display.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The live screen stream a Prism display receives.
 *
 * ## Why a private stream rather than Miracast
 *
 * Miracast is negotiated by Wi-Fi Direct and its capabilities go through `setWfdInfo`, which
 * Android protects with a signature permission. A third-party app therefore cannot be a Miracast
 * source, and pretending otherwise would leave the feature broken on every phone. Two Prism
 * devices have a better route: the control link that already proved who they are, followed by a
 * plain TCP stream of the sender's screen.
 *
 * ## Frames, not a container
 *
 * Each frame is a width, a height, a length and a JPEG. There is no sample table, no muxer and no
 * seeking, because a mirror has none of those things. A receiver that falls behind drops frames
 * instead of growing a buffer, so the delay stays flat and the picture follows the finger.
 *
 * The token is not the security boundary -- [WirelessDisplay]'s pairing handshake is. It exists
 * so a stray process on the LAN cannot attach to the port simply because it found it.
 */
data class MirrorFrame(
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
)

/**
 * The sending end: one port, one viewer, the newest frame only.
 *
 * A second viewer replaces the first rather than being refused. A display that reconnects after a
 * Wi-Fi hiccup should get the live edge immediately, and a mirror has no reason to be watched by
 * two screens at once.
 */
class MirrorStreamServer(
    private val token: String,
    private val onViewersChanged: (Int) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : Closeable {

    private var server: ServerSocket? = null
    private var client: Client? = null
    private val clientLock = Any()

    @Volatile
    private var stopped = false

    /** The bound port, valid after [start]. */
    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        server = bound
        port = bound.localPort
        stopped = false
        thread(name = "prism-mirror-stream", isDaemon = true) { acceptLoop(bound) }
        return port
    }

    /** Sends a JPEG frame to the current viewer, or does nothing when nobody is watching. */
    fun sendFrame(width: Int, height: Int, jpeg: ByteArray) {
        if (width <= 0 || height <= 0 || jpeg.isEmpty()) return
        val current = synchronized(clientLock) { client } ?: return
        current.offer(frameBytes(width, height, jpeg))
    }

    fun viewers(): Int = synchronized(clientLock) { if (client == null) 0 else 1 }

    private fun acceptLoop(listener: ServerSocket) {
        while (!stopped && !listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (error: Exception) {
                break
            }
            thread(name = "prism-mirror-admit", isDaemon = true) { admit(socket) }
        }
    }

    private fun admit(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val offered = readAsciiLine(input, 256)
            if (offered != token) {
                runCatching {
                    val out = socket.getOutputStream()
                    out.write("DENIED\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                socket.close()
                return
            }

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
            out.write("PRISM-MIRROR/1\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            socket.soTimeout = 0

            val replacement = Client(socket, out)
            val previous = synchronized(clientLock) {
                val old = client
                client = replacement
                old
            }
            previous?.close(notify = false)
            onViewersChanged(1)
            replacement.start()
        } catch (error: Exception) {
            runCatching { socket.close() }
            if (!stopped) onError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun frameBytes(width: Int, height: Int, jpeg: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream(jpeg.size + 12)
        val data = DataOutputStream(bytes)
        data.writeInt(width)
        data.writeInt(height)
        data.writeInt(jpeg.size)
        data.write(jpeg)
        data.flush()
        return bytes.toByteArray()
    }

    override fun close() {
        stopped = true
        runCatching { server?.close() }
        server = null
        val current = synchronized(clientLock) {
            val old = client
            client = null
            old
        }
        current?.close(notify = false)
    }

    private inner class Client(
        private val socket: Socket,
        private val out: DataOutputStream,
    ) {
        private val queue = ArrayDeque<ByteArray>()
        private val queueLock = Object()

        @Volatile
        private var closed = false

        fun start() {
            thread(name = "prism-mirror-writer", isDaemon = true) { writeLoop() }
        }

        fun offer(frame: ByteArray) {
            synchronized(queueLock) {
                if (closed) return
                // Two frames is enough to absorb a scheduling hiccup. More than that is latency
                // the viewer can see, and a mirror that lags is worse than one that skips.
                while (queue.size >= 2) queue.removeFirst()
                queue.addLast(frame)
                queueLock.notifyAll()
            }
        }

        private fun writeLoop() {
            try {
                while (!closed) {
                    val frame = synchronized(queueLock) {
                        while (queue.isEmpty() && !closed) queueLock.wait(1_000L)
                        if (closed) return
                        queue.removeFirstOrNull()
                    } ?: continue
                    out.write(frame)
                    out.flush()
                }
            } catch (error: Exception) {
                close(notify = true)
            }
        }

        fun close(notify: Boolean) {
            if (closed) return
            closed = true
            synchronized(queueLock) {
                queue.clear()
                queueLock.notifyAll()
            }
            runCatching { socket.close() }
            if (notify) {
                val wasCurrent = synchronized(clientLock) {
                    if (client === this) {
                        client = null
                        true
                    } else {
                        false
                    }
                }
                if (wasCurrent) onViewersChanged(0)
            }
        }
    }
}

/**
 * The receiving end of [MirrorStreamServer].
 *
 * Blocking by design: a viewer owns one reader thread, and the caller posts decoded bitmaps to
 * whatever UI toolkit it has. Returning null means the sender closed the session.
 */
class MirrorStreamClient(
    private val host: String,
    private val port: Int,
    private val token: String,
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null

    fun connect(timeoutMs: Int = 5_000) {
        val connected = Socket()
        connected.tcpNoDelay = true
        connected.connect(InetSocketAddress(host, port), timeoutMs)
        connected.soTimeout = 10_000
        socket = connected

        val out: OutputStream = connected.getOutputStream()
        out.write((token + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()

        val raw = connected.getInputStream()
        val greeting = readAsciiLine(raw, 64)
        if (greeting != "PRISM-MIRROR/1") {
            connected.close()
            throw IllegalStateException(
                if (greeting == "DENIED") "The sender refused this display." else "Not a Prism mirror."
            )
        }
        connected.soTimeout = 0
        input = DataInputStream(BufferedInputStream(raw, 512 * 1024))
    }

    /** Reads the next frame, or null when the sender has stopped. */
    fun readFrame(): MirrorFrame? {
        val stream = input ?: return null
        val header = ByteArray(12)
        return try {
            stream.readFully(header)
            val width = readInt(header, 0)
            val height = readInt(header, 4)
            val length = readInt(header, 8)
            if (width <= 0 || height <= 0 || length <= 0 || length > MAX_FRAME) return null
            val jpeg = ByteArray(length)
            stream.readFully(jpeg)
            MirrorFrame(width, height, jpeg)
        } catch (error: EOFException) {
            null
        } catch (error: Exception) {
            null
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    companion object {
        private const val MAX_FRAME = 8 * 1024 * 1024
    }
}

/** A short unguessable token for one mirror session. */
fun newMirrorToken(): String {
    val alphabet = "23456789abcdefghjkmnpqrstuvwxyz"
    val random = SecureRandom()
    return buildString(10) {
        repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

private fun readAsciiLine(input: InputStream, limit: Int): String? {
    val bytes = ByteArrayOutputStream(limit)
    try {
        while (bytes.size() < limit) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(value)
        }
    } catch (error: Exception) {
        return null
    }
    return null
}
