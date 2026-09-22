package com.prism.launcher.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.prism.launcher.PrismLogger
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Android's own speech engine.
 *
 * ## What this is for
 *
 * It is the floor, not the plan. Kokoro is what Prism speaks with, and it is a 86 MB download that
 * has to happen before it can say anything at all. Without a floor, the first call a user places
 * before that download finishes is silent -- and silence is indistinguishable from a bug.
 *
 * So this engine covers exactly two cases: Kokoro is not installed yet, or the device cannot run it.
 * It is never chosen in preference to Kokoro, and [PrismSpeaker] says which one is actually talking
 * so the difference is visible rather than mysterious.
 *
 * ## Voices
 *
 * The `voiceId` a caller passes is a Kokoro voice id and means nothing here, so it is used only for
 * the one thing that does carry over: the language it implies. A Kokoro voice from the Japanese set
 * speaking through the system engine at least speaks Japanese.
 */
class SystemTtsEngine(context: Context) : TtsEngine {

    override val displayName: String get() = "System voice"

    private val main = Handler(Looper.getMainLooper())

    private val utterances = AtomicLong(0)

    /** Completion for the utterance in flight, cleared the moment it is delivered. */
    @Volatile
    private var pending: ((String?) -> Unit)? = null

    @Volatile
    private var ready = false

    private val appContext = context.applicationContext

    private var engine: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            attachListener()
        } else {
            // NOT the end of it. A device can have a perfectly good engine installed and no DEFAULT
            // selected -- `settings get secure tts_default_synth` returns null -- and the no-argument
            // constructor then fails even though com.google.android.tts is sitting right there.
            // Observed on the test device, where it made speech look unavailable when it was not.
            retryWithInstalledEngine()
        }
    }

    /**
     * Retries against whichever engine is actually installed, rather than the unset default.
     *
     * Engines advertise themselves with an intent filter, so the package manager can name one
     * without the TextToSpeech instance that would have to exist to ask it.
     */
    private fun retryWithInstalledEngine() {
        val candidate = runCatching {
            appContext.packageManager
                .queryIntentServices(android.content.Intent("android.intent.action.TTS_SERVICE"), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
                .firstOrNull()
        }.getOrNull()

        if (candidate == null) {
            PrismLogger.logWarning(TAG, "No speech engine is installed")
            return
        }

        PrismLogger.logInfo(TAG, "No default speech engine; trying $candidate")
        runCatching { engine.shutdown() }
        engine = TextToSpeech(appContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) attachListener()
            else PrismLogger.logWarning(TAG, "$candidate could not be initialised either")
        }, candidate)
    }

    private fun attachListener() {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(null)

            @Deprecated("Superseded by the two-argument form, which is not called on older releases")
            override fun onError(utteranceId: String?) = finish("The system engine could not speak")

            override fun onError(utteranceId: String?, errorCode: Int) =
                finish("The system engine could not speak (code $errorCode)")
        })
    }

    /**
     * Delivers the completion exactly once.
     *
     * Both error callbacks can arrive for one utterance on some devices, and a caller that advances
     * a call's state machine twice ends up listening and speaking at the same time.
     */
    private fun finish(error: String?) {
        val callback = pending ?: return
        pending = null
        main.post { callback(error) }
    }

    override fun isReady(): Boolean = ready

    override fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        localeTag: String?,
        onDone: (String?) -> Unit,
    ) {
        if (!ready) {
            main.post { onDone("The system speech engine is not available on this device") }
            return
        }

        // An explicit locale wins. It is the only one that knows what language the TEXT is in;
        // the voice only knows what the speaker normally sounds like.
        if (localeTag != null) {
            runCatching { engine.language = Locale.forLanguageTag(localeTag) }
        } else {
            voiceId?.let { applyLanguageOf(it) }
        }
        engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))

        pending = onDone
        val id = utterances.incrementAndGet().toString()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) finish("The system engine refused the request")
    }

    /** Maps a Kokoro voice id onto the only thing the system engine can honour: its language. */
    private fun applyLanguageOf(voiceId: String) {
        val language = com.prism.launcher.speech.KokoroVoices.find(voiceId)?.language ?: return
        val locale = when (language.pack) {
            "en-us" -> Locale.US
            "en-gb" -> Locale.UK
            "es" -> Locale("es")
            "fr-fr" -> Locale.FRANCE
            "hi" -> Locale("hi")
            "it" -> Locale.ITALY
            "pt-br" -> Locale("pt", "BR")
            "ja" -> Locale.JAPAN
            "zh" -> Locale.CHINA
            else -> Locale.US
        }
        runCatching { engine.language = locale }
    }

    override fun stop() {
        runCatching { engine.stop() }
        // Reported as a completion rather than dropped: whoever was waiting is waiting still.
        finish(null)
    }

    override fun release() {
        pending = null
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        ready = false
    }

    private companion object {
        const val TAG = "PrismSpeech"
    }
}
