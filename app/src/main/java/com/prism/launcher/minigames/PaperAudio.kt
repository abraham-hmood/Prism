package com.prism.launcher.minigames

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import com.prism.launcher.PrismLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Everything the paper empire sounds like, synthesised rather than shipped.
 *
 * ## Why there are no audio files
 *
 * Because there would have to be hundreds of them. The catalogue holds eleven hundred weapons
 * across three eras, and a bow, a musket, a railgun and a plasma caster do not sound remotely
 * alike — a handful of shared clips would be worse than silence, and recording or licensing enough
 * would be a larger job than the game. What a weapon sounds like, though, is almost entirely
 * decided by the three things the catalogue already knows: its class, how much damage it does and
 * how big its blast is. So the sound is generated from the weapon.
 *
 * Everything here is PCM written by hand into a byte array and handed to a [SoundPool] as a WAV.
 * No assets, no dependencies, and a new weapon class is a new case in [voiceFor] rather than a new
 * recording session.
 *
 * ## The peacetime bed
 *
 * A country that is not being invaded gets birds and wind. That is a deliberately different
 * texture from battle rather than decoration: it is the clearest possible signal that nothing is
 * happening, which matters in a game where something can start happening while you are not looking.
 *
 * ## It is always the player's choice
 *
 * Off by default would make the work invisible; on with no way out would be rude. It follows the
 * device's own media volume and can be silenced from the page, and everything checks [enabled]
 * before it makes a sound.
 */
object PaperAudio {

    private const val TAG = "PrismMinigames"
    private const val RATE = 22_050

    @Volatile
    var enabled: Boolean = true

    private var pool: SoundPool? = null
    private val sounds = ConcurrentHashMap<String, Int>()
    private var ambience: AudioTrack? = null

    @Volatile
    private var cacheDir: File? = null

    /** The families of sound a weapon can make. A weapon's class picks one. */
    enum class Voice { THWACK, CLANG, TWANG, CRACK, BOOM, RATTLE, ZAP, HUM, WHINE, RUMBLE }

    fun start(context: android.content.Context) {
        if (pool != null) return
        cacheDir = File(context.cacheDir, "paper-audio").apply { mkdirs() }
        pool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }

    fun stop() {
        stopAmbience()
        runCatching { pool?.release() }
        pool = null
        sounds.clear()
    }

    // ── Weapons ────────────────────────────────────────────────────────────

    /**
     * What a weapon sounds like.
     *
     * The class decides the family and the weapon's own numbers decide the pitch and the length —
     * a heavy siege weapon is the same voice as a light one, an octave down and half again as long.
     * That is what makes a thousand weapons sound distinct without a thousand recordings.
     */
    fun voiceFor(weapon: WeaponCatalog.Weapon): Voice = when (weapon.weaponClass) {
        WeaponCatalog.WeaponClass.BLADE -> Voice.CLANG
        WeaponCatalog.WeaponClass.BLUNT, WeaponCatalog.WeaponClass.POLEARM -> Voice.THWACK
        WeaponCatalog.WeaponClass.BOW, WeaponCatalog.WeaponClass.CROSSBOW -> Voice.TWANG
        WeaponCatalog.WeaponClass.THROWN -> Voice.THWACK
        WeaponCatalog.WeaponClass.SIEGE -> Voice.RUMBLE
        WeaponCatalog.WeaponClass.FIREARM -> Voice.CRACK
        WeaponCatalog.WeaponClass.AUTOMATIC -> Voice.RATTLE
        WeaponCatalog.WeaponClass.EXPLOSIVE, WeaponCatalog.WeaponClass.ARTILLERY,
        WeaponCatalog.WeaponClass.MISSILE,
        -> Voice.BOOM
        WeaponCatalog.WeaponClass.ARMOUR -> Voice.RUMBLE
        WeaponCatalog.WeaponClass.AIRCRAFT -> Voice.WHINE
        WeaponCatalog.WeaponClass.BEAM -> Voice.ZAP
        WeaponCatalog.WeaponClass.PLASMA -> Voice.HUM
        WeaponCatalog.WeaponClass.RAIL -> Voice.CRACK
        WeaponCatalog.WeaponClass.DRONE -> Voice.WHINE
        WeaponCatalog.WeaponClass.EXOTIC -> Voice.HUM
        WeaponCatalog.WeaponClass.SUPPORT -> Voice.THWACK
    }

    /** Fires [weapon], at a volume that falls off with how much is already going on. */
    fun fire(weapon: WeaponCatalog.Weapon, volume: Float = 0.6f) {
        if (!enabled) return
        val voice = voiceFor(weapon)
        // Heavier weapons are lower and longer. Clamped so nothing becomes infrasound or a click.
        val heft = (weapon.damage / 120.0).coerceIn(0.15, 3.0)
        val pitch = (1.35 - heft * 0.3).coerceIn(0.55, 1.5)
        val key = "${voice.name}:${(pitch * 10).toInt()}:${weapon.splash.coerceAtMost(4)}"
        play(key, volume) { synth(voice, pitch, weapon.splash) }
    }

    /** A building coming down. */
    fun collapse() = playSimple("collapse", 0.7f) { synth(Voice.RUMBLE, 0.7, 3) }

    /** A building finished, a law passed — the small affirmative noises. */
    fun chime() = playSimple("chime", 0.45f) { chimePcm() }

    /** A thing placed on the paper: a pencil scratch. */
    fun scratch() = playSimple("scratch", 0.35f) { scratchPcm() }

    /** Something refused. */
    fun refuse() = playSimple("refuse", 0.4f) { refusePcm() }

    private fun playSimple(key: String, volume: Float, make: () -> ByteArray) =
        play(key, volume, make)

    private fun play(key: String, volume: Float, make: () -> ByteArray) {
        if (!enabled) return
        val soundPool = pool ?: return
        runCatching {
            val id = sounds.getOrPut(key) {
                val file = File(cacheDir ?: return, "$key.wav")
                if (!file.isFile) file.writeBytes(wav(make()))
                soundPool.load(file.absolutePath, 1)
            }
            soundPool.play(id, volume, volume, 1, 0, 1f)
        }.onFailure { PrismLogger.logWarning(TAG, "Could not play $key: ${it.message}") }
    }

    // ── Peacetime ──────────────────────────────────────────────────────────

    /**
     * Birds and wind, for a country nobody is attacking.
     *
     * Written straight to an [AudioTrack] in a loop rather than through the pool, because it is
     * minutes long and generated on the fly: a bed of filtered noise for the wind with occasional
     * swept chirps over it. Each chirp is a short frequency sweep, which is most of what a bird
     * actually is to an ear that is not paying attention — and this bed is explicitly for an ear
     * that is not paying attention.
     */
    fun startAmbience() {
        if (!enabled || ambience != null) return
        runCatching {
            val seconds = 6
            val samples = RATE * seconds
            val pcm = ShortArray(samples)
            val rng = Rng(Rng.seedOf("ambience"))

            // Wind: brown-ish noise, very quiet, slowly breathing.
            var brown = 0.0
            for (i in 0 until samples) {
                brown = (brown + (rng.nextDouble() - 0.5) * 0.06).coerceIn(-1.0, 1.0)
                val breath = 0.5 + 0.5 * sin(2 * PI * i / (RATE * 4.0))
                pcm[i] = (brown * 1400 * breath).toInt().toShort()
            }

            // Birds: a dozen chirps scattered through, each a short upward sweep.
            repeat(14) {
                val at = rng.nextInt(samples - RATE / 2)
                val length = RATE / 12 + rng.nextInt(RATE / 14)
                val from = 1800.0 + rng.nextDouble() * 1600
                val to = from + 600 + rng.nextDouble() * 1400
                var phase = 0.0
                for (i in 0 until length) {
                    val t = i.toDouble() / length
                    val freq = from + (to - from) * t
                    phase += 2 * PI * freq / RATE
                    // A short attack and a long tail, which is what stops a chirp sounding like a beep.
                    val envelope = exp(-3.0 * t) * sin(PI * t.coerceIn(0.0, 1.0))
                    val value = sin(phase) * envelope * 5200
                    val index = at + i
                    if (index < samples) {
                        pcm[index] = (pcm[index] + value.toInt()).coerceIn(-32000, 32000).toShort()
                    }
                }
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, pcm.size, -1)
            track.setVolume(0.35f)
            track.play()
            ambience = track
        }.onFailure { PrismLogger.logWarning(TAG, "No ambience: ${it.message}") }
    }

    fun stopAmbience() {
        runCatching {
            ambience?.pause()
            ambience?.flush()
            ambience?.release()
        }
        ambience = null
    }

    // ── Synthesis ──────────────────────────────────────────────────────────

    /**
     * One weapon sound.
     *
     * Each voice is a different shape of envelope over a different source — noise for the percussive
     * ones, a tone for the electronic ones, and a sweep for the ones that are supposed to travel.
     * Nothing here is clever; what makes them distinguishable is that the SHAPES differ, which is
     * how ears tell sounds apart far more than timbre does.
     */
    private fun synth(voice: Voice, pitch: Double, splash: Int): ByteArray {
        val rng = Rng(Rng.seedOf("synth", voice.name, (pitch * 100).toInt(), splash))
        val length = when (voice) {
            Voice.BOOM, Voice.RUMBLE -> RATE / 2
            Voice.RATTLE -> RATE / 3
            Voice.HUM, Voice.ZAP, Voice.WHINE -> RATE / 5
            else -> RATE / 8
        }
        val pcm = ShortArray(length)
        var phase = 0.0
        var lowpass = 0.0

        for (i in 0 until length) {
            val t = i.toDouble() / length
            val noise = rng.nextDouble() * 2 - 1
            val value = when (voice) {
                // A dull impact: filtered noise with a hard attack.
                Voice.THWACK -> {
                    lowpass += (noise - lowpass) * 0.10
                    lowpass * exp(-14.0 * t)
                }
                // Metal: two detuned tones ringing out.
                Voice.CLANG -> {
                    phase += 2 * PI * 900 * pitch / RATE
                    (sin(phase) + 0.6 * sin(phase * 1.51) + 0.3 * noise) * exp(-9.0 * t) * 0.5
                }
                // A bowstring: a short pitched pluck with a noisy front.
                Voice.TWANG -> {
                    phase += 2 * PI * 260 * pitch / RATE
                    (sin(phase) * 0.7 + noise * exp(-60.0 * t)) * exp(-16.0 * t)
                }
                // A gunshot: almost all attack, almost no tail.
                Voice.CRACK -> {
                    lowpass += (noise - lowpass) * 0.55
                    lowpass * exp(-40.0 * t)
                }
                // An explosion: low filtered noise with a long decay.
                Voice.BOOM -> {
                    lowpass += (noise - lowpass) * 0.035
                    lowpass * exp(-5.0 * t) * (1.0 + splash * 0.25)
                }
                // Automatic fire: a train of cracks.
                Voice.RATTLE -> {
                    val period = RATE / 18
                    val within = (i % period).toDouble() / period
                    lowpass += (noise - lowpass) * 0.5
                    lowpass * exp(-26.0 * within) * exp(-2.2 * t)
                }
                // A beam: a pitched zap that falls.
                Voice.ZAP -> {
                    phase += 2 * PI * (2400 - 1500 * t) * pitch / RATE
                    (sin(phase) * 0.8 + noise * 0.15) * exp(-11.0 * t)
                }
                // Plasma: a thick unstable tone.
                Voice.HUM -> {
                    phase += 2 * PI * (180 + 60 * sin(t * 40)) * pitch / RATE
                    (sin(phase) + 0.4 * sin(phase * 2.02) + noise * 0.2) * exp(-6.0 * t) * 0.6
                }
                // A drone or a jet: a rising whine.
                Voice.WHINE -> {
                    phase += 2 * PI * (700 + 900 * t) * pitch / RATE
                    (sin(phase) * 0.6 + noise * 0.25) * sin(PI * t)
                }
                // Something enormous moving: low noise, slow in, slow out.
                Voice.RUMBLE -> {
                    lowpass += (noise - lowpass) * 0.02
                    lowpass * sin(PI * t) * 1.2
                }
            }
            pcm[i] = (value * 11_000).toInt().coerceIn(-32_000, 32_000).toShort()
        }
        return toBytes(pcm)
    }

    private fun chimePcm(): ByteArray {
        val length = RATE / 3
        val pcm = ShortArray(length)
        var a = 0.0
        var b = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / length
            a += 2 * PI * 880 / RATE
            b += 2 * PI * 1320 / RATE
            pcm[i] = ((sin(a) * 0.6 + sin(b) * 0.4) * exp(-6.0 * t) * 8_000).toInt().toShort()
        }
        return toBytes(pcm)
    }

    private fun scratchPcm(): ByteArray {
        val length = RATE / 12
        val pcm = ShortArray(length)
        val rng = Rng(Rng.seedOf("scratch"))
        var lowpass = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / length
            lowpass += ((rng.nextDouble() * 2 - 1) - lowpass) * 0.25
            pcm[i] = (lowpass * sin(PI * t) * 5_000).toInt().toShort()
        }
        return toBytes(pcm)
    }

    private fun refusePcm(): ByteArray {
        val length = RATE / 6
        val pcm = ShortArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / length
            phase += 2 * PI * (330 - 120 * t) / RATE
            pcm[i] = (sin(phase) * exp(-7.0 * t) * 6_000).toInt().toShort()
        }
        return toBytes(pcm)
    }

    private fun toBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, value ->
            out[i * 2] = (value.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Wraps raw PCM in a WAV header.
     *
     * SoundPool will not take a byte array, only a file or a descriptor, so the generated audio has
     * to become a real file — and a file needs a container. Forty-four bytes of header is the whole
     * difference between "noise SoundPool refuses" and "a sound".
     */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun short(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        int(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int(16)
        short(1)             // PCM
        short(1)             // mono
        int(RATE)
        int(RATE * 2)        // byte rate
        short(2)             // block align
        short(16)            // bits per sample
        out.write("data".toByteArray())
        int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
