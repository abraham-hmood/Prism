package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.messaging.SmsRelay
import java.io.File

/**
 * Text messages across trusted devices, in both directions.
 *
 * ## What "both directions" means here, and why it is not symmetric
 *
 * A phone has a radio and a PC does not. So the two directions are different things:
 *
 *   INBOUND   the phone relays what it received, and the PC shows it. Both devices end up holding the
 *             same conversation.
 *   OUTBOUND  the PC has no way to send an SMS, so a reply typed there is sent to the PHONE, which puts
 *             it on the radio. The PC is a terminal for the phone's line rather than a second line.
 *
 * That asymmetry is the feature. Pretending a desktop could send a text itself would mean either a paid
 * gateway or a lie, and relaying it through the device that already has the line costs nothing.
 *
 * ## Why a reply is sent to ONE device
 *
 * Because the conversation belongs to a phone. [SmsRelay.Relayed.fromDevice] records which one it came
 * from, and the reply goes back to exactly that one -- broadcasting would have every paired phone send
 * the same text, which costs the user money and reaches the recipient three times.
 *
 * ## Why the backfill is capped
 *
 * A pairing sends the existing conversations so the other device is not empty until the next text
 * arrives. All of them would be tens of thousands of messages through a datagram transport; the most
 * recent [BACKFILL_LIMIT] is what a person actually looks at, and anything older is still on the phone.
 */
object TrustedMessages {

    private const val TAG = "PrismTrust"

    /** How many past messages a pairing sends. */
    const val BACKFILL_LIMIT = 500

    /** How many go in one datagram. Sized so a batch stays under the 16 KB mesh frame. */
    private const val BATCH = 25

    /** Payload kinds inside a MESSAGES share. */
    private const val TYPE_INBOUND = "in"
    private const val TYPE_OUTBOUND = "out"

    /**
     * How this platform puts a text on the radio. Android installs one; a desktop leaves it null.
     *
     * Returns an error to report, or null on success. A hook rather than a call into Android, for the
     * same reason every other platform difference in :core is a hook.
     */
    @Volatile
    var radioSender: ((address: String, body: String) -> String?)? = null

    /**
     * This device's own message history, where it has one. Installed per platform.
     *
     * Android reads the SMS provider; a desktop has nothing to read and leaves it null. A hook rather
     * than a branch, because :core cannot name a content provider.
     */
    @Volatile
    var historyProvider: (() -> List<SmsRelay.Relayed>)? = null

    /** Where the inbox lives on this platform. Installed at startup. */
    @Volatile
    var inboxRoot: () -> File = { File(PrismPlatform.host.dataDir(), "relay") }

    /** Called after anything lands, so a page can reload. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    // ── Sending ────────────────────────────────────────────────────────────

    /**
     * Sends everything this device already holds to a device that has just paired.
     *
     * Called from the confirmation hook. Batched, and each batch is a separate share so one oversized
     * conversation cannot stop the rest arriving.
     */
    fun backfill(fingerprint: String): Int {
        // The phone's own conversations come from the SMS provider, which only Android can read; every
        // device also has the relay inbox. Both, de-duplicated, because a phone that has relayed to one
        // PC already holds some of its own texts in the inbox as well.
        val fromDevice = runCatching { historyProvider?.invoke() }.getOrNull().orEmpty()
        val fromInbox = runCatching { SmsRelay.load(inboxRoot()) }.getOrDefault(emptyList())
        val existing = (fromDevice + fromInbox)
            .distinctBy { it.address + "|" + it.receivedAt + "|" + it.body.take(40) }
        if (existing.isEmpty()) return 0

        var sent = 0
        existing.sortedByDescending { it.receivedAt }.take(BACKFILL_LIMIT)
            .chunked(BATCH)
            .forEach { batch ->
                val ok = TrustedDevices.shareWith(
                    fingerprint,
                    TrustedDevices.Kind.MESSAGES,
                    JSONObject().apply {
                        put("t", TYPE_INBOUND)
                        put("msgs", encode(batch))
                    },
                )
                if (ok) sent += batch.size
            }
        if (sent > 0) PrismPlatform.log.info(TAG, "Sent $sent past messages to a new trusted device")
        return sent
    }

    /** Relays newly arrived texts to everyone paired for messages. */
    fun relay(messages: List<SmsRelay.Relayed>): Int {
        if (messages.isEmpty()) return 0
        return TrustedDevices.share(
            TrustedDevices.Kind.MESSAGES,
            JSONObject().apply {
                put("t", TYPE_INBOUND)
                put("msgs", encode(messages))
            },
        )
    }

    /**
     * Sends a reply the user typed here, through the device that owns the conversation.
     *
     * Stored locally first and marked outgoing, so the thread shows it immediately -- a reply that only
     * appeared once a phone confirmed it would look lost on a slow network. Returns false when there is
     * no device to send it through, which is the honest answer for a conversation whose phone is gone.
     */
    fun sendThrough(deviceFingerprint: String, address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false

        val local = SmsRelay.Relayed(
            address = address,
            body = body,
            receivedAt = System.currentTimeMillis(),
            threadKey = address,
            fromDevice = deviceFingerprint,
            outgoing = true,
        )
        runCatching { SmsRelay.store(inboxRoot(), listOf(local)) }
        onChanged?.invoke()

        val sent = TrustedDevices.shareWith(
            deviceFingerprint,
            TrustedDevices.Kind.MESSAGES,
            JSONObject().apply {
                put("t", TYPE_OUTBOUND)
                put("addr", address)
                put("body", body)
                put("at", local.receivedAt)
            },
        )
        if (!sent) {
            PrismPlatform.log.warn(TAG, "No trusted device could carry a reply to $address")
        }
        return sent
    }

    // ── Receiving ──────────────────────────────────────────────────────────

    /**
     * Registers the consumer. Called once at startup on both platforms.
     *
     * One consumer for both directions, because both are MESSAGES shares and which one it is depends on
     * the payload rather than on the transport. An inbound batch is stored; an outbound request is put on
     * the radio if this device has one.
     */
    fun install() {
        TrustedDevices.consume(TrustedDevices.Kind.MESSAGES) { received ->
            when (received.body.optString("t").ifBlank { TYPE_INBOUND }) {
                TYPE_OUTBOUND -> onOutboundRequest(received)
                else -> onInbound(received)
            }
        }
    }

    private fun onInbound(received: TrustedDevices.Received) {
        val batch = received.body.optJSONArray("msgs")
        val messages = if (batch != null) {
            decode(batch, received.fromName)
        } else {
            // A single message in the flat shape the first version of this sent. Still read, because a
            // phone on an older build is exactly the device somebody is pairing with.
            val body = received.body.optString("body")
            if (body.isBlank()) emptyList() else listOf(
                SmsRelay.Relayed(
                    address = received.body.optString("addr").ifBlank { received.fromName },
                    body = body,
                    receivedAt = received.body.optLong("at", System.currentTimeMillis()),
                    fromDevice = received.fromFingerprint,
                )
            )
        }
        if (messages.isEmpty()) return

        val stored = runCatching {
            SmsRelay.store(inboxRoot(), messages.map { it.copy(fromDevice = received.fromFingerprint) })
        }.getOrDefault(0)
        if (stored > 0) {
            PrismPlatform.log.info(TAG, "$stored message(s) from ${received.fromName}")
            onChanged?.invoke()
        }
    }

    /**
     * Another device asked this one to send a text on its behalf.
     *
     * ONLY A DEVICE THAT IS TRUSTED FOR MESSAGES gets here -- the share was already authorised -- and the
     * check that matters beyond that is whether this device even has a radio. A desktop that silently
     * dropped the request would leave the sender's thread showing a message that never went anywhere, so
     * the failure is logged and the message is still recorded as attempted.
     */
    private fun onOutboundRequest(received: TrustedDevices.Received) {
        val address = received.body.optString("addr")
        val body = received.body.optString("body")
        if (address.isBlank() || body.isBlank()) return

        val radio = radioSender
        if (radio == null) {
            PrismPlatform.log.warn(
                TAG,
                "${received.fromName} asked this device to text $address, but it has no radio",
            )
            return
        }

        val error = runCatching { radio(address, body) }.getOrElse { it.message ?: "send failed" }
        if (error != null) {
            PrismPlatform.log.error(TAG, "Could not text $address for ${received.fromName}: $error")
            return
        }

        // Recorded here too, so the phone's own thread shows what it sent on the PC's behalf.
        runCatching {
            SmsRelay.store(
                inboxRoot(),
                listOf(
                    SmsRelay.Relayed(
                        address = address,
                        body = body,
                        receivedAt = received.body.optLong("at", System.currentTimeMillis()),
                        threadKey = address,
                        fromDevice = TrustedDevices.localFingerprint(),
                        outgoing = true,
                    )
                ),
            )
        }
        PrismPlatform.log.info(TAG, "Sent a text to $address for ${received.fromName}")
        onChanged?.invoke()
    }

    // ── Wire shape ─────────────────────────────────────────────────────────

    private fun encode(messages: List<SmsRelay.Relayed>): JSONArray =
        JSONArray().also { array ->
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
        }

    private fun decode(array: JSONArray, fromName: String): List<SmsRelay.Relayed> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val body = item.optString("body")
            if (body.isBlank()) return@mapNotNull null
            SmsRelay.Relayed(
                address = item.optString("addr").ifBlank { fromName },
                body = body,
                receivedAt = item.optLong("at", System.currentTimeMillis()),
                threadKey = item.optString("thread").ifBlank { item.optString("addr") },
                outgoing = item.optBoolean("out", false),
            )
        }
}
