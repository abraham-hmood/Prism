package com.prism.launcher

import com.prism.core.PrismPlatform
import java.io.File
import java.net.URLEncoder

/**
 * Browsing a configured network location.
 *
 * WHAT THE JDK GIVES US FOR FREE, AND WHAT IT DOES NOT. `java.net.URL` has an FTP handler built
 * in, so listing and downloading over FTP needs no dependency at all -- an `ftp://` URL ending in
 * a slash returns a directory listing, and one ending in a filename returns the file. That covers
 * the protocol most of these shares actually use.
 *
 * WebDAV is HTTP with a PROPFIND verb, which `HttpURLConnection` refuses to send (it validates
 * the method against a fixed list). Supporting it properly means either reflection against
 * HttpURLConnection's internals -- fragile and blocked on modern JDKs -- or an HTTP client that
 * allows arbitrary verbs. Prism already depends on OkHttp for the mesh, so that is the route when
 * it is done; it is not done here, and [browse] says so rather than returning an empty folder.
 *
 * `.p2p` shares route through the mesh tunnel, which is Phase 47-49 and not on desktop yet.
 *
 * DIRECTORY LISTINGS ARE PARSED FROM UNIX `ls -l` OUTPUT, which is what essentially every FTP
 * server emits regardless of its host OS, because that is what the informal convention settled
 * on. Windows-style listings ("01-02-24  10:30AM  <DIR> name") are handled too, since IIS still
 * produces them.
 */
object NetworkStorage {

    private const val TAG = "Prism/netstorage"

    /** One entry in a remote listing. */
    data class RemoteEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long = 0,
        /** Full URL of this entry, ready to browse or download. */
        val url: String,
    )

    sealed interface Result {
        data class Listing(val entries: List<RemoteEntry>) : Result
        data class Unsupported(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Lists a path on a configured storage.
     *
     * @param path relative to the storage root, "" for the root itself.
     */
    fun browse(storage: PrismSettings.NetworkStorage, path: String = ""): Result =
        when (storage.protocol.lowercase()) {
            "ftp" -> browseFtp(storage, path)
            "webdav", "http", "https" -> Result.Unsupported(
                "WebDAV needs an HTTP client that can send PROPFIND; HttpURLConnection refuses " +
                    "the verb. Not implemented yet."
            )
            "p2p" -> Result.Unsupported(
                "P2P shares route through the mesh tunnel, which is not on desktop yet."
            )
            else -> Result.Unsupported("Unknown protocol \"${storage.protocol}\".")
        }

    /** The URL for a storage plus a relative path, credentials included when configured. */
    fun urlFor(storage: PrismSettings.NetworkStorage, path: String, directory: Boolean): String {
        val credentials = if (storage.username.isNotBlank()) {
            val u = URLEncoder.encode(storage.username, "UTF-8")
            val p = URLEncoder.encode(storage.password, "UTF-8")
            "$u:$p@"
        } else ""
        val clean = path.trim('/')
        val suffix = when {
            clean.isEmpty() -> "/"
            directory -> "/$clean/"
            else -> "/$clean"
        }
        // ";type=d" tells the FTP handler this is a directory listing rather than a file. Without
        // it a directory URL can be fetched as a zero-byte file on some servers.
        val typeHint = if (directory) ";type=d" else ""
        return "${storage.protocol.lowercase()}://$credentials${storage.host}:${storage.port}$suffix$typeHint"
    }

    private fun browseFtp(storage: PrismSettings.NetworkStorage, path: String): Result {
        val url = urlFor(storage, path, directory = true)
        return try {
            val connection = java.net.URL(url).openConnection().apply {
                connectTimeout = 8_000
                readTimeout = 12_000
            }
            val text = connection.getInputStream().bufferedReader().use { it.readText() }
            val entries = text.lineSequence()
                .mapNotNull { parseListingLine(it) }
                .filter { it.name != "." && it.name != ".." }
                .map { entry ->
                    val childPath = if (path.isBlank()) entry.name else "${path.trimEnd('/')}/${entry.name}"
                    entry.copy(url = urlFor(storage, childPath, entry.isDirectory))
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
            Result.Listing(entries)
        } catch (e: Exception) {
            PrismPlatform.log.warn(TAG, "FTP listing failed for ${storage.host}: ${e.message}")
            Result.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Parses one line of a directory listing.
     *
     * Returns null for anything unrecognized -- servers emit banners, totals and blank lines, and
     * treating those as filenames produces a listing full of junk entries.
     */
    internal fun parseListingLine(raw: String): RemoteEntry? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("total ")) return null

        // Unix: "drwxr-xr-x 2 owner group 4096 Jan 2 10:30 name with spaces"
        if (line.length > 10 && (line[0] == 'd' || line[0] == '-' || line[0] == 'l')) {
            val parts = line.split(Regex("\\s+"), limit = 9)
            if (parts.size >= 9) {
                return RemoteEntry(
                    name = parts[8],
                    isDirectory = line[0] == 'd',
                    size = parts.getOrNull(4)?.toLongOrNull() ?: 0,
                    url = "",
                )
            }
            return null
        }

        // Windows/IIS: "01-02-24  10:30AM       <DIR>          name" or "... 1234 name"
        val windows = Regex("""^\d{2}-\d{2}-\d{2,4}\s+\d{2}:\d{2}(AM|PM)?\s+(<DIR>|\d+)\s+(.+)$""")
            .find(line)
        if (windows != null) {
            val marker = windows.groupValues[2]
            return RemoteEntry(
                name = windows.groupValues[3].trim(),
                isDirectory = marker == "<DIR>",
                size = marker.toLongOrNull() ?: 0,
                url = "",
            )
        }

        return null
    }

    /**
     * Downloads a remote file to a local destination.
     *
     * Streams rather than buffering: these are shares, and a share holds things too large to hold
     * in memory.
     */
    fun download(url: String, destination: File): Boolean = try {
        destination.parentFile?.mkdirs()
        java.net.URL(url).openStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        true
    } catch (e: Exception) {
        PrismPlatform.log.error(TAG, "Download failed: $url", e)
        false
    }
}
