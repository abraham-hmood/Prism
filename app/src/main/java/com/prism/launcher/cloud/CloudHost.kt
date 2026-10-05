package com.prism.launcher.cloud

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import java.io.File
import java.io.OutputStream

/**
 * This device holding other people's data, and hosting their games.
 *
 * ## What is stored, and what this device can tell about it
 *
 * Ciphertext, under a name that is the hash of the ciphertext. Nothing else. This device cannot tell
 * which file a chunk belongs to, what position it holds in it, whose it is, or what it contains — the
 * chunk arrives with no metadata because there is none to send. That is the property that makes it
 * reasonable to hold a stranger's data at all: it is not possible to be complicit in what you cannot
 * read, and it is not possible to be blackmailed into producing it either.
 *
 * ## Why the quota is enforced here and not only in the UI
 *
 * Because this is a network route. A peer can POST whatever it likes as often as it likes, and a
 * quota that lived only in the settings screen would be a suggestion. Every store checks the quota
 * against what is already held, and a store that would exceed it is refused with the reason, so the
 * uploader can go elsewhere rather than retrying forever.
 *
 * ## Gaming
 *
 * A game session is not streamed by this code — it is *arranged* by it. The peer asks for a title, this
 * device starts it in the virtualisation container it already has (Wine for Windows titles, the VM for
 * everything else), and answers with the port its display is on. The client then connects to that port
 * with the RFB client Prism already uses for its own VMs, which is why the frames and the input work at
 * all: it is the same code path that drives virtualisation locally, pointed across the mesh.
 *
 * The honest limits of that: RFB over Wi-Fi is fine for a strategy game or an emulator and visibly not
 * fine for anything that needs sixty frames a second, because Raw-encoded rectangles are uncompressed
 * pixels. Nothing here pretends otherwise, and the panel says what it is before the user starts.
 */
object CloudHost {

    private const val TAG = "PrismCloudHost"

    fun handles(path: String): Boolean = path == CloudWire.PATH_PUT ||
        path == CloudWire.PATH_GET ||
        path == CloudWire.PATH_DROP ||
        path == CloudWire.PATH_CONTROL

    fun serve(context: Context, path: String, body: ByteArray, output: OutputStream) {
        runCatching {
            when (path) {
                CloudWire.PATH_PUT -> put(context, body, output)
                CloudWire.PATH_GET -> get(context, body, output)
                CloudWire.PATH_DROP -> drop(context, body, output)
                CloudWire.PATH_CONTROL -> control(context, body, output)
                else -> refuse(output, "No such cloud route.")
            }
        }.onFailure {
            PrismLogger.logError(TAG, "Serving $path failed", it)
            runCatching { refuse(output, "Failed: ${it.message}") }
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun put(context: Context, body: ByteArray, output: OutputStream) {
        if (!PrismSettings.getCloudSellStorage()) {
            refuse(output, "This device is not selling storage.")
            return
        }

        val newline = body.indexOfFirst { it == '\n'.code.toByte() }
        if (newline <= 0) {
            refuse(output, "Expected a chunk id, a newline, then the chunk.")
            return
        }
        val id = String(body, 0, newline).trim()
        if (!CloudWire.validId(id)) {
            refuse(output, "That is not a chunk id.")
            return
        }
        val sealed = body.copyOfRange(newline + 1, body.size)
        if (sealed.isEmpty()) {
            refuse(output, "Empty chunk.")
            return
        }

        // The id is verified against the bytes. It is the storage key and the retrieval key, so a peer
        // that could name one id and store different bytes could make a later fetch return the wrong
        // chunk — which the downloader would see as a decryption failure and blame on its own wallet.
        if (CloudVault.hashOf(sealed) != id) {
            refuse(output, "Chunk does not match its id.")
            return
        }

        val target = File(CloudStorage.hostedRoot(context), id)
        if (target.isFile && target.length() == sealed.size.toLong()) {
            // Already held. Answered as success rather than as a duplicate: from the uploader's side
            // "you have it" and "you now have it" are the same fact, and it is what makes a retried
            // upload cheap.
            ok(output, JSONObject().apply { put("ok", true); put("held", true) })
            return
        }

        if (sealed.size > CloudStorage.freeForOthers(context)) {
            ok(
                output,
                JSONObject().apply {
                    put("ok", false)
                    put("reason", "quota")
                    put("free", CloudStorage.freeForOthers(context))
                }
            )
            return
        }

        target.writeBytes(sealed)
        PrismLogger.logInfo(
            TAG,
            "Holding ${sealed.size / 1024} KB for a peer · ${CloudStorage.hostedCount(context)} chunks total",
        )
        ok(output, JSONObject().apply { put("ok", true) })
    }

    private fun get(context: Context, body: ByteArray, output: OutputStream) {
        val id = String(body).trim()
        if (!CloudWire.validId(id)) {
            ok(output, JSONObject().apply { put("ok", false); put("reason", "bad id") })
            return
        }
        val file = File(CloudStorage.hostedRoot(context), id)
        if (!file.isFile) {
            ok(output, JSONObject().apply { put("ok", false); put("reason", "not held") })
            return
        }
        // Touched so an actively-read chunk is the last thing evicted if the quota is ever lowered.
        file.setLastModified(System.currentTimeMillis())
        raw(output, file.readBytes())
    }

    private fun drop(context: Context, body: ByteArray, output: OutputStream) {
        val id = String(body).trim()
        if (!CloudWire.validId(id)) {
            ok(output, JSONObject().apply { put("ok", false) })
            return
        }
        // Not authenticated, and that is a considered decision rather than an oversight. Knowing a
        // chunk id is knowing the hash of ciphertext you were given, so only somebody who had the
        // chunk can ask for it to be dropped — and the worst a malicious dropper achieves is deleting
        // a copy that the owner's repair pass will re-place. The alternative, proving ownership, would
        // mean the peer learning something linking chunks to a person, which is the thing the design
        // is built to avoid.
        val deleted = File(CloudStorage.hostedRoot(context), id).delete()
        ok(output, JSONObject().apply { put("ok", deleted) })
    }

    // ── Control ────────────────────────────────────────────────────────────

    private fun control(context: Context, body: ByteArray, output: OutputStream) {
        val json = runCatching { JSONObject(String(body)) }.getOrNull()
        if (json == null) {
            refuse(output, "Body is not JSON.")
            return
        }
        when (json.optString("op")) {
            CloudWire.OP_OFFER -> ok(
                output,
                JSONObject().apply {
                    put("ok", true)
                    put("storage", PrismSettings.getCloudSellStorage())
                    put("free", CloudStorage.freeForOthers(context))
                    put("gaming", CloudGaming.hostingEnabled())
                    put("games", JSONArray().also { array ->
                        CloudGaming.hostableTitles(context).forEach { array.put(it) }
                    })
                }
            )

            CloudWire.OP_GAME_LIST -> ok(
                output,
                JSONObject().apply {
                    put("ok", true)
                    put("games", JSONArray().also { array ->
                        CloudGaming.hostableTitles(context).forEach { array.put(it) }
                    })
                }
            )

            CloudWire.OP_GAME_START -> {
                val port = CloudGaming.startHosting(
                    context,
                    json.optString("title"),
                    json.optInt("w", 1280),
                    json.optInt("h", 720),
                )
                ok(
                    output,
                    JSONObject().apply {
                        put("ok", port != null)
                        if (port != null) put("port", port) else put("reason", CloudGaming.lastRefusal())
                    }
                )
            }

            CloudWire.OP_GAME_STOP -> {
                CloudGaming.stopHosting(context)
                ok(output, JSONObject().apply { put("ok", true) })
            }

            else -> refuse(output, "Unknown operation.")
        }
    }

    // ── Responses ──────────────────────────────────────────────────────────

    private fun ok(output: OutputStream, json: JSONObject) {
        val body = json.toString().toByteArray()
        write(output, 200, "application/json", body)
    }

    private fun raw(output: OutputStream, body: ByteArray) {
        write(output, 200, "application/octet-stream", body)
    }

    private fun refuse(output: OutputStream, message: String) {
        write(output, 400, "text/plain; charset=utf-8", message.toByteArray())
    }

    private fun write(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        output.write(
            ("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Server: PrismMesh/1.0-Cloud\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }
}
