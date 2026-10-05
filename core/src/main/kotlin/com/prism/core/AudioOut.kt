package com.prism.core

/**
 * Sound coming OUT of Prism. PHASE 100.
 *
 * ## Why this is a platform slot and not a library call
 *
 * Speech synthesis ports almost perfectly -- Kokoro is ONNX, the phonemiser is a dictionary and the
 * tokeniser is a table, none of which know what a platform is. What does not port is the last ten
 * centimetres: `android.media.AudioTrack` on the phone, `javax.sound.sampled.SourceDataLine` on a PC.
 * So that is the seam, and it is deliberately the SMALLEST one that works -- a buffer of samples and
 * a rate, nothing about streams, mixers, sessions or attributes.
 *
 * ## [play] BLOCKS, and that is the important part of the contract
 *
 * Not "starts playing". Call mode and the language lessons both start LISTENING when speech
 * finishes, and a sink that returned as soon as the samples were queued would have the microphone
 * open while the speaker was still talking -- so Prism would transcribe itself and answer its own
 * question. Both implementations therefore follow the playback position to the end rather than the
 * write position, because writing the last sample and playing it are up to a buffer apart.
 *
 * That is also why [cancelled] is a function rather than a flag checked once: a long sentence has to
 * be interruptible mid-playback, and the only place that can be noticed is inside the loop.
 */
interface AudioSink {

    /**
     * Plays mono float samples in [-1, 1] at [sampleRate], returning when they have been HEARD.
     *
     * @param cancelled polled during playback; true abandons the rest of the buffer at once.
     * @return null on success, or a reason the audio did not play.
     */
    fun play(samples: FloatArray, sampleRate: Int, cancelled: () -> Boolean = { false }): String?

    /** Stops whatever is playing now and discards it. Safe when nothing is. */
    fun stop()

    /** False when this machine has no usable output device, so callers can say so rather than fail. */
    fun isAvailable(): Boolean = true

    /** Why it cannot play, in one sentence, when [isAvailable] is false. */
    fun unavailableReason(): String = "No audio output device is available."
}

/**
 * A sink for a platform that has not installed one.
 *
 * REFUSES RATHER THAN PRETENDING. A no-op that returned success would make a silent machine
 * indistinguishable from a working one, and the failure would surface as "the voice does not work"
 * with nothing to go on. Returning a reason means the page can print it.
 */
object NoAudioSink : AudioSink {
    override fun play(samples: FloatArray, sampleRate: Int, cancelled: () -> Boolean): String? =
        "This build has no audio output installed."

    override fun stop() = Unit
    override fun isAvailable(): Boolean = false
    override fun unavailableReason(): String = "This build has no audio output installed."
}

/**
 * Somewhere to run a callback that the UI is allowed to touch.
 *
 * Every engine in the speech package guarantees its completion callback fires exactly once on the
 * platform's UI thread, because call mode drives a state machine off it -- a callback delivered on a
 * worker would mutate Compose state or Android views from the wrong thread, and the failure is
 * intermittent rather than immediate, which is the worst kind.
 */
interface MainThread {
    fun post(block: () -> Unit)
}

/**
 * Runs it where it stands.
 *
 * Correct for tests and for the console harness, both of which have no UI thread to speak of. It is
 * deliberately NOT correct for a GUI, so each GUI platform installs its own.
 */
object InlineMainThread : MainThread {
    override fun post(block: () -> Unit) {
        block()
    }
}
