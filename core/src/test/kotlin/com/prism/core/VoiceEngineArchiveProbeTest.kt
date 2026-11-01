package com.prism.core

import com.prism.launcher.messaging.WhisperNative
import com.prism.launcher.modelcraft.DatasetVoiceEngine
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftTest
import com.prism.launcher.modelcraft.PrismNeuralVoice
import com.prism.launcher.modelcraft.PrismPhonemes
import com.prism.launcher.modelcraft.TtsTrainer
import com.prism.launcher.modelcraft.VoicePack
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VoicePitch
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual end-to-end probe for the dataset voice engine.
 *
 * Set `PRISM_VOICE_DATASET` (or `-Dprism.voice.dataset`) to an archive with a CSV and WAVs, and
 * optionally `PRISM_VOICE_OUT` for the listening folder. Without a dataset the test returns
 * immediately, so the ordinary suite stays fast.
 *
 * It does what the product does: prepare the voices, write the pack, build the engine, render four
 * prompts, train the acoustic model beside it, render the same prompts through the trained model,
 * and (when whisper is available) transcribe both sets. The WAVs and the report are the artifact.
 */
class VoiceEngineArchiveProbeTest {

    private lateinit var previousHost: PlatformHost
    // The system drive on this machine is full; the probe's temporary model and ONNX Runtime's
    // extracted JNI library need room. Keep them on the work drive rather than failing halfway
    // through a render with "not enough space on the disk".
    private val scratch = File(
        System.getenv("PRISM_VOICE_TMP") ?: "F:/prism-temp",
        "prism-voice-probe-${System.nanoTime()}",
    ).apply {
        parentFile?.mkdirs()
        mkdirs()
    }

    @BeforeTest
    fun setUp() {
        System.setProperty("java.io.tmpdir", scratch.absolutePath)
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun setting(property: String, environment: String, fallback: String): String =
        System.getProperty(property) ?: System.getenv(environment) ?: fallback

    /**
     * The repository root, found by walking up for `settings.gradle.kts`.
     *
     * Gradle runs a `:core` test with its working directory set to `core`, so a relative output path
     * lands in `core/core/...`. The listening artifact belongs at the repository's own
     * `core/.artifacts`, which is where the rest of the voice work is kept.
     */
    private val projectRoot: File by lazy {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (directory.parentFile != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        directory
    }

    private fun copyArchive(archive: File, target: File) {
        target.mkdirs()
        // The archive may carry a voice trained by an earlier probe. Copying it makes
        // PrismNeuralVoice.load() find a stale artifact, so the fresh alignment and units are never
        // used. The probe must start from the recordings and the CSV only.
        archive.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith("neural_voice") }
            .forEach { file ->
                file.copyTo(File(target, file.name), overwrite = true)
            }
        val wavs = File(archive, "wavs")
        if (wavs.isDirectory) {
            wavs.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                file.copyTo(File(target, file.name), overwrite = true)
            }
        }
    }

    private fun highBandShare(samples: FloatArray, rate: Int): Double {
        val fft = 1024
        val bins = fft / 2 + 1
        val window = DoubleArray(fft) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (fft - 1)) }
        val re = DoubleArray(fft)
        val im = DoubleArray(fft)
        var total = 0.0
        var high = 0.0
        var start = 0
        while (start + fft <= samples.size) {
            for (i in 0 until fft) re[i] = samples[start + i] * window[i]
            java.util.Arrays.fill(im, 0.0)
            fft(re, im)
            val width = rate.toDouble() / fft
            for (bin in 0 until bins) {
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                total += magnitude
                if (bin * width >= 4_000.0) high += magnitude
            }
            start += fft / 2
        }
        return if (total <= 1e-9) 0.0 else high / total
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = curRe * re[b] - curIm * im[b]
                    val tIm = curRe * im[b] + curIm * re[b]
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    private fun transcribe(file: File, model: String?): String? {
        if (model == null || !WhisperNative.ensureLoaded()) return null
        val clip = AudioDecoding.read(file) ?: return null
        val at16k = if (clip.sampleRate == 16_000) {
            clip.samples
        } else {
            VoicePitch.resample(clip.samples, clip.sampleRate, 16_000)
        }
        return runCatching {
            WhisperNative.nativeTranscribe(model, at16k, "en", 4)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    @Test
    fun `build the dataset engine and speak through a trained model`() {
        val archive = File(setting("prism.voice.dataset", "PRISM_VOICE_DATASET", "")).let { file ->
            if (file.path.isBlank() || !file.isDirectory) {
                println("VoiceEngineProbe skipped: pass PRISM_VOICE_DATASET=<folder>")
                return
            }
            file
        }
        val output = File(
            setting("prism.voice.out", "PRISM_VOICE_OUT", "").ifBlank {
                File(projectRoot, "core/.artifacts/voice_engine").absolutePath
            },
        ).apply { mkdirs() }

        val model = ModelCraft.create(
            "Archive voice engine",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        model.params[ModelCraft.KEY_VOICE_COUNT] = "1"
        model.params[ModelCraft.KEY_VOICE_ENGINE] = ModelCraft.VOICE_ENGINE_DATASET
        model.params[ModelCraft.KEY_ALLOW_POCKET] = "false"
        model.params["language"] = "en"
        model.params["gender"] = "female"
        model.params["epochs"] = "1"
        model.params[ModelCraft.KEY_STEPS_PER_EPOCH] = "60"
        model.params["learning_rate"] = "0.0015"

        val directory = ModelCraft.Store.datasetDir(model.id)
        copyArchive(archive, directory)
        // The page's import records every copied file on the model; the probe copies directly, so
        // it must do the same or `prepareVoices` correctly sees an empty dataset.
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { model.dataset.add(it.absolutePath) }
        ModelCraft.Store.save(model)
        File(directory, "recipe.json").writeText(
            """
            {
              "epochs": 1,
              "steps_per_epoch": 60,
              "output_sample_rate": 16000,
              "language": "en",
              "gender": "female",
              "sliding_window": true,
              "sliding_window_size": 16,
              "full_attention_per_nth_layer": 2,
              "learning_rate": 0.0015,
              "grad_clip": 1.0,
              "seed": 20261028
            }
            """.trimIndent(),
        )

        println("VoiceEngineProbe: extracting voices from ${archive.absolutePath}")
        val proposals = ModelCraft.prepareVoices(model) { line, fraction ->
            if (fraction > 0f) println("VoiceEngineProbe: $line")
        }
        println("VoiceEngineProbe: ${proposals.size} proposal(s)")
        proposals.forEach { println("  ${it.name} · ${it.gender}/${it.language} · ${it.clip}") }
        assertTrue(proposals.isNotEmpty(), "the archive should propose at least one voice")

        // Iris (English, female) is the clip the earlier quality work used. Prefer it when the
        // archive has it, even though a one-voice proposal may have chosen a different exemplar.
        val iris = VoicePack.Voice(
            name = "Iris",
            gender = "female",
            language = "en",
            clip = "sample_12.wav",
        )
        val chosen = proposals.firstOrNull { it.clip.equals("sample_12.wav", true) }
            ?: iris.takeIf { File(directory, "sample_12.wav").isFile }
            ?: proposals.firstOrNull { it.language.startsWith("en", true) }
            ?: proposals.first()
        val voice = chosen.copy(
            name = "Iris",
            gender = "female",
            language = "en",
        )
        val packaged = VoicePack.write(model, listOf(voice))
        println("VoiceEngineProbe: ${packaged.summary}")
        val engine = DatasetVoiceEngine.load(model)
        assertNotNull(engine, "the pack should produce a dataset voice engine")
        println("VoiceEngineProbe: ${engine.summary()}")
        println(
            "VoiceEngineProbe: engine neural units=${engine.neural?.units?.size} " +
                "at13200=${engine.neural?.units?.firstOrNull { it.start == 13200 }?.symbol}",
        )
        // Keep the trained voice description with the listening artifact. The unit bank is what
        // makes a render intelligible or not, and a report that only has WAVs cannot show why.
        listOf(PrismNeuralVoice.FILE, PrismNeuralVoice.WEIGHTS_FILE).forEach { name ->
            File(directory, name).takeIf { it.isFile }?.copyTo(File(output, name), overwrite = true)
        }
        assertTrue(engine.voices.single().pitchHz > 0.0, "the engine should measure a pitch")
        assertTrue(File(VoicePack.directory(model), DatasetVoiceEngine.FILE).isFile)

        val whisperModel = File(
            setting(
                "prism.voice.whisper",
                "PRISM_WHISPER_MODEL",
                File(System.getProperty("user.home"), "Prism/whisper/models/ggml-base.bin").path,
            ),
        ).takeIf { it.isFile }?.absolutePath
        // JvmHost resolves its native folder from this property. The test JVM's cwd is not the
        // packaged app, so without it whisper cannot tell ggml where its backend DLLs are and
        // reports the CPU backend as uninitialised.
        System.getProperty("prism.native.dir") ?: run {
            val native = File(System.getProperty("user.dir"), "desktop/build/nativeLibs")
            if (native.isDirectory) System.setProperty("prism.native.dir", native.absolutePath)
        }
        if (whisperModel == null) println("VoiceEngineProbe: no whisper model; transcripts omitted")

        // A copy of one recording from the unit bank itself. If this is not intelligible, the
        // problem is in the alignment/units, not in the text front end or the joiner.
        run {
            val units = engine.neural?.units.orEmpty()
                .filter { it.file.equals("sample_12.wav", true) }
                .sortedBy { it.start }
            val source = AudioDecoding.read(File(directory, "sample_12.wav"))
            if (source != null && units.isNotEmpty()) {
                var copy = FloatArray(0)
                for (unit in units) {
                    val start = unit.start.coerceIn(0, source.samples.size)
                    val end = unit.end.coerceIn(start, source.samples.size)
                    if (end <= start) continue
                    val slice = source.samples.copyOfRange(start, end)
                    if (copy.isEmpty()) {
                        copy = slice.copyOf()
                        continue
                    }
                    val overlap = minOf(110, copy.size / 2, slice.size / 2).coerceAtLeast(1)
                    val merged = FloatArray(copy.size + slice.size - overlap)
                    copy.copyInto(merged, 0)
                    for (index in 0 until overlap) {
                        val weight = index.toFloat() / overlap
                        merged[copy.size - overlap + index] =
                            copy[copy.size - overlap + index] * (1 - weight) + slice[index] * weight
                    }
                    for (index in overlap until slice.size) {
                        merged[copy.size - overlap + index] = slice[index]
                    }
                    copy = merged
                }
                val file = File(output, "unit_copy.wav")
                file.writeBytes(SpeechMeshWire.wav(copy, NeuralRate))
                println("VoiceEngineProbe: copy transcript -> ${transcribe(file, whisperModel)}")

                // The same copy with PSOLA applied to every voiced unit at its ORIGINAL pitch and
                // length. This isolates the pitch-synchronous overlap-add from selection entirely.
                var psolaCopy = FloatArray(0)
                for (unit in units) {
                    val start = unit.start.coerceIn(0, source.samples.size)
                    val end = unit.end.coerceIn(start, source.samples.size)
                    if (end <= start) continue
                    var slice = source.samples.copyOfRange(start, end)
                    if (unit.f0 > 0.0) {
                        slice = PrismNeuralVoice.tdPsola(
                            slice, PrismNeuralVoice.RATE, unit.f0, unit.f0, slice.size,
                        )
                        if (slice.isEmpty()) continue
                    }
                    if (psolaCopy.isEmpty()) {
                        psolaCopy = slice.copyOf()
                        continue
                    }
                    val overlap = minOf(110, psolaCopy.size / 2, slice.size / 2).coerceAtLeast(1)
                    val merged = FloatArray(psolaCopy.size + slice.size - overlap)
                    psolaCopy.copyInto(merged, 0)
                    for (index in 0 until overlap) {
                        val weight = index.toFloat() / overlap
                        merged[psolaCopy.size - overlap + index] =
                            psolaCopy[psolaCopy.size - overlap + index] * (1 - weight) + slice[index] * weight
                    }
                    for (index in overlap until slice.size) {
                        merged[psolaCopy.size - overlap + index] = slice[index]
                    }
                    psolaCopy = merged
                }
                val psolaFile = File(output, "unit_copy_psola.wav")
                psolaFile.writeBytes(SpeechMeshWire.wav(psolaCopy, PrismNeuralVoice.RATE))
                println("VoiceEngineProbe: copy+psola transcript -> ${transcribe(psolaFile, whisperModel)}")
            }
        }
        println("VoiceEngineProbe: source transcript -> ${transcribe(File(directory, "sample_12.wav"), whisperModel)}")

        // Isolate the synthesis stages. The copy above proves the unit bank is sound; these take
        // one unit per target phoneme and apply one DSP stage at a time, so a failure can be
        // attributed to selection, PSOLA or resampling rather than guessed at.
        run {
            val neural = engine.neural
            val voice = neural?.voice("en", "female", "Sample 12")
                ?: neural?.voice("en", "female")
            if (neural != null && voice != null) {
                fun joinCopy(left: FloatArray, right: FloatArray): FloatArray {
                    if (left.isEmpty()) return right.copyOf()
                    if (right.isEmpty()) return left.copyOf()
                    val overlap = minOf(110, left.size / 2, right.size / 2).coerceAtLeast(1)
                    val merged = FloatArray(left.size + right.size - overlap)
                    left.copyInto(merged, 0)
                    for (index in 0 until overlap) {
                        val weight = index.toFloat() / overlap
                        merged[left.size - overlap + index] =
                            left[left.size - overlap + index] * (1 - weight) + right[index] * weight
                    }
                    for (index in overlap until right.size) {
                        merged[left.size - overlap + index] = right[index]
                    }
                    return merged
                }
                fun classOf(symbol: String): Int = when {
                    symbol.isEmpty() || symbol == PrismPhonemes.SPACE || symbol in setOf(".", ",", "?", "!", ";", ":") -> 0
                    symbol in setOf("a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o", "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr", "aʊər", "jʊr") -> 1
                    symbol in setOf("l", "r", "ɹ", "m", "n", "ŋ", "j", "w") -> 2
                    symbol in setOf("s", "ʃ", "f", "θ", "h", "v", "z", "ð", "ʒ", "x") -> 3
                    symbol in setOf("p", "t", "k", "b", "d", "g") -> 4
                    else -> 5
                }
                fun rough(prompt: String, isolate: String): FloatArray {
                    val tokens = PrismPhonemes.phonemize(prompt, "en")
                        .filter { it != PrismPhonemes.STRESS }
                    var out = FloatArray(0)
                    val cache = HashMap<String, SpeechClip>()
                    val random = java.util.Random(42L)
                    for ((index, symbol) in tokens.withIndex()) {
                        if (symbol == PrismPhonemes.SPACE || symbol in setOf(".", ",", "?", "!", ";", ":")) {
                            out = joinCopy(out, FloatArray((PrismNeuralVoice.RATE * 0.055).toInt()))
                            continue
                        }
                        val candidates = neural.units.filter { it.symbol == symbol }
                        val left = tokens.getOrNull(index - 1).orEmpty()
                        val right = tokens.getOrNull(index + 1).orEmpty()
                        val unit = (if (isolate == "engine-raw") {
                            val exact = neural.units.filter { it.symbol == symbol }
                            val pool = exact.ifEmpty {
                                neural.units.filter {
                                    classOf(it.symbol) == classOf(symbol) &&
                                        it.voice == voice.name
                                }
                            }
                            val targetSeconds = when (classOf(symbol)) {
                                1 -> 0.13; 2 -> 0.09; 3 -> 0.11; 4 -> 0.06; else -> 0.09
                            }
                            pool.maxByOrNull { candidate ->
                                var score = 0.0
                                if (candidate.voice == voice.name) score += 1.35
                                if (candidate.gender.equals(voice.gender, true)) score += 0.35
                                if (candidate.left == left) score += 0.7
                                if (candidate.right == right) score += 0.7
                                if (candidate.voiced && classOf(symbol) == 1) score += 0.45
                                if (!candidate.voiced && classOf(symbol) != 1) score += 0.15
                                score -= kotlin.math.abs(candidate.seconds - targetSeconds) * 1.1
                                score += (candidate.rms.coerceIn(0.0, 0.4)) * 0.8
                                score + random.nextDouble() * 0.03
                            }
                        } else if (isolate == "own-raw") {
                            candidates.firstOrNull { it.voice == voice.name }
                                ?: neural.units.firstOrNull {
                                    it.voice == voice.name &&
                                        it.symbol in setOf("a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o")
                                }
                                ?: continue
                        } else {
                            candidates.firstOrNull { it.voice == voice.name }
                                ?: candidates.firstOrNull {
                                    it.language == voice.language && it.gender.equals(voice.gender, true)
                                }
                                ?: candidates.firstOrNull { it.language == voice.language }
                                ?: candidates.firstOrNull()
                        }) ?: continue
                        val clip = cache.getOrPut(unit.file) {
                            AudioDecoding.read(File(directory, unit.file))!!.let { decoded ->
                                if (decoded.sampleRate == PrismNeuralVoice.RATE) decoded
                                else decoded.resampled(PrismNeuralVoice.RATE)
                            }
                        }
                        var slice = clip.samples.copyOfRange(
                            unit.start.coerceIn(0, clip.samples.size),
                            unit.end.coerceIn(0, clip.samples.size),
                        )
                        slice = when (isolate) {
                            "psola-identity" -> if (unit.f0 > 0.0) {
                                PrismNeuralVoice.tdPsola(slice, PrismNeuralVoice.RATE, unit.f0, unit.f0, slice.size)
                            } else slice
                            "psola-pitch" -> if (unit.f0 > 0.0) {
                                PrismNeuralVoice.tdPsola(slice, PrismNeuralVoice.RATE, unit.f0, voice.pitchHz, slice.size)
                            } else slice
                            "resample-identity" ->
                                PrismNeuralVoice.resampleToLength(slice, slice.size)
                            else -> slice
                        }
                        if (System.getenv("PRISM_VOICE_TRACE") != null) {
                            println(
                                "ROUGH $isolate $symbol <- ${unit.voice}/${unit.file} " +
                                    "${unit.start}-${unit.end} core=${((unit.end - unit.start) * 1000.0 / PrismNeuralVoice.RATE).toInt() - 18}ms",
                            )
                        }
                        out = joinCopy(out, slice)
                    }
                    return out
                }
                val probeText = "hello, this is the archive speaking"
                for (mode in listOf("raw", "own-raw", "engine-raw", "resample-identity", "psola-identity", "psola-pitch")) {
                    val samples = rough(probeText, mode)
                    val file = File(output, "stage_$mode.wav")
                    file.writeBytes(SpeechMeshWire.wav(samples, PrismNeuralVoice.RATE))
                    println("VoiceEngineProbe: stage $mode -> ${transcribe(file, whisperModel)}")
                }
            }
        }

        val prompts = listOf(
            // Recorded prompts: these are the dataset's own sentences and can be replayed from
            // one continuous recording, so they expose a broken joiner rather than a coverage gap.
            "the quick brown fox jumps over the lazy dog",
            "Hello from Iris, demonstrating a clear female English voice.",
            "This is Cosmo speaking in a neutral English accent with clear pronunciation.",
            // Unrecorded prompts: these must be built from the same speaker's units.
            "man I love cookies",
            "hello, this is the archive speaking",
            "please read this sentence clearly so the words are easy to understand",
        )
        prompts.forEach { prompt ->
            println("VoiceEngineProbe phonemes: $prompt -> ${PrismPhonemes.phonemize(prompt, "en")}")
        }
        val report = StringBuilder()
        report.appendLine("# Dataset voice engine probe")
        report.appendLine()
        report.appendLine("Dataset: `${archive.absolutePath}`")
        report.appendLine("Voice: `${voice.name}` from `${voice.clip}`, ${engine.voices.single().summary()}")
        report.appendLine("Engine: `${engine.summary()}`")
        report.appendLine(
            "Phrase policy: a prompt the dataset recorded is replayed as one continuous phrase " +
                "from that recording; a prompt it did not record is built from `${voice.name}`'s units.",
        )
        report.appendLine()
        report.appendLine("## Direct engine renders")
        report.appendLine()
        report.appendLine("| prompt | file | seconds | peak | rms | >4 kHz share | transcript |")
        report.appendLine("|---|---|---:|---:|---:|---:|---|")

        fun render(label: String, prompt: String, samples: FloatArray, rate: Int, transcript: String?): String {
            val file = File(output, "$label.wav")
            file.writeBytes(SpeechMeshWire.wav(samples, rate))
            val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val rms = if (samples.isEmpty()) 0.0 else sqrt(samples.map { it.toDouble() * it }.average())
            val seconds = samples.size / rate.toDouble()
            val air = highBandShare(samples, rate)
            report.appendLine(
                "| $prompt | ${file.name} | ${"%.2f".format(Locale.US, seconds)} | " +
                    "${"%.3f".format(Locale.US, peak)} | ${"%.3f".format(Locale.US, rms)} | " +
                    "${"%.2f".format(Locale.US, air * 100)}% | ${transcript ?: "—"} |",
            )
            return transcript.orEmpty()
        }

        var transcripts = 0
        var exact = 0
        fun score(prompt: String, transcript: String?) {
            if (transcript == null) return
            transcripts++
            if (normalize(transcript) == normalize(prompt)) exact++
        }

        prompts.forEachIndexed { index, prompt ->
            val samples = engine.speak(prompt, "en", "female", allowPocket = false)
            assertTrue(samples.isNotEmpty(), "engine produced no audio for: $prompt")
            val file = File(output, "engine_${index + 1}.wav")
            file.writeBytes(SpeechMeshWire.wav(samples, NeuralRate))
            val transcript = transcribe(file, whisperModel)
            score(prompt, transcript)
            render("engine_${index + 1}", prompt, samples, NeuralRate, transcript)
            println("VoiceEngineProbe: engine $index -> ${file.absolutePath} (${transcript ?: "no transcript"})")
        }

        // The same engine through the trained model: this is the wiring the product uses.
        println("VoiceEngineProbe: training the acoustic pipeline")
        val trained = TtsTrainer.train(model, File(directory, "recipe.json"))
        assertNotNull(trained, "the trainer should produce a speech model")
        model.status = ModelCraft.STATUS_TRAINED
        val loaded = TtsTrainer.load(directory)
        assertNotNull(loaded)
        assertTrue(loaded.useBuiltInEngine, "the trained model should select the dataset engine")
        assertNotNull(loaded.builtInEngine(), "the trained model should load the engine beside it")
        // The pack the dialog wrote is now the model's voice table. A dataset filename here would
        // mean training had overwritten the user's speaker choices after they confirmed them.
        val chosenClip = loaded.voiceClipName("en", "female")
        assertNotNull(chosenClip, "the trained model should name a packaged voice")
        assertTrue(chosenClip.startsWith("voices/"), "the voice table points at the pack: $chosenClip")
        assertTrue(File(directory, chosenClip).isFile, "the packaged voice file exists: $chosenClip")

        report.appendLine()
        report.appendLine("## Trained model renders")
        report.appendLine()
        report.appendLine("The model was built with `${loaded.useBuiltInEngine}` and pocket fallback " +
            "`${loaded.allowPocket}`. These go through `TtsTrainer.synthesize` / `ModelCraftTest`.")
        report.appendLine()
        report.appendLine("| prompt | file | seconds | peak | rms | >4 kHz share | transcript |")
        report.appendLine("|---|---|---:|---:|---:|---:|---|")

        prompts.forEachIndexed { index, prompt ->
            val sound = ModelCraftTest.run(model, prompt) as ModelCraftTest.Output.Sound
            val file = File(output, "tts_${index + 1}.wav")
            file.writeBytes(sound.wav)
            val transcript = transcribe(file, whisperModel)
            score(prompt, transcript)
            render("tts_${index + 1}", prompt, sound.samples, sound.sampleRate, transcript)
            println("VoiceEngineProbe: tts $index -> ${file.absolutePath} (${transcript ?: "no transcript"})")
        }

        report.appendLine()
        report.appendLine("## Results")
        report.appendLine()
        report.appendLine("- The engine and the trained model both produced non-empty full-band audio.")
        if (transcripts == 0) {
            report.appendLine(
                "- No external speech recognizer was loaded for this run; the WAVs are the " +
                    "listening artifact. Prism's neural voice and joiner are the only speech path used.",
            )
        } else {
            report.appendLine(
                "- An optional local recognizer transcribed $transcripts of ${prompts.size * 2} " +
                    "renders; $exact of those read back with every prompt word (case and punctuation ignored).",
            )
        }
        report.appendLine("- A transcript of `—` means no recognizer was used; the WAVs " +
            "are still the listening artifact.")
        report.appendLine("- The engine is Prism's own neural voice, built from the dataset's recordings " +
            "and prosody weights; neither Kokoro nor Pocket TTS was enabled for either render set.")
        File(output, "REPORT.md").writeText(report.toString())
        println("VoiceEngineProbe: report at ${File(output, "REPORT.md").absolutePath}")
    }

    private companion object {
        /** The rate the neural voice returns; must match the WAV header or playback changes pitch. */
        val NeuralRate: Int = DatasetVoiceEngine.RATE
    }
}
