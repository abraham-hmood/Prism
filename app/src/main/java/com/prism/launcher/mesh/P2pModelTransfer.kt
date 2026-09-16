package com.prism.launcher.mesh

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.vpn.PrismSocket
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * Moving a purchased model from the peer that sold it.
 *
 * ## The same channel inference already uses
 *
 * There is no new protocol here. Peer model hosting already reaches a seller over [PrismSocket]'s
 * PRISM_CONNECT handshake into port 8080, tagged with the reserved
 * [P2pModelRegistry.MODEL_HOST_DOMAIN] marker so the proxy dispatches to the AI host rather than
 * the web host. Inference sends `POST /generate`; a transfer sends `GET /model/<name>` down the
 * identical pipe. Reusing it means peer discovery, NAT traversal and the tunnel come for free, and
 * there is one transport to keep working rather than two.
 *
 * ## Why the length header matters
 *
 * The response is streamed rather than buffered -- a model is gigabytes and will not fit in memory
 * -- so the reader needs to know when the body ends. `Content-Length` is what says so, and a
 * transfer that arrives without one is refused instead of being written to disk as a file of
 * unknown truncation.
 */
object P2pModelTransfer {

    private const val PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 15_000

    /**
     * How long one read may stall before the transfer is called dead.
     *
     * Applies per read rather than to the transfer as a whole, so a model of any size is fine for
     * as long as bytes keep arriving. Generous because the first read waits for the seller to
     * locate and open the file, which on a phone holding gigabytes on slow storage is not instant
     * -- while an infinite timeout would pin the download thread forever against a peer that went
     * away mid-transfer without closing its end.
     */
    private const val READ_TIMEOUT_MS = 60_000

    /** Where downloaded models land. Shared with the store's own downloads. */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun destinationFor(context: Context, modelName: String): File =
        File(modelsDir(context), modelName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /**
     * Fetches a model, reporting progress as a fraction when the size is known.
     *
     * Blocking; call it off the main thread. Returns the file on success, null otherwise.
     */
    fun download(
        context: Context,
        listing: P2pModelListings.Listing,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? {
        var socket: PrismSocket? = null
        val partial = File(destinationFor(context, listing.name).absolutePath + ".part")
        return try {
            socket = PrismSocket()
            socket.setHostHint(P2pModelRegistry.MODEL_HOST_DOMAIN)
            socket.connect(InetSocketAddress(listing.peerIp, PORT), CONNECT_TIMEOUT_MS)
            // Set AFTER connect, because the PRISM_CONNECT handshake runs inside connect() and
            // manages its own much shorter timeout. This is the one every read of the response
            // actually uses.
            socket.soTimeout = READ_TIMEOUT_MS

            val request = "GET /model/${listing.name} HTTP/1.1\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }

            val input = socket.getInputStream()
            val length = readHeadersAndLength(input) ?: run {
                PrismLogger.logWarning(
                    "ModelShop",
                    "Peer ${listing.peerIp} sent no Content-Length for ${listing.name}; refusing " +
                        "a transfer that cannot be checked for truncation."
                )
                return null
            }

            partial.outputStream().use { out -> copy(input, out, length, onProgress) }

            val finished = destinationFor(context, listing.name)
            if (finished.exists()) finished.delete()
            if (!partial.renameTo(finished)) {
                PrismLogger.logWarning("ModelShop", "Could not finalise ${finished.name}")
                return null
            }
            PrismLogger.logInfo("ModelShop", "Downloaded ${listing.name} (${finished.length()} bytes)")
            finished
        } catch (e: Exception) {
            PrismLogger.logError("ModelShop", "Transfer of ${listing.name} failed", e)
            partial.delete()
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Reads the status line and headers, returning the declared body length. */
    private fun readHeadersAndLength(input: InputStream): Long? {
        var length: Long? = null
        var ok = false
        var line = readLine(input) ?: return null
        ok = line.contains(" 200 ")
        while (true) {
            line = readLine(input) ?: break
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                length = lower.substringAfter(':').trim().toLongOrNull()
            }
        }
        return if (ok) length else null
    }

    /** A header line, read a byte at a time so the body is left untouched in the stream. */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(b.toChar())
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        var lastReport = 0L
        while (written < total) {
            val want = minOf(buffer.size.toLong(), total - written).toInt()
            val n = input.read(buffer, 0, want)
            if (n <= 0) break
            out.write(buffer, 0, n)
            written += n
            // Reporting every chunk would post thousands of UI updates on a large model.
            if (written - lastReport > 1_000_000L || written == total) {
                lastReport = written
                onProgress(written, total)
            }
        }
        out.flush()
        if (written < total) {
            throw java.io.IOException("Transfer ended early: $written of $total bytes")
        }
    }
}
