package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Makes Kokoro sound like it feels something, by steering its style vector.
 *
 * ## What is steered, and why only half of it
 *
 * A Kokoro voice is a 256-value style vector (see [KokoroEngine]). Kokoro is built on StyleTTS 2,
 * which splits that vector in two: the first 128 values condition the decoder -- the timbre, who is
 * speaking -- and the last 128 condition the prosody predictor: pitch contour, energy and the length
 * of every phoneme. Emotion in speech lives almost entirely in prosody, so only the second half is
 * moved. Heart stays recognisably Heart; Heart angry speaks higher, louder, faster and brighter.
 *
 * ## What "towards an emotion" means, measurably
 *
 * Each emotion has a vocal profile from the research on how emotions sound -- Juslin & Laukka's 2003
 * review of 104 studies, Scherer (2003), and Banse & Scherer (1996): how far the voice's pitch rises
 * or falls, how much wider or flatter its melody is, how much faster or slower it speaks, how much
 * louder, and how much brighter (more high-frequency energy, as in a tense or shouting voice). Where
 * the studies measured an emotion directly, its profile is theirs; the rest are placed by where they
 * sit on valence, arousal and dominance, the three dimensions those cues follow.
 *
 * A candidate style is judged by speaking a sentence with it, measuring those five things from the
 * audio, and comparing them with the same sentence spoken neutrally plus the emotion's profile. The
 * search variables are a handful of directions in prosody space -- the directions in which Kokoro's
 * own published voices differ from this one, which are directions the model is known to render well
 * -- and the speaking speed.
 *
 * ## The two searches
 *
 *  - **Particle swarm optimisation**: a swarm of candidate styles explores the space at once, each
 *    pulled towards its own best and the swarm's best. Global and derivative-free, so it is run once
 *    per voice and emotion on a calibration sentence and the result remembered on disk.
 *  - **Gradient descent**: ONNX Runtime gives no gradients, so they are estimated by simultaneous
 *    perturbation (SPSA) -- two renders per step, whatever the number of variables. It runs DURING
 *    inference, on the sentence actually being spoken, and keeps refining across later sentences, so
 *    the steering adapts to real text rather than to one calibration line.
 */
object EmotionSteering {

    private const val TAG = "PrismSpeech"

    enum class Method { NONE, PSO, GRADIENT }

    /** The vocal change an emotion makes, relative to the same voice speaking neutrally. */
    data class Profile(
        /** Pitch level, in semitones. */
        val pitch: Double,
        /** Pitch range (how much the melody moves), as a ratio. */
        val range: Double,
        /** Speaking rate, as a ratio: above 1 is faster. */
        val rate: Double,
        /** Loudness, in decibels. */
        val energy: Double,
        /** Brightness (high-frequency energy), as a ratio. */
        val brightness: Double,
    )

    data class Emotion(
        val id: String,
        val label: String,
        val valence: Double,
        val arousal: Double,
        val dominance: Double,
        /** How the emotion sounds, for the settings page. */
        val sound: String,
        /** A line to test it with, carrying the indicator. */
        val sample: String,
        val aliases: List<String> = emptyList(),
        val measured: Profile? = null,
        /**
         * True for an emotion that is SHOUTED. A style vector changes how Kokoro phrases a line but
         * cannot make it raise its voice past what it was trained on, so a yelled line is also driven
         * hard afterwards: loudness pushed up, the vocal-effort frequencies boosted and the peaks
         * saturated, which is what a voice at full force does to the sound.
         */
        val yell: Boolean = false,
    ) {
        /** The profile the steering aims for: measured where the research measured it, derived otherwise. */
        val profile: Profile
            get() = measured ?: Profile(
                pitch = 2.6 * arousal + 0.9 * valence,
                range = exp(0.33 * arousal + 0.22 * valence),
                rate = exp(0.16 * arousal),
                energy = 4.2 * arousal + 1.4 * dominance,
                brightness = exp(0.24 * arousal + 0.08 * dominance),
            )
    }

    private fun e(
        id: String, label: String, v: Double, a: Double, d: Double, sound: String, sample: String,
        aliases: List<String> = emptyList(), measured: Profile? = null, yell: Boolean = false,
    ) = Emotion(id, label, v, a, d, sound, "[$id] $sample", aliases, measured, yell)

    /**
     * Every emotion the steering supports: the 27 categories Cowen and Keltner (2017) found people
     * reliably distinguish, plus the further emotions the vocal-expression studies cover (contempt,
     * pride, shame, guilt, love, hope and the rest).
     */
    val ALL: List<Emotion> = listOf(
        e("neutral", "Neutral", 0.0, 0.0, 0.0, "Even pitch and pace; the voice as it is.", "This is how I sound when nothing in particular is going on.",
            measured = Profile(0.0, 1.0, 1.0, 0.0, 1.0)),
        e("admiration", "Admiration", 0.7, 0.3, 0.1, "Warm, slightly raised and open, with lingering emphasis.", "Honestly, the way you handled that was brilliant. I really look up to you.", listOf("admiring")),
        e("adoration", "Adoration", 0.85, 0.25, 0.0, "Soft, warm, gently rising, unhurried.", "Oh, look at you. You are just the sweetest thing in the whole world.", listOf("adoring")),
        e("aesthetic", "Aesthetic appreciation", 0.6, 0.1, 0.0, "Quiet, slow and breathy, savouring each word.", "Look at that light on the water. It is so beautiful it almost hurts.", listOf("aesthetic appreciation", "appreciation")),
        e("amusement", "Amusement", 0.75, 0.5, 0.25, "Bouncy, higher, wide melody, laughter in the voice.", "Ha! No way, you actually wore that to the meeting? That's hilarious!", listOf("amused", "laughing", "playful")),
        e("anger", "Anger", -0.65, 0.8, 0.6, "Loud, fast, high and harsh, with sharp stresses.", "I told you a hundred times not to touch it, and you did it anyway!", listOf("angry", "mad", "cross", "irate"),
            measured = Profile(3.0, 1.35, 1.15, 6.0, 1.35)),
        e("rage", "Rage", -0.85, 1.0, 0.9, "Yelling: as loud and high as the voice goes, fast, harsh and strained.",
            "Get out! Get out of my house right now! I never want to see you again!",
            listOf("furious", "enraged", "livid", "yelling", "shouting", "screaming", "yell", "shout", "fury"),
            measured = Profile(6.0, 1.3, 1.12, 11.0, 1.9), yell = true),
        e("anxiety", "Anxiety", -0.55, 0.6, -0.4, "Higher, quicker, tight and uneven, with hesitations.", "What if it doesn't work? What if everyone notices? I just, I can't stop thinking about it.", listOf("anxious", "nervous", "worried", "worry")),
        e("awe", "Awe", 0.5, 0.45, -0.3, "Hushed, slow, with a lifted, held pitch.", "Wow. The whole sky is full of stars. I have never seen anything like this.", listOf("awed", "wonder", "amazed")),
        e("awkwardness", "Awkwardness", -0.35, 0.25, -0.45, "Hesitant, quieter, uneven pace, falling off at the ends.", "Oh. Um. So, I think that was meant for someone else. Sorry. Awkward.", listOf("awkward", "embarrassed", "embarrassment")),
        e("boredom", "Boredom", -0.4, -0.7, -0.1, "Low, flat, slow and quiet.", "Another meeting about the meeting. Great. Can't wait.", listOf("bored"),
            measured = Profile(-1.5, 0.55, 0.85, -3.0, 0.85)),
        e("calmness", "Calmness", 0.4, -0.6, 0.2, "Low, smooth, slow and soft.", "Take a slow breath. Everything is okay. We have all the time we need.", listOf("calm", "serene", "peaceful", "relaxed")),
        e("confusion", "Confusion", -0.2, 0.25, -0.35, "Rising questions, uneven pace, a puzzled lift.", "Wait, what? I thought the train left at nine. Why is it already gone?", listOf("confused", "puzzled")),
        e("craving", "Craving", 0.3, 0.55, 0.0, "Eager, slightly faster, pressing on key words.", "I need a slice of that pizza right now. I have been thinking about it all day.", listOf("desire", "longing")),
        e("disgust", "Disgust", -0.7, 0.35, 0.3, "Low, slow, with drawn-out, nasal stresses.", "Ugh, that smell. Who left the milk out for a week? That is revolting.", listOf("disgusted", "grossed out"),
            measured = Profile(-1.5, 0.9, 0.85, 1.0, 1.0)),
        e("empathic", "Empathic pain", -0.55, 0.3, -0.3, "Softened, slower, slightly lowered, caring.", "Oh no. I am so sorry that happened to you. That must have hurt so much.", listOf("empathic pain", "empathy", "sympathy", "compassion")),
        e("entrancement", "Entrancement", 0.45, -0.2, -0.2, "Slow, soft, dreamy and drawn out.", "I could listen to this song forever. It is like floating somewhere far away.", listOf("entranced", "dreamy")),
        e("excitement", "Excitement", 0.8, 0.9, 0.4, "High, fast, loud and bright, with a wide melody.", "We got the tickets! We are actually going! This is the best day ever!", listOf("excited", "thrilled", "enthusiastic")),
        e("fear", "Fear", -0.75, 0.75, -0.6, "High, fast, tight and trembling, with narrow pitch.", "Did you hear that? Someone is in the house. Please, we have to get out now.", listOf("afraid", "scared", "terrified", "frightened"),
            measured = Profile(4.0, 0.85, 1.15, 2.0, 1.15)),
        e("horror", "Horror", -0.85, 0.7, -0.5, "Strained, breathy and shaken, rising then breaking.", "Oh my god. There is blood everywhere. What happened in here?", listOf("horrified")),
        e("interest", "Interest", 0.45, 0.35, 0.15, "Lively, attentive, gently rising.", "Really? Tell me more about that. How did you even figure it out?", listOf("interested", "curious", "curiosity")),
        e("joy", "Joy", 0.9, 0.6, 0.35, "Higher, bright, wide melody, slightly faster and louder.", "I passed! I actually passed the exam! I am so happy right now!", listOf("happy", "happiness", "joyful", "delighted", "cheerful"),
            measured = Profile(3.0, 1.45, 1.08, 4.0, 1.2)),
        e("nostalgia", "Nostalgia", 0.3, -0.3, -0.1, "Soft, slow, wistful, gently falling.", "Do you remember summers at grandma's house? I miss those days so much.", listOf("nostalgic", "wistful")),
        e("relief", "Relief", 0.6, -0.3, 0.15, "A falling, released voice, slower and softer.", "Oh, thank goodness. They are safe. I can finally breathe again.", listOf("relieved")),
        e("romance", "Romance", 0.75, 0.2, 0.0, "Low, soft, slow and intimate.", "Being here with you, right now, under these lights. There is nowhere else I would rather be.", listOf("romantic", "love", "loving", "affection")),
        e("sadness", "Sadness", -0.8, -0.45, -0.4, "Low, slow, quiet and flat, with a darker tone.", "She is gone. I keep reaching for the phone to call her, and then I remember.", listOf("sad", "unhappy", "sorrow", "grief", "melancholy", "crying"),
            measured = Profile(-2.0, 0.6, 0.82, -5.0, 0.8)),
        e("satisfaction", "Satisfaction", 0.7, 0.05, 0.4, "Settled, warm, moderately low and even.", "There. Everything is finished and it all works. That feels really good.", listOf("satisfied", "content", "contentment")),
        e("desire", "Sexual desire", 0.6, 0.45, 0.2, "Low, slow, breathy and soft.", "Come a little closer. I like the way you look tonight.", listOf("sexual desire", "seductive", "flirtatious", "flirty")),
        e("surprise", "Surprise", 0.2, 0.75, 0.0, "A sudden high jump, wide melody, quick.", "What? You are here? I thought you were in another country!", listOf("surprised", "shocked", "astonished"),
            measured = Profile(4.0, 1.6, 1.05, 3.0, 1.15)),
        e("contempt", "Contempt", -0.6, 0.2, 0.7, "Low, slow, flat and clipped, sneering emphasis.", "Oh, you think you are an expert now? That is adorable.", listOf("contemptuous", "sarcastic", "sarcasm", "disdain", "scornful"),
            measured = Profile(-1.0, 1.0, 0.9, 1.0, 1.05)),
        e("pride", "Pride", 0.7, 0.4, 0.7, "Firm, full, steady and slightly louder.", "I built this whole thing myself, from nothing. And it works.", listOf("proud", "confident", "confidence"),
            measured = Profile(0.5, 1.1, 0.95, 3.0, 1.1)),
        e("shame", "Shame", -0.65, -0.1, -0.7, "Quiet, low, slow and trailing off.", "I should never have said that. I am so ashamed of myself.", listOf("ashamed", "humiliated"),
            measured = Profile(-1.0, 0.7, 0.85, -5.0, 0.85)),
        e("guilt", "Guilt", -0.6, 0.0, -0.5, "Lowered, hesitant, quiet and slow.", "It was my fault. I knew it was wrong and I did it anyway.", listOf("guilty", "remorse", "regret")),
        e("hope", "Hope", 0.6, 0.3, 0.1, "Lifted and warm, gently rising towards the end.", "Maybe this time it will work. I really think things are about to change.", listOf("hopeful", "optimistic", "optimism")),
        e("gratitude", "Gratitude", 0.8, 0.2, 0.0, "Warm, soft, slightly slowed and sincere.", "Thank you. Really, from the bottom of my heart, thank you for being there.", listOf("grateful", "thankful")),
        e("tenderness", "Tenderness", 0.7, -0.4, -0.1, "Low, soft, slow, a narrow gentle melody.", "Shh, it is okay. I am right here. Go back to sleep, little one.", listOf("tender", "gentle", "soothing"),
            measured = Profile(-0.5, 0.8, 0.88, -4.0, 0.85)),
        e("frustration", "Frustration", -0.6, 0.55, 0.2, "Tense, clipped, louder bursts, sighing.", "It crashed again! I have restarted it six times and it still does not work!", listOf("frustrated", "annoyed", "irritated", "irritation", "exasperated")),
        e("jealousy", "Jealousy", -0.55, 0.45, -0.1, "Tight, bitter, pointed emphasis.", "Oh, so she gets the promotion. Of course she does. Lucky her.", listOf("jealous", "envy", "envious")),
        e("determination", "Determination", 0.3, 0.55, 0.75, "Firm, steady, louder and deliberate.", "I do not care how long it takes. We are finishing this, tonight.", listOf("determined", "resolute")),
        e("loneliness", "Loneliness", -0.65, -0.35, -0.5, "Quiet, slow, low and hollow.", "The house is so quiet now. Some nights I just talk to the walls.", listOf("lonely")),
        e("triumph", "Triumph", 0.85, 0.85, 0.85, "Loud, high, bright and expansive.", "We did it! We won the championship! Nobody believed in us, and we did it!", listOf("triumphant", "victorious", "elated", "elation")),
    )

    private val byName: Map<String, Emotion> = buildMap {
        ALL.forEach { em ->
            put(em.id, em)
            put(em.label.lowercase(), em)
            em.aliases.forEach { put(it.lowercase(), em) }
        }
    }

    /** The emotion a name or adverb refers to: "angry", "Anger", "angrily", "excitedly". */
    fun find(name: String): Emotion? {
        val key = name.trim().lowercase()
        byName[key]?.let { return it }
        if (key.endsWith("ly")) {
            val stem = key.removeSuffix("ly")
            (byName[stem] ?: byName[stem.removeSuffix("i") + "y"] ?: byName[stem + "e"])?.let { return it }
            if (key.endsWith("ily")) byName[key.removeSuffix("ily") + "y"]?.let { return it }
        }
        return null
    }

    fun method(): Method = when {
        PrismSettings.getKokoroEmotionPso() -> Method.PSO
        PrismSettings.getKokoroEmotionGradient() -> Method.GRADIENT
        else -> Method.NONE
    }

    // ── Indicators ──────────────────────────────────────────────────────

    /** `[angry]`, `(sadly)`, `[in an excited voice]` -- a bracketed emotion name or adverb. */
    private val INDICATOR = Regex("[\\[(]\\s*(?:in an? |with |sounding )?([A-Za-z][A-Za-z ]{1,28}?)(?: voice| tone)?\\s*[\\])]")

    /**
     * [text] split at each emotion indicator: every piece and the emotion it should be spoken with.
     * Brackets that do not name an emotion are left as text for the caller to deal with.
     */
    fun segments(text: String): List<Pair<Emotion?, String>> {
        val out = ArrayList<Pair<Emotion?, String>>()
        var current: Emotion? = null
        var at = 0
        for (m in INDICATOR.findAll(text)) {
            val emotion = find(m.groupValues[1]) ?: continue
            val before = text.substring(at, m.range.first)
            if (before.isNotBlank()) out += current to before.trim()
            current = emotion.takeIf { it.id != "neutral" }
            at = m.range.last + 1
        }
        val rest = text.substring(at)
        if (rest.isNotBlank()) out += current to rest.trim()
        return out
    }

    /** [text] with its emotion indicators removed, for an engine that cannot act on them. */
    fun strip(text: String): String =
        INDICATOR.replace(text) { m -> if (find(m.groupValues[1]) != null) "" else m.value }
            .replace(Regex("\\s{2,}"), " ").trim()

    // ── Measuring a voice ───────────────────────────────────────────────

    /** The five things an emotion changes, measured from audio. */
    data class Features(val pitch: Double, val range: Double, val seconds: Double, val energy: Double, val brightness: Double)

    /**
     * Pitch (median, in semitones above 55 Hz), pitch range (10th to 90th percentile, in semitones),
     * the length of the speech, its loudness (dB of the voiced frames) and its brightness (the share
     * of energy in the signal's first difference -- a cheap spectral-tilt measure). Null when the clip
     * has too little voiced speech to measure.
     */
    fun measure(samples: FloatArray, rate: Int): Features? {
        if (samples.isEmpty()) return null
        val step = if (rate >= 20_000) 2 else 1
        val sr = rate / step
        val x = FloatArray(samples.size / step) { samples[it * step] }
        val frame = (sr * 0.04).toInt()
        val hop = (sr * 0.01).toInt().coerceAtLeast(1)
        val minLag = sr / 450
        val maxLag = sr / 65
        val pitches = ArrayList<Double>()
        val energies = ArrayList<Double>()
        var diffEnergy = 0.0
        var totalEnergy = 0.0
        var first = -1
        var last = -1
        var i = 0
        while (i + frame + maxLag < x.size) {
            var e = 0.0
            for (j in 0 until frame) e += x[i + j] * x[i + j]
            val rms = sqrt(e / frame)
            if (rms > 0.01) {
                if (first < 0) first = i
                last = i + frame
                var best = 0.0
                var bestLag = 0
                for (lag in minLag..maxLag) {
                    var c = 0.0
                    var e2 = 0.0
                    for (j in 0 until frame) {
                        c += x[i + j] * x[i + j + lag]
                        e2 += x[i + j + lag] * x[i + j + lag]
                    }
                    val r = c / sqrt(e * e2 + 1e-12)
                    if (r > best) { best = r; bestLag = lag }
                }
                if (best > 0.5 && bestLag > 0) {
                    pitches += 12 * log2(sr.toDouble() / bestLag / 55.0)
                    energies += 20 * log10(rms + 1e-9)
                    for (j in 1 until frame) {
                        val d = x[i + j] - x[i + j - 1]
                        diffEnergy += d * d
                    }
                    totalEnergy += e
                }
            }
            i += hop
        }
        if (pitches.size < 8 || first < 0) return null
        val sorted = pitches.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return Features(
            pitch = pct(0.5),
            range = (pct(0.9) - pct(0.1)).coerceAtLeast(0.3),
            seconds = (last - first).toDouble() / sr,
            energy = energies.average(),
            brightness = diffEnergy / (totalEnergy + 1e-12),
        )
    }

    /** How far [f] is from [neutral] moved by [p]. Scaled so each cue counts about equally. */
    internal fun loss(f: Features, neutral: Features, p: Profile, x: DoubleArray): Double {
        val pitch = (f.pitch - (neutral.pitch + p.pitch)) / 1.2
        val range = ln(f.range / (neutral.range * p.range)) / 0.13
        val time = ln(f.seconds / (neutral.seconds / p.rate)) / 0.06
        val energy = (f.energy - (neutral.energy + p.energy)) / 2.0
        val bright = ln(f.brightness / (neutral.brightness * p.brightness)) / 0.12
        // A small pull towards the voice as published: of two styles that sound equally angry, the
        // one closer to the real voice sounds less synthetic.
        val prior = 0.04 * x.sumOf { it * it }
        return pitch * pitch + range * range + time * time + energy * energy + bright * bright + prior
    }

    // ── Steering ────────────────────────────────────────────────────────

    /**
     * What steering produced for one voice and emotion.
     *
     * [offset] and [speedFactor] are the style-vector change and the speed the search settled on. The
     * rest is a finishing pass measured at the same time: whatever the style could not reach of the
     * emotion's loudness, brightness and pitch is applied to the audio directly, so the result hits
     * the profile rather than stopping wherever the model's style space ran out.
     */
    class Steer(
        val offset: FloatArray,
        val speedFactor: Float,
        val gainDb: Float = 0f,
        val tilt: Float = 0f,
        val pitchFix: Float = 0f,
        /** The pitch change the steered voice actually makes, in semitones -- what a cloned voice keeps. */
        val pitchDelta: Float = 0f,
        val yell: Boolean = false,
    )

    /** Renders [text] with the voice's style moved by [offset] at [speed]. Supplied by [KokoroEngine]. */
    fun interface Renderer {
        fun render(text: String, offset: FloatArray?, speed: Float): FloatArray?
    }

    /** A voice being tuned for an emotion, for a progress notification. */
    data class Tuning(val voice: String, val emotion: Emotion, val method: Method, val fraction: Float, val stage: String, val done: Boolean)

    /**
     * Called as a voice is tuned. Defaults to the platform's notifier, so a phone and a desktop both
     * show progress; replace it to route progress elsewhere.
     */
    @Volatile var onTuning: (Tuning) -> Unit = { t -> notifyTuning(t) }

    private const val NOTIFICATION_ID = 7541
    @Volatile private var lastPercent = -1

    private fun notifyTuning(t: Tuning) {
        val notifier = PrismPlatform.notifier
        val title = "Tuning ${voiceLabel(t.voice)} for ${t.emotion.label.lowercase()}"
        if (t.done) {
            lastPercent = -1
            runCatching { notifier.cancel(NOTIFICATION_ID) }
            return
        }
        val percent = (t.fraction * 100).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return
        lastPercent = percent
        runCatching { notifier.notifyProgress("Voice tuning", NOTIFICATION_ID, title, t.stage, percent, ongoing = true) }
    }

    private fun voiceLabel(id: String): String =
        ClonedVoices.find(id)?.displayName ?: KokoroVoices.find(id)?.displayName ?: id

    private const val DIRECTIONS = 12
    private const val WIDTH = 256
    private const val PROSODY_START = 128
    private const val CALIBRATION = "This is a short sentence, spoken the way I am feeling right now."

    /** kokoro_hack's weighting of the spectral term against the emotion term. */
    private const val STFT_WEIGHT = 0.7

    /** How long a first-time particle swarm search may take, and a gradient pass on one line. */
    private const val PSO_BUDGET_SECONDS = 90.0
    private const val GD_BUDGET_SECONDS = 15.0

    private val lock = Any()
    private val memory = HashMap<String, Pair<Steer, Int>>()

    /**
     * The steering for [emotion] in [voiceId], found by [method] if it is not already known.
     *
     * [text] is what is about to be spoken; gradient descent optimises on it directly. [label] names
     * the voice in the notification -- a cloned voice is tuned through its base voice but is shown
     * under its own name. Returns null when no steering applies.
     */
    fun steer(
        voiceId: String,
        emotion: Emotion,
        method: Method,
        text: String,
        baseSpeed: Float,
        renderer: Renderer,
        label: String = voiceId,
    ): Steer? {
        if (method == Method.NONE || emotion.id == "neutral") return null
        synchronized(lock) {
            val key = "${method.name}|$voiceId|${emotion.id}"
            val known = memory[key] ?: load(key)?.also { memory[key] = it }
            when (method) {
                Method.PSO -> known?.let { return it.first }
                Method.GRADIENT -> if (known != null && known.second >= GD_TOTAL) return known.first
                else -> return null
            }
            val report = { fraction: Float, stage: String ->
                runCatching { onTuning(Tuning(label, emotion, method, fraction, stage, done = false)) }
            }
            report(0f, "Rendering the voice as it is…")
            try {
                val basis = basisFor(voiceId)
                if (basis.isEmpty()) return null
                val sentence = if (method == Method.PSO) CALIBRATION else text.take(160).ifBlank { CALIBRATION }
                val started = System.nanoTime()
                val neutralAudio = renderer.render(sentence, null, baseSpeed) ?: return null
                val renderSeconds = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.05)
                val neutral = measure(neutralAudio, KokoroEngine.SAMPLE_RATE) ?: return known?.first
                val neutralBands = Spectra.bands(neutralAudio)
                val profile = emotion.profile
                val dims = basis.size + 1

                fun offsetOf(x: DoubleArray): FloatArray {
                    val out = FloatArray(WIDTH)
                    basis.forEachIndexed { b, dir -> for (k in 0 until WIDTH) out[k] += (dir[k] * x[b]).toFloat() }
                    return out
                }
                fun speedOf(x: DoubleArray) = exp(x[dims - 1] * 0.14).toFloat()
                val features = HashMap<Int, Features>()
                fun evaluate(x: DoubleArray): Double {
                    val audio = renderer.render(sentence, offsetOf(x), baseSpeed * speedOf(x)) ?: return 1e6
                    val f = measure(audio, KokoroEngine.SAMPLE_RATE) ?: return 1e6
                    features[x.contentHashCode()] = f
                    // kokoro_hack's objective, with its emotion embedding replaced by measured vocal
                    // cues: how far from the emotion, plus 0.7 x a multi-resolution spectral distance
                    // from the neutral render, which keeps the voice from turning to artefacts.
                    val emotional = loss(f, neutral, profile, x) / 5.0
                    val spectral = Spectra.distance(Spectra.bands(Spectra.stretch(audio, neutralAudio.size)), neutralBands)
                    return emotional + STFT_WEIGHT * spectral
                }

                val deadline = System.currentTimeMillis() + ((if (method == Method.PSO) PSO_BUDGET_SECONDS else GD_BUDGET_SECONDS) * 1000).toLong()
                val outOfTime = { System.currentTimeMillis() > deadline }
                val evaluations = when (method) {
                    Method.PSO -> (PSO_BUDGET_SECONDS / renderSeconds).toInt().coerceIn(48, 640)
                    else -> (GD_BUDGET_SECONDS / renderSeconds).toInt().coerceIn(8, 80)
                }
                val start = known?.let { previous -> fromSteer(previous.first, basis) } ?: DoubleArray(dims)
                val best = when (method) {
                    Method.PSO -> {
                        val particles = if (evaluations >= 320) 16 else 12
                        val iterations = (evaluations / particles).coerceAtLeast(3)
                        pso(dims, ::evaluate, particles = particles, iterations = iterations, stop = outOfTime) { i, n ->
                            report(i.toFloat() / n, "Particle swarm: iteration $i of up to $n")
                            rest()
                        }
                    }
                    else -> {
                        val steps = if (known == null) (evaluations / 2).coerceIn(4, 40) else GD_REFINE
                        spsa(start, steps, ::evaluate, stop = outOfTime) { i, n ->
                            report(i.toFloat() / n, "Gradient descent: step $i of $n")
                            rest()
                        }
                    }
                }
                report(0.98f, "Measuring the result…")
                val achieved = features[best.contentHashCode()]
                    ?: renderer.render(sentence, offsetOf(best), baseSpeed * speedOf(best))?.let { measure(it, KokoroEngine.SAMPLE_RATE) }
                    ?: neutral
                // The finishing pass: what the style did not reach, made up on the audio itself.
                val steer = Steer(
                    offset = offsetOf(best),
                    speedFactor = speedOf(best),
                    gainDb = (neutral.energy + profile.energy - achieved.energy).toFloat().coerceIn(-10f, 12f),
                    tilt = ln(profile.brightness * neutral.brightness / achieved.brightness).toFloat().coerceIn(-0.6f, 0.9f),
                    pitchFix = (neutral.pitch + profile.pitch - achieved.pitch).toFloat().let { if (abs(it) < 0.6f) 0f else it.coerceIn(-3f, 3f) },
                    pitchDelta = (achieved.pitch - neutral.pitch).toFloat(),
                    yell = emotion.yell,
                )
                val total = (known?.second ?: 0) + if (method == Method.GRADIENT) GD_REFINE.coerceAtLeast(1) else 0
                memory[key] = steer to total
                save(key, steer, total)
                PrismPlatform.log.info(TAG, "Steered $voiceId towards ${emotion.id} by ${method.name} in ${evaluations} renders")
                return steer
            } finally {
                runCatching { onTuning(Tuning(label, emotion, method, 1f, "Done", done = true)) }
            }
        }
    }

    /**
     * A pause between iterations when the device is hot, by Prism's own duty cycle. A search is a
     * minute or more of solid synthesis, and on a warm phone that is what gets an app force-stopped
     * for overheating.
     */
    private fun rest() {
        val duty = com.prism.core.PrismSustain.dutyCycle()
        if (duty.isDutyCycled) runCatching { Thread.sleep(duty.restMillis) }
    }

    private const val GD_REFINE = 3
    private const val GD_TOTAL = 60

    /**
     * Particle swarm optimisation with kokoro_hack's constants: inertia 0.72, cognitive and social
     * weights 1.49 (Clerc's constriction values), positions bounded to ±2.5, velocities clipped to
     * ±0.5, and an early stop after a run of iterations with no improvement. The first particle starts
     * at the voice as published, so the swarm never returns something worse than not steering.
     */
    internal fun pso(
        dims: Int,
        f: (DoubleArray) -> Double,
        particles: Int = 12,
        iterations: Int = 20,
        patience: Int = 8,
        seed: Long = 1234,
        stop: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): DoubleArray {
        val rng = java.util.Random(seed)
        val bound = 2.5
        val pos = Array(particles) { p -> DoubleArray(dims) { if (p == 0) 0.0 else (rng.nextDouble() * 2 - 1) * bound } }
        val vel = Array(particles) { DoubleArray(dims) }
        val bestPos = Array(particles) { pos[it].copyOf() }
        val bestVal = DoubleArray(particles) { f(pos[it]) }
        var g = bestVal.indices.minByOrNull { bestVal[it] } ?: 0
        var globalPos = bestPos[g].copyOf()
        var globalVal = bestVal[g]
        var stale = 0
        for (it in 0 until iterations) {
            if (stop()) break
            progress(it + 1, iterations)
            var improved = false
            for (p in 0 until particles) {
                for (d in 0 until dims) {
                    vel[p][d] = (0.72 * vel[p][d] +
                        1.49 * rng.nextDouble() * (bestPos[p][d] - pos[p][d]) +
                        1.49 * rng.nextDouble() * (globalPos[d] - pos[p][d])).coerceIn(-0.5, 0.5)
                    pos[p][d] = (pos[p][d] + vel[p][d]).coerceIn(-bound, bound)
                }
                val value = f(pos[p])
                if (value < bestVal[p]) { bestVal[p] = value; bestPos[p] = pos[p].copyOf() }
                if (value < globalVal - 1e-9) { globalVal = value; globalPos = pos[p].copyOf(); improved = true }
            }
            g = bestVal.indices.minByOrNull { bestVal[it] } ?: g
            stale = if (improved) 0 else stale + 1
            if (stale >= patience) break
        }
        return globalPos
    }

    /**
     * Gradient descent on gradients estimated by simultaneous perturbation (Spall's SPSA), stepped
     * with Adam under a cosine-annealed learning rate -- kokoro_hack's optimiser, with SPSA standing
     * in for the backpropagation ONNX Runtime cannot do. Two renders estimate the gradient for every
     * variable at once, so the cost does not grow with the number of directions. The best point seen
     * is returned rather than the last.
     */
    internal fun spsa(
        start: DoubleArray,
        steps: Int,
        f: (DoubleArray) -> Double,
        seed: Long = 11,
        stop: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): DoubleArray {
        val rng = java.util.Random(seed + start.sum().toBits())
        var x = start.copyOf()
        var best = x.copyOf()
        var bestVal = f(x)
        val m = DoubleArray(x.size)
        val v = DoubleArray(x.size)
        val lr0 = 0.35
        for (t in 0 until steps) {
            if (stop()) break
            progress(t + 1, steps)
            val lr = 0.5 * lr0 * (1 + kotlin.math.cos(PI * t / steps.coerceAtLeast(1)))
            val c = 0.3 / (t + 1.0).pow(0.101)
            val delta = DoubleArray(x.size) { if (rng.nextBoolean()) 1.0 else -1.0 }
            val plus = DoubleArray(x.size) { x[it] + c * delta[it] }
            val minus = DoubleArray(x.size) { x[it] - c * delta[it] }
            val yPlus = f(plus)
            val yMinus = f(minus)
            listOf(plus to yPlus, minus to yMinus).forEach { (p, value) -> if (value < bestVal) { bestVal = value; best = p.copyOf() } }
            val step = t + 1
            x = DoubleArray(x.size) { d ->
                val g = (yPlus - yMinus) / (2 * c) * delta[d]
                m[d] = 0.9 * m[d] + 0.1 * g
                v[d] = 0.999 * v[d] + 0.001 * g * g
                val mHat = m[d] / (1 - 0.9.pow(step))
                val vHat = v[d] / (1 - 0.999.pow(step))
                (x[d] - lr * mHat / (sqrt(vHat) + 1e-8)).coerceIn(-2.5, 2.5)
            }
        }
        val last = f(x)
        return if (last < bestVal) x else best
    }

    /** Projects a stored steering back onto [basis], to warm-start a further search from it. */
    private fun fromSteer(steer: Steer, basis: List<DoubleArray>): DoubleArray {
        val x = DoubleArray(basis.size + 1)
        basis.forEachIndexed { b, dir ->
            var dot = 0.0
            var norm = 0.0
            for (k in 0 until WIDTH) { dot += dir[k] * steer.offset[k]; norm += dir[k] * dir[k] }
            x[b] = if (norm > 0) dot / norm else 0.0
        }
        x[basis.size] = ln(steer.speedFactor.toDouble().coerceAtLeast(0.1)) / 0.14
        return x
    }

    /**
     * The directions the search moves in, all inside the prosody half: how each other installed voice
     * differs from this one, and the main ways this voice's own style changes across utterance
     * lengths. Kokoro was trained on exactly these vectors, so moving along them stays where it renders
     * well. Made orthogonal, sized to a typical voice-to-voice step, and padded with deterministic
     * random directions when few voices are installed.
     */
    private fun basisFor(voiceId: String): List<DoubleArray> {
        val own = row(voiceId) ?: return emptyList()
        val others = KokoroInstall.installedVoices().filter { it != voiceId && !ClonedVoices.isCloned(it) }.sorted()
            .mapNotNull { row(it) }
        val diffs = others.map { o -> DoubleArray(WIDTH) { k -> if (k < PROSODY_START) 0.0 else (o[k] - own[k]).toDouble() } }
            .sortedByDescending { d -> d.sumOf { it * it } }
        // How the voice's own prosody moves between a short line and a long one: a direction the
        // model uses for pacing, and one that never changes who is speaking.
        val lengthwise = listOfNotNull(row(voiceId, 10), row(voiceId, 120), row(voiceId, 300)).map { r ->
            DoubleArray(WIDTH) { k -> if (k < PROSODY_START) 0.0 else (r[k] - own[k]).toDouble() }
        }
        val scale = diffs.map { d -> sqrt(d.sumOf { it * it }) }.let { if (it.isEmpty()) 0.0 else it.average() }
            .takeIf { it > 1e-6 }
            ?: (sqrt((PROSODY_START until WIDTH).sumOf { own[it].toDouble().pow(2) } / (WIDTH - PROSODY_START)) * 4.0)
        val basis = ArrayList<DoubleArray>()
        fun addOrthogonal(v: DoubleArray) {
            val w = v.copyOf()
            basis.forEach { b ->
                val dot = (0 until WIDTH).sumOf { w[it] * b[it] }
                for (k in 0 until WIDTH) w[k] -= dot * b[k]
            }
            val n = sqrt(w.sumOf { it * it })
            if (n > 1e-6) basis += DoubleArray(WIDTH) { w[it] / n }
        }
        (diffs.take(DIRECTIONS - 2) + lengthwise).forEach { if (basis.size < DIRECTIONS) addOrthogonal(it) }
        val rng = java.util.Random(voiceId.hashCode().toLong())
        while (basis.size < DIRECTIONS) {
            addOrthogonal(DoubleArray(WIDTH) { k -> if (k < PROSODY_START) 0.0 else rng.nextGaussian() })
        }
        // A coefficient of 1 is about 0.4 of a voice-to-voice step; ±2.5 reaches a full voice and beyond.
        return basis.map { b -> DoubleArray(WIDTH) { b[it] * scale * 0.4 } }
    }

    /** One style row of a voice file -- a published voice or a legacy cloned tensor. */
    private fun row(voiceId: String, index: Int = 40): FloatArray? = runCatching {
        val file = KokoroInstall.voiceFile(voiceId)
        if (!file.isFile) return null
        val bytes = ByteArray(WIDTH * 4)
        file.inputStream().use { input ->
            input.skip(index.toLong() * WIDTH * 4)
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < bytes.size) return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(WIDTH) { buffer.get(it) }
    }.getOrNull()

    // ── Finishing the audio ─────────────────────────────────────────────

    /**
     * Applies what the style vector could not do: the remaining loudness, brightness and pitch, and
     * for a yelled emotion the strain of a voice at full force. Used after Kokoro speaks a steered line,
     * and after a cloned voice re-voices one.
     */
    fun finish(samples: FloatArray, rate: Int, steer: Steer, applyPitch: Boolean = true): FloatArray {
        var out = samples
        if (applyPitch && steer.pitchFix != 0f) out = VoicePitch.shift(out, rate, 2.0.pow(steer.pitchFix / 12.0).toFloat())
        if (abs(steer.tilt) > 0.02f) out = tilt(out, steer.tilt)
        if (steer.yell) return yell(out)
        return gain(out, steer.gainDb)
    }

    /**
     * Emotion for audio no style vector made: a cloned voice spoken by a reference engine (which
     * speaks the words itself, in the recorded voice). Every cue of the profile is applied to the
     * sound directly -- pitch, pace, loudness, brightness, and shouting for a yelled emotion.
     */
    fun acoustic(samples: FloatArray, rate: Int, emotion: Emotion): FloatArray {
        if (emotion.id == "neutral") return samples
        val p = emotion.profile
        var out = samples
        val pitch = p.pitch.coerceIn(-4.0, 6.0)
        if (abs(pitch) > 0.3) out = VoicePitch.shift(out, rate, 2.0.pow(pitch / 12.0).toFloat())
        if (abs(p.rate - 1.0) > 0.03) {
            // Faster without a pitch change: shift down by the rate, then resample back up.
            val r = p.rate.coerceIn(0.75, 1.3)
            out = VoicePitch.resample(VoicePitch.shift(out, rate, (1.0 / r).toFloat()), (rate * r).toInt(), rate)
        }
        out = tilt(out, ln(p.brightness).toFloat().coerceIn(-0.6f, 0.9f))
        if (emotion.yell) return yell(out)
        return gain(out, p.energy.toFloat().coerceIn(-8f, 12f))
    }

    /** A first-order spectral tilt: positive brightens (more high-frequency energy), negative darkens. */
    private fun tilt(samples: FloatArray, amount: Float): FloatArray {
        val out = FloatArray(samples.size)
        var previous = 0f
        val a = amount.coerceIn(-0.9f, 0.9f)
        for (i in samples.indices) {
            val x = samples[i]
            if (a >= 0) {
                out[i] = x + a * (x - previous)
                previous = x
            } else {
                // A one-pole low-pass whose strength follows the amount: real darkening, not a nudge.
                val k = (-a * 1.6f).coerceAtMost(0.92f)
                previous = (1 - k) * x + k * previous
                out[i] = previous
            }
        }
        return out
    }

    /**
     * What shouting does to a voice: energy moves up into the 1-4 kHz "vocal effort" region, and the
     * peaks compress and saturate as the voice runs out of headroom.
     */
    private fun yell(samples: FloatArray): FloatArray {
        val bright = tilt(samples, 0.75f)
        val peak = bright.maxOfOrNull { abs(it) }?.takeIf { it > 1e-6f } ?: return samples
        val drive = 2.6f / peak
        return FloatArray(bright.size) { i -> (kotlin.math.tanh((bright[i] * drive).toDouble()) * 0.9).toFloat() }
    }

    private fun gain(samples: FloatArray, db: Float): FloatArray {
        if (abs(db) < 0.1f) return samples
        val g = 10.0.pow(db / 20.0).toFloat()
        // A soft ceiling rather than clipping: a louder line should get louder, not crackle.
        return FloatArray(samples.size) { i ->
            val y = samples[i] * g
            if (abs(y) < 0.8f) y else (kotlin.math.sign(y) * (0.8f + 0.2f * kotlin.math.tanh(((abs(y) - 0.8f) / 0.2f).toDouble()).toFloat()))
        }
    }

    // ── Remembering ─────────────────────────────────────────────────────

    private fun store(): File = File(KokoroInstall.homeDir(), "emotion_steering.json")

    /** Bumped when the search changes, so steerings found by an older search are searched again. */
    private const val VERSION = 3

    private fun load(key: String): Pair<Steer, Int>? = runCatching {
        val root = JSONObject(store().readText())
        val o = root.optJSONObject(key) ?: return null
        if (o.optInt("v") != VERSION) return null
        val values = o.optJSONArray("offset") ?: return null
        Steer(
            offset = FloatArray(values.length()) { values.optDouble(it).toFloat() },
            speedFactor = o.optDouble("speed", 1.0).toFloat(),
            gainDb = o.optDouble("gain", 0.0).toFloat(),
            tilt = o.optDouble("tilt", 0.0).toFloat(),
            pitchFix = o.optDouble("pitchFix", 0.0).toFloat(),
            pitchDelta = o.optDouble("pitchDelta", 0.0).toFloat(),
            yell = o.optBoolean("yell"),
        ) to o.optInt("steps")
    }.getOrNull()

    private fun save(key: String, steer: Steer, steps: Int) {
        runCatching {
            val file = store()
            val root = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
            root.put(key, JSONObject()
                .put("v", VERSION)
                .put("offset", JSONArray().also { a -> steer.offset.forEach { a.put(it.toDouble()) } })
                .put("speed", steer.speedFactor.toDouble())
                .put("gain", steer.gainDb.toDouble())
                .put("tilt", steer.tilt.toDouble())
                .put("pitchFix", steer.pitchFix.toDouble())
                .put("pitchDelta", steer.pitchDelta.toDouble())
                .put("yell", steer.yell)
                .put("steps", steps))
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    /** The steering already found for [emotion] in [voiceId], if any, without searching. */
    fun known(voiceId: String, emotion: Emotion, method: Method): Steer? {
        if (method == Method.NONE) return null
        val key = "${method.name}|$voiceId|${emotion.id}"
        synchronized(lock) { return memory[key]?.first ?: load(key)?.also { memory[key] = it }?.first }
    }

    /** Forgets every learned steering, e.g. after the Kokoro model itself changes. */
    fun forget() {
        synchronized(lock) {
            memory.clear()
            runCatching { store().delete() }
        }
    }
}

/**
 * The multi-resolution spectral comparison kokoro_hack keeps its voices clean with.
 *
 * Three STFTs at its resolutions (FFT 512, 1024 and 2048 with hops of 50, 120 and 240 samples), each
 * reduced to 40 log-spaced bands so that a change of PITCH -- which an emotion is supposed to make --
 * is not mistaken for damage, while a change of timbre, buzz or noise is. The distance is the usual
 * pair: spectral convergence plus mean absolute log-magnitude difference.
 */
internal object Spectra {

    private val RESOLUTIONS = listOf(512 to 50, 1024 to 120, 2048 to 240)
    private const val BANDS = 40

    /** Band energies per frame, for each resolution. */
    fun bands(samples: FloatArray): List<List<DoubleArray>> = RESOLUTIONS.map { (fft, hop) ->
        val window = DoubleArray(fft) { 0.5 - 0.5 * kotlin.math.cos(2 * PI * it / fft) }
        val edges = IntArray(BANDS + 1) { b ->
            (fft / 2.0 * (60.0 / 12_000).pow(1 - b.toDouble() / BANDS)).toInt().coerceIn(1, fft / 2)
        }
        val frames = ArrayList<DoubleArray>()
        var start = 0
        val re = DoubleArray(fft)
        val im = DoubleArray(fft)
        while (start + fft <= samples.size) {
            for (i in 0 until fft) { re[i] = samples[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            frames += DoubleArray(BANDS) { b ->
                var e = 0.0
                for (k in edges[b] until maxOf(edges[b + 1], edges[b] + 1)) e += re[k] * re[k] + im[k] * im[k]
                sqrt(e)
            }
            start += hop
        }
        frames
    }

    /** Spectral convergence plus log-magnitude distance, averaged over the resolutions. */
    fun distance(a: List<List<DoubleArray>>, b: List<List<DoubleArray>>): Double {
        var total = 0.0
        var counted = 0
        for (r in a.indices) {
            val n = minOf(a[r].size, b.getOrNull(r)?.size ?: 0)
            if (n == 0) continue
            var diff = 0.0
            var ref = 0.0
            var logs = 0.0
            for (f in 0 until n) for (k in 0 until BANDS) {
                val x = a[r][f][k]
                val y = b[r][f][k]
                diff += (x - y) * (x - y)
                ref += y * y
                logs += abs(ln(x + 1e-5) - ln(y + 1e-5))
            }
            total += sqrt(diff) / (sqrt(ref) + 1e-9) + logs / (n * BANDS)
            counted++
        }
        return if (counted == 0) 1.0 else total / counted
    }

    /** [samples] resampled to [length], so a line spoken faster is compared frame for frame. */
    fun stretch(samples: FloatArray, length: Int): FloatArray {
        if (samples.size == length || samples.isEmpty() || length <= 0) return samples
        val step = (samples.size - 1).toDouble() / (length - 1).coerceAtLeast(1)
        return FloatArray(length) { i ->
            val pos = i * step
            val a = pos.toInt().coerceAtMost(samples.size - 1)
            val bIdx = (a + 1).coerceAtMost(samples.size - 1)
            val t = (pos - a).toFloat()
            samples[a] * (1 - t) + samples[bIdx] * t
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = kotlin.math.cos(ang)
            val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Breaths in the pauses, so a long reading sounds like someone speaking rather than a voice that
 * never needs air.
 *
 * A breath is shaped noise: an inhale is mostly 500 Hz-3 kHz turbulence through the open mouth, quiet
 * (about 26 dB below the speech around it), rising quickly and fading slowly. Kokoro's own pauses
 * are silent, so each one long enough to breathe in -- a sentence break, a comma held a beat -- gets
 * an inhale placed just before the next phrase, the way a speaker breathes in to start it.
 */
object Breath {

    /** One inhale of [ms] milliseconds at [level] (peak amplitude). */
    fun inhale(rate: Int, ms: Int, level: Float, seed: Long = 1L): FloatArray {
        val n = rate * ms / 1000
        val rng = java.util.Random(seed)
        val out = FloatArray(n)
        // Band-passed noise: a high-pass and a low-pass one-pole pair, then a resonance near 1.6 kHz.
        var hp = 0.0; var lpPrev = 0.0; var prev = 0.0
        val aHp = exp(-2 * PI * 450.0 / rate)
        val aLp = exp(-2 * PI * 3_000.0 / rate)
        var y1 = 0.0; var y2 = 0.0
        val r = 0.97
        val theta = 2 * PI * 1_600.0 / rate
        for (i in 0 until n) {
            val white = rng.nextGaussian()
            hp = aHp * (hp + white - prev); prev = white
            lpPrev = (1 - aLp) * hp + aLp * lpPrev
            val res = lpPrev + 2 * r * kotlin.math.cos(theta) * y1 - r * r * y2
            y2 = y1; y1 = res
            val t = i.toDouble() / n
            // Quick onset, slow release: air drawn in, then the mouth closing to speak.
            val envelope = if (t < 0.3) (t / 0.3).pow(1.5) else exp(-(t - 0.3) * 3.2)
            out[i] = (0.6 * lpPrev + 0.08 * res).toFloat() * envelope.toFloat()
        }
        val peak = out.maxOfOrNull { abs(it) }?.takeIf { it > 1e-9f } ?: return out
        for (i in out.indices) out[i] = out[i] / peak * level
        return out
    }

    /** The breath level that suits [samples]: about 26 dB under their speech. */
    fun levelFor(samples: FloatArray): Float {
        val loud = samples.filter { abs(it) > 0.01f }
        if (loud.isEmpty()) return 0.02f
        val rms = sqrt(loud.sumOf { (it * it).toDouble() } / loud.size).toFloat()
        return (rms * 10.0.pow(-26.0 / 20.0).toFloat() * 3f).coerceIn(0.004f, 0.05f)
    }

    /**
     * [samples] with an inhale in every pause of at least 220 ms. The audio keeps its length: breaths
     * replace silence, they do not add time.
     */
    fun fillPauses(samples: FloatArray, rate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val frame = rate / 100
        val level = levelFor(samples)
        val out = samples.copyOf()
        var silentStart = -1
        var count = 0
        var f = 0
        while (f * frame < samples.size) {
            var e = 0.0
            val from = f * frame
            val to = minOf(from + frame, samples.size)
            for (i in from until to) e += samples[i] * samples[i]
            val quiet = sqrt(e / (to - from)) < 0.006
            if (quiet && silentStart < 0) silentStart = from
            if ((!quiet || to == samples.size) && silentStart >= 0) {
                val length = from - silentStart
                // Not at the very start: nobody inhales before the first word of a clip mid-sentence.
                if (length >= rate * 22 / 100 && silentStart > 0 && to < samples.size) {
                    val ms = (length * 1000 / rate * 0.75).toInt().coerceIn(180, 480)
                    val breath = inhale(rate, ms, level, seed = (silentStart + count).toLong())
                    val at = from - breath.size - rate * 3 / 100
                    if (at > silentStart) for (i in breath.indices) out[at + i] += breath[i]
                    count++
                }
                silentStart = -1
            }
            f++
        }
        return out
    }
}
