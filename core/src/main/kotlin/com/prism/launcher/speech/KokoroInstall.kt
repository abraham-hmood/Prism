package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where Kokoro-82M lives on the device, and how it gets there.
 *
 * ## Why the weights do not come from the repository you would expect
 *
 * `hexgrad/Kokoro-82M` is Kokoro's home, and it publishes exactly one set of weights:
 * `kokoro-v1_0.pth`, 327 MB of PyTorch. Android cannot load a `.pth` -- there is no PyTorch here,
 * and adding one to run a single 82M model would cost more than the model does. Converting it on
 * device is not an option either: the conversion needs PyTorch to read it in the first place.
 *
 * So the weights come from `onnx-community/Kokoro-82M-v1.0-ONNX`, which is the same model exported
 * to ONNX, with quantisations that matter on a phone -- 86 MB against 327 MB -- and voice tensors in
 * a flat binary layout instead of pickled ones. The voices are the same 54 voices; the model is the
 * same model. Only the container differs, which is the whole reason to prefer it.
 *
 * ## Layout
 *
 * ```
 * <data>/models/kokoro/
 *   model_q8f16.onnx      the chosen quantisation, one at a time
 *   tokenizer.json        phoneme -> id, published with the export
 *   voices/af_heart.bin   510 x 256 float32, one file per voice, fetched on demand
 * ```
 *
 * Voices are fetched individually rather than all at once: 54 of them is 28 MB for a set the user
 * will pick one or two from, and each is half a megabyte, so fetching on selection is quick enough
 * to be invisible.
 */
object KokoroInstall {

    private const val TAG = "PrismSpeech"

    /** The ONNX export. See the class comment for why this and not `hexgrad/Kokoro-82M`. */
    private const val MODEL_REPO = "onnx-community/Kokoro-82M-v1.0-ONNX"

    /** Kokoro's own home, kept for the record and for anything that wants to point a user at it. */
    const val UPSTREAM_REPO_URL = "https://huggingface.co/hexgrad/Kokoro-82M"

    private fun resolve(path: String) = "https://huggingface.co/$MODEL_REPO/resolve/main/$path"

    /**
     * misaki's American English lexicon, 3 MB of word-to-IPA.
     *
     * From Kokoro's own G2P package rather than a general pronunciation dictionary: CMUdict would
     * need its ARPAbet transcriptions mapped onto Kokoro's IPA vocabulary, and every mapping choice
     * in that conversion is a chance to hand the model a phoneme it was not trained on.
     */
    private const val LEXICON_URL =
        "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/us_gold.json"

    /**
     * Where Kokoro lives. Does NOT create anything: this is called to answer "is it installed?",
     * and a question should not leave a directory behind as a side effect of being asked.
     */
    fun homeDir(): File = File(File(PrismPlatform.host.dataDir(), "models"), "kokoro")

    fun voicesDir(): File = File(homeDir(), "voices")

    fun modelFile(variant: String = PrismSettings.getKokoroVariant()): File =
        File(homeDir(), "$variant.onnx")

    fun tokenizerFile(): File = File(homeDir(), "tokenizer.json")

    /**
     * The word-to-IPA lexicon, without which the model has nothing to say.
     *
     * Kokoro takes phonemes, not text, and the phonemiser is a dictionary -- see KokoroPhonemizer.
     * It comes from misaki, which is Kokoro's own G2P, so the phonemes the model receives are the
     * ones it was trained against rather than a third party's approximation of them.
     */
    fun lexiconFile(): File = File(homeDir(), "lexicon.json")

    fun isLexiconInstalled(): Boolean =
        lexiconFile().let { it.isFile && it.length() > 0 }

    fun voiceFile(voiceId: String): File = File(voicesDir(), "$voiceId.bin")

    /** Published size of each quantisation, so the picker can say what a choice costs. */
    fun variantSizeMb(variant: String): Int = when (variant) {
        PrismSettings.KOKORO_VARIANT_Q8F16 -> 86
        PrismSettings.KOKORO_VARIANT_QUANTIZED -> 92
        PrismSettings.KOKORO_VARIANT_FP16 -> 163
        else -> 325
    }

    /**
     * What a precision choice costs, and whether it works.
     *
     * ## q8f16 IS BROKEN UPSTREAM AND SAYS SO
     *
     * `model_q8f16.onnx` is the smallest export and was this build's default. It is incomplete: the
     * graph contains a `SkipLayerNormalization` node whose
     * `...albert_layers.0.full_layer_layer_norm.weight` input is not among the initializers, so ONNX
     * Runtime opens the session, reports the right three inputs, and throws `Missing Input` on the
     * first sentence. The download is not truncated -- an 82 MiB file arrives intact and hashes the
     * same twice -- and lowering the optimisation level makes no difference, which is what rules out
     * an optimiser fusion and puts it in the export.
     *
     * It is LEFT IN THE LIST rather than removed, because a newer upload to the same repository would
     * fix it and silently deleting a choice hides the reason it was removed. It is labelled instead.
     */
    fun variantLabel(variant: String): String = when (variant) {
        PrismSettings.KOKORO_VARIANT_Q8F16 ->
            "Quantised 8-bit, fp16 weights — 86 MB (BROKEN: this export is missing a weight)"
        PrismSettings.KOKORO_VARIANT_QUANTIZED -> "Quantised — 92 MB (recommended)"
        PrismSettings.KOKORO_VARIANT_FP16 -> "Half precision — 163 MB"
        else -> "Full precision — 325 MB"
    }

    val VARIANTS = listOf(
        PrismSettings.KOKORO_VARIANT_Q8F16,
        PrismSettings.KOKORO_VARIANT_QUANTIZED,
        PrismSettings.KOKORO_VARIANT_FP16,
        PrismSettings.KOKORO_VARIANT_FULL,
    )

    /**
     * True when everything needed to SPEAK is present: weights, vocabulary and lexicon.
     *
     * All three, deliberately. Any two of them is a Kokoro that loads and then cannot turn a
     * sentence into phonemes, which fails later and less clearly than not being installed at all.
     * Voices are checked separately, because which voices are needed depends on who is talking.
     */
    fun isModelInstalled(): Boolean =
        modelFile().let { it.isFile && it.length() > 0 } &&
            tokenizerFile().isFile &&
            isLexiconInstalled()

    fun isVoiceInstalled(voiceId: String): Boolean =
        voiceFile(voiceId).let { it.isFile && it.length() > 0 }

    /** Every voice already fetched, which is what a "downloaded" marker in the picker reads from. */
    fun installedVoices(): Set<String> =
        voicesDir().listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 }
            .map { it.nameWithoutExtension }
            .toSet()

    fun installedBytes(): Long =
        homeDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes everything, so the user can reclaim the space without hunting for a directory. */
    fun uninstall(): Boolean = homeDir().deleteRecursively()

    // -- Fetching -------------------------------------------------------------

    /**
     * Downloads the model and its tokenizer. Blocking; callers run it off the main thread.
     *
     * Written to a `.part` file and renamed on success, so an interrupted download cannot leave
     * something that looks installed. [onProgress] reports bytes so a 86 MB wait can show progress
     * rather than a spinner.
     */
    fun downloadModel(
        variant: String = PrismSettings.getKokoroVariant(),
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): String? {
        homeDir().mkdirs()
        val tokenizer = tokenizerFile()
        if (!tokenizer.isFile) {
            fetch(resolve("tokenizer.json"), tokenizer) { _, _ -> }
                ?.let { return "Could not fetch Kokoro's tokenizer: $it" }
        }

        if (!isLexiconInstalled()) {
            fetch(LEXICON_URL, lexiconFile()) { _, _ -> }
                ?.let { return "Could not fetch the pronunciation lexicon: $it" }
        }

        val target = modelFile(variant)
        if (target.isFile && target.length() > 0) return null

        return fetch(resolve("onnx/$variant.onnx"), target, onProgress)
            ?.let { "Could not fetch Kokoro ($variant): $it" }
    }

    /** Downloads one voice tensor. Blocking. Returns null on success, else a reason. */
    fun downloadVoice(voiceId: String): String? {
        if (isVoiceInstalled(voiceId)) return null
        voicesDir().mkdirs()
        return fetch(resolve("voices/$voiceId.bin"), voiceFile(voiceId)) { _, _ -> }
            ?.let { "Could not fetch the $voiceId voice: $it" }
    }

    private fun fetch(
        url: String,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): String? {
        // Unique per attempt, for the same reason the model downloader's is: two fetches sharing one
        // scratch file can interleave into a file with a hole in it, and the result looks like a
        // corrupt model rather than like two downloads.
        val staging = File(target.parentFile, "${target.name}.part-${System.nanoTime()}")
        return runCatching {
            target.parentFile?.mkdirs()
            staging.delete()

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                // Hugging Face serves weights from a CDN behind a redirect, and a plain
                // HttpURLConnection will not follow one that crosses protocols on its own.
                setRequestProperty("User-Agent", "Prism")
            }

            connection.inputStream.use { input ->
                val total = connection.contentLengthLong
                staging.outputStream().use { output ->
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

            if (staging.length() <= 0) throw IllegalStateException("nothing was downloaded")
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            PrismPlatform.log.info(TAG, "Fetched ${target.name} (${target.length() shr 20} MB)")
            null
        }.getOrElse {
            staging.delete()
            PrismPlatform.log.warn(TAG, "Fetch of $url failed: ${it.message}")
            it.message ?: "unknown error"
        }
    }
}
