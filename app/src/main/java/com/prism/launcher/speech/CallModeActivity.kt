package com.prism.launcher.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A spoken conversation with Sam, Nora or Aether.
 *
 * ## The loop, and why it is a loop
 *
 * Listen, think, speak, listen again. A call is not dictation with the replies read out: the point
 * is that neither end has to touch the phone, so every state hands directly to the next and the only
 * controls are mute and hang up.
 *
 * That makes the transitions the whole design. Each one is driven by a completion -- the recogniser
 * finishing, the model answering, the engine finishing the audio -- and never by a timer, because a
 * timer would either cut a long reply off or leave dead air after a short one. It also means every
 * callback has to fire exactly once; see [TtsEngine.speak], which guarantees it, and [finishTurn],
 * which is the only place the state advances.
 *
 * ## Why it does not just call the models directly
 *
 * Sam is answered in this process because that is where Sam already lives. Nora and Aether are not:
 * they build resident connectomes in their own processes, and asking one of them a question from
 * here would build a SECOND one in the launcher. So a call to them sends down the same path the
 * Messages page uses and waits for the answer to arrive in their chat store -- the same answer, in
 * the same place, just read out loud.
 */
class CallModeActivity : PrismBaseActivity() {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING, ENDED }

    private lateinit var nameLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var transcriptLabel: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var endButton: ImageButton

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var state = State.IDLE

    /** Set by the mute control: the loop keeps running but stops opening the microphone. */
    private var muted = false

    private lateinit var speaker: String
    private lateinit var displayName: String

    /** Everything said so far, so Sam's replies follow the conversation rather than each line. */
    private val history = mutableListOf<String>()

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) listen() else {
            statusLabel.text = "Microphone access is needed for a call"
            state = State.IDLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speaker = intent?.getStringExtra(EXTRA_SPEAKER) ?: PrismSettings.VOICE_SPEAKER_SAM
        displayName = intent?.getStringExtra(EXTRA_NAME) ?: "Prism"

        buildUi()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusLabel.text = "This device has no speech recogniser, so a call cannot be placed"
            return
        }

        start()
    }

    // -- UI -------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(IosUi.groupedBackground(this@CallModeActivity))
            val pad = IosUi.dp(this@CallModeActivity, 32f)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(spacer(64))

        nameLabel = TextView(this).apply {
            text = displayName
            textSize = 34f
            setTextColor(IosUi.label(this@CallModeActivity))
            gravity = Gravity.CENTER
        }
        root.addView(nameLabel)

        statusLabel = TextView(this).apply {
            text = "Connecting…"
            textSize = 17f
            setTextColor(IosUi.secondaryLabel(this@CallModeActivity))
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(this@CallModeActivity, 10f), 0, 0)
        }
        root.addView(statusLabel)

        transcriptLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(IosUi.tertiaryLabel(this@CallModeActivity))
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(this@CallModeActivity, 28f), 0, 0)
        }
        root.addView(transcriptLabel)

        root.addView(spacer(0).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        muteButton = controlIcon(
            com.prism.launcher.R.drawable.ic_call_mic_32,
            micTint(),
            "Mute",
        ).apply { setOnClickListener { toggleMute() } }

        endButton = controlIcon(
            com.prism.launcher.R.drawable.ic_call_hangup_32,
            IosUi.destructive(this),
            "End call",
        ).apply { setOnClickListener { hangUp() } }

        controls.addView(muteButton)
        controls.addView(spacer(0).apply {
            layoutParams = LinearLayout.LayoutParams(IosUi.dp(this@CallModeActivity, 40f), 1)
        })
        controls.addView(endButton)

        root.addView(controls)
        root.addView(spacer(32))

        setContentView(root)
    }

    /**
     * One of the call's two controls: an icon on nothing.
     *
     * No background, because on a call screen the two controls ARE the interface and a pill around
     * each one is chrome competing with the only thing on screen. The touch target stays 72dp
     * regardless -- the icon is 32dp and the rest is padding, so the control is easy to hit in the
     * dark without being visually heavy.
     *
     * The press feedback is a BORDERLESS ripple (`selectableItemBackgroundBorderless`): it is the
     * one background that draws nothing at rest, so "transparent" and "you can tell you pressed it"
     * are both true.
     */
    private fun controlIcon(iconRes: Int, tint: Int, describedAs: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = describedAs
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true,
            )
            setBackgroundResource(ripple.resourceId)
            layoutParams = LinearLayout.LayoutParams(
                IosUi.dp(this@CallModeActivity, 72f),
                IosUi.dp(this@CallModeActivity, 72f),
            )
        }

    /**
     * The microphone's colour, which is the mute state.
     *
     * Black when the call can hear you, red when it cannot. [IosUi.label] rather than a literal
     * black so the icon does not vanish on a dark background -- it IS black on the light theme this
     * screen normally runs in, and white on the dark one, which keeps the black/red contrast that
     * carries the meaning in both.
     */
    private fun micTint(): Int =
        if (muted) IosUi.destructive(this) else IosUi.label(this)

    private fun spacer(heightDp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@CallModeActivity, heightDp.toFloat()),
        )
    }

    // -- The loop -------------------------------------------------------------

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listen()
    }

    private fun listen() {
        if (state == State.ENDED) return
        if (muted) {
            setStatus("Muted")
            return
        }

        state = State.LISTENING
        setStatus("Listening…")

        val recognizerInstance = SpeechRecognizer.createSpeechRecognizer(this).also {
            recognizer?.destroy()
            recognizer = it
        }

        recognizerInstance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = setStatus("Listening…")
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = setStatus("…")
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                val said = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()

                if (said.isEmpty()) {
                    // Nothing heard is not an error and not the end of the call; the natural
                    // response is to keep listening, the way a person waits through a pause.
                    listen()
                    return
                }

                transcriptLabel.text = "“$said”"
                think(said)
            }

            override fun onError(error: Int) {
                if (state == State.ENDED) return
                when (error) {
                    // Both mean "the caller said nothing yet", so the call waits rather than ending.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        setStatus("Microphone access is needed for a call")
                        state = State.IDLE
                    }

                    else -> {
                        PrismLogger.logWarning(TAG, "Recogniser error $error")
                        listen()
                    }
                }
            }
        })

        runCatching {
            recognizerInstance.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                },
            )
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not start listening: ${it.message}")
            setStatus("The microphone could not be opened")
            state = State.IDLE
        }
    }

    private fun think(said: String) {
        state = State.THINKING
        setStatus("Thinking…")
        history.add(said)

        lifecycleScope.launch {
            val reply = runCatching { replyTo(said) }.getOrElse {
                PrismLogger.logError(TAG, "Generating a reply failed", it)
                "Sorry — something went wrong answering that."
            }
            if (state == State.ENDED) return@launch
            history.add(reply)
            say(reply)
        }
    }

    private fun say(text: String) {
        state = State.SPEAKING
        setStatus("Speaking…")
        transcriptLabel.text = text

        PrismSpeaker.speak(this, text, speaker) { error ->
            if (error != null) PrismLogger.logWarning(TAG, "Speech failed: $error")
            finishTurn()
        }
    }

    /** The one place a turn ends, so the loop cannot advance twice off one completion. */
    private fun finishTurn() {
        if (state != State.SPEAKING) return
        listen()
    }

    // -- Answers --------------------------------------------------------------

    private suspend fun replyTo(said: String): String = when (speaker) {
        PrismSettings.VOICE_SPEAKER_NORA -> askThroughService(said) {
            com.prism.launcher.nora.NoraService.sendChat(this, said)
        }

        PrismSettings.VOICE_SPEAKER_AETHER -> askThroughService(said) {
            com.prism.launcher.aether.AetherService.sendChat(this, said)
        }

        else -> withContext(Dispatchers.IO) {
            com.prism.launcher.messaging.AiManager.getResponse(
                this@CallModeActivity,
                said,
                history = history.dropLast(1),
            ).first
        }
    }

    /**
     * Sends to Nora or Aether and waits for the answer to land in their chat store.
     *
     * The wait is on their busy flag rather than a fixed delay, because a reply can take a moment or
     * most of a minute depending on what was asked. Bounded anyway: a call that waits forever is a
     * call that has silently died, and saying so is better than dead air.
     */
    private suspend fun askThroughService(said: String, send: () -> Unit): String {
        val before = latestInbound()

        withContext(Dispatchers.Main) { send() }

        val state = if (speaker == PrismSettings.VOICE_SPEAKER_NORA) {
            com.prism.launcher.nora.NoraChatState.busy
        } else {
            com.prism.launcher.aether.AetherChatState.busy
        }

        // Becoming busy can be missed if the answer is instant, so it is bounded and optional; the
        // wait that matters is the one for busy to clear.
        withTimeoutOrNull(4_000) { state.first { it } }
        withTimeoutOrNull(180_000) { state.first { !it } }

        val answer = latestInbound()
        return when {
            answer.isNotBlank() && answer != before -> answer
            else -> "I don't have an answer for that right now."
        }
    }

    private suspend fun latestInbound(): String = withContext(Dispatchers.IO) {
        if (speaker == PrismSettings.VOICE_SPEAKER_NORA) {
            com.prism.launcher.nora.NoraChatStore.load(this@CallModeActivity)
                .lastOrNull { !it.isSent }?.text.orEmpty()
        } else {
            com.prism.launcher.aether.AetherChatStore.load(this@CallModeActivity)
                .lastOrNull { !it.isSent }?.text.orEmpty()
        }
    }

    // -- Controls -------------------------------------------------------------

    private fun toggleMute() {
        muted = !muted
        muteButton.imageTintList = android.content.res.ColorStateList.valueOf(micTint())
        // The label is now carried by the colour, so it lives in the content description -- which is
        // the only place a screen reader was ever going to read it from anyway.
        muteButton.contentDescription = if (muted) "Unmute" else "Mute"
        if (muted) {
            runCatching { recognizer?.cancel() }
            setStatus("Muted")
        } else if (state != State.SPEAKING && state != State.THINKING) {
            listen()
        }
    }

    private fun hangUp() {
        state = State.ENDED
        PrismSpeaker.stop()
        runCatching { recognizer?.cancel() }
        finish()
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusLabel.text = text }
    }

    override fun onDestroy() {
        state = State.ENDED
        PrismSpeaker.stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PrismSpeech"

        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_NAME = "name"

        fun intentFor(context: Context, speaker: String, displayName: String): Intent =
            Intent(context, CallModeActivity::class.java).apply {
                putExtra(EXTRA_SPEAKER, speaker)
                putExtra(EXTRA_NAME, displayName)
            }
    }
}
