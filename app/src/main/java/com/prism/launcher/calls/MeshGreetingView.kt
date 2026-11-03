package com.prism.launcher.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setting your own meshvoice greeting: what a caller hears before leaving you a voicemail.
 *
 * ## Two ways to make one
 *
 * The floating record button records it from the microphone -- tap to start, tap to stop, and it
 * stops itself at [MeshGreeting.MAX_SECONDS]. When a speech model is set up (Kokoro, a voice cloned
 * from it, or a ModelCraft model), a text box, a send button and a voice list appear at the bottom
 * as well, so the greeting can be typed and spoken by the model instead.
 *
 * ## Everything is the theme's
 *
 * Background, cards, text, the field, the record button and the voice list's popup are all read
 * from the active theme and its light or dark mode through [IosUi], so this screen matches the rest
 * of Prism whatever the user picked -- including the dropdown, which Android would otherwise draw
 * in its own default colours.
 */
@SuppressLint("ViewConstructor")
class MeshGreetingView(
    context: Context,
    /** Asks for a runtime permission through whatever hosts this view. */
    private val requestPermission: (permission: String, onResult: (Boolean) -> Unit) -> Unit,
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val status: TextView
    private val statusDetail: TextView
    private val playButton: TextView
    private val deleteButton: TextView
    private val recordingLabel: TextView
    private val record: ImageButton
    private var voiceList: Spinner? = null
    private var input: EditText? = null
    private var send: ImageButton? = null

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var recording = false
    private var recordStartedAt = 0L
    private val pcm = ByteArrayOutputStream()

    private var player: MediaPlayer? = null
    private var voices: List<MeshGreeting.Voice> = emptyList()

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        isClickable = true

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Header: back to the mailbox, and the title.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8), d(18), d(20), d(6))
        }
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_back_24)
            imageTintList = ColorStateList.valueOf(IosUi.accent(context))
            background = null
            contentDescription = "Back to Meshvoice"
            setOnClickListener { close() }
        }
        val title = TextView(context).apply {
            text = "Voicemail greeting"
            textSize = 26f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        }
        header.addView(back, LinearLayout.LayoutParams(d(48), d(48)))
        header.addView(title)
        column.addView(header)

        // The middle: what is set now, and the floating record button over it.
        val middle = FrameLayout(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(8), d(16), d(120))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(14), d(16), d(14))
            background = GradientDrawable().apply {
                cornerRadius = d(14).toFloat()
                setColor(IosUi.cardBackground(context))
            }
        }
        status = TextView(context).apply {
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        }
        statusDetail = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, d(3), 0, d(10))
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = pill("Play") { togglePlayback() }
        deleteButton = pill("Remove", destructive = true) { confirmRemove() }
        buttons.addView(playButton)
        buttons.addView(deleteButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = d(10) })
        card.addView(status)
        card.addView(statusDetail)
        card.addView(buttons)
        content.addView(card)

        recordingLabel = TextView(context).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(IosUi.destructive(context))
            setPadding(0, d(18), 0, 0)
            visibility = View.GONE
        }
        content.addView(recordingLabel)

        content.addView(TextView(context).apply {
            text = "Your trusted devices keep a copy of this greeting and play it to anyone leaving " +
                "you a meshnet voicemail while you are away. Tap the record button to record it" +
                (if (MeshGreeting.canSpeak()) ", or type it below and have your speech model say it." else ".")
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(d(4), d(14), d(4), 0)
        })

        val scroll = ScrollView(context).apply { addView(content) }
        middle.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        record = ImageButton(context).apply {
            setImageResource(R.drawable.ic_call_mic_32)
            contentDescription = "Record your greeting"
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(d(17), d(17), d(17), d(17))
            elevation = d(6).toFloat()
            setOnClickListener { if (recording) stopRecording(save = true) else startRecording() }
        }
        middle.addView(record, LayoutParams(d(64), d(64), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = d(22)
            bottomMargin = d(22)
        })
        column.addView(middle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        if (MeshGreeting.canSpeak()) column.addView(buildSpeakBar())

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Keep the bottom bar above the navigation bar, wherever this view is hosted.
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            column.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            insets
        }

        paintRecordButton()
        renderStatus()
    }

    // ── Typed greeting ─────────────────────────────────────────────────────

    /** The voice list, the text box and the send button: only built when a speech model exists. */
    private fun buildSpeakBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(10), d(12), d(12))
            setBackgroundColor(IosUi.cardBackground(context))
        }
        voices = MeshGreeting.voices()
        val spinner = Spinner(context, Spinner.MODE_DROPDOWN).apply {
            adapter = ThemedVoiceAdapter(context, voices)
            setPopupBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = d(12).toFloat()
                setColor(IosUi.cardBackground(context))
                setStroke(d(1), IosUi.separator(context))
            })
            background = GradientDrawable().apply {
                cornerRadius = d(10).toFloat()
                setColor(IosUi.fill(context))
            }
            val preferred = MeshGreeting.defaultVoice()
            val index = voices.indexOfFirst { it.id == preferred }
            if (index >= 0) setSelection(index)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = Unit
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        voiceList = spinner
        bar.addView(spinner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, d(44)).apply {
            bottomMargin = d(8)
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(context).apply {
            hint = "Type your greeting"
            textSize = 15f
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = IosUi.fieldBackground(context)
            setPadding(d(14), d(10), d(14), d(10))
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(400))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { speak(); true } else false
            }
        }
        input = field
        val sendButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_call_send_24)
            contentDescription = "Speak this greeting"
            imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(d(11), d(11), d(11), d(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(context))
            }
            setOnClickListener { speak() }
        }
        send = sendButton
        row.addView(field, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sendButton, LinearLayout.LayoutParams(d(44), d(44)).apply { marginStart = d(8) })
        bar.addView(row)
        return bar
    }

    private fun speak() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, "Type what the greeting should say", Toast.LENGTH_SHORT).show()
            return
        }
        if (recording) stopRecording(save = false)
        val voice = voices.getOrNull(voiceList?.selectedItemPosition ?: -1)
        send?.isEnabled = false
        send?.alpha = 0.4f
        status.text = "Speaking your greeting…"
        statusDetail.text = voice?.label ?: "Default voice"
        Thread({
            val result = MeshGreeting.synthesize(text, voice?.id.orEmpty())
            handler.post {
                send?.isEnabled = true
                send?.alpha = 1f
                result.fold(
                    onSuccess = { wav ->
                        MeshGreeting.set(wav, "spoken", "Spoken in ${voice?.label ?: "the default voice"}: “$text”")
                        input?.setText("")
                        renderStatus()
                        togglePlayback()
                        Toast.makeText(context, "Greeting saved", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        renderStatus()
                        Toast.makeText(
                            context, error.message ?: "The greeting could not be spoken", Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }, "meshvoice-greeting-speak").apply { isDaemon = true; start() }
    }

    // ── Recording ──────────────────────────────────────────────────────────

    private fun startRecording() {
        requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
            if (!granted) {
                Toast.makeText(context, "Recording a greeting needs the microphone.", Toast.LENGTH_LONG).show()
                return@requestPermission
            }
            beginRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginRecording() {
        stopPlayback()
        val minimum = AudioRecord.getMinBufferSize(
            MeshCalls.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val source = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                MeshCalls.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, MeshCalls.FRAME_BYTES * 8),
            )
        }.getOrNull()
        if (source == null || source.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { source?.release() }
            Toast.makeText(context, "The microphone could not be opened.", Toast.LENGTH_LONG).show()
            return
        }
        // A greeting is recorded on the microphone chosen for calls, like the voicemail it plays before.
        CallDevices.applyTo(context, source)
        recorder = source
        synchronized(pcm) { pcm.reset() }
        recording = true
        recordStartedAt = System.currentTimeMillis()
        source.startRecording()
        recordThread = Thread({
            val frame = ByteArray(MeshCalls.FRAME_BYTES)
            val limit = MeshGreeting.MAX_SECONDS * MeshCalls.SAMPLE_RATE * 2
            while (recording) {
                val read = runCatching { source.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read <= 0) continue
                val full = synchronized(pcm) {
                    pcm.write(frame, 0, read)
                    pcm.size() >= limit
                }
                if (full) {
                    handler.post { if (recording) stopRecording(save = true) }
                    break
                }
            }
        }, "meshvoice-greeting-record").apply { isDaemon = true; start() }
        paintRecordButton()
        handler.post(ticker)
    }

    private fun stopRecording(save: Boolean) {
        if (!recording) return
        recording = false
        runCatching { recorder?.stop() }
        runCatching { recordThread?.join(400) }
        runCatching { recorder?.release() }
        recorder = null
        recordThread = null
        handler.removeCallbacks(ticker)
        recordingLabel.visibility = View.GONE
        paintRecordButton()
        val captured = synchronized(pcm) { pcm.toByteArray() }
        if (!save) return
        if (captured.size < MeshCalls.SAMPLE_RATE) {
            Toast.makeText(context, "That was too short to be a greeting.", Toast.LENGTH_SHORT).show()
            return
        }
        MeshGreeting.set(MeshGreeting.fromPcm(captured), "recorded", "Recorded on this device")
        renderStatus()
        Toast.makeText(context, "Greeting saved", Toast.LENGTH_SHORT).show()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt()
            recordingLabel.visibility = View.VISIBLE
            recordingLabel.text = "● Recording  %d:%02d  ·  stops at 0:%02d".format(
                seconds / 60, seconds % 60, MeshGreeting.MAX_SECONDS,
            )
            handler.postDelayed(this, 250)
        }
    }

    private fun paintRecordButton() {
        record.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (recording) IosUi.destructive(context) else IosUi.accent(context))
        }
        record.setImageResource(if (recording) R.drawable.ic_call_stop_24 else R.drawable.ic_call_mic_32)
        record.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        record.contentDescription = if (recording) "Stop recording" else "Record your greeting"
    }

    // ── What is set ────────────────────────────────────────────────────────

    private fun renderStatus() {
        val info = MeshGreeting.info()
        if (info == null) {
            status.text = "No greeting yet"
            statusDetail.text = "Callers hear a beep and then leave their message."
            playButton.visibility = View.GONE
            deleteButton.visibility = View.GONE
            return
        }
        val seconds = (info.durationMs / 1000).toInt()
        status.text = "Your greeting · %d:%02d".format(seconds / 60, seconds % 60)
        statusDetail.text = buildString {
            append(info.detail.ifBlank { if (info.source == "spoken") "Spoken by your speech model" else "Recorded" })
            if (info.at > 0) {
                append(" · ")
                append(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(info.at)))
            }
        }
        playButton.visibility = View.VISIBLE
        deleteButton.visibility = View.VISIBLE
        playButton.text = if (player != null) "Stop" else "Play"
    }

    private fun togglePlayback() {
        if (player != null) {
            stopPlayback()
            renderStatus()
            return
        }
        val wav = MeshGreeting.current() ?: return
        val temp = runCatching { File(context.cacheDir, "greeting-preview.wav").apply { writeBytes(wav) } }
            .getOrNull() ?: return
        val media = MediaPlayer()
        val ok = runCatching {
            media.setDataSource(temp.absolutePath)
            media.setOnCompletionListener { stopPlayback(); renderStatus() }
            media.prepare()
            media.start()
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { media.release() }
            Toast.makeText(context, "The greeting could not be played", Toast.LENGTH_SHORT).show()
            return
        }
        player = media
        renderStatus()
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun confirmRemove() {
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Remove your greeting?")
            .setMessage("Your trusted devices are told too, so callers will hear only the beep.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Remove") { _, _ ->
                stopPlayback()
                MeshGreeting.clear()
                renderStatus()
            }
            .show()
    }

    // ── Leaving ────────────────────────────────────────────────────────────

    fun close() {
        release()
        onClose?.invoke()
    }

    /** Stops the microphone and the player; called whenever this screen goes away. */
    fun release() {
        stopRecording(save = false)
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun pill(label: String, destructive: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(if (destructive) IosUi.destructive(context) else IosUi.accent(context))
            setPadding(d(16), d(8), d(16), d(8))
            background = GradientDrawable().apply {
                cornerRadius = d(18).toFloat()
                setColor(IosUi.fill(context))
            }
            setOnClickListener { onClick() }
        }

    private fun d(value: Int): Int = IosUi.dp(context, value.toFloat())

    /**
     * The voice list in the theme's colours, both closed and open: Android's default spinner rows
     * are black-on-white regardless of theme, which is unreadable on a dark one.
     */
    private class ThemedVoiceAdapter(
        context: Context,
        voices: List<MeshGreeting.Voice>,
    ) : ArrayAdapter<MeshGreeting.Voice>(context, 0, voices) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position, convertView, closed = true)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            row(position, convertView, closed = false)

        private fun row(position: Int, convertView: View?, closed: Boolean): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            val voice = getItem(position)
            val pad = IosUi.dp(context, 14f)
            view.setPadding(pad, IosUi.dp(context, if (closed) 6f else 12f), pad, IosUi.dp(context, if (closed) 6f else 12f))
            view.text = if (closed) "Voice: ${voice?.label.orEmpty()}" else voice?.label.orEmpty()
            view.setTextColor(
                if (voice?.cloned == true && !closed) IosUi.accent(context) else IosUi.label(context)
            )
            return view
        }
    }
}
