package com.prism.launcher.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.prism.core.AudioSink
import com.prism.core.MainThread

/**
 * Android's half of the speech seam. PHASE 100.
 *
 * ## This is the sixty lines that could not move
 *
 * Everything else about Kokoro is in :core now -- the phonemiser, the tokeniser, the style tensors,
 * the ONNX call. This file is the entirety of what was Android-specific, and it is kept verbatim
 * rather than rewritten, because every awkward detail in it was learned from a device that produced
 * silence.
 *
 * ## A SMALL buffer, on purpose
 *
 * Sizing the track to hold the whole clip is the obvious thing and it silently breaks playback:
 * `WRITE_BLOCKING` then never blocks, because everything fits, so the write loop finishes in
 * milliseconds and the track is stopped and released while the audio is still sitting in the buffer
 * waiting to be played. The device reported "58200 frames delivered" and the speaker stayed silent. A
 * quarter-second buffer makes the writes pace against real playback.
 *
 * ## And then WAIT for it to come out of the speaker
 *
 * Writing the last sample is not the same event as playing it: there is still up to a buffer's worth
 * queued. `playbackHeadPosition` is the only honest measure of what has been HEARD, so this follows
 * it to the end rather than assuming. That is the behaviour [AudioSink] now requires of every
 * platform, and it is required because call mode starts listening when speech finishes.
 */
class AndroidAudioSink : AudioSink {

    @Volatile
    private var track: AudioTrack? = null

    override fun play(samples: FloatArray, sampleRate: Int, cancelled: () -> Boolean): String? {
        stop()
        if (cancelled()) return null

        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate / 4 * 4)

        val instance = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minimum)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse { return it.message ?: "The audio track could not be opened" }

        track = instance
        instance.play()

        // Blocking writes, so completion means the audio has actually been consumed rather than
        // queued -- call mode starts listening off that, and starting while the speaker is still
        // talking makes it hear itself.
        var written = 0
        while (written < samples.size && !cancelled()) {
            val n = instance.write(samples, written, samples.size - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            written += n
        }

        val deadline = System.currentTimeMillis() +
            (samples.size * 1000L / sampleRate) + PLAYBACK_GRACE_MS
        while (!cancelled() &&
            instance.playbackHeadPosition < written &&
            System.currentTimeMillis() < deadline
        ) {
            val remaining = written - instance.playbackHeadPosition
            runCatching { Thread.sleep((remaining * 1000L / sampleRate).coerceIn(10, 150)) }
        }

        runCatching {
            if (!cancelled()) {
                instance.stop()
                // The tail is still audible for a moment after stop(), and cutting straight to
                // listening clips the last word.
                Thread.sleep(TAIL_MS)
            }
        }
        stop()
        return null
    }

    override fun stop() {
        val instance = track ?: return
        track = null
        runCatching { instance.pause() }
        runCatching { instance.flush() }
        runCatching { instance.release() }
    }

    private companion object {
        const val TAIL_MS = 120L

        /** Slack on the playback wait, so a stalled mixer cannot hang the call loop forever. */
        const val PLAYBACK_GRACE_MS = 1_500L
    }
}

/** The looper every Prism view already runs on. */
object AndroidMainThread : MainThread {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(block: () -> Unit) {
        handler.post(block)
    }
}
