package com.prism.launcher.virtualization

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Surface
import java.io.DataInputStream
import java.net.Socket

/**
 * An RFB (VNC) client that renders QEMU's display onto an Android [Surface] and sends input back.
 *
 * ## Why the protocol handling is not "minimal" any more
 *
 * The first version read only Raw rectangles and ignored everything else. That works right up until
 * the server sends anything unexpected -- a resize, a bell, a clipboard update -- at which point the
 * unread payload is consumed as if it were the next message header and the stream is desynchronised
 * for good. The symptom is not an error: it is a picture that silently stops updating while the
 * connection stays open. Every message type the server can send is therefore either handled or
 * explicitly consumed.
 */
internal class VncSurfaceRenderer(
    private val socket: Socket,
    surface: Surface,
) : Thread("VncSurfaceRenderer") {

    /**
     * Where frames are drawn, and it CHANGES.
     *
     * A SurfaceView destroys and recreates its Surface every time the page is recycled, which the
     * desktop pager does constantly. Holding the one passed at construction meant that after the
     * first swipe away the renderer was drawing into a destroyed Surface -- blitToSurface checks
     * isValid and returns, so it failed silently and the VM appeared frozen while the connection
     * underneath was perfectly healthy.
     */
    @Volatile
    private var surface: Surface = surface

    /** Set when the target changed, so the next update asks for a whole frame rather than a delta. */
    @Volatile
    private var needsFullRefresh = false

    @Volatile private var running = true

    /** The stream's output side, published only once the handshake is complete. */
    @Volatile private var out: java.io.OutputStream? = null

    /**
     * Outgoing writes, serialised on one thread.
     *
     * TWO REASONS, both load-bearing. Keystrokes arrive from the IME on the MAIN thread, and
     * Android forbids socket I/O there -- the write throws NetworkOnMainThreadException, which
     * carries no message at all and so reads as a dead connection rather than a threading rule.
     * And RFB is a stream protocol: two threads writing concurrently would interleave messages
     * even with locking, so a single thread is what keeps a key press ahead of its release.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "VncWriter").apply { isDaemon = true }
    }

    /**
     * Points the renderer at a new Surface.
     *
     * A full update is requested rather than an incremental one: the new Surface is blank, and
     * incremental updates carry only what changed on the guest, so a still screen would stay empty.
     */
    fun rebind(newSurface: Surface) {
        surface = newSurface
        needsFullRefresh = true
        // Sent from the WRITER thread, because the render thread is blocked reading and will not
        // notice the flag until the server happens to send something. On a guest sitting at a
        // prompt that can be never, which is exactly how a reattached page stayed black.
        val stream = out ?: return
        writer.execute {
            runCatching { requestUpdate(stream, frameWidth, frameHeight, incremental = false) }
        }
    }

    /** Whether this renderer still has a usable connection. */
    val isUsable: Boolean get() = out != null && running

    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    override fun run() {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // RFB handshake: server sends "RFB 003.008\n"
            val greeting = ByteArray(12)
            input.readFully(greeting)
            output.write("RFB 003.008\n".toByteArray())

            // Security: read offered types, choose None (1)
            val numTypes = input.read()
            val types = ByteArray(numTypes)
            input.readFully(types)
            output.write(byteArrayOf(1))

            // SecurityResult
            input.readInt()

            // ClientInit: shared flag
            output.write(byteArrayOf(1))

            // ServerInit: framebuffer dimensions, then the pixel format we are about to override.
            var width = input.readShort().toInt() and 0xFFFF
            var height = input.readShort().toInt() and 0xFFFF
            input.skipBytes(16)
            val nameLen = input.readInt()
            input.skipBytes(nameLen)

            frameWidth = width
            frameHeight = height

            // Only NOW is the stream safe to share. Publishing it earlier would let a keystroke
            // interleave with the handshake and desynchronise the protocol.
            out = output
            android.util.Log.i("PrismVM", "VNC ready: ${width}x${height}, keyboard attached")

            // Anything a previous session left held is still held as far as the guest knows.
            releaseModifiers()

            // PIXEL FORMAT IS DICTATED, NOT INHERITED. The server's own format used to be skipped
            // over and then decoded as though it were RGBX, so the bytes were read in an order
            // nobody had agreed on. Asking for one this client can decode removes the guesswork.
            sendSetPixelFormat(output)

            // Without SetEncodings a server may send rectangles this client cannot parse, and an
            // unparsed rectangle desynchronises the stream permanently. Advertising exactly what is
            // implemented keeps the server inside it.
            sendSetEncodings(output)

            sendFramebufferUpdateRequest(output, 0, 0, width, height, incremental = false)

            val paint = Paint()
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            while (running) {
                val msgType = input.read()

                // -1 is end of stream. The old loop treated it as "not an update" and span,
                // burning a core against a socket that was already gone.
                if (msgType < 0) {
                    android.util.Log.i("PrismVM", "VNC stream closed by the server")
                    break
                }

                when (msgType) {
                    0 -> {
                        input.read()
                        val numRects = input.readShort().toInt() and 0xFFFF
                        var resized = false

                        for (r in 0 until numRects) {
                            val x = input.readShort().toInt() and 0xFFFF
                            val y = input.readShort().toInt() and 0xFFFF
                            val w = input.readShort().toInt() and 0xFFFF
                            val h = input.readShort().toInt() and 0xFFFF
                            val encoding = input.readInt()

                            if (encoding == 0) {
                                // Raw, in the format requested above: B, G, R, unused.
                                val pixels = IntArray(w * h)
                                val row = ByteArray(w * 4)
                                for (line in 0 until h) {
                                    input.readFully(row)
                                    var p = line * w
                                    var i = 0
                                    while (i < row.size) {
                                        val b = row[i].toInt() and 0xFF
                                        val g = row[i + 1].toInt() and 0xFF
                                        val rr = row[i + 2].toInt() and 0xFF
                                        pixels[p++] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                                        i += 4
                                    }
                                }
                                if (x + w <= bitmap.width && y + h <= bitmap.height) {
                                    bitmap.setPixels(pixels, 0, w, x, y, w, h)
                                }
                            } else if (encoding == ENCODING_DESKTOP_SIZE) {
                                // The guest changed resolution, which Alpine does the moment its
                                // kernel takes over from the firmware. The framebuffer must be
                                // rebuilt or every later update is written against the wrong size.
                                android.util.Log.i("PrismVM", "guest resized to ${w}x${h}")
                                width = w
                                height = h
                                frameWidth = w
                                frameHeight = h
                                bitmap.recycle()
                                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                resized = true
                            } else {
                                // An unknown encoding cannot be skipped -- its length is not in the
                                // header -- so continuing would read pixels as message types.
                                android.util.Log.w("PrismVM", "unsupported encoding $encoding; closing")
                                running = false
                                break
                            }
                        }

                        if (running) {
                            blitToSurface(bitmap, paint, width, height)
                            val full = resized || needsFullRefresh
                            needsFullRefresh = false
                            sendFramebufferUpdateRequest(output, 0, 0, width, height, incremental = !full)
                        }
                    }

                    1 -> {
                        // SetColourMapEntries. Unused in true colour, but it still has to be
                        // consumed or everything after it is misread.
                        input.skipBytes(1)
                        input.readShort()
                        val count = input.readShort().toInt() and 0xFFFF
                        input.skipBytes(count * 6)
                    }

                    2 -> Unit // Bell: no payload.

                    3 -> {
                        // ServerCutText: the guest's clipboard.
                        input.skipBytes(3)
                        val length = input.readInt()
                        if (length > 0) input.skipBytes(length)
                    }

                    else -> {
                        android.util.Log.w("PrismVM", "unknown server message $msgType; closing")
                        running = false
                    }
                }
            }
            bitmap.recycle()
        } catch (e: Exception) {
            // Logged, not swallowed. A silent catch here made a protocol desync look exactly like
            // a VM sitting idle, which is how this went unnoticed.
            android.util.Log.w(
                "PrismVM",
                "VNC renderer stopped: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
            )
        } finally {
            out = null
            runCatching { socket.close() }
        }
    }

    fun stopRenderer() {
        running = false
        out = null
        runCatching { writer.shutdownNow() }
        runCatching { socket.close() }
    }

    /**
     * Sends one RFB KeyEvent (message type 4).
     *
     * PRESS AND RELEASE ARE SEPARATE MESSAGES and both are required: a guest that receives only the
     * press sees the key held down, auto-repeat takes over, and one tap fills the line.
     *
     * @param keysym an X11 keysym, not an Android keycode. See [VncKeysyms].
     */
    fun sendKeyEvent(keysym: Int, pressed: Boolean): Boolean {
        val stream = out ?: return false
        writer.execute {
            try {
                synchronized(stream) {
                    stream.write(
                        byteArrayOf(
                            4,
                            if (pressed) 1 else 0,
                            0, 0,
                            (keysym ushr 24).toByte(), (keysym ushr 16).toByte(),
                            (keysym ushr 8).toByte(), keysym.toByte(),
                        )
                    )
                    stream.flush()
                }
                android.util.Log.i(
                    "PrismVM",
                    "sent keysym 0x${keysym.toString(16)} ${if (pressed) "down" else "up"}",
                )
            } catch (e: Throwable) {
                android.util.Log.w(
                    "PrismVM",
                    "key write failed: ${e.javaClass.name}: ${e.message ?: "(no message)"}",
                    e,
                )
                out = null
            }
        }
        return true
    }

    /**
     * A complete keystroke: down, a real hold, then up.
     *
     * THE HOLD IS THE POINT. A press and release sent back to back last zero milliseconds, and a
     * guest running under TCG emulation polls its virtio-input queue far too slowly to be sure of
     * seeing both -- so keystrokes get dropped, and if the release is the one that goes missing the
     * key sticks down and swallows everything typed afterwards. That is the "first letter appears,
     * then the keyboard goes dead" failure exactly.
     *
     * A real key is held for something like 50-100 ms. Matching that costs nothing at human typing
     * speed and makes delivery reliable.
     *
     * Queued on the writer thread rather than slept on the caller's, because the caller is the IME
     * running on the main thread and blocking it would freeze whatever app is being typed into.
     */
    fun typeKey(keysym: Int, shift: Boolean = false): Boolean {
        val stream = out ?: return false
        writer.execute {
            runCatching {
                if (shift) writeKey(stream, VncKeysyms.SHIFT_L, true)
                writeKey(stream, keysym, true)
                Thread.sleep(KEY_HOLD_MS)
                writeKey(stream, keysym, false)
                if (shift) writeKey(stream, VncKeysyms.SHIFT_L, false)
                // A gap between keystrokes, for the same reason as the hold: an emulated guest
                // needs time to drain its queue before the next event arrives.
                Thread.sleep(KEY_GAP_MS)
            }.onFailure {
                android.util.Log.w("PrismVM", "key sequence failed: ${it.javaClass.simpleName}: ${it.message}")
                out = null
            }
        }
        return true
    }

    /**
     * Releases every modifier, unconditionally.
     *
     * Sent when a connection is established, because there is no way to ask a guest what it thinks
     * is currently held. If a previous session left Shift or Ctrl down -- which is what happens when
     * a release is dropped -- every subsequent keystroke arrives as a control sequence and appears
     * to do nothing. Explicit releases cost four messages and remove the whole failure mode.
     */
    fun releaseModifiers() {
        val stream = out ?: return
        writer.execute {
            runCatching {
                for (m in intArrayOf(
                    VncKeysyms.SHIFT_L, VncKeysyms.CONTROL_L, VncKeysyms.ALT_L, 0xFFE2, 0xFFE4,
                )) {
                    writeKey(stream, m, false)
                }
                android.util.Log.i("PrismVM", "released any stuck modifiers")
            }
        }
    }

    /** Writes one KeyEvent. Callers are already on the writer thread. */
    private fun writeKey(stream: java.io.OutputStream, keysym: Int, pressed: Boolean) {
        synchronized(stream) {
            stream.write(
                byteArrayOf(
                    4,
                    if (pressed) 1 else 0,
                    0, 0,
                    (keysym ushr 24).toByte(), (keysym ushr 16).toByte(),
                    (keysym ushr 8).toByte(), keysym.toByte(),
                )
            )
            stream.flush()
        }
        android.util.Log.i(
            "PrismVM",
            "sent keysym 0x${keysym.toString(16)} ${if (pressed) "down" else "up"}",
        )
    }

    private fun blitToSurface(bitmap: Bitmap, paint: Paint, w: Int, h: Int) {
        if (!surface.isValid) return
        val canvas: Canvas = surface.lockCanvas(null) ?: return
        try {
            // Scaled to fill, since the guest's resolution rarely matches the view's.
            val dst = android.graphics.Rect(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, null, dst, paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Asks for 32-bit true colour in a byte order this client decodes.
     *
     * Little-endian with red at bit 16 puts the bytes on the wire as B, G, R, unused, which is what
     * the Raw decoder above reads. Accepting the server's own format instead means decoding
     * whatever QEMU happened to prefer, which is how colours end up swapped.
     */
    private fun sendSetPixelFormat(out: java.io.OutputStream) = synchronized(out) {
        out.write(
            byteArrayOf(
                0,                          // SetPixelFormat
                0, 0, 0,                    // padding
                32,                         // bits per pixel
                24,                         // depth
                0,                          // big-endian flag: little
                1,                          // true colour
                0, 255.toByte(),            // red max
                0, 255.toByte(),            // green max
                0, 255.toByte(),            // blue max
                16,                         // red shift
                8,                          // green shift
                0,                          // blue shift
                0, 0, 0,                    // padding
            )
        )
        out.flush()
    }

    /** Advertises exactly the encodings this client implements, and nothing else. */
    private fun sendSetEncodings(out: java.io.OutputStream) = synchronized(out) {
        val encodings = intArrayOf(0, ENCODING_DESKTOP_SIZE)
        val message = java.io.ByteArrayOutputStream()
        message.write(2)
        message.write(0)
        message.write((encodings.size shr 8) and 0xFF)
        message.write(encodings.size and 0xFF)
        for (e in encodings) {
            message.write((e shr 24) and 0xFF)
            message.write((e shr 16) and 0xFF)
            message.write((e shr 8) and 0xFF)
            message.write(e and 0xFF)
        }
        out.write(message.toByteArray())
        out.flush()
    }

    private fun requestUpdate(
        out: java.io.OutputStream,
        w: Int,
        h: Int,
        incremental: Boolean,
    ) = sendFramebufferUpdateRequest(out, 0, 0, w, h, incremental)

    private fun sendFramebufferUpdateRequest(
        out: java.io.OutputStream,
        x: Int, y: Int, w: Int, h: Int,
        incremental: Boolean,
    ) = synchronized(out) {
        out.write(
            byteArrayOf(
                3,
                if (incremental) 1 else 0,
                (x shr 8).toByte(), x.toByte(),
                (y shr 8).toByte(), y.toByte(),
                (w shr 8).toByte(), w.toByte(),
                (h shr 8).toByte(), h.toByte(),
            )
        )
        out.flush()
    }

    private companion object {
        /** DesktopSize pseudo-encoding: the guest telling us it changed resolution. */
        const val ENCODING_DESKTOP_SIZE = -223

        /** How long a key is held down. Roughly what a real keypress lasts. */
        const val KEY_HOLD_MS = 45L

        /** Quiet time after a keystroke, so a slow guest can drain its input queue. */
        const val KEY_GAP_MS = 25L
    }
}
