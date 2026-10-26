package com.prism.launcher.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.calls.MeshCalls.FRAME_BYTES
import com.prism.launcher.calls.MeshCalls.SAMPLE_RATE

/**
 * Full-duplex audio for a mesh call on Android.
 *
 * ## VOICE_COMMUNICATION, and the mode that must match it
 *
 * The source is `VOICE_COMMUNICATION`, not `MIC`: it enables the platform's echo canceller and
 * noise suppressor, which is the difference between a call and a speakerphone feeding itself. It
 * only does that when the audio mode is `MODE_IN_COMMUNICATION`, which [CallActivity] sets for the
 * duration and restores afterwards. Leaving the mode set would route the next notification through
 * the earpiece.
 *
 * ## Why AudioTrack rather than the TTS sink
 *
 * The speech path plays whole utterances and then drains. A call is continuous and must not block
 * the network thread that feeds it, so this writes into a streaming track sized at a few frames.
 * A stalled write means the network is faster than the speaker, which is normal and must not stall
 * packet handling.
 */
class CallAudio(private val context: Context) : MeshCalls.Audio {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var capture: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var capturing = false

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun startCapture(onFrame: (ByteArray) -> Unit): Boolean {
        if (!hasMicrophonePermission()) {
            PrismLogger.logWarning(TAG, "Cannot start a mesh call without the microphone permission")
            return false
        }
        if (capturing) return true

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            PrismLogger.logWarning(TAG, "No AudioRecord buffer for $SAMPLE_RATE Hz mono")
            return false
        }

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, FRAME_BYTES * 8),
            )
        }.getOrElse {
            PrismLogger.logError(TAG, "Could not open the microphone", it)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            PrismLogger.logWarning(TAG, "The microphone did not initialise")
            return false
        }

        record = recorder
        capturing = true
        recorder.startRecording()

        capture = Thread({
            val frame = ByteArray(FRAME_BYTES)
            while (capturing) {
                val read = runCatching { recorder.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read <= 0) {
                    if (!capturing) break
                    continue
                }
                if (read == frame.size) onFrame(frame.copyOf()) else onFrame(frame.copyOf(read))
            }
        }, "prism-call-capture").apply { isDaemon = true; start() }
        return true
    }

    override fun stopCapture() {
        capturing = false
        // stop() UNBLOCKS a pending read; without it the capture thread would sit in read() until
        // the next frame arrived, and a call that ended in a silent room would keep the microphone
        // open for as long as the room stayed silent.
        runCatching { record?.stop() }
        runCatching { capture?.join(500) }
        runCatching { record?.release() }
        record = null
        capture = null
    }

    override fun startPlayback(): Boolean {
        if (track != null) return true
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return false

        val output = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, FRAME_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            PrismLogger.logError(TAG, "Could not open the call output", it)
            return false
        }

        output.play()
        track = output
        return true
    }

    override fun write(frame: ByteArray) {
        val output = track ?: return
        if (frame.isEmpty()) return
        runCatching { output.write(frame, 0, frame.size) }
    }

    override fun stopPlayback() {
        val output = track ?: return
        track = null
        runCatching { output.stop() }
        runCatching { output.flush() }
        runCatching { output.release() }
    }

    /** Sets the platform audio mode for the duration of a call, and restores it after. */
    fun enterCallMode() {
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = false
        }
    }

    fun leaveCallMode() {
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.mode = AudioManager.MODE_NORMAL
        }
    }

    private companion object {
        const val TAG = "PrismCallAudio"
    }
}
