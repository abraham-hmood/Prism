package com.prism.launcher.browser

import com.prism.core.MeshConnect
import com.prism.core.MeshDns
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Copying a mesh site so it survives the machine that served it going away. PHASE 54.
 *
 * ## Why a mirror is not a cache
 *
 * A cache answers faster and is discarded under pressure. A MIRROR is a second copy that is itself
 * hosted: once a device has mirrored `notes.p2p`, it serves `notes.p2p` too, and the site stays up when
 * the original machine is asleep. That is the whole point on a mesh where every host is somebody's
 * laptop — the failure mode a mirror exists for is not slowness, it is absence.
 *
 * ## Why every file is hashed
 *
 * The bytes came from a peer over a tunnel with no public certificate authority behind it. A hash per
 * file in the manifest means a corrupted or substituted file is REJECTED rather than mirrored and then
 * served onward as though this device vouched for it — and serving it onward is exactly what mirroring
 * does, which is what makes silent corruption worse here than in a cache.
 *
 * ## What the origin has to provide
 *
 * `/manifest.json`: a list of `{path, hash}`. [MeshWebHost.manifestFor] generates one, so a site hosted
 * by Prism is mirrorable with nothing extra; a site served by something else is not, and that is
 * reported rather than guessed at by crawling links.
 */
object MeshMirror {

    private const val TAG = "PrismMirror"

    data class Mirrored(
        val domain: String,
        val root: File,
        val files: Int,
        val bytes: Long,
        val at: Long = System.currentTimeMillis(),
    )

    /** Progress, 0..100, or -1 for failed. Set so a UI can follow a long copy. */
    @Volatile
    var onProgress: ((domain: String, percent: Int) -> Unit)? = null

    @Volatile private var storage: File? = null

    fun install(directory: File) {
        storage = directory.apply { mkdirs() }
    }

    private fun root(): File =
        storage ?: File(PrismPlatform.host.dataDir(), "mirrors").apply { mkdirs() }

    fun all(): List<Mirrored> =
        root().listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                Mirrored(dir.name, dir, files.size, files.sumOf { it.length() }, dir.lastModified())
            }
            .sortedBy { it.domain }

    /**
     * Copies a site from the peer currently serving it, then hosts it here.
     *
     * Blocking and potentially slow. Returns an error to show, or null on success.
     *
     * THE ADDRESS COMES FROM DNS rather than being asked for, because a mirror is of a NAME: the point
     * is that the name keeps working, and which machine happens to answer for it today is exactly the
     * detail the mirror exists to stop mattering.
     */
    fun mirror(domain: String, fromIp: String? = null, asDomain: String? = null): String? {
        val source = domain.lowercase().trim()
        if (source.isBlank()) return "Name a site."
        // Mirrored UNDER a different name when asked. Usually the same name -- that is the point -- but a
        // machine that already serves the original needs somewhere else to put the copy, and so does
        // anyone keeping a snapshot beside the live site.
        val name = (asDomain ?: source).lowercase().trim()

        val peerIp = fromIp?.takeIf { it.isNotBlank() }
            ?: MeshDns.resolve(source, onlyP2p = false)
            ?: return "Nothing on the mesh answers for $source."
        if (fromIp == null && peerIp == "127.0.0.1") {
            return "$source is served by this device already."
        }

        onProgress?.invoke(name, 0)

        val manifestBytes = MeshConnect.get(peerIp, source, "/manifest.json")
            ?: run {
                onProgress?.invoke(name, -1)
                return "$peerIp did not send a manifest, so there is no list of files to copy. A site " +
                    "Prism hosts publishes one automatically; one served by something else cannot be " +
                    "mirrored this way."
            }

        val entries = runCatching {
            val array = JSONObject(manifestBytes.toString(Charsets.UTF_8)).optJSONArray("files")
                ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("path")
                if (path.isBlank()) null else path to item.optString("hash")
            }
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) {
            onProgress?.invoke(name, -1)
            return "The manifest from $peerIp listed no files."
        }

        val target = File(root(), safe(name)).apply { mkdirs() }
        var copied = 0
        var bytes = 0L

        entries.forEachIndexed { index, (path, expected) ->
            // A path from the wire, resolved and then CHECKED against the mirror directory rather than
            // trusted -- a manifest is a peer telling this device where to write files.
            val file = File(target, path.trimStart('/'))
            val inside = runCatching {
                file.canonicalPath.startsWith(target.canonicalPath + File.separator) ||
                    file.canonicalPath == target.canonicalPath
            }.getOrDefault(false)
            if (!inside) {
                PrismPlatform.log.warn(TAG, "Refused a path from $peerIp: $path")
                return@forEachIndexed
            }

            file.parentFile?.mkdirs()
            val got = MeshConnect.get(peerIp, source, "/" + path.trimStart('/'), file)
            if (got == null) {
                PrismPlatform.log.warn(TAG, "Could not fetch $path from $peerIp")
                return@forEachIndexed
            }

            if (expected.isNotBlank()) {
                val actual = sha256(file)
                if (!actual.equals(expected, ignoreCase = true)) {
                    // DELETED, not kept. A mirror serves what it holds, so a file that does not match
                    // would be handed to the next device as though this one had checked it.
                    file.delete()
                    PrismPlatform.log.warn(TAG, "$path did not match its hash; dropped")
                    return@forEachIndexed
                }
            }

            copied++
            bytes += file.length()
            onProgress?.invoke(name, ((index + 1) * 100) / entries.size)
        }

        if (copied == 0) {
            onProgress?.invoke(name, -1)
            return "Nothing could be copied from $peerIp."
        }

        // AND NOW SERVE IT. This is what separates a mirror from a download: the name works from this
        // device afterwards, for this device and for every other peer.
        MeshWebHost.host(name, target)
        onProgress?.invoke(name, 100)
        PrismPlatform.log.info(TAG, "Mirrored $name — $copied file(s), " + (bytes shr 10) + " KB")
        return null
    }

    fun remove(domain: String) {
        val name = domain.lowercase().trim()
        MeshWebHost.stopHosting(name)
        runCatching { File(root(), safe(name)).deleteRecursively() }
        PrismPlatform.log.info(TAG, "Removed the mirror of $name")
    }

    private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
