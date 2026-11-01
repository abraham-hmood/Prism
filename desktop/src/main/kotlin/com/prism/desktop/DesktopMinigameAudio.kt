package com.prism.desktop

import com.prism.launcher.minigames.MinigameMusic
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * The desktop end of [MinigameMusic].
 *
 * The same bars the phone synthesises are written to a `SourceDataLine`. The thread blocks in
 * `write`, which is what paces the music: nothing schedules a bar, the line asks for the next one
 * when it has room. Stopping closes the line from the caller's thread, which unblocks the write and
 * lets the pump exit.
 *
 * Intensity is read at each bar boundary, exactly like the Android player, so the two platforms
 * cannot drift into different tempos for the same run.
 */
object DesktopMinigameAudio {

    private val format = AudioFormat(
        MinigameMusic.SAMPLE_RATE.toFloat(),
        16,
        1,
        true,
        false,
    )

    @Volatile private var running = false
    @Volatile private var intensity = 0.3f
    @Volatile private var gameId = "arcade"
    @Volatile private var generation = 0

    @Volatile private var line: SourceDataLine? = null
    private var thread: Thread? = null

    fun start(id: String) {
        if (running && gameId == id) return
        stop()
        gameId = id
        running = true
        val mine = ++generation
        thread = Thread({ pump(mine) }, "prism-minigame-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun update(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        generation++
        val old = line
        line = null
        runCatching { old?.stop() }
        runCatching { old?.flush() }
        runCatching { old?.close() }
        thread?.interrupt()
        thread = null
    }

    private fun pump(mine: Int) {
        if (mine != generation) return
        val output = runCatching {
            AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format))
                as SourceDataLine
        }.getOrElse { if (mine == generation) running = false; return }
        if (mine != generation) return
        val opened = runCatching {
            output.open(format, MinigameMusic.SAMPLE_RATE / 2)
            output.start()
            output
        }.getOrElse {
            runCatching { output.close() }
            if (mine == generation) running = false
            return
        }
        if (mine != generation) {
            runCatching { opened.close() }
            return
        }
        line = opened

        var bar = 0
        while (running && mine == generation) {
            val pcm = MinigameMusic.renderBar(gameId, intensity, bar++)
            val bytes = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            val written = runCatching { opened.write(bytes, 0, bytes.size) }.getOrElse { -1 }
            if (written <= 0) break
        }

        runCatching { opened.drain() }
        runCatching { opened.stop() }
        runCatching { opened.close() }
        if (line === opened) line = null
    }
}
