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
 * Pass `-Dprism.pocket.reference=<clip.wav>` and `-Dprism.pocket.text=<sentence>` to hear what the
 * installed Pocket TTS models say in that clip's voice, written to `-Dprism.pocket.out=<file.wav>`.
 * Without the reference property the test returns immediately.
 */
class PocketReferenceProbeTest {

    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        // The installed engine models live in the real data directory; this probe only reads them.
        val data = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Prism")
        PrismPlatform.host = JvmHost(rootOverride = data)
    }

    @AfterTest
    fun cleanUp() {
        PrismPlatform.host = previousHost
        PocketTtsEngine.close()
    }

    private fun setting(property: String, environment: String, fallback: String): String =
        System.getProperty(property) ?: System.getenv(environment) ?: fallback

    @Test
    fun `speak a prompt in a reference clip's voice`() {
        val reference = setting("prism.pocket.reference", "PRISM_POCKET_REFERENCE", "")
            .let(::File)
            .takeIf { it.isFile }
            ?: run {
                println("PocketReferenceProbe skipped: set PRISM_POCKET_REFERENCE=<clip.wav>")
                return
            }
        val text = setting("prism.pocket.text", "PRISM_POCKET_TEXT", "Man, I love cookies.")
        val out = File(setting("prism.pocket.out", "PRISM_POCKET_OUT", "pocket_probe.wav"))

        val decoded = AudioDecoding.read(reference) ?: error("Could not decode $reference")
        val at22k = VoicePitch.resample(decoded.samples, decoded.sampleRate, 22_050)
        out.parentFile?.mkdirs()
        // Raw first: what the model itself emits, with no conditioning and no mastering. The
        // difference between this and the polished take is the whole contribution of VoicePolish,
        // which is impossible to judge from the finished file alone.
        PocketTtsEngine.polishForTest = false
        val raw = PocketTtsEngine.speakReference(text, "probe-raw", at22k)
        File(out.parentFile, out.nameWithoutExtension + "_raw.wav")
            .writeBytes(SpeechMeshWire.wav(raw.samples, raw.sampleRate))

        PocketTtsEngine.polishForTest = true
        val started = System.nanoTime()
        val converted = PocketTtsEngine.speakReference(text, "probe", at22k)
        out.writeBytes(SpeechMeshWire.wav(converted.samples, converted.sampleRate))
        val seconds = converted.samples.size / converted.sampleRate.toDouble()
        println(
            "PocketReferenceProbe: \"$text\" -> ${out.absolutePath} " +
                "(${"%.2f".format(seconds)} s, ${"%.2f".format((System.nanoTime() - started) / 1e9)} s of work)",
        )
    }
}
