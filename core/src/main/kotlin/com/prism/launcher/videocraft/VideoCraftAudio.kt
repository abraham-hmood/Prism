package com.prism.launcher.videocraft

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.KokoroEngine
import com.prism.launcher.speech.KokoroInstall
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A block of mono speech ready to be placed on the timeline. */
data class SpeechClip(val samples: FloatArray, val sampleRate: Int)

/**
 * Dialogue, as the soundtrack builder needs it.
 *
 * Null rather than an exception when no voice can speak: a shot with a line still has music and
 * ambience, and losing the whole film because one TTS call failed would be a worse outcome than a
 * silent line. The builder records which lines were silent so the page can say so.
 */
fun interface VideoCraftSpeech {
    /**
     * Speaks one line.
     *
     * [voiceId] is the casting decision made by [VideoCraftVoices] -- a Kokoro voice id or a cloned
     * voice id. Null means "whatever this engine normally sounds like", which is what an imported
     * speech model with no voice list gets.
     */
    fun say(text: String, speaker: String, voiceId: String?): SpeechClip?
}

/**
 * The built-in speech path: Kokoro, or the imported model when it can be opened.
 *
 * Kokoro is in :core and runs on both platforms, which is why this is here rather than beside
 * either UI. When an imported speech model is active but not runnable, this falls back to Kokoro --
 * the same choice `PrismSpeaker` makes -- instead of producing silence.
 */
object KokoroVideoCraftSpeech : VideoCraftSpeech {

    override fun say(text: String, speaker: String, voiceId: String?): SpeechClip? {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return null
        return runCatching {
            // An imported speech model wins, exactly as it does for calls and for Sam. The importer
            // only claims compatible Kokoro-format ONNX exports; anything else opens as null and
            // this falls back to the installed Kokoro rather than producing noise.
            val imported = PrismSettings.getLocalAudioModelPath()
            val engine = KokoroEngine.openOrNull(imported.takeIf { it.isNotEmpty() })
                ?: KokoroEngine.openOrNull()
            if (engine == null) {
                PrismPlatform.log.warn("VideoCraft", "No speech engine is installed; dialogue will be silent")
                return null
            }
            // The cast voice when one was chosen, otherwise Sam's configured voice -- which is
            // what every line used before casting existed, and is still the right answer for a
            // narrator in a project with no characters.
            val voice = voiceId?.takeIf { it.isNotBlank() }
                ?: PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM)
            engine.synthesizeToPcm(cleaned, voice, PrismSettings.getKokoroSpeed())?.let {
                SpeechClip(it.samples, it.sampleRate)
            }
        }.onFailure {
            PrismPlatform.log.warn("VideoCraft", "Speech synthesis failed: ${it.message}")
        }.getOrNull()
    }

    fun available(): Boolean =
        PrismSettings.getLocalAudioModelPath().isNotEmpty() || KokoroInstall.isModelInstalled()
}

data class AudioBuildResult(
    val file: File,
    val durationSeconds: Float,
    val silentLines: Int,
    val usedSpeech: Boolean,
)

/**
 * Builds the complete soundtrack before any frame is encoded, so the muxer can write one
 * interleaved stream instead of a video file that has to be reopened and remuxed.
 *
 * ## The layering
 *
 * - Dialogue is spoken by the speech model and placed at each line's start.
 * - Ambience is per scene, synthesised from the location and mood.
 * - Music is per scene, and each scene's synth receives the tail of the previous scene's music so
 *   the score continues rather than restarting on a cut. This is the "conditioned on the tail"
 *   requirement; a procedural generator can honour it exactly by continuing phase and chord state.
 * - Every clip boundary gets a short equal-power crossfade, and dialogue ducks the music under it.
 *
 * ## Why the soundtrack is built in PCM
 *
 * A 60-second film is under 6 MB of float audio, and the muxers want interleaved samples anyway.
 * Holding it is cheaper than seeking around a dozen temporary WAVs and mixing them at finalise
 * time, and unlike video frames it is not the thing that exhausts a phone's heap.
 */
/**
 * A speech engine that says each line once.
 *
 * Wraps the platform's engine for the length of one render, because a render asks for the same line
 * twice: once when a scene's mouths are built, and once when the soundtrack is mixed. On a phone the
 * synthesis is the slowest part of the audio pass, and a second take of the same sentence would also be
 * a second thing for the mouth and the sound to disagree about.
 *
 * The map is bounded by the script -- every key is a line the film actually says -- so it needs no
 * eviction policy: a 200-line film is a few megabytes of float audio, the same order as the mix buffer
 * the soundtrack already holds.
 */
class CachedSpeech(private val delegate: VideoCraftSpeech) : VideoCraftSpeech {

    private val cache = HashMap<String, SpeechClip?>()

    override fun say(text: String, speaker: String, voiceId: String?): SpeechClip? =
        synchronized(cache) {
            val key = "$speaker\u0000$voiceId\u0000$text"
            if (cache.containsKey(key)) return cache[key]
            val clip = delegate.say(text, speaker, voiceId)
            cache[key] = clip
            clip
        }
}

object VideoCraftAudio {

    const val SAMPLE_RATE = 24_000
    private const val CROSSFADE_SECONDS = 0.12f

    fun build(
        project: VideoCraftProject,
        speech: VideoCraftSpeech,
        voiceFor: (speaker: String) -> String? = { null },
        /**
         * The clip chain's soundtrack: Prism Score only, and never a spoken line.
         *
         * A FILM MADE FROM THE VIDEO TAB HAS NO VOICE. Its chain has no mouths, and that tab does not
         * require a speech model or an audio generator -- so opening either one here would be work the
         * user did not ask for, on models the gate said were not needed, for samples nothing shows.
         * The built-in synth is deterministic and needs no model at all.
         */
        scoreOnly: Boolean = false,
        onStage: (String) -> Unit = {},
    ): AudioBuildResult {
        val clips = project.clips.ifEmpty {
            VideoCraftTimeline.planClips(project)
        }
        // NO DIALOGUE MEANS NO SPEECH ENGINE AT ALL, not "speech that returns nothing". The
        // checkbox and a script the planner wrote without lines are the same case here: the
        // soundtrack is score and ambience, the speech model is never opened, and a machine with
        // no speech model installed can still make a film.
        val speakDialogue = !scoreOnly && !project.noDialogue &&
            project.shots.any { shot -> shot.dialogue.any { it.text.isNotBlank() } }
        val total = max(1f, clips.maxOfOrNull { it.startSeconds + it.duration } ?: project.durationSeconds)
        val totalSamples = (total * SAMPLE_RATE).roundToInt() + SAMPLE_RATE
        val mix = FloatArray(totalSamples)
        var silent = 0
        var spoke = false

        val fadeSamples = (CROSSFADE_SECONDS * SAMPLE_RATE).toInt()
        var previousTail = FloatArray(0)

        for (scene in project.scenes) {
            onStage("Scoring scene ${scene.number}: ${scene.slug}")
            val sceneClips = clips.filter { it.sceneIndex == scene.number - 1 }
            if (sceneClips.isEmpty()) continue
            val sceneStart = sceneClips.minOf { it.startSeconds }
            val sceneEnd = sceneClips.maxOf { it.startSeconds + it.duration }
            val sceneSamples = ((sceneEnd - sceneStart) * SAMPLE_RATE).roundToInt().coerceAtLeast(1)
            // Each bed is synthesised PAST the scene's end by one crossfade, and both ends are
            // faded. Adjacent beds therefore overlap rather than merely touching, and their
            // weights sum to one across the overlap: a real crossfade, not a dip to silence at
            // the cut. This is why the length is sceneSamples + fadeSamples.
            val bedSamples = sceneSamples + fadeSamples

            // Ambience: one bed per scene.
            // Both beds go through the audio-model slot, not directly to ScoreSynth: selecting
            // Prism Score, an imported model or the cloud entry is a real choice, and the built-in
            // synth is only what that slot falls back to when no runnable engine answers.
            val ambience = crossfadeEdges(
                if (scoreOnly) ScoreSynth.ambience(scene, bedSamples, project.seed)
                else VideoCraftAudioModel.ambience(scene, bedSamples, project.seed),
                fadeSamples,
            )
            addAt(mix, ambience, (sceneStart * SAMPLE_RATE).roundToInt(), project.ambienceVolume * 0.5f)

            // Music: continues from the previous scene's tail.
            val music = crossfadeEdges(
                if (scoreOnly) ScoreSynth.music(scene, bedSamples, project.seed, previousTail)
                else VideoCraftAudioModel.music(scene, bedSamples, project.seed, previousTail),
                fadeSamples,
            )
            addAt(mix, music, (sceneStart * SAMPLE_RATE).roundToInt(), project.musicVolume)
            // The next scene continues from this one's own last half-second, not from the faded
            // crossfade tail -- that tail exists to hand over to the next scene, not to be played
            // twice.
            val tailEnd = min(music.size, sceneSamples)
            previousTail = music.copyOfRange(max(0, tailEnd - SAMPLE_RATE / 2), tailEnd)

            // Dialogue. Per shot by default; per scene batches the lines into one utterance. The
            // placement decisions live in [sceneUtterances], which the engine also calls to drive lip
            // sync -- one implementation, so a mouth can never move at a time the sound does not.
            if (speakDialogue) {
                val utterances = sceneUtterances(project, sceneClips, speech, voiceFor)
                for (utterance in utterances) {
                    val spokenClip = utterance.clip
                    if (spokenClip == null) {
                        silent++
                        continue
                    }
                    spoke = true
                    val resampled = resample(spokenClip.samples, spokenClip.sampleRate, SAMPLE_RATE)
                    val offset = (utterance.startSeconds * SAMPLE_RATE).roundToInt()
                    val fade = (0.06f * SAMPLE_RATE).toInt()
                    addAt(mix, fadeEdges(resampled, fade), offset, project.dialogueVolume)
                }
            }
        }

        softLimit(mix)
        val file = File(VideoCraftStore.artifacts(project.id), "audio.wav")
        writeWav(file, mix, SAMPLE_RATE)
        return AudioBuildResult(
            file = file,
            durationSeconds = total,
            silentLines = silent,
            usedSpeech = spoke,
        )
    }

    /**
     * One spoken thing, and when the film hears it.
     *
     * `clip` is null when the speech engine could not say it, which the mixer counts as a silent line and
     * the lip sync treats as a closed mouth.
     */
    data class Utterance(
        val speaker: String,
        val clip: SpeechClip?,
        val startSeconds: Float,
        val lines: Int,
    )

    /**
     * The dialogue a scene speaks and where it lands, in film time.
     *
     * Extracted from [build] so that lip sync and the soundtrack are derived from one set of decisions.
     * The two grouping modes are the whole of it: per scene one joined utterance at the scene's start, or
     * per shot one per line at the shot's first clip plus the line's own offset. The shot grouping exists
     * because the old loop walked clips and said every line of a shot once per clip it was split into.
     */
    fun sceneUtterances(
        project: VideoCraftProject,
        sceneClips: List<ClipPlan>,
        speech: VideoCraftSpeech,
        voiceFor: (String) -> String?,
    ): List<Utterance> {
        if (sceneClips.isEmpty()) return emptyList()
        val out = mutableListOf<Utterance>()
        if (project.audioScope == PrismSettings.AUDIO_SCOPE_SCENE) {
            val lines = sceneClips.flatMap { clip ->
                project.shots.getOrNull(clip.shotIndex)?.dialogue.orEmpty()
            }.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) return out
            val joined = lines.joinToString(" … ") { it.text }
            val speaker = lines.first().speaker
            out += Utterance(
                speaker = speaker,
                clip = speech.say(joined, speaker, voiceFor(speaker)),
                startSeconds = sceneClips.minOf { it.startSeconds },
                lines = lines.size,
            )
            return out
        }
        val byShot = sceneClips.groupBy { it.shotIndex }
        for ((shotIndex, shotClips) in byShot) {
            val shot = project.shots.getOrNull(shotIndex) ?: continue
            val first = shotClips.minByOrNull { it.startSeconds } ?: continue
            for (line in shot.dialogue) {
                if (line.text.isBlank()) continue
                out += Utterance(
                    speaker = line.speaker,
                    clip = speech.say(line.text, line.speaker, voiceFor(line.speaker)),
                    startSeconds = first.startSeconds + line.start,
                    lines = 1,
                )
            }
        }
        return out
    }

    private fun addAt(target: FloatArray, source: FloatArray, offset: Int, gain: Float) {
        if (source.isEmpty()) return
        val start = offset.coerceIn(0, max(0, target.size - 1))
        val count = min(source.size, target.size - start)
        for (i in 0 until count) target[start + i] += source[i] * gain
    }

    /**
     * Fades both ends of a bed so overlapping neighbours sum to constant level.
     *
     * Linear rather than equal-power weights: the two sides of a scene cut are the same score
     * and the same ambience continuing, so correlated signals want 1-t and t, which sums to one.
     * Equal-power would be right for unrelated signals and would bump the level in the middle.
     */
    private fun crossfadeEdges(source: FloatArray, samples: Int): FloatArray {
        if (samples <= 0 || source.size < samples * 2) return source
        val out = source.copyOf()
        for (i in 0 until samples) {
            val g = i.toFloat() / samples
            out[i] *= g
            out[out.size - 1 - i] *= g
        }
        return out
    }

    private fun fadeEdges(source: FloatArray, samples: Int): FloatArray {
        if (samples <= 0 || source.size < samples * 2) return source
        val out = source.copyOf()
        for (i in 0 until samples) {
            val g = i.toFloat() / samples
            out[i] *= g
            out[out.size - 1 - i] *= g
        }
        return out
    }

    private fun softLimit(samples: FloatArray) {
        // tanh-shaped limiter: linear below 0.7, smoothly compressed above, never clipped square.
        for (i in samples.indices) {
            val x = samples[i]
            samples[i] = if (x > 0.7f) 0.7f + (1f - 0.7f) * tanh((x - 0.7f) / 0.3f)
            else if (x < -0.7f) -0.7f - (1f - 0.7f) * tanh((-x - 0.7f) / 0.3f)
            else x
        }
    }

    private fun tanh(x: Float): Float {
        val e = exp(2 * x)
        return (e - 1) / (e + 1)
    }

    fun resample(source: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || source.isEmpty()) return source
        val out = FloatArray(max(1, (source.size.toLong() * to / from).toInt()))
        val ratio = from.toDouble() / to
        for (i in out.indices) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, source.size - 1)
            val i1 = min(i0 + 1, source.size - 1)
            val f = pos - i0
            out[i] = (source[i0] * (1 - f) + source[i1] * f).toFloat()
        }
        return out
    }

    fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        for (s in samples) {
            buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        file.writeBytes(buffer.array())
    }

    fun readWavPcm(file: File): Pair<FloatArray, Int>? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(22)
        val channels = buffer.short.toInt()
        buffer.position(24)
        val rate = buffer.int
        buffer.position(34)
        val bits = buffer.short.toInt()
        var pos = 12
        var dataStart = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataStart = pos + 8
                dataLength = size.coerceAtMost(bytes.size - dataStart)
                break
            }
            pos += 8 + size + (size and 1)
        }
        if (dataStart < 0 || bits != 16) return null
        val sampleCount = dataLength / 2 / max(1, channels)
        val out = FloatArray(sampleCount)
        val shorts = ByteBuffer.wrap(bytes, dataStart, dataLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.short.toInt()
            out[i] = (sum.toFloat() / channels / 32768f).coerceIn(-1f, 1f)
        }
        out to rate
    }.getOrNull()
}

/**
 * The built-in `Prism Score` audio generator.
 *
 * Deterministic synthesis rather than a neural music model: chord pads, a bass pulse, a sparse
 * arpeggio and filtered noise for ambience, all chosen from the scene's mood and description. The
 * point is that a scene always gets a score in the right key and tempo, and that consecutive
 * scenes continue from the previous tail rather than restarting, which is the behaviour the audio
 * model slot exists to provide. A downloaded model can replace this later through
 * [VideoCraftAudioModel]; the rest of the soundtrack builder does not change.
 */
object ScoreSynth {

    fun music(scene: Scene, samples: Int, seed: Long, tail: FloatArray): FloatArray {
        val out = FloatArray(samples)
        val rng = Rng(seed xor scene.slug.hashCode().toLong() xor scene.mood.hashCode().toLong())
        val mood = scene.mood.lowercase()
        val minor = mood.contains("dark") || mood.contains("tense") || mood.contains("restless") ||
            mood.contains("sad") || mood.contains("hushed")
        val tempo = when {
            mood.contains("fast") || mood.contains("action") -> 128.0
            mood.contains("restless") -> 104.0
            mood.contains("warm") -> 76.0
            else -> 88.0
        }
        val beat = 60.0 / tempo
        val root = 48 + (rng.nextInt(7) - 3)
        val scale = if (minor) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)
        val chordRoot = root + scale[rng.nextInt(scale.size)]
        val third = if (minor) 3 else 4
        val fifth = 7

        fun freq(semitone: Int) = 440.0 * Math.pow(2.0, (semitone - 69) / 12.0)

        val padFreqs = doubleArrayOf(
            freq(chordRoot), freq(chordRoot + third), freq(chordRoot + fifth),
            freq(chordRoot + 12),
        )
        // Each scene gets a phase derived from the previous tail, so the pad glides across a cut
        // instead of restarting at zero and clicking.
        var phaseA = if (tail.isNotEmpty()) tail[0] * 0.02 else rng.nextDouble()
        var phaseB = if (tail.size > 8) tail[8] * 0.02 else rng.nextDouble()
        var phaseC = if (tail.size > 16) tail[16] * 0.02 else rng.nextDouble()
        val padPhase = doubleArrayOf(phaseA, phaseB, phaseC, 0.0)

        val beats = samples / (beat * VideoCraftAudio.SAMPLE_RATE)
        for (i in 0 until samples) {
            val t = i.toDouble() / VideoCraftAudio.SAMPLE_RATE
            val beatPos = t / beat
            var v = 0.0

            // Chord pad: slow attack at the scene start, gentle detune.
            val padEnv = min(1.0, t / 0.9) * (1.0 - 0.15 * (t / max(0.1, samples / VideoCraftAudio.SAMPLE_RATE.toDouble())))
            for (k in padFreqs.indices) {
                padPhase[k] += 2 * PI * padFreqs[k] * (1.0 + 0.0015 * k) / VideoCraftAudio.SAMPLE_RATE
                v += sin(padPhase[k]) * 0.075 * padEnv
            }

            // Bass: one note per bar, with a soft attack.
            val bar = (beatPos / 4).toInt()
            val bassNote = chordRoot - 12 + scale[bar % scale.size]
            val bassEnv = exp(-((beatPos % 4) * 0.55))
            v += sin(2 * PI * freq(bassNote) * t) * 0.12 * bassEnv

            // Arpeggio: sparse, only on some beats, so it does not crowd dialogue.
            if ((beatPos.toInt() % 2) == 0) {
                val step = scale[(beatPos.toInt() / 2) % scale.size]
                val env = exp(-((beatPos % 1) * 6.0)) * 0.5
                v += sin(2 * PI * freq(chordRoot + 12 + step) * t) * 0.045 * env
            }

            // A soft kick on the bar, and a brushed tick on the backbeat.
            val barT = (beatPos % 1.0)
            v += sin(2 * PI * 54 * t) * exp(-barT * 18.0) * 0.09
            if ((beatPos % 2).toInt() == 1) {
                v += (rng.nextDouble() - 0.5) * exp(-barT * 25.0) * 0.035
            }
            out[i] = v.toFloat()
        }
        return out
    }

    fun ambience(scene: Scene, samples: Int, seed: Long): FloatArray {
        val out = FloatArray(samples)
        val rng = Rng(seed xor scene.location.hashCode().toLong() xor 0xBEEF)
        val text = (scene.summary + " " + scene.location + " " + scene.mood).lowercase()
        val rain = text.contains("rain") || text.contains("storm")
        val wind = text.contains("wind") || text.contains("ridge") || text.contains("field")
        val room = text.contains("room") || text.contains("interior") || text.contains("warm")
        val water = text.contains("water") || text.contains("river") || text.contains("sea")
        var lp = 0.0
        var lp2 = 0.0
        val cutoff = if (rain) 0.55 else if (wind) 0.08 else 0.18
        val sampleRate = VideoCraftAudio.SAMPLE_RATE
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            val white = rng.nextDouble() - 0.5
            lp += (white - lp) * cutoff
            lp2 += (lp - lp2) * 0.04
            var v = lp2 * 0.9
            if (wind) {
                v *= 0.55 + 0.45 * sin(2 * PI * 0.11 * t + 1.3)
                v += sin(2 * PI * 0.05 * t) * 0.02
            }
            if (room) {
                v *= 0.5
                v += sin(2 * PI * 60 * t) * 0.012
            }
            if (water) {
                v += sin(2 * PI * (0.19 + 0.04 * sin(t * 0.3)) * t) * 0.02
            }
            if (rain && rng.nextDouble() < 0.00035) {
                // Individual droplets, so rain is not just a noise wash.
                val drop = rng.nextDouble() * 0.06
                v += drop
            }
            out[i] = v.toFloat()
        }
        // Bird calls for outdoor scenes, as short descending chirps.
        if (!room && !rain && rng.nextDouble() < 0.8) {
            val calls = 1 + rng.nextInt(3)
            repeat(calls) {
                val at = (rng.nextDouble() * samples * 0.8).toInt()
                val len = (0.12 * sampleRate).toInt()
                val f0 = 1800.0 + rng.nextDouble() * 1600
                for (j in 0 until min(len, samples - at)) {
                    val p = j.toDouble() / len
                    val f = f0 * (1.0 - 0.35 * p)
                    out[at + j] += (sin(2 * PI * f * j / sampleRate) * exp(-p * 4) * 0.05).toFloat()
                }
            }
        }
        return out
    }
}

/**
 * The soundtrack model slot.
 *
 * A downloaded audio generation model would plug in here. The built-in is used when the slot holds
 * `builtin:prism-score`, a local file that this build cannot drive, or a cloud endpoint without a
 * compatible audio route. Keeping the choice in one object means the page can name what actually
 * played rather than what was selected.
 */
object VideoCraftAudioModel {

    fun describeSelection(): String {
        val path = PrismSettings.getLocalAudioGenModelPath()
        return when {
            path == PrismSettings.BUILTIN_AUDIO_GEN -> "Prism Score (built-in)"
            // A model that is selected is named by what it will actually be asked for, which is what
            // VideoCraftAudioGen answers: the cloud model id, the ONNX file, or the reason it cannot be
            // called and Prism Score will write the bed instead.
            path == PrismSettings.CLOUD_MODEL_SENTINEL -> VideoCraftAudioGen.describe()
            path.isNotEmpty() -> File(path).name + " · " + VideoCraftAudioGen.describe()
            else -> "No audio generator selected"
        }
    }

    /**
     * The music bed, from the selected model when it can render one and [ScoreSynth] when it cannot.
     *
     * The prompt is the scene's own description rather than a genre list: a music model asked for "a
     * hushed ridge at dawn, the traveller alone" writes something that belongs to that scene, which is
     * the same reason the shot list is written per scene.
     */
    fun music(scene: Scene, samples: Int, seed: Long, tail: FloatArray): FloatArray =
        VideoCraftAudioGen.render(scorePrompt(scene, "music"), samples.toFloat() / VideoCraftAudio.SAMPLE_RATE, seed)
            ?: ScoreSynth.music(scene, samples, seed, tail)

    fun ambience(scene: Scene, samples: Int, seed: Long): FloatArray =
        VideoCraftAudioGen.render(scorePrompt(scene, "ambience"), samples.toFloat() / VideoCraftAudio.SAMPLE_RATE, seed)
            ?: ScoreSynth.ambience(scene, samples, seed)

    /** One line describing the bed a scene needs, for a model to render. */
    private fun scorePrompt(scene: Scene, kind: String): String {
        val mood = if (scene.mood.isBlank()) "" else ", ${scene.mood}"
        val place = if (scene.location.isBlank()) "" else " at ${scene.location}"
        return "instrumental $kind for a film scene$place$mood: ${scene.summary}"
    }
}

/** A formant-based stand-in used when a speech model is selected but cannot be opened. */
object FallbackVoice {

    fun say(text: String): SpeechClip {
        val rate = VideoCraftAudio.SAMPLE_RATE
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val perWord = (0.28 * rate).toInt()
        val samples = FloatArray(max(perWord, words.size * perWord))
        val rng = Rng(text.hashCode().toLong())
        var at = 0
        for (word in words) {
            val syllables = max(1, word.length / 3)
            for (s in 0 until syllables) {
                val vowel = 220.0 + rng.nextDouble() * 160 + s * 12
                val len = perWord / syllables
                for (i in 0 until len) {
                    if (at + i >= samples.size) break
                    val p = i.toDouble() / len
                    val env = sin(PI * p).let { it * it } * 0.16
                    val t = i.toDouble() / rate
                    samples[at + i] = (
                        sin(2 * PI * vowel * t) * 0.6 +
                            sin(2 * PI * vowel * 2 * t) * 0.22 +
                            sin(2 * PI * vowel * 3 * t) * 0.1
                        ).toFloat() * env.toFloat()
                }
                at += len
            }
        }
        return SpeechClip(samples, rate)
    }
}
