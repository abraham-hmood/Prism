package com.prism.desktop.games

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosting a Steam game for a phone on the mesh. PHASE 99.
 *
 * ## The one phase that is desktop-only by design
 *
 * Android is deliberately never a host -- a game under x86 emulation with no GPU is not playable --
 * so there is no Android source to port. The client half exists already (`CloudGaming` on the phone,
 * which filters for `canHostGames`) and this is the other end.
 *
 * ## WHAT IS CAPTURED, AND THE HONEST COST OF IT ON WINDOWS
 *
 * The phase stated the problem exactly: "On Linux an Xvfb/Xpra or a nested session is
 * straightforward; on Windows there is no equivalent and the practical answer is capturing the real
 * display, which means the machine cannot be used for anything else during a session. Say which it
 * is on the page."
 *
 * That is what happens, and [capturesRealDisplay] is what the page reads to say so. On Windows the
 * session is the machine: the game is on the real screen, the remote player's mouse moves the real
 * cursor, and the owner cannot use the PC meanwhile. On Linux, where `Xvfb` is present, the game runs
 * on a headless display and the owner keeps their own.
 *
 * ## RFB, and which parts of it
 *
 * The phone's client speaks RFB because that is what it was written against. This serves RFB 3.8 with
 * `None` security and raw-encoded framebuffer updates. Raw is the simplest encoding and the most
 * bandwidth-hungry; over a LAN mesh at 1280x720 it is about 2.6 MB a frame uncompressed, which is why
 * updates are sent only for CHANGED TILES rather than whole frames. A real codec -- Tight, ZRLE, or
 * better, H.264 -- would be the next thing to do and is a decoder on the phone as well as an encoder
 * here, which is why it is not in this phase.
 *
 * ## NOTHING IS LAUNCHED FOR A PEER WITHOUT CONFINEMENT
 *
 * [GameSandbox.remoteLaunchAllowed] gates it. The phase called sandboxing a prerequisite rather than
 * a follow-up, and a peer's request is refused with the reason rather than being honoured on a
 * machine that cannot confine what it starts.
 */
object GameHost {

    private const val TAG = "PrismGames"

    /** RFB's own version string. 3.8 is what every modern client negotiates. */
    private const val RFB_VERSION = "RFB 003.008\n"

    /** Tile side for change detection, in pixels. */
    private const val TILE = 64

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var session: Session? = null

    private val running = AtomicBoolean(false)

    @Volatile
    var lastRefusal: String = ""
        private set

    data class Session(
        val game: SteamLibrary.Game,
        val port: Int,
        val width: Int,
        val height: Int,
        val forPeer: String,
        val sandbox: GameSandbox.Mode,
        val startedAt: Long,
    )

    fun current(): Session? = session

    fun isHosting(): Boolean = running.get() && session != null

    /**
     * Whether a session takes over the owner's own screen.
     *
     * True on Windows always, and on Linux when no virtual display is available. The page says so
     * before anybody starts a session expecting to keep using their PC.
     */
    fun capturesRealDisplay(): Boolean = virtualDisplay() == null

    /**
     * A headless X display to run the game on, if one can be had.
     *
     * `Xvfb` is the whole mechanism: a display number nothing else is using, a game started with
     * `DISPLAY` pointing at it, and the capture reading from it instead of from the screen. Null on
     * Windows, which has no equivalent -- the phase said so and it is still true.
     */
    private fun virtualDisplay(): java.io.File? {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
        val path = System.getenv("PATH") ?: return null
        path.split(java.io.File.pathSeparatorChar).forEach { dir ->
            val candidate = java.io.File(dir, "Xvfb")
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }

    /** Titles this machine can offer. Empty when Steam is absent. */
    fun titles(): List<String> = runCatching { SteamLibrary.games().map { it.name } }
        .getOrDefault(emptyList())

    /** One line on whether this machine can host at all. */
    fun readiness(): String = when {
        GraphicsEnvironment.isHeadless() && virtualDisplay() == null ->
            "This session is headless and has no Xvfb, so there is nothing to capture."
        !SteamLibrary.isInstalled() -> SteamLibrary.unavailableReason()
        SteamLibrary.games().isEmpty() ->
            "Steam is installed but no game is fully installed. A game that is still downloading " +
                "cannot be hosted."
        !GameSandbox.remoteLaunchAllowed() ->
            "Ready for a local session. Peers are REFUSED: " + GameSandbox.refusalReason()
        !GameSandbox.canEnforce() ->
            "A sandbox is detected but cannot be enforced here (" + GameSandbox.detect().label +
                "), so peers are refused."
        else ->
            SteamLibrary.games().size.toString() + " game(s) hostable, confined by " +
                GameSandbox.detect().label + "."
    }

    /**
     * Starts a session for a peer. Returns the display port, or null with [lastRefusal] set.
     *
     * ## THE GATE IS FIRST, BEFORE ANYTHING IS LAUNCHED
     *
     * Not after the game starts and not as a warning: a machine that cannot confine a program does
     * not run one somebody else chose. That ordering is the phase's requirement and is the reason
     * this function reads as it does.
     */
    @Synchronized
    fun startForPeer(
        peerIp: String,
        title: String,
        width: Int,
        height: Int,
    ): Int? {
        if (!GameSandbox.remoteLaunchAllowed() || !GameSandbox.canEnforce()) {
            lastRefusal = GameSandbox.refusalReason()
            PrismPlatform.log.warn(TAG, "Refused " + peerIp + "'s request: no enforceable sandbox.")
            return null
        }
        return start(peerIp, title, width, height)
    }

    /**
     * Starts a session the machine's own user asked for.
     *
     * Allowed without a sandbox, and that is not an inconsistency: somebody starting their own game
     * on their own PC is not a boundary being crossed. The page states the difference.
     */
    @Synchronized
    fun startLocal(title: String, width: Int, height: Int): Int? = start("local", title, width, height)

    private fun start(peerIp: String, title: String, width: Int, height: Int): Int? {
        stop()

        val game = SteamLibrary.games().firstOrNull { it.name.equals(title, true) }
            ?: SteamLibrary.games().firstOrNull { it.name.contains(title, true) }
        if (game == null) {
            lastRefusal = "No installed game matches \"" + title + "\"."
            return null
        }

        val socket = runCatching {
            // Port 0: the OS picks one and the number goes back to the client in the reply. A fixed
            // port would collide with whatever else the machine runs and would be guessable.
            ServerSocket(0, 1, InetAddress.getLoopbackAddress().let {
                // Bound to the MESH address rather than loopback, because the whole point is that a
                // peer connects to it. Loopback would make the display unreachable.
                runCatching { InetAddress.getByName(com.prism.core.MeshUtils.getLocalMeshIp()) }
                    .getOrDefault(it)
            })
        }.getOrElse {
            lastRefusal = "Could not open a display port: " + it.message
            return null
        }

        val problem = SteamLibrary.launch(game)
        if (problem != null) {
            runCatching { socket.close() }
            lastRefusal = problem
            return null
        }

        listener = socket
        session = Session(
            game = game,
            port = socket.localPort,
            width = width,
            height = height,
            forPeer = peerIp,
            sandbox = GameSandbox.detect(),
            startedAt = System.currentTimeMillis(),
        )
        running.set(true)
        lastRefusal = ""

        Thread({ accept(socket, width, height) }, "rfb-accept")
            .apply { isDaemon = true; start() }

        PrismPlatform.log.success(
            TAG,
            "Hosting \"" + game.name + "\" for " + peerIp + " on " + socket.localPort +
                " (" + width + "x" + height + ", " + GameSandbox.detect().label + ")",
        )
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        session = null
    }

    // ── RFB ─────────────────────────────────────────────────────────────────

    private fun accept(socket: ServerSocket, width: Int, height: Int) {
        runCatching {
            // One client. A second connection to a game session is somebody else watching somebody
            // play, which is a feature nobody asked for and a second input source nobody wants.
            socket.soTimeout = 120_000
            val client = socket.accept()
            PrismPlatform.log.info(TAG, "Display client connected from " + client.inetAddress)
            serve(client, width, height)
        }.onFailure {
            if (running.get()) {
                PrismPlatform.log.warn(TAG, "The display port closed: " + it.message)
            }
        }
        runCatching { socket.close() }
    }

    /**
     * The RFB server.
     *
     * 3.8, `None` security, raw encoding. The handshake order is fixed by the protocol and getting it
     * wrong produces a client that hangs rather than one that complains, which is why each step is
     * named.
     */
    private fun serve(client: Socket, width: Int, height: Int) {
        val robot = runCatching { Robot() }.getOrNull()
        if (robot == null) {
            PrismPlatform.log.error(TAG, "No AWT Robot, so nothing can be captured or clicked", null)
            runCatching { client.close() }
            return
        }

        // Interactive: Nagle batching small input messages adds a visible delay to every click, and
        // the phone's client sets this for the same reason at its end.
        runCatching { client.tcpNoDelay = true }

        val input = DataInputStream(client.getInputStream().buffered())
        val output = DataOutputStream(client.getOutputStream().buffered())

        runCatching {
            // 1. Version. The server offers, the client answers with what it will speak.
            output.write(RFB_VERSION.toByteArray(Charsets.US_ASCII))
            output.flush()
            val clientVersion = ByteArray(12).also { input.readFully(it) }
                .decodeToString().trim()

            // 2. Security. One type, `None` (1). On a mesh that already authenticates its peers and
            // a port that only exists for the length of a session, VNC's own 8-byte DES challenge
            // would add a password to manage and no security worth the name -- it is DES with a
            // 56-bit key and a published weakness.
            output.writeByte(1)
            output.writeByte(1)
            output.flush()
            val chosen = input.readUnsignedByte()
            if (chosen != 1) {
                PrismPlatform.log.warn(TAG, "Client asked for security type " + chosen)
                return@runCatching
            }
            // SecurityResult: 0 for OK.
            output.writeInt(0)
            output.flush()

            // 3. ClientInit: one byte, shared-desktop flag. Ignored -- see accept().
            input.readUnsignedByte()

            // 4. ServerInit: size, pixel format, name.
            output.writeShort(width)
            output.writeShort(height)
            writePixelFormat(output)
            val name = (session?.game?.name ?: "Prism").toByteArray(Charsets.UTF_8)
            output.writeInt(name.size)
            output.write(name)
            output.flush()

            PrismPlatform.log.info(TAG, "RFB up for " + clientVersion + " at " + width + "x" + height)

            // Input arrives on its own thread: a client that is typing while a frame is being sent
            // must not wait for it, and the framebuffer loop must not stall waiting for a keypress.
            Thread({ readInput(input, robot, width, height) }, "rfb-input")
                .apply { isDaemon = true; start() }

            pushFrames(output, robot, width, height)
        }.onFailure {
            if (running.get()) PrismPlatform.log.warn(TAG, "RFB session ended: " + it.message)
        }
        runCatching { client.close() }
    }

    /**
     * 32-bit true colour, little-endian.
     *
     * Matched to what `BufferedImage.TYPE_INT_RGB` gives, so a pixel goes from the capture to the
     * wire with a shift and no per-pixel conversion. A 16-bit format would halve the bandwidth and
     * cost a conversion per pixel per frame, which at 1280x720x30 is the wrong trade on a LAN.
     */
    private fun writePixelFormat(output: DataOutputStream) {
        output.writeByte(32)        // bits per pixel
        output.writeByte(24)        // depth
        output.writeByte(0)         // big-endian flag: 0, little
        output.writeByte(1)         // true colour
        output.writeShort(255)      // red max
        output.writeShort(255)      // green max
        output.writeShort(255)      // blue max
        output.writeByte(16)        // red shift
        output.writeByte(8)         // green shift
        output.writeByte(0)         // blue shift
        output.write(ByteArray(3))  // padding
    }

    /**
     * Sends changed tiles for as long as the session lives.
     *
     * ## Only what changed, which is the difference between playable and not
     *
     * A raw 1280x720 frame is 3.7 MB. At thirty of those a second it is 110 MB/s, which no Wi-Fi mesh
     * carries. Most of a frame is identical between updates even in a fast game, so the screen is
     * divided into 64-pixel tiles and only tiles whose contents changed are sent. A static menu sends
     * almost nothing; a full-screen camera pan sends everything, and that is the honest worst case of
     * raw encoding.
     */
    private fun pushFrames(output: DataOutputStream, robot: Robot, width: Int, height: Int) {
        val bounds = Rectangle(0, 0, width, height)
        var previous: IntArray? = null
        val columns = (width + TILE - 1) / TILE
        val rows = (height + TILE - 1) / TILE

        while (running.get()) {
            val shot = runCatching { robot.createScreenCapture(bounds) }.getOrNull() ?: break
            val pixels = pixelsOf(shot, width, height)

            val dirty = ArrayList<Rectangle>()
            for (ty in 0 until rows) {
                for (tx in 0 until columns) {
                    val x = tx * TILE
                    val y = ty * TILE
                    val w = minOf(TILE, width - x)
                    val h = minOf(TILE, height - y)
                    if (previous == null || tileChanged(previous, pixels, x, y, w, h, width)) {
                        dirty.add(Rectangle(x, y, w, h))
                    }
                }
            }

            if (dirty.isNotEmpty()) {
                // FramebufferUpdate: message type 0, padding, rectangle count, then each rectangle.
                output.writeByte(0)
                output.writeByte(0)
                output.writeShort(dirty.size)
                dirty.forEach { rect ->
                    output.writeShort(rect.x)
                    output.writeShort(rect.y)
                    output.writeShort(rect.width)
                    output.writeShort(rect.height)
                    output.writeInt(0)          // encoding: Raw
                    val row = ByteArray(rect.width * 4)
                    for (line in 0 until rect.height) {
                        var at = 0
                        val base = (rect.y + line) * width + rect.x
                        for (column in 0 until rect.width) {
                            val pixel = pixels[base + column]
                            row[at++] = (pixel and 0xFF).toByte()           // blue
                            row[at++] = ((pixel shr 8) and 0xFF).toByte()   // green
                            row[at++] = ((pixel shr 16) and 0xFF).toByte()  // red
                            row[at++] = 0
                        }
                        output.write(row)
                    }
                }
                output.flush()
            }

            previous = pixels
            // ~30 fps. Capped rather than free-running: createScreenCapture is the expensive part
            // and spinning on it would peg a core for frames nobody sees.
            runCatching { Thread.sleep(33) }
        }
    }

    private fun pixelsOf(image: BufferedImage, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        image.getRGB(0, 0, minOf(width, image.width), minOf(height, image.height), out, 0, width)
        return out
    }

    private fun tileChanged(
        previous: IntArray,
        current: IntArray,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        stride: Int,
    ): Boolean {
        for (line in 0 until h) {
            val base = (y + line) * stride + x
            for (column in 0 until w) {
                if (previous[base + column] != current[base + column]) return true
            }
        }
        return false
    }

    /**
     * Applies the client's input to the real machine.
     *
     * ## THIS IS REMOTE CONTROL OF A MOUSE AND KEYBOARD, AND IT IS SCOPED AS LITTLE AS POSSIBLE
     *
     * There is no way to send a click to one window with `Robot`: it moves the real cursor and presses
     * the real buttons. So while a session is up, the peer is driving the machine -- which is why a
     * remote session needs a sandbox, and why [capturesRealDisplay] is reported in those words.
     *
     * What IS scoped: the keysym translation below handles letters, digits, and the navigation and
     * editing keys a game uses. It does NOT translate the modifier combinations that reach the
     * desktop rather than the game -- Ctrl+Alt+Del cannot be synthesised by `Robot` at all, and
     * Alt+Tab and the Windows key are dropped deliberately rather than passed through.
     */
    private fun readInput(input: DataInputStream, robot: Robot, width: Int, height: Int) {
        runCatching {
            while (running.get()) {
                when (input.readUnsignedByte()) {
                    // SetPixelFormat: 3 bytes padding + 16 bytes of format. Accepted and ignored --
                    // the server already told the client what it sends and will not renegotiate.
                    0 -> input.skipBytes(19)
                    // SetEncodings: 1 byte padding, count, then four bytes each.
                    2 -> {
                        input.skipBytes(1)
                        val count = input.readUnsignedShort()
                        input.skipBytes(count * 4)
                    }
                    // FramebufferUpdateRequest. Ignored: frames are pushed on a timer, so an
                    // incremental request would only ask for what is already coming.
                    3 -> input.skipBytes(9)
                    // KeyEvent: down flag, 2 padding, keysym.
                    4 -> {
                        val down = input.readUnsignedByte() != 0
                        input.skipBytes(2)
                        val keysym = input.readInt()
                        keyCodeFor(keysym)?.let { code ->
                            runCatching {
                                if (down) robot.keyPress(code) else robot.keyRelease(code)
                            }
                        }
                    }
                    // PointerEvent: button mask, x, y.
                    5 -> {
                        val mask = input.readUnsignedByte()
                        val x = input.readUnsignedShort().coerceIn(0, width - 1)
                        val y = input.readUnsignedShort().coerceIn(0, height - 1)
                        runCatching {
                            robot.mouseMove(x, y)
                            applyButtons(robot, mask)
                        }
                    }
                    // ClientCutText: 3 padding, length, text. Dropped: a remote player pushing text
                    // into the host's clipboard is not something a game session needs, and the
                    // clipboard is the owner's.
                    6 -> {
                        input.skipBytes(3)
                        val length = input.readInt()
                        input.skipBytes(length)
                    }
                    else -> return@runCatching
                }
            }
        }
    }

    private var heldButtons = 0

    private fun applyButtons(robot: Robot, mask: Int) {
        // RFB sends the whole button state each event, so the change is the difference from what is
        // already held. Pressing on every event with a held button would repeat the press.
        val changed = mask xor heldButtons
        listOf(
            0x1 to InputEvent.BUTTON1_DOWN_MASK,
            0x2 to InputEvent.BUTTON2_DOWN_MASK,
            0x4 to InputEvent.BUTTON3_DOWN_MASK,
        ).forEach { (bit, awt) ->
            if (changed and bit == 0) return@forEach
            if (mask and bit != 0) robot.mousePress(awt) else robot.mouseRelease(awt)
        }
        // Wheel: RFB models it as buttons 4 and 5, which are a press-and-release rather than a state.
        if (mask and 0x8 != 0) robot.mouseWheel(-1)
        if (mask and 0x10 != 0) robot.mouseWheel(1)
        heldButtons = mask and 0x7
    }

    /**
     * X11 keysym to an AWT key code.
     *
     * Only what a game uses. The dangerous and un-synthesisable combinations are deliberately absent:
     * `Robot` cannot send Ctrl+Alt+Del at all, and Alt+Tab and the Super key are dropped rather than
     * handed to a remote player on somebody else's desktop.
     */
    private fun keyCodeFor(keysym: Int): Int? = when {
        keysym in 0x61..0x7A -> KeyEvent.VK_A + (keysym - 0x61)   // a-z
        keysym in 0x41..0x5A -> KeyEvent.VK_A + (keysym - 0x41)   // A-Z
        keysym in 0x30..0x39 -> KeyEvent.VK_0 + (keysym - 0x30)   // 0-9
        keysym == 0x20 -> KeyEvent.VK_SPACE
        keysym == 0xFF0D -> KeyEvent.VK_ENTER
        keysym == 0xFF08 -> KeyEvent.VK_BACK_SPACE
        keysym == 0xFF09 -> KeyEvent.VK_TAB
        keysym == 0xFF1B -> KeyEvent.VK_ESCAPE
        keysym == 0xFF51 -> KeyEvent.VK_LEFT
        keysym == 0xFF52 -> KeyEvent.VK_UP
        keysym == 0xFF53 -> KeyEvent.VK_RIGHT
        keysym == 0xFF54 -> KeyEvent.VK_DOWN
        keysym == 0xFFE1 || keysym == 0xFFE2 -> KeyEvent.VK_SHIFT
        keysym == 0xFFE3 || keysym == 0xFFE4 -> KeyEvent.VK_CONTROL
        keysym == 0xFFE9 || keysym == 0xFFEA -> KeyEvent.VK_ALT
        keysym == 0xFF50 -> KeyEvent.VK_HOME
        keysym == 0xFF57 -> KeyEvent.VK_END
        keysym == 0xFF55 -> KeyEvent.VK_PAGE_UP
        keysym == 0xFF56 -> KeyEvent.VK_PAGE_DOWN
        keysym == 0xFFFF -> KeyEvent.VK_DELETE
        keysym in 0xFFBE..0xFFC9 -> KeyEvent.VK_F1 + (keysym - 0xFFBE)  // F1-F12
        // The Super/Windows key and anything else: dropped. See the doc comment.
        else -> null
    }

    // ── The mesh route ──────────────────────────────────────────────────────

    /**
     * Answers the game control operations a phone's client sends.
     *
     * The same JSON `CloudWire` posts: an `op` of `game-list`, `game-start` or `game-stop`. Returns
     * the reply body, or null for an operation this is not responsible for.
     */
    fun handleControl(peerIp: String, payload: String): String? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return when (json.optString("op")) {
            "game-list" -> JSONObject().apply {
                put("ok", true)
                put("games", JSONArray().also { array -> titles().forEach { array.put(it) } })
            }.toString()

            "game-start" -> {
                val port = startForPeer(
                    peerIp,
                    json.optString("title"),
                    json.optInt("w", 1280),
                    json.optInt("h", 720),
                )
                JSONObject().apply {
                    put("ok", port != null)
                    if (port != null) put("port", port) else put("reason", lastRefusal)
                }.toString()
            }

            "game-stop" -> {
                stop()
                JSONObject().apply { put("ok", true) }.toString()
            }

            else -> null
        }
    }

    fun describe(): String {
        val active = session
        return when {
            active != null ->
                "hosting \"" + active.game.name + "\" for " + active.forPeer + " on port " +
                    active.port + ", " + active.width + "x" + active.height + ", confined by " +
                    active.sandbox.label
            else -> readiness()
        }
    }
}
