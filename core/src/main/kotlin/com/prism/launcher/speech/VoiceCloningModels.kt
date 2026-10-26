package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the ONNX files that voice-conversion engines run, wherever they are hosted.
 *
 * ## Why this knows nothing about engines
 *
 * There is no single source of truth for "the clone models" any more: OpenVoice's exports live
 * on Hugging Face, the engines Prism exports itself live on this repository's releases, and the
 * list grows when a new engine does. So this file is a downloader with a manifest type ([Model])
 * and every engine carries its own manifest -- sizes and URLs included, never guessed.
 *
 * ## Verified, not estimated
 *
 * Sizes are the bytes actually served by the host, checked before display, because a list that
 * says "0 MB" teaches the user to distrust every other number on the screen. Downloads stage to
 * a `.part-` file and rename on success, so an interrupted fetch is never mistaken for a model.
 * The unusable int8 QOperator exports (see [Legacy]) are purged rather than left on disk.
 */
object VoiceCloningModels {

    /**
     * One file an engine needs: where it lives, how big it is, what it must hash to.
     *
     * [sha256] is REQUIRED, not optional. These graphs are 3–358 MB of executable model data
     * fetched over a network and then handed to ONNX Runtime, so "the bytes that arrived" is not
     * good enough: a truncated transfer, a CDN serving a previous upload, or an actively swapped
     * file would all load and produce garbage rather than failing. The digest is the release's
     * published asset digest (GitHub's release API for the voice-engines tag, and the LFS oid for
     * the Hugging Face exports), pinned here so it is checked on every install.
     */
    data class Model(
        val id: String,
        val label: String,
        val file: String,
        val sizeBytes: Long,
        val url: String,
        val sha256: String,
    ) {
        val sizeLabel: String get() = "%.1f MB".format(sizeBytes / 1_000_000.0)
    }

    private fun homeDir(): File = File(File(PrismPlatform.host.dataDir(), "models"), "voice-cloning")

    fun fileFor(model: Model): File = File(homeDir(), model.file)

    /**
     * Whether a file is present and known-good.
     *
     * THE MARKER IS THE POINT. Hashing 358 MB every time a picker asks "is this installed?"
     * would freeze the page, and size alone is what the old code trusted -- which meant a file
     * one byte off was reported as MISSING forever and silently re-downloaded in full. So a
     * successful verified install writes `<file>.sha256`; its presence and contents are the cheap
     * proof. A file with no marker (an install that predates this) is still reported as installed
     * if its size matches, and [ensureInstalled] verifies it once and writes the marker the next
     * time it runs.
     */
    fun isInstalled(model: Model): Boolean {
        val file = fileFor(model)
        if (!file.isFile || file.length() != model.sizeBytes) return false
        val marker = markerFor(model)
        if (!marker.isFile) return true
        return runCatching { marker.readText().trim().equals(model.sha256, ignoreCase = true) }
            .getOrDefault(false)
    }

    /** True when the file is present AND its digest has been verified and recorded. */
    fun isVerified(model: Model): Boolean = isInstalled(model) && markerFor(model).isFile

    private fun markerFor(model: Model): File = File(homeDir(), "${model.file}.sha256")

    fun statusLine(models: List<Model>): String = models.joinToString("  ·  ") { model ->
        if (isInstalled(model)) "${model.label} ${model.sizeLabel} ✓" else "${model.label} ${model.sizeLabel}"
    }

    fun totalSizeLabel(models: List<Model>): String =
        "%.0f MB".format(models.sumOf { it.sizeBytes } / 1_000_000.0)

    /**
     * Downloads whichever files in [models] are missing. Blocking; run off the UI thread.
     *
     * Purges stale downloads first (the QOperator int8 exports Android cannot execute, and any
     * abandoned `.part-` staging file), so an obsolete or interrupted fetch never occupies
     * storage. [onProgress] receives the file being fetched and its byte counts. Returns null
     * on success; every failure is logged with its throwable before it is returned as text.
     */
    fun ensureInstalled(
        models: List<Model>,
        onProgress: (model: Model, copied: Long, total: Long) -> Unit = { _, _, _ -> },
    ): String? {
        purgeStaleFiles()
        for (model in models) {
            if (isVerified(model)) continue
            val file = fileFor(model)

            // Present but unverified: either a pre-marker install or a corrupted one. Verify
            // before deciding, because "re-download 358 MB" is a much worse answer than one hash
            // pass when the file was already correct.
            if (file.isFile) {
                val verified = runCatching {
                    file.length() == model.sizeBytes && digest(file).equals(model.sha256, true)
                }.getOrDefault(false)
                if (verified) {
                    writeMarker(model)
                    continue
                }
                PrismPlatform.log.warn(
                    TAG,
                    "Discarding ${model.file}: it does not match the published digest",
                )
                file.delete()
                markerFor(model).delete()
            }

            val error = download(model, onProgress)
            if (error != null) return error
            // Any engine whose file just changed must drop its sessions to pick the new one up.
            CloneEngines.closeAll()
        }
        return null
    }

    /** Streaming SHA-256, so a 358 MB graph never has to fit in memory twice. */
    internal fun digest(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun writeMarker(model: Model) {
        runCatching {
            markerFor(model).writeText(model.sha256)
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not record the checksum for ${model.file}")
        }
    }

    /**
     * The int8 QOperator exports Android cannot run (`ConvInteger`/`MatMulInteger` have no
     * kernel in the shipped runtime -- the session fails with `ORT_NOT_IMPLEMENTED` before it
     * runs a frame), plus staging leftovers. Named explicitly so a device that already has them
     * reclaims the space instead of carrying them forever.
     */
    private val LEGACY_Q8 = listOf("tone_ref_encoder_q8.onnx", "tone_converter_q8.onnx")

    private fun purgeStaleFiles() {
        val dir = homeDir()
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            val stale = file.name in LEGACY_Q8 || file.name.contains(".part-")
            if (!stale) return@forEach
            val bytes = file.length()
            if (file.delete()) {
                PrismPlatform.log.info(TAG, "Purged stale download ${file.name} ($bytes bytes)")
            }
        }
    }

    private fun download(
        model: Model,
        onProgress: (model: Model, copied: Long, total: Long) -> Unit,
    ): String? = runCatching {
        homeDir().mkdirs()
        val target = fileFor(model)
        val staging = File(homeDir(), "${model.file}.part-${System.nanoTime()}")
        val error = fetch(model.url, staging) { copied, total -> onProgress(model, copied, total) }
        if (error != null) {
            staging.delete()
            return error
        }

        // VERIFY BEFORE IT BECOMES A MODEL. The staging file is checked against the pinned
        // digest, and a mismatch is a hard failure with both hashes shown -- not a warning that
        // lets an unverified graph be loaded later.
        val actual = digest(staging)
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            staging.delete()
            val message = "${model.file} failed checksum verification: expected " +
                "${model.sha256.take(16)}…, got ${actual.take(16)}…. The download was " +
                "truncated or does not match the published file."
            PrismPlatform.log.error(TAG, message, null)
            return message
        }

        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        writeMarker(model)
        PrismPlatform.log.info(
            TAG,
            "Installed ${model.file} (${target.length()} bytes, sha256 verified)",
        )
        null
    }.getOrElse {
        PrismPlatform.log.error(TAG, "Install of ${model.file} failed", it)
        it.message ?: "download failed"
    }

    private fun fetch(
        url: String,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): String? = runCatching {
        target.parentFile?.mkdirs()
        target.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("User-Agent", "Prism")
        }
        connection.inputStream.use { input ->
            val total = connection.contentLengthLong
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                }
            }
        }
        if (target.length() <= 0) throw IllegalStateException("nothing was downloaded")
        null
    }.getOrElse {
        target.delete()
        PrismPlatform.log.error(TAG, "Fetch of $url failed", it)
        it.message ?: "download failed"
    }

    private const val TAG = "PrismSpeech"
}
