package com.prism.launcher.speech

/**
 * Something that can say a sentence out loud.
 *
 * Three things implement this and they have almost nothing in common underneath: Kokoro is a neural
 * vocoder driven by phonemes, an imported model is whatever the user brought, and the system engine
 * is Android's own. What callers need from all three is identical, so the call sites -- call mode,
 * a spoken reply, a voice preview -- are written once against this and never against an engine.
 */
interface TtsEngine {

    /** Short name for the UI, e.g. "Kokoro-82M" or "System voice". */
    val displayName: String

    /** False when the engine exists but cannot speak yet — not downloaded, not supported here. */
    fun isReady(): Boolean

    /**
     * Says [text], calling [onDone] when the audio finishes or fails.
     *
     * [onDone] is guaranteed exactly once, on the main thread, because call mode drives a state
     * machine off it: a missed callback leaves the call stuck listening to nothing, which is worse
     * than a sentence that does not play.
     *
     * @param localeTag BCP-47 for the language [text] is actually in, when the caller knows it.
     *
     * Added for the language lessons, where the two are genuinely different things: a tutor with an
     * American voice has to read a Mandarin word, and the system engine was previously deriving its
     * locale from the VOICE — so it was told the Mandarin was English and said nothing at all.
     * Engines that pick their language from the voice (Kokoro) ignore this; the system engine, which
     * cannot, honours it.
     */
    fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        localeTag: String? = null,
        onDone: (error: String?) -> Unit,
    )

    /** Stops immediately, discarding anything queued. Safe to call when not speaking. */
    fun stop()

    /** Frees whatever the engine holds. The instance is not reusable afterwards. */
    fun release()
}
