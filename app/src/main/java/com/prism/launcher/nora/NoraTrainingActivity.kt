package com.prism.launcher.nora

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismFontEngine
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nora's training screen, styled after iOS's grouped-settings idiom.
 *
 * Two tabs below the controls: a training log, and a live visualization of her connectome.
 * The visualization renders on its own thread ([NoraBrainView]) and is gated on a Prism
 * setting, because on a phone that is already saturated by training, drawing thirty frames a
 * second is not free.
 */
class NoraTrainingActivity : PrismBaseActivity() {

    private lateinit var statusValue: TextView
    private lateinit var datasetValue: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var progressCaption: TextView
    private lateinit var trainButton: TextView
    private lateinit var epochsInput: EditText
    private lateinit var focusInput: EditText
    private lateinit var tabs: IosSegmentedControl
    private lateinit var tabContainer: FrameLayout
    private lateinit var logPane: View
    private lateinit var brainPane: View
    private var brainView: NoraBrainView? = null

    /** Only guards the one-time battery-exemption prompt; training state lives in the service. */
    private var batteryPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(ctx))
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, IosUi.dp(ctx, 28f))
        }
        scroll.addView(content)

        content.addView(largeTitle(ctx))
        content.addView(overviewSection(ctx))
        content.addView(controlsSection(ctx))
        content.addView(actionsSection(ctx))
        content.addView(tabsSection(ctx))

        root.addView(scroll)
        setContentView(root)

        applyFontRecursively(root)
        refreshStatus()
        observeTraining()
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private fun largeTitle(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 20f), 0)
        }
        box.addView(TextView(ctx).apply {
            text = "Nora"
            textSize = 34f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(ctx))
        })
        box.addView(TextView(ctx).apply {
            text = "Simulated visual cortex · trains and generates on-device"
            textSize = 15f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 2f), 0, 0)
        })
        return box
    }

    private fun overviewSection(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(IosUi.sectionHeader(ctx, "Connectome"))

        val card = IosUi.card(ctx)
        card.addView(valueRow(ctx, "Dataset") { datasetValue = it })
        card.addView(IosUi.hairline(ctx))
        card.addView(valueRow(ctx, "State") { statusValue = it })
        box.addView(inset(ctx, card))

        box.addView(
            IosUi.sectionFooter(
                ctx,
                "Drop images into ${NoraConfig.datasetDir().absolutePath} and name each file " +
                    "after what it shows — “a red apple on a table.png” teaches exactly that."
            )
        )
        return box
    }

    private fun controlsSection(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(IosUi.sectionHeader(ctx, "Session"))

        val card = IosUi.card(ctx)

        epochsInput = fieldFor(ctx, "10", InputType.TYPE_CLASS_NUMBER)
        card.addView(fieldRow(ctx, "Epochs", epochsInput, IosUi.dp(ctx, 78f)))
        card.addView(IosUi.hairline(ctx))
        focusInput = fieldFor(ctx, "", InputType.TYPE_CLASS_TEXT)
        focusInput.hint = "optional"
        card.addView(fieldRow(ctx, "Focus word", focusInput, 0))

        card.addView(IosUi.hairline(ctx))
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressDrawable = IosUi.progressDrawable(ctx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 6f)
            )
        }
        card.addView(progress)
        progressCaption = TextView(ctx).apply {
            text = "Not running"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 8f), 0, 0)
        }
        card.addView(progressCaption)

        box.addView(inset(ctx, card))
        box.addView(
            IosUi.sectionFooter(
                ctx,
                "A focus word doubles dopamine on matching examples, so their synapses " +
                    "potentiate harder and survive the sleep phase’s downscaling."
            )
        )
        return box
    }

    private fun actionsSection(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 18f), IosUi.dp(ctx, 16f), 0)
        }

        trainButton = IosUi.filledButton(ctx, "Start Training").apply {
            setOnClickListener { toggleTraining() }
        }
        box.addView(trainButton)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, IosUi.dp(ctx, 10f), 0, 0)
        }
        row.addView(IosUi.tintedButton(ctx, "Test Image").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = IosUi.dp(ctx, 5f) }
            setOnClickListener { testGenerate() }
        })
        row.addView(IosUi.tintedButton(ctx, "Forget", IosUi.destructive(ctx)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = IosUi.dp(ctx, 5f) }
            setOnClickListener { confirmForget() }
        })
        box.addView(row)
        return box
    }

    private fun tabsSection(ctx: android.content.Context): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        tabs = IosSegmentedControl(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 20f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
            }
            setSegments(listOf("Log", "Brain"))
            onSelected = { showTab(it) }
        }
        box.addView(tabs)

        tabContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 320f)
            ).apply { setMargins(IosUi.dp(ctx, 16f), 0, IosUi.dp(ctx, 16f), 0) }
        }

        logPane = buildLogPane(ctx)
        brainPane = buildBrainPane(ctx)
        tabContainer.addView(logPane)
        tabContainer.addView(brainPane)
        brainPane.visibility = View.GONE

        box.addView(tabContainer)
        return box
    }

    private fun buildLogPane(ctx: android.content.Context): View {
        logView = TextView(ctx).apply {
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(ctx))
            movementMethod = ScrollingMovementMethod()
            setPadding(IosUi.dp(ctx, 14f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 12f))
        }
        logScroll = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(IosUi.cardBackground(ctx))
                cornerRadius = IosUi.dp(ctx, 12f).toFloat()
            }
            clipToOutline = true
            addView(logView)
        }
        return logScroll
    }

    private fun buildBrainPane(ctx: android.content.Context): View {
        val frame = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(IosUi.cardBackground(ctx))
                cornerRadius = IosUi.dp(ctx, 12f).toFloat()
            }
            clipToOutline = true
        }

        if (PrismSettings.getNoraVisualizerEnabled()) {
            brainView = NoraBrainView(ctx).apply {
                setDarkTheme(IosUi.isDark(ctx))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            frame.addView(brainView)
        } else {
            frame.addView(TextView(ctx).apply {
                text = "Connectome visualization is turned off.\n\n" +
                    "Prism Settings → Intelligence & Messaging → Nora"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(ctx))
                setPadding(IosUi.dp(ctx, 24f), 0, IosUi.dp(ctx, 24f), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        }
        return frame
    }

    private fun showTab(index: Int) {
        logPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        brainPane.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    // ── Row builders ────────────────────────────────────────────────────────

    private fun valueRow(
        ctx: android.content.Context,
        title: String,
        bind: (TextView) -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })
        val value = TextView(ctx).apply {
            textSize = 17f
            gravity = Gravity.END
            setTextColor(IosUi.secondaryLabel(ctx))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bind(value)
        row.addView(value)
        return row
    }

    private fun fieldRow(
        ctx: android.content.Context,
        title: String,
        field: EditText,
        fixedWidth: Int
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })
        field.layoutParams = if (fixedWidth > 0) {
            LinearLayout.LayoutParams(fixedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, 0f)
                .apply { marginStart = IosUi.dp(ctx, 12f) }
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = IosUi.dp(ctx, 12f) }
        }
        row.addView(field)
        return row
    }

    private fun fieldFor(ctx: android.content.Context, initial: String, type: Int): EditText =
        EditText(ctx).apply {
            setText(initial)
            inputType = type
            textSize = 16f
            gravity = Gravity.END
            background = IosUi.fieldBackground(ctx)
            setTextColor(IosUi.label(ctx))
            setHintTextColor(IosUi.tertiaryLabel(ctx))
            val p = IosUi.dp(ctx, 10f)
            setPadding(p, IosUi.dp(ctx, 8f), p, IosUi.dp(ctx, 8f))
            maxLines = 1
        }

    private fun inset(ctx: android.content.Context, child: View): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(ctx, 16f), 0, IosUi.dp(ctx, 16f), 0)
            addView(child)
        }

    private fun applyFontRecursively(view: View) {
        PrismFontEngine.applyToView(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyFontRecursively(view.getChildAt(i))
        }
    }

    // ── Behaviour ───────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val n = NoraStudio.datasetSize(this)
        datasetValue.text = "$n image${if (n == 1) "" else "s"}"
        statusValue.text = if (NoraStudio.isTrained(this)) {
            "${NoraPersistence.sizeBytes() / 1024} KB on disk"
        } else {
            "Untrained"
        }
    }

    private fun log(line: String) {
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Subscribes the UI to the service's state.
     *
     * Everything the screen shows is derived from [NoraTrainingState], never held here, so the
     * activity can be destroyed and recreated mid-run without interrupting or losing anything.
     * The log replays on collect, which is what repaints the history when you come back to a
     * job that has been running for hours.
     */
    private fun observeTraining() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    NoraTrainingState.log.collect { line ->
                        logView.append(line + "\n")
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                launch {
                    NoraTrainingState.running.collect { running ->
                        setTrainingUi(running)
                        if (!running) refreshStatus()
                    }
                }
                launch {
                    NoraTrainingState.progress.collect { p ->
                        if (p == null) return@collect
                        progress.progress = NoraTrainingState.percent
                        progressCaption.text = if (p.phase == "sleep") {
                            "Epoch ${p.epoch} · consolidating"
                        } else {
                            "Epoch ${p.epoch} of ${p.totalEpochs} · " +
                                "image ${p.sample} of ${p.totalSamples}"
                        }
                    }
                }
            }
        }
    }

    private fun toggleTraining() {
        if (NoraTrainingState.running.value) {
            NoraService.stop(this)
            return
        }

        val epochs = epochsInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 10
        val focus = focusInput.text.toString().trim().ifBlank { null }

        if (!hasStorageAccess()) {
            // Without all-files access the dataset directory reads as empty even when it is
            // full, which looks exactly like "no images" and sends people hunting for a bug
            // that isn't there. Say what is actually wrong.
            requestStorageAccess()
            return
        }

        if (NoraStudio.datasetSize(this) == 0) {
            Toast.makeText(this, "No images in the dataset folder", Toast.LENGTH_LONG).show()
            return
        }

        ensureNotificationPermission()
        NoraService.startTraining(this, epochs, focus)
        // Optimistic: the service flips this itself a moment later, but waiting for the round
        // trip makes the button feel unresponsive.
        setTrainingUi(true)
        promptBatteryExemptionOnce()
    }

    /**
     * Without this the notification silently never appears on Android 13+, and the only symptom
     * is a training run you cannot monitor.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
    }

    /**
     * A foreground service plus a wake lock still gets throttled by Doze over a long idle
     * stretch, which is exactly the overnight case. This exemption cannot be granted from code,
     * so it has to be asked for -- once, and only if training is actually being used.
     */
    private fun promptBatteryExemptionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (batteryPromptShown) return
        batteryPromptShown = true

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Let Nora train overnight?")
            .setMessage(
                "Android throttles long-running work when the screen is off. Exempting Prism " +
                    "from battery optimization lets a multi-hour training run finish instead " +
                    "of stalling. Training will still work without it — it will just be slower " +
                    "and may pause while the phone is idle."
            )
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Couldn't open battery settings", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun setTrainingUi(running: Boolean) {
        trainButton.text = if (running) "Cancel" else "Start Training"
        trainButton.background = IosUi.filledBackground(
            this,
            if (running) IosUi.destructive(this) else IosUi.accent(this)
        )
        if (!running) {
            progress.progress = 0
            progressCaption.text = "Not running"
            NoraTelemetry.live = false
        }
    }

    /**
     * Runs in the service, not here, so rotating the phone or leaving the screen mid-generation
     * doesn't cancel it. Results arrive through the shared log.
     */
    private fun testGenerate() {
        val prompt = focusInput.text.toString().trim().ifBlank { "a shape" }
        ensureNotificationPermission()
        NoraService.testImage(this, prompt)
    }

    private fun hasStorageAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    private fun requestStorageAccess() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Storage access needed")
            .setMessage(
                "Nora reads her dataset from ${NoraConfig.datasetDir().absolutePath} and " +
                    "checkpoints her connectome alongside it. Without all-files access that " +
                    "folder reads as empty even when it isn't."
            )
            .setPositiveButton("Grant") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun confirmForget() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Erase Nora's connectome?")
            .setMessage(
                "This deletes every weight, every episode, and her whole vocabulary. " +
                    "Training starts from a newborn cortex again. Images in the dataset folder are kept."
            )
            .setPositiveButton("Erase") { _, _ ->
                NoraStudio.forget(this)
                NoraTelemetry.clear()
                log("Connectome erased.")
                refreshStatus()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** Tapping the notification re-delivers the intent here rather than stacking a new screen. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshStatus()
    }

    override fun onDestroy() {
        // Deliberately does NOT stop training. The service owns the run, and the whole point is
        // that closing this screen -- or having it destroyed while the phone sits idle
        // overnight -- leaves the job untouched.
        if (!NoraTrainingState.running.value) NoraTelemetry.live = false
        super.onDestroy()
    }
}
