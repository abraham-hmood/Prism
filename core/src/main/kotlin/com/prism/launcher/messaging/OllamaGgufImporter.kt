package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Turns a model that an Ollama server is hosting into a plain `.gguf` Prism can run.
 *
 * ## What "convert" actually means here
 *
 * An Ollama model is not a file. It is a manifest plus a handful of content-addressed blobs --
 * weights, template, licence, stop parameters -- and only the weights are a GGUF. `ollama pull`
 * writes those blobs into `~/.ollama/models/blobs/sha256-...` with no extension, and a running
 * server will happily chat with them but exposes no endpoint that hands one back: `/api/show` and
 * `/api/tags` describe it, `/api/pull` fetches it *to the server*, and there is no `/api/export`.
 *
 * So the conversion is a fetch and an unpack: find the model's published weights, take the one
 * layer that is a GGUF, write it under a `.gguf` name in the models folder and check it against the
 * digest the publisher gave. The bytes are already GGUF -- that is why this is a repackaging rather
 * than a re-quantisation, and why it takes as long as the download and no longer.
 *
 * ## Two publishers, because Ollama has two
 *
 * `llama3.2`, `qwen2.5-coder:3b` and anything else under a namespace come from Ollama's own
 * registry (`registry.ollama.ai`), which is where `ollama pull` gets them. A name starting
 * `hf.co/` is different: Ollama reads that repository on Hugging Face and, when it holds GGUFs,
 * hands them to llama.cpp unchanged. Both are "a model the server is hosting", so both are
 * supported here; a repository that only holds safetensors has no GGUF to take, which is reported
 * rather than papered over -- the conversion in that case happened on Ollama's servers, not here.
 *
 * ## Why not read it off the discovered server
 *
 * A model on a phone across the room is on a *different device's disk*, and this one has no way to
 * read it. Going to the same publisher the server pulled from means the source works for a server
 * in the next room and for one on this machine, and the result is reproducible: the name and tag
 * pick the weights, and the digest says they arrived intact.
 *
 * ## Verification is not optional
 *
 * A multi-gigabyte download that is truncated or subtly wrong does not fail where it happened; it
 * fails inside llama.cpp, days later, complaining about a tensor. So the digest the publisher
 * carries is checked against the bytes as they are written, the size is checked too, and the result
 * has to begin with a GGUF header before it is renamed into place.
 */
object OllamaGgufImporter {

    private const val TAG = "Prism/ollama"

    /** Ollama's own registry, the same host `ollama pull` talks to. Anonymous reads. */
    const val REGISTRY = "https://registry.ollama.ai"

    /** Where `hf.co/...` names actually live. */
    const val HUGGING_FACE = "https://huggingface.co"

    /** The media type of the layer that holds the weights. There is exactly one per manifest. */
    const val MODEL_LAYER = "application/vnd.ollama.image.model"

    private const val ADAPTER_LAYER = "application/vnd.ollama.image.adapter"

    private const val MANIFEST_ACCEPT = "application/vnd.docker.distribution.manifest.v2+json"
    private const val USER_AGENT = "Prism/1.0 (ollama-gguf-import)"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 6

    /** How often progress is reported while a file streams in. */
    private const val PROGRESS_INTERVAL_MS = 200L

    private const val CHUNK_BYTES = 64 * 1024

    /** A name Ollama understands, split the way its registry wants it. */
    data class Reference(
        /** The name exactly as the server lists it, e.g. `qwen2.5-coder:3b`. */
        val display: String,
        /** Namespace and repository, e.g. `library/qwen2.5-coder` or `hf.co/user/repo`. */
        val repository: String,
        /** The tag, e.g. `3b`. */
        val tag: String,
    ) {
        /**
         * What the file is called on disk for a registry model.
         *
         * A colon cannot appear in a Windows filename, and the tag is part of what makes the name
         * mean anything, so `qwen2.5-coder:3b` becomes `qwen2.5-coder-3b.gguf` rather than
         * `qwen2.5-coder.gguf` -- the latter would silently collide with the 7B one.
         */
        val fileName: String
            get() {
                // `library/` is where every official model lives; carrying it into the filename
                // would put "library-" in front of every one of them for no information.
                val simple = repository.removePrefix("library/")
                val stem = (if (tag == "latest") simple else "$simple-$tag")
                    .replace(Regex("[^A-Za-z0-9._-]"), "-")
                    .trim('-', '.')
                return (stem.ifBlank { "ollama-model" }) + ".gguf"
            }

        val manifestUrl: String get() = "$REGISTRY/v2/$repository/manifests/$tag"

        fun blobUrl(digest: String): String = "$REGISTRY/v2/$repository/blobs/$digest"
    }

    /** The weights layer of a manifest. */
    data class Layer(val digest: String, val size: Long)

    /** One GGUF offered by a Hugging Face repository. */
    data class GgufFile(val path: String, val size: Long, val sha256: String?)

    /** A failure worth showing the user, phrased as what they can do about it. */
    class ImportFailure(message: String) : Exception(message)

    /** Where the weights come from, once the name has been resolved. */
    private class Source(
        val url: String,
        val fileName: String,
        val size: Long,
        /** Hex, without the `sha256:` prefix; null when the publisher does not name one. */
        val sha256: String?,
    )

    /**
     * Splits `name`, `name:tag`, `namespace/name`, `namespace/name:tag` and `hf.co/owner/repo:tag`
     * into publisher parts.
     *
     * The tag is everything after the LAST colon, so a name that itself contains a port survives.
     * A bare name is what Ollama's library holds, hence the `library/` prefix; a name with a slash
     * in it already carries its namespace.
     */
    fun reference(modelName: String): Reference {
        val raw = modelName.trim()
        if (raw.isEmpty()) throw ImportFailure("No Ollama model was named.")
        val colon = raw.lastIndexOf(':')
        val repositoryPart = if (colon > 0) raw.substring(0, colon) else raw
        val tag = if (colon > 0) raw.substring(colon + 1).trim() else "latest"
        if (repositoryPart.isBlank() || tag.isBlank()) {
            throw ImportFailure("`$raw` is not an Ollama model name.")
        }
        val repository = if (repositoryPart.contains('/')) repositoryPart else "library/$repositoryPart"
        return Reference(raw, repository, tag)
    }

    /**
     * The weights layer, or a failure explaining what the manifest held instead.
     *
     * Checked rather than assumed: an embedding-only or adapter-only tag has no model layer, and
     * writing the template blob out under a `.gguf` name would produce a tiny file that fails in
     * the loader.
     */
    fun modelLayer(manifest: JSONObject): Layer {
        val layers = manifest.optJSONArray("layers")
            ?: throw ImportFailure("Ollama's manifest carries no layers, so there are no weights to convert.")
        val all = (0 until layers.length()).map { layers.getJSONObject(it) }
        if (all.any { it.optString("mediaType") == ADAPTER_LAYER }) {
            // Worth a line in the log but not a refusal: the base weights are a runnable model on
            // their own. The adapter is LoRA on top of them and merging it needs llama.cpp's
            // exporter, which is not part of this job.
            PrismPlatform.log.warn(
                TAG,
                "This Ollama tag carries a LoRA adapter; converting the base weights only.",
            )
        }
        val layer = all.firstOrNull { it.optString("mediaType") == MODEL_LAYER }
            ?: throw ImportFailure("That Ollama tag has no model weights to convert.")
        val digest = layer.optString("digest")
        if (!digest.startsWith("sha256:")) {
            throw ImportFailure("Ollama's manifest names its weights without a sha256 digest.")
        }
        return Layer(digest.removePrefix("sha256:"), layer.optLong("size", 0L))
    }

    /**
     * Which GGUF in a Hugging Face repository matches Ollama's tag.
     *
     * The tag IS the quantisation when the name came from `hf.co` (`:Q4_K_M`), so the file has to
     * be picked by what it is called. `latest` has no such tag -- Ollama itself picks a default --
     * so Q4_K_M is preferred, then the first GGUF, which is the same useful compromise anyone
     * browsing that repository would make. Split files (`-00001-of-00002`) are skipped: this writes
     * one GGUF, and a shard of one is not a model.
     */
    fun chooseGguf(files: List<GgufFile>, tag: String): GgufFile? {
        val usable = files.filter {
            it.path.endsWith(".gguf", ignoreCase = true) && !SPLIT_PART.containsMatchIn(it.path)
        }
        if (usable.isEmpty()) return null
        if (!tag.equals("latest", ignoreCase = true)) {
            val needle = tag.lowercase()
            usable.firstOrNull { it.path.substringAfterLast('/').lowercase().contains(needle) }
                ?.let { return it }
        }
        return usable.firstOrNull { it.path.lowercase().contains("q4_k_m") } ?: usable.first()
    }

    private val SPLIT_PART = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** The GGUF files a repository lists, with their sizes and checksums where HF publishes them. */
    fun huggingFaceFiles(json: JSONObject): List<GgufFile> {
        val siblings = json.optJSONArray("siblings") ?: return emptyList()
        return (0 until siblings.length()).mapNotNull { index ->
            val sibling = siblings.getJSONObject(index)
            val path = sibling.optString("rfilename")
            if (path.isBlank()) return@mapNotNull null
            val lfs = sibling.optJSONObject("lfs")
            GgufFile(
                path = path,
                size = lfs?.optLong("size", 0L)?.takeIf { it > 0L } ?: sibling.optLong("size", 0L),
                sha256 = lfs?.optString("sha256")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Fetches [modelName]'s weights and leaves a verified `.gguf` in [directory].
     *
     * The file is written under a `.part` name and renamed only once the checksum, the length and
     * the GGUF header all agree, so nothing half-downloaded can ever be registered as a model.
     *
     * @param directory where the completed file lands; the shared models folder unless a caller
     *        says otherwise (a test has no business writing into the user's Models directory).
     * @param onProgress bytes written and total bytes (`0` when the publisher did not say).
     */
    suspend fun download(
        modelName: String,
        directory: File = ModelRegistry.directory(),
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val reference = reference(modelName)
        val source = if (huggingFaceRepo(reference.repository) != null) {
            huggingFaceSource(reference)
        } else {
            registrySource(reference)
        }

        directory.mkdirs()
        val target = File(directory, source.fileName)

        // Already converted. Re-registering is the caller's job, so this only has to say "there it
        // is" -- downloading a second copy of a file that is already on disk helps nobody.
        if (target.isFile && target.length() > 0L && (source.size <= 0L || target.length() == source.size)) {
            PrismPlatform.log.info(TAG, "${target.name} is already in ${directory.name}; using it")
            return@withContext target
        }

        val part = File(directory, source.fileName + ".part")
        part.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var total = source.size

        try {
            val connection = open(source.url, null)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw ImportFailure(
                        "The weights for ${reference.display} could not be downloaded " +
                            "(HTTP ${connection.responseCode}).",
                    )
                }
                if (total <= 0L) total = connection.contentLengthLong.coerceAtLeast(0L)

                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0 && written > total) {
                                // Read more than the publisher says the file is. Stopping here is
                                // better than filling the disk with whatever is being sent.
                                throw ImportFailure(
                                    "The download for ${reference.display} was larger than advertised.",
                                )
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(written, total)
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }

            onProgress(written, total)

            if (total > 0 && written != total) {
                throw ImportFailure(
                    "The download stopped at ${megabytes(written)} of ${megabytes(total)} MB.",
                )
            }

            source.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw ImportFailure(
                        "The converted file failed its checksum, so it was thrown away. Try again.",
                    )
                }
            }

            // The one thing that separates a GGUF from "some bytes that downloaded fine". Checked
            // before the rename so a bad file never appears under a name the models page trusts.
            val detection = ModelFormat.detect(part)
            if (detection.kind != ModelFormat.Kind.GGUF) {
                throw ImportFailure(
                    "The weights are not a GGUF file after all: " + detection.explain(part),
                )
            }
            if (ModelFormat.ggufFamily(part) == ModelFormat.GgufFamily.DIFFUSION) {
                throw ImportFailure(
                    "${reference.display} is a diffusion checkpoint, not a text model. Import it " +
                        "from the Models page as an image model instead.",
                )
            }

            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            PrismPlatform.log.info(
                TAG,
                "Converted ${reference.display} to ${target.name} (${megabytes(target.length())} MB)",
            )
            target
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** Registers a converted file as a text model. Idempotent, keyed by path. */
    fun importAsText(file: File, displayName: String): Boolean =
        ModelRegistry.install(file, displayName = displayName, type = com.prism.launcher.PrismSettings.MODEL_TYPE_TEXT)

    // ── Sources ─────────────────────────────────────────────────────────────────────────────

    private fun registrySource(reference: Reference): Source {
        PrismPlatform.log.info(TAG, "Reading the Ollama manifest for ${reference.display}")
        val manifest = JSONObject(readText(reference.manifestUrl, MANIFEST_ACCEPT, reference))
        val layer = modelLayer(manifest)
        return Source(
            url = reference.blobUrl("sha256:" + layer.digest),
            fileName = reference.fileName,
            size = layer.size,
            sha256 = layer.digest,
        )
    }

    private fun huggingFaceSource(reference: Reference): Source {
        val repo = huggingFaceRepo(reference.repository)
            ?: throw ImportFailure("${reference.display} does not name a Hugging Face repository.")
        PrismPlatform.log.info(TAG, "Reading the Hugging Face file list for $repo")

        val body = readText("$HUGGING_FACE/api/models/$repo?blobs=true", null, reference)
        val chosen = chooseGguf(huggingFaceFiles(JSONObject(body)), reference.tag)
            ?: throw ImportFailure(
                "$repo on Hugging Face publishes no GGUF file" +
                    (if (reference.tag != "latest") " for ${reference.tag}" else "") +
                    ". Ollama converts formats like safetensors on its own server, so there is " +
                    "nothing here to save -- pick a GGUF repository, or import the file by hand.",
            )

        return Source(
            url = "$HUGGING_FACE/$repo/resolve/main/" + chosen.path,
            fileName = sanitizeFileName(chosen.path.substringAfterLast('/')),
            size = chosen.size,
            sha256 = chosen.sha256,
        )
    }

    private fun huggingFaceRepo(repository: String): String? = when {
        repository.startsWith("hf.co/") -> repository.removePrefix("hf.co/")
        repository.startsWith("huggingface.co/") -> repository.removePrefix("huggingface.co/")
        else -> null
    }?.takeIf { it.contains('/') }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').ifBlank { "ollama-model.gguf" }

    // ── HTTP ───────────────────────────────────────────────────────────────────────────────

    private fun readText(spec: String, accept: String?, reference: Reference): String {
        val connection = open(spec, accept)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw failureFor(code, reference)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A GET that walks redirects itself.
     *
     * The registry answers a blob request with a signed 307 to object storage, and Hugging Face
     * answers a resolve URL with a 302 to its CDN. The JDK's follower only handles redirects within
     * one protocol -- doing it here means a publisher that points at plain HTTP (or anywhere else)
     * still works, and gives a real message instead of an empty body when it loops.
     */
    private fun open(spec: String, accept: String?): HttpURLConnection {
        var current = spec
        repeat(MAX_REDIRECTS + 1) {
            val connection = com.prism.core.Urls.of(current).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            accept?.let { connection.setRequestProperty("Accept", it) }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?.takeIf { it.isNotBlank() }
                    ?: run {
                        connection.disconnect()
                        throw ImportFailure("A publisher redirected without saying where: $current")
                    }
                connection.disconnect()
                current = if (location.startsWith("http")) location
                else com.prism.core.Urls.resolve(current, location).toString()
                return@repeat
            }
            return connection
        }
        throw ImportFailure("That address redirected too many times to download: $spec")
    }

    /** Turns an HTTP status into the sentence it deserves. */
    private fun failureFor(code: Int, reference: Reference): ImportFailure = ImportFailure(
        when (code) {
            401, 403 ->
                "${reference.display} is private or gated, so its weights cannot be fetched."
            404 ->
                "${reference.display} is not published where Ollama got it from, so there is " +
                    "nothing to download. A model made on that machine with `ollama create` " +
                    "cannot be converted this way -- copy the file over and import it instead."
            -1 -> "The publisher could not be reached."
            else -> "The publisher answered $code for ${reference.display}."
        },
    )

    private fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)
}
