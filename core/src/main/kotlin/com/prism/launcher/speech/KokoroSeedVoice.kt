package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.launcher.modelcraft.NeuralVoice
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * A clearer route to a dataset voice than direct neural TTS.
 *
 * Pocket TTS conditions on a recording and reproduces not only its timbre but its bandwidth and
 * room character. On a 16 kHz dataset that is a voice with no 4-8 kHz octave at all. Kokoro-82M is
 * a much larger, studio-trained speaker, and Seed-VC can move its output onto the reference
 * speaker while keeping Kokoro's natural pacing and consonants -- so the same dataset yields a
 * close-microphone voice instead of a copy of the microphone.
 *
 * Both engines are optional downloads Prism already ships. When either is missing, or the text is
 * not English (Kokoro's bundled phonemiser is English), [speak] returns null and
 * [NeuralVoice] falls back to Pocket TTS. Nothing here changes a saved model; this is a rendering
 * path, not another artifact.
 */
object KokoroSeedVoice {

    private const val TAG = "PrismSpeech"
    private const val MAX_REFERENCE_SECONDS = 15.0

    @Volatile
    private var kokoro: KokoroEngine? = null

    @Volatile
    private var tried = false

    /** True when both halves of the cloning route are on disk, whether or not opened yet. */
    fun isInstalled(): Boolean =
        runCatching { SeedVcEngine.isInstalled() && kokoroModel() != null }.getOrDefault(false)

    /** True when Kokoro alone is available, which is all plain text-to-speech needs. */
    fun isStudioInstalled(): Boolean = runCatching { kokoroModel() != null }.getOrDefault(false)

    /** One line for a caption, or null when this device will fall back to Pocket TTS. */
    fun label(): String? = if (isStudioInstalled()) "Kokoro" else null

    /**
     * Speaks [text] in a published studio voice, without re-voicing it toward a recording.
     *
     * This is the plain text-to-speech path. Seed-VC, which moves Kokoro's output onto the
     * reference speaker, resamples through an 8 kHz-band mel and measurably removes the 8-12 kHz
     * air band (2.2% of Kokoro's energy, 0.01% after conversion). That is the "recorded on a
     * cheap microphone" quality: it cannot be recovered by EQ afterwards, because the energy is
     * gone. So a model that only needs to sound like a person gets Kokoro directly, pitch-matched
     * to the dataset speaker's median F0; Seed-VC is reserved for a deliberate clone, where
     * matching the reference timbre is the whole request.
     *
     * [reference] is at [NeuralVoice.WORK_RATE] and may be null. Returns null when Kokoro is not
     * installed or cannot speak, so [NeuralVoice] can fall back to Pocket TTS.
     */
    fun speakStudio(
        text: String,
        reference: FloatArray?,
        language: String,
        gender: String,
    ): FloatArray? {
        if (text.isBlank()) return null
        val engine = open() ?: return null
        val voice = voiceFor(language, gender)
        return synchronized(engine) {
            val spoken = runCatching {
                engine.synthesizeToPcm(text, voice, 1f)
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Kokoro could not speak: ${it.message}")
            }.getOrNull() ?: return@synchronized null
            if (spoken.samples.isEmpty()) return@synchronized null
            val atModelRate = if (spoken.sampleRate == NeuralVoice.RATE) {
                spoken.samples
            } else {
                VoicePitch.resample(spoken.samples, spoken.sampleRate, NeuralVoice.RATE)
            }
            val target = reference
                ?.takeIf { it.isNotEmpty() }
                ?.let { VoicePitch.medianPitch(it, NeuralVoice.WORK_RATE) }
                ?: 0f
            val matched = if (target > 0f) {
                VoicePitch.match(atModelRate, NeuralVoice.RATE, target)
            } else {
                atModelRate
            }
            VoicePolish.masterStudio(matched, NeuralVoice.RATE)
        }
    }

    /**
     * The published voice for a language and gender, preferring one already on disk.
     *
     * Kokoro ships no German, Korean or Russian voice, so those (and anything else without a
     * match) fall back to the configured default rather than failing in the middle of a sentence.
     */
    private fun voiceFor(language: String, gender: String): String {
        val fallback = PrismSettings.KOKORO_DEFAULT_VOICE
        val code = when (language.lowercase().substringBefore('-')) {
            "en" -> if (language.contains("gb", ignoreCase = true)) 'b' else 'a'
            "es" -> 'e'
            "fr" -> 'f'
            "hi" -> 'h'
            "it" -> 'i'
            "pt" -> 'p'
            "ja" -> 'j'
            "zh" -> 'z'
            else -> return fallback
        }
        val want = if (gender.startsWith("m", ignoreCase = true)) {
            KokoroVoices.Gender.MALE
        } else {
            KokoroVoices.Gender.FEMALE
        }
        val candidates = KokoroVoices.ALL.filter { it.language.code == code && it.gender == want }
        if (candidates.isEmpty()) return fallback
        candidates.firstOrNull { KokoroInstall.isVoiceInstalled(it.id) }?.let { return it.id }
        // Not downloaded yet: fetch the best-graded published voice once. A failed download (offline
        // first run) falls back to whatever is already installed rather than failing the render.
        val preferred = listOf(
            "af_heart", "af_bella", "af_nicole",
            "am_michael", "am_fenrir", "am_puck", "bm_george",
            "ef_dora", "em_alex", "ff_siwis", "hf_alpha", "hm_omega",
            "if_sara", "im_nicola", "pf_dora", "pm_alex",
            "jf_alpha", "jm_kumo", "zf_xiaoxiao", "zm_yunjian",
        )
        val target = preferred.firstOrNull { id -> candidates.any { it.id == id } }
            ?: candidates.minByOrNull { qualityRank(it.quality) }?.id
        if (target != null && KokoroInstall.downloadVoice(target) == null) return target
        return fallback
    }

    /** A=0, A-=1, ... F=12; used only to put the strongest voice first. */
    private fun qualityRank(quality: String): Int {
        val letter = quality.firstOrNull()?.uppercaseChar() ?: 'F'
        val base = (letter - 'A').coerceIn(0, 12) * 2
        return base + if (quality.contains('-')) 0 else if (quality.contains('+')) 2 else 1
    }

    /**
     * Speaks [text] in [reference]'s voice, or null when this route is unavailable or fails.
     *
     * The reference is expected at [NeuralVoice.WORK_RATE]. Seed-VC rebuilds pitch from it, so no
     * separate pitch match is applied. The result is mastered through the same [VoicePolish]
     * chain as every other generated voice.
     */
    fun speak(text: String, reference: FloatArray, voiceId: String): FloatArray? {
        if (text.isBlank() || reference.isEmpty()) return null
        if (!SeedVcEngine.isInstalled()) return null
        val engine = open() ?: return null
        val limit = (MAX_REFERENCE_SECONDS * NeuralVoice.WORK_RATE).toInt()
        val ref = if (reference.size > limit) reference.copyOf(limit) else reference
        return synchronized(engine) {
            val spoken = runCatching {
                engine.synthesizeToPcm(text, PrismSettings.KOKORO_DEFAULT_VOICE, 1f)
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Kokoro could not speak through the clearer route: ${it.message}")
            }.getOrNull() ?: return@synchronized null
            if (spoken.samples.isEmpty()) return@synchronized null
            runCatching {
                SeedVcEngine.convert(voiceId, spoken.samples, spoken.sampleRate, ref)
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Seed-VC could not match the reference voice: ${it.message}")
            }.getOrNull()?.let { converted ->
                // The model artifact reports NeuralVoice.RATE for every trained voice, so the
                // clearer route must hand back that rate too -- otherwise the player runs Seed-VC's
                // 22.05 kHz audio 9% fast and high. Resample once, after conversion, so the
                // mastering chain sees the rate it is actually working at.
                val atModelRate = if (converted.sampleRate == NeuralVoice.RATE) {
                    converted.samples
                } else {
                    VoicePitch.resample(converted.samples, converted.sampleRate, NeuralVoice.RATE)
                }
                VoicePolish.master(atModelRate, NeuralVoice.RATE)
            }
        }
    }

    private fun open(): KokoroEngine? {
        kokoro?.let { return it }
        if (tried) return null
        synchronized(this) {
            kokoro?.let { return it }
            if (tried) return null
            tried = true
            val file = kokoroModel() ?: return null
            val opened = KokoroEngine.openOrNull(file.absolutePath)
            if (opened == null) {
                PrismPlatform.log.info(TAG, "Kokoro is on disk but would not open; using Pocket TTS")
            } else {
                PrismPlatform.log.info(TAG, "Using Kokoro + Seed-VC for trained voices")
            }
            kokoro = opened
            return opened
        }
    }

    /** The first installed Kokoro export, preferring the full model that is known good. */
    private fun kokoroModel(): File? = listOf(
        "model.onnx",
        "model_quantized.onnx",
        "model_fp16.onnx",
        "model_q8f16.onnx",
    ).map { File(KokoroInstall.homeDir(), it) }.firstOrNull { it.isFile }
}
