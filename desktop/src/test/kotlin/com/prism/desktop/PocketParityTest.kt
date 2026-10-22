package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import com.prism.launcher.speech.CloneEngines
import com.prism.launcher.speech.PocketTtsEngine
import com.prism.launcher.speech.VoiceCloningModels
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * Runs PocketTtsEngine (the real on-device code path) against the bundle's published graphs on
 * the desktop JVM, with the golden artifacts the python wrapper produced on a 22050 Hz
 * reference at temperature 0. The AR loop can pull apart after small resampler differences;
 * the numbers below say how much actually transferred.
 */
class PocketParityTest {

    companion object {
        const val POCKET = "C:/Users/bremo/AppData/Local/Temp/opencode/pocket"
        const val BUNDLE = "$POCKET/english_2026-04"
        const val GOLDEN = "$POCKET/golden22050"
        const val TEXT = "Hello, this is a test of voice cloning from a short reference."
        val GOLDEN_IDS = intArrayOf(
            2994, 262, 285, 277, 267, 1115, 272, 1483, 331, 2101, 273, 340, 267, 1165, 1936, 599, 263,
        )

        @BeforeClass
        @JvmStatic
        fun stage() {
            val root = Files.createTempDirectory("pocketparity").toFile()
            PrismPlatform.host = JvmHost(rootOverride = root)
            val models = File(root, "models/voice-cloning")
            models.mkdirs()
            for (m in PocketTtsEngine.models) {
                // The ONNX graphs live in the bundle directory; the release assets land at root.
                val from = File(BUNDLE, m.file).takeIf { it.isFile } ?: File(POCKET, m.file)
                check(from.isFile) { "missing parity asset ${m.file}" }
                File(models, m.file).writeBytes(from.readBytes())
            }
            PocketTtsEngine.temperatureForTest = 0f
        }

        @AfterClass
        @JvmStatic
        fun close() {
            PocketTtsEngine.temperatureForTest = -1f
            CloneEngines.ALL.forEach { runCatching { it.close() } }
        }

        fun readFloats(path: File): FloatArray {
            val bytes = path.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { bb.getFloat(it * 4) }
        }

        fun rms(a: FloatArray): Double = sqrt(a.map { it * it }.average())

        fun corr(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0.0
            val ma = a.take(n).average() ?: 0.0
            val mb = b.take(n).average() ?: 0.0
            var nab = 0.0; var aa = 0.0; var bb2 = 0.0
            for (i in 0 until n) {
                val da = a[i] - ma; val db = b[i] - mb
                nab += da * db; aa += da * da; bb2 += db * db
            }
            return if (aa == 0.0 || bb2 == 0.0) 0.0 else nab / sqrt(aa * bb2)
        }

        /** The wrapper's prompt normalisation -- asserts itself by producing the EXPECTED input. */
        fun preparedPrompt(text: String): String {
            var t = text.trim()
            if (t.isEmpty()) return t
            t = t.replace("\n", " ").replace("\r", " ").replace("  ", " ")
            if (!t[0].isUpperCase()) t = t[0].uppercase() + t.substring(1)
            if (t.last().isLetterOrDigit()) t += "."
            return t
        }
    }

    /** Tokenizer parity: the prepared text's ids must be exactly the golden ones. */
    @Test
    fun tokenizerMatchesGolden() {
        val tokenizer = com.prism.launcher.speech.PocketTokenizer.load(
            VoiceCloningModels.fileFor(PocketTtsEngine.VOCAB),
        ) ?: error("the staged tokenizer failed to load")
        val ids = tokenizer.encode(preparedPrompt(TEXT))
        println("ids ${ids.joinToString()}")
        check(ids.size == GOLDEN_IDS.size) { "id count ${ids.size} vs golden ${GOLDEN_IDS.size}" }
        for (i in ids.indices) check(ids[i] == GOLDEN_IDS[i]) {
            "id $i is ${ids[i]}, golden ${GOLDEN_IDS[i]}"
        }
    }

    /**
     * Full generation on the actual engine: a 22050 reference through the engine's own linear
     * resample vs the wrapper's identical-path run on the same resampled audio (the resampler
     * difference against the wrapper's polyphase path pulls the AR loop about a frame by the
     * end, which is why the reference golden lives in latents/audio_linear.f32).
     */
    @Test
    fun generationMatchesGolden() {
        val reference = readFloats(File(POCKET, "ref22050.f32"))
        val goldenAudio = readFloats(File(GOLDEN, "audio_linear.f32"))
        println("golden audio ${goldenAudio.size}")

        val spoken = PocketTtsEngine.speakReference(TEXT, "parity", reference)
        val audio = spoken.samples
        check(spoken.sampleRate == 24_000)
        println("audio ${audio.size} vs golden ${goldenAudio.size}, " +
            "corr ${corr(audio, goldenAudio)}, rms ${rms(audio)} vs ${rms(goldenAudio)}")
        check(audio.size == goldenAudio.size) { "length ${audio.size} vs golden ${goldenAudio.size}" }
        check(rms(audio) > 0.005) { "output rms ${rms(audio)} is silence" }
        check(corr(audio, goldenAudio) > 0.8) { "corr ${corr(audio, goldenAudio)} below voice parity" }
    }
}
