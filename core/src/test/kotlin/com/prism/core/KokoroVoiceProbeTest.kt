package com.prism.core

import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.KokoroEngine
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.speech.SeedVcEngine
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VoicePitch
import com.prism.launcher.speech.VoicePolish
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Manual probe: the same sentence spoken by Kokoro and re-voiced toward a dataset reference.
 *
 * PocketTTS speaks directly in the reference voice, but it reproduces the recording's bandwidth
 * too. Kokoro's prosody plus a conversion engine is the other route Prism already ships, so this
 * writes both paths beside each other for a listener to compare. It is skipped unless the
 * `prism.kokoro.reference` / `PRISM_KOKORO_REFERENCE` property names a real clip.
 */
class KokoroVoiceProbeTest {

    private lateinit var previousHost: PlatformHost

    private val reference: File by lazy {
        val path = System.getProperty("prism.kokoro.reference")
            ?: System.getenv("PRISM_KOKORO_REFERENCE")
            ?: ""
        File(path)
    }

    private val output: File by lazy {
        File(
            System.getProperty("prism.kokoro.outdir")
                ?: System.getenv("PRISM_KOKORO_OUTDIR")
                ?: "build/kokoro-probe",
        ).apply { mkdirs() }
    }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(
            rootOverride = File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                "Prism",
            ),
        )
    }

    @AfterTest
    fun cleanUp() {
        runCatching { SeedVcEngine.close() }
        PrismPlatform.host = previousHost
    }

    @Test
    fun `speak through Kokoro and Seed-VC`() {
        if (!reference.isFile) {
            println("KokoroVoiceProbe skipped: set PRISM_KOKORO_REFERENCE=<clip.wav>")
            return
        }
        val clip = AudioDecoding.read(reference) ?: error("Could not decode ${reference.absolutePath}")
        val referenceAt22k = clip.resampled(22_050).samples

        val model = listOf("model.onnx", "model_quantized.onnx", "model_fp16.onnx")
            .map { File(KokoroInstall.homeDir(), it) }
            .firstOrNull { it.isFile }
            ?: run {
                println("KokoroVoiceProbe skipped: no Kokoro model in ${KokoroInstall.homeDir()}")
                return
            }
        val kokoro = KokoroEngine.openOrNull(model.absolutePath) ?: run {
            println("KokoroVoiceProbe skipped: Kokoro would not open $model")
            return
        }

        val prompts = listOf(
            "man I love cookies",
            "the quick brown fox jumps over the lazy dog",
            "hello, this is the archive speaking",
            "The quick brown fox jumps over the lazy dog in this English sample.",
        )
        for ((index, prompt) in prompts.withIndex()) {
            val spoken = runCatching { kokoro.synthesizeToPcm(prompt, "af_heart", 1f) }
                .onFailure { println("KokoroVoiceProbe: Kokoro failed on \"$prompt\": ${it.message}") }
                .getOrNull() ?: continue
            File(output, "kokoro_raw_${index + 1}.wav")
                .writeBytes(SpeechMeshWire.wav(spoken.samples, spoken.sampleRate))
            val converted = runCatching {
                SeedVcEngine.convert("archive-kokoro", spoken.samples, spoken.sampleRate, referenceAt22k)
            }.onFailure {
                println("KokoroVoiceProbe: Seed-VC failed on \"$prompt\": ${it.message}")
            }.getOrNull() ?: continue
            File(output, "seedvc_raw_${index + 1}.wav")
                .writeBytes(SpeechMeshWire.wav(converted.samples, converted.sampleRate))
            val mastered = VoicePolish.master(converted.samples, converted.sampleRate)
            val target = File(output, "kokoro_${index + 1}.wav")
            target.writeBytes(SpeechMeshWire.wav(mastered, converted.sampleRate))
            println(
                "KokoroVoiceProbe: ${target.absolutePath}  \"$prompt\"  " +
                    "%.2fs".format(mastered.size / converted.sampleRate.toDouble()),
            )
        }
        File(output, "prompts.txt").writeText(prompts.joinToString("\n"))
    }
}
