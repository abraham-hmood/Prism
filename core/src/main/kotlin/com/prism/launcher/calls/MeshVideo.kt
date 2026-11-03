package com.prism.launcher.calls

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.launcher.cloud.CloudVault
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The picture half of a mesh video call: small JPEG frames over the same UDP mesh as the audio.
 *
 * ## Why JPEG and not a video codec
 *
 * A codec needs a keyframe to start from, so one lost datagram corrupts every frame until the next
 * keyframe arrives; a JPEG frame is complete on its own, and losing one loses exactly one frame. At
 * the sizes a call tile needs (about 320x240) a frame is 8-20 KB, which at eight frames a second is
 * well inside what a LAN carries. It also needs nothing either platform does not already ship:
 * Android's YuvImage encodes it and both platforms decode it.
 *
 * ## Chunks
 *
 * Both receivers read 16 KB datagrams, so a frame is split into pieces of at most [MAX_CHUNK]
 * base64 characters, numbered, and reassembled by sequence. A newer frame from the same sender
 * replaces a half-arrived older one: a late frame is a frame nobody wants to see any more.
 *
 * ## Sealed like the audio
 *
 * A link between trusted devices seals each frame with the wallet's calls key before it is split;
 * a link to an untrusted device is plain, and the call screen already says so. Frames are accepted
 * only from the peer of a live video call or a joined member of a live group call -- a camera feed
 * is not something to render because an arbitrary address sent it.
 *
 * Wire format, one datagram: `v1|seq|index|count|rotation|` followed by a base64 piece.
 */
object MeshVideo {

    private const val TAG = "PrismVideo"

    /** 0x36: free on both platforms (0x35 is trusted presence, 0x3A the remote pointer). */
    const val OPCODE_VIDEO: Byte = 0x36

    /** Base64 characters per datagram; with the header this stays well under 16 KB. */
    private const val MAX_CHUNK = 11_000

    /** A frame larger than this is not a call tile and is not reassembled. */
    private const val MAX_FRAME_BYTES = 256 * 1024

    /** One decoded-ready frame from one sender. [rotation] is how far to turn it to stand it up. */
    class Frame(val peerIp: String, val jpeg: ByteArray, val rotation: Int, val at: Long)

    private val listeners = CopyOnWriteArrayList<(Frame) -> Unit>()

    fun addListener(listener: (Frame) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (Frame) -> Unit) {
        listeners.remove(listener)
    }

    private val sequence = AtomicLong(System.currentTimeMillis())

    /** Opening and decoding happen off the socket thread, one frame at a time, in order. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mesh-video-open").apply { isDaemon = true }
    }

    private class Partial(val seq: Long, val count: Int, val rotation: Int) {
        val pieces = arrayOfNulls<String>(count)
        var received = 0
    }

    private val partials = ConcurrentHashMap<String, Partial>()
    private val newestSeq = ConcurrentHashMap<String, Long>()

    // ── Who a frame goes to, and who it may come from ──────────────────────

    /** Where this device's camera is going right now: address and whether the link is sealed. */
    fun recipients(): List<Pair<String, Boolean>> {
        val call = MeshCalls.active
        if (call != null && call.video && call.state == MeshCalls.State.ACTIVE) {
            return listOf(call.peerIp to call.trusted)
        }
        val group = MeshGroupCalls.current
        if (group != null && group.video && group.state == MeshGroupCalls.GroupState.ACTIVE) {
            return group.joined().map { it.ip to it.trusted }
        }
        return emptyList()
    }

    /** Whether [peerIp] may send this device video, and whether its link is sealed. Null if not. */
    private fun linkFrom(peerIp: String): Boolean? {
        val call = MeshCalls.active
        if (call != null && call.state == MeshCalls.State.ACTIVE && call.peerIp == peerIp) {
            return call.trusted
        }
        val group = MeshGroupCalls.current
        if (group != null && group.state == MeshGroupCalls.GroupState.ACTIVE) {
            val member = group.member(peerIp) ?: return null
            if (member.state == MeshGroupCalls.MemberState.JOINED) return member.trusted
        }
        return null
    }

    // ── Sending ────────────────────────────────────────────────────────────

    /**
     * Sends one JPEG frame to everyone the current call says should see it.
     *
     * [rotation] is the clockwise turn the receiver applies to stand the picture up -- the camera
     * sensor is mounted sideways on almost every phone, and rotating on the sender would mean
     * decoding and re-encoding every frame on the device that is already busiest.
     */
    fun send(jpeg: ByteArray, rotation: Int): Int {
        val targets = recipients()
        if (targets.isEmpty() || jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES) return 0
        val seq = sequence.incrementAndGet()
        val plain by lazy { Base64.getEncoder().encodeToString(jpeg) }
        var sent = 0
        for ((ip, trusted) in targets) {
            // Sealed per recipient: each trusted pair has its own link key now (see PairLink).
            val body = if (trusted) {
                val key = MeshCalls.peerKey(ip) ?: continue
                Base64.getEncoder().encodeToString(CloudVault.seal(jpeg, key))
            } else plain
            val count = (body.length + MAX_CHUNK - 1) / MAX_CHUNK
            for (index in 0 until count) {
                val start = index * MAX_CHUNK
                val piece = body.substring(start, minOf(body.length, start + MAX_CHUNK))
                runCatching {
                    MeshTransport.sendToPeer(ip, OPCODE_VIDEO, "v1|$seq|$index|$count|$rotation|$piece")
                }
            }
            sent++
        }
        return sent
    }

    // ── Receiving ──────────────────────────────────────────────────────────

    /** Hands one datagram from either platform's transport to the reassembler. */
    fun receive(peerIp: String, payload: String) {
        val trusted = linkFrom(peerIp) ?: return
        if (!payload.startsWith("v1|")) return
        // Header fields are split off the front only: the base64 piece itself never contains '|'.
        val parts = payload.split('|', limit = 6)
        if (parts.size != 6) return
        val seq = parts[1].toLongOrNull() ?: return
        val index = parts[2].toIntOrNull() ?: return
        val count = parts[3].toIntOrNull() ?: return
        val rotation = parts[4].toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
        if (count !in 1..32 || index !in 0 until count) return

        val newest = newestSeq[peerIp] ?: Long.MIN_VALUE
        if (seq < newest) return
        val partial = partials[peerIp]?.takeIf { it.seq == seq }
            ?: Partial(seq, count, rotation).also {
                partials[peerIp] = it
                newestSeq[peerIp] = seq
            }
        if (partial.count != count) return
        if (partial.pieces[index] == null) {
            partial.pieces[index] = parts[5]
            partial.received++
        }
        if (partial.received < partial.count) return
        partials.remove(peerIp, partial)

        val body = buildString { partial.pieces.forEach { append(it) } }
        worker.execute {
            val raw = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return@execute
            // The same rule as group audio: the key first, plain only on a link that may be plain.
            val jpeg = MeshCalls.openLink(peerIp, trusted, raw) ?: return@execute
            val frame = Frame(peerIp, jpeg, partial.rotation, System.currentTimeMillis())
            for (listener in listeners) {
                runCatching { listener(frame) }
                    .onFailure { PrismPlatform.log.warn(TAG, "A video listener threw: ${it.message}") }
            }
        }
    }

    /** Forgets half-arrived frames; called when a call ends so the next one starts clean. */
    fun reset() {
        partials.clear()
        newestSeq.clear()
    }
}
