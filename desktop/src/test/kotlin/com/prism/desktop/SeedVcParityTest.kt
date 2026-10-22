package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import com.prism.launcher.speech.CloneEngines
import com.prism.launcher.speech.SeedVcEngine
import com.prism.launcher.speech.VoicePitch
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * Runs the actual on-device engine code (SeedVcEngine) against the published int8 graphs on the
 * desktop JVM, with the exact audio pair the export pipeline was validated on. This is the line
 * of investigation for the on-device "clicks" failure: if this harness produces clean speech while
 * the phone produces clicks, the bug is in the Android ONNX Runtime build; if it produces clicks
 * too, the engine's own plumbing is broken and the per-stage bisect against the golden tensors
 * pins down which graph or which piece of glue is wrong.
 */
class SeedVcParityTest {

    companion object {
        const val SEED_OUT = "C:/Users/bremo/AppData/Local/Temp/opencode/seed-out"
        const val PARITY = "C:/Users/bremo/AppData/Local/Temp/opencode/parity"
        val waveOut = File(PARITY, "kotlin_wave.f32")

        @BeforeClass
        @JvmStatic
        fun stage() {
            val root = Files.createTempDirectory("seedparity").toFile()
            PrismPlatform.host = JvmHost(rootOverride = root)
            val models = File(root, "models/voice-cloning")
            models.mkdirs()
            for (m in SeedVcEngine.models) {
                File(models, m.file).writeBytes(File(SEED_OUT, m.file).readBytes())
            }
        }

        @AfterClass
        @JvmStatic
        fun close() {
            CloneEngines.ALL.forEach { runCatching { it.close() } }
        }

        fun readFloats(name: String, from: String = PARITY): FloatArray {
            val bytes = File(from, name).readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { bb.getFloat(it * 4) }
        }

        fun rms(a: FloatArray): Double = sqrt(a.map { it * it }.average())

        fun corr(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0.0
            val ma = a.take(n).average() ?: 0.0; val mb = b.take(n).average() ?: 0.0
            var nab = 0.0; var aa = 0.0; var bb2 = 0.0
            for (i in 0 until n) {
                val da = a[i] - ma; val db = b[i] - mb
                nab += da * db; aa += da * da; bb2 += db * db
            }
            return if (aa == 0.0 || bb2 == 0.0) 0.0 else nab / sqrt(aa * bb2)
        }
    }

    /**
     * The buggy run reproduced: full pipeline on desktop, clicking on device. This right now
     * fails with rms 0.014, which is the investigation's headline number.
     */
    @Test
    fun fullPipelineReproduction() {
        val source = readFloats("source22050.f32")
        val reference = readFloats("ref22050.f32")
        val golden = readFloats("wave.f32")
        val converted = SeedVcEngine.convert("parity", source, SeedVcEngine.RATE, reference)
        val wave = converted.samples
        check(converted.sampleRate == SeedVcEngine.RATE)
        check(wave.size == golden.size) { "length ${wave.size} vs golden ${golden.size}" }
        val kotlinRms = rms(wave)
        println("kotlin rms $kotlinRms, golden rms ${rms(golden)}, corr ${corr(wave, golden)}")
        check(kotlinRms > 0.05) { "output rms $kotlinRms is not speech-like (clicks reproduced)" }
    }

    /** Kotlin's linear resampler vs the pipeline's sinc resampler -- informational. */
    @Test
    fun resampleChecks() {
        val src = readFloats("source22050.f32")
        val ref = readFloats("ref22050.f32")
        val src16G = readFloats("src16.f32")
        val ref16G = readFloats("ref16.f32")
        val src16K = VoicePitch.resample(src, SeedVcEngine.RATE, 16_000)
        val ref16K = VoicePitch.resample(ref, SeedVcEngine.RATE, 16_000)
        println("src16 ${src16K.size} (${src16G.size}), corr ${corr(src16K, src16G)}")
        println("ref16 ${ref16K.size} (${ref16G.size}), corr ${corr(ref16K, ref16G)}")
    }

    /**
     * Every stage fed the EXACT golden input the python pipeline produced; the first stage whose
     * output falls off the golden is where the bug lives. Correlations of ~1.0 are expected for
     * every stage as long as some glue or sizing does not corrupt the plumbing.
     */
    @Test
    fun stageBisect() {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val dit = env.createSession(
            File(SEED_OUT, SeedVcEngine.DIT.file).absolutePath,
            ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) },
        )
        val hift = env.createSession(
            File(SEED_OUT, SeedVcEngine.VOCODER.file).absolutePath,
            ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) },
        )
        try {
            // mel helper: golden ref at 22.05k -> (80, 1257)
            val mel = runFlat(env, File(SEED_OUT, SeedVcEngine.MEL.file).absolutePath, 4,
                "wave" to f32(readFloats("ref22050.f32"), longArrayOf(1, 321871)))
            val mel2G = readFloats("mel2.f32")
            println("mel corr ${corr(mel, mel2G)} (size ${mel.size}, golden ${mel2G.size})")

            // content: golden 16k source -> (1, 364, 1024)
            val content = runFlat(env, File(SEED_OUT, SeedVcEngine.CONTENT.file).absolutePath, 4,
                "wave" to f32(readFloats("src16.f32"), longArrayOf(1, 116775)))
            val sAltG = readFloats("S_alt.f32")
            println("content corr ${corr(content, sAltG)} (size ${content.size}, golden ${sAltG.size})")

            // lenreg: golden S_alt, ylens 628 -> (1, 628, 384)
            val lenreg = runFlat(env, File(SEED_OUT, SeedVcEngine.REGULATOR.file).absolutePath, 4,
                "S" to f32(readFloats("S_alt.f32"), longArrayOf(1, 364, 1024)),
                "ylens" to int64(longArrayOf(628), longArrayOf(1)))
            val condG = readFloats("cond.f32")
            println("cond corr ${corr(lenreg, condG)} (size ${lenreg.size}, golden ${condG.size})")

            // speaker: golden 16k reference -> (192)
            val sess = env.createSession(
                File(SEED_OUT, SeedVcEngine.STYLE.file).absolutePath,
                ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) },
            )
            val style = sess.run(mapOf("wave" to f32(readFloats("ref16.f32"), longArrayOf(1, 233558)))).use {
                flatten(it[0].value)
            }
            check(style.size == 192) { "style ${style.size}" }
            val styleG = readFloats("style.f32")
            println("style corr ${corr(style, styleG)}")
            sess.close()

            // DiT: golden z/mel2/pcond/cond/style, eight CFG euler steps
            val vcG = readFloats("vc.f32")
            val vcK = diffuseGolden(env, dit)
            println("vc corr ${corr(vcK, vcG)} (rmsK ${rms(vcK)}, rmsG ${rms(vcG)})")

            // vocoder: golden gen/phase/noise -> wave
            val waveK = hiftRun(env, hift, readFloats("gen.f32"), readFloats("phase.f32"), readFloats("noise.f32"))
            val waveG = readFloats("wave.f32")
            println("hift corr ${corr(waveK, waveG)} (rmsK ${rms(waveK)}, rmsG ${rms(waveG)})")
        } finally {
            runCatching { dit.close() }
            runCatching { hift.close() }
        }
    }

    private fun diffuseGolden(env: ai.onnxruntime.OrtEnvironment, dit: ai.onnxruntime.OrtSession): FloatArray {
        val z = readFloats("z.f32")
        val mel2 = readFloats("mel2.f32")
        val pcond = readFloats("pcond.f32")
        val cond = readFloats("cond.f32")
        val style = readFloats("style.f32")
        val plen = 1257
        val frames = 1885
        val mel = 80 * frames
        val x = z.copyOf()
        for (m in 0 until 80) x.fill(0f, m * frames, m * frames + plen)
        val pBatch = FloatArray(2 * mel)   // row 1 must stay zeros
        for (m in 0 until 80) System.arraycopy(mel2, m * plen, pBatch, m * frames, plen)
        val mu = pcond + cond
        val muBatch = FloatArray(2 * mu.size)
        System.arraycopy(mu, 0, muBatch, 0, mu.size)
        val styleBatch = FloatArray(2 * 192)
        System.arraycopy(style, 0, styleBatch, 0, 192)
        val xBatch = FloatArray(2 * mel)
        var time = 0.0f
        val dt = 1.0f / 8
        repeat(8) {
            System.arraycopy(x, 0, xBatch, 0, mel)
            System.arraycopy(x, 0, xBatch, mel, mel)
            dit.run(mapOf(
                "x" to f32(xBatch, longArrayOf(2, 80, frames.toLong())),
                "prompt_x" to f32(pBatch, longArrayOf(2, 80, frames.toLong())),
                "x_lens" to int64(longArrayOf(frames.toLong()), longArrayOf(1)),
                "t" to f32(floatArrayOf(time, time), longArrayOf(2)),
                "style" to f32(styleBatch, longArrayOf(2, 192)),
                "cond" to f32(muBatch, longArrayOf(2, frames.toLong(), 384)),
            )).use { rr ->
                val dphi = flatten(rr[0].value)
                check(dphi.size == 2 * mel) { "dphi ${dphi.size}" }
                for (i in 0 until mel) {
                    x[i] += dt * (1.7f * dphi[i] - 0.7f * dphi[mel + i])
                }
            }
            time += dt
            for (m in 0 until 80) x.fill(0f, m * frames, m * frames + plen)
        }
        return x
    }

    private fun hiftRun(
        env: ai.onnxruntime.OrtEnvironment,
        hift: ai.onnxruntime.OrtSession,
        gen: FloatArray,
        phase: FloatArray,
        noise: FloatArray,
    ): FloatArray =
        hift.run(mapOf(
            "mel" to f32(gen, longArrayOf(1, 80, 628)),
            "phase" to f32(phase, longArrayOf(1, 9, 1)),
            "noise" to f32(noise, longArrayOf(1, 9, noise.size.toLong() / 9)),
        )).use { flatten(it[0].value) }

    private fun runFlat(
        env: ai.onnxruntime.OrtEnvironment,
        path: String,
        threads: Int,
        vararg inputs: Pair<String, ai.onnxruntime.OnnxTensor>,
    ): FloatArray {
        val s = env.createSession(path, ai.onnxruntime.OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
        })
        try {
            return s.run(inputs.toMap()).use { flatten(it[0].value) }
        } finally {
            runCatching { s.close() }
        }
    }

    private fun f32(a: FloatArray, shape: LongArray): ai.onnxruntime.OnnxTensor =
        ai.onnxruntime.OnnxTensor.createTensor(env0(), java.nio.FloatBuffer.wrap(a), shape)

    private fun int64(v: LongArray, shape: LongArray): ai.onnxruntime.OnnxTensor =
        ai.onnxruntime.OnnxTensor.createTensor(env0(), java.nio.LongBuffer.wrap(v), shape)

    private fun env0() = ai.onnxruntime.OrtEnvironment.getEnvironment()

    private fun flatten(value: Any?): FloatArray = when (value) {
        is DoubleArray -> FloatArray(value.size) { i -> value[i].toFloat() }
        is FloatArray -> value
        is Array<*> -> flattenNested(value)
        else -> throw IllegalStateException("type ${value?.javaClass}")
    }

    private fun flattenNested(value: Array<*>): FloatArray {
        val parts = value.map { flatten(it) }
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, at, part.size)
            at += part.size
        }
        return out
    }

    private fun envelope(w: FloatArray, frame: Int): FloatArray {
        val n = w.size / frame
        return FloatArray(n) { i ->
            var s = 0f
            for (j in 0 until frame) s += w[i * frame + j] * w[i * frame + j]
            sqrt((s / frame).toDouble()).toFloat()
        }
    }
}
