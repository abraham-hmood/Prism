package com.prism.launcher.nora

import android.graphics.Bitmap
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prism.launcher.PrismBaseActivity
import kotlinx.coroutines.launch

/**
 * Trains the current brain size on a tiny generated dataset and reports what happened.
 *
 * This is the answer to "is the size I just picked actually usable?", which neither a neuron
 * count nor a memory estimate can tell you. It measures three things the estimates cannot:
 *
 *   IT FITS         a geometry whose estimate cleared the budget can still hit
 *                   OutOfMemoryError against real heap fragmentation. That is caught here and
 *                   reported as a result rather than a crash, which is the entire reason the
 *                   test constructs its own brain instead of using the shared one.
 *   IT TRAINS       divergence is reported by NoraHealth, and a run that goes non-finite says so.
 *   IT RENDERS      the surface dynamic range is checked, so the black-image collapse shows up
 *                   as a number here instead of as a mystery weeks later.
 *
 * SANDBOXED. Its own [NoraBrain], its own trainer, `sandbox = true` so nothing is loaded from or
 * written to the real connectome. Measuring a candidate size must never cost you the brain you
 * actually trained.
 */
class NoraSelfTestActivity : PrismBaseActivity() {

    private lateinit var summary: TextView
    private lateinit var epochLine: TextView
    private lateinit var detailLine: TextView
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var runButton: TextView
    private lateinit var regenButton: TextView
    private lateinit var buttonRow: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var releaseLine: TextView
    private lateinit var routeValue: TextView

    /** Which generation route is under test. Saccadic is the default because it is Nora's. */
    private var route: NoraImageryMode = NoraImageryMode.DETERMINISTIC

    private fun pickRoute() {
        val modes = NoraImageryMode.entries.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Route to test")
            .setItems(modes.map { NoraSelfTest.routeName(it) }.toTypedArray()) { _, i ->
                route = modes[i]
                routeValue.text = "Route: ${NoraSelfTest.routeName(route)}"
                if (::regenButton.isInitialized) refreshRegenButton()
            }
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
            text = "Model Test"
            textSize = 28f
            setTextColor(IosUi.label(ctx))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 4f))
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        // ── Geometry under test ─────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(ctx, "SIZE UNDER TEST"))
        val card = IosUi.card(ctx)
        summary = TextView(ctx).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(ctx))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        card.addView(summary)
        content.addView(card)

        // ── Run control ─────────────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(ctx, "TEST"))
        val runCard = IosUi.card(ctx)

        epochLine = TextView(ctx).apply {
            text = "Not started"
            textSize = 17f
            setTextColor(IosUi.label(ctx))
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 2f))
        }
        detailLine = TextView(ctx).apply {
            text = "${NoraSelfTest.DEFAULT_EPOCHS} epochs on 9 generated shapes"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(IosUi.dp(ctx, 16f), 0, IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f))
        }
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = IosUi.progressDrawable(ctx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 6f)
            ).apply {
                marginStart = IosUi.dp(ctx, 16f)
                marginEnd = IosUi.dp(ctx, 16f)
                bottomMargin = IosUi.dp(ctx, 12f)
            }
        }
        runButton = IosUi.filledButton(ctx, "Run Test").apply {
            setOnClickListener {
                if (NoraSelfTestState.running.value) NoraService.stop(ctx)
                else NoraService.runSelfTest(ctx, route)
            }
        }

        // Route picker. Default is saccadic — the route Nora uses when no command is given, so
        // testing it is testing what she actually does most of the time.
        routeValue = TextView(ctx).apply {
            text = "Route: ${NoraSelfTest.routeName(route)}"
            textSize = 15f
            setTextColor(IosUi.accent(ctx))
            isClickable = true
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
            setOnClickListener { pickRoute() }
        }
        runCard.addView(routeValue)
        runCard.addView(IosUi.hairline(ctx))

        // Regenerate. Sits beside Run/Cancel and appears only once a trained brain is standing
        // by, because that is exactly when it means anything: training is route-independent and
        // slow, generation is route-specific and fast, so comparing routes at one size should
        // cost one training run rather than four.
        regenButton = IosUi.tintedButton(ctx, "Regenerate").apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = IosUi.dp(ctx, 8f) }
            setOnClickListener {
                if (NoraSelfTestState.running.value) return@setOnClickListener
                NoraService.regenerate(ctx, route)
            }
        }
        runButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = IosUi.dp(ctx, 16f)
                marginEnd = IosUi.dp(ctx, 16f)
                bottomMargin = IosUi.dp(ctx, 14f)
            }
            addView(regenButton)
            addView(runButton)
        }

        runCard.addView(epochLine)
        runCard.addView(detailLine)
        runCard.addView(progress)
        runCard.addView(buttonRow)
        content.addView(runCard)

        // ── Generated samples ───────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(ctx, "GENERATED AFTER TRAINING"))
        val resultCard = IosUi.card(ctx)
        results = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(IosUi.dp(ctx, 12f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 12f))
        }
        resultCard.addView(results)
        content.addView(resultCard)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Every prompt below was in the training set. If they are blank, or identical to " +
                    "each other, the size under test is not learning — that is what this " +
                    "measures. Nothing here touches the connectome you actually use."
            )
        )

        // The trained brain stays resident so routes can be compared without retraining, which
        // at a large geometry is a lot of memory to hold indefinitely. Anyone who would rather
        // have it back can say so, instead of restarting the app to get the same effect.
        releaseLine = TextView(ctx).apply {
            textSize = 13f
            setTextColor(IosUi.destructive(ctx))
            isClickable = true
            visibility = View.GONE
            setPadding(IosUi.dp(ctx, 20f), IosUi.dp(ctx, 4f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 8f))
            setOnClickListener {
                NoraSelfTestState.releaseRetained()
                android.widget.Toast.makeText(
                    ctx, "Trained brain released", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        content.addView(releaseLine)

        // ── Log ─────────────────────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(ctx, "LOG"))
        val logCard = IosUi.card(ctx)
        log = TextView(ctx).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(ctx))
            movementMethod = ScrollingMovementMethod()
            setPadding(IosUi.dp(ctx, 14f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 12f))
        }
        logScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 220f)
            )
            addView(log)
        }
        logCard.addView(logScroll)
        content.addView(logCard)
        content.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 24f)
            )
        })

        root.addView(scroll)
        setContentView(root)

        refreshSummary()
        observeState()
    }

    /**
     * Renders whatever the service is doing, from scratch, every time this screen appears.
     *
     * The activity holds no run state of its own. That is the point: the test runs in
     * [NoraService], so leaving and coming back mid-run rebuilds the log, the progress and any
     * finished result out of [NoraSelfTestState] rather than showing an empty form.
     */
    private fun observeState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    NoraSelfTestState.regenerable.collect { refreshRegenButton() }
                }
                launch {
                    NoraSelfTestState.running.collect { running ->
                        refreshRegenButton()
                        runButton.text = if (running) "Cancel" else "Run Test"
                        if (!running && NoraSelfTestState.outcome.value == null &&
                            NoraSelfTestState.log.value.isEmpty()
                        ) {
                            epochLine.text = "Not started"
                            detailLine.text = "${NoraSelfTest.DEFAULT_EPOCHS} epochs on 9 generated shapes"
                        }
                    }
                }
                launch {
                    NoraSelfTestState.progress.collect { p ->
                        if (p == null) return@collect
                        progress.progress = p.percent
                        epochLine.text = "Epoch ${p.epoch} of ${p.totalEpochs}"
                        detailLine.text = if (p.phase == "sleep") {
                            "consolidating"
                        } else {
                            "image ${p.image}/${p.totalImages} · err %.4f · %s"
                                .format(p.errorRms, p.caption)
                        }
                    }
                }
                launch {
                    // Whole-log replacement rather than appends: the screen may be arriving
                    // partway through a run, and an append-only view would start from wherever
                    // it happened to attach.
                    NoraSelfTestState.log.collect { lines ->
                        log.text = lines.joinToString("\n")
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                launch {
                    NoraSelfTestState.outcome.collect { outcome ->
                        results.removeAllViews()
                        if (outcome == null) return@collect
                        epochLine.text = outcome.headline
                        progress.progress = 100
                        for ((prompt, path) in outcome.samplePaths) {
                            val bmp = runCatching {
                                android.graphics.BitmapFactory.decodeFile(path)
                            }.getOrNull() ?: continue
                            addSample(prompt, bmp)
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows the regenerate button only when it would do something.
     *
     * Three conditions, all necessary: a trained brain is retained, no job is running, and a
     * result is on screen. A button that is present but inert is worse than an absent one --
     * it invites a tap that silently does nothing, which is indistinguishable from a bug.
     */
    private fun refreshRegenButton() {
        val ready = NoraSelfTestState.regenerable.value && !NoraSelfTestState.running.value
        buttonRow.removeView(regenButton)
        if (ready) {
            buttonRow.addView(regenButton, 0)
            regenButton.text = "Regenerate: ${NoraSelfTest.routeName(route)}"
        }
        if (::releaseLine.isInitialized) {
            releaseLine.visibility = if (ready) View.VISIBLE else View.GONE
            releaseLine.text = "Release the trained brain " +
                "(${NoraGeometry.formatBytes(NoraConfig.geometry.estimateBytes())} held for regeneration)"
        }
    }

    private fun refreshSummary() {
        val g = NoraConfig.geometry
        summary.text = buildString {
            appendLine("neurons     ${NoraGeometry.formatCount(g.totalNeurons)}")
            appendLine("parameters  ${NoraGeometry.formatCount(g.totalParameters)}")
            appendLine("est. RAM    ${NoraGeometry.formatBytes(g.estimateBytes())}")
            appendLine("train cost  %.2fx default".format(g.relativeTrainingCost()))
            appendLine("sheet       ${g.rings} x ${g.wedges}")
            append("V1 ${g.v1Channels}ch  V2 ${g.v2Channels}ch  ")
            append("V4 ${g.v4Channels}ch  IT ${g.itChannels}ch")
        }
    }

    /** One generated sample, decoded from the cache file the run wrote it to. */
    private fun addSample(prompt: String, bitmap: Bitmap) {
        val ctx = this
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(ImageView(ctx).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                IosUi.dp(ctx, 92f), IosUi.dp(ctx, 92f)
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        })
        column.addView(TextView(ctx).apply {
            text = prompt
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 6f), 0, 0)
        })
        results.addView(column)
    }
}
