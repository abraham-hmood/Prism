package com.prism.launcher.minigames

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streams [MinigameMusic] into the phone's audio output.
 *
 * One bar in flight at a time, synthesised on a background thread. The intensity is read at each
 * bar boundary, so the soundtrack speeds up as a run heats up without a mid-bar tempo jump -- a
 * tempo change in the middle of a beat is the one thing that sounds broken even when it is
 * intentional.
 *
 * The track is built for GAME usage so a phone in silent mode still follows the media volume rather
 * than the ringer, and so focus is handled by the system like any other game.
 */
class MinigameAudio {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var intensity = 0.3f
    @Volatile private var gameId = "arcade"
    @Volatile private var seed = 7L

    /**
     * Bumped on every start and stop.
     *
     * A pump that was still opening its track when a new game was selected would otherwise finish
     * opening it AFTER the stop, see the new [running] flag, and play alongside the replacement --
     * two bars of different songs at once. A generation the pump must still match prevents that.
     */
    @Volatile private var generation = 0

    val isRunning: Boolean get() = running

    /** The key the music is currently in, so switching cabinets changes key. */
    fun currentGame(): String = gameId

    fun start(id: String, seed: Long = 7L) {
        if (running && gameId == id) return
        stop()
        gameId = id
        this.seed = seed
        running = true
        val mine = ++generation
        thread = Thread({ pump(mine) }, "prism-minigame-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun updateIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun stop() {
        if (!running && track == null) return
        running = false
        generation++
        val old = thread
        thread = null
        // Closing the track is what unblocks a WRITE_BLOCKING call. Release here and let the pump
        // thread observe the failure and exit; joining would block the UI for up to a bar.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        old?.interrupt()
    }

    private fun pump(mine: Int) {
        if (mine != generation) return
        val minimum = AudioTrack.getMinBufferSize(
            MinigameMusic.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MinigameMusic.SAMPLE_RATE / 2)

        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MinigameMusic.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minimum)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            if (mine == generation) running = false
            return
        }
        if (mine != generation) {
            runCatching { audio.release() }
            return
        }
        track = audio
        if (audio.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { audio.release() }
            if (mine == generation) running = false
            return
        }
        runCatching { audio.play() }

        var bar = 0
        while (running && mine == generation) {
            val pcm = MinigameMusic.renderBar(gameId, intensity, bar++, seed)
            val written = runCatching {
                audio.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            }.getOrElse { -1 }
            if (written <= 0) break
        }

        runCatching { audio.stop() }
        runCatching { audio.release() }
        if (track === audio) track = null
    }
}
