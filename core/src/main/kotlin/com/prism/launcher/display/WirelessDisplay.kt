package com.prism.launcher.display

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.trusted.PairingCode
import com.prism.launcher.trusted.TrustedDevices
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Mirroring a screen to another device, with a pairing link in front of it.
 *
 * ## Two kinds of display
 *
 * A PRISM DEVICE receives a live picture of the sender's screen. The control link decides who is
 * allowed to send; the pixels travel on a separate stream, whose port and one-session token are
 * sent only after the handshake. A display that rebuilds a page locally is not a mirror: it shows
 * the receiver's own Files page when the sender is on Files.
 *
 * A NON-PRISM DISPLAY -- a TV or a monitor the operating system already knows about -- is presented
 * through the platform's own display API (Android's `Presentation`, an undecorated full-screen
 * window on a desktop screen). Those are listed by the platform layer, not by this file, because
 * discovering and presenting a system display are both platform calls. No browser and no cast
 * settings app is involved.
 *
 * ## Mimic and extend
 *
 * The mode is the SENDER's choice, made on the display picker. MIMIC streams the screen as it is.
 * EXTEND streams the page after it, rendered on the sender: a display must show the sender's own
 * files and icons, not rebuild a page with its own. Platforms with an offscreen page renderer
 * publish that page through [onStartExtend]; the slot message remains as a fallback for a system
 * display attached to the same machine, where rendering the page locally is rendering the
 * sender's page. Extend is never used for mimic.
 *
 * ## Verification
 *
 * A listening device is not automatically allowed to see what is on this one. Two devices that have
 * already paired are trusted and connect silently. Two that have not are asked to prove the user is
 * in the room: the ACCEPTING device shows a short code, the INITIATING device asks its user to type
 * it, and the accepting device checks the proof with [PairingCode]'s key derivation. The code never
 * travels -- only a salt and an AES-GCM proof of the derived key -- so a passive listener cannot
 * read it off the wire and a captured proof cannot be replayed into another session.
 *
 * ## Reverse links, and why the socket is symmetric
 *
 * Normally the sender initiates: you pick a display and it shows your screen or its next page.
 * Long-pressing a device means the opposite -- the device you pressed becomes the sender and the
 * device you are holding becomes the display. The direction is inverted, but the MODE is not:
 * the person who opened the link chose mimic or extend, so that choice travels in the hello and
 * the other end obeys it. The TCP connection is still opened by the initiator, but after the
 * handshake either end may announce a mirror on it. [Link.isSender] decides which end does.
 *
 * ## Leaving
 *
 * Closing the link closes the viewer, and the sender's capture service stops. The last frame stays
 * on screen until then rather than blanking the instant a phone is pocketed.
 */
object WirelessDisplay {

    private const val TAG = "PrismDisplay"

    const val DISCOVERY_PORT = 8096
    const val CONTROL_PORT = 8097

    /**
     * What the device that opened the link wants the other device to send.
     *
     * This travels on the hello rather than being read from the sender's own settings. A
     * long-press reverses the roles: the device the user is holding is the display, so ITS
     * choice of mimic or extend has to decide what the far side sends. Reading the far side's
     * setting instead made a phone long-pressed while it happened to be in extend mode answer
     * a request for its screen with nothing usable at all.
     */
    const val MODE_MIMIC = "mimic"
    const val MODE_EXTEND = "extend"

    /** Ten seconds: three missed beacons at the three-second interval. */
    private const val DISPLAY_LEASE_MS = 10_000L
    private const val BEACON_INTERVAL_MS = 3_000L

    /**
     * How long a verified control link may be silent before it is treated as dead.
     *
     * A mirror sends its frames on a separate socket, so the control link can sit idle for a
     * whole session without either end writing to it. A Wi-Fi drop does not always deliver a FIN,
     * and a link that stays "connected" forever is worse than no link: the one-link-at-a-time
     * rule then refuses every later request, which is a display that looks reachable and does
     * nothing. Three missed heartbeats is long enough to ride out a pause and short enough that a
     * user who reconnects after a lost network is not locked out.
     */
    private const val LINK_SILENCE_LIMIT_MS = 18_000L
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    /** How long a connected peer may leave a fresh handshake unanswered. */
    private const val HANDSHAKE_TIMEOUT_MS = 20_000L

    /** A screen on the network that will accept a mirror. */
    data class Display(
        val name: String,
        val ip: String,
        val platform: String,
        val port: Int,
        val lastSeen: Long,
        /** The Prism identity, so a paired device can be recognised before connecting. */
        val fingerprint: String = "",
    ) {
        /** True when the pairing list already confirms this device, now or under an old identity. */
        val trusted: Boolean
            get() = fingerprint.isNotBlank() &&
                runCatching { TrustedDevices.byFingerprint(fingerprint)?.confirmed == true }
                    .getOrDefault(false)
    }

    /**
     * The page an extending sender wants the display to show.
     *
     * The index is the page the receiver should render, already chosen by the sender: extend means
     * the page after the one on screen, and the last page is used at the end rather than a blank
     * screen that looks like a crash.
     */
    data class Show(
        val slot: String,
        val index: Int,
        val from: String,
        /**
         * The desktop's own page identity, when the sender has one.
         *
         * A [slot] is a launcher concept: several desktop pages deliberately share one (Nora,
         * diagnostics and the wallet extras all fall back to the desktop grid). A desktop
         * extending its rail therefore names the page as well, so the display gets that page
         * rather than a generic slot that happens to sit next to it.
         */
        val page: String? = null,
    )

    /**
     * A live screen stream on the sender.
     *
     * The host is only filled in on the receiving end, from the address the control link
     * actually reached. A sender does not get to name a different host and turn the receiver into
     * a client of some third machine.
     */
    data class Mirror(
        val host: String,
        val port: Int,
        val token: String,
        val width: Int,
        val height: Int,
        val from: String,
    )

    /** What this build calls itself in a beacon. Set at startup by each platform. */
    @Volatile
    var deviceName: () -> String = { "Prism device" }

    @Volatile
    var devicePlatform: () -> String = { "unknown" }

    /** Called on the RECEIVING side with the stream the other device is publishing. */
    @Volatile
    var onMirror: ((Mirror) -> Unit)? = null

    /** Called on the RECEIVING side when an extending sender names a page to show. */
    @Volatile
    var onShow: ((Show) -> Unit)? = null

    /**
     * Called on either side when the live stream ends.
     *
     * The flag is true when THIS device was the sender. A receiver closes its viewer; a sender
     * stops its capture service. Without the flag a phone that had been sending would open a
     * black fullscreen receiver window just to dismiss it again.
     */
    @Volatile
    var onMirrorStopped: ((localWasSender: Boolean) -> Unit)? = null

    /**
     * Called on the SENDING side once the link is verified.
     *
     * A desktop can start capturing immediately. Android uses this hook to announce a capture
     * service the user has already authorised; it deliberately does not ask for permission from
     * a background socket thread, which is not a prompt anyone can answer.
     */
    @Volatile
    var onStartMirror: (() -> Unit)? = null

    /**
     * Called on the SENDING side when EXTEND is accepted.
     *
     * A page is not portable data: the sender's desktop has its own icons, its Files page its own
     * folders. Sending a slot name and rendering it on the receiver showed the receiver's page
     * instead. A platform that can draw the page it is extending publishes it as a mirror stream
     * through this hook, so the display sees the sender's actual next page. When the hook is
     * absent the old slot message is used -- a system display on the same machine renders its own
     * page correctly, and a platform without an offscreen renderer still has a page to show.
     */
    @Volatile
    var onStartExtend: (() -> Unit)? = null

    /**
     * Called on the ACCEPTING device with the code its user should read out.
     *
     * The platform opens a full-screen code screen. It is not a dialog the user can miss: the code
     * is only useful while it is visible, and a notification can be dismissed unread.
     */
    @Volatile
    var onPairingCode: ((code: String, from: String, sending: Boolean, extending: Boolean) -> Unit)? = null

    /**
     * Called on the INITIATING device when the far side wants a code typed in.
     *
     * The platform shows a text prompt; the answer goes to [submitCode]. The far side's name is
     * included so the prompt can say whose screen to look at.
     */
    @Volatile
    var onChallenge: ((from: String) -> Unit)? = null

    /** Called on both ends when the handshake succeeded. */
    @Volatile
    var onAccepted: (() -> Unit)? = null

    /** Called when a code was wrong or the far side refused. */
    @Volatile
    var onRejected: ((reason: String) -> Unit)? = null

    /**
     * Called on the ACCEPTING device when the code screen should go away.
     *
     * A wrong proof, or a sender that hung up mid-pairing, leaves a code that will never be used.
     * Leaving it on a television is worse than a blank screen: it looks like something is still
     * waiting for input when nothing is.
     */
    @Volatile
    var onPairingFailed: (() -> Unit)? = null

    /** The stream this device is sending, cached until a verified link exists. */
    @Volatile
    private var outboundMirror: Mirror? = null

    /**
     * The link [outboundMirror] was announced on, or null when it was announced before any link.
     *
     * Without this, the cached stream outlives the connection it belonged to: the next link is
     * greeted with the old port, the old server has been shut down, and the display connects to
     * nothing. A mirror announced with no link yet is still sent to the first one that verifies.
     */
    @Volatile
    private var outboundMirrorLink: Link? = null

    /** Why the last connect or publish failed, when it did. */
    @Volatile
    var lastError: String? = null
        private set

    private val found = ConcurrentHashMap<String, Display>()

    private var started = false
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null

    /**
     * One control connection.
     *
     * A class rather than a handful of fields so a replaced connection cannot be half-installed:
     * [disconnect] closes the socket and clears the reference under one lock, and the reader loop
     * only acts while it is still the current link.
     */
    private class Link(
        val socket: Socket,
        val out: BufferedWriter,
        val remoteIp: String,
        val remoteName: String,
        val remoteFingerprint: String,
        /** True when the local device is the one sending its screen on this link. */
        val isSender: Boolean,
        /** True when the local device opened the socket (and therefore types the code). */
        val initiator: Boolean,
        /**
         * True when the far side asked THIS device to show its screen (a long-press request).
         *
         * A reverse link is a request for one specific thing, chosen by the requester: its
         * current screen in mimic mode or its next page in extend mode. The requester's mode is
         * carried in [requestedExtend] rather than inferred from this device's own setting.
         */
        val reverse: Boolean,
        /**
         * The transport the device that opened the link asked for, or null for an older peer.
         *
         * Null is why this is not a Boolean: an old sender in extend mode must still get its
         * own mode honoured on a normal link, while an old reverse link has to keep working as
         * the mimic it was built to be.
         */
        val requestedExtend: Boolean?,
    ) {
        @Volatile var verified = false
        @Volatile var closed = false

        /** Last time a line arrived. Used to notice a peer that vanished without a FIN. */
        @Volatile var lastSeen: Long = System.currentTimeMillis()

        /** Set on the accepting side while a code is outstanding. */
        @Volatile var salt: ByteArray? = null

        @Volatile var code: String? = null

        /** Set on the initiating side when a challenge arrives. */
        @Volatile var challengeSalt: ByteArray? = null
    }

    private var link: Link? = null
    private val linkLock = Any()

    /** The sender's pager, recorded even while no display is connected. */
    @Volatile
    private var lastSlots: List<String> = emptyList()

    @Volatile
    private var lastIndex: Int = 0

    /**
     * The sender's own page identities, parallel to [lastSlots], when its UI has them.
     *
     * A slot is not enough on the desktop because several rail pages share one. The list is
     * parallel so the PAGE AFTER the current one is named by its own identity, not by the current
     * page's: extending Nora to a display must not say "extend Nora again".
     */
    @Volatile
    private var lastPages: List<String> = emptyList()

    @Volatile
    private var connectedName: String = ""

    /** True when this device should extend rather than mirror. */
    fun isExtend(): Boolean =
        PrismSettings.getWirelessDisplayMode() == PrismSettings.WIRELESS_DISPLAY_EXTEND

    /** Screens heard from in the last few seconds, online first and by name. */
    fun displays(): List<Display> {
        val cutoff = System.currentTimeMillis() - DISPLAY_LEASE_MS
        return found.values
            .filter { it.lastSeen > cutoff }
            .sortedBy { it.name.lowercase() }
    }

    fun isConnected(): Boolean =
        synchronized(linkLock) { link?.let { it.verified && !it.closed } == true }

    /** True when this end of the current link is the one sending pages. */
    fun isSending(): Boolean =
        synchronized(linkLock) { link?.let { it.verified && it.isSender && !it.closed } == true }

    /** Which end will send once the handshake completes. Usable while a code is still pending. */
    fun willSend(): Boolean = synchronized(linkLock) { link?.isSender == true }

    fun connectedTo(): String = connectedName

    fun isAwaitingCode(): Boolean {
        val current = synchronized(linkLock) { link } ?: return false
        return current.initiator && current.challengeSalt != null && !current.verified
    }

    /**
     * Starts the beacon, the discovery listener and the control server.
     *
     * One call per process. Repeated calls return, because a second UDP socket on 8096 in the same
     * process would fail to bind and a second server on 8097 would throw -- and the feature must
     * not take the launcher down because a page was rebuilt.
     */
    fun install() {
        synchronized(this) {
            if (started) return
            started = true
        }

        thread(name = "prism-display-discovery", isDaemon = true) { discoveryLoop() }
        thread(name = "prism-display-server", isDaemon = true) { serverLoop() }
        thread(name = "prism-display-heartbeat", isDaemon = true) { heartbeatLoop() }
        PrismPlatform.log.info(TAG, "Wireless display ready on UDP $DISCOVERY_PORT / TCP $CONTROL_PORT")
    }

    // ── Sending ───────────────────────────────────────────────────────────

    /**
     * Connects to [display].
     *
     * [reverse] is the long-press case: this device becomes the DISPLAY and the far side sends its
     * pages. Either way this side initiated, so this side is the one that types the code.
     *
     * Returns a message for the page to show. The connection is asynchronous after this point --
     * the handshake arrives on the reader thread.
     */
    fun connect(display: Display, reverse: Boolean = false): String {
        // Quietly: replacing one link with the next is not the end of this device's mirror. If
        // this were the public disconnect, the capture service would be torn down at the exact
        // moment a reconnect asked it for frames.
        disconnectSession(notify = false)
        return runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(display.ip, display.port), 3_000)
            socket.tcpNoDelay = true
            runCatching { socket.keepAlive = true }
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val created = Link(
                socket = socket,
                out = out,
                remoteIp = display.ip,
                remoteName = display.name,
                remoteFingerprint = display.fingerprint,
                isSender = !reverse,
                initiator = true,
                reverse = reverse,
                requestedExtend = isExtend(),
            )
            synchronized(linkLock) {
                link = created
                connectedName = display.name
            }
            thread(name = "prism-display-reader", isDaemon = true) { readLoop(created, socket, null) }
            PrismSettings.setWirelessDisplayTarget(display.ip, display.name)
            send(created, JSONObject().apply {
                put("t", "hello")
                put("from", runCatching { deviceName() }.getOrDefault("Prism device"))
                put("fp", runCatching { TrustedDevices.localFingerprint() }.getOrDefault(""))
                put("reverse", reverse)
                put("mode", if (isExtend()) MODE_EXTEND else MODE_MIMIC)
            })
            PrismPlatform.log.info(
                TAG,
                "Connecting to ${display.name} at ${display.ip} " +
                    "(reverse=$reverse, ${if (isExtend()) "extend" else "mimic"})",
            )
            // A device that accepted the TCP connection and then said nothing used to leave the
            // picker on "Asking…" forever. There is no useful distinction between that and a
            // request the far side never saw, so say which one it was after a short wait. A
            // challenge is excluded: a person typing a code is allowed to take their time.
            thread(name = "prism-display-handshake-timeout", isDaemon = true) {
                Thread.sleep(HANDSHAKE_TIMEOUT_MS)
                val stalled = synchronized(linkLock) {
                    link === created && !created.verified && created.challengeSalt == null &&
                        !created.closed
                }
                if (!stalled) return@thread
                created.closed = true
                runCatching { socket.close() }
                synchronized(linkLock) {
                    if (link === created) {
                        link = null
                        connectedName = ""
                    }
                }
                val reason = "${display.name} did not answer. Make sure Prism is open there."
                lastError = reason
                PrismPlatform.log.warn(TAG, reason)
                runCatching { onRejected?.invoke(reason) }
            }
            if (reverse) {
                if (isExtend()) "Asked ${display.name} to show its next page here."
                else "Asked ${display.name} to show its screen here."
            } else {
                if (isExtend()) "Asking ${display.name} to accept this device's next page…"
                else "Asking ${display.name} to show this screen…"
            }
        }.getOrElse {
            lastError = it.message ?: it.javaClass.simpleName
            PrismPlatform.log.warn(TAG, "Could not connect to ${display.name}: $lastError")
            "Could not connect to ${display.name}: $lastError"
        }
    }

    /**
     * Answers a challenge on the initiating side.
     *
     * The key derivation is deliberately slow (see [PairingCode]), so it runs on a thread of its
     * own; the caller is a dialog that should not freeze while its button is pressed.
     */
    fun submitCode(code: String) {
        val active = synchronized(linkLock) { link } ?: return
        val salt = active.challengeSalt ?: return
        thread(name = "prism-display-proof", isDaemon = true) {
            val key = PairingCode.keyFor(code.trim(), salt)
            if (key == null) {
                onRejected?.invoke("The code could not be checked on this device.")
                return@thread
            }
            val proof = PairingCode.proofFor(key, salt)
            send(active, JSONObject().apply {
                put("t", "proof")
                put("proof", Base64.getEncoder().encodeToString(proof))
            })
        }
    }

    fun disconnect() {
        disconnectSession(notify = true)
    }

    private fun disconnectSession(notify: Boolean) {
        val old = synchronized(linkLock) {
            val current = link
            link = null
            connectedName = ""
            current
        }
        val wasVerified = old?.verified == true
        old?.let {
            it.closed = true
            runCatching { it.socket.close() }
        }
        PrismSettings.clearWirelessDisplayTarget()
        if (notify && wasVerified) runCatching { onMirrorStopped?.invoke(old?.isSender == true) }
    }

    /**
     * Publishes the local screen stream on a verified sender link.
     *
     * It is safe to call before the handshake completes: the stream is held here and sent the
     * moment the far side is verified. That is what lets a capture service start before the TCP
     * connect finishes without losing the first second of the mirror.
     */
    fun announceMirror(port: Int, token: String, width: Int, height: Int) {
        if (port <= 0 || token.isBlank() || width <= 0 || height <= 0) return
        val session = Mirror(
            host = "",
            port = port,
            token = token,
            width = width,
            height = height,
            from = runCatching { deviceName() }.getOrDefault("Prism device"),
        )
        outboundMirror = session
        val active = synchronized(linkLock) { link }
        outboundMirrorLink = active?.takeIf { it.isSender } ?: outboundMirrorLink
        if (active != null && active.verified && active.isSender && !active.closed) {
            sendMirror(active, session)
        }
    }

    /**
     * Tells the far side why it is waiting for something that will never arrive.
     *
     * A sender that cannot render a page, or a phone whose capture permission was refused from
     * a background prompt, used to fail only in its own log. The display sat on "Asking…"
     * forever, which is indistinguishable from a network that dropped the request.
     */
    fun reportError(reason: String) {
        val active = synchronized(linkLock) { link } ?: return
        if (!active.verified || active.closed) return
        send(active, JSONObject().apply {
            put("t", "display-error")
            put("reason", reason)
        })
    }

    /** Tells the far side the live stream is over. Does not close the control link. */
    fun stopMirror() {
        outboundMirror = null
        outboundMirrorLink = null
        val active = synchronized(linkLock) { link } ?: return
        if (!active.verified || !active.isSender || active.closed) return
        send(active, JSONObject().apply { put("t", "mirror-stop") })
    }

    /**
     * Records the sender's pager and, in EXTEND mode, tells the display which page to render.
     *
     * Called every time the pager settles, even with no display connected: a link that completes
     * later asks for the current page rather than waiting for the user to swipe.
     */
    fun notePage(slots: List<String>, index: Int, pages: List<String>? = null) {
        lastSlots = slots
        lastIndex = index.coerceAtLeast(0)
        lastPages = pages?.takeIf { it.size == slots.size } ?: emptyList()
        val active = synchronized(linkLock) { link }
        if (active != null && active.verified && active.isSender && !active.closed &&
            senderShouldExtend(active)
        ) {
            // A page stream redraws itself from [displayPage], so swiping the sender's pager is
            // picked up without another control message. Only the slot fallback needs telling.
            if (onStartExtend == null) sendShow(active)
        }
    }

    /**
     * Whether this end of [link] extends a page or streams the screen.
     *
     * The choice belongs to whoever opened the link, in both directions. On a normal link that
     * is this device (the sender). On a reverse link it is the far side: the person who
     * long-pressed this device said "show me your screen" or "show me your next page" from
     * THEIR picker, and that choice has to be obeyed here. Falling back to this device's own
     * setting was the bug that made a long-press ignore the selected mode entirely.
     */
    private fun senderShouldExtend(link: Link): Boolean =
        resolveExtend(link.reverse, link.requestedExtend, isExtend())

    /**
     * The direction rule, separated from the socket so it can be tested without a network.
     *
     * requestedExtend is the requester's choice. When it is present it wins in both directions;
     * when it is absent (an older peer) a normal link falls back to this device's setting and a
     * reverse link stays mimic, which is what older senders expect.
     */
    internal fun resolveExtend(
        reverse: Boolean,
        requestedExtend: Boolean?,
        localExtend: Boolean,
    ): Boolean = requestedExtend ?: if (reverse) false else localExtend

    /** The page the current mode would put on a display, or null before a pager is known. */
    fun displayPage(): Show? = chosenShow()

    private fun chosenShow(): Show? {
        if (lastSlots.isEmpty()) return null
        val index = lastIndex.coerceIn(0, lastSlots.lastIndex)
        // MIMIC is a live stream, so a page is only ever chosen for EXTEND. The page after the
        // one on screen is the extension; at the last page there is nothing further, and the last
        // page is used rather than a blank that looks like a failed connection.
        val chosen = (index + 1).coerceAtMost(lastSlots.lastIndex)
        return Show(
            slot = lastSlots[chosen],
            index = chosen,
            from = runCatching { deviceName() }.getOrDefault("Prism device"),
            page = lastPages.getOrNull(chosen),
        )
    }

    // ── Discovery ─────────────────────────────────────────────────────────

    private fun discoveryLoop() {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not listen for displays on $DISCOVERY_PORT", it)
            return
        }
        udp = socket
        val buffer = ByteArray(4096)

        while (true) {
            runCatching {
                broadcastBeacon(socket)
                socket.soTimeout = BEACON_INTERVAL_MS.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                acceptBeacon(packet)
            }.onFailure {
                if (it !is java.net.SocketTimeoutException) {
                    PrismPlatform.log.warn(TAG, "Display discovery: ${it.message}")
                }
            }
        }
    }

    private fun broadcastBeacon(socket: DatagramSocket) {
        val payload = JSONObject().apply {
            put("t", "wd")
            put("n", runCatching { deviceName() }.getOrDefault("Prism device"))
            put("p", CONTROL_PORT)
            put("plat", runCatching { devicePlatform() }.getOrDefault("unknown"))
            put("fp", runCatching { TrustedDevices.localFingerprint() }.getOrDefault(""))
        }.toString().toByteArray()

        MeshUtils.getBroadcastAddresses().forEach { address ->
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
            }
        }

        // BOTH BROADCAST AND DIRECT. On a network where the router filters directed broadcast
        // between wired and wireless clients, a beacon that is only broadcast is heard by nobody --
        // which is exactly what happened while testing this against a phone on Wi-Fi. Mesh peers
        // are already known addresses, so sending to them as well costs a few packets and makes
        // discovery work across that boundary and across a mesh overlay.
        runCatching {
            com.prism.core.MeshTransport.peers().forEach { peer ->
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(peer), DISCOVERY_PORT))
                }
            }
        }
    }

    private fun acceptBeacon(packet: DatagramPacket) {
        val ip = packet.address.hostAddress ?: return
        if (localAddresses().contains(ip)) return
        val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: return
        if (json.optString("t") != "wd") return
        val name = json.optString("n").ifBlank { ip }
        val port = json.optInt("p", CONTROL_PORT)
        found[ip] = Display(
            name = name,
            ip = ip,
            platform = json.optString("plat"),
            port = port,
            lastSeen = System.currentTimeMillis(),
            fingerprint = json.optString("fp"),
        )
    }

    private fun localAddresses(): Set<String> =
        runCatching { MeshUtils.getAllLocalIps() }.getOrDefault(emptySet())

    // ── Receiving ─────────────────────────────────────────────────────────

    private fun serverLoop() {
        val listener = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(CONTROL_PORT))
            }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not open the display receiver on $CONTROL_PORT", it)
            return
        }
        server = listener

        while (true) {
            val client = runCatching { listener.accept() }.getOrNull() ?: continue
            thread(name = "prism-display-client", isDaemon = true) {
                acceptClient(client)
            }
        }
    }

    /**
     * Reads the opening message and decides whether this is a trusted link or a challenge.
     *
     * The opening read happens before a [Link] exists because a device that just says "hello" and
     * stops is not a connection worth installing. Everything after it goes through [readLoop].
     */
    private fun acceptClient(client: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val opening = input.readLine() ?: run {
                client.close()
                return
            }
            val json = runCatching { JSONObject(opening) }.getOrNull()
            if (json == null || json.optString("t") != "hello") {
                client.close()
                return
            }

            val remoteIp = client.inetAddress.hostAddress ?: ""
            val remoteName = json.optString("from").ifBlank { remoteIp }
            val remoteFp = json.optString("fp")
            val reverse = json.optBoolean("reverse", false)
            // An older peer has no mode. A normal link then keeps this device's setting; a
            // reverse link stays the mimic it has always been.
            val requestedExtend = when (json.optString("mode")) {
                MODE_EXTEND -> true
                MODE_MIMIC -> false
                else -> null
            }

            // ONE LINK AT A TIME, BUT AN EXPLICIT REQUEST WINS. A newcomer may not knock a
            // working display off the air by connecting in a loop, so a normal second link is
            // refused while a live one exists. Two cases are not that: the same peer
            // reconnecting (its old socket can be half-dead while the heartbeat still sees it)
            // and a reverse long-press, which is a person explicitly asking this device to send
            // something now. Refusing those made a long-press look like it did nothing at all.
            val existing = synchronized(linkLock) { link }
            if (existing != null && existing.verified && !existing.closed) {
                val silence = System.currentTimeMillis() - existing.lastSeen
                val samePeer = existing.remoteIp == remoteIp
                if (!reverse && !samePeer && silence < LINK_SILENCE_LIMIT_MS) {
                    PrismPlatform.log.warn(TAG, "Refused a second display link while one is connected")
                    // Say WHY before hanging up. The initiator used to block on a read that only
                    // ended in EOF, so its screen said "Asking…" forever with nothing to act on.
                    runCatching {
                        val busyOut = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
                        busyOut.write(JSONObject().apply {
                            put("t", "busy")
                            put("from", runCatching { deviceName() }.getOrDefault("Prism device"))
                        }.toString())
                        busyOut.write("\n")
                        busyOut.flush()
                    }
                    client.close()
                    return
                }
                PrismPlatform.log.warn(
                    TAG,
                    "Replacing the display link" + (if (reverse) " for a new reverse request" else ""),
                )
                runCatching { existing.socket.close() }
            }

            // The accepting side is the display in a normal link and the sender in a reverse one.
            val isSender = reverse
            runCatching { client.keepAlive = true }
            val out = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
            val incoming = Link(
                socket = client,
                out = out,
                remoteIp = remoteIp,
                remoteName = remoteName,
                remoteFingerprint = remoteFp,
                isSender = isSender,
                initiator = false,
                reverse = reverse,
                requestedExtend = requestedExtend,
            )

            // A paired device is trusted WITHOUT a prompt, in both directions. Identity comes
            // first and the address is the fallback: a phone's DHCP lease changes, and a code
            // prompt because the router moved an already-paired device is a pairing that does not
            // mean anything. An address-only match still has to belong to a confirmed device.
            val trusted = runCatching {
                val known = remoteFp.takeIf { it.isNotBlank() }
                    ?.let { TrustedDevices.byFingerprint(it) }
                    ?: TrustedDevices.byIp(remoteIp)
                known != null && known.confirmed &&
                    (remoteFp.isBlank() || known.answersTo(remoteFp))
            }.getOrDefault(false)

            var displacedPendingCode = false
            synchronized(linkLock) {
                link?.let { previous ->
                    // If a newcomer displaces a link that was still showing a code, that code can
                    // no longer be answered; close its screen rather than leave it waiting.
                    displacedPendingCode = !previous.verified && previous.code != null
                    previous.closed = true
                    runCatching { previous.socket.close() }
                }
                link = incoming
                connectedName = remoteName
            }
            if (displacedPendingCode) runCatching { onPairingFailed?.invoke() }
            // The reader that consumed the greeting is the one that keeps reading. A second
            // BufferedReader over the same stream could already have swallowed the proof into
            // its buffer, leaving the next read waiting forever.
            thread(name = "prism-display-reader", isDaemon = true) { readLoop(incoming, client, input) }

            if (trusted) {
                incoming.verified = true
                send(incoming, JSONObject().apply { put("t", "accepted") })
                accepted(incoming)
            } else {
                val code = PairingCode.generate(PrismSettings.getPairingCodeLength())
                val salt = PairingCode.salt()
                incoming.code = code
                incoming.salt = salt
                // Shown on THIS device's screen. It goes no further than this process: only the
                // salt is sent, so somebody watching the network gets nothing to type.
                onPairingCode?.invoke(code, remoteName, isSender, senderShouldExtend(incoming))
                send(incoming, JSONObject().apply {
                    put("t", "challenge")
                    put("salt", Base64.getEncoder().encodeToString(salt))
                    put("from", runCatching { deviceName() }.getOrDefault("Prism device"))
                })
            }
        } catch (failure: Throwable) {
            PrismPlatform.log.warn(TAG, "Display client dropped: ${failure.message}")
            runCatching { client.close() }
        }
    }

    /**
     * Keeps the control link honest.
     *
     * Neither end writes to this socket while a mirror is running -- frames take the other
     * socket -- so a silent link is normal and silence alone proves nothing. Two things are
     * needed: a periodic ping that any live peer answers, and a close of a link that has stopped
     * answering. Without this, a peer that loses Wi-Fi leaves a verified link behind and every
     * later request is refused by the one-link rule.
     */
    private fun heartbeatLoop() {
        while (true) {
            Thread.sleep(HEARTBEAT_INTERVAL_MS)
            val active = synchronized(linkLock) { link } ?: continue
            if (!active.verified || active.closed) continue
            val silence = System.currentTimeMillis() - active.lastSeen
            if (silence > LINK_SILENCE_LIMIT_MS) {
                PrismPlatform.log.warn(TAG, "Display link silent for ${silence}ms; closing it")
                // Closing the socket wakes the reader, whose cleanup clears the link and tells
                // the UI that the mirror stopped. Marking it closed here would suppress that.
                runCatching { active.socket.close() }
                continue
            }
            send(active, JSONObject().apply { put("t", "ping") })
        }
    }

    private fun readLoop(link: Link, socket: Socket, existing: BufferedReader?) {
        try {
            val reader = existing
                ?: BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            reader.use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    handle(link, line)
                }
            }
        } catch (failure: Throwable) {
            if (!link.closed) PrismPlatform.log.warn(TAG, "Display link dropped: ${failure.message}")
        } finally {
            if (!link.closed) {
                link.closed = true
                val wasCurrent = synchronized(linkLock) {
                    if (this.link === link) {
                        this.link = null
                        connectedName = ""
                        true
                    } else {
                        false
                    }
                }
                runCatching { socket.close() }
                // A code that was on screen when the connection died can never be answered.
                if (!link.verified && link.code != null) {
                    runCatching { onPairingFailed?.invoke() }
                }
                // The side that STARTED the link is sitting on a status line until the handshake
                // completes. If the far side hangs up first -- busy, refused, or gone -- say so
                // instead of leaving "Asking…" on screen with nothing happening.
                if (!link.verified && link.initiator && wasCurrent) {
                    runCatching { onRejected?.invoke("The other device closed the connection.") }
                }
                // A mirror window must not outlive the stream. Only the side that was actually
                // showing something needs this; a pending code already has its own callback.
                if (link.verified && wasCurrent) {
                    runCatching { onMirrorStopped?.invoke(link.isSender) }
                }
            }
        }
    }

    private fun handle(link: Link, line: String) {
        link.lastSeen = System.currentTimeMillis()
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("t")) {
            "ping" -> send(link, JSONObject().apply { put("t", "pong") })

            "pong" -> Unit

            "busy" -> {
                // The far side is already showing something. It said so rather than dropping the
                // socket, so the user gets a reason instead of a spinner.
                onRejected?.invoke(
                    (json.optString("from").ifBlank { link.remoteName }) +
                        " is already connected to another display."
                )
                disconnect()
            }
            "challenge" -> {
                if (!link.initiator) return
                val salt = runCatching {
                    Base64.getDecoder().decode(json.optString("salt"))
                }.getOrNull() ?: return
                link.challengeSalt = salt
                onChallenge?.invoke(link.remoteName)
            }

            "proof" -> {
                if (link.initiator) return
                val code = link.code ?: return
                val salt = link.salt ?: return
                val proof = runCatching {
                    Base64.getDecoder().decode(json.optString("proof"))
                }.getOrNull() ?: return
                // The derivation is slow; this is already a reader thread, so it blocking here
                // only delays the next line on this one link.
                val key = PairingCode.keyFor(code, salt)
                val ok = key != null && PairingCode.verify(proof, key, salt)
                if (!ok) {
                    send(link, JSONObject().apply { put("t", "rejected") })
                    onRejected?.invoke("The code did not match.")
                    runCatching { onPairingFailed?.invoke() }
                    disconnect()
                    return
                }
                link.verified = true
                link.code = null
                link.salt = null
                send(link, JSONObject().apply { put("t", "accepted") })
                accepted(link)
            }

            "accepted" -> {
                link.verified = true
                link.challengeSalt = null
                accepted(link)
            }

            "rejected" -> {
                onRejected?.invoke("The other device refused the code.")
                disconnect()
            }

            "mirror" -> {
                // Only the display side receives a stream. Accepting one while this end is the
                // sender would let a display redirect the mirror back at itself.
                if (!link.verified || link.isSender) return
                val port = json.optInt("port", 0)
                val token = json.optString("token")
                if (port <= 0 || token.isBlank()) return
                val mirror = Mirror(
                    host = link.remoteIp,
                    port = port,
                    token = token,
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0),
                    from = json.optString("from").ifBlank { link.remoteName },
                )
                runCatching { onMirror?.invoke(mirror) }
                    .onFailure { PrismPlatform.log.warn(TAG, "Mirror receiver failed: ${it.message}") }
            }

            "mirror-stop" -> {
                if (!link.verified) return
                runCatching { onMirrorStopped?.invoke(link.isSender) }
            }

            "display-error" -> {
                if (!link.verified) return
                val reason = json.optString("reason")
                    .ifBlank { "The other device could not send its display." }
                runCatching { onRejected?.invoke(reason) }
                // The sender has already said it cannot serve this request. Keeping the link
                // would leave the picker looking connected to a display that will stay blank.
                disconnect()
            }

            "page" -> {
                if (!link.verified || link.isSender) return
                val slot = json.optString("slot")
                if (slot.isBlank()) return
                val show = Show(
                    slot = slot,
                    index = json.optInt("index", 0).coerceAtLeast(0),
                    from = json.optString("from").ifBlank { link.remoteName },
                    page = json.optString("page").takeIf { it.isNotBlank() },
                )
                runCatching { onShow?.invoke(show) }
                    .onFailure { PrismPlatform.log.warn(TAG, "Page receiver failed: ${it.message}") }
            }
        }
    }

    /**
     * The handshake completed.
     *
     * A sender starts pushing immediately; a display is told so its receiver window can open and
     * wait for the first page. Both callbacks are on this side only, so a UI can react.
     *
     * The mode is resolved once, from the link's requested mode, so a reverse long-press is able
     * to ask for the next page just as a normal click can.
     */
    private fun accepted(link: Link) {
        if (link.isSender) {
            if (senderShouldExtend(link)) {
                if (onStartExtend != null) {
                    // The page is rendered on THIS device and sent as frames. Sending just its
                    // slot would make the receiver assemble its own copy of that page, which is
                    // the wrong file list and the wrong set of apps.
                    PrismPlatform.log.info(TAG, "Display link accepted; streaming the next page")
                    runCatching { onStartExtend?.invoke() }
                } else {
                    PrismPlatform.log.info(TAG, "Display link accepted; extending a page")
                    sendShow(link)
                }
            } else {
                PrismPlatform.log.info(TAG, "Display link accepted; starting the screen stream")
                runCatching { onStartMirror?.invoke() }
                // Only a stream that belongs to this link, or one published before there was a
                // link at all, is re-announced. A stream from a previous link is gone even
                // though its cached descriptor is still here.
                val cached = outboundMirror
                if (cached != null && (outboundMirrorLink == null || outboundMirrorLink === link)) {
                    sendMirror(link, cached)
                }
            }
        }
        runCatching { onAccepted?.invoke() }
    }

    private fun sendShow(link: Link) {
        val show = chosenShow() ?: return
        send(link, JSONObject().apply {
            put("t", "page")
            put("slot", show.slot)
            put("index", show.index)
            put("from", show.from)
            show.page?.takeIf { it.isNotBlank() }?.let { put("page", it) }
        })
    }

    private fun sendMirror(link: Link, mirror: Mirror) {
        send(link, JSONObject().apply {
            put("t", "mirror")
            put("port", mirror.port)
            put("token", mirror.token)
            put("width", mirror.width)
            put("height", mirror.height)
            put("from", mirror.from)
        })
    }

    private fun send(link: Link, json: JSONObject) {
        if (link.closed) return
        runCatching {
            synchronized(link.out) {
                link.out.write(json.toString())
                link.out.write("\n")
                link.out.flush()
            }
        }.onFailure {
            lastError = it.message
            PrismPlatform.log.warn(TAG, "Display link write failed: ${it.message}")
            val wasVerified = link.verified
            val wasCurrent = link === synchronized(linkLock) { link }
            if (wasCurrent) {
                link.closed = true
                synchronized(linkLock) {
                    if (this.link === link) {
                        this.link = null
                        connectedName = ""
                    }
                }
                runCatching { link.socket.close() }
                // Closing the link closes the viewer. Nothing else will: the reader is about to
                // see a closed socket, but it skips its cleanup for a link already marked closed.
                if (wasVerified) runCatching { onMirrorStopped?.invoke(link.isSender) }
            }
        }
    }
}
