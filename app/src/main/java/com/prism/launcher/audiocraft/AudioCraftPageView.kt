package com.prism.launcher.audiocraft

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.audiocraft.AudioCraft
import com.prism.launcher.audiocraft.AudioClip
import com.prism.launcher.media.DocumentImport
import com.prism.launcher.media.ImportedText
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * AudioCraft as a page of the launcher, in the shape of an audiobook app.
 *
 * ## The two layouts
 *
 * The LIBRARY is the page's home: one row per generated file, cover, title, voice and length, with the
 * creation button floating over the bottom corner. Tapping a row opens the PLAYER for it, and the
 * floating button opens the same player for a file that does not exist yet -- which is why the two
 * layouts are one screen and not two: they are the same player, with and without something to play.
 *
 * ## What the player is for
 *
 * A generated file is meant to be listened to, so the player is the real one: a scrubber, fifteen
 * seconds back and thirty forward, a playback speed, and a sleep timer. The passages of the script are
 * listed under it and jumping to one seeks there, which is the chapter list an audiobook has.
 *
 * ## Creating
 *
 * The field takes text, the two icon buttons beside it import documents and start the read. Importing
 * a file DISABLES the field, because the imported document is the input from then on -- that is what
 * the user asked for by picking a file, and leaving the field editable would leave it ambiguous which
 * words get spoken.
 */
class AudioCraftPageView(context: Context) : FrameLayout(context), com.prism.launcher.offload.BusyPage {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val importer = DocumentImport(context)

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background.toInt())
    }
    private val fab = ImageView(context).apply {
        setImageResource(com.prism.launcher.R.drawable.ic_add_24)
        background = rounded(palette.accent.toInt(), 999)
        elevation = dp(6).toFloat()
        setPadding(dp(16), dp(16), dp(16), dp(16))
        contentDescription = "Create a new audio file"
    }

    private var libraryList: RecyclerView? = null
    private var creatorOpen = false
    private var player: MediaPlayer? = null
    private var playing: AudioClip? = null

    // Creator and player widgets, kept for reading back.
    private var promptField: EditText? = null
    private var sourceLabel: String = "typed"
    private var importedText: String = ""
    private var importedNames: MutableList<String> = mutableListOf()
    private var chipsRow: LinearLayout? = null
    private var stageText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var playButton: TextView? = null
    private var seekBar: SeekBar? = null
    private var elapsedLabel: TextView? = null
    private var remainingLabel: TextView? = null
    private var titleLabel: TextView? = null
    private var metaLabel: TextView? = null
    private var artworkLabel: TextView? = null
    private var speedPill: TextView? = null
    private var sleepPill: TextView? = null
    private var passagesCard: View? = null
    private var voiceSpinner: Spinner? = null
    private var speed = 1.0f
    private var sleepMinutes = 0
    private var sleepToken = 0

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            fab,
            LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(20), dp(24))
            },
        )
        fab.setOnClickListener { showCreator(null) }
        showLibrary()
    }

    // ── The page's contract with the launcher ────────────────────────────────

    /** Back from the player returns to the library. The library itself is the page's floor. */
    fun onBack(): Boolean {
        if (!creatorOpen) return false
        stopPlayback()
        showLibrary()
        return true
    }

    /**
     * AudioCraft keeps its view while something is audible or being made.
     *
     * Playing is the obvious half. The other is the run: generation is process-scoped and its progress
     * lives in this page's widgets, so swapping the page out mid-read would drop the only place the
     * user could see it.
     */
    override fun hasBackgroundWork(): Boolean =
        player?.isPlaying == true || creatorOpen || AudioCraft.state.value.running

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        watch?.cancel()
        watch = scope.launch {
            AudioCraft.state.collect { state -> renderRun(state) }
        }
        if (creatorOpen) {
            // A read that finished while this page was away has to appear where it belongs.
            val finished = AudioCraft.state.value.clipId
            if (finished.isNotEmpty()) {
                AudioCraft.clips().firstOrNull { it.id == finished }?.let { clip ->
                    if (playing == null) showCreator(clip)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        watch?.cancel()
        watch = null
        scope.cancel()
        importer.release()
        handler.removeCallbacksAndMessages(null)
        // The MediaPlayer is released rather than detached: a page that scrolled away is not
        // supposed to keep talking, and the instance cannot be reused afterwards.
        stopPlayback()
        super.onDetachedFromWindow()
    }

    // ── Library ──────────────────────────────────────────────────────────────

    private fun showLibrary() {
        creatorOpen = false
        content.removeAllViews()
        content.addView(header())

        val clips = AudioCraft.clips()
        val gate = AudioCraft.gate()
        content.addView(TextView(context).apply {
            text = if (gate.ready) {
                if (clips.isEmpty()) {
                    "Nothing recorded yet — press the button and give it something to read."
                } else {
                    "${clips.size} file${if (clips.size == 1) "" else "s"} · " +
                        gate.ofVoiceCount()
                }
            } else {
                gate.reason
            }
            textSize = 12f
            setTextColor(if (gate.ready) palette.muted.toInt() else palette.destructive.toInt())
            setPadding(dp(18), 0, dp(18), dp(8))
        })

        if (clips.isEmpty()) {
            content.addView(TextView(context).apply {
                text = "No audio files yet"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(palette.text.toInt())
                setPadding(dp(30), dp(60), dp(30), dp(30))
            })
        }

        libraryList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ClipAdapter(clips)
            setPadding(dp(12), 0, dp(12), dp(96))
            clipToPadding = false
        }
        content.addView(
            libraryList,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        fab.visibility = View.VISIBLE
    }

    private fun AudioCraft.Gate.ofVoiceCount(): String {
        val voices = AudioCraft.voiceCatalogue().size
        return "$voices voice${if (voices == 1) "" else "s"} available"
    }

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(12))
        }
        row.addView(TextView(context).apply {
            text = "AudioCraft"
            textSize = 26f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private inner class ClipAdapter(private val items: List<AudioClip>) :
        RecyclerView.Adapter<ClipAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(row())

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val clip = items[position]
            val card = holder.root as LinearLayout
            card.removeAllViews()

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cover = TextView(context).apply {
                text = clip.title.take(1).uppercase()
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(palette.onAccent.toInt())
                background = rounded(withAlpha(palette.accent.toInt(), 0.85f), 12)
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(56), dp(56)))
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            column.addView(TextView(context).apply {
                text = clip.title
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.text.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            column.addView(TextView(context).apply {
                text = buildString {
                    if (clip.voiceLabel.isNotBlank()) append(clip.voiceLabel).append(" · ")
                    append(clock(clip.durationSeconds))
                    append(" · ")
                    append(if (clip.source == "typed") "typed" else clip.source)
                }
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.muted.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            if (playing?.id == clip.id) {
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = playerProgress(clip)
                    progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(palette.divider.toInt())
                }
                column.addView(bar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(3),
                ).apply { topMargin = dp(6) })
            }
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val glyph = TextView(context).apply {
                text = if (playing?.id == clip.id && player?.isPlaying == true) "❚❚" else "▶"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(palette.accent.toInt())
                background = rounded(palette.raised.toInt(), 999)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            row.addView(glyph, LinearLayout.LayoutParams(dp(40), dp(40)))
            card.addView(row)
            card.setOnClickListener { showCreator(clip) }
            card.setOnLongClickListener {
                PrismDialogs.Builder(context)
                    .setTitle("Delete audio file")
                    .setMessage("Remove \"${clip.title}\" and its WAV?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (playing?.id == clip.id) stopPlayback()
                        AudioCraft.delete(clip)
                        showLibrary()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }

    private fun row(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card.toInt(), 16)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    // ── Player and creator ───────────────────────────────────────────────────

    private fun showCreator(clip: AudioClip?) {
        creatorOpen = true
        fab.visibility = View.GONE
        content.removeAllViews()
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        scroll.addView(column)
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Top bar: the collapse chevron an audiobook player has, and the page's own name.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(24), dp(16), dp(4))
        }
        bar.addView(TextView(context).apply {
            text = "⌄"
            textSize = 30f
            setTextColor(palette.text.toInt())
            setPadding(dp(6), 0, dp(14), dp(6))
            setOnClickListener {
                stopPlayback()
                showLibrary()
            }
        })
        bar.addView(TextView(context).apply {
            text = if (clip == null) "New audio file" else "Now playing"
            textSize = 18f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(bar)

        // Artwork: the cover the player is built around.
        artworkLabel = TextView(context).apply {
            text = (clip?.title ?: "AudioCraft").take(2).uppercase()
            textSize = 46f
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent.toInt())
            background = rounded(withAlpha(palette.accent.toInt(), 0.9f), 26)
        }
        column.addView(artworkLabel, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })

        titleLabel = TextView(context).apply {
            text = clip?.title ?: "Untitled"
            textSize = 20f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(24), dp(16), dp(24), dp(2))
        }
        column.addView(titleLabel)
        metaLabel = TextView(context).apply {
            text = clip?.let { clipMeta(it) } ?: "Nothing recorded yet"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.muted.toInt())
            setPadding(dp(24), 0, dp(24), dp(10))
        }
        column.addView(metaLabel)

        // Scrubber with elapsed and remaining, the pair an audiobook player shows.
        val seekRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        elapsedLabel = TextView(context).apply {
            text = "0:00"
            textSize = 11f
            setTextColor(palette.muted.toInt())
        }
        remainingLabel = TextView(context).apply {
            text = "-0:00"
            textSize = 11f
            setTextColor(palette.muted.toInt())
        }
        seekBar = SeekBar(context).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.divider.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val time = ((seekSpan() * progress / 1000f)).roundToInt()
                    elapsedLabel?.text = clock(time.toFloat())
                    remainingLabel?.text = "-" + clock((seekSpan() - time).coerceAtLeast(0f))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val time = (seekSpan() * (sb?.progress ?: 0) / 1000f).roundToInt()
                    runCatching { player?.seekTo(time) }
                }
            })
        }
        seekRow.addView(elapsedLabel)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(8), 0, dp(8), 0) })
        seekRow.addView(remainingLabel)
        column.addView(seekRow)

        // Transport: back fifteen, play, forward thirty -- the intervals Audible uses.
        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        transport.addView(pill("↺ 15").apply {
            setOnClickListener { skip(-15) }
        })
        playButton = TextView(context).apply {
            text = if (playing != null && player?.isPlaying == true) "❚❚" else "▶"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent.toInt())
            background = rounded(palette.accent.toInt(), 999)
            setOnClickListener { togglePlayback(clip) }
        }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })
        transport.addView(pill("30 ↻").apply {
            setOnClickListener { skip(30) }
        })
        column.addView(transport)

        val secondary = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        speedPill = pill("Speed ${trimSpeed(speed)}×").apply {
            setOnClickListener {
                speed = when (speed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    else -> 1.0f
                }
                text = "Speed ${trimSpeed(speed)}×"
                applySpeed()
            }
        }
        secondary.addView(speedPill)
        sleepPill = pill("Sleep off").apply {
            setOnClickListener {
                sleepMinutes = when (sleepMinutes) {
                    0 -> 5
                    5 -> 15
                    15 -> 30
                    else -> 0
                }
                text = if (sleepMinutes == 0) "Sleep off" else "Sleep $sleepMinutes min"
                armSleepTimer()
            }
        }
        (sleepPill?.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(8), 0, dp(8), 0)
        secondary.addView(sleepPill)
        secondary.addView(pill("Passages").apply {
            setOnClickListener {
                passagesCard?.visibility =
                    if (passagesCard?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        column.addView(secondary)

        passagesCard = passagesCard(clip)
        column.addView(passagesCard)

        column.addView(creationCard(clip))
        renderRun(AudioCraft.state.value)
    }

    /**
     * The passages of the script, as a chapter list.
     *
     * Cuts at the same places the read did -- [AudioCraft.scriptChunks] is the one implementation -- so
     * the list is the actual structure of the recording. Jumping seeks proportionally, which is exact
     * for a uniform reader and a second or two off for a text that varies in pace; the label says so
     * rather than pretending to be a cue sheet.
     */
    private fun passagesCard(clip: AudioClip?): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(12), dp(16), 0) }
        val chunks = clip?.let { AudioCraft.scriptChunks(it.text) }.orEmpty()
        card.addView(TextView(context).apply {
            text = if (chunks.isEmpty()) "No passages yet" else
                "${chunks.size} passage${if (chunks.size == 1) "" else "s"} — jumps are by position"
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        chunks.forEachIndexed { index, chunk ->
            card.addView(TextView(context).apply {
                text = "${index + 1}. ${chunk.take(90)}${if (chunk.length > 90) "…" else ""}"
                textSize = 13f
                setTextColor(palette.text.toInt())
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    val total = chunks.size.coerceAtLeast(1)
                    val fraction = index.toFloat() / total
                    val target = (seekSpan() * fraction).roundToInt()
                    runCatching { player?.seekTo(target) }
                    seekBar?.progress = (fraction * 1000).roundToInt()
                }
            })
        }
        return card
    }

    private fun creationCard(clip: AudioClip?): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(14), dp(16), 0) }

        card.addView(TextView(context).apply {
            text = "READ ALOUD"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(palette.faint.toInt())
            setPadding(0, 0, 0, dp(6))
        })

        // The voice, from the same catalogue a film casts from.
        voiceSpinner = spinner(AudioCraft.voiceCatalogue().map { it.label }) { position ->
            voiceIdAt(position)
        }
        card.addView(voiceSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ))

        promptField = EditText(context).apply {
            hint = "Type what should be read aloud, or import a document"
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            textSize = 14f
            minLines = 4
            maxLines = 12
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = rounded(palette.raised.toInt(), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (clip != null) setText(clip.title)
            setEnabled(!importedNames.any { true })
        }
        card.addView(promptField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        // The two icon buttons: import documents, and have the speech model read it.
        val icons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val import = iconButton(
            com.prism.launcher.R.drawable.ic_upload_file_24,
            "Import text or PDF files",
        ) { openImport() }
        val speak = iconButton(
            com.prism.launcher.R.drawable.ic_speaker_24,
            "Read it aloud with the speech model",
        ) { produce() }
        icons.addView(import, LinearLayout.LayoutParams(dp(48), dp(48)))
        icons.addView(speak, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })
        icons.addView(TextView(context).apply {
            text = "Import files · read aloud"
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(icons)

        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        card.addView(chipsRow)
        renderChips()

        card.addView(TextView(context).apply {
            text = "The document is the input while it is attached; the field is locked so there is " +
                "no doubt which words are read. Press ✕ to go back to typing."
            textSize = 10f
            setTextColor(palette.faint.toInt())
            setPadding(0, dp(6), 0, 0)
        })

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.divider.toInt())
        }
        card.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
        ).apply { topMargin = dp(10) })
        stageText = TextView(context).apply {
            text = ""
            textSize = 12f
            setTextColor(palette.muted.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(stageText)
        return card
    }

    private fun openImport() {
        val spec = AudioCraft.importMimeTypes
        importer.open(spec) { uris ->
            if (uris.isEmpty()) return@open
            val pieces = mutableListOf<String>()
            val names = mutableListOf<String>()
            for (uri in uris) {
                val name = ImportedText.name(context, uri)
                val text = ImportedText.read(context, uri)
                if (text.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        "$name had no readable text — a scanned PDF has none to read",
                        Toast.LENGTH_LONG,
                    ).show()
                    continue
                }
                pieces += text
                names += name
            }
            if (pieces.isEmpty()) return@open
            importedText = pieces.joinToString("\n\n")
            importedNames = names.toMutableList()
            sourceLabel = names.joinToString(", ")
            promptField?.apply {
                setEnabled(false)
                setText("")
                hint = "Using the imported document"
            }
            renderChips()
            stageText?.text = "Imported ${names.size} document${if (names.size == 1) "" else "s"} " +
                "— press the speaker to read it."
        }
    }

    private fun renderChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        if (importedNames.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        for (name in importedNames) {
            val chip = TextView(context).apply {
                text = name
                textSize = 11f
                setTextColor(palette.text.toInt())
                background = rounded(palette.raised.toInt(), 999)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) })
        }
        val clear = TextView(context).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.destructive.toInt())
            background = rounded(palette.raised.toInt(), 999)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                importedText = ""
                importedNames = mutableListOf()
                sourceLabel = "typed"
                promptField?.apply {
                    isEnabled = true
                    hint = "Type what should be read aloud, or import a document"
                }
                renderChips()
                stageText?.text = "Type the text to read, or import a document."
            }
        }
        row.addView(clear)
    }

    private fun produce() {
        val state = AudioCraft.state.value
        if (state.running) {
            AudioCraft.cancel()
            return
        }
        val text = if (importedText.isNotBlank()) importedText
        else promptField?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            stageText?.text = "Type something to read, or import a document first."
            return
        }
        val gate = AudioCraft.gate()
        if (!gate.ready) {
            stageText?.text = gate.reason
            return
        }
        AudioCraft.start(text, source = if (importedText.isNotBlank()) sourceLabel else "typed",
            voiceId = voiceIdAt(voiceSpinner?.selectedItemPosition ?: 0))
    }

    private fun renderRun(state: AudioCraft.State) {
        val bar = progressBar ?: return
        if (state.running) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = false
            bar.progress = (state.progress.coerceIn(0f, 1f) * 100).roundToInt()
            stageText?.text = state.stage
            playButton?.text = "■"
            return
        }
        bar.visibility = View.GONE
        playButton?.text = if (player?.isPlaying == true) "❚❚" else "▶"
        if (state.error != null) {
            stageText?.text = "Failed: ${state.error}"
        } else if (state.complete && state.clipId.isNotEmpty()) {
            stageText?.text = "Recorded ${clock(state.seconds)} " +
                "in ${"%.1f".format(state.millis / 1000.0)}s."
            AudioCraft.clips().firstOrNull { it.id == state.clipId }?.let { clip ->
                if (playing?.id != clip.id) {
                    titleLabel?.text = clip.title
                    metaLabel?.text = clipMeta(clip)
                    seekBar?.progress = 0
                }
            }
        } else if (state.stage.isNotEmpty()) {
            stageText?.text = state.stage
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun togglePlayback(clip: AudioClip?) {
        val state = AudioCraft.state.value
        if (state.running) {
            AudioCraft.cancel()
            return
        }
        val target = clip ?: playing
        if (target == null) {
            stageText?.text = "Pick a file to play, or record one."
            return
        }
        if (playing?.id == target.id && player?.isPlaying == true) {
            runCatching { player?.pause() }
            playButton?.text = "▶"
            return
        }
        if (playing?.id == target.id && player != null) {
            runCatching { player?.start() }
            playButton?.text = "❚❚"
            startTicker()
            return
        }
        load(target)
    }

    private fun load(clip: AudioClip) {
        stopPlayback()
        val file = File(clip.path)
        if (!file.isFile) {
            stageText?.text = "That file is missing from disk."
            return
        }
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    playButton?.text = "▶"
                    seekBar?.progress = 0
                    elapsedLabel?.text = "0:00"
                    remainingLabel?.text = "-" + clock(clip.durationSeconds)
                }
            }
        }.getOrNull()
        if (created == null) {
            stageText?.text = "The audio file could not be opened."
            return
        }
        player = created
        playing = clip
        runCatching { created.start() }
        applySpeed()
        playButton?.text = "❚❚"
        elapsedLabel?.text = "0:00"
        remainingLabel?.text = "-" + clock(clip.durationSeconds)
        seekBar?.progress = 0
        startTicker()
        armSleepTimer()
    }

    private fun stopPlayback() {
        handler.removeCallbacks(ticker)
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        playing = null
        playButton?.text = "▶"
    }

    private val ticker = object : Runnable {
        override fun run() {
            val active = player ?: return
            val position = runCatching { active.currentPosition }.getOrDefault(0)
            val span = seekSpan().toInt().coerceAtLeast(1)
            seekBar?.progress = (position * 1000 / span).coerceIn(0, 1000)
            elapsedLabel?.text = clock(position / 1000f)
            remainingLabel?.text = "-" + clock(((span - position).coerceAtLeast(0)) / 1000f)
            handler.postDelayed(this, 400)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** How long the scrubber is in milliseconds, from the loaded player or the clip's own length. */
    private fun seekSpan(): Float {
        val duration = runCatching { player?.duration ?: 0 }.getOrDefault(0)
        if (duration > 0) return duration.toFloat()
        return (playing?.durationSeconds ?: 0f) * 1000f
    }

    private fun skip(seconds: Int) {
        val active = player ?: return
        val position = runCatching { active.currentPosition }.getOrDefault(0)
        val span = seekSpan().toInt().coerceAtLeast(0)
        runCatching { active.seekTo((position + seconds * 1000).coerceIn(0, span)) }
    }

    private fun applySpeed() {
        val active = player ?: return
        runCatching {
            active.playbackParams = active.playbackParams.setSpeed(speed)
        }.onFailure {
            // Older devices without playback parameters simply stay at 1x; the pill still shows the
            // chosen speed so the control is not a silent no-op for the user.
        }
    }

    private fun armSleepTimer() {
        sleepToken++
        val token = sleepToken
        handler.removeCallbacksAndMessages(SLEEP_MESSAGE)
        if (sleepMinutes <= 0) return
        handler.postAtTime({
            if (token != sleepToken) return@postAtTime
            runCatching { player?.pause() }
            playButton?.text = "▶"
            stageText?.text = "Sleep timer stopped playback after $sleepMinutes minutes."
            sleepMinutes = 0
            sleepPill?.text = "Sleep off"
        }, SLEEP_MESSAGE, android.os.SystemClock.uptimeMillis() + sleepMinutes * 60_000L)
    }

    // ── Small widgets ────────────────────────────────────────────────────────

    private fun clipMeta(clip: AudioClip): String = buildString {
        if (clip.voiceLabel.isNotBlank()) append(clip.voiceLabel).append(" · ")
        append(clock(clip.durationSeconds))
        append(" · ")
        append(if (clip.source == "typed") "typed" else clip.source)
    }

    private fun playerProgress(clip: AudioClip): Int {
        if (playing?.id != clip.id) return 0
        val span = seekSpan().coerceAtLeast(1f)
        val position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        return (position * 1000 / span).roundToInt().coerceIn(0, 1000)
    }

    private fun voiceIdAt(position: Int): String =
        AudioCraft.voiceCatalogue().getOrNull(position)?.id.orEmpty()

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            background = rounded(palette.raised.toInt(), 14)
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, icon)
            setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
            compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            gravity = Gravity.CENTER
            contentDescription = description
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    private fun pill(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(palette.text.toInt())
        background = rounded(palette.raised.toInt(), 999)
        isClickable = true
        isFocusable = true
    }

    private fun spinner(labels: List<String>, onSelect: (Int) -> Unit): Spinner {
        val safe = labels.ifEmpty { listOf("No voices available") }
        return Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item, safe,
            ) {
                private fun row(position: Int, convert: View?): View {
                    val view = convert as? TextView ?: TextView(context).apply {
                        textSize = 13f
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                    }
                    view.text = safe[position]
                    view.setTextColor(palette.text.toInt())
                    view.setBackgroundColor(palette.raised.toInt())
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    row(position, convertView)

                override fun getDropDownView(
                    position: Int, convertView: View?, parent: ViewGroup,
                ): View = row(position, convertView)
            }
            dropDownWidth = dp(240)
            if (labels.isNotEmpty()) setSelection(0)
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
                ) = onSelect(position)

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
            background = rounded(palette.raised.toInt(), 10)
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = ((color ushr 24) * fraction).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun clock(seconds: Float): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun trimSpeed(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val SLEEP_MESSAGE = Any()
    }
}
