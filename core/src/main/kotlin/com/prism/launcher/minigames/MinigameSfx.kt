package com.prism.launcher.minigames

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The cabinet's effects, generated rather than shipped.
 *
 * A gunshot, a reload, a growl and a footstep are short enough to synthesise in a few thousand
 * samples. Generating them keeps the APK from carrying a folder of WAVs and, more usefully, lets a
 * custom Zombies pack replace any one of them by name without a new code path.
 */
object MinigameSfx {

    const val SAMPLE_RATE = 22050

    enum class Sound(val file: String) {
        SHOT("shot.wav"),
        SHOTGUN("shotgun.wav"),
        RELOAD("reload.wav"),
        ZOMBIE("zombie.wav"),
        STEP("step.wav"),
        HURT("hurt.wav"),
        WAVE("wave.wav"),
        EMPTY("empty.wav"),
        PICKUP("pickup.wav"),
        SCREAM("scream.wav"),
        REVIVE("revive.wav"),
        SWITCH("switch.wav"),
    }

    fun pcm(sound: Sound, seed: Long = 1L): ShortArray {
        val random = Random(seed xor sound.ordinal.toLong() * 7919L)
        val length = when (sound) {
            Sound.STEP -> 0.09
            Sound.EMPTY, Sound.SWITCH -> 0.06
            Sound.PICKUP, Sound.REVIVE -> 0.35
            Sound.SCREAM -> 0.75
            Sound.WAVE -> 1.2
            Sound.ZOMBIE -> 0.9
            Sound.SHOT, Sound.SHOTGUN, Sound.RELOAD, Sound.HURT -> 0.3
        }
        val samples = (length * SAMPLE_RATE).toInt()
        val mix = FloatArray(samples)
        when (sound) {
            Sound.SHOT -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val noise = random.nextFloat() * 2f - 1f
                    val thump = sin(2.0 * PI * (120.0 - 70.0 * t / length) * t).toFloat()
                    mix[i] += (noise * 0.55f + thump * 0.6f) * exp(-18f * t).toFloat()
                }
            }
            Sound.SHOTGUN -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val noise = random.nextFloat() * 2f - 1f
                    val body = sin(2.0 * PI * (78.0 - 40.0 * t / length) * t).toFloat()
                    mix[i] += (noise * 0.7f + body * 0.8f) * exp(-9f * t).toFloat()
                }
            }
            Sound.RELOAD -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val click = when {
                        t < 0.035f -> exp(-80f * t) * sin(2.0 * PI * 1600.0 * t).toFloat()
                        t > 0.11f && t < 0.16f -> exp(-70f * (t - 0.11f)) * sin(2.0 * PI * 900.0 * t).toFloat()
                        else -> 0f
                    }
                    mix[i] += click * 0.6f
                }
            }
            Sound.ZOMBIE -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val wobble = sin(2.0 * PI * 3.2 * t).toFloat() * 18f
                    val growl = sin(2.0 * PI * (72.0 + wobble) * t).toFloat()
                    val rough = (random.nextFloat() * 2f - 1f) * 0.35f
                    val envelope = (1f - (t / length).toFloat()).coerceIn(0f, 1f)
                    mix[i] += (growl * 0.5f + rough).toFloat() * envelope
                }
            }
            Sound.STEP -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    mix[i] += sin(2.0 * PI * 55.0 * t).toFloat() * exp(-35f * t).toFloat() * 0.5f +
                        (random.nextFloat() * 2f - 1f) * exp(-70f * t).toFloat() * 0.12f
                }
            }
            Sound.HURT -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val tone = sin(2.0 * PI * (420.0 - 260.0 * t / length) * t).toFloat()
                    mix[i] += (tone * 0.3f + (random.nextFloat() * 2f - 1f) * 0.2f) * exp(-9f * t).toFloat()
                }
            }
            Sound.WAVE -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val frequency = 180.0 + 420.0 * (t / length) + sin(2.0 * PI * 2.0 * t) * 20.0
                    mix[i] += sin(2.0 * PI * frequency * t).toFloat() * 0.32f *
                        (if (t < 0.08f) t / 0.08f else 1f)
                }
            }
            Sound.EMPTY, Sound.SWITCH -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    mix[i] += sin(2.0 * PI * 1100.0 * t).toFloat() * exp(-90f * t).toFloat() * 0.4f
                }
            }
            Sound.PICKUP -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val frequency = if (t < 0.17f) 660.0 else 990.0
                    mix[i] += sin(2.0 * PI * frequency * t).toFloat() * 0.28f *
                        exp(-3.5f * t).toFloat()
                }
            }
            Sound.SCREAM -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val frequency = 520.0 + 900.0 * (t / length) +
                        sin(2.0 * PI * 13.0 * t) * 80.0
                    val tone = sin(2.0 * PI * frequency * t).toFloat()
                    mix[i] += (tone * 0.45f + (random.nextFloat() * 2f - 1f) * 0.18f) *
                        exp(-2.2f * t).toFloat()
                }
            }
            Sound.REVIVE -> {
                for (i in 0 until samples) {
                    val t = i.toFloat() / SAMPLE_RATE
                    val frequency = if (t < 0.18f) 440.0 else 700.0
                    mix[i] += sin(2.0 * PI * frequency * t).toFloat() * 0.22f
                }
            }
        }
        return toPcm(mix)
    }

    private fun toPcm(mix: FloatArray): ShortArray {
        var peak = 0f
        for (value in mix) {
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        val scale = if (peak > 0.94f) 0.94f / peak else 1f
        return ShortArray(mix.size) { index ->
            (mix[index] * scale * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** A mono 16-bit WAV. */
    fun wav(sound: Sound, seed: Long = 1L): ByteArray = wavFromPcm(pcm(sound, seed))

    /** Wraps already-rendered mono PCM in a WAV container (used for the generated music loops). */
    fun wavFromPcm(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(44 + dataBytes)
        fun putAscii(offset: Int, text: String) {
            for (i in text.indices) out[offset + i] = text[i].code.toByte()
        }
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = (value ushr 8 and 0xFF).toByte()
            out[offset + 2] = (value ushr 16 and 0xFF).toByte()
            out[offset + 3] = (value ushr 24 and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = (value ushr 8 and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + dataBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataBytes)
        for (i in pcm.indices) putShort(44 + i * 2, pcm[i].toInt())
        return out
    }

    /** Writes the default pack, or returns the files that already exist. */
    fun writeDefaults(directory: File, seed: Long = 1L): Map<Sound, File> {
        directory.mkdirs()
        val files = LinkedHashMap<Sound, File>()
        for (sound in Sound.entries) {
            val file = File(directory, sound.file)
            if (!file.isFile || file.length() == 0L) {
                runCatching { file.writeBytes(wav(sound, seed)) }
            }
            files[sound] = file
        }
        return files
    }
}
