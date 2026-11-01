package com.prism.launcher.modelcraft

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.core.PrismPlatform
import com.prism.launcher.datasetcraft.DatasetReferences
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
 * ModelCraft as a page of the launcher.
 *
 * ## The two layouts
 *
 * A LIST of the models the user has made -- name, kind, architecture, status -- with the create button
 * floating over the corner. Tapping a row, or the button, opens the CREATOR, which is one form with a
 * tab per kind of model.
 *
 * ## Why the form is drawn from data
 *
 * Every tab's fields come from [ModelCraft.fields], its architecture families from
 * [ModelCraft.archTypes], and what it imports from [ModelCraft.datasets]. The page holds no list of
 * its own, so a field added to the recipe appears on both platforms' forms in the same order, and the
 * recipe a trainer reads can never disagree with the form that filled it.
 *
 * ## What training does here
 *
 * The button prepares the dataset for real -- imports are copied, CSVs parsed, video frames extracted
 * -- writes the recipe, and reports where it went. Whether a run follows is the platform's business
 * (see [ModelCraft.runner]); this page shows what happened either way rather than a progress bar that
 * moves because a timer says so.
 */
class ModelCraftPageView(context: Context) : FrameLayout(context), com.prism.launcher.offload.BusyPage {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null

    /**
     * Whether Android's sustained-performance hint is currently on for this window.
     *
     * Kept beside the scope rather than read back from the window: `renderRun` is called on every
     * progress emission, and setting the hint again on each one would be a binder call several
     * times a second for a value that only changes at the start and end of a run.
     */
    private var sustainedPerformance = false
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
        contentDescription = "Create a new model"
    }

    private var list: RecyclerView? = null
    private var creatorOpen = false
    private var model: CraftedModel? = null
    private var kind: ModelCraft.Kind = ModelCraft.Kind.TEXT
    private var formColumn: LinearLayout? = null
    private var tabsRow: LinearLayout? = null
    private var fieldEditors = linkedMapOf<String, EditText>()

    /**
     * The dropdowns, by recipe key.
     *
     * Separate from [fieldEditors] because there is no text to read: the selection IS the value, and
     * a Spinner cannot stand in for an EditText in the maps the rest of the form iterates.
     */
    private var fieldChoices = linkedMapOf<String, Spinner>()
    private var nameField: EditText? = null
    private var archSpinner: Spinner? = null
    private var familySpinner: Spinner? = null
    private var datasetSummaryView: TextView? = null
    private var stageView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var trainButton: TextView? = null
    private var validationView: TextView? = null
    // The controls that are not plain recipe fields: a checkbox that turns two boxes on, and a
    // radio group whose second answer adds a required one. Held as views because what is typed has
    // to be read back by [persistForm] like every other box on the form.
    private var attentionCheck: android.widget.CheckBox? = null
    private var windowEditor: EditText? = null
    private var nthEditor: EditText? = null
    private var audioTypeGroup: android.widget.RadioGroup? = null
    private var voiceEditor: EditText? = null
    private var voiceBlock: View? = null
    private var speechBlock: View? = null
    private var musicBlock: View? = null
    // Off unless ticked: the miniature renderer does not speak the words.
    private var miniatureCheck: android.widget.CheckBox? = null
    // The speech engine switches. The dataset engine is built from this model's own recordings;
    // Pocket TTS is the optional multilingual fallback, not an invisible default.
    private var engineCheck: android.widget.CheckBox? = null
    private var pocketCheck: android.widget.CheckBox? = null
    // The optional pretrained voices: Kokoro is a switch, and a custom voice is a checkbox plus
    // the folder importer it reveals. Both are off unless the user asks for them.
    private var kokoroCheck: android.widget.CheckBox? = null
    private var customCheck: android.widget.CheckBox? = null
    // The Neural Voice tab is a workshop of its own, not a seventh model kind: it edits the current
    // audio model's voice and can be opened from any model without changing what is being trained.
    private var neuralTab = false
    private var neuralSettingsEditors = linkedMapOf<String, EditText>()
    private var neuralStatusView: TextView? = null
    private var neuralVoiceStrip: LinearLayout? = null
    private var neuralTestInput: EditText? = null
    private var neuralTestVoice: String? = null
    // True while the pre-training voice pass is running, and true when the dialog it opens should
    // start training on OK. Both are UI state: the extraction itself lives in ModelCraft.
    private var voicesPreparing = false
    // The two capacity boxes, for every kind: the count the model is asked to build and the token
    // limit it is asked to hold. The context box follows sliding attention.
    private var parameterEditor: EditText? = null
    private var contextEditor: EditText? = null
    // What those two boxes showed when the form was built, so a value the user did not touch is
    // not written back over what switching the attention checkbox just computed.
    private var parameterShown: String = ""
    private var contextShown: String = ""
    // The capacity line under the two boxes; it changes when the window does.
    private var capacityView: TextView? = null
    // The two creation switches, for every kind. Held as views for the same reason the attention
    // checkbox is: what is ticked has to be read back by [persistForm] like every other control.
    private var scratchCheck: android.widget.CheckBox? = null
    private var localCheck: android.widget.CheckBox? = null
    // And the one control about what the run PRODUCES: the container the weights are written in.
    private var formatGroup: android.widget.RadioGroup? = null
    private var exportButton: TextView? = null
    private var voiceButton: TextView? = null
    // The voices dialog: opened by its button, and before a text-to-speech run starts.
    private var voiceDialog: android.app.Dialog? = null
    // The two schedule boxes, present for every kind: passes over the dataset and examples per pass.
    private var scheduleEpochsEditor: EditText? = null
    private var scheduleStepsEditor: EditText? = null
    // The field cells by key, so the from-scratch switch can grey the base-only ones without
    // rebuilding the form.
    private var fieldCells = linkedMapOf<String, LinearLayout>()
    // Trying the trained model: the result view, the box under it, and the controls' own state.
    private var testOutputHolder: LinearLayout? = null
    private var testInput: EditText? = null
    private var testButton: TextView? = null
    private var testCaption: TextView? = null
    private var testHintView: TextView? = null
    // The voice chips above the waveform, and which packaged voice the next test speaks in.
    private var voiceStripHolder: LinearLayout? = null
    private var testVoice: String? = null
    private var testAnimation: Runnable? = null
    // The live audio-training graph, created once per opened form and reused by every progress
    // frame. Held here so a redraw is an update rather than a new view tree twelve times a second.
    private var trainingGraph: VoiceTrainingGraphView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var testRun = 0

    init {
        AndroidModelCraft.install()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            fab,
            LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(20), dp(24))
            },
        )
        fab.setOnClickListener { newModel() }
        showList()
    }

    fun onBack(): Boolean {
        if (!creatorOpen) return false
        persistForm()
        creatorOpen = false
        showList()
        return true
    }

    override fun hasBackgroundWork(): Boolean =
        creatorOpen || ModelCraft.state.value.running

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        watch?.cancel()
        watch = scope.launch {
            ModelCraft.state.collect { state -> renderRun(state) }
        }
    }

    override fun onDetachedFromWindow() {
        // Leaving the page while a run is still going would otherwise leave the window capped for
        // whatever screen is next. The run itself continues; only the clock hint is withdrawn.
        if (sustainedPerformance) {
            sustainedPerformance = false
            com.prism.launcher.platform.AndroidThermalGovernor.setSustainedPerformance(
                context as? android.app.Activity, false,
            )
        }
        watch?.cancel()
        watch = null
        stopTestAnimation()
        scope.cancel()
        importer.release()
        super.onDetachedFromWindow()
    }

    // ── List ─────────────────────────────────────────────────────────────────

    private fun showList() {
        creatorOpen = false
        model = null
        // The workshop belongs to the model that was open, never to the next one.
        neuralTab = false
        voiceDialog?.dismiss()
        voiceDialog = null
        stopTestAnimation()
        testOutputHolder = null
        testInput = null
        testButton = null
        testCaption = null
        testHintView = null
        trainingGraph = null
        voiceStripHolder = null
        testVoice = null
        content.removeAllViews()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(12))
        }
        header.addView(TextView(context).apply {
            text = "ModelCraft"
            textSize = 26f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        val models = ModelCraft.models()
        content.addView(TextView(context).apply {
            text = if (models.isEmpty()) {
                "Nothing built yet — press the button to make a text, image, audio, interpolator " +
                    "or video model."
            } else {
                "${models.size} model${if (models.size == 1) "" else "s"} · datasets live under " +
                    "Documents/ModelCraft"
            }
            textSize = 12f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), 0, dp(18), dp(8))
        })

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ModelAdapter(models)
            setPadding(dp(12), 0, dp(12), dp(96))
            clipToPadding = false
        }
        content.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fab.visibility = View.VISIBLE
    }

    private inner class ModelAdapter(private val items: List<CraftedModel>) :
        RecyclerView.Adapter<ModelAdapter.VH>() {

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
            top.addView(TextView(context).apply {
                text = item.kind.label
                textSize = 10f
                setTextColor(palette.onAccent.toInt())
                background = rounded(palette.accent.toInt(), 999)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
            top.addView(TextView(context).apply {
                text = statusLine(item)
                textSize = 11f
                setTextColor(statusColor(item.status))
                setPadding(dp(8), 0, 0, 0)
            })
            card.addView(top)
            card.addView(TextView(context).apply {
                text = item.name
                textSize = 16f
                setTextColor(palette.text.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            card.addView(TextView(context).apply {
                text = buildString {
                    append(item.architecture)
                    if (item.family.isNotBlank()) append(" · ").append(item.family)
                    if (item.datasetSummary.isNotBlank()) append("\n").append(item.datasetSummary)
                }
                textSize = 12f
                setTextColor(palette.muted.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.setOnClickListener { openCreator(item) }
            card.setOnLongClickListener {
                PrismDialogs.Builder(context)
                    .setTitle("Delete model")
                    .setMessage("Remove \"${item.name}\" and its imported dataset?")
                    .setPositiveButton("Delete") { _, _ ->
                        ModelCraft.delete(item)
                        showList()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
                true
            }
        }
    }

    private fun statusLine(model: CraftedModel): String = when (model.status) {
        ModelCraft.STATUS_TRAINED -> "trained"
        ModelCraft.STATUS_READY -> "recipe ready"
        ModelCraft.STATUS_TRAINING -> "preparing"
        ModelCraft.STATUS_FAILED -> "failed"
        else -> "draft"
    }

    private fun statusColor(status: String): Int = when (status) {
        ModelCraft.STATUS_TRAINED -> palette.accent.toInt()
        ModelCraft.STATUS_FAILED -> palette.destructive.toInt()
        else -> palette.muted.toInt()
    }

    private fun card(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card.toInt(), 16)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    // ── Creator ──────────────────────────────────────────────────────────────

    private fun newModel() {
        val created = ModelCraft.create(
            name = "",
            kind = ModelCraft.Kind.TEXT,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        openCreator(created)
    }

    private fun openCreator(target: CraftedModel) {
        creatorOpen = true
        model = target
        neuralTab = false
        kind = target.kind
        voiceDialog?.dismiss()
        voiceDialog = null
        fab.visibility = View.GONE
        content.removeAllViews()
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(30))
        }
        scroll.addView(column)
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(24), dp(16), dp(4))
        }
        bar.addView(TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(palette.text.toInt())
            setPadding(dp(6), 0, dp(14), dp(6))
            setOnClickListener {
                persistForm()
                creatorOpen = false
                showList()
            }
        })
        bar.addView(TextView(context).apply {
            text = "Model creator"
            textSize = 20f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(bar)

        nameField = EditText(context).apply {
            setText(target.name)
            hint = "Model name"
            textSize = 15f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            background = rounded(palette.raised.toInt(), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 1
        }
        column.addView(nameField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })

        tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(2))
        }
        // Six kinds no longer fit a phone at once. Scrolling the row keeps the last tab reachable
        // instead of letting it sit off the right edge where it cannot be tapped.
        column.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        })
        renderTabs()

        formColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(formColumn)
        buildForm()
        renderRun(ModelCraft.state.value)
    }

    private fun renderTabs() {
        val row = tabsRow ?: return
        row.removeAllViews()
        ModelCraft.Kind.entries.forEach { entry ->
            val selected = entry == kind && !neuralTab
            val tab = TextView(context).apply {
                // Read from the kind rather than a parallel list: a seventh kind must not be able to
                // index past a hand-written array and take the page down before it is drawn.
                text = if (entry == ModelCraft.Kind.INTERPOLATION) "Frames" else entry.label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (selected) palette.onAccent.toInt() else palette.text.toInt())
                background = rounded(
                    if (selected) palette.accent.toInt() else palette.raised.toInt(), 999,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (entry == kind && !neuralTab) return@setOnClickListener
                    persistForm()
                    neuralTab = false
                    kind = entry
                    // The tab IS the kind of model being built, so switching one rewrites the record
                    // (same id, same dataset folder, new family defaults) rather than keeping a
                    // second invisible model behind the form.
                    model?.let { switchKind(it, entry) }
                    renderTabs()
                    buildForm()
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) })
        }
        // The voice workshop. It selects the audio kind when opened, because a voice belongs to a
        // speech model, but it is not a seventh kind: it edits that model's engine and settings
        // rather than replacing what the model is.
        val neural = TextView(context).apply {
            text = "Neural voice"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(if (neuralTab) palette.onAccent.toInt() else palette.text.toInt())
            background = rounded(if (neuralTab) palette.accent.toInt() else palette.raised.toInt(), 999)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (neuralTab) return@setOnClickListener
                persistForm()
                neuralTab = true
                if (kind != ModelCraft.Kind.AUDIO) {
                    kind = ModelCraft.Kind.AUDIO
                    model?.let { switchKind(it, ModelCraft.Kind.AUDIO) }
                }
                renderTabs()
                buildForm()
            }
        }
        row.addView(neural, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(6) })
    }

    private fun switchKind(target: CraftedModel, next: ModelCraft.Kind) {
        // A record's kind is fixed at construction in the JSON (it is written on save), so a model
        // that changes tab is re-created rather than mutated in place: same id, same folder, new kind
        // and family defaults.
        if (target.kind == next) return
        val replacement = CraftedModel(
            id = target.id,
            name = nameField?.text?.toString()?.trim().orEmpty().ifBlank { target.name },
            kind = next,
            architecture = ModelCraft.architectures.first(),
            family = ModelCraft.archTypes.getValue(next).first().name,
            params = target.params,
        )
        replacement.dataset.addAll(target.dataset)
        replacement.datasetSummary = target.datasetSummary
        ModelCraft.Store.save(replacement)
        model = replacement
    }

    /**
     * The form: architecture, family, dataset, then every field the recipe carries.
     *
     * Two fields per row where the label is short enough to fit, because forty stacked labels is a
     * screen of scrolling nobody reads. Values are read back by [persistForm], so typing is not saved
     * per keystroke -- a save on the way out of the form is what the store does.
     */
    private fun buildForm() {
        val column = formColumn ?: return
        column.removeAllViews()
        fieldEditors = linkedMapOf()
        fieldChoices = linkedMapOf()
        attentionCheck = null
        windowEditor = null
        nthEditor = null
        miniatureCheck = null
        parameterEditor = null
        contextEditor = null
        parameterShown = ""
        contextShown = ""
        capacityView = null
        audioTypeGroup = null
        voiceEditor = null
        voiceBlock = null
        speechBlock = null
        musicBlock = null
        scheduleEpochsEditor = null
        scheduleStepsEditor = null
        stopTestAnimation()
        testOutputHolder = null
        testInput = null
        testButton = null
        testCaption = null
        testHintView = null
        trainingGraph = null
        voiceStripHolder = null
        testVoice = null
        neuralStatusView = null
        neuralVoiceStrip = null
        neuralTestInput = null
        neuralSettingsEditors.clear()
        kokoroCheck = null
        customCheck = null
        val target = model ?: return

        if (neuralTab) {
            buildNeuralForm(target, column)
            renderRun(ModelCraft.state.value)
            return
        }

        column.addView(section("ARCHITECTURE"))
        archSpinner = spinner(ModelCraft.architectures, "Broad shape of the network") { position ->
            target.architecture = ModelCraft.architectures[position]
            ModelCraft.Store.save(target)
        }
        archSpinner?.setSelection(
            ModelCraft.architectures.indexOf(target.architecture).coerceAtLeast(0),
        )
        column.addView(archSpinner, spinnerParams())
        val families = ModelCraft.archTypes.getValue(kind)
        familySpinner = spinner(families.map { it.name }, families.firstOrNull { it.name == target.family }?.note.orEmpty()) { position ->
            target.family = families[position].name
            ModelCraft.Store.save(target)
        }
        familySpinner?.setSelection(families.indexOfFirst { it.name == target.family }.coerceAtLeast(0))
        column.addView(familySpinner, spinnerParams())
        column.addView(TextView(context).apply {
            text = families.firstOrNull { it.name == target.family }?.note.orEmpty()
            textSize = 10f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(2), dp(18), dp(4))
        })

        column.addView(section("DATASET"))
        val spec = ModelCraft.datasets.getValue(kind)
        column.addView(TextView(context).apply {
            text = spec.help
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), 0, dp(18), dp(6))
        })
        val importRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        importRow.addView(pill("Import folder").apply {
            setOnClickListener { importDataset(target, spec) }
        })
        if (target.dataset.isNotEmpty()) {
            importRow.addView(pill("Re-sync").apply {
                setOnClickListener { importDataset(target, spec, resync = true) }
            })
        }
        importRow.addView(TextView(context).apply {
            text = "${target.dataset.size} file${if (target.dataset.size == 1) "" else "s"} imported"
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(dp(10), 0, 0, 0)
        })
        column.addView(importRow)
        datasetSummaryView = TextView(context).apply {
            text = target.datasetSummary
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), dp(6), dp(18), dp(2))
            visibility = if (target.datasetSummary.isBlank()) View.GONE else View.VISIBLE
        }
        column.addView(datasetSummaryView)

        // ── What the model IS, before how it trains ───────────────────────────────────────────
        //
        // Both of these are deliberately not recipe fields. A checkbox that enables two boxes, and
        // a radio group whose second answer adds a required one, are controls a text box cannot be.
        if (kind == ModelCraft.Kind.AUDIO) {
            column.addView(section("AUDIO MODEL TYPE"))
            column.addView(
                hint(
                    "Every type is trained during a run on the same audio; the type decides what " +
                        "the run produces and how it is used.",
                ),
            )
            val group = android.widget.RadioGroup(context).apply {
                orientation = android.widget.RadioGroup.VERTICAL
                setPadding(dp(14), 0, dp(14), dp(2))
            }
            ModelCraft.AudioType.entries.forEach { entry ->
                group.addView(android.widget.RadioButton(context).apply {
                    id = RADIO_ID_BASE + entry.ordinal
                    text = entry.label
                    textSize = 14f
                    setTextColor(palette.text.toInt())
                    buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
            val selectedType = ModelCraft.audioType(target)
            group.check(RADIO_ID_BASE + selectedType.ordinal)
            group.setOnCheckedChangeListener { _, checkedId ->
                val picked = ModelCraft.AudioType.entries
                    .getOrNull(checkedId - RADIO_ID_BASE) ?: return@setOnCheckedChangeListener
                val wantsVoices = picked == ModelCraft.AudioType.TEXT_TO_SPEECH
                voiceBlock?.visibility = if (wantsVoices) View.VISIBLE else View.GONE
                speechBlock?.visibility = if (wantsVoices) View.VISIBLE else View.GONE
                musicBlock?.visibility =
                    if (picked == ModelCraft.AudioType.MUSIC) View.VISIBLE else View.GONE
                // The box is created disabled for the default cloning model, so showing it again is
                // not enough: switching to text to speech has to make it editable as well.
                voiceEditor?.isEnabled = wantsVoices
                engineCheck?.isEnabled = wantsVoices
                pocketCheck?.isEnabled = wantsVoices
                persistForm()
            }
            audioTypeGroup = group
            column.addView(group)
            column.addView(hint(selectedType.note))

            // Music conditioning. A music model learns timbres and progressions from the dataset's
            // clips, so these fields steer generation rather than describing a score: the
            // instruments and voices it heard are the palette, the genre/key/tempo are part of the
            // same condition vector as the prompt.
            val musicHolder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(2), dp(12), dp(2))
                visibility = if (selectedType == ModelCraft.AudioType.MUSIC) View.VISIBLE else View.GONE
            }
            fun musicField(label: String, key: String, fallback: String) {
                val (cell, editor) = labelledEditor(label, target.params[key] ?: fallback, true)
                fieldEditors[key] = editor
                musicHolder.addView(cell)
            }
            musicField("INSTRUMENTS", "instruments", "piano, strings")
            musicField("SINGING VOICES", "singing_voices", "1")
            musicField("GENRE", "genre", "")
            musicField("TEMPO (BPM)", "tempo_bpm", "100")
            musicField("KEY", "key", "C minor")
            musicField("LENGTH (SECONDS)", "music_seconds", "8")
            musicField("TEMPERATURE", "music_temperature", "0.9")
            musicField("TOP-K TIMBRES", "music_top_k", "20")
            musicHolder.addView(
                hint(
                    "Lower temperature and top-k repeat the safest learned timbres; higher values " +
                        "wander. The prompt names what the clip is for, and these fields are always " +
                        "part of the condition.",
                    indent = 0,
                ),
            )
            musicBlock = musicHolder
            column.addView(musicHolder)

            // The voice count, present for text to speech and required there. Hidden for a cloning
            // model rather than merely disabled: a cloning model has exactly one voice, the
            // speaker's, and a greyed-out count would suggest otherwise.
            val voiceHolder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(2))
            }
            val (voiceCell, voiceInput) = labelledEditor(
                "VOICE TYPES TO BUILD",
                target.params[ModelCraft.KEY_VOICE_COUNT] ?: "",
                selectedType == ModelCraft.AudioType.TEXT_TO_SPEECH,
            )
            voiceHolder.addView(voiceCell)
            voiceHolder.addView(
                hint("Required for text to speech: how many different voice types this model must " +
                    "be able to speak in once it is built.", indent = 0),
            )
            voiceHolder.visibility =
                if (selectedType == ModelCraft.AudioType.TEXT_TO_SPEECH) View.VISIBLE else View.GONE
            voiceEditor = voiceInput
            voiceBlock = voiceHolder
            column.addView(voiceHolder)

            // Every voice control below belongs to text to speech, so they live in one holder that
            // hides as a whole. Leaving the miniature and Pocket boxes visible but disabled under a
            // music or cloning model only invites the question of what they would do.
            val speechHolder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (selectedType == ModelCraft.AudioType.TEXT_TO_SPEECH) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            column.addView(speechHolder)
            speechBlock = speechHolder

            val miniature = android.widget.CheckBox(context).apply {
                text = "Use the miniature voice model"
                textSize = 14f
                setTextColor(palette.text.toInt())
                buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                isChecked = ModelCraft.miniatureFallback(target)
                setPadding(dp(12), dp(10), 0, 0)
            }
            speechHolder.addView(miniature)
            speechHolder.addView(
                hint(
                    "Only for a dataset with no transcripts. It renders the acoustic model's " +
                        "predicted frames; it does not say the words. Off by default, and a trained " +
                        "text-to-speech model always wins when there is one.",
                ),
            )
            miniatureCheck = miniature
            miniature.setOnCheckedChangeListener { _, checked ->
                ModelCraft.setMiniatureFallback(target, checked)
                ModelCraft.Store.save(target)
            }

            val engine = android.widget.CheckBox(context).apply {
                text = "Use the dataset voice engine"
                textSize = 14f
                setTextColor(palette.text.toInt())
                buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                isChecked = ModelCraft.usesBuiltInVoiceEngine(target)
                isEnabled = selectedType == ModelCraft.AudioType.TEXT_TO_SPEECH
                setPadding(dp(12), dp(10), 0, 0)
            }
            engine.setOnCheckedChangeListener { _, checked ->
                ModelCraft.setVoiceEngine(
                    target,
                    if (checked) ModelCraft.VOICE_ENGINE_DATASET else ModelCraft.VOICE_ENGINE_POCKET,
                )
                // Choosing Pocket records it as allowed; show the same answer in the fallback box.
                if (!checked) pocketCheck?.isChecked = true
                ModelCraft.Store.save(target)
            }
            speechHolder.addView(engine)
            speechHolder.addView(
                hint(
                    "The engine is measured from this dataset's own recordings: each voice's " +
                        "pitch and spectral profile are written beside the model, and the local " +
                        "studio voice speaks through them. The pack is written either way; off " +
                        "makes Pocket TTS the engine (its models must be installed).",
                ),
            )
            engineCheck = engine

            val pocket = android.widget.CheckBox(context).apply {
                text = "Allow Pocket TTS fallback"
                textSize = 14f
                setTextColor(palette.text.toInt())
                buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                isChecked = ModelCraft.allowsPocketFallback(target)
                isEnabled = selectedType == ModelCraft.AudioType.TEXT_TO_SPEECH
                setPadding(dp(12), dp(10), 0, 0)
            }
            pocket.setOnCheckedChangeListener { _, checked ->
                ModelCraft.setAllowsPocketFallback(target, checked)
                ModelCraft.Store.save(target)
            }
            speechHolder.addView(pocket)
            speechHolder.addView(
                hint(
                    "Off by default. On, Pocket TTS covers a language the studio voice cannot " +
                        "say or a device without it; off means the failure is named instead of " +
                        "changing engines silently.",
                ),
            )
            pocketCheck = pocket
        }

        if (kind in ModelCraft.attentionKinds) {
            column.addView(section("ATTENTION"))
            val current = ModelCraft.attention(target)
            val check = android.widget.CheckBox(context).apply {
                text = "Sliding window attention"
                textSize = 14f
                setTextColor(palette.text.toInt())
                buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                isChecked = current.enabled
                setPadding(dp(12), dp(4), 0, 0)
            }
            column.addView(check)
            column.addView(
                hint(
                    "Every layer attends to the most recent window of tokens, except every Nth " +
                        "layer, which attends to everything.",
                ),
            )
            val (windowCell, windowInput) = labelledEditor(
                "WINDOW SIZE (TOKENS)", current.window.toString(), current.enabled,
            )
            val (nthCell, nthInput) = labelledEditor(
                "PER NTH LAYER FULL ATTENTION", current.fullAttentionEveryNthLayer.toString(),
                current.enabled,
            )
            val boxes = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(2), dp(12), dp(2))
            }
            boxes.addView(windowCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6), 0, dp(6), 0)
            })
            boxes.addView(nthCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6), 0, dp(6), 0)
            })
            column.addView(boxes)
            column.addView(
                hint(
                    "N layers of sliding attention, then one that attends fully. The local layers " +
                        "carry the detail and the periodic one carries the sentence.",
                ),
            )
            attentionCheck = check
            windowEditor = windowInput
            nthEditor = nthInput
            check.setOnCheckedChangeListener { _, checked ->
                // Enabling the checkbox is what makes the two numbers editable, and switching it off
                // leaves what was typed in place: turning it back on should not mean retyping it.
                windowInput.isEnabled = checked
                nthInput.isEnabled = checked
                windowCell.alpha = if (checked) 1f else 0.5f
                nthCell.alpha = if (checked) 1f else 0.5f
                persistForm()
            }
        }

        // ── What size it is asked to be ─────────────────────────────────────────────────────────
        //
        // Every kind gets these two, because every recipe a full-scale trainer reads carries them:
        // how many parameters, and how many tokens of context. Sliding attention is what makes the
        // second number bigger, so the box is drawn after the attention controls and reads through
        // ModelCapacity -- the same calculator the recipe and the desktop page use.
        column.addView(section("MODEL SIZE"))
        column.addView(
            hint(
                "The count the model is asked to build, and the token limit it is asked to hold. " +
                    "The count is turned into a concrete shape (layers, width, heads) and the real " +
                    "total is shown below; the context box follows sliding attention, because local " +
                    "layers cache less per token.",
            ),
        )
        val asked = ModelCraft.capacity(target)
        val (parameterCell, parameterInput) = labelledEditor(
            "PARAMETER COUNT", asked.requestedParameters.toString(), true,
        )
        val (contextCell, contextInput) = labelledEditor(
            "CONTEXT WINDOW (TOKENS)", asked.effectiveContext.toString(), true,
        )
        val capacityRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        capacityRow.addView(parameterCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        capacityRow.addView(contextCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        column.addView(capacityRow)
        val capacity = TextView(context).apply {
            text = asked.summary()
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), dp(6), dp(18), dp(2))
            setLineSpacing(0f, 1.15f)
        }
        column.addView(capacity)
        parameterEditor = parameterInput
        contextEditor = contextInput
        parameterShown = asked.requestedParameters.toString()
        contextShown = asked.effectiveContext.toString()
        capacityView = capacity

        // ── How the model comes into being ───────────────────────────────────────────────────────
        //
        // Two switches, on every tab and for every kind, for the two things a run can be told to
        // skip: the base it starts from, and this device as the place it trains.
        column.addView(section("CREATION"))
        val scratch = android.widget.CheckBox(context).apply {
            text = "Create from scratch (no base model)"
            textSize = 14f
            setTextColor(palette.text.toInt())
            buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
            isChecked = ModelCraft.fromScratch(target)
            setPadding(dp(12), dp(4), 0, 0)
        }
        column.addView(scratch)
        column.addView(
            hint(
                "The architecture is created at a default shape and trained from random weights: no " +
                    "base checkpoint, no fine-tune, nothing to download. The base-only fields below " +
                    "are greyed and are not written to the recipe — what was typed stays on the " +
                    "model, so switching this back off restores it.",
            ),
        )
        val local = android.widget.CheckBox(context).apply {
            text = "Train on this device"
            textSize = 14f
            setTextColor(palette.text.toInt())
            buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
            isChecked = ModelCraft.trainsLocally(target)
            setPadding(dp(12), dp(8), 0, 0)
        }
        column.addView(local)
        column.addView(
            hint(
                "Off: the run writes the recipe, prepares the dataset and exports both, and trains " +
                    "nothing here — what training on another machine wants. On: a trainer for this " +
                    "architecture is looked for here, and when there is none the status says so.",
            ),
        )
        column.addView(
            hint(
                "From-scratch shape: " +
                    ModelCraft.scratchConfig(target).keys().asSequence().joinToString(", "),
            ),
        )
        scratchCheck = scratch
        localCheck = local
        scratch.setOnCheckedChangeListener { _, _ ->
            applyCreation(target)
            persistForm()
        }
        local.setOnCheckedChangeListener { _, _ -> persistForm() }

        // ── What the run must produce ───────────────────────────────────────────────────────────
        //
        // The one control on this form about output rather than input. Present for every kind, for
        // the same reason the creation switches are: the answer is the file the user carries away
        // from the device, and finding out afterwards is finding out too late.
        column.addView(section("MODEL FILES"))
        column.addView(
            hint(
                "The container the trained weights are written in. Both hold the same weights; " +
                    "they differ in how many files come out and what reads them.",
            ),
        )
        val formats = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(2))
        }
        ModelCraft.FileFormat.entries.forEach { entry ->
            formats.addView(android.widget.RadioButton(context).apply {
                id = FORMAT_ID_BASE + entry.ordinal
                text = entry.label
                textSize = 14f
                setTextColor(palette.text.toInt())
                buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        formats.check(FORMAT_ID_BASE + ModelCraft.fileFormat(target).ordinal)
        column.addView(formats)
        // The option's own note, and the file names this kind will actually leave behind: read from
        // the same list the writers use, so the sentence cannot outlive the truth.
        val filesHint = hint(plannedFiles(target)) as TextView
        formats.setOnCheckedChangeListener { _, _ ->
            filesHint.text = plannedFiles(target)
            persistForm()
        }
        column.addView(filesHint)
        formatGroup = formats

        // ── How long the run trains ──────────────────────────────────────────────────────────────
        //
        // Two boxes for every kind, together rather than among the training fields: passes over the
        // dataset, and examples per pass. The local trainers read these as their loop bounds.
        column.addView(section("TRAINING SCHEDULE"))
        column.addView(
            hint(
                "How long the run trains. One step is one example here; steps per epoch caps how " +
                    "many examples a pass takes, and 0 means every example in the dataset.",
            ),
        )
        val currentSchedule = ModelCraft.schedule(target)
        val (stepsCell, stepsInput) = labelledEditor(
            "STEPS PER EPOCH", currentSchedule.stepsPerEpoch.toString(), true,
        )
        val (epochsCell, epochsInput) = labelledEditor(
            "EPOCHS", currentSchedule.epochs.toString(), true,
        )
        val scheduleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        scheduleRow.addView(stepsCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        scheduleRow.addView(epochsCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        column.addView(scheduleRow)
        column.addView(
            hint(
                if (currentSchedule.plannedSteps > 0) {
                    "${currentSchedule.epochs} epoch(s) of ${currentSchedule.stepsPerEpoch} step(s): " +
                        "exactly ${currentSchedule.plannedSteps} steps. One step is one example, so " +
                        "the run takes all of them."
                } else {
                    "${currentSchedule.epochs} epoch(s), each a full pass over the training split: " +
                        "steps per epoch is 0, so every example is trained on that many times."
                },
            ),
        )
        scheduleStepsEditor = stepsInput
        scheduleEpochsEditor = epochsInput

        column.addView(section("TRAINING FIELDS"))
        val fields = ModelCraft.fields.getValue(kind)
        for (pair in fields.chunked(2)) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(2), dp(12), dp(2))
            }
            pair.forEach { field ->
                row.addView(fieldCell(target, field), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { setMargins(dp(6), 0, dp(6), 0) })
            }
            if (pair.size == 1) {
                row.addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            column.addView(row)
        }
        // The form is built with its switches already in the position they were left in, so the
        // base-only fields are greyed straight away on a from-scratch model rather than only after
        // the box is touched.
        applyCreation(target)

        column.addView(section("TRAINING"))
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.divider.toInt())
        }
        column.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
        ).apply { setMargins(dp(18), dp(6), dp(18), 0) })
        stageView = TextView(context).apply {
            text = target.note
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), dp(6), dp(18), dp(2))
        }
        column.addView(stageView)
        trainButton = pill("Prepare and train", accent = true).apply {
            textSize = 15f
            setOnClickListener {
                persistForm()
                // THE VOICES COME BEFORE THE RUN. A text-to-speech model has to know who its
                // speakers are before it is trained, because the engine it will speak through is
                // built from those answers. The extraction runs first, then the dialog; OK builds
                // the engine and starts the run, Cancel leaves the model untrained.
                if (ModelCraft.wantsVoiceReview(target)) {
                    beginVoicesThenTrain(target)
                } else {
                    ModelCraft.train(target)
                }
            }
        }
        column.addView(trainButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50),
        ).apply { setMargins(dp(16), dp(10), dp(16), 0) })
        // The export is a button as well as something every run does: a model that is already
        // trained, or one that has never run, can be taken somewhere else in one tap.
        exportButton = pill("Export + recreate script").apply {
            setOnClickListener {
                persistForm()
                val target = model ?: return@setOnClickListener
                scope.launch(Dispatchers.IO) {
                    val exported = runCatching { ModelCraftExport.export(target) }.getOrNull()
                    launch(Dispatchers.Main) {
                        val message = if (exported == null) {
                            "The export could not be written."
                        } else {
                            "Exported to ${exported.absolutePath} — " +
                                "${ModelCraftExport.scriptName(target)} rebuilds this model on any " +
                                "machine with Python."
                        }
                        stageView?.text = message
                        Toast.makeText(
                            context,
                            if (exported == null) "Export failed" else "Exported",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        column.addView(exportButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ).apply { setMargins(dp(16), dp(8), dp(16), 0) })
        // The same dialog a finished run opens, for every later look: the speaker names, genders and
        // languages are editable, and OK writes the pack beside the model again.
        if (target.kind == ModelCraft.Kind.AUDIO) {
            voiceButton = pill("Voices and voice pack").apply {
                setOnClickListener {
                    persistForm()
                    showVoiceDialog()
                }
            }
            column.addView(voiceButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
            ).apply { setMargins(dp(16), dp(8), dp(16), 0) })
        }
        column.addView(TextView(context).apply {
            text = "The recipe is written to the model's own folder, with the dataset manifest beside " +
                "it. If this build has a trainer for the chosen architecture it runs from there; " +
                "otherwise the status says so and names the file."
            textSize = 10f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(6), dp(18), 0)
        })
        validationView = TextView(context).apply {
            textSize = 11f
            setTextColor(palette.destructive.toInt())
            setPadding(dp(18), dp(8), dp(18), 0)
            visibility = View.GONE
        }
        column.addView(validationView)
        renderValidation(target)

        // ── Trying what was built ────────────────────────────────────────────────────────────────
        //
        // Result first, box last: the order a test is read in. Both stay disabled until the model
        // has weights, and the sentence above the box says what the words do for the kinds whose
        // models do not read text.
        column.addView(section("TEST THE MODEL"))
        val testHint = hint(if (ModelCraftTest.ready(target)) {
            ModelCraftTest.promptHint(kind)
        } else {
            "Train the model first. Once it has weights, this is where you try them without " +
                "leaving the form."
        })
        testHintView = testHint as TextView
        column.addView(testHint)

        // The voices sit directly above the waveform: pick one, then Test says the same sentence
        // in it. Built even before training, when the pack may still be written by the dialog.
        voiceStripHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(voiceStripHolder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(2), 0, 0) })
        renderTestVoices(target)

        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(palette.raised.toInt(), 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        column.addView(holder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(200),
        ).apply { setMargins(dp(16), dp(4), dp(16), 0) })
        testOutputHolder = holder
        showTestPlaceholder(target)

        testCaption = TextView(context).apply {
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(6), dp(18), dp(2))
        }
        column.addView(testCaption)

        val testRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        val (inputCell, input) = labelledEditor(ModelCraftTest.promptLabel(kind), "", true)
        input.hint = "Type something to test with"
        input.isEnabled = ModelCraftTest.ready(target)
        testInput = input
        testRow.addView(inputCell, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { setMargins(dp(4), 0, dp(8), 0) })
        testButton = pill("Test", accent = true).apply {
            setOnClickListener { runTest() }
            alpha = 0.5f
            isEnabled = false
        }
        testRow.addView(testButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
        ).apply { setMargins(0, 0, dp(4), 0) })
        column.addView(testRow)
        refreshTestControls(target)
    }

    /**
     * The Neural Voice workshop: datasets, model parameters, engine switches and a test box.
     *
     * It edits the current audio model instead of being a seventh kind. The voice belongs to that
     * model's recordings, and the same engine is what the Test box and Prism's speech callers use.
     */
    private fun buildNeuralForm(target: CraftedModel, column: LinearLayout) {
        val settings = ModelCraft.neuralVoiceSettings(target)
        val spec = ModelCraft.datasets.getValue(ModelCraft.Kind.AUDIO)
        column.addView(section("NEURAL VOICE DATASET"))
        column.addView(
            hint(
                "The same recording folder the audio model uses. Each recording is aligned to its " +
                    "transcript and becomes both a speaker profile and the units that speaker's voice " +
                    "is built from. No pretrained voice is needed.",
            ),
        )
        val importRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        importRow.addView(pill("Import folder").apply {
            setOnClickListener { importDataset(target, spec) }
        })
        if (target.dataset.isNotEmpty()) {
            importRow.addView(pill("Re-sync").apply {
                setOnClickListener { importDataset(target, spec, resync = true) }
            })
        }
        importRow.addView(TextView(context).apply {
            text = "${target.dataset.size} file${if (target.dataset.size == 1) "" else "s"} imported"
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(dp(10), 0, 0, 0)
        })
        column.addView(importRow)
        datasetSummaryView = TextView(context).apply {
            text = target.datasetSummary
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), dp(6), dp(18), dp(2))
            visibility = if (target.datasetSummary.isBlank()) View.GONE else View.VISIBLE
        }
        column.addView(datasetSummaryView)

        column.addView(section("MODEL PARAMETERS"))
        column.addView(
            hint(
                "These are the parameters that build the voice, not decoration: the network width " +
                    "and embedding decide how much phonetic context the prosody model can learn, the " +
                    "unit bounds decide what counts as one sound, and the render controls change the " +
                    "same trained units without retraining.",
            ),
        )
        fun editor(label: String, key: String, value: String): LinearLayout {
            val (cell, edit) = labelledEditor(label, value, true)
            neuralSettingsEditors[key] = edit
            return cell
        }
        fun two(left: LinearLayout, right: LinearLayout) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(2), dp(12), dp(2))
            }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(6), 0)
            })
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(4), 0)
            })
            column.addView(row)
        }
        two(
            editor("LAYER WIDTH", "hidden", settings.hidden.toString()),
            editor("VOICE EMBEDDING", "embedding", settings.embedding.toString()),
        )
        two(
            editor("TRAINING EPOCHS", "epochs", settings.epochs.toString()),
            editor("LEARNING RATE", "learning_rate", "%.4f".format(settings.learningRate)),
        )
        two(
            editor("JOIN CROSSFADE (MS)", "crossfade_ms", settings.crossfadeMs.toString()),
            editor("UNITS PER PHONEME", "max_units_per_symbol", settings.maxUnitsPerSymbol.toString()),
        )
        two(
            editor("MIN UNIT (MS)", "min_unit_ms", settings.minUnitMs.toString()),
            editor("MAX UNIT (MS)", "max_unit_ms", settings.maxUnitMs.toString()),
        )
        two(
            editor("PITCH RANGE", "pitch_range", "%.2f".format(settings.pitchRange)),
            editor("TEMPO", "tempo", "%.2f".format(settings.tempo)),
        )
        two(
            editor("BREATH", "breath", "%.2f".format(settings.breath)),
            editor("WARMTH", "warmth", "%.2f".format(settings.warmth)),
        )

        column.addView(section("VOICE ENGINE"))
        val kokoro = android.widget.CheckBox(context).apply {
            text = "Allow Kokoro studio voice"
            textSize = 14f
            setTextColor(palette.text.toInt())
            buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
            isChecked = ModelCraft.allowsKokoro(target)
            setPadding(dp(12), dp(6), 0, 0)
        }
        kokoro.setOnCheckedChangeListener { _, checked ->
            ModelCraft.setAllowsKokoro(target, checked)
            ModelCraft.Store.save(target)
        }
        column.addView(kokoro)
        column.addView(
            hint(
                "Off by default. Kokoro is a pretrained voice, so leaving it off is what keeps the " +
                    "model speaking through the voice Prism built from this dataset. Turn it on if " +
                    "the custom voice cannot cover a sentence and you would rather hear it than a gap.",
            ),
        )
        kokoroCheck = kokoro

        val custom = android.widget.CheckBox(context).apply {
            text = "Use a custom pretrained neural voice"
            textSize = 14f
            setTextColor(palette.text.toInt())
            buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
            isChecked = ModelCraft.usesCustomVoice(target)
            setPadding(dp(12), dp(8), 0, 0)
        }
        column.addView(custom)
        val customRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 2)
        }
        val customButton = pill("Import neural voice folder").apply {
            visibility = if (custom.isChecked) View.VISIBLE else View.GONE
            setOnClickListener { importCustomVoice(target) }
        }
        customRow.addView(customButton)
        column.addView(customRow)
        column.addView(
            hint(
                "A Prism neural voice folder: ${PrismNeuralVoice.FILE}, " +
                    "${PrismNeuralVoice.WEIGHTS_FILE} and the recordings its units name. Importing " +
                    "copies them into Prism, so the folder can be moved or deleted afterwards.",
            ),
        )
        custom.setOnCheckedChangeListener { _, checked ->
            customButton.visibility = if (checked) View.VISIBLE else View.GONE
            val path = ModelCraft.customVoicePath(target)
            if (checked && path.isBlank()) {
                importCustomVoice(target)
            } else {
                ModelCraft.setCustomVoice(target, path, checked)
                ModelCraft.Store.save(target)
            }
        }
        customCheck = custom

        val pocket = android.widget.CheckBox(context).apply {
            text = "Allow Pocket TTS fallback"
            textSize = 14f
            setTextColor(palette.text.toInt())
            buttonTintList = ColorStateList.valueOf(palette.accent.toInt())
            isChecked = ModelCraft.allowsPocketFallback(target)
            setPadding(dp(12), dp(8), 0, 0)
        }
        pocket.setOnCheckedChangeListener { _, checked ->
            ModelCraft.setAllowsPocketFallback(target, checked)
            ModelCraft.Store.save(target)
        }
        column.addView(pocket)
        column.addView(
            hint("Last resort, and off by default. Pocket TTS is another pretrained engine, not part of the Prism voice."),
        )
        pocketCheck = pocket

        column.addView(section("BUILD THE VOICE"))
        val status = TextView(context).apply {
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(dp(18), dp(4), dp(18), dp(4))
            setLineSpacing(0f, 1.15f)
        }
        neuralStatusView = status
        column.addView(status)
        val build = pill("Build neural voice", accent = true)
        build.setOnClickListener {
            persistNeuralSettings(target)
            val chosen = ModelCraft.neuralVoiceSettings(target)
            build.isEnabled = false
            build.alpha = 0.5f
            status.text = "Aligning recordings and training the prosody model…"
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    PrismNeuralVoice.train(
                        ModelCraft.Store.datasetDir(target.id),
                        chosen,
                    ) { line, fraction ->
                        scope.launch(Dispatchers.Main) {
                            status.text = "$line · ${(fraction * 100).toInt()}%"
                        }
                    }
                }
                // Rebuild the engine so the Test box and Prism's speech callers see the new voice
                // immediately rather than after the next app start.
                runCatching { DatasetVoiceEngine.rebuild(target) }
                launch(Dispatchers.Main) {
                    build.isEnabled = true
                    build.alpha = 1f
                    val trained = result.getOrNull()
                    status.text = if (trained == null) {
                        result.exceptionOrNull()?.message
                            ?: "No voice could be built. Import audio with transcripts first."
                    } else {
                        PrismNeuralVoice.summary(trained) + " · trained in ${trained.steps} step(s)"
                    }
                    renderNeuralVoices(target)
                    Toast.makeText(
                        context,
                        if (trained == null) "Neural voice not built" else "Neural voice built",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        column.addView(build, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        column.addView(section("TEST THE VOICE"))
        val existing = runCatching { PrismNeuralVoice.load(ModelCraft.Store.datasetDir(target.id)) }.getOrNull()
        status.text = existing?.let { PrismNeuralVoice.summary(it) }
            ?: "No neural voice has been built for this model yet."
        neuralVoiceStrip = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(neuralVoiceStrip)
        renderNeuralVoices(target)
        val testRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        val (testCell, testInput) = labelledEditor("SENTENCE", "", true)
        testInput.hint = "Type a sentence to hear in this voice"
        neuralTestInput = testInput
        testRow.addView(testCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(8), 0)
        })
        testRow.addView(pill("Speak", accent = true).apply {
            setOnClickListener {
                val sentence = neuralTestInput?.text?.toString()?.trim().orEmpty()
                if (sentence.isBlank()) return@setOnClickListener
                status.text = "Speaking…"
                scope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val voice = PrismNeuralVoice.load(ModelCraft.Store.datasetDir(target.id))
                            ?: throw IllegalStateException("Build a neural voice first.")
                        voice.synthesize(
                            sentence,
                            language = voice.voices.firstOrNull()?.language ?: "en",
                            gender = voice.voices.firstOrNull()?.gender.orEmpty(),
                            voiceName = neuralTestVoice,
                        )
                    }
                    launch(Dispatchers.Main) {
                        result.onSuccess { samples ->
                            val problem = PrismPlatform.audio.play(samples, PrismNeuralVoice.RATE)
                            status.text = problem ?: "Played ${samples.size / PrismNeuralVoice.RATE.toFloat()} s."
                        }.onFailure { status.text = it.message ?: "The voice could not speak that." }
                    }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            setMargins(0, 0, dp(4), 0)
        })
        column.addView(testRow)
    }

    /** Reads the neural settings boxes and saves them on the model. */
    private fun persistNeuralSettings(target: CraftedModel) {
        val defaults = ModelCraft.neuralVoiceSettings(target)
        fun text(key: String): String = neuralSettingsEditors[key]?.text?.toString()?.trim().orEmpty()
        fun int(key: String, fallback: Int): Int = text(key).toIntOrNull() ?: fallback
        fun double(key: String, fallback: Double): Double = text(key).toDoubleOrNull() ?: fallback
        val settings = PrismNeuralVoice.Settings(
            hidden = int("hidden", defaults.hidden),
            embedding = int("embedding", defaults.embedding),
            epochs = int("epochs", defaults.epochs),
            learningRate = double("learning_rate", defaults.learningRate),
            crossfadeMs = int("crossfade_ms", defaults.crossfadeMs),
            minUnitMs = int("min_unit_ms", defaults.minUnitMs),
            maxUnitMs = int("max_unit_ms", defaults.maxUnitMs),
            pitchRange = double("pitch_range", defaults.pitchRange),
            tempo = double("tempo", defaults.tempo),
            breath = double("breath", defaults.breath),
            warmth = double("warmth", defaults.warmth),
            maxUnitsPerSymbol = int("max_units_per_symbol", defaults.maxUnitsPerSymbol),
        ).cleaned()
        ModelCraft.setNeuralVoiceSettings(target, settings)
        ModelCraft.Store.save(target)
    }

    /** The voices in the trained neural model, as selectable test chips. */
    private fun renderNeuralVoices(target: CraftedModel) {
        val holder = neuralVoiceStrip ?: return
        holder.removeAllViews()
        val voice = runCatching { PrismNeuralVoice.load(ModelCraft.Store.datasetDir(target.id)) }
            .getOrNull() ?: return
        if (neuralTestVoice != null && voice.voices.none { it.name == neuralTestVoice }) neuralTestVoice = null
        holder.addView(TextView(context).apply {
            text = "VOICE"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(2), dp(18), dp(5))
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        val chips = mutableListOf<Pair<String, TextView>>()
        fun paint(chip: TextView, selected: Boolean) {
            chip.setTextColor(if (selected) palette.onAccent.toInt() else palette.text.toInt())
            chip.background = rounded(if (selected) palette.accent.toInt() else palette.raised.toInt(), 999)
        }
        for (candidate in voice.voices) {
            val chip = TextView(context).apply {
                text = candidate.name + " · " + candidate.gender
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    neuralTestVoice = candidate.name
                    chips.forEach { (name, view) -> paint(view, name == candidate.name) }
                }
            }
            paint(chip, candidate.name == neuralTestVoice)
            chips += candidate.name to chip
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36),
            ).apply { setMargins(0, 0, dp(6), 0) })
        }
        holder.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    /**
     * Imports a Prism neural voice folder chosen with the system picker.
     *
     * The folder is copied rather than referenced: a SAF tree is not a path a JVM model loader can
     * reopen later, and a voice has to survive the picker's grant. Only the files under the folder
     * that contains the voice description are copied, so a pick of a whole storage volume does not
     * drag unrelated files along.
     */
    private fun importCustomVoice(target: CraftedModel) {
        importer.openFolder { tree ->
            if (tree == null) return@openFolder
            scope.launch(Dispatchers.IO) {
                val files = runCatching { listFolder(tree) }.getOrDefault(emptyList())
                val description = files.firstOrNull { it.name == PrismNeuralVoice.FILE }
                val weights = files.firstOrNull { it.name == PrismNeuralVoice.WEIGHTS_FILE }
                if (description == null || weights == null) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "That folder does not contain ${PrismNeuralVoice.FILE} and " +
                                PrismNeuralVoice.WEIGHTS_FILE,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
                val root = description.path.substringBeforeLast('/', "")
                val destination = File(
                    context.filesDir,
                    "neural-voices/imported-${System.currentTimeMillis().toString(36)}",
                ).apply { mkdirs() }
                var copied = 0
                for (file in files) {
                    if (root.isNotEmpty() && !file.path.startsWith("$root/")) continue
                    val relative = if (root.isEmpty()) file.path else file.path.removePrefix("$root/")
                    if (relative.startsWith("..")) continue
                    val out = File(destination, relative)
                    val ok = runCatching {
                        out.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        out.isFile && out.length() > 0
                    }.getOrDefault(false)
                    if (ok) copied++
                }
                val loaded = PrismNeuralVoice.load(destination)
                launch(Dispatchers.Main) {
                    if (loaded == null) {
                        destination.deleteRecursively()
                        Toast.makeText(context, "The neural voice could not be read", Toast.LENGTH_LONG).show()
                    } else {
                        ModelCraft.setCustomVoice(target, destination.absolutePath, true)
                        ModelCraft.Store.save(target)
                        customCheck?.isChecked = true
                        neuralStatusView?.text = "Imported ${PrismNeuralVoice.summary(loaded)} from $copied file(s)"
                        Toast.makeText(context, "Neural voice imported", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun fieldCell(target: CraftedModel, field: ModelCraft.Field): View {
        // A field that declares choices is a dropdown, not a text box. The list comes from
        // ModelCraft so this page and desktop cannot disagree about what a recipe key accepts.
        if (field.choices.isNotEmpty()) return choiceCell(target, field)
        val (column, editor) = labelledEditor(field.label, target.param(field.key), true)
        fieldEditors[field.key] = editor
        fieldCells[field.key] = column
        return column
    }

    /**
     * A labelled dropdown for a field whose values are a fixed list -- the quantization levels, for
     * instance.
     *
     * The selection is written as it changes rather than only in [persistForm]: the spinner reports
     * its initial selection immediately, and writing the same value back is harmless, whereas a
     * form that relied on the save-on-exit path would lose the choice if the page were detached
     * straight after it was made.
     */
    private fun choiceCell(target: CraftedModel, field: ModelCraft.Field): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = field.label
            textSize = 10f
            setTextColor(palette.muted.toInt())
            setPadding(dp(2), 0, 0, dp(2))
        })
        val current = target.param(field.key).ifBlank { field.default }
        val control = spinner(field.choices, field.label) { position ->
            field.choices.getOrNull(position)?.let { target.params[field.key] = it }
        }
        control.setSelection(
            field.choices.indexOfFirst { it.equals(current, true) }.coerceAtLeast(0)
        )
        fieldChoices[field.key] = control
        fieldCells[field.key] = column
        column.addView(control, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40),
        ).apply { setMargins(0, 0, 0, dp(2)) })
        return column
    }

    /**
     * Greys and disables the base-only fields when the model is being built from nothing.
     *
     * Disabled rather than hidden: a user who switches the box back off should find what they typed
     * still there, and a field that disappears is a field nobody can compare against.
     */
    private fun applyCreation(target: CraftedModel) {
        val fromScratch = scratchCheck?.isChecked == true
        val baseKeys = ModelCraft.baseFields.getValue(kind)
        for ((key, cell) in fieldCells) {
            val used = !(fromScratch && key in baseKeys)
            cell.alpha = if (used) 1f else 0.5f
            fieldEditors[key]?.isEnabled = used
            fieldChoices[key]?.let { control ->
                control.isEnabled = used
                control.alpha = if (used) 1f else 0.5f
            }
        }
    }

    /**
     * A label over a text box -- the shape every input on this form has.
     *
     * Returns the box alongside its label because some callers need both: the attention section
     * enables and dims the pair together, and the voice count has to be readable on the way out.
     */
    private fun labelledEditor(
        label: String,
        value: String,
        enabled: Boolean,
    ): Pair<LinearLayout, EditText> {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(palette.muted.toInt())
            setPadding(dp(2), 0, 0, dp(2))
        })
        val editor = EditText(context).apply {
            setText(value)
            textSize = 12f
            maxLines = 1
            isEnabled = enabled
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            background = rounded(palette.raised.toInt(), 8)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        column.addView(editor)
        return column to editor
    }

    /** A line of explanation under a control, in the faint colour this page uses for its quiet text. */
    private fun hint(text: String, indent: Int = 18): View = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(palette.faint.toInt())
        setPadding(dp(indent), dp(2), dp(18), dp(2))
    }

    /** Reads every editor back into the record, so leaving the form keeps what was typed. */
    private fun persistForm() {
        val target = model ?: return
        nameField?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { target.name = it }
        for (field in ModelCraft.fields.getValue(kind)) {
            fieldEditors[field.key]?.text?.toString()?.let { target.params[field.key] = it }
            // The dropdown's value is its selection. Read back here as well as in the listener so
            // leaving the form keeps one even if the selection was set programmatically.
            fieldChoices[field.key]?.let { control ->
                field.choices.getOrNull(control.selectedItemPosition)?.let { target.params[field.key] = it }
            }
        }
        // The music keys are form fields but not part of the generic field list: they only exist
        // when the music audio type is selected, and writing them for a speech model would put
        // meaningless parameters in its recipe. They are still read back here so leaving the form
        // keeps what was typed.
        for (key in MUSIC_KEYS) {
            fieldEditors[key]?.text?.toString()?.let { target.params[key] = it }
        }
        // Through ModelCraft's own setters rather than by writing the param keys here, so the names
        // and the defaults live in one place for both platforms.
        attentionCheck?.let { check ->
            ModelCraft.setAttention(
                target,
                ModelCraft.Attention(
                    enabled = check.isChecked,
                    window = windowEditor?.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1)
                        ?: ModelCraft.DEFAULT_WINDOW,
                    fullAttentionEveryNthLayer = nthEditor?.text?.toString()?.trim()?.toIntOrNull()
                        ?.coerceAtLeast(1) ?: ModelCraft.DEFAULT_FULL_ATTENTION_EVERY_NTH_LAYER,
                ),
            )
        }
        audioTypeGroup?.let { group ->
            ModelCraft.AudioType.entries.getOrNull(group.checkedRadioButtonId - RADIO_ID_BASE)?.let {
                target.params[ModelCraft.KEY_AUDIO_TYPE] = it.recipeValue
            }
        }
        formatGroup?.let { group ->
            ModelCraft.FileFormat.entries.getOrNull(group.checkedRadioButtonId - FORMAT_ID_BASE)?.let {
                ModelCraft.setFileFormat(target, it)
            }
        }
        voiceEditor?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val before = ModelCraft.voiceCount(target)
            target.params[ModelCraft.KEY_VOICE_COUNT] = it
            // A different number of voices needs a different voice bank, so the measurements
            // from the previous dialog must not be reused for the next run.
            if (before != ModelCraft.voiceCount(target)) ModelCraft.invalidateVoicePreparation(target)
        }
        // The two capacity boxes. Written only when the user changed what the form showed, so the
        // value sliding attention just computed is not overwritten by the stale text the box held
        // before the checkbox was toggled.
        parameterEditor?.text?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it != parameterShown
        }?.let { ModelCraft.setParameterCount(target, it) }
        contextEditor?.text?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it != contextShown
        }?.let { ModelCraft.setContextWindow(target, it) }
        if (scheduleStepsEditor != null || scheduleEpochsEditor != null) {
            val current = ModelCraft.schedule(target)
            ModelCraft.setSchedule(
                target,
                ModelCraft.Schedule(
                    epochs = scheduleEpochsEditor?.text?.toString()?.trim()?.toIntOrNull()
                        ?: current.epochs,
                    stepsPerEpoch = scheduleStepsEditor?.text?.toString()?.trim()?.toIntOrNull()
                        ?: current.stepsPerEpoch,
                ),
            )
        }
        // Through ModelCraft's own setters, so the key names and the defaults live in one place for
        // both platforms.
        scratchCheck?.let { ModelCraft.setFromScratch(target, it.isChecked) }
        localCheck?.let { ModelCraft.setTrainsLocally(target, it.isChecked) }
        ModelCraft.Store.save(target)
        // What the boxes say after the write, which is the effective window when sliding is on.
        val capacityAfter = ModelCraft.capacity(target)
        if (parameterEditor != null && parameterEditor?.hasFocus() != true) {
            parameterShown = capacityAfter.requestedParameters.toString()
            parameterEditor?.setText(parameterShown)
        }
        if (contextEditor != null && contextEditor?.hasFocus() != true) {
            contextShown = capacityAfter.effectiveContext.toString()
            contextEditor?.setText(contextShown)
        }
        capacityView?.text = capacityAfter.summary()
        if (miniatureCheck != null) {
            miniatureCheck?.isChecked = ModelCraft.miniatureFallback(target)
        }
        renderValidation(target)
    }

    /**
     * The format note with this kind's own file names: what the model-files section says under the
     * radio group, and what it re-says when the answer changes.
     */
    private fun plannedFiles(target: CraftedModel): String {
        val format = ModelCraft.fileFormat(target)
        val names = ModelFiles.fileNames(
            ModelCraft.fileStem(kind),
            format,
            ModelCraft.fileComponents(kind),
        )
        return format.note + " This kind writes " + names.size +
            (if (names.size == 1) " file: " else " files: ") + names.joinToString(", ") + "."
    }

    /** Says what is still missing, under the button that cannot do anything about it yet. */
    private fun renderValidation(target: CraftedModel) {
        val view = validationView ?: return
        val problem = ModelCraft.validationError(target)
        view.text = problem.orEmpty()
        view.visibility = if (problem == null) View.GONE else View.VISIBLE
    }

    /**
     * Imports a dataset by FOLDER.
     *
     * The folder is listed first, its CSVs are found wherever they sit inside it, and only the files
     * their rows name are copied. That is the difference from the old file picker: a CSV picked on
     * its own was staged into a cache folder with no media beside it, so every relative path in its
     * rows resolved to nothing -- on every kind of model, because every CSV was read that way.
     */
    private fun importDataset(
        target: CraftedModel,
        spec: ModelCraft.DatasetSpec,
        resync: Boolean = false,
    ) {
        importer.openFolder { tree ->
            if (tree == null) return@openFolder
            stageView?.text = "Reading the folder…"
            scope.launch(Dispatchers.IO) {
                val listing = listFolder(tree)
                if (listing.isEmpty()) {
                    launch(Dispatchers.Main) {
                        stageView?.text = "That folder is empty."
                        Toast.makeText(context, "That folder is empty", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val byPath = listing.associateBy { it.path }
                val plan = ModelCraft.planFolderImport(
                    listing.map { ModelCraft.PickedEntry(it.path, it.name) },
                    csvIsManifest = spec.csv,
                ) { entry -> byPath[entry.path]?.let { readCsv(it) } }
                if (plan.files.isEmpty()) {
                    launch(Dispatchers.Main) {
                        val note = if (plan.csvs == 0) {
                            "No CSV or media files were found in that folder."
                        } else {
                            "The CSV named no files that are in that folder."
                        }
                        stageView?.text = note
                        Toast.makeText(context, note, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                // One staging folder per import: two files with the same leaf name from different
                // subfolders cannot land on each other, and the copy can be thrown away afterwards.
                val scratch = File(
                    context.cacheDir,
                    "modelcraft-import/${System.currentTimeMillis().toString(36)}",
                ).apply { mkdirs() }
                val staged = mutableListOf<File>()
                try {
                    val splitAware = target.kind == ModelCraft.Kind.IMAGE
                    for (path in plan.files) {
                        val node = byPath[path] ?: continue
                        // The image trainer reads train/validation/test folders; staging those
                        // files flat would throw the publisher's split away before it could.
                        val split = if (splitAware) splitFolderFor(path) else null
                        val destination = if (split == null) {
                            scratch
                        } else {
                            File(scratch, split).apply { mkdirs() }
                        }
                        copyDocument(node.uri, destination, node.name)?.let { staged += it }
                    }
                    // The copy runs on IO; the line it is showing belongs on the UI thread.
                    val announce: (String) -> Unit = { line ->
                        scope.launch(Dispatchers.Main) { stageView?.text = line }
                    }
                    val added: Int
                    val summary: String
                    if (resync) {
                        added = 0
                        summary = ModelCraft.resyncDataset(
                            target, staged, announce, preserveSplits = splitAware,
                        )
                    } else {
                        added = ModelCraft.attachDataset(
                            target, staged, onStage = announce, preserveSplits = splitAware,
                        )
                        summary = ModelCraft.prepare(target)
                    }
                    launch(Dispatchers.Main) {
                        datasetSummaryView?.apply {
                            text = summary
                            visibility = View.VISIBLE
                        }
                        stageView?.text = if (resync) {
                            "Dataset re-synced from ${plan.files.size} file(s). $summary"
                        } else {
                            describeImport(added, plan, summary)
                        }
                        if (resync) {
                            Toast.makeText(context, "Dataset re-synced", Toast.LENGTH_SHORT).show()
                        } else if (added == 0) {
                            Toast.makeText(context, "Nothing was imported", Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    // The dataset keeps its own copies; the staging folder is only a bridge.
                    scratch.deleteRecursively()
                }
            }
        }
    }

    /** The line under the form: what was copied, and what the CSV could not find. */
    private fun describeImport(added: Int, plan: ModelCraft.FolderPlan, summary: String): String =
        buildString {
            append(added)
            append(if (added == 1) " file imported" else " files imported")
            if (plan.csvs > 0) {
                append(if (plan.csvs == 1) " from 1 CSV" else " from ${plan.csvs} CSVs")
            }
            if (plan.missing > 0) {
                append(" · ${plan.missing} referenced file")
                if (plan.missing != 1) append("s")
                append(" not found in that folder")
            }
            append(". ")
            append(summary)
        }

    /** One file inside the picked folder, with the path a CSV would name it by. */
    private class FolderFile(val path: String, val name: String, val uri: Uri, val size: Long)

    /**
     * Lists the picked tree without copying it.
     *
     * Listing first is what lets a CSV be read before anything is copied: its rows are matched
     * against these relative paths, so only the files the dataset actually names are brought over.
     * The depth and entry limits are guards against a mistaken pick of a whole storage volume, not a
     * policy about dataset layout.
     */
    private fun listFolder(tree: Uri): List<FolderFile> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<FolderFile>()
        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH || out.size >= MAX_FOLDER_ENTRIES) return
            val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (child in children) {
                if (out.size >= MAX_FOLDER_ENTRIES) return
                val name = child.name ?: continue
                // Hidden entries are the picker's own bookkeeping (.thumbnails and the like); a
                // folder of thumbnails imported as a photo dataset is worse than an empty import.
                if (name.startsWith(".")) continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path, depth + 1)
                } else {
                    out += FolderFile(path, name, child.uri, child.length())
                }
            }
        }
        walk(root, "", 0)
        return out
    }

    /** The text of a CSV in the picked folder, or null when it cannot be read or is no manifest. */
    private fun readCsv(file: FolderFile): String? {
        if (file.size > MAX_CSV_BYTES) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Skipping ${file.path}: ${file.size} bytes is not a dataset manifest",
            )
            return null
        }
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader().use { it.readText() }
            }
        }.getOrNull()
    }

    /** The canonical split folder named anywhere in a relative import path, or null. */
    private fun splitFolderFor(path: String): String? {
        for (segment in path.split('/')) {
            when (segment.trim().lowercase()) {
                "train", "training" -> return "train"
                "val", "valid", "validation" -> return "validation"
                "test", "testing" -> return "test"
            }
        }
        return null
    }

    private fun copyDocument(uri: Uri, target: File, displayName: String? = null): File? = runCatching {
        // A display name with a space is still the name the CSV beside it will use. Replacing
        // spaces (and every non-ASCII letter) with underscores made the media exist but made the
        // manifest look for a file that did not.
        val name = DatasetReferences.storedName(displayName ?: ImportedText.name(context, uri))
        // The staging folder is per-import, so a collision here is a genuine pair of same-named
        // files from different subfolders; the second is stamped rather than overwriting the first.
        var file = File(target, name)
        if (file.isFile) file = File(target, "${System.currentTimeMillis().toString(36)}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    private fun renderRun(state: ModelCraft.State) {
        // A MULTI-MINUTE TRAINING RUN IS THE LONGEST SUSTAINED LOAD THIS APP STARTS. Without the
        // platform hint, Android boosts to peak clocks for the first seconds and then drops, and
        // that boost is pure heat: it buys nothing for a job that runs for ten minutes. The hint
        // caps the clocks at a level the phone can hold, which is cooler and, on a phone that would
        // otherwise throttle hard, faster overall. Best-effort by construction -- see the governor.
        if (sustainedPerformance != state.running) {
            sustainedPerformance = state.running
            com.prism.launcher.platform.AndroidThermalGovernor.setSustainedPerformance(
                context as? android.app.Activity, state.running,
            )
        }
        val bar = progressBar ?: return
        if (state.running) {
            bar.visibility = View.VISIBLE
            bar.progress = (state.progress.coerceIn(0f, 1f) * 100).roundToInt()
            // The training line carries epoch, step, file, loss and accuracy; the preparation
            // stages have no detail and fall back to their own sentence.
            stageView?.text = state.detail.ifBlank { state.stage }
            trainButton?.isEnabled = false
            trainButton?.alpha = 0.5f
            refreshTestControls(model)
            // THE OUTPUT PANEL BECOMES THE GRAPH WHILE AN AUDIO MODEL TRAINS. It is the one view
            // below the form that is already the size of a chart, and there is no waveform worth
            // testing until the run ends. Music is excluded: its dataset is not a voice.
            val target = model
            if (
                target != null &&
                target.id == state.modelId &&
                target.kind == ModelCraft.Kind.AUDIO &&
                !ModelCraft.isMusic(target)
            ) {
                showVoiceTrainingGraph()
            }
            return
        }
        setTestHolderHeight(200)
        bar.visibility = View.GONE
        trainButton?.isEnabled = true
        trainButton?.alpha = 1f
        // The voices dialog is behind us by the time a run starts: it was opened after the
        // speaker extraction and its OK began this run. What is left is the run's own sentence.
        val note = when {
            state.error != null -> "Failed: ${state.error}"
            state.note.isNotBlank() -> state.note
            else -> model?.note.orEmpty()
        }
        if (note.isNotBlank()) stageView?.text = note
        refreshTestControls(model)
        // A run writes the pack before it trains, so the chips that did not exist when the form
        // opened appear here as soon as the run finishes.
        renderTestVoices(model)
    }

    // ── Testing a built model ───────────────────────────────────────────────

    /** Enables the test box once there is a model to test, and clears it when there is not. */
    private fun refreshTestControls(target: CraftedModel?) {
        val ready = target != null && ModelCraftTest.ready(target)
        val usable = ready && !ModelCraft.state.value.running
        testInput?.isEnabled = usable
        testInput?.hint = if (usable) "Type something to test with" else "Train the model to test it"
        testButton?.isEnabled = usable
        testButton?.alpha = if (usable) 1f else 0.5f
        // While an audio run is training, the output panel belongs to the graph. Replacing it
        // with the placeholder here would tear the chart down on every progress report.
        val graphing = target != null &&
            target.kind == ModelCraft.Kind.AUDIO &&
            !ModelCraft.isMusic(target) &&
            ModelCraft.state.value.running &&
            ModelCraft.state.value.modelId == target.id
        testHintView?.text = when {
            graphing -> "Live voice features from the training clips: pitch contour, rhythm, " +
                "stress, pauses, micro-variation, jitter and shimmer, breathiness, vocal fry, " +
                "expression, source/filter, timbre, identity, duration, coarticulation, " +
                "temporal detail and phonation types."
            target != null && ModelCraftTest.ready(target) -> ModelCraftTest.promptHint(target.kind)
            else -> "Train the model first. Once it has weights, this is where you try them " +
                "without leaving the form."
        }
        if (!usable) {
            stopTestAnimation()
            testCaption?.text = ""
            if (!graphing) showTestPlaceholder(target)
        }
    }

    /**
     * Swaps the output panel for the live voice graph.
     *
     * The panel is made taller for the graph because seven labelled lanes do not fit in the 200 dp
     * used for a waveform; it is restored by [showTestPlaceholder] when the run ends.
     */
    private fun showVoiceTrainingGraph() {
        val holder = testOutputHolder ?: return
        val graph = trainingGraph ?: VoiceTrainingGraphView(context, palette).also {
            trainingGraph = it
        }
        setTestHolderHeight(300)
        if (graph.parent !== holder) {
            holder.removeAllViews()
            holder.addView(
                graph,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        graph.render(VoiceTrainingMonitor.current())
    }

    private fun setTestHolderHeight(heightDp: Int) {
        val holder = testOutputHolder ?: return
        val params = holder.layoutParams as? LinearLayout.LayoutParams ?: return
        val wanted = dp(heightDp)
        if (params.height != wanted) {
            params.height = wanted
            holder.layoutParams = params
        }
    }

    /**
     * The voice buttons above the waveform, one per measured voice.
     *
     * Only speech models that speak through the dataset engine have these: a cloning model's voice
     * is its reference recording, and a Pocket-engine model has no dataset engine to choose from.
     * Selecting a chip sets the voice for the next Test; when there is a sentence in the box, it is
     * spoken again at once so the difference is audible without another tap.
     */
    private fun renderTestVoices(target: CraftedModel?) {
        val holder = voiceStripHolder ?: return
        holder.removeAllViews()
        if (target == null || target.kind != ModelCraft.Kind.AUDIO) return
        if (ModelCraft.audioType(target) == ModelCraft.AudioType.VOICE_CLONING) return
        if (!ModelCraft.usesBuiltInVoiceEngine(target)) return
        // The engine, not the pack, because its rows are what [DatasetVoiceEngine.Engine.speakVoice]
        // can actually resolve. A pack that was never measured is not a set of voices to test.
        val engine = runCatching { DatasetVoiceEngine.load(target) }.getOrNull() ?: return
        val voices = engine.voices
        if (voices.isEmpty()) return
        if (testVoice != null && voices.none { it.name == testVoice || it.file == testVoice }) {
            testVoice = null
        }

        holder.addView(TextView(context).apply {
            text = "VOICE"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(2), dp(18), dp(5))
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        val chips = mutableListOf<Pair<DatasetVoiceEngine.Voice, TextView>>()
        fun paint(chip: TextView, selected: Boolean) {
            chip.setTextColor(
                if (selected) palette.onAccent.toInt() else palette.text.toInt(),
            )
            chip.background = rounded(
                if (selected) palette.accent.toInt() else palette.raised.toInt(), 999,
            )
        }
        for (voice in voices) {
            val chip = TextView(context).apply {
                text = buildString {
                    append(voice.name)
                    if (voice.gender.isNotBlank()) {
                        append(" · ")
                        append(voice.gender)
                    }
                    if (voice.language.isNotBlank() && !voice.language.startsWith("en", true)) {
                        append(" · ")
                        append(voice.language)
                    }
                }
                contentDescription = "Test in ${voice.name}, ${voice.gender}, ${voice.language}"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    testVoice = voice.name
                    chips.forEach { (otherVoice, otherChip) ->
                        paint(otherChip, otherVoice.name == voice.name)
                    }
                    if (!ModelCraft.state.value.running && !testInput?.text.isNullOrBlank()) {
                        runTest()
                    }
                }
            }
            paint(chip, voice.name == testVoice)
            chips += voice to chip
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36),
            ).apply { setMargins(0, 0, dp(6), 0) })
        }
        holder.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun showTestPlaceholder(target: CraftedModel?) {
        val holder = testOutputHolder ?: return
        setTestHolderHeight(200)
        holder.removeAllViews()
        holder.addView(TextView(context).apply {
            text = if (target != null && ModelCraftTest.ready(target)) {
                "Nothing tested yet — type below and press Test."
            } else {
                "No model to test yet."
            }
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(palette.faint.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })
    }

    /**
     * Runs the test off the UI thread.
     *
     * A generation is not instant even at these sizes, and every trainer here is CPU-bound: doing
     * it on the main thread is a frozen form rather than a slow one. The run counter makes a second
     * press discard the first run's answer instead of letting a stale result replace a newer one.
     */
    private fun runTest() {
        val target = model ?: return
        if (!ModelCraftTest.ready(target)) return
        persistForm()
        val prompt = testInput?.text?.toString().orEmpty()
        val voice = testVoice
        val run = ++testRun
        testCaption?.setTextColor(palette.muted.toInt())
        testCaption?.text = "Testing…"
        testButton?.isEnabled = false
        testButton?.alpha = 0.5f
        scope.launch(Dispatchers.Default) {
            val result = runCatching { ModelCraftTest.run(target, prompt, voice) }
            launch(Dispatchers.Main) {
                if (run != testRun) return@launch
                testButton?.isEnabled = true
                testButton?.alpha = 1f
                result.onSuccess { showTestOutput(it) }.onFailure { failure ->
                    testCaption?.setTextColor(palette.destructive.toInt())
                    testCaption?.text = failure.message ?: "The test could not run."
                }
            }
        }
    }

    private fun showTestOutput(output: ModelCraftTest.Output) {
        val holder = testOutputHolder ?: return
        stopTestAnimation()
        setTestHolderHeight(200)
        holder.removeAllViews()
        testCaption?.setTextColor(palette.muted.toInt())
        testCaption?.text = output.caption
        when (output) {
            is ModelCraftTest.Output.Text -> {
                val scroll = ScrollView(context)
                scroll.addView(TextView(context).apply {
                    text = output.text
                    textSize = 12f
                    setTextColor(palette.text.toInt())
                    setTextIsSelectable(true)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                })
                holder.addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
                ))
            }
            is ModelCraftTest.Output.Film -> {
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(pictureBitmap(output.frames.first()))
                    (drawable as? BitmapDrawable)?.isFilterBitmap = false
                }
                holder.addView(image, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
                ))
                if (output.frames.size > 1 && output.fps > 0) {
                    var index = 0
                    val delay = (1_000L / output.fps).coerceAtLeast(40L)
                    val tick = object : Runnable {
                        override fun run() {
                            if (!isAttachedToWindow || testOutputHolder?.getChildAt(0) !== image) {
                                return
                            }
                            index = (index + 1) % output.frames.size
                            image.setImageBitmap(pictureBitmap(output.frames[index]))
                            (image.drawable as? BitmapDrawable)?.isFilterBitmap = false
                            handler.postDelayed(this, delay)
                        }
                    }
                    testAnimation = tick
                    handler.postDelayed(tick, delay)
                }
            }
            is ModelCraftTest.Output.Sound -> {
                val waveform = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(pictureBitmap(output.waveform))
                    (drawable as? BitmapDrawable)?.isFilterBitmap = false
                }
                holder.addView(waveform, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                ))
                val play = pill("Play").apply {
                    setOnClickListener {
                        isEnabled = false
                        alpha = 0.5f
                        scope.launch(Dispatchers.IO) {
                            val problem = PrismPlatform.audio.play(output.samples, output.sampleRate)
                            launch(Dispatchers.Main) {
                                isEnabled = true
                                alpha = 1f
                                if (problem != null) {
                                    testCaption?.setTextColor(palette.destructive.toInt())
                                    testCaption?.text = problem
                                }
                            }
                        }
                    }
                }
                holder.addView(play, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(6)
                })
            }
        }
    }

    private fun stopTestAnimation() {
        testAnimation?.let { handler.removeCallbacks(it) }
        testAnimation = null
    }

    private fun pictureBitmap(picture: ModelCraftTest.Picture): Bitmap =
        Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)

    // ── Voices ───────────────────────────────────────────────────────────────

    /** One row being edited: the three answers, plus the recording that is the voice. */
    private class VoiceDraft(
        var name: String,
        var gender: String,
        var language: String,
        val source: VoicePack.Voice,
    )

    /**
     * The voices dialog: the pack, or the proposal for one, as a table the user edits.
     *
     * Speaker, gender and language are the three columns because those are the answers a recording
     * cannot give about itself. Tapping a row opens them; OK writes the WAVs and the table into
     * `voices/` and points `tts_model.json` at them, so the pack is what Prism speaks through and
     * what another machine's engine opens without Prism at all.
     */
    /**
     * Does the speech extraction, then opens the voices dialog, then starts the run on OK.
     *
     * This is the order the feature was asked for: the dialog is the FIRST thing after the voice
     * data is extracted, not something that appears once training has already chosen voices. A
     * failed extraction is reported here and no run starts -- training on a dataset whose speaker
     * information could not be read would only produce a model with the wrong engine.
     */
    private fun beginVoicesThenTrain(target: CraftedModel) {
        if (voicesPreparing || ModelCraft.state.value.running) return
        if (target.dataset.isEmpty()) {
            stageView?.text = "Import a dataset first — there is nothing to build voices from."
            Toast.makeText(context, "Import a dataset first", Toast.LENGTH_SHORT).show()
            return
        }
        voicesPreparing = true
        trainButton?.isEnabled = false
        trainButton?.alpha = 0.5f
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        stageView?.text = "Listening to the dataset before the voices dialog…"
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                ModelCraft.prepareVoices(target) { line, fraction ->
                    scope.launch(Dispatchers.Main) {
                        stageView?.text = line
                        progressBar?.progress =
                            (fraction.coerceIn(0f, 1f) * 100f).roundToInt()
                    }
                }
            }
            launch(Dispatchers.Main) {
                voicesPreparing = false
                trainButton?.isEnabled = true
                trainButton?.alpha = 1f
                progressBar?.visibility = View.GONE
                result.onSuccess { voices ->
                    if (voices.isEmpty()) {
                        stageView?.text =
                            "No voice recordings were found, so there is nothing to build an " +
                                "engine from. Import audio with transcripts first."
                        Toast.makeText(context, "No voices to build", Toast.LENGTH_SHORT).show()
                    } else {
                        showVoiceDialog(fresh = true) { ModelCraft.train(target) }
                    }
                }.onFailure { failure ->
                    val message = failure.message ?: "The voice data could not be prepared."
                    stageView?.text = message
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showVoiceDialog(
        fresh: Boolean = false,
        onConfirmed: (() -> Unit)? = null,
    ) {
        val target = model ?: return
        voiceDialog?.dismiss()
        // `fresh` is a finished run: the new model chose its own recordings, so the dialog proposes
        // those rather than naming the pack a previous run wrote. The button reads the pack back.
        val drafts = (if (fresh) VoicePack.proposal(target)
        else VoicePack.packaged(target).ifEmpty { VoicePack.proposal(target) })
            .map { voice -> VoiceDraft(voice.name, voice.gender, voice.language, voice) }
            .toMutableList()
        val requested = ModelCraft.voiceCount(target) ?: 1

        val dialog = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 20)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        root.addView(TextView(context).apply {
            text = "Voices"
            textSize = 20f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = "The $requested voice(s) this model should speak in, one recording each. Tap a " +
                "row to change the speaker's name, gender or language. OK writes them into voices/ " +
                "beside the model, measures them into the dataset voice engine, and starts training. " +
                "The same WAVs and table work in any other speech tool."
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(0, dp(6), 0, dp(10))
            setLineSpacing(0f, 1.15f)
        })

        if (drafts.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "This model has no recordings to package yet. Import audio with transcripts, " +
                    "then train it."
                textSize = 12f
                setTextColor(palette.faint.toInt())
                setPadding(0, dp(4), 0, dp(12))
                setLineSpacing(0f, 1.15f)
            })
        } else {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), 0, dp(4), dp(5))
            }
            listOf("SPEAKER" to 0.46f, "GENDER" to 0.24f, "LANGUAGE" to 0.18f, "" to 0.12f).forEach { (label, weight) ->
                header.addView(TextView(context).apply {
                    text = label
                    textSize = 9f
                    letterSpacing = 0.1f
                    setTextColor(palette.faint.toInt())
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
            }
            root.addView(header)

            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(context).apply {
                isFillViewport = false
                addView(list, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            root.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300),
            ))

            drafts.forEachIndexed { index, draft ->
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                }
                val nameView = TextView(context).apply {
                    text = draft.name.ifBlank { "Unnamed voice" }
                    textSize = 13f
                    setTextColor(palette.text.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val genderView = TextView(context).apply {
                    text = draft.gender.ifBlank { "—" }
                    textSize = 12f
                    setTextColor(palette.muted.toInt())
                }
                val languageView = TextView(context).apply {
                    text = draft.language.ifBlank { "—" }
                    textSize = 12f
                    setTextColor(palette.muted.toInt())
                }
                val editView = TextView(context).apply {
                    text = "Edit"
                    textSize = 11f
                    setTextColor(palette.accent.toInt())
                }
                val summary = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                }
                summary.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f))
                summary.addView(genderView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.24f))
                summary.addView(languageView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.18f))
                summary.addView(editView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.12f))

                val editor = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(0, dp(8), 0, dp(2))
                }
                val fields = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                fields.addView(voiceField("SPEAKER NAME", draft.name) { value ->
                    draft.name = value
                    nameView.text = value.ifBlank { "Unnamed voice" }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f).apply {
                    setMargins(0, 0, dp(6), 0)
                })
                fields.addView(voiceField("GENDER", draft.gender) { value ->
                    draft.gender = value
                    genderView.text = value.ifBlank { "—" }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.24f).apply {
                    setMargins(0, 0, dp(6), 0)
                })
                fields.addView(voiceField("LANGUAGE", draft.language) { value ->
                    draft.language = value
                    languageView.text = value.ifBlank { "—" }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.18f))
                editor.addView(fields)
                editor.addView(TextView(context).apply {
                    text = "From ${draft.source.clip.ifBlank { "the dataset" }}" +
                        (if (draft.source.clips > 1) " · ${draft.source.clips} clips in this voice" else "") +
                        " · gender is female, male or neutral; language is a code such as en or es."
                    textSize = 9f
                    setTextColor(palette.faint.toInt())
                    setPadding(0, dp(5), 0, 0)
                })
                summary.setOnClickListener {
                    val open = editor.visibility != View.VISIBLE
                    editor.visibility = if (open) View.VISIBLE else View.GONE
                    editView.text = if (open) "Close" else "Edit"
                }
                container.addView(summary)
                container.addView(editor)
                list.addView(container)
                if (index != drafts.lastIndex) {
                    list.addView(View(context).apply {
                        setBackgroundColor(palette.divider.toInt())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1),
                    ))
                }
            }
            if (drafts.size < requested) {
                root.addView(TextView(context).apply {
                    text = "The dataset holds ${drafts.size} distinct recording(s), so " +
                        "${if (drafts.size == 1) "one voice" else "${drafts.size} voices"} can be " +
                        "built rather than $requested. Add more speakers to build the rest."
                    textSize = 10f
                    setTextColor(palette.faint.toInt())
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(0f, 1.15f)
                })
            }
        }

        root.addView(TextView(context).apply {
            text = "Where they go: ${VoicePack.directory(target).absolutePath}"
            textSize = 9f
            setTextColor(palette.faint.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = pill("Cancel").apply {
            setOnClickListener { dialog.dismiss() }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(0, 0, dp(8), 0)
        })
        // Declared before its listener: the listener disables and re-enables this button, and a
        // local read inside its own initializer is not in scope.
        val ok = pill(
            if (onConfirmed != null) "OK — build engine and train" else "OK — rebuild engine",
            accent = true,
        )
        ok.isEnabled = drafts.isNotEmpty()
        ok.alpha = if (drafts.isNotEmpty()) 1f else 0.5f
        ok.setOnClickListener {
            if (drafts.any { it.name.isBlank() }) {
                Toast.makeText(context, "Every voice needs a speaker name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ok.isEnabled = false
            ok.alpha = 0.5f
            val voices = drafts.map { draft ->
                draft.source.copy(
                    name = draft.name.trim(),
                    gender = draft.gender.trim(),
                    language = draft.language.trim(),
                )
            }
            scope.launch(Dispatchers.IO) {
                val result = runCatching { VoicePack.write(target, voices) }
                launch(Dispatchers.Main) {
                    result.onSuccess { packaged ->
                        stageView?.text = packaged.summary
                        Toast.makeText(
                            context,
                            "${packaged.voices.size} voice(s) packaged",
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                        // The engine is built by VoicePack.write; only now may the run start.
                        onConfirmed?.invoke()
                    }.onFailure { failure ->
                        ok.isEnabled = true
                        ok.alpha = 1f
                        val message = failure.message ?: "The voices could not be written."
                        stageView?.text = message
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        buttons.addView(ok, LinearLayout.LayoutParams(0, dp(44), 1.2f))
        root.addView(buttons)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
        )
        // A table needs room for three columns. Without this the dialog is as wide as its widest
        // short label and the weighted fields are squeezed into each other.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.setOnDismissListener {
            if (voiceDialog === dialog) voiceDialog = null
        }
        voiceDialog = dialog
        dialog.show()
    }

    /** A labelled box inside an expanded voice row; edits are read on OK, not per keystroke. */
    private fun voiceField(label: String, value: String, onChange: (String) -> Unit): LinearLayout {
        val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        cell.addView(TextView(context).apply {
            text = label
            textSize = 9f
            letterSpacing = 0.1f
            setTextColor(palette.faint.toInt())
        })
        val field = EditText(context).apply {
            setText(value)
            textSize = 12f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            background = rounded(palette.raised.toInt(), 8)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    onChange(text?.toString().orEmpty())
                }
                override fun afterTextChanged(text: android.text.Editable?) {}
            })
        }
        cell.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38),
        ).apply { setMargins(0, dp(3), 0, 0) })
        return cell
    }

    // ── Small widgets ────────────────────────────────────────────────────────

    private fun section(title: String): View = TextView(context).apply {
        text = title
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(palette.faint.toInt())
        setPadding(dp(18), dp(16), dp(18), dp(6))
    }

    private fun spinnerParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
    ).apply { setMargins(dp(16), dp(2), dp(16), dp(2)) }

    private fun spinner(
        labels: List<String>,
        hint: String,
        onSelect: (Int) -> Unit,
    ): Spinner = Spinner(context).apply {
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
            private fun row(position: Int, convert: View?): View {
                val view = convert as? TextView ?: TextView(context).apply {
                    textSize = 13f
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                view.text = labels[position]
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
        dropDownWidth = dp(280)
        contentDescription = hint
        setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) = onSelect(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        background = rounded(palette.raised.toInt(), 10)
    }

    private fun pill(text: String, accent: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(if (accent) palette.onAccent.toInt() else palette.text.toInt())
        background = rounded(
            if (accent) palette.accent.toInt() else palette.raised.toInt(), 999,
        )
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** How deep a picked folder is searched for its CSV. A dataset is not nested deeper. */
        const val MAX_FOLDER_DEPTH = 12

        /** How many entries the folder listing holds, so a mistaken root pick cannot stall on it. */
        const val MAX_FOLDER_ENTRIES = 20_000

        /** A CSV past this size is not a manifest; reading it would be a file copy through memory. */
        const val MAX_CSV_BYTES = 32L * 1024 * 1024

        /** Radio-button ids, offset so they cannot collide with a generated view id. */
        const val RADIO_ID_BASE = 1000

        /**
         * The model-files group's own id range. Separate from [RADIO_ID_BASE] because an audio
         * model has BOTH groups on its form, and two radio buttons with the same id in one view
         * hierarchy is a group that reports the other group's selection back.
         */
        const val FORMAT_ID_BASE = 1200

        /** Music form fields, saved by [persistForm] but only shown for the music audio type. */
        val MUSIC_KEYS = listOf(
            "instruments", "singing_voices", "genre", "tempo_bpm", "key", "music_seconds",
            "music_temperature", "music_top_k",
        )
    }
}
