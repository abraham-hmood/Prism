package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.TtsEngine
import java.io.File

/**
 * A ModelCraft speech model, plugged into Prism's speech system.
 *
 * ## Why this exists
 *
 * A model that can only be heard from the ModelCraft test box is a demo. This is the other half:
 * once a trained model is chosen as the imported speech model, every caller that already speaks
 * through [TtsEngine] -- a reply, call mode, a preview -- gets it without knowing it exists.
 *
 * ## Text-to-speech and cloning are one engine
 *
 * Both tasks are the same model. A text-to-speech build speaks in the dataset's own cleanest
 * recording; a cloning build conditions every sentence on its reference recording instead. The
 * reference is read here rather than in the trainer because this is the only place that knows
 * which recording the model was built around outside the dataset folder.
 *
 * ## Failure is a sentence, not silence
 *
 * [TtsEngine] promises exactly one callback on the main thread. Every path here posts exactly one:
 * blank text is success, a missing model is a reason, a synthesis that produced nothing is a reason.
 */
class ModelCraftSpeechEngine private constructor(
    private val directory: File,
    private val trained: TtsTrainer.Trained,
    private val task: ModelCraft.AudioType,
    private val reference: SpeechClip?,
) : TtsEngine {

    override val displayName: String =
        "ModelCraft " + trained.name.ifBlank { directory.name }

    @Volatile
    private var generation = 0

    /** The language this model was trained on, for callers that do not supply one. */
    private val language: String = trained.languages.firstOrNull() ?: trained.config.defaultLanguage

    override fun isReady(): Boolean = true

    override fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        localeTag: String?,
        onDone: (error: String?) -> Unit,
    ) {
        if (text.isBlank()) {
            onDone(null)
            return
        }
        val mine = ++generation
        Thread({
            var failure: String? = null
            val samples = runCatching {
                trained.synthesize(
                    text = text,
                    language = localeTag?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: language,
                    gender = trained.config.defaultGender,
                    reference = if (task == ModelCraft.AudioType.VOICE_CLONING) reference else null,
                    speed = speed,
                )
            }.getOrElse { error ->
                PrismPlatform.log.warn("ModelCraftSpeech", "Synthesis failed: ${error.message}")
                failure = error.message
                    ?: "The ModelCraft speech model could not speak that sentence."
                FloatArray(0)
            }
            if (mine != generation) return@Thread
            if (samples.isEmpty()) {
                PrismPlatform.main.post {
                    onDone(failure ?: "The ModelCraft speech model produced no audio for that sentence.")
                }
                return@Thread
            }
            val problem = PrismPlatform.audio.play(samples, trained.outputRate) { mine != generation }
            if (mine != generation) return@Thread
            PrismPlatform.main.post { onDone(problem) }
        }, "modelcraft-speech").apply { isDaemon = true; start() }
    }

    override fun stop() {
        generation++
        PrismPlatform.audio.stop()
    }

    override fun release() {
        stop()
    }

    companion object {

        /** The file a settings picker will hand over: the model's metadata document. */
        const val METADATA = TtsTrainer.METADATA_FILE

        /**
         * Opens [path], which may be the model's folder or its `tts_model.json`.
         *
         * Null means "not a trained ModelCraft speech model", and the caller keeps whatever engine
         * it had. A folder that has a metadata file but no weights is not ready and is reported as
         * such by [ImportedAudioEngine], not silently opened.
         */
        fun openOrNull(path: String): ModelCraftSpeechEngine? {
            if (path.isBlank()) return null
            val file = File(path)
            val directory = when {
                file.isDirectory -> file
                file.isFile && file.name.equals(TtsTrainer.METADATA_FILE, true) -> file.parentFile
                else -> return null
            } ?: return null
            val trained = TtsTrainer.load(directory) ?: return null
            val document = runCatching {
                JSONObject(File(directory, CraftedAudioModel.FILE).readText())
            }.getOrNull()
            val task = ModelCraft.AudioType.of(
                document?.optString("task", "")?.takeIf { it.isNotBlank() }
                    ?: trained.task,
            )
            val reference = if (task == ModelCraft.AudioType.VOICE_CLONING) {
                referenceOf(directory, document)
            } else {
                null
            }
            if (task == ModelCraft.AudioType.VOICE_CLONING && reference == null) {
                PrismPlatform.log.warn(
                    "ModelCraftSpeech",
                    "The cloning model in ${directory.name} has no reference recording; it will speak " +
                        "in its mean voice",
                )
            }
            return ModelCraftSpeechEngine(directory, trained, task, reference)
        }

        private fun referenceOf(directory: File, document: JSONObject?): SpeechClip? {
            val named = document?.optString("reference_clip", "").orEmpty()
            val candidates = listOfNotNull(
                named.takeIf { it.isNotBlank() }?.let { File(directory, File(it).name) },
                File(directory, "reference.wav"),
            )
            for (candidate in candidates) {
                if (!candidate.isFile) continue
                val decoded = AudioDecoding.read(candidate) ?: continue
                if (decoded.samples.isNotEmpty()) return decoded
            }
            return null
        }
    }
}
