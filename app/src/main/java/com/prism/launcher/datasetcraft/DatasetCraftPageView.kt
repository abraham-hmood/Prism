package com.prism.launcher.datasetcraft

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.media.DocumentImport
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DatasetCraft as a page of the launcher.
 *
 * ## The two layouts
 *
 * A LIST of the datasets the user has made, and the CREATOR behind it: what kind of dataset it is (a
 * real [RadioGroup]), a name and a description, the columns, and the rows themselves -- each row a
 * file in the dataset's own folder or a URL waiting to be fetched.
 *
 * ## Why every edit writes
 *
 * The CSV on disk IS the dataset ([DatasetCraft.save]), so an edit is saved as it is made rather than on
 * a Save button. A page that is swiped away mid-edit therefore loses nothing, and a dataset can be
 * handed to ModelCraft the moment the last row is typed.
 *
 * ## What is on the phone and what is not
 *
 * The Android picker hands back document URIs, so an import is a multi-file pick of the kind's own
 * media; each file is copied into the dataset's folder, which is what makes the dataset portable.
 * Picking a whole FOLDER is a desktop affordance -- the desktop page has the directory chooser.
 */
class DatasetCraftPageView(context: Context) : FrameLayout(context), com.prism.launcher.offload.BusyPage {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
        contentDescription = "Create a new dataset"
    }

    private var creatorOpen = false
    private var dataset: DatasetCraft.CraftedDataset? = null
    private var kind: DatasetCraft.Kind = DatasetCraft.Kind.TEXT
    private var nameField: EditText? = null
    private var descriptionField: EditText? = null
    private var statusView: TextView? = null
    private var rowsColumn: LinearLayout? = null
    private var columnsRow: LinearLayout? = null
    private var autoFillBox: CheckBox? = null
    private var autoFillSection: View? = null
    private var audioFillJob: Job? = null
    private var audioFillPending = false
    private var audioFillPendingForce = false

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            fab,
            LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(20), dp(24))
            },
        )
        fab.setOnClickListener { newDataset() }
        showList()
    }

    /** Back inside a dataset returns to the library, like ModelCraft's creator does. */
    fun onBack(): Boolean {
        if (!creatorOpen) return false
        persist()
        creatorOpen = false
        showList()
        return true
    }

    override fun hasBackgroundWork(): Boolean = creatorOpen

    override fun onDetachedFromWindow() {
        scope.cancel()
        importer.release()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    // ── The library ──────────────────────────────────────────────────────────

    private fun showList() {
        audioFillJob?.cancel()
        audioFillJob = null
        audioFillPending = false
        audioFillPendingForce = false
        creatorOpen = false
        dataset = null
        autoFillBox = null
        autoFillSection = null
        content.removeAllViews()
        content.addView(
            TextView(context).apply {
                text = "DatasetCraft"
                textSize = 26f
                setTextColor(palette.text.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(18), dp(28), dp(18), dp(12))
            },
        )

        val datasets = DatasetCraft.all()
        content.addView(
            TextView(context).apply {
                text = if (datasets.isEmpty()) {
                    "No datasets yet — press the button to make one. A dataset is a CSV of files " +
                        "(or of URLs) plus the files themselves, which is what a trainer reads."
                } else {
                    "${datasets.size} dataset${if (datasets.size == 1) "" else "s"} · " +
                        "each one is a CSV in Prism's data folder"
                }
                textSize = 12f
                setTextColor(palette.muted.toInt())
                setPadding(dp(18), 0, dp(18), dp(8))
            },
        )

        content.addView(
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = DatasetAdapter(datasets)
                setPadding(dp(12), 0, dp(12), dp(96))
                clipToPadding = false
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        fab.visibility = View.VISIBLE
    }

    private inner class DatasetAdapter(private val items: List<DatasetCraft.CraftedDataset>) :
        RecyclerView.Adapter<DatasetAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(card())

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val card = holder.root as LinearLayout
            card.removeAllViews()
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                TextView(context).apply {
                    text = item.kind.label
                    textSize = 10f
                    setTextColor(palette.onAccent.toInt())
                    background = rounded(palette.accent.toInt(), 999)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                },
            )
            top.addView(
                TextView(context).apply {
                    text = "${item.rowCount} row${if (item.rowCount == 1) "" else "s"}"
                    textSize = 11f
                    setTextColor(palette.muted.toInt())
                    setPadding(dp(8), 0, 0, 0)
                },
            )
            card.addView(top)
            card.addView(
                TextView(context).apply {
                    text = item.name
                    textSize = 16f
                    setTextColor(palette.text.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(8), 0, dp(2))
                },
            )
            card.addView(
                TextView(context).apply {
                    text = buildString {
                        append(item.columns.joinToString(" · "))
                        if (item.remoteRows > 0) append("\n").append("${item.remoteRows} row(s) to download")
                        if (item.note.isNotBlank()) append("\n").append(item.note)
                    }
                    textSize = 12f
                    setTextColor(palette.muted.toInt())
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            card.setOnClickListener { openCreator(item) }
            card.setOnLongClickListener {
                PrismDialogs.Builder(context)
                    .setTitle("Delete dataset")
                    .setMessage("Remove \"${item.name}\" and every file it holds?")
                    .setPositiveButton("Delete") { _, _ ->
                        DatasetCraft.delete(item)
                        showList()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }

    private fun card(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card.toInt(), 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    // ── The creator ──────────────────────────────────────────────────────────

    private fun newDataset() {
        // The kind is asked for first: it decides the columns the dataset starts with, so it cannot be
        // changed afterwards without the rows meaning something else.
        val kinds = DatasetCraft.Kind.entries.map { it.label }.toTypedArray()
        PrismDialogs.Builder(context)
            .setTitle("What is this dataset about?")
            .setItems(kinds) { _, which ->
                val chosen = DatasetCraft.Kind.entries[which]
                openCreator(DatasetCraft.create("", chosen))
            }
            .show()
    }

    private fun openCreator(target: DatasetCraft.CraftedDataset) {
        creatorOpen = true
        dataset = target
        kind = target.kind
        fab.visibility = View.GONE
        content.removeAllViews()

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(96))
        }
        scroll.addView(column)
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        column.addView(
            TextView(context).apply {
                text = "Dataset"
                textSize = 24f
                setTextColor(palette.text.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )

        // The kind: a real radio group, because there are five and none is a default the user would
        // thank us for.
        column.addView(section("WHAT KIND OF DATA"))
        val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        DatasetCraft.Kind.entries.forEachIndexed { index, option ->
            group.addView(
                RadioButton(context).apply {
                    id = RADIO_ID_BASE + index
                    text = "${option.label} — ${option.note}"
                    textSize = 13f
                    setTextColor(palette.text.toInt())
                    isChecked = option == kind
                    setPadding(0, dp(6), 0, dp(6))
                },
            )
        }
        group.setOnCheckedChangeListener { _, checked ->
            DatasetCraft.Kind.entries.getOrNull(checked - RADIO_ID_BASE)?.let { chosen ->
                if (chosen != kind) {
                    // Changing the kind changes what the columns mean, so the columns the new kind
                    // wants are added rather than the old ones removed: a caption typed for an image
                    // set is still a caption if the set turns out to hold audio.
                    kind = chosen
                    target.kind = chosen
                    // A set that has just become speech gets automatic transcription: that is the
                    // moment its rows stopped being files and became training examples with no
                    // words in them yet.
                    target.autoFillAudio = chosen.autoTranscribes
                    DatasetCraft.save(target)
                    for (name in chosen.columns) DatasetCraft.addColumn(target, name)
                    autoFillBox?.isChecked = target.autoFillAudio
                    autoFillSection?.visibility = if (chosen.autoTranscribes) View.VISIBLE else View.GONE
                    render()
                    if (chosen.autoTranscribes) runAudioFill(target)
                }
            }
        }
        column.addView(group)

        // AUTO TRANSCRIPTION. A folder of takes has no words in it until something hears them,
        // and a trainer reads the CSV rather than discovering text files beside the clips. The
        // feature is here, under the dataset, for BOTH kinds that carry speech: an audio clip's
        // words and a video's audio track are the same problem to this page.
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (target.kind.autoTranscribes) View.VISIBLE else View.GONE
                addView(section("AUTO TRANSCRIPTION"))
                addView(
                    CheckBox(context).apply {
                        text = "Transcribe files that have no text"
                        textSize = 13f
                        setTextColor(palette.text.toInt())
                        isChecked = target.autoFillAudio
                        setOnCheckedChangeListener { _, checked ->
                            if (target.autoFillAudio != checked) {
                                target.autoFillAudio = checked
                                DatasetCraft.save(target)
                                if (checked) runAudioFill(target)
                            }
                        }
                        autoFillBox = this
                    },
                )
                addView(
                    TextView(context).apply {
                        text = "Audio files are heard with the transcription engine; a video's " +
                            "audio track is used the same way. The first run downloads a 142 MB " +
                            "model once. Only blank text is filled, so a typed transcript is " +
                            "never replaced."
                        textSize = 12f
                        setTextColor(palette.muted.toInt())
                        setPadding(0, 0, 0, dp(8))
                    },
                )
                addView(
                    row(
                        pill("Transcribe missing now", accent = false) {
                            runAudioFill(target, force = true)
                        },
                    ),
                )
                autoFillSection = this
            },
        )

        column.addView(section("NAME"))
        nameField = editor(target.name, "What this dataset is called") { value ->
            DatasetCraft.rename(target, value)
        }
        column.addView(nameField)

        column.addView(section("WHAT IT IS FOR"))
        descriptionField = editor(target.description, "One line about where this data came from") { value ->
            target.description = value
            DatasetCraft.save(target)
        }
        column.addView(descriptionField)

        column.addView(section("COLUMNS"))
        columnsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        column.addView(columnsRow)

        column.addView(section("FILES"))
        val importLabel = if (kind == DatasetCraft.Kind.GAME) {
            "Import game asset files"
        } else {
            "Import ${kind.label.lowercase()} files"
        }
        column.addView(
            row(
                pill(importLabel, accent = true) { importInto(target) },
                pill("Add a row from a URL") { askUrl(target) },
            ),
        )
        column.addView(
            row(
                pill("Download the rows that are URLs") { download(target) },
                pill("Add a column") { askColumn(target) },
            ),
        )

        column.addView(section("ROWS"))
        rowsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rowsColumn)

        statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.muted.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(statusView)

        render()
        // A checked dataset is an instruction that outlives this page: opening it again finishes
        // any rows added since, without the user having to tick the box a second time.
        if (target.autoFillAudio) runAudioFill(dataset ?: target)
    }

    /** Redraws the parts that change as rows and columns do, without rebuilding the whole form. */
    private fun render() {
        val target = dataset ?: return
        // While the listener is running it owns the dataset object, and re-reading the CSV here
        // would hand the row editors a second copy that the run's final write could overwrite.
        val actual = if (audioFillJob?.isActive == true) target else DatasetCraft.find(target.id) ?: target
        dataset = actual

        columnsRow?.let { row ->
            row.removeAllViews()
            for (name in actual.columns) {
                row.addView(
                    TextView(context).apply {
                        text = name
                        textSize = 12f
                        setTextColor(palette.text.toInt())
                        background = rounded(palette.raised.toInt(), 999)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        params.rightMargin = dp(8)
                        layoutParams = params
                        setOnLongClickListener {
                            if (name.equals(DatasetCraft.PATH_COLUMN, true)) {
                                Toast.makeText(
                                    context,
                                    "Every row needs its ${DatasetCraft.PATH_COLUMN} column",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                PrismDialogs.Builder(context)
                                    .setTitle("Remove the $name column?")
                                    .setMessage("Its values go with it; the files stay.")
                                    .setPositiveButton("Remove") { _, _ ->
                                        DatasetCraft.removeColumn(actual, name)
                                        render()
                                    }
                                    .setNegativeButton("Keep", null)
                                    .show()
                            }
                            true
                        }
                    },
                )
            }
        }

        rowsColumn?.let { column ->
            column.removeAllViews()
            if (actual.rows.isEmpty()) {
                column.addView(
                    TextView(context).apply {
                        text = "No rows yet. Import the files, or add a row whose file is a URL."
                        textSize = 13f
                        setTextColor(palette.muted.toInt())
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )
            }
            actual.rows.forEach { row -> column.addView(rowCard(actual, row)) }
        }

        statusView?.text = actual.summary()
    }

    /**
     * One row: its file, then a box per other column.
     *
     * The values are edited in place and written on focus loss, so a row is never half-saved and the
     * CSV the trainer reads is the CSV on screen.
     */
    private fun rowCard(target: DatasetCraft.CraftedDataset, entry: DatasetCraft.Row): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(context).apply {
                text = entry.path
                textSize = 13f
                setTextColor(if (entry.isUrl) palette.accent.toInt() else palette.text.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            TextView(context).apply {
                text = "⋯"
                textSize = 18f
                setTextColor(palette.muted.toInt())
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener {
                    PrismDialogs.Builder(context)
                        .setTitle(entry.path)
                        .setItems(arrayOf("Move up", "Move down", "Delete row")) { _, which ->
                            when (which) {
                                0 -> DatasetCraft.moveRow(target, entry, -1)
                                1 -> DatasetCraft.moveRow(target, entry, 1)
                                else -> DatasetCraft.removeRow(target, entry)
                            }
                            render()
                        }
                        .show()
                }
            },
        )
        card.addView(head)

        for (column in target.columns.filterNot { it.equals(DatasetCraft.PATH_COLUMN, true) }) {
            card.addView(
                EditText(context).apply {
                    hint = column
                    textSize = 13f
                    setTextColor(palette.text.toInt())
                    setHintTextColor(palette.faint.toInt())
                    background = rounded(palette.raised.toInt(), 10)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setText(entry.value(column))
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) DatasetCraft.setValue(target, entry, column, text.toString())
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    params.topMargin = dp(6)
                    layoutParams = params
                },
            )
        }
        return card
    }

    // ── The actions ──────────────────────────────────────────────────────────

    private fun importInto(target: DatasetCraft.CraftedDataset) {
        importer.open(mimeTypes(target.kind)) { uris ->
            if (uris.isEmpty()) return@open
            scope.launch(Dispatchers.IO) {
                val staging = File(context.cacheDir, "datasetcraft-import").apply { mkdirs() }
                val staged = mutableListOf<File>()
                for (uri: Uri in uris) copyDocument(uri, staging)?.let { staged += it }
                val rows = DatasetCraft.importFiles(target, staged)
                staging.deleteRecursively()
                launch(Dispatchers.Main) {
                    render()
                    Toast.makeText(
                        context,
                        "$rows row${if (rows == 1) "" else "s"} imported",
                        Toast.LENGTH_SHORT,
                    ).show()
                    val current = DatasetCraft.find(target.id) ?: target
                    // IMPORTING SPEECH IS THE MOMENT ITS TRANSCRIPTS ARE MADE, whatever the
                    // standing preference says: the rows exist now, they have no words, and a
                    // trainer reads the CSV rather than discovering text beside each clip.
                    if (current.kind.autoTranscribes) runAudioFill(current, force = true)
                }
            }
        }
    }

    private fun askUrl(target: DatasetCraft.CraftedDataset) {
        val box = EditText(context).apply {
            hint = "https://…/clip.wav"
            textSize = 14f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
        }
        PrismDialogs.Builder(context)
            .setTitle("Add a row whose file is a URL")
            .setMessage("It downloads into the dataset folder, named as the link names it.")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val url = box.text.toString().trim()
                if (DatasetCraft.addUrlRow(target, url) == null) {
                    Toast.makeText(context, "That is not an http(s) URL", Toast.LENGTH_SHORT).show()
                } else if (target.autoFillAudio) {
                    // A URL row has nothing to listen to until its bytes are here; downloading and
                    // then filling keeps a checked dataset from silently collecting empty rows.
                    download(target)
                } else {
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askColumn(target: DatasetCraft.CraftedDataset) {
        val box = EditText(context).apply {
            hint = "speaker"
            textSize = 14f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
        }
        PrismDialogs.Builder(context)
            .setTitle("Add a column")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                if (!DatasetCraft.addColumn(target, box.text.toString())) {
                    Toast.makeText(context, "That column is already there", Toast.LENGTH_SHORT).show()
                } else {
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(target: DatasetCraft.CraftedDataset) {
        if (target.remoteRows == 0) {
            Toast.makeText(context, "No row names a URL", Toast.LENGTH_SHORT).show()
            return
        }
        statusView?.text = "Downloading ${target.remoteRows} file(s)…"
        scope.launch(Dispatchers.IO) {
            val fetched = DatasetCraft.materialise(target.folder)
            launch(Dispatchers.Main) {
                target.note = "$fetched file(s) downloaded"
                DatasetCraft.save(target)
                render()
                Toast.makeText(context, "$fetched file(s) downloaded", Toast.LENGTH_SHORT).show()
                val current = DatasetCraft.find(target.id) ?: target
                if (current.kind.autoTranscribes) runAudioFill(current, force = true)
            }
        }
    }

    /**
     * Fills the audio columns for [target] in the background.
     *
     * Transcription is the slow part -- one whisper pass per clip -- so it never runs on the UI
     * thread. A request that arrives while one is running is remembered rather than started twice:
     * an import that finishes mid-run still gets its new rows listened to, and two passes cannot
     * write the same CSV at once.
     */
    private fun runAudioFill(target: DatasetCraft.CraftedDataset, force: Boolean = false) {
        if (!target.kind.autoTranscribes) return
        if (!force && !target.autoFillAudio) return
        if (audioFillJob?.isActive == true) {
            audioFillPending = true
            // An import that lands mid-pass still gets transcribed even when the standing
            // preference is off: force is remembered until the run it was waiting for ends.
            audioFillPendingForce = audioFillPendingForce || force
            return
        }
        statusView?.text = "Listening to the clips…"
        audioFillJob = scope.launch(Dispatchers.IO) {
            val result = AudioAutofill.fill(target) { line, fraction ->
                scope.launch(Dispatchers.Main) {
                    // A download already carries its own percentage; a per-file stage does not.
                    statusView?.text = if (fraction > 0f) {
                        "$line ${(fraction * 100).toInt()}%"
                    } else {
                        line
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val current = DatasetCraft.find(target.id) ?: target
                current.note = result.summary
                DatasetCraft.save(current)
                // Clear before drawing: render() re-reads the CSV unless the run still owns the
                // object, and the note and every filled cell are already in it.
                audioFillJob = null
                render()
                Toast.makeText(context, result.summary, Toast.LENGTH_SHORT).show()
                if (audioFillPending) {
                    audioFillPending = false
                    val pendingForce = audioFillPendingForce
                    audioFillPendingForce = false
                    runAudioFill(DatasetCraft.find(current.id) ?: current, force = pendingForce)
                }
            }
        }
    }

    private fun persist() {
        val target = dataset ?: return
        nameField?.text?.toString()?.let { if (it.isNotBlank()) DatasetCraft.rename(target, it) }
        descriptionField?.text?.toString()?.let { value ->
            target.description = value
            DatasetCraft.save(target)
        }
    }

    private fun copyDocument(uri: Uri, target: File): File? = runCatching {
        val name = com.prism.launcher.media.ImportedText.name(context, uri)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        // The game kind classifies by where a file sits: an FBX under Animations is a clip, one
        // under Meshes is a model, and a PNG under Spectrograms is audio rather than texture. The
        // picker returns URIs, whose document id still carries the folder path; keeping the
        // immediate parent in the staging path is enough to preserve that context until the
        // descriptor has been read.
        val parentHint = documentParentHint(uri)
        val directory = if (parentHint.isBlank()) target else File(target, parentHint).apply { mkdirs() }
        val file = File(directory, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    /** The immediate folder name from a document URI, sanitized, or blank when unavailable. */
    private fun documentParentHint(uri: Uri): String = runCatching {
        val docId = android.provider.DocumentsContract.getDocumentId(uri)
        val relative = docId.substringAfter(':', "")
        val parent = relative.substringBeforeLast('/', "")
        val name = parent.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40)
        if (name == "." || name == "..") "" else name
    }.getOrDefault("")

    private fun mimeTypes(kind: DatasetCraft.Kind): Array<String> = when (kind) {
        DatasetCraft.Kind.TEXT -> arrayOf("text/*", "application/json", "application/octet-stream")
        DatasetCraft.Kind.IMAGE -> arrayOf("image/*")
        DatasetCraft.Kind.AUDIO -> arrayOf("audio/*")
        DatasetCraft.Kind.VIDEO -> arrayOf("video/*")
        // A game dataset holds mesh, image, audio and text files at once, and several of its
        // formats have no registered MIME type at all. Filtering the picker would hide them.
        DatasetCraft.Kind.GAME -> arrayOf("*/*")
    }

    // ── Small widgets ────────────────────────────────────────────────────────

    private fun section(title: String): View = TextView(context).apply {
        text = title
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(palette.faint.toInt())
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun editor(initial: String, hint: String, onEdit: (String) -> Unit): EditText =
        EditText(context).apply {
            setText(initial)
            this.hint = hint
            textSize = 14f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            background = rounded(palette.raised.toInt(), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onEdit(text.toString()) }
        }

    private fun row(vararg views: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { view ->
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.rightMargin = dp(8)
            addView(view, params)
        }
    }

    private fun pill(text: String, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(if (accent) palette.onAccent.toInt() else palette.text.toInt())
            background = rounded(if (accent) palette.accent.toInt() else palette.raised.toInt(), 999)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Radio-button ids, offset so they cannot collide with a generated view id. */
        const val RADIO_ID_BASE = 2000
    }
}
