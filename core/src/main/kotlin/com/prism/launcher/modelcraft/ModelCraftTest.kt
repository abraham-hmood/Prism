package com.prism.launcher.modelcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.assetcraft.MeshCamera
import com.prism.launcher.assetcraft.MeshNode
import com.prism.launcher.assetcraft.MeshScene
import com.prism.launcher.assetcraft.MeshView
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VocalAnalysis
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Trying a built model on something the user types.
 *
 * ## What "testing" means for each kind
 *
 * Every trainer here can be asked a question once it has weights, and the answer is different for
 * each kind because the models are different. This object hides that difference from the two pages:
 * they hand it a model and the text in the box, and get back one thing to show -- text, a sequence of
 * pictures, or a sound with a waveform drawn for it.
 *
 *  - **Text** continues the prompt, byte by byte, with the model's own greedy decoder.
 *  - **Image** either integrates the flow field from noise toward the picture the caption describes
 *    (diffusion families) or reconstructs a hidden patch of one of the dataset's own images
 *    (the masked-patch families). The prompt conditions the picture when the dataset carried
 *    captions; without them it only chooses the starting noise.
 *  - **Audio** speaks the typed text in the voice the dataset taught: a pretrained neural
 *    text-to-speech engine conditioned on the dataset's cleanest recording (the model's reference
 *    clip for a cloning build). The small acoustic network is not asked to free-run a new
 *    sentence -- it cannot -- it is the miniature renderer behind its own checkbox, and there is
 *    no speech model at all when the dataset had no transcripts.
 *  - **Frame interpolation** takes two frames from the dataset and shows the model's middle frame
 *    between them.
 *  - **Video** takes the first two frames of a clip and asks the model to continue, frame by frame.
 *
 * ## The input box is a seed as well as a prompt
 *
 * The text model reads the words, and so does an image model built on a captioned dataset. For the
 * others the string is hashed into the generator's seed, which chooses the noise, the dataset image
 * or clip, and (for audio) a pitch offset. That is said on the page rather than implied, so "type
 * words into a video model" is not mistaken for conditioning it does not have.
 *
 * ## Where the work belongs
 *
 * This is blocking and allocates: callers run it off the UI thread. It reads artifacts the trainer
 * wrote beside the dataset, so a model trained on another machine and brought here can be tested as
 * long as its files came with it.
 */
object ModelCraftTest {

    /** One picture, in ARGB rows, already scaled up so a 24-pixel model is visible. */
    class Picture(val width: Int, val height: Int, val argb: IntArray)

    /** What a run produced, plus the sentence saying how it was made. */
    sealed class Output {
        abstract val caption: String

        class Text(val text: String, override val caption: String) : Output()

        /** One or more pictures to show; [fps] above 0 means the page should animate them. */
        class Film(val frames: List<Picture>, val fps: Int, override val caption: String) : Output()

        class Sound(
            val samples: FloatArray,
            val sampleRate: Int,
            /** A playable RIFF/WAVE wrapper, for a player that takes a file rather than samples. */
            val wav: ByteArray,
            val waveform: Picture,
            override val caption: String,
        ) : Output()
    }

    /** True when this model has been trained and therefore has something to test. */
    fun ready(model: CraftedModel): Boolean = model.status == ModelCraft.STATUS_TRAINED

    /** The label over the box, per kind. */
    fun promptLabel(kind: ModelCraft.Kind): String = when (kind) {
        ModelCraft.Kind.TEXT -> "Prompt"
        ModelCraft.Kind.AUDIO -> "Text to speak"
        ModelCraft.Kind.GAME -> "Asset prompt"
        ModelCraft.Kind.IMAGE -> "Prompt"
        else -> "Seed"
    }

    /** What the box does, said before the user types into it. */
    fun promptHint(kind: ModelCraft.Kind): String = when (kind) {
        ModelCraft.Kind.TEXT ->
            "The text the model continues. Blank starts from the model's own name."
        ModelCraft.Kind.IMAGE ->
            "What the picture should be. A model trained on a captioned dataset conditions on these " +
                "words; one trained without captions only uses them to pick the starting noise, " +
                "so the same words still give the same picture."
        ModelCraft.Kind.AUDIO ->
            "The sentence to say, or — for a music model — the instruments and mood to compose " +
                "with. A text-to-speech model reads it in the voice of the dataset's cleanest " +
                "recording; a cloning model reads it in the voice of its reference clip. Blank uses " +
                "the model's own fields."
        ModelCraft.Kind.INTERPOLATION ->
            "Any words. They pick which clip's frames are interpolated."
        ModelCraft.Kind.VIDEO ->
            "Any words. They pick which clip the animation continues from."
        ModelCraft.Kind.GAME ->
            "What the asset is. The words pick the category and condition the predicted shape; " +
                "the model was trained on the dataset's own meshes."
    }

    /**
     * Runs the model and returns what to show.
     *
     * @throws IllegalStateException with a sentence fit for the page when the model has no
     *         artifacts this kind knows how to read.
     */
    fun run(model: CraftedModel, input: String, voice: String? = null): Output {
        if (!ready(model)) {
            throw IllegalStateException("Train the model before testing it.")
        }
        val directory = ModelCraft.Store.datasetDir(model.id)
        val prompt = input.trim()
        val seed = seedOf(if (prompt.isEmpty()) model.name else prompt)
        return when (model.kind) {
            ModelCraft.Kind.TEXT -> text(model, directory, prompt)
            ModelCraft.Kind.AUDIO -> sound(model, directory, prompt, seed, voice)
            ModelCraft.Kind.IMAGE -> image(model, directory, prompt, seed)
            ModelCraft.Kind.INTERPOLATION -> interpolation(model, prompt, seed)
            ModelCraft.Kind.VIDEO -> video(model, prompt, seed)
            ModelCraft.Kind.GAME -> game(model, prompt, seed)
        }
    }

    // ── Text ───────────────────────────────────────────────────────────────────────────────────

    private fun text(model: CraftedModel, directory: File, prompt: String): Output {
        val trained = TextTrainer.load(directory)
            ?: throw IllegalStateException(
                "The text model's weights are not in ${directory.absolutePath}.",
            )
        val start = prompt.ifBlank { model.name.ifBlank { "Prism" } }
        val answer = trained.respond(start, TEXT_LENGTH)
        val readable = answer.map { character ->
            if (character.code in 32..126 || character == '\n') character else '·'
        }.joinToString("")
        val text = if (trained.config.dialogue) {
            "User: $start\nAssistant: $readable"
        } else {
            start + readable
        }
        return Output.Text(
            text = text,
            caption = "${if (trained.tokenizer.byteMode) "Byte" else "Word"}-level generation · " +
                "${trained.config.positions}-token context · " +
                "${trained.net.parameterCount()} parameters · " +
                "${trained.tokenizer.size} vocabulary",
        )
    }

    // ── Audio ──────────────────────────────────────────────────────────────────────────────────

    private fun sound(
        model: CraftedModel,
        directory: File,
        prompt: String,
        seed: Long,
        voice: String? = null,
    ): Output {
        val task = ModelCraft.audioType(model)
        if (task == ModelCraft.AudioType.MUSIC) {
            val music = MusicTrainer.load(directory)
                ?: throw IllegalStateException(
                    "The music model's weights are not in ${directory.absolutePath}.",
                )
            val seconds = model.param("music_seconds").trim().toIntOrNull()?.coerceIn(1, 30) ?: 4
            val description = prompt.ifBlank { model.param("instruments").ifBlank { "music" } }
            val samples = music.generate(description, seconds, seed)
            if (samples.isEmpty()) {
                throw IllegalStateException("The music model produced no audio for this prompt.")
            }
            return Output.Sound(
                samples = samples,
                sampleRate = music.sampleRate,
                wav = SpeechMeshWire.wav(samples, music.sampleRate),
                waveform = waveform(samples),
                caption = "“$description” · ${seconds}s from ${music.centroids.size} learned timbres · " +
                    "${music.clipCount} clip(s) trained · ${music.parameters} parameters",
            )
        }
        val language = model.param("language").ifBlank { "en" }
        val gender = model.param("gender").ifBlank { "female" }
        val text = prompt.ifBlank { DEFAULT_SPEECH }
        // A cloning model speaks in its reference recording; choosing one of the pack's voices
        // would contradict what the model is. For every other speech build the selected pack row
        // is what the test should demonstrate.
        val chosen = if (task != ModelCraft.AudioType.VOICE_CLONING && !voice.isNullOrBlank()) {
            VoicePack.packaged(model).firstOrNull {
                it.name.equals(voice, true) || it.file.equals(voice, true)
            }
        } else {
            null
        }

        // THE SPEECH MODEL FIRST. A model trained from clips with transcripts has a full TTS
        // pipeline beside the acoustic one, and that is what should answer the box -- typed text in
        // the learned voice, or in the reference recording's voice for a clone.
        val speech = TtsTrainer.load(directory)
        if (speech != null) {
            val reference = if (task == ModelCraft.AudioType.VOICE_CLONING) {
                referenceClip(model, directory)
            } else {
                null
            }
            if (task == ModelCraft.AudioType.VOICE_CLONING && reference == null) {
                throw IllegalStateException(
                    "This is a cloning model, but its reference recording is missing. Put the clip " +
                        "back in ${directory.absolutePath}, or point \"Speaker reference clip\" at it.",
                )
            }
            val samples = speech.synthesize(
                text = text,
                language = language,
                gender = gender,
                reference = reference,
                voiceName = chosen?.name,
            )
            if (samples.isEmpty()) {
                throw IllegalStateException("The speech model produced no audio for this sentence.")
            }
            return Output.Sound(
                samples = samples,
                sampleRate = speech.outputRate,
                wav = SpeechMeshWire.wav(samples, speech.outputRate),
                waveform = waveform(samples),
                caption = buildString {
                    append("\"")
                    append(text.take(60))
                    if (text.length > 60) append("…")
                    append("\" · ")
                    append(
                        when {
                            reference != null -> "clone of the reference voice"
                            chosen != null -> "voice of ${chosen.name}"
                            else -> "voice of ${speech.voiceClipName(language, gender) ?: "the dataset"}"
                        },
                    )
                    append(" · ${chosen?.language?.ifBlank { language } ?: language} · ")
                    append("${ModelCapacity.count(speech.net.parameterCount().toLong())} parameters · ")
                    append("${speech.outputRate / 1000} kHz · ")
                    append(
                        if (speech.useBuiltInEngine) {
                            speech.builtInEngine()?.summary()?.substringAfter('·')?.trim()
                                ?: "Prism neural voice (not built yet)"
                        } else {
                            "Pocket TTS neural voice"
                        },
                    )
                },
            )
        }

        if (!ModelCraft.miniatureFallback(model)) {
            // NOT "none of its clips had a transcript": at this point all that is known is that no
            // speech model was SAVED here (TtsTrainer.load found no tts_model.json). The training run
            // may never have been made, may have failed for an unrelated reason, or may have found
            // the words in a column this build could not read. Reporting what the trainer sees is
            // accurate; asserting the CSV is empty when a person can see sentences in it is not.
            val seen = runCatching { TtsTrainer.inspect(directory) }.getOrDefault("")
            throw IllegalStateException(
                "No text-to-speech model was saved for this model, so there is nothing to speak " +
                    "with yet. Training reads words from a transcript or description column (or a " +
                    ".txt beside the clip) in ${directory.absolutePath}" +
                    (if (seen.isBlank()) "." else ", where it now sees $seen.") +
                    " Retrain the model; if it still saves no speech model, the ModelCraft log names " +
                    "the pass that stopped. Or tick \"Use the miniature voice model\" to hear the " +
                    "acoustic predictor's render.",
            )
        }

        // The miniature renderer: the acoustic model's predicted frames, made audible. Only reached
        // when the user ticks the box, because it does not say the words.
        val trained = AcousticTrainer.load(directory)
            ?: throw IllegalStateException(
                "The acoustic model's weights are not in ${directory.absolutePath}.",
            )
        val conditioning = trained.normalisation.conditioningMean.copyOf()
        val pitch = ((seed ushr 3) % 41 - 20).toDouble()
        val loudness = ((seed ushr 17) % 9 - 4).toDouble()
        if (conditioning.isNotEmpty()) conditioning[0] += pitch
        if (conditioning.size > 16) conditioning[16] += loudness
        val neutral = List(FRAMES) { neutralFrame() }
        val predicted = trained.predict(conditioning, neutral)
        if (predicted.isEmpty()) {
            throw IllegalStateException("The acoustic model produced no frames for this seed.")
        }
        val rate = 16_000
        val samples = render(predicted, rate, (pitch / 6.0))
        return Output.Sound(
            samples = samples,
            sampleRate = rate,
            wav = SpeechMeshWire.wav(samples, rate),
            waveform = waveform(samples),
            caption = "${predicted.size} predicted acoustic frames " +
                "(${"%.1f".format(predicted.size / 100.0)} s at a 10 ms hop) · the miniature " +
                "acoustic renderer, which does not speak the words",
        )
    }

    /**
     * The clip a cloning model conditions on: the path on the form when it is set, otherwise the
     * cleanest clip the audio-model document recorded when the dataset was measured.
     */
    private fun referenceClip(model: CraftedModel, directory: File): SpeechClip? {
        val raw = model.param("speaker_reference").trim()
        val file = when {
            raw.isBlank() -> {
                val document = runCatching {
                    com.prism.core.json.JSONObject(File(directory, CraftedAudioModel.FILE).readText())
                }.getOrNull()
                val name = document?.optString("reference_clip", "").orEmpty()
                if (name.isBlank()) null else File(directory, File(name).name)
            }
            File(raw).isAbsolute -> File(raw)
            else -> File(directory, raw)
        }
        if (file == null || !file.isFile) return null
        return AudioDecoding.read(file)
    }

    private const val DEFAULT_SPEECH =
        "Hello, this is Prism speaking with a voice trained on this dataset."

    /**
     * A neutral frame to start the acoustic model from.
     *
     * The model normalises against the frames it is given, so a series of identical neutral frames
     * presents it with zeros -- "here is an ordinary voiced frame" -- and every bit of movement in
     * the output is the model's own. The eight band energies are the middle of the frame's bands,
     * not silences, because a band at zero would be an absent measurement rather than a quiet one.
     */
    private fun neutralFrame(): VocalAnalysis.FrameSample = VocalAnalysis.FrameSample(
        time = 0.0,
        f0 = 120.0,
        db = -24.0,
        centroid = 1_200.0,
        rolloff = 2_500.0,
        flatness = 0.2,
        bands = FloatArray(AcousticTrainer.TARGETS - 5) { 0.01f },
    )

    /**
     * Turns a predicted acoustic trajectory into a waveform.
     *
     * The thirteen values are the ones an acoustic front end measures: pitch relative to the clip's
     * median, loudness relative to the same median, centroid, rolloff, flatness and eight band
     * energies. A source-filter voice can be approximated from the first two without a vocoder --
     * a harmonic source at the predicted f0, shaped by the predicted brightness and roughness -- and
     * that is what this does. It is deliberately simple and deliberately says "rendered": the model
     * predicts acoustics, and a real vocoder is what would turn them into a human voice.
     */
    private fun render(frames: List<DoubleArray>, sampleRate: Int, pitchShiftOctaves: Double): FloatArray {
        val hop = sampleRate / 100
        val samples = FloatArray(frames.size * hop)
        val base = 120.0 * 2.0.pow(pitchShiftOctaves.coerceIn(-0.5, 0.5))
        var phase = 0.0
        for ((frameIndex, frame) in frames.withIndex()) {
            val semitones = frame.getOrElse(0) { 0.0 }.coerceIn(-24.0, 24.0)
            val f0 = (base * 2.0.pow(semitones / 12.0)).coerceIn(50.0, 500.0)
            val decibels = frame.getOrElse(1) { -10.0 }.coerceIn(-30.0, 6.0)
            val amplitude = (0.20 * 10.0.pow(decibels / 20.0)).coerceIn(0.008, 0.5)
            val brightness = frame.getOrElse(2) { 0.4 }.coerceIn(0.0, 1.5)
            val roughness = frame.getOrElse(4) { 0.2 }.coerceIn(0.0, 1.0)
            val edge = min(frameIndex, frames.size - 1 - frameIndex)
            val envelope = when {
                edge <= 0 -> 0.15
                edge == 1 -> 0.55
                else -> 1.0
            }
            for (offset in 0 until hop) {
                phase += f0 / sampleRate
                phase -= floor(phase)
                var value = 0.0
                var weight = 1.0
                for (harmonic in 1..6) {
                    if (harmonic > 1) {
                        weight = (brightness / harmonic.toDouble().pow(1.3)).coerceAtLeast(0.01)
                    }
                    value += weight * sin(2.0 * PI * harmonic * phase)
                }
                // A little breath for the flat frames, scaled by the flatness the model predicted:
                // a perfectly periodic source is a buzzer, not a voice.
                val breath = (hashNoise(frameIndex * hop + offset, sampleRate) * roughness * 0.08)
                samples[frameIndex * hop + offset] =
                    (value * amplitude * envelope * 0.55 + breath).toFloat()
            }
        }
        // One global fade, so a clip never starts or stops with a click.
        val fade = min(sampleRate / 50, samples.size / 2)
        for (index in 0 until fade) {
            val gain = index.toFloat() / fade
            samples[index] *= gain
            samples[samples.size - 1 - index] *= gain
        }
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0.95f) {
            val scale = 0.95f / peak
            for (index in samples.indices) samples[index] *= scale
        }
        return samples
    }

    /** Deterministic noise in [-1, 1] from an index, so a render is repeatable. */
    private fun hashNoise(index: Int, salt: Int): Double {
        var value = index * 1_103_515_245 + salt * 12_345
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        return (value % 20_001) / 10_000.0 - 1.0
    }

    // ── Image ──────────────────────────────────────────────────────────────────────────────────

    private fun image(model: CraftedModel, directory: File, prompt: String, seed: Long): Output {
        val trained = PixelTrainer.load(directory, PixelTrainer.Task.IMAGE)
            ?: throw IllegalStateException(
                "The image model's weights are not in ${directory.absolutePath}.",
            )
        val side = trained.config.side
        val random = java.util.Random(seed)
        val description = prompt.ifBlank { "an image" }
        if (trained.diffusion) {
            // FOUR SEEDS, AVERAGED. A small denoiser trained on a few thousand pictures puts most
            // of its residual error into per-seed speckle: the subject is what the seeds agree on,
            // and the noise is what they do not. Averaging four at forty steps cost about the same
            // as one at a hundred and sixty, and on the FFHQ pass it was the difference between a
            // coloured cloud and a face with eyes, a nose and a mouth. The caption says that is what
            // happened; the image is not a single sample dressed up as one.
            val pixels = DoubleArray(3 * side * side)
            val seeds = 4
            for (sample in 0 until seeds) {
                val one = trained.generate(
                    description,
                    java.util.Random(seed + sample * 104_729L),
                    PixelTrainer.SAMPLE_STEPS,
                )
                for (index in pixels.indices) pixels[index] += one[index] / seeds
            }
            return Output.Film(
                frames = listOf(picture(pixels, side)),
                fps = 0,
                caption = if (trained.conditional) {
                    "“$description” · $seeds-seed average of ${PixelTrainer.SAMPLE_STEPS} flow steps " +
                        "with guidance · ${side}×${side} pixels · " +
                        "${trained.net.parameterCount()} parameters"
                } else {
                    "$seeds-seed average sampled from noise with ${PixelTrainer.SAMPLE_STEPS} flow " +
                        "steps · ${side}×${side} pixels · ${trained.net.parameterCount()} parameters · " +
                        "the dataset had no captions, so the prompt chose the noise"
                },
            )
        }
        // A masked-patch model was trained to put a square back, so the test hides one and shows
        // what it reconstructed. A dataset image makes that a comparison with something real; a
        // random one keeps the test working when the dataset was not carried along.
        val clean = datasetImage(directory, side, seed) ?: randomPixels(side, random)
        val plane = side * side
        val channels = trained.net.channels
        val source = if (channels == 1) DoubleArray(plane) { clean[it] } else clean
        val patched = hidePatch(source, side, seed)
        val predicted = trained.predictWithCaption(patched, description)
        val output = if (channels == 1) {
            DoubleArray(3 * plane) { predicted[it % plane] }
        } else {
            predicted
        }
        return Output.Film(
            frames = listOf(picture(output, side)),
            fps = 0,
            caption = "Reconstructed a hidden patch for “$description” · ${side}×${side} pixels · " +
                "${trained.net.parameterCount()} parameters",
        )
    }

    private fun randomPixels(side: Int, random: java.util.Random): DoubleArray =
        DoubleArray(3 * side * side) { random.nextGaussian().coerceIn(-1.0, 1.0) }

    /** The first image in the dataset's own folder, chosen by the seed; null when there is none. */
    private fun datasetImage(directory: File, side: Int, seed: Long): DoubleArray? {
        val files = buildList {
            val root = directory.listFiles().orEmpty()
            addAll(root)
            for (child in root) if (child.isDirectory) addAll(child.listFiles().orEmpty())
        }.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.path }
        if (files.isEmpty()) return null
        val chosen = files[abs(seed).mod(files.size.toLong()).toInt()]
        val decoded = PrismPlatform.images.decode(chosen, side) ?: return null
        return pixelsOf(decoded, side)
    }

    private fun pixelsOf(image: PrismImage, side: Int): DoubleArray {
        val scaled = image.scaledTo(side, side)
        val plane = side * side
        return DoubleArray(3 * plane) { index ->
            val pixel = scaled.pixels[index % plane]
            val channel = when (index / plane) {
                0 -> PrismImage.red(pixel)
                1 -> PrismImage.green(pixel)
                else -> PrismImage.blue(pixel)
            }
            channel / 127.5 - 1.0
        }
    }

    /** Hides the same size patch the trainer did, with its mask plane set. */
    private fun hidePatch(clean: DoubleArray, side: Int, seed: Long): DoubleArray {
        val plane = side * side
        val patch = max(1, side / 4)
        val picker = java.util.Random(seed)
        val left = picker.nextInt(side - patch + 1)
        val top = picker.nextInt(side - patch + 1)
        val input = DoubleArray(clean.size + plane)
        System.arraycopy(clean, 0, input, 0, clean.size)
        for (row in top until top + patch) {
            for (column in left until left + patch) {
                val index = row * side + column
                for (channel in 0 until 3) input[channel * plane + index] = 0.0
                input[clean.size + index] = 1.0
            }
        }
        return input
    }

    // ── Frame interpolation ───────────────────────────────────────────────────────────────────

    private fun interpolation(model: CraftedModel, prompt: String, seed: Long): Output {
        val trained = PixelTrainer.load(ModelCraft.Store.datasetDir(model.id), PixelTrainer.Task.INTERPOLATION)
            ?: throw IllegalStateException(
                "The frame model's weights are not in ${ModelCraft.Store.datasetDir(model.id)}.",
            )
        val side = trained.config.side
        val frames = datasetFrames(model, side, seed)
        val left = frames.getOrNull(0) ?: randomPixels(side, java.util.Random(seed))
        val right = frames.getOrNull(2) ?: frames.getOrNull(1)
            ?: randomPixels(side, java.util.Random(seed xor 0x5EED))
        val middle = trained.interpolate(left, right)
        return Output.Film(
            frames = listOf(picture(left, side), picture(middle, side), picture(right, side)),
            fps = 2,
            caption = "Middle frame predicted between two frames of the dataset · ${side}×${side} " +
                "pixels · ${trained.net.parameterCount()} parameters",
        )
    }

    // ── Game ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Samples the game model and renders the mesh it predicted.
     *
     * The shape is the model's; the viewport is the same one AssetCraft uses. That is deliberate:
     * a test that drew a different picture from the editor's own would let a model pass here and
     * look broken where it is actually used.
     */
    private fun game(model: CraftedModel, prompt: String, seed: Long): Output {
        val trained = GameTrainer.load(model)
            ?: throw IllegalStateException(
                "The game model's weights are not in ${ModelCraft.Store.datasetDir(model.id)}. " +
                    "Train it on meshes the trainer can read (.obj, .ply or .glb) and try again.",
            )
        val mesh = trained.sample(prompt, seed)
        val scene = MeshScene().apply { add(MeshNode(mesh)) }
        val side = 256
        val pixels = MeshView.render(
            scene = scene,
            width = side,
            height = side,
            camera = MeshCamera(yaw = 0.7f, pitch = 0.32f, distance = 4.4f),
            time = 0.8f,
        )
        val window = if (trained.slidingWindow) " · sliding window of ${trained.window} tokens" else ""
        return Output.Film(
            frames = listOf(Picture(side, side, pixels)),
            fps = 0,
            caption = "Sampled a ${mesh.category} with ${mesh.triangleCount} triangles · " +
                "${trained.parameterCount()} parameters · ${trained.objective()}$window",
        )
    }

    // ── Video ─────────────────────────────────────────────────────────────────────────────────

    private fun video(model: CraftedModel, prompt: String, seed: Long): Output {
        val trained = PixelTrainer.load(ModelCraft.Store.datasetDir(model.id), PixelTrainer.Task.VIDEO)
            ?: throw IllegalStateException(
                "The video model's weights are not in ${ModelCraft.Store.datasetDir(model.id)}.",
            )
        val side = trained.config.side
        val source = datasetFrames(model, side, seed)
        val start = if (source.size >= PixelTrainer.PREVIOUS_FRAMES) {
            source.take(PixelTrainer.PREVIOUS_FRAMES)
        } else {
            listOf(
                randomPixels(side, java.util.Random(seed)),
                randomPixels(side, java.util.Random(seed xor 0x5EED)),
            ).take(PixelTrainer.PREVIOUS_FRAMES)
        }
        val generated = start.toMutableList()
        repeat(VIDEO_FRAMES) {
            generated += trained.nextFrame(generated.takeLast(PixelTrainer.PREVIOUS_FRAMES))
        }
        return Output.Film(
            frames = generated.map { picture(it, side) },
            fps = 8,
            caption = "$VIDEO_FRAMES frames predicted after the dataset's first two · ${side}×${side} " +
                "pixels · ${trained.net.parameterCount()} parameters",
        )
    }

    /** Frames of one clip, chosen by the seed, at the model's own resolution. */
    private fun datasetFrames(model: CraftedModel, side: Int, seed: Long): List<DoubleArray> {
        val root = ModelCraft.Store.framesDir(model.id)
        val folders = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (folders.isEmpty()) return emptyList()
        val folder = folders[abs(seed).mod(folders.size.toLong()).toInt()]
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            ?.take(24)
            ?.mapNotNull { file -> PrismPlatform.images.decode(file, side)?.let { pixelsOf(it, side) } }
            .orEmpty()
    }

    // ── Pictures ──────────────────────────────────────────────────────────────────────────────

    /** Three channelled planes of [-1, 1] as an ARGB picture; the page scales it up to show it. */
    private fun picture(pixels: DoubleArray, side: Int, scale: Int = 1): Picture {
        val width = side * scale
        val plane = side * side
        val argb = IntArray(width * width)
        for (row in 0 until width) {
            for (column in 0 until width) {
                val sourceRow = row / scale
                val sourceColumn = column / scale
                val index = sourceRow * side + sourceColumn
                val red = channel(pixels.getOrZero(index))
                val green = channel(pixels.getOrZero(plane + index))
                val blue = channel(pixels.getOrZero(2 * plane + index))
                argb[row * width + column] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Picture(width, width, argb)
    }

    /** A waveform drawn as a picture: one vertical line per column, in a colour that reads on both themes. */
    private fun waveform(samples: FloatArray): Picture {
        val width = 320
        val height = 72
        val middle = height / 2
        val argb = IntArray(width * height)
        val line = 0xFF4DA3FF.toInt()
        for (column in 0 until width) {
            val from = (column.toLong() * samples.size / width).toInt().coerceIn(0, samples.size - 1)
            val to = (((column + 1).toLong() * samples.size) / width).toInt()
                .coerceIn(from + 1, samples.size)
            var low = 0f
            var high = 0f
            for (index in from until to) {
                low = min(low, samples[index])
                high = max(high, samples[index])
            }
            val top = (middle - high * (height / 2 - 2)).toInt().coerceIn(0, height - 1)
            val bottom = (middle - low * (height / 2 - 2)).toInt().coerceIn(0, height - 1)
            for (row in min(top, bottom)..max(top, bottom)) {
                argb[row * width + column] = line
            }
        }
        return Picture(width, height, argb)
    }

    private fun channel(value: Double): Int =
        (((value.coerceIn(-1.0, 1.0) + 1.0) * 127.5).toInt()).coerceIn(0, 255)

    private fun DoubleArray.getOrZero(index: Int): Double =
        if (index in indices) this[index] else 0.0

    /** A stable seed from the box, so the same words give the same test on both platforms. */
    private fun seedOf(text: String): Long =
        text.fold(1_125_899_906_842_597L) { seed, character -> seed * 31 + character.code }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    /** Characters of continuation a text test shows. Long enough to judge, short enough to read. */
    private const val TEXT_LENGTH = 320

    /** Acoustic frames a sound test predicts: one second at the front end's 10 ms hop. */
    private const val FRAMES = 100

    /** Frames a video test predicts after the two it starts from. */
    private const val VIDEO_FRAMES = 16
}
