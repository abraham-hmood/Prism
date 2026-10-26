package com.prism.launcher.trusted

import com.prism.core.MeshMembership
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject

/**
 * Moving another machine's pointer, and clicking it.
 *
 * ## What crosses the network
 *
 * An event, not a frame. A mouse move is a handful of bytes -- a normalised position, or a button
 * and a wheel notch -- so a phone tilting in someone's hand is a few hundred bytes a second rather
 * than a video stream. The device that receives it decides what "0.42, 0.63" means on its own
 * screen, which is the only way one implementation can serve a 4K monitor and a laptop panel.
 *
 * ## Who may do it
 *
 * ONLY A CONFIRMED TRUSTED DEVICE, checked on both ends: [send] refuses to address anything that is
 * not confirmed, and [onPacket] refuses to execute anything from an address that is not a confirmed
 * pairing. Being on the same Wi-Fi is not consent to control a machine's cursor, so membership of
 * the mesh is deliberately not enough on its own.
 *
 * The receiving end also has to exist. A desktop runs [install] at startup and injects through
 * `java.awt.Robot`; an Android device has no such facility without an accessibility service, and
 * reports that to the sender rather than pretending the click landed.
 *
 * ## Why pointer control is not a share kind
 *
 * The pairing kinds are about data -- clipboard, files, messages. Pointer control is not a copy of
 * anything; it is an action, and it is scoped to the Mouse page being open on a device the user
 * paired. Making it a fourth kind would mean a device could be trusted for clipboard and then, by
 * accident, for input. The page itself is the consent surface: it names the device it is driving,
 * and nothing is sent while it is closed.
 */
object RemotePointer {

    private const val TAG = "PrismPointer"

    /**
     * Opcode 0x3A.
     *
     * Checked against every other mesh feature: 0x30-0x34 are calls and social, 0x35-0x39 are
     * unused, and 0x50+ are minigames and speech. 0x3A is clear on both platforms.
     */
    const val OPCODE: Byte = 0x3A

    enum class Button { LEFT, RIGHT }

    /**
     * One pointer action.
     *
     * [x] and [y] are 0..1 across the receiving screen. [amount] is wheel notches, positive for
     * up/away. Movement and scrolling carry no button; a click carries no coordinates because it
     * happens where the cursor already is.
     */
    data class Event(
        val kind: Kind,
        val x: Float = 0f,
        val y: Float = 0f,
        val button: Button = Button.LEFT,
        val amount: Float = 0f,
        /**
         * The sender's Prism identity.
         *
         * Carried because a USB link gives the sender a different source address than the one
         * the pairing recorded over Wi-Fi. The address is a hint; the fingerprint is who the
         * device is, and [onPacket] falls back to it when the address is not the known one.
         */
        val from: String = "",
    ) {
        enum class Kind { MOVE, CLICK, SCROLL }
    }

    /**
     * Runs an accepted event. Installed by a platform that can actually inject input.
     *
     * No default implementation: a device that cannot move a cursor must not silently accept
     * events and leave the sender believing they landed.
     */
    @Volatile
    var executor: ((TrustedDevices.Trust, Event) -> Unit)? = null

    /** Whether this device can be driven by a remote pointer. */
    fun canReceive(): Boolean = executor != null

    /**
     * The confirmed devices the Mouse page may target, online first.
     *
     * Online is a lease check, not a stored flag -- see `MeshCore.isPeer` -- so a device that has
     * closed Prism drops out of the list rather than being offered as a dead target.
     */
    fun targets(): List<TrustedDevices.Trust> =
        TrustedDevices.all()
            .filter { it.confirmed }
            .sortedWith(compareByDescending<TrustedDevices.Trust> { it.online }.thenBy { it.name.lowercase() })

    /** The device the page is currently driving, or the first online target. */
    fun activeTarget(): TrustedDevices.Trust? {
        val all = targets()
        val saved = com.prism.launcher.PrismSettings.getPointerTargetFingerprint()
        return all.firstOrNull { it.answersTo(saved) }
            ?: all.firstOrNull { it.online }
            ?: all.firstOrNull()
    }

    /** The whole feature requires a meshnet and somebody to drive. */
    fun available(): Boolean =
        MeshMembership.isOnMeshnet() && targets().isNotEmpty()

    /**
     * Sends one event to [device].
     *
     * Returns false rather than throwing for the ordinary refusals -- unconfirmed pairing, blank
     * address, mesh stopped -- because a button press on a page that is no longer connected should
     * not crash the launcher. The page shows the returned state instead of claiming success.
     */
    fun send(device: TrustedDevices.Trust, event: Event): Boolean {
        if (!device.confirmed) return false
        val usb = com.prism.launcher.PrismSettings.getPointerTransport() ==
            com.prism.launcher.PrismSettings.POINTER_TRANSPORT_USB
        // A USB link is a different address for the SAME device. When no USB host is visible the
        // Wi-Fi address is still the right one, so a cable that was unplugged mid-click degrades
        // to the link the pairing already had rather than dropping the event.
        val address = (if (usb) usbPeerAddress() else null) ?: device.lastIp
        if (address.isBlank()) return false
        if (address == device.lastIp && !runCatching { device.online }.getOrDefault(false)) return false
        return runCatching {
            TrustedDevices.sender(address, OPCODE, encode(event.copy(from = localIdentity())))
            true
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not send a pointer event to ${device.name}: ${it.message}")
            false
        }
    }

    /**
     * This device's fingerprint, computed once per process.
     *
     * Deriving it reaches the wallet: the namespace is derived from the recovery phrase, and
     * reading that phrase means decrypting it. A pointer move is sent up to a hundred times a
     * second, and doing that work on the UI thread for every one of them was a measurable part of
     * the lag between the finger and the cursor. Thirty seconds is the compromise: a pointer
     * stream never pays the cost, while importing a wallet still produces the new identity
     * without a restart.
     */
    @Volatile private var cachedIdentity: String? = null
    @Volatile private var cachedIdentityAt = 0L

    private fun localIdentity(): String {
        val now = System.currentTimeMillis()
        cachedIdentity?.let { if (now - cachedIdentityAt < 30_000L) return it }
        return runCatching { TrustedDevices.localFingerprint() }.getOrDefault("")
            .also {
                if (it.isNotBlank()) {
                    cachedIdentity = it
                    cachedIdentityAt = now
                }
            }
    }

    /** Cached for a second: reading the ARP table and the interface list per move was work the
     *  pointer sent at up to a hundred times a second, for an answer that only changes when a
     *  cable is plugged in. */
    @Volatile private var cachedUsb: Pair<Long, String?>? = null

    /**
     * The other end of a USB cable, when this device is tethering over it.
     *
     * USB tethering gives the phone a small network (`usb0` or `rndis0`) and the computer a lease
     * on it. That address is not the one the pairing saw over Wi-Fi, so it is read from the
     * kernel's neighbour table rather than guessed: the only other host on that interface is the
     * cable's far end. Null means there is no cable, and [send] falls back to Wi-Fi.
     */
    private fun usbPeerAddress(): String? {
        val now = System.currentTimeMillis()
        cachedUsb?.let { (at, value) -> if (now - at < 1_000L) return value }
        val value = readUsbPeerAddress()
        cachedUsb = now to value
        return value
    }

    private fun readUsbPeerAddress(): String? = runCatching {
        val usb = java.net.NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nic ->
            val name = nic.name.lowercase()
            nic.isUp && (name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("ncm"))
        } ?: return@runCatching null
        val arp = java.io.File("/proc/net/arp")
        if (!arp.canRead()) return@runCatching null
        arp.readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { fields ->
                fields.size >= 6 && fields[5] == usb.name && fields[3] != "00:00:00:00:00:00"
            }
            ?.getOrNull(0)
    }.getOrNull()

    fun encode(event: Event): String = when (event.kind) {
        Event.Kind.MOVE -> JSONObject().apply {
            put("t", "m")
            put("x", event.x.coerceIn(0f, 1f).toDouble())
            put("y", event.y.coerceIn(0f, 1f).toDouble())
            if (event.from.isNotBlank()) put("fp", event.from)
        }.toString()

        Event.Kind.CLICK -> JSONObject().apply {
            put("t", "c")
            put("b", if (event.button == Button.RIGHT) "r" else "l")
            if (event.from.isNotBlank()) put("fp", event.from)
        }.toString()

        Event.Kind.SCROLL -> JSONObject().apply {
            put("t", "s")
            put("d", event.amount.toDouble())
            if (event.from.isNotBlank()) put("fp", event.from)
        }.toString()
    }

    fun decode(payload: String): Event? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val from = json.optString("fp")
        return when (json.optString("t")) {
            "m" -> {
                val x = json.optDouble("x", Double.NaN).toFloat()
                val y = json.optDouble("y", Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) return null
                Event(Event.Kind.MOVE, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), from = from)
            }
            "c" -> Event(
                Event.Kind.CLICK,
                button = if (json.optString("b") == "r") Button.RIGHT else Button.LEFT,
                from = from,
            )
            "s" -> {
                val delta = json.optDouble("d", 0.0).toFloat()
                if (!delta.isFinite()) return null
                Event(Event.Kind.SCROLL, amount = delta.coerceIn(-10f, 10f), from = from)
            }
            else -> null
        }
    }

    /**
     * Handles one datagram.
     *
     * Called by the Android service's dispatcher and by the MeshCore registration below. The trust
     * check is here rather than in the caller so there is exactly one place it can be forgotten.
     */
    fun onPacket(peerIp: String, payload: String) {
        val event = decode(payload) ?: return
        // Address first, identity second. A USB link is the same paired device on a second
        // address, so refusing it because the cable changed the source would break exactly the
        // transport the choice asks for.
        val device = TrustedDevices.byIp(peerIp)
            ?: event.from.takeIf { it.isNotBlank() }?.let { TrustedDevices.byFingerprint(it) }
            ?: run {
                PrismPlatform.log.warn(TAG, "Refused pointer input from an unknown address: $peerIp")
                return
            }
        if (!device.confirmed) {
            PrismPlatform.log.warn(TAG, "Refused pointer input from unconfirmed ${device.name}")
            return
        }
        val run = executor ?: run {
            // Not an error worth a dialog: the sender is told by its page that this device does not
            // accept input, and repeated datagrams should not fill the log.
            return
        }
        runCatching { run(device, event) }
            .onFailure { PrismPlatform.log.warn(TAG, "Pointer event failed: ${it.message}") }
    }

    /** Registers the receive path. A no-op on Android, whose service dispatches this itself. */
    fun install() {
        com.prism.core.MeshCore.register(OPCODE) { peerIp, payload ->
            onPacket(peerIp, payload)
            true
        }
    }
}
