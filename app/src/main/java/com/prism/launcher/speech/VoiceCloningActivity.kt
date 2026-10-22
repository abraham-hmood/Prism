package com.prism.launcher.speech

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi

/**
 * Record or upload a sample, clone it into a Kokoro voice, and name it.
 *
 * Reached from Kokoro settings under Intelligence & Messaging. The save dialog offers every
 * built-in language plus any the user has added, and a row that opens a popup to type a new one.
 */
class VoiceCloningActivity : PrismBaseActivity() {

    private lateinit var statusLine: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var micButton: View
    private lateinit var micRing: View

    private val engineRows = ArrayList<EngineRow>()

    private class EngineRow(
        val engine: CloneEngine,
        val badge: TextView,
        val status: TextView,
        val attribution: TextView,
        val action: TextView,
    )

    private var recording = false
    private var working = false
    private var recordStartTime = 0L
    private var recordThread: Thread? = null
    private var pendingProfile: VoiceCloner.SpeakerProfile? = null

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAudioUri(uri)
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            PrismLogger.logWarning("VoiceCloning", "Microphone permission denied")
            Toast.makeText(this, "Microphone access is needed to record a voice sample", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Clone a voice"

        val root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@VoiceCloningActivity))
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@VoiceCloningActivity, 16f)
            setPadding(pad, IosUi.dp(this@VoiceCloningActivity, 8f), pad, IosUi.dp(this@VoiceCloningActivity, 130f))
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(scroll)

        // iOS Navigation Header
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, IosUi.dp(this@VoiceCloningActivity, 8f), 0, IosUi.dp(this@VoiceCloningActivity, 4f))
        }
        val backBtn = TextView(this).apply {
            text = "‹ Settings"
            textSize = 17f
            setTextColor(IosUi.accent(this@VoiceCloningActivity))
            isClickable = true
            setPadding(0, IosUi.dp(this@VoiceCloningActivity, 6f), IosUi.dp(this@VoiceCloningActivity, 16f), IosUi.dp(this@VoiceCloningActivity, 6f))
            setOnClickListener { finish() }
        }
        navBar.addView(backBtn)
        column.addView(navBar)

        val titleView = TextView(this).apply {
            text = "Clone a voice"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@VoiceCloningActivity))
            setPadding(0, 0, 0, IosUi.dp(this@VoiceCloningActivity, 12f))
        }
        column.addView(titleView)

        column.addView(IosUi.sectionHeader(this, "CLONE ENGINES"))
        column.addView(IosUi.sectionFooter(
            this,
            "Kokoro speaks the words; the engine in use re-voices them toward your recording, " +
                "with pitch matched to your voice. Each engine downloads once and every saved " +
                "voice works through all of them.",
        ))
        buildEngineCards(column)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressDrawable = IosUi.progressDrawable(this@VoiceCloningActivity)
        }
        column.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this, 4f),
        ).apply { bottomMargin = IosUi.dp(this@VoiceCloningActivity, 8f) })

        column.addView(spacer())
        column.addView(IosUi.sectionHeader(this, "VOICE SAMPLE"))
        statusLine = TextView(this).apply {
            text = "Long press the microphone to record, or upload an audio file."
            textSize = 15f
            setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
            val h = IosUi.dp(this@VoiceCloningActivity, 4f)
            setPadding(h, h, h, h)
        }
        column.addView(statusLine)

        buildBottomControls(root)
        setContentView(root)

        refreshEngineCards()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    /** One card per engine: name + status + a Download or Use action (or an "In use" badge). */
    private fun buildEngineCards(column: LinearLayout) {
        CloneEngines.ALL.forEach { engine ->
            val card = IosUi.card(this)

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = engine.label
                textSize = 15f
                setTextColor(IosUi.label(this@VoiceCloningActivity))
            }
            titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val badge = TextView(this).apply {
                text = "IN USE"
                textSize = 11f
                setTextColor(IosUi.accent(this@VoiceCloningActivity))
                visibility = View.GONE
            }
            titleRow.addView(badge)
            card.addView(titleRow)

            val status = TextView(this).apply {
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
                setPadding(0, IosUi.dp(this@VoiceCloningActivity, 4f), 0, 0)
            }
            card.addView(status)

            val attribution = TextView(this).apply {
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@VoiceCloningActivity))
                visibility = View.GONE
            }
            engine.attribution?.let {
                attribution.text = it
                attribution.visibility = View.VISIBLE
            }
            card.addView(attribution)

            val action = TextView(this).apply {
                textSize = 15f
                setTextColor(IosUi.accent(this@VoiceCloningActivity))
                isClickable = true
                setPadding(0, IosUi.dp(this@VoiceCloningActivity, 8f), 0, 0)
                setOnClickListener {
                    if (engine.isInstalled()) activateEngine(engine) else downloadEngine(engine)
                }
            }
            card.addView(action)

            column.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = IosUi.dp(this@VoiceCloningActivity, 8f) })

            engineRows.add(EngineRow(engine, badge, status, attribution, action))
        }
    }

    private fun refreshEngineCards() {
        val activeId = CloneEngines.active().id
        engineRows.forEach { row ->
            val installed = row.engine.isInstalled()
            val active = row.engine.id == activeId
            row.badge.visibility = if (active) View.VISIBLE else View.GONE
            row.status.text = row.engine.statusLine()
            row.action.visibility = if (active) View.GONE else View.VISIBLE
            row.action.text = if (installed) "Use" else "Download"
            row.action.isEnabled = !working
        }
    }

    private fun activateEngine(engine: CloneEngine) {
        CloneEngines.setActive(engine)
        refreshEngineCards()
        Toast.makeText(this, "Using ${engine.label}", Toast.LENGTH_SHORT).show()
        PrismLogger.logInfo("VoiceCloning", "Active clone engine: ${engine.id}")
    }

    private fun downloadEngine(engine: CloneEngine) {
        if (working) return
        working = true
        refreshEngineCards()
        progressBar.visibility = View.VISIBLE
        setStatus("Downloading ${engine.label}…")
        Thread({
            val error = engine.ensureInstalled { model, copied, total ->
                if (total > 0) {
                    val pct = (copied * 100 / total).toInt()
                    runOnUiThread { setStatus("Fetching ${model.label}… $pct%") }
                }
            }
            runOnUiThread {
                working = false
                progressBar.visibility = View.GONE
                if (error != null) {
                    refreshEngineCards()
                    fail("${engine.label} download failed: $error")
                    setStatus("Long press the microphone to record, or upload audio.")
                } else {
                    CloneEngines.setActive(engine)
                    refreshEngineCards()
                    Toast.makeText(this, "${engine.label} ready and in use", Toast.LENGTH_SHORT).show()
                    setStatus("Record or upload a sample to clone.")
                }
            }
        }, "voice-clone-dl").apply { isDaemon = true; start() }
    }

    private fun buildBottomControls(root: FrameLayout) {
        val bar = FrameLayout(this).apply {
            val pad = IosUi.dp(this@VoiceCloningActivity, 24f)
            setPadding(pad, pad, pad, IosUi.dp(this@VoiceCloningActivity, 28f))
        }
        val size = IosUi.dp(this, 74f)

        micRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(this@VoiceCloningActivity) and 0x33FFFFFF)
            }
            visibility = View.GONE
        }
        bar.addView(micRing, FrameLayout.LayoutParams(size + IosUi.dp(this, 20f), size + IosUi.dp(this, 20f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        })

        micButton = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(this@VoiceCloningActivity))
            }
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!working) startRecording()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (recording) stopRecording()
                        true
                    }
                    else -> false
                }
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_mic_24)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val inset = IosUi.dp(context, 20f)
                setPadding(inset, inset, inset, inset)
            })
        }
        bar.addView(micButton, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        })

        val upload = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.cardBackground(this@VoiceCloningActivity))
                setStroke(IosUi.dp(this@VoiceCloningActivity, 1f), IosUi.separator(this@VoiceCloningActivity))
            }
            isClickable = true
            setOnClickListener {
                if (!working) pickAudio.launch(arrayOf("audio/*"))
            }
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_upload)
                setColorFilter(IosUi.accent(context))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val inset = IosUi.dp(context, 14f)
                setPadding(inset, inset, inset, inset)
            })
        }
        val uploadSize = IosUi.dp(this, 54f)
        bar.addView(upload, FrameLayout.LayoutParams(uploadSize, uploadSize).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            bottomMargin = IosUi.dp(this@VoiceCloningActivity, 10f)
            marginEnd = IosUi.dp(this@VoiceCloningActivity, 4f)
        })

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
    }

    private fun startRecording() {
        if (recording || working) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recording = true
        recordStartTime = System.currentTimeMillis()
        micRing.visibility = View.VISIBLE
        micButton.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
        micButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setStatus("Recording… release when you are done.")

        recordThread = Thread({
            val rate = VoiceCloner.WORK_RATE
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            @Suppress("MissingPermission")
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4,
                )
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runOnUiThread {
                    recording = false
                    micRing.visibility = View.GONE
                    micButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    fail("The microphone could not be opened")
                    setStatus("The microphone could not be opened.")
                }
                return@Thread
            }

            val chunks = ArrayList<Short>(rate * 30)
            val frame = ShortArray(minBuffer)
            runCatching {
                recorder.startRecording()
                while (recording) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read > 0) for (i in 0 until read) chunks.add(frame[i])
                }
            }
            runCatching { recorder.stop(); recorder.release() }

            val elapsed = System.currentTimeMillis() - recordStartTime
            if (elapsed < 400 || chunks.size < rate * 0.4f) {
                runOnUiThread {
                    micRing.visibility = View.GONE
                    micButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    setStatus("Long press the microphone to record, or upload audio.")
                }
                return@Thread
            }

            val pcm = ShortArray(chunks.size) { chunks[it] }
            val samples = VoiceCloneAudio.shortsToFloat(pcm, pcm.size)
            runOnUiThread {
                micRing.visibility = View.GONE
                micButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                if (samples.isNotEmpty()) cloneSamples(samples, rate)
                else setStatus("Nothing was recorded.")
            }
        }, "voice-clone-rec").apply { isDaemon = true; start() }
    }

    private fun stopRecording() {
        if (!recording) return
        val elapsed = System.currentTimeMillis() - recordStartTime
        recording = false
        micRing.visibility = View.GONE
        micButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        if (elapsed < 400) {
            Toast.makeText(this, "Hold down the microphone to record", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAudioUri(uri: Uri) {
        if (working) return
        working = true
        setStatus("Reading that file…")
        Thread({
            val result = runCatching { VoiceCloneAudio.decodeUri(this, uri) }
            runOnUiThread {
                working = false
                val pair = result.getOrNull()
                if (pair == null) {
                    fail("Could not read audio", result.exceptionOrNull())
                    setStatus("Long press the microphone to record, or upload audio.")
                } else {
                    cloneSamples(pair.first, pair.second)
                }
            }
        }, "voice-clone-file").apply { isDaemon = true; start() }
    }

    private fun cloneSamples(samples: FloatArray, sampleRate: Int) {
        working = true
        progressBar.visibility = View.VISIBLE
        Thread({
            val profile = runCatching {
                VoiceCloner.profile(samples, sampleRate) { stage ->
                    runOnUiThread { setStatus(stage) }
                }
            }
            runOnUiThread {
                working = false
                progressBar.visibility = View.GONE
                val built = profile.getOrNull()
                if (built == null) {
                    fail("Could not analyse the recording", profile.exceptionOrNull())
                    setStatus("Long press the microphone to record, or upload audio.")
                } else {
                    pendingProfile = built
                    showSaveDialog(built)
                }
            }
        }, "voice-clone-run").apply { isDaemon = true; start() }
    }

    private fun showSaveDialog(profile: VoiceCloner.SpeakerProfile) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@VoiceCloningActivity))
                cornerRadius = IosUi.dp(this@VoiceCloningActivity, 14f).toFloat()
            }
        }

        val pad = IosUi.dp(this, 18f)
        val title = TextView(this).apply {
            text = "Save Cloned Voice"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(this@VoiceCloningActivity))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(pad, pad, pad, IosUi.dp(this@VoiceCloningActivity, 4f))
        }
        card.addView(title)

        val subtitle = TextView(this).apply {
            text = "%.1f s recorded · Kokoro speaks, your voice carries it".format(profile.seconds)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
            setPadding(pad, 0, pad, IosUi.dp(this@VoiceCloningActivity, 4f))
        }
        card.addView(subtitle)

        // Which published voice Kokoro speaks through before conversion, so a language with no
        // Kokoro voice is not a surprise: the words still come out, in an American base.
        val baseNote = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.tertiaryLabel(this@VoiceCloningActivity))
            setPadding(pad, 0, pad, IosUi.dp(this@VoiceCloningActivity, 14f))
        }
        card.addView(baseNote)

        val formPad = IosUi.dp(this, 16f)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(formPad, 0, formPad, IosUi.dp(this@VoiceCloningActivity, 16f))
        }

        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.05f
            setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
            setPadding(IosUi.dp(this@VoiceCloningActivity, 4f), IosUi.dp(this@VoiceCloningActivity, 8f), 0, IosUi.dp(this@VoiceCloningActivity, 4f))
        }

        // Voice Name
        form.addView(fieldLabel("Voice Name"))
        val nameField = EditText(this).apply {
            hint = "e.g. My Voice"
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(IosUi.label(this@VoiceCloningActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@VoiceCloningActivity))
            background = IosUi.fieldBackground(this@VoiceCloningActivity)
            val h = IosUi.dp(this@VoiceCloningActivity, 12f)
            setPadding(h, h, h, h)
            isSingleLine = true
        }
        form.addView(nameField)

        // Language. Built-ins plus anything the user has typed in, and a row that adds more.
        var language: KokoroVoices.Language? = KokoroVoices.Language.AMERICAN_ENGLISH
        var customLanguage: String? = null
        form.addView(fieldLabel("Language"))
        val languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = IosUi.fieldBackground(this@VoiceCloningActivity)
            val h = IosUi.dp(this@VoiceCloningActivity, 12f)
            setPadding(h, h, h, h)
            isClickable = true
        }
        val languageText = TextView(this).apply {
            text = language!!.display
            textSize = 15f
            setTextColor(IosUi.label(this@VoiceCloningActivity))
        }
        val chevron = TextView(this).apply {
            text = "▾"
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
        }
        languageRow.addView(languageText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        languageRow.addView(chevron)
        form.addView(languageRow)

        // Gender (iOS Segmented Control). The default is what the recording's pitch suggests --
        // the base voice follows this choice, and a male speaker who leaves it on Female gets a
        // female voice converted toward them.
        var gender = VoiceCloner.guessGender(profile)
        form.addView(fieldLabel("Gender"))
        val genderSegment = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(IosUi.fill(this@VoiceCloningActivity))
                cornerRadius = IosUi.dp(this@VoiceCloningActivity, 8f).toFloat()
            }
            val p = IosUi.dp(this@VoiceCloningActivity, 3f)
            setPadding(p, p, p, p)
        }

        val femaleBtn = TextView(this).apply {
            text = "Female"
            textSize = 14f
            gravity = Gravity.CENTER
            val v = IosUi.dp(this@VoiceCloningActivity, 8f)
            setPadding(0, v, 0, v)
            isClickable = true
        }
        val maleBtn = TextView(this).apply {
            text = "Male"
            textSize = 14f
            gravity = Gravity.CENTER
            val v = IosUi.dp(this@VoiceCloningActivity, 8f)
            setPadding(0, v, 0, v)
            isClickable = true
        }

        fun updateGenderUI() {
            val selectedBg = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@VoiceCloningActivity))
                cornerRadius = IosUi.dp(this@VoiceCloningActivity, 6f).toFloat()
            }
            if (gender == KokoroVoices.Gender.FEMALE) {
                femaleBtn.background = selectedBg
                femaleBtn.setTextColor(IosUi.label(this@VoiceCloningActivity))
                femaleBtn.paint.isFakeBoldText = true
                maleBtn.background = null
                maleBtn.setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
                maleBtn.paint.isFakeBoldText = false
            } else {
                femaleBtn.background = null
                femaleBtn.setTextColor(IosUi.secondaryLabel(this@VoiceCloningActivity))
                femaleBtn.paint.isFakeBoldText = false
                maleBtn.background = selectedBg
                maleBtn.setTextColor(IosUi.label(this@VoiceCloningActivity))
                maleBtn.paint.isFakeBoldText = true
            }
        }
        updateGenderUI()

        // Declared after language and gender so it can read both; called on every change to
        // either, so the note under the title always names the voice Kokoro will speak through.
        fun refreshBaseNote() {
            val base = ClonedVoices.baseVoiceFor(language, gender)
            val chosen = customLanguage ?: language?.display
            baseNote.text = when {
                base == null -> "No published voice is available"
                language != null && base.language == language ->
                    "Kokoro speaks through ${base.displayName} · ${base.language.display}"
                else ->
                    "No published $chosen voice — Kokoro speaks through " +
                        "${base.displayName} · ${base.language.display}"
            }
        }
        refreshBaseNote()

        femaleBtn.setOnClickListener {
            gender = KokoroVoices.Gender.FEMALE
            updateGenderUI()
            refreshBaseNote()
        }
        maleBtn.setOnClickListener {
            gender = KokoroVoices.Gender.MALE
            updateGenderUI()
            refreshBaseNote()
        }

        genderSegment.addView(femaleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        genderSegment.addView(maleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(genderSegment)

        card.addView(form)

        // Separator Hairline
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, IosUi.dp(this@VoiceCloningActivity, 0.5f))
            )
            setBackgroundColor(IosUi.separator(this@VoiceCloningActivity))
        })

        // Action Buttons
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@VoiceCloningActivity))
            val v = IosUi.dp(this@VoiceCloningActivity, 14f)
            setPadding(0, v, 0, v)
            isClickable = true
        }

        val saveBtn = TextView(this).apply {
            text = "Save"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@VoiceCloningActivity))
            paint.isFakeBoldText = true
            val v = IosUi.dp(this@VoiceCloningActivity, 14f)
            setPadding(0, v, 0, v)
            isClickable = true
        }

        val divider = View(this).apply {
            setBackgroundColor(IosUi.separator(this@VoiceCloningActivity))
        }

        buttonBar.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonBar.addView(divider, LinearLayout.LayoutParams(Math.max(1, IosUi.dp(this, 0.5f)), ViewGroup.LayoutParams.MATCH_PARENT))
        buttonBar.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(card)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)

        fun openLanguagePicker() {
            val builtins = KokoroVoices.Language.entries
            val customs = ClonedVoices.customLanguages()
            val labels = buildList {
                builtins.forEach { add(it.display) }
                customs.forEach { add(it) }
                add("＋ Add custom language…")
            }.toTypedArray()

            val selected = when {
                customLanguage != null -> builtins.size + customs.indexOfFirst {
                    it.equals(customLanguage, ignoreCase = true)
                }
                else -> builtins.indexOf(language!!)
            }

            AlertDialog.Builder(this)
                .setTitle("Language")
                .setSingleChoiceItems(labels, selected) { picker, which ->
                    when {
                        which < builtins.size -> {
                            language = builtins[which]
                            customLanguage = null
                        }
                        which < builtins.size + customs.size -> {
                            language = null
                            customLanguage = customs[which - builtins.size]
                        }
                        else -> {
                            picker.dismiss()
                            showAddLanguageDialog { added ->
                                language = null
                                customLanguage = added
                            }
                            return@setSingleChoiceItems
                        }
                    }
                    languageText.text = customLanguage ?: language!!.display
                    refreshBaseNote()
                    picker.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        languageRow.setOnClickListener { openLanguagePicker() }

        cancelBtn.setOnClickListener {
            pendingProfile = null
            dialog.dismiss()
            setStatus("Long press the microphone to record, or upload audio.")
        }

        saveBtn.setOnClickListener {
            val name = nameField.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a voice name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveBtn.isEnabled = false
            cancelBtn.isEnabled = false
            saveBtn.text = "Saving…"

            Thread({
                val saveResult = runCatching {
                    ClonedVoices.save(
                        displayName = name,
                        language = language,
                        customLanguage = customLanguage,
                        gender = gender,
                        region = profile.region,
                        sampleRate = VoiceCloner.WORK_RATE,
                        pitchHz = profile.pitchHz,
                        onStage = { stage -> runOnUiThread { saveBtn.text = stage } },
                    )
                }
                runOnUiThread {
                    if (saveResult.isSuccess) {
                        pendingProfile = null
                        dialog.dismiss()
                        Toast.makeText(this, "Saved as $name", Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        saveBtn.isEnabled = true
                        cancelBtn.isEnabled = true
                        saveBtn.text = "Save"
                        fail("Could not save the voice", saveResult.exceptionOrNull())
                    }
                }
            }, "voice-clone-save").start()
        }

        dialog.show()
    }

    /** The popup behind "＋ Add custom language…": one text field, Cancel and Add. */
    private fun showAddLanguageDialog(onAdded: (String) -> Unit) {
        val field = EditText(this).apply {
            hint = "e.g. Klingon"
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(IosUi.label(this@VoiceCloningActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@VoiceCloningActivity))
            background = IosUi.fieldBackground(this@VoiceCloningActivity)
            val h = IosUi.dp(this@VoiceCloningActivity, 14f)
            setPadding(h, h, h, h)
            isSingleLine = true
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(this@VoiceCloningActivity, 20f)
            setPadding(p, IosUi.dp(this@VoiceCloningActivity, 8f), p, 0)
            addView(field)
        }

        AlertDialog.Builder(this)
            .setTitle("Add a language")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val typed = field.text?.toString()?.trim().orEmpty()
                if (typed.isEmpty()) {
                    Toast.makeText(this, "Type a language name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = ClonedVoices.addCustomLanguage(typed)
                if (added != null) onAdded(added)
            }
            .show()
        field.requestFocus()
    }

    private fun setStatus(text: String) {
        statusLine.text = text
    }

    /**
     * Files [error] into System Diagnostics and shows a toast pointing there.
     *
     * A toast is a few lines at best and disappears; the diagnostics log keeps the full message
     * and stack trace, which is the only place a failure deep in ONNX or a download actually
     * becomes diagnosable. The tag is fixed so every voice-cloning entry is filterable together.
     */
    private fun fail(message: String, error: Throwable? = null) {
        PrismLogger.logError("VoiceCloning", message, error)
        Toast.makeText(this, "$message — see System Diagnostics", Toast.LENGTH_LONG).show()
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@VoiceCloningActivity, 16f),
        )
    }
}
