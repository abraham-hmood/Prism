package com.prism.launcher.helper

import com.prism.core.MeshCore
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * Help requests on the mesh.
 *
 * ## Why the posts travel and the bills do not
 *
 * The request that goes on the wire is the author's own description: a name, a reason, an amount and
 * a percentage. The PDF stays on the device that uploaded it, because a bill is a document with an
 * address, an account number and sometimes a diagnosis on it -- publishing it to every peer on the
 * network so that strangers can see "username + amount" is exactly the trade this feature must not
 * make. Verification happens before the post exists, on the uploading device, which is also the only
 * device that has the file.
 *
 * ## One hop, deliberately
 *
 * A device that hears a request records it and does not forward it. Forwarding needs a relay TTL and a
 * seen-cache to stop two peers trading the same payload forever, and every device that reloads the
 * page re-announces its own posts, so a mesh of the size this runs on converges without it. The honest
 * limit: a request posted by a device two hops away is invisible until one of those hops reloads.
 *
 * ## Contributions are announcements, not transfers
 *
 * A contribution message says "I paid this request this much, here is the tx id". The money moved over
 * USDC before the message was sent, and every peer can check the tx id against the chain. The message
 * exists so the progress bar moves for everyone without a server, not so that anyone is trusted to
 * have paid.
 */
object HelpMesh {

    private const val TAG = "PrismHelper"

    /** A request, or a batch of them, from the device that posted it. */
    const val OPCODE_REQUESTS: Byte = 0x60

    /** A payment somebody made, so peers can move their progress bars. */
    const val OPCODE_CONTRIBUTIONS: Byte = 0x61

    /**
     * Registers both opcodes with [MeshCore].
     *
     * Desktop hosts call this from the same place they install the rest of the paid market. Android's
     * own mesh service has its own dispatch and calls [ingest] directly, so it does not use this --
     * registering here as well would put handlers on a socket that is not the one Android listens on.
     */
    fun install(): Boolean {
        MeshCore.register(OPCODE_REQUESTS) { peerIp, payload ->
            ingest(peerIp, payload)
            true
        }
        MeshCore.register(OPCODE_CONTRIBUTIONS) { peerIp, payload ->
            ingest(peerIp, payload)
            true
        }
        return true
    }

    /** Announces this device's own open requests. Called when the page opens and after a post. */
    fun announceMine() {
        if (!MeshTransport.isOnMesh()) return
        val mine = HelpStore.mine().filter { !it.funded }
        if (mine.isEmpty()) return
        MeshTransport.announce(OPCODE_REQUESTS, requestsPayload(mine))
        PrismPlatform.log.info(TAG, "Announced ${mine.size} open help request(s)")
    }

    /** Sends one just-posted request, so peers see it without waiting for a reload. */
    fun publish(request: HelpRequest) {
        if (!MeshTransport.isOnMesh()) return
        MeshTransport.announce(OPCODE_REQUESTS, requestsPayload(listOf(request)))
    }

    /** Says a payment happened, so every peer can add it to the same progress bar. */
    fun announceContribution(requestId: String, contribution: HelpContribution) {
        if (!MeshTransport.isOnMesh()) return
        val payload = JSONObject().apply {
            put("request", requestId)
            put("contribution", contribution.toJson())
        }.toString()
        MeshTransport.announce(OPCODE_CONTRIBUTIONS, payload)
    }

    /** A peer's packet. Safe to call from the mesh dispatch thread: it only writes the store. */
    fun ingest(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val requests = json.optJSONArray("requests")
            if (requests != null) {
                val incoming = (0 until requests.length()).mapNotNull { index ->
                    requests.optJSONObject(index)?.let { HelpRequest.fromJson(it) }
                }
                // A request learned from a peer never carries a local file path, and the merge keeps
                // the peer as the author of everything it said.
                val changed = HelpStore.merge(incoming.filter { it.authorId != HelpIdentity.id() })
                if (changed > 0) {
                    PrismPlatform.log.info(TAG, "Recorded $changed request(s) from $peerIp")
                }
                return@runCatching
            }

            val requestId = json.optString("request", "")
            val contribution = json.optJSONObject("contribution")
            if (requestId.isNotBlank() && contribution != null) {
                val parsed = HelpContribution.fromJson(contribution, System.currentTimeMillis())
                    ?: return@runCatching
                HelpStore.addContribution(requestId, parsed)
            }
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Bad help packet from $peerIp: ${it.message}")
        }
    }

    private fun requestsPayload(requests: List<HelpRequest>): String {
        val array = JSONArray()
        for (request in requests) array.put(request.toJson())
        return JSONObject().apply { put("requests", array) }.toString()
    }
}
