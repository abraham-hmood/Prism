package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.cloud.CloudVault
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64

/**
 * Text messages, relayed from a phone to its owner's other devices. PHASE 39.
 *
 * ## Why this is a feature and not a port
 *
 * There is nothing to port: the Android build does not do this. It exists because a PC genuinely cannot
 * read SMS — Windows exposes no API to an application and Linux needs a modem — while a phone that
 * already has the texts is already on the same meshnet. So the answer is not to find a missing API, it
 * is to move the messages.
 *
 * ## The encryption, and why the recovery phrase is the shared secret
 *
 * A relayed text crosses the mesh, so devices that are not the owner see the packet. The payload is
 * therefore sealed with a key derived from the wallet seed — [CloudVault.Purpose.MESSAGING] — which
 * makes it readable on the owner's other devices and nowhere else.
 *
 * That choice has a precondition worth stating plainly rather than discovering: BOTH DEVICES MUST HOLD
 * THE SAME RECOVERY PHRASE. A desktop with its own freshly created wallet derives a different key and
 * will receive packets it cannot open — which is correct behaviour, not a bug, and is exactly what
 * [Status.WRONG_WALLET] reports. Restoring the phone's phrase on the desktop is what pairs them, and it
 * is the same pairing the mesh cloud already requires.
 *
 * The alternative designs were a pairing code (another secret to manage, and one nobody writes down) or
 * trusting the mesh (which would mean every peer on the network reading your texts). Neither is better.
 *
 * ## What a peer that is not the owner can still see
 *
 * That a relay packet went past, how big it was, and when. Encryption does not hide traffic and this does
 * not claim to. What is inside — the sender, the body, the thread — is sealed.
 *
 * ## Why the transport here is a bare datagram and not the mesh service
 *
 * Because the mesh service is Android-only today (PHASE 48 moves it), and this needs to work on both
 * ends now. It speaks the SAME wire framing the mesh does — the `PRISM` header and a one-byte opcode on
 * UDP 8081 — so an Android sender can use [PrismMeshService] unchanged while a desktop listens with
 * [listen] below. When Phase 48 lands, the desktop side becomes one more opcode on the real service and
 * this listener is deleted; until then it is forty lines rather than a blocked phase.
 */
object SmsRelay {

    private const val TAG = "PrismSmsRelay"

    /**
     * The opcode, chosen from the range PrismMeshService leaves unused.
     *
     * 0x28 onwards was free when this was written; the science page ends at 0x27 and the compute market
     * occupies 0x20-0x23. Kept as a constant here so the Android dispatcher and the desktop listener
     * cannot disagree about it.
     */
    const val OPCODE_SMS: Byte = 0x28

    /** Same framing as the mesh: the header, then one opcode byte, then the payload. */
    private const val PROTOCOL_HEADER = "PRISM"

    const val DEFAULT_PORT = 8081

    /**
     * A relayed message.
     *
     * [threadKey] is the sender's address rather than the phone's internal thread id: the id means
     * nothing on another device, and grouping by address is what a reader actually wants.
     */
    data class Relayed(
        val address: String,
        val body: String,
        val receivedAt: Long,
        val threadKey: String = address,
        /** Which device sent it on, for a reader with two phones. */
        val fromDevice: String = "",
        /**
         * True when the user wrote it rather than received it.
         *
         * A relayed conversation is a conversation: a reader who can see what arrived but not what they
         * replied is reading half of it, and cannot tell an unanswered message from an answered one. The
         * field defaults to false so an inbox written before it existed loads as all-incoming, which is
         * what those messages were.
         */
        val outgoing: Boolean = false,
    )

    enum class Status {
        /** Listening, and able to decrypt. */
        READY,

        /** No wallet, so there is no key to decrypt with. */
        NO_WALLET,

        /** Packets are arriving but do not open — the other device has a different phrase. */
        WRONG_WALLET,

        /** Nothing has arrived yet. */
        IDLE,
    }

    // ── The envelope ───────────────────────────────────────────────────────

    /**
     * Seals a batch for the wire.
     *
     * A BATCH RATHER THAN ONE MESSAGE because a phone relaying its backlog would otherwise send a packet
     * per text, and UDP at that rate loses some of them silently. Batching also means the per-packet
     * overhead — nonce, tag, framing — is paid once for many messages.
     *
     * Returns null when there is no key, rather than sending plaintext. That is the one failure mode
     * this must never have.
     */
    fun seal(messages: List<Relayed>, fromDevice: String): ByteArray? {
        if (messages.isEmpty()) return null
        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING) ?: return null

        val json = JSONObject().apply {
            put("v", 1)
            put("device", fromDevice)
            put("sent", System.currentTimeMillis())
            put("msgs", JSONArray().also { array ->
                messages.forEach { message ->
                    array.put(
                        JSONObject().apply {
                            put("addr", message.address)
                            put("body", message.body)
                            put("at", message.receivedAt)
                            put("thread", message.threadKey)
                            put("out", message.outgoing)
                        }
                    )
                }
            })
        }
        return CloudVault.seal(json.toString().toByteArray(), key)
    }

    /** Opens a batch, or null when this device holds a different wallet. */
    fun open(sealed: ByteArray): List<Relayed>? {
        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING) ?: return null
        val plain = CloudVault.open(sealed, key) ?: return null
        return runCatching {
            val json = JSONObject(String(plain))
            val device = json.optString("device")
            val array = json.optJSONArray("msgs") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val body = item.optString("body")
                if (body.isBlank()) return@mapNotNull null
                Relayed(
                    address = item.optString("addr").ifBlank { "unknown" },
                    body = body,
                    receivedAt = item.optLong("at"),
                    threadKey = item.optString("thread").ifBlank { item.optString("addr") },
                    fromDevice = device,
                    outgoing = item.optBoolean("out", false),
                )
            }
        }.getOrNull()
    }

    /**
     * Wraps a sealed batch in the mesh's own framing.
     *
     * Identical to what `PrismMeshService.broadcastToOthers` produces, so the Android side can use the
     * real service and the desktop side can read either. Base64 because the mesh's payload field is a
     * string — the service was built for JSON gossip and giving it raw bytes would mean changing a
     * protocol every other subsystem already depends on.
     */
    fun frame(sealed: ByteArray): ByteArray {
        val payload = Base64.getEncoder().encodeToString(sealed).toByteArray()
        val out = ByteArray(PROTOCOL_HEADER.length + 1 + payload.size)
        PROTOCOL_HEADER.toByteArray().copyInto(out)
        out[PROTOCOL_HEADER.length] = OPCODE_SMS
        payload.copyInto(out, PROTOCOL_HEADER.length + 1)
        return out
    }

    /** The payload of a framed relay packet, or null when it is not one. */
    fun unframe(packet: ByteArray, length: Int): ByteArray? {
        val headerLength = PROTOCOL_HEADER.length
        if (length <= headerLength) return null
        if (String(packet, 0, headerLength) != PROTOCOL_HEADER) return null
        if (packet[headerLength] != OPCODE_SMS) return null
        val body = String(packet, headerLength + 1, length - headerLength - 1)
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }

    /** The base64 payload string, for a caller that is going through the real mesh service. */
    fun payloadString(sealed: ByteArray): String = Base64.getEncoder().encodeToString(sealed)

    fun fromPayloadString(payload: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(payload) }.getOrNull()

    // ── The inbox ──────────────────────────────────────────────────────────

    @Volatile private var lastPacketAt = 0L
    @Volatile private var failedToOpen = 0

    fun inboxFile(root: File): File = File(root, "relayed-sms.json")

    /**
     * Appends to the inbox, skipping anything already there.
     *
     * Deduplicated on address plus timestamp plus body. UDP delivers duplicates, a phone that restarts
     * re-relays its recent backlog, and two phones on one mesh may both forward a group message — all
     * three produce the same text twice, and a reader seeing every message doubled would conclude the
     * relay is broken.
     */
    fun store(root: File, messages: List<Relayed>): Int {
        if (messages.isEmpty()) return 0
        root.mkdirs()
        val existing = load(root)
        val seen = existing.map { it.address + "|" + it.receivedAt + "|" + it.body }.toHashSet()

        val fresh = messages.filter { seen.add(it.address + "|" + it.receivedAt + "|" + it.body) }
        if (fresh.isEmpty()) return 0

        // Newest last, and bounded. An unbounded relay inbox grows for as long as the mesh is up, and
        // this is a convenience view of messages that live on the phone rather than the record of them.
        val combined = (existing + fresh).sortedBy { it.receivedAt }.takeLast(MAX_KEPT)
        save(root, combined)
        return fresh.size
    }

    fun load(root: File): List<Relayed> {
        val file = inboxFile(root)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Relayed(
                    address = item.optString("addr"),
                    body = item.optString("body"),
                    receivedAt = item.optLong("at"),
                    threadKey = item.optString("thread"),
                    fromDevice = item.optString("device"),
                    outgoing = item.optBoolean("out", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(root: File, messages: List<Relayed>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("addr", message.address)
                    put("body", message.body)
                    put("at", message.receivedAt)
                    put("thread", message.threadKey)
                    put("device", message.fromDevice)
                    put("out", message.outgoing)
                }
            )
        }
        runCatching { inboxFile(root).writeText(array.toString()) }
    }

    fun threads(root: File): Map<String, List<Relayed>> =
        load(root).groupBy { it.threadKey.ifBlank { it.address } }

    fun clear(root: File) {
        runCatching { inboxFile(root).delete() }
    }

    // ── Receiving ──────────────────────────────────────────────────────────

    @Volatile private var listening = false
    @Volatile private var socket: DatagramSocket? = null

    val isListening: Boolean get() = listening

    /**
     * Listens for relay packets and stores what it can open.
     *
     * Blocking; callers give it a daemon thread. Binds with `reuseAddress` so it can share the mesh port
     * with the real mesh service on a machine that eventually runs both — without it, whichever started
     * second would fail to bind and report a port conflict rather than a relay that is not listening.
     *
     * Everything that is not a relay packet is ignored silently. This port carries the whole mesh gossip
     * protocol, so the overwhelming majority of what arrives is somebody else's opcode, and logging those
     * would fill the log with correct behaviour.
     */
    fun listen(root: File, port: Int = DEFAULT_PORT, onMessages: (Int) -> Unit = {}) {
        if (listening) return
        listening = true

        val bound = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
        }.getOrElse {
            listening = false
            PrismPlatform.log.error(TAG, "Could not bind UDP $port for the SMS relay", it)
            return
        }
        socket = bound
        PrismPlatform.log.info(TAG, "SMS relay listening on UDP $port")

        val buffer = ByteArray(64 * 1024)
        try {
            while (listening) {
                val packet = DatagramPacket(buffer, buffer.size)
                // Not runCatching: a `break` inside an inline lambda is still an experimental feature,
                // and a closed socket has to end the loop rather than spin on a failing receive.
                try {
                    bound.receive(packet)
                } catch (e: Exception) {
                    break
                }

                val sealed = unframe(packet.data, packet.length) ?: continue
                lastPacketAt = System.currentTimeMillis()

                val messages = open(sealed)
                if (messages == null) {
                    // A relay packet that will not open is the wrong-wallet case, and it is worth
                    // counting rather than dropping: it is the difference between "nothing is arriving"
                    // and "your two devices hold different recovery phrases", which need different
                    // answers from the user.
                    failedToOpen++
                    continue
                }
                val added = store(root, messages)
                if (added > 0) onMessages(added)
            }
        } finally {
            listening = false
            runCatching { bound.close() }
            socket = null
        }
    }

    fun stop() {
        listening = false
        runCatching { socket?.close() }
    }

    // ── Sending ────────────────────────────────────────────────────────────

    /**
     * Broadcasts a batch directly, for a platform with no mesh service.
     *
     * Android should NOT use this — it has `PrismMeshService.broadcastToOthers`, which knows the peers
     * and reaches them through the tunnel. This is the subnet-broadcast fallback, which works on a plain
     * LAN and not across a routed mesh.
     */
    fun broadcast(messages: List<Relayed>, fromDevice: String, port: Int = DEFAULT_PORT): Boolean {
        val sealed = seal(messages, fromDevice) ?: return false
        val framed = frame(sealed)
        return runCatching {
            DatagramSocket().use { out ->
                out.broadcast = true
                out.send(
                    DatagramPacket(
                        framed, framed.size,
                        InetAddress.getByName("255.255.255.255"), port,
                    )
                )
            }
            true
        }.getOrDefault(false)
    }

    // ── Status ─────────────────────────────────────────────────────────────

    fun status(): Status = when {
        !CloudVault.isReady() -> Status.NO_WALLET
        failedToOpen > 0 && lastPacketAt > 0 -> Status.WRONG_WALLET
        lastPacketAt > 0 -> Status.READY
        else -> Status.IDLE
    }

    /** One line for the Messages page. */
    fun desktopStatus(): String = when (status()) {
        Status.NO_WALLET ->
            "Not receiving: this machine has no wallet, so there is no key to decrypt a relay with. " +
                "Restore your phone's recovery phrase here to pair them."
        Status.WRONG_WALLET ->
            "Relay packets are arriving but will not open — this machine holds a different recovery " +
                "phrase than the phone sending them. Restore the phone's phrase to pair them."
        Status.READY ->
            "Receiving. " + if (isListening) "Listening on UDP $DEFAULT_PORT." else "Listener is stopped."
        Status.IDLE ->
            if (isListening) {
                "Listening on UDP $DEFAULT_PORT — nothing relayed yet. Turn the relay on in Prism on " +
                    "your phone."
            } else {
                "Not listening."
            }
    }

    /** Bounded: this is a convenience view of messages that live on the phone, not the record of them. */
    private const val MAX_KEPT = 2000
}
