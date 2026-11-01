package com.prism.core

import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftTest
import com.prism.launcher.modelcraft.TtsSignal
import com.prism.launcher.modelcraft.TtsTrainer
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VoiceCloningModels
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A manual probe, not part of the ordinary suite.
 *
 * Set `-Dprism.tts.dataset=<folder>` to train on a real archive and `-Dprism.tts.out=<folder>` to
 * keep the rendered WAVs. Without the dataset property the test returns immediately.
 *
 * The point is to hear what the product ships, not to assert on synthetic vowels: the archive's own
 * CSV supplies the words, the model is trained exactly as the page trains it, and every prompt is
 * rendered through [ModelCraftTest] -- the same path the Test button uses. The WAVs are the artifact
 * a person can listen to.
 *
 * The neural engine's files are copied into this profile from the machine's real Prism data folder
 * (`PRISM_TTS_ENGINE` to override), because the test deliberately does not read or write the real
 * profile. Without them every render fails with the install sentence, which is itself checked.
 */
class TtsArchiveProbeTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-tts-archive-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
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
     * Copies the installed Pocket TTS files into this profile.
     *
     * A copy, not a link: the test must be able to delete its profile afterwards, and the source is
     * the same engine every other part of Prism uses. Missing source files are fine -- the render
     * then fails with the sentence this probe prints, which is what a fresh machine looks like.
     */
    private fun stageEngine() {
        val source = File(
            setting("prism.tts.engine", "PRISM_TTS_ENGINE", "").ifBlank {
                File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Prism")
                    .resolve("models/voice-cloning")
                    .path
            },
        )
        if (!source.isDirectory) {
            println("TtsArchiveProbe: no engine folder at ${source.absolutePath}")
            return
        }
        for (model in com.prism.launcher.speech.PocketTtsEngine.models +
            com.prism.launcher.speech.SeedVcEngine.models) {
            val target = VoiceCloningModels.fileFor(model)
            if (target.isFile && target.length() == model.sizeBytes) continue
            val installed = File(source, model.file)
            if (!installed.isFile) continue
            target.parentFile?.mkdirs()
            installed.copyTo(target, overwrite = true)
        }
        // Kokoro is the clearer route's first half. The probe's profile is deliberately isolated,
        // so the real install has to be staged here too or the renderer silently falls back to
        // Pocket TTS and the comparison measures the wrong engine.
        val kokoroSource = File(source.parentFile, "kokoro")
        val kokoroTarget = com.prism.launcher.speech.KokoroInstall.homeDir()
        if (kokoroSource.isDirectory) {
            kokoroSource.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                val target = File(kokoroTarget, file.name)
                if (target.isFile && target.length() == file.length()) return@forEach
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
            val voices = File(kokoroSource, "voices")
            if (voices.isDirectory) {
                val targetVoices = com.prism.launcher.speech.KokoroInstall.voicesDir().apply { mkdirs() }
                voices.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    val target = File(targetVoices, file.name)
                    if (target.isFile && target.length() == file.length()) return@forEach
                    file.copyTo(target, overwrite = true)
                }
            }
        }
    }

    @Test
    fun `train the archive and render the probe prompts`() {
        val archive = setting("prism.tts.dataset", "PRISM_TTS_DATASET", "").let(::File)
            .takeIf { it.isDirectory }
            ?: run {
                println("TtsArchiveProbe skipped: pass -Dprism.tts.dataset=<folder>")
                return
            }
        val output = setting("prism.tts.out", "PRISM_TTS_OUT", "").let(::File)
            .takeIf { it.path.isNotBlank() }
            ?: File(scratch, "out").apply { mkdirs() }
        output.mkdirs()
        stageEngine()

        val model = ModelCraft.create(
            "Archive Probe",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        if (setting("prism.tts.scratch", "PRISM_TTS_SCRATCH", "0") in setOf("1", "true", "yes")) {
            ModelCraft.setFromScratch(model, true)
        }
        model.params["language"] = "en"
        model.params["gender"] = "female"
        model.params["epochs"] = "1"
        model.params[ModelCraft.KEY_STEPS_PER_EPOCH] =
            setting("prism.tts.steps", "PRISM_TTS_STEPS", "600")
        model.params["learning_rate"] = setting("prism.tts.lr", "PRISM_TTS_LR", "0.0015")

        val directory = ModelCraft.Store.datasetDir(model.id)
        archive.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            file.copyTo(File(directory, file.name), overwrite = true)
        }
        File(directory, "recipe.json").writeText(
            """
            {
              "epochs": 1,
              "steps_per_epoch": ${setting("prism.tts.steps", "PRISM_TTS_STEPS", "600")},
              "output_sample_rate": 16000,
              "language": "en",
              "gender": "female",
              "sliding_window": true,
              "sliding_window_size": 16,
              "full_attention_per_nth_layer": 2,
              "learning_rate": ${setting("prism.tts.lr", "PRISM_TTS_LR", "0.0015")},
              "grad_clip": 1.0,
              "seed": 20261028
            }
            """.trimIndent(),
        )

        println("TtsArchiveProbe: ${TtsTrainer.inspect(directory)}")
        val started = System.currentTimeMillis()
        val trained = TtsTrainer.train(model, File(directory, "recipe.json"))
        if (trained == null) {
            error("TtsArchiveProbe: the trainer produced no model for ${directory.absolutePath}")
        }
        println(
            "TtsArchiveProbe: ${trained.net.parameterCount()} params, ${trained.steps} steps, " +
                "loss ${trained.firstLoss} -> ${trained.lastLoss}, " +
                "validation ${trained.validationLoss}, codec ${trained.codec?.lastLoss}, " +
                "${System.currentTimeMillis() - started} ms",
        )
        println("TtsArchiveProbe: voices ${trained.voiceClips}")
        model.status = ModelCraft.STATUS_TRAINED

        // Evidence for why the scratch network is not the renderer: teacher-forced frames of a
        // training clip are the clip; free-running frames of a new sentence are not. Both files are
        // kept beside the spoken prompts so the difference is audible.
        run {
            val clip = trained.clips.firstOrNull { it.name == "sample_01.wav" }
            if (clip != null) {
                val frames = trained.net.reconstruct(clip, trained.mel, trained.speaker)
                val audio = TtsSignal.vocode(
                    frames.map { trained.mel.denormalise(it) },
                    trained.config.signal,
                )
                File(output, "acoustic_teacher_clip.wav")
                    .writeBytes(SpeechMeshWire.wav(audio, trained.config.sampleRate))
                val fresh = trained.synthesizeAcoustic("man I love cookies", "en", "female")
                File(output, "acoustic_freerun_prompt.wav")
                    .writeBytes(SpeechMeshWire.wav(fresh, trained.config.sampleRate))
                println("TtsArchiveProbe: acoustic renders written for comparison")
            }
        }

        // The production path, exactly as the Test button calls it.
        val prompts = listOf(
            "man I love cookies",
            "the quick brown fox jumps over the lazy dog",
            "hello, this is the archive speaking",
            "The quick brown fox jumps over the lazy dog in this English sample.",
        )
        for ((index, prompt) in prompts.withIndex()) {
            val sound = try {
                ModelCraftTest.run(model, prompt) as ModelCraftTest.Output.Sound
            } catch (error: IllegalStateException) {
                println("TtsArchiveProbe: \\\"$prompt\\\" failed: ${error.message}")
                continue
            }
            val file = File(output, "prompt_${index + 1}.wav")
            file.writeBytes(sound.wav)
            val seconds = sound.samples.size / sound.sampleRate.toDouble()
            println(
                "TtsArchiveProbe: ${file.absolutePath}  \\\"$prompt\\\"  " +
                    "${"%.2f".format(seconds)}s  ${sound.caption}",
            )
        }
        File(output, "prompts.txt").writeText(prompts.joinToString("\n"))
    }
}
