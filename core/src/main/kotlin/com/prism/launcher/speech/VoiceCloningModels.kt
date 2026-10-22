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

    /** One file an engine needs: where it lives, how big it is, what to call it in progress UI. */
    data class Model(
        val id: String,
        val label: String,
        val file: String,
        val sizeBytes: Long,
        val url: String,
    ) {
        val sizeLabel: String get() = "%.1f MB".format(sizeBytes / 1_000_000.0)
    }

    private fun homeDir(): File = File(File(PrismPlatform.host.dataDir(), "models"), "voice-cloning")

    fun fileFor(model: Model): File = File(homeDir(), model.file)

    fun isInstalled(model: Model): Boolean =
        fileFor(model).let { it.isFile && it.length() == model.sizeBytes }

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
            if (isInstalled(model)) continue
            val error = download(model, onProgress)
            if (error != null) return error
            // Any engine whose file just changed must drop its sessions to pick the new one up.
            CloneEngines.closeAll()
        }
        return null
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
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        PrismPlatform.log.info(TAG, "Installed ${model.file} (${target.length()} bytes)")
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
