package com.prism.core

import com.prism.launcher.modelcraft.PrismNeuralVoice
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class PrismNeuralVoiceTest {

    private val archive = File("C:/Users/bremo/wav_samples_archive")

    @Test
    fun buildsAndSpeaksFromTheArchive() {
        if (!archive.isDirectory) return
        val probe = com.prism.launcher.speech.AudioDecoding.read(File(archive, "sample_01.wav"))
        if (probe != null) {
            val frames = com.prism.launcher.modelcraft.TtsSignal.analyse(
                probe.samples,
                com.prism.launcher.modelcraft.TtsSignal.Config(probe.sampleRate),
            )
            val centroids = frames.map { it.centroid }.sorted()
            val levels = frames.map { it.rms }.sorted()
            println(
                "PROBE frames ${frames.size}, voiced ${frames.count { !it.f0.isNaN() }}, " +
                    "centroid p10 ${centroids[centroids.size / 10].toInt()} p50 ${centroids[centroids.size / 2].toInt()} " +
                    "p90 ${centroids[centroids.size * 9 / 10].toInt()} max ${centroids.last().toInt()}, " +
                    "rms p10 ${levels[levels.size / 10]} p90 ${levels[levels.size * 9 / 10]}",
            )
        }
        val output = File("build/neural-voice-test").apply { mkdirs() }
        val settings = PrismNeuralVoice.Settings(
            epochs = 16,
            hidden = 64,
            embedding = 32,
            maxUnitsPerSymbol = 12,
        )
        val model = PrismNeuralVoice.train(archive, settings)
        assertTrue(model != null, "the archive should produce a neural voice")
        val trained = model!!
        println("VOICE ${PrismNeuralVoice.summary(trained)}")
        println("LANGUAGES ${trained.languages}")
        println("VOICES ${trained.voices.joinToString(" | ") { it.summary() }}")
        val symbols = trained.units.groupBy { it.symbol }.mapValues { it.value.size }
        println("UNITS $symbols")
        val vowels = setOf("a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o", "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr")
        for (kind in listOf("vowel" to vowels, "other" to (trained.units.map { it.symbol }.toSet() - vowels))) {
            val rows = trained.units.filter { it.symbol in kind.second }
            if (rows.isEmpty()) continue
            println(
                "CLASS ${kind.first}: ${rows.size} units, " +
                    "voiced ${(rows.count { it.voiced } * 100 / rows.size)}%, " +
                    "median ${rows.map { it.seconds * 1000 }.sorted()[rows.size / 2].toInt()} ms, " +
                    "median f0 ${rows.map { it.f0 }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2].toInt() }} Hz",
            )
        }

        for (symbol in listOf("s", "ʃ", "f", "θ", "t", "k", "p", "m", "n", "l", "ɑ", "i", "æ")) {
            val rows = trained.units.filter { it.symbol == symbol }
            if (rows.isEmpty()) {
                println("SYMBOL $symbol: none")
                continue
            }
            println(
                "SYMBOL $symbol: ${rows.size} units, " +
                    "voiced ${(rows.count { it.voiced } * 100 / rows.size)}%, " +
                    "centroid ${rows.map { it.centroid }.average().toInt()} Hz, " +
                    "median ${rows.map { it.seconds * 1000 }.sorted()[rows.size / 2].toInt()} ms",
            )
        }

        val english = trained.voices.filter { it.language == "en" }
            .maxByOrNull { voice -> trained.units.count { it.voice == voice.name } }
            ?: trained.voices.first()
        println("CHOSEN ${english.summary()} with ${trained.units.count { it.voice == english.name }} units")
        val loaded = PrismNeuralVoice.load(archive)
        assertTrue(loaded != null, "the written voice should load back")
        assertTrue(
            loaded!!.units.size == trained.units.size,
            "the loaded voice should keep every unit",
        )

        val prompts = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "Man, I love cookies.",
            "Hello from Prism. This is a neural voice built from the dataset.",
        )
        for ((index, prompt) in prompts.withIndex()) {
            val samples = trained.synthesize(
                prompt,
                language = "en",
                gender = english.gender,
                voiceName = english.name,
            )
            assertTrue(samples.isNotEmpty(), "no audio for: $prompt")
            assertTrue(samples.all { it.isFinite() }, "non-finite sample for: $prompt")
            val seconds = samples.size.toDouble() / PrismNeuralVoice.RATE
            assertTrue(seconds in 0.4..30.0, "implausible duration $seconds for: $prompt")
            val rms = sqrt(samples.sumOf { it * it.toDouble() } / samples.size)
            val peak = samples.maxOfOrNull { abs(it) } ?: 0f
            println("PROMPT[$index] $prompt -> ${"%.2f".format(seconds)} s, rms $rms, peak $peak")
            File(output, "prompt_$index.wav").writeBytes(
                SpeechMeshWire.wav(samples, PrismNeuralVoice.RATE),
            )
        }
        File(output, "summary.txt").writeText(
            buildString {
                appendLine(PrismNeuralVoice.summary(trained))
                appendLine("loss ${trained.loss}, duration accuracy ${trained.accuracy}, steps ${trained.steps}")
                appendLine(trained.voices.joinToString("\n") { it.summary() })
                appendLine(trained.units.groupBy { it.symbol }.mapValues { it.value.size }.toString())
            },
        )
    }
}
