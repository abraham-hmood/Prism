package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.VocalAnalysis
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A neural voice built only from the user's recordings.
 *
 * ## No pretrained voice
 *
 * This is the part Prism used to borrow from Kokoro or Pocket TTS: the thing that turns a sentence
 * nobody recorded into audible speech. Here that thing is made from the dataset itself. Every
 * recording is aligned to its transcript, sliced into phone-sized units, and described with the
 * measurements [VocalAnalysis] already takes -- pitch, level, formants, LTAS, phonation. A small
 * network is trained on those units to predict how long each sound lasts and where its pitch sits.
 * Synthesis then joins the user's own recorded units back together at the predicted rhythm and
 * melody.
 *
 * ## Why concatenation rather than a decoder
 *
 * A decoder trained on a handful of clips cannot generalise to a new sentence; that is a property
 * of the amount of speech, not of the code. The recordings can still speak, though: they are the
 * voice. Selecting and joining units preserves the speaker's real timbre, formants and
 * coarticulation, which is exactly what makes the result sound human rather than synthetic. The
 * trained part supplies what a fixed join cannot: per-phoneme duration, intonation and energy
 * predicted from context.
 *
 * ## What is trained, and what is measured
 *
 * - **Alignment**: a two-pass Viterbi over phone classes, scored by voicing, level, spectral
 *   centroid and flatness. It is Prism's own aligner; no external forced aligner is involved.
 * - **Prosody network**: three layers (context -> hidden -> embedding -> duration/pitch/level),
 *   trained with [TrainerKit.Adam] on the aligned units. This is a real neural network whose
 *   weights are written beside the voice.
 * - **Voice profile**: median pitch, formants, LTAS and phonation from [VocalAnalysis], used to
 *   choose units, set the target pitch, and match level.
 * - **Unit bank**: the recordings themselves, with offsets. Nothing is synthesised from an average
 *   voice.
 *
 * ## Limits, said plainly
 *
 * The result can only contain sounds the dataset contains. A language with no recordings cannot be
 * spoken, and a phone that never occurs falls back to the nearest class. A dataset of one or two
 * short clips will be intelligible only for words built from those sounds. The builder reports the
 * coverage it achieved rather than pretending otherwise.
 */
object PrismNeuralVoice {

    /** The voice description written beside the unit bank. */
    const val FILE = "neural_voice.json"

    /** The trained prosody weights. */
    const val WEIGHTS_FILE = "neural_voice.wts"

    /** The folder a packaged voice may also live in. */
    const val DIRECTORY = "neural_voice"

    const val FORMAT = "prism-neural-voice"
    const val VERSION = 1L

    private const val MAGIC = "prism-neural-voice-v1"
    private const val TAG = "PrismNeuralVoice"
    private const val HOP_SECONDS = 0.010
    private const val MAX_FRAMES = 4_000
    private const val MAX_STEPS = 24_000

    /** The rate units are decoded at; the same rate synthesis returns. */
    const val RATE = 22_050

    private val VOWELS = setOf(
        "a", "æ", "ʌ", "ɑ", "ɒ", "ɔ", "ə", "ɛ", "ɜ", "ɪ", "i", "u", "ʊ", "e", "o",
        "eɪ", "aɪ", "ɔɪ", "aʊ", "oʊ", "ər", "ɑr", "ɔr", "ɜr", "ɪr", "ɛr", "ʊr", "aʊər", "jʊr",
    )
    private val SONORANTS = setOf("l", "r", "ɹ", "m", "n", "ŋ", "j", "w")
    private val FRICATIVES = setOf("s", "ʃ", "f", "θ", "h", "v", "z", "ð", "ʒ", "x")
    private val STOPS = setOf("p", "t", "k", "b", "d", "g")
    private val AFFRICATES = setOf("tʃ", "dʒ")
    private val PAUSES = setOf(" ", ".", ",", "?", "!", ";", ":")

    /**
     * The closest sound to use when the dataset has no instance of the exact phone.
     *
     * A class fallback is not enough for vowels: /ʊ/ is a back rounded vowel, and the nearest
     * member of the vague "vowel" class could be /aɪ/, which turns "cookies" into something no
     * listener can parse. These are ordered articulatory neighbours -- rounding, height, backness --
     * so a missing phone is replaced by a sound the recordings actually contain and the word
     * remains recognisable.
     */
    private val NEAREST: Map<String, List<String>> = mapOf(
        "ʊ" to listOf("u", "ʌ", "ɔ", "ə"),
        "u" to listOf("ʊ", "oʊ", "ɔ"),
        "æ" to listOf("ɛ", "a", "ʌ", "ɑ"),
        "ʌ" to listOf("ə", "ɑ", "æ"),
        "ɑ" to listOf("a", "ɔ", "ʌ", "æ"),
        "ɒ" to listOf("ɑ", "ɔ"),
        "ɔ" to listOf("oʊ", "ɑ", "ʌ"),
        "ə" to listOf("ʌ", "ɛ", "ɪ"),
        "ɛ" to listOf("e", "æ", "ə", "ɪ"),
        "ɪ" to listOf("i", "e", "ə", "ɛ"),
        "i" to listOf("ɪ", "eɪ", "e"),
        "e" to listOf("ɛ", "eɪ", "ɪ"),
        "eɪ" to listOf("ɛ", "i", "e"),
        "aɪ" to listOf("a", "i", "ɑ", "æ"),
        "aʊ" to listOf("ɑ", "oʊ", "ɔ"),
        "oʊ" to listOf("ɔ", "u", "o"),
        "ɔɪ" to listOf("ɔ", "aɪ", "oʊ"),
        "ər" to listOf("ɜr", "ə", "ɹ"),
        "ɜr" to listOf("ər", "ɜ", "ə"),
        "ɑr" to listOf("ɑ", "ɹ", "ɔr"),
        "ɔr" to listOf("ɔ", "ɹ", "oʊ"),
        "ɪr" to listOf("ɪ", "ɹ", "ɛr"),
        "ɛr" to listOf("ɛ", "ɹ", "ɪr"),
        "ð" to listOf("d", "z", "θ"),
        "z" to listOf("s", "ð", "dʒ"),
        "ʒ" to listOf("ʃ", "dʒ", "z"),
        "dʒ" to listOf("tʃ", "ʒ", "z"),
        "ŋ" to listOf("n", "m"),
        "ɹ" to listOf("r", "l"),
        "θ" to listOf("t", "f", "s"),
        "v" to listOf("f", "w", "b"),
        "ʃ" to listOf("s", "ʒ", "tʃ"),
        "tʃ" to listOf("ʃ", "t", "dʒ"),
        "h" to listOf("x", "f"),
        "j" to listOf("i", "w"),
        "w" to listOf("u", "v"),
    )

    /**
     * The knobs the Neural Voice tab exposes.
     *
     * These are the parameters that actually change the trained voice, not decoration: the
     * alignment window decides what counts as one sound, the network size decides how much context
     * the prosody model can represent, the crossfade decides how smooth a join is, and pitch,
     * tempo, breath and warmth decide how the same units are rendered.
     */
    data class Settings(
        /** Width of the second network layer: the voice's learned embedding. */
        val embedding: Int = 32,
        /** Width of the first network layer. */
        val hidden: Int = 64,
        /** Passes over the aligned units. */
        val epochs: Int = 30,
        val learningRate: Double = 0.015,
        /** Overlap between two units, in milliseconds. */
        val crossfadeMs: Int = 6,
        /** Shortest unit kept, in milliseconds. */
        val        minUnitMs: Int = 22,
        /** Longest unit kept, in milliseconds. */
        val maxUnitMs: Int = 320,
        /** Multiplier on the learned pitch contour. */
        val pitchRange: Double = 1.0,
        /** Speaking-rate multiplier applied after the learned durations. */
        val tempo: Double = 1.0,
        /** Air mixed into unvoiced units, 0..1. */
        val breath: Double = 0.08,
        /** Low-pass warmth of the final mix, 0..1. */
        val warmth: Double = 0.45,
        /** Maximum units kept per phoneme. */
        val maxUnitsPerSymbol: Int = 10,
        val seed: Long = 42L,
    ) {
        fun cleaned(): Settings = copy(
            embedding = embedding.coerceIn(8, 128),
            hidden = hidden.coerceIn(16, 256),
            epochs = epochs.coerceIn(1, 200),
            learningRate = learningRate.takeIf { it.isFinite() }?.coerceIn(1e-4, 0.2) ?: 0.015,
            crossfadeMs = crossfadeMs.coerceIn(2, 30),
            minUnitMs = minUnitMs.coerceIn(12, 120),
            maxUnitMs = maxUnitMs.coerceIn(minUnitMs + 30, 600),
            pitchRange = pitchRange.takeIf { it.isFinite() }?.coerceIn(0.5, 2.0) ?: 1.0,
            tempo = tempo.takeIf { it.isFinite() }?.coerceIn(0.5, 2.0) ?: 1.0,
            breath = breath.takeIf { it.isFinite() }?.coerceIn(0.0, 0.5) ?: 0.08,
            warmth = warmth.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.45,
            maxUnitsPerSymbol = maxUnitsPerSymbol.coerceIn(2, 40),
        )

        fun toJson(): JSONObject = JSONObject().apply {
            put("embedding", embedding.toLong())
            put("hidden", hidden.toLong())
            put("epochs", epochs.toLong())
            put("learning_rate", learningRate)
            put("crossfade_ms", crossfadeMs.toLong())
            put("min_unit_ms", minUnitMs.toLong())
            put("max_unit_ms", maxUnitMs.toLong())
            put("pitch_range", pitchRange)
            put("tempo", tempo)
            put("breath", breath)
            put("warmth", warmth)
            put("max_units_per_symbol", maxUnitsPerSymbol.toLong())
            put("seed", seed)
        }

        companion object {
            fun of(json: JSONObject?): Settings = Settings(
                embedding = json?.optInt("embedding", 32) ?: 32,
                hidden = json?.optInt("hidden", 64) ?: 64,
                epochs = json?.optInt("epochs", 30) ?: 30,
                learningRate = json?.optDouble("learning_rate", 0.015) ?: 0.015,
                crossfadeMs = json?.optInt("crossfade_ms", 6) ?: 6,
                minUnitMs = json?.optInt("min_unit_ms", 22) ?: 22,
                maxUnitMs = json?.optInt("max_unit_ms", 320) ?: 320,
                pitchRange = json?.optDouble("pitch_range", 1.0) ?: 1.0,
                tempo = json?.optDouble("tempo", 1.0) ?: 1.0,
                breath = json?.optDouble("breath", 0.08) ?: 0.08,
                warmth = json?.optDouble("warmth", 0.45) ?: 0.45,
                maxUnitsPerSymbol = json?.optInt("max_units_per_symbol", 10) ?: 10,
                seed = json?.optDouble("seed", 42.0)?.toLong() ?: 42L,
            ).cleaned()
        }
    }

    /** One speaker's measurable identity, used to aim the units and the pitch contour. */
    data class Voice(
        val name: String,
        val gender: String,
        val language: String,
        val pitchHz: Double,
        val formant1: Double,
        val formant2: Double,
        val formant3: Double,
        val rms: Double,
        val clips: Int,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("gender", gender)
            put("language", language)
            put("pitch_hz", pitchHz)
            put("formant_1_hz", formant1)
            put("formant_2_hz", formant2)
            put("formant_3_hz", formant3)
            put("rms", rms)
            put("clips", clips.toLong())
        }

        fun summary(): String = "$name · $gender · $language · ${pitchHz.toInt()} Hz"
    }

    /**
     * A word the dataset actually contains, as its units in the order they were spoken.
     *
     * A word is more than a bag of phones: the /k/ in "quick" is rounded by the following /w/,
     * the /l/ in "clear" is darker than the /l/ in "leaf". Reusing the contiguous span preserves
     * that, and makes a known word sound like the recording instead of a join of isolated slices.
     */
    data class WordGroup(
        /** The phones the speaker actually said, including any an interior unit filter dropped. */
        val symbols: List<String>,
        val file: String,
        val start: Int,
        val end: Int,
        val voice: String,
        val language: String,
        val gender: String,
        val f0: Double,
        val rms: Double,
    ) {
        val key: String = symbols.joinToString(" ")

        fun seconds(): Double = (end - start).toDouble() / RATE

        fun toJson(): JSONObject = JSONObject().apply {
            put("symbols", JSONArray(symbols))
            put("file", file)
            put("start", start.toLong())
            put("end", end.toLong())
            put("voice", voice)
            put("language", language)
            put("gender", gender)
            put("f0_hz", f0)
            put("rms", rms)
        }

        companion object {
            fun fromJson(entry: JSONObject): WordGroup? {
                val symbols = entry.optJSONArray("symbols")?.let { array ->
                    (0 until array.length()).map { array.optString(it, "") }
                }.orEmpty().filter { it.isNotBlank() }
                val file = entry.optString("file", "")
                if (symbols.isEmpty() || file.isBlank()) return null
                return WordGroup(
                    symbols = symbols,
                    file = file,
                    start = entry.optInt("start", 0),
                    end = entry.optInt("end", 0),
                    voice = entry.optString("voice", ""),
                    language = entry.optString("language", ""),
                    gender = entry.optString("gender", ""),
                    f0 = entry.optDouble("f0_hz", 0.0),
                    rms = entry.optDouble("rms", 0.0),
                )
            }
        }
    }

    /** Consecutive words a recording spoke as one run, with the pauses between them intact. */
    class Phrase(val words: List<WordGroup>) {
        val symbols: List<String> = words.flatMapIndexed { index, word ->
            if (index == 0) word.symbols else listOf(PrismPhonemes.SPACE) + word.symbols
        }
        val file: String = words.first().file
        val start: Int = words.first().start
        val end: Int = words.last().end
        val voice: String = words.first().voice
        val language: String = words.first().language
        val gender: String = words.first().gender
        val f0: Double = words.firstOrNull { it.f0 > 0.0 }?.f0 ?: 0.0
        val rms: Double = words.map { it.rms }.average()
        val keys: List<String> = words.map { it.key }
    }

    /** A token to speak, optionally carrying the word or phrase it belongs to. */
    private class RenderToken(
        val symbol: String,
        val word: WordGroup? = null,
        val wordId: Int = -1,
        val phrase: Phrase? = null,
        val phraseId: Int = -1,
    )

    /** One slice of a real recording, with the context it was spoken in. */
    data class Unit(
        val symbol: String,
        val file: String,
        val start: Int,
        val end: Int,
        val f0: Double,
        val rms: Double,
        val centroid: Double,
        val language: String,
        val gender: String,
        val voice: String,
        val left: String,
        val right: String,
        /** Share of the span's frames that carried a measurable pitch. */
        val voicing: Double = 0.0,
    ) {
        val seconds: Double get() = (end - start).toDouble() / RATE
        val voiced: Boolean get() = f0 > 0.0 && voicing >= 0.5

        fun toJson(): JSONObject = JSONObject().apply {
            put("symbol", symbol)
            put("file", file)
            put("start", start.toLong())
            put("end", end.toLong())
            put("f0_hz", f0)
            put("rms", rms)
            put("centroid_hz", centroid)
            put("language", language)
            put("gender", gender)
            put("voice", voice)
            put("left", left)
            put("right", right)
            put("voicing", voicing)
        }
    }

    /** A trained prosody network: context -> duration, pitch and level. */
    class Net(
        val input: Int,
        val hidden: Int,
        val embedding: Int,
        val w1: TrainerKit.Param,
        val b1: TrainerKit.Param,
        val w2: TrainerKit.Param,
        val b2: TrainerKit.Param,
        val w3: TrainerKit.Param,
        val b3: TrainerKit.Param,
    ) {
        val parameters: List<TrainerKit.Param> get() = listOf(w1, b1, w2, b2, w3, b3)

        class Forward(val h1: DoubleArray, val h2: DoubleArray, val out: DoubleArray)

        fun forward(x: DoubleArray): Forward {
            val h1 = TrainerKit.linear(x, w1, b1)
            for (index in h1.indices) if (h1[index] < 0.0) h1[index] = 0.0
            val h2 = TrainerKit.linear(h1, w2, b2)
            for (index in h2.indices) if (h2[index] < 0.0) h2[index] = 0.0
            return Forward(h1, h2, TrainerKit.linear(h2, w3, b3))
        }

        /** One example's gradient; returns the squared error. */
        fun accumulate(x: DoubleArray, target: DoubleArray): Double {
            val f = forward(x)
            val dy = DoubleArray(3) { index -> 2.0 * (f.out[index] - target[index]) / 3.0 }
            TrainerKit.accumulateLinear(f.h2, dy, w3, b3)
            val dh2 = DoubleArray(embedding)
            for (row in 0 until 3) {
                val g = dy[row]
                if (g == 0.0) continue
                for (col in 0 until embedding) dh2[col] += w3.data[row * embedding + col] * g
            }
            for (index in dh2.indices) if (f.h2[index] <= 0.0) dh2[index] = 0.0
            TrainerKit.accumulateLinear(f.h1, dh2, w2, b2)
            val dh1 = DoubleArray(hidden)
            for (row in 0 until embedding) {
                val g = dh2[row]
                if (g == 0.0) continue
                for (col in 0 until hidden) dh1[col] += w2.data[row * hidden + col] * g
            }
            for (index in dh1.indices) if (f.h1[index] <= 0.0) dh1[index] = 0.0
            TrainerKit.accumulateLinear(x, dh1, w1, b1)
            var error = 0.0
            for (index in 0 until 3) {
                val d = f.out[index] - target[index]
                error += d * d
            }
            return error
        }

        fun zeroGradients() {
            parameters.forEach { java.util.Arrays.fill(it.grad, 0.0) }
        }

        fun parameterCount(): Int = parameters.sumOf { it.data.size }

        companion object {
            const val INPUT_SCALARS = 9

            fun create(settings: Settings, vocabulary: Int, random: java.util.Random): Net {
                val settingsClean = settings.cleaned()
                val input = vocabulary * 3 + INPUT_SCALARS
                return Net(
                    input = input,
                    hidden = settingsClean.hidden,
                    embedding = settingsClean.embedding,
                    w1 = TrainerKit.gaussian("prosody.w1", settingsClean.hidden, input, random),
                    b1 = TrainerKit.Param("prosody.b1", settingsClean.hidden, 1),
                    w2 = TrainerKit.gaussian("prosody.w2", settingsClean.embedding, settingsClean.hidden, random),
                    b2 = TrainerKit.Param("prosody.b2", settingsClean.embedding, 1),
                    w3 = TrainerKit.gaussian("prosody.w3", 3, settingsClean.embedding, random),
                    b3 = TrainerKit.Param("prosody.b3", 3, 1),
                )
            }
        }
    }

    /** A finished neural voice: units, profiles, settings and the prosody network. */
    class Model(
        val directory: File,
        val voices: List<Voice>,
        val units: List<Unit>,
        val settings: Settings,
        val net: Net,
        val languages: List<String>,
        /** Whole words cut from the same recordings, keyed by their phone sequence at use. */
        val words: List<WordGroup> = buildWordGroups(units),
        /** Consecutive runs of those words, spoken as one recording. */
        val phrases: List<Phrase> = buildPhrases(words),
        val loss: Double,
        val accuracy: Double,
        val steps: Int,
    ) {
        fun voice(language: String, gender: String, name: String? = null): Voice? {
            if (!name.isNullOrBlank()) {
                return voices.firstOrNull { it.name.equals(name, true) }
                    ?: voices.firstOrNull { it.name.contains(name, true) }
            }
            val lang = language.trim().lowercase(Locale.ROOT).substringBefore('-')
            val sex = gender.trim().lowercase(Locale.ROOT)
            return voices.firstOrNull { it.language == lang && it.gender.equals(sex, true) }
                ?: voices.firstOrNull { it.language == lang }
                ?: voices.firstOrNull { it.gender.equals(sex, true) }
                ?: voices.firstOrNull()
        }

        /**
         * Speaks [text] by selecting and joining this dataset's units.
         *
         * [speed] above one compresses the learned durations; [pitch] is a semitone offset that
         * lets a caller try the same voice higher or lower without retraining.
         */
        fun synthesize(
            text: String,
            language: String = "",
            gender: String = "",
            voiceName: String? = null,
            speed: Float = 1f,
            pitch: Double = 0.0,
        ): FloatArray {
            if (text.isBlank()) return FloatArray(0)
            val spoken = PrismPhonemes.phonemize(text, language.ifBlank { "en" })
                .filter { it != PrismPhonemes.STRESS }
            if (spoken.isEmpty()) return FloatArray(0)
            val lang = language.ifBlank {
                voices.firstOrNull()?.language ?: "en"
            }.trim().lowercase(Locale.ROOT).substringBefore('-')
            val chosen = voice(lang, gender, voiceName)
                ?: voices.firstOrNull()
                ?: throw IllegalStateException(
                    "This neural voice has no speaker profile. Rebuild it from the Neural Voice tab.",
                )
            val languageUnits = units.filter { it.language == chosen.language }
                .ifEmpty { units.filter { it.language == lang } }
                .ifEmpty { units }
            // The chosen recording's units come first. A sound it does not contain is borrowed
            // from the same language and gender, and the caption/stats say how much of the
            // sentence was borrowed rather than pretending the identity is uniform.
            val ownUnits = units.filter { it.voice == chosen.name }
            val bySymbol = ownUnits.groupBy { it.symbol }
            val ownClasses = ownUnits.groupBy { classOf(it.symbol) }
            // Borrowing is permitted, but not from the opposite register: stitching a male
            // consonant into a female sentence changes the apparent talker mid-word, and the ear
            // hears that as a broken recording even when the phone itself is correct.
            val sameGender = languageUnits.filter { it.gender.equals(chosen.gender, true) }
            val borrowFrom = sameGender.ifEmpty { languageUnits }
            val fallbackBySymbol = borrowFrom.groupBy { it.symbol }
            val fallbackClasses = borrowFrom.groupBy { classOf(it.symbol) }
            // One more tier: the same register in ANY language. A missing /ɑ/ borrowed from a
            // Spanish or Italian woman is that vowel; substituted with an English /ʌ/ it turns
            // "archive" into "co-op". Class fallback is the last resort, not the second one.
            val worldUnits = units.filter { it.gender.equals(chosen.gender, true) }
                .ifEmpty { units }
            val worldBySymbol = worldUnits.groupBy { it.symbol }
            val worldClasses = worldUnits.groupBy { classOf(it.symbol) }
            val crossfade = (settings.crossfadeMs * RATE / 1000).coerceAtLeast(8)
            val random = java.util.Random((text.hashCode().toLong() shl 1) xor settings.seed)
            val cache = HashMap<String, SpeechClip>()
            val rendered = mutableListOf<FloatArray>()
            var lastSymbol = ""
            var indexInWord = 0

            // WHOLE WORDS FIRST. A word this dataset actually recorded is spoken from its own
            // recording, coarticulation and all. Building every word from isolated phones throws
            // away exactly the transitions that make a word intelligible, and a sentence of
            // stitched phones reads back as a string of unrelated syllables. Unseen words still
            // fall back to the phone path below.
            val ownWords = words.filter { it.voice == chosen.name }.groupBy { it.key }
            val promptWords = mutableListOf<List<String>>()
            var cursor = 0
            while (cursor < spoken.size) {
                if (spoken[cursor] == PrismPhonemes.SPACE || spoken[cursor] in PAUSES) {
                    cursor++
                    continue
                }
                var end = cursor
                while (end < spoken.size &&
                    spoken[end] != PrismPhonemes.SPACE && spoken[end] !in PAUSES
                ) {
                    end++
                }
                promptWords += spoken.subList(cursor, end).toList()
                cursor = end
            }

            // PHRASES BEFORE WORDS. If the archive recorded a run of words together, replaying
            // that run keeps its real coarticulation, rhythm and pauses. Splicing the same words
            // one by one is what makes a clear sentence sound assembled.
            if (System.getenv("PRISM_VOICE_TRACE") != null) {
                println("NVP phrases=${phrases.size} words=${words.size} prompt=${promptWords.size}")
            }
            // Every contiguous run inside a phrase, not just the whole recording. A prompt can
            // share "the quick brown" with a sentence whose next word differs, and that run is
            // still the recording's own coarticulation.
            val phraseIndex = HashMap<String, MutableList<Phrase>>()
            for (phrase in phrases) {
                for (from in phrase.keys.indices) {
                    val longest = minOf(24, phrase.keys.size - from)
                    for (length in 2..longest) {
                        val slice = Phrase(phrase.words.subList(from, from + length))
                        phraseIndex.getOrPut(slice.keys.joinToString("|")) { mutableListOf() }
                            .add(slice)
                    }
                }
            }
            val phraseAt = arrayOfNulls<Phrase>(promptWords.size)
            val phraseIdAt = IntArray(promptWords.size) { -1 }
            var phraseCursor = 0
            while (phraseCursor < promptWords.size) {
                var matched: Phrase? = null
                var matchedLength = 0
                var length = minOf(24, promptWords.size - phraseCursor)
                while (length >= 2 && matched == null) {
                    val key = (phraseCursor until phraseCursor + length)
                        .joinToString("|") { promptWords[it].joinToString(" ") }
                    // A two-word run from a different speaker is a mid-sentence identity jump:
                    // the listener hears two people, not a better sentence. Borrow a phrase only
                    // when it is long enough to be a coherent run, or when it is the whole prompt.
                    val borrowedAllowed = length >= 4 || length == promptWords.size
                    val pool = phraseIndex[key].orEmpty().filter { phrase ->
                        phrase.voice.equals(chosen.name, true) || borrowedAllowed
                    }
                    matched = choosePhrase(pool, chosen, random)
                    if (matched != null) matchedLength = length
                    length--
                }
                if (matched != null) {
                    for (offset in 0 until matchedLength) {
                        phraseAt[phraseCursor + offset] = matched
                        phraseIdAt[phraseCursor + offset] = phraseCursor
                    }
                    phraseCursor += matchedLength
                } else {
                    phraseCursor++
                }
            }

            val tokens = mutableListOf<RenderToken>()
            var wordId = 0
            cursor = 0
            while (cursor < spoken.size) {
                val symbol = spoken[cursor]
                if (symbol == PrismPhonemes.SPACE || symbol in PAUSES) {
                    tokens += RenderToken(symbol)
                    cursor++
                    continue
                }
                val wordSymbols = promptWords.getOrElse(wordId) { emptyList() }
                val key = wordSymbols.joinToString(" ")
                val group = chooseWord(key, ownWords)
                val phrase = phraseAt.getOrNull(wordId)
                val phraseId = phraseIdAt.getOrElse(wordId) { -1 }
                if (System.getenv("PRISM_VOICE_TRACE") != null) {
                    println(
                        "NVW ${phrase?.let { "phrase ${it.voice}/${it.file}" }
                            ?: group?.let { "${it.voice}/${it.file}" }
                            ?: "phones"} <- $key",
                    )
                }
                wordSymbols.forEach {
                    tokens += RenderToken(it, group, wordId, phrase, phraseId)
                }
                wordId++
                cursor += wordSymbols.size.coerceAtLeast(1)
            }
            var spokenWord = -1
            var spokenPhrase = -1

            tokens.forEachIndexed { index, token ->
                val symbol = token.symbol
                if (symbol == PrismPhonemes.SPACE || symbol in PAUSES) {
                    // A phrase was cut from one continuous recording, so the pauses between its
                    // words are already in that audio. Adding the prompt's gaps again inserts a
                    // fresh hole after every word of an otherwise natural sentence -- the exact
                    // "choppy, one word at a time" sound. A space or comma belongs to the phrase
                    // only when the next spoken token is still inside the same phrase.
                    var nextSpoken = index + 1
                    while (nextSpoken < tokens.size &&
                        (tokens[nextSpoken].symbol == PrismPhonemes.SPACE ||
                            tokens[nextSpoken].symbol in PAUSES)
                    ) {
                        nextSpoken++
                    }
                    if (spokenPhrase >= 0 &&
                        tokens.getOrNull(nextSpoken)?.phraseId == spokenPhrase
                    ) {
                        lastSymbol = symbol
                        indexInWord = 0
                        return@forEachIndexed
                    }
                    // A word gap and a punctuation pause are part of intelligibility, not silence
                    // to be skipped: without them every word runs into the next at unit speed.
                    val milliseconds = when (symbol) {
                        // A word gap is not cosmetic. At 55 ms every word runs into the next and a
                        // listener hears one long slur; the same gap the recordings use reads as
                        // separate words.
                        PrismPhonemes.SPACE -> 70.0
                        ",", ";", ":" -> 230.0
                        ".", "!", "?" -> 340.0
                        else -> 150.0
                    } / (settings.tempo * speed.coerceAtLeast(0.3f))
                    rendered += FloatArray((milliseconds * RATE / 1000.0).toInt())
                    indexInWord = 0
                    lastSymbol = symbol
                    return@forEachIndexed
                }
                if (token.phrase != null) {
                    if (token.phraseId == spokenPhrase) return@forEachIndexed
                    spokenPhrase = token.phraseId
                    rendered += renderPhrase(token.phrase, chosen, cache)
                    lastSymbol = symbol
                    indexInWord = token.phrase.symbols.size
                    return@forEachIndexed
                }
                if (token.word != null) {
                    if (token.wordId == spokenWord) return@forEachIndexed
                    spokenWord = token.wordId
                    rendered += renderWord(token.word, chosen, cache)
                    lastSymbol = symbol
                    indexInWord = token.word.symbols.size
                    return@forEachIndexed
                }
                val unit = chooseUnit(
                    symbol = symbol,
                    bySymbol = bySymbol,
                    classes = ownClasses,
                    fallbackBySymbol = fallbackBySymbol,
                    fallbackClasses = fallbackClasses,
                    worldBySymbol = worldBySymbol,
                    worldClasses = worldClasses,
                    chosen = chosen,
                    left = tokens.getOrNull(index - 1)?.symbol.orEmpty(),
                    right = tokens.getOrNull(index + 1)?.symbol.orEmpty(),
                    random = random,
                ) ?: run {
                    lastSymbol = symbol
                    return@forEachIndexed
                }
                val context = contextVector(
                    symbol = symbol,
                    left = tokens.getOrNull(index - 1)?.symbol.orEmpty(),
                    right = tokens.getOrNull(index + 1)?.symbol.orEmpty(),
                    position = index.toDouble() / tokens.size,
                    indexInWord = indexInWord,
                    isLast = index == tokens.lastIndex,
                    chosen = chosen,
                )
                val predicted = net.forward(context).out
                // A phone has a natural floor. The aligner can hand a stop two frames, and the
                // prosody net then predicts 25 ms; joined at that length the word is a click
                // rather than a consonant. The floor is per class, not one number for every sound.
                val floorMs = when (classOf(symbol)) {
                    CLASS_VOWEL -> 95.0
                    CLASS_SONORANT -> 70.0
                    CLASS_FRICATIVE -> 65.0
                    CLASS_STOP -> 45.0
                    CLASS_AFFRICATE -> 70.0
                    else -> 60.0
                }
                // Clear speech is slower speech. The learned durations were trained to reproduce
                // clipped, hurried units; a listener needs a vowel long enough to identify and a
                // consonant long enough not to be heard as a click.
                val duration = (0.105 * exp(predicted[0].coerceIn(-2.2, 2.2)) /
                    (settings.tempo * speed.coerceAtLeast(0.3f)) * 1000.0)
                    .coerceIn(floorMs, 520.0)
                // ONE CONTOUR, NOT ONE PITCH PER RECORDING. A unit's own F0 includes the
                // intonation of the sentence it was cut from; reusing it verbatim makes adjacent
                // phones jump by several semitones even when they came from the same speaker.
                // The chosen voice's median is the register; the prosody network supplies the
                // inflection around it. Borrowed sounds are restricted to the same register, so
                // this no longer pushes a male consonant up an octave.
                val natural = unit.f0.takeIf { it > 0.0 } ?: chosen.pitchHz
                val register = chosen.pitchHz.takeIf { it > 0.0 } ?: natural
                val inflection = predicted[1].coerceIn(-0.18, 0.18) * 12.0 * settings.pitchRange
                val targetF0 = (register * 2.0.pow((inflection + pitch) / 12.0))
                    .coerceIn(70.0, 420.0)
                if (System.getenv("PRISM_VOICE_TRACE") != null) {
                    println(
                        "NV $symbol <- ${unit.voice}/${unit.file} ${unit.start}-${unit.end} " +
                            "core=${((unit.end - unit.start) * 1000.0 / RATE).toInt() - 18}ms " +
                            "dur=${duration.toInt()}ms targetF0=${targetF0.toInt()} " +
                            "L=${unit.left} R=${unit.right} f0=${unit.f0.toInt()}",
                    )
                }
                val targetSamples = (duration * RATE / 1000.0).toInt().coerceAtLeast(220)
                val clip = cache.getOrPut(unit.file) {
                    val decoded = AudioDecoding.read(File(directory, unit.file))
                        ?: throw IllegalStateException(
                            "The recording ${unit.file} is missing, so this voice cannot speak it. " +
                                "Keep the dataset folder beside ${FILE}, or rebuild the voice.",
                        )
                    if (decoded.sampleRate == RATE) decoded else decoded.resampled(RATE)
                }
                val slice = clip.samples.copyOfRange(
                    unit.start.coerceIn(0, clip.samples.size),
                    unit.end.coerceIn(0, clip.samples.size),
                )
                if (slice.isEmpty()) {
                    lastSymbol = symbol
                    return@forEachIndexed
                }
                val stretched = when {
                    abs(targetF0 - natural) < 0.5 && abs(targetSamples - slice.size) < 24 ->
                        slice.copyOf()
                    unit.voiced && unit.f0 > 0.0 ->
                        tdPsola(slice, RATE, unit.f0, targetF0, targetSamples)
                    else -> resampleToLength(slice, targetSamples)
                }
                // A fricative is not as loud as a vowel. Matching every phone to the same RMS
                // amplified a quiet /s/ by ten or twenty times, and that hiss is what listeners
                // hear as a broken microphone. The class sets a natural level relative to the
                // voice; the cap stops a nearly silent slice from becoming noise.
                val level = when (classOf(symbol)) {
                    CLASS_VOWEL -> 1.0
                    CLASS_SONORANT -> 0.8
                    CLASS_FRICATIVE -> 0.3
                    CLASS_STOP -> 0.5
                    CLASS_AFFRICATE -> 0.4
                    else -> 0.6
                }
                val gain = (((chosen.rms.takeIf { it > 1e-6 } ?: 0.1) * level) /
                    (unit.rms.takeIf { it > 1e-6 } ?: 0.1)).coerceIn(0.1, 6.0)
                for (sample in stretched.indices) {
                    stretched[sample] = (stretched[sample] * gain).toFloat()
                }
                if (!unit.voiced && settings.breath > 0.0) {
                    val noise = java.util.Random((unit.start.toLong() xor settings.seed))
                    for (sample in stretched.indices) {
                        stretched[sample] = (stretched[sample] +
                            (noise.nextDouble() * 2.0 - 1.0) * settings.breath * 0.03).toFloat()
                    }
                }
                rendered += stretched
                lastSymbol = symbol
                indexInWord++
            }

            if (rendered.isEmpty()) {
                throw IllegalStateException(
                    "The neural voice has no units for that sentence. Its dataset covers " +
                        "${bySymbol.size} sound(s); try a sentence closer to the recordings.",
                )
            }

            // Join with a short equal-power crossfade. A hard cut between two glottal periods is
            // audible as a click, and a click is the thing listeners describe as "robotic".
            var out = rendered.first()
            for (next in rendered.drop(1)) {
                out = join(out, next, crossfade)
            }
            return master(out, settings)
        }

        /** Picks a recorded word, preferring this speaker and this exact phone sequence. */
        private fun chooseWord(key: String, own: Map<String, List<WordGroup>>): WordGroup? {
            if (key.isBlank()) return null
            // A recorded word from another speaker is a single-word voice change. The chosen
            // speaker's own phones, joined at the same pitch and level, keep one identity; a
            // borrowed word does not, however clean it sounds by itself.
            val pool = own[key].orEmpty()
            if (pool.isEmpty()) return null
            return pool.minByOrNull { group ->
                // A word stretched far past its recording sounds edited even when its phones are
                // right, so a length close to the dataset average is preferred.
                abs(group.seconds() - 0.45)
            }
        }

        /** Picks a phrase this speaker can say that matches the upcoming run of words. */
        private fun choosePhrase(
            pool: List<Phrase>,
            chosen: Voice,
            random: java.util.Random,
        ): Phrase? {
            if (pool.isEmpty()) return null
            return pool.minByOrNull { phrase ->
                var score = if (phrase.voice == chosen.name) 0.0 else 0.3
                if (phrase.language != chosen.language) score += 0.7
                if (!phrase.gender.equals(chosen.gender, true)) score += 0.6
                if (phrase.f0 > 0.0 && chosen.pitchHz > 0.0) {
                    score += kotlin.math.abs(kotlin.math.log2(phrase.f0 / chosen.pitchHz)) * 0.25
                }
                score + random.nextDouble() * 0.02
            }
        }

        private fun renderWord(
            word: WordGroup,
            chosen: Voice,
            cache: MutableMap<String, SpeechClip>,
        ): FloatArray = renderSpan(word.file, word.start, word.end, word.f0, word.rms, chosen, cache)

        private fun renderPhrase(
            phrase: Phrase,
            chosen: Voice,
            cache: MutableMap<String, SpeechClip>,
        ): FloatArray = renderSpan(
            phrase.file, phrase.start, phrase.end, phrase.f0, phrase.rms, chosen, cache,
        )

        /**
         * Plays one span of a recording unchanged at natural tempo. PSOLA is only used when the
         * caller has asked for a different rate; at 1.0 the untouched recording is both the most
         * natural and the most intelligible option, and processed speech is exactly what makes a
         * clean word sound like it came through a cheap microphone.
         */
        private fun renderSpan(
            file: String,
            from: Int,
            to: Int,
            f0: Double,
            spanRms: Double,
            chosen: Voice,
            cache: MutableMap<String, SpeechClip>,
        ): FloatArray {
            val clip = cache.getOrPut(file) {
                val decoded = AudioDecoding.read(File(directory, file))
                    ?: throw IllegalStateException(
                        "The recording $file is missing, so this voice cannot speak it. " +
                            "Keep the dataset folder beside $FILE, or rebuild the voice.",
                    )
                if (decoded.sampleRate == RATE) decoded else decoded.resampled(RATE)
            }
            val slice = clip.samples.copyOfRange(
                from.coerceIn(0, clip.samples.size),
                to.coerceIn(0, clip.samples.size),
            )
            if (slice.isEmpty()) return slice
            val target = (slice.size / (settings.tempo * 1.0).coerceAtLeast(0.3)).toInt()
                .coerceIn(220, RATE * 3)
            val stretched = when {
                abs(settings.tempo - 1.0) < 0.02 -> slice.copyOf()
                f0 > 0.0 -> tdPsola(slice, RATE, f0, f0, target)
                else -> resampleToLength(slice, target)
            }
            val measured = spanRms.takeIf { it > 1e-5 } ?: run {
                var energy = 0.0
                for (sample in slice) energy += sample * sample.toDouble()
                sqrt(energy / slice.size.coerceAtLeast(1))
            }
            val gain = (((chosen.rms.takeIf { it > 1e-6 } ?: 0.1) * 0.9) /
                (measured.takeIf { it > 1e-5 } ?: 0.1)).coerceIn(0.1, 5.0)
            for (i in stretched.indices) stretched[i] = (stretched[i] * gain).toFloat()
            return stretched
        }

        private fun chooseUnit(
            symbol: String,
            bySymbol: Map<String, List<Unit>>,
            classes: Map<Int, List<Unit>>,
            fallbackBySymbol: Map<String, List<Unit>>,
            fallbackClasses: Map<Int, List<Unit>>,
            worldBySymbol: Map<String, List<Unit>>,
            worldClasses: Map<Int, List<Unit>>,
            chosen: Voice,
            left: String,
            right: String,
            random: java.util.Random,
        ): Unit? {
            // THE CHOSEN SPEAKER OWNS EVERY SOUND THEY ACTUALLY RECORDED. Mixing in a second
            // talker's exact phone is more intelligible in isolation but changes timbre mid-word,
            // and the ear hears that as a spliced recording even when the phoneme is right.
            // Borrow only sounds the chosen voice does not contain.
            val ownExact = bySymbol[symbol].orEmpty()
            val exact = if (ownExact.isNotEmpty()) {
                ownExact
            } else {
                (fallbackBySymbol[symbol].orEmpty() + worldBySymbol[symbol].orEmpty()).distinct()
            }
            // No instance of this exact sound: try an articulatory neighbour before the coarse
            // class. A class fallback can turn /ʊ/ into /aɪ/; a neighbour turns it into /u/.
            fun pool(sound: String): List<Unit> = bySymbol[sound].orEmpty().ifEmpty {
                (fallbackBySymbol[sound].orEmpty() + worldBySymbol[sound].orEmpty()).distinct()
            }
            val neighbour = NEAREST[symbol].orEmpty().firstNotNullOfOrNull { alternative ->
                pool(alternative).takeIf { it.isNotEmpty() }
            }
            val candidates = exact.ifEmpty {
                neighbour ?: classes[classOf(symbol)].orEmpty().ifEmpty {
                    (fallbackClasses[classOf(symbol)].orEmpty() +
                        worldClasses[classOf(symbol)].orEmpty())
                }
            }.distinct()
            if (candidates.isEmpty()) return null
            val targetSeconds = when (classOf(symbol)) {
                CLASS_VOWEL -> 0.13
                CLASS_SONORANT -> 0.08
                CLASS_FRICATIVE -> 0.10
                CLASS_STOP -> 0.06
                CLASS_AFFRICATE -> 0.09
                else -> 0.09
            }
            // A whole syllable labelled as one phone cannot be fixed by crossfading it. Drop the
            // obvious outliers before scoring, so a consonant is never spoken as a 110 ms hum.
            val plausible = candidates.filter { unit ->
                unit.seconds <= targetSeconds * if (classOf(symbol) == CLASS_VOWEL) 2.2 else 1.9
            }.ifEmpty { candidates }
            // A 106 Hz male /ð/ shifted to a 242 Hz female is not the same phoneme any more; PSOLA
            // can move the pitch but not the vocal-tract length, and the result is the chipmunk
            // buzz heard on every borrowed consonant. Keep voiced units near the target voice's
            // own register, and let an unvoiced consonant come from any talker.
            val voicedClass = classOf(symbol) == CLASS_VOWEL ||
                classOf(symbol) == CLASS_SONORANT || classOf(symbol) == CLASS_AFFRICATE
            val registerMatched = if (voicedClass && chosen.pitchHz > 0.0) {
                plausible.filter { unit ->
                    unit.f0 <= 0.0 || abs(log2(unit.f0 / chosen.pitchHz)) <= 0.55
                }.ifEmpty { plausible }
            } else {
                plausible
            }
            val scored = registerMatched.map { unit ->
                var score = 0.0
                if (unit.voice == chosen.name) score += 1.0
                if (unit.gender.equals(chosen.gender, true)) score += 0.4
                // Context now outweighs identity. A phone from the right talker in the wrong
                // neighbour is a different sound once the unit is joined; the same phone from a
                // close-register talker in the right context is the one that keeps the word.
                if (unit.left == left) score += 1.0
                if (unit.right == right) score += 1.0
                if (unit.voiced && classOf(symbol) == CLASS_VOWEL) score += 0.45
                if (!unit.voiced && classOf(symbol) != CLASS_VOWEL) score += 0.15
                // Prefer a steady phone over a slice that is mostly its neighbours, as a ratio:
                // 40 ms too long is a different problem for a 60 ms stop than for a 130 ms vowel.
                score -= (abs(unit.seconds - targetSeconds) / targetSeconds) * 2.2
                if (unit.f0 > 0.0 && chosen.pitchHz > 0.0) {
                    score -= abs(log2(unit.f0 / chosen.pitchHz)) * 1.6
                }
                score += (unit.rms.coerceIn(0.0, 0.4)) * 0.8
                score += random.nextDouble() * 0.03
                unit to score
            }
            return scored.maxByOrNull { it.second }?.first
        }

        private fun contextVector(
            symbol: String,
            left: String,
            right: String,
            position: Double,
            indexInWord: Int,
            isLast: Boolean,
            chosen: Voice,
        ): DoubleArray {
            val vocabulary = PrismPhonemes.SYMBOLS.size
            val input = DoubleArray(vocabulary * 3 + Net.INPUT_SCALARS)
            fun oneHot(offset: Int, value: String) {
                val id = PrismPhonemes.idOf(value)
                if (id in 0 until vocabulary) input[offset + id] = 1.0
            }
            oneHot(0, left)
            oneHot(vocabulary, symbol)
            oneHot(vocabulary * 2, right)
            val base = vocabulary * 3
            input[base] = position.coerceIn(0.0, 1.0) * 2.0 - 1.0
            input[base + 1] = if (symbol in PAUSES) 1.0 else 0.0
            input[base + 2] = if (right in PAUSES) 1.0 else 0.0
            input[base + 3] = log2((chosen.pitchHz / 120.0).coerceAtLeast(0.1))
            input[base + 4] = classOf(symbol) / 5.0
            input[base + 5] = indexInWord.coerceIn(0, 12) / 12.0
            input[base + 6] = if (isLast) 1.0 else 0.0
            input[base + 7] = 1.0
            input[base + 8] = 0.0
            return input
        }
    }

    /**
     * Cuts whole words out of the aligned units.
     *
     * The aligner already knows where a word begins and ends: the unit before it and the unit
     * after it are the SPACE token. A run of contiguous units in one recording between those two
     * boundaries is the word as the speaker actually said it.
     */
    private fun buildWordGroups(units: List<Unit>): List<WordGroup> {
        val out = mutableListOf<WordGroup>()
        for ((_, group) in units.groupBy { it.file to it.voice }) {
            val sorted = group.sortedBy { it.start }
            var run = mutableListOf<Unit>()
            fun flush() {
                if (run.size in 2..12 && run.sumOf { it.end - it.start } >= RATE / 20) {
                    val first = run.first()
                    out += WordGroup(
                        symbols = run.map { it.symbol },
                        file = first.file,
                        start = first.start,
                        end = run.last().end,
                        voice = first.voice,
                        language = first.language,
                        gender = first.gender,
                        f0 = run.firstOrNull { it.f0 > 0.0 }?.f0 ?: 0.0,
                        rms = run.map { it.rms }.average(),
                    )
                }
                run = mutableListOf()
            }
            for (unit in sorted) {
                val prev = run.lastOrNull()
                if (prev != null) {
                    val boundary = unit.left == PrismPhonemes.SPACE || unit.left in PAUSES ||
                        unit.left.isEmpty() || prev.right == PrismPhonemes.SPACE ||
                        prev.right in PAUSES || prev.right.isEmpty()
                    val gap = unit.start - prev.end
                    if (boundary || gap > RATE * 0.05) flush()
                }
                run += unit
            }
            flush()
        }
        return out.distinctBy { Triple(it.file, it.start, it.end) }
    }

    // ── Training ───────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a voice from every recording in [directory] that has transcript words.
     *
     * Returns null with a logged reason when there is not enough material to align. That is a
     * different answer from an exception: a model folder without audio simply has no neural voice,
     * while a voice that was asked for and failed has a reason the page should show.
     */
    fun train(
        directory: File,
        settings: Settings = Settings(),
        defaultLanguage: String = "en",
        progress: ((String, Double) -> kotlin.Unit)? = null,
    ): Model? {
        val clean = settings.cleaned()
        val readings = TtsTrainer.spokenClips(directory, defaultLanguage)
        if (readings.isEmpty()) {
            PrismPlatform.log.warn(TAG, "No audio with transcripts in ${directory.absolutePath}")
            return null
        }
        PrismPlatform.log.info(
            TAG,
            "Aligning ${readings.size} recording(s) for a neural voice in ${directory.name}",
        )
        val signal = TtsSignal.Config(RATE)
        var total = 0
        val clips = readings.mapNotNull { reading ->
            val decoded = AudioDecoding.read(reading.file) ?: return@mapNotNull null
            if (decoded.samples.isEmpty()) return@mapNotNull null
            val atRate = if (decoded.sampleRate == RATE) decoded else decoded.resampled(RATE)
            val frames = TtsSignal.analyse(atRate.samples, signal).take(MAX_FRAMES)
            val trimmed = trim(frames)
            if (trimmed.size < 12) return@mapNotNull null
            val tokens = PrismPhonemes.phonemize(reading.transcript, reading.language)
                .filter { it != PrismPhonemes.STRESS }
            if (tokens.count { it !in PAUSES } < 3) return@mapNotNull null
            val start = frames.indexOf(trimmed.first())
            val vocal = runCatching {
                VocalAnalysis.extract(reading.file.name, atRate, reading.language)
            }.getOrNull()
            total += trimmed.size
            // A recording is a speaker. Pooling every English clip into one "English" voice is
            // how a sentence ends up sounding like four different people taking turns; the unit
            // bank keeps each recording's identity and only borrows a missing sound when it must.
            val measuredPitch = vocal?.value("f0_median_hz")?.takeIf { it.isFinite() && it > 0.0 }
                ?: trimmed.mapNotNull { it.f0.takeIf { f -> !f.isNaN() } }.sorted()
                    .let { if (it.isEmpty()) reading.pitchHz else it[it.size / 2] }
            // "neutral" is what the transcript reader returns when the CSV had no gender
            // column, not a measurement. A 105 Hz speaker labelled neutral is how every voice in
            // the archive ended up genderless and the gender filter had nothing to select on.
            val declared = reading.gender.takeIf {
                it.isNotBlank() && !it.equals("neutral", true)
            }
            val gender = declared ?: SpeakerGender.label(
                measuredPitch,
                vocal?.value("formant_1_hz"),
                vocal?.value("formant_2_hz"),
                vocal?.value("formant_3_hz"),
            )
            Clip(
                reading = reading,
                samples = atRate.samples,
                frames = trimmed,
                startFrame = start.coerceAtLeast(0),
                tokens = tokens,
                vocal = vocal,
                // A file name is where the take was stored, not who is speaking. The generated
                // name is stable for this recording, so reloading a pack does not rename a voice.
                voiceName = SpeakerNames.forClip(reading.file.name, gender = gender),
                voiceGender = gender,
            )
        }
        if (clips.isEmpty()) {
            PrismPlatform.log.warn(TAG, "No recording could be decoded and aligned")
            return null
        }

        clips.forEach { clip -> clip.alignment = align(clip.frames, clip.tokens) }

        val units = buildUnits(clips, clean)
        if (units.isEmpty()) {
            PrismPlatform.log.warn(TAG, "The aligner produced no usable units")
            return null
        }
        val words = buildWords(clips)
        val voices = buildVoices(clips)
        val random = java.util.Random(clean.seed)
        val net = Net.create(clean, PrismPhonemes.SYMBOLS.size, random)
        val adam = TrainerKit.Adam(clean.learningRate)
        val bySymbol = units.groupBy { it.symbol }

        var steps = 0
        var lastLoss = Double.NaN
        var lastAccuracy = 0.0
        var epoch = 0
        while (epoch < clean.epochs && steps < MAX_STEPS) {
            epoch++
            var epochError = 0.0
            var correct = 0
            var examples = 0
            val shuffled = units.shuffled(random)
            for (unit in shuffled) {
                if (steps >= MAX_STEPS) break
                val voice = voices.firstOrNull { it.name == unit.voice }
                    ?: voices.firstOrNull() ?: break
                val x = unitInput(unit, voice, clean)
                val target = doubleArrayOf(
                    ln((unit.seconds / 0.08).coerceAtLeast(0.05)),
                    (12.0 * log2((unit.f0.takeIf { it > 0.0 } ?: voice.pitchHz) / voice.pitchHz)) / 12.0,
                    ln((unit.rms.coerceAtLeast(1e-5)) / (voice.rms.coerceAtLeast(1e-5))),
                )
                net.zeroGradients()
                val error = net.accumulate(x, target)
                adam.step(net.parameters, 5.0)
                steps++
                examples++
                epochError += error
                val predictedMs = 80.0 * exp(net.forward(x).out[0])
                val actualMs = unit.seconds * 1000.0
                if (abs(predictedMs - actualMs) <= max(20.0, actualMs * 0.25)) correct++
            }
            if (examples == 0) break
            lastLoss = epochError / examples
            lastAccuracy = correct.toDouble() / examples
            val clipName = shuffled.firstOrNull()?.file ?: directory.name
            progress?.invoke(
                "epoch $epoch/${clean.epochs} · ${"%.4f".format(lastLoss)} loss · " +
                    "${(lastAccuracy * 100).toInt()}% duration",
                epoch.toDouble() / clean.epochs,
            )
            ModelCraft.reportTraining(
                ModelCraft.Progress(
                    epoch = epoch,
                    epochs = clean.epochs,
                    step = examples,
                    steps = units.size,
                    file = clipName,
                    loss = lastLoss,
                    accuracy = lastAccuracy,
                ),
            )
            PrismPlatform.log.info(
                TAG,
                "Neural voice epoch $epoch/${clean.epochs}: loss ${"%.4f".format(lastLoss)}, " +
                    "duration ${(lastAccuracy * 100).toInt()}%",
            )
        }
        if (steps == 0 || !lastLoss.isFinite()) {
            PrismPlatform.log.warn(TAG, "Training produced no finite step")
            return null
        }
        val languages = voices.map { it.language }.distinct().sorted()
        val model = Model(
            directory = directory,
            voices = voices,
            units = units,
            settings = clean,
            net = net,
            languages = languages,
            words = words,
            loss = lastLoss,
            accuracy = lastAccuracy,
            steps = steps,
        )
        write(model, directory)
        PrismPlatform.log.info(
            TAG,
            "Neural voice for ${directory.name}: ${units.size} unit(s), ${voices.size} voice(s), " +
                "${net.parameterCount()} parameters, coverage ${(coverage(units) * 100).toInt()}%",
        )
        return model
    }

    /** The sounds the voice actually contains, as the fraction of the phoneme table. */
    fun coverage(units: List<Unit>): Double =
        units.map { it.symbol }.distinct().size.toDouble() / PrismPhonemes.SYMBOLS.size

    private class Clip(
        val reading: TtsTrainer.SpokenClip,
        val samples: FloatArray,
        val frames: List<TtsSignal.Frame>,
        val startFrame: Int,
        val tokens: List<String>,
        val vocal: com.prism.launcher.speech.VocalFeatures?,
        val voiceName: String,
        val voiceGender: String,
        var alignment: IntArray = IntArray(0),
    )

    /**
     * Features normalised against the clip's own distribution.
     *
     * Absolute levels differ between recordings and between microphones; what a phone is lies in
     * where a frame sits relative to the rest of THAT utterance. A sibilant is the high-centroid
     * part of its sentence, not a frame above some fixed 3 kHz line, and a stop is a dip in this
     * speaker's level, not a fixed -30 dB. Without this normalisation the aligner cannot tell a
     * fricative from a vowel and falls back to uniform durations -- the exact failure that made
     * every consonant a vowel-like slice.
     */
    private fun normalisedFeatures(frames: List<TtsSignal.Frame>): Array<DoubleArray> {
        val raw = Array(frames.size) { index ->
            val frame = frames[index]
            val level = 20.0 * kotlin.math.log10(frame.rms.coerceAtLeast(1e-6))
            doubleArrayOf(
                level,
                if (frame.f0.isNaN()) 0.0 else 1.0,
                ln(frame.centroid.coerceAtLeast(60.0)),
                frame.flatness.coerceIn(1e-6, 1.0),
            )
        }
        if (raw.isEmpty()) return raw
        val mean = DoubleArray(4)
        val deviation = DoubleArray(4)
        for (row in raw) for (index in 0 until 4) mean[index] += row[index]
        for (index in 0 until 4) mean[index] /= raw.size
        for (row in raw) for (index in 0 until 4) deviation[index] += (row[index] - mean[index]).pow(2)
        for (index in 0 until 4) deviation[index] = sqrt(deviation[index] / raw.size).coerceAtLeast(1e-4)
        return Array(raw.size) { index ->
            val row = raw[index]
            DoubleArray(4) { dim ->
                when (dim) {
                    // Voicing is already a 0/1 observation, not a continuous measurement to centre.
                    1 -> row[1]
                    else -> ((row[dim] - mean[dim]) / deviation[dim]).coerceIn(-4.0, 4.0)
                }
            }
        }
    }

    /**
     * Aligns [tokens] to [frames] with a monotonic Viterbi.
     *
     * Each phone has a legal duration range and a duration prior. The emission is a small
     * articulation model -- vowels want voicing and level, fricatives want flat noise and high
     * frequency, stops want a short closure -- replaced by the measured class statistics on the
     * second pass when [stats] is present.
     */
    private fun align(
        frames: List<TtsSignal.Frame>,
        tokens: List<String>,
    ): IntArray {
        if (tokens.isEmpty() || frames.isEmpty()) return IntArray(tokens.size)
        val frameCount = frames.size
        val features = normalisedFeatures(frames)
        val prefixes = Array(6) { cls ->
            val prefix = DoubleArray(frameCount + 1)
            for (index in 0 until frameCount) prefix[index + 1] = prefix[index] + emission(cls, features[index])
            prefix
        }
        val inf = 1e30
        val cost = Array(tokens.size) { DoubleArray(frameCount) { inf } }
        val back = Array(tokens.size) { IntArray(frameCount) { -1 } }
        for (index in tokens.indices) {
            val cls = classOf(tokens[index])
            val range = durationRange(cls, tokens[index])
            for (end in 0 until frameCount) {
                for (duration in range) {
                    val start = end - duration + 1
                    if (start < 0) continue
                    // Every phone after the first must leave a frame before it for its
                    // predecessor; start == 0 would index cost[index - 1][-1].
                    if (index > 0 && start == 0) continue
                    val previous = if (index == 0) {
                        if (start == 0) 0.0 else continue
                    } else {
                        cost[index - 1][start - 1]
                    }
                    if (!previous.isFinite() || previous >= inf) continue
                    // The Viterbi minimises cost, and [emission] is a reward: subtract it. Adding
                    // it here made the aligner choose the frames that matched each phone LEAST --
                    // vowels on hiss, sibilants on voiced audio -- which is not a small quality
                    // issue, it is how a voice ends up saying nothing resembling the text.
                    val value = previous + durationPenalty(cls, duration, tokens[index]) -
                        (prefixes[cls][end + 1] - prefixes[cls][start])
                    if (value < cost[index][end]) {
                        cost[index][end] = value
                        back[index][end] = duration
                    }
                }
            }
        }
        var bestEnd = -1
        var best = inf
        val tail = 4
        for (end in max(0, frameCount - tail) until frameCount) {
            val value = cost.last()[end]
            if (value < best) {
                best = value
                bestEnd = end
            }
        }
        if (bestEnd < 0) {
            // A very short clip and a long sentence: fall back to the trainer's proportional
            // alignment rather than emitting zero-length units for every phone.
            PrismPlatform.log.warn(
                TAG,
                "Viterbi found no path for ${tokens.size} token(s) over $frameCount frame(s); " +
                    "falling back to proportional alignment",
            )
            return TtsTrainer.align(tokens, frameCount)
        }
        val durations = IntArray(tokens.size)
        var end = bestEnd
        for (index in tokens.indices.reversed()) {
            val duration = back[index][end].coerceAtLeast(1)
            durations[index] = duration
            end -= duration
        }
        return durations
    }

    /**
     * Class prototypes in the aligner's normalised feature space.
     *
     * Level, voicing, log-centroid and spectral flatness are z-scored per recording, so a vowel is
     * not "above -20 dB" but "louder than this speaker's average and voiced", and a sibilant is
     * "quieter, unvoiced, brighter and flatter". The old additive rewards made every voiced frame
     * prefer a vowel by a wide margin, so consonants got the minimum two frames and the voice
     * clicked. A diagonal Gaussian makes the classes compete on the same scale instead.
     */
    private val CLASS_MEANS: Array<DoubleArray> = arrayOf(
        doubleArrayOf(-2.0, 0.0, 0.0, 0.0),    // pause
        doubleArrayOf(0.8, 1.0, 0.0, -0.5),    // vowel
        doubleArrayOf(0.0, 0.9, -0.6, -0.4),   // sonorant
        doubleArrayOf(-0.3, 0.05, 1.6, 1.0),   // fricative
        doubleArrayOf(-1.2, 0.1, 0.3, 0.0),    // stop
        doubleArrayOf(0.0, 0.2, 1.0, 0.6),     // affricate
    )

    private val CLASS_VARIANCES: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.05, 1.0, 1.0),
        doubleArrayOf(0.8, 0.05, 1.0, 0.5),
        doubleArrayOf(0.7, 0.1, 0.7, 0.6),
        doubleArrayOf(0.8, 0.05, 1.0, 0.8),
        doubleArrayOf(0.8, 0.1, 1.0, 1.0),
        doubleArrayOf(0.8, 0.1, 1.0, 0.8),
    )

    /** Negative log-likelihood of one frame under a class's articulation prototype. */
    private fun emission(cls: Int, f: DoubleArray): Double {
        val mean = CLASS_MEANS[cls.coerceIn(0, CLASS_MEANS.size - 1)]
        val variance = CLASS_VARIANCES[cls.coerceIn(0, CLASS_VARIANCES.size - 1)]
        var value = 0.0
        for (dimension in 0 until 4) {
            val difference = f[dimension] - mean[dimension]
            value += difference * difference / variance[dimension] + ln(variance[dimension])
        }
        return -0.5 * value
    }

    private fun durationRange(cls: Int, symbol: String): IntRange = when (cls) {
        CLASS_PAUSE -> if (symbol == " ") 2..16 else 4..40
        // Minimums are real durations, not one frame. A 20 ms /l/ is a click, and the old range
        // let the aligner put four consonants inside one vowel's worth of frames.
        CLASS_VOWEL -> 5..30
        CLASS_SONORANT -> 4..22
        CLASS_FRICATIVE -> 4..26
        CLASS_STOP -> 3..14
        else -> 4..20
    }

    private fun durationPenalty(cls: Int, duration: Int, symbol: String): Double {
        val (mean, deviation) = when (cls) {
            CLASS_PAUSE -> if (symbol == " ") 5.0 to 2.0 else 10.0 to 4.0
            CLASS_VOWEL -> 9.0 to 3.0
            CLASS_SONORANT -> 6.0 to 2.0
            CLASS_FRICATIVE -> 7.0 to 2.5
            CLASS_STOP -> 4.0 to 1.5
            else -> 6.0 to 2.0
        }
        val z = (duration - mean) / deviation
        // A real prior now, not a tie-breaker. With the old 0.09 the acoustics could stretch a
        // vowel to half a second and squeeze every /l/ and /m/ into a two-frame click.
        return -0.5 * z * z
    }

    private fun classOf(symbol: String): Int = when {
        symbol.isEmpty() || symbol in PAUSES -> CLASS_PAUSE
        symbol in VOWELS -> CLASS_VOWEL
        symbol in SONORANTS -> CLASS_SONORANT
        symbol in FRICATIVES -> CLASS_FRICATIVE
        symbol in STOPS -> CLASS_STOP
        symbol in AFFRICATES -> CLASS_AFFRICATE
        else -> CLASS_SONORANT
    }

    private const val CLASS_PAUSE = 0
    private const val CLASS_VOWEL = 1
    private const val CLASS_SONORANT = 2
    private const val CLASS_FRICATIVE = 3
    private const val CLASS_STOP = 4
    private const val CLASS_AFFRICATE = 5

    private fun trim(frames: List<TtsSignal.Frame>): List<TtsSignal.Frame> {
        if (frames.isEmpty()) return frames
        val peak = frames.maxOf { it.rms }
        if (peak <= 1e-6) return frames
        val threshold = peak * 0.045
        val first = frames.indexOfFirst { it.rms >= threshold }.coerceAtLeast(0)
        val last = frames.indexOfLast { it.rms >= threshold }.coerceAtLeast(first)
        val start = max(0, first - 2)
        val end = min(frames.size, last + 3)
        return if (end - start < 10) frames else frames.subList(start, end)
    }

    private fun buildUnits(clips: List<Clip>, settings: Settings): List<Unit> {
        // The cap is per voice and phoneme, not global per phoneme. A global cap let one loud
        // recording take every /t/ in the dataset, leaving another speaker with 19 units and no
        // stop at all -- so the chosen voice could not say its own words.
        val bySymbol = HashMap<Pair<String, String>, MutableList<Unit>>()
        for (clip in clips) {
            val peak = clip.frames.maxOfOrNull { it.rms } ?: continue
            var frameIndex = 0
            var droppedShort = 0
            var droppedQuiet = 0
            var kept = 0
            clip.tokens.forEachIndexed { index, symbol ->
                val duration = clip.alignment.getOrElse(index) { 0 }
                if (symbol in PAUSES || duration <= 0) {
                    frameIndex += duration
                    return@forEachIndexed
                }
                // The guard is class-specific. A flat 18 ms either side was longer than many
                // phones, so the "unit" was mostly the two neighbours -- the join then said the
                // transitions twice and the words dissolved. A short guard keeps coarticulation
                // without turning every consonant into a syllable.
                val guard = when (classOf(symbol)) {
                    CLASS_VOWEL -> 0.004
                    CLASS_SONORANT -> 0.003
                    CLASS_FRICATIVE -> 0.003
                    CLASS_STOP -> 0.002
                    else -> 0.003
                }
                val startSample = (clip.startFrame + frameIndex) * (RATE / 100) - (RATE * guard).toInt()
                val endSample = (clip.startFrame + frameIndex + duration) * (RATE / 100) +
                    (RATE * guard).toInt()
                var start = startSample.coerceIn(0, clip.samples.size - 1)
                var end = endSample.coerceIn(start + 1, clip.samples.size)
                // A whole syllable can be assigned to one vowel by the aligner. Keeping it whole
                // would make a unit that speaks a word nobody asked for, so cap each class at a
                // plausible steady-state length before the unit enters the bank.
                val classCapMs = when (classOf(symbol)) {
                    CLASS_VOWEL -> 170.0
                    CLASS_SONORANT -> 105.0
                    CLASS_FRICATIVE -> 190.0
                    CLASS_STOP -> 85.0
                    CLASS_AFFRICATE -> 120.0
                    else -> 150.0
                }
                val maximumSamples =
                    (min(settings.maxUnitMs.toDouble(), classCapMs) * RATE / 1000.0).toInt()
                if (end - start > maximumSamples) {
                    // Trim symmetrically: the centre of the phone carries the sound, and keeping
                    // the whole 600 ms vowel would only repeat one period many times.
                    val centre = (start + end) / 2
                    start = (centre - maximumSamples / 2).coerceAtLeast(0)
                    end = (start + maximumSamples).coerceAtMost(clip.samples.size)
                }
                val milliseconds = (end - start) * 1000.0 / RATE
                if (milliseconds < settings.minUnitMs) droppedShort++
                if (milliseconds >= settings.minUnitMs) {
                    val span = clip.frames.subList(frameIndex, min(frameIndex + duration, clip.frames.size))
                    val voiced = span.mapNotNull { frame -> frame.f0.takeIf { !it.isNaN() } }.sorted()
                    val f0 = if (voiced.isEmpty()) 0.0 else voiced[voiced.size / 2]
                    val rms = span.map { it.rms }.average().coerceAtLeast(0.0)
                    val centroid = span.map { it.centroid }.average()
                    if (rms >= peak * 0.05 || classOf(symbol) in setOf(CLASS_STOP, CLASS_FRICATIVE)) {
                        kept++
                        val voicing = span.count { !it.f0.isNaN() }.toDouble() / span.size.coerceAtLeast(1)
                        val unit = Unit(
                            symbol = symbol,
                            file = clip.reading.file.name,
                            start = start,
                            end = end,
                            // A median pitch from one voiced frame in a hiss is not a pitch. Only
                            // a majority-voiced span is spoken through PSOLA; the rest is noise and
                            // must be time-scaled, or an /s/ acquires a buzz it never had.
                            f0 = if (voicing >= 0.5) f0 else 0.0,
                            rms = rms,
                            centroid = centroid,
                            language = clip.reading.language,
                            gender = clip.voiceGender,
                            voice = clip.voiceName,
                            left = clip.tokens.getOrNull(index - 1).orEmpty(),
                            right = clip.tokens.getOrNull(index + 1).orEmpty(),
                            voicing = voicing,
                        )
                        bySymbol.getOrPut(clip.voiceName to symbol) { mutableListOf() }.add(unit)
                    } else {
                        droppedQuiet++
                    }
                }
                frameIndex += duration
            }
            PrismPlatform.log.info(
                TAG,
                "${clip.reading.file.name}: ${clip.tokens.size} token(s), ${clip.frames.size} frame(s), " +
                    "kept $kept, short $droppedShort, quiet $droppedQuiet",
            )
        }
        return bySymbol.values.flatMap { list ->
            list.sortedWith(
                compareByDescending<Unit> { unit ->
                    unit.rms + (if (unit.voiced) 0.02 else 0.0) - abs(unit.seconds - 0.11) * 0.02
                }.thenByDescending { it.voiced },
            ).take(settings.maxUnitsPerSymbol)
        }.sortedWith(compareBy({ it.language }, { it.symbol }, { it.file }, { it.start }))
    }

    /**
     * Whole words from the aligned token stream, not from the surviving units.
     *
     * A word is the span its tokens occupy, even when one interior phone was too quiet or too
     * short to become a stand-alone unit. Cutting only between kept units silently discarded
     * "jumps" because its /m/ was filtered, leaving a gap that no sentence could match.
     */
    private fun buildWords(clips: List<Clip>): List<WordGroup> {
        val out = mutableListOf<WordGroup>()
        for (clip in clips) {
            var frameIndex = 0
            var wordStart = -1
            var wordEnd = -1
            var symbols = mutableListOf<String>()
            fun flush() {
                if (symbols.isNotEmpty() && wordEnd > wordStart) {
                    val start = ((clip.startFrame + wordStart) * (RATE / 100) -
                        (RATE * 0.002).toInt()).coerceAtLeast(0)
                    val end = ((clip.startFrame + wordEnd) * (RATE / 100) +
                        (RATE * 0.003).toInt()).coerceAtMost(clip.samples.size)
                    if (end - start >= RATE / 25) {
                        val span = clip.frames.subList(
                            wordStart.coerceIn(0, clip.frames.size),
                            wordEnd.coerceIn(wordStart, clip.frames.size),
                        )
                        val f0 = span.mapNotNull { frame ->
                            frame.f0.takeIf { !it.isNaN() && it > 0.0 }
                        }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                        out += WordGroup(
                            symbols = symbols.toList(),
                            file = clip.reading.file.name,
                            start = start,
                            end = end,
                            voice = clip.voiceName,
                            language = clip.reading.language,
                            gender = clip.voiceGender,
                            f0 = f0,
                            rms = span.map { it.rms }.average().takeIf { it > 0.0 } ?: 0.0,
                        )
                    }
                }
                symbols = mutableListOf()
                wordStart = -1
                wordEnd = -1
            }
            clip.tokens.forEachIndexed { index, token ->
                val duration = clip.alignment.getOrElse(index) { 0 }
                if (token in PAUSES) {
                    flush()
                } else {
                    if (symbols.isEmpty()) wordStart = frameIndex
                    symbols += token
                    wordEnd = (frameIndex + duration).coerceAtMost(clip.frames.size)
                }
                frameIndex += duration
            }
            flush()
        }
        return out.distinctBy { Triple(it.file, it.start, it.end) }
    }

    /** Runs of adjacent recorded words, kept together so their original coarticulation survives. */
    private fun buildPhrases(words: List<WordGroup>): List<Phrase> {
        val out = mutableListOf<Phrase>()
        for ((_, group) in words.groupBy { it.file to it.voice }) {
            val sorted = group.sortedBy { it.start }
            var run = mutableListOf<WordGroup>()
            fun flush() {
                if (run.size >= 2) out += Phrase(run.toList())
                run = mutableListOf()
            }
            for (word in sorted) {
                val previous = run.lastOrNull()
                if (previous != null) {
                    val gap = word.start - previous.end
                    if (gap > RATE * 0.45) flush()
                }
                run += word
            }
            flush()
        }
        return out.distinctBy { Triple(it.file, it.start, it.end) }
    }

    private fun buildVoices(clips: List<Clip>): List<Voice> {
        return clips.map { clip ->
            val voice = Voice(
                name = clip.voiceName,
                gender = clip.voiceGender,
                language = clip.reading.language,
                pitchHz = clip.vocal?.value("f0_median_hz")?.takeIf { it.isFinite() && it > 0.0 }
                    ?: clip.frames.mapNotNull { it.f0.takeIf { f -> !f.isNaN() } }.sorted()
                        .let { if (it.isEmpty()) 120.0 else it[it.size / 2] },
                formant1 = clip.vocal?.value("formant_1_hz")?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                formant2 = clip.vocal?.value("formant_2_hz")?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                formant3 = clip.vocal?.value("formant_3_hz")?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                rms = clip.frames.map { it.rms }.average(),
                clips = 1,
            )
            voice
        }.distinctBy { it.name }
            .sortedWith(compareBy({ it.language }, { it.gender }, { it.name }))
    }

    private fun unitInput(unit: Unit, voice: Voice, settings: Settings): DoubleArray {
        val vocabulary = PrismPhonemes.SYMBOLS.size
        val input = DoubleArray(vocabulary * 3 + Net.INPUT_SCALARS)
        fun oneHot(offset: Int, value: String) {
            val id = PrismPhonemes.idOf(value)
            if (id in 0 until vocabulary) input[offset + id] = 1.0
        }
        oneHot(0, unit.left)
        oneHot(vocabulary, unit.symbol)
        oneHot(vocabulary * 2, unit.right)
        val base = vocabulary * 3
        input[base] = 0.0
        input[base + 1] = if (unit.symbol in PAUSES) 1.0 else 0.0
        input[base + 2] = if (unit.right in PAUSES) 1.0 else 0.0
        input[base + 3] = log2((voice.pitchHz / 120.0).coerceAtLeast(0.1))
        input[base + 4] = classOf(unit.symbol) / 5.0
        input[base + 5] = 0.0
        input[base + 6] = 0.0
        input[base + 7] = 1.0
        input[base + 8] = 0.0
        return input
    }

    // ── Synthesis DSP ──────────────────────────────────────────────────────────────────────────

    /**
     * Pitch-synchronous overlap-add.
     *
     * Output grain `n` sits at `n * targetPeriod` and reads the source at
     * `n * targetPeriod / beta`, where beta is the requested time scale. Grain spacing sets the
     * pitch, the number of grains sets the duration, and the grain waveform is the recording
     * itself, so formants and timbre survive the shift.
     */
    internal fun tdPsola(
        input: FloatArray,
        rate: Int,
        srcF0: Double,
        dstF0: Double,
        targetSamples: Int,
    ): FloatArray {
        if (input.isEmpty() || targetSamples <= 0) return FloatArray(0)
        if (srcF0 < 45.0 || dstF0 < 45.0) return resampleToLength(input, targetSamples)
        val srcPeriod = rate / srcF0
        val dstPeriod = rate / dstF0
        if (srcPeriod < 8 || dstPeriod < 8) return resampleToLength(input, targetSamples)
        val marks = pitchMarks(input, srcPeriod)
        if (marks.size < 2) return resampleToLength(input, targetSamples)
        val beta = targetSamples.toDouble() / input.size
        val grain = (2.0 * srcPeriod).toInt().coerceAtLeast(8)
        val output = FloatArray(targetSamples + grain + 2)
        val weight = FloatArray(output.size)
        val hann = FloatArray(grain) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (grain - 1).coerceAtLeast(1))).toFloat()
        }
        val count = ceil(targetSamples / dstPeriod).toInt()
        for (n in 0 until count) {
            val outPosition = (n * dstPeriod).toInt()
            if (outPosition >= targetSamples) break
            val source = (outPosition / beta).toInt().coerceIn(0, input.size - 1)
            var mark = marks[0]
            for (candidate in marks) {
                if (candidate <= source) mark = candidate else break
            }
            val start = outPosition - grain / 2
            for (index in 0 until grain) {
                val target = start + index
                if (target < 0 || target >= output.size) continue
                val sourceIndex = mark - grain / 2 + index
                if (sourceIndex < 0 || sourceIndex >= input.size) continue
                output[target] += input[sourceIndex] * hann[index]
                weight[target] += hann[index]
            }
        }
        for (index in 0 until targetSamples) {
            if (weight[index] > 1e-4f) output[index] /= weight[index]
        }
        // The first and last grains are cut mid-window by the target length. Without a short
        // edge fade that truncation is a step into the next unit -- a click at every voiced
        // phone boundary, heard as crackle rather than speech. Two milliseconds is shorter than
        // a pitch period at 100 Hz, so it removes the step without removing the phone.
        val edge = min(rate / 500, targetSamples / 2).coerceAtLeast(1)
        for (index in 0 until edge) {
            val gain = index.toFloat() / edge
            output[index] *= gain
            output[targetSamples - 1 - index] *= gain
        }
        return output.copyOf(targetSamples)
    }

    /** Glottal marks spaced near [period], each placed on the local absolute peak. */
    private fun pitchMarks(input: FloatArray, period: Double): IntArray {
        if (input.size < period * 2) return IntArray(0)
        val marks = ArrayList<Int>()
        var position = 0
        var first = 0
        var firstPeak = 0.0f
        val firstEnd = min(input.size, period.toInt().coerceAtLeast(2))
        for (index in 0 until firstEnd) {
            val value = abs(input[index])
            if (value > firstPeak) {
                firstPeak = value
                first = index
            }
        }
        marks += first
        position = first
        while (true) {
            val expected = (position + period).toInt()
            if (expected >= input.size) break
            val radius = (period * 0.28).toInt().coerceAtLeast(2)
            var peakIndex = expected
            var peakValue = -1f
            for (index in max(0, expected - radius)..min(input.size - 1, expected + radius)) {
                val value = abs(input[index])
                if (value > peakValue) {
                    peakValue = value
                    peakIndex = index
                }
            }
            marks += peakIndex
            position = peakIndex
            if (marks.size > 2_000) break
        }
        return marks.toIntArray()
    }

    internal fun resampleToLength(input: FloatArray, target: Int): FloatArray {
        if (target <= 0) return FloatArray(0)
        if (input.isEmpty()) return FloatArray(target)
        if (input.size == target) return input.copyOf()
        val out = FloatArray(target)
        val ratio = input.size.toDouble() / target
        for (index in 0 until target) {
            val position = index * ratio
            val base = position.toInt().coerceIn(0, input.size - 1)
            val next = min(base + 1, input.size - 1)
            val fraction = (position - base).toFloat()
            out[index] = input[base] * (1 - fraction) + input[next] * fraction
        }
        return out
    }

    /** Joins two renders, crossfading the tail of the first into the head of the second. */
    private fun join(left: FloatArray, right: FloatArray, crossfade: Int): FloatArray {
        if (left.isEmpty()) return right.copyOf()
        if (right.isEmpty()) return left.copyOf()
        val overlap = minOf(crossfade, left.size / 2, right.size / 2).coerceAtLeast(1)
        val merged = FloatArray(left.size + right.size - overlap)
        left.copyInto(merged, 0)
        // Equal-power, not linear. Two uncorrelated stretches of speech lose about 3 dB at the
        // middle of a linear crossfade, which is heard as a soft hole between every word. The
        // sine/cosine pair keeps the sum of squares constant across the join.
        for (index in 0 until overlap) {
            val angle = (index + 0.5) / overlap * (PI / 2.0)
            val fadeIn = sin(angle).toFloat()
            val fadeOut = cos(angle).toFloat()
            merged[left.size - overlap + index] =
                left[left.size - overlap + index] * fadeOut + right[index] * fadeIn
        }
        for (index in overlap until right.size) {
            merged[left.size - overlap + index] = right[index]
        }
        return merged
    }

    /** DC removal, gentle warmth, consistent level and a short fade at both ends. */
    private fun master(samples: FloatArray, settings: Settings): FloatArray {
        if (samples.isEmpty()) return samples
        var mean = 0.0
        for (sample in samples) mean += sample
        mean /= samples.size
        var previous = 0.0f
        // A one-pole low-pass blended with the dry signal: the warmth control cannot introduce a
        // resonance, so it cannot make the voice sound hollow or cupped. 0 leaves the recording
        // alone; 1 takes the darker, close-miked half of the blend.
        // A 1.3 kHz one-pole blended into the dry voice is exactly the "recorded through a sock"
        // colour listeners call a bad microphone. The warmth control stays, but its filter now
        // sits high enough to remove edge rather than presence, and its maximum blend is small.
        val mix = (settings.warmth * 0.22).toFloat()
        for (index in samples.indices) {
            val dry = (samples[index] - mean).toFloat()
            previous += (dry - previous) * 0.65f
            samples[index] = (dry * (1f - mix) + previous * mix).toFloat()
        }
        var sum = 0.0
        for (sample in samples) sum += sample * sample.toDouble()
        val rms = sqrt(sum / samples.size).coerceAtLeast(1e-9)
        val gain = (0.12 / rms).coerceIn(0.2, 40.0)
        for (index in samples.indices) samples[index] = (samples[index] * gain).toFloat()
        // A look-ahead limiter rather than one global scale factor. Scaling the whole clip down
        // to fit its loudest join leaves the speech quiet and still lets the transient click; an
        // envelope that only ducks the peaks keeps the sentence at speech level.
        val threshold = 0.88f
        var envelope = 1.0f
        for (index in samples.indices) {
            val magnitude = abs(samples[index])
            val target = if (magnitude > threshold) threshold / magnitude else 1.0f
            envelope = if (target < envelope) target else envelope + (target - envelope) * 0.0015f
            samples[index] *= envelope
        }
        val fade = min(RATE / 50, samples.size / 2)
        for (index in 0 until fade) {
            val gainOut = index.toFloat() / fade
            samples[index] *= gainOut
            samples[samples.size - 1 - index] *= gainOut
        }
        return samples
    }

    // ── Files ──────────────────────────────────────────────────────────────────────────────────

    fun write(model: Model, directory: File = model.directory): List<File> {
        val target = File(directory, DIRECTORY).apply { mkdirs() }
        // Units reference recordings by name. They live in the dataset folder itself, so the voice
        // folder only carries the description and the weights. A copied model keeps working as long
        // as its audio came with it, and the error names the missing recording when it did not.
        val document = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("sample_rate", RATE.toLong())
            put("unit_count", model.units.size.toLong())
            put("parameter_count", model.net.parameterCount().toLong())
            put("loss", model.loss)
            put("duration_accuracy", model.accuracy)
            put("steps", model.steps.toLong())
            put("languages", JSONArray(model.languages))
            put("coverage", coverage(model.units))
            put("settings", model.settings.toJson())
            put("voices", JSONArray(model.voices.map { it.toJson() }))
            put("units", JSONArray(model.units.map { it.toJson() }))
            put("words", JSONArray(model.words.map { it.toJson() }))
            put(
                "note",
                "Units cut from this dataset's recordings, aligned with Prism's own Viterbi " +
                    "aligner and joined by PSOLA at a contour predicted by neural_voice.wts. No " +
                    "pretrained speech model is used.",
            )
            put("built_at", System.currentTimeMillis())
        }
        val description = File(target, FILE)
        description.writeText(document.toString())
        val weights = File(target, WEIGHTS_FILE)
        TrainerKit.WeightFile.write(
            weights,
            MAGIC,
            intArrayOf(model.net.input, model.net.hidden, model.net.embedding, 3),
            model.net.parameters,
        )
        // Also at the dataset root, which is where the engine looks first. Keeping the canonical
        // artifact in one place and a pointer here would be a second format to keep in step.
        File(directory, FILE).writeText(document.toString())
        File(directory, WEIGHTS_FILE).writeText("${DIRECTORY}/$WEIGHTS_FILE\n")
        return listOf(target, description, weights)
    }

    /** Writes [model] into its own folder, so several voices can live side by side. */
    fun write(model: Model) {
        write(model, model.directory)
    }

    fun load(directory: File): Model? {
        val candidates = listOf(directory, File(directory, DIRECTORY))
        for (folder in candidates) {
            val description = File(folder, FILE)
            if (!description.isFile) continue
            val json = runCatching { JSONObject(description.readText()) }.getOrNull() ?: continue
            if (json.optString("format", "") != FORMAT) continue
            val settings = Settings.of(json.optJSONObject("settings"))
            val voices = json.optJSONArray("voices")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    Voice(
                        name = entry.optString("name", "Voice"),
                        gender = entry.optString("gender", "neutral"),
                        language = entry.optString("language", "en"),
                        pitchHz = entry.optDouble("pitch_hz", 120.0),
                        formant1 = entry.optDouble("formant_1_hz", 0.0),
                        formant2 = entry.optDouble("formant_2_hz", 0.0),
                        formant3 = entry.optDouble("formant_3_hz", 0.0),
                        rms = entry.optDouble("rms", 0.1),
                        clips = entry.optInt("clips", 1),
                    )
                }
            }.orEmpty()
            val units = json.optJSONArray("units")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    Unit(
                        symbol = entry.optString("symbol", ""),
                        file = entry.optString("file", ""),
                        start = entry.optInt("start", 0),
                        end = entry.optInt("end", 0),
                        f0 = entry.optDouble("f0_hz", 0.0),
                        rms = entry.optDouble("rms", 0.0),
                        centroid = entry.optDouble("centroid_hz", 0.0),
                        language = entry.optString("language", "en"),
                        gender = entry.optString("gender", "neutral"),
                        voice = entry.optString("voice", ""),
                        left = entry.optString("left", ""),
                        right = entry.optString("right", ""),
                        voicing = entry.optDouble("voicing", if (entry.optDouble("f0_hz", 0.0) > 0.0) 1.0 else 0.0),
                    )
                }
            }.orEmpty()
            if (units.isEmpty() || voices.isEmpty()) continue
            val weightFile = if (File(folder, WEIGHTS_FILE).isFile && File(folder, WEIGHTS_FILE).length() > 64) {
                File(folder, WEIGHTS_FILE)
            } else {
                File(File(directory, DIRECTORY), WEIGHTS_FILE)
            }
            if (!weightFile.isFile) continue
            val vocabulary = PrismPhonemes.SYMBOLS.size
            val input = vocabulary * 3 + Net.INPUT_SCALARS
            val net = Net(
                input = input,
                hidden = settings.hidden,
                embedding = settings.embedding,
                w1 = TrainerKit.Param("prosody.w1", settings.hidden, input),
                b1 = TrainerKit.Param("prosody.b1", settings.hidden, 1),
                w2 = TrainerKit.Param("prosody.w2", settings.embedding, settings.hidden),
                b2 = TrainerKit.Param("prosody.b2", settings.embedding, 1),
                w3 = TrainerKit.Param("prosody.w3", 3, settings.embedding),
                b3 = TrainerKit.Param("prosody.b3", 3, 1),
            )
            val ok = TrainerKit.WeightFile.read(
                weightFile,
                MAGIC,
                intArrayOf(input, settings.hidden, settings.embedding, 3),
                net.parameters,
            )
            if (!ok) continue
            val words = json.optJSONArray("words")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(WordGroup::fromJson)
                }
            }.orEmpty().ifEmpty { buildWordGroups(units) }
            return Model(
                directory = directory,
                voices = voices,
                units = units,
                settings = settings,
                net = net,
                languages = json.optJSONArray("languages")?.let { array ->
                    (0 until array.length()).map { array.optString(it, "en") }
                }.orEmpty().ifEmpty { voices.map { it.language }.distinct() },
                words = words,
                loss = json.optDouble("loss", 0.0),
                accuracy = json.optDouble("duration_accuracy", 0.0),
                steps = json.optInt("steps", 0),
            )
        }
        return null
    }

    /** True when [directory] has a readable neural voice. */
    fun ready(directory: File): Boolean = load(directory) != null

    fun describe(directory: File): JSONObject? {
        val file = File(directory, FILE)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }
            .getOrNull()?.takeIf { it.optString("format", "") == FORMAT }
    }

    fun summary(model: Model): String {
        val languages = model.languages.joinToString(", ")
        return "${model.units.size} unit(s) · ${model.voices.size} voice(s) · " +
            "${ModelCapacity.count(model.net.parameterCount().toLong())} prosody parameters · " +
            "$languages · covers ${(coverage(model.units) * 100).toInt()}% of the phoneme table"
    }
}
