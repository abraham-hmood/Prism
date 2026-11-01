package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One decoded clip: mono, in [-1, 1], at [sampleRate].
 *
 * Prism's recorders and every engine that consumes audio produce this shape, which is why the
 * analysis in [VocalAnalysis] is written once for both platforms and every container.
 */
class SpeechClip(val samples: FloatArray, val sampleRate: Int) {

    val seconds: Double get() = samples.size.toDouble() / sampleRate.coerceAtLeast(1)

    /**
     * Linear-interpolated resample to [rate].
     *
     * Needed for exactly one caller: whisper is trained at 16 kHz and refuses anything else, so a
     * dataset recorded at 44.1 kHz has to be converted before it can be transcribed for its language.
     */
    fun resampled(rate: Int): SpeechClip {
        if (rate <= 0 || rate == sampleRate || samples.isEmpty()) return this
        val out = FloatArray(max(1, (samples.size.toDouble() * rate / sampleRate).toInt()))
        val ratio = sampleRate.toDouble() / rate
        for (i in out.indices) {
            val position = i * ratio
            val index = position.toInt().coerceIn(0, samples.size - 1)
            val next = min(index + 1, samples.size - 1)
            val fraction = (position - index).toFloat()
            out[i] = samples[index] * (1 - fraction) + samples[next] * fraction
        }
        return SpeechClip(out, rate)
    }

    /**
     * The samples as 16-bit little-endian PCM, which is the form every transcription engine takes.
     *
     * Scaled by 32767 and clamped, so a sample at or beyond full scale comes back as full scale
     * instead of wrapping to the opposite sign -- the conversion whisper-style engines assume, and the
     * inverse of the float conversion in [WhisperCppEngine] rather than a second convention.
     */
    fun pcm16(): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = (samples[index] * 32767f).toInt().coerceIn(-32_768, 32_767)
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/**
 * Reading audio files into [SpeechClip]s.
 *
 * THE PLATFORM DECODES WHEN IT CAN. Android has MediaExtractor/MediaCodec and the desktop has
 * javax.sound.sampled; both read mp3, flac, ogg and m4a, which no shared Kotlin code can. The
 * built-in reader below covers WAV, which is what trainers and TTS datasets are usually shipped as,
 * so the feature pass still works on a machine where the platform hook was never installed -- a test
 * JVM, for instance.
 */
object AudioDecoding {

    /**
     * Set by the platform. Returns null when it cannot read the file, which falls back to the WAV
     * reader rather than failing the dataset: a dataset of mixed formats should lose nothing any
     * reader in the process can open.
     */
    @Volatile
    var decoder: ((File) -> SpeechClip?)? = null

    fun read(file: File): SpeechClip? {
        if (!file.isFile) return null
        decoder?.let { decode ->
            runCatching { decode(file) }.getOrNull()?.let { return it }
        }
        return readWav(file)
    }

    /** Extensions the built-in reader understands, so a caller can say what it will attempt. */
    val WAV_EXTENSIONS = setOf("wav", "wave")

    /**
     * The longest single clip this reader decodes, in seconds.
     *
     * Ten minutes of speech is an unusually long training example; anything past this is a whole
     * recording rather than a clip, and decoding it would allocate hundreds of megabytes of samples
     * that no metric here wants. A dataset with one in it keeps working and says what it dropped.
     */
    private const val MAX_CLIP_SECONDS = 600

    /**
     * A RIFF/WAVE reader: PCM 8/16/24/32-bit and IEEE float, mono or stereo.
     *
     * Channel data is averaged to mono. Trained voice models are mono and a stereo take is the same
     * performance in two channels; averaging is what every trainer does with one, and it halves the
     * work for every metric downstream.
     *
     * IT STREAMS, RATHER THAN READING THE WHOLE FILE IN. `file.readBytes()` was the first version of
     * this and it is where a "GC overhead limit exceeded" on a phone comes from: twenty minutes of
     * 48 kHz stereo is 230 MB of bytes held alive, and the samples decoded out of them are another
     * 115 MB on top -- two live copies of one clip at the moment the collector is still working on
     * the previous clip's. Reading the data chunk in whole-frame blocks through one reused scratch
     * buffer means the peak is the samples plus 64 KB, whatever the file's length.
     */
    fun readWav(file: File): SpeechClip? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < 44) return null
            val signature = ByteArray(12)
            if (input.read(signature) != 12) return null
            if (String(signature, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(signature, 8, 4, Charsets.US_ASCII) != "WAVE") return null

            var format = 1
            var channels = 1
            var rate = 16_000
            var bits = 16
            var dataStart = -1L
            var dataLength = 0L
            var position = 12L
            while (position + 8 <= length) {
                input.seek(position)
                val chunkHeader = ByteArray(8)
                if (input.read(chunkHeader) != 8) break
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                val body = position + 8
                when (id) {
                    "fmt " -> {
                        // A fmt chunk is 16 bytes, 18 or 40 with the extensible extension. Cap the
                        // read at what can be described so a corrupt size cannot ask for a gigabyte.
                        val wanted = size.coerceAtMost(64L).toInt()
                        val chunk = ByteArray(wanted)
                        if (input.read(chunk) != wanted) break
                        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        format = buffer.short.toInt()
                        channels = max(1, buffer.short.toInt())
                        rate = max(1, buffer.int)
                        buffer.int // byte rate
                        buffer.short // block align
                        bits = buffer.short.toInt()
                        // 0xFFFE is WAVE_FORMAT_EXTENSIBLE: the real format is in the sub-format
                        // GUID's first two bytes, which sit after the 22-byte extension.
                        if (format == 0xFFFE && size >= 40 && wanted >= 26) {
                            buffer.short // extension size
                            buffer.short // valid bits
                            buffer.int // channel mask
                            format = buffer.short.toInt()
                        }
                    }
                    "data" -> {
                        dataStart = body
                        dataLength = min(size, length - body)
                    }
                }
                if (dataStart >= 0) break
                // Chunks are word-aligned: an odd size is followed by a pad byte.
                position = body + size + (size and 1L)
            }
            if (dataStart < 0 || dataLength <= 0) return null

            val bytesPerSample = (bits + 7) / 8
            if (bytesPerSample <= 0) return null
            val frameSize = bytesPerSample * channels
            if (frameSize <= 0) return null
            val framesInFile = dataLength / frameSize
            val frames = framesInFile.coerceAtMost(MAX_CLIP_SECONDS.toLong() * rate).toInt()
            if (frames <= 0) return null
            if (framesInFile > frames) {
                PrismPlatform.log.warn(
                    "Prism/speech",
                    "${file.name} is ${framesInFile / rate}s long; only the first " +
                        "${MAX_CLIP_SECONDS}s of it are analysed",
                )
            }

            val out = FloatArray(frames)
            val blockFrames = max(1, BLOCK_BYTES / frameSize)
            val block = ByteArray(blockFrames * frameSize)
            val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            input.seek(dataStart)
            var index = 0
            while (index < frames) {
                val wanted = min(blockFrames, frames - index) * frameSize
                val read = input.read(block, 0, wanted)
                if (read <= 0) break
                // Whole frames only: a partial frame is a truncated file, not half a sample.
                val complete = read / frameSize
                if (complete <= 0) break
                buffer.clear()
                buffer.limit(complete * frameSize)
                for (frame in 0 until complete) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += when {
                            format == 3 -> buffer.float
                            bits == 8 -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
                            bits == 16 -> buffer.short / 32768f
                            bits == 24 -> {
                                val low = buffer.get().toInt() and 0xFF
                                val middle = buffer.get().toInt() and 0xFF
                                val high = buffer.get()
                                ((high.toInt() shl 16) or (middle shl 8) or low) / 8388608f
                            }
                            bits == 32 -> buffer.int / 2147483648f
                            else -> return null
                        }
                    }
                    out[index + frame] = sum / channels
                }
                index += complete
            }
            if (index == 0) return null
            SpeechClip(if (index == frames) out else out.copyOf(index), rate)
        }
    }.getOrNull()

    /** Scratch size for the streaming read: whole frames per read, so no sample straddles two. */
    private const val BLOCK_BYTES = 1 shl 16
}

/**
 * The vocal features one clip carries, ready to be written as a CSV row.
 *
 * ## Why every one of these is extracted
 *
 * A model trained on transcripts alone learns WHAT was said and nothing about HOW. Everything that
 * makes a voice sound like a person rather than a reader -- the pitch moving with meaning, the jitter
 * and shimmer of real vocal folds, the breath before a long phrase, the creak at the end of a
 * sentence, the particular shape of one speaker's vocal tract -- lives in these numbers, and a
 * trainer that never sees them cannot learn them. [VocalAnalysis] measures them per file;
 * [SpeechDataset] writes them beside each clip's path and folds them into the dataset's own CSV,
 * which is what "train on them alongside the audio" means in practice.
 *
 * ## The column set is fixed
 *
 * Every clip produces the same columns in the same order ([COLUMNS]), including the ones that could
 * not be measured (NaN, written as an empty cell). A CSV whose columns depend on what was found in
 * each file is unreadable by every trainer that reads CSVs.
 */
class VocalFeatures(
    val file: String,
    val language: String,
    val languageConfidence: Double,
    val columns: LinkedHashMap<String, Double>,
) {

    fun value(key: String): Double = columns[key] ?: Double.NaN

    /** One CSV record: the path, the language, then every column, in [COLUMNS] order. */
    fun toCsvRow(): List<String> = buildList {
        add(file)
        add(language)
        COLUMNS.forEach { column -> add(format(columns[column])) }
    }

    companion object {

        /**
         * Every column, grouped by what it describes.
         *
         * The grouping is not decoration: it is what the creator page shows after a run, and it is
         * the order a trainer finds them in -- the linguistic side of prosody first, then the
         * involuntary detail, then identity, then timing.
         */
        val GROUPS: LinkedHashMap<String, List<String>> = linkedMapOf(
            "prosody" to listOf(
                "f0_mean_hz", "f0_median_hz", "f0_std_hz", "f0_variation", "f0_range_semitones",
                "f0_slope_semitones_per_s", "voiced_fraction", "speaking_rate_syllables_per_s",
                "rhythm_variability", "pause_count", "pause_ratio", "mean_pause_s", "longest_pause_s",
                "stress_contrast_db", "emphasis_per_min",
            ),
            "micro_variation" to listOf(
                "jitter_local_percent", "shimmer_local_percent", "hnr_db", "breathiness_index",
                "vocal_fry_fraction", "amplitude_irregularity", "breath_events_per_min",
                "onset_jitter_ms",
            ),
            "expression" to listOf(
                "intensity_mean_db", "intensity_range_db", "intensity_std_db",
                "spectral_centroid_hz", "spectral_flatness", "spectral_rolloff_hz",
                "register_shift_count_per_min",
            ),
            "source_filter" to listOf(
                "spectral_tilt_db_per_octave", "glottal_open_quotient_proxy", "harmonic_ratio",
                "subharmonic_ratio",
            ),
            "identity" to listOf(
                "ltas_0_125_hz_db", "ltas_125_250_hz_db", "ltas_250_500_hz_db", "ltas_500_1k_hz_db",
                "ltas_1k_2k_hz_db", "ltas_2k_3k_hz_db", "ltas_3k_4k_hz_db", "ltas_4k_8k_hz_db",
                "formant_1_hz", "formant_2_hz", "formant_3_hz", "formant_dispersion_hz",
            ),
            "timing" to listOf(
                "duration_s", "voiced_s", "unvoiced_s", "speech_ratio", "syllable_duration_mean_ms",
                "syllable_duration_variability", "consonant_transition_ms", "vowel_reduction",
                "coarticulation",
            ),
            "phonation" to listOf(
                "modal_fraction", "breathy_fraction", "creak_fraction", "falsetto_fraction",
                "register_switches_per_min",
            ),
        )

        /** Flat list of every feature column, header order. */
        val COLUMNS: List<String> = GROUPS.values.flatten()

        /**
         * What each column is measured in, so a recipe can tell a trainer which axis is which.
         *
         * Derived from the column's own name rather than written out sixty more times, which keeps
         * the two in step: a column cannot be added to [GROUPS] and be left without a unit here.
         */
        val units: Map<String, String> = COLUMNS.associateWith { unitFor(it) }

        private fun unitFor(column: String): String = when {
            column.startsWith("ltas") -> "dB"
            column.endsWith("_db_per_octave") -> "dB/octave"
            column.endsWith("_semitones_per_s") -> "semitones/s"
            column.endsWith("_semitones") -> "semitones"
            column.endsWith("_hz") -> "Hz"
            column.endsWith("_db") -> "dB"
            column.endsWith("_percent") -> "percent"
            column.endsWith("_per_min") -> "per minute"
            column.endsWith("_per_s") -> "per second"
            column.endsWith("_ms") -> "ms"
            column.endsWith("_s") -> "s"
            else -> ""
        }

        /** Header of the features CSV, in the same order as [toCsvRow]. */
        val HEADER: List<String> = listOf("file", "language") + COLUMNS

        /**
         * NaN is an empty cell: the measurement was not possible, which is not the same as zero.
         *
         * The locale is pinned to US, which is a correctness detail rather than a preference -- under
         * a locale that writes decimal commas, `%.4f` would produce `1,2345` and every numeric column
         * would split into two when the CSV was read back.
         */
        fun format(value: Double?): String =
            if (value == null || value.isNaN() || value.isInfinite()) "" else {
                String.format(Locale.US, "%.4f", value)
            }
    }
}

/**
 * Measures [VocalFeatures] from a clip.
 *
 * ## How it works
 *
 * One pass of overlapping frames -- 40 ms window, 10 ms hop -- and every frame contributes its
 * intensity, zero-crossing rate, magnitude spectrum, pitch and harmonicity. The metrics are then
 * aggregates over those frames and over the syllable and pause structure of the intensity envelope.
 * That is the whole design: measure locally, describe globally.
 *
 * ## What is a proxy and what is not
 *
 * Real analysis tools differ in what they can honestly claim, and so does this. Pitch, intensity,
 * spectral shape, timing, envelope structure, jitter, shimmer and the harmonic balance are measured
 * from the signal: perturbations walk the waveform's individual glottal cycles
 * ([periodPerturbation]) and the open-quotient column is the H1 - H2 harmonic difference in dB
 * ([harmonicBalance]), which is the acoustic reading the literature uses. Formants are the resonances
 * of an all-pole model of the tract, checked against a resonator cascade whose frequencies are known
 * to within a few percent (FormantEstimateTest).
 *
 * What remains an estimator rather than a measurement: `breathiness_index` is the high-band share of a
 * voiced frame's energy, `glottal_open_quotient_proxy` is H1 - H2 rather than a quotient in degrees of
 * opening (its column name says so), formant frequencies carry a few percent of bias on a very
 * periodic synthetic signal, and the language column is only as good as the transcriber installed.
 *
 * ## Why frames do not keep their spectra
 *
 * A ten-minute clip is sixty thousand frames, and holding a 512-bin spectrum for each of them is a
 * hundred megabytes of exactly the kind of live garbage that makes a collector give up. Everything
 * that needs the spectrum is reduced to a number INSIDE the frame loop -- the tilt from an averaged
 * spectrum, the harmonic ratio per frame -- so what is retained is a few dozen doubles per frame.
 */
object VocalAnalysis {

    /** Analysis window. 40 ms at 16 kHz is three or four pitch periods: long enough to be stable. */
    private const val WINDOW_MS = 40.0

    /** Hop. 10 ms is the usual frame rate for speech features and gives 100 frames a second. */
    private const val HOP_MS = 10.0

    private const val MIN_F0_HZ = 60.0
    private const val MAX_F0_HZ = 400.0

    /** A frame whose normalised autocorrelation peaks below this is not voiced. */
    private const val VOICING_THRESHOLD = 0.35

    /** Pause: at least this far below the clip's own speech level, for at least this long. */
    private const val PAUSE_DROP_DB = 25.0
    private const val MIN_PAUSE_MS = 150.0

    /** Minimum spacing between syllable peaks; below this it is one sound, not two. */
    private const val MIN_SYLLABLE_MS = 80.0

    /** How much waveform one perturbation measurement looks at, in milliseconds. */
    private const val PERIOD_WINDOW_MS = 120.0

    /** Below this many cycles in the window there is no perturbation to measure. */
    private const val MIN_PERIODS_PER_FRAME = 4

    /** A ceiling on the peak walk, so a very low pitch cannot walk a whole clip. */
    private const val MAX_PERIODS_PER_FRAME = 40

    /** How far the measured period may sit from the frame's pitch before it is not that voice. */
    private const val PERIOD_TOLERANCE = 0.25

    /**
     * How far either side of the expected peak the period search looks, as a fraction of a period.
     *
     * Four tenths of a period either side: wide enough that the peak is inside the window as the
     * measured period drifts, narrow enough that the search cannot wander onto the neighbouring
     * cycle's peak -- which is a different amplitude, and would be counted as shimmer.
     */
    private const val PERIOD_SEARCH_FRACTION = 0.4

    /** The band formants are looked for in. Below 150 Hz is the fundamental, above 3.5 kHz is F4's job. */
    private const val FORMANT_MIN_HZ = 150.0
    private const val FORMANT_MAX_HZ = 3500.0

    /**
     * A resonance whose pole is broader than this is not a formant.
     *
     * Wide on purpose: a breathy vowel or a frame with noise in it carries formants up to a few hundred
     * Hz wide, and the 400-600 Hz the textbooks quote assumes a clean, well-fitted frame. What this
     * threshold is for is the other kind of pole -- the one that models the SOURCE rather than the
     * tract, which on the resonator cascade in FormantEstimateTest comes back at 1040 Hz with a
     * bandwidth of 581 Hz next to the real resonances' 58 to 218 Hz.
     */
    private const val MAX_FORMANT_BANDWIDTH_HZ = 1500.0

    /** Two poles closer than this describe one resonance, and the sharper of them is the one it is. */
    private const val FORMANT_MERGE_HZ = 200.0

    /** Iterations of the simultaneous root finder. It converges in tens, not hundreds. */
    private const val ROOT_ITERATIONS = 80

    /**
     * How far below a clip's median voiced level a frame may sit and still be measured for formants.
     *
     * Wide enough for the natural variation within a syllable and narrow enough to leave out its tail.
     */
    private const val FORMANT_LEVEL_DB = 10.0

    /**
     * LPC order for the formant estimate.
     *
     * Higher than the textbook `rate / 1000 + 2`, deliberately, and the difference was measured on a
     * synthetic vowel whose formants are known. A 16 kHz voice is a comb of sharp harmonics that an
     * all-pole envelope cannot represent, so part of the pole budget always goes on following the comb;
     * at order 18 what is left describes the tract so badly that F1 came back at 1.5 kHz for a
     * resonance at 700 Hz. At 26 the two lowest poles sit on the envelope (measured: 672 Hz and
     * 1239 Hz against 700 and 1200 built in), and the comb's poles are the ones the bandwidth filter
     * then throws away.
     */
    private const val LPC_ORDER = 26

    /** How much waveform the all-pole model is fitted to: longer is a sharper model of the envelope. */
    private const val LPC_WINDOW_SAMPLES = 2048

    /** The usual speech pre-emphasis at 16 kHz, which removes the glottal tilt from the model. */
    private const val PRE_EMPHASIS = 0.97f

    private val LTAS_BANDS = listOf(
        0.0 to 125.0, 125.0 to 250.0, 250.0 to 500.0, 500.0 to 1000.0,
        1000.0 to 2000.0, 2000.0 to 3000.0, 3000.0 to 4000.0, 4000.0 to 8000.0,
    )

    /**
     * Measures [clip], naming it [file] in the result.
     *
     * [language] arrives from [LanguageId] rather than being detected here: language identification
     * needs a model of language, and this file is a model of a voice.
     */
    fun extract(
        file: String,
        clip: SpeechClip,
        language: String = "",
        languageConfidence: Double = 0.0,
    ): VocalFeatures {
        val values = LinkedHashMap<String, Double>()
        VocalFeatures.COLUMNS.forEach { values[it] = Double.NaN }
        val analysis = analyse(clip)
        fill(values, analysis, clip)
        return VocalFeatures(file, language, languageConfidence, values)
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────

    /**
     * One frame's acoustics, as the local acoustic model consumes them.
     *
     * A reduced view of [Frame]: the numbers a frame predicts the next one from, and nothing that
     * needs the clip's context to interpret. [bands] is the frame's energy in each of
     * [LTAS_BANDS], in the same order the LTAS columns use.
     */
    class FrameSample(
        val time: Double,
        /** NaN when the frame is unvoiced. */
        val f0: Double,
        val db: Double,
        val centroid: Double,
        val rolloff: Double,
        val flatness: Double,
        val bands: FloatArray,
    )

    /**
     * The frame series of [clip], for a model that predicts one frame from the ones before it.
     *
     * This is the same framing the measurements are taken on -- 40 ms windows at a 10 ms hop -- so a
     * model trained here sees the same view of the signal the columns in the CSV were measured from.
     */
    fun framesOf(clip: SpeechClip): List<FrameSample> =
        analyse(clip).frames.map { frame ->
            FrameSample(
                time = frame.time,
                f0 = frame.f0,
                db = frame.db,
                centroid = frame.centroid,
                rolloff = frame.rolloff,
                flatness = if (frame.flatness.isNaN()) 0.0 else frame.flatness,
                bands = frame.bands.copyOf(),
            )
        }

    private class Frame(
        val time: Double,
        val rms: Float,
        val db: Double,
        val centroid: Double,
        val rolloff: Double,
        val flatness: Double,
        val bands: FloatArray,
        /** NaN when the frame is unvoiced. */
        val f0: Double,
        val harmonicity: Double,
        /** H1 minus H2 in dB: the acoustic open-quotient proxy. NaN when the frame is unvoiced. */
        val h1MinusH2: Double,
        /** Energy at whole multiples of the pitch, over the frame's total, 0-4 kHz. */
        val harmonicRatio: Double,
        /** Energy an octave below the pitch over the energy AT it: a subharmonic break. */
        val subharmonicRatio: Double,
    )

    private class Analysis(
        val frames: List<Frame>,
        val sampleRate: Int,
        val hop: Int,
        val samples: FloatArray,
        /** Mean magnitude spectrum over every frame, for the spectral tilt and nothing else. */
        val averageSpectrum: DoubleArray,
        val binWidth: Double,
    ) {
        val voiced: List<Frame> get() = frames.filter { !it.f0.isNaN() }
        val db: DoubleArray get() = DoubleArray(frames.size) { frames[it].db }
    }

    private fun analyse(clip: SpeechClip): Analysis {
        val rate = clip.sampleRate.coerceAtLeast(8_000)
        val window = max(256, (rate * WINDOW_MS / 1000.0).toInt())
        val hop = max(1, (rate * HOP_MS / 1000.0).toInt())
        val fftSize = nextPowerOfTwo(window)
        val windowed = FloatArray(fftSize)
        val hann = FloatArray(window) { n -> (0.5 - 0.5 * cos(2.0 * Math.PI * n / window)).toFloat() }
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val bins = fftSize / 2 + 1
        val binWidth = rate.toDouble() / fftSize
        val averageSpectrum = DoubleArray(bins)
        // REUSED ACROSS FRAMES rather than allocated per frame. At a 10 ms hop this is a hundred 2 KB
        // arrays for every second of audio, and every value in each of them is overwritten before it
        // is read -- pure churn for the collector to walk, on a device where the collector is the
        // thing that runs out of patience first. Nothing below keeps a reference to it.
        val magnitude = FloatArray(bins)
        val frames = mutableListOf<Frame>()

        var start = 0
        while (start + window <= clip.samples.size) {
            var sumSquares = 0.0
            for (i in 0 until window) {
                val sample = clip.samples[start + i]
                sumSquares += sample * sample.toDouble()
                windowed[i] = sample * hann[i]
            }
            for (i in window until fftSize) windowed[i] = 0f
            val rms = sqrt(sumSquares / window).toFloat()

            System.arraycopy(windowed, 0, re, 0, fftSize)
            java.util.Arrays.fill(im, 0f)
            fft(re, im)
            for (bin in 0 until bins) {
                magnitude[bin] = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                averageSpectrum[bin] += magnitude[bin]
            }
            val total = magnitude.sumOf { it.toDouble() }.coerceAtLeast(1e-12)
            var centroid = 0.0
            var flatLog = 0.0
            var flatCount = 0
            for (bin in 0 until bins) {
                centroid += bin * binWidth * magnitude[bin]
                if (magnitude[bin] > 0f) {
                    flatLog += log10(magnitude[bin].toDouble())
                    flatCount++
                }
            }
            centroid /= total
            val flatness = if (flatCount > 0) {
                10.0.pow(flatLog / flatCount) / (total / bins)
            } else {
                Double.NaN
            }
            var cumulative = 0.0
            var rolloff = 0.0
            for (bin in 0 until bins) {
                cumulative += magnitude[bin]
                if (rolloff == 0.0 && cumulative >= total * 0.95) {
                    rolloff = bin * binWidth
                }
            }
            val bands = FloatArray(LTAS_BANDS.size)
            for ((index, band) in LTAS_BANDS.withIndex()) {
                var energy = 0.0
                for (bin in 0 until bins) {
                    val frequency = bin * binWidth
                    if (frequency >= band.first && frequency < band.second) {
                        energy += magnitude[bin] * magnitude[bin].toDouble()
                    }
                }
                bands[index] = energy.toFloat()
            }

            val (f0, harmonicity) = pitch(windowed, window, rate)
            val harmonics = harmonicStructure(f0, magnitude, binWidth)
            frames += Frame(
                time = start.toDouble() / rate,
                rms = rms,
                db = 20.0 * log10(rms.toDouble().coerceAtLeast(1e-7)),
                centroid = centroid,
                rolloff = rolloff,
                flatness = flatness,
                bands = bands,
                f0 = f0,
                harmonicity = harmonicity,
                h1MinusH2 = harmonics.third,
                harmonicRatio = harmonics.first,
                subharmonicRatio = harmonics.second,
            )
            start += hop
        }
        val frameCount = frames.size.coerceAtLeast(1).toDouble()
        for (bin in 0 until bins) averageSpectrum[bin] /= frameCount
        return Analysis(frames, rate, hop, clip.samples, averageSpectrum, binWidth)
    }

    /**
     * Harmonic energy against total energy, and the subharmonic ratio.
     *
     * A clean modal voice is almost all harmonics; a breathy or creaky one is not, and a register
     * break puts real energy an octave BELOW the pitch -- which is what the second figure watches
     * for, and what makes it a nonlinearity measurement rather than a duplicate of the first.
     */
    private fun harmonicStructure(
        f0: Double,
        magnitude: FloatArray,
        binWidth: Double,
    ): Triple<Double, Double, Double> {
        if (f0.isNaN() || f0 <= 0.0 || binWidth <= 0.0) {
            return Triple(Double.NaN, Double.NaN, Double.NaN)
        }
        var total = 0.0
        var harmonics = 0.0
        for (bin in 1 until magnitude.size) {
            val frequency = bin * binWidth
            if (frequency > 4000.0) break
            val energy = magnitude[bin] * magnitude[bin].toDouble()
            total += energy
            val nearest = Math.round(frequency / f0) * f0
            if (nearest >= f0 * 0.75 && abs(frequency - nearest) <= binWidth) harmonics += energy
        }
        if (total <= 0.0) return Triple(Double.NaN, Double.NaN, Double.NaN)
        val half = binIndex(f0 / 2.0, binWidth)
        val fundamental = binIndex(f0, binWidth)
        val halfEnergy = magnitude.getOrNull(half)?.let { it * it.toDouble() } ?: 0.0
        val fundamentalEnergy = magnitude.getOrNull(fundamental)?.let { it * it.toDouble() } ?: 0.0
        val subharmonic = if (fundamentalEnergy > 0.0) halfEnergy / fundamentalEnergy else Double.NaN
        return Triple(harmonics / total, subharmonic, harmonicBalance(f0, magnitude, binWidth))
    }

    /**
     * The two lowest harmonics' levels, in dB, and the difference between them.
     *
     * Each is read as the HIGHEST BIN within a tenth of its own frequency, because the harmonics of a
     * real voice never land exactly on a bin and a fixed bin would sample a valley instead of a peak
     * as the pitch moves. H1 - H2 is the acoustic proxy for how long the glottal folds stay open, and
     * it is a difference of two levels rather than a ratio, which is the unit the literature and the
     * column's own name (dB) use.
     */
    private fun harmonicBalance(f0: Double, magnitude: FloatArray, binWidth: Double): Double {
        if (binWidth <= 0.0) return Double.NaN
        if (2 * f0 + 150.0 > binWidth * (magnitude.size - 1)) return Double.NaN

        fun level(frequency: Double): Double {
            val span = max(1, (frequency * 0.1 / binWidth).toInt())
            val centre = binIndex(frequency, binWidth)
            val from = (centre - span).coerceAtLeast(0)
            val to = (centre + span).coerceAtMost(magnitude.size - 1)
            var peak = 0.0
            for (bin in from..to) peak = max(peak, magnitude[bin].toDouble())
            return if (peak <= 1e-9) Double.NaN else 20.0 * log10(peak)
        }

        val first = level(f0)
        val second = level(2 * f0)
        if (first.isNaN() || second.isNaN()) return Double.NaN
        return first - second
    }

    // ── Aggregation ─────────────────────────────────────────────────────────────────────────────

    private fun fill(values: LinkedHashMap<String, Double>, analysis: Analysis, clip: SpeechClip) {
        val frames = analysis.frames
        if (frames.size < 4) return
        val voiced = analysis.voiced
        val duration = clip.seconds
        values["duration_s"] = duration

        fillPitch(values, analysis, voiced, duration)
        fillProsody(values, analysis, voiced, duration)
        fillMicroVariation(values, analysis)
        fillExpression(values, analysis, voiced)
        fillSourceFilter(values, analysis, voiced)
        fillIdentity(values, analysis, voiced)
        fillTiming(values, analysis, duration)
        fillPhonation(values, voiced, duration)
    }

    private fun fillPitch(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        voiced: List<Frame>,
        duration: Double,
    ) {
        // The fraction of FRAMES that carry pitch: silence, breaths and unvoiced consonants are what
        // it leaves out, and a clip that is mostly pauses should say so.
        values["voiced_fraction"] =
            if (analysis.frames.isEmpty()) Double.NaN else voiced.size.toDouble() / analysis.frames.size
        if (voiced.isEmpty()) return

        val pitch = voiced.map { it.f0 }.sorted()
        val mean = pitch.average()
        val median = pitch[pitch.size / 2]
        val standardDeviation = sqrt(pitch.sumOf { (it - mean) * (it - mean) } / pitch.size)
        values["f0_mean_hz"] = mean
        values["f0_median_hz"] = median
        values["f0_std_hz"] = standardDeviation
        // Variation as a ratio rather than in Hz, so a bass and a soprano are comparable.
        values["f0_variation"] = if (mean > 0) standardDeviation / mean else Double.NaN
        // 5th to 95th percentile rather than min to max: one octave-doubling error at the edge of a
        // clip is not a two-octave range.
        val low = percentile(pitch, 0.05)
        val high = percentile(pitch, 0.95)
        values["f0_range_semitones"] =
            if (low > 0 && high > low) 12.0 * log2(high / low) else Double.NaN
        // Declination: pitch falls through a phrase in most languages, and how fast it falls is part
        // of what a sentence sounds like.
        values["f0_slope_semitones_per_s"] = slope(
            voiced.map { it.time },
            voiced.map { 12.0 * log2(it.f0 / median.coerceAtLeast(1e-6)) },
        )
    }

    private fun fillProsody(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        voiced: List<Frame>,
        duration: Double,
    ) {
        val db = analysis.db
        val sorted = db.sorted()
        val speechLevel = percentile(sorted, 0.9)
        val floor = speechLevel - PAUSE_DROP_DB
        val silent = db.map { it < floor }

        // Pauses: runs of silence long enough to be a pause rather than the gap between two words.
        val minimumFrames = (MIN_PAUSE_MS / HOP_MS).toInt()
        val pauses = runsOf(silent, true).filter { it.second - it.first >= minimumFrames }
        val pausedFrames = pauses.sumOf { it.second - it.first }
        values["pause_count"] = pauses.size.toDouble()
        values["pause_ratio"] = pausedFrames.toDouble() / db.size
        values["mean_pause_s"] =
            if (pauses.isEmpty()) 0.0 else pauses.sumOf { (it.second - it.first) * HOP_MS } / pauses.size / 1000.0
        values["longest_pause_s"] =
            if (pauses.isEmpty()) 0.0 else pauses.maxOf { it.second - it.first } * HOP_MS / 1000.0
        values["speech_ratio"] = 1.0 - values.getValue("pause_ratio")

        // Syllables: peaks of the intensity envelope, at least MIN_SYLLABLE_MS apart.
        val syllables = syllables(db)
        values["speaking_rate_syllables_per_s"] = syllables.size / duration.coerceAtLeast(1e-6)
        val intervals = syllables.zipWithNext { a, b -> (b - a) * HOP_MS }.filter { it > 0 }
        if (intervals.isNotEmpty()) {
            val mean = intervals.average()
            values["syllable_duration_mean_ms"] = mean
            values["syllable_duration_variability"] =
                sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size) / mean
        }

        // Stress: the loudest quarter of syllables against the quietest quarter. A language or a
        // speaker with real stress has a gap here; a flat machine reading has almost none.
        if (syllables.size >= 4) {
            val levels = syllables.map { db[it] }.sorted()
            val quarter = max(1, levels.size / 4)
            val loud = levels.takeLast(quarter).average()
            val quiet = levels.take(quarter).average()
            values["stress_contrast_db"] = loud - quiet
            val median = percentile(levels, 0.5)
            values["emphasis_per_min"] =
                levels.count { it > median + 6.0 } * 60.0 / duration.coerceAtLeast(1e-6)
        }

        values["voiced_s"] = voiced.size * HOP_MS / 1000.0
        values["unvoiced_s"] = (duration - values.getValue("voiced_s")).coerceAtLeast(0.0)
    }

    private fun fillMicroVariation(values: LinkedHashMap<String, Double>, analysis: Analysis) {
        val voiced = analysis.voiced
        if (voiced.isEmpty()) return
        // PERIOD TO PERIOD, from the waveform itself. Jitter and shimmer compare one glottal cycle
        // with the next, so what has to be measured is a sequence of period LENGTHS and period
        // AMPLITUDES -- not the difference between two 10 ms frame averages, which at 200 Hz folds
        // five cycles into one number and reports a smaller figure for the same voice. The earlier
        // version of this function did the latter, and the object's comment said so.
        val jitters = mutableListOf<Double>()
        val shimmers = mutableListOf<Double>()
        analysis.frames.forEachIndexed { index, frame ->
            if (frame.f0.isNaN()) return@forEachIndexed
            val perturbation = periodPerturbation(analysis, index, frame.f0) ?: return@forEachIndexed
            jitters += perturbation.first
            shimmers += perturbation.second
        }
        values["jitter_local_percent"] = if (jitters.isNotEmpty()) jitters.average() else Double.NaN
        values["shimmer_local_percent"] = if (shimmers.isNotEmpty()) shimmers.average() else Double.NaN

        val harmonicity = voiced.map { it.harmonicity }.filter { it > 0.0 }
        if (harmonicity.isNotEmpty()) {
            val mean = harmonicity.average()
            // HNR from the autocorrelation peak: the standard r / (1 - r) reading of it.
            values["hnr_db"] =
                10.0 * log10((mean / (1.0 - mean).coerceAtLeast(1e-6)).coerceAtLeast(1e-6))
        }

        // Breathiness: the share of a voiced frame's energy that sits above 2 kHz. A breathy voice
        // puts more of its energy there than a pressed one.
        var breathy = 0.0
        var measured = 0
        for (frame in voiced) {
            val total = frame.bands.sumOf { it.toDouble() }
            if (total <= 0.0) continue
            breathy += frame.bands.drop(4).sumOf { it.toDouble() } / total
            measured++
        }
        values["breathiness_index"] = if (measured > 0) breathy / measured else Double.NaN

        // Vocal fry: a very low pitch, or a pitch so irregular it is creak rather than a note.
        values["vocal_fry_fraction"] = voiced.count {
            it.f0 < 75.0 || (it.harmonicity < 0.45 && it.f0 < 110.0)
        }.toDouble() / voiced.size

        val levels = voiced.map { it.rms }
        val mean = levels.average()
        values["amplitude_irregularity"] =
            sqrt(levels.sumOf { (it - mean).toDouble().pow(2) } / levels.size) / mean.coerceAtLeast(1e-6)

        // A breath is a short unvoiced stretch with mid-band noise in it: an inhalation is audible
        // and has no pitch, which is what tells it apart from a stop closure.
        val breaths = voiced.zipWithNext().count { (a, b) ->
            b.time - a.time in 0.15..0.6 && b.centroid > 1500.0 && b.f0.isNaN()
        }
        values["breath_events_per_min"] =
            breaths * 60.0 / voiced.last().time.coerceAtLeast(1e-6)
    }

    private fun fillExpression(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        voiced: List<Frame>,
    ) {
        val db = analysis.db.sorted()
        values["intensity_mean_db"] = analysis.db.average()
        values["intensity_range_db"] = percentile(db, 0.95) - percentile(db, 0.05)
        values["intensity_std_db"] = run {
            val levels = analysis.db
            val mean = levels.average()
            sqrt(levels.sumOf { (it - mean) * (it - mean) } / levels.size)
        }
        if (voiced.isEmpty()) return
        values["spectral_centroid_hz"] = voiced.map { it.centroid }.average()
        values["spectral_flatness"] = voiced.map { it.flatness }.filter { !it.isNaN() }.average()
        values["spectral_rolloff_hz"] = voiced.map { it.rolloff }.average()
    }

    /**
     * Jitter and shimmer for one frame, in percent, from the cycles the waveform actually contains.
     *
     * The period the frame's own pitch implies seeds a peak search; each following peak is looked for
     * within half a period of where the previous one says it should be, so the search follows the
     * voice rather than the clock. Peaks are refined parabolically, because the difference between two
     * neighbouring period lengths is a fraction of a sample and its quantisation would be the noise
     * floor of the measurement.
     *
     * Nulls, in the order they are checked: a period too short to sample, too few periods in the
     * window, a peak that would have to be read off the search window's own edge, a mean period that
     * disagrees with the frame's pitch by more than [PERIOD_TOLERANCE] (so a stretch that is not
     * periodic cannot invent a perturbation figure) and silence instead of a voice. A null frame simply
     * does not contribute, which is what makes the average over a clip a mean of real measurements
     * rather than of guesses.
     */
    private fun periodPerturbation(analysis: Analysis, frameIndex: Int, f0: Double): Pair<Double, Double>? {
        val rate = analysis.sampleRate
        val period = rate / f0
        if (period < 2.0) return null
        val window = min(
            analysis.samples.size,
            (PERIOD_WINDOW_MS * rate / 1000.0).toInt().coerceAtLeast(64),
        )
        if (window < period * MIN_PERIODS_PER_FRAME) return null
        val centre = frameIndex * analysis.hop + analysis.hop / 2
        val start = (centre - window / 2).coerceIn(0, max(0, analysis.samples.size - window))
        val samples = analysis.samples
        val positions = mutableListOf<Double>()
        // THE WALK LOOKS OVER A WHOLE PERIOD, CENTRED ON WHERE THE NEXT ONE SHOULD BE, and the centre
        // follows the period it MEASURES rather than the pitch's nominal one. Two failures are being
        // avoided at once, and both were observed: a window narrower than the period ends up reading
        // the side of a peak instead of its top as the true period drifts away from the nominal one,
        // and a window centred on the nominal period loses the peak entirely on a voice whose pitch
        // estimate is a fraction low -- which is where the nulls and the twenty-five percent shimmer
        // came from. A voiced period has ONE dominant maximum, the glottal closing, so this finds the
        // same phase of the waveform in every cycle, which is what makes the differences between them
        // period lengths.
        var searchFrom = start.toDouble()
        var searchTo = start + period
        while (positions.size < MAX_PERIODS_PER_FRAME) {
            val from = max(start, searchFrom.toInt())
            val to = min(start + window - 1, searchTo.toInt())
            if (to - from < 3) break
            var best = from
            var bestValue = Double.NEGATIVE_INFINITY
            for (index in from..to) {
                val value = samples[index].toDouble()
                if (value > bestValue) {
                    bestValue = value
                    best = index
                }
            }
            // A maximum found AT the window's edge is the edge, not the waveform's peak, and both of
            // its coordinates are then wrong. The walk stops rather than reporting clipped cycles.
            if (best == from || best == to) break
            var refined = best.toDouble()
            if (best > 0 && best < samples.size - 1) {
                val left = samples[best - 1].toDouble()
                val middle = samples[best].toDouble()
                val right = samples[best + 1].toDouble()
                val denominator = left - 2 * middle + right
                if (abs(denominator) > 1e-12) {
                    refined += (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
                }
            }
            positions += refined
            // The period measured over the last two peaks, which is what the next window is centred on.
            val measuredPeriod =
                if (positions.size >= 2) positions.last() - positions[positions.size - 2] else period
            if (measuredPeriod <= 0.0) break
            searchFrom = refined + measuredPeriod * (1.0 - PERIOD_SEARCH_FRACTION)
            searchTo = refined + measuredPeriod * (1.0 + PERIOD_SEARCH_FRACTION)
            if (searchTo > start + window) break
        }
        if (positions.size < MIN_PERIODS_PER_FRAME) return null
        val lengths = positions.zipWithNext { a, b -> b - a }
        val meanLength = lengths.average()
        if (meanLength <= 0.0 || abs(meanLength - period) > period * PERIOD_TOLERANCE) return null
        // SHIMMER FROM THE CYCLE'S ENERGY, not from its single largest sample. The largest sample of a
        // voiced wave ripple-topped by two formants moves between ripples as the phase drifts, and a
        // peak that hops between two nearly-equal maxima reports that hop as amplitude variation --
        // measured at twelve to twenty-two percent shimmer on a signal whose amplitude really changes
        // by two or three. The RMS over one cycle is the same measurement without the hop in it.
        val levels = mutableListOf<Double>()
        for (index in 0 until positions.size - 1) {
            val from = positions[index].toInt().coerceIn(0, samples.size - 1)
            val to = positions[index + 1].toInt().coerceIn(from + 1, samples.size)
            var sum = 0.0
            for (sample in from until to) {
                val value = samples[sample].toDouble()
                sum += value * value
            }
            levels += sqrt(sum / (to - from).coerceAtLeast(1))
        }
        if (levels.size < MIN_PERIODS_PER_FRAME - 1) return null
        val meanLevel = levels.average()
        if (meanLevel <= 1e-6) return null
        val jitter = lengths.zipWithNext { a, b -> abs(b - a) }.average() / meanLength * 100.0
        val shimmer = levels.zipWithNext { a, b -> abs(b - a) }.average() / meanLevel * 100.0
        return jitter to shimmer
    }

    private fun fillSourceFilter(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        voiced: List<Frame>,
    ) {
        // Spectral tilt: the slope of the average log spectrum from 100 Hz to 4 kHz, in dB per
        // octave. The glottal source is what puts most of it there, which is why it is the classic
        // voice-quality measurement.
        var points = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (bin in 1 until analysis.averageSpectrum.size) {
            val frequency = bin * analysis.binWidth
            if (frequency < 100.0 || frequency > 4000.0) continue
            val magnitude = analysis.averageSpectrum[bin]
            if (magnitude <= 0.0) continue
            val x = log2(frequency)
            val y = 20.0 * log10(magnitude)
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
            points++
        }
        if (points > 2) {
            val denominator = points * sumXX - sumX * sumX
            if (abs(denominator) > 1e-9) {
                values["spectral_tilt_db_per_octave"] =
                    (points * sumXY - sumX * sumY) / denominator
            }
        }

        if (voiced.isEmpty()) return
        // H1 - H2, in dB: the amplitude difference between the first and second harmonics. It is the
        // standard ACOUSTIC reading of the glottal open quotient -- a pulse that stays open longer is
        // more SINUSOIDAL, so its second harmonic sits further below the first and the difference goes
        // up; a pulse that closes sharply is rich in high harmonics and the difference is small. It is
        // computed from the harmonics, not estimated from the waveform's shape: the earlier version of
        // this column was the waveform's third moment (skewness), which tracks pulse asymmetry rather
        // than openness.
        val openQuotient = voiced.map { it.h1MinusH2 }.filter { !it.isNaN() }
        if (openQuotient.isNotEmpty()) {
            values["glottal_open_quotient_proxy"] = openQuotient.average()
        }

        val harmonics = voiced.map { it.harmonicRatio }.filter { !it.isNaN() }
        if (harmonics.isNotEmpty()) values["harmonic_ratio"] = harmonics.average()
        val subharmonics = voiced.map { it.subharmonicRatio }.filter { !it.isNaN() }
        if (subharmonics.isNotEmpty()) values["subharmonic_ratio"] = subharmonics.average()
    }

    private fun fillIdentity(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        voiced: List<Frame>,
    ) {
        if (voiced.isEmpty()) return
        // Long-term average spectrum: the average energy in each band over the whole clip. This is
        // the closest thing to a speaker's fingerprint that a few hundred numbers can hold.
        val totals = FloatArray(LTAS_BANDS.size)
        for (frame in analysis.frames) {
            for (index in totals.indices) totals[index] += frame.bands[index]
        }
        val frameCount = analysis.frames.size.toDouble().coerceAtLeast(1.0)
        LTAS_BANDS.forEachIndexed { index, band ->
            values["ltas_${bandLabel(band.first, band.second)}"] =
                10.0 * log10((totals[index] / frameCount).toDouble().coerceAtLeast(1e-12))
        }

        // Formants from LPC on voiced frames: the vocal tract's resonances, which is what makes one
        // speaker's "ah" different from another's at the same pitch.
        //
        // A FRAME HAS TO CARRY A VOICE TO BE WORTH MEASURING. The vowels at the tail of a syllable
        // hold a fraction of its energy and their model fits the noise floor rather than the tract;
        // measured on the synthetic vowel in FormantEstimateTest, averaging over those frames read F1
        // at 552 Hz where the mid-syllable frames -- where the pole actually is -- agree on 640. So
        // only the voiced frames within [FORMANT_LEVEL_DB] of the clip's own median voiced level are
        // measured, which is a level filter and not a lucky constant.
        val loudEnough = percentile(voiced.map { it.db }.sorted(), 0.5) - FORMANT_LEVEL_DB
        val formants = mutableListOf<DoubleArray>()
        var index = 0
        while (index < voiced.size) {
            val frame = voiced[index]
            if (frame.db >= loudEnough) {
                formantsFor(analysis, (frame.time * analysis.sampleRate).toInt())?.let { formants += it }
            }
            index += 3
        }
        if (formants.isEmpty()) return
        val f1 = formants.mapNotNull { it.getOrNull(0) }
        val f2 = formants.mapNotNull { it.getOrNull(1) }
        val f3 = formants.mapNotNull { it.getOrNull(2) }
        if (f1.isNotEmpty()) values["formant_1_hz"] = f1.average()
        if (f2.isNotEmpty()) values["formant_2_hz"] = f2.average()
        if (f3.isNotEmpty()) values["formant_3_hz"] = f3.average()
        if (f1.isNotEmpty() && f2.isNotEmpty()) {
            values["formant_dispersion_hz"] = f2.average() - f1.average()
        }
    }

    private fun fillTiming(
        values: LinkedHashMap<String, Double>,
        analysis: Analysis,
        duration: Double,
    ) {
        val db = analysis.db
        val syllables = syllables(db)
        if (syllables.size < 2) return

        // Onset rise time: how long the envelope takes to go from a tenth to nine tenths of a
        // syllable's peak. A slow rise is a consonant transitioning into a vowel.
        var rise = 0.0
        var counted = 0
        for (peak in syllables) {
            val level = db[peak]
            val low = level - 20.0
            var start = peak
            while (start > 0 && db[start - 1] > low) start--
            var end = peak
            while (end < db.size - 1 && db[end + 1] > low) end++
            if (end <= start) continue
            val tenth = low + (level - low) * 0.1
            val nineTenths = low + (level - low) * 0.9
            var first = -1
            var last = -1
            for (frame in start..end) {
                if (first < 0 && db[frame] >= tenth) first = frame
                if (db[frame] >= nineTenths) {
                    last = frame
                    break
                }
            }
            if (first >= 0 && last > first) {
                rise += (last - first) * HOP_MS
                counted++
            }
        }
        if (counted > 0) values["consonant_transition_ms"] = rise / counted

        // Onset jitter: how far each syllable sits from a perfectly regular grid. This is the "tiny
        // timing offsets" that make speech sound human; a machine reading has none.
        val intervals = syllables.zipWithNext { a, b -> (b - a) * HOP_MS }
        val median = percentile(intervals.sorted(), 0.5)
        if (median > 0) values["onset_jitter_ms"] = intervals.map { abs(it - median) }.average()

        // Coarticulation: how far the second formant moves between one syllable's nucleus and the
        // next. Sounds blending into each other IS that movement.
        val nuclei = syllables.mapNotNull { peak ->
            val frame = analysis.frames.getOrNull(peak) ?: return@mapNotNull null
            formantsFor(analysis, (frame.time * analysis.sampleRate).toInt())?.getOrNull(1)
        }
        if (nuclei.size >= 2) {
            val moves = nuclei.zipWithNext { a, b -> abs(b - a) }
            val mean = nuclei.average()
            values["coarticulation"] = moves.average() / mean.coerceAtLeast(1e-6)
            // Vowel reduction: how far each nucleus's formant sits from the clip's own mean, which is
            // what an unstressed vowel does and a clipped read-out-loud dataset does not.
            values["vowel_reduction"] =
                nuclei.map { abs(it - mean) / mean.coerceAtLeast(1e-6) }.average()
        }
        values["duration_s"] = duration
    }

    private fun fillPhonation(
        values: LinkedHashMap<String, Double>,
        voiced: List<Frame>,
        duration: Double,
    ) {
        if (voiced.isEmpty()) return
        val median = percentile(voiced.map { it.f0 }.sorted(), 0.5)
        var modal = 0
        var breathy = 0
        var creak = 0
        var falsetto = 0
        val classes = ArrayList<String>(voiced.size)
        for (frame in voiced) {
            val hnr = if (frame.harmonicity > 0) {
                10.0 * log10(
                    (frame.harmonicity / (1.0 - frame.harmonicity).coerceAtLeast(1e-6))
                        .coerceAtLeast(1e-6)
                )
            } else {
                -20.0
            }
            val label = when {
                frame.f0 > median * 1.75 -> "falsetto"
                frame.f0 < 75.0 || (hnr < 6.0 && frame.f0 < median * 0.85) -> "creak"
                hnr < 8.0 -> "breathy"
                else -> "modal"
            }
            when (label) {
                "falsetto" -> falsetto++
                "creak" -> creak++
                "breathy" -> breathy++
                else -> modal++
            }
            classes += label
        }
        val total = voiced.size.toDouble()
        values["modal_fraction"] = modal / total
        values["breathy_fraction"] = breathy / total
        values["creak_fraction"] = creak / total
        values["falsetto_fraction"] = falsetto / total
        // Only the registers that are a DEPARTURE count: modal to falsetto is a register change, and
        // so is anything involving creak or breath. Which ones were counted is spelled out here
        // because "register switch" means different things to different tools.
        val switches = classes.zipWithNext().count { (a, b) ->
            a != b && (a != "modal" || b != "modal") && (a == "creak" || b == "creak" || a == "breathy" || b == "breathy" || a == "falsetto" || b == "falsetto")
        }
        values["register_switches_per_min"] = switches * 60.0 / duration.coerceAtLeast(1e-6)
    }

    // ── Small signal tools ──────────────────────────────────────────────────────────────────────

    /**
     * Pitch and harmonicity by autocorrelation.
     *
     * The peak of the normalised autocorrelation inside the human pitch range is the period; its
     * height is how periodic the frame is, which is what [fillMicroVariation] turns into an HNR.
     */
    private fun pitch(frame: FloatArray, length: Int, rate: Int): Pair<Double, Double> {
        var mean = 0.0
        for (i in 0 until length) mean += frame[i]
        mean /= length
        val centred = FloatArray(length)
        var energy = 0.0
        for (i in 0 until length) {
            val value = frame[i] - mean.toFloat()
            centred[i] = value
            energy += value * value
        }
        if (energy <= 1e-9) return Double.NaN to 0.0
        val minimumLag = max(2, (rate / MAX_F0_HZ).toInt())
        val maximumLag = min(length - 2, (rate / MIN_F0_HZ).toInt())
        if (maximumLag <= minimumLag) return Double.NaN to 0.0
        var bestLag = 0
        var best = 0.0
        for (lag in minimumLag..maximumLag) {
            var sum = 0.0
            for (i in 0 until length - lag) sum += centred[i] * centred[i + lag]
            val normalised = sum / energy
            if (normalised > best) {
                best = normalised
                bestLag = lag
            }
        }
        if (bestLag == 0 || best < VOICING_THRESHOLD) return Double.NaN to best
        // Parabolic interpolation around the peak: at a 10 ms hop the lag quantisation is a few Hz,
        // which matters for a jitter figure that is a fraction of one.
        return rate / refinePeak(centred, energy, bestLag, minimumLag, maximumLag) to best
    }

    private fun refinePeak(
        centred: FloatArray,
        energy: Double,
        lag: Int,
        minimumLag: Int,
        maximumLag: Int,
    ): Double {
        if (lag <= minimumLag || lag >= maximumLag) return lag.toDouble()
        fun correlation(at: Int): Double {
            var sum = 0.0
            for (i in 0 until centred.size - at) sum += centred[i] * centred[i + at]
            return sum / energy
        }
        val previous = correlation(lag - 1)
        val current = correlation(lag)
        val next = correlation(lag + 1)
        val denominator = previous - 2 * current + next
        if (abs(denominator) < 1e-12) return lag.toDouble()
        return lag + (0.5 * (previous - next) / denominator).coerceIn(-1.0, 1.0)
    }

    /**
     * Formant estimates from the PEAKS of the LPC envelope, or null when the frame has no clear
     * resonances.
     *
     * Levinson-Durbin solves for the all-pole coefficients, and the model's own envelope
     * `|1/A(e^{jω})|` is evaluated from [FORMANT_MIN_HZ] to [FORMANT_MAX_HZ] on a 5 Hz grid. A peak of
     * that curve is a resonance of the vocal tract, which is exactly what a formant is, and it is read
     * where the resonance actually peaks rather than at a pole's angle.
     *
     * WHY NOT THE POLE ANGLES. They are the textbook answer and they are nearly right, but two biases
     * are built in and both were measured on the resonator cascade in FormantEstimateTest -- two-pole
     * resonances at 700, 1200 and 2600 Hz read as 614/1138/1673 Hz:
     *
     *  * The model is fitted to a PRE-EMPHASISED signal, so its envelope carries the +6 dB/octave tilt
     *    that pre-emphasis imposes. A tilt moves a broad resonance's maximum down in frequency, which
     *    is the first formant reading low, and multiplying the curve by the pre-emphasis filter's own
     *    response undoes it.
     *  * A model of a real vowel carries SHOULDER poles -- poles that approximate the side of a
     *    resonance rather than being one. They have angles, and the third of them displaces the real
     *    F3. They do not have peaks: a shoulder is a curve rising monotonically with a kink in it, so
     *    requiring the curve to dip [FORMANT_PROMINENCE_DB] on BOTH sides of a candidate rejects it.
     *
     * Reading peaks of the RAW spectrum is the version that failed before this, and it fails for a
     * different reason worth keeping in mind: a periodic voice's raw spectrum is a comb, and its peaks
     * are the harmonics, not the tract. The envelope of a lag-windowed model is not a comb.
     */
    /**
     * Formant estimates from the POLES of the LPC model, or null when the frame has no clear
     * resonances.
     *
     * Levinson-Durbin solves for the all-pole coefficients, and then the roots of A(z) are found: a
     * root at `rate·angle/2π` Hz with a bandwidth of `-rate·ln(radius)/π` Hz IS a resonance of the
     * vocal tract, which is exactly what a formant is. The frame is pre-emphasised first, which
     * flattens the glottal tilt so the poles describe the tract rather than the source.
     *
     * TWO THINGS WERE WRONG BEFORE THIS VERSION, and both are pinned by FormantEstimateTest, which
     * measures the estimator against a resonator cascade whose formants are the filters' own
     * frequencies:
     *
     *  * The pole polynomial was built the wrong way round. Levinson-Durbin returns the PREDICTOR
     *    coefficients, so the inverse filter is `1 - a1·z^-1 - … - ap·z^-p`; the roots of that are the
     *    poles. Reading the coefficients as they came back found the roots of a different polynomial,
     *    which on the cascade produced poles at radius 0.85 with bandwidths near 1 kHz and formants at
     *    500/863/1196 Hz for resonances built at 700/1200/2600.
     *  * The three LOWEST resonances were reported, which let the broad pole that models the glottal
     *    SOURCE stand in for F3: it read 1671 Hz where the tract's third resonance was at 2600.
     *
     * With both fixed the same cascade reads 664/1183/2626 Hz for 700/1200/2600, and a noise-excited
     * one reads 700/1224/2637.
     */
    private fun formantsFor(analysis: Analysis, start: Int, order: Int = LPC_ORDER): DoubleArray? {
        val frameIndex = start / analysis.hop
        val frame = analysis.frames.getOrNull(frameIndex) ?: return null
        if (frame.f0.isNaN()) return null
        val coefficients = lpcCoefficients(analysis, start, order) ?: return null
        // THE SIGN CONVENTION IS THE WHOLE TRICK. Levinson-Durbin returns the PREDICTOR coefficients
        // of `x[n] = a1·x[n-1] + … + ap·x[n-p] + e[n]`, so the inverse filter is
        // A(z) = 1 - a1·z^-1 - … - ap·z^-p: it is the NEGATED coefficients whose roots are the poles
        // of the model. Reading them as they came back finds the roots of a different polynomial --
        // measured here as every pole landing at a radius of 0.85 with a bandwidth near 1 kHz, which
        // looks like a very broad model and is really nothing of the kind.
        val roots = polynomialRoots(DoubleArray(coefficients.size) { if (it == 0) 1.0 else -coefficients[it] })
            ?: return null

        // A pole is a resonance: z = r·e^(i theta) sits at rate·theta/2pi Hz with a bandwidth of
        // -rate·ln(r)/pi. Only the upper half plane is read, because a real signal's model has the
        // conjugate of every complex pole and the two describe ONE resonance.
        val resonances = mutableListOf<Pair<Double, Double>>()
        for (root in roots) {
            val imaginary = root[1]
            if (imaginary <= 1e-9) continue
            val radius = hypot(root[0], imaginary)
            if (radius <= 0.0 || radius >= 1.0) continue
            val frequency = analysis.sampleRate * atan2(imaginary, root[0]) / (2.0 * Math.PI)
            val bandwidth = -analysis.sampleRate * ln(radius) / Math.PI
            if (frequency < FORMANT_MIN_HZ || frequency > FORMANT_MAX_HZ) continue
            if (bandwidth > MAX_FORMANT_BANDWIDTH_HZ) continue
            resonances += frequency to bandwidth
        }
        if (resonances.isEmpty()) return null

        // Poles inside one resonance's width describe ONE formant, and the sharper of them is the one
        // that is the resonance; the other is the shoulder that approximates its flank.
        val clusters = mutableListOf<MutableList<Pair<Double, Double>>>()
        for (resonance in resonances.sortedBy { it.first }) {
            val last = clusters.lastOrNull()
            if (last != null && resonance.first - last.first().first < FORMANT_MERGE_HZ) {
                last += resonance
            } else {
                clusters += mutableListOf(resonance)
            }
        }
        val sharpest = clusters
            .mapNotNull { cluster -> cluster.minByOrNull { it.second } }
            // THE THREE SHARPEST, NOT THE THREE LOWEST. F1, F2 and F3 are the tract's three principal
            // resonances, and a tract resonance is narrow; the pole that models the SOURCE is broad.
            // Taking the three lowest instead let that broad pole in ahead of the real F3 -- measured
            // on the resonator cascade as F3 = 1671 Hz for a resonance at 2600, with the shoulder at
            // 1116 Hz (bandwidth 561) standing where F3 should be. Ranking by bandwidth keeps the real
            // resonances and then names them in frequency order, which is what the ordinal names mean.
            .sortedBy { it.second }
            .take(3)
            .sortedBy { it.first }
        return sharpest.takeIf { it.isNotEmpty() }?.map { it.first }?.toDoubleArray()
    }

    /**
     * Every complex root of the model polynomial, by Durand-Kerner.
     *
     * All roots are iterated at once, each step pushing every estimate toward its own root through the
     * product of its distances to the others and the value of the polynomial there. It needs no
     * derivative and no deflation, its starting point is the spread of roots of unity, and an LPC
     * polynomial of this order is small enough for eighty iterations to be nothing.
     *
     * Null when it does not settle, which is a frame whose model is a poor fit -- reported as "no
     * formants" rather than as formants read off roots that never converged.
     */
    private fun polynomialRoots(coefficients: DoubleArray): Array<DoubleArray>? {
        // TWO THINGS HAVE TO BE RIGHT BEFORE ITERATING: the order the coefficients are read in, and
        // the leading coefficient's being 1.
        //
        // Order: A(z) = 1 + a1·z^-1 + ... + ap·z^-p vanishes where z^p + a1·z^(p-1) + ... + ap = 0, so
        // the array has to be read from its END down. Read forwards instead and Durand-Kerner happily
        // converges on the RECIPROCALS of the poles -- resonances mirrored about half the sample rate,
        // at radii above one, which is exactly what this looked like before the order was fixed.
        //
        // Monic: the update divides the polynomial's value by the product of the distances to the
        // other roots, which is a step toward this root only when the leading coefficient is 1. In this
        // order that coefficient is a0, which Levinson-Durbin fixes at 1 by construction.
        var degree = coefficients.size - 1
        while (degree > 0 && abs(coefficients[degree]) < 1e-12) degree--
        if (degree < 1) return null
        val monic = DoubleArray(degree + 1) { index -> coefficients[degree - index] }
        if (abs(monic[degree]) < 1e-12) return null
        val roots = Array(degree) { index ->
            val angle = 2.0 * Math.PI * index / degree + 0.4
            doubleArrayOf(0.4 * cos(angle), 0.9 * sin(angle))
        }
        repeat(ROOT_ITERATIONS) {
            var moved = 0.0
            for (i in 0 until degree) {
                // A(z), by Horner on the complex estimate.
                var real = monic[degree]
                var imaginary = 0.0
                for (index in degree - 1 downTo 0) {
                    val nextReal = real * roots[i][0] - imaginary * roots[i][1] + monic[index]
                    imaginary = real * roots[i][1] + imaginary * roots[i][0]
                    real = nextReal
                }
                // The product of the distances to every OTHER root.
                var denominatorReal = 1.0
                var denominatorImaginary = 0.0
                for (j in 0 until degree) {
                    if (j == i) continue
                    val differenceReal = roots[i][0] - roots[j][0]
                    val differenceImaginary = roots[i][1] - roots[j][1]
                    val nextReal =
                        denominatorReal * differenceReal - denominatorImaginary * differenceImaginary
                    denominatorImaginary =
                        denominatorReal * differenceImaginary + denominatorImaginary * differenceReal
                    denominatorReal = nextReal
                }
                val magnitude = denominatorReal * denominatorReal +
                    denominatorImaginary * denominatorImaginary
                if (magnitude < 1e-18) continue
                val deltaReal = (real * denominatorReal + imaginary * denominatorImaginary) / magnitude
                val deltaImaginary = (imaginary * denominatorReal - real * denominatorImaginary) / magnitude
                roots[i][0] -= deltaReal
                roots[i][1] -= deltaImaginary
                moved = max(moved, abs(deltaReal) + abs(deltaImaginary))
            }
            if (moved < 1e-12) return roots
        }
        return null
    }

    /**
     * The all-pole model of one frame, as PREDICTOR coefficients in `z^-1` order.
     *
     * Pre-emphasis first, which flattens the glottal tilt so the model describes the vocal tract rather
     * than the source. The autocorrelation is then taken straight -- no lag window.
     *
     * A LAG WINDOW WAS TRIED AND REMOVED. It is the usual remedy for a periodic signal, whose comb of
     * harmonics an all-pole model has to spend poles on, and the reasoning is sound: the taper smooths
     * the fit over roughly `rate/order` Hz, which folds a 90 Hz-wide resonance into its neighbours. On
     * the resonator cascade in FormantEstimateTest the taper cost what it was meant to save -- 700,
     * 1200 and 2600 Hz came back as 640/1163/2536 at half strength and 517/1539/2874 at full, against
     * 666/1173/2603 with no taper at all. It was compensating for the sign error in [formantsFor]:
     * with the poles read from the right polynomial, order 26 already separates the tract's resonances
     * from the pitch's harmonics.
     */
    private fun lpcCoefficients(analysis: Analysis, start: Int, order: Int = LPC_ORDER): DoubleArray? {
        val length = min(LPC_WINDOW_SAMPLES, analysis.samples.size - start - 1)
        if (length < order * 4) return null
        val autocorrelation = DoubleArray(order + 1)
        for (lag in 0..order) {
            var sum = 0.0
            for (i in 0 until length - lag) {
                val left = analysis.samples[start + i] -
                    PRE_EMPHASIS * analysis.samples[(start + i - 1).coerceAtLeast(0)]
                val right = analysis.samples[start + i + lag] -
                    PRE_EMPHASIS * analysis.samples[(start + i + lag - 1).coerceAtLeast(0)]
                sum += left * right
            }
            autocorrelation[lag] = if (lag == 0) sum.coerceAtLeast(1e-12) else sum
        }
        return levinsonDurbin(autocorrelation)
    }

    /** Levinson-Durbin: the all-pole coefficients from the autocorrelation. */
    private fun levinsonDurbin(autocorrelation: DoubleArray): DoubleArray? {
        val order = autocorrelation.size - 1
        val coefficients = DoubleArray(order + 1)
        val previous = DoubleArray(order + 1)
        var error = autocorrelation[0]
        if (error <= 0.0) return null
        coefficients[0] = 1.0
        previous[0] = 1.0
        for (i in 1..order) {
            var sum = autocorrelation[i]
            for (j in 1 until i) sum -= previous[j] * autocorrelation[i - j]
            val reflection = sum / error
            if (reflection.isNaN() || reflection.isInfinite()) return null
            coefficients[i] = reflection
            for (j in 1 until i) coefficients[j] = previous[j] - reflection * previous[i - j]
            System.arraycopy(coefficients, 0, previous, 0, i + 1)
            error *= (1.0 - reflection * reflection)
            if (error <= 1e-12) break
        }
        return coefficients
    }

    // ── Envelope structure ──────────────────────────────────────────────────────────────────────

    /** Indices of the intensity envelope's peaks, at least [MIN_SYLLABLE_MS] apart. */
    private fun syllables(db: DoubleArray): List<Int> {
        if (db.size < 3) return emptyList()
        val floor = percentile(db.sorted(), 0.15) + 3.0
        val minimumGap = (MIN_SYLLABLE_MS / HOP_MS).toInt()
        val peaks = mutableListOf<Int>()
        for (index in 1 until db.size - 1) {
            if (db[index] <= floor) continue
            if (db[index] < db[index - 1] || db[index] < db[index + 1]) continue
            val previousPeak = peaks.lastOrNull()
            if (previousPeak != null && index - previousPeak < minimumGap) {
                // Two peaks one syllable apart: keep the louder.
                if (db[index] > db[previousPeak]) peaks[peaks.size - 1] = index
                continue
            }
            peaks += index
        }
        return peaks
    }

    private fun runsOf(flags: List<Boolean>, value: Boolean): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        flags.forEachIndexed { index, flag ->
            val matches = if (value) flag else !flag
            if (matches && start < 0) start = index
            if (!matches && start >= 0) {
                runs += start to index
                start = -1
            }
        }
        if (start >= 0) runs += start to flags.size
        return runs
    }

    private fun binIndex(frequency: Double, binWidth: Double): Int =
        if (binWidth <= 0.0) -1 else Math.round(frequency / binWidth).toInt()

    private fun bandLabel(low: Double, high: Double): String {
        fun name(value: Double) = if (value >= 1000) "${(value / 1000).toInt()}k" else value.toInt().toString()
        return "${name(low)}_${name(high)}_hz_db"
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Least-squares slope of y against x, or NaN when x has no spread. */
    private fun slope(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2 || x.size != y.size) return Double.NaN
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in x.indices) {
            covariance += (x[i] - meanX) * (y[i] - meanY)
            variance += (x[i] - meanX) * (x[i] - meanX)
        }
        return if (variance > 1e-12) covariance / variance else Double.NaN
    }

    private fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }

    /**
     * In-place iterative radix-2 FFT.
     *
     * Written out rather than borrowed from the speech engines' own copies: those are private to a
     * vocoder and a voice converter, and reaching into a model's internals for a transform would be a
     * coupling with nothing to show for it.
     */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val temporaryRe = re[i]
                re[i] = re[j]
                re[j] = temporaryRe
                val temporaryIm = im[i]
                im[i] = im[j]
                im[j] = temporaryIm
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var currentRe = 1f
                var currentIm = 0f
                for (i in 0 until length / 2) {
                    val index = start + i
                    val mirror = index + length / 2
                    val productRe = re[mirror] * currentRe - im[mirror] * currentIm
                    val productIm = re[mirror] * currentIm + im[mirror] * currentRe
                    re[mirror] = re[index] - productRe
                    im[mirror] = im[index] - productIm
                    re[index] += productRe
                    im[index] += productIm
                    val nextRe = currentRe * stepRe - currentIm * stepIm
                    currentIm = currentRe * stepIm + currentIm * stepRe
                    currentRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
