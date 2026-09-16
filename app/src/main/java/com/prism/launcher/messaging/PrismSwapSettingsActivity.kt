package com.prism.launcher.messaging

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * Sam's disk-backed swap -- see [PrismSwap]/[GgufInferenceService]'s three-tier RAM-shortfall
 * ladder. Split into its own Activity rather than added to the declarative `SettingsActivity`
 * list, same reasoning `NoraSettingsActivity` was: this needs continuous sliders (swap size, the
 * two activation thresholds), not the Header/Toggle/Picker/TextInput/Nav shapes that shared list
 * supports -- and `NoraSettingsActivity`'s own swap-size slider (`swapRow()`) is the template this
 * mirrors, generalized to three sliders instead of one.
 */
class PrismSwapSettingsActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout
    private lateinit var sizeRowContainer: View

    companion object {
        private const val SLIDER_STEPS = 1000
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
            text = "Prism Swap"
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

        buildSection()
    }

    override fun onResume() {
        super.onResume()
        refreshEnabledState()
    }

    private fun buildSection() {
        val ctx = this
        content.addView(IosUi.sectionHeader(ctx, "PRISM SWAP"))
        val toggleCard = IosUi.card(ctx)
        toggleCard.addView(
            toggleRow(
                "Enable Prism Swap",
                "Lets Sam's local (.gguf) text models spill onto disk when they don't fit in " +
                    "free RAM, instead of refusing to load.",
                PrismSettings.getPrismSwapEnabled()
            ) { checked ->
                PrismSettings.setPrismSwapEnabled(checked)
                if (!checked) PrismSwap.delete()
                refreshEnabledState()
            }
        )
        content.addView(toggleCard)
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "SWAP FILE SIZE"))
        val sizeCard = IosUi.card(ctx)
        sizeRowContainer = sizeRow(
            title = "Swap file size",
            minBytes = PrismSwap.MIN_BYTES,
            maxBytesProvider = { PrismSwap.freeStorageBytes().coerceAtLeast(PrismSwap.MIN_BYTES) },
            get = { PrismSettings.getPrismSwapBytes() },
            set = { PrismSettings.setPrismSwapBytes(it) },
            onCommitted = { if (PrismSettings.getPrismSwapEnabled()) PrismSwap.reset() }
        )
        sizeCard.addView(sizeRowContainer)
        content.addView(sizeCard)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Created sparse -- disk is consumed only as pages are actually written. Auto-raised " +
                    "whenever an imported model needs more than fits in free RAM."
            )
        )
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "AUTOMATIC ACTIVATION THRESHOLDS"))
        val thresholdCard = IosUi.card(ctx)
        thresholdCard.addView(
            sizeRow(
                title = "Mitigation threshold",
                minBytes = 0L,
                maxBytesProvider = { 2L shl 30 },
                get = { PrismSettings.getPrismSwapMitigationThresholdBytes() },
                set = { PrismSettings.setPrismSwapMitigationThresholdBytes(it) },
                onCommitted = {}
            )
        )
        thresholdCard.addView(IosUi.hairline(ctx))
        thresholdCard.addView(
            sizeRow(
                title = "Full swap threshold",
                minBytes = 0L,
                maxBytesProvider = { 8L shl 30 },
                get = { PrismSettings.getPrismSwapFullThresholdBytes() },
                set = { PrismSettings.setPrismSwapFullThresholdBytes(it) },
                onCommitted = {}
            )
        )
        content.addView(thresholdCard)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "When a model needs more RAM than is free, Prism tries progressively heavier " +
                    "fallbacks: under the mitigation threshold, a few quick parameter changes " +
                    "(mmap fallback, smaller KV cache and context). Under the full swap threshold, " +
                    "the model runs almost entirely off Prism Swap instead of RAM -- slower, but it " +
                    "runs. Short by more than both, the model is refused rather than failing later."
            )
        )
        content.addView(spacer())

        refreshEnabledState()
    }

    private fun refreshEnabledState() {
        if (!::sizeRowContainer.isInitialized) return
        val enabled = PrismSettings.getPrismSwapEnabled()
        sizeRowContainer.alpha = if (enabled) 1.0f else 0.4f
        sizeRowContainer.isEnabled = enabled
    }

    /** Generic GB-scale slider row -- shared shape for swap size and both activation thresholds,
     * all expressed as bytes 0..[maxBytesProvider], sliding in [SLIDER_STEPS] increments,
     * committed on release only (not per pixel -- each commit rewrites a preference and, for the
     * size slider, re-sizes the swap file). */
    private fun sizeRow(
        title: String, minBytes: Long, maxBytesProvider: () -> Long,
        get: () -> Long, set: (Long) -> Unit, onCommitted: () -> Unit
    ): View {
        val ctx = this
        val maxBytes = maxBytesProvider().coerceAtLeast(minBytes + 1)

        fun bytesFor(progress: Int): Long {
            if (maxBytes <= minBytes) return minBytes
            val t = progress.toDouble() / SLIDER_STEPS
            return minBytes + ((maxBytes - minBytes) * t).toLong()
        }
        fun progressFor(bytes: Long): Int {
            if (maxBytes <= minBytes) return 0
            val t = (bytes - minBytes).toDouble() / (maxBytes - minBytes)
            return (t * SLIDER_STEPS).toInt().coerceIn(0, SLIDER_STEPS)
        }
        fun describe(bytes: Long): String = "${formatBytes(bytes)} of ${formatBytes(maxBytes)}"

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        column.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(IosUi.label(ctx))
        })

        val label = TextView(ctx).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 4f), 0, 0)
            text = describe(get())
        }

        val slider = android.widget.SeekBar(ctx).apply {
            max = SLIDER_STEPS
            progressTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            thumbTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }
            progress = progressFor(get())

            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    label.text = describe(bytesFor(value))
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    set(bytesFor(progress))
                    label.text = describe(get())
                    onCommitted()
                }
            })
        }
        column.addView(slider)
        column.addView(label)
        return column
    }

    private fun formatBytes(v: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = v.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) { value /= 1024.0; unit++ }
        return if (unit == 0) "$v ${units[0]}" else "%.2f %s".format(value, units[unit])
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@PrismSwapSettingsActivity, 24f))
    }

    private fun toggleRow(title: String, detail: String, value: Boolean, onChanged: (Boolean) -> Unit): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f))
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
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        })
        return row
    }
}
