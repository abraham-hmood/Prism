package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.datasetcraft.DatasetReferences
import com.prism.launcher.datasetcraft.DatasetTranscription
import com.prism.launcher.datasetcraft.GameAssets
import com.prism.launcher.quant.PrismQuantizer
import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.VocalFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * One model the user is building.
 *
 * A record, not a live object: the tab it was created on, the architecture choices, every field the
 * form collected, and the dataset folders it points at. Training reads this and nothing else, which is
 * why the same model can be resumed from the list a week later.
 */
class CraftedModel(
    val id: String,
    var name: String,
    val kind: ModelCraft.Kind,
    /** One of [ModelCraft.architectures]: the broad shape of the network. */
    var architecture: String,
    /**
     * The named architecture family inside [kind] -- DiT, LDMVFI, VITS and the rest. Kept beside the
     * broad shape because "diffuser" alone does not say which diffuser, and the field set of a model
     * is chosen from the family.
     */
    var family: String,
    /** Field key -> value as the user typed it. Unset keys fall back to [ModelCraft.Field.default]. */
    val params: MutableMap<String, String> = linkedMapOf(),
    /** Every file the user imported, target folder resolved at attach time. */
    val dataset: MutableList<String> = mutableListOf(),
    /** What the dataset turned out to be: rows in each CSV, files by type. Written by [ModelCraft.prepare]. */
    var datasetSummary: String = "",
    var status: String = ModelCraft.STATUS_DRAFT,
    var note: String = "",
    var recipePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun param(key: String): String =
        params[key]?.takeIf { it.isNotBlank() }
            ?: ModelCraft.fields.getValue(kind).firstOrNull { it.key == key }?.default.orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("architecture", architecture)
        put("family", family)
        val paramJson = JSONObject()
        params.forEach { (k, v) -> paramJson.put(k, v) }
        put("params", paramJson)
        put("dataset", JSONArray(dataset))
        put("datasetSummary", datasetSummary)
        put("status", status)
        put("note", note)
        put("recipePath", recipePath)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun newId(): String =
            "mc-" + System.currentTimeMillis().toString(36) + "-" +
                UUID.randomUUID().toString().take(6)

        fun fromJson(json: JSONObject): CraftedModel {
            val model = CraftedModel(
                id = json.optString("id", newId()),
                name = json.optString("name", "Untitled model"),
                kind = ModelCraft.Kind.of(json.optString("kind", "TEXT")),
                architecture = json.optString("architecture", ModelCraft.architectures.first()),
                family = json.optString("family", ""),
                status = json.optString("status", ModelCraft.STATUS_DRAFT),
                note = json.optString("note", ""),
                recipePath = json.optString("recipePath", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            )
            model.datasetSummary = json.optString("datasetSummary", "")
            json.optJSONObject("params")?.let { params ->
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    model.params[key] = params.optString(key, "")
                }
            }
            json.optJSONArray("dataset")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i, "").takeIf { it.isNotBlank() }?.let { model.dataset.add(it) }
                }
            }
            return model
        }
    }
}

/**
 * ModelCraft: the user's own models, from a dropdown, a dataset and twenty-odd numbers.
 *
 * ## What this actually does
 *
 * Two things, and both of them for real:
 *
 *  1. **Prepares a dataset.** Imported files are copied into the model's own folder, every CSV is
 *     parsed (that is where the caption or transcript for each media file lives, which is why a CSV
 *     among the picks is detected rather than treated as another sample), and a manifest is written
 *     naming each file and the row that describes it. For a frame-interpolator model the video files
 *     are also broken into frames, by the platform, into a folder the trainer reads.
 *
 *  2. **Writes the training recipe.** The chosen architecture, the named family, and every field the
 *     form collected are written to `recipe.json` in a shape a training script reads as its config.
 *     [runner] is the seam for actually running one: when the platform has a trainer for this kind of
 *     model it is called with the recipe and reports its stages; when it does not, the model's status
 *     says so and names the recipe file rather than pretending a run happened.
 *
 * ## Why the field lists live here
 *
 * They are the contract between the form and the recipe. A page that invented its own field names
 * would fill a recipe with keys no trainer knows, so the fields, their defaults and their order are
 * one list in one place, and both platforms' creator layouts are drawn from it.
 */
object ModelCraft {

    const val STATUS_DRAFT = "draft"
    const val STATUS_READY = "recipe-ready"
    const val STATUS_TRAINING = "training"
    const val STATUS_TRAINED = "trained"
    const val STATUS_FAILED = "failed"

    /** The kinds of model the creator can make, one per tab. */
    enum class Kind(val label: String, val modelType: String) {
        TEXT("Text", "Text generation"),
        IMAGE("Image", "Image generation"),
        AUDIO("Audio", "Audio generation"),
        INTERPOLATION("Frame interpolator", "Frame interpolation"),
        VIDEO("Video", "Video generation"),

        /**
         * Game assets: meshes, scenes, props and the worlds they are placed in.
         *
         * A sixth kind rather than a flavour of IMAGE because what it produces is not a picture:
         * the artifact is a shape model, the dataset is meshes and textures, and the consumer is
         * AssetCraft rather than a gallery. It shares the broad architecture list with every other
         * kind -- including the diffuser and byte-latent shapes -- because those are network shapes,
         * not output formats.
         */
        GAME("Game", "Game asset generation");

        companion object {
            fun of(raw: String): Kind =
                entries.firstOrNull { it.name.equals(raw.trim(), true) } ?: TEXT
        }
    }

    /**
     * The broad architectures, identical on every tab.
     *
     * These are the shapes a network can have, not what it generates: a diffuser that makes pictures
     * and a diffuser that makes speech are both diffusion models, and the tab says which one. That is
     * why this list does not change per kind -- the per-kind choices are [archTypes] instead.
     */
    val architectures: List<String> = listOf(
        "Normal transformer model (decoder only)",
        "Diffuser model",
        "Byte latent transformer model",
        "Encoder only",
        "Encoder and decoder",
    )

    /** One named family inside a kind. */
    data class ArchType(val name: String, val note: String)

    /**
     * The families each kind can be built as.
     *
     * Researched from the literature rather than invented: these are the architecture lineages a
     * practitioner would actually pick between, from RIFE and LDMVFI for interpolation to VITS and
     * flow-matching DiT for speech. The note is one line of what the family is for, because a
     * dropdown of fifteen names with no hints is a quiz.
     */
    val archTypes: Map<Kind, List<ArchType>> = mapOf(
        Kind.TEXT to listOf(
            ArchType("Decoder-only causal transformer", "The GPT shape: predicts the next token from what precedes it."),
            ArchType("Encoder-decoder transformer", "The T5/BART shape: reads the input both ways, then generates."),
            ArchType("Encoder-only masked language model", "The BERT shape: fills in masked spans of text."),
            ArchType("Prefix-LM / infilling", "A prompt is context and the missing span is generated."),
            ArchType("Sparse mixture of experts", "Routes each token to a few expert layers for more capacity."),
            ArchType("Mamba (selective state space)", "Input-dependent recurrent state, linear-time sequence modelling."),
            ArchType("RWKV", "Transformer-like training with recurrent, token-by-token inference."),
            ArchType("RetNet", "Retention instead of attention: parallel, recurrent or chunked."),
            ArchType("Hyena", "Gated long convolutions in place of attention."),
            ArchType("Byte latent transformer", "Models raw bytes grouped into variable-size patches."),
            ArchType("Diffusion language model", "Starts from masked text and denoises tokens in place."),
            ArchType("Autoregressive RNN / LSTM", "An older, still useful lightweight recurrent design."),
        ),
        Kind.IMAGE to listOf(
            ArchType("Latent diffusion (U-Net)", "Denoises a compressed latent; the Stable Diffusion 1.x shape."),
            ArchType("Diffusion transformer (DiT)", "Transformer blocks denoise patches instead of a U-Net."),
            ArchType("Multimodal diffusion transformer (MM-DiT)", "Separate text and image streams joined in attention."),
            ArchType("Rectified flow / flow matching", "Learns a vector field from noise to image."),
            ArchType("Pixel-space DDPM (U-Net)", "Denoises full-resolution pixels, no latent codec."),
            ArchType("Generative adversarial network", "Generator against discriminator, style-controllable."),
            ArchType("Variational autoencoder", "Samples latents and decodes them into an image."),
            ArchType("VQ-VAE / VQGAN tokenizer", "Discrete image codes a prior can be trained over."),
            ArchType("Pixel autoregressive (PixelCNN)", "Generates pixels one at a time, each conditioned on the last."),
            ArchType("Autoregressive image-token transformer", "Predicts discrete image tokens from a prompt."),
            ArchType("Visual autoregressive (VAR)", "Generates coarse-to-fine, one resolution scale at a time."),
        ),
        Kind.AUDIO to listOf(
            ArchType("Autoregressive waveform (WaveNet)", "Generates one audio sample at a time, conditioned on earlier ones."),
            ArchType("Autoregressive spectrogram TTS (Tacotron)", "Produces acoustic frames in sequence, then vocodes."),
            ArchType("Non-autoregressive duration TTS (FastSpeech)", "Predicts every frame in parallel from a duration model."),
            ArchType("Normalizing-flow TTS (Glow-TTS)", "Invertible flows with learned text-to-speech alignment."),
            ArchType("VAE + flow + GAN (VITS)", "Variational inference, flows and adversarial training, end to end."),
            ArchType("Diffusion TTS", "Denoises spectrograms or latents, conditioned on text and speaker."),
            ArchType("Flow-matching DiT TTS (F5-style)", "A diffusion transformer with flow matching, non-autoregressive."),
            ArchType("GAN vocoder (HiFi-GAN)", "Turns mel frames into a waveform under an adversarial loss."),
            ArchType("Autoregressive codec language model (AudioLM)", "Predicts discrete codec tokens, then decodes them."),
            ArchType("Masked parallel codec transformer (SoundStorm)", "Fills in masked codec tokens in parallel."),
            ArchType("Latent audio diffusion (AudioLDM)", "Generates a compressed audio representation, then decodes it."),
            ArchType("Cascaded flow-matching music", "Semantic features first, acoustics second, in stages."),
            ArchType("Symbolic autoregressive music transformer", "Predicts score events; a synth renders them."),
        ),
        Kind.INTERPOLATION to listOf(
            ArchType("Motion-compensated / block matching", "Estimates motion per block and warps it toward the middle."),
            ArchType("Bidirectional optical flow", "Both directions estimated, warped to the target time, blended."),
            ArchType("Occlusion-aware flow and splatting", "Visibility handling so occlusions do not tear."),
            ArchType("Flow-guided learned synthesis", "Predicted flow, then a network refines the new frame."),
            ArchType("Voxel flow", "A space-time motion field mapping pixels to in-between positions."),
            ArchType("Kernel-based adaptive convolution", "Learns spatially varying filters instead of explicit flow."),
            ArchType("Adaptive separable convolution", "Separate horizontal and vertical kernels; the SepConv shape."),
            ArchType("Phase-based (PhaseNet)", "Motion as a change of image-filter phase."),
            ArchType("Hybrid structure-motion fusion", "Structure and motion estimates combined and refined."),
            ArchType("Transformer-based", "Attention between frames handles large motion."),
            ArchType("GAN-based", "Adversarial training on the synthesized middle frame."),
            ArchType("Mamba / SSM-based", "State-space modelling of the inter-frame relation."),
            ArchType("Latent diffusion (LDMVFI)", "The missing frame as conditional generation in a latent space."),
        ),
        Kind.VIDEO to listOf(
            ArchType("Latent video diffusion (VideoLDM)", "Temporal layers added to an image-diffusion backbone."),
            ArchType("Spatiotemporal patch DiT", "Denoises patches arranged across space and time."),
            ArchType("Factorized spatial-temporal transformer", "Separate spatial and temporal attention."),
            ArchType("Pixel-space 3D U-Net diffusion", "3D convolutions over the whole video volume."),
            ArchType("Autoregressive video-token transformer", "Predicts discrete video tokens in sequence."),
            ArchType("Masked-token video transformer (MaskGIT)", "Iteratively fills masked tokens in parallel."),
            ArchType("Chunk-autoregressive denoiser", "Generates successive chunks, conditioned on the last."),
            ArchType("Causal autoregressive diffusion transformer", "A bidirectional model adapted for streaming frames."),
            ArchType("Pyramidal flow-matching DiT", "Linked coarse-to-fine spatial and temporal stages."),
            ArchType("Recurrent video predictor (ConvLSTM)", "Predicts the next frames from a recurrent state."),
            ArchType("Video GAN", "Adversarial generation, appearance and motion separated."),
            ArchType("Action-conditioned world model", "Predicts future frames from supplied actions."),
            ArchType("Keyframe + temporal upsampling cascade", "Sparse keyframes first, then interpolation stages."),
        ),
        Kind.GAME to listOf(
            ArchType("Mesh diffusion (MeshDiffusion)", "Denoises vertex and face structure, then a mesh is assembled."),
            ArchType("Gaussian splat generator", "Predicts oriented points that render as a soft volumetric asset."),
            ArchType("Neural radiance field (NeRF)", "A coordinate network that paints density and colour through a volume."),
            ArchType("Signed distance field (DeepSDF)", "Predicts the distance to a surface; the mesh is its zero level set."),
            ArchType("Octree / voxel diffusion", "Generates occupied cells coarse-to-fine, then extracts a surface."),
            ArchType("Autoregressive mesh transformer (MeshGPT)", "Predicts face tokens in sequence from a shape prefix."),
            ArchType("Shape-token latent transformer", "Models a compact shape descriptor as a sequence of learned tokens."),
            ArchType("Scene-graph transformer", "Places objects, transforms and materials as a graph of scene nodes."),
            ArchType("Procedural / grammar generator", "A learned rule set that expands into geometry and variation."),
            ArchType("Texture and sprite lattice", "Builds billboards, tiles and atlases for 2D and 2.5D games."),
            ArchType("Implicit surface hypernetwork", "A small network produces the weights of a per-asset surface network."),
            ArchType("Point-cloud completion", "Fills a sparse partial scan out into a complete prop or creature."),
        ),
    )

    /**
     * One training input. [default] is what an untouched form contributes to the recipe.
     *
     * [choices] makes a field a dropdown rather than a text box. Empty -- the usual case -- means
     * free text, which is what a learning rate or a path wants. The list is here rather than in the
     * page so Android and desktop cannot offer different values for the same recipe key.
     */
    data class Field(
        val key: String,
        val label: String,
        val default: String,
        val choices: List<String> = emptyList(),
    )

    /**
     * The precisions a trained model can be stored at, in the order the dropdown shows them.
     *
     * These are exactly [PrismQuantizer.Level]'s twelve formats, read from the enum rather than
     * retyped: the list a form offers and the list the quantiser can actually write have to be the
     * same list, or a run finishes with a level nothing on the device can produce. That includes the
     * four sub-2-bit ones (binary, ternary, quaternary, quinary), which are real ggml types in this
     * build rather than labels -- see PrismQuantizer.
     *
     * Q8_0 is the default because it is the only level here that is effectively lossless: a run that
     * leaves the setting alone gets a model that behaves like the one it trained.
     */
    val quantizations: List<String> = PrismQuantizer.Level.entries.map { it.fileSuffix }

    /** The recipe key the quantization dropdown writes. Shared by every kind. */
    const val KEY_QUANTIZATION = "quantization"

    /** What an untouched form asks for. See [quantizations]. */
    const val DEFAULT_QUANTIZATION = "Q8_0"

    /**
     * The level named by [label], or null when it is not one of [quantizations].
     *
     * Null rather than a fallback: a recipe naming a precision this build cannot write should fail
     * validation where somebody can see it, not train for an hour and then quietly store float32.
     */
    fun quantizationLevel(label: String): PrismQuantizer.Level? =
        PrismQuantizer.Level.entries.firstOrNull { it.fileSuffix.equals(label.trim(), true) }

    /** The model's chosen precision, or [DEFAULT_QUANTIZATION] when the field is blank. */
    fun quantization(model: CraftedModel): String =
        model.param(KEY_QUANTIZATION).trim().ifBlank { DEFAULT_QUANTIZATION }

    /** Writes the dropdown's value in the form both platforms and the recipe read. */
    fun setQuantization(model: CraftedModel, label: String) {
        val match = quantizationLevel(label)
        model.params[KEY_QUANTIZATION] = match?.fileSuffix ?: DEFAULT_QUANTIZATION
    }

    /**
     * Every field each kind's recipe carries.
     *
     * Grouped in the order a training script reads them: what is being trained, on what data, then the
     * optimiser, then the schedule, then the bookkeeping. Defaults are the commonly quoted starting
     * points from the frameworks this borrows its vocabulary from (axolotl and Trainer for text,
     * diffusers for image and video, Coqui for speech, RIFE for interpolation).
     */
    val fields: Map<Kind, List<Field>> = mapOf(
        Kind.TEXT to listOf(
            Field("base_model", "Base model or checkpoint", ""),
            Field("tokenizer", "Tokenizer", "same as base"),
            Field("vocabulary", "Vocabulary size (words)", "8192"),
            Field("dim", "Model width (d_model)", "96"),
            Field("layers", "Transformer layers", "4"),
            Field("heads", "Attention heads", "4"),
            Field("dataset_format", "Dataset format", "conversation"),
            Field("chat_template", "Chat template", "model default"),
            Field("train_split", "Training split", "train"),
            Field("validation_split", "Validation split", "5%"),
            Field("test_split", "Test split", "test"),
            Field("max_seq_length", "Max sequence length", "4096"),
            Field("packing", "Pack short examples", "true"),
            Field("loss_on_prompt", "Loss on prompt tokens", "false"),
            Field("finetuning_type", "Fine-tuning type", "lora"),
            Field("lora_rank", "LoRA rank (r)", "16"),
            Field("lora_alpha", "LoRA alpha", "16"),
            Field("lora_dropout", "LoRA dropout", "0.05"),
            Field("lora_targets", "LoRA target modules", "q_proj,k_proj,v_proj,o_proj"),
            // THE PRECISION THE MODEL IS STORED AT, set before the run rather than after it. On a
            // fine-tune it is the precision the base weights are loaded at; on a from-scratch build
            // it is the precision the trainer writes. Always in the recipe, including from scratch:
            // unlike the base fields this one still means something with no base to load.
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("batch_size", "Batch size", "2"),
            Field("grad_accumulation", "Gradient accumulation steps", "8"),
            Field("learning_rate", "Learning rate", "0.0001"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("weight_decay", "Weight decay", "0.01"),
            Field("lr_scheduler", "LR scheduler", "cosine"),
            Field("warmup_ratio", "Warmup ratio", "0.03"),
            Field("max_steps", "Max steps (overrides epochs)", "0"),
            Field("max_grad_norm", "Gradient clipping", "1.0"),
            Field("precision", "Precision", "bf16"),
            Field("gradient_checkpointing", "Gradient checkpointing", "true"),
            Field("seed", "Seed", "42"),
            Field("eval_steps", "Evaluate every N steps", "200"),
            Field("temperature", "Sampling temperature", "0.5"),
            Field("top_k", "Sampling top-k", "10"),
            Field("repetition_penalty", "Repetition penalty", "1.15"),
            Field("save_steps", "Save every N steps", "500"),
            Field("save_limit", "Checkpoints to keep", "3"),
            Field("logging_steps", "Log every N steps", "10"),
            Field("resume_from", "Resume from checkpoint", ""),
            Field("output_dir", "Output directory", "model/"),
        ),
        Kind.IMAGE to listOf(
            Field("base_checkpoint", "Base checkpoint", ""),
            Field("training_method", "Training method", "lora"),
            Field("caption_column", "Caption column", "text"),
            Field("train_split", "Training split", "train"),
            Field("validation_split", "Validation split", "validation"),
            Field("test_split", "Test split", "test"),
            Field("instance_prompt", "Instance prompt", ""),
            Field("resolution", "Resolution", "1024"),
            Field("center_crop", "Center crop", "true"),
            Field("random_flip", "Random horizontal flip", "false"),
            Field("aspect_buckets", "Aspect-ratio buckets", "true"),
            Field("batch_size", "Batch size", "2"),
            Field("grad_accumulation", "Gradient accumulation steps", "2"),
            Field("max_steps", "Max steps (overrides epochs)", "0"),
            Field("learning_rate", "Learning rate", "0.0001"),
            Field("lr_scheduler", "LR scheduler", "cosine"),
            Field("warmup_steps", "LR warmup steps", "100"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("adam_beta1", "Adam beta1", "0.9"),
            Field("adam_beta2", "Adam beta2", "0.999"),
            Field("weight_decay", "Weight decay", "0.01"),
            Field("max_grad_norm", "Gradient clipping", "1.0"),
            Field("lora_rank", "LoRA rank", "8"),
            Field("lora_alpha", "LoRA alpha", "8"),
            Field("lora_dropout", "LoRA dropout", "0"),
            Field("lora_targets", "LoRA target modules", "to_q,to_k,to_v,to_out.0"),
            Field("train_text_encoder", "Also train the text encoder", "false"),
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("snr_gamma", "Min-SNR gamma", "5.0"),
            Field("prediction_type", "Prediction type", "epsilon"),
            Field("use_ema", "EMA of the weights", "false"),
            Field("ema_decay", "EMA decay", "0.9999"),
            Field("precision", "Mixed precision", "fp16"),
            Field("gradient_checkpointing", "Gradient checkpointing", "true"),
            Field("seed", "Seed", "42"),
            Field("checkpointing_steps", "Checkpoint every N steps", "500"),
            Field("validation_prompt", "Validation prompt", ""),
            Field("validation_steps", "Validate every N steps", "200"),
            Field("dataloader_workers", "Dataloader workers", "4"),
            Field("resume_from", "Resume from checkpoint", ""),
            Field("output_dir", "Output directory", "model/"),
        ),
        Kind.AUDIO to listOf(
            Field("base_model", "Base model or checkpoint", ""),
            Field("model_config", "Model config", "same as base"),
            Field("dataset_formatter", "Dataset formatter", "ljspeech"),
            Field("metadata_file", "Metadata / manifest file", "metadata.csv"),
            Field("language", "Language", "en"),
            Field("gender", "Voice gender", "female"),
            Field("sample_rate", "Sample rate", "22050"),
            Field("output_sample_rate", "Output sample rate", "24000"),
            Field("audio_channels", "Audio channels", "1"),
            Field("resample", "Resample mismatched audio", "true"),
            Field("text_cleaner", "Text cleaner / phonemizer", "english_cleaners"),
            Field("vocabulary", "Vocabulary / tokenizer", "same as base"),
            Field("speaker_id", "Speaker id", "narrator"),
            Field("max_wav_length", "Max waveform seconds", "11.6"),
            Field("max_text_length", "Max text length", "200"),
            Field("conditioning_min", "Reference audio min seconds", "3"),
            Field("conditioning_max", "Reference audio max seconds", "6"),
            Field("eval_split_size", "Validation examples", "256"),
            Field("batch_size", "Batch size", "3"),
            Field("eval_batch_size", "Evaluation batch size", "3"),
            Field("grad_accumulation", "Gradient accumulation steps", "84"),
            Field("batch_group_size", "Batch group size", "48"),
            Field("learning_rate", "Learning rate", "0.000005"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("adam_beta1", "Adam beta1", "0.9"),
            Field("adam_beta2", "Adam beta2", "0.96"),
            Field("weight_decay", "Weight decay", "0.01"),
            Field("lr_scheduler", "LR scheduler", "multistep"),
            Field("scheduler_milestones", "Scheduler milestones", "50000,100000"),
            Field("grad_clip", "Gradient clipping", "1.0"),
            Field("precision", "Mixed precision", "fp16"),
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("dataloader_workers", "Dataloader workers", "8"),
            Field("save_step", "Checkpoint every N steps", "10000"),
            Field("save_n_checkpoints", "Checkpoints to keep", "1"),
            Field("print_step", "Log every N steps", "50"),
            Field("plot_step", "Plot audio every N steps", "100"),
            Field("test_sentences", "Test sentences", ""),
            Field("speaker_reference", "Speaker reference clip", ""),
            // Music models read the same audio, but these describe what to compose rather than who
            // is speaking. They are ordinary audio fields, so both pages draw them without a new
            // layout: a music build is an audio build with a different objective.
            Field("instruments", "Instruments (comma separated)", "piano, strings, bass"),
            Field("singing_voices", "Singing voice types", "2"),
            Field("genre", "Genre / style", "instrumental"),
            Field("tempo_bpm", "Tempo (BPM)", "100"),
            Field("key", "Key", "C major"),
            Field("music_seconds", "Generated length (seconds)", "4"),
            Field("music_temperature", "Sampling temperature", "0.9"),
            Field("music_top_k", "Sampling top-k", "20"),
            Field("run_name", "Run name", "audiocraft"),
            Field("seed", "Seed", "42"),
            Field("output_path", "Output directory", "model/"),
        ),
        Kind.INTERPOLATION to listOf(
            Field("model_config", "Model config / architecture file", ""),
            Field("pretrained_checkpoint", "Initialize from checkpoint", ""),
            Field("input_frame_count", "Input frames per sample", "2"),
            Field("target_frame", "Target frame to reconstruct", "middle"),
            Field("timestep", "Interpolation timestep", "0.5"),
            Field("crop_size", "Random crop size", "224"),
            Field("input_resolution", "Input resolution", "source"),
            Field("scale_factor", "Scale augmentation factor", "1.0"),
            Field("batch_size", "Batch size", "16"),
            Field("grad_accumulation", "Gradient accumulation steps", "1"),
            Field("dataloader_workers", "Dataloader workers", "8"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("learning_rate", "Learning rate", "0.0003"),
            Field("lr_scheduler", "LR scheduler", "cosine"),
            Field("warmup_steps", "LR warmup steps", "0"),
            Field("train_steps", "Training steps", "300000"),
            Field("train_split", "Training split", "train"),
            Field("val_split", "Validation split", "val"),
            Field("test_split", "Test split", "test"),
            Field("weight_decay", "Weight decay", "0.0001"),
            Field("loss_reconstruction", "Reconstruction loss weight", "1.0"),
            Field("loss_perceptual", "Perceptual loss weight", "0.0"),
            Field("loss_flow", "Flow loss weight", "0.0"),
            Field("loss_distillation", "Teacher distillation weight", "0.0"),
            Field("teacher_checkpoint", "Teacher model", ""),
            Field("augmentation", "Random crop and flip", "true"),
            Field("grad_clip", "Gradient clipping", "1.0"),
            Field("ema", "Weight averaging (EMA)", "false"),
            Field("precision", "Mixed precision", "fp32"),
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("seed", "Seed", "42"),
            Field("distributed_backend", "Distributed backend", "none"),
            Field("world_size", "Processes", "1"),
            Field("validation_interval", "Validate every N steps", "10000"),
            Field("validation_metrics", "Validation metrics", "psnr,ssim"),
            Field("save_interval", "Checkpoint every N steps", "10000"),
            Field("resume_from", "Resume from checkpoint", ""),
            Field("output_dir", "Output directory", "model/"),
        ),
        Kind.VIDEO to listOf(
            Field("base_checkpoint", "Base checkpoint", ""),
            Field("training_type", "Training type", "lora"),
            Field("task_type", "Task type", "text-to-video"),
            Field("instance_data_root", "Video data root", ""),
            Field("caption_column", "Caption column", "caption"),
            Field("video_column", "Video path column", "video"),
            Field("image_column", "Reference image column", ""),
            Field("id_token", "Concept token", ""),
            Field("width", "Width", "720"),
            Field("height", "Height", "480"),
            Field("max_frames", "Frames per clip", "49"),
            Field("fps", "Sampling fps", "8"),
            Field("skip_start", "Skip frames at the start", "0"),
            Field("skip_end", "Skip frames at the end", "0"),
            Field("cache_latents", "Cache latents to disk", "true"),
            Field("batch_size", "Batch size", "1"),
            Field("grad_accumulation", "Gradient accumulation steps", "4"),
            Field("max_steps", "Max steps (overrides epochs)", "0"),
            Field("learning_rate", "Learning rate", "0.0001"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("adam_beta1", "Adam beta1", "0.9"),
            Field("adam_beta2", "Adam beta2", "0.95"),
            Field("weight_decay", "Weight decay", "0.01"),
            Field("lr_scheduler", "LR scheduler", "cosine"),
            Field("warmup_steps", "LR warmup steps", "200"),
            Field("lora_rank", "LoRA rank", "64"),
            Field("lora_alpha", "LoRA alpha", "64"),
            Field("lora_dropout", "LoRA dropout", "0"),
            Field("lora_targets", "LoRA target modules", "to_q,to_k,to_v"),
            Field("max_grad_norm", "Gradient clipping", "1.0"),
            Field("precision", "Mixed precision", "bf16"),
            Field("gradient_checkpointing", "Gradient checkpointing", "true"),
            Field("slicing", "Enable slicing / tiling", "true"),
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("seed", "Seed", "42"),
            Field("checkpointing_steps", "Checkpoint every N steps", "1000"),
            Field("validation_prompt", "Validation prompt", ""),
            Field("validation_epochs", "Validate every N epochs", "10"),
            Field("num_validation_videos", "Validation videos", "1"),
            Field("precision_notes", "Extra trainer arguments", ""),
            Field("num_processes", "Processes", "1"),
            Field("output_dir", "Output directory", "model/"),
        ),
        Kind.GAME to listOf(
            Field("base_model", "Base game model or checkpoint", ""),
            Field(KEY_QUANTIZATION, "Model quantization", DEFAULT_QUANTIZATION, quantizations),
            Field("model_config", "Model config", "same as base"),
            Field("training_method", "Training method", "lora"),
            Field("lora_rank", "LoRA rank", "16"),
            Field("lora_alpha", "LoRA alpha", "16"),
            Field("dataset_format", "Dataset format", "meshes-and-captions"),
            Field("asset_types", "Asset types to train on", "mesh,texture,scene"),
            Field("target_format", "Target engine format", "obj"),
            Field("up_axis", "Up axis", "Y"),
            Field("coordinate_units", "Coordinate units", "meters"),
            Field("triangle_budget", "Triangle budget per asset", "5000"),
            Field("texture_resolution", "Texture resolution", "2048"),
            Field("shading_model", "Shading model", "PBR-metallic-roughness"),
            Field("rigged_assets", "Assets may be rigged", "false"),
            Field("animation_fps", "Animation sampling fps", "30"),
            Field("lod_levels", "Levels of detail", "3"),
            Field("collision_mesh", "Build a collision mesh", "true"),
            Field("batch_size", "Batch size", "1"),
            Field("grad_accumulation", "Gradient accumulation steps", "4"),
            Field("max_steps", "Max steps (overrides epochs)", "0"),
            Field("learning_rate", "Learning rate", "0.001"),
            Field("optimizer", "Optimizer", "adamw"),
            Field("weight_decay", "Weight decay", "0.01"),
            Field("lr_scheduler", "LR scheduler", "cosine"),
            Field("warmup_steps", "LR warmup steps", "100"),
            Field("max_grad_norm", "Gradient clipping", "1.0"),
            Field("precision", "Mixed precision", "bf16"),
            Field("gradient_checkpointing", "Gradient checkpointing", "true"),
            Field("seed", "Seed", "42"),
            Field("save_steps", "Checkpoint every N steps", "500"),
            Field("eval_steps", "Evaluate every N steps", "200"),
            Field("resume_from", "Resume from checkpoint", ""),
            Field("output_dir", "Output directory", "model/"),
        ),
    )

    // ── The two switches that are not plain fields ───────────────────────────────────────────────
    //
    // Sliding-window attention and the audio model's type are deliberately NOT entries in [fields].
    // Both need a control a text box cannot be: a checkbox that enables two more boxes, and a radio
    // group whose second option adds a required one. What they DO need is one declaration of their
    // recipe keys and their meaning, here, so both platforms' forms write the same thing and a
    // trainer has a single place to read the shape from.

    /** Recipe keys for the controls that are drawn specially. Public: the pages and tests use them. */
    const val KEY_SLIDING_WINDOW = "sliding_window"
    const val KEY_SLIDING_WINDOW_SIZE = "sliding_window_size"
    const val KEY_FULL_ATTENTION_EVERY_NTH_LAYER = "full_attention_per_nth_layer"
    const val KEY_AUDIO_TYPE = "audio_model_type"
    const val KEY_VOICE_COUNT = "voice_count"
    const val KEY_FILE_FORMAT = "file_format"

    /**
     * Which engine speaks a trained text-to-speech model.
     *
     * `dataset` is the engine built from this model's own recordings: the voices, their pitch and
     * their brightness are measured from the pack and drive the local studio synthesizer. `pocket`
     * is the old unconditional Pocket TTS path, kept as an explicit choice rather than a hidden
     * fallback. Pocket TTS can also be allowed as a fallback for languages the studio voice cannot
     * speak; that is a separate switch ([KEY_ALLOW_POCKET]).
     */
    const val KEY_VOICE_ENGINE = "voice_engine"
    const val VOICE_ENGINE_DATASET = "dataset"
    const val VOICE_ENGINE_POCKET = "pocket-tts"

    /** Whether Pocket TTS may stand in when the dataset engine cannot speak a sentence. */
    const val KEY_ALLOW_POCKET = "allow_pocket_tts"

    /**
     * Whether the Kokoro studio voice may speak this model.
     *
     * Off by default. Kokoro is a pretrained voice and the neural voice is Prism's own; a model
     * that has the latter must not quietly borrow the former just because it is installed. The
     * switch is the explicit permission, and [KEY_CUSTOM_VOICE] outranks it when set.
     */
    const val KEY_ALLOW_KOKORO = "allow_kokoro_voice"

    /** A folder holding a Prism neural voice to use instead of the one trained here. */
    const val KEY_CUSTOM_VOICE = "custom_neural_voice"

    /** Whether the custom voice folder is actually selected. */
    const val KEY_USE_CUSTOM_VOICE = "use_custom_neural_voice"

    /** The knobs of the dataset-built neural voice, as one JSON document. */
    const val KEY_NEURAL_VOICE_SETTINGS = "neural_voice_settings"

    /**
     * The two controls that decide how long a run trains.
     *
     * They are not entries in [fields] for every kind: every kind has them, they sit together at the
     * top of Training setup, and both platforms draw them from [Schedule] so the recipe cannot say one
     * thing while the form shows another. `epochs` was a field on four of the five kinds before this;
     * it is read the same way from an older recipe, so nothing written yesterday is orphaned.
     */
    const val KEY_EPOCHS = "epochs"
    const val KEY_STEPS_PER_EPOCH = "steps_per_epoch"

    /** What a form may ask for; every local trainer clamps to the same ceiling. */
    const val MAX_EPOCHS = 200
    const val MAX_STEPS_PER_EPOCH = 10_000

    /** What `steps_per_epoch = 0` means, said once so the pages and the recipe agree. */
    const val STEPS_PER_EPOCH_NOTE =
        "One step is one example in this build's trainers. steps_per_epoch caps how many examples " +
            "an epoch trains on; 0 means every example in the dataset, which is a full pass. epochs " +
            "is how many passes. A trainer that batches may read a step as one batch instead; the " +
            "numbers still mean passes and steps per pass."

    /**
     * The switch that says there is NO base to start from — the model is built from nothing.
     *
     * Off, every kind starts from the checkpoint named in its base field and the run is a fine-tune
     * or a LoRA on top of it. On, there is no base to load: the architecture named on the form is
     * created at the size [scratchConfig] describes and trained from random weights. That is what
     * makes "create an AI model completely from scratch" possible rather than a figure of speech, and
     * it is why every base-only field is dropped from the recipe when it is on — a trainer handed a
     * `base_checkpoint` and `learning_rate` in the same file would have to guess which one the user
     * meant.
     */
    const val KEY_FROM_SCRATCH = "from_scratch"

    /**
     * Whether the run is handed to a trainer on THIS device.
     *
     * Off, the recipe is the deliverable and the status says so ([STATUS_READY]): it is written, the
     * dataset is prepared, and a run on a training box picks it up. On, ModelCraft looks for a trainer
     * for the architecture here and runs it, and when there is none it says that instead of pretending.
     */
    const val KEY_TRAIN_LOCALLY = "train_locally"

    /** The two sentences [KEY_FROM_SCRATCH] turns into, so the recipe and the script agree. */
    const val SCRATCH_MODE =
        "built from scratch: the architecture named here is created at the shape below and trained " +
            "from random weights — no base model, no checkpoint"
    const val BASE_MODE =
        "fine-tuned from a base model or checkpoint named on this recipe"

    private val TRUE_VALUES_BASE = setOf("true", "yes", "1", "on")

    /**
     * The kinds whose attention can be shaped by the user.
     *
     * Every kind, because every recipe carries the setting and the local text and speech trainers
     * really use it: the text transformer masks each layer's keys with the window and keeps every
     * Nth layer full, and the speech encoder does the same. The image, video, interpolation and
     * game trainers are dense predictors in this build and record the setting for the full-scale
     * trainer their recipe describes.
     */
    val attentionKinds: Set<Kind> =
        setOf(Kind.TEXT, Kind.AUDIO, Kind.IMAGE, Kind.VIDEO, Kind.INTERPOLATION, Kind.GAME)

    /** Window size a fresh form starts from, in tokens. Large enough to hold a whole phrase. */
    const val DEFAULT_WINDOW = 1024

    /** How many layers apart a full-attention layer sits when the box is first enabled. */
    const val DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER = 4

    private val TRUE_VALUES = setOf("true", "yes", "1", "on")

    /**
     * What the sliding-window checkbox currently says.
     *
     * [fullAttentionEveryNthLayer] is the user's "per Nth layer full attention": N layers of sliding
     * attention, then one layer that attends to everything. It is what keeps a sliding-window model
     * able to remember something longer than its window -- the local layers carry the detail and the
     * periodic full one carries the sentence.
     */
    data class Attention(
        val enabled: Boolean,
        val window: Int,
        val fullAttentionEveryNthLayer: Int,
    ) {
        /** True when N is 1, i.e. every layer attends fully. A valid, expensive choice. */
        val effectivelyFullAttention: Boolean get() = fullAttentionEveryNthLayer <= 1
    }

    /** Reads the three attention params off [model], with the defaults for anything unset. */
    fun attention(model: CraftedModel): Attention = Attention(
        enabled = model.params[KEY_SLIDING_WINDOW]?.trim()?.lowercase() in TRUE_VALUES,
        window = model.params[KEY_SLIDING_WINDOW_SIZE]?.trim()?.toIntOrNull()?.coerceAtLeast(1)
            ?: DEFAULT_WINDOW,
        fullAttentionEveryNthLayer = model.params[KEY_FULL_ATTENTION_EVERY_NTH_LAYER]
            ?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER,
    )

    /** Writes the three attention params back, so a page never has to know the key names. */
    fun setAttention(model: CraftedModel, attention: Attention) {
        model.params[KEY_SLIDING_WINDOW] = attention.enabled.toString()
        model.params[KEY_SLIDING_WINDOW_SIZE] = attention.window.coerceAtLeast(1).toString()
        model.params[KEY_FULL_ATTENTION_EVERY_NTH_LAYER] =
            attention.fullAttentionEveryNthLayer.coerceAtLeast(1).toString()
        // The context box follows the attention controls. Sliding attention buys KV-cache budget,
        // so the number the user reads is the number the cache actually holds -- see ModelCapacity.
        model.params[ModelCapacity.KEY_CONTEXT_WINDOW] =
            ModelCapacity.contextAfterAttention(model, attention).toString()
    }

    /** The shape [model]'s two capacity boxes describe. */
    fun capacity(model: CraftedModel): ModelCapacity.Plan = ModelCapacity.plan(model)

    /** Writes the parameter-count box through the one calculator both pages read. */
    fun setParameterCount(model: CraftedModel, raw: String): Long =
        ModelCapacity.setParameters(model, raw)

    /** Writes the context box and returns what it should now display. */
    fun setContextWindow(model: CraftedModel, raw: String): Int =
        ModelCapacity.setContext(model, raw)

    /** True when the miniature voice model may stand in for a speech model that is not there. */
    fun miniatureFallback(model: CraftedModel): Boolean = ModelCapacity.miniatureFallback(model)

    /** Writes the miniature switch, so a page never has to know the key name. */
    fun setMiniatureFallback(model: CraftedModel, allowed: Boolean) =
        ModelCapacity.setMiniatureFallback(model, allowed)

    /**
     * The things an audio model can be asked to be.
     *
     * They are different models with different objectives, not a setting on one: a cloning model
     * learns ONE speaker's identity and is driven by a reference clip, while a text-to-speech model
     * learns a text-to-voice mapping and is driven by text -- with [KEY_VOICE_COUNT] telling it how
     * many distinct voices to end up able to speak in. The training data preparation is shared
     * (see [measureVoices]); what differs is which artifact the run writes, and that is what this
     * decides.
     */
    enum class AudioType(val label: String, val recipeValue: String, val note: String) {
        VOICE_CLONING(
            "Voice cloning model", "voice-cloning",
            "Learns one speaker from the dataset, then speaks anything in that voice.",
        ),
        TEXT_TO_SPEECH(
            "Text-to-speech model", "text-to-speech",
            "Learns text to speech, and is built to speak in several distinct voice types.",
        ),
        MUSIC(
            "Music generation model", "music",
            "Learns the instruments and singing timbres in the dataset, then composes new music.",
        ),
        ;

        companion object {
            fun of(raw: String): AudioType = entries.firstOrNull {
                it.recipeValue.equals(raw.trim(), true) || it.name.equals(raw.trim(), true)
            } ?: VOICE_CLONING
        }
    }

    /** The audio model type [model] is being built as. Meaningless for every other kind. */
    fun audioType(model: CraftedModel): AudioType =
        AudioType.of(model.params[KEY_AUDIO_TYPE] ?: AudioType.VOICE_CLONING.recipeValue)

    /** True when this audio build composes music instead of speaking text. */
    fun isMusic(model: CraftedModel): Boolean =
        model.kind == Kind.AUDIO && audioType(model) == AudioType.MUSIC

    /** How many voice types a text-to-speech model must be able to speak in, or null when unset. */
    fun voiceCount(model: CraftedModel): Int? =
        model.params[KEY_VOICE_COUNT]?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * The engine a text-to-speech model speaks through.
     *
     * The dataset engine is the default because it is the one built from the user's audio. A model
     * that was created before this switch existed, or one whose engine could not be built, still
     * has the studio voice; Pocket TTS is only used when this says so or when the fallback is on.
     */
    fun voiceEngine(model: CraftedModel): String =
        model.params[KEY_VOICE_ENGINE]?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: VOICE_ENGINE_DATASET

    /** True when the model speaks through the dataset-built engine rather than Pocket TTS. */
    fun usesBuiltInVoiceEngine(model: CraftedModel): Boolean =
        voiceEngine(model) != VOICE_ENGINE_POCKET

    /**
     * Writes the engine choice.
     *
     * Choosing Pocket also records that Pocket is allowed. The fallback switch's job is to decide
     * whether Pocket may cover a sentence the DATASET engine cannot say; a model that selected
     * Pocket as its engine has already answered that question, and leaving the fallback off would
     * make the choice fail with a sentence instead of speaking.
     */
    fun setVoiceEngine(model: CraftedModel, engine: String) {
        val pocket = engine.equals(VOICE_ENGINE_POCKET, true)
        model.params[KEY_VOICE_ENGINE] = if (pocket) VOICE_ENGINE_POCKET else VOICE_ENGINE_DATASET
        if (pocket) model.params[KEY_ALLOW_POCKET] = "true"
    }

    /**
     * Whether Pocket TTS may cover a sentence the built-in engine cannot say.
     *
     * Off by default: the point of the dataset engine is to use it, and a silent change of engine
     * in the middle of a sentence is the kind of thing a listener notices and cannot explain. On,
     * the multilingual fallback works exactly as it did before the switch existed.
     */
    fun allowsPocketFallback(model: CraftedModel): Boolean =
        model.params[KEY_ALLOW_POCKET]?.trim()?.toBooleanStrictOrNull() ?: false

    /** Writes the fallback switch. */
    fun setAllowsPocketFallback(model: CraftedModel, allowed: Boolean) {
        model.params[KEY_ALLOW_POCKET] = allowed.toString()
    }

    /** True when the user has explicitly allowed the pretrained Kokoro studio voice. */
    fun allowsKokoro(model: CraftedModel): Boolean =
        model.params[KEY_ALLOW_KOKORO]?.trim()?.toBooleanStrictOrNull() ?: false

    fun setAllowsKokoro(model: CraftedModel, allowed: Boolean) {
        model.params[KEY_ALLOW_KOKORO] = allowed.toString()
    }

    /** The imported voice folder, or blank when none was chosen. */
    fun customVoicePath(model: CraftedModel): String =
        model.params[KEY_CUSTOM_VOICE]?.trim().orEmpty()

    /** True when the imported voice is selected AND a folder was chosen. */
    fun usesCustomVoice(model: CraftedModel): Boolean =
        (model.params[KEY_USE_CUSTOM_VOICE]?.trim()?.toBooleanStrictOrNull() ?: false) &&
            customVoicePath(model).isNotBlank()

    fun setCustomVoice(model: CraftedModel, path: String, use: Boolean = path.isNotBlank()) {
        model.params[KEY_CUSTOM_VOICE] = path.trim()
        model.params[KEY_USE_CUSTOM_VOICE] = use.toString()
    }

    /** The neural voice settings saved with this model, or the defaults. */
    fun neuralVoiceSettings(model: CraftedModel): PrismNeuralVoice.Settings {
        val raw = model.params[KEY_NEURAL_VOICE_SETTINGS]?.takeIf { it.isNotBlank() } ?: return PrismNeuralVoice.Settings()
        val json = runCatching { com.prism.core.json.JSONObject(raw) }.getOrNull()
        return PrismNeuralVoice.Settings.of(json)
    }

    /** Saves the neural voice settings as the model's recipe values. */
    fun setNeuralVoiceSettings(model: CraftedModel, settings: PrismNeuralVoice.Settings) {
        model.params[KEY_NEURAL_VOICE_SETTINGS] = settings.cleaned().toJson().toString()
    }

    /**
     * Whether this model has voices to name before it can be trained.
     *
     * Only a text-to-speech model has a voice bank: a cloning model is one speaker, and the other
     * kinds have no recordings to name. Both pages open the dialog BEFORE training when this is
     * true, after the speech preparation has extracted the speaker information it shows; the same
     * dialog remains reachable from the Voices button for later edits.
     */
    fun wantsVoiceReview(model: CraftedModel): Boolean =
        model.kind == Kind.AUDIO && audioType(model) == AudioType.TEXT_TO_SPEECH

    /**
     * The two containers a run can write its finished weights into.
     *
     * They are not two views of the same thing, and the difference matters when a build has more than
     * one part. SAFETENSORS is a file PER TENSOR GROUP: the model is one file and -- for the kinds
     * whose build includes them, audio and video -- the encoder and the decoder are files of their
     * own beside it, which is how a checkpoint with a VAE next to it is distributed in that
     * ecosystem. GGUF is ONE FILE PER MODEL: everything the build made goes into a single container
     * with each component's tensors under its own name prefix, because that is the shape that format
     * has and splitting a GGUF into three would make files no GGUF reader expects.
     *
     * Neither choice changes what was trained -- the same weights, the same components -- only the
     * containers they are written to. The recipe records which one was asked for, so a trainer on
     * another machine produces the same files this build does.
     */
    enum class FileFormat(
        val label: String,
        val recipeValue: String,
        val extension: String,
        val note: String,
    ) {
        SAFETENSORS(
            "Safetensors", "safetensors", "safetensors",
            "One file per component, in Hugging Face's container. An audio or video model is written " +
                "as the model plus its encoder and decoder beside it.",
        ),
        GGUF(
            "GGUF", "gguf", "gguf",
            "One file, in llama.cpp's container. An audio or video model keeps its encoder and " +
                "decoder in the same file, under their own name prefixes.",
        ),
        ;

        companion object {
            fun of(raw: String): FileFormat = entries.firstOrNull {
                it.recipeValue.equals(raw.trim(), true) || it.name.equals(raw.trim(), true)
            } ?: SAFETENSORS
        }
    }

    /** The container [model]'s build writes. Safetensors until the user picks otherwise. */
    fun fileFormat(model: CraftedModel): FileFormat =
        FileFormat.of(model.params[KEY_FILE_FORMAT] ?: FileFormat.SAFETENSORS.recipeValue)

    /** Writes the format choice, so a page never has to know the key name. */
    fun setFileFormat(model: CraftedModel, format: FileFormat) {
        model.params[KEY_FILE_FORMAT] = format.recipeValue
    }

    /**
     * How long a run trains: passes over the dataset, and examples per pass.
     *
     * [stepsPerEpoch] of 0 is not "no training": it is a full pass, every example the trainer has.
     * That is the default because it is how these trainers already worked before the control existed,
     * and a number in a box should not quietly shorten a run nobody asked to shorten.
     */
    data class Schedule(val epochs: Int, val stepsPerEpoch: Int) {
        /** What the form promises, or 0 when an epoch is the whole dataset and cannot be counted here. */
        val plannedSteps: Int get() = if (stepsPerEpoch > 0) epochs * stepsPerEpoch else 0
    }

    /**
     * The starting schedule for a fresh model of [kind].
     *
     * The epoch counts are the ones each kind's form already offered, so opening a model written
     * before this control gives the same run it would have given then. Steps per epoch starts at 0 --
     * every example, a full pass -- for the reason [Schedule] gives.
     */
    fun defaultSchedule(kind: Kind): Schedule = when (kind) {
        Kind.TEXT -> Schedule(epochs = 3, stepsPerEpoch = 0)
        Kind.IMAGE -> Schedule(epochs = 10, stepsPerEpoch = 0)
        Kind.AUDIO -> Schedule(epochs = 10, stepsPerEpoch = 0)
        Kind.VIDEO -> Schedule(epochs = 30, stepsPerEpoch = 0)
        Kind.INTERPOLATION -> Schedule(epochs = 12, stepsPerEpoch = 0)
        Kind.GAME -> Schedule(epochs = 8, stepsPerEpoch = 0)
    }

    /** Reads the two schedule params off [model], falling back to [defaultSchedule] for its kind. */
    fun schedule(model: CraftedModel): Schedule {
        val defaults = defaultSchedule(model.kind)
        val epochs = model.params[KEY_EPOCHS]?.trim()?.toDoubleOrNull()?.toInt()
            ?.takeIf { it > 0 } ?: defaults.epochs
        val steps = model.params[KEY_STEPS_PER_EPOCH]?.trim()?.toDoubleOrNull()?.toInt()
            ?.takeIf { it >= 0 } ?: defaults.stepsPerEpoch
        return Schedule(
            epochs = epochs.coerceIn(1, MAX_EPOCHS),
            stepsPerEpoch = steps.coerceIn(0, MAX_STEPS_PER_EPOCH),
        )
    }

    /** Writes the schedule back, so a page never has to know the key names or the limits. */
    fun setSchedule(model: CraftedModel, schedule: Schedule) {
        model.params[KEY_EPOCHS] = schedule.epochs.coerceIn(1, MAX_EPOCHS).toString()
        model.params[KEY_STEPS_PER_EPOCH] =
            schedule.stepsPerEpoch.coerceIn(0, MAX_STEPS_PER_EPOCH).toString()
    }

    /**
     * The components a build of [kind] produces, in the order they are written.
     *
     * Audio and video models get an encoder and a decoder as well as the model itself: the acoustic
     * model is the predictor, and the encoder and decoder are the pair that turns its frames into a
     * small code and back -- the half of that pipeline this build trains from the same dataset. The
     * other three kinds are one network and write one file, and saying so as a list rather than
     * "sometimes three files" is what keeps the writers, the recipe and the pages agreeing.
     */
    fun fileComponents(kind: Kind): List<String> =
        if (kind == Kind.AUDIO || kind == Kind.VIDEO) listOf("model", "encoder", "decoder")
        else listOf("model")

    /**
     * The stem a kind's container files are named from: `text_model`, `video_model`, `frame_model`,
     * `audio_model`, `image_model`.
     *
     * One list rather than a string written in each trainer: the page says which files a run will
     * leave behind before it runs, and a page whose names came from a second list would eventually
     * name files the writers do not write.
     */
    fun fileStem(kind: Kind): String = when (kind) {
        Kind.TEXT -> "text_model"
        Kind.IMAGE -> "image_model"
        Kind.AUDIO -> "audio_model"
        Kind.INTERPOLATION -> "frame_model"
        Kind.VIDEO -> "video_model"
        Kind.GAME -> "game_model"
    }

    /** True when [model] is being built from nothing rather than from a base checkpoint. */
    fun fromScratch(model: CraftedModel): Boolean =
        model.params[KEY_FROM_SCRATCH]?.trim()?.lowercase() in TRUE_VALUES_BASE

    /** Writes the from-scratch switch, so a page never has to know the key name. */
    fun setFromScratch(model: CraftedModel, fromScratch: Boolean) {
        model.params[KEY_FROM_SCRATCH] = fromScratch.toString()
    }

    /**
     * Whether the run should look for a trainer on THIS device.
     *
     * The checkbox is the user's, but a record that has never been near one answers "yes": a model
     * made before this switch existed still trains when a trainer for its architecture exists in this
     * build, and switching the box OFF is what makes the run recipe-and-export only. On, and there is
     * no trainer here, the status says so ([STATUS_READY]) instead of pretending.
     */
    /**
     * Installs the trainers this build has, once.
     *
     * Called by each platform at startup. Every kind has one:
     *
     *  - audio -- [AcousticTrainer], a prosody-conditioned acoustic model from the merged CSV;
     *  - text -- [TextTrainer], a byte-level transformer, causal or masked by architecture;
     *  - image, interpolation and video -- [PixelTrainer], a pixel denoiser, a middle-frame
     *    reconstructor and a next-frame predictor, each trained on the dataset's own pixels.
     *
     * A trainer still returns null when ITS dataset has nothing it can read -- no decodable clip, no
     * frames extracted, no text -- and the run reports the recipe-ready status then. That is the
     * distinction the null exists for: no trainer that could run, versus a trainer whose data was not
     * there. What each trainer trained, and at what size, is written into its own artifacts.
     */
    fun installDefaultTrainer() {
        if (runner != null) return
        runner = { model, recipe ->
            when (model.kind) {
                Kind.AUDIO -> if (isMusic(model)) {
                    MusicTrainer.train(model, recipe)
                } else {
                    AcousticTrainer.train(model, recipe)
                }
                Kind.TEXT -> TextTrainer.train(model, recipe)
                Kind.IMAGE -> PixelTrainer.trainImage(model, recipe)?.also {
                    activateImageArtifact(model)
                }
                Kind.INTERPOLATION -> PixelTrainer.trainInterpolation(model, recipe)
                Kind.VIDEO -> PixelTrainer.trainVideo(model, recipe)
                Kind.GAME -> GameTrainer.train(model, recipe)
            }
        }
    }

    /**
     * Makes a just-trained image model the active one, once.
     *
     * "Automatically imported", in the only sense that means anything to the rest of the app: the
     * Models page reads the same local-image path as ImageCraft and GameCraft, and the Models page's
     * import button writes that same key. Writing it here is therefore the import. It does NOT
     * overwrite a model the user chose deliberately -- a second ModelCraft run leaves an existing
     * SD checkpoint or `.task` bundle in place, and the new model stays available in its dataset
     * folder and on the ModelCraft list.
     */
    private fun activateImageArtifact(model: CraftedModel) {
        val directory = Store.datasetDir(model.id)
        val metadata = File(directory, PixelTrainer.Task.IMAGE.metadataFile)
        val weights = File(directory, PixelTrainer.Task.IMAGE.weightsFile)
        if (!metadata.isFile || !weights.isFile) return
        val current = PrismSettings.getLocalImageModelPath()
        if (current.isBlank() || ModelCraftImageGenerator.directoryOf(current) != null) {
            PrismSettings.setLocalImageModelPath(directory.absolutePath)
        }
        PrismPlatform.log.info(
            "ModelCraft",
            "Image model ${model.name} is available as the ModelCraft image engine at " +
                directory.absolutePath,
        )
    }

    fun trainsLocally(model: CraftedModel): Boolean {
        val raw = model.params[KEY_TRAIN_LOCALLY]?.trim()?.lowercase()
        if (raw.isNullOrEmpty()) return true
        return raw in TRUE_VALUES_BASE
    }

    /** Writes the local-training switch. */
    fun setTrainsLocally(model: CraftedModel, locally: Boolean) {
        model.params[KEY_TRAIN_LOCALLY] = locally.toString()
    }

    /**
     * The fields that only mean something WITH a base model or checkpoint.
     *
     * They are not hidden when the from-scratch box is ticked — the form keeps them where they were,
     * greyed, so switching back does not lose what was typed — but they are dropped from the recipe,
     * because a from-scratch run has nothing to load a base into and no pre-trained weights to keep
     * near the surface. The dataset and training fields are NOT in these lists: a model built from
     * nothing still has a learning rate, an optimizer and a number of epochs.
     */
    val baseFields: Map<Kind, List<String>> = mapOf(
        Kind.TEXT to listOf(
            "base_model", "tokenizer", "finetuning_type",
            "lora_rank", "lora_alpha", "lora_dropout", "lora_targets", "resume_from",
        ),
        Kind.IMAGE to listOf(
            "base_checkpoint", "training_method", "lora_rank", "lora_alpha", "lora_dropout",
            "lora_targets",
        ),
        Kind.VIDEO to listOf(
            "base_checkpoint", "training_type", "lora_rank", "lora_alpha", "lora_dropout",
            "lora_targets",
        ),
        Kind.AUDIO to listOf("base_model", "model_config", "vocabulary"),
        Kind.INTERPOLATION to listOf("model_config", "pretrained_checkpoint", "teacher_checkpoint"),
        Kind.GAME to listOf(
            "base_model", "model_config", "training_method", "lora_rank", "lora_alpha",
        ),
    )

    /**
     * The shape a from-scratch model of [kind] is created at, read off the form where the form says
     * anything about size and from these defaults where it does not.
     *
     * These are Prism's defaults for a model that has to be trained on a phone or a laptop as well as
     * on a training box: a small dimension, a handful of layers, and a context or resolution the
     * dataset fields already name. They are not the only sizes the architecture can be — a trainer is
     * free to scale them — but a run has to start somewhere, and "somewhere" written down beats an
     * empty checkpoint name.
     */
    fun scratchConfig(model: CraftedModel): JSONObject = JSONObject().apply {
        put("description", "Initial shape for a from-scratch ${model.kind.label.lowercase()} model.")
        when (model.kind) {
            Kind.TEXT -> {
                // The field's own number, capped: a from-scratch model built here starts at a size
                // that trains on this machine, and the context is what a trainer scales when it has
                // the memory for more.
                // The recipe records what the form asked for; the on-device trainer records the
                // value it actually built after its own cap. The floor is 8 rather than 64 so a
                // deliberate small-context run is not silently raised.
                put("context_length", paramInt(model, "max_seq_length", 1024).coerceIn(8, 2_048))
                // The vocabulary is learned from the training split, not the 32k a downloaded
                // tokenizer would carry. One output row per word is most of a small model's
                // compute, so the form's number is what the trainer builds.
                put(
                    "vocab_size",
                    paramInt(model, "vocabulary", TextTrainer.DEFAULT_VOCABULARY)
                        .coerceIn(1_024, TextTrainer.MAX_VOCABULARY),
                )
                put("layers", paramInt(model, "layers", TextTrainer.LAYERS).coerceIn(1, 4))
                put("heads", paramInt(model, "heads", TextTrainer.HEADS).coerceIn(1, 8))
                put("dim", paramInt(model, "dim", TextTrainer.D_MODEL).coerceIn(16, 128))
                put("ffn_dim", TextTrainer.HIDDEN)
                put("activation", "gelu")
                put("positional", "sinusoidal")
                put("tied_embeddings", false)
            }
            Kind.IMAGE -> {
                // Sixteen is a deliberate floor: a from-scratch pixel model at this size can still
                // learn a glyph, and a dataset like MNIST is natively 28. The trainer caps what it
                // actually builds at PixelTrainer.MAX_SIDE and records that number.
                put("resolution", paramInt(model, "resolution", 128).coerceIn(16, 256))
                // PIXELS, not latents: a from-scratch run here has no VAE, so the model is built over
                // the images themselves and the exported script says so.
                put("channels", 3)
                put("patch_size", 8)
                put("layers", 8)
                put("heads", 4)
                put("dim", 256)
                put("conditioning", "cross-attention")
            }
            Kind.VIDEO -> {
                put("width", paramInt(model, "width", 256).coerceIn(64, 256))
                put("height", paramInt(model, "height", 256).coerceIn(64, 256))
                put("frames", paramInt(model, "max_frames", 24).coerceIn(4, 24))
                put("fps", paramInt(model, "fps", 8))
                put("channels", 3)
                put("patch_size", 8)
                put("layers", 6)
                put("heads", 4)
                put("dim", 256)
                put("conditioning", "cross-attention")
            }
            Kind.AUDIO -> {
                put("sample_rate", paramInt(model, "sample_rate", 22_050))
                put("output_sample_rate", paramInt(model, "output_sample_rate", 24_000))
                put("n_mels", 80)
                put("spec_channels", 513)
                put("layers", 6)
                put("heads", 4)
                put("dim", 256)
                put("task", audioType(model).recipeValue)
                voiceCount(model)?.let { put("voices", it) }
                put("objective", "flow-matching on mel spectrograms")
            }
            Kind.INTERPOLATION -> {
                put("channels", 3)
                put("input_frames", paramInt(model, "input_frame_count", 2))
                put("resolution", paramInt(model, "crop_size", 128).coerceIn(32, 256))
                put("patch_size", 8)
                put("layers", 4)
                put("heads", 4)
                put("dim", 64)
                put("objective", "flow-matching between frames")
            }
            Kind.GAME -> {
                put("shape_dim", GameTrainer.SHAPE_DIM)
                put("condition_dim", 8)
                put("layers", 3)
                put("heads", 4)
                put("dim", 256)
                put("target_format", model.param("target_format"))
                put("triangle_budget", paramInt(model, "triangle_budget", 5_000))
                put("up_axis", model.param("up_axis"))
                put(
                    "objective",
                    if (model.architecture.contains("Diffuser", true)) "flow-matching over shape descriptors"
                    else "autoregressive prediction over shape tokens",
                )
            }
        }
        put(
            "note",
            "Prism's default starting shape: small enough to build and train on a laptop or a phone. " +
                "It is a STARTING point — a trainer with the hardware scales these numbers, and the " +
                "exported script builds exactly this.",
        )
    }

    /** One field read as a decimal number, falling back to [fallback] for blank or unparseable text. */
    private fun paramDouble(model: CraftedModel, key: String, fallback: Double): Double =
        model.param(key).trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback

    /** One field read as a number, falling back to [fallback] for blank or unparseable text. */
    private fun paramInt(model: CraftedModel, key: String, fallback: Int): Int =
        model.params[key]?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: fallback

    /**
     * What still has to be filled in before this model can be trained, or null when nothing does.
     *
     * Checked by [train] rather than trusted to the forms, because it is the recipe that must not be
     * written without it: a text-to-speech run whose voice count is missing produces a model nobody
     * can ask for a voice, and a sliding-window run with no window produces one that attends to
     * everything after all -- silently, which is worse than an error.
     */
    fun validationError(model: CraftedModel): String? {
        // A RECIPE THAT NAMES A PRECISION NOTHING CAN WRITE IS WORSE THAN NO PRECISION AT ALL: the
        // run completes, the export records it, and the first person to load the model gets float32
        // and no explanation. Caught here, before the run, where it can be fixed in one tap.
        val requested = model.param(KEY_QUANTIZATION)
        if (requested.isNotBlank() && quantizationLevel(requested) == null) {
            return "'$requested' is not a precision this build can write. Choose one of " +
                quantizations.joinToString(", ") + "."
        }
        if (model.kind == Kind.AUDIO) {
            val type = audioType(model)
            if (type == AudioType.TEXT_TO_SPEECH && voiceCount(model) == null) {
                return "How many voice types should this text-to-speech model be able to speak in? " +
                    "Fill in 'Voice types to build' — it is required for a text-to-speech model."
            }
        }
        if (model.kind in attentionKinds && attention(model).enabled) {
            val attention = attention(model)
            if (attention.window < 2) {
                return "A sliding window of ${attention.window} token(s) attends to nothing: the " +
                    "window size has to be at least 2."
            }
            if (attention.fullAttentionEveryNthLayer < 1) {
                return "'Per Nth layer full attention' has to be at least 1 — 1 means every layer " +
                    "attends fully."
            }
        }
        return null
    }

    /** What each kind's dataset import accepts. */
    data class DatasetSpec(
        val label: String,
        val mimeTypes: List<String>,
        /** True when the kind's datasets reference their media from a CSV. */
        val csv: Boolean,
        /** True when video files become frames before training. */
        val frames: Boolean,
        val help: String,
    )

    val datasets: Map<Kind, DatasetSpec> = mapOf(
        Kind.TEXT to DatasetSpec(
            "Text, JSON and conversation files",
            listOf(
                "text/plain", "text/markdown", "application/json", "application/jsonl",
                "text/csv", "application/octet-stream",
            ),
            csv = false,
            frames = false,
            help = ".txt, .json, .jsonl and .csv are read as language. Conversation JSON is read " +
                "from conversations/messages; a train/, validation/ and test/ folder (or a split " +
                "field, or file names like dialogues_train.txt) gives the run its three splits.",
        ),
        Kind.IMAGE to DatasetSpec(
            "Photos and their CSV",
            listOf("image/*", "text/csv", "text/comma-separated-values", "application/octet-stream"),
            csv = true,
            frames = false,
            help = "The CSV names each photo's file path and its caption; the files beside it are imported too.",
        ),
        Kind.AUDIO to DatasetSpec(
            "Audio and its CSV",
            listOf("audio/*", "text/csv", "text/comma-separated-values", "application/octet-stream"),
            csv = true,
            frames = false,
            help = "The CSV maps each clip's path to its transcript.",
        ),
        Kind.INTERPOLATION to DatasetSpec(
            "Videos, their CSV, and the frames",
            listOf("video/*", "text/csv", "text/comma-separated-values", "application/octet-stream"),
            csv = true,
            frames = true,
            help = "Same dataset as video models; ModelCraft breaks each clip into frames.",
        ),
        Kind.VIDEO to DatasetSpec(
            "Videos and their CSV",
            listOf("video/*", "text/csv", "text/comma-separated-values", "application/octet-stream"),
            csv = true,
            // Frames are broken out for video models too, because the trainer that predicts the next
            // frame reads frames rather than decoding a container: the same extraction the
            // interpolator's datasets get, and the same reason for it.
            frames = true,
            help = "The CSV names each clip's path and its caption; the clips are broken into frames.",
        ),
        Kind.GAME to DatasetSpec(
            "Game meshes, textures, scenes and their CSV",
            // A game project is not a media type. Meshes, texture maps, scenes and engine packages
            // all belong in one dataset, and a picker that only offered image/* would hide the half
            // of it that is geometry.
            listOf("*/*"),
            csv = true,
            frames = false,
            help = ".obj, .ply and .glb meshes train directly; textures and scenes are catalogued. " +
                "The CSV can caption each mesh; its captions condition the shapes the model learns.",
        ),
    )

    /**
     * Breaks one video into frames, for the frame-interpolator datasets. Set by the platform.
     *
     * @return how many frames were written, or 0 when the file could not be read.
     */
    @Volatile
    var frameExtractor: ((video: File, destination: File) -> Int)? = null

    /**
     * Runs a prepared recipe. Set by the platform when it has a trainer for that kind.
     *
     * @return the stage it ran to, or null when this build cannot train that architecture. Null is not
     *         a failure: the recipe is still written and the status says so, which is the difference
     *         between "no engine here" and "it did not work".
     */
    @Volatile
    var runner: ((model: CraftedModel, recipe: File) -> String?)? = null

    /**
     * The model whose speech was prepared for the voices dialog, and how many voices it measured.
     *
     * The dialog opens BEFORE training now, so the extraction that used to happen inside a run
     * happens first. Remembering it lets the run immediately afterwards reuse the measurements
     * instead of decoding every clip twice -- on a phone that second pass is the difference between
     * a prompt and a freeze. A dataset import, a resync or a changed voice count invalidates it.
     */
    @Volatile
    private var preparedVoicesFor: String = ""

    @Volatile
    private var preparedVoiceCount: Int = -1

    /** Forgets the pre-training speech preparation for [model]. */
    fun invalidateVoicePreparation(model: CraftedModel) {
        if (preparedVoicesFor == model.id) {
            preparedVoicesFor = ""
            preparedVoiceCount = -1
        }
    }

    /** True when [prepareVoices] has already measured this model at its current voice count. */
    fun voicePreparationReady(model: CraftedModel): Boolean =
        preparedVoicesFor == model.id &&
            preparedVoiceCount == (voiceCount(model) ?: 0) &&
            File(Store.datasetDir(model.id), CraftedAudioModel.FILE).isFile

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        /** The live training line: epoch, step, file, loss and accuracy while a trainer runs. */
        val detail: String = "",
        val progress: Float = 0f,
        val modelId: String = "",
        val error: String? = null,
        val complete: Boolean = false,
        val note: String = "",
    )

    /**
     * One trainer's position, as a page can print it.
     *
     * [step] is the step inside [epoch], not the run's total, because that is the pair a person
     * watching a progress line reads: "epoch 2/10, step 34/120". [accuracy] is NaN where the trainer's
     * objective has no honest accuracy (a denoiser predicts numbers, not labels), and the line leaves
     * it out rather than inventing one.
     */
    data class Progress(
        val epoch: Int,
        val epochs: Int,
        val step: Int,
        val steps: Int,
        val file: String,
        val loss: Double,
        val accuracy: Double = Double.NaN,
    ) {
        fun line(): String = buildString {
            append("Epoch ")
            append(epoch.coerceAtLeast(0))
            append('/')
            append(epochs.coerceAtLeast(0))
            append(" · step ")
            append(step.coerceAtLeast(0))
            if (steps > 0) {
                append('/')
                append(steps)
            }
            if (file.isNotBlank()) {
                append(" · ")
                append(file)
            }
            if (loss.isFinite()) {
                append(" · loss ")
                append(String.format(Locale.US, "%.4f", loss))
            }
            if (accuracy.isFinite()) {
                append(" · accuracy ")
                append(String.format(Locale.US, "%.1f", (accuracy * 100.0).coerceIn(0.0, 100.0)))
                append('%')
            }
        }
    }

    /**
     * Receives every progress report, before the UI throttles itself. Tests and any future exporter
     * use it; the page reads [state]'s `detail` line.
     */
    @Volatile
    var progressListener: ((Progress) -> Unit)? = null

    private var lastProgressReportAt = 0L

    /**
     * Prints where a trainer is, and moves the page's line and bar.
     *
     * Called from the trainer's own thread. Reports are throttled to roughly twelve a second so a
     * fast step does not turn the state flow into a frame-rate problem, but the last step of an epoch
     * always lands. The line is also logged, which is what makes a run on a headless machine say
     * what it is doing.
     */
    fun reportTraining(progress: Progress) {
        progressListener?.invoke(progress)
        if (!_state.value.running) return
        val now = System.nanoTime()
        val lastOfEpoch = progress.steps > 0 && progress.step >= progress.steps
        if (!lastOfEpoch && now - lastProgressReportAt < 80_000_000L) return
        lastProgressReportAt = now
        val line = progress.line()
        val inEpoch = if (progress.steps > 0) {
            progress.step.toFloat() / progress.steps
        } else {
            0f
        }
        val overall = (
            (progress.epoch - 1).coerceAtLeast(0) + inEpoch.coerceIn(0f, 1f)
            ) / progress.epochs.coerceAtLeast(1)
        _state.value = _state.value.copy(
            stage = "Training…",
            detail = line,
            progress = (0.42f + overall.coerceIn(0f, 1f) * 0.30f).coerceIn(0f, 1f),
        )
        // Updated on the throttled path, not per step. A graph that allocated a snapshot for every
        // micro-batch would add the exact garbage-collector pressure the throttle exists to avoid.
        // It is a no-op for every non-audio kind and until an audio run starts.
        VoiceTrainingMonitor.progress(progress)
        PrismPlatform.log.info("ModelCraft", line)
    }

    object Store {

        fun root(): File = File(PrismPlatform.host.documentsDir(), "ModelCraft")

        fun modelsDir(): File = File(root(), "models").apply { mkdirs() }

        fun modelDir(id: String): File = File(modelsDir(), id).apply { mkdirs() }

        fun datasetDir(id: String): File = File(modelDir(id), "dataset").apply { mkdirs() }

        fun framesDir(id: String): File = File(modelDir(id), "frames").apply { mkdirs() }

        fun recipeFile(id: String): File = File(modelDir(id), "recipe.json")

        fun manifestFile(id: String): File = File(modelDir(id), "dataset.json")

        /** Where the speech preparation writes the per-clip vocal measurements. */
        fun vocalFeaturesFile(id: String): File = File(datasetDir(id), SpeechDataset.FEATURES_FILE)

        /** The dataset's own CSV with the vocal columns folded in: the file a trainer reads. */
        fun mergedCsvFile(id: String): File = File(datasetDir(id), SpeechDataset.MERGED_FILE)

        fun speakerProfileFile(id: String): File = File(datasetDir(id), SpeechDataset.PROFILE_FILE)

        /** The crafted audio model itself: the speaker, or the voice bank. See [CraftedAudioModel]. */
        fun audioModelFile(id: String): File = File(datasetDir(id), CraftedAudioModel.FILE)

        fun voicesFile(id: String): File = File(datasetDir(id), SpeechDataset.VOICES_FILE)

        private fun indexFile(): File = File(root(), "models.json")

        /**
         * Writes the model index, reporting a storage failure instead of throwing it.
         *
         * THIS USED TO TAKE THE WHOLE APP DOWN. On a fresh install, or after the user cleared
         * Prism's data, `documentsDir()` can point at a folder Android will not let this app create
         * because All Files Access has not been granted. `writeText` then threw
         * `FileNotFoundException` from the click handler that had just created a model, so pressing
         * "new model" closed the launcher. [PlatformHost.documentsDir] now has an app-private
         * fallback, and this second boundary is for the cases it cannot cover -- a full disk, a
         * removed SD card, a folder moved behind the app's back. A model that could not be saved is
         * still not worth killing a session over; it is logged with the path that failed.
         */
        fun save(model: CraftedModel) {
            model.updatedAt = System.currentTimeMillis()
            val file = indexFile()
            val temp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                val all = list().filterNot { it.id == model.id } + model
                root().mkdirs()
                temp.writeText(JSONArray(all.map { it.toJson() }).toString())
                java.nio.file.Files.move(
                    temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure {
                PrismPlatform.log.error(
                    "ModelCraft",
                    "Could not save ${model.name} to ${file.absolutePath}",
                    it,
                )
            }
        }

        fun list(): List<CraftedModel> {
            val file = indexFile()
            if (!file.isFile) return emptyList()
            return runCatching {
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { CraftedModel.fromJson(it) }
                }
            }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
        }

        fun delete(model: CraftedModel) {
            modelDir(model.id).deleteRecursively()
            val file = indexFile()
            val temp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                root().mkdirs()
                temp.writeText(
                    JSONArray(list().filterNot { it.id == model.id }.map { it.toJson() }).toString(),
                )
                java.nio.file.Files.move(
                    temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure {
                PrismPlatform.log.error(
                    "ModelCraft",
                    "Could not rewrite the model index at ${file.absolutePath}",
                    it,
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    @Volatile
    private var cancelled = false

    fun models(): List<CraftedModel> = Store.list()

    fun create(name: String, kind: Kind, architecture: String, family: String): CraftedModel {
        val model = CraftedModel(
            id = CraftedModel.newId(),
            name = name.ifBlank { "Untitled ${kind.label.lowercase()} model" },
            kind = kind,
            architecture = architecture,
            family = family,
        )
        Store.save(model)
        return model
    }

    /**
     * One file a platform folder picker found, named by its path inside the picked folder.
     *
     * [path] is relative and uses `/`, because a document tree has no filesystem path to give: the
     * file lives behind a URI until it is read. [name] is its display name, which is what a CSV's
     * bare `take 1.wav` cell resolves against.
     */
    data class PickedEntry(val path: String, val name: String) {
        val isCsv: Boolean get() = name.substringAfterLast('.', "").equals("csv", true)
    }

    /**
     * What a picked folder contributes to a dataset.
     *
     * [files] is what the platform should copy, as relative paths: every CSV found, each file those
     * CSVs' rows resolve to, and -- when there is no CSV at all -- the folder's own files.
     */
    data class FolderPlan(
        val files: List<String>,
        val csvs: Int,
        val rows: Int,
        val missing: Int,
    )

    /**
     * Reads a picked folder's listing and decides which of its files are the dataset.
     *
     * THE CSV IS THE INDEX. Its rows name the media, often under a folder (`clips/0001.wav`) or with
     * a path from the machine it was written on (`C:\data\clips\0001.wav`), and each match is the
     * file to copy. A file picker could never do this: it hands over the files the user tapped and
     * nothing else, so a CSV pulled in alone has none of the media it names beside it. A folder can
     * be listed, so the manifest is found first and its files are brought along with it.
     *
     * A folder with no CSV is not an error: images, chapters and meshes are their own dataset, so
     * that folder's files are taken wholesale (up to [MAX_FOLDER_FILES], which stops a mistaken pick
     * of a huge tree from becoming a huge copy).
     */
    fun planFolderImport(
        entries: List<PickedEntry>,
        csvIsManifest: Boolean = true,
        readCsv: (PickedEntry) -> String?,
    ): FolderPlan {
        val listing = DatasetReferences.Listing(entries.map { it.path })
        val chosen = LinkedHashSet<String>()
        // Only a kind whose dataset is INDEXED by a CSV treats one as a manifest. A text corpus is
        // its .txt files; a CSV beside them is data, and reading it as an index of the folder would
        // import the table and drop the corpus.
        val csvs = if (csvIsManifest) {
            entries.filter { it.isCsv && it.name !in GENERATED_FILES }
        } else {
            emptyList()
        }
        var rows = 0
        var missing = 0
        for (csv in csvs) {
            chosen += csv.path
            val text = readCsv(csv) ?: continue
            val parsed = parseCsvText(text)
            rows += parsed.size
            for (row in parsed) {
                // No extension list decides which cell is a path: every distinct cell is offered to
                // the listing, and only one that names a listed file is taken. That is what lets a
                // caption that happens to end in `.png` be a caption and a bare `take 1.wav` be a
                // file, on every kind of dataset this form can build.
                for (value in row.values.distinct()) {
                    if (DatasetReferences.isUrl(value)) continue
                    val match = listing.find(value)
                    if (match != null) {
                        chosen += match
                    } else if (DatasetReferences.looksLikeReference(value)) {
                        // Counted rather than guessed at: a row naming a file that is not in the
                        // folder is the difference between "the dataset was read" and "the dataset
                        // was half read", and the page reports the number.
                        missing++
                    }
                }
            }
        }
        if (csvs.isEmpty()) {
            entries.asSequence()
                .filter { !it.name.startsWith(".") && it.name !in GENERATED_FILES }
                .take(MAX_FOLDER_FILES)
                .forEach { chosen += it.path }
        }
        return FolderPlan(chosen.toList(), csvs.size, rows, missing)
    }

    /**
     * Copies imported files into the model's dataset folder.
     *
     * COPIED, not referenced. A dataset assembled from where the user happened to keep the files would
     * break the day they moved a folder, and training reads these paths months after import; the copy
     * is the file's home from then on.
     *
     * @return how many files were added.
     */
    fun attachDataset(
        model: CraftedModel,
        files: List<File>,
        overwrite: Boolean = false,
        onStage: ((String) -> Unit)? = null,
        /**
         * Keep the `train/`, `validation/` and `test/` parent folders instead of flattening.
         *
         * Only the image trainer reads folder splits; an audio or text dataset that kept its
         * folders would hand the trainer subdirectories it does not walk into. The picker stages
         * an image dataset with those folders intact, so this is what lets a publisher's own split
         * reach [PixelTrainer] instead of being re-split deterministically as one flat pile.
         */
        preserveSplits: Boolean = false,
    ): Int {
        val target = Store.datasetDir(model.id)
        var added = 0
        for (file in files) {
            if (!file.isFile) continue
            val split = if (preserveSplits) splitFolderOf(file) else null
            val destination = if (split == null) target else File(target, split).apply { mkdirs() }
            if (copyInto(model, file, destination, overwrite)) added++
            // THE CSV'S OWN PATHS ARE PART OF THE IMPORT. A dataset of this shape is a CSV plus the
            // media beside it, so a user who picked the CSV has picked the dataset: every path its
            // rows name, resolved the way the row means it (against the folder the CSV came from),
            // is imported too. Without this, importing `metadata.csv` from a voice dataset yields a
            // folder holding one CSV and a run that measures no audio at all.
            // A split-aware image import already copied every image the picker staged, in its
            // split folder. Asking the CSV for them again would put a second copy at the root,
            // outside the split, and the trainer would count those as training examples too.
            if (file.extension.equals("csv", true) && !preserveSplits) {
                added += importCsvMedia(model, file, target, overwrite)
            }
        }
        // A CSV whose rows name URLs is imported as the CSV plus its files: without this, the folder
        // holds a manifest and the run afterwards measures nothing decodable. Done once, here, rather
        // than per CSV, so a URL named by two CSVs is fetched once.
        val fetched = com.prism.launcher.datasetcraft.DatasetCraft.materialise(target)
        if (fetched > 0) {
            PrismPlatform.log.info("ModelCraft", "Downloaded $fetched file(s) named by URL into ${model.name}")
        }
        // New audio means the old voice measurements no longer describe this dataset.
        invalidateVoicePreparation(model)
        // A FOLDER OF TAKES HAS NO TRANSCRIPTS UNTIL SOMETHING HEARS THEM. Importing a speech
        // dataset is that moment: the files are here, the CSV may name them or may not, and the
        // trainer needs words in the CSV rather than a text file beside each clip. Audio and video
        // both carry speech -- a video row's transcript comes from its audio track -- so both are
        // transcribed here. The pass writes into the dataset's own CSV and never replaces a value
        // that is already there.
        if ((model.kind == Kind.AUDIO && !isMusic(model)) || model.kind == Kind.VIDEO) {
            val transcribed = DatasetTranscription.transcribeMissing(
                target,
                includeVideo = model.kind == Kind.VIDEO,
            ) { line, _ ->
                onStage?.invoke(line)
            }
            if (transcribed.transcribed > 0) {
                PrismPlatform.log.info("ModelCraft", "${model.name}: ${transcribed.summary}")
            }
            // Import must not end in the silence the user came from: when nothing could hear the
            // files, the reason is on the model record where the run will read it.
            if (transcribed.transcribed == 0 && transcribed.unavailableReason != null) {
                PrismPlatform.log.warn("ModelCraft", "${model.name}: ${transcribed.summary}")
            }
        }
        Store.save(model)
        return added + fetched
    }

    /**
     * Re-reads a dataset from the user's source files, replacing the model's copies.
     *
     * Import copies a dataset into the model's own folder once; editing the CSV in the folder it came
     * from then changes nothing the trainer reads. This is that update: the picked files overwrite
     * their copies BY NAME (a re-synced `take.wav` must still be named `take.wav` for the CSV row that
     * names it), new files are added, and the manifest and auto-transcription are run again. Files the
     * source no longer contains are left in place because a dataset may have several sources and this
     * pass cannot tell which of them stopped naming one.
     *
     * Blocking, like [attachDataset]; callers run it off the UI thread.
     *
     * @return the dataset summary [prepare] wrote after the refresh.
     */
    fun resyncDataset(
        model: CraftedModel,
        files: List<File>,
        onStage: ((String) -> Unit)? = null,
        preserveSplits: Boolean = false,
    ): String {
        if (files.isEmpty()) return "No files were picked, so the dataset was not changed."
        val synced = attachDataset(
            model, files, overwrite = true, onStage = onStage, preserveSplits = preserveSplits,
        )
        val summary = prepare(model)
        PrismPlatform.log.info(
            "ModelCraft",
            "${model.name}: re-synced $synced source file(s) into ${Store.datasetDir(model.id).name}",
        )
        return summary
    }

    /**
     * Copies one file into the model's dataset folder and records it on the model.
     *
     * [overwrite] is what a re-sync uses: the source file REPLACES the copy with the same name even
     * when its length changed. A normal import keeps the old copy and gives the changed file a
     * timestamped name, because that import may be bringing a new take beside an old one rather than
     * updating either.
     *
     * @return true when the file was newly added or replaced, false when it was already there (an
     *   import run twice, or a CSV naming a file the user also picked by hand) or could not be read.
     */
    /** The split folder [file] is already inside, or null when it is in a flat dataset. */
    private fun splitFolderOf(file: File): String? = when (
        file.parentFile?.name?.trim()?.lowercase(Locale.ROOT)
    ) {
        "train", "training" -> "train"
        "val", "valid", "validation" -> "validation"
        "test", "testing" -> "test"
        else -> null
    }

    private fun copyInto(
        model: CraftedModel,
        file: File,
        target: File,
        overwrite: Boolean = false,
    ): Boolean {
        // Spaces and non-ASCII letters are part of a file name, not unsafe characters. Replacing them
        // was what made an imported `take 1.wav` stop matching the CSV row that named it.
        val safe = DatasetReferences.storedName(file.name)
        var destination = File(target, safe)
        if (!overwrite && destination.isFile && destination.length() != file.length()) {
            destination = File(target, "${Util.stamp()}-$safe")
        }
        return runCatching {
            val existed = destination.isFile
            // A file already in the dataset folder is not copied onto itself: that is the same file,
            // and a copy-to-itself is a way to lose it.
            if (!file.absolutePath.equals(destination.absolutePath, ignoreCase = true)) {
                file.copyTo(destination, overwrite = destination.isFile)
            }
            val known = destination.absolutePath in model.dataset
            if (!known) model.dataset.add(destination.absolutePath)
            if (overwrite) existed || !known else !known
        }.onFailure {
            PrismPlatform.log.warn("ModelCraft", "Could not import ${file.name}: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Imports the media a CSV's rows name.
     *
     * Each value in each row is read as a path until one is found on disk: absolute paths are taken as
     * they are, and a relative one is resolved against the folder the CSV itself came from, which is
     * how these manifests are written (`clips/0001.mp4` beside `metadata.csv`, `../wavs/a.wav` one
     * level up). Nothing is guessed about which column is the path column -- a row is a list of values
     * and the ones that name files are imported, because a caption that happens to end in `.png`
     * simply will not resolve to a file.
     *
     * @return how many files were copied in.
     */
    private fun importCsvMedia(
        model: CraftedModel,
        csv: File,
        target: File,
        overwrite: Boolean = false,
    ): Int {
        val base = csv.parentFile
        val index = DatasetReferences.Index(listOfNotNull(base, target))
        var added = 0
        for (row in parseCsv(csv)) {
            // No extension list decides this: every cell is offered to the resolver, and only one that
            // names a file that actually exists is copied. That is what lets a text corpus, a Game
            // asset and a speech clip all use the same import path.
            for (value in row.values.distinct()) {
                val source = DatasetReferences.resolve(value, base, target, index) ?: continue
                if (copyInto(model, source, target, overwrite)) added++
            }
        }
        if (added > 0) {
            PrismPlatform.log.info(
                "ModelCraft",
                "Imported $added file(s) named by ${csv.name} in the model's dataset",
            )
        }
        return added
    }

    /**
     * Reads the dataset folder and writes the manifest every other step reads.
     *
     * The CSV pass is the part that matters: a dataset of this shape is a list of media files plus one
     * CSV whose rows say what each file IS -- the caption, the transcript. So a CSV among the imports
     * is parsed rather than counted as another sample, each row's referenced path is checked against
     * what was imported (a missing one is reported, because a trainer that silently drops half a
     * dataset is a trainer that silently makes a bad model), and the result is written to
     * `dataset.json` alongside links to the frame folders.
     */
    fun prepare(model: CraftedModel): String {
        val dir = Store.datasetDir(model.id)
        val spec = datasets.getValue(model.kind)
        // A CSV IN THIS FOLDER MAY STILL NAME FILES SOMEWHERE ELSE. This is where a hand-copied
        // manifest, or one whose import ran before the reference resolver did, gets its files: every
        // row that resolves is brought in before the folder is read. For every kind, because a text
        // corpus names its chapters the same way a speech dataset names its clips.
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) && it.name !in GENERATED_FILES }
            ?.forEach { csv -> importCsvMedia(model, csv, dir) }
        // THE FILES THIS PIPELINE WROTE ARE NOT PART OF THE DATASET. vocal_features.csv is a row per
        // clip and dataset_merged.csv is the dataset's own CSV again with those columns beside it, so
        // counting either as a dataset CSV would double every row and make a second run merge a
        // merged file into itself.
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name !in GENERATED_FILES }
            .orEmpty()
            .sortedBy { it.name }
        val media = files.filterNot { it.extension.equals("csv", true) || it.extension.equals("json", true) }
        val index = DatasetReferences.Index(listOf(dir))
        val rows = mutableListOf<JSONObject>()
        var csvRows = 0
        var missing = 0
        if (spec.csv) {
            for (csv in files.filter { it.extension.equals("csv", true) }) {
                val parsed = parseCsv(csv)
                csvRows += parsed.size
                for (row in parsed) {
                    // WHICH CELL IS THE PATH is answered by the resolver, not by a list of extensions:
                    // Windows separators, a nested folder, a file name with spaces and a path that
                    // was made absolute on another machine all count. If every candidate is missing,
                    // the first one is still the row's reference, so the summary can say so.
                    val candidates = row.values.filter { value ->
                        DatasetReferences.looksLikeReference(value) || index.find(value) != null
                    }
                    val referenced = candidates.firstOrNull { value ->
                        DatasetReferences.resolve(value, dir, dir, index) != null
                    } ?: candidates.firstOrNull().orEmpty()
                    // FOUND means the file is here: by name in the dataset folder, or at the path the
                    // row itself gives (absolute, relative, either separator). What is not found is
                    // counted and reported, because a trainer that silently drops half a dataset is a
                    // trainer that silently makes a bad model.
                    val exists = referenced.isEmpty() ||
                        DatasetReferences.resolve(referenced, dir, dir, index) != null
                    if (!exists) missing++
                    rows += JSONObject().apply {
                        put("source", csv.name)
                        row.forEach { (k, v) -> put(k, v) }
                        put("media_found", exists)
                    }
                }
            }
        }
        val frames = if (spec.frames) extractFrames(model, media) else 0
        // A GAME DATASET IS CLASSIFIED, not just listed. Extension says what a .png is and says
        // nothing about a .fbx or a .uasset, so the same classifier DatasetCraft uses fills in each
        // row's type, format, material and engine. The trainer reads meshes directly; the manifest is
        // how AssetCraft and a person can see what the dataset actually contained.
        val gameAssets = if (model.kind == Kind.GAME) {
            media.mapNotNull { file ->
                GameAssets.describe(file)?.let { descriptor ->
                    JSONObject().apply {
                        put("file", file.name)
                        put("type", descriptor.type.csv)
                        put("format", descriptor.format)
                        put("role", descriptor.role)
                        put("material", descriptor.material)
                        put("engine", descriptor.engine)
                        put("channels", descriptor.channels)
                        put("color_space", descriptor.colorSpace)
                    }
                }
            }
        } else {
            emptyList()
        }
        val manifest = JSONObject().apply {
            put("kind", model.kind.name)
            put("files", JSONArray(media.map { it.name }))
            put("csv_rows", csvRows)
            put("missing_media", missing)
            put("frames", frames)
            put("rows", JSONArray(rows))
            if (model.kind == Kind.GAME) {
                put("game_assets", JSONArray(gameAssets))
                put(
                    "game_asset_counts",
                    JSONObject().apply {
                        gameAssets.groupBy { it.optString("type") }.forEach { (type, entries) ->
                            put(type, entries.size)
                        }
                    },
                )
            }
        }
        Store.manifestFile(model.id).writeText(manifest.toString())
        val summary = buildString {
            append("${media.size} file${if (media.size == 1) "" else "s"}")
            if (csvRows > 0) append(" · $csvRows CSV row${if (csvRows == 1) "" else "s"}")
            if (missing > 0) append(" · $missing referenced file${if (missing == 1) "" else "s"} not found")
            if (frames > 0) append(" · $frames frames extracted")
            if (gameAssets.isNotEmpty()) {
                val meshes = gameAssets.count { it.optString("type") == GameAssets.Type.MESH.csv }
                val textures = gameAssets.count { it.optString("type") == GameAssets.Type.TEXTURE.csv }
                append(" · $meshes mesh${if (meshes == 1) "" else "es"}")
                append(" · $textures texture${if (textures == 1) "" else "s"}")
            }
        }
        model.datasetSummary = summary
        Store.save(model)
        return summary
    }

    /**
     * Writes the training recipe and hands it to the platform's trainer, when there is one.
     *
     * The recipe is the deliverable: architecture, family, every field, and the dataset manifest, in
     * the one file a training script is pointed at. What happens after that is honest -- a trained
     * status when [runner] ran it, a recipe-ready status naming the file when no trainer for that
     * architecture exists in this build.
     */
    /**
     * Measures the dataset and returns the voices the pre-training dialog should propose.
     *
     * This is the speech preparation the run used to do invisibly, moved to where the user can see
     * and edit its answer. It transcribes anything missing, measures every clip, builds the voice
     * bank and writes `audio_model.json`, then proposes one row per voice. The run that follows
     * reuses those measurements through [voicePreparationReady].
     *
     * Blocking; pass a callback for the progress line and call it off the UI thread.
     */
    fun prepareVoices(
        model: CraftedModel,
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): List<VoicePack.Voice> {
        if (model.kind != Kind.AUDIO || isMusic(model)) return emptyList()
        if (model.dataset.isEmpty()) return emptyList()
        if (voicePreparationReady(model)) return VoicePack.proposal(model)
        prepare(model)
        val note = measureVoices(model, onStage)
        preparedVoicesFor = model.id
        preparedVoiceCount = voiceCount(model) ?: 0
        model.datasetSummary = note
        Store.save(model)
        return VoicePack.proposal(model)
    }

    fun train(model: CraftedModel): Boolean {
        if (_state.value.running) return false
        if (model.dataset.isEmpty()) {
            _state.value = State(
                error = "Import a dataset first — there is nothing to train on.",
                modelId = model.id,
            )
            return false
        }
        validationError(model)?.let { problem ->
            _state.value = State(error = problem, modelId = model.id)
            return false
        }
        cancelled = false
        lastProgressReportAt = 0L
        model.status = STATUS_TRAINING
        Store.save(model)
        _state.value = State(running = true, stage = "Preparing the dataset…", modelId = model.id)
        // A new run never inherits the previous one's graph: a failed preparation would otherwise
        // leave a playhead for a model that is no longer being trained.
        VoiceTrainingMonitor.clear()

        job = scope.launch {
            try {
                val summary = prepare(model)
                if (cancelled) return@launch

                // THE VOICE IS MEASURED BEFORE THE RECIPE IS WRITTEN, because the recipe has to
                // NAME the file the measurements went into -- which is what makes them training data
                // rather than a report beside it. When the voices dialog ran first, its preparation
                // is reused rather than decoding the whole dataset a second time.
                val vocal = if (model.kind == Kind.AUDIO && !isMusic(model)) {
                    if (voicePreparationReady(model) && model.datasetSummary.isNotBlank()) {
                        model.datasetSummary
                    } else {
                        measureVoices(model)
                    }
                } else {
                    null
                }
                if (cancelled) return@launch

                _state.value = _state.value.copy(
                    stage = "Writing the recipe…",
                    progress = 0.4f,
                )
                val recipe = recipeFile(model)
                // Local training is the user's switch. With it off this build writes the recipe and
                // the export and touches no trainer, which is what a user who trains elsewhere asks
                // for -- and the only honest thing to do when there is no trainer is to say so.
                // THE GRAPH STARTS AFTER THE VOICE PASS, because that pass is what writes the
                // features this reads, and only when a local trainer will actually report steps.
                val graph = model.kind == Kind.AUDIO && !isMusic(model) && trainsLocally(model)
                if (graph) VoiceTrainingMonitor.start(model)
                val trained = try {
                    if (trainsLocally(model)) runner?.invoke(model, recipe) else null
                } finally {
                    if (graph) VoiceTrainingMonitor.finish(model.id)
                }
                if (cancelled) return@launch

                // ── The export, on every run of every kind and on BOTH outcomes. A run that made
                // weights and a run that made a recipe both end with a folder a person can carry to
                // another machine: the model's own artifacts, the dataset's CSV, the recipe, the
                // configuration, and a Python script that builds the same model again.
                _state.value = _state.value.copy(stage = "Exporting the model…", progress = 0.75f)
                val exported = runCatching { ModelCraftExport.export(model) }
                    .onFailure {
                        PrismPlatform.log.warn("ModelCraft", "Exporting ${model.id} failed: ${it.message}")
                    }
                    .getOrNull()
                if (cancelled) return@launch
                val exportLine = exported?.let {
                    "Exported to ${it.absolutePath} — ${ModelCraftExport.scriptName(model)} rebuilds this " +
                        "model on any machine with Python."
                }

                if (trained != null) {
                    model.status = STATUS_TRAINED
                    model.note = "Trained: $trained"
                    Store.save(model)
                    // A GAME MODEL IS A MODEL THE REST OF THE APP CAN USE THE MOMENT IT EXISTS.
                    // Registering its own folder (the one holding game_model.json/weights) is what
                    // makes the completed run appear in Prism Settings' game-generator list and in
                    // GameCraft's picker without a second export or import step.
                    if (model.kind == Kind.GAME) {
                        runCatching {
                            PrismSettings.addImportedModel(
                                PrismSettings.ImportedModel(
                                    path = Store.datasetDir(model.id).absolutePath,
                                    displayName = model.name,
                                    type = PrismSettings.MODEL_TYPE_GAME,
                                ),
                            )
                        }.onFailure {
                            PrismPlatform.log.warn(
                                "ModelCraft",
                                "Trained ${model.id} but could not register its game model: ${it.message}",
                            )
                        }
                    }
                    _state.value = State(
                        running = false,
                        complete = true,
                        modelId = model.id,
                        stage = "Trained",
                        progress = 1f,
                        note = buildString {
                            if (!vocal.isNullOrBlank()) appendLine(vocal)
                            if (exportLine != null) appendLine(exportLine)
                            append(model.note)
                        },
                    )
                } else {
                    model.status = STATUS_READY
                    model.note = if (!trainsLocally(model)) {
                        "Recipe written to ${recipe.absolutePath} — local training is switched off for " +
                            "this model, so the recipe, the dataset it names and the export are what " +
                            "a trainer reads."
                    } else {
                        // TWO READINGS, and the note says both rather than guessing: a build with no
                        // trainer for the kind, or a trainer that found nothing it can read in this
                        // dataset -- which the log names file by file. What it must never say is that
                        // a model was made when one was not.
                        "Recipe written to ${recipe.absolutePath} — the local trainer produced no model " +
                            "for ${model.family.ifBlank { model.architecture }}: either this build has " +
                            "none for that kind, or it found nothing in the dataset it can read (the " +
                            "log says which). The recipe and the dataset it names are what a trainer " +
                            "reads."
                    }
                    Store.save(model)
                    _state.value = State(
                        running = false,
                        complete = true,
                        modelId = model.id,
                        stage = "Recipe ready",
                        progress = 1f,
                        note = buildString {
                            if (!vocal.isNullOrBlank()) appendLine(vocal)
                            appendLine(summary)
                            if (exportLine != null) appendLine(exportLine)
                            append(model.note)
                        },
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.error("ModelCraft", "Preparing ${model.id} failed", t)
                model.status = STATUS_FAILED
                model.note = t.message ?: t::class.simpleName.orEmpty()
                Store.save(model)
                _state.value = State(
                    running = false,
                    modelId = model.id,
                    error = model.note,
                    stage = "Failed",
                )
            }
        }
        return true
    }

    fun cancel() {
        if (!_state.value.running) return
        cancelled = true
        _state.value = _state.value.copy(stage = "Stopping…")
    }

    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    fun delete(model: CraftedModel) {
        Store.delete(model)
    }

    // ── The speech preparation ──────────────────────────────────────────────────────────────────

    /**
     * The speech-specific half of a run: measure the voice in every clip, fold the measurements into
     * the dataset's own CSV, and write the artifacts the recipe names.
     *
     * Runs for BOTH audio types, because both train on the same measurements -- a cloning model reads
     * them to keep the speaker's breath and jitter, a text-to-speech model reads them to learn how the
     * speaker moves between words. It runs before [recipeFile], not after training, because the recipe
     * has to name files that exist.
     *
     * The stages are reported through [state] so the progress bar moves through a phase that on a
     * small dataset is quick and on a long one is not: decoding every clip is the expensive part.
     */
    private fun measureVoices(
        model: CraftedModel,
        onStage: ((String, Float) -> Unit)? = null,
    ): String {
        val dir = Store.datasetDir(model.id)

        fun stage(text: String, fraction: Float) {
            // The dialog's pass is not a run, so it must not move the training state; it gets the
            // line through the callback instead. A real run still prints to the progress bar.
            onStage?.invoke(text, fraction)
            if (_state.value.running) {
                _state.value = _state.value.copy(stage = text, progress = 0.1f + fraction * 0.28f)
            }
        }

        // BEFORE THE FILES ARE LISTED, because a dataset whose CSV names URLs has no clips on disk
        // yet: those rows are how most published speech datasets describe themselves. Downloading
        // first is what makes them part of the measurement instead of rows that decode to nothing.
        stage("Fetching the files this dataset names by URL…", 0f)
        val fetched = com.prism.launcher.datasetcraft.DatasetCraft.materialise(dir)

        // SECOND CHANCE FOR A DATASET IMPORTED BEFORE THIS PASS EXISTED, or copied in by hand:
        // anything the importer could not hear yet gets its transcript now, before the vocal merge
        // reads the CSV. A dataset that is already labelled is left untouched.
        stage("Transcribing clips that have no transcript…", 0f)
        val transcribed = DatasetTranscription.transcribeMissing(dir) { line, fraction ->
            stage(line, fraction * 0.1f)
        }
        if (transcribed.unavailableReason != null) {
            // Named on the record and in the log, not swallowed: otherwise the run ends with "no
            // clip had a transcript", which reads as a broken dataset rather than a missing model.
            PrismPlatform.log.warn("ModelCraft", "${model.name}: ${transcribed.summary}")
        }

        stage("Listening to every clip…", 0f)
        // The CSV's own words are the language measurement. Reading them is what keeps a dataset
        // that already shipped descriptions from making whisper listen to every clip again just
        // to decide whether it is English -- on a phone that second pass reads as a freeze.
        val labelled = DatasetTranscription.transcripts(dir)
        val extraction = SpeechDataset.extract(dir, ::stage, labelled)

        // The dataset's own CSVs, which may be several -- and never the files this wrote itself.
        val ownCsvs = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) && it.name !in GENERATED_FILES }
            .orEmpty()

        stage("Merging the vocal measurements into the dataset…", 0.6f)
        val features = extraction.featuresFile
        val merged = if (features == null) null else SpeechDataset.merge(dir, features, ownCsvs, ::stage)

        // The voice bank, for the one task that has one. Built BEFORE the note is written so the
        // note can report what the model came out with rather than what was asked for.
        val type = audioType(model)
        val requested = voiceCount(model) ?: 0
        var bank: JSONObject? = null
        if (type == AudioType.TEXT_TO_SPEECH) {
            stage("Choosing how many voices the model can speak in…", 0.85f)
            bank = SpeechDataset.voices(extraction.rows, requested, dir)
        }

        val profile = SpeechDataset.speakerProfile(extraction.rows, dir, model.name)
        val made = CraftedAudioModel.write(
            directory = dir,
            name = model.name,
            type = type,
            rows = extraction.rows,
            requestedVoices = requested,
            bank = bank,
        )

        val note = buildString {
            if (fetched > 0) append("downloaded $fetched file(s) named by URL · ")
            if (transcribed.transcribed > 0) {
                append(transcribed.summary).append(" · ")
            } else if (transcribed.unavailableReason != null) {
                append(transcribed.unavailableReason).append(" · ")
            }
            append(extraction.summary)
            if (merged == null) {
                append(" · nothing was merged: no clip could be decoded")
            } else {
                append(" · merged into ${merged.name}")
                if (ownCsvs.isEmpty()) append(" (the dataset shipped no CSV, so it is the features)")
            }

            if (type == AudioType.TEXT_TO_SPEECH) {
                append(" · ")
                if (bank == null) {
                    append("no voice bank: the measurements could not be read back")
                } else {
                    val built = bank.optLong("built", 0L).toInt()
                    append("$built voice type")
                    append(if (built == 1) "" else "s")
                    append(" of $requested drawn from the dataset")
                    if (built < requested) {
                        append(" (k-means cannot split ${extraction.rows.size} clip(s) into more ")
                        append("voices than there are examples; add more speakers to the dataset)")
                    }
                }
            }

            append(if (profile != null) " · speaker profile written" else " · nothing to profile")
            if (made != null) append(" ").append(made.summary)
        }

        model.datasetSummary = note
        Store.save(model)
        PrismPlatform.log.info("ModelCraft", "Speech preparation for ${model.id}: $note")
        return note
    }

    /**
     * The files this pipeline writes into a dataset folder. Never treated as dataset files: they are
     * rows about the dataset, and a run that counted them as part of it would see every clip twice.
     */
    private val GENERATED_FILES = setOf(
        SpeechDataset.FEATURES_FILE,
        SpeechDataset.MERGED_FILE,
        SpeechDataset.PROFILE_FILE,
        SpeechDataset.VOICES_FILE,
        CraftedAudioModel.FILE,
        GameTrainer.META_FILE,
        GameTrainer.WEIGHTS_FILE,
    )

    /**
     * How many of a manifest-less folder's files one import takes.
     *
     * A CSV names its dataset exactly, so it is not capped. A folder with no CSV is taken wholesale,
     * and picking the wrong folder -- Downloads, a camera roll -- must not turn into a copy of it.
     */
    private const val MAX_FOLDER_FILES = 4_000

    /**
     * The recipe file: the model, and every field with its value or default.
     *
     * Written as one JSON object where each field key is a top-level entry, which is the shape the
     * frameworks this vocabulary comes from read (`train.py --config recipe.json`). The dataset
     * manifest is named, not inlined, so a large dataset does not become a large recipe.
     */
    fun recipeFile(model: CraftedModel): File {
        // THE DATASET SUMMARY IS NOT THIS FUNCTION'S TO OVERWRITE. `prepare` is called here because
        // the recipe names the manifest it writes, but for an audio model the summary on the record
        // was written by the speech preparation and says what the run MEASURED -- how many clips,
        // which languages, how many voice types. Re-preparing would replace all of that with a file
        // count, which is how a run that had just measured a dataset reported "3 files · 2 CSV rows"
        // as its result.
        val summary = model.datasetSummary
        prepare(model)
        model.datasetSummary = summary
        val recipe = JSONObject().apply {
            put("kind", model.kind.name)
            put("model_type", model.kind.modelType)
            put("architecture", model.architecture)
            put("family", model.family)
            put("name", model.name)
            put("dataset_dir", Store.datasetDir(model.id).absolutePath)
            put("dataset_manifest", Store.manifestFile(model.id).absolutePath)
            if (model.kind == Kind.INTERPOLATION) {
                put("frames_dir", Store.framesDir(model.id).absolutePath)
            }
            val scratch = fromScratch(model)
            val baseKeys = baseFields.getValue(model.kind)
            for (field in fields.getValue(model.kind)) {
                // A FROM-SCRATCH RUN HAS NO BASE, so it does not get the base's fields either. A
                // trainer handed both a base_checkpoint and "build the architecture yourself" would
                // have to guess which one the user meant, and guessing wrong is a run that quietly
                // fine-tunes when the user asked for a model built from nothing.
                if (scratch && field.key in baseKeys) continue
                put(field.key, model.param(field.key))
            }

            // ── How long the run trains. Written for every kind, as NUMBERS rather than the form's
            // strings: a trainer scheduling a loop should not have to parse what a text box held.
            // `schedule` repeats the pair in one object, so a reader that looks for either shape
            // finds it.
            // ── The storage precision, in the form a trainer reads rather than only the form's own
            // key. `quantization` is already above because every kind declares it as a field; the
            // bits-per-weight beside it is what a script needs to size the output before it starts.
            quantizationLevel(model.param(KEY_QUANTIZATION))?.let { level ->
                put(KEY_QUANTIZATION, level.fileSuffix)
                put("quantization_bits_per_weight", level.bitsPerWeight)
                put(
                    "quantization_note",
                    "Weights are stored at ${level.fileSuffix} (${level.bitsPerWeight} bits per weight) " +
                        "from the start of the run. Levels below about four bits are designed for models " +
                        "trained at that precision; on an ordinary model expect quality loss that the size " +
                        "saving does not repay.",
                )
            }

            val schedule = schedule(model)
            put(KEY_EPOCHS, schedule.epochs)
            put(KEY_STEPS_PER_EPOCH, schedule.stepsPerEpoch)
            put(
                "schedule",
                JSONObject().apply {
                    put("epochs", schedule.epochs)
                    put("steps_per_epoch", schedule.stepsPerEpoch)
                    put("planned_steps", schedule.plannedSteps)
                    put("note", STEPS_PER_EPOCH_NOTE)
                },
            )

            // ── How the model is sampled after training. Only a text model needs it, and a trainer
            // that ignores it still produces the greedy answer; writing it keeps the test page and
            // the recipe describing the same distribution.
            if (model.kind == Kind.TEXT) {
                put(
                    "generation",
                    JSONObject().apply {
                        put("temperature", paramDouble(model, "temperature", 0.5).coerceIn(0.1, 2.0))
                        put("top_k", paramInt(model, "top_k", 10).coerceIn(1, 200))
                        put(
                            "repetition_penalty",
                            paramDouble(model, "repetition_penalty", 1.15).coerceIn(1.0, 2.0),
                        )
                        put("note", "Sampling used by respond/continueBytes; greedy is not used for word models.")
                    },
                )
            }

            // ── How this model comes into being. Written for every kind, on every run, because it is
            // the first thing a trainer has to know: load a base, or create the architecture.
            put(KEY_FROM_SCRATCH, scratch)
            put(KEY_TRAIN_LOCALLY, trainsLocally(model))
            put(
                "creation",
                JSONObject().apply {
                    put("from_scratch", scratch)
                    put("mode", if (scratch) SCRATCH_MODE else BASE_MODE)
                    put("train_locally", trainsLocally(model))
                    put("architecture", model.architecture)
                    put("family", model.family)
                    if (scratch) {
                        put("initialisation", scratchConfig(model))
                        put("fields_not_used", JSONArray(baseKeys))
                        put("fields_not_used_note", "Base-only fields: not in this recipe and not " +
                            "read by a from-scratch run. The values typed on the form are kept on " +
                            "the model record, so switching the box back off restores them.")
                    } else {
                        put("base_field", baseKeys.first())
                        put("base", model.param(baseKeys.first()))
                        put("initialisation", JSONObject.NULL)
                    }
                },
            )

            // ── What the run produces. Written for every kind, because a trainer that was asked for
            // a GGUF and wrote a safetensors -- or the other way round -- made the wrong deliverable.
            // The components list says up front how many files that is, so a page showing "3 files"
            // for an audio model is reading the same list the writers do.
            val format = fileFormat(model)
            put(KEY_FILE_FORMAT, format.recipeValue)
            put(
                "model_files",
                JSONObject().apply {
                    put("format", format.recipeValue)
                    put("extension", format.extension)
                    put("components", JSONArray(fileComponents(model.kind)))
                    put("note", format.note)
                    put(
                        "encoders_and_decoders",
                        when (model.kind) {
                            Kind.AUDIO -> "Trained on the dataset's own acoustic frames: the encoder " +
                                "turns a frame into a small code, the decoder turns the code back into " +
                                "a frame."
                            Kind.VIDEO -> "Trained on the dataset's own frames: the encoder turns a " +
                                "frame into a small code, the decoder turns the code back into a frame."
                            else -> "This kind is one network, so its build is one file with nothing " +
                                "beside it."
                        },
                    )
                },
            )

            // ── Attention. Written for the four kinds that can be shaped, and written even when the
            // checkbox is off, so a trainer always finds the same keys and has to decide nothing.
            if (model.kind in attentionKinds) {
                val attention = attention(model)
                put(KEY_SLIDING_WINDOW, attention.enabled)
                put(KEY_SLIDING_WINDOW_SIZE, if (attention.enabled) attention.window else 0)
                put(
                    KEY_FULL_ATTENTION_EVERY_NTH_LAYER,
                    if (attention.enabled) attention.fullAttentionEveryNthLayer else 0,
                )
                put(
                    "attention",
                    JSONObject().apply {
                        put("sliding_window", attention.enabled)
                        put("window", attention.window)
                        put("full_attention_every_nth_layer", attention.fullAttentionEveryNthLayer)
                        put("interleaved", attention.enabled && !attention.effectivelyFullAttention)
                        put(
                            "description",
                            if (!attention.enabled) "full attention on every layer"
                            else if (attention.effectivelyFullAttention) "full attention on every layer (N = 1)"
                            else "sliding window of ${attention.window} tokens, every " +
                                "${attention.fullAttentionEveryNthLayer}th layer full",
                        )
                    },
                )
            }

            // ── Capacity. Every kind has both boxes, and the number written here is what the shape
            // REALLY is, not the one that was typed: a transformer's width has to divide into heads,
            // so the requested 82M is built at whatever d and layer count round to. The effective
            // context is the KV-cache budget the sliding-attention settings buy, and both numbers are
            // written so a trainer on another machine builds the same shape and the same limit.
            val capacity = capacity(model)
            put(ModelCapacity.KEY_PARAMETER_COUNT, capacity.parameters)
            put("parameters_requested", capacity.requestedParameters)
            put(ModelCapacity.KEY_CONTEXT_WINDOW, capacity.effectiveContext)
            put(ModelCapacity.KEY_CONTEXT_WINDOW_BASE, capacity.configuredContext)
            put("capacity", capacity.toJson())
            put(ModelCapacity.KEY_MINIATURE_FALLBACK, miniatureFallback(model))

            // ── Game. The artifact a run writes is a shape model beside the dataset, plus the
            // manifest that classified it. Naming both means AssetCraft can find a model a person
            // carried over from another machine, and a trainer reading the recipe knows which
            // files to update when the run finishes.
            if (model.kind == Kind.GAME) {
                val directory = Store.datasetDir(model.id)
                put("game_model", File(directory, GameTrainer.META_FILE).absolutePath)
                put("game_model_weights", File(directory, GameTrainer.WEIGHTS_FILE).absolutePath)
                put("game_shape_dim", GameTrainer.SHAPE_DIM)
                put("game_token_bins", GameTrainer.TOKEN_BINS)
                put(
                    "game_objective",
                    if (model.architecture.contains("Diffuser", true)) "flow-matching" else "shape-token-autoregression",
                )
                put("game_asset_manifest", Store.manifestFile(model.id).absolutePath)
                put(
                    "game_note",
                    "The dataset's .obj, .ply and .glb meshes are described as sixteen-number shape " +
                        "vectors. AssetCraft samples that model and builds the mesh back out of the " +
                        "predicted shape.",
                )
            }

            // ── Audio. The type decides the objective; the vocal measurements and the merged CSV
            // are named as FILES rather than inlined, because they are per-clip and there are as many
            // rows as the dataset has clips.
            if (model.kind == Kind.AUDIO) {
                val type = audioType(model)
                put(KEY_AUDIO_TYPE, type.recipeValue)
                put("audio_task", type.recipeValue)
                put("audio_task_label", type.label)
                put("audio_task_description", type.note)
                if (type == AudioType.TEXT_TO_SPEECH) {
                    put(KEY_VOICE_COUNT, voiceCount(model) ?: 0)
                    put("voice_types_to_build", voiceCount(model) ?: 0)
                    put("voices_file", Store.voicesFile(model.id).absolutePath)
                    put(KEY_VOICE_ENGINE, voiceEngine(model))
                    put(KEY_ALLOW_POCKET, allowsPocketFallback(model))
                    put(
                        "voice_engine_file",
                        File(Store.datasetDir(model.id), DatasetVoiceEngine.FILE).absolutePath,
                    )
                    put(
                        "voice_engine_note",
                        if (usesBuiltInVoiceEngine(model)) {
                            "Speaks through the voice engine built from this dataset's own recordings " +
                                "(see voice_engine.json). Pocket TTS is used only when allow_pocket_tts " +
                                "is true and the built-in engine cannot speak the text."
                        } else {
                            "Speaks through Pocket TTS conditioned on the model's chosen recording."
                        },
                    )
                }
                // The user's own CSV is kept under its original name, and metadata_file points at
                // the merged one: a trainer that reads only metadata_file still gets both halves.
                put("dataset_metadata_file", model.param("metadata_file"))
                put("metadata_file", SpeechDataset.MERGED_FILE)
                put("vocal_features_csv", Store.vocalFeaturesFile(model.id).absolutePath)
                put("merged_csv", Store.mergedCsvFile(model.id).absolutePath)
                put("speaker_profile", Store.speakerProfileFile(model.id).absolutePath)
                // The model this run MADE, as opposed to the recipe that describes the run. Analogous
                // to a checkpoint path, and named for the same reason: so a trainer, an engine or a
                // person can open the artifact the dataset was measured into.
                put("crafted_model", Store.audioModelFile(model.id).absolutePath)
                put("gender", model.param("gender"))
                put(ModelCapacity.KEY_MINIATURE_FALLBACK, miniatureFallback(model))
                // The speech model proper: a trainer writes these beside the acoustic artifacts, and
                // naming them here is what lets an importer, a test or another machine find them.
                put(
                    "speech_model",
                    JSONObject().apply {
                        put("metadata_file", TtsTrainer.METADATA_FILE)
                        put("weights_file", TtsTrainer.WEIGHTS_FILE)
                        put("codec_weights_file", TtsTrainer.CODEC_FILE)
                        put(
                            "components",
                            JSONArray(
                                listOf(
                                    "text_encoder", "phoneme_aligner", "duration_model",
                                    "acoustic_decoder", "codec", "vocoder", "speaker_encoder",
                                    "voice",
                                ),
                            ),
                        )
                        put(
                            "reference_audio",
                            model.param("speaker_reference").ifBlank {
                                "the cleanest clip in the dataset"
                            },
                        )
                        put(
                            "voice_engine",
                            if (usesBuiltInVoiceEngine(model)) "dataset" else "pocket-tts",
                        )
                        put("allow_pocket_tts", allowsPocketFallback(model))
                        put("engine_file", DatasetVoiceEngine.FILE)
                        put(
                            "note",
                            "Training measures every clip and chooses the cleanest recording per " +
                                "language and gender as the model's voice. When the dataset engine is " +
                                "selected, the packaged voices are measured into voice_engine.json and " +
                                "the local studio synthesizer speaks through those profiles. Pocket TTS " +
                                "remains an explicit choice and an optional multilingual fallback; it " +
                                "is not used unless this model allows it. The text encoder, aligner, " +
                                "duration model, decoder, codec and Griffin-Lim vocoder are fitted " +
                                "beside it for diagnosis and for the miniature renderer; a compact " +
                                "network trained on a few minutes of audio cannot free-run a new " +
                                "sentence, and those stages are not what [synthesize] returns.",
                        )
                    },
                )
                put("vocal_feature_columns", JSONArray(VocalFeatures.COLUMNS))
                put("vocal_feature_units", JSONObject().apply {
                    VocalFeatures.units.forEach { (column, unit) -> put(column, unit) }
                })
                put(
                    "vocal_feature_note",
                    "One row per clip in dataset_merged.csv, right beside the metadata columns the " +
                        "dataset shipped with. Measured from the audio, not labelled by hand: prosody, " +
                        "micro-variation, expression, glottal source behaviour, timbre, timing, " +
                        "coarticulation and phonation registers. Language is detected per clip too.",
                )
            }
        }
        val file = Store.recipeFile(model.id)
        file.writeText(recipe.toString())
        model.recipePath = file.absolutePath
        Store.save(model)
        return file
    }

    private fun extractFrames(model: CraftedModel, media: List<File>): Int {
        val extractor = frameExtractor ?: return 0
        val videos = media.filter { VIDEO_EXTENSIONS.any { ext -> it.extension.equals(ext, true) } }
        if (videos.isEmpty()) return 0
        val out = Store.framesDir(model.id)
        var frames = 0
        for (video in videos) {
            val folder = File(out, video.nameWithoutExtension)
            folder.mkdirs()
            frames += runCatching { extractor(video, folder) }.getOrDefault(0)
        }
        return frames
    }

    /**
     * A CSV, read as rows of named columns.
     *
     * Flat and quote-aware, which is what these manifests are: one header line, one media file per
     * row, a path and a sentence. Nothing here needs a full RFC 4180 parser, and a dependency for one
     * file type would be a dependency for every platform.
     */
    fun parseCsv(file: File): List<Map<String, String>> = parseCsvText(readCsvText(file))

    /**
     * A dataset CSV in whichever 8-bit encoding it was saved.
     *
     * Spreadsheet exports on Windows are routinely Windows-1252 even when the headers are ASCII.
     * Decoding those bytes as UTF-8 replaces every accented letter with U+FFFD, which silently
     * destroys exactly the words language detection keys on ("español", "français") and turns
     * their rows into the recipe's fallback language. UTF-8 wins when it decodes cleanly; otherwise
     * the file is read as Windows-1252, whose 0x80..0x9F range is the only difference that matters
     * for transcripts.
     */
    private fun readCsvText(file: File): String {
        val bytes = file.readBytes()
        val utf8 = bytes.toString(Charsets.UTF_8)
        if (utf8.indexOf('\uFFFD') < 0) return repairMojibake(utf8)
        // A file can be mixed: one description saved by a Windows tool is Windows-1252 while the
        // next is UTF-8. Decode line by line so one bad byte does not mojibake every other row.
        val legacy = runCatching { java.nio.charset.Charset.forName("windows-1252") }
            .getOrDefault(Charsets.ISO_8859_1)
        val decoded = ArrayList<String>()
        var start = 0
        for (i in 0..bytes.size) {
            val atEnd = i == bytes.size
            if (!atEnd && bytes[i] != '\n'.code.toByte()) continue
            val line = bytes.copyOfRange(start, if (atEnd) i else i)
            val asUtf8 = line.toString(Charsets.UTF_8)
            decoded += if (asUtf8.indexOf('\uFFFD') < 0) asUtf8 else line.toString(legacy)
            if (!atEnd) decoded += "\n"
            start = i + 1
        }
        return repairMojibake(decoded.joinToString(""))
    }

    /**
     * Reverses the common double-encoding where UTF-8 bytes were read as Windows-1252 and saved
     * as UTF-8 again -- "español" arriving as "espaÃ±ol". One or two passes cover the damage; a
     * text with no such sequences, or with characters that cannot be represented in Latin-1, is
     * returned untouched.
     */
    private fun repairMojibake(text: String): String {
        var current = text
        repeat(2) {
            if (!current.contains("Ã") && !current.contains("Â")) return current
            if (current.any { it.code > 0xFF }) return current
            val repaired = current.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
            if (repaired.indexOf('\uFFFD') >= 0 || repaired == current) return current
            current = repaired
        }
        return current
    }

    /**
     * The same reader for a CSV that arrived through a document provider rather than as a file.
     *
     * A byte-order mark is dropped rather than becoming part of the first column's name: a manifest
     * saved by a Windows editor starts with one, and `\uFEFFpath` is not the `path` column a trainer
     * asks for.
     */
    fun parseCsvText(text: String): List<Map<String, String>> {
        val clean = text.removePrefix("\uFEFF")
        val headerLine = clean.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val records = readCsvRecords(clean, csvDelimiter(headerLine))
        if (records.size < 2) return emptyList()
        val header = records.first().map { it.trim().lowercase() }
        val out = mutableListOf<Map<String, String>>()
        for (cells in records.drop(1)) {
            val row = LinkedHashMap<String, String>()
            header.forEachIndexed { index, key ->
                if (key.isNotEmpty()) row[key] = cells.getOrNull(index)?.trim().orEmpty()
            }
            out += row
        }
        return out
    }

    /**
     * The separator the header uses: comma, semicolon or tab, whichever appears most outside quotes.
     *
     * A dataset exported in a spreadsheet configured for a comma-decimal locale uses semicolons, and
     * reading that as one comma-less column is how a CSV full of descriptions looks like it has no
     * text at all.
     */
    private fun csvDelimiter(headerLine: String): Char =
        listOf(',', ';', '\t').maxByOrNull { countUnquoted(headerLine, it) } ?: ','

    private fun countUnquoted(line: String, delimiter: Char): Int {
        var count = 0
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                quoted && ch == '"' && line.getOrNull(index + 1) == '"' -> index++
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> count++
            }
            index++
        }
        return count
    }

    /**
     * Records rather than lines: a quoted description can contain a comma or a newline, and both are
     * part of the sentence rather than a column or row boundary.
     */
    private fun readCsvRecords(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    row += cell.toString()
                    cell.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && text.getOrNull(index + 1) == '\n') index++
                    if (cell.isNotEmpty() || row.isNotEmpty()) {
                        row += cell.toString()
                        cell.setLength(0)
                        rows += row
                        row = mutableListOf()
                    }
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows.filter { cells -> cells.any { it.isNotBlank() } }
    }

    private val VIDEO_EXTENSIONS = listOf("mp4", "mov", "mkv", "webm", "avi")

    private object Util {
        fun stamp(): String = System.currentTimeMillis().toString(36)
    }
}
