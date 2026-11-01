package com.prism.launcher.modelcraft

import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.KokoroSeedVoice
import com.prism.launcher.speech.PocketTtsEngine
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.VoiceCloningModels
import java.io.File

/**
 * The voice a ModelCraft speech model speaks in.
 *
 * ## Why this is not the little network
 *
 * A model trained from a handful of clips can reconstruct the clips it was trained on -- its
 * teacher-forced frames transcribe perfectly -- but it cannot say a sentence it has never heard.
 * Speech is a long, precisely aligned sequence: a few minutes of audio and a phone-sized network do
 * not contain the phoneme-to-acoustics mapping of a language, and free-running such a network
 * produces the babble this page used to play. The trained network keeps its role (it measures the
 * voice and reports how the dataset fitted); the sound itself is rendered by [PocketTtsEngine], a
 * pretrained neural text-to-speech model that speaks any sentence in the voice of a short recording.
 *
 * That recording is the part training produces: [TtsTrainer] measures every clip, picks the
 * cleanest one per language and gender, and writes those names into the model. Asking for a
 * sentence then speaks it in that recording's voice -- the dataset's actual speaker, not an average
 * of everybody in it.
 *
 * ## What it costs
 *
 * The engine is seven ONNX files, about 166 MB, shared with the rest of Prism's voice cloning. It is
 * installed once, from Voice Cloning, and never per model. [installHint] says exactly that when it
 * is missing, because "no audio" with no reason is the failure this whole file exists to stop.
 */
object NeuralVoice {

    /** What the engine emits; every model it speaks for reports this as its output rate. */
    const val RATE = 24_000

    /** What [PocketTtsEngine] wants its reference recording at. */
    const val WORK_RATE = 22_050

    /**
     * A reference recording longer than this is cut.
     *
     * The engine conditions on a handful of seconds; a whole ten-minute interview would cost the
     * encoder pass for every sentence and add nothing but somebody else's pauses.
     */
    private const val MAX_REFERENCE_SECONDS = 30.0

    /** The engine files that are not on disk yet (empty when it is ready). */
    fun missingModels(): List<VoiceCloningModels.Model> =
        PocketTtsEngine.models.filterNot { VoiceCloningModels.isInstalled(it) }

    /**
     * The sentence to show when the engine is not installed, or null when it is.
     *
     * Names the screen and the size: "download the models" without either is a message a person
     * cannot act on.
     */
    fun installHint(missing: List<VoiceCloningModels.Model> = missingModels()): String? {
        if (missing.isEmpty()) return null
        return "The neural voice engine is not downloaded yet (" +
            "${VoiceCloningModels.totalSizeLabel(missing)}, ${missing.size} file(s) missing). " +
            "Install Pocket TTS from Voice Cloning once, then this model speaks through it on this " +
            "device."
    }

    /**
     * Speaks [text] through Pocket TTS itself, with no Kokoro attempt first.
     *
     * This is the explicitly selected Pocket engine, not the fallback: [NeuralVoice.speak] prefers
     * Kokoro for English even when Pocket is allowed, so routing a model that chose Pocket through
     * it would silently give the listener Kokoro. The reference is prepared the same way, and a
     * missing model set is reported before anything is decoded.
     */
    fun speakPocket(text: String, reference: SpeechClip, voiceId: String): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        installHint()?.let { throw IllegalStateException(it) }
        val limit = (MAX_REFERENCE_SECONDS * reference.sampleRate).toInt()
        val trimmed = if (reference.samples.size > limit) {
            SpeechClip(reference.samples.copyOf(limit), reference.sampleRate)
        } else {
            reference
        }
        val prepared = if (trimmed.sampleRate == WORK_RATE) trimmed else trimmed.resampled(WORK_RATE)
        val spoken = PocketTtsEngine.speakReference(text, voiceId, prepared.samples).samples
        if (spoken.isEmpty()) {
            throw IllegalStateException(
                "Pocket TTS produced no audio for that sentence. Try a shorter sentence, or " +
                    "reinstall the voice models from Voice Cloning.",
            )
        }
        return spoken
    }

    /**
     * Speaks [text] in the voice of [reference], as 24 kHz mono samples.
     *
     * [voiceId] is only a cache key: the engine prepares a voice once and reuses that state while
     * the id and the recording length stay the same, which is what makes a paragraph of sentences
     * cheap after the first one.
     *
     * @throws IllegalStateException with an actionable sentence when the engine is missing or the
     *         recording cannot be decoded -- a caller that catches it can show it verbatim.
     */
    fun speak(
        text: String,
        reference: File,
        voiceId: String = reference.name,
        language: String = "",
        gender: String = "",
        clone: Boolean = false,
        allowPocket: Boolean = true,
    ): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        requireEngine(allowPocket)
        val clip = AudioDecoding.read(reference)
            ?: throw IllegalStateException(
                "The recording ${reference.name} could not be decoded, so the voice has nothing to " +
                    "speak in. Replace it with a WAV or re-run training.",
            )
        return speak(text, clip, voiceId, language, gender, clone, allowPocket)
    }

    /**
     * The same, from an already-decoded clip.
     *
     * Used by a cloning model, whose reference was chosen on the form rather than by the trainer.
     */
    fun speak(
        text: String,
        reference: SpeechClip,
        voiceId: String,
        language: String = "",
        gender: String = "",
        clone: Boolean = false,
        allowPocket: Boolean = true,
    ): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        requireEngine(allowPocket)
        val limit = (MAX_REFERENCE_SECONDS * reference.sampleRate).toInt()
        val trimmed = if (reference.samples.size > limit) {
            SpeechClip(reference.samples.copyOf(limit), reference.sampleRate)
        } else {
            reference
        }
        val prepared = if (trimmed.sampleRate == WORK_RATE) trimmed else trimmed.resampled(WORK_RATE)
        // English first. A plain text-to-speech voice gets Kokoro directly, pitch-matched to the
        // dataset speaker: full-band, studio-clean, and measurably not muffled. A CLONE asks for
        // the reference's timbre specifically, so it pays the Seed-VC conversion -- which caps the
        // top octave, but that is the recording's own bandwidth, not a mastering fault. Other
        // languages stay on Pocket TTS, whose reference carries the language itself rather than
        // being read by an English G2P.
        if (language.isBlank() || language.startsWith("en", ignoreCase = true)) {
            val kokoro = com.prism.launcher.speech.KokoroSeedVoice
            if (clone) {
                kokoro.speak(text, prepared.samples, voiceId)?.let { return it }
            } else {
                kokoro.speakStudio(text, prepared.samples, language, gender)?.let { return it }
            }
        }
        // Pocket TTS is the known-good fallback and the path for non-English text. It is only
        // reached when the model allows it; a disabled fallback says why instead of changing
        // voice halfway through a sentence.
        if (!allowPocket) {
            throw IllegalStateException(
                "The built-in voice engine could not speak this text" +
                    (if (KokoroSeedVoice.isStudioInstalled()) {
                        " (the local studio voice does not cover this language)."
                    } else {
                        " because Kokoro is not installed."
                    }) +
                    " Turn on \"Allow Pocket TTS fallback\" for this model to use the multilingual " +
                    "engine, or install the built-in voice model.",
            )
        }
        installHint()?.let { throw IllegalStateException(it) }
        return PocketTtsEngine.speakReference(text, voiceId, prepared.samples).samples
    }

    /**
     * Fails early when there is no engine at all, with a sentence that names the switch.
     *
     * Called before the recording is decoded so a missing engine is reported as a missing engine,
     * not as an undecodable reference file.
     */
    private fun requireEngine(allowPocket: Boolean) {
        if (KokoroSeedVoice.isStudioInstalled()) return
        if (!allowPocket) {
            throw IllegalStateException(
                "The built-in voice engine is not installed on this device, and Pocket TTS fallback " +
                    "is turned off for this model. Install the built-in voice from Voice Cloning, or " +
                    "turn the fallback on in the model's audio settings.",
            )
        }
        installHint()?.let { throw IllegalStateException(it) }
    }
}
