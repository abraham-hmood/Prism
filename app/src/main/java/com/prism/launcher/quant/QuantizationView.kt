package com.prism.launcher.quant

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.nora.IosUi
import com.prism.launcher.quant.PrismQuantizer.Level
import java.io.File

/**
 * The quantisation half of the models page: pick a model, pick a level, run it.
 *
 * ## Two radio groups, not a spinner each
 *
 * `RadioGroup` is what the request asked for and it is also the right control: both lists are short,
 * every option should be readable without a tap, and the mutual exclusion is the point -- one model,
 * one target. It gives "clicking one unchecks the rest" for free rather than having it hand-managed,
 * which is where that behaviour usually goes wrong.
 *
 * ## Built in code
 *
 * The lists are generated from [PrismSettings.getImportedModels] and [Level], both of which change --
 * models as the user imports them, levels if another is added to the quantiser. An XML layout would
 * have to be kept in step by hand with an enum that already knows its own contents.
 *
 * ## It shows what the run is doing, not that something is happening
 *
 * The progress figure comes from [QuantizationService]'s state, which measures the output file
 * growing. When a run is in flight the page reattaches to it: the service holds the state
 * process-wide, so swiping away and back finds the same run rather than an idle screen.
 */
class QuantizationView(context: Context) : FrameLayout(context) {

    private val modelGroup = RadioGroup(context)
    private val levelGroup = RadioGroup(context)
    private val runButton = IosUi.filledButton(context, "Quantise")
    private val exportButton = IosUi.tintedButton(context, "Export…")
    private val statusText = TextView(context)
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val emptyText = TextView(context)

    /** Asked to hand [File] to the user through the system file picker. */
    var onExportRequested: ((File) -> Unit)? = null

    /**
     * Where the candidate models come from.
     *
     * SUPPLIED BY THE HOST PAGE rather than read from settings here, so this list and the Models list
     * cannot disagree about what exists. They did: the Models page reconciles the registry against
     * what is actually in `filesDir/models` (registering strays, dropping entries whose file is gone)
     * and this read the raw registry, so a model visible one tap away was missing here. Defaults to
     * the registry for the case where nothing sets it.
     */
    var modelSource: () -> List<PrismSettings.ImportedModel> = { PrismSettings.getImportedModels() }

    private var models: List<PrismSettings.ImportedModel> = emptyList()

    /** Paths currently drawn as radio buttons; null until the first build. See [refreshModelsInner]. */
    private var renderedPaths: List<String>? = null

    /** The finished model from the last successful run, for [exportButton]. */
    private var exportable: File? = null

    init {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(IosUi.groupedBackground(context))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(context, 16f)
            setPadding(pad, IosUi.dp(context, 8f), pad, IosUi.dp(context, 32f))
        }

        column.addView(IosUi.sectionHeader(context, "LOCAL MODEL"))
        emptyText.apply {
            text = "No local GGUF models found. Quantising reads a GGUF file, so import one first."
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(IosUi.dp(context, 16f), IosUi.dp(context, 12f), IosUi.dp(context, 16f), IosUi.dp(context, 12f))
            visibility = View.GONE
        }
        column.addView(emptyText)
        column.addView(IosUi.card(context).apply { addView(modelGroup) })

        column.addView(IosUi.sectionHeader(context, "QUANTISATION"))
        column.addView(IosUi.card(context).apply { addView(levelGroup) })
        column.addView(
            IosUi.sectionFooter(
                context,
                "Quantising rewrites every tensor at the chosen precision. It runs in the " +
                    "background and takes minutes; a notification tracks it and can stop it."
            )
        )

        statusText.apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(IosUi.dp(context, 16f), IosUi.dp(context, 12f), IosUi.dp(context, 16f), 0)
        }
        column.addView(statusText)

        progressBar.apply {
            max = 100
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = IosUi.dp(context, 10f)
                marginStart = IosUi.dp(context, 16f)
                marginEnd = IosUi.dp(context, 16f)
            }
            layoutParams = lp
        }
        column.addView(progressBar)

        runButton.apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(context, 18f) }
            layoutParams = lp
            setOnClickListener { startRun() }
        }
        column.addView(runButton)

        exportButton.apply {
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(context, 10f) }
            layoutParams = lp
            setOnClickListener {
                val file = exportable
                if (file == null || !file.isFile) {
                    Toast.makeText(context, "That model is no longer on disk", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onExportRequested?.invoke(file)
            }
        }
        column.addView(exportButton)

        scroll.addView(column)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        modelGroup.setOnCheckedChangeListener { _, _ -> onSelectionChanged() }

        buildLevels()
        refreshModels()
    }

    /**
     * Re-reads the model list.
     *
     * Called whenever the page becomes visible, because a model can be imported or deleted from
     * Settings while this sits in the pager -- including by a quantisation that just finished, whose
     * result should appear here without the user having to go looking for it.
     */
    fun refreshModels() {
        try {
            refreshModelsInner()
        } catch (t: Throwable) {
            // Whatever goes wrong here, the list must still say something true. Left unhandled this
            // produced the worst possible outcome: a blank card with no rows AND no empty-state
            // text, because the failure landed between emptying the group and setting either
            // visibility -- so the screen showed nothing and reported nothing.
            com.prism.launcher.PrismLogger.logError(TAG, "Could not build the model list", t)
            renderedPaths = null
            emptyText.text = "Could not read the model list: ${t.javaClass.simpleName}"
            emptyText.visibility = View.VISIBLE
            modelGroup.visibility = View.GONE
        }
    }

    private fun refreshModelsInner() {
        val checkedPath = models.getOrNull(modelGroup.checkedIndex(MODEL_ID_BASE))?.path

        // GGUF ONLY, DECIDED BY THE FILE'S MAGIC BYTES, not by its name.
        //
        // The quantiser is llama.cpp's, so a GGUF is the only thing it can read, and the imported
        // list is not all GGUF -- it also holds diffusion weights. But filtering on a `.gguf`
        // extension was wrong and emptied this list: imports keep whatever name the source had, and
        // plenty of perfectly good models arrive without the extension. The first four bytes are
        // "GGUF" whatever the file is called, which is why GgufInferenceService already checks them
        // before loading anything.
        val next = modelSource().filter { model ->
            File(model.path).isFile && GgufInferenceService.isGgufFile(model.path)
        }

        // NOTHING TO DO IF NOTHING CHANGED, and that is a correctness guard rather than an
        // optimisation. Rebuilding the radio buttons schedules a layout pass, and this is called
        // from callbacks a layout pass can itself deliver -- so an unconditional rebuild can feed
        // itself forever, which is exactly what it did. Comparing paths means a repeat call is inert
        // and the cycle cannot start.
        if (renderedPaths == next.map { it.path }) return
        renderedPaths = next.map { it.path }
        models = next
        modelGroup.removeAllViews()

        models.forEachIndexed { index, model ->
            val file = File(model.path)
            modelGroup.addView(
                radio(
                    id = MODEL_ID_BASE + index,
                    title = model.displayName,
                    detail = "${formatSize(file.length())} · ${file.name}",
                )
            )
        }

        emptyText.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        modelGroup.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE

        // Keep the user's selection across a refresh where possible; a list that silently reset
        // every time the page was revisited would be unusable.
        val restored = models.indexOfFirst { it.path == checkedPath }
        when {
            restored >= 0 -> modelGroup.check(MODEL_ID_BASE + restored)
            models.isNotEmpty() -> modelGroup.check(MODEL_ID_BASE)
        }

        updateRunButton()
    }

    private fun buildLevels() {
        Level.entries.forEachIndexed { index, level ->
            levelGroup.addView(
                radio(
                    id = LEVEL_ID_BASE + index,
                    title = level.label,
                    detail = buildString {
                        append(String.format(java.util.Locale.US, "~%.2f bits/weight", level.bitsPerWeight))
                        if (level.note.isNotBlank()) append(" · ").append(level.note)
                    },
                )
            )
        }
        levelGroup.check(LEVEL_ID_BASE + Level.Q4_K_M.ordinal)
    }

    /**
     * Re-renders when the chosen model changes, so the export button names what it will actually
     * write rather than whatever was selected when the view was built.
     */
    private fun onSelectionChanged() {
        render(QuantizationService.state.value)
    }

    /**
     * One option, as a RadioButton that is a DIRECT child of its group.
     *
     * That is load-bearing rather than stylistic: RadioGroup only enforces mutual exclusion over
     * RadioButtons it owns directly. Wrapping each one in a row to get a two-line label -- the
     * obvious way to lay this out -- silently breaks the one behaviour the control exists for, and
     * leaves every option checkable at once. So the two lines are built as one styled string
     * instead, and the button stays where the group can see it.
     */
    private fun radio(id: Int, title: String, detail: String): RadioButton {
        val text = if (detail.isBlank()) title else "$title\n$detail"
        val styled = android.text.SpannableString(text)
        if (detail.isNotBlank()) {
            val from = title.length + 1
            styled.setSpan(
                android.text.style.RelativeSizeSpan(0.75f), from, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                android.text.style.ForegroundColorSpan(IosUi.secondaryLabel(context)), from, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        return RadioButton(context).apply {
            this.id = id
            this.text = styled
            textSize = 16f
            setTextColor(IosUi.label(context))
            buttonTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            setPadding(
                IosUi.dp(context, 10f), IosUi.dp(context, 10f),
                IosUi.dp(context, 12f), IosUi.dp(context, 10f),
            )
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /**
     * Reflects a run's state.
     *
     * Driven by the service rather than by the button press, so the display is correct whether this
     * view started the run, another instance of the page did, or the process was rebuilt while it
     * was going.
     */
    fun render(state: QuantizationService.State) {
        runButton.isEnabled = !state.running && models.isNotEmpty()
        runButton.alpha = if (runButton.isEnabled) 1f else 0.4f
        runButton.text = if (state.running) "Quantising…" else "Quantise"

        progressBar.visibility = if (state.running) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = state.running && state.percent <= 0
        if (state.percent > 0) progressBar.progress = state.percent

        statusText.text = when {
            state.running ->
                "${state.modelName} → ${state.level?.fileSuffix} · ${state.percent}%" +
                    if (state.bytesWritten > 0) " (${formatSize(state.bytesWritten)} written)" else ""
            state.error != null -> "Last run failed: ${state.error}"
            state.finished && state.outputPath != null ->
                "${state.modelName} → ${state.level?.fileSuffix} finished and was added to your models."
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // Prefers the run that just finished, and falls back to whatever is selected.
        //
        // The fallback is what makes this survive a restart. Tying the button solely to the last
        // run's output means a model quantised yesterday -- sitting right there in the list above --
        // cannot be exported at all, because the service's state is empty in a fresh process. Since
        // the result is imported as an ordinary model, selecting it and exporting is the same
        // operation, and one that keeps working.
        val finished = state.outputPath?.let(::File)?.takeIf { it.isFile }
        val selected = models.getOrNull(modelGroup.checkedIndex(MODEL_ID_BASE))
            ?.let { File(it.path) }?.takeIf { it.isFile }

        exportable = finished ?: selected
        val target = exportable
        exportButton.visibility = if (target != null && !state.running) View.VISIBLE else View.GONE
        exportButton.text = if (target != null) "Export ${target.name}" else "Export…"
    }

    private fun updateRunButton() {
        runButton.isEnabled = models.isNotEmpty()
        runButton.alpha = if (runButton.isEnabled) 1f else 0.4f
    }

    private fun startRun() {
        val model = models.getOrNull(modelGroup.checkedIndex(MODEL_ID_BASE))
        if (model == null) {
            Toast.makeText(context, "Choose a model first", Toast.LENGTH_SHORT).show()
            return
        }
        val level = Level.entries.getOrNull(levelGroup.checkedIndex(LEVEL_ID_BASE))
        if (level == null) {
            Toast.makeText(context, "Choose a quantisation first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PrismQuantizer.isAvailable()) {
            Toast.makeText(context, "The native quantiser is unavailable on this device", Toast.LENGTH_LONG).show()
            return
        }

        QuantizationService.start(context, File(model.path), level)
        Toast.makeText(context, "Quantising ${model.displayName} to ${level.fileSuffix}…", Toast.LENGTH_SHORT).show()
    }

    /**
     * The checked option's index within its list, or -1.
     *
     * Ids are the index plus a per-group base rather than the bare index, because a view id of 0 is
     * indistinguishable from "no id" in enough of the framework to be worth avoiding, and two groups
     * in one hierarchy must not hand out the same ids.
     */
    private fun RadioGroup.checkedIndex(base: Int): Int {
        val checked = checkedRadioButtonId
        return if (checked < base) -1 else checked - base
    }

    private companion object {
        const val TAG = "PrismQuantView"
        const val MODEL_ID_BASE = 0x51_00_00
        const val LEVEL_ID_BASE = 0x52_00_00
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
