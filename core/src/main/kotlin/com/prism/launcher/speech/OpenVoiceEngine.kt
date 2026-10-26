package com.prism.launcher.speech

import com.prism.core.PrismPlatform

/**
 * OpenVoice v2: the engine Prism's cloning shipped with, and still the smallest one-pass option.
 *
 * Two graphs, both MIT, both verified against their author's PyTorch reference (identity
 * conversion reconstructs the source; embeddings track speakers). The encoder turns a reference
 * spectrogram into a 256-value tone direction -- that direction is the per-voice artifact cached
 * at [prepare]; the converter re-voices Kokoro's audio between two such directions.
 *
 * The stored tone file is the ORIGINAL `<voice>.tone` layout with no header, deliberately: every
 * clone saved before engines existed has exactly that file, and reading it as-is is what makes
 * old clones keep speaking with no migration.
 */
object OpenVoiceEngine : CloneEngine {

    override val id: String = "openvoice"
    override val label: String = "OpenVoice v2"
    override val summary: String =
        "Tone-colour transfer · one fast pass · MIT"

    private const val HF = "https://huggingface.co/TigreGotico/voiceclonnx-openvoice-v2/resolve/main"

    val ENCODER = VoiceCloningModels.Model(
        id = "encoder",
        label = "Speaker encoder",
        file = "tone_ref_encoder.onnx",
        sizeBytes = 3_259_275L,
        url = "$HF/tone_ref_encoder.onnx",
        sha256 = "3dd4918cab90e1acf7fa5c6f7539c27710e7a3cdfba550468c5ea49399178bf7",
    )
    val CONVERTER = VoiceCloningModels.Model(
        id = "converter",
        label = "Voice converter",
        file = "tone_converter.onnx",
        sizeBytes = 128_051_288L,
        url = "$HF/tone_converter.onnx",
        sha256 = "7d7ee834c230037ead5cd6b64d44fcd842fbab5cd4cf6fe3ab381fad763325e9",
    )

    override val models: List<VoiceCloningModels.Model> = listOf(ENCODER, CONVERTER)

    override fun prepare(voiceId: String, reference: FloatArray, onStage: (String) -> Unit) {
        if (ClonedVoices.toneFor(voiceId) != null) return
        onStage("Listening to your voice…")
        val tone = runCatching { VoiceConverter.embedding(reference, VoiceCloner.WORK_RATE) }
            .getOrElse {
                PrismPlatform.log.error(TAG, "Could not encode the voice sample", it)
                throw IllegalStateException("Could not read that voice: ${it.message}")
            }
        ClonedVoices.writeTone(voiceId, tone)
    }

    override fun convert(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray?,
    ): CloneEngine.Converted {
        var target = ClonedVoices.toneFor(voiceId)
        if (target == null) {
            if (reference == null) {
                throw IllegalStateException(
                    "This voice has no stored tone and no reference recording to make one from " +
                        "-- record it again in Voice Cloning.",
                )
            }
            // Saved while another engine was active: compute and cache the tone now, once.
            prepare(voiceId, reference)
            target = ClonedVoices.toneFor(voiceId)
                ?: throw IllegalStateException("The speaker encoder produced no tone direction")
        }
        val sourceTone = VoiceConverter.embedding(source, sourceRate)
        val converted = VoiceConverter.convert(source, sourceRate, sourceTone, target)
        return CloneEngine.Converted(converted, VoiceConverter.RATE)
    }

    override fun close() = VoiceConverter.close()

    private const val TAG = "PrismSpeech"
}
