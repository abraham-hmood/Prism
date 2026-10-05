package com.prism.desktop.vm

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.prism.core.PrismPlatform
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The guest's screen and keyboard, over RFB. PHASE 69.
 *
 * ## Why the protocol is implemented rather than a library used
 *
 * The Android build already speaks RFB — `VncSurfaceRenderer` does, into a `Surface`. What is
 * Android-specific there is the DRAWING, not the protocol: Bitmap, Canvas and Surface have no desktop
 * equivalents, but the framing, the handshake and the rectangle decoding are the same bytes. So this is
 * the same protocol against a different sink, which is smaller and more predictable than adding a VNC
 * library whose threading model would have to be reconciled with Compose's.
 *
 * ## Raw and CopyRect only, and why that is enough
 *
 * QEMU offers several encodings; a client advertises what it wants and QEMU picks from that list. Raw
 * is every pixel, which is simple and correct and costs bandwidth that does not matter over loopback —
 * the guest is on this machine. CopyRect is free to support and saves a great deal on window drags.
 * Tight and ZRLE would cost less bandwidth and need a decompressor each; over 127.0.0.1 that is paying
 * complexity for nothing.
 *
 * ## Why the frame is published rather than drawn
 *
 * Compose owns the drawing. This holds a `BufferedImage` it mutates on its own thread and publishes an
 * immutable snapshot after each update — so the UI never reads a half-decoded frame, which is what a
 * shared mutable bitmap would give it during a large rectangle.
 */
class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int = QemuVm.VNC_PORT,
) {

    private companion object {
        const val TAG = "PrismVnc"

        // Message types, client to server.
        const val SET_PIXEL_FORMAT = 0
        const val SET_ENCODINGS = 2
        const val FRAMEBUFFER_UPDATE_REQUEST = 3
        const val KEY_EVENT = 4
        const val POINTER_EVENT = 5

        // Encodings, in the order they are preferred.
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_RAW = 0
    }

    @Volatile var width: Int = 0; private set
    @Volatile var height: Int = 0; private set
    @Volatile var connected: Boolean = false; private set
    @Volatile var lastError: String = ""; private set

    /** The most recent complete frame. Replaced wholesale, never mutated in place. */
    @Volatile
    var frame: ImageBitmap? = null
        private set

    /** Called after each frame, so a page can recompose. */
    @Volatile
    var onFrame: (() -> Unit)? = null

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var canvas: BufferedImage? = null
    private var pixels: IntArray = IntArray(0)

    @Volatile private var running = false

    /** Connects and starts reading frames. Returns null on success or the reason it failed. */
    fun connect(): String? {
        if (running) return null
        return runCatching {
            val client = Socket()
            client.connect(InetSocketAddress(host, port), 4_000)
            client.tcpNoDelay = true
            socket = client
            input = DataInputStream(client.getInputStream().buffered(1 shl 16))
            output = DataOutputStream(client.getOutputStream())

            handshake()
            running = true
            connected = true

            thread(name = "vnc-reader", isDaemon = true) { readLoop() }
            requestUpdate(incremental = false)
            null
        }.getOrElse {
            lastError = it.message.orEmpty()
            disconnect()
            "Could not attach to the guest's display: " + it.message
        }
    }

    fun disconnect() {
        running = false
        connected = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ── Handshake ──────────────────────────────────────────────────────────

    /**
     * RFB 3.8 with no authentication.
     *
     * QEMU's `-vnc :N` without a password offers security type 1, None. Prism does not expose the
     * display outside loopback, so there is nothing for a password to protect that the socket's own
     * binding does not already: anything that could reach the port could also read this process's
     * memory.
     */
    private fun handshake() {
        val stream = input!!
        val out = output!!

        val version = ByteArray(12)
        stream.readFully(version)
        out.write("RFB 003.008\n".toByteArray())
        out.flush()

        val securityCount = stream.readUnsignedByte()
        if (securityCount == 0) {
            val reason = ByteArray(stream.readInt())
            stream.readFully(reason)
            throw IllegalStateException("The guest refused the connection: " + String(reason))
        }
        val types = ByteArray(securityCount)
        stream.readFully(types)
        if (!types.contains(1)) {
            throw IllegalStateException("The guest wants authentication Prism does not implement.")
        }
        out.writeByte(1)
        out.flush()

        val result = stream.readInt()
        if (result != 0) throw IllegalStateException("The guest rejected the handshake.")

        // Shared, so attaching does not disconnect anything else already looking at it.
        out.writeByte(1)
        out.flush()

        width = stream.readUnsignedShort()
        height = stream.readUnsignedShort()

        // The server's pixel format, which is then replaced by one this client can decode directly.
        val serverFormat = ByteArray(16)
        stream.readFully(serverFormat)
        val nameLength = stream.readInt()
        val name = ByteArray(nameLength)
        stream.readFully(name)

        canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        pixels = IntArray(width * height)

        setPixelFormat()
        setEncodings()

        PrismPlatform.log.info(TAG, "Attached to " + String(name) + " at " + width + "x" + height)
    }

    /**
     * Asks for 32-bit little-endian BGRX.
     *
     * CHOSEN TO MATCH `TYPE_INT_RGB` so a pixel is a copy rather than a shuffle: the server could offer
     * anything, and converting several million pixels per frame between layouts is exactly the work
     * that makes a software VNC client feel slow.
     */
    private fun setPixelFormat() {
        val out = output!!
        out.writeByte(SET_PIXEL_FORMAT)
        out.write(ByteArray(3))          // padding
        out.writeByte(32)                // bits per pixel
        out.writeByte(24)                // depth
        out.writeByte(0)                 // little endian
        out.writeByte(1)                 // true colour
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0)
        out.write(ByteArray(3))          // padding
        out.flush()
    }

    private fun setEncodings() {
        val out = output!!
        out.writeByte(SET_ENCODINGS)
        out.writeByte(0)
        out.writeShort(2)
        out.writeInt(ENCODING_COPY_RECT)
        out.writeInt(ENCODING_RAW)
        out.flush()
    }

    // ── Frames ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        val stream = input ?: return
        while (running) {
            val handled = runCatching {
                when (stream.readUnsignedByte()) {
                    0 -> readFramebufferUpdate(stream)
                    1 -> readColourMap(stream)
                    2 -> Unit                       // bell
                    3 -> readCutText(stream)
                    else -> throw IllegalStateException("Unknown message from the guest")
                }
                true
            }.getOrElse {
                if (running) {
                    lastError = it.message.orEmpty()
                    PrismPlatform.log.info(TAG, "Display connection ended: " + it.message)
                }
                false
            }
            if (!handled) break
            // Incremental from here: a full update per frame would send the whole screen every time.
            runCatching { requestUpdate(incremental = true) }
        }
        connected = false
    }

    private fun readFramebufferUpdate(stream: DataInputStream) {
        stream.readUnsignedByte()                   // padding
        val rectangles = stream.readUnsignedShort()
        val image = canvas ?: return

        repeat(rectangles) {
            val x = stream.readUnsignedShort()
            val y = stream.readUnsignedShort()
            val w = stream.readUnsignedShort()
            val h = stream.readUnsignedShort()
            when (stream.readInt()) {
                ENCODING_RAW -> readRaw(stream, x, y, w, h)
                ENCODING_COPY_RECT -> {
                    val sourceX = stream.readUnsignedShort()
                    val sourceY = stream.readUnsignedShort()
                    copyRect(sourceX, sourceY, x, y, w, h)
                }
                else -> throw IllegalStateException("The guest used an encoding Prism did not ask for")
            }
        }

        image.setRGB(0, 0, width, height, pixels, 0, width)
        frame = image.toComposeImageBitmap()
        runCatching { onFrame?.invoke() }
    }

    private fun readRaw(stream: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val row = ByteArray(w * 4)
        for (line in 0 until h) {
            stream.readFully(row)
            val target = (y + line) * width + x
            if (target < 0 || target + w > pixels.size) continue
            for (column in 0 until w) {
                val base = column * 4
                // BGRX, as requested in setPixelFormat.
                pixels[target + column] =
                    ((row[base + 2].toInt() and 0xFF) shl 16) or
                        ((row[base + 1].toInt() and 0xFF) shl 8) or
                        (row[base].toInt() and 0xFF)
            }
        }
    }

    /**
     * Moves a block already on screen.
     *
     * Copied through a temporary because the source and destination can overlap -- a window dragged a
     * few pixels is the common case, and copying in place would smear it.
     */
    private fun copyRect(sourceX: Int, sourceY: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val buffer = IntArray(w * h)
        for (line in 0 until h) {
            val from = (sourceY + line) * width + sourceX
            if (from < 0 || from + w > pixels.size) continue
            System.arraycopy(pixels, from, buffer, line * w, w)
        }
        for (line in 0 until h) {
            val to = (y + line) * width + x
            if (to < 0 || to + w > pixels.size) continue
            System.arraycopy(buffer, line * w, pixels, to, w)
        }
    }

    private fun readColourMap(stream: DataInputStream) {
        stream.readUnsignedByte()
        stream.readUnsignedShort()
        val count = stream.readUnsignedShort()
        stream.skipBytes(count * 6)
    }

    private fun readCutText(stream: DataInputStream) {
        stream.skipBytes(3)
        val length = stream.readInt()
        stream.skipBytes(length)
    }

    private fun requestUpdate(incremental: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(FRAMEBUFFER_UPDATE_REQUEST)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(width)
            out.writeShort(height)
            out.flush()
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────

    /** A key, as an X11 keysym. Press and release are separate messages, as the protocol requires. */
    fun sendKey(keysym: Int, pressed: Boolean) {
        val out = output ?: return
        runCatching {
            synchronized(out) {
                out.writeByte(KEY_EVENT)
                out.writeByte(if (pressed) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            }
        }
    }

    fun typeKey(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    /** A pointer position and button mask. Bit 0 is the left button. */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val out = output ?: return
        runCatching {
            synchronized(out) {
                out.writeByte(POINTER_EVENT)
                out.writeByte(buttons)
                out.writeShort(x.coerceIn(0, width))
                out.writeShort(y.coerceIn(0, height))
                out.flush()
            }
        }
    }
}
