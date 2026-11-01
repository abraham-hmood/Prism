package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.Urls
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.HttpURLConnection

/**
 * The whisper model auto-transcription uses, and where it comes from.
 *
 * ## Why a model is provisioned rather than required
 *
 * Auto-transcription is only useful if it works on a fresh install: a folder of takes has no words
 * until something hears them, and telling the user to go and find a ggml file before the feature
 * does anything is the same as not having the feature. The dictation engine already knows how to
 * run one; what it lacked was a model to run.
 *
 * So the first dataset that needs transcriptions fetches ONE model -- the multilingual base model,
 * about 142 MB -- and selects it. It is a one-time download, it is only started when a CSV
 * actually has a blank transcript, and a model the user chose themselves is never replaced: their
 * path is used whenever it names a real file.
 *
 * ## Why the bytes are checked
 *
 * A captive portal or a redirect to an HTML error page arrives as a 200 with a body. Writing that
 * to `ggml-base.bin` would leave a file the app reports as installed and whisper refuses to load,
 * which is a worse failure than a download that says it failed. The first four bytes of a ggml
 * model are its magic; requiring them, and the length the server promised, is enough to tell a
 * model from an error page without hashing 142 MB.
 */
object WhisperModels {

    /** The model fetched for a machine that has none. Multilingual, and small enough for a phone. */
    const val DEFAULT_FILE = "ggml-base.bin"

    /** Its label, for a progress line that should say what is being downloaded. */
    const val DEFAULT_LABEL = "ggml-base.bin (142 MB)"

    /**
     * A ggml model's first four bytes.
     *
     * The format writes `GGML_FILE_MAGIC` (0x67676d6c, the text "ggml") as a little-endian
     * uint32, so the bytes in the file read `lmgg`. Verified against the real ggml-base.bin
     * Hugging Face publishes; checking for the ASCII "ggml" would reject every genuine model.
     */
    private val MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)

    /** Below this a "model" is a truncated transfer or an error page, not a model. */
    private const val MIN_BYTES = 1L shl 20

    /** Every model this downloads lands here, whether or not the user ever opens the folder. */
    fun modelsDir(): File = File(PrismPlatform.host.documentsDir(), "Whisper/models")

    fun defaultModelFile(): File = File(modelsDir(), DEFAULT_FILE)

    /**
     * The model to use now: the user's choice when it exists, else the automatically fetched one.
     *
     * Null is the honest answer when neither is there. [ensureModel] is what turns that into a
     * download, and it is deliberately not called from here: a check must not start 142 MB.
     */
    fun modelPath(): String? {
        val chosen = PrismSettings.getWhisperModelPath().trim()
        if (chosen.isNotEmpty()) {
            val file = File(chosen)
            if (isModelFile(file)) return file.absolutePath
        }
        val fallback = defaultModelFile()
        return if (isModelFile(fallback)) fallback.absolutePath else null
    }

    /** True when [file] starts with the ggml magic, which is the cheap proof it is a model. */
    fun isModelFile(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MIN_BYTES) return false
        val head = ByteArray(MAGIC.size)
        file.inputStream().use { input ->
            if (input.read(head) != MAGIC.size) return false
        }
        head.contentEquals(MAGIC)
    }.getOrDefault(false)

    /**
     * Makes sure a whisper model is on disk and selected.
     *
     * Blocking: the download is 142 MB, so callers run it off the UI thread. [onStage] receives a
     * line per percent so an import can show what the wait is for. Returns null on success, or the
     * sentence explaining why no model could be made ready.
     *
     * Synchronized because two datasets prepared at once must not fetch the same file twice:
     * the second call waits, then finds the model the first one installed.
     */
    @Synchronized
    fun ensureModel(onStage: (String) -> Unit = {}): String? {
        if (!WhisperNative.ensureLoaded()) {
            return "The whisper_bridge library is not loaded" +
                WhisperNative.loadError.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() +
                ". On desktop, build it with the buildWhisper Gradle task."
        }

        modelPath()?.let { existing ->
            if (!existing.equals(PrismSettings.getWhisperModelPath(), ignoreCase = true)) {
                PrismSettings.setWhisperModelPath(existing)
            }
            return null
        }

        val target = defaultModelFile()
        onStage("Downloading the transcription model $DEFAULT_LABEL, once…")
        val problem = download(DEFAULT_FILE, target, onStage)
        if (problem != null) return problem
        PrismSettings.setWhisperModelPath(target.absolutePath)
        onStage("Transcription model ready.")
        return null
    }

    /** Fetches one model into [target]. Returns null on success, else the reason. */
    private fun download(fileName: String, target: File, onStage: (String) -> Unit): String? {
        // Unique per attempt, like Kokoro's: two fetches sharing one scratch file interleave into a
        // file with a hole in it, and the result reads as a corrupt model rather than two downloads.
        val staging = File(target.parentFile, "${target.name}.part-${System.nanoTime()}")
        return runCatching {
            target.parentFile?.mkdirs()
            staging.delete()

            val connection = (Urls.of(WhisperCppEngine.downloadUrl(fileName)).openConnection()
                as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                // A read timeout is per read, not for the whole transfer: 142 MB on a slow link is
                // many reads, each of which should arrive well inside two minutes.
                readTimeout = 120_000
                setRequestProperty("User-Agent", "Prism")
            }

            connection.inputStream.use { input ->
                val total = connection.contentLengthLong
                staging.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onStage("Downloading the transcription model… $percent%")
                            }
                        }
                    }
                    if (total > 0 && copied < total) {
                        throw IllegalStateException("the transfer ended at $copied of $total bytes")
                    }
                }
            }

            if (staging.length() < MIN_BYTES) {
                throw IllegalStateException("the download was only ${staging.length()} bytes")
            }
            if (!isModelFile(staging)) {
                throw IllegalStateException("the download was not a ggml whisper model")
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            PrismPlatform.log.info(
                "PrismWhisper",
                "Installed $fileName (${target.length() shr 20} MB) at ${target.absolutePath}",
            )
            null
        }.getOrElse {
            staging.delete()
            PrismPlatform.log.warn("PrismWhisper", "Fetching $fileName failed: ${it.message}")
            "Could not download the transcription model: ${it.message ?: "unknown error"}"
        }
    }
}
