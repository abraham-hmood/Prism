package com.prism.launcher.language

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import com.prism.launcher.speech.PrismSpeaker
import kotlinx.coroutines.launch

/**
 * One lesson, as a conversation.
 *
 * ## The shape
 *
 * A stage at the top — the tutor, or at A0 the picture they are naming — and the conversation
 * under it, and one way to answer at the bottom. Nothing else. A language lesson is two people
 * talking and the screen should not contain anything that is not that.
 *
 * ## Why you cannot type at a speaking lesson
 *
 * Deliberately. Given a text box, everybody types: it is faster, it is private, and it does not
 * require hearing your own accent. And typing is not the skill — the learner who can write a
 * paragraph and cannot order a coffee is the single most common product of language apps, and it is
 * produced exactly by making the keyboard available. So speaking lessons have a microphone and no
 * alternative.
 *
 * Grammar and script lessons get the keyboard instead, for the opposite reason: saying a spelling
 * out loud teaches nothing, and the hand is the thing being trained.
 *
 * ## Why the tutor's line is spoken before it is readable
 *
 * The bubble appears and the voice starts together, and the translation is behind a tap. Reading
 * the sentence first means the ear never has to do the work, and listening is the skill that leads
 * speaking at every level.
 */
class LessonActivity : PrismBaseActivity() {

    private val accent = 0xFF6A3FE0.toInt()

    private lateinit var runner: LessonRunner
    private lateinit var tutor: LanguageTutors.Tutor

    private lateinit var stage: FrameLayout
    private lateinit var stageImage: ImageView
    private lateinit var stagePicture: TextView
    private lateinit var stageCaption: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var statusLine: TextView
    private lateinit var hintChip: TextView
    private lateinit var inputArea: FrameLayout

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busy = false
    private var lastHint: String? = null
    private var startedAt = 0L

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Refusing the microphone must not close the door on the level. A0 is spoken from end to
        // end, so "no microphone" used to mean "no lessons at all" — for a learner on a bus, or one
        // who taps Deny once by reflex, that is the whole feature gone. Typing is a worse exercise
        // than speaking and it is not pretended otherwise, but it is a lesson rather than nothing.
        if (!granted) {
            toast("No microphone — you can type your answers instead.")
            buildTextInput()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lessonId = intent.getStringExtra(EXTRA_LESSON_ID)
        val built = lessonId?.let { LessonRunner.forLesson(this, it) }
        if (built == null) {
            toast("That lesson is no longer part of your plan.")
            finish()
            return
        }
        runner = built
        tutor = LanguageProfile.tutor() ?: LanguageTutors.ALL.first()
        startedAt = System.currentTimeMillis()

        setContentView(buildUi())

        if (runner.spec.mode == LessonSpec.Mode.SPEAKING &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        begin()
    }

    // -- Layout ---------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@LessonActivity))
            fitsSystemWindows = true
        }

        root.addView(header(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(buildStage(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200f),
        ).apply { setMargins(dp(14f), dp(4f), dp(14f), dp(10f)) })

        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14f)
            setPadding(p, p, p, p)
        }
        scroller = ScrollView(this).apply {
            addView(conversation, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        statusLine = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@LessonActivity))
            setPadding(dp(20f), 0, dp(20f), dp(6f))
        }
        root.addView(statusLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // "What to say?" — the single most useful affordance in a speaking app. A learner stuck for
        // a word either gets a nudge or leaves; there is no third outcome.
        hintChip = TextView(this).apply {
            text = "💡  What to say?"
            textSize = 14f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                setColor(TutorPortrait.withAlpha(accent, 26))
                cornerRadius = dp(18f).toFloat()
            }
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showHint() }
        }
        root.addView(hintChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END; setMargins(0, 0, dp(18f), dp(8f)) })

        inputArea = FrameLayout(this)
        root.addView(inputArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16f), 0, dp(16f), dp(22f)) })

        if (runner.spec.mode == LessonSpec.Mode.SPEAKING) buildMicInput() else buildTextInput()
        return root
    }

    private fun header(): View = FrameLayout(this).apply {
        val p = dp(14f)
        setPadding(p, dp(12f), p, dp(4f))

        addView(TextView(this@LessonActivity).apply {
            text = "✕"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(this@LessonActivity))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@LessonActivity))
                shape = GradientDrawable.OVAL
            }
            isClickable = true
            setOnClickListener { confirmLeave() }
        }, FrameLayout.LayoutParams(dp(38f), dp(38f), Gravity.START or Gravity.CENTER_VERTICAL))

        addView(LinearLayout(this@LessonActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@LessonActivity).apply {
                text = tutor.name
                textSize = 17f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(IosUi.label(this@LessonActivity))
            })
            addView(TextView(this@LessonActivity).apply {
                text = "${runner.spec.level.code} · ${runner.spec.kind.label}"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(this@LessonActivity))
            })
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
    }

    /**
     * The stage: the tutor, or the picture they are naming.
     *
     * At A0 the picture takes the whole panel and the tutor shrinks to a corner badge, because at
     * that level the picture IS the lesson — the word arrives attached to it, and the face is a
     * reminder of who is speaking rather than the subject.
     */
    private fun buildStage(): View {
        stage = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(tutor.portrait.backdrop)
                cornerRadius = dp(26f).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(26f).toFloat())
                }
            }
        }

        stageImage = ImageView(this).apply {
            setImageDrawable(TutorPortrait(tutor.portrait, circular = false, withBackdrop = false))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        stage.addView(stageImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        stagePicture = TextView(this).apply {
            textSize = 84f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        stage.addView(stagePicture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        stageCaption = TextView(this).apply {
            textSize = 26f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(0xFF15131A.toInt())
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                setColor(0xE6FFFFFF.toInt())
                cornerRadius = dp(16f).toFloat()
            }
            visibility = View.GONE
            isClickable = true
            // Tapping the word says it again. A learner at A0 needs a word four or five times and
            // asking the tutor to repeat is itself a skill they do not have yet.
            setOnClickListener { speakCurrentWord() }
        }
        stage.addView(stageCaption, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f) })

        return stage
    }

    // -- Input ----------------------------------------------------------------

    /** Hold to talk. A tap-to-toggle mic leaves the recogniser running when somebody puts the phone down. */
    private fun buildMicInput() {
        inputArea.removeAllViews()

        val mic = TextView(this).apply {
            text = "🎤"
            textSize = 30f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(accent)
                shape = GradientDrawable.OVAL
            }
        }
        mic.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!busy) {
                        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start()
                        startListening()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    stopListening()
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        inputArea.addView(mic, FrameLayout.LayoutParams(dp(76f), dp(76f), Gravity.CENTER))
    }

    /** The pillbox, for grammar and script lessons. */
    private fun buildTextInput() {
        inputArea.removeAllViews()

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@LessonActivity))
                cornerRadius = dp(26f).toFloat()
                setStroke(dp(1f), IosUi.separator(this@LessonActivity))
            }
            setPadding(dp(18f), dp(4f), dp(6f), dp(4f))
        }

        val field = EditText(this).apply {
            hint = "Write your answer"
            setSingleLine()
            textSize = 16f
            background = null
            setTextColor(IosUi.label(this@LessonActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@LessonActivity))
        }
        pill.addView(field, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))

        val send = TextView(this).apply {
            text = "→"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(accent)
            // Transparent, as asked: the arrow is the button.
            background = null
            isClickable = true
            setOnClickListener {
                val typed = field.text.toString().trim()
                if (typed.isNotEmpty() && !busy) {
                    field.setText("")
                    submit(typed)
                }
            }
        }
        pill.addView(send, LinearLayout.LayoutParams(dp(46f), dp(46f)))

        inputArea.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    // -- The conversation -----------------------------------------------------

    private fun begin() {
        busy = true
        status("${tutor.name} is getting ready…")
        lifecycleScope.launch {
            val turn = runner.open()
            deliver(turn)
        }
    }

    private fun submit(said: String) {
        addBubble(said, fromTutor = false)
        busy = true
        hintChip.visibility = View.GONE
        status("${tutor.name} is thinking…")

        lifecycleScope.launch {
            val turn = runner.reply(said)
            deliver(turn)
        }
    }

    /** Puts a tutor turn on screen, speaks it, and re-arms the input. */
    private fun deliver(turn: LessonRunner.TutorTurn) {
        busy = false
        status("")

        turn.picture?.let { showPicture(it) }
        addBubble(turn.say, fromTutor = true, note = turn.note, teach = turn.teach)

        lastHint = turn.hint
        hintChip.visibility = if (turn.hint.isNullOrBlank()) View.GONE else View.VISIBLE

        if (turn.fromTemplate) {
            status(LessonTemplates.noModelNotice(runner.spec))
        }

        speakTurn(turn)

        if (turn.done) finishLesson()
    }

    /**
     * One bubble.
     *
     * The tutor's sit left in the card colour, the learner's right in the accent — the arrangement
     * every messaging app uses, because it is the one everybody can already read. A correction rides
     * under the tutor's bubble in a smaller, quieter style rather than as its own message, so it
     * reads as an aside instead of interrupting the conversation.
     */
    private fun addBubble(
        text: String,
        fromTutor: Boolean,
        note: String? = null,
        teach: String? = null,
    ) {
        if (text.isBlank() && teach.isNullOrBlank()) return

        val bubble = TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(if (fromTutor) IosUi.label(this@LessonActivity) else Color.WHITE)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = GradientDrawable().apply {
                // The learner's own bubble is iOS blue, not the page's violet. This screen borrows
                // the conventions of a messaging app wholesale because those are the ones everybody
                // can already read, and "the blue one is mine" is the strongest of them.
                setColor(
                    if (fromTutor) IosUi.cardBackground(this@LessonActivity)
                    else IosUi.accent(this@LessonActivity)
                )
                cornerRadius = dp(20f).toFloat()
            }
            isClickable = true
            // Tapping the tutor plays it again; a learner who missed it needs the audio, not a
            // transcript they can read at their leisure.
            if (fromTutor) setOnClickListener { speakNative(text) }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (fromTutor) Gravity.START else Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6f)
                if (fromTutor) marginEnd = dp(52f) else marginStart = dp(52f)
            }
            addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = if (fromTutor) Gravity.START else Gravity.END })
        }

        // The taught item gets its own line, larger and in the level's colour, because it is the
        // only part of the bubble the learner is meant to repeat — and at a glance it must be
        // obvious which half of a bilingual message that is.
        if (!teach.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                this.text = teach
                textSize = 22f
                paint.isFakeBoldText = true
                setTextColor(accent)
                setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
                background = GradientDrawable().apply {
                    setColor(TutorPortrait.withAlpha(accent, 26))
                    cornerRadius = dp(18f).toFloat()
                }
                isClickable = true
                setOnClickListener { speakTarget(teach) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f); gravity = Gravity.START })
        }

        if (!note.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                this.text = "✎  $note"
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@LessonActivity))
                setPadding(dp(8f), dp(5f), dp(8f), 0)
            })
        }

        conversation.addView(row)
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showPicture(item: LessonSpec.PictureItem) {
        when (val picture = PictureBank.picture(this, item)) {
            is PictureBank.Picture.Emoji -> {
                stagePicture.text = picture.glyph
                stagePicture.visibility = View.VISIBLE
                stageImage.visibility = View.GONE
            }
            is PictureBank.Picture.Photo -> {
                PictureBank.bitmap(picture.file)?.let {
                    stageImage.setImageBitmap(it)
                    stageImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    stageImage.visibility = View.VISIBLE
                    stagePicture.visibility = View.GONE
                }
            }
            // No picture available: the word alone still teaches, and the alternative is a blank
            // panel that looks broken.
            PictureBank.Picture.None -> {
                stagePicture.text = "🔤"
                stagePicture.visibility = View.VISIBLE
                stageImage.visibility = View.GONE
            }
        }

        stageCaption.text = if (runner.spec.showRomanisation && item.romanisation != null) {
            "${item.word}  ·  ${item.romanisation}"
        } else {
            item.word
        }
        stageCaption.visibility = View.VISIBLE
    }

    private fun speakCurrentWord() {
        runner.currentPicture()?.let { speakTarget(it.word) }
    }

    /**
     * Says the whole turn: the tutor's own line, then the thing being taught.
     *
     * Two utterances in two voices, because they are in two languages. Handing a Mandarin word to an
     * American English voice is what made the tutors silent — see [LanguageVoices]. The taught item
     * also goes slower, which is what a person does when they want you to copy them.
     */
    private fun speakTurn(turn: LessonRunner.TutorTurn) {
        PrismSpeaker.stop()
        val item = turn.teach
        if (turn.say.isBlank()) {
            item?.let { speakTarget(it) }
            return
        }
        PrismSpeaker.speakAs(
            context = this,
            text = turn.say,
            voiceId = LanguageVoices.tutorVoice(runner.spec.nativeCode, tutor),
            speed = LanguageProfile.speakingSpeed(),
            localeTag = LanguageVoices.tutorLocaleTag(runner.spec.nativeCode, tutor),
        ) { item?.let { speakTarget(it) } }
    }

    /** The tutor talking to the learner, in the learner's language. */
    private fun speakNative(text: String) {
        PrismSpeaker.stop()
        PrismSpeaker.speakAs(
            context = this,
            text = text,
            voiceId = LanguageVoices.tutorVoice(runner.spec.nativeCode, tutor),
            speed = LanguageProfile.speakingSpeed(),
            localeTag = LanguageVoices.tutorLocaleTag(runner.spec.nativeCode, tutor),
        )
    }

    /** The thing being learned, in a voice that actually speaks that language. */
    private fun speakTarget(text: String) {
        PrismSpeaker.stop()
        PrismSpeaker.speakAs(
            context = this,
            text = text,
            // Null when Kokoro has no voice of this tutor's gender in this language: the system
            // engine then takes it with the locale below, rather than the word being read aloud by
            // somebody of the wrong gender speaking the wrong language.
            voiceId = LanguageVoices.targetVoice(runner.spec.targetCode, tutor),
            // Slower than conversation: this is the bit being copied.
            speed = (LanguageProfile.speakingSpeed() * 0.85f).coerceAtLeast(0.6f),
            localeTag = LanguageVoices.localeTag(runner.spec.targetCode),
        )
    }

    private fun showHint() {
        val hint = lastHint ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("You could say")
            .setMessage(hint)
            .setPositiveButton("Got it", null)
            .show()
    }

    // -- Listening ------------------------------------------------------------

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("This device has no speech recognition. Switching to typing.")
            buildTextInput()
            return
        }

        val instance = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        listening = true
        status("Listening…")

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (heard.isNullOrBlank()) {
                    status("Didn't catch that — hold the button and try again")
                } else {
                    submit(heard)
                }
            }

            override fun onError(error: Int) {
                listening = false
                status(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone is not allowed"
                        else -> "Could not hear you — try again"
                    }
                )
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { status("“$it”") }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = status("…")
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching {
            instance.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    // The target language, not the phone's. Recognising Spanish speech with an
                    // English model returns phonetic nonsense, and the learner is then told they
                    // said something they did not.
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            )
        }.onFailure {
            listening = false
            PrismLogger.logWarning(TAG, "Could not start listening: ${it.message}")
            status("Could not open the microphone")
        }
    }

    private fun stopListening() {
        if (!listening) return
        runCatching { recognizer?.stopListening() }
    }

    /**
     * The BCP-47 tag the recogniser wants.
     *
     * A bare language code works for most; the ones that genuinely need a region get one, because
     * `zh` alone is ambiguous between Mandarin and Cantonese on some devices and `pt` defaults to
     * European Portuguese for a learner who chose Brazilian.
     */
    private fun recognitionLocale(): String = when (val code = runner.spec.targetCode) {
        "zh" -> "zh-CN"
        "zh-TW" -> "zh-TW"
        "pt-BR" -> "pt-BR"
        "pt" -> "pt-PT"
        "en" -> "en-US"
        "ar" -> "ar-SA"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        "hi" -> "hi-IN"
        else -> code
    }

    // -- Ending ---------------------------------------------------------------

    private fun finishLesson() {
        busy = true
        hintChip.visibility = View.GONE
        inputArea.removeAllViews()
        status("${tutor.name} is going over it…")

        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt().coerceIn(1, 120)

        lifecycleScope.launch {
            val outcome = runner.finish(minutes)
            status("")
            showReport(outcome, minutes)
        }
    }

    /**
     * The report card.
     *
     * One thing done well and one thing to work on, which is what a good teacher gives and what a
     * learner can actually hold. A list of eleven corrections is a list nobody reads.
     */
    private fun showReport(outcome: LessonRunner.Outcome, minutes: Int) {
        val streak = LanguageStats.streak()
        val body = buildString {
            outcome.report.score?.let { appendLine("Score: $it / 100").appendLine() }
            outcome.report.didWell?.let { appendLine("What went well").appendLine(it).appendLine() }
            outcome.report.toWorkOn?.let { appendLine("To work on").appendLine(it).appendLine() }

            val learned = outcome.ratings.keys.count { it.startsWith(LearnerModel.VOCAB_PREFIX) }
            appendLine("$learned ${if (learned == 1) "item" else "items"} scheduled for review.")
            appendLine("$minutes ${if (minutes == 1) "minute" else "minutes"} of practice.")
            if (streak > 0) appendLine("Streak: $streak ${if (streak == 1) "day" else "days"}.")
        }.trim()

        android.app.AlertDialog.Builder(this)
            .setTitle("Lesson complete")
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton("Done") { _, _ ->
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    private fun confirmLeave() {
        if (runner.turns.isEmpty()) {
            finish()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Leave the lesson?")
            .setMessage("It will not count as done, and what you covered here will not be scheduled.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = confirmLeave()

    private fun status(text: String) {
        runOnUiThread {
            statusLine.text = text
            statusLine.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Float) = IosUi.dp(this, value)

    override fun onPause() {
        super.onPause()
        PrismSpeaker.stop()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        PrismSpeaker.stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        private const val TAG = "PrismLanguage"
        private const val EXTRA_LESSON_ID = "lesson_id"

        fun intent(context: Context, lessonId: String): Intent =
            Intent(context, LessonActivity::class.java).putExtra(EXTRA_LESSON_ID, lessonId)
    }
}
