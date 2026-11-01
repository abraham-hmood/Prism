package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.datasetcraft.DatasetReferences
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.LanguageId
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The on-device text-to-speech and voice-cloning trainer.
 *
 * ## What it builds
 *
 * Training produces two things, and they are not the same thing:
 *
 *  1. **The voice.** Every clip is measured, and the cleanest recording per language and gender is
 *     written into the model as its voice (see [Trained.voiceClips]). Speech is then rendered by
 *     [NeuralVoice] -- Pocket TTS, a pretrained neural text-to-speech model conditioned on that
 *     recording. This is the part that says new sentences, and it is what [synthesize] returns.
 *  2. **A compact acoustic pipeline**, described below. It is fitted to the dataset for diagnosis
 *     and for the miniature renderer, but it is not asked to say novel text. Its teacher-forced
 *     frames reconstruct a training clip almost exactly; its free-running frames of a new sentence
 *     babble, because a few minutes of audio and forty thousand parameters do not contain a
 *     language. [synthesizeAcoustic] is that path, kept so the two can be compared.
 *
 * The pipeline's stages are all real:
 *
 *  1. **Text encoder** -- phonemes from [PrismPhonemes] are embedded, tagged with the utterance's
 *     language and gender, and mixed by two self-attention layers. The layers obey the recipe's
 *     sliding-window settings: a sliding layer sees a window of tokens around each one, a full layer
 *     sees the whole phrase, and `full_attention_per_nth_layer` decides which is which. The same
 *     masks are used here in training and in [synthesize], so a model trained with a window is a
 *     model that generates with it.
 *  2. **Phoneme aligner** -- a monotonic alignment that gives every phoneme a run of frames. The
 *     frames per phoneme come from the clip's own duration: sonorants and vowels get more, stops
 *     get fewer, pauses get the most. It is a heuristic because this build has no forced aligner,
 *     and the artifact's metadata says so rather than implying one.
 *  3. **Duration model** -- a two-layer head over the encoder's states, trained on the alignment's
 *     log frame counts. At synthesis it predicts how long each phoneme lasts; summing those is the
 *     utterance's frame count, so a longer sentence really does take longer.
 *  4. **Acoustic decoder** -- predicts one mel frame from the aligned phoneme state, the previous
 *     three mel frames, the speaker embedding, the language and the gender. It is trained with
 *     teacher forcing and generates autoregressively, which is what makes prosody carry across
 *     frames instead of every frame being an independent guess.
 *  5. **Codec** -- [CoderTrainer]'s bottleneck autoencoder over the mel frames, plus a k-means
 *     codebook over its codes. Generation passes through it, so the decoder predicts acoustics, the
 *     codec quantises them, and the vocoder receives the quantised frames -- the same round trip a
 *     streaming speech codec makes. Its reconstruction loss is in the metadata.
 *  6. **Vocoder** -- [TtsSignal.vocode]: mel inversion plus Griffin-Lim phase recovery. Not a
 *     neural vocoder, and named as such.
 *  7. **Speaker encoder** -- [TtsSignal.speakerVector]: twenty statistics of a reference recording.
 *     The same function conditions every training clip and every clone, so cloning a new voice is
 *     supplying a recording rather than retraining the model.
 *
 * ## Text, language and gender as training targets
 *
 * The transcript is the input, and the alignment, the language and the gender are trained beside it:
 * language and gender are embedded and fed to the decoder, so a voice bank trained on several
 * languages and both genders learns which acoustics belong to which. The raw waveform is read for the
 * mel targets and the speaker vector -- the model is trained on the signal, not on pre-extracted
 * features alone.
 *
 * ## What it will not do
 *
 * It will not invent a voice that is not in the dataset: the voice IS a recording from it, which is
 * why a dataset with no audio has no speech model. It will not clone from text alone either -- a
 * cloning build conditions the same engine on the reference recording the user supplied. And it
 * will not sound like the compact network's own mel frames; those are the babble this design
 * replaced, not a quieter version of it.
 */
object TtsTrainer {

    /** The metadata artifact: architecture, normalisation, codebook and what the run measured. */
    const val METADATA_FILE = "tts_model.json"

    /** The network's weights, in [TrainerKit.WeightFile]'s container. */
    const val WEIGHTS_FILE = "tts_model.weights"

    /** The codec's weights, written only when there were frames to learn a codec from. */
    const val CODEC_FILE = "tts_codec.weights"

    /** Magic for the network and for the codec; each refuses the other's file. */
    private const val MAGIC = "PRISM-TTS-1"
    private const val CODEC_MAGIC = "PRISM-TTS-CODEC-1"

    /** The width of the encoder and of every embedding it adds. */
    const val D_MODEL = 32

    /** Encoder layers. Two is enough for one sliding and one full layer to differ. */
    const val ENCODER_LAYERS = 2

    /** Width of the frame decoder's hidden layer. */
    const val FRAME_HIDDEN = 64

    /** Width of the duration head's hidden layer. */
    const val DURATION_HIDDEN = 24

    /** How many previous mel frames the decoder sees. */
    const val FRAME_CONTEXT = 3

    /** How many codes the codec's codebook holds. */
    const val CODEBOOK_SIZE = 32

    /** Width of a codec code. */
    const val CODE_WIDTH = 16

    /** Phonemes in the longest utterance this trainer will align. */
    const val MAX_TOKENS = 160

    /**
     * Frames in the longest clip it will train on or generate.
     *
     * Six seconds at a 10 ms hop. The archive's clips run to 4.2 s, and the old 400-frame cap cut
     * the last fifth of a second off the longest ones -- a small loss per clip that becomes a
     * truncated final phoneme in every training example. Generation is capped by the same number so
     * a long prompt still ends in bounded time.
     */
    const val MAX_FRAMES = 600

    /** How many frames a single phoneme may be stretched to at synthesis. */
    private const val MAX_PHONEME_FRAMES = 40

    /**
     * A ceiling on the second training pass. The acoustic model has its own schedule.
     *
     * The form allows thousands of steps; this cap is what the on-device loop can finish in a
     * reasonable time. It is deliberately above the previous 600 so a recipe that asks for 1,000
     * steps is not silently cut in half. The dataset's size, not this number, is what stops the
     * loss moving long before it.
     */
    private const val MAX_STEPS = 2_000

    /** Language slots the embedding covers. Slot 0 means "not one of these". */
    val LANGUAGES: List<String> = listOf(
        "", "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi", "pl", "cs", "sk",
        "hu", "ro", "ru", "uk", "tr", "ar", "he", "hi", "bn", "ta", "ja", "ko", "zh", "th", "vi",
        "id", "ms",
    )

    /** Gender slots: neutral first, so an unknown label is not forced into a binary choice. */
    val GENDERS: List<String> = listOf("neutral", "female", "male")

    private const val POSITION_DIM = 8
    private const val FRAME_INPUT = D_MODEL * 6 + FRAME_CONTEXT * TtsSignal.BANDS + POSITION_DIM + 1

    private val VOWELS = setOf(
        "a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o",
        "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr", "aʊər", "jʊr",
    )

    /** Everything the recipe says about the run, read from the recipe file itself. */
    data class Config(
        val sampleRate: Int,
        val window: Int,
        val fullEvery: Int,
        val epochs: Int,
        val stepsPerEpoch: Int,
        val learningRate: Double,
        val gradientClip: Double,
        val seed: Long,
        val defaultLanguage: String,
        val defaultGender: String,
        val voiceCount: Int,
        val task: String,
    ) {
        val signal: TtsSignal.Config get() = TtsSignal.Config(sampleRate)
    }

    /**
     * Trains a text-to-speech or cloning model from the audio and transcripts in [directory].
     *
     * Returns null when there is no usable pair of transcript and audio: a model without text is a
     * prosody model, which the caller already has.
     */
    fun train(model: CraftedModel, recipe: File): Trained? {
        if (model.kind != ModelCraft.Kind.AUDIO) return null
        val directory = ModelCraft.Store.datasetDir(model.id)
        val config = readConfig(recipe, model)
        val clips = loadClips(directory, config)
        if (clips.size < 2) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "TTS training needs at least two clips with transcripts; ${directory.name} has ${clips.size}",
            )
            return null
        }

        val mel = Normalisation.of(TtsSignal.BANDS)
        mel.fit(clips.flatMap { it.mels.take(MAX_FRAMES) })
        val speaker = Normalisation.of(TtsSignal.SPEAKER_DIMENSIONS)
        speaker.fit(clips.map { it.speaker })
        val net = Net(config, Random(config.seed))
        val adam = TrainerKit.Adam(config.learningRate)
        val random = Random(config.seed xor 0x7A11)

        val validation = clips.filterIndexed { index, _ -> index % 4 == 3 }.ifEmpty { listOf(clips.last()) }
        val training = clips.filterNot { it in validation }
        val perEpoch = if (config.stepsPerEpoch > 0) config.stepsPerEpoch else training.size
        var steps = 0
        var first = Double.NaN
        var last = Double.NaN
        var epoch = 0
        var displayEpoch = 0
        while (epoch < config.epochs && steps < MAX_STEPS) {
            epoch++
            repeat(perEpoch) { offset ->
                if (steps >= MAX_STEPS) return@repeat
                val clip = training[offset % training.size]
                val loss = net.trainClip(
                    clip,
                    mel,
                    speaker,
                    adam,
                    config.gradientClip,
                    random,
                    // Scheduled sampling: one frame in five is fed back from the model's own
                    // prediction instead of the ground-truth target. Without it the decoder only
                    // ever sees perfect history during training, and at generation a single early
                    // mistake becomes the context for every later frame.
                    // Ramp from pure teacher forcing to a quarter generated history. Starting at
                    // a high rate feeds the model its own untrained noise on the first steps and
                    // teaches it to repeat that noise; the ramp keeps the early pass stable and
                    // only asks it to recover once it can already predict a frame.
                    contextSampling = (steps.toDouble() / MAX_STEPS * 0.25).coerceIn(0.0, 0.25),
                )
                if (first.isNaN()) first = loss
                last = loss
                steps++
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = epoch,
                        epochs = config.epochs,
                        step = offset + 1,
                        steps = perEpoch,
                        file = clip.name,
                        loss = loss,
                        accuracy = net.lastTrainAccuracy,
                    ),
                )
            }
            if (steps >= MAX_STEPS) {
                PrismPlatform.log.info(
                    "ModelCraft",
                    "TTS pass stopped at its $MAX_STEPS-step ceiling (${config.epochs} epochs were asked for)",
                )
            }
            displayEpoch = epoch
            PrismPlatform.log.info(
                "ModelCraft",
                "TTS epoch $epoch: ${"%.4f".format(last)} loss",
            )
        }
        if (steps == 0) return null
        if (!last.isFinite()) {
            // A non-finite loss is a numerical failure in this trainer, not a missing transcript:
            // the clips and their words were already loaded or the loop would never have run. Say
            // so, because the test page's "no model was saved" line must not be read as "the CSV has
            // no words" when it does.
            PrismPlatform.log.warn(
                "ModelCraft",
                "TTS training diverged for ${model.id}: loss ${last} after $steps step(s), so no " +
                    "speech model was saved. The clips did have transcripts; the failure is in the " +
                    "feature or gradient values, not in the CSV.",
            )
            return null
        }

        // The duration head is fitted after the encoder, against the alignment the clips carry. A
        // frozen encoder is what makes this a regression rather than a second unstable joint loss.
        net.fitDurations(training, mel, speaker, config)
        val durationLoss = net.durationLoss(training, mel, speaker)

        // THE CODEC LIVES IN THE SAME SPACE AS THE DECODER. The acoustic decoder predicts
        // normalised mel frames, so a codec trained on raw log-mel values is a codec that encodes
        // a different distribution from the frames it is handed at synthesis. That mismatch does
        // not show up in either loss: the decoder is scored against normalised targets and the
        // codec against raw ones, and only generation crosses between them. The old build did
        // exactly that, and the resulting spectrogram was scaled and shaped wrongly before the
        // vocoder ever saw it. Fit the codec and the codebook on `mel.normalise(frame)` instead,
        // and write the space into the metadata so a loader can refuse an older, raw-space pair.
        val examples = clips
            .flatMap { clip -> clip.mels.take(MAX_FRAMES).map { mel.normalise(it) } }
            .take(4_000)
        val coder = CoderTrainer.train(
            vectors = examples,
            code = CODE_WIDTH,
            seed = config.seed xor 0xC0DEC,
            epochs = config.epochs.coerceIn(1, 8),
            stepsPerEpoch = min(config.stepsPerEpoch.takeIf { it > 0 } ?: examples.size, 1_200),
            learningRate = config.learningRate,
            gradientClip = config.gradientClip,
            label = "speech codec",
        )
        val codebook = if (coder == null) emptyArray() else {
            kmeans(examples.map { coder.encode(it) }, CODEBOOK_SIZE, 12, Random(config.seed xor 0xB00C))
        }

        // THE PACK WINS. The voices dialog runs before this trainer and writes the recordings, the
        // speaker names, the genders and the languages the user confirmed. Computing the clip map
        // only from the dataset would write the importer's filenames back over those answers, so
        // the trained model would name a different take from the one the engine it speaks through
        // was built from. When there is no pack -- an older model, or a run started without the
        // dialog -- the dataset's own choice is still the right answer.
        val packaged = runCatching { VoicePack.packaged(model) }.getOrDefault(emptyList())
            .filter { voice -> File(VoicePack.directory(model), voice.file).isFile }
        val packagedClips = packaged.takeIf { it.isNotEmpty() }?.let { VoicePack.clipMap(it) }

        val trained = Trained(
            net = net,
            config = config,
            directory = directory,
            mel = mel,
            speaker = speaker,
            codec = coder,
            codebook = codebook,
            modelId = model.id,
            name = model.name,
            task = ModelCraft.audioType(model).recipeValue,
            clips = clips,
            firstLoss = first,
            lastLoss = last,
            durationLoss = durationLoss,
            steps = steps,
            epochs = displayEpoch,
            requestedSteps = if (config.stepsPerEpoch > 0) config.epochs * config.stepsPerEpoch else 0,
            validationLoss = validation.map { clip ->
                net.clipLoss(clip, mel, speaker)
            }.average(),
            voiceClipsOverride = packagedClips,
            builtInEngineOverride = ModelCraft.usesBuiltInVoiceEngine(model),
            allowPocketOverride = ModelCraft.allowsPocketFallback(model),
        )
        val files = trained.writeArtifacts(directory, model)
        PrismPlatform.log.info(
            "ModelCraft",
            "TTS model for ${model.id}: ${net.parameterCount()} parameters, $steps step(s), " +
                "codec ${coder?.parameters()?.size ?: 0} tensors, ${files.size} file(s)",
        )
        val voice = trained.voiceClip(config.defaultLanguage, config.defaultGender)
        PrismPlatform.log.info(
            "ModelCraft",
            "TTS voice for ${model.id}: ${voice?.name ?: "none"} (" +
                "${trained.voiceClips.size} recording(s) across the dataset), rendered by " +
                if (trained.useBuiltInEngine) DatasetVoiceEngine.SYNTHESIZER else "Pocket TTS",
        )
        // THE VOICE THE MODEL WILL ACTUALLY USE. Training the acoustic network measures the
        // dataset, but the thing that says a new sentence is the neural voice cut from the same
        // recordings. Building it here means a model trained without opening the Voices dialog
        // still has its own engine instead of silently borrowing a pretrained one.
        if (ModelCraft.usesBuiltInVoiceEngine(model) && !ModelCraft.usesCustomVoice(model)) {
            val existing = PrismNeuralVoice.load(directory)
            if (existing == null) {
                val neural = runCatching { PrismNeuralVoice.train(directory, ModelCraft.neuralVoiceSettings(model)) }
                    .onFailure {
                        PrismPlatform.log.warn(
                            "ModelCraft",
                            "Neural voice could not be trained: ${it.message}",
                        )
                    }
                    .getOrNull()
                if (neural != null) {
                    PrismPlatform.log.info("ModelCraft", "Neural voice: ${PrismNeuralVoice.summary(neural)}")
                }
            } else {
                PrismPlatform.log.info("ModelCraft", "Neural voice already present: ${PrismNeuralVoice.summary(existing)}")
            }
        }
        NeuralVoice.installHint()?.let { hint -> PrismPlatform.log.warn("ModelCraft", hint) }
        return trained
    }

    /** A summary line ModelCraft appends to the audio run's status. */
    fun summary(trained: Trained): String = buildString {
        append("${ModelCapacity.count(trained.net.parameterCount().toLong())} parameter text-to-speech")
        append(" · text encoder ${ENCODER_LAYERS} layers, ${trained.config.window} token window")
        append(" · duration model ${"%.3f".format(trained.durationLoss)} log-frame loss")
        append(" · ${trained.config.task}")
        append(" · codec ${trained.codec?.code ?: 0}×${CODEBOOK_SIZE} codebook")
        append(" · voice ${trained.voiceClips["default"] ?: "none"} via ")
        append(if (trained.useBuiltInEngine) "the dataset voice engine" else "Pocket TTS")
        append(" · ${trained.steps} step(s)")
    }

    // ── The dataset ─────────────────────────────────────────────────────────────────────────────

    /** A clip and the words it speaks, from whichever CSV or sidecar names them. */
    class SpokenClip(
        val file: File,
        val transcript: String,
        val language: String,
        val gender: String,
        val pitchHz: Double?,
    )

    /**
     * Every audio file in [directory] that has a transcript, with the language and gender its row
     * declares. The neural voice builder needs the words but not the mel frames, so it reads them
     * through this rather than duplicating the CSV-column rules above.
     */
    fun spokenClips(directory: File, defaultLanguage: String = "en"): List<SpokenClip> {
        val rows = transcripts(directory)
        val audio = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SpeechDataset.AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        return audio.mapNotNull { file ->
            val row = rows[file.name.lowercase()]
            val transcript = row?.transcript.orEmpty().ifBlank {
                File(directory, file.nameWithoutExtension + ".txt").takeIf { it.isFile }
                    ?.readText().orEmpty()
            }.trim()
            if (transcript.isBlank()) return@mapNotNull null
            val language = row?.language?.takeIf { it.isNotBlank() }
                ?: languageOf(transcript, defaultLanguage)
            val declared = row?.gender?.takeIf {
                it.isNotBlank() && !it.equals("neutral", true)
            }
            val gender = declared ?: SpeakerGender.label(
                row?.pitchHz,
                row?.formant1Hz,
                row?.formant2Hz,
                row?.formant3Hz,
            )
            SpokenClip(file, transcript, language, gender, row?.pitchHz)
        }
    }

    /** One clip's tokens, alignment, mel frames and speaker vector. */
    class ClipData(
        val name: String,
        val symbols: List<String>,
        val tokens: IntArray,
        val durations: IntArray,
        val mels: Array<DoubleArray>,
        val speaker: DoubleArray,
        val language: String,
        val gender: String,
        /** Energy above ~4 kHz as a fraction of the clip's total mel energy, from the clip itself. */
        val clarity: Double = 0.0,
    ) {
        val frames: Int get() = durations.sum()
    }

    private fun loadClips(directory: File, config: Config): List<ClipData> {
        val rows = transcripts(directory)
        val audio = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SpeechDataset.AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        val clips = mutableListOf<ClipData>()
        for (file in audio) {
            val decoded = AudioDecoding.read(file) ?: continue
            if (decoded.samples.isEmpty()) continue
            val row = rows[file.name.lowercase()]
            val transcript = row?.transcript.orEmpty().ifBlank {
                File(directory, file.nameWithoutExtension + ".txt").takeIf { it.isFile }
                    ?.readText().orEmpty()
            }
            if (transcript.isBlank()) continue
            val signal = config.signal
            // The voice is measured before the row is labelled, because a dataset without a gender
            // column still has a measurable pitch. The old fallback labelled every unlabelled clip
            // with the recipe's default, which turned a twenty-voice archive into twenty "female"
            // clips and made the model's default voice an average of all of them.
            val measuredSpeaker = TtsSignal.speakerVector(decoded.samples, signal)
            val measuredPitch = 120.0 * Math.pow(2.0, measuredSpeaker[0])
            val language = row?.language?.takeIf { it.isNotBlank() } ?: languageOf(transcript, config.defaultLanguage)
            val declared = row?.gender?.takeIf {
                it.isNotBlank() && !it.equals("neutral", true)
            }
            // Keep "neutral" when the measurements do not separate the voice: turning every
            // uncertain clip into the recipe's default is how a mixed archive became all-female.
            val gender = declared ?: SpeakerGender.label(
                row?.pitchHz ?: measuredPitch,
                row?.formant1Hz,
                row?.formant2Hz,
                row?.formant3Hz,
            )
            val phonemes = PrismPhonemes.phonemize(transcript, language).take(MAX_TOKENS)
            if (phonemes.size < 2) continue
            val frames = TtsSignal.analyse(decoded.samples, signal)
            val trimmed = trimSilence(frames).take(MAX_FRAMES)
            if (trimmed.size < 8) continue
            val durations = align(phonemes, trimmed.size)
            val symbols = phonemes.take(durations.size)
            val tokens = IntArray(symbols.size) { PrismPhonemes.idOf(symbols[it]) }
            val totalEnergy = trimmed.sumOf { frame ->
                frame.logMel.sumOf { exp(it) }
            }.coerceAtLeast(1e-9)
            val highEnergy = trimmed.sumOf { frame ->
                (30 until TtsSignal.BANDS).sumOf { exp(frame.logMel[it]) }
            }
            clips += ClipData(
                name = file.name,
                symbols = symbols,
                tokens = tokens,
                durations = durations,
                mels = Array(trimmed.size) { trimmed[it].logMel },
                speaker = measuredSpeaker,
                language = language,
                gender = gender,
                clarity = (highEnergy / totalEnergy).coerceIn(0.0, 1.0),
            )
        }
        return clips
    }

    /** A row's transcript, language, gender and pitch, from whichever CSV describes it. */
    private class Row(
        val transcript: String,
        val language: String,
        val gender: String,
        val pitchHz: Double?,
        val formant1Hz: Double? = null,
        val formant2Hz: Double? = null,
        val formant3Hz: Double? = null,
    )

    /**
     * The headers a speech dataset uses for the words spoken in a clip.
     *
     * `description` is here because it is what published datasets (and this app's own DatasetCraft
     * exports) commonly call the transcript; treating the exact word "transcript" as the only
     * possible spelling is how a CSV full of sentences trained with no transcripts at all.
     */
    private val TEXT_COLUMNS = listOf(
        "transcript", "text", "normalized_text", "sentence", "transcription", "words",
        "description", "utterance", "utterance_text", "transcript_text", "text_normalized",
        "sentence_normalized", "spoken_text", "script", "phrase", "label", "caption",
    )

    /**
     * Headers that are never the transcript, for the fallback in [textColumns].
     *
     * The merged CSV carries sixty numeric vocal measurements beside the dataset's own columns, and
     * the loudest-looking one must not beat a sentence because it is longer.
     */
    private val NOT_TEXT_COLUMNS = listOf(
        "path", "file", "audio", "clip", "sample", "id", "speaker", "voice", "name", "gender",
        "sex", "language", "lang", "locale", "duration", "seconds", "rate", "f0", "hnr",
        "jitter", "shimmer", "formant", "spectral", "ltas", "pitch", "breath", "vocal",
        "speaking", "mdd", "confidence",
    )
    private val LANGUAGE_COLUMNS = listOf("language", "language_detected", "language_code", "lang", "locale")
    private val GENDER_COLUMNS = listOf("gender", "sex", "voice_gender", "speaker_gender")
    private val PITCH_COLUMNS = listOf("f0_median_hz", "f0_mean_hz", "pitch_hz", "median_pitch_hz")
    private val FORMANT_COLUMNS = listOf("formant_1_hz", "formant_2_hz", "formant_3_hz")

    private fun transcripts(directory: File): Map<String, Row> {
        // EVERY CSV IS A CANDIDATE, and the one used is the one that actually holds words. The
        // merged file is preferred on a tie because it carries the vocal measurements, but an old
        // merged file whose transcript column is blank must not hide a dataset CSV beside it that
        // has the text -- which is exactly what "none of its clips had a transcript" used to mean.
        val candidates = buildList {
            File(directory, SpeechDataset.MERGED_FILE).takeIf { it.isFile }?.let { add(it) }
            directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("csv", true) && it.name != SpeechDataset.MERGED_FILE }
                ?.sortedBy { it.name }
                ?.let { addAll(it) }
        }
        var rows: List<Map<String, String>> = emptyList()
        var textColumns: List<String> = emptyList()
        var labelled = 0
        for (file in candidates) {
            val parsed = ModelCraft.parseCsv(file)
            if (parsed.isEmpty()) continue
            val columns = textColumns(parsed)
            if (columns.isEmpty()) continue
            // A FILE THAT HOLDS NO WORDS IS NOT THE METADATA FILE, even when its header says
            // "transcript". Counting the non-blank rows is what distinguishes a manifest with a
            // sentence per clip from an empty column a merge left behind.
            val count = parsed.count { row ->
                columns.any { column -> !row[column].isNullOrBlank() }
            }
            if (count > labelled) {
                labelled = count
                rows = parsed
                textColumns = columns
            }
        }
        // No CSV has text: the first one is still the metadata (language, gender, pitch), because a
        // dataset of clips with a .txt beside each can have a CSV that carries only those columns.
        if (rows.isEmpty()) {
            rows = candidates.firstOrNull()?.let { ModelCraft.parseCsv(it) }.orEmpty()
        }
        if (rows.isEmpty()) return emptyMap()
        val byName = HashMap<String, Row>()
        for (row in rows) {
            val path = row.values.firstOrNull { value ->
                value.substringBefore('?').substringAfterLast('.').lowercase() in SpeechDataset.AUDIO_EXTENSIONS
            } ?: continue
            val key = path.substringBefore('?').trim().replace('\\', '/').substringAfterLast('/').lowercase()
            if (key.isEmpty()) continue
            fun first(columns: List<String>): String =
                columns.firstNotNullOfOrNull { column -> row.get(column)?.takeIf { it.isNotBlank() } }.orEmpty()
            val pitch = PITCH_COLUMNS.firstNotNullOfOrNull { column -> row.get(column)?.toDoubleOrNull() }
            val formants = FORMANT_COLUMNS.map { column -> row.get(column)?.toDoubleOrNull() }
            // THE WORDS ARE WHICHEVER TEXT COLUMN HAS THEM ON THIS ROW. A dataset can carry both a
            // transcript column and a description column, and they are not always filled in the
            // same rows; taking the first header regardless is how a filled description was thrown
            // away for an empty transcript.
            val transcript = textColumns.firstNotNullOfOrNull { column ->
                row[column]?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            byName[key] = Row(
                transcript = transcript,
                language = normaliseLanguage(first(LANGUAGE_COLUMNS)),
                // "M" and "man" mean male; filing them under neutral because the CSV did not
                // spell "male" made the voice picker's gender filter useless.
                gender = VoicePack.normaliseGender(first(GENDER_COLUMNS)).orEmpty(),
                pitchHz = pitch,
                formant1Hz = formants.getOrNull(0),
                formant2Hz = formants.getOrNull(1),
                formant3Hz = formants.getOrNull(2),
            )
        }
        return byName
    }

    /**
     * The columns that can hold what was said, most specific first.
     *
     * Known headers lead, in [TEXT_COLUMNS] order, so `transcript` still beats a generic `caption`.
     * They are followed by columns that read as prose, which is how a dataset that names its words
     * something else entirely is still trainable. A column is not dropped for being empty: a row
     * chooses the first of these that has a value, so an empty transcript beside a filled
     * description yields the description.
     */
    private fun textColumns(rows: List<Map<String, String>>): List<String> {
        val headers = rows.firstOrNull()?.keys?.toList().orEmpty()
        if (headers.isEmpty()) return emptyList()
        val known = TEXT_COLUMNS.mapNotNull { name ->
            headers.firstOrNull { header -> header.equals(name, ignoreCase = true) }
        }.distinct()
        val prose = headers
            .filter { header -> known.none { it.equals(header, ignoreCase = true) } }
            .filterNot { header -> NOT_TEXT_COLUMNS.any { token -> header.contains(token) } }
            .filter { header -> rows.any { row -> row[header]?.let(::readsAsProse) == true } }
        return known + prose
    }

    /** True for a cell that reads like a sentence rather than a number, a path or a label. */
    private fun readsAsProse(value: String): Boolean {
        if (value.isBlank() || DatasetReferences.looksLikeReference(value)) return false
        val letters = value.count { it.isLetter() }
        val digits = value.count { it.isDigit() }
        if (letters < 3 || letters < digits) return false
        // A transcript without a space ("hello") is possible but a metadata value such as
        // "female" or "Altair" is far more common, so short single tokens are not accepted unless
        // the column was named as text above.
        return value.contains(' ') || value.length >= 12
    }

    private fun normaliseLanguage(raw: String): String {
        val code = raw.trim().lowercase().substringBefore('-').substringBefore('_')
        // Blank stays blank: the caller then detects the language from the transcript. Returning
        // "en" here made every unlabelled row English before detection had a chance to run.
        return code
    }

    /**
     * The language of a transcript when the CSV does not name one.
     *
     * A missing language column is not a reason to call every sentence English. The rule set in
     * [PrismPhonemes] uses different letter-to-sound paths for English and everything else, so a
     * Spanish or French clip trained as English teaches the model the wrong mapping for its own
     * words. [LanguageId] scores the text's stop words first; the language-name hints cover the
     * short sample sentences that contain no stop word at all. A sentence it cannot place falls
     * back to the recipe's language rather than inventing a code.
     */
    private fun languageOf(text: String, fallback: String): String {
        val lower = text.lowercase()
        val hinted = LANGUAGE_HINTS.firstOrNull { (_, hints) -> hints.any { lower.contains(it) } }?.first
        if (hinted != null) return hinted
        val detected = LanguageId.guessFromText(text)
        return if (detected.code.isNotBlank() && detected.confidence >= 0.34) detected.code else fallback
    }

    /**
     * Language names that appear in a sentence about its own audio. Sample corpora commonly say
     * "in Spanish" or "français" in the prompt, and that is the only unambiguous language signal in
     * a one-line clip; ordinary prose simply has no hit and goes to [LanguageId] instead.
     */
    private val LANGUAGE_HINTS: List<Pair<String, List<String>>> = listOf(
        "en" to listOf("english", "anglais", "inglés", "inglese", "englisch"),
        "es" to listOf("español", "espanol", "spanish", "castellano"),
        "fr" to listOf("français", "francais", "french", "francophone"),
        "de" to listOf("deutsch", "german", "allemand", "alemán", "aleman"),
        "it" to listOf("italiano", "italian", "italien", "italienisch"),
        "pt" to listOf("português", "portugues", "portuguese", "portugais"),
        "ja" to listOf("japanese", "japonais", "japonés", "japones", "日本語"),
        "ko" to listOf("korean", "coréen", "coreano", "한국어"),
        "zh" to listOf("chinese", "mandarin", "chinois", "chino", "中文", "普通话"),
        "ru" to listOf("russian", "russe", "ruso", "русский"),
        "ar" to listOf("arabic", "arabe", "árabe", "arabisch", "العربية"),
        "hi" to listOf("hindi", "हिन्दी", "हिंदी"),
    )

    /** Kept for callers that only have a pitch; the formant-aware estimate is in [SpeakerGender]. */
    private fun genderFrom(pitchHz: Double?, fallback: String): String =
        SpeakerGender.label(pitchHz).takeIf { it != "neutral" } ?: fallback

    /** Drops the silent head and tail of a clip, keeping at least a tenth of it. */
    private fun trimSilence(frames: List<TtsSignal.Frame>): List<TtsSignal.Frame> {
        if (frames.isEmpty()) return frames
        val peak = frames.maxOf { it.rms }
        if (peak <= 1e-6) return frames
        val threshold = peak * 0.04
        val first = frames.indexOfFirst { it.rms >= threshold }.coerceAtLeast(0)
        val last = frames.indexOfLast { it.rms >= threshold }.coerceAtLeast(first)
        val start = max(0, first - 2)
        val end = min(frames.size, last + 3)
        return if (end - start < frames.size / 10) frames else frames.subList(start, end)
    }

    /**
     * The aligner. Every phoneme gets at least one frame, and the rest are handed out in proportion
     * to how much time that class of sound occupies: a vowel or a nasal lasts, a stop is short, a
     * pause is longest. The total is exactly [frames].
     */
    fun align(symbols: List<String>, frames: Int): IntArray {
        if (symbols.isEmpty()) return IntArray(0)
        val usable = min(frames, MAX_FRAMES)
        val count = min(symbols.size, usable)
        val weights = DoubleArray(count) { weightOf(symbols[it]) }
        val total = weights.sum().coerceAtLeast(1e-6)
        val durations = IntArray(count) { index ->
            max(1, (weights[index] / total * usable).roundToInt())
        }
        var sum = durations.sum()
        // Give the surplus to the longest phoneme, take the deficit from the longest that can spare it.
        while (sum < usable) {
            val index = durations.indices.maxByOrNull { durations[it] } ?: break
            durations[index]++
            sum++
        }
        while (sum > usable) {
            val index = durations.indices.filter { durations[it] > 1 }.maxByOrNull { durations[it] } ?: break
            durations[index]--
            sum--
        }
        return durations
    }

    private fun weightOf(symbol: String): Double = when {
        symbol == PrismPhonemes.SPACE -> 2.2
        symbol == PrismPhonemes.STRESS -> 0.4
        symbol in setOf(".", "?", "!", ",", ";", ":") -> 3.0
        symbol in VOWELS -> 2.4
        symbol in setOf("m", "n", "ŋ", "l", "r", "ɹ", "w", "j", "v", "z", "ð", "ʒ") -> 1.6
        symbol in setOf("s", "ʃ", "f", "θ", "h") -> 1.2
        else -> 1.0
    }

    // ── Normalisation ───────────────────────────────────────────────────────────────────────────

    /** Per-dimension means and deviations, for mel frames or speaker vectors. */
    class Normalisation private constructor(val dimensions: Int) {
        val mean = DoubleArray(dimensions)
        val deviation = DoubleArray(dimensions) { 1.0 }

        companion object {
            fun of(dimensions: Int) = Normalisation(dimensions)
        }

        fun fit(vectors: List<DoubleArray>) {
            val usable = vectors.filter { it.size == dimensions }
            if (usable.isEmpty()) return
            for (index in 0 until dimensions) {
                val values = usable.map { it[index] }.filter { it.isFinite() }
                if (values.isEmpty()) continue
                val average = values.average()
                val variance = values.sumOf { (it - average) * (it - average) } / values.size
                mean[index] = average
                deviation[index] = sqrt(variance).takeIf { it > 1e-9 } ?: 1.0
            }
        }

        fun normalise(values: DoubleArray): DoubleArray =
            DoubleArray(dimensions) { index ->
                val scaled = (values.getOrElse(index) { mean[index] } - mean[index]) / deviation[index]
                // A NaN does not compare equal to anything, so coerceIn alone would let it through
                // and one unmeasurable feature would turn every weight into NaN. Treat it as the
                // mean instead: uninformative, but a value the network can learn from.
                if (scaled.isFinite()) scaled.coerceIn(-8.0, 8.0) else 0.0
            }

        fun denormalise(values: DoubleArray): DoubleArray =
            DoubleArray(dimensions) { index ->
                val raw = values.getOrElse(index) { 0.0 } * deviation[index] + mean[index]
                if (raw.isFinite()) raw else mean[index]
            }
    }

    // ── The network ─────────────────────────────────────────────────────────────────────────────

    /**
     * The network: a phoneme encoder, a duration head, and a frame decoder.
     *
     * It writes its own forward and backward passes rather than hiding them behind an autograd
     * engine, for the same reason every other trainer here does: the architecture is the code, and a
     * general engine would make this file read like every other model while behaving differently.
     */
    class Net(val config: Config, random: Random) {

        /** The last [trainClip]'s share of mel bands within a tenth of their target. */
        var lastTrainAccuracy = Double.NaN
            private set
        private val d = D_MODEL
        private val vocab = PrismPhonemes.SYMBOLS.size

        val embedding = TrainerKit.gaussian("text.embedding", vocab, d, random)
        val language = TrainerKit.gaussian("text.language", LANGUAGES.size, d, random)
        val gender = TrainerKit.gaussian("text.gender", GENDERS.size, d, random)

        val wq = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.query", d, d, random) }
        val wk = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.key", d, d, random) }
        val wv = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.value", d, d, random) }
        val wo = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.output", d, d, random) }
        val w1 = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.mlp.in", d * 2, d, random) }
        val b1 = Array(ENCODER_LAYERS) { TrainerKit.Param("encoder.$it.mlp.in.bias", d * 2, 1) }
        val w2 = Array(ENCODER_LAYERS) { TrainerKit.gaussian("encoder.$it.mlp.out", d, d * 2, random) }
        val b2 = Array(ENCODER_LAYERS) { TrainerKit.Param("encoder.$it.mlp.out.bias", d, 1) }

        val durationW1 = TrainerKit.gaussian("duration.in", DURATION_HIDDEN, d, random)
        val durationB1 = TrainerKit.Param("duration.in.bias", DURATION_HIDDEN, 1)
        val durationW2 = TrainerKit.gaussian("duration.out", 1, DURATION_HIDDEN, random)
        val durationB2 = TrainerKit.Param("duration.out.bias", 1, 1)

        val speakerW = TrainerKit.gaussian("speaker.projection", d, TtsSignal.SPEAKER_DIMENSIONS, random)
        val frameW1 = TrainerKit.gaussian("decoder.in", FRAME_HIDDEN, FRAME_INPUT, random)
        val frameB1 = TrainerKit.Param("decoder.in.bias", FRAME_HIDDEN, 1)
        val frameW2 = TrainerKit.gaussian("decoder.out", TtsSignal.BANDS, FRAME_HIDDEN, random)
        val frameB2 = TrainerKit.Param("decoder.out.bias", TtsSignal.BANDS, 1)

        init {
            for (param in arrayOf(durationB1, durationB2, frameB1, frameB2)) param.data.fill(0.0)
        }

        fun parameters(): List<TrainerKit.Param> = buildList {
            add(embedding); add(language); add(gender)
            for (layer in 0 until ENCODER_LAYERS) {
                add(wq[layer]); add(wk[layer]); add(wv[layer]); add(wo[layer])
                add(w1[layer]); add(b1[layer]); add(w2[layer]); add(b2[layer])
            }
            add(durationW1); add(durationB1); add(durationW2); add(durationB2)
            add(speakerW); add(frameW1); add(frameB1); add(frameW2); add(frameB2)
        }

        fun parameterCount(): Int = parameters().sumOf { it.data.size }

        /** True when [layer] attends over every token instead of its window. */
        fun isFull(layer: Int): Boolean =
            config.window <= 0 || (layer + 1) % config.fullEvery == 0

        fun zeroGradients() = parameters().forEach { it.grad.fill(0.0) }

        // ── Encoder ─────────────────────────────────────────────────────────────────────────────

        class Encoded(
            val tokens: IntArray,
            val languageIndex: Int,
            val genderIndex: Int,
            val layerInput: List<Array<DoubleArray>>,
            val queries: List<Array<DoubleArray>>,
            val keys: List<Array<DoubleArray>>,
            val values: List<Array<DoubleArray>>,
            val weights: List<Array<DoubleArray>>,
            val attended: List<Array<DoubleArray>>,
            val mlpInput: List<Array<DoubleArray>>,
            val mlpHidden: List<Array<DoubleArray>>,
            val masks: List<BooleanArray>,
            val states: Array<DoubleArray>,
        )

        private fun position(index: Int, dimensions: Int = POSITION_DIM): DoubleArray =
            DoubleArray(dimensions) { k ->
                val frequency = 1.0 / Math.pow(10_000.0, k.toDouble() / dimensions)
                if (k % 2 == 0) sin(index * frequency) else cos(index * frequency)
            }

        fun languageIndex(code: String): Int {
            val normalised = code.trim().lowercase().substringBefore('-').substringBefore('_')
            return LANGUAGES.indexOf(normalised).takeIf { it > 0 } ?: 0
        }

        fun genderIndex(label: String): Int {
            val normalised = label.trim().lowercase()
            return GENDERS.indexOf(normalised).takeIf { it >= 0 } ?: 0
        }

        private fun maskFor(layer: Int, length: Int): BooleanArray {
            val mask = BooleanArray(length * length)
            if (isFull(layer)) {
                java.util.Arrays.fill(mask, true)
                return mask
            }
            val window = config.window.coerceAtLeast(1)
            val left = window / 2
            val right = window - left - 1
            for (index in 0 until length) {
                val from = max(0, index - left)
                val to = min(length - 1, index + right)
                for (other in from..to) mask[index * length + other] = true
            }
            return mask
        }

        fun encode(tokens: IntArray, languageIndex: Int, genderIndex: Int): Encoded {
            val length = tokens.size
            val layerInput = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val queries = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val keys = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val values = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val weightsList = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val attended = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val mlpInput = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val mlpHidden = ArrayList<Array<DoubleArray>>(ENCODER_LAYERS)
            val masks = ArrayList<BooleanArray>(ENCODER_LAYERS)

            var h = Array(length) { index ->
                DoubleArray(d) { dim ->
                    embedding.at(tokens[index], dim) +
                        language.at(languageIndex, dim) * 0.5 +
                        gender.at(genderIndex, dim) * 0.5 +
                        position(index, d)[dim]
                }
            }
            for (layer in 0 until ENCODER_LAYERS) {
                layerInput += h
                val q = Array(length) { index -> TrainerKit.linear(h[index], wq[layer], null) }
                val k = Array(length) { index -> TrainerKit.linear(h[index], wk[layer], null) }
                val v = Array(length) { index -> TrainerKit.linear(h[index], wv[layer], null) }
                queries += q
                keys += k
                values += v
                val mask = maskFor(layer, length)
                masks += mask
                val rows = Array(length) { index -> softmax(q[index], k, mask, index, length) }
                weightsList += rows
                val mixed = Array(length) { index ->
                    val result = DoubleArray(d)
                    for (other in 0 until length) {
                        val weight = rows[index][other]
                        if (weight == 0.0) continue
                        for (dim in 0 until d) result[dim] += weight * v[other][dim]
                    }
                    result
                }
                attended += mixed
                val projected = Array(length) { index -> TrainerKit.linear(mixed[index], wo[layer], null) }
                h = Array(length) { index -> DoubleArray(d) { dim -> h[index][dim] + projected[index][dim] } }

                mlpInput += h
                val activation = Array(length) { index ->
                    val pre = TrainerKit.linear(h[index], w1[layer], b1[layer])
                    DoubleArray(d * 2) { dim -> kotlin.math.tanh(pre[dim]) }
                }
                mlpHidden += activation
                val output = Array(length) { index -> TrainerKit.linear(activation[index], w2[layer], b2[layer]) }
                h = Array(length) { index -> DoubleArray(d) { dim -> h[index][dim] + output[index][dim] } }
            }
            return Encoded(
                tokens, languageIndex, genderIndex, layerInput, queries, keys, values,
                weightsList, attended, mlpInput, mlpHidden, masks, h,
            )
        }

        /** Softmax over the allowed keys of one position. */
        private fun softmax(
            query: DoubleArray,
            keys: Array<DoubleArray>,
            mask: BooleanArray,
            index: Int,
            length: Int,
        ): DoubleArray {
            val scores = DoubleArray(length) { Double.NEGATIVE_INFINITY }
            var best = Double.NEGATIVE_INFINITY
            for (other in 0 until length) {
                if (!mask[index * length + other]) continue
                var sum = 0.0
                for (dim in 0 until d) sum += query[dim] * keys[other][dim]
                val score = sum / sqrt(d.toDouble())
                scores[other] = score
                if (score > best) best = score
            }
            if (best == Double.NEGATIVE_INFINITY) return DoubleArray(length)
            var total = 0.0
            for (other in 0 until length) {
                if (!mask[index * length + other]) continue
                val weight = exp(scores[other] - best)
                scores[other] = weight
                total += weight
            }
            val row = DoubleArray(length)
            if (total <= 0.0) return row
            for (other in 0 until length) {
                if (mask[index * length + other]) row[other] = scores[other] / total
            }
            return row
        }

        /**
         * Backpropagates [dStates] through the encoder into every projection and embedding.
         *
         * The attention backward is the softmax Jacobian written out: `dA_j = dO · V_j`, then
         * `dS_j = a_j (dA_j - Σ a dA)`, then into Q, K and V. No layer norm to differentiate, for the
         * reason the acoustic trainer gives: at this size Adam is stable and the extra derivative is
         * another place to be subtly wrong.
         */
        fun backwardEncoder(encoded: Encoded, dStates: Array<DoubleArray>) {
            val length = encoded.tokens.size
            var dCurrent = Array(length) { index -> dStates[index].copyOf() }
            for (layer in ENCODER_LAYERS - 1 downTo 0) {
                dCurrent = backwardMlp(encoded, layer, dCurrent)
                dCurrent = backwardAttention(encoded, layer, dCurrent)
            }
            for (index in 0 until length) {
                val token = encoded.tokens[index]
                for (dim in 0 until d) {
                    embedding.grad[token * d + dim] += dCurrent[index][dim]
                    language.grad[encoded.languageIndex * d + dim] += dCurrent[index][dim] * 0.5
                    gender.grad[encoded.genderIndex * d + dim] += dCurrent[index][dim] * 0.5
                }
            }
        }

        private fun backwardMlp(
            encoded: Encoded,
            layer: Int,
            incoming: Array<DoubleArray>,
        ): Array<DoubleArray> {
            val length = encoded.tokens.size
            val out = Array(length) { incoming[it].copyOf() }
            val input = encoded.mlpInput[layer]
            val activation = encoded.mlpHidden[layer]
            for (index in 0 until length) {
                val dPre = DoubleArray(d * 2)
                for (dim in 0 until d) {
                    val gradient = incoming[index][dim]
                    if (gradient == 0.0) continue
                    b2[layer].grad[dim] += gradient
                    for (unit in 0 until d * 2) {
                        w2[layer].grad[dim * (d * 2) + unit] += gradient * activation[index][unit]
                        dPre[unit] += gradient * w2[layer].at(dim, unit)
                    }
                }
                for (unit in 0 until d * 2) {
                    val value = activation[index][unit]
                    dPre[unit] *= 1.0 - value * value
                    if (dPre[unit] == 0.0) continue
                    b1[layer].grad[unit] += dPre[unit]
                    for (dim in 0 until d) {
                        w1[layer].grad[unit * d + dim] += dPre[unit] * input[index][dim]
                        out[index][dim] += dPre[unit] * w1[layer].at(unit, dim)
                    }
                }
            }
            return out
        }

        private fun backwardAttention(
            encoded: Encoded,
            layer: Int,
            incoming: Array<DoubleArray>,
        ): Array<DoubleArray> {
            val length = encoded.tokens.size
            val out = Array(length) { incoming[it].copyOf() }
            val input = encoded.layerInput[layer]
            val q = encoded.queries[layer]
            val k = encoded.keys[layer]
            val v = encoded.values[layer]
            val rows = encoded.weights[layer]
            val inverse = 1.0 / sqrt(d.toDouble())

            val dMixed = Array(length) { DoubleArray(d) }
            for (index in 0 until length) {
                for (dim in 0 until d) {
                    val gradient = incoming[index][dim]
                    if (gradient == 0.0) continue
                    for (other in 0 until d) {
                        wo[layer].grad[dim * d + other] += gradient * encoded.attended[layer][index][other]
                        dMixed[index][other] += gradient * wo[layer].at(dim, other)
                    }
                }
            }

            val dQ = Array(length) { DoubleArray(d) }
            val dK = Array(length) { DoubleArray(d) }
            val dV = Array(length) { DoubleArray(d) }
            for (index in 0 until length) {
                val row = rows[index]
                val dA = DoubleArray(length)
                var dot = 0.0
                for (other in 0 until length) {
                    if (row[other] == 0.0) continue
                    var sum = 0.0
                    for (dim in 0 until d) sum += dMixed[index][dim] * v[other][dim]
                    dA[other] = sum
                    dot += row[other] * sum
                }
                for (other in 0 until length) {
                    val weight = row[other]
                    if (weight == 0.0) continue
                    for (dim in 0 until d) dV[other][dim] += weight * dMixed[index][dim]
                    val dScore = weight * (dA[other] - dot)
                    if (dScore == 0.0) continue
                    for (dim in 0 until d) {
                        dQ[index][dim] += dScore * k[other][dim] * inverse
                        dK[other][dim] += dScore * q[index][dim] * inverse
                    }
                }
            }

            for (index in 0 until length) {
                TrainerKit.accumulateLinear(input[index], dQ[index], wq[layer], null)
                TrainerKit.accumulateLinear(input[index], dK[index], wk[layer], null)
                TrainerKit.accumulateLinear(input[index], dV[index], wv[layer], null)
            }
            for (index in 0 until length) {
                for (dim in 0 until d) {
                    var sum = 0.0
                    for (other in 0 until d) {
                        sum += dQ[index][other] * wq[layer].at(dim, other)
                        sum += dK[index][other] * wk[layer].at(dim, other)
                        sum += dV[index][other] * wv[layer].at(dim, other)
                    }
                    out[index][dim] += sum
                }
            }
            return out
        }

        // ── The frame decoder ───────────────────────────────────────────────────────────────────

        private fun frameInput(
            state: DoubleArray,
            before: DoubleArray,
            after: DoubleArray,
            speaker: DoubleArray,
            languageVector: DoubleArray,
            genderVector: DoubleArray,
            previous: Array<DoubleArray>,
            index: Int,
        ): DoubleArray {
            val input = DoubleArray(FRAME_INPUT)
            var offset = 0
            state.copyInto(input, offset, 0, d); offset += d
            // The states either side of this frame are what carry a consonant into the next vowel
            // and a vowel back into a consonant. Without them the decoder has to infer every
            // transition from the previous mel frames, which is exactly the information it does
            // not have when generation starts from silence.
            before.copyInto(input, offset, 0, d); offset += d
            after.copyInto(input, offset, 0, d); offset += d
            speaker.copyInto(input, offset, 0, d); offset += d
            languageVector.copyInto(input, offset, 0, d); offset += d
            genderVector.copyInto(input, offset, 0, d); offset += d
            for (frame in previous) {
                frame.copyInto(input, offset, 0, min(TtsSignal.BANDS, frame.size))
                offset += TtsSignal.BANDS
            }
            val position = position(index)
            position.copyInto(input, offset, 0, POSITION_DIM); offset += POSITION_DIM
            input[offset] = index / 100.0
            return input
        }

        private fun decode(
            input: DoubleArray,
        ): Pair<DoubleArray, DoubleArray> {
            val pre = TrainerKit.linear(input, frameW1, frameB1)
            val hidden = DoubleArray(FRAME_HIDDEN) { kotlin.math.tanh(pre[it]) }
            val output = TrainerKit.linear(hidden, frameW2, frameB2)
            return hidden to output
        }

        /** The speaker vector projected into the decoder's input space. */
        fun projectSpeaker(vector: DoubleArray): DoubleArray = TrainerKit.linear(vector, speakerW, null)

        /** One clip's loss, without touching gradients. */
        fun clipLoss(clip: ClipData, mel: Normalisation, speaker: Normalisation): Double {
            val encoded = encode(clip.tokens, languageIndex(clip.language), genderIndex(clip.gender))
            val count = min(clip.frames, min(clip.mels.size, MAX_FRAMES))
            if (count == 0) return 0.0
            val expanded = expand(encoded.states, clip.durations, count)
            val speakerVector = projectSpeaker(speaker.normalise(clip.speaker))
            val languageVector = DoubleArray(d) { language.at(languageIndex(clip.language), it) }
            val genderVector = DoubleArray(d) { gender.at(genderIndex(clip.gender), it) }
            val previous = Array(FRAME_CONTEXT) { DoubleArray(TtsSignal.BANDS) }
            var total = 0.0
            for (frame in 0 until count) {
                val input = frameInput(
                    expanded[frame],
                    expanded.getOrElse(frame - 1) { expanded[frame] },
                    expanded.getOrElse(frame + 1) { expanded[frame] },
                    speakerVector,
                    languageVector,
                    genderVector,
                    previous,
                    frame,
                )
                val (_, output) = decode(input)
                val target = mel.normalise(clip.mels[frame])
                for (band in 0 until TtsSignal.BANDS) {
                    val difference = output[band] - target[band]
                    total += difference * difference
                }
                if (FRAME_CONTEXT >= 3) {
                    previous[2] = previous[1]
                    previous[1] = previous[0]
                    previous[0] = target
                }
            }
            return total / (count * TtsSignal.BANDS)
        }

        /**
         * The model's own frames for a training clip, with the ground-truth history it was trained
         * on. This is the teacher-forced counterpart to [generate]: if these frames do not vocode
         * back to the clip's words, the acoustic model is not learning the alignment, and no amount
         * of free-running repair can fix that. Exposed for the archive probe and for a page that
         * wants to show the model's reconstruction beside the real clip.
         */
        fun reconstruct(clip: ClipData, mel: Normalisation, speaker: Normalisation): Array<DoubleArray> {
            val encoded = encode(clip.tokens, languageIndex(clip.language), genderIndex(clip.gender))
            val count = min(clip.frames, min(clip.mels.size, MAX_FRAMES))
            if (count == 0) return emptyArray()
            val expanded = expand(encoded.states, clip.durations, count)
            val speakerVector = projectSpeaker(speaker.normalise(clip.speaker))
            val languageVector = DoubleArray(d) { language.at(languageIndex(clip.language), it) }
            val genderVector = DoubleArray(d) { gender.at(genderIndex(clip.gender), it) }
            val previous = Array(FRAME_CONTEXT) { DoubleArray(TtsSignal.BANDS) }
            val out = Array(count) { DoubleArray(TtsSignal.BANDS) }
            for (frame in 0 until count) {
                val input = frameInput(
                    expanded[frame],
                    expanded.getOrElse(frame - 1) { expanded[frame] },
                    expanded.getOrElse(frame + 1) { expanded[frame] },
                    speakerVector,
                    languageVector,
                    genderVector,
                    previous,
                    frame,
                )
                val (_, output) = decode(input)
                out[frame] = output
                if (FRAME_CONTEXT >= 3) {
                    previous[2] = previous[1]
                    previous[1] = previous[0]
                    previous[0] = mel.normalise(clip.mels[frame])
                }
            }
            return out
        }

        /**
         * One clip through forward and backward, then an Adam step.
         *
         * The gradient reaches the encoder through the aligned states: every frame's decoder input
         * carries its phoneme's state, so the per-frame gradients are summed back onto the token that
         * produced them. That is the whole of the text-to-speech supervision.
         */
        fun trainClip(
            clip: ClipData,
            mel: Normalisation,
            speaker: Normalisation,
            adam: TrainerKit.Adam,
            clipNorm: Double,
            random: Random,
            contextSampling: Double = 0.0,
        ): Double {
            lastTrainAccuracy = Double.NaN
            zeroGradients()
            val encoded = encode(clip.tokens, languageIndex(clip.language), genderIndex(clip.gender))
            val count = min(clip.frames, min(clip.mels.size, MAX_FRAMES))
            if (count == 0) return 0.0
            val expanded = expand(encoded.states, clip.durations, count)
            val speakerNormalised = speaker.normalise(clip.speaker)
            val speakerVector = projectSpeaker(speakerNormalised)
            val languageVector = DoubleArray(d) { language.at(languageIndex(clip.language), it) }
            val genderVector = DoubleArray(d) { gender.at(genderIndex(clip.gender), it) }
            val previous = Array(FRAME_CONTEXT) { DoubleArray(TtsSignal.BANDS) }
            val dExpanded = Array(count) { DoubleArray(d) }
            var loss = 0.0
            var close = 0
            var values = 0
            val scale = 2.0 / (count * TtsSignal.BANDS)

            for (frame in 0 until count) {
                val input = frameInput(
                    expanded[frame],
                    expanded.getOrElse(frame - 1) { expanded[frame] },
                    expanded.getOrElse(frame + 1) { expanded[frame] },
                    speakerVector,
                    languageVector,
                    genderVector,
                    previous,
                    frame,
                )
                val (hidden, output) = decode(input)
                val target = mel.normalise(clip.mels[frame])
                val dOutput = DoubleArray(TtsSignal.BANDS)
                for (band in 0 until TtsSignal.BANDS) {
                    val difference = output[band] - target[band]
                    loss += difference * difference
                    if (abs(difference) <= 0.10) close++
                    values++
                    dOutput[band] = difference * scale
                }
                for (band in 0 until TtsSignal.BANDS) {
                    val gradient = dOutput[band]
                    frameB2.grad[band] += gradient
                    for (unit in 0 until FRAME_HIDDEN) {
                        frameW2.grad[band * FRAME_HIDDEN + unit] += gradient * hidden[unit]
                    }
                }
                // Through the tanh, then into the input. The input is a concatenation, so each
                // segment's gradient is a slice of the same vector.
                val dPre = DoubleArray(FRAME_HIDDEN)
                for (band in 0 until TtsSignal.BANDS) {
                    val gradient = dOutput[band]
                    if (gradient == 0.0) continue
                    for (unit in 0 until FRAME_HIDDEN) {
                        dPre[unit] += gradient * frameW2.at(band, unit)
                    }
                }
                val dInput = DoubleArray(FRAME_INPUT)
                for (unit in 0 until FRAME_HIDDEN) {
                    val value = hidden[unit]
                    val gradient = dPre[unit] * (1.0 - value * value)
                    if (gradient == 0.0) continue
                    frameB1.grad[unit] += gradient
                    for (dim in 0 until FRAME_INPUT) {
                        frameW1.grad[unit * FRAME_INPUT + dim] += gradient * input[dim]
                        dInput[dim] += gradient * frameW1.at(unit, dim)
                    }
                }
                var offset = 0
                for (dim in 0 until d) dExpanded[frame][dim] += dInput[offset + dim]
                offset += d
                val beforeIndex = (frame - 1).coerceAtLeast(0)
                val afterIndex = (frame + 1).coerceAtMost(count - 1)
                for (dim in 0 until d) dExpanded[beforeIndex][dim] += dInput[offset + dim]
                offset += d
                for (dim in 0 until d) dExpanded[afterIndex][dim] += dInput[offset + dim]
                offset += d
                val dSpeaker = DoubleArray(d) { dInput[offset + it] }
                offset += d
                for (dim in 0 until d) {
                    language.grad[languageIndex(clip.language) * d + dim] += dInput[offset + dim]
                    gender.grad[genderIndex(clip.gender) * d + dim] += dInput[offset + d + dim]
                }
                TrainerKit.accumulateLinear(speakerNormalised, dSpeaker, speakerW, null)

                if (FRAME_CONTEXT >= 3) {
                    previous[2] = previous[1]
                    previous[1] = previous[0]
                    previous[0] = if (contextSampling > 0.0 && random.nextDouble() < contextSampling) {
                        DoubleArray(TtsSignal.BANDS) { band -> output[band].coerceIn(-8.0, 8.0) }
                    } else {
                        target
                    }
                }
            }

            // Aligned frames back onto their phonemes.
            val dStates = Array(encoded.tokens.size) { DoubleArray(d) }
            var token = 0
            var remaining = encoded.tokens.indices.map { clip.durations.getOrElse(it) { 0 } }.toMutableList()
            for (frame in 0 until count) {
                while (token < remaining.size && remaining[token] <= 0) token++
                if (token >= remaining.size) break
                for (dim in 0 until d) dStates[token][dim] += dExpanded[frame][dim]
                remaining[token]--
            }
            backwardEncoder(encoded, dStates)
            adam.step(parameters().filter { it !== durationW1 && it !== durationB1 && it !== durationW2 && it !== durationB2 }, clipNorm)
            lastTrainAccuracy = if (values == 0) Double.NaN else close.toDouble() / values
            return loss / (count * TtsSignal.BANDS)
        }

        /** States repeated over each phoneme's frames. */
        private fun expand(states: Array<DoubleArray>, durations: IntArray, count: Int): Array<DoubleArray> {
            val out = ArrayList<DoubleArray>(count)
            for (index in states.indices) {
                val repeats = max(1, durations.getOrElse(index) { 1 })
                repeat(repeats) {
                    if (out.size < count) out += states[index]
                }
                if (out.size >= count) break
            }
            while (out.size < count) out += states.lastOrNull() ?: DoubleArray(d)
            return out.toTypedArray()
        }

        // ── Duration model ──────────────────────────────────────────────────────────────────────

        private fun durationForward(state: DoubleArray): Pair<DoubleArray, Double> {
            val pre = TrainerKit.linear(state, durationW1, durationB1)
            val hidden = DoubleArray(DURATION_HIDDEN) { kotlin.math.tanh(pre[it]) }
            val output = TrainerKit.linear(hidden, durationW2, durationB2)[0]
            return hidden to output
        }

        /** Fits the duration head on the encoder's states and the alignment's log durations. */
        fun fitDurations(
            clips: List<ClipData>,
            mel: Normalisation,
            speaker: Normalisation,
            config: Config,
        ) {
            val examples = clips.flatMap { clip ->
                val encoded = encode(clip.tokens, languageIndex(clip.language), genderIndex(clip.gender))
                encoded.states.mapIndexed { index, state ->
                    state to ln(1.0 + clip.durations.getOrElse(index) { 1 })
                }
            }
            if (examples.isEmpty()) return
            val params = listOf(durationW1, durationB1, durationW2, durationB2)
            params.forEach { it.grad.fill(0.0) }
            val adam = TrainerKit.Adam(5e-3)
            repeat(300) {
                var loss = 0.0
                for ((state, target) in examples) {
                    val (hidden, output) = durationForward(state)
                    val difference = output - target
                    loss += difference * difference
                    params.forEach { param -> param.grad.fill(0.0) }
                    val scale = 2.0 * difference / examples.size
                    durationB2.grad[0] += scale
                    for (unit in 0 until DURATION_HIDDEN) {
                        durationW2.grad[unit] += scale * hidden[unit]
                    }
                    for (unit in 0 until DURATION_HIDDEN) {
                        val dPre = scale * durationW2.at(0, unit) * (1.0 - hidden[unit] * hidden[unit])
                        durationB1.grad[unit] += dPre
                        for (dim in 0 until d) durationW1.grad[unit * d + dim] += dPre * state[dim]
                    }
                    adam.step(params, config.gradientClip)
                }
                if (it % 100 == 99) {
                    PrismPlatform.log.info("ModelCraft", "TTS duration fit: ${"%.4f".format(loss / examples.size)}")
                }
            }
            params.forEach { it.grad.fill(0.0) }
        }

        /** The duration head's loss on [clips], using the same examples the fit used. */
        fun durationLoss(clips: List<ClipData>, mel: Normalisation, speaker: Normalisation): Double {
            var total = 0.0
            var count = 0
            for (clip in clips) {
                val encoded = encode(clip.tokens, languageIndex(clip.language), genderIndex(clip.gender))
                for (index in encoded.states.indices) {
                    val target = ln(1.0 + clip.durations.getOrElse(index) { 1 })
                    val (_, output) = durationForward(encoded.states[index])
                    total += (output - target) * (output - target)
                    count++
                }
            }
            return if (count == 0) 0.0 else total / count
        }

        /** Predicted frames per phoneme, at least one each. */
        fun durations(states: Array<DoubleArray>): IntArray = IntArray(states.size) { index ->
            val (_, output) = durationForward(states[index])
            exp(output).roundToInt().coerceIn(1, MAX_PHONEME_FRAMES)
        }

        // ── Generation ──────────────────────────────────────────────────────────────────────────

        /**
         * Generates mel frames for [tokens] in the given voice.
         *
         * Every frame conditions on the previous three, which are the model's own outputs once
         * generation starts. That is what carries intonation: an independent per-frame prediction
         * would be a sequence of disconnected vowels.
         */
        fun generate(
            tokens: IntArray,
            languageCode: String,
            genderLabel: String,
            speakerVector: DoubleArray,
            maxFrames: Int = MAX_FRAMES,
            speed: Float = 1f,
            /** A known alignment, for a training clip or a probe; null predicts durations. */
            durationsOverride: IntArray? = null,
        ): Array<DoubleArray> {
            if (tokens.isEmpty()) return emptyArray()
            val languageIndex = languageIndex(languageCode)
            val genderIndex = genderIndex(genderLabel)
            val encoded = encode(tokens, languageIndex, genderIndex)
            val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
            val durations = durationsOverride
                ?.takeIf { it.size == tokens.size }
                ?.copyOf()
                ?: durations(encoded.states).map {
                    (it / rate).roundToInt().coerceIn(1, MAX_PHONEME_FRAMES)
                }.toIntArray()
            val frames = min(durations.sum(), maxFrames)
            val expanded = expand(encoded.states, durations, frames)
            val projected = projectSpeaker(speakerVector)
            val languageVector = DoubleArray(d) { language.at(languageIndex, it) }
            val genderVector = DoubleArray(d) { gender.at(genderIndex, it) }
            val previous = Array(FRAME_CONTEXT) { DoubleArray(TtsSignal.BANDS) }
            val out = Array(frames) { DoubleArray(TtsSignal.BANDS) }
            for (frame in 0 until frames) {
                val input = frameInput(
                    expanded[frame],
                    expanded.getOrElse(frame - 1) { expanded[frame] },
                    expanded.getOrElse(frame + 1) { expanded[frame] },
                    projected,
                    languageVector,
                    genderVector,
                    previous,
                    frame,
                )
                val (_, output) = decode(input)
                val clamped = DoubleArray(TtsSignal.BANDS) { output[it].coerceIn(-8.0, 8.0) }
                out[frame] = clamped
                if (FRAME_CONTEXT >= 3) {
                    previous[2] = previous[1]
                    previous[1] = previous[0]
                    previous[0] = clamped
                }
            }
            return out
        }

        /**
         * Refines a whole utterance in parallel rather than one frame at a time.
         *
         * The decoder was trained on real previous frames. Feeding it its own output one frame at
         * a time is a different distribution: the first mistake becomes the context for every
         * later frame. This method freezes a whole draft -- either zeros or [initial] -- and runs
         * every frame with that draft as history. The result replaces the draft, and the pass is
         * repeated. It is a fixed-point iteration, not a new model: each frame still sees the same
         * inputs it was trained on, and a bad frame cannot pull the next one off course until the
         * next pass.
         */
        fun generateRefined(
            tokens: IntArray,
            languageCode: String,
            genderLabel: String,
            speakerVector: DoubleArray,
            maxFrames: Int = MAX_FRAMES,
            speed: Float = 1f,
            initial: Array<DoubleArray>? = null,
            rounds: Int = 6,
            blend: Double = 0.5,
        ): Array<DoubleArray> {
            if (tokens.isEmpty()) return emptyArray()
            val languageIndex = languageIndex(languageCode)
            val genderIndex = genderIndex(genderLabel)
            val encoded = encode(tokens, languageIndex, genderIndex)
            val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
            val durations = durations(encoded.states).map {
                (it / rate).roundToInt().coerceIn(1, MAX_PHONEME_FRAMES)
            }.toIntArray()
            val frames = min(durations.sum(), maxFrames)
            if (frames == 0) return emptyArray()
            val expanded = expand(encoded.states, durations, frames)
            val projected = projectSpeaker(speakerVector)
            val languageVector = DoubleArray(d) { language.at(languageIndex, it) }
            val genderVector = DoubleArray(d) { gender.at(genderIndex, it) }

            var draft = if (initial != null && initial.size == frames) {
                Array(frames) { index ->
                    DoubleArray(TtsSignal.BANDS) { band ->
                        initial[index].getOrElse(band) { 0.0 }.coerceIn(-8.0, 8.0)
                    }
                }
            } else {
                Array(frames) { DoubleArray(TtsSignal.BANDS) }
            }

            repeat(rounds.coerceAtLeast(1)) {
                val next = Array(frames) { DoubleArray(TtsSignal.BANDS) }
                for (frame in 0 until frames) {
                    val previous = Array(FRAME_CONTEXT) { offset ->
                        draft.getOrElse(frame - 1 - offset) { DoubleArray(TtsSignal.BANDS) }
                    }
                    val input = frameInput(
                        expanded[frame],
                        expanded.getOrElse(frame - 1) { expanded[frame] },
                        expanded.getOrElse(frame + 1) { expanded[frame] },
                        projected,
                        languageVector,
                        genderVector,
                        previous,
                        frame,
                    )
                    val (_, output) = decode(input)
                    next[frame] = DoubleArray(TtsSignal.BANDS) { band ->
                        val value = output[band] * blend + draft[frame][band] * (1.0 - blend)
                        value.coerceIn(-8.0, 8.0)
                    }
                }
                draft = next
            }
            return draft
        }
    }

    // ── The codec and the vocoder ───────────────────────────────────────────────────────────────

    /** Nearest codebook entry to [vector], by squared distance. */
    fun nearest(codebook: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        if (codebook.isEmpty()) return vector
        var best = codebook[0]
        var bestDistance = Double.MAX_VALUE
        for (entry in codebook) {
            var sum = 0.0
            for (index in vector.indices) {
                val difference = vector[index] - entry.getOrElse(index) { 0.0 }
                sum += difference * difference
            }
            if (sum < bestDistance) {
                bestDistance = sum
                best = entry
            }
        }
        return best
    }

    /** k-means with deterministic initial centres: every k-th example. */
    fun kmeans(vectors: List<DoubleArray>, k: Int, iterations: Int, random: Random): Array<DoubleArray> {
        if (vectors.isEmpty() || k <= 0) return emptyArray()
        val width = vectors.first().size
        val centres = Array(min(k, vectors.size)) { index ->
            vectors[(index * vectors.size / min(k, vectors.size)) % vectors.size].copyOf()
        }
        val assignment = IntArray(vectors.size)
        repeat(iterations) {
            for ((index, vector) in vectors.withIndex()) {
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for ((centreIndex, centre) in centres.withIndex()) {
                    var sum = 0.0
                    for (dim in 0 until width) {
                        val difference = vector.getOrElse(dim) { 0.0 } - centre.getOrElse(dim) { 0.0 }
                        sum += difference * difference
                    }
                    if (sum < bestDistance) {
                        bestDistance = sum
                        best = centreIndex
                    }
                }
                assignment[index] = best
            }
            val sums = Array(centres.size) { DoubleArray(width) }
            val counts = IntArray(centres.size)
            for ((index, vector) in vectors.withIndex()) {
                val centre = assignment[index]
                counts[centre]++
                for (dim in 0 until width) sums[centre][dim] += vector.getOrElse(dim) { 0.0 }
            }
            for (centre in centres.indices) {
                if (counts[centre] == 0) {
                    // An empty cluster moves to a real example rather than staying where it started.
                    val replacement = vectors[random.nextInt(vectors.size)]
                    centres[centre] = replacement.copyOf()
                    continue
                }
                for (dim in 0 until width) centres[centre][dim] = sums[centre][dim] / counts[centre]
            }
        }
        return centres
    }

    // ── The trained model ───────────────────────────────────────────────────────────────────────

    /**
     * A finished speech model: the network, everything it was normalised with, the codec and the
     * voices it learned.
     */
    class Trained(
        val net: Net,
        val config: Config,
        /** The folder the model lives in: its dataset's recordings are the voice. */
        val directory: File,
        val mel: Normalisation,
        val speaker: Normalisation,
        val codec: CoderTrainer.Trained?,
        val codebook: Array<DoubleArray>,
        val modelId: String,
        val name: String,
        val task: String,
        val clips: List<ClipData>,
        val firstLoss: Double,
        val lastLoss: Double,
        val durationLoss: Double,
        val steps: Int,
        val epochs: Int,
        val requestedSteps: Int,
        val validationLoss: Double,
        /** A loaded model's language list; computed from the clips when null. */
        private val languagesOverride: List<String>? = null,
        /** A loaded model's mean voice; computed from the clips when null. */
        private val meanSpeakerOverride: DoubleArray? = null,
        /** A loaded model's per-gender voices; computed from the clips when null. */
        private val genderSpeakersOverride: Map<String, DoubleArray>? = null,
        /** A loaded model's chosen default voice; computed from the clips when null. */
        private val leadSpeakerOverride: DoubleArray? = null,
        /**
         * Which recording speaks for which language/gender, as written at training time.
         *
         * Keyed `"language|gender"`, then `"language"`, then `"default"`. A loaded model reads
         * this from its metadata; a freshly trained one computes it from the clips.
         */
        private val voiceClipsOverride: Map<String, String>? = null,
        /** The sample rate of [synthesize]'s output; neural voices are 24 kHz. */
        private val outputRateOverride: Int? = null,
        /**
         * Whether [codec] was trained on normalised frames.
         *
         * Version 1 and 2 artifacts were trained on raw log-mel frames while the decoder emitted
         * normalised ones. Those codecs cannot be used on generated frames without corrupting
         * them; [synthesize] bypasses them instead of pretending an old pair is usable. Version 3
         * writes `codec.space = normalised` and is the only one this flag is true for.
         */
        val codecNormalised: Boolean = true,
        /** True to speak through the dataset engine; false to keep Pocket TTS as the engine. */
        private val builtInEngineOverride: Boolean? = null,
        /** True when Pocket TTS may cover text the built-in engine cannot speak. */
        private val allowPocketOverride: Boolean? = null,
    ) {
        /** Files [writeArtifacts] wrote, in order. */
        var artifactFiles: List<File> = emptyList()
            private set

        /**
         * The recording this model speaks in, per language and gender.
         *
         * The map is the point of training a voice model now: the network fits the dataset and the
         * sentence is spoken by a pretrained neural voice conditioned on one of these recordings.
         */
        val voiceClips: Map<String, String> = voiceClipsOverride ?: voiceClipsOf(clips, config.defaultLanguage)

        /** What [synthesize] returns: the neural engine's rate, not the analysis rate. */
        val outputRate: Int = outputRateOverride ?: NeuralVoice.RATE

        /** True when this model speaks through the engine built from its own dataset. */
        val useBuiltInEngine: Boolean = builtInEngineOverride ?: true

        /** True when Pocket TTS may stand in for text the built-in engine cannot speak. */
        val allowPocket: Boolean = allowPocketOverride ?: true

        @Volatile
        private var voiceEngineCache: DatasetVoiceEngine.Engine? = null

        @Volatile
        private var voiceEngineTried: Boolean = false

        /**
         * The dataset engine beside this model, or null when there is none (or it is switched off).
         *
         * Loaded once. A model folder copied without its `voice_engine.json` still speaks through
         * its reference clip, so this is a fallback rather than a hard requirement.
         */
        fun builtInEngine(): DatasetVoiceEngine.Engine? {
            if (!useBuiltInEngine) return null
            voiceEngineCache?.let { return it }
            if (voiceEngineTried) return null
            synchronized(this) {
                voiceEngineCache?.let { return it }
                if (voiceEngineTried) return null
                voiceEngineTried = true
                voiceEngineCache = DatasetVoiceEngine.load(directory)?.let { engine ->
                    DatasetVoiceEngine.Engine(
                        engine.directory,
                        engine.voices,
                        allowPocket,
                        engine.neural,
                        engine.allowKokoro,
                        engine.custom,
                        engine.customPath,
                    )
                }
                return voiceEngineCache
            }
        }

        /** The languages this dataset actually contained, most frequent first. */
        val languages: List<String> = languagesOverride ?: clips.map { it.language }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

        /** The mean voice, in raw (un-normalised) speaker space. */
        val meanSpeaker: DoubleArray =
            meanSpeakerOverride ?: average(clips.map { it.speaker }, TtsSignal.SPEAKER_DIMENSIONS)

        /**
         * The voice a model speaks in when the caller names only a gender.
         *
         * A REAL TRAINING CLIP, not the mean of every clip. The mean of twenty different speakers
         * is not a person: it has an average pitch, an average brightness and none of the formant
         * structure that makes a voice recognisable. The model was conditioned on actual speaker
         * vectors, so generation should be conditioned on one too. [leadVoice] picks the clip with
         * the best combination of voiced frames, level and length, which is the cleanest example of
         * a voice this dataset contains rather than an invented average of all of them.
         */
        val leadSpeaker: DoubleArray = leadSpeakerOverride
            ?: leadVoice(clips)
            ?: meanSpeaker

        /** The selected real voice per gender, or the lead voice when that gender is absent. */
        val genderSpeakers: Map<String, DoubleArray> = genderSpeakersOverride ?: GENDERS.associateWith { label ->
            leadVoice(clips.filter { it.gender.equals(label, true) }) ?: leadSpeaker
        }

        /**
         * The recording this model speaks in for [language] and [gender], or null when the dataset
         * has no usable audio left beside the model.
         *
         * Lookup is most specific first: language and gender, then language alone, then whatever
         * [voiceClips] calls the default. The file is checked here rather than assumed, because a
         * model folder can be copied without its recordings and the caller must be told.
         */
        fun voiceClip(language: String, gender: String): File? =
            voiceClipName(language, gender)?.let { File(directory, it) }?.takeIf { it.isFile }

        /** The name of that recording, for a caption; null when there is none. */
        fun voiceClipName(language: String, gender: String): String? {
            val lang = language.trim().lowercase()
            val sex = gender.trim().lowercase()
            return voiceClips["$lang|$sex"] ?: voiceClips[lang] ?: voiceClips["default"]
        }

        /**
         * Speaks [text] in the model's voice, as samples at [outputRate].
         *
         * [reference] is the cloning path: a recording chosen by the caller replaces the dataset's
         * own clip, so the same voice model speaks as somebody else. Null uses the best recording
         * this dataset has for the requested language and gender.
         *
         * The sound comes from [DatasetVoiceEngine] when one was built, or from [NeuralVoice] as a
         * fallback: either way it is a local neural text-to-speech model conditioned on the
         * dataset's recording and profile. The small network trained here cannot free-run a novel
         * sentence -- see [synthesizeAcoustic], which is kept for diagnosis and for the miniature
         * renderer -- so it measures the voice and the engine says the words in it. Calling this
         * with no engine installed throws a sentence naming what is missing, which the pages show
         * verbatim instead of playing silence or babble.
         *
         * [speed] is accepted for the speech-engine interface; the neural renderer speaks at its
         * own pace and ignores it.
         */
        fun synthesize(
            text: String,
            language: String = languages.firstOrNull() ?: config.defaultLanguage,
            gender: String = config.defaultGender,
            reference: SpeechClip? = null,
            speed: Float = 1f,
            useCodec: Boolean = true,
            /**
             * A packaged voice to speak in, by speaker name or WAV name. Null keeps the
             * language-and-gender choice the model was trained to make.
             */
            voiceName: String? = null,
        ): FloatArray {
            if (text.isBlank()) return FloatArray(0)
            // AN EXPLICIT VOICE WINS. The test page lets a listener compare every voice the
            // dialog built, which is impossible through the language/gender map: two English
            // female voices share a key, so the second could never be heard.
            if (reference == null && !voiceName.isNullOrBlank()) {
                val engine = builtInEngine() ?: throw IllegalStateException(
                    "This model has no dataset voice engine, so its voice \"$voiceName\" cannot " +
                        "be chosen individually. Open the model's Voices dialog and press OK to " +
                        "build the engine, or turn the built-in engine back on.",
                )
                return engine.speakVoice(text, voiceName, speed, allowPocket)
            }
            val named = voiceClip(language, gender)
            val voice = reference ?: named?.let { AudioDecoding.read(it) }
            if (voice == null) {
                throw IllegalStateException(
                    "This voice model has no recording to speak in. Its training folder " +
                        "${directory.name} has no clip named by the model's metadata" +
                        (if (named == null) "." else " (${named.name} is missing).") +
                        " Retrain the model with its audio files beside the CSV.",
                )
            }
            // THE DATASET ENGINE FIRST. When this model was built with the voices dialog, its
            // own recordings and their measured profiles are the engine. A cloning request always
            // bypasses it: the caller supplied a recording and asked for that voice specifically.
            if (reference == null) {
                builtInEngine()?.let { engine ->
                    val spoken = runCatching {
                        engine.speak(text, language, gender, speed, allowPocket)
                    }.onFailure {
                        PrismPlatform.log.warn(
                            "ModelCraftSpeech",
                            "Dataset voice engine could not speak in ${directory.name}: ${it.message}",
                        )
                    }.getOrNull()
                    if (spoken != null && spoken.isNotEmpty()) return spoken
                }
                // POCKET AS THE CHOSEN ENGINE, not the fallback. NeuralVoice.speak prefers Kokoro
                // for English, so a model whose switch says Pocket must not go through it: that
                // would play Kokoro while the model's metadata promises Pocket.
                if (!useBuiltInEngine) {
                    return NeuralVoice.speakPocket(
                        text = text,
                        reference = voice,
                        voiceId = "pocket-$named",
                    )
                }
            }
            // The engine caches the conditioned voice by this id plus the recording's length. A
            // content hash joins them so two different recordings that happen to be equally long
            // cannot inherit each other's prepared state.
            val voiceId = reference?.let {
                "reference-${it.samples.size}-${it.samples.contentHashCode()}"
            } ?: "dataset-$named"
            // A non-null reference is the cloning path; the dataset's own clip is a normal
            // text-to-speech voice and must not be put through Seed-VC.
            return NeuralVoice.speak(
                text = text,
                reference = voice,
                voiceId = voiceId,
                language = language,
                gender = gender,
                clone = reference != null,
                allowPocket = allowPocket,
            )
        }

        /**
         * The from-scratch acoustic renderer: predicted mel, codec, Griffin-Lim.
         *
         * This is the trained network doing what it can. It reconstructs the clips it was fitted
         * on closely (teacher-forced frames transcribe as the source sentence), but it was not
         * trained on enough speech to say a new sentence, so free-running it babbles. Kept because
         * the probe tests need exactly this comparison, and because the miniature renderer on the
         * test page is built from its acoustic predictions.
         *
         * [reference] is a recording's samples; its speaker vector replaces the bank voice.
         */
        fun synthesizeAcoustic(
            text: String,
            language: String = languages.firstOrNull() ?: config.defaultLanguage,
            gender: String = config.defaultGender,
            reference: FloatArray? = null,
            /** 1.0 is the model's own rate; 2.0 says the same sentence in half the time. */
            speed: Float = 1f,
            /**
             * Whether generated frames go through the trained codec.
             *
             * True is the model as designed: the decoder predicts acoustics, the codec quantises
             * them, and the vocoder receives the round trip. False is the decoder's own frames
             * straight into the vocoder, which is the comparison that tells a listener whether the
             * codec is helping or adding buzz on this dataset.
             */
            useCodec: Boolean = true,
        ): FloatArray {
            val tokens = PrismPhonemes.phonemize(text, language)
                .map { PrismPhonemes.idOf(it) }
                .take(MAX_TOKENS)
                .toIntArray()
            if (tokens.isEmpty()) return FloatArray(0)
            val rawSpeaker = if (reference != null) {
                TtsSignal.speakerVector(reference, config.signal)
            } else {
                genderSpeakers[gender.trim().lowercase()] ?: leadSpeaker
            }
            val normalisedSpeaker = speaker.normalise(rawSpeaker)
            val mels = net.generate(tokens, language, gender, normalisedSpeaker, speed = speed)
            if (mels.isEmpty()) return FloatArray(0)
            val decoded = mels.map { frame ->
                // The codec and the decoder share the normalised mel space in a version-3 model.
                // An older codec was fitted on raw log-mel values; running it here would hand the
                // vocoder a differently scaled spectrogram, so it is bypassed rather than used
                // wrongly. The decoder's own frame is the fallback, and it is already normalised.
                val quantised = if (useCodec && codecNormalised && codec != null && codebook.isNotEmpty()) {
                    val code = codec.decode(nearest(codebook, codec.encode(frame)))
                    DoubleArray(TtsSignal.BANDS) { band ->
                        code.getOrElse(band) { 0.0 }.coerceIn(-8.0, 8.0)
                    }
                } else {
                    frame
                }
                mel.denormalise(quantised)
            }
            return TtsSignal.vocode(decoded, config.signal, config.seed xor 0x5EED)
        }

        /** The same synthesis, as a playable RIFF/WAVE file. */
        fun wav(
            text: String,
            language: String,
            gender: String,
            reference: SpeechClip? = null,
            useCodec: Boolean = true,
        ): ByteArray = SpeechMeshWire.wav(
            synthesize(text, language, gender, reference, useCodec = useCodec),
            outputRate,
        )

        /** Writes the model and returns the files it created. */
        fun writeArtifacts(directory: File, model: CraftedModel): List<File> {
            directory.mkdirs()
            val files = mutableListOf<File>()
            val weights = File(directory, WEIGHTS_FILE)
            TrainerKit.WeightFile.write(
                weights,
                MAGIC,
                intArrayOf(PrismPhonemes.SYMBOLS.size, D_MODEL, ENCODER_LAYERS, TtsSignal.BANDS),
                net.parameters(),
            )
            files += weights
            codec?.let { coder ->
                val codecFile = File(directory, CODEC_FILE)
                TrainerKit.WeightFile.write(
                    codecFile,
                    CODEC_MAGIC,
                    intArrayOf(coder.inputs, coder.code),
                    coder.parameters(),
                )
                files += codecFile
            }

            val metadata = JSONObject().apply {
                put("format", "prism-tts-model")
                put("version", 4L)
                put("kind", "text-to-speech-and-voice-cloning")
                put("model", name)
                put("crafted_model_id", modelId)
                put("audio_task", task)
                put("sample_rate", config.sampleRate.toLong())
                put("hop_ms", TtsSignal.HOP_MS.toLong())
                put("window_ms", TtsSignal.WINDOW_MS.toLong())
                put("mel_bands", TtsSignal.BANDS)
                put("frames_per_second", 1_000 / TtsSignal.HOP_MS)
                put("output_rate", outputRate.toLong())

                put(
                    "components",
                    JSONArray(
                        listOf(
                            component(
                                "text_encoder",
                                "Phoneme embedding + $ENCODER_LAYERS self-attention layers, d=$D_MODEL",
                                encoderParameters(net).toLong(),
                            ),
                            component(
                                "phoneme_aligner",
                                "Monotonic duration alignment: sonority-weighted frame assignment",
                                0L,
                            ),
                            component(
                                "duration_model",
                                "Two-layer head over encoder states, $DURATION_HIDDEN hidden units",
                                (DURATION_HIDDEN * D_MODEL + DURATION_HIDDEN + DURATION_HIDDEN + 1).toLong(),
                            ),
                            component(
                                "acoustic_decoder",
                                "$FRAME_HIDDEN hidden units, $FRAME_CONTEXT frames of context, ${TtsSignal.BANDS} mel outputs",
                                (FRAME_INPUT * FRAME_HIDDEN + FRAME_HIDDEN + FRAME_HIDDEN * TtsSignal.BANDS + TtsSignal.BANDS).toLong(),
                            ),
                            component(
                                "codec",
                                "${codec?.code ?: 0}-wide codes, ${codebook.size} entries, ${codec?.inputs ?: 0}-band reconstruction",
                                codec?.parameters()?.sumOf { it.data.size }?.toLong() ?: 0L,
                            ),
                            component(
                                "vocoder",
                                "Griffin-Lim: ${config.signal.fftSize}-sample FFT, ${config.signal.hop}-sample hop, " +
                                    "32 iterations, mel-filterbank inversion",
                                0L,
                            ),
                            component(
                                "speaker_encoder",
                                "${TtsSignal.SPEAKER_DIMENSIONS} statistics of a reference recording: pitch, " +
                                    "brightness, tilt, noise and eight band energies",
                                0L,
                            ),
                            component(
                                "voice",
                                "Dataset voice engine: each speaker's recording and measured pitch, " +
                                    "spectral centroid and air, spoken by the local studio voice. " +
                                    "Pocket TTS is the optional fallback, off unless this model " +
                                    "allows it. The from-scratch stages above measure the dataset and " +
                                    "render its diagnosis; they do not say a new sentence",
                                0L,
                            ),
                        ),
                    ),
                )

                put("architecture", JSONObject().apply {
                    put("shape", "non-autoregressive duration TTS (FastSpeech lineage)")
                    put("text_encoder_layers", ENCODER_LAYERS)
                    put("d_model", D_MODEL)
                    put("sliding_window", config.window > 0)
                    put("sliding_window_size", config.window)
                    put("full_attention_per_nth_layer", config.fullEvery)
                    put(
                        "attention_masks",
                        JSONArray((0 until ENCODER_LAYERS).map { layer ->
                            if (net.isFull(layer)) "full" else "sliding:${config.window}"
                        }),
                    )
                    put("duration_hidden", DURATION_HIDDEN)
                    put("decoder_hidden", FRAME_HIDDEN)
                    put("decoder_context_frames", FRAME_CONTEXT)
                    put("codec_code_width", codec?.code ?: 0)
                    put("codebook_size", codebook.size)
                    put("vocoder", "griffin-lim")
                    put("parameters", net.parameterCount().toLong())
                })

                put("normalisation", JSONObject().apply {
                    put("mel_mean", JSONArray(mel.mean.toList()))
                    put("mel_deviation", JSONArray(mel.deviation.toList()))
                    put("speaker_mean", JSONArray(speaker.mean.toList()))
                    put("speaker_deviation", JSONArray(speaker.deviation.toList()))
                })
                put("codebook", JSONArray(codebook.map { entry -> JSONArray(entry.toList()) }))
                put("mean_speaker", JSONArray(meanSpeaker.toList()))
                put("lead_speaker", JSONArray(leadSpeaker.toList()))
                put(
                    "gender_speakers",
                    JSONObject().apply {
                        genderSpeakers.forEach { (label, vector) -> put(label, JSONArray(vector.toList())) }
                    },
                )
                put(
                    "languages",
                    JSONArray(languages.ifEmpty { listOf(config.defaultLanguage) }),
                )
                put(
                    "voice",
                    JSONObject().apply {
                        put(
                            "engine",
                            if (ModelCraft.usesBuiltInVoiceEngine(model)) "dataset" else "pocket-tts",
                        )
                        put("allow_pocket_tts", ModelCraft.allowsPocketFallback(model))
                        put("engine_file", DatasetVoiceEngine.FILE)
                        put("synthesizer", DatasetVoiceEngine.SYNTHESIZER)
                        put("sample_rate", NeuralVoice.RATE.toLong())
                        put("reference_rate", NeuralVoice.WORK_RATE.toLong())
                        put("engine_ready", NeuralVoice.missingModels().isEmpty())
                        put(
                            "studio_ready",
                            com.prism.launcher.speech.KokoroSeedVoice.isStudioInstalled(),
                        )
                        put(
                            "clips",
                            JSONObject().apply {
                                voiceClips.forEach { (key, name) -> put(key, name) }
                            },
                        )
                    },
                )
                put("clips", clips.size.toLong())
                put("tokens", clips.sumOf { it.tokens.size }.toLong())
                put("frames", clips.sumOf { it.mels.size }.toLong())
                put("voices", config.voiceCount.coerceAtLeast(1))
                put("epochs", epochs.toLong())
                put("steps_requested", requestedSteps.toLong())
                put("steps_taken", steps.toLong())
                put("loss_first", firstLoss)
                put("loss_last", lastLoss)
                put("loss_validation", validationLoss)
                put("duration_loss", durationLoss)
                put("codec", JSONObject().apply {
                    put("inputs", codec?.inputs ?: 0)
                    put("code", codec?.code ?: 0)
                    put("examples", codec?.examples ?: 0)
                    put("loss_first", codec?.firstLoss ?: 0.0)
                    put("loss_last", codec?.lastLoss ?: 0.0)
                    // What space the codec encodes. A loader that does not find this is reading a
                    // version-2 model whose codec was trained on raw log-mel values.
                    put("space", "normalised")
                })
                put("weights_file", WEIGHTS_FILE)
                if (codec != null) put("codec_weights_file", CODEC_FILE)
                put("weights_format", TrainerKit.WeightFile::class.simpleName ?: "prism-weights")
                put(
                    "note",
                    "Training fits a ${net.parameterCount()}-parameter acoustic pipeline to this " +
                        "dataset and chooses the finest recording per language and gender as the " +
                        "voice. A text-to-speech model speaks through the dataset voice engine " +
                        "(voice_engine.json) when one was built; Pocket TTS is the explicit fallback " +
                        "and is only used when allow_pocket_tts is true. A small network trained on " +
                        "a few minutes of audio can rebuild those minutes but cannot say a new " +
                        "sentence, and rendering its free-running frames is what produced babble. " +
                        "The from-scratch stages remain in this file for diagnosis and for the " +
                        "miniature acoustic renderer.",
                )
            }
            val metadataFile = File(directory, METADATA_FILE)
            metadataFile.writeText(metadata.toString())
            files += metadataFile
            artifactFiles = files
            return files
        }

        private fun component(name: String, detail: String, parameters: Long): JSONObject =
            JSONObject().apply {
                put("name", name)
                put("detail", detail)
                put("parameters", parameters)
            }

        private fun encoderParameters(net: Net): Int =
            (0 until ENCODER_LAYERS).sumOf { layer ->
                net.wq[layer].data.size + net.wk[layer].data.size +
                    net.wv[layer].data.size + net.wo[layer].data.size +
                    net.w1[layer].data.size + net.b1[layer].data.size +
                    net.w2[layer].data.size + net.b2[layer].data.size
            }
    }

    private fun average(vectors: List<DoubleArray>, dimensions: Int): DoubleArray {
        if (vectors.isEmpty()) return DoubleArray(dimensions)
        val out = DoubleArray(dimensions)
        for (vector in vectors) {
            for (index in 0 until dimensions) out[index] += vector.getOrElse(index) { 0.0 }
        }
        for (index in 0 until dimensions) out[index] /= vectors.size
        return out
    }

    /**
     * The cleanest actual voice among [clips], or null when there are none.
     *
     * The speaker vector holds the measurements [TtsSignal.speakerVector] made: index 16 is the
     * fraction of voiced frames, index 17 is level and index 18 is level variation. A clip with
     * many voiced frames is one where the model has speech to learn from; one that is loud is not
     * buried under a noise floor; one that is longer gives that voice more examples. Those are the
     * three things that make a usable default, and none of them is an average of other voices.
     */
    private fun leadVoice(clips: List<ClipData>): DoubleArray? =
        clips.maxByOrNull(::voiceScore)?.speaker

    /**
     * How clean a clip is as this dataset's voice: voiced, audible, long enough to teach and,
     * importantly for a neural clone, bright. Pocket TTS reproduces the reference's spectral
     * envelope as faithfully as its pitch, so a dull 16 kHz take conditions a dull voice no
     * matter how well the rest of the model is trained. The eight band means at indices 8..15
     * are the coarse long-term spectrum; the top four are the presence/air half, and a clip with
     * real energy there makes a far better default than one that only wins on loudness.
     */
    private fun voiceScore(clip: ClipData): Double {
        val voiced = clip.speaker.getOrElse(16) { 0.0 }.coerceIn(0.0, 1.0)
        val level = (clip.speaker.getOrElse(17) { 0.0 } / 4.0).coerceIn(0.0, 1.0)
        val seconds = clip.frames.toDouble() / 100.0
        val noise = clip.speaker.getOrElse(7) { 0.0 }.coerceIn(0.0, 1.0)
        return voiced * 2.0 + level + ln(1.0 + seconds) * 0.25 + clip.clarity * 4.0 - noise * 0.5
    }

    /**
     * The best recording per language and gender, plus the fallbacks.
     *
     * A dataset with one speaker yields one entry per form of key; a multilingual archive gets a
     * voice per language instead of the accent of whichever clip happened to sort first. Only real
     * recordings are listed -- an average of twenty speakers is not a person, and the neural engine
     * conditions on a waveform, not on a speaker vector.
     */
    private fun voiceClipsOf(clips: List<ClipData>, defaultLanguage: String): Map<String, String> {
        if (clips.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        // A model configured for English should not speak its first sentence in whichever
        // language happens to have the loudest recording in the archive.
        val wanted = defaultLanguage.trim().lowercase()
        (clips.filter { it.language == wanted }.ifEmpty { clips })
            .maxByOrNull(::voiceScore)?.let { out["default"] = it.name }
        for ((language, group) in clips.groupBy { it.language.trim().lowercase() }) {
            if (language.isBlank()) continue
            group.maxByOrNull(::voiceScore)?.let { out[language] = it.name }
            for ((gender, pair) in group.groupBy { it.gender.trim().lowercase() }) {
                if (gender.isBlank()) continue
                pair.maxByOrNull(::voiceScore)?.let { best -> out["$language|$gender"] = best.name }
            }
        }
        return out
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    /** True when [directory] holds a trained speech model rather than only a recipe. */
    fun trained(directory: File): Boolean =
        File(directory, METADATA_FILE).isFile && File(directory, WEIGHTS_FILE).isFile

    /**
     * What the trainer can see in [directory], in one line.
     *
     * Used by the test page when no speech model was saved, so the message can say which CSV and
     * which columns the run actually had rather than asserting the person's transcript is not
     * there. It reads CSVs and counts files; it does not decode, train or download anything.
     */
    fun inspect(directory: File): String {
        val clips = directory.listFiles()
            ?.count { it.isFile && it.extension.lowercase() in SpeechDataset.AUDIO_EXTENSIONS }
            ?: 0
        val csvs = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (csvs.isEmpty()) return "$clips audio clip(s), no CSV beside them"
        val parts = csvs.map { csv ->
            val rows = runCatching { ModelCraft.parseCsv(csv) }.getOrDefault(emptyList())
            val columns = textColumns(rows)
            val filled = rows.count { row -> columns.any { column -> !row[column].isNullOrBlank() } }
            val names = if (columns.isEmpty()) "no transcript/description column" else columns.joinToString("/")
            "${csv.name} [$names] $filled row(s) with text"
        }
        return "$clips audio clip(s), ${parts.joinToString("; ")}"
    }

    /** Reads a model back. Null when the artifacts are missing or not this build's. */
    fun load(directory: File): Trained? {
        val metadata = runCatching { JSONObject(File(directory, METADATA_FILE).readText()) }.getOrNull()
            ?: return null
        if (metadata.optString("format", "") != "prism-tts-model") return null
        val sampleRate = metadata.optInt("sample_rate", 16_000).coerceIn(8_000, 48_000)
        val architecture = metadata.optJSONObject("architecture") ?: JSONObject()
        val config = Config(
            sampleRate = sampleRate,
            window = if (metadata.optJSONObject("architecture")?.optBoolean("sliding_window", false) == true) {
                architecture.optInt("sliding_window_size", 0).coerceAtLeast(1)
            } else {
                0
            },
            fullEvery = architecture.optInt("full_attention_per_nth_layer", 1).coerceAtLeast(1),
            epochs = metadata.optInt("epochs", 0),
            stepsPerEpoch = 0,
            learningRate = 1e-3,
            gradientClip = 1.0,
            seed = 42L,
            defaultLanguage = metadata.optJSONArray("languages")?.optString(0, "en") ?: "en",
            defaultGender = "female",
            voiceCount = metadata.optInt("voices", 1),
            task = metadata.optString("audio_task", ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue),
        )
        val net = Net(config, Random(42L))
        if (!TrainerKit.WeightFile.read(
                File(directory, WEIGHTS_FILE),
                MAGIC,
                intArrayOf(PrismPhonemes.SYMBOLS.size, D_MODEL, ENCODER_LAYERS, TtsSignal.BANDS),
                net.parameters(),
            )
        ) {
            return null
        }

        val normalisation = metadata.optJSONObject("normalisation") ?: return null
        val mel = Normalisation.of(TtsSignal.BANDS)
        val speaker = Normalisation.of(TtsSignal.SPEAKER_DIMENSIONS)
        readArray(normalisation.optJSONArray("mel_mean"), mel.mean)
        readArray(normalisation.optJSONArray("mel_deviation"), mel.deviation, 1.0)
        readArray(normalisation.optJSONArray("speaker_mean"), speaker.mean)
        readArray(normalisation.optJSONArray("speaker_deviation"), speaker.deviation, 1.0)

        val codebook = buildList {
            val array = metadata.optJSONArray("codebook") ?: return@buildList
            for (index in 0 until array.length()) {
                val entry = array.optJSONArray(index) ?: continue
                add(DoubleArray(entry.length()) { entry.optDouble(it, 0.0) })
            }
        }.toTypedArray()

        // A version-2 codec was trained on raw log-mel values. Its tensors are readable, but using
        // them on normalised decoder output is the exact scale mismatch that made generated speech
        // screech. Record the space and let synthesis bypass the codec when it is not normalised.
        val codecMeta = metadata.optJSONObject("codec")
        val codecNormalised = codecMeta?.optString("space", "") == "normalised"

        val coder = runCatching {
            val file = File(directory, CODEC_FILE)
            if (!file.isFile) return@runCatching null
            val inputs = codecMeta?.optInt("inputs", 0)?.takeIf { it > 0 } ?: return@runCatching null
            val code = codecMeta.optInt("code", 0).takeIf { it > 0 } ?: return@runCatching null
            val encoder = TrainerKit.Param("encoder.weight", code, inputs)
            val encoderBias = TrainerKit.Param("encoder.bias", code, 1)
            val decoder = TrainerKit.Param("decoder.weight", inputs, code)
            val decoderBias = TrainerKit.Param("decoder.bias", inputs, 1)
            val params = listOf(encoder, encoderBias, decoder, decoderBias)
            if (!TrainerKit.WeightFile.read(file, CODEC_MAGIC, intArrayOf(inputs, code), params)) {
                return@runCatching null
            }
            CoderTrainer.Trained(
                encoder = encoder,
                encoderBias = encoderBias,
                decoder = decoder,
                decoderBias = decoderBias,
                inputs = inputs,
                code = code,
                examples = codecMeta.optInt("examples", 0),
                firstLoss = codecMeta.optDouble("loss_first", 0.0),
                lastLoss = codecMeta.optDouble("loss_last", 0.0),
                steps = 0,
            )
        }.getOrNull()

        val meanSpeaker = readVector(metadata.optJSONArray("mean_speaker"), TtsSignal.SPEAKER_DIMENSIONS)
        val leadSpeaker = metadata.optJSONArray("lead_speaker")?.let { array ->
            readVector(array, TtsSignal.SPEAKER_DIMENSIONS)
        }
        val genderSpeakers = HashMap<String, DoubleArray>()
        metadata.optJSONObject("gender_speakers")?.let { objectJson ->
            GENDERS.forEach { label ->
                objectJson.optJSONArray(label)?.let { array ->
                    genderSpeakers[label] = readVector(array, TtsSignal.SPEAKER_DIMENSIONS)
                }
            }
        }
        val clips = metadata.optJSONArray("languages")?.let { array ->
            (0 until array.length()).map { index -> array.optString(index, "en") }
        } ?: listOf(config.defaultLanguage)
        val languages = clips.ifEmpty { listOf("en") }

        val voiceMeta = metadata.optJSONObject("voice")
        val builtInEngine = when (voiceMeta?.optString("engine", "").orEmpty()) {
            "dataset" -> true
            "pocket-tts" -> false
            else -> null
        }
        val allowPocket = if (voiceMeta?.has("allow_pocket_tts") == true) {
            voiceMeta.optBoolean("allow_pocket_tts", true)
        } else {
            null
        }
        val voiceClips = HashMap<String, String>()
        voiceMeta?.optJSONObject("clips")?.let { clipsJson ->
            val keys = clipsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                clipsJson.optString(key, "").takeIf { it.isNotBlank() }?.let { voiceClips[key] = it }
            }
        }

        return Trained(
            net = net,
            config = config.copy(defaultLanguage = languages.first()),
            directory = directory,
            mel = mel,
            speaker = speaker,
            codec = coder,
            codebook = codebook,
            modelId = metadata.optString("crafted_model_id", ""),
            name = metadata.optString("model", ""),
            task = metadata.optString("audio_task", ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue),
            clips = emptyList(),
            firstLoss = metadata.optDouble("loss_first", 0.0),
            lastLoss = metadata.optDouble("loss_last", 0.0),
            durationLoss = metadata.optDouble("duration_loss", 0.0),
            steps = metadata.optInt("steps_taken", 0),
            epochs = metadata.optInt("epochs", 0),
            requestedSteps = metadata.optInt("steps_requested", 0),
            validationLoss = metadata.optDouble("loss_validation", Double.NaN),
            languagesOverride = languages,
            meanSpeakerOverride = meanSpeaker,
            genderSpeakersOverride = genderSpeakers,
            leadSpeakerOverride = leadSpeaker,
            voiceClipsOverride = voiceClips,
            outputRateOverride = metadata.optInt("output_rate", 0).takeIf { it in 8_000..96_000 },
            codecNormalised = codecNormalised,
            builtInEngineOverride = builtInEngine,
            allowPocketOverride = allowPocket,
        )
    }

    private fun readArray(array: JSONArray?, into: DoubleArray, fallback: Double = 0.0) {
        if (array == null) {
            if (fallback != 0.0) into.fill(fallback)
            return
        }
        for (index in 0 until min(array.length(), into.size)) {
            into[index] = array.optDouble(index, into[index])
        }
    }

    private fun readVector(array: JSONArray?, dimensions: Int): DoubleArray =
        DoubleArray(dimensions) { index -> array?.optDouble(index, 0.0) ?: 0.0 }

    private fun readConfig(recipe: File, model: CraftedModel): Config {
        val json = runCatching { JSONObject(recipe.readText()) }.getOrNull()
        val attention = ModelCraft.attention(model)
        val sliding = json?.optBoolean(ModelCraft.KEY_SLIDING_WINDOW, attention.enabled) ?: attention.enabled
        val window = (json?.optInt(ModelCraft.KEY_SLIDING_WINDOW_SIZE, 0) ?: 0)
            .takeIf { it > 0 } ?: attention.window
        val fullEvery = (json?.optInt(ModelCraft.KEY_FULL_ATTENTION_EVERY_NTH_LAYER, 0) ?: 0)
            .takeIf { it > 0 } ?: attention.fullAttentionEveryNthLayer
        val sampleRate = (json?.optInt("output_sample_rate", 0) ?: 0)
            .takeIf { it in 8_000..48_000 }
            ?: (json?.optInt("sample_rate", 0) ?: 0).takeIf { it in 8_000..48_000 }
            ?: 16_000
        val epochs = (json?.optInt("epochs", 0) ?: 0).takeIf { it > 0 } ?: 10
        val learningRate = json?.optDouble("learning_rate", Double.NaN)?.takeIf { it.isFinite() && it > 0.0 }
        return Config(
            sampleRate = sampleRate,
            window = if (sliding) min(window.coerceAtLeast(1), MAX_TOKENS) else 0,
            fullEvery = fullEvery.coerceAtLeast(1),
            epochs = epochs.coerceIn(1, ModelCraft.MAX_EPOCHS),
            stepsPerEpoch = (json?.optInt(ModelCraft.KEY_STEPS_PER_EPOCH, 0) ?: 0)
                .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH),
            learningRate = (learningRate ?: 1.5e-3).coerceIn(5e-4, 3e-3),
            gradientClip = json?.optDouble("grad_clip", 1.0)?.coerceIn(0.1, 10.0) ?: 1.0,
            seed = json?.optLong("seed", 42L) ?: 42L,
            defaultLanguage = json?.optString("language", "en")?.takeIf { it.isNotBlank() } ?: "en",
            defaultGender = json?.optString("gender", "female")?.takeIf { it.isNotBlank() } ?: "female",
            voiceCount = ModelCraft.voiceCount(model) ?: 1,
            task = ModelCraft.audioType(model).recipeValue,
        )
    }
}
