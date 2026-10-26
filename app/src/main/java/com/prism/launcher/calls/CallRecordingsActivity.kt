package com.prism.launcher.calls

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * The recordings saved by [CallRecorder].
 *
 * A recording that cannot be found again is not a feature, so this is reachable from both the
 * dialer and the keypad. Playing is local and never uploads anything; deleting removes the WAV and
 * its index entry together.
 */
class CallRecordingsActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var playingButton: TextView? = null

    private fun d(value: Int): Int = IosUi.dp(this, value.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Call recordings"

        val root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@CallRecordingsActivity))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(8), d(16), d(36))
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(scroll)
        setContentView(root)

        column.addView(TextView(this).apply {
            text = "‹ Dialer"
            textSize = 17f
            setTextColor(IosUi.accent(this@CallRecordingsActivity))
            isClickable = true
            isFocusable = true
            setPadding(0, d(6), d(16), d(6))
            setOnClickListener { finish() }
        })
        column.addView(TextView(this).apply {
            text = "Call recordings"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@CallRecordingsActivity))
            setPadding(0, 0, 0, d(6))
        })
        column.addView(TextView(this).apply {
            text = "Stored only on this device. The source line says whether Android allowed both " +
                "sides of the call to be captured or only the microphone."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@CallRecordingsActivity))
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(0, 0, 0, d(14))
        })

        empty = TextView(this).apply {
            text = "No calls have been recorded yet.\n\nDuring a carrier call, tap Record on the call screen."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@CallRecordingsActivity))
            setPadding(d(20), d(40), d(20), d(20))
            visibility = View.GONE
        }
        column.addView(empty)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        load()
    }

    private fun load() {
        list.removeAllViews()
        val recordings = CallRecordings.all(this)
        empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        recordings.forEach { recording -> list.addView(row(recording)) }
    }

    private fun row(recording: CallRecordings.Recording): View {
        val card = IosUi.card(this)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = d(10) }

        val who = TextView(this).apply {
            text = recording.name.ifBlank { recording.number.ifBlank { "Unknown caller" } }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@CallRecordingsActivity))
        }
        card.addView(who)
        if (recording.number.isNotBlank() &&
            recording.name.isNotBlank() && recording.name != recording.number
        ) {
            card.addView(TextView(this).apply {
                text = recording.number
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@CallRecordingsActivity))
            })
        }
        val seconds = (recording.durationMs / 1000).coerceAtLeast(0)
        card.addView(TextView(this).apply {
            text = DateUtils.getRelativeTimeSpanString(recording.at) as String +
                " · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · " +
                recording.source.ifBlank { "call audio" }
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@CallRecordingsActivity))
            setPadding(0, d(3), 0, d(6))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = TextView(this).apply {
            text = "Play"
            textSize = 14f
            setTextColor(IosUi.accent(this@CallRecordingsActivity))
            setPadding(d(10), d(8), d(18), d(8))
            isClickable = true
            setOnClickListener { togglePlayback(recording, this) }
        }
        actions.addView(play)
        actions.addView(TextView(this).apply {
            text = "Delete"
            textSize = 14f
            setTextColor(IosUi.destructive(this@CallRecordingsActivity))
            setPadding(d(10), d(8), d(10), d(8))
            isClickable = true
            setOnClickListener {
                com.prism.launcher.theme.PrismDialogs.Builder(this@CallRecordingsActivity)
                    .setTitle("Delete recording?")
                    .setMessage("This removes the audio file from Prism. It cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        if (playingId == recording.id) stopPlayback()
                        CallRecordings.delete(this@CallRecordingsActivity, recording)
                        load()
                    }
                    .show()
            }
        })
        card.addView(actions)
        return card
    }

    private fun togglePlayback(recording: CallRecordings.Recording, button: TextView) {
        if (playingId == recording.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val mp = MediaPlayer()
        val started = runCatching {
            mp.setDataSource(recording.file.absolutePath)
            mp.prepare()
            mp.start()
        }.isSuccess
        if (!started) {
            mp.release()
            Toast.makeText(this, "That recording could not be played", Toast.LENGTH_SHORT).show()
            return
        }
        player = mp
        playingId = recording.id
        playingButton = button
        button.text = "Stop"
        mp.setOnCompletionListener {
            if (playingId == recording.id) stopPlayback()
        }
    }

    private fun stopPlayback() {
        val mp = player
        player = null
        playingId = null
        playingButton?.text = "Play"
        playingButton = null
        if (mp != null) {
            runCatching { mp.stop() }
            mp.release()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
