package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.KokoroSeedVoice
import com.prism.launcher.speech.PocketTtsEngine
import com.prism.launcher.speech.VoicePitch
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A speech engine whose voices are measured from the model's own dataset.
 *
 * ## What is built, and what speaks
 *
 * The dataset supplies the thing a generic engine cannot: who the voices are. For every packaged
 * recording this measures the speaker's median pitch, spectral centroid and upper-band share, and
 * writes those numbers beside the WAV in `voice_engine.json`. At synthesis time the engine picks the
 * voice matching the requested language and gender, conditions the local Kokoro studio voice on that
 * recording's pitch, and masters the result through the same close-microphone chain as every other
 * Prism speech path.
 *
 * The neural synthesis core is Kokoro because it is already part of Prism and is the only local
 * model that produces full-band, human-sounding speech on a phone. The ENGINE -- the inventory of
 * speakers, their pitch, their brightness and the recording that carries their identity -- is built
 * from the dataset, not from a default voice. That is the part training used to leave implicit.
 *
 * ## Pocket TTS is optional
 *
 * [Engine.allowPocket] is the model's switch. When it is off, a voice that Kokoro cannot speak (an
 * unsupported language, or a device without the Kokoro model) fails with a sentence naming the
 * switch rather than quietly changing engine. When it is on, Pocket TTS is the fallback exactly
 * where it used to be. Neither answer is silent.
 */
object DatasetVoiceEngine {

    /** The artifact written beside `tts_model.json`. */
    const val FILE = "voice_engine.json"

    /** The format tag a reader checks before trusting [FILE]. */
    const val FORMAT = "prism-voice-engine"

    /** The engine's own version; a reader that does not understand a version says so. */
    const val VERSION = 2L

    /**
     * The synthesizer a freshly built engine prefers.
     *
     * This names the PRISM neural voice, not a pretrained model: units are cut from the dataset
     * and joined by PSOLA at a contour the model trained here predicts. Kokoro is an explicit
     * switch and writes its own name into the artifact when enabled.
     */
    const val SYNTHESIZER = "prism-neural"

    /** The rate every profile is measured at and the rate [Engine.speak] returns. */
    const val RATE = NeuralVoice.RATE

    private const val TAG = "DatasetVoiceEngine"

    /** One speaker: the recording, and the measurements that make it a voice rather than a file. */
    data class Voice(
        val name: String,
        val gender: String,
        val language: String,
        /** The packaged WAV's name inside `voices/`. */
        val file: String,
        val seconds: Double,
        val sampleRate: Int,
        /** Median F0 of the recording, in Hz; 0 when it had no measurable voiced speech. */
        val pitchHz: Double,
        /** Energy-weighted mean frequency of the long-term spectrum. */
        val centroidHz: Double,
        /** Share of spectral energy above 4 kHz: how much air the recording actually has. */
        val air: Double,
        /** The dataset recording this voice came from, for provenance. */
        val sourceClip: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("gender", gender)
            put("language", language)
            put("file", file)
            put("seconds", seconds)
            put("sample_rate", sampleRate.toLong())
            put("pitch_hz", pitchHz)
            put("centroid_hz", centroidHz)
            put("air", air)
            put("source_clip", sourceClip)
        }

        /** A one-line description for the UI and the log. */
        fun summary(): String = buildString {
            append(name)
            append(" · ")
            append(gender.ifBlank { "neutral" })
            append(" · ")
            append(language.ifBlank { "und" })
            if (pitchHz > 0.0) append(" · ${pitchHz.toInt()} Hz")
            append(" · ${file}")
        }
    }

    /** A loaded engine: the voices and the switch that decides what happens when Kokoro cannot. */
    class Engine(
        val directory: File,
        val voices: List<Voice>,
        val allowPocket: Boolean,
        /** The Prism-built neural voice, when one was trained from this dataset. */
        val neural: PrismNeuralVoice.Model? = null,
        /** The user explicitly allowed the pretrained Kokoro studio voice. */
        val allowKokoro: Boolean = false,
        /** A user-supplied Prism neural voice folder, when one was selected. */
        val custom: PrismNeuralVoice.Model? = null,
        /** The folder that was selected, kept so a failed import can say why. */
        val customPath: String = "",
    ) {
        /** The voice for a language and gender, falling back the way the trainer's map does. */
        fun voice(language: String, gender: String): Voice? {
            val lang = language.trim().lowercase(Locale.ROOT).substringBefore('-')
            val sex = gender.trim().lowercase(Locale.ROOT)
            return voices.firstOrNull {
                it.language.trim().lowercase(Locale.ROOT).substringBefore('-') == lang &&
                    it.gender.equals(sex, true)
            } ?: voices.firstOrNull {
                it.language.trim().lowercase(Locale.ROOT).substringBefore('-') == lang
            } ?: voices.firstOrNull { it.gender.equals(sex, true) } ?: voices.firstOrNull()
        }

        /**
         * The voice whose speaker name or WAV name is [query], or a unique partial match.
         *
         * A partial match is accepted because a page shows the speaker's name while a manifest
         * may carry only the file name, and a caller should not have to know which one it holds.
         */
        fun voiceNamed(query: String): Voice? {
            val wanted = query.trim()
            if (wanted.isEmpty()) return null
            return voices.firstOrNull {
                it.name.equals(wanted, true) || it.file.equals(wanted, true)
            } ?: voices.firstOrNull {
                it.name.contains(wanted, true) || it.file.contains(wanted, true)
            }
        }

        /**
         * Speaks [text] in [language] and [gender].
         *
         * The reference recording is decoded on every call rather than cached: a voice is a few
         * seconds of 22 kHz audio, and a cached FloatArray per voice would be held for the lifetime
         * of a model that may have thirty of them. Kokoro caches the conditioned state.
         */
        fun speak(
            text: String,
            language: String = "",
            gender: String = "",
            speed: Float = 1f,
            allowPocket: Boolean = this.allowPocket,
        ): FloatArray {
            if (text.isBlank()) return FloatArray(0)
            val chosen = voice(language, gender) ?: throw IllegalStateException(
                "This voice engine has no voices. Rebuild it from the model's Voices dialog.",
            )
            return speakWith(text, chosen, language, gender, speed, allowPocket)
        }

        /**
         * Speaks [text] in exactly [voiceName], not the first voice matching a language and gender.
         *
         * This is what the test page's voice buttons use: a model with two English female voices
         * must be able to demonstrate both, and [speak] would always choose the first.
         */
        fun speakVoice(
            text: String,
            voiceName: String,
            speed: Float = 1f,
            allowPocket: Boolean = this.allowPocket,
        ): FloatArray {
            if (text.isBlank()) return FloatArray(0)
            val chosen = voiceNamed(voiceName) ?: throw IllegalStateException(
                "This voice engine has no voice called \"$voiceName\". Rebuild it from the " +
                    "model's Voices dialog.",
            )
            // Empty language and gender let the chosen profile supply both, which is what makes
            // this the selected voice rather than a re-selection by the caller's defaults.
            return speakWith(text, chosen, "", "", speed, allowPocket)
        }

        /** The shared rendering path: one chosen profile, one sentence. */
        private fun speakWith(
            text: String,
            chosen: Voice,
            language: String,
            gender: String,
            speed: Float,
            allowPocket: Boolean,
        ): FloatArray {
            val source = File(directory, chosen.file)
            val decoded = AudioDecoding.read(source) ?: throw IllegalStateException(
                "The voice recording ${chosen.file} could not be decoded, so ${chosen.name} has " +
                    "nothing to speak in. Rebuild the engine from the Voices dialog.",
            )
            val prepared = if (decoded.sampleRate == NeuralVoice.WORK_RATE) {
                decoded.samples
            } else {
                decoded.resampled(NeuralVoice.WORK_RATE).samples
            }
            val voiceLanguage = language.ifBlank { chosen.language }.ifBlank { "en" }
            val voiceGender = gender.ifBlank { chosen.gender }.ifBlank { "female" }

            // THE ENGINE ORDER IS THE POINT. A selected custom voice wins; then the Kokoro
            // switch, because enabling it is an explicit request; then Prism's own neural voice,
            // which is what a normal build uses. Pocket is last and only when allowed.
            if (customPath.isNotBlank()) {
                val customVoice = custom ?: throw IllegalStateException(
                    "The imported neural voice in $customPath could not be read. Choose the folder " +
                        "that contains ${PrismNeuralVoice.FILE} and ${PrismNeuralVoice.WEIGHTS_FILE}, " +
                        "or turn off \"Use a custom neural voice\" in the model's audio settings.",
                )
                return atOutputRate(
                    customVoice.synthesize(
                        text = text,
                        language = voiceLanguage,
                        gender = voiceGender,
                        voiceName = neuralVoiceName(chosen, customVoice),
                        speed = speed,
                    ),
                )
            }
            if (allowKokoro) {
                val studio = KokoroSeedVoice.speakStudio(
                    text = text,
                    reference = prepared,
                    language = voiceLanguage,
                    gender = voiceGender,
                )
                if (studio != null && studio.isNotEmpty()) return studio
            }
            neural?.let { voice ->
                val spoken = runCatching {
                    voice.synthesize(
                        text = text,
                        language = voiceLanguage,
                        gender = voiceGender,
                        voiceName = neuralVoiceName(chosen, voice),
                        speed = speed,
                    )
                }.onFailure {
                    PrismPlatform.log.warn(TAG, "Neural voice could not speak: ${it.message}")
                }.getOrNull()
                if (spoken != null && spoken.isNotEmpty()) return atOutputRate(spoken)
            }
            // No silent substitution. A model without a Prism neural voice used to fall through to
            // the installed studio voice, which meant "Prism's own engine" could still be Kokoro.
            // The switch is the only way in now; without it the caller gets a sentence saying what
            // to do instead of audio from an engine the user did not choose.
            if (!allowPocket) {
                throw IllegalStateException(
                    "The Prism neural voice could not speak this sentence" +
                        (if (neural == null) " because none has been built yet" else " (it is missing a sound or a recording)") +
                        ". Rebuild it from the Neural Voice tab, turn on \"Allow Kokoro studio voice\" " +
                        "to use the pretrained voice, or turn on \"Allow Pocket TTS fallback\" for the " +
                        "multilingual engine.",
                )
            }
            val pocket = PocketTtsEngine.speakReference(
                text,
                "dataset-${chosen.file}",
                prepared,
            ).samples
            if (pocket.isEmpty()) {
                throw IllegalStateException(
                    "The dataset voice engine produced no audio for that sentence.",
                )
            }
            return pocket
        }

        /**
         * Resamples a Prism neural voice from its analysis rate to the engine's output rate.
         *
         * [PrismNeuralVoice.RATE] and [NeuralVoice.RATE] are deliberately different: the unit bank
         * is cut and pitched at 22.05 kHz, while every player and the rest of Prism's speech paths
         * expect 24 kHz. Writing the 22.05 kHz samples under a 24 kHz WAV header played the sentence
         * about 8.8% fast -- enough to turn clear words into something a listener hears as slurred.
         */
        private fun atOutputRate(samples: FloatArray): FloatArray =
            if (samples.isEmpty() || PrismNeuralVoice.RATE == RATE) samples
            else VoicePitch.resample(samples, PrismNeuralVoice.RATE, RATE)

        /** Maps a packaged voice row to the neural voice cut from the same recording. */
        private fun neuralVoiceName(chosen: Voice, model: PrismNeuralVoice.Model): String? {
            val wanted = listOf(chosen.sourceClip, chosen.file, chosen.name)
                .map { normaliseName(it) }
                .filter { it.isNotBlank() }
            if (wanted.isEmpty()) return null
            return model.voices.firstOrNull { voice ->
                val candidate = normaliseName(voice.name)
                wanted.any { key -> candidate == key || candidate.contains(key) || key.contains(candidate) }
            }?.name
        }

        private fun normaliseName(value: String): String =
            value.substringBeforeLast('.').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /** The one line a status area can print. */
        fun summary(): String {
            val synthesizer = when {
                customPath.isNotBlank() -> "custom neural voice"
                allowKokoro -> "Kokoro studio voice"
                neural != null -> "Prism neural voice"
                else -> "Prism neural voice (not built yet)"
            }
            return "${voices.size} voice(s) · $synthesizer · Pocket TTS " +
                (if (allowPocket) "fallback allowed" else "fallback off")
        }
    }

    /**
     * Builds the engine from [voices], which have already been written into the pack.
     *
     * Returns null only when no recording could be decoded, because an engine naming files that do
     * not exist is worse than no engine: the model can still speak through its reference clip.
     */
    fun build(
        model: CraftedModel,
        voices: List<VoicePack.Voice>,
        allowPocket: Boolean = ModelCraft.allowsPocketFallback(model),
        /** Train the Prism neural voice when the dataset does not have one yet. */
        trainNeural: Boolean = false,
    ): Engine? {
        val directory = VoicePack.directory(model)
        val dataset = ModelCraft.Store.datasetDir(model.id)
        val measured = voices.mapNotNull { voice -> profileOf(directory, voice) }
        if (measured.isEmpty()) {
            PrismPlatform.log.warn(TAG, "No voice recording could be measured for ${model.id}")
            return null
        }
        var neural = PrismNeuralVoice.load(dataset)
        if (neural == null && trainNeural) {
            neural = runCatching {
                PrismNeuralVoice.train(dataset, ModelCraft.neuralVoiceSettings(model))
            }.onFailure {
                PrismPlatform.log.warn(
                    TAG,
                    "Prism neural voice could not be trained for ${model.id}: ${it.message}",
                )
            }.getOrNull()
        }
        val customPath = ModelCraft.customVoicePath(model).takeIf {
            ModelCraft.usesCustomVoice(model) && it.isNotBlank()
        }.orEmpty()
        val custom = customPath.takeIf { it.isNotBlank() }
            ?.let { path -> PrismNeuralVoice.load(File(path)) }
        if (customPath.isNotBlank() && custom == null) {
            PrismPlatform.log.warn(TAG, "Custom neural voice at $customPath could not be read")
        }
        val allowKokoro = ModelCraft.allowsKokoro(model)
        val document = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("name", model.name)
            put("crafted_model_id", model.id)
            put("synthesizer", if (neural != null) PrismNeuralVoice.FORMAT else SYNTHESIZER)
            put("sample_rate", RATE.toLong())
            put("allow_pocket_tts", allowPocket)
            put("allow_kokoro", allowKokoro)
            put("neural_voice", neural != null)
            put("neural_voice_units", neural?.units?.size?.toLong() ?: 0L)
            put("custom_voice", customPath)
            put(
                "note",
                "Voices measured from this model's dataset. A Prism neural voice built from the " +
                    "same recordings is the default synthesizer; Kokoro is used only when the user " +
                    "turns it on, a custom neural voice when one is selected, and Pocket TTS only " +
                    "when the model allows the fallback.",
            )
            put("voices", JSONArray(measured.map { it.toJson() }))
            put(
                "built_from",
                JSONObject().apply {
                    put("pack", VoicePack.MANIFEST)
                    put("table", VoicePack.TABLE)
                    put("clips", measured.size.toLong())
                    put("measured_at", System.currentTimeMillis())
                },
            )
        }
        val file = File(directory, FILE)
        file.parentFile?.mkdirs()
        file.writeText(document.toString())
        PrismPlatform.log.info(
            TAG,
            "Built voice engine for ${model.id}: ${measured.size} voice(s), " +
                "Pocket TTS ${if (allowPocket) "allowed" else "off"}",
        )
        return Engine(directory, measured, allowPocket, neural, allowKokoro, custom, customPath)
    }

    /** Rebuilds the engine from the pack already on disk. */
    fun rebuild(model: CraftedModel): Engine? {
        val voices = VoicePack.packaged(model)
        if (voices.isEmpty()) return null
        return build(model, voices)
    }

    /** The engine for [model], or null when it has not been built. */
    fun load(model: CraftedModel): Engine? = load(ModelCraft.Store.datasetDir(model.id), model)

    /**
     * The engine in [directory], or null when there is no valid artifact.
     *
     * [directory] may be the model's dataset folder or the pack folder itself. The artifact lives
     * beside the WAVs, so a caller holding the model folder must not look for it at the top level.
     */
    fun load(directory: File, model: CraftedModel? = null): Engine? {
        val pack = if (File(directory, FILE).isFile || !File(directory, VoicePack.DIR).isDirectory) {
            directory
        } else {
            File(directory, VoicePack.DIR)
        }
        val file = File(pack, FILE)
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (json.optString("format", "") != FORMAT) return null
        val array = json.optJSONArray("voices") ?: return null
        val voices = (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("file", "")
            if (name.isBlank()) return@mapNotNull null
            Voice(
                name = entry.optString("name", name.substringBeforeLast('.')),
                gender = entry.optString("gender", "neutral"),
                language = entry.optString("language", ""),
                file = name,
                seconds = entry.optDouble("seconds", 0.0),
                sampleRate = entry.optInt("sample_rate", NeuralVoice.WORK_RATE),
                pitchHz = entry.optDouble("pitch_hz", 0.0),
                centroidHz = entry.optDouble("centroid_hz", 0.0),
                air = entry.optDouble("air", 0.0),
                sourceClip = entry.optString("source_clip", ""),
            )
        }
        if (voices.isEmpty()) return null
        // The model's switch is authoritative when the model is known: a user who just turned
        // the fallback off must not be overruled by a stale artifact.
        val allowPocket = model?.let { ModelCraft.allowsPocketFallback(it) }
            ?: json.optBoolean("allow_pocket_tts", false)
        val allowKokoro = model?.let { ModelCraft.allowsKokoro(it) }
            ?: json.optBoolean("allow_kokoro", false)
        val dataset = model?.let { ModelCraft.Store.datasetDir(it.id) } ?: directory
        val neural = PrismNeuralVoice.load(dataset) ?: PrismNeuralVoice.load(pack)
        val customPath = model?.let { current ->
            ModelCraft.customVoicePath(current).takeIf { ModelCraft.usesCustomVoice(current) }
        } ?: json.optString("custom_voice", "").takeIf { it.isNotBlank() }
        val custom = customPath?.let { path -> PrismNeuralVoice.load(File(path)) }
        return Engine(pack, voices, allowPocket, neural, allowKokoro, custom, customPath.orEmpty())
    }

    /** The manifest as a document, for an export or a diagnostic. Null when there is none. */
    fun describe(model: CraftedModel): JSONObject? =
        runCatching { JSONObject(File(VoicePack.directory(model), FILE).readText()) }.getOrNull()
            ?.takeIf { it.optString("format", "") == FORMAT }

    /** True when [model] has a readable engine with at least one voice. */
    fun ready(model: CraftedModel): Boolean = load(model) != null

    /** The sentence a page can show instead of pretending an engine exists. */
    fun missing(model: CraftedModel): String =
        "No dataset voice engine has been built for this model yet. Open Voices and build one, " +
            "or run a training pass; the engine is written beside the voice pack."

    private fun profileOf(directory: File, voice: VoicePack.Voice): Voice? {
        val file = File(directory, voice.file)
        if (!file.isFile) return null
        val clip = AudioDecoding.read(file) ?: return null
        if (clip.samples.isEmpty()) return null
        val atWorkRate = if (clip.sampleRate == NeuralVoice.WORK_RATE) {
            clip.samples
        } else {
            clip.resampled(NeuralVoice.WORK_RATE).samples
        }
        val pitch = VoicePitch.medianPitch(atWorkRate, NeuralVoice.WORK_RATE).toDouble()
        val stats = spectralStats(atWorkRate, NeuralVoice.WORK_RATE)
        return Voice(
            name = voice.name.ifBlank { voice.file.substringBeforeLast('.') },
            gender = voice.gender,
            language = voice.language,
            file = voice.file,
            seconds = voice.seconds.takeIf { it > 0.0 } ?: clip.seconds,
            sampleRate = clip.sampleRate,
            pitchHz = if (pitch.isFinite()) pitch else 0.0,
            centroidHz = stats.first,
            air = stats.second,
            sourceClip = voice.clip,
        )
    }

    /**
     * Centroid and upper-band share of a recording's long-term spectrum.
     *
     * Hann-windowed 1024-point frames, half-overlapped, magnitudes accumulated. Only the shape of
     * the spectrum is used, so no calibration is needed: both numbers are ratios and mean the same
     * thing whatever the recording's level.
     */
    private fun spectralStats(samples: FloatArray, rate: Int): Pair<Double, Double> {
        if (samples.isEmpty() || rate <= 0) return 0.0 to 0.0
        val fft = 1024
        val hop = fft / 2
        val bins = fft / 2 + 1
        val spectrum = DoubleArray(bins)
        val re = DoubleArray(fft)
        val im = DoubleArray(fft)
        val window = DoubleArray(fft) { index ->
            0.5 - 0.5 * cos(2.0 * PI * index / (fft - 1))
        }
        var start = 0
        while (start + fft <= samples.size) {
            for (index in 0 until fft) re[index] = samples[start + index] * window[index]
            java.util.Arrays.fill(im, 0.0)
            fft(re, im)
            for (bin in 0 until bins) {
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                spectrum[bin] += magnitude
            }
            start += hop
        }
        var weighted = 0.0
        var total = 0.0
        var upper = 0.0
        val width = rate.toDouble() / fft
        for (bin in 0 until bins) {
            val frequency = bin * width
            weighted += frequency * spectrum[bin]
            total += spectrum[bin]
            if (frequency >= 4_000.0) upper += spectrum[bin]
        }
        if (total <= 1e-9) return 0.0 to 0.0
        return (weighted / total) to (upper / total)
    }

    /** In-place iterative radix-2 FFT, real input in [re], imaginary in [im]. */
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

}
