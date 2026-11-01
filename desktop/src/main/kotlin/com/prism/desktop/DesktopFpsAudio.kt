package com.prism.desktop

import com.prism.launcher.minigames.FpsEvent
import com.prism.launcher.minigames.FpsSound
import com.prism.launcher.minigames.MinigameSfx
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.hypot

/**
 * DOOM and Zombies' effects on the desktop.
 *
 * The phone uses `SoundPool`, which is Android-only. A PC equivalent is not a `Clip` per shot:
 * a shotgun blast every hundred milliseconds would open a new line per blast, and Java Sound does
 * not recycle them quickly enough. This is a tiny mixer instead — the generated [MinigameSfx] PCM
 * lives in memory, active voices are mixed into one 22 kHz mono line, and distance controls gain
 * exactly the way [com.prism.launcher.minigames.FpsAudio] does it on Android.
 *
 * The mixer thread only opens a device when the first sound is played. A desktop with no audio
 * device therefore plays a silent game instead of failing to start one.
 */
object DesktopFpsAudio {

    private const val SAMPLE_RATE = MinigameSfx.SAMPLE_RATE
    private const val CHUNK = 441
    private const val MAX_VOICES = 18

    private class Voice(
        val samples: ShortArray,
        var position: Double,
        val step: Double,
        val volume: Float,
    )

    private val cache = HashMap<MinigameSfx.Sound, ShortArray>()
    private val pending = ConcurrentLinkedQueue<Voice>()
    private val active = ArrayList<Voice>()

    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun play(event: FpsEvent, listenerX: Double, listenerY: Double) {
        val sound = when (event.sound) {
            FpsSound.SHOT_PISTOL -> MinigameSfx.Sound.SHOT
            FpsSound.SHOT_SHOTGUN -> MinigameSfx.Sound.SHOTGUN
            FpsSound.SHOT_SMG -> MinigameSfx.Sound.SHOT
            FpsSound.SHOT_RIFLE -> MinigameSfx.Sound.SHOTGUN
            FpsSound.HIT -> MinigameSfx.Sound.STEP
            FpsSound.ZOMBIE, FpsSound.GROAN -> MinigameSfx.Sound.ZOMBIE
            FpsSound.STEP -> MinigameSfx.Sound.STEP
            FpsSound.RELOAD -> MinigameSfx.Sound.RELOAD
            FpsSound.HURT -> MinigameSfx.Sound.HURT
            FpsSound.WAVE -> MinigameSfx.Sound.WAVE
            FpsSound.REVIVE, FpsSound.EXIT -> MinigameSfx.Sound.REVIVE
            FpsSound.EMPTY -> MinigameSfx.Sound.EMPTY
            FpsSound.PICKUP, FpsSound.BUY -> MinigameSfx.Sound.PICKUP
            FpsSound.SCREAM -> MinigameSfx.Sound.SCREAM
            FpsSound.SWITCH -> MinigameSfx.Sound.SWITCH
        }
        val pcm = cache.getOrPut(sound) { MinigameSfx.pcm(sound) }
        val distance = hypot(event.x - listenerX, event.y - listenerY)
        val falloff = (1.0 / (1.0 + distance * 0.16)).coerceIn(0.08, 1.0).toFloat()
        val volume = (event.volume * falloff).coerceIn(0f, 1f)
        if (volume <= 0.02f) return
        // A running step is a step at a higher pitch. It is the same generated transient, so a
        // custom step.wav still applies to both; only the playback rate changes.
        val step = if (event.sound == FpsSound.STEP && event.running) 1.34 else 1.0
        pending.add(Voice(pcm, 0.0, step, volume))
        ensureRunning()
    }

    @Synchronized
    private fun ensureRunning() {
        if (running) return
        running = true
        thread = Thread({ pump() }, "prism-fps-audio").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        pending.clear()
        active.clear()
        thread?.interrupt()
        thread = null
    }

    private fun pump() {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val output = runCatching {
            AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        }.getOrElse {
            running = false
            return
        }
        val opened = runCatching {
            output.open(format, SAMPLE_RATE / 4)
            output.start()
            output
        }.getOrElse {
            runCatching { output.close() }
            running = false
            return
        }

        val mix = FloatArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        while (running) {
            while (true) {
                val voice = pending.poll() ?: break
                if (active.size >= MAX_VOICES) active.removeAt(0)
                active.add(voice)
            }
            java.util.Arrays.fill(mix, 0f)
            var index = 0
            while (index < active.size) {
                val voice = active[index]
                for (frame in 0 until CHUNK) {
                    val at = voice.position + frame * voice.step
                    val sample = at.toInt()
                    if (sample >= voice.samples.size) break
                    mix[frame] += (voice.samples[sample] / 32768f) * voice.volume
                }
                voice.position += CHUNK * voice.step
                if (voice.position >= voice.samples.size) active.removeAt(index) else index++
            }
            for (i in 0 until CHUNK) {
                val value = (mix[i].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[i * 2] = (value and 0xFF).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            if (runCatching { opened.write(bytes, 0, bytes.size) }.getOrElse { -1 } <= 0) break
        }
        runCatching { opened.stop() }
        runCatching { opened.flush() }
        runCatching { opened.close() }
    }
}
