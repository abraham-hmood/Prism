package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The JNI surface of `whisper_bridge`.
 *
 * Separate from [WhisperCppEngine] because the native method names are part of the ABI: the symbol the
 * linker exports is derived from the fully-qualified class name, so moving or renaming this class
 * silently breaks loading in a way that reports itself as `UnsatisfiedLinkError` naming a mangled symbol
 * nobody recognises. Keeping it minimal and separate makes that coupling visible.
 *
 * Loading is attempted ONCE and the outcome remembered. `System.loadLibrary` throws on every call after
 * a failure, and an engine that retried it per transcription would pay the exception cost and produce
 * the same answer — while a build that has the library gets it on first use either way.
 */
internal object WhisperNative {

    @Volatile private var attempted = false
    @Volatile private var loaded = false

    /** Why the library is not usable, for an engine that has to explain itself. */
    @Volatile var loadError: String = ""
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (attempted) return loaded
        attempted = true

        // DEPENDENCIES BY ABSOLUTE PATH FIRST, exactly as GgufInferenceService does, and for the same
        // reason. `java.library.path` is consulted for the library you NAME; its own imports are
        // resolved by the OS loader through the platform DLL search order, which does not include
        // java.library.path. So whisper_bridge.dll is found, fails to bind ggml.dll, and reports
        // "Can't find dependent libraries" -- naming nothing. Loading each dependency in link order
        // avoids the search entirely.
        //
        // This was not a theoretical risk: it is exactly how this failed the first time it ran.
        for (name in DEPENDENCIES) {
            val found = libraryRoots().asSequence()
                .flatMap { dir ->
                    sequenceOf("$name.dll", "lib$name.so", "lib$name.dylib").map { File(dir, it) }
                }
                .firstOrNull { it.isFile }
                ?: continue
            runCatching { System.load(found.absolutePath) }
        }

        loaded = runCatching {
            System.loadLibrary("whisper_bridge")
            true
        }.getOrElse {
            loadError = it.message ?: it::class.simpleName.orEmpty()
            PrismPlatform.log.info("PrismWhisper", "whisper_bridge not loaded: $loadError")
            false
        }
        if (loaded) PrismPlatform.log.info("PrismWhisper", "whisper_bridge loaded")
        return loaded
    }

    /**
     * ggml only, and in link order: ggml-base underpins the backends and ggml links them.
     *
     * llama is deliberately absent. whisper_bridge links ggml and not llama -- see the whisper
     * CMakeLists -- so pulling llama in here would load a large library a dictation needs nothing from.
     */
    private val DEPENDENCIES = listOf("ggml-base", "ggml-cpu", "ggml")

    private fun libraryRoots(): List<File> {
        val fromPath = System.getProperty("java.library.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
        // Android puts them in a directory the platform knows and java.library.path does not always
        // name, so the host is asked as well.
        val fromHost = runCatching { PrismPlatform.host.nativeLibraryDir() }.getOrNull()
        return if (fromHost != null) listOf(fromHost) + fromPath else fromPath
    }

    external fun nativeLoad(modelPath: String): Boolean

    external fun nativeTranscribe(
        modelPath: String,
        samples: FloatArray,
        language: String,
        threads: Int,
    ): String?

    external fun nativeRelease()
}

/**
 * Whisper, running on this machine.
 *
 * ## Why this is the route the plan wanted
 *
 * PHASE 36 says replacing Android's `SpeechRecognizer` with whisper.cpp makes dictation MORE consistent
 * across platforms, not less, and that is exactly right: `SpeechRecognizer` is a different
 * implementation on every OEM's build of Android and does not exist at all on a desktop. One model file
 * transcribing the same way everywhere is the only version of dictation that behaves identically on both
 * — and unlike the cloud route it costs nothing per use and the audio never leaves the device.
 *
 * ## Why it is not the default
 *
 * It needs a model. The smallest useful one is about 75 MB and the good ones run to gigabytes, so a
 * fresh install cannot transcribe with it until something has been downloaded. Until then this engine
 * says which of the two things it is missing — the library or the model — rather than reporting a
 * generic unavailability the user cannot act on.
 *
 * ## Why the samples are converted here
 *
 * whisper takes normalised floats; every recorder in Prism produces 16-bit PCM, because that is what
 * both platforms' capture APIs produce. One of the two has to convert, and doing it on this side means
 * the conversion happens once, next to the code that needs it, rather than in each recorder.
 */
class WhisperCppEngine : TranscriptionEngine {

    override val id = "whisper"
    override val label = "Whisper (on-device)"
    override val description =
        "whisper.cpp running locally. Free per use, works offline, and transcribes identically on " +
            "desktop and Android — which is what makes it worth a model download."

    override fun availability(): ImageGenerator.Availability {
        if (!WhisperNative.ensureLoaded()) {
            return ImageGenerator.Availability.Unavailable(
                "The whisper_bridge library is not loaded" +
                    WhisperNative.loadError.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() +
                    ". On desktop, build it with the buildWhisper Gradle task."
            )
        }
        val path = PrismSettings.getWhisperModelPath()
        if (path.isBlank()) {
            return ImageGenerator.Availability.Unavailable(
                "No whisper model chosen. Point Prism at a ggml whisper model — ggml-base.en.bin is " +
                    "about 150 MB and enough for dictation."
            )
        }
        if (!File(path).isFile) {
            return ImageGenerator.Availability.Unavailable("The chosen whisper model is missing: $path")
        }
        return ImageGenerator.Availability.Ready
    }

    override fun transcribe(
        pcm16: ByteArray,
        sampleRate: Int,
        language: String?,
        onStage: ((String) -> Unit)?,
    ): String? {
        if (!WhisperNative.ensureLoaded()) return null
        val model = PrismSettings.getWhisperModelPath()
        if (model.isBlank() || !File(model).isFile) return null

        // Resampling is NOT done here. whisper wants 16 kHz and every recorder in Prism captures at it;
        // a clip at another rate would transcribe as gibberish rather than fail, so it is refused.
        if (sampleRate != Dictation.SAMPLE_RATE) {
            PrismPlatform.log.error(
                "PrismWhisper",
                "Refusing ${sampleRate}Hz audio; whisper needs ${Dictation.SAMPLE_RATE}Hz",
            )
            return null
        }

        onStage?.invoke("Converting ${"%.1f".format(Dictation.durationSeconds(pcm16, sampleRate))}s…")
        val samples = toFloats(pcm16)

        onStage?.invoke("Transcribing on this device…")
        val text = WhisperNative.nativeTranscribe(
            model,
            samples,
            language.orEmpty(),
            // Leaves a core for the UI. whisper saturates whatever it is given, and a transcription
            // that freezes the window it was started from reads as a hang.
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8),
        )
        // The bridge returns an empty string when a transcription is already running, which is a
        // different thing from silence and is reported as such.
        return if (text.isNullOrBlank()) null else text
    }

    /**
     * 16-bit little-endian PCM to normalised floats.
     *
     * Divided by 32768 rather than 32767: the negative range of a signed 16-bit sample goes one further
     * than the positive, so dividing by 32767 lets the most negative sample become slightly less than
     * -1.0 and clip. It is a small error that shows up as distortion on loud audio, which a speech model
     * hears as noise.
     */
    private fun toFloats(pcm16: ByteArray): FloatArray {
        val count = pcm16.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val low = pcm16[i * 2].toInt() and 0xFF
            val high = pcm16[i * 2 + 1].toInt()
            out[i] = ((high shl 8) or low).toShort() / 32768f
        }
        return out
    }

    /** Frees the native model. Worth calling when dictation is done with for a while. */
    fun release() {
        if (WhisperNative.ensureLoaded()) runCatching { WhisperNative.nativeRelease() }
    }

    companion object {
        /**
         * The model files whisper.cpp publishes, smallest first.
         *
         * Listed so a UI can offer them rather than making the user find the naming convention. The
         * `.en` variants are English-only and meaningfully better at English than the multilingual model
         * of the same size, which is the trade worth showing rather than hiding behind one recommendation.
         */
        val KNOWN_MODELS = listOf(
            "ggml-tiny.en.bin" to "75 MB — English only, fastest, roughest",
            "ggml-base.en.bin" to "142 MB — English only, good enough for dictation",
            "ggml-small.en.bin" to "466 MB — English only, noticeably better",
            "ggml-base.bin" to "142 MB — multilingual",
            "ggml-small.bin" to "466 MB — multilingual",
            "ggml-medium.bin" to "1.5 GB — multilingual, slow on a phone",
        )

        /** Where the files come from, for a UI that offers to fetch one. */
        fun downloadUrl(fileName: String): String =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    }
}
