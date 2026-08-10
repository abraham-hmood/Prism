package com.prism.launcher.nora

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.launch

/**
 * Everything about Nora, in one place.
 *
 * Split out of the main Settings list rather than added to it, because brain size is nine
 * coupled numbers plus a cost readout, and that does not belong interleaved with the AI-model
 * and messaging options it used to sit among.
 *
 * THE EDITING MODEL. Every size box is expressed in NEURONS, not in the underlying dimension,
 * because neurons are the thing the user asked to control and rings-times-wedges is an
 * implementation detail. A typed value is therefore a REQUEST: [NoraGeometry] solves for the
 * nearest legal configuration and every box is redrawn from the result. That is why a number
 * often comes back slightly different from what was entered — the legal sizes are a discrete
 * ladder (multiples of 8 on the sheet, of 3 on V1's orientations, of 2 on MT's directions) and
 * you get the nearest rung, not the request. Silently accepting an illegal value would produce
 * a hierarchy whose halvings are inexact, which does not crash: it misaligns every prediction.
 */
class NoraSettingsActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout

    /** Rows that need redrawing after any geometry change, since all of them are coupled. */
    private val sizeFields = ArrayList<Pair<EditText, () -> Long>>()
    private lateinit var costLine: TextView

    private lateinit var ramSlider: android.widget.SeekBar
    private lateinit var ramLabel: TextView
    private var sliderMinBytes = 0L
    private var sliderMaxBytes = 0L

    private lateinit var swapSlider: android.widget.SeekBar
    private lateinit var swapLabel: TextView
    private lateinit var swapContainer: View
    private var swapMinBytes = 0L
    private var swapMaxBytes = 0L

    /** Rows that grey out when the feature they belong to is switched off. */
    private val gatedRows = ArrayList<Pair<View, () -> Boolean>>()

    /** Guards the slider against reacting to its own programmatic repositioning. */
    private var suppressSlider = false

    private companion object {
        /** Slider resolution. Fine enough that a drag feels continuous in megabytes. */
        const val SLIDER_STEPS = 1000
    }

    private val backupPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val r = NoraArchive.backup(this@NoraSettingsActivity, uri)
                dialog(if (r.ok) "Backup complete" else "Backup failed", r.message)
            }
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            AlertDialog.Builder(this)
                .setTitle("Import Nora?")
                .setMessage(
                    "This overwrites her connectome and merges the archive's images into your " +
                        "dataset folder. Anything she has learned since your last backup is lost."
                )
                .setPositiveButton("Import") { _, _ ->
                    lifecycleScope.launch {
                        val r = NoraArchive.import(this@NoraSettingsActivity, uri)
                        dialog(if (r.ok) "Import complete" else "Import failed", r.message)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
            text = "Nora"
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

        buildSizeSection()
        buildRegionSection()
        buildTrainingSection()
        buildPerformanceSection()
        buildTuningSection()
        buildDataSection()
        refreshSizes()
        refreshGates()
    }

    // ── Performance ─────────────────────────────────────────────────────────

    /**
     * Where Nora's memory lives and what runs her arithmetic.
     *
     * Kept apart from the tuning parameters below because the two answer different questions.
     * A tuning parameter changes WHAT Nora computes, so a different value is a different model
     * and a worse image is a modelling result. Everything here changes only HOW the identical
     * computation is carried out, so a worse image is a bug. Mixing them would make that
     * distinction unavailable exactly when someone needs it.
     *
     * Each feature's parameters stay VISIBLE but DISABLED until its switch is on. Hiding them
     * would mean you cannot see what a feature costs until after committing to it, which is
     * backwards: the numbers are how you decide.
     */
    private fun buildPerformanceSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "PERFORMANCE"))
        val card = IosUi.card(ctx)
        for ((i, flag) in NoraPerformance.FLAGS.withIndex()) {
            if (i > 0) card.addView(IosUi.hairline(ctx))
            val unavailable = flag.key == "native_conv" && !NoraNative.available()
            card.addView(
                toggleRow(
                    flag.label,
                    if (unavailable) {
                        "${flag.detail}\n\nUnavailable on this device — the native library did " +
                            "not load, so the Kotlin kernels are in use regardless of this switch."
                    } else {
                        flag.detail
                    },
                    flag.read(),
                    enabled = !unavailable
                ) { checked ->
                    flag.write(checked)
                    NoraPerformance.save()
                    onPerformanceFlagChanged(flag.key, checked)
                }
            )
        }
        content.addView(card)

        content.addView(IosUi.sectionHeader(ctx, "SWAP FILE SIZE"))
        val swapCard = IosUi.card(ctx)
        swapCard.addView(swapRow())
        content.addView(swapCard)

        buildExpertsSection()

        for (group in NoraPerformance.GROUPS) {
            content.addView(IosUi.sectionHeader(ctx, group.uppercase()))
            val g = IosUi.card(ctx)
            val params = NoraPerformance.PARAMS.filter { it.group == group }
            for ((i, param) in params.withIndex()) {
                if (i > 0) g.addView(IosUi.hairline(ctx))
                g.addView(tuningRow(param))
            }
            content.addView(g)
        }

        content.addView(IosUi.sectionHeader(ctx, "PERFORMANCE DEFAULTS"))
        val resetCard = IosUi.card(ctx)
        resetCard.addView(
            navRow(
                "Verify native parity",
                if (NoraNative.available()) {
                    "Runs both kernels on the real weights and reports the largest disagreement."
                } else {
                    "Unavailable — the native library did not load on this device."
                }
            ) { verifyNativeParity() }
        )
        resetCard.addView(IosUi.hairline(ctx))
        resetCard.addView(
            navRow(
                "Reset performance settings",
                "${NoraPerformance.changedCount()} differ from default",
                destructive = true
            ) {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset performance settings?")
                    .setMessage(
                        "Turns every storage and acceleration option off and restores their " +
                            "parameters. The connectome is untouched — none of these options " +
                            "changes what Nora has learned."
                    )
                    .setPositiveButton("Reset") { _, _ ->
                        NoraPerformance.resetToDefaults()
                        NoraSwap.delete()
                        NoraStudio.releaseBrain()
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
                "None of these changes a single arithmetic result — the sparse hub skips terms " +
                    "that are provably zero, a float read from a mapped page is the same float, " +
                    "and the native kernels sum in the same order as the Kotlin ones. They " +
                    "change speed and memory only. A change here takes effect when the brain is " +
                    "next built, which is why toggling one releases it."
            )
        )
    }

    /**
     * Applies a switch immediately rather than at some unstated later point.
     *
     * Storage placement is decided in constructors, so a change cannot take effect on the brain
     * currently in memory. Releasing it here makes the switch mean what it appears to mean; the
     * connectome is on disk, so nothing is lost by rebuilding.
     */
    /**
     * Checks the native kernels against the Kotlin ones on this device's actual weights.
     *
     * The native path is only worth having if it produces the same numbers, and "trust me" is
     * not a verification strategy. Every link is tested against random input at its real
     * dimensions; the pass condition is exact equality, not a tolerance, because the
     * implementation is designed to preserve summation order and anything above zero means that
     * design has been violated somewhere.
     */
    private fun verifyNativeParity() {
        if (!NoraNative.available()) {
            dialog(
                "Native kernels unavailable",
                "The library did not load on this device, so the Kotlin kernels are in use. " +
                    "Nothing is wrong — this is the reference implementation, not a fallback " +
                    "mode, and Nora behaves identically. Only speed differs."
            )
            return
        }
        lifecycleScope.launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val brain = NoraStudio.brain(this@NoraSettingsActivity)
                val rng = kotlin.random.Random(4242L)
                buildString {
                    var worst = 0f
                    for (link in brain.links) {
                        val probe = Tensor3(link.topC, link.topH, link.topW)
                        for (i in probe.data.indices) probe.data[i] = rng.nextFloat() * 2f - 1f
                        val err = link.nativeParityError(probe)
                        if (err > worst) worst = err
                        appendLine(
                            "${link.name}: " +
                                if (err < 0f) "native call refused" else "max difference $err"
                        )
                    }
                    appendLine()
                    append(
                        if (worst == 0f) {
                            "Exact match on every link. The native kernels reproduce the Kotlin " +
                                "results bit for bit, which is the design guarantee."
                        } else {
                            "MISMATCH. The native path disagrees by up to $worst, which it must " +
                                "not. Leave native convolution off and report this."
                        }
                    )
                }
            }
            dialog("Native parity", report)
        }
    }

    private fun onPerformanceFlagChanged(key: String, checked: Boolean) {
        if (key == "swap" && !checked) NoraSwap.delete()
        NoraStudio.releaseBrain()
        refreshGates()
    }

    /**
     * The swap-file size slider.
     *
     * Floor is the smallest file that could hold anything worth spilling; ceiling is the free
     * space on the volume, read live so the top of the range cannot promise capacity a photo
     * library has already taken. Unlike the memory slider in Brain Size there is no heap cap
     * involved here at all — that is the entire point of the feature.
     */
    private fun swapRow(): View {
        val ctx = this
        swapMinBytes = NoraSwap.MIN_BYTES
        swapMaxBytes = NoraSwap.freeStorageBytes().coerceAtLeast(NoraSwap.MIN_BYTES)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        column.addView(TextView(ctx).apply {
            text = "Swap file size"
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })

        swapSlider = android.widget.SeekBar(ctx).apply {
            max = SLIDER_STEPS
            progressTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            thumbTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }
            progress = swapProgressFor(NoraPerformance.swapBytes)

            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    swapLabel.text = swapDescription(swapBytesFor(value))
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    // Committed on release, not per pixel: each commit rewrites preferences and
                    // re-sizes the file, which is not something to do sixty times a swipe.
                    NoraPerformance.swapBytes = swapBytesFor(progress)
                    NoraPerformance.save()
                    if (NoraPerformance.swapEnabled) {
                        NoraSwap.reset()
                        NoraStudio.releaseBrain()
                    }
                    swapLabel.text = swapDescription(NoraPerformance.swapBytes)
                }
            })
        }
        column.addView(swapSlider)

        swapLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 4f), 0, 0)
            text = swapDescription(NoraPerformance.swapBytes)
        }
        column.addView(swapLabel)
        swapContainer = column
        return column
    }

    private fun swapBytesFor(progress: Int): Long {
        if (swapMaxBytes <= swapMinBytes) return swapMinBytes
        val t = progress.toDouble() / SLIDER_STEPS
        return swapMinBytes + ((swapMaxBytes - swapMinBytes) * t).toLong()
    }

    private fun swapProgressFor(bytes: Long): Int {
        if (swapMaxBytes <= swapMinBytes) return 0
        val t = (bytes - swapMinBytes).toDouble() / (swapMaxBytes - swapMinBytes)
        return (t * SLIDER_STEPS).toInt().coerceIn(0, SLIDER_STEPS)
    }

    private fun swapDescription(bytes: Long): String = buildString {
        append(NoraGeometry.formatBytes(bytes))
        append(" of ${NoraGeometry.formatBytes(swapMaxBytes)} free storage")
        // Sparse allocation is worth stating: the file is created at full length but consumes
        // blocks only as pages are written, so a large setting is not a large commitment.
        append("\nCreated sparse — disk is consumed only as pages are actually written.")
    }

    /**
     * Greys out every control whose feature is switched off.
     *
     * Disabled rather than hidden, and the whole row rather than just the field, so a disabled
     * parameter reads as "not in effect" instead of "broken".
     */
    private fun refreshGates() {
        for ((view, gate) in gatedRows) setRowEnabled(view, gate())
        if (::swapContainer.isInitialized) {
            setRowEnabled(swapContainer, NoraPerformance.swapEnabled)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setRowEnabled(view: View, enabled: Boolean) {
        view.alpha = if (enabled) 1f else 0.4f
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setRowEnabled(view.getChildAt(i), enabled)
        }
    }

    /**
     * Every numeric parameter the model uses, grouped by what it affects.
     *
     * Generated from [NoraTuning.PARAMS] rather than hand-written, so a parameter cannot exist
     * in the model without appearing here — the failure mode of hand-written settings screens
     * is a knob that silently stops being reachable.
     */
    private fun buildTuningSection() {
        val ctx = this
        for (group in NoraTuning.GROUPS) {
            content.addView(IosUi.sectionHeader(ctx, group.uppercase()))
            val card = IosUi.card(ctx)
            val params = NoraTuning.PARAMS.filter { it.group == group }
            for ((i, param) in params.withIndex()) {
                if (i > 0) card.addView(IosUi.hairline(ctx))
                card.addView(tuningRow(param))
            }
            content.addView(card)
        }

        content.addView(IosUi.sectionHeader(ctx, "TUNING"))
        val card = IosUi.card(ctx)
        card.addView(
            navRow(
                "Reset all parameters",
                "${NoraTuning.changedCount()} of ${NoraTuning.PARAMS.size} differ from default",
                destructive = true
            ) {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset every parameter?")
                    .setMessage(
                        "Returns all ${NoraTuning.PARAMS.size} values to their built-in defaults. " +
                            "Brain size and the connectome are untouched."
                    )
                    .setPositiveButton("Reset") { _, _ ->
                        NoraTuning.resetToDefaults()
                        recreate()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "These take effect on the next generation or training run — nothing needs " +
                    "retraining. Values are clamped to a safe range, but a legal value can " +
                    "still be a bad one: raising the learning rate an order of magnitude " +
                    "reproduces the divergence in NORA.md §6a. Run the model test after a " +
                    "change you are unsure about."
            )
        )
    }

    /** One editable parameter. Commits on IME-done or focus loss, same as the size boxes. */
    private fun tuningRow(param: NoraTuning.Param): View {
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
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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
            // Both registries are written. A parameter belongs to exactly one of them, and
            // saving the other is a no-op that costs a preferences round-trip nobody notices --
            // considerably cheaper than a row that silently fails to persist because the shared
            // row builder guessed the wrong owner.
            NoraTuning.save()
            NoraPerformance.save()
            // Re-read rather than trusting the typed text: the value was clamped to the
            // parameter's range on the way in, and showing what was typed would misreport it.
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
            text = "${param.detail}\nRange ${trim(param.min)}–${trim(param.max)}"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        })
        gatedRows.add(column as View to param.enabled)
        return column
    }

    private fun trim(v: Float): String =
        "%.6f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

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
                read = { NoraConfig.geometry.totalNeurons },
                write = { NoraConfig.geometry.withTotalNeurons(it) },
                actionIcon = com.prism.launcher.R.drawable.ic_max_24,
                actionDescription = "Use this device's maximum",
                onAction = { applyDeviceMaximum() }
            )
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(memoryRow())

        content.addView(card)

        costLine = IosUi.sectionFooter(ctx, "")
        content.addView(costLine)
    }

    private fun buildRegionSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "REGIONS"))
        val card = IosUi.card(ctx)
        val regions = NoraGeometry.Region.entries.toTypedArray()
        for ((i, region) in regions.withIndex()) {
            if (i > 0) card.addView(IosUi.hairline(ctx))
            card.addView(
                numberRow(
                    title = region.label,
                    detail = region.detail,
                    read = { NoraConfig.geometry.neuronsIn(region) },
                    write = { NoraConfig.geometry.withRegionNeurons(region, it) }
                )
            )
        }
        content.addView(card)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Changing the retina sheet resizes V1, V2, V4, IT and MT with it — they are " +
                    "defined as fractions of it. The rest change only their own channel count. " +
                    "Values snap to the nearest legal size."
            )
        )
    }

    /**
     * The memory slider: pick how much RAM Nora may use, and get the largest brain that fits.
     *
     * The same solver as the neuron box, entered from the other end. Neurons are what the
     * architecture cares about; megabytes are what the *device* cares about, and they are not
     * interchangeable — memory grows roughly with the cube of the scale factor, so "twice the
     * neurons" and "twice the RAM" are very different requests. Offering both means you can
     * think in whichever unit your actual constraint is expressed in.
     *
     * The range is the device's, not an arbitrary one: the floor is the smallest legal brain and
     * the ceiling is the share of this app's heap Nora may safely claim. There is deliberately
     * no way to drag past the ceiling.
     */
    private fun memoryRow(): View {
        val ctx = this
        sliderMinBytes = NoraGeometry.minimum().estimateBytes()
        sliderMaxBytes = NoraGeometry.memoryBudgetBytes()

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
                    // Preview only while dragging. Committing per pixel would re-solve the
                    // geometry, rewrite preferences and drop the brain dozens of times per swipe.
                    if (!fromUser || suppressSlider) return
                    previewMemory(bytesForProgress(value))
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    if (suppressSlider) return
                    applyGeometry(NoraGeometry.largestWithin(bytesForProgress(progress)))
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

        // Why the ceiling is what it is. Without this the control looks broken on a high-RAM
        // device: an 8 GB phone still only grants this process a few hundred megabytes of
        // managed heap, and the connectome is FloatArrays, so that heap is the real wall.
        column.addView(TextView(ctx).apply {
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
            val ram = NoraGeometry.deviceRamBytes()
            text = buildString {
                append("Ceiling is Android's heap limit for this app")
                append(" (${NoraGeometry.formatBytes(NoraGeometry.heapCeilingBytes())}")
                if (ram > 0) append(" of ${NoraGeometry.formatBytes(ram)} device RAM")
                append("), not total RAM. Nora's tensors live on the managed heap, so that ")
                append("limit is a hard wall regardless of how much memory is free.")
            }
        })

        // A device whose safe share of the heap cannot hold even the smallest brain. Better to
        // say so than to present a control with no usable range.
        if (sliderMaxBytes <= sliderMinBytes) {
            ramSlider.isEnabled = false
            ramLabel.text =
                "This device's heap is too small for a resizable brain — " +
                    "${NoraGeometry.formatBytes(sliderMaxBytes)} available, " +
                    "${NoraGeometry.formatBytes(sliderMinBytes)} needed at minimum."
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
        val g = NoraGeometry.largestWithin(bytes)
        ramLabel.text = buildString {
            append("${NoraGeometry.formatBytes(bytes)} of ")
            append("${NoraGeometry.formatBytes(sliderMaxBytes)} available → ")
            append("${NoraGeometry.formatCount(g.totalNeurons)} neurons")
            append(" · %.1fx training cost".format(g.relativeTrainingCost()))
        }
    }

    /**
     * A label, a numeric field, and optionally a button to its right.
     *
     * The value is committed on IME-done or on losing focus, never per keystroke: every commit
     * re-solves the whole geometry and redraws every other field, which would be unusable if it
     * fired while the user was still typing a three-digit number.
     */
    private fun numberRow(
        title: String,
        detail: String,
        read: () -> Long,
        write: (Long) -> NoraGeometry,
        actionIcon: Int? = null,
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

        if (actionIcon != null && onAction != null) {
            row.addView(android.widget.ImageButton(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    IosUi.dp(ctx, 40f), IosUi.dp(ctx, 40f)
                ).apply { marginStart = IosUi.dp(ctx, 10f) }
                setImageResource(actionIcon)
                imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
                background = IosUi.fieldBackground(ctx)
                setPadding(IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f), IosUi.dp(ctx, 9f))
                contentDescription = actionDescription
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

    private fun applyGeometry(g: NoraGeometry) {
        if (!NoraStudio.applyGeometry(this, g)) {
            Toast.makeText(
                this,
                "Nora is busy — stop training or generation before resizing her.",
                Toast.LENGTH_LONG
            ).show()
            refreshSizes()
            return
        }
        refreshSizes()
    }

    private fun applyDeviceMaximum() {
        val max = NoraGeometry.maxForDevice()
        AlertDialog.Builder(this)
            .setTitle("Use this device's maximum?")
            .setMessage(
                "Largest size that fits in ${NoraGeometry.formatBytes(NoraGeometry.memoryBudgetBytes())} " +
                    "— the share of this app's heap Nora may use.\n\n" +
                    "neurons     ${NoraGeometry.formatCount(max.totalNeurons)}\n" +
                    "parameters  ${NoraGeometry.formatCount(max.totalParameters)}\n" +
                    "est. RAM    ${NoraGeometry.formatBytes(max.estimateBytes())}\n" +
                    "train cost  %.1fx default\n\n".format(max.relativeTrainingCost()) +
                    "This is bounded by memory, not by patience. At %.1fx the cost, a run that "
                        .format(max.relativeTrainingCost()) +
                    "takes an hour today would take roughly %.0f hours. Run the model test "
                        .format(max.relativeTrainingCost()) +
                    "afterwards to confirm it really fits — the estimate is a model, not a " +
                    "measurement."
            )
            .setPositiveButton("Use maximum") { _, _ -> applyGeometry(max) }
            .setNeutralButton("Reset to default") { _, _ -> applyGeometry(NoraGeometry.DEFAULT) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSizes() {
        for ((field, read) in sizeFields) {
            val text = read().toString()
            if (field.text.toString() != text) field.setText(text)
        }
        val g = NoraConfig.geometry

        // The slider tracks whatever the geometry now is, however it got there -- typing a
        // neuron count, editing one region, or the maximum button all move it. Suppressed
        // because setProgress fires the listener, which would immediately re-solve and fight
        // the change that triggered it.
        if (::ramSlider.isInitialized && ramSlider.isEnabled) {
            suppressSlider = true
            val bytes = g.estimateBytes()
            ramSlider.progress = progressForBytes(bytes)
            // Same shape as the drag preview, so letting go of the slider does not make the
            // readout appear to lose information.
            ramLabel.text = buildString {
                append("${NoraGeometry.formatBytes(bytes)} of ")
                append("${NoraGeometry.formatBytes(sliderMaxBytes)} available → ")
                append("${NoraGeometry.formatCount(g.totalNeurons)} neurons")
                append(" · %.1fx training cost".format(g.relativeTrainingCost()))
                if (bytes > sliderMaxBytes) {
                    append("\nOver budget. This size was set by hand and may not allocate — ")
                    append("run the model test to find out.")
                }
            }
            suppressSlider = false
        }
        costLine.text = buildString {
            append("${NoraGeometry.formatCount(g.totalParameters)} parameters · ")
            append("~${NoraGeometry.formatBytes(g.estimateBytes())} RAM · ")
            append("%.2fx default training cost".format(g.relativeTrainingCost()))
            append("\nSheet ${g.rings}x${g.wedges} · V1 ${g.v1Channels}ch · V2 ${g.v2Channels}ch · ")
            append("V4 ${g.v4Channels}ch · IT ${g.itChannels}ch · MT ${g.mtChannels}ch")
            append("\n\nEach size keeps its own connectome file, so changing this parks the " +
                "brain trained at the previous size rather than deleting it.")
        }
    }

    // ── Training ────────────────────────────────────────────────────────────

    private fun buildTrainingSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "TRAINING"))
        val card = IosUi.card(ctx)

        card.addView(navRow("Train Nora", "Teach her from your dataset, with a live connectome view") {
            startActivity(Intent(ctx, NoraTrainingActivity::class.java))
        })
        card.addView(IosUi.hairline(ctx))
        card.addView(
            navRow(
                "Test this size",
                "Train on nine generated shapes and report whether it fits, trains and renders"
            ) { startActivity(Intent(ctx, NoraSelfTestActivity::class.java)) }
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(
            toggleRow(
                "Denoising curriculum",
                "Train on corrupted images and recover the original — several times more " +
                    "supervision per image, at no generation-time cost",
                PrismSettings.getNoraDenoisingEnabled()
            ) { PrismSettings.setNoraDenoisingEnabled(it) }
        )
        card.addView(IosUi.hairline(ctx))

        val messagesActive = NoraAutoTrainWorker.messagesPageActive(ctx)
        val exempt = NoraAutoTrainWorker.batteryExempt(ctx)
        card.addView(
            toggleRow(
                "Autonomous training",
                when {
                    !messagesActive -> "Add the Messages page to a desktop slot to enable this."
                    !exempt -> "Runs while the phone is idle. Prism is not exempt from battery " +
                        "optimization, so Android will likely refuse the background start."
                    else -> "Retrain on her dataset while the phone is idle."
                },
                PrismSettings.getNoraAutoTrainEnabled() && messagesActive,
                enabled = messagesActive
            ) {
                PrismSettings.setNoraAutoTrainEnabled(it)
                NoraAutoTrainWorker.schedule(ctx)
            }
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(
            navRow(
                "Autonomous interval",
                "Currently every ${PrismSettings.getNoraAutoTrainIntervalHours()} hours"
            ) { pickInterval() }
        )
        card.addView(IosUi.hairline(ctx))
        card.addView(
            toggleRow(
                "Connectome visualization",
                "Live brain map during training. Own thread, but still costs frames on a hot phone",
                PrismSettings.getNoraVisualizerEnabled()
            ) { PrismSettings.setNoraVisualizerEnabled(it) }
        )
        content.addView(card)
    }

    private fun pickInterval() {
        val hours = (1..24).toList()
        AlertDialog.Builder(this)
            .setTitle("Autonomous training interval")
            .setItems(hours.map { "$it hour${if (it == 1) "" else "s"}" }.toTypedArray()) { _, i ->
                PrismSettings.setNoraAutoTrainIntervalHours(hours[i])
                NoraAutoTrainWorker.schedule(this)
                recreate()
            }
            .show()
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private fun buildDataSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "DATA"))
        val card = IosUi.card(ctx)
        card.addView(navRow("Backup Nora", "Connectome, dataset, feedback and conversation to a zip") {
            backupPicker.launch(NoraArchive.suggestedFileName())
        })
        card.addView(IosUi.hairline(ctx))
        card.addView(navRow("Import Nora", "Restore everything from a backup zip") {
            importPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        })
        card.addView(IosUi.hairline(ctx))
        card.addView(
            navRow("Erase connectome", "Delete what she has learned at the current size", destructive = true) {
                AlertDialog.Builder(ctx)
                    .setTitle("Erase Nora's connectome?")
                    .setMessage(
                        "Everything she has learned at the current size is deleted. Brains " +
                            "trained at other sizes are left alone."
                    )
                    .setPositiveButton("Erase") { _, _ ->
                        NoraStudio.forget(ctx)
                        Toast.makeText(ctx, "Connectome erased.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        content.addView(card)
        content.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 28f)
            )
        })
    }

    // ── Row builders ────────────────────────────────────────────────────────

    private fun navRow(
        title: String,
        detail: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f))
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = title
                textSize = 17f
                setTextColor(if (destructive) IosUi.destructive(ctx) else IosUi.label(ctx))
            })
            addView(TextView(ctx).apply {
                text = detail
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(ctx))
                setPadding(0, IosUi.dp(ctx, 3f), 0, 0)
            })
        }
    }

    private fun toggleRow(
        title: String,
        detail: String,
        value: Boolean,
        enabled: Boolean = true,
        onChanged: (Boolean) -> Unit
    ): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (enabled) 1f else 0.4f
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                this.text = title
                textSize = 17f
                setTextColor(IosUi.label(ctx))
            })
            addView(TextView(ctx).apply {
                this.text = detail
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(ctx))
                setPadding(0, IosUi.dp(ctx, 3f), 0, 0)
            })
        }
        val toggle = SwitchCompat(ctx).apply {
            isChecked = value
            isEnabled = enabled
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(text)
        row.addView(toggle)
        return row
    }

    private fun dialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Mixture-of-experts, which is experimental and says so.
     *
     * THE COPY HERE IS DELIBERATELY UNFLATTERING because the honest answer is unflattering. The
     * bench puts 81% of a learning step in the V1-to-retina link; IT-to-V4, which is what this
     * routes, is the cheapest link in the hierarchy. Presenting MoE as a speed feature -- which
     * is what everyone expects it to be -- would be selling something the measurement says is not
     * there. What it actually buys is IT capacity at roughly constant compute.
     *
     * The dependent controls grey out until the switch is on, matching how the out-of-core
     * options behave, so the shape of the feature is visible before it is enabled.
     */
    private fun buildExpertsSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "MIXTURE OF EXPERTS (EXPERIMENTAL)"))
        val card = IosUi.card(ctx)

        var enabled = PrismSettings.getNoraMoeEnabled()

        card.addView(
            toggleRow(
                "Use expert banks in IT",
                "Gives IT several weight banks and routes each image to one, by competitive " +
                    "learning.\n\nThis does NOT make training or generation faster -- the " +
                    "bottleneck is the V1-to-retina link, and this routes the cheapest link in " +
                    "the hierarchy. What it buys is more concept capacity for about the same " +
                    "compute. It may also improve prompt differentiation; watch the routing " +
                    "entropy in the model test to see whether the experts specialized or the " +
                    "router simply memorized one mode each.\n\nTakes effect on the next brain " +
                    "load. Existing training is preserved -- every expert starts as a copy of " +
                    "the weights you already have.",
                enabled
            ) { checked ->
                enabled = checked
                PrismSettings.setNoraMoeEnabled(checked)
                refreshExpertRows()
            }
        )

        card.addView(IosUi.hairline(ctx))
        expertCountRow = intStepperRow(
            "Experts",
            "How many weight banks IT gets. Each is a full copy of the IT-to-V4 weights, which " +
                "are a small fraction of Nora's footprint -- eight experts cost far less than " +
                "the semantic hub already does.",
            PrismSettings.getNoraMoeExperts(), 2, 16
        ) { PrismSettings.setNoraMoeExperts(it) }
        card.addView(expertCountRow)

        card.addView(IosUi.hairline(ctx))
        expertTopKRow = intStepperRow(
            "Active per image",
            "1 routes to a single expert. Higher blends the best few, which softens the " +
                "partition at the cost of running more than one bank.",
            PrismSettings.getNoraMoeTopK(), 1, 4
        ) { PrismSettings.setNoraMoeTopK(it) }
        card.addView(expertTopKRow)

        card.addView(IosUi.hairline(ctx))
        expertConscienceRow = intStepperRow(
            "Load balancing",
            "How hard an over-used expert is handicapped. 0 turns balancing off, and one expert " +
                "will then win everything while the rest never train -- which is worth seeing " +
                "once, because it is what this mechanism exists to prevent.",
            PrismSettings.getNoraMoeConscience().toInt(), 0, 50
        ) { PrismSettings.setNoraMoeConscience(it.toFloat()) }
        card.addView(expertConscienceRow)

        content.addView(card)
        refreshExpertRows()
    }

    private var expertCountRow: android.view.View? = null
    private var expertTopKRow: android.view.View? = null
    private var expertConscienceRow: android.view.View? = null

    /** Greys out the dependent rows when the feature is off, without hiding them. */
    private fun refreshExpertRows() {
        val on = PrismSettings.getNoraMoeEnabled()
        for (row in listOf(expertCountRow, expertTopKRow, expertConscienceRow)) {
            // setRowEnabled already walks the tree setting alpha and isEnabled -- it is the
            // same helper the gated out-of-core rows use, so these grey out identically.
            setRowEnabled(row ?: continue, on)
        }
    }

    /**
     * A label plus minus/plus buttons over an integer.
     *
     * A stepper rather than a slider because every one of these values is a small integer where
     * the exact number matters -- "4 experts" is a decision, "somewhere between 3 and 5" is not
     * a thing you can mean.
     */
    private fun intStepperRow(
        title: String,
        detail: String,
        initial: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit,
    ): android.view.View {
        val ctx = this
        var value = initial.coerceIn(min, max)

        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val top = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = android.widget.TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFE6E6EE.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        val readout = android.widget.TextView(ctx).apply {
            text = value.toString()
            textSize = 16f
            setTextColor(PrismSettings.getGlowColor())
            setPadding(dp(12), 0, dp(12), 0)
        }
        fun stepper(symbol: String, delta: Int) = android.widget.TextView(ctx).apply {
            text = symbol
            textSize = 20f
            setTextColor(0xFFB9B9C4.toInt())
            setPadding(dp(14), dp(2), dp(14), dp(2))
            setOnClickListener {
                if (!row.isEnabled) return@setOnClickListener
                val next = (value + delta).coerceIn(min, max)
                if (next != value) {
                    value = next
                    readout.text = value.toString()
                    onChange(value)
                }
            }
        }
        top.addView(label)
        top.addView(stepper("\u2212", -1))
        top.addView(readout)
        top.addView(stepper("+", 1))
        row.addView(top)
        row.addView(
            android.widget.TextView(ctx).apply {
                text = detail
                textSize = 12f
                setTextColor(0xFF83838F.toInt())
                setPadding(0, dp(4), 0, 0)
            }
        )
        return row
    }

}
