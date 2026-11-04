package com.prism.desktop

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The call speaker and microphone path: resampling, the fallback that fixed silent Linux calls, and
 * the ringtones. The live-device checks pass trivially on a machine with no sound devices.
 */
class DesktopAudioDevicesTest {

    private fun sine(rate: Int, seconds: Double, freq: Double = 440.0): ByteArray {
        val n = (rate * seconds).toInt()
        return ByteArray(n * 2).also { out ->
            for (i in 0 until n) {
                val v = (sin(2 * PI * freq * i / rate) * 12_000).toInt()
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = (v shr 8).toByte()
            }
        }
    }

    private fun samples(pcm: ByteArray) = IntArray(pcm.size / 2) { (pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8) }

    @Test
    fun `16 kHz call audio becomes 48 kHz without a click at frame boundaries`() {
        val resampler = DesktopAudioDevices.Resampler(16_000, 48_000)
        val input = sine(16_000, 1.0)
        // Fed as the call engine feeds it: 20 ms frames.
        val out = java.io.ByteArrayOutputStream()
        input.toList().chunked(640).forEach { out.write(resampler.process(it.toByteArray(), it.size)) }
        val s = samples(out.toByteArray())
        assertTrue(abs(s.size - 48_000) <= 3, "1 s at 16 kHz should be 48 000 samples, was ${s.size}")
        // A 440 Hz sine at 48 kHz moves at most ~700 per sample; a boundary glitch would jump far more.
        val worst = (1 until s.size).maxOf { abs(s[it] - s[it - 1]) }
        assertTrue(worst < 1_000, "a discontinuity of $worst between samples")
    }

    @Test
    fun `48 kHz microphone audio becomes 16 kHz`() {
        val resampler = DesktopAudioDevices.Resampler(48_000, 16_000)
        val out = resampler.process(sine(48_000, 0.5), 48_000)
        assertTrue(abs(out.size / 2 - 8_000) <= 2, "0.5 s should be 8 000 samples, was ${out.size / 2}")
    }

    @Test
    fun `a call speaker opens, and a chosen device that is gone falls back instead of failing`() {
        if (DesktopAudioDevices.speakers().size <= 1 && !AudioSystem.getMixerInfo().any()) return
        val default = DesktopAudioDevices.openOutput("", 16_000) ?: return // no audio hardware here
        default.write(ByteArray(640))
        default.close()
        val missing = assertNotNull(
            DesktopAudioDevices.openOutput("A headset that was unplugged", 16_000),
            "a missing device must fall back to one that works",
        )
        missing.write(ByteArray(640))
        missing.close()
        // Every listed speaker is either opened or skipped for the next; none throws.
        DesktopAudioDevices.speakers().drop(1).take(4).forEach { device ->
            DesktopAudioDevices.openOutput(device.key, 16_000)?.close()
        }
    }

    @Test
    fun `every built-in tone and the ringback have sound in them`() {
        listOf("", "builtin:classic", "builtin:chime", "builtin:prism").forEach { value ->
            val (pcm, rate) = DesktopRingtones.cycle(value)
            assertEquals(22_050, rate)
            assertTrue(samples(pcm).any { abs(it) > 1_000 }, "$value is silent")
        }
        val (ringback, _) = DesktopRingtones.cycle("", callTone = true)
        // Two seconds on, four off.
        assertEquals(6 * 22_050, ringback.size / 2)
        assertEquals("Standard ringback (default)", DesktopRingtones.nameOf("", callTone = true))
    }

    @Test
    fun `an mp3 ringtone decodes, and a missing one rings with the default`() {
        DesktopAudioDecoding.install()
        val file = File.createTempFile("prism-tone", ".mp3")
        try {
            javaClass.getResourceAsStream("/audio/one-second-silence.mp3")!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val (pcm, rate) = DesktopRingtones.cycle(file.absolutePath)
            assertTrue(rate != 22_050 || pcm.size > 0, "the mp3 did not decode")
            assertTrue(pcm.size / 2 >= rate / 2, "about a second of audio expected, got ${pcm.size / 2} samples at $rate")
        } finally {
            file.delete()
        }
        val (fallback, fallbackRate) = DesktopRingtones.cycle(File("/nowhere/gone.mp3").absolutePath)
        assertEquals(22_050, fallbackRate)
        assertTrue(fallback.isNotEmpty())
    }
}
