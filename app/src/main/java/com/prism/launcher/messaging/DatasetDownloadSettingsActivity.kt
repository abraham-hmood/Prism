package com.prism.launcher.messaging

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dataset discovery/download settings -- Hugging Face + GitHub, via [DatasetDiscoveryService],
 * routed into Nora's and Aether's own dataset directories by [DatasetDownloader]. Split into its
 * own Activity rather than added to the declarative `SettingsActivity` list, same reasoning
 * [PrismSwapSettingsActivity] was: this needs a range slider, a search bar, and a live dataset
 * list, not the Header/Toggle/Picker/TextInput/Nav shapes that shared list supports.
 *
 * Controls: (1) a toggle for automatic downloads, (2) a toggle for git-based downloading
 * (default on -- see [DatasetDownloader]'s own doc comment on why this is a strict improvement
 * over plain HTTP, never a riskier alternative to it), (3) a number textbox for the
 * automatic-check interval in hours, (4) a min/max range slider for dataset size, (5) a live list
 * of datasets within that range -- tapping one downloads it immediately, on top of whatever the
 * automatic toggle is doing, and (6) a search bar below the list (typing `-random` searches a
 * randomized topic and skips datasets already shown, rather than searching for the literal text
 * "-random").
 */
class DatasetDownloadSettingsActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout
    private lateinit var intervalInput: EditText
    private lateinit var sizeRangeLabel: TextView
    private lateinit var datasetListContainer: LinearLayout
    private lateinit var searchInput: EditText

    /** The query the list is currently showing -- reused when the size range changes so a
     * narrowed/widened range re-searches the same topic rather than resetting to the default. */
    private var currentQuery: String = ""

    companion object {
        private const val MIN_SIZE_BYTES = 10L * 1024 * 1024 // 10MB
        private const val MAX_SIZE_BYTES = 10L * 1024 * 1024 * 1024 // 10GB
        private const val RANDOM_TOKEN = "-random"
        private const val MAX_RANDOM_ATTEMPTS = 4

        /** Generic, broad topics biased toward what this feature can actually use (images,
         * plain text, small/sharded datasets) -- there is no "random dataset" API on either
         * Hugging Face or GitHub, so `-random` approximates one by trying a randomized term from
         * here and filtering out anything already shown (see [getSeenDatasetRepoIds]). */
        private val RANDOM_QUERY_POOL = listOf(
            "images", "photos", "text", "captions", "conversations", "stories", "articles",
            "reviews", "recipes", "poems", "faces", "animals", "nature", "objects",
            "handwriting", "digits", "letters", "words", "sentences", "descriptions", "labels",
            "small", "mini", "tiny", "sample", "corpus", "dialogue", "questions", "translations"
        )
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
            text = "Dataset Downloads"
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

        buildSections()
        // refreshDatasetList() not called here too -- onResume always follows onCreate/onStart on
        // first launch as well, so calling it in both places would just fire the same search twice.
    }

    override fun onResume() {
        super.onResume()
        refreshDatasetList()
    }

    private fun buildSections() {
        val ctx = this

        content.addView(IosUi.sectionHeader(ctx, "AUTOMATIC DOWNLOADS"))
        val toggleCard = IosUi.card(ctx)
        toggleCard.addView(
            toggleRow(
                "Download datasets automatically",
                "Looks for and downloads datasets from Hugging Face and GitHub on the schedule " +
                    "below, saved straight into Nora's and Aether's dataset folders.",
                PrismSettings.getDatasetAutoDownloadEnabled()
            ) { checked ->
                PrismSettings.setDatasetAutoDownloadEnabled(checked)
                DatasetDownloadWorker.schedule(ctx)
            }
        )
        content.addView(toggleCard)
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "DOWNLOAD METHOD"))
        val gitCard = IosUi.card(ctx)
        gitCard.addView(
            toggleRow(
                "Download whole repos via git",
                "Hugging Face and GitHub both serve datasets as real git repositories -- cloning " +
                    "the whole thing at once is faster and more reliable than fetching every " +
                    "file individually. Falls back to plain downloads automatically for anything " +
                    "git can't retrieve (e.g. a Git LFS pointer) or if git isn't reachable.",
                PrismSettings.getDatasetUseGitEnabled()
            ) { checked -> PrismSettings.setDatasetUseGitEnabled(checked) }
        )
        content.addView(gitCard)
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "CHECK INTERVAL"))
        val intervalCard = IosUi.card(ctx)
        intervalInput = fieldFor(ctx, PrismSettings.getDatasetAutoDownloadIntervalHours().toString())
        intervalCard.addView(fieldRow(ctx, "Check every (hours)", intervalInput, IosUi.dp(ctx, 78f)))
        content.addView(intervalCard)
        content.addView(
            IosUi.sectionFooter(ctx, "1-24 hours. Applied as soon as you leave this screen.")
        )
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "DATASET SIZE RANGE"))
        val sizeCard = IosUi.card(ctx)
        sizeCard.addView(sizeRangeRow())
        content.addView(sizeCard)
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Only datasets whose total size falls within this range are shown below, " +
                    "searched, or downloaded automatically."
            )
        )
        content.addView(spacer())

        content.addView(IosUi.sectionHeader(ctx, "AVAILABLE DATASETS"))
        val listCard = IosUi.card(ctx)
        datasetListContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(datasetListContainer)
        content.addView(listCard)
        content.addView(searchRow())
        content.addView(
            IosUi.sectionFooter(
                ctx,
                "Tap a dataset to download it now, regardless of the automatic-download toggle " +
                    "above. Images go to both Nora's and Aether's dataset folders; text/.parquet " +
                    "files go to Aether's only. Search for a topic above, or type -random to see " +
                    "datasets you haven't been shown yet."
            )
        )
        content.addView(spacer())
    }

    override fun onPause() {
        // Persisted on the way out rather than per-keystroke, same reasoning the Aether training
        // page's own epoch/checkpoint-interval fields use.
        val hours = intervalInput.text.toString().toIntOrNull()?.coerceIn(1, 24)
            ?: PrismSettings.getDatasetAutoDownloadIntervalHours()
        PrismSettings.setDatasetAutoDownloadIntervalHours(hours)
        DatasetDownloadWorker.schedule(this)
        super.onPause()
    }

    // ── Dataset list ────────────────────────────────────────────────────────

    private fun refreshDatasetList(query: String = currentQuery) {
        val ctx = this
        currentQuery = query
        datasetListContainer.removeAllViews()
        datasetListContainer.addView(TextView(ctx).apply {
            text = "Searching Hugging Face and GitHub…"
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f))
        })

        lifecycleScope.launch {
            val minBytes = PrismSettings.getDatasetMinSizeBytes()
            val maxBytes = PrismSettings.getDatasetMaxSizeBytes()
            val isRandom = query.trim().equals(RANDOM_TOKEN, ignoreCase = true)

            val results = withContext(Dispatchers.IO) {
                try {
                    if (isRandom) searchRandom(minBytes, maxBytes)
                    else DatasetDiscoveryService.discoverAll(
                        query = query.ifBlank { "dataset" }, minSizeBytes = minBytes, maxSizeBytes = maxBytes
                    )
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (isRandom && results.isNotEmpty()) {
                PrismSettings.addSeenDatasetRepoIds(results.map { it.repoId })
            }
            renderDatasetList(results, isRandom)
        }
    }

    /** Tries a few different random topics (see [RANDOM_QUERY_POOL]) until one turns up a
     * dataset the user hasn't already been shown, within [MAX_RANDOM_ATTEMPTS] tries -- there is
     * no "random dataset" API on either source, so this is the closest honest approximation:
     * genuinely varied topics, with repeats filtered out. */
    private suspend fun searchRandom(minBytes: Long, maxBytes: Long): List<DatasetDiscoveryService.DiscoveredDataset> {
        val seen = PrismSettings.getSeenDatasetRepoIds()
        val triedTerms = mutableSetOf<String>()
        repeat(MAX_RANDOM_ATTEMPTS) {
            val term = RANDOM_QUERY_POOL.filter { it !in triedTerms }.randomOrNull() ?: return@repeat
            triedTerms.add(term)
            val results = try {
                DatasetDiscoveryService.discoverAll(query = term, minSizeBytes = minBytes, maxSizeBytes = maxBytes)
            } catch (e: Exception) {
                emptyList()
            }
            val unseen = results.filter { it.repoId !in seen }
            if (unseen.isNotEmpty()) return unseen
        }
        return emptyList()
    }

    private fun renderDatasetList(datasets: List<DatasetDiscoveryService.DiscoveredDataset>, isRandom: Boolean) {
        val ctx = this
        datasetListContainer.removeAllViews()

        if (datasets.isEmpty()) {
            datasetListContainer.addView(TextView(ctx).apply {
                text = if (isRandom)
                    "No new datasets found in that range -- you may have already seen everything " +
                        "small enough to fit. Try widening the size range."
                else
                    "No datasets found in that range. Try widening the size range, or a different search."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(ctx))
                setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f))
            })
            return
        }

        val downloaded = PrismSettings.getDownloadedDatasetRepoIds()
        for ((index, dataset) in datasets.withIndex()) {
            if (index > 0) datasetListContainer.addView(IosUi.hairline(ctx))
            datasetListContainer.addView(datasetRow(dataset, downloaded.contains(dataset.repoId)))
        }
    }

    private fun datasetRow(dataset: DatasetDiscoveryService.DiscoveredDataset, alreadyDownloaded: Boolean): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }

        val title = TextView(ctx).apply {
            text = dataset.repoId
            textSize = 15f
            setTextColor(IosUi.label(ctx))
        }
        row.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "${dataset.source} · ${DatasetDiscoveryService.formatSize(dataset.totalSizeBytes)} · " +
                "${dataset.files.size} file(s)" + if (alreadyDownloaded) " · Downloaded" else ""
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(ctx))
            setPadding(0, IosUi.dp(ctx, 3f), 0, 0)
        }
        row.addView(subtitle)

        row.setOnClickListener { startDownload(dataset, subtitle) }
        return row
    }

    private fun startDownload(dataset: DatasetDiscoveryService.DiscoveredDataset, subtitle: TextView) {
        val progressDialog = ModelLoadProgressDialog(this, title = "Downloading Dataset")
        progressDialog.show()
        val maxBytes = PrismSettings.getDatasetMaxSizeBytes()

        lifecycleScope.launch(Dispatchers.IO) {
            val (files, bytes) = DatasetDownloader.download(dataset, maxBytes) { idx, total, name ->
                val pct = if (total > 0) (idx * 100) / total else 0
                progressDialog.update("$name ($idx/$total)", pct)
            }
            if (files > 0) PrismSettings.addDownloadedDatasetRepoId(dataset.repoId)

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                subtitle.text = "${dataset.source} · ${DatasetDiscoveryService.formatSize(dataset.totalSizeBytes)} · " +
                    "${dataset.files.size} file(s) · Downloaded ($files file(s), " +
                    "${DatasetDiscoveryService.formatSize(bytes)})"
            }
        }
    }

    // ── Search bar ──────────────────────────────────────────────────────────

    private fun searchRow(): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 4f))
        }
        searchInput = EditText(ctx).apply {
            hint = "Search datasets, or type -random"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            background = IosUi.fieldBackground(ctx)
            setTextColor(IosUi.label(ctx))
            setHintTextColor(IosUi.tertiaryLabel(ctx))
            val p = IosUi.dp(ctx, 10f)
            setPadding(p, IosUi.dp(ctx, 8f), p, IosUi.dp(ctx, 8f))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                val submitted = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (submitted) {
                    refreshDatasetList(text.toString())
                    true
                } else false
            }
        }
        row.addView(searchInput)

        row.addView(IosUi.tintedButton(ctx, "Search").apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = IosUi.dp(ctx, 8f) }
            setOnClickListener { refreshDatasetList(searchInput.text.toString()) }
        })
        return row
    }

    // ── Shared row/field building blocks (mirrors PrismSwapSettingsActivity/AetherTrainingActivity) ──

    private fun sizeRangeRow(): View {
        val ctx = this
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val stepMb = 10f
        val fromMb = MIN_SIZE_BYTES / (1024f * 1024f)
        val toMb = MAX_SIZE_BYTES / (1024f * 1024f)
        // RangeSlider requires every value to land exactly on valueFrom + a whole multiple of
        // stepSize, or it throws -- a value read back from a stored byte count (or a float
        // round-trip through MB) is not guaranteed to land there exactly, so it's snapped here
        // rather than handed to the slider as-is.
        fun snapToStep(mb: Float): Float {
            val steps = Math.round((mb - fromMb) / stepMb)
            return (fromMb + steps * stepMb).coerceIn(fromMb, toMb)
        }

        val minMb = snapToStep(PrismSettings.getDatasetMinSizeBytes().coerceIn(MIN_SIZE_BYTES, MAX_SIZE_BYTES) / (1024f * 1024f))
        val maxMb = snapToStep(PrismSettings.getDatasetMaxSizeBytes().coerceIn(MIN_SIZE_BYTES, MAX_SIZE_BYTES) / (1024f * 1024f))

        sizeRangeLabel = TextView(ctx).apply {
            textSize = 15f
            setTextColor(IosUi.label(ctx))
            text = "${DatasetDiscoveryService.formatSize(PrismSettings.getDatasetMinSizeBytes())} - " +
                DatasetDiscoveryService.formatSize(PrismSettings.getDatasetMaxSizeBytes())
        }
        column.addView(sizeRangeLabel)

        val slider = RangeSlider(ctx).apply {
            valueFrom = fromMb
            valueTo = toMb
            stepSize = stepMb
            values = listOf(minMb.coerceAtMost(maxMb), maxMb.coerceAtLeast(minMb))
            trackActiveTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            thumbTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(ctx))
            setLabelFormatter { value -> DatasetDiscoveryService.formatSize((value * 1024 * 1024).toLong()) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(ctx, 6f) }

            addOnChangeListener { rangeSlider, _, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val lo = rangeSlider.values.min()
                val hi = rangeSlider.values.max()
                sizeRangeLabel.text = "${DatasetDiscoveryService.formatSize((lo * 1024 * 1024).toLong())} - " +
                    DatasetDiscoveryService.formatSize((hi * 1024 * 1024).toLong())
            }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {}
                override fun onStopTrackingTouch(slider: RangeSlider) {
                    val lo = slider.values.min()
                    val hi = slider.values.max()
                    PrismSettings.setDatasetMinSizeBytes((lo * 1024 * 1024).toLong())
                    PrismSettings.setDatasetMaxSizeBytes((hi * 1024 * 1024).toLong())
                    refreshDatasetList()
                }
            })
        }
        column.addView(slider)
        return column
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(this@DatasetDownloadSettingsActivity, 24f))
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

    private fun fieldRow(ctx: android.content.Context, title: String, field: EditText, fixedWidth: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 12f))
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(IosUi.label(ctx))
        })
        field.layoutParams = LinearLayout.LayoutParams(fixedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, 0f)
            .apply { marginStart = IosUi.dp(ctx, 12f) }
        row.addView(field)
        return row
    }

    private fun fieldFor(ctx: android.content.Context, initial: String): EditText =
        EditText(ctx).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            gravity = Gravity.END
            background = IosUi.fieldBackground(ctx)
            setTextColor(IosUi.label(ctx))
            setHintTextColor(IosUi.tertiaryLabel(ctx))
            val p = IosUi.dp(ctx, 10f)
            setPadding(p, IosUi.dp(ctx, 8f), p, IosUi.dp(ctx, 8f))
            maxLines = 1
        }
}
