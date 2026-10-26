package com.prism.launcher.clipboard

import com.prism.core.MeshConnect
import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.MeshModels
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving a copied file or folder between trusted devices.
 *
 * ## Why a zip and not a stream of files
 *
 * A clipboard entry is ONE thing even when it is a folder of four hundred, so the receiver needs
 * one request that returns the whole item. A directory listing followed by N downloads would need
 * a protocol for partial failure -- what does "the folder arrived" mean when three of its files
 * did? -- and the zip already answers it: the transfer either produced a complete archive or it
 * did not. It also makes a CUT honest: the sender deletes the source only after the archive has
 * been written to the socket.
 *
 * ## Why the bytes come over TCP
 *
 * A trust share is a single datagram. File contents are not, for the same reason an APK is not:
 * they are larger than the datagram budget by orders of magnitude. So the clipboard share carries
 * the metadata and this route carries the bytes, exactly as [com.prism.launcher.trusted.TrustedAppTransfer]
 * does for apps.
 *
 * ## Why authorisation is checked per request
 *
 * The route is reachable by anything that can open the tunnel. Serving whoever asks because the
 * entry exists would hand any peer on the mesh every file the user ever copied, so the check is
 * "did this device agree to receive clipboard data TO this address", evaluated here rather than
 * trusted from the sender.
 */
object TrustedFileTransfer {

    private const val TAG = "PrismClipboard"

    /** The prefix on the AI domain, alongside `/model/` and `/trusted-app/`. */
    const val PATH = "/trusted-file/"

    @Volatile
    private var cacheRoot: File? = null

    /** Where temporary archives are staged. Installed by each platform at startup. */
    fun install(cache: File) {
        cacheRoot = cache.apply { mkdirs() }
    }

    private fun cache(): File? = cacheRoot?.apply { mkdirs() }

    // ── Serving ────────────────────────────────────────────────────────────

    /**
     * Answers `GET /trusted-file/<id>` for a peer.
     *
     * Returns true when it handled the request, including a refusal. A path that is not this route
     * returns false so a host can try its own.
     */
    fun serve(peerIp: String, requestPath: String, output: OutputStream): Boolean {
        if (!requestPath.startsWith(PATH)) return false

        val id = requestPath.removePrefix(PATH).substringBefore('?').trim('/')
        if (id.isBlank()) {
            respond(output, 400, "Name a clipboard entry.")
            return true
        }

        // OUTGOING, not incoming: the question is whether this device agreed to SEND clipboard
        // data to that address. A route that served anyone would expose every copied file.
        val authorised = TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD)
            .any { it.lastIp == peerIp }
        if (!authorised) {
            PrismPlatform.log.warn(TAG, "Refused a file request from $peerIp -- not a clipboard recipient")
            respond(output, 403, "This device has not agreed to send you clipboard data.")
            return true
        }

        val entry = ClipboardHistory.byId(id)
        if (entry == null || entry.kind != ClipboardHistory.Kind.FILE || entry.remote || entry.path.isBlank()) {
            respond(output, 404, "That entry is not a file on this device.")
            return true
        }

        val source = File(entry.path)
        if (!source.exists()) {
            respond(output, 404, "That file is no longer there.")
            return true
        }

        val cache = cache() ?: run {
            respond(output, 500, "This device has nowhere to stage the archive.")
            return true
        }
        val archive = File(cache, "clip-" + UUID.randomUUID() + ".zip")
        val written = runCatching { zip(source, archive) }
        if (written.isFailure) {
            archive.delete()
            PrismPlatform.log.warn(TAG, "Could not archive ${source.name}: " + written.exceptionOrNull()?.message)
            respond(output, 500, "Could not archive that item.")
            return true
        }

        var sent = false
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/zip\r\n" +
                    "Content-Length: ${archive.length()}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            archive.inputStream().use { it.copyTo(output, 64 * 1024) }
            output.flush()
            sent = true
        }
        archive.delete()

        // A CUT is a move, and the move happens when the bytes have actually left. Deleting on the
        // intent instead would lose the item for a recipient whose download failed.
        if (sent && entry.mode == ClipboardHistory.Mode.CUT) {
            runCatching { source.deleteRecursively() }
            ClipboardHistory.remove(entry.id)
            PrismPlatform.log.info(TAG, "Moved ${source.name} to $peerIp")
        } else if (sent) {
            PrismPlatform.log.info(TAG, "Sent ${source.name} to $peerIp")
        }
        return true
    }

    // ── Fetching ───────────────────────────────────────────────────────────

    /**
     * Downloads a remote entry into [destinationDir]. Blocking; call it off the main thread.
     *
     * Returns the extracted top-level files, or null on failure. A partial archive leaves nothing:
     * `MeshConnect.get` writes through a `.part` file, and extraction only starts once it has been
     * renamed, so a failed transfer cannot leave half a folder behind that looks complete.
     */
    fun fetch(entry: ClipboardHistory.Entry, destinationDir: File): List<File>? {
        if (!entry.remote) return null
        val peer = TrustedDevices.byFingerprint(entry.sourceFingerprint) ?: run {
            PrismPlatform.log.warn(TAG, "No trusted device for " + entry.sourceName + "; cannot fetch " + entry.name)
            return null
        }
        if (peer.lastIp.isBlank()) return null

        val cache = cache() ?: return null
        val archive = File(cache, "incoming-" + UUID.randomUUID() + ".zip")
        val loaded = MeshConnect.get(
            peer.lastIp,
            MeshModels.HOST_DOMAIN,
            PATH + entry.id,
            into = archive,
            timeoutMs = 300_000,
            maxResponseBytes = 2L * 1024 * 1024 * 1024,
        ) != null
        if (!loaded) {
            archive.delete()
            PrismPlatform.log.warn(TAG, "Could not fetch ${entry.name} from ${peer.name}")
            return null
        }

        val extracted = runCatching { unzip(archive, destinationDir) }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not unpack ${entry.name}: " + it.message)
            null
        }
        archive.delete()
        if (extracted.isNullOrEmpty()) return null

        // The entry is local now. Replacing it rather than adding a second row means the paste the
        // user just did does not leave the same item twice in the history.
        ClipboardHistory.replace(
            entry.copy(
                path = extracted.first().absolutePath,
                directory = extracted.first().isDirectory,
                bytes = if (extracted.first().isFile) extracted.first().length() else 0,
                sourceName = entry.sourceName,
            )
        )
        PrismPlatform.log.info(TAG, "Received ${entry.name} from ${peer.name}")
        return extracted
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    /**
     * Writes [source] into a zip whose entries are relative to it.
     *
     * The top-level name is included, so a folder arrives as a folder rather than dumping its
     * contents into the destination.
     */
    private fun zip(source: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            fun add(file: File, name: String) {
                if (file.isDirectory) {
                    out.putNextEntry(ZipEntry(if (name.endsWith("/")) name else "$name/"))
                    out.closeEntry()
                    file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                        add(child, name.trimEnd('/') + "/" + child.name)
                    }
                } else {
                    out.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    out.closeEntry()
                }
            }
            add(source, source.name)
        }
    }

    /**
     * Unpacks [archive] inside [destination].
     *
     * EVERY ENTRY IS RESOLVED AND CHECKED TO BE INSIDE THE DESTINATION. The names in an archive
     * are attacker-supplied in the general case, and `../` in a zip is the oldest trick there is;
     * refusing the entry rather than sanitising it means a hostile peer cannot quietly choose a
     * different name for a file the user asked to receive.
     */
    private fun unzip(archive: File, destination: File): List<File> {
        destination.mkdirs()
        val root = destination.canonicalFile
        val topLevel = LinkedHashSet<File>()

        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (name.isBlank() || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    PrismPlatform.log.warn(TAG, "Refused an archive entry named $name")
                    continue
                }
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    PrismPlatform.log.warn(TAG, "Refused an archive entry escaping the folder: $name")
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> copy(input, output) }
                }
                topLevel.add(File(root, name.substringBefore('/')))
            }
        }
        return topLevel.filter { it.exists() }
    }

    /** Copies the current stream entry only; the next `nextEntry` delimits it. */
    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        runCatching {
            output.write(
                ("HTTP/1.1 $code Error\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.write(body)
            output.flush()
        }
    }
}
