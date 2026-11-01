package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.minigames.EchoScreech
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The desktop half of Echo Chamber's ears and voice.
 *
 * ## Why the microphone is a live level, not a recording
 *
 * The game asks one question of the input -- how loud is the room right now? -- so this opens a
 * capture line and publishes an RMS a few times a second. Recording a clip and analysing it would
 * add latency to the only control the game has.
 *
 * ## The ping
 *
 * A short falling chirp with a noise tail, written to a fresh `SourceDataLine` on a daemon thread.
 * It is deliberately not a beep: the colour of the sound is the game's only feedback about the room
 * beyond the marks on the walls.
 */
class EchoDesktopAudio {

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        22_050f,
        16,
        1,
        2,
        22_050f,
        false,
    )

    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    var unavailable: Boolean = false
        private set

    @Volatile
    private var running = false
    private var microphone: TargetDataLine? = null
    private var micThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        micThread = Thread({
            val captureFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                16_000f,
                16,
                1,
                2,
                16_000f,
                false,
            )
            val info = DataLine.Info(TargetDataLine::class.java, captureFormat)
            try {
                if (!AudioSystem.isLineSupported(info)) {
                    unavailable = true
                    return@Thread
                }
                val line = (AudioSystem.getLine(info) as TargetDataLine).apply {
                    open(captureFormat)
                    start()
                }
                microphone = line
                val buffer = ByteArray(1600)
                while (running) {
                    val read = line.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    var samples = 0
                    var i = 0
                    while (i + 1 < read) {
                        val value = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort() / 32768.0
                        sum += value * value
                        samples++
                        i += 2
                    }
                    level = if (samples == 0) 0f else kotlin.math.sqrt(sum / samples).toFloat()
                }
            } catch (failure: Throwable) {
                unavailable = true
                PrismPlatform.log.warn("PrismEcho", "Desktop microphone unavailable: ${failure.message}")
            } finally {
                runCatching { microphone?.stop() }
                runCatching { microphone?.close() }
                microphone = null
            }
        }, "echo-desktop-mic").apply { isDaemon = true }
        micThread?.start()
    }

    fun ping(loudness: Double) {
        Thread({
            val rate = 22_050
            val length = (rate * 0.42).toInt()
            val samples = ByteArray(length * 2)
            var seed = 0x5EED1234
            for (i in 0 until length) {
                val t = i.toDouble() / rate
                val envelope = exp(-t * 7.5) * (1 - t / 0.42).coerceAtLeast(0.0)
                seed = seed * 1664525 + 1013904223
                val noise = ((seed ushr 16) and 0x7FFF) / 32768.0 - 0.5
                val freq = 880.0 - t * 1450.0
                val tone = sin(2 * Math.PI * freq * t)
                val echo = sin(2 * Math.PI * (freq * 0.5) * (t - 0.10).coerceAtLeast(0.0)) *
                    exp(-max(0.0, t - 0.10) * 6.0) * if (t > 0.10) 0.35 else 0.0
                val value = ((tone * 0.62 + noise * 0.10 + echo) * envelope * loudness.coerceIn(0.2, 1.0) * 11000)
                    .toInt().coerceIn(-32768, 32767)
                samples[i * 2] = (value and 0xFF).toByte()
                samples[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            try {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) return@Thread
                val line = (AudioSystem.getLine(info) as SourceDataLine).apply {
                    open(format)
                    start()
                }
                line.write(samples, 0, samples.size)
                line.drain()
                line.stop()
                line.close()
            } catch (failure: Throwable) {
                PrismPlatform.log.warn("PrismEcho", "Could not play a ping: ${failure.message}")
            }
        }, "echo-desktop-ping").apply { isDaemon = true }.start()
    }

    /** The jumpscare: the same layered scream the phone plays, generated once in `:core`. */
    fun scare() {
        Thread({
            try {
                val voice = EchoScreech.samples(EchoScreech.SAMPLE_RATE)
                val bytes = ByteArray(voice.size * 2)
                for (i in voice.indices) {
                    val value = voice[i].toInt()
                    bytes[i * 2] = (value and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) return@Thread
                val line = (AudioSystem.getLine(info) as SourceDataLine).apply {
                    open(format)
                    start()
                }
                line.write(bytes, 0, bytes.size)
                line.drain()
                line.stop()
                line.close()
            } catch (failure: Throwable) {
                PrismPlatform.log.warn("PrismEcho", "Could not play the scare: ${failure.message}")
            }
        }, "echo-desktop-scare").apply { isDaemon = true }.start()
    }

    fun close() {
        running = false
        runCatching { microphone?.stop() }
        micThread?.interrupt()
        micThread = null
        level = 0f
    }
}
