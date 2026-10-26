package com.prism.launcher.mouse

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The tiny sounds and touches a mouse makes.
 *
 * ## A generated tick, not an asset
 *
 * A wheel tick is forty milliseconds of noise with a fast decay. Shipping a WAV for it would work,
 * but it would also be the only audio file in a build whose other sounds are synthesised, and a
 * generated one can be pitched differently for up and down without a second file.
 *
 * ## The vibration is deliberately barely there
 *
 * A click should confirm the press, not announce it. Ten milliseconds at roughly a sixth of the
 * amplitude is the difference between a physical button and a glass rectangle; anything stronger
 * turns a control surface into a buzzer.
 */
object MouseAudio {

    private const val RATE = 22_050
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    fun tick(context: Context, direction: Int) {
        val soundPool = pool ?: start(context)
        if (!loaded) return
        runCatching {
            soundPool.play(
                soundId,
                0.42f,
                0.42f,
                1,
                0,
                if (direction > 0) 1.03f else 0.94f,
            )
        }
    }

    private fun start(context: Context): SoundPool {
        val created = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool = created
        runCatching {
            val file = File(context.cacheDir, "mouse-wheel-tick.wav")
            if (!file.isFile) file.writeBytes(wav(pcm()))
            created.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) loaded = true
            }
            soundId = created.load(file.absolutePath, 1)
        }
        return created
    }

    /** A click followed by a tiny scrape: the sound of a detent, not a beep. */
    private fun pcm(): ByteArray {
        val samples = (RATE * 0.035).toInt()
        val pcm = ShortArray(samples)
        var phase = 0.0
        var noise = 1.0
        for (index in 0 until samples) {
            val t = index.toDouble() / samples
            phase += 2 * PI * (1_650 - 700 * t) / RATE
            // Deterministic pseudo-noise: an LCG keeps the waveform identical on every run.
            noise = (noise * 1103515245.0 + 12345.0) % 2147483647.0
            val grit = (noise / 2147483647.0) * 2.0 - 1.0
            val envelope = exp(-26.0 * t)
            val value = (sin(phase) * 7_000 + grit * 1_800) * envelope
            pcm[index] = value.toInt().coerceIn(-32_000, 32_000).toShort()
        }
        return toBytes(pcm)
    }

    private fun toBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { index, value ->
            out[index * 2] = (value.toInt() and 0xFF).toByte()
            out[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

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
        short(1)
        short(1)
        int(RATE)
        int(RATE * 2)
        short(2)
        short(16)
        out.write("data".toByteArray())
        int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** A soft detent under the finger. */
    fun tap(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(10L, 42))
        }
    }
}
