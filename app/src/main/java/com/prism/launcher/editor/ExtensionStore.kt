package com.prism.launcher.editor

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Finds, installs and tracks VS Code extensions.
 *
 * ## Where they come from
 *
 * Open VSX. Microsoft's own marketplace terms permit its use only from Microsoft's products, so
 * every other VS Code-derived editor -- VSCodium, Gitpod, Theia -- uses Open VSX, which carries the
 * same `.vsix` packages published by the same authors. A `.vsix` is a zip with a manifest, so
 * installing one is a download and an unpack.
 *
 * ## Which host an extension gets, and why that is decided here
 *
 * An extension's manifest says what it was built for. `browser` means it runs in the Web Worker
 * host -- no Node, no filesystem. `main` means it needs Node, and Prism has one: [NodeRuntime], a
 * real Node.js in the app's own process. Both are installable, and the manifest alone decides which
 * host loads it. An extension with neither is declarative -- a theme, a grammar, a snippet set --
 * and contributes through its manifest with nothing to run.
 *
 * The one case still refused is a `main`-only extension on a device with no Node runtime (an ABI
 * the library was not built for). Refusing at install time with that reason is far better than
 * letting someone install it, enable it, and find it silently dead.
 */
object ExtensionStore {

    private const val TAG = "PrismEditor"

    private const val OPEN_VSX = "https://open-vsx.org/api"

    /**
     * Ceiling on a single extension package.
     *
     * Was 64 MB, which is under the size of several real extensions -- Anthropic's Claude Code
     * package alone is about 98 MB, because platform-specific builds bundle a native binary. A limit
     * that refuses genuine extensions is not protecting anything.
     *
     * It is raised rather than removed because the number is not about the download, which streams
     * to disk and costs almost no memory: it is about the phone. A `.vsix` unpacks to appreciably
     * more than it downloads, so this is the last point at which refusing is cheap, and "no limit"
     * on a device with a few gigabytes free is how storage gets filled by a mis-click.
     */
    private const val MAX_VSIX_BYTES = 512L * 1024 * 1024

    /**
     * How much bigger the unpacked tree is assumed to be than the package.
     *
     * A `.vsix` is a zip of mostly JavaScript, which compresses well; three-to-one is conservative
     * for text and about right once a bundled binary is in there too.
     */
    private const val UNPACK_RATIO = 3

    /** Headroom left free after unpacking, so a successful install cannot fill the device. */
    private const val SPACE_MARGIN_BYTES = 256L * 1024 * 1024

    /** One extension as the marketplace describes it. */
    data class Listing(
        val id: String,              // publisher.name
        val name: String,
        val publisher: String,
        val displayName: String,
        val description: String,
        val version: String,
        val downloads: Int,
        val iconUrl: String?,
        val downloadUrl: String?,
    )

    /** One extension as it exists on this device. */
    data class Installed(
        val id: String,
        val displayName: String,
        val version: String,
        val directory: File,
        /** The worker entry point from the manifest's `browser` field. */
        val browserEntry: String?,
        /** The Node entry point from the manifest's `main` field. */
        val nodeEntry: String?,
        val enabled: Boolean,
    ) {
        /**
         * Which host should load this, or null when there is nothing to load.
         *
         * `browser` wins when an extension declares both, which many do: the Worker is the cheaper
         * host and the browser build is the one its author tested against a host like this one.
         */
        val runtime: String? get() = when {
            browserEntry != null -> RUNTIME_WORKER
            nodeEntry != null && NodeRuntime.isAvailable -> RUNTIME_NODE
            else -> null
        }

        val isRunnable: Boolean get() = runtime != null
    }

    const val RUNTIME_WORKER = "worker"
    const val RUNTIME_NODE = "node"

    fun root(context: Context): File = File(context.filesDir, "editor/extensions").apply { mkdirs() }

    // ── Browsing ───────────────────────────────────────────────────────────

    /**
     * Searches Open VSX. Blocking; callers run it off the main thread.
     *
     * Failures return an empty list rather than throwing: a marketplace that cannot be reached is a
     * normal condition on a phone, and the page shows "nothing found" with a retry rather than an
     * error dialog over a feature the user was only browsing.
     */
    fun search(query: String, limit: Int = 40): List<Listing> {
        val url = if (query.isBlank()) {
            "$OPEN_VSX/-/search?size=$limit&sortBy=downloadCount&sortOrder=desc"
        } else {
            "$OPEN_VSX/-/search?query=${encode(query)}&size=$limit&sortBy=relevance"
        }

        val body = fetchText(url) ?: return emptyList()
        return runCatching {
            val results = JSONObject(body).optJSONArray("extensions") ?: JSONArray()
            (0 until results.length()).mapNotNull { index ->
                val item = results.getJSONObject(index)
                val publisher = item.optString("namespace")
                val name = item.optString("name")
                if (publisher.isBlank() || name.isBlank()) return@mapNotNull null
                Listing(
                    id = "$publisher.$name",
                    name = name,
                    publisher = publisher,
                    displayName = item.optString("displayName").ifBlank { name },
                    description = item.optString("description"),
                    version = item.optString("version"),
                    downloads = item.optInt("downloadCount", 0),
                    iconUrl = item.optString("files.icon").ifBlank { null },
                    downloadUrl = null,     // resolved at install time, see [resolveDownload]
                )
            }
        }.getOrElse {
            PrismLogger.logError(TAG, "Could not parse marketplace results", it)
            emptyList()
        }
    }

    /** The `.vsix` URL for a listing's current version. */
    private fun resolveDownload(listing: Listing): String? {
        val body = fetchText("$OPEN_VSX/${listing.publisher}/${listing.name}") ?: return null
        return runCatching {
            JSONObject(body).optJSONObject("files")?.optString("download")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ── Installing ─────────────────────────────────────────────────────────

    /**
     * Downloads and unpacks one extension.
     *
     * @return null on success, or a message describing why not.
     */
    fun install(context: Context, listing: Listing, onProgress: (Int, String) -> Unit): String? {
        onProgress(0, "Looking up ${listing.displayName}…")
        val url = listing.downloadUrl ?: resolveDownload(listing)
            ?: return "Could not find a download for ${listing.id}"

        val target = File(root(context), listing.id)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                return "Download failed with HTTP ${connection.responseCode}"
            }

            val declared = connection.contentLengthLong
            if (declared > MAX_VSIX_BYTES) {
                return "${listing.displayName} is ${declared / (1024 * 1024)} MB, over the " +
                    "${MAX_VSIX_BYTES / (1024 * 1024)} MB limit for a single extension."
            }
            if (declared > 0 && !hasRoomFor(target, declared * UNPACK_RATIO)) {
                return "Not enough free space: ${listing.displayName} needs roughly " +
                    "${(declared * UNPACK_RATIO) / (1024 * 1024)} MB once unpacked."
            }

            // Progress is reported against the DOWNLOAD, which is the part that takes minutes at
            // this size. Unzipping a hundred megabytes of JavaScript is comparatively instant, so a
            // bar that only moved between coarse stages would sit at 20% for the whole wait and look
            // like a hang.
            var read = 0L
            val counted = object : java.io.InputStream() {
                private val inner = connection!!.inputStream
                override fun read(): Int = inner.read().also { if (it >= 0) note(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    inner.read(b, off, len).also { if (it > 0) note(it.toLong()) }
                override fun close() = inner.close()

                private fun note(bytes: Long) {
                    read += bytes
                    // Enforced while streaming as well, because Content-Length is a claim: a server
                    // that under-reports or omits it would otherwise bypass the check entirely.
                    if (read > MAX_VSIX_BYTES) {
                        throw java.io.IOException(
                            "The package exceeded ${MAX_VSIX_BYTES / (1024 * 1024)} MB while downloading"
                        )
                    }
                    val percent = if (declared > 0) ((read * 90) / declared).toInt() else 0
                    onProgress(
                        percent.coerceIn(0, 90),
                        "Downloading ${listing.displayName} · ${read / (1024 * 1024)} MB" +
                            if (declared > 0) " of ${declared / (1024 * 1024)} MB" else "",
                    )
                }
            }

            val files = counted.use { unzipVsix(it, target) }
            if (files == 0) return "The package was empty"

            onProgress(80, "Reading the manifest…")
            val manifest = File(target, "package.json")
            if (!manifest.isFile) {
                target.deleteRecursively()
                return "The package has no manifest"
            }

            val json = JSONObject(manifest.readText())
            val browser = json.optString("browser").takeIf { it.isNotBlank() }
            val main = json.optString("main").takeIf { it.isNotBlank() }

            if (browser == null && main != null && !NodeRuntime.isAvailable) {
                target.deleteRecursively()
                return "${listing.displayName} needs Node.js to run, and this build of Prism has " +
                    "no Node runtime for this device. Look for an extension that lists web support."
            }

            if (browser == null && main == null) {
                // No entry point at all is normal for themes and grammars -- they are pure data,
                // contributed through the manifest, and there is nothing to run.
                File(target, ".prism-declarative").writeText("1")
                onProgress(100, "Installed")
                PrismLogger.logSuccess(TAG, "Installed ${listing.id} (declarative)")
                return null
            }

            onProgress(100, "Installed")
            val host = if (browser != null) "worker" else "node"
            PrismLogger.logSuccess(TAG, "Installed ${listing.id} (${browser ?: main}, $host host)")
            null
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "Installing ${listing.id} failed", t)
            runCatching { target.deleteRecursively() }
            t.message ?: t.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }

    fun uninstall(context: Context, id: String): Boolean =
        runCatching { File(root(context), id).deleteRecursively() }.getOrDefault(false)

    /** Everything installed, readable or not. */
    fun installed(context: Context): List<Installed> {
        val dir = root(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            val manifest = File(folder, "package.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(manifest.readText())
                Installed(
                    id = folder.name,
                    displayName = json.optString("displayName").ifBlank { json.optString("name") },
                    version = json.optString("version"),
                    directory = folder,
                    browserEntry = json.optString("browser").takeIf { it.isNotBlank() },
                    nodeEntry = json.optString("main").takeIf { it.isNotBlank() },
                    enabled = !File(folder, ".prism-disabled").isFile,
                )
            }.getOrNull()
        }.sortedBy { it.displayName.lowercase() }
    }

    fun isInstalled(context: Context, id: String): Boolean =
        File(root(context), "$id/package.json").isFile

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val flag = File(root(context), "$id/.prism-disabled")
        if (enabled) flag.delete() else runCatching { flag.writeText("1") }
    }

    /**
     * What the host needs to load this extension: source for the Worker, a path for Node.
     *
     * The difference is the point of having two hosts. A Worker has no filesystem, so its extension
     * has to be read here and shipped across as text. Node has one, and handing it a path is what
     * gives the extension real `__dirname`, relative requires and its own `node_modules` -- reading
     * the file here and evaluating it there would break all three.
     */
    fun entrySource(installed: Installed): String? = when (installed.runtime) {
        RUNTIME_WORKER -> {
            val file = File(installed.directory, installed.browserEntry!!.removePrefix("./"))
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
        RUNTIME_NODE -> {
            val entry = installed.nodeEntry!!.removePrefix("./")
            val file = File(installed.directory, entry)
            // An extension may name its entry without the extension, exactly as `require` allows.
            val resolved = when {
                file.isFile -> file
                File(installed.directory, "$entry.js").isFile -> File(installed.directory, "$entry.js")
                File(file, "index.js").isFile -> File(file, "index.js")
                else -> null
            }
            resolved?.absolutePath
        }
        else -> null
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    /**
     * Unpacks a `.vsix`, which is a zip whose payload sits under `extension/`.
     *
     * Entry paths are checked the same way the tar reader checks its own: a zip entry may name
     * `../..` and an unpacker that does not verify will write wherever it is told.
     */
    private fun unzipVsix(source: java.io.InputStream, target: File): Int {
        var count = 0
        val root = target.canonicalFile
        ZipInputStream(source.buffered(128 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                // Everything outside `extension/` is packaging metadata Prism has no use for.
                if (!name.startsWith("extension/")) { zip.closeEntry(); continue }
                val relative = name.removePrefix("extension/")
                if (relative.isEmpty()) { zip.closeEntry(); continue }

                val destination = File(target, relative).canonicalFile
                if (!destination.toPath().startsWith(root.toPath())) {
                    throw SecurityException("Package entry escapes the install directory: $name")
                }

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out, 64 * 1024) }
                    count++
                }
                zip.closeEntry()
            }
        }
        return count
    }

    /**
     * Whether [destination]'s volume has room for [needed] plus a margin.
     *
     * Checked before the download rather than discovered during the unpack: running out partway
     * leaves a half-written extension directory, and the user has already waited for the whole
     * transfer by then.
     */
    private fun hasRoomFor(destination: File, needed: Long): Boolean = runCatching {
        val dir = destination.parentFile ?: return true
        android.os.StatFs(dir.absolutePath).availableBytes > needed + SPACE_MARGIN_BYTES
    }.getOrDefault(true)

    private fun fetchText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
