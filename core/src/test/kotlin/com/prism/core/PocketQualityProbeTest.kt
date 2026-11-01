package com.prism.core

import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.PocketTtsEngine
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VoicePitch
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A manual probe, not part of the ordinary suite.
 *
 * Renders the same sentence in the same reference voice across flow-integration step counts and
 * temperatures, so the spectrum of each variant can be compared. Pass
 * `-Dprism.pocket.reference=<clip.wav>` and `-Dprism.pocket.outdir=<dir>` (or the matching
 * PRISM_POCKET_* environment variables); without a reference the test returns immediately.
 */
class PocketQualityProbeTest {

    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        val data = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Prism")
        PrismPlatform.host = JvmHost(rootOverride = data)
    }

    @AfterTest
    fun cleanUp() {
        PocketTtsEngine.stepsForTest = -1
        PocketTtsEngine.temperatureForTest = -1f
        PrismPlatform.host = previousHost
        PocketTtsEngine.close()
    }

    private fun setting(property: String, environment: String, fallback: String): String =
        System.getProperty(property) ?: System.getenv(environment) ?: fallback

    @Test
    fun `compare flow steps and temperatures`() {
        val reference = setting("prism.pocket.reference", "PRISM_POCKET_REFERENCE", "")
            .let(::File)
            .takeIf { it.isFile }
            ?: run {
                println("PocketQualityProbe skipped: set PRISM_POCKET_REFERENCE=<clip.wav>")
                return
            }
        val text = setting("prism.pocket.text", "PRISM_POCKET_TEXT", "Man, I love cookies.")
        val outDir = File(
            setting(
                "prism.pocket.outdir",
                "PRISM_POCKET_OUTDIR",
                "build/pocket-quality",
            )
        )
        outDir.mkdirs()

        val decoded = AudioDecoding.read(reference) ?: error("Could not decode $reference")
        val at22k = VoicePitch.resample(decoded.samples, decoded.sampleRate, 22_050)
        val conditioned = com.prism.launcher.speech.VoicePolish.conditionReference(at22k, 22_050)
        File(outDir, "conditioned.wav").writeBytes(SpeechMeshWire.wav(conditioned, 22_050))

        for (temperature in listOf(0.7f, 0.3f, 0f)) {
            for (steps in listOf(1, 2, 4, 8)) {
                PocketTtsEngine.stepsForTest = steps
                PocketTtsEngine.temperatureForTest = temperature
                val started = System.nanoTime()
                val converted = PocketTtsEngine.speakReference(text, "quality", at22k)
                val out = File(outDir, "t${(temperature * 10).toInt()}_s$steps.wav")
                out.writeBytes(SpeechMeshWire.wav(converted.samples, converted.sampleRate))
                println(
                    "PocketQualityProbe: t=$temperature steps=$steps -> ${out.name} " +
                        "(${"%.2f".format(converted.samples.size / converted.sampleRate.toDouble())} s, " +
                        "${"%.2f".format((System.nanoTime() - started) / 1e9)} s of work)",
                )
            }
        }
    }
}
