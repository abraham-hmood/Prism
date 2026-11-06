package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.podcasts.PcmLine
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * One Java Sound line for a whole podcast episode.
 *
 * Kept open from the first sample to the last so pause, resume and seek happen on the same line;
 * the per-clip [DesktopAudioSink] would reopen a line every tenth of a second and click each time.
 */
class DesktopPodcastLine private constructor(private val line: SourceDataLine) : PcmLine {

    private val buffer = ByteArray(64 * 1024)

    override fun write(samples: ShortArray, count: Int): Int {
        val bytes = if (count * 2 <= buffer.size) buffer else ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        line.write(bytes, 0, count * 2)
        return count
    }

    override fun pause() { runCatching { line.stop() } }
    override fun resume() { runCatching { line.start() } }
    override fun flush() { runCatching { line.flush() } }
    override fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    companion object {
        fun open(rate: Int): PcmLine? = runCatching {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
            line.open(format, rate)
            line.start()
            DesktopPodcastLine(line) as PcmLine
        }.onFailure { PrismPlatform.log.warn("Podcasts", "No audio line: ${it.message}") }.getOrNull()
    }
}
