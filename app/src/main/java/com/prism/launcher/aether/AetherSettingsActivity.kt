package com.prism.launcher.aether

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * Everything about Aether, in one place -- same shape as `NoraSettingsActivity` (reused `IosUi`
 * directly rather than duplicating it, since it's a plain public design-system object with no
 * Nora-specific state). Aether's connectome is now resizable, same as Nora's -- see
 * [AetherGeometry] and [buildSizeSection]/[buildRegionSection] below, ported from Nora's own
 * size UI once Aether stopped being a fixed architecture.
 */
class AetherSettingsActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout
    private lateinit var statusLine: TextView

    /** One collapsible tuning-group section: its clickable header, its card of rows, and whether
     * it's currently expanded. Natural resting state is collapsed ([expanded] starts false); a
     * live filter search overrides this display-wise without mutating it (see [applyTuningVisibility]). */
    private class TuningGroupInfo(val header: TextView, val card: LinearLayout, val baseLabel: String) {
        var expanded: Boolean = false
    }
    private val tuningGroups = mutableListOf<TuningGroupInfo>()

    /** One tuning row: its view, the plain-text row content to search against, and the group it belongs to. */
    private class TuningRowEntry(val row: View, val searchText: String, val group: TuningGroupInfo)
    private val tuningRows = mutableListOf<TuningRowEntry>()
    private var tuningFilterQuery: String = ""

    private lateinit var knowledgeListContainer: LinearLayout
    private lateinit var annBaselineContainer: LinearLayout
    /** Picking a dashboard brain image ([AetherTuning.dashboardBrainImage]'s "browse" button, in
     * [stringTuningRow]) -- must be registered here at construction time, not inside the
     * dynamically-built tuning row, since [registerForActivityResult] requires the activity not
     * yet be started. Mirrors `NoraSettingsActivity`'s `importPicker`. */
    private val brainImagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBrainImage(uri)
        }

    private fun importBrainImage(uri: android.net.Uri) {
        try {
            val dir = java.io.File(filesDir, "aether/brain_images").apply { mkdirs() }
            val ext = contentResolver.getType(uri)?.substringAfter('/')?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "png"
            val dest = java.io.File(dir, "dashboard_brain.$ext")
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "Couldn't read that image.", Toast.LENGTH_SHORT).show()
                return
            }
            input.use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
            AetherTuning.dashboardBrainImage = dest.absolutePath
            AetherTuning.save()
            Toast.makeText(this, "Brain image updated.", Toast.LENGTH_SHORT).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val knowledgeRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val knowledgeRefreshRunnable = object : Runnable {
        override fun run() {
            refreshKnowledgeList()
            knowledgeRefreshHandler.postDelayed(this, 3000)
        }
    }

    /** Rows that need redrawing after any geometry change, since all of them are coupled. */
    private val sizeFields = ArrayList<Pair<EditText, () -> Long>>()
    private lateinit var costLine: TextView

    private lateinit var ramSlider: android.widget.SeekBar
    private lateinit var ramLabel: TextView
    private var sliderMinBytes = 0L
    private var sliderMaxBytes = 0L

    /** Guards the slider against reacting to its own programmatic repositioning. */
    private var suppressSlider = false

    companion object {
        const val SLIDER_STEPS = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(ctx))
        }
        root.addView(TextView(ctx).apply {
            text = "Aether"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(ctx))
            setPadding(IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 4f))
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        AetherTuning.load()

        buildAboutSection()
        buildSizeSection()
        buildRegionSection()
        buildMemorySection()
        buildRoutesSection()
        buildAnnBaselineSection()
        buildKnowledgeSharingSection()
        buildTrainingSection()
        buildTuningSection()
        buildDataSection()
        refreshStatus()
        refreshSizes()
    }

    override fun onResume() {
        super.onResume()
        knowledgeRefreshHandler.post(knowledgeRefreshRunnable)
        refreshAnnBaselineSection()
    }

    override fun onPause() {
        super.onPause()
        knowledgeRefreshHandler.removeCallbacks(knowledgeRefreshRunnable)
    }

    // ── About ───────────────────────────────────────────────────────────────

    private fun buildAboutSection() {
        content.addView(IosUi.sectionHeader(this, "STATUS"))
        val card = IosUi.card(this)
        statusLine = TextView(this).apply {
            textSize = 14f
            setTextColor(IosUi.label(this@AetherSettingsActivity))
            setPadding(IosUi.dp(this@AetherSettingsActivity, 16f), IosUi.dp(this@AetherSettingsActivity, 12f), IosUi.dp(this@AetherSettingsActivity, 16f), IosUi.dp(this@AetherSettingsActivity, 12f))
        }
        card.addView(statusLine)
        content.addView(card)
        content.addView(spacer())
    }

    private fun refreshStatus() {
        // Cheap checks only -- AetherService (its own process) is the only place that should
        // ever build a resident connectome. See AetherStudio.hasTrainedWeights's doc comment.
        val trained = AetherStudio.hasTrainedWeights()
        statusLine.text = buildString {
            append(if (trained) "Connectome loaded.\n" else "Untrained -- fresh from infancy.\n")
            append(AetherStudio.status())
            append("\nDataset: ${AetherStudio.datasetSize()} sample(s) (images + text chunks) in ${AetherConfig.datasetDir().absolutePath}")
        }
    }

    // ── Size ────────────────────────────────────────────────────────────────

    private fun buildSizeSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "BRAIN SIZE"))
        val card = IosUi.card(ctx)

        card.addView(
            numberRow(
                title = "Total neurons",
                detail = "Resizes the whole hierarchy together, keeping the proportions the " +
                    "architecture was tuned at.",
                read = { AetherConfig.geometry.totalNeurons },
                write = { AetherConfig.geometry.withTotalNeurons(it) },
                actionDescription = "Use this device's maximum",
                onAction = { applyDeviceMaximum() }
            )
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(memoryRow())

        content.addView(card)

        costLine = IosUi.sectionFooter(ctx, "")
        content.addView(costLine)
        content.addView(spacer())
    }

    /** The nine free knobs; everything else in [AetherGeometry.REGION_LABELS] is fixed or coupled and shown read-only in the footer instead. */
    private val editableRegions = listOf("visual", "temporal", "parietal", "hippocampus", "executive", "amygdala")

    private fun buildRegionSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "REGIONS"))
        val card = IosUi.card(ctx)
        for ((i, region) in editableRegions.withIndex()) {
            if (i > 0) card.addView(IosUi.hairline(ctx))
            card.addView(
                numberRow(
                    title = AetherGeometry.REGION_LABELS[region] ?: region,
                    detail = "Resizes this region's neuron count directly.",
                    read = { AetherConfig.geometry.neuronsByRegion()[region]?.toLong() ?: 0L },
                    write = { AetherConfig.geometry.withRegionNeurons(region, it) }
                )
            )
        }
        content.addView(card)
        val fixed = AetherGeometry.REGION_LABELS.keys - editableRegions.toSet()
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Visual resizes its three conv stages together (channel counts) plus its dense " +
                    "output; the others change a single width. Values snap to the nearest legal " +
                    "size.\n\nFixed/coupled (not independently editable): " +
                    fixed.joinToString(", ") { AetherGeometry.REGION_LABELS[it] ?: it } + "."
            )
        )
        content.addView(spacer())
    }

    /**
     * The memory slider: pick how much RAM Aether may use, and get the largest brain that fits.
     *
     * Unlike Nora's equivalent slider, the ceiling here is physical RAM actually free right now
     * ([AetherGeometry.memoryBudgetBytes]), not the ART heap cap -- Aether's tensors are meant to
     * move off the managed heap (see the off-heap/swap work), so the heap ceiling is not the real
     * wall for this feature the way it still is for Nora's plain `FloatArray`s.
     */
    private fun memoryRow(): View {
        val ctx = this
        sliderMinBytes = AetherGeometry.minimum().estimateBytes()
        sliderMaxBytes = AetherGeometry.memoryBudgetBytes()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        column.addView(TextView(ctx).apply {
            text = "Memory budget"
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })

        ramSlider = android.widget.SeekBar(ctx).apply {
            max = SLIDER_STEPS
            progressTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            thumbTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }

            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser || suppressSlider) return
                    previewMemory(bytesForProgress(value))
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    if (suppressSlider) return
                    applyGeometry(AetherGeometry.largestWithin(bytesForProgress(progress)))
                }
            })
        }
        column.addView(ramSlider)

        ramLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 4f), 0, 0)
        }
        column.addView(ramLabel)

        column.addView(TextView(ctx).apply {
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
            val ram = AetherGeometry.deviceRamBytes()
            text = buildString {
                append("Ceiling is RAM actually free right now")
                append(" (${AetherGeometry.formatBytes(sliderMaxBytes)}")
                if (ram > 0) append(" of ${AetherGeometry.formatBytes(ram)} device RAM")
                append("), reserving a share for the rest of Prism.")
            }
        })

        if (sliderMaxBytes <= sliderMinBytes) {
            ramSlider.isEnabled = false
            ramLabel.text =
                "Not enough free RAM for a resizable brain right now -- " +
                    "${AetherGeometry.formatBytes(sliderMaxBytes)} available, " +
                    "${AetherGeometry.formatBytes(sliderMinBytes)} needed at minimum."
        }
        return column
    }

    private fun bytesForProgress(progress: Int): Long {
        if (sliderMaxBytes <= sliderMinBytes) return sliderMinBytes
        val t = progress.toDouble() / SLIDER_STEPS
        return sliderMinBytes + ((sliderMaxBytes - sliderMinBytes) * t).toLong()
    }

    private fun progressForBytes(bytes: Long): Int {
        if (sliderMaxBytes <= sliderMinBytes) return 0
        val t = (bytes - sliderMinBytes).toDouble() / (sliderMaxBytes - sliderMinBytes)
        return (t * SLIDER_STEPS).toInt().coerceIn(0, SLIDER_STEPS)
    }

    /** Live readout while dragging, before anything is committed. */
    private fun previewMemory(bytes: Long) {
        val g = AetherGeometry.largestWithin(bytes)
        ramLabel.text = buildString {
            append("${AetherGeometry.formatBytes(bytes)} of ")
            append("${AetherGeometry.formatBytes(sliderMaxBytes)} available -> ")
            append("${AetherGeometry.formatCount(g.totalNeurons)} neurons")
            append(" · %.1fx training cost".format(g.relativeTrainingCost()))
        }
    }

    /**
     * A label, a numeric field, and optionally a button to its right. Same shape as
     * `NoraSettingsActivity.numberRow` -- committed on IME-done or focus loss, never per
     * keystroke, since every commit re-solves the whole geometry and redraws every other field.
     */
    private fun numberRow(
        title: String,
        detail: String,
        read: () -> Long,
        write: (Long) -> AetherGeometry,
        actionDescription: String? = null,
        onAction: (() -> Unit)? = null
    ): View {
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }

        column.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        }

        val field = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(IosUi.label(ctx))
            background = IosUi.fieldBackground(ctx)
            setPadding(
                IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f),
                IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f)
            )
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }

        fun commit() {
            val requested = field.text.toString().filter { it.isDigit() }.toLongOrNull()
            if (requested == null || requested <= 0) {
                refreshSizes()
                return
            }
            if (requested == read()) return
            applyGeometry(write(requested))
        }

        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                commit()
                true
            } else false
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        row.addView(field)

        if (actionDescription != null && onAction != null) {
            row.addView(TextView(ctx).apply {
                text = "Max"
                textSize = 14f
                setTextColor(IosUi.accent(ctx))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    IosUi.dp(ctx, 56f), IosUi.dp(ctx, 40f)
                ).apply { marginStart = IosUi.dp(ctx, 10f) }
                background = IosUi.fieldBackground(ctx)
                contentDescription = actionDescription
                isClickable = true
                isFocusable = true
                setOnClickListener { onAction() }
            })
        }

        column.addView(row)
        column.addView(TextView(ctx).apply {
            text = detail
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        })

        sizeFields.add(field to read)
        return column
    }

    private fun applyGeometry(g: AetherGeometry) {
        if (!AetherStudio.applyGeometry(this, g)) {
            Toast.makeText(
                this,
                "Aether is busy -- stop training or generation before resizing her.",
                Toast.LENGTH_LONG
            ).show()
            refreshSizes()
            return
        }
        refreshSizes()
    }

    private fun applyDeviceMaximum() {
        val max = AetherGeometry.maxForDevice()
        AlertDialog.Builder(this)
            .setTitle("Use this device's maximum?")
            .setMessage(
                "Largest size that fits in ${AetherGeometry.formatBytes(AetherGeometry.memoryBudgetBytes())} " +
                    "of RAM actually free right now.\n\n" +
                    "neurons     ${AetherGeometry.formatCount(max.totalNeurons)}\n" +
                    "parameters  ${AetherGeometry.formatCount(max.totalParameters)}\n" +
                    "est. RAM    ${AetherGeometry.formatBytes(max.estimateBytes())}\n" +
                    "train cost  %.1fx default\n\n".format(max.relativeTrainingCost()) +
                    "This is bounded by memory, not by patience. At %.1fx the cost, a run that "
                        .format(max.relativeTrainingCost()) +
                    "takes an hour today would take roughly %.0f hours."
                        .format(max.relativeTrainingCost())
            )
            .setPositiveButton("Use maximum") { _, _ -> applyGeometry(max) }
            .setNeutralButton("Reset to default") { _, _ -> applyGeometry(AetherGeometry.DEFAULT) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSizes() {
        for ((field, read) in sizeFields) {
            val text = read().toString()
            if (field.text.toString() != text) field.setText(text)
        }
        val g = AetherConfig.geometry

        if (::ramSlider.isInitialized && ramSlider.isEnabled) {
            suppressSlider = true
            val bytes = g.estimateBytes()
            ramSlider.progress = progressForBytes(bytes)
            ramLabel.text = buildString {
                append("${AetherGeometry.formatBytes(bytes)} of ")
                append("${AetherGeometry.formatBytes(sliderMaxBytes)} available -> ")
                append("${AetherGeometry.formatCount(g.totalNeurons)} neurons")
                append(" · %.1fx training cost".format(g.relativeTrainingCost()))
                if (bytes > sliderMaxBytes) {
                    append("\nOver budget. This size was set by hand and may not allocate.")
                }
            }
            suppressSlider = false
        }
        if (::costLine.isInitialized) {
            costLine.text = buildString {
                append("${AetherGeometry.formatCount(g.totalParameters)} parameters · ")
                append("~${AetherGeometry.formatBytes(g.estimateBytes())} RAM · ")
                append("%.2fx default training cost".format(g.relativeTrainingCost()))
                append(
                    "\nVisual ${g.v1Filters}/${g.v2Filters}/${g.v3Filters}ch, ${g.v3Dim}d · " +
                        "Temporal ${g.temporalDim}d · Parietal ${g.parietalDim}d · " +
                        "Hippocampus ${g.hippocampalDim}d · Executive ${g.cognitiveDim}d · " +
                        "Amygdala ${g.amygdalaDim}d"
                )
                append(
                    "\n\nEach size keeps its own connectome file, so changing this parks the " +
                        "previous size's trained weights rather than overwriting them."
                )
            }
        }
    }

    // ── Memory placement ───────────────────────────────────────────────────

    /**
     * The two off-heap switches from [AetherPerformance]. Off by default, same policy as Nora's
     * equivalent switches -- see [AetherPerformance]'s doc comment for why this is a separate
     * section from brain size (HOW the connectome is stored, not WHAT it computes).
     */
    private fun buildMemorySection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "MEMORY PLACEMENT"))
        val card = IosUi.card(ctx)
        for ((i, flag) in AetherPerformance.FLAGS.withIndex()) {
            if (i > 0) card.addView(IosUi.hairline(ctx))
            card.addView(
                toggleRow(flag.label, flag.detail, flag.read()) {
                    flag.write(it)
                    AetherPerformance.save()
                }
            )
        }
        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Swap file size: ${AetherGeometry.formatBytes(AetherPerformance.swapBytes)}. " +
                    "${AetherPerformance.describe()}. These do not change a single arithmetic " +
                    "result -- see AetherSwap's doc comment for what is and isn't routed " +
                    "through them yet."
            )
        )
        content.addView(spacer())
    }

    // ── Knowledge sharing ──────────────────────────────────────────────────

    /**
     * `--share-knowledge`/`--receive-knowledge` as toggles, plus the live device list the plan
     * asked for: every AetherCortex/Aether instance findable on this LAN ([AetherKnowledgeSync])
     * merged with every Aether-hosting Mesh peer ([AetherMeshSync]) into one list, tagged by
     * source. Tapping any row (LAN or Mesh) triggers manual reception from that specific peer,
     * bypassing whatever the background receiver would have auto-picked.
     */
    private fun buildKnowledgeSharingSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "KNOWLEDGE SHARING"))
        val toggleCard = IosUi.card(ctx)
        toggleCard.addView(
            toggleRow(
                "Share Aether's knowledge",
                "Serves your trained connectome to other AetherCortex/Aether instances on this " +
                    "Wi-Fi network, and to Mesh peers too when the Mesh is enabled and connected.",
                PrismSettings.getAetherShareKnowledgeEnabled()
            ) { enabled ->
                PrismSettings.setAetherShareKnowledgeEnabled(enabled)
                restartKnowledgeSync()
            }
        )
        toggleCard.addView(IosUi.hairline(ctx))
        toggleCard.addView(
            toggleRow(
                "Receive knowledge from peers",
                "Discovers other instances and merges (or adopts, if Aether hasn't trained yet) " +
                    "the newest trained connectome found, automatically in the background.",
                PrismSettings.getAetherReceiveKnowledgeEnabled()
            ) { enabled ->
                PrismSettings.setAetherReceiveKnowledgeEnabled(enabled)
                restartKnowledgeSync()
            }
        )
        content.addView(toggleCard)
        content.addView(spacer())

        content.addView(nearbyDevicesHeaderRow())
        knowledgeListContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(knowledgeListContainer)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "\"Version\" is the date the tapped peer's connectome was built, shown after a " +
                    "successful receive. LAN devices are found directly; Mesh devices only " +
                    "appear here while the Mesh is enabled and connected to at least one peer."
            )
        )
        content.addView(spacer())
        refreshKnowledgeList()
    }

    /** "NEARBY DEVICES" header plus a manual reload button that re-broadcasts discovery and
     * redraws the list immediately, instead of waiting for [knowledgeRefreshRunnable]'s next tick. */
    private fun nearbyDevicesHeaderRow(): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(IosUi.sectionHeader(ctx, "NEARBY DEVICES").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.ImageButton(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(IosUi.dp(ctx, 32f), IosUi.dp(ctx, 32f)).apply {
                    marginEnd = IosUi.dp(ctx, 16f)
                }
                setImageResource(com.prism.launcher.R.drawable.ic_refresh_24)
                imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
                background = null
                setPadding(IosUi.dp(ctx, 4f), IosUi.dp(ctx, 4f), IosUi.dp(ctx, 4f), IosUi.dp(ctx, 4f))
                contentDescription = "Scan for nearby devices now"
                setOnClickListener {
                    AetherKnowledgeSync.broadcastDiscover()
                    refreshKnowledgeList()
                }
            })
        }
    }

    private fun restartKnowledgeSync() {
        AetherKnowledgeSync.stop()
        val share = PrismSettings.getAetherShareKnowledgeEnabled()
        val receive = PrismSettings.getAetherReceiveKnowledgeEnabled()
        if (share || receive) {
            AetherKnowledgeSync.start(share, receive)
        }
        if (share && PrismSettings.getMeshEnabled() && com.prism.launcher.mesh.PrismMeshService.getPeerCount() > 0) {
            AetherMeshSync.announce(this)
        } else if (!share) {
            AetherMeshSync.revoke()
        }
    }

    private fun refreshKnowledgeList() {
        if (!::knowledgeListContainer.isInitialized) return
        val ctx = this
        knowledgeListContainer.removeAllViews()

        val lanPeers = AetherKnowledgeSync.peers.value.values.toList()
        val meshPeers = AetherMeshSync.getAll()

        if (lanPeers.isEmpty() && meshPeers.isEmpty()) {
            val card = IosUi.card(ctx)
            card.addView(
                navRow(
                    "No devices found yet",
                    if (PrismSettings.getAetherReceiveKnowledgeEnabled())
                        "Still looking on the LAN" + (if (PrismSettings.getMeshEnabled()) " and the Mesh..." else "...")
                    else
                        "Enable \"Receive knowledge from peers\" above to search"
                ) {}
            )
            knowledgeListContainer.addView(card)
            return
        }

        // Merged, ranked by score descending regardless of transport -- the best available
        // connectome should always be the first thing the user sees, not just the first LAN
        // peer followed by every Mesh peer.
        data class Row(val source: String, val address: String, val geometrySignature: String, val hasModel: Boolean, val score: Int, val fetch: () -> Boolean)
        val rows = lanPeers.map { peer ->
            Row("LAN", peer.address, peer.geometrySignature, peer.hasModel, peer.score) { AetherKnowledgeSync.fetchAndStage(peer) }
        } + meshPeers.map { peer ->
            Row("Mesh", peer.peerIp, peer.geometrySignature, peer.hasModel, peer.score) { AetherMeshSync.fetchAndStage(peer) }
        }

        val card = IosUi.card(ctx)
        var first = true
        for (row in rows.sortedByDescending { it.score }) {
            if (!first) card.addView(IosUi.hairline(ctx))
            first = false
            card.addView(
                peerRow(row.source, row.address, row.geometrySignature, row.hasModel, row.score) {
                    receiveFrom(row.fetch)
                }
            )
        }
        knowledgeListContainer.addView(card)
    }

    private fun peerRow(source: String, address: String, geometrySignature: String, hasModel: Boolean, score: Int, onTap: () -> Unit): View {
        val ctx = this
        val detail = if (hasModel) "Trained, geometry $geometrySignature, score $score/100 -- tap to receive" else "No trained connectome yet"
        return navRow("$address ($source)", detail) {
            if (!hasModel) {
                Toast.makeText(ctx, "This device hasn't trained a connectome yet.", Toast.LENGTH_SHORT).show()
                return@navRow
            }
            onTap()
        }
    }

    /**
     * Manual "tap to receive" from one specific peer -- bypasses whatever the background
     * receiver would have auto-picked. The fetch (network I/O, writing a temp file, staging the
     * result) runs right here in the background thread below -- it never touches a live
     * connectome, only files, so it's harmless to do from this (main) process. Actually APPLYING
     * the staged merge is delegated to [AetherService] via [AetherService.applyKnowledge]: that
     * mutation must land on the ONE resident connectome [AetherService] owns in its own process,
     * never on a second one this Activity would otherwise build by calling [AetherStudio.brain]
     * directly -- see [AetherStudio.hasTrainedWeights]'s doc comment.
     */
    private fun receiveFrom(fetch: () -> Boolean) {
        val ctx = this
        Thread {
            val fetched = try { fetch() } catch (e: Exception) { false }
            val message = if (!fetched) {
                "Couldn't receive: ${AetherKnowledgeSync.lastStatus}"
            } else {
                AetherService.applyKnowledge(ctx)
                "Received from peer -- applying (see the training log for confirmation)."
            }
            runOnUiThread {
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                refreshKnowledgeList()
            }
        }.start()
    }

    // ── Routes ──────────────────────────────────────────────────────────────

    /**
     * `--biotrain`/`--biogen`, both opt-out (on by default): [PrismSettings.getAetherBiotrainEnabled]/
     * [PrismSettings.getAetherBiogenEnabled].
     */
    private fun buildRoutesSection() {
        content.addView(IosUi.sectionHeader(this, "GENERATION & TRAINING ROUTES"))
        val card = IosUi.card(this)
        card.addView(
            toggleRow(
                "Biological training (--biotrain)",
                "Local Hebbian/STDP across the whole connectome -- no backpropagation, no GPU needed. Off falls back to backprop, updating Broca's area only.",
                PrismSettings.getAetherBiotrainEnabled()
            ) { PrismSettings.setAetherBiotrainEnabled(it) }
        )
        card.addView(IosUi.hairline(this))
        card.addView(
            toggleRow(
                "Elaborate generation (--biogen)",
                "Enables /autoregress, /hallucinate, /deepdream and /saccadic. Off: every prompt uses the plain single-pass generation only.",
                PrismSettings.getAetherBiogenEnabled()
            ) { PrismSettings.setAetherBiogenEnabled(it) }
        )
        content.addView(card)
        content.addView(spacer())
    }

    // ── ANN baseline conversion (experimental) ───────────────────────────────

    /**
     * `--use-text-model` equivalent: turns Sam's active local text model into a smarter starting
     * point for a fresh Aether connectome via a single calibration pass (see [AetherAnnBaseline]).
     * The checkbox stays visible but disabled until Sam actually has a local text model active --
     * [refreshAnnBaselineSection] re-derives that condition on every [onResume], so switching
     * Sam's model elsewhere and returning here reflects the new state without a full recreate().
     */
    private fun buildAnnBaselineSection() {
        content.addView(IosUi.sectionHeader(this, "ANN BASELINE (EXPERIMENTAL)"))
        annBaselineContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(annBaselineContainer)
        content.addView(
            IosUi.sectionFooter(
                this,
                "Uses Sam's active local text model to derive a calibrated starting point for a " +
                    "connectome that hasn't trained yet -- see Settings > AI Engine and Models. " +
                    "Only applies the first time Aether trains from scratch, or right after " +
                    "\"Erase connectome\" below -- no effect once she's already learned something."
            )
        )
        content.addView(spacer())
        refreshAnnBaselineSection()
    }

    private fun hasActiveLocalTextModel(): Boolean {
        if (PrismSettings.getAiMode() != PrismSettings.AI_MODE_LOCAL) return false
        val path = PrismSettings.getLocalAiModelPath()
        if (path.isEmpty()) return false
        return PrismSettings.getImportedModels().any { it.path == path && it.type == PrismSettings.MODEL_TYPE_TEXT }
    }

    private fun refreshAnnBaselineSection() {
        if (!::annBaselineContainer.isInitialized) return
        val ctx = this
        annBaselineContainer.removeAllViews()

        val modelActive = hasActiveLocalTextModel()
        val baselineOn = PrismSettings.getAetherAnnBaselineEnabled()
        // The two optional signals below only ever do anything while calibration itself runs --
        // same "model active" gate, AND the master toggle right above them.
        val subRowsEnabled = modelActive && baselineOn

        val card = IosUi.card(ctx)
        card.addView(
            toggleRow(
                "Use imported text model as training baseline",
                if (modelActive)
                    "Calibrates a fresh connectome's starting weights from Sam's active local text model. " +
                        "Also distills soft targets (what the model predicted, not just where it looked) " +
                        "automatically -- that part isn't optional."
                else
                    "Import and activate a local .gguf text model for Sam (Settings > AI Engine) to enable this.",
                baselineOn,
                enabled = modelActive
            ) {
                PrismSettings.setAetherAnnBaselineEnabled(it)
                refreshAnnBaselineSection()
            }
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(
            toggleRow(
                "Surprisal weighting",
                "Emphasizes characters the text model found hard to predict, on top of the " +
                    "baseline above.",
                PrismSettings.getAetherSurprisalWeightingEnabled(),
                enabled = subRowsEnabled
            ) { PrismSettings.setAetherSurprisalWeightingEnabled(it) }
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(
            toggleRow(
                "Attention co-occurrence structural prior",
                "Nudges Broca's area's initial weights so characters the text model's attention " +
                    "treats as related start correlated, not just individually scaled.",
                PrismSettings.getAetherCooccurrencePriorEnabled(),
                enabled = subRowsEnabled
            ) { PrismSettings.setAetherCooccurrencePriorEnabled(it) }
        )
        annBaselineContainer.addView(card)
    }

    // ── Training ────────────────────────────────────────────────────────────

    private fun buildTrainingSection() {
        content.addView(IosUi.sectionHeader(this, "TRAINING"))
        val card = IosUi.card(this)
        card.addView(
            navRow("Open training page", "Live log and connectome visualization") {
                startActivity(android.content.Intent(this, AetherTrainingActivity::class.java))
            }
        )
        content.addView(card)
        content.addView(spacer())
    }

    // ── Tuning ──────────────────────────────────────────────────────────────

    /**
     * Every constant AetherCortex exposes as a `--flag` (`config/constants.py` on the Python
     * side), generated from [AetherTuning.PARAMS]/[AetherTuning.STRING_PARAMS] rather than
     * hand-written -- same reasoning as `NoraSettingsActivity.buildTuningSection`: a knob that
     * exists in the model but not here is a silent gap. At ~230 entries (Aether mirrors every
     * constant, not a curated subset -- see [AetherTuning]'s doc comment) a plain scroll is a lot
     * to hunt through, so this section leads with a live text filter.
     */
    private fun buildTuningSection() {
        val ctx = this
        tuningRows.clear()
        tuningGroups.clear()

        content.addView(IosUi.sectionHeader(ctx, "TUNING"))

        val filterCard = IosUi.card(ctx)
        val filterField = EditText(ctx).apply {
            hint = "Filter ${AetherTuning.PARAMS.size + AetherTuning.STRING_PARAMS.size} parameters..."
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            textSize = 15f
            setTextColor(IosUi.label(ctx))
            background = IosUi.fieldBackground(ctx)
            setPadding(IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val filterWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f))
        }
        filterWrap.addView(filterField)
        filterCard.addView(filterWrap)
        content.addView(filterCard)
        content.addView(spacer())

        for (groupName in AetherTuning.GROUPS) {
            val group = startTuningGroup(ctx, groupName)
            val params = AetherTuning.PARAMS.filter { it.group == groupName }
            for ((i, param) in params.withIndex()) {
                if (i > 0) group.card.addView(IosUi.hairline(ctx))
                val row = tuningRow(param)
                group.card.addView(row)
                tuningRows.add(TuningRowEntry(row, (param.label + " " + param.key).lowercase(), group))
            }
            content.addView(group.card)
        }

        for (groupName in AetherTuning.STRING_GROUPS) {
            val params = AetherTuning.STRING_PARAMS.filter { it.group == groupName }
            if (params.isEmpty()) continue
            val group = startTuningGroup(ctx, "$groupName (TEXT)")
            for ((i, param) in params.withIndex()) {
                if (i > 0) group.card.addView(IosUi.hairline(ctx))
                val row = stringTuningRow(param)
                group.card.addView(row)
                tuningRows.add(TuningRowEntry(row, (param.label + " " + param.key).lowercase(), group))
            }
            content.addView(group.card)
        }

        applyTuningVisibility()

        filterField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                tuningFilterQuery = s?.toString().orEmpty()
                applyTuningVisibility()
            }
        })

        content.addView(IosUi.sectionHeader(ctx, "RESET"))
        val resetCard = IosUi.card(ctx)
        resetCard.addView(
            navRow(
                "Reset all parameters",
                "${AetherTuning.changedCount()} of ${AetherTuning.PARAMS.size + AetherTuning.STRING_PARAMS.size} differ from default",
                destructive = true
            ) {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset every parameter?")
                    .setMessage(
                        "Returns all ${AetherTuning.PARAMS.size + AetherTuning.STRING_PARAMS.size} values to " +
                            "their built-in defaults, matching AetherCortex's own CLI defaults. The connectome " +
                            "itself is untouched."
                    )
                    .setPositiveButton("Reset") { _, _ ->
                        AetherTuning.resetToDefaults()
                        recreate()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        content.addView(resetCard)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Mirrors every `--flag` AetherCortex's Python side accepts (see config/constants.py). " +
                    "Takes effect on the next training run or generation call -- nothing here requires " +
                    "retraining."
            )
        )
        content.addView(spacer())
    }

    /** Creates a collapsible group's header (tap to expand/collapse) and its (still-empty) card,
     * registers it, and adds the header to [content]. The caller populates `group.card` with rows
     * and adds it to [content] itself afterwards -- mirrors the two-step header-then-card shape
     * the un-collapsible version used, since callers need the card reference while filling it. */
    private fun startTuningGroup(ctx: android.content.Context, baseLabel: String): TuningGroupInfo {
        val header = IosUi.sectionHeader(ctx, baseLabel)
        val card = IosUi.card(ctx)
        val group = TuningGroupInfo(header, card, baseLabel)
        updateGroupHeaderText(group)
        header.setOnClickListener { toggleTuningGroup(group) }
        content.addView(header)
        tuningGroups.add(group)
        return group
    }

    private fun updateGroupHeaderText(group: TuningGroupInfo) {
        val chevron = if (group.expanded) "▾ " else "▸ "
        group.header.text = (chevron + group.baseLabel).uppercase()
    }

    private fun toggleTuningGroup(group: TuningGroupInfo) {
        group.expanded = !group.expanded
        updateGroupHeaderText(group)
        applyTuningVisibility()
    }

    /**
     * Natural state (empty filter): every row visible, each group's card shown/hidden per its own
     * [TuningGroupInfo.expanded] flag. Active filter: rows filtered by text match as before, and
     * groups are shown/hidden (header + card together) based on whether they contain a match --
     * `expanded` is ignored while filtering, so a matching group always shows fully expanded and
     * clearing the filter reveals whatever collapsed/expanded state the user left each group in.
     */
    private fun applyTuningVisibility() {
        val q = tuningFilterQuery.trim().lowercase()
        if (q.isEmpty()) {
            for (entry in tuningRows) entry.row.visibility = View.VISIBLE
            for (group in tuningGroups) {
                group.header.visibility = View.VISIBLE
                group.card.visibility = if (group.expanded) View.VISIBLE else View.GONE
            }
            return
        }
        val matchedGroups = mutableSetOf<TuningGroupInfo>()
        for (entry in tuningRows) {
            val matches = entry.searchText.contains(q)
            entry.row.visibility = if (matches) View.VISIBLE else View.GONE
            if (matches) matchedGroups.add(entry.group)
        }
        for (group in tuningGroups) {
            val visible = group in matchedGroups
            group.header.visibility = if (visible) View.VISIBLE else View.GONE
            group.card.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** One editable numeric parameter. Commits on IME-done or focus loss. */
    private fun tuningRow(param: AetherTuning.Param): View {
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        column.addView(TextView(ctx).apply {
            text = param.label
            textSize = 16f
            setTextColor(IosUi.label(ctx))
        })

        val field = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }
            inputType = if (param.isInt) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            textSize = 16f
            setTextColor(IosUi.label(ctx))
            background = IosUi.fieldBackground(ctx)
            setPadding(IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setText(param.display())
        }

        fun commit() {
            if (!param.apply(field.text.toString())) {
                field.setText(param.display())
                return
            }
            AetherTuning.save()
            field.setText(param.display())
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus(); commit(); true
            } else false
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        column.addView(field)
        column.addView(TextView(ctx).apply {
            text = "${param.detail}\nRange ${trim(param.min)}-${trim(param.max)}"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        })
        return column
    }

    /** One editable text parameter (paths, URLs, keys) -- same shape as [tuningRow] without numeric clamping. */
    private fun stringTuningRow(param: AetherTuning.StringParam): View {
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        column.addView(TextView(ctx).apply {
            text = param.label
            textSize = 16f
            setTextColor(IosUi.label(ctx))
        })

        val field = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setTextColor(IosUi.label(ctx))
            background = IosUi.fieldBackground(ctx)
            setPadding(IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 9f))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setText(param.read())
        }

        fun commit() {
            param.write(field.text.toString())
            AetherTuning.save()
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus(); commit(); true
            } else false
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        if (param.key == "dashboard_brain_image") {
            val fieldRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = IosUi.dp(ctx, 6f) }
            }
            (field.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 0
                width = 0
                weight = 1f
            }
            fieldRow.addView(field)
            fieldRow.addView(android.widget.ImageButton(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(IosUi.dp(ctx, 40f), IosUi.dp(ctx, 40f)).apply {
                    marginStart = IosUi.dp(ctx, 10f)
                }
                setImageResource(com.prism.launcher.R.drawable.ic_folder_24)
                imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
                background = IosUi.fieldBackground(ctx)
                setPadding(IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f))
                contentDescription = "Browse for a brain image"
                setOnClickListener { brainImagePicker.launch(arrayOf("image/*")) }
            })
            column.addView(fieldRow)
        } else {
            column.addView(field)
        }
        column.addView(TextView(ctx).apply {
            text = param.detail
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        })
        return column
    }

    private fun trim(v: Float): String =
        "%.6f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    // ── Data ────────────────────────────────────────────────────────────────

    private fun buildDataSection() {
        content.addView(IosUi.sectionHeader(this, "DATA"))
        val card = IosUi.card(this)
        card.addView(
            navRow("Erase connectome", "Delete everything she's learned", destructive = true) {
                AlertDialog.Builder(this)
                    .setTitle("Erase Aether's connectome?")
                    .setMessage("Every weight and every trace is deleted. Training starts from infancy again. Dataset images are kept.")
                    .setPositiveButton("Erase") { _, _ ->
                        AetherStudio.forget(this)
                        Toast.makeText(this, "Connectome erased.", Toast.LENGTH_SHORT).show()
                        refreshStatus()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        content.addView(card)
        content.addView(spacer())
    }

    // ── Row builders (same shape as NoraSettingsActivity's) ────────────────

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@AetherSettingsActivity, 16f))
    }

    private fun navRow(title: String, detail: String, destructive: Boolean = false, onClick: () -> Unit): View {
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 13f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 13f))
            setOnClickListener { onClick() }
        }
        column.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(if (destructive) IosUi.destructive(ctx) else IosUi.label(ctx))
        })
        if (detail.isNotEmpty()) {
            column.addView(TextView(ctx).apply {
                text = detail
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(ctx))
                setPadding(0, IosUi.dp(ctx, 3f), 0, 0)
            })
        }
        return column
    }

    /** [enabled] false greys the row out and disables the switch entirely -- no [onChanged]
     * listener is even wired, so it can't fire from a stray tap while disabled. Used for settings
     * that are visible but not yet actionable, e.g. the experimental ANN-baseline-conversion row
     * until Sam has an active local text model (see [buildTuningSection]/its "ANN BASELINE" group). */
    private fun toggleRow(title: String, detail: String, value: Boolean, enabled: Boolean = true, onChanged: (Boolean) -> Unit): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f))
            alpha = if (enabled) 1.0f else 0.4f
        }
        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(IosUi.label(ctx))
        })
        textColumn.addView(TextView(ctx).apply {
            text = detail
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 3f), 0, 0)
        })
        row.addView(textColumn)
        row.addView(SwitchCompat(ctx).apply {
            isChecked = value
            isEnabled = enabled
            if (enabled) setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        })
        return row
    }
}
