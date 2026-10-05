package com.prism.desktop.net

import com.prism.core.PrismPlatform
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A virtual network adapter. PHASE 59.
 *
 * ## What this actually is
 *
 * A TUN interface is a file handle that the operating system treats as a network card. Whatever is
 * written to it appears to the system as an arriving IP packet; whatever the system routes to it can be
 * read out. That is the whole mechanism, and it is what every VPN on every platform is built on.
 *
 * ## Two implementations, one interface
 *
 *   WINDOWS   WinTun — WireGuard's own redistributable driver. It is signed, it needs no reboot, and it
 *             exposes a small C API. There is no command-line front end, so this is the reason the
 *             desktop build carries JNA at all.
 *   LINUX     `/dev/net/tun` plus one ioctl (`TUNSETIFF`) naming the interface. After that it is an
 *             ordinary file descriptor, which is why Linux is the easy one.
 *   macOS     NOT IMPLEMENTED, and the reason is not effort. There is no /dev/net/tun; the supported
 *             route for a shipped application is a Network Extension, which requires an Apple developer
 *             entitlement. That is a paperwork dependency, and no amount of code here removes it.
 *
 * ## What it still needs from the user
 *
 * ADMINISTRATOR RIGHTS. Creating a network adapter is a privileged operation on every platform, and
 * Prism does not elevate itself — [Availability] says what is missing and the caller shows it. A
 * launcher that silently acquired administrator rights would be a thing to be suspicious of.
 *
 * WINTUN.DLL. Prism does not bundle it: it is a driver, it is signed by WireGuard rather than by Prism,
 * and shipping somebody else's signed driver inside an application is a supply-chain decision rather
 * than a packaging one. [Availability.missing] names where to put it.
 */
object TunDevice {

    private const val TAG = "PrismTun"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** The ring buffer WinTun allocates per session. 4 MB is WireGuard's own default. */
    private const val RING_CAPACITY = 0x400000

    /** Linux: TUN (layer 3) with no packet information header. */
    private const val IFF_TUN = 0x0001
    private const val IFF_NO_PI = 0x1000
    private const val TUNSETIFF = 0x400454ca

    // ── The native surfaces ────────────────────────────────────────────────

    /**
     * WinTun, as C declares it.
     *
     * Only the calls a tunnel needs. WinTun also has logging and adapter-enumeration entry points that
     * are useful for a driver installer and not for this.
     */
    private interface Wintun : Library {
        fun WintunCreateAdapter(name: WString, tunnelType: WString, requestedGuid: Pointer?): Pointer?
        fun WintunCloseAdapter(adapter: Pointer)
        fun WintunOpenAdapter(name: WString): Pointer?
        fun WintunGetAdapterLUID(adapter: Pointer, luid: Pointer)
        fun WintunStartSession(adapter: Pointer, capacity: Int): Pointer?
        fun WintunEndSession(session: Pointer)
        fun WintunGetReadWaitEvent(session: Pointer): Pointer?
        fun WintunReceivePacket(session: Pointer, packetSize: IntByReference): Pointer?
        fun WintunReleaseReceivePacket(session: Pointer, packet: Pointer)
        fun WintunAllocateSendPacket(session: Pointer, packetSize: Int): Pointer?
        fun WintunSendPacket(session: Pointer, packet: Pointer)
    }

    /** Just enough libc for the Linux ioctl. */
    private interface LibC : Library {
        fun ioctl(fd: Int, request: Long, argp: Structure): Int
        fun open(path: String, flags: Int): Int
        fun close(fd: Int): Int
        fun read(fd: Int, buffer: ByteArray, count: Int): Int
        fun write(fd: Int, buffer: ByteArray, count: Int): Int
    }

    /** `struct ifreq`, the part of it TUNSETIFF reads. */
    class IfReq : Structure() {
        @JvmField var ifrName: ByteArray = ByteArray(16)
        @JvmField var ifrFlags: Short = 0
        @JvmField var padding: ByteArray = ByteArray(22)
        override fun getFieldOrder(): List<String> = listOf("ifrName", "ifrFlags", "padding")
    }

    // ── Availability ───────────────────────────────────────────────────────

    data class Availability(
        val usable: Boolean,
        val mechanism: String,
        val missing: List<String>,
        val detail: String,
    )

    /**
     * Whether an adapter could be created right now, and what is missing if not.
     *
     * Everything here is checked rather than attempted. A half-created WinTun adapter has to be cleaned
     * up out of the registry, which is a worse outcome than a report.
     */
    fun availability(): Availability {
        val missing = mutableListOf<String>()

        if (mac) {
            return Availability(
                false, "utun",
                listOf("a signed Network Extension, which needs an Apple developer entitlement"),
                "macOS has no /dev/net/tun and no redistributable driver. The supported route for a " +
                    "shipped application is a Network Extension; that is an entitlement Apple grants, " +
                    "not a library that can be added here.",
            )
        }

        if (windows) {
            val dll = wintunPath()
            if (dll == null) {
                missing += "wintun.dll — download it from wintun.net and put it beside Prism or in " +
                    File(PrismPlatform.host.dataDir(), "wintun.dll").absolutePath
            }
            if (!elevated()) missing += "an elevated process; creating an adapter is privileged"

            return Availability(
                missing.isEmpty(), "WinTun", missing,
                if (missing.isEmpty()) {
                    "WinTun is present and this process can create an adapter."
                } else {
                    "WinTun is the right mechanism -- it is what WireGuard uses on Windows, it is " +
                        "signed, and it needs no reboot. Prism does not bundle it: shipping somebody " +
                        "else's signed driver inside an application is a supply-chain decision."
                },
            )
        }

        if (!File("/dev/net/tun").exists()) missing += "/dev/net/tun — run `modprobe tun`"
        if (!elevated()) {
            missing += "CAP_NET_ADMIN — `sudo setcap cap_net_admin+ep <java binary>`, or run as root"
        }
        return Availability(
            missing.isEmpty(), "/dev/net/tun", missing,
            "On Linux the device node is a file and everything after the ioctl that names the " +
                "interface is ordinary read and write.",
        )
    }

    fun wintunPath(): File? = listOf(
        File(System.getProperty("user.dir"), "wintun.dll"),
        File(PrismPlatform.host.dataDir(), "wintun.dll"),
        File("C:/Windows/System32/wintun.dll"),
    ).firstOrNull { it.isFile }

    /** Whether this process could create an interface. Probed, because Java cannot ask. */
    fun elevated(): Boolean = runCatching {
        val probe = if (windows) {
            File(System.getenv("SystemRoot") ?: "C:/Windows", "prism-elevation-probe.tmp")
        } else {
            File("/etc", "prism-elevation-probe.tmp")
        }
        val created = probe.createNewFile()
        if (created) probe.delete()
        created
    }.getOrDefault(false)

    // ── The device ─────────────────────────────────────────────────────────

    /**
     * An open adapter. Packets in and out, and a close.
     *
     * The two platforms have nothing in common underneath -- a session handle and a ring buffer against
     * a file descriptor -- so the interface is the smallest thing that covers both, which is also
     * exactly what a tunnel needs.
     */
    interface Session {
        val name: String
        fun read(): ByteArray?
        fun write(packet: ByteArray): Boolean
        fun close()
    }

    @Volatile
    var current: Session? = null
        private set

    /**
     * Creates and opens an adapter. Returns null and sets [lastError] on failure.
     *
     * NOT IDEMPOTENT ON PURPOSE: an existing session is returned rather than a second adapter created.
     * Two adapters with the same name is a state Windows handles badly and a user cannot easily clean
     * up.
     */
    fun open(name: String = "Prism"): Session? {
        current?.let { return it }

        val availability = availability()
        if (!availability.usable) {
            lastError = availability.missing.joinToString("; ")
            return null
        }

        return runCatching {
            val session = if (windows) openWintun(name) else openLinux(name)
            current = session
            PrismPlatform.log.info(TAG, "Opened the " + availability.mechanism + " adapter " + name)
            session
        }.getOrElse {
            lastError = it.message.orEmpty()
            PrismPlatform.log.error(TAG, "Could not open a TUN adapter", it)
            null
        }
    }

    @Volatile
    var lastError: String = ""
        private set

    fun close() {
        current?.close()
        current = null
    }

    // ── Windows ────────────────────────────────────────────────────────────

    private fun openWintun(name: String): Session {
        val dll = wintunPath() ?: throw IllegalStateException("wintun.dll is not here")
        // Loaded by absolute path: the DLL may be beside Prism rather than on the search path, and
        // Windows resolves a bare name through its own order rather than through java.library.path.
        val wintun = Native.load(dll.absolutePath, Wintun::class.java)

        val adapter = wintun.WintunCreateAdapter(WString(name), WString("Prism"), null)
            ?: throw IllegalStateException(
                "WinTun refused to create the adapter (error " + Native.getLastError() + "). " +
                    "This almost always means the process is not elevated."
            )

        val session = wintun.WintunStartSession(adapter, RING_CAPACITY)
            ?: run {
                wintun.WintunCloseAdapter(adapter)
                throw IllegalStateException("WinTun created the adapter but would not start a session.")
            }

        return object : Session {
            override val name = name

            override fun read(): ByteArray? {
                val size = IntByReference()
                val packet = wintun.WintunReceivePacket(session, size) ?: return null
                return try {
                    packet.getByteArray(0, size.value)
                } finally {
                    wintun.WintunReleaseReceivePacket(session, packet)
                }
            }

            override fun write(packet: ByteArray): Boolean {
                val buffer = wintun.WintunAllocateSendPacket(session, packet.size) ?: return false
                buffer.write(0, packet, 0, packet.size)
                wintun.WintunSendPacket(session, buffer)
                return true
            }

            override fun close() {
                runCatching { wintun.WintunEndSession(session) }
                runCatching { wintun.WintunCloseAdapter(adapter) }
                PrismPlatform.log.info(TAG, "Closed the WinTun adapter")
            }
        }
    }

    // ── Linux ──────────────────────────────────────────────────────────────

    private fun openLinux(name: String): Session {
        val libc = Native.load("c", LibC::class.java)

        // O_RDWR is 2. Opened through libc rather than through Java's file API because the ioctl
        // below needs the raw descriptor, and a FileDescriptor's integer is not reachable portably.
        val fd = libc.open("/dev/net/tun", 2)
        if (fd < 0) throw IllegalStateException("Could not open /dev/net/tun")

        val request = IfReq()
        val bytes = name.toByteArray().copyOf(16)
        System.arraycopy(bytes, 0, request.ifrName, 0, minOf(bytes.size, 15))
        request.ifrFlags = (IFF_TUN or IFF_NO_PI).toShort()
        request.write()

        if (libc.ioctl(fd, TUNSETIFF.toLong(), request) < 0) {
            libc.close(fd)
            throw IllegalStateException(
                "TUNSETIFF was refused. This needs CAP_NET_ADMIN or root."
            )
        }

        return object : Session {
            override val name = name
            private val buffer = ByteArray(65_536)

            override fun read(): ByteArray? {
                val read = libc.read(fd, buffer, buffer.size)
                return if (read <= 0) null else buffer.copyOf(read)
            }

            override fun write(packet: ByteArray): Boolean =
                libc.write(fd, packet, packet.size) == packet.size

            override fun close() {
                runCatching { libc.close(fd) }
                PrismPlatform.log.info(TAG, "Closed the tun device")
            }
        }
    }
}
