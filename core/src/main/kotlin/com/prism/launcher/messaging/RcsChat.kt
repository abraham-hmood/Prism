package com.prism.launcher.messaging

import com.prism.core.MeshCore
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.cloud.CloudVault
import java.util.Base64
import java.util.UUID

/**
 * Prism's own rich messaging channel, carried over the mesh.
 *
 * ## Why this is not the carrier's RCS
 *
 * Android exposes no RCS send API to a third-party application. Messaging over RCS is owned by the
 * carrier's IMS stack and, in practice, by the system messaging app; a launcher can open that app
 * and nothing more. Pretending otherwise -- a switch that says "RCS" and quietly hands the thread to
 * Google Messages -- is what this replaces.
 *
 * What Prism can do itself is carry rich messages over the transport it already owns. That is this
 * class: a first-class thread in [MessagingStore], sent to every peer on the mesh, grouped when the
 * conversation has several participants, and recorded on both ends. It needs no provider account, no
 * API key and no webhook, which is the part of RCS the user actually asked for.
 *
 * ## Who can read one
 *
 * The payload is sealed with the wallet's MESSAGING key, the same key trusted-device texts use. It
 * is therefore readable on devices that hold the same recovery phrase and nowhere else. That is a
 * deliberate boundary rather than an oversight: an arbitrary Prism peer has published no identity
 * key this build can encrypt to, so claiming end-to-end encryption to a stranger would be false. The
 * Messages page says "your Prism devices" instead of implying it reaches any phone number.
 *
 * ## Wire shape
 *
 * One JSON envelope per message, base64 of the sealed bytes, on [OPCODE_RCS]. The envelope carries
 * the conversation id so a reply lands in the same thread on both sides, and the message id so a
 * duplicated broadcast cannot appear twice.
 */
object RcsChat {

    const val APP_ID = "rcs"

    /**
     * Chosen from the unused range above the social and call opcodes. A value below 0x0C is refused
     * by [MeshCore] because those are the control-plane opcodes.
     */
    const val OPCODE_RCS: Byte = 0x3A

    /** Called after a message lands, so an open page can refresh. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Whether this device can seal and open a Prism RCS message at all. */
    fun isReady(): Boolean = CloudVault.keyFor(CloudVault.Purpose.MESSAGING) != null

    /**
     * Registers the desktop transport's handler. Android dispatches the opcode itself and calls
     * [onPacket] from `PrismMeshService`, because its mesh has its own socket.
     *
     * Safe to call twice: [MeshCore.register] treats re-registering the same handler as an install
     * that ran again rather than a conflict.
     */
    fun install() {
        MeshCore.register(OPCODE_RCS) { _, payload ->
            onPacket(payload)
            true
        }
    }

    /**
     * Sends [text] into [thread].
     *
     * Returns a failure rather than appending to the store when there is no key or no peer. A
     * message that appears in the thread but never left the device is worse than an error: the user
     * would have no way to tell it apart from one that was delivered.
     */
    fun send(thread: MessagingStore.Thread, text: String): MessagingApi.SendResult {
        val body = text.trim()
        if (body.isBlank()) return MessagingApi.SendResult.Failed("Nothing to send.")
        // One datagram carries this, and the mesh frame is 16 KB. A longer message would fail at the
        // transport with an error that cannot be acted on; refusing here names the limit.
        if (body.toByteArray(Charsets.UTF_8).size > 3_000) {
            return MessagingApi.SendResult.Failed("Prism RCS messages are limited to about 3,000 characters.")
        }
        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING)
            ?: return MessagingApi.SendResult.Failed(
                "Prism RCS needs the wallet: messages are sealed with its recovery phrase.",
            )
        if (MeshTransport.peerCount() <= 0) {
            return MessagingApi.SendResult.Failed(
                "No Prism device is on the mesh right now. Turn the mesh on, or connect the other " +
                    "device, and send again.",
            )
        }

        val me = PrismSettings.getLykeUserName().ifBlank {
            PrismSettings.getPrismNodeName().ifBlank { "Prism" }
        }
        val recipients = if (thread.group) {
            thread.participants.ifEmpty { listOf(remoteOf(thread)) }
        } else {
            listOf(remoteOf(thread))
        }.filter { it.isNotBlank() }.distinct()

        val envelope = JSONObject().apply {
            put("v", 1)
            put("id", UUID.randomUUID().toString())
            put("convo", thread.id)
            put("fromUser", me)
            put("fromName", me)
            put("title", thread.title)
            put("group", thread.group)
            put("participants", JSONArray(thread.participants.toMutableList<Any?>()))
            put("to", JSONArray(recipients.toMutableList<Any?>()))
            put("body", body)
            put("at", System.currentTimeMillis())
        }

        val sealed = runCatching {
            CloudVault.seal(envelope.toString().toByteArray(Charsets.UTF_8), key)
        }.getOrElse { error ->
            PrismPlatform.log.error(TAG, "Could not seal an RCS message", error)
            return MessagingApi.SendResult.Failed("The message could not be sealed: ${error.message}")
        }
        val payload = Base64.getEncoder().encodeToString(sealed)
        runCatching { MeshTransport.announce(OPCODE_RCS, payload) }
            .onFailure { error ->
                PrismPlatform.log.error(TAG, "Could not broadcast an RCS message", error)
                return MessagingApi.SendResult.Failed("The mesh refused the message: ${error.message}")
            }
        return MessagingApi.SendResult.Sent("Sent over the Prism mesh")
    }

    /**
     * Records one received envelope.
     *
     * The conversation id comes from the sender, so a reply from this device stays in the thread the
     * sender opened. A message that will not open is somebody else's wallet and is ignored without a
     * log line per packet -- on a shared mesh that would be an error for every peer that is not the
     * owner, which is the normal case rather than a failure.
     */
    fun onPacket(payload: String) {
        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING) ?: return
        val sealed = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return
        val plain = CloudVault.open(sealed, key) ?: return
        val json = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return

        val body = json.optString("body")
        if (body.isBlank()) return
        val fromUser = json.optString("fromUser").ifBlank { "Prism" }
        val messageId = json.optString("id")
        val conversation = json.optString("convo").ifBlank { "$APP_ID:$fromUser" }
        val group = json.optBoolean("group", false)
        val at = json.optLong("at", System.currentTimeMillis())
        val title = if (group) {
            json.optString("title").ifBlank { "Prism group" }
        } else {
            fromUser
        }
        val participants = json.optJSONArray("participants").strings()
            .plus(json.optJSONArray("to").strings())
            .plus(fromUser)
            .filter { it.isNotBlank() && it != PrismSettings.getLykeUserName() }
            .distinct()

        MessagingStore.thread(conversation)?.let { existing ->
            // A group name can arrive on the first message of a thread the user started from the
            // other side; keep the name rather than reverting to a single participant.
            if (group && existing.title.isBlank()) {
                MessagingStore.upsert(existing.copy(title = title, group = true))
            }
        } ?: MessagingStore.upsert(
            MessagingStore.Thread(
                id = conversation,
                app = APP_ID,
                title = title,
                group = group,
                participants = participants,
                lastAt = at,
                snippet = "",
                unread = 0,
            )
        )

        val stored = MessagingStore.append(
            MessagingStore.Message(
                id = UUID.randomUUID().toString(),
                threadId = conversation,
                app = APP_ID,
                author = fromUser,
                body = body,
                at = at,
                outgoing = false,
                remoteId = messageId,
            )
        )
        if (stored) {
            PrismPlatform.log.info(TAG, "RCS message from $fromUser in $conversation")
            onChanged?.invoke()
        }
    }

    /**
     * The other participant in a direct RCS thread.
     *
     * The thread id is the sorted pair of both people, so it cannot be read as one address. The
     * participants list is authoritative; the id is only split as a fallback for a thread whose
     * participants were not stored.
     */
    private fun remoteOf(thread: MessagingStore.Thread): String {
        val me = PrismSettings.getLykeUserName()
        return thread.participants.firstOrNull { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
            ?: thread.id.removePrefix("$APP_ID:").split("~")
                .firstOrNull { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
                .orEmpty()
    }

    private fun JSONArray?.strings(): List<String> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private const val TAG = "PrismRcs"
}
