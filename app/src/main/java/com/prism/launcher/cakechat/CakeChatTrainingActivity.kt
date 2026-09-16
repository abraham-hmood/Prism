package com.prism.launcher.cakechat

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supplying a corpus and watching CakeChat train on it.
 *
 * ## The activity does not own the run
 *
 * Training lives in [CakeChatTrainingService] and this screen renders its state. That is what makes
 * it survivable: the notification can be tapped hours later and land back here with the run still
 * going, and rotating the phone or backing out does not cost the training. Everything shown comes
 * from `CakeChatTrainingService.state`, so there is exactly one source of truth and no chance of the
 * screen and the notification disagreeing.
 *
 * ## Why the dataset step is explicit
 *
 * CakeChat cannot be trained on raw dialogs. `tools/train.py` requires a processed corpus plus
 * index files, and its own docs describe the input as one JSON list of utterances per line. The
 * corpus is validated here, before a run starts, because the alternative is discovering a malformed
 * line an hour in through a traceback that names none of it.
 */
class CakeChatTrainingActivity : PrismBaseActivity() {

    private lateinit var datasetStatus: TextView
    private lateinit var importStatus: TextView
    private lateinit var exportButton: TextView
    private lateinit var useBundled: android.widget.CheckBox
    private lateinit var chooseButton: TextView
    private lateinit var epochsInput: android.widget.EditText
    private lateinit var batchInput: android.widget.EditText
    private lateinit var subsetInput: android.widget.EditText
    private lateinit var hiddenInput: android.widget.EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var progressCaption: TextView
    private lateinit var logView: TextView
    private lateinit var trainButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var convertButton: TextView
    private lateinit var convertStatus: TextView

    /**
     * A TREE picker, not a file picker. A trained model is three files -- weights plus both index
     * files -- and importing them one at a time makes it easy to bring the weights without their
     * vocabulary, which loads into the wrong shape and answers nonsense rather than failing.
     */
    private val pickModelDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                importStatus.text = "Importing…"
                val message = withContext(Dispatchers.IO) {
                    CakeChatInstall.importTrainedModel(applicationContext, uri)
                }
                importStatus.text = message
                refreshButtons()
            }
        }

    /** A bundle written by the Export button, on this device or another one. */
    private val pickModelZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                importStatus.text = "Importing…"
                val message = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            CakeChatInstall.importTrainedModelZip(applicationContext, stream)
                        } ?: "Could not open that file."
                    }.getOrElse { "Could not read that file: ${it.message}" }
                }
                importStatus.text = message
                refreshButtons()
            }
        }

    /**
     * Writes the bundle.
     *
     * CreateDocument rather than a fixed path: the point of exporting is getting the model OFF this
     * device, so the user picks Drive, a USB stick, wherever -- somewhere Prism cannot reach on its
     * own.
     */
    private val exportModel =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                importStatus.text = "Exporting…"
                val message = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            CakeChatInstall.exportTrainedModel(applicationContext, stream)
                        } ?: "Could not write to that location."
                    }.getOrElse { "Export failed: ${it.message}" }
                }
                importStatus.text = message
            }
        }

    private val pickDataset =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val message = withCorpusProgress {
                    withContext(Dispatchers.IO) { importDataset(uri) }
                }
                datasetStatus.text = message
                refreshDatasetControls()
                refreshButtons()
            }
        }

    /**
     * Picks a whole folder, for datasets published as numbered shards.
     *
     * A SEPARATE CONTRACT FROM [pickDataset], because Android has no chooser that returns either a
     * file or a tree: OpenDocument yields one document, OpenDocumentTree yields a directory, and
     * the permission each grants is different. One button that sometimes returned the wrong kind
     * would be worse than two that each do one thing.
     */
    private val pickDatasetFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                datasetStatus.text = "Copying dataset files…"
                val message = withCorpusProgress {
                    withContext(Dispatchers.IO) { importDatasetFolder(uri) }
                }
                datasetStatus.text = message
                refreshDatasetControls()
                refreshButtons()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@CakeChatTrainingActivity))
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Train CakeChat"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@CakeChatTrainingActivity))
        })
        root.addView(TextView(this).apply {
            text = "CakeChat ships no pretrained weights any more — the project is unmaintained " +
                "and its download bucket is gone — so a fresh install has to be trained on your " +
                "own corpus before it can answer anything."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            setPadding(0, dp(6), 0, dp(16))
        })

        // ── Dataset ────────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "CORPUS"))
        val datasetCard = IosUi.card(this)

        // ABOVE the picker, because it decides whether the picker matters at all -- reading the
        // control that governs another one after it is the wrong order.
        useBundled = android.widget.CheckBox(this).apply {
            text = "Use the bundled roleplay dataset"
            textSize = 14f
            setTextColor(IosUi.label(this@CakeChatTrainingActivity))
            isChecked = CakeChatInstall.useBundledDataset(this@CakeChatTrainingActivity)
            setOnCheckedChangeListener { _, checked ->
                CakeChatInstall.setUseBundledDataset(this@CakeChatTrainingActivity, checked)
                datasetStatus.text = describeActiveDataset()
                refreshDatasetControls()
                refreshButtons()
            }
        }
        datasetCard.addView(useBundled)

        chooseButton = IosUi.tintedButton(this, "Choose dataset file").apply {
            setOnClickListener { pickDataset.launch(arrayOf("*/*")) }
        }
        datasetCard.addView(chooseButton)
        datasetCard.addView(
            IosUi.tintedButton(this, "Choose dataset folder (Parquet)").apply {
                setOnClickListener { pickDatasetFolder.launch(null) }
            }
        )
        datasetStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        datasetCard.addView(datasetStatus)
        root.addView(datasetCard)
        root.addView(
            IosUi.sectionFooter(
                this,
                "One JSON array per line. Each utterance is an object with text and condition, " +
                    "where condition is neutral, joy, sadness, anger or fear:\n" +
                    "[{\"text\": \"Hello\", \"condition\": \"neutral\"}, " +
                    "{\"text\": \"Oh, hi!\", \"condition\": \"joy\"}]"
            )
        )
        root.addView(spacer(16))

        // ── Import a PC-trained model ──────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "IMPORT A TRAINED MODEL"))
        val importCard = IosUi.card(this)
        importCard.addView(IosUi.tintedButton(this, "Import model bundle (.zip)").apply {
            setOnClickListener { pickModelZip.launch(arrayOf("application/zip", "*/*")) }
        })
        importCard.addView(IosUi.tintedButton(this, "Import from folder").apply {
            setOnClickListener { pickModelDir.launch(null) }
        })
        exportButton = IosUi.tintedButton(this, "Export model bundle (.zip)").apply {
            setOnClickListener {
                exportModel.launch("prism-cakechat-model.zip")
            }
        }
        importCard.addView(exportButton)
        importStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            setPadding(dp(12), 0, dp(12), dp(12))
            text = if (CakeChatInstall.hasImportedModel(this@CakeChatTrainingActivity))
                "A trained model is already imported."
            else "Nothing imported yet."
        }
        importCard.addView(importStatus)
        root.addView(importCard)
        root.addView(
            IosUi.sectionFooter(
                this,
                "A bundle is the weights plus both index files in one zip. They only mean " +
                    "anything together — weights are sized by the vocabulary that produced them, " +
                    "so importing them without it loads the wrong shape and answers nonsense."
            )
        )
        root.addView(spacer(16))

        // ── Progress ───────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "TRAINING SETTINGS"))
        val settingsCard = IosUi.card(this)
        epochsInput = numberField(CakeChatInstall.epochs(this)) { value ->
            CakeChatInstall.setEpochs(this, value)
        }
        settingsCard.addView(settingRow("Epochs", epochsInput))
        settingsCard.addView(IosUi.hairline(this))
        batchInput = numberField(CakeChatInstall.batchSize(this)) { value ->
            CakeChatInstall.setBatchSize(this, value)
        }
        settingsCard.addView(settingRow("Batch size", batchInput))
        settingsCard.addView(IosUi.hairline(this))
        subsetInput = numberField(CakeChatInstall.subsetSize(this)) { value ->
            CakeChatInstall.setSubsetSize(this, value)
        }
        // 0 reads as "no limit", so it is shown as an empty box rather than as a zero.
        if (CakeChatInstall.subsetSize(this) == 0) {
            subsetInput.setText("")
            subsetInput.hint = "all"
        }
        settingsCard.addView(settingRow("Limit to N dialogs", subsetInput))
        settingsCard.addView(IosUi.hairline(this))
        hiddenInput = numberField(CakeChatInstall.hiddenDim(this)) { value ->
            CakeChatInstall.setHiddenDim(this, value)
        }
        // 0 means "CakeChat's own 768", which reads better as an empty box hinting the default.
        if (CakeChatInstall.hiddenDim(this) == 0) {
            hiddenInput.setText("")
            hiddenInput.hint = "768"
        }
        settingsCard.addView(settingRow("Layer width", hiddenInput))
        root.addView(settingsCard)
        root.addView(
            IosUi.sectionFooter(
                this,
                "One epoch is one pass over the corpus. Batch size is how many dialogs are used " +
                    "per gradient step — larger is faster but needs more memory, and the trainer " +
                    "halves it by itself if a run runs out. The dialog limit is what decides how " +
                    "long training takes: leave it empty to use the whole corpus, which on a " +
                    "phone can mean hours. Layer width decides whether the result will RUN on " +
                    "this phone at all — a recurrent layer's parameters grow with the square of " +
                    "it, and the default 768 needs around 850 MB to load, which is more than " +
                    "Android gives one process. Try 256. A model can only be loaded at the width " +
                    "it was trained at."
            )
        )
        root.addView(spacer(16))

        root.addView(IosUi.sectionHeader(this, "PROGRESS"))
        val progressCard = IosUi.card(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = IosUi.progressDrawable(this@CakeChatTrainingActivity)
        }
        progressCard.addView(progressBar)
        progressCaption = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            text = "Not training."
        }
        progressCard.addView(progressCaption)
        root.addView(progressCard)
        root.addView(spacer(12))

        trainButton = IosUi.filledButton(this, "Train").apply {
            setOnClickListener {
                // Read from the fields rather than from prefs, so a value typed but not yet
                // committed by a focus change still applies to the run being started.
                CakeChatTrainingService.start(
                    this@CakeChatTrainingActivity,
                    readField(epochsInput, 1, 100),
                    readField(batchInput, 1, 512),
                    // 0 rather than 1 when empty: this box means "no limit", not "one dialog".
                    subsetInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    hiddenInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            }
        }
        stopButton = IosUi.tintedButton(this, "Stop", IosUi.destructive(this)).apply {
            setOnClickListener { CakeChatTrainingService.stop(this@CakeChatTrainingActivity) }
        }
        root.addView(trainButton)
        root.addView(spacer(8))
        root.addView(stopButton)
        root.addView(spacer(8))

        // Beside Train rather than inside it: training converts as it finishes, so this is only for
        // a model trained before that was the case -- or one imported from a machine that did not.
        convertButton = IosUi.tintedButton(this, "Convert for mobile (TFLite)").apply {
            setOnClickListener {
                CakeChatTrainingService.convert(this@CakeChatTrainingActivity)
            }
        }
        root.addView(convertButton)
        convertStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            val pad = IosUi.dp(context, 12f)
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(convertStatus)
        root.addView(
            IosUi.sectionFooter(
                this,
                "Converting lets CakeChat answer without TensorFlow — tens of megabytes instead of " +
                    "hundreds, which is the difference between replying and being killed for memory " +
                    "on most phones. It reads the trained weights and takes minutes, not another " +
                    "training run."
            )
        )
        root.addView(spacer(16))

        // ── Logs ───────────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "LOG"))
        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(IosUi.card(this).apply { addView(logView) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@CakeChatTrainingActivity))
            addView(root)
        })

        datasetStatus.text = describeActiveDataset()
        refreshDatasetControls()
        refreshButtons()

        // The CLIENT's flow, not the service's. Training runs in ":cakechat" now, so the service's
        // own StateFlow lives in a different process and this one would never update.
        lifecycleScope.launch {
            CakeChatTrainingClient.state.collect { render(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        CakeChatTrainingClient.bind(this)
    }

    override fun onStop() {
        super.onStop()
        // Training is a foreground service and keeps going; only the relay stops.
        CakeChatTrainingClient.unbind(this)
    }

    private fun render(progress: CakeChatTrainingService.Progress) {
        progressBar.isIndeterminate = progress.running && progress.total <= 0
        if (progress.total > 0) progressBar.progress = progress.percent

        progressCaption.text = when {
            progress.error != null -> "Failed: ${progress.error.lineSequence().firstOrNull().orEmpty()}"
            progress.finished -> "Training finished. Weights are in the model directory."
            progress.phase == "preprocessing" -> "Preparing corpus and index files…"
            progress.phase == "converting" -> "Converting the model for mobile (TFLite)…"
            progress.running && progress.total > 0 -> buildString {
                append("Step ").append(progress.step).append(" of ").append(progress.total)
                append("  ·  ").append(progress.stepsLeft).append(" steps left\n")
                append("Epoch ").append(progress.epoch).append('/').append(progress.epochs)
                if (progress.loss > 0) append("  ·  loss %.4f".format(progress.loss))
                append('\n')
                append(clock(progress.elapsedSeconds)).append(" elapsed")
                if (progress.etaSeconds > 0) append("  ·  ETA ").append(clock(progress.etaSeconds))
            }
            progress.running -> "Starting…"
            else -> "Not training."
        }

        logView.text = progress.logs.joinToString("\n").ifBlank { "No output yet." }
        refreshButtons(progress)
    }

    private fun refreshButtons(
        progress: CakeChatTrainingService.Progress = CakeChatTrainingClient.state.value,
    ) {
        // Either source counts. Gating on the user file alone would leave Train dead while the
        // bundled corpus was selected, which is the one case that needs no file at all.
        if (::exportButton.isInitialized) {
            val trained = CakeChatInstall.state(this) == CakeChatInstall.State.TRAINED
            exportButton.isEnabled = trained
            exportButton.isClickable = trained
            exportButton.alpha = if (trained) 1f else 0.5f
        }

        val hasDataset = CakeChatInstall.activeDatasetFile(this) != null
        trainButton.isEnabled = hasDataset && !progress.running
        trainButton.alpha = if (trainButton.isEnabled) 1f else 0.5f
        stopButton.isEnabled = progress.running
        stopButton.alpha = if (progress.running) 1f else 0.5f

        val trainedNow = CakeChatInstall.state(this) == CakeChatInstall.State.TRAINED
        convertButton.isEnabled = trainedNow && !progress.running
        convertButton.alpha = if (convertButton.isEnabled) 1f else 0.5f

        // VISIBLE AT A GLANCE, because the difference decides whether answering works at all on
        // this device, and it is otherwise only discoverable by sending a message and being told
        // the process died. A model can be present and trained and still have no converted form --
        // trained before conversion existed, or exported from a machine that had not converted it.
        val absent = CakeChatLite.missing(this)
        convertStatus.text = when {
            !trainedNow -> "No model on this device yet."
            absent.isEmpty() ->
                "Converted — CakeChat answers without TensorFlow, in tens of megabytes."
            absent.size == 3 ->
                "Not converted. Answering will load TensorFlow, which most phones cannot spare " +
                    "the memory for. Convert it here, or on the machine that trained it."
            else -> "Partly converted — missing ${absent.joinToString()}. Convert again."
        }
    }

    /**
     * Copies the picked file in and validates it before anything long starts.
     *
     * Validation runs in Python rather than being re-implemented here, so the rules the trainer
     * enforces and the rules the picker enforces cannot drift apart.
     */
    /**
     * Copies every Parquet shard out of a picked folder, then converts them as one corpus.
     *
     * COPIED RATHER THAN READ IN PLACE, and that is not avoidable. A folder chosen through the
     * Storage Access Framework is a tree of content:// URIs, not a filesystem path -- Python's
     * `open()` cannot touch it. The converter needs real files it can seek within, because Parquet
     * is read from its footer backwards.
     *
     * Only .parquet files are taken. A dataset folder also holds READMEs, JSON metadata and
     * sometimes a second copy of the data in another format, and sweeping those in would either
     * waste a copy of several gigabytes or confuse the format sniffer.
     */
    /**
     * Runs [work] while showing the converter's progress in the corpus status line.
     *
     * POLLED, BECAUSE THE CALL IS BLOCKING. Chaquopy invokes Python synchronously, so a multi-file
     * conversion returns nothing until every shard is read -- minutes, during which the only
     * honest thing the UI can do is ask the converter what it is on. `current_status` is a single
     * line by design; the whole log goes to diagnostics.
     */
    private suspend fun <T> withCorpusProgress(work: suspend () -> T): T {
        val poller = lifecycleScope.launch {
            var last = ""
            while (true) {
                val line = withContext(Dispatchers.IO) {
                    runCatching {
                        CakeChatBridge.corpusModule(applicationContext)
                            .callAttr("current_status")?.toString().orEmpty()
                    }.getOrDefault("")
                }
                if (line.isNotBlank() && line != last) {
                    last = line
                    datasetStatus.text = line
                }
                kotlinx.coroutines.delay(300)
            }
        }
        try {
            return work()
        } finally {
            poller.cancel()
            // The converter's own log is worth keeping: which shard produced how many dialogs is
            // the difference between "it worked" and "one file of six was readable".
            runCatching {
                val lines = CakeChatBridge.corpusModule(applicationContext)
                    .callAttr("recent_logs", 200).asList().map { it.toString() }
                for (line in lines) com.prism.launcher.PrismLogger.logInfo("CakeChat", line)
            }
        }
    }

    private fun importDatasetFolder(treeUri: android.net.Uri): String {
        val staging = java.io.File(CakeChatInstall.weightsDir(this), "imported_dataset")
        staging.deleteRecursively()
        staging.mkdirs()

        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        )

        var copied = 0
        var bytes = 0L
        val failure = runCatching {
            contentResolver.query(
                children,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    if (!name.endsWith(".parquet", ignoreCase = true)) continue

                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, documentId
                    )
                    val target = java.io.File(staging, name)
                    contentResolver.openInputStream(fileUri)?.use { input ->
                        target.outputStream().use { output -> bytes += input.copyTo(output, 1 shl 16) }
                    } ?: continue
                    copied++
                }
            }
            null
        }.exceptionOrNull()

        if (failure != null) {
            com.prism.launcher.PrismLogger.logError("CakeChat", "Could not read that folder", failure)
            return "Could not read that folder: ${failure.message}"
        }
        if (copied == 0) {
            staging.deleteRecursively()
            return "No .parquet files in that folder. Pick the folder that holds the dataset's " +
                "shards, not the one above it."
        }

        com.prism.launcher.PrismLogger.logInfo(
            "CakeChat", "Copied $copied parquet file(s), ${bytes / (1024 * 1024)} MB"
        )

        return runCatching {
            val converted = CakeChatBridge.corpusModule(applicationContext)
                .callAttr(
                    "convert",
                    staging.absolutePath,
                    CakeChatInstall.datasetFile(this).absolutePath,
                ).asList()
            val ok = converted[0].toBoolean()
            val message = converted[1].toString()
            // The copies are the size of the dataset -- gigabytes for anything worth training on --
            // and the converted corpus is what training reads, so they are not kept afterwards.
            staging.deleteRecursively()
            if (ok) {
                CakeChatInstall.setUseBundledDataset(this, false)
                message
            } else {
                message
            }
        }.getOrElse {
            staging.deleteRecursively()
            com.prism.launcher.PrismLogger.logError("CakeChat", "Parquet conversion failed", it)
            "Could not convert that dataset: ${it.message}"
        }
    }

    private fun importDataset(uri: android.net.Uri): String {
        // Staged under its original name first. The converter sniffs the format from the CONTENT,
        // but a DailyDialog download is a zip and zipfile.is_zipfile needs a real seekable file --
        // so the raw bytes land on disk before anything looks at them.
        val staged = java.io.File(CakeChatInstall.weightsDir(this), "imported_source")
        val target = CakeChatInstall.datasetFile(this)

        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return "Could not open that file."
            true
        }.getOrDefault(false)
        if (!copied) return "Could not copy that file."

        return runCatching {
            // CONVERT, THEN VALIDATE. CakeChat reads exactly one shape -- a JSON array of
            // {text, condition} objects per line -- and nothing a user downloads is in it.
            // DailyDialog is __eou__-separated text with a parallel numeric emotion file; other
            // corpora are JSON lines of bare strings. Copying the file in unchanged is what made
            // the import fail, so conversion happens here rather than being the user's problem.
            val converted = CakeChatBridge.corpusModule(applicationContext)
                .callAttr("convert", staged.absolutePath, target.absolutePath).asList()
            val ok = converted[0].toBoolean()
            val message = converted[1].toString()

            staged.delete()
            if (!ok) {
                target.delete()
                return@runCatching message
            }

            // Validated afterwards anyway: the converter can write a file it believes in that the
            // trainer's own reader still rejects, and finding that out now costs a second.
            val checked = CakeChatBridge.module(applicationContext)
                .callAttr("validate_dataset", target.absolutePath).asList()
            if (!checked[0].toBoolean()) {
                target.delete()
                "Converted, but the result was rejected: ${checked[1]}"
            } else {
                message
            }
        }.getOrElse {
            staged.delete()
            "Imported, but it could not be converted: ${it.message}"
        }
    }

    /**
     * Greys the picker out while the bundled corpus is selected, without hiding it.
     *
     * Visible-but-disabled rather than gone: a control that disappears when its neighbour is ticked
     * reads as a bug, and it hides the fact that choosing a file is still available at all. The
     * status line underneath says which corpus is actually in use.
     */
    private fun refreshDatasetControls() {
        val bundled = useBundled.isChecked
        chooseButton.isEnabled = !bundled
        chooseButton.isClickable = !bundled
        chooseButton.alpha = if (bundled) 0.5f else 1f
    }

    private fun describeActiveDataset(): String {
        if (CakeChatInstall.useBundledDataset(this)) {
            val bundled = CakeChatInstall.bundledDatasetFile(this)
            return if (bundled != null)
                "Using the bundled roleplay corpus — 2000 dialogs, five emotion conditions " +
                    "(${bundled.length() / 1024} KB). Enough to prove training runs; too small " +
                    "to produce coherent replies."
            else "The bundled corpus could not be extracted — see diagnostics."
        }
        val file = CakeChatInstall.datasetFile(this)
        return if (file.isFile) "Using ${file.name} (${file.length() / 1024} KB)."
        else "No corpus chosen yet."
    }

    private fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun spacer(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(height)
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * A right-aligned numeric field, in the shape of an iOS settings row's value.
     *
     * Committed on focus loss rather than on every keystroke: saving as the user types means an
     * intermediate "1" is written while they are on their way to typing "16", and a field cleared
     * before retyping would persist as its own minimum.
     */
    private fun numberField(initial: Int, onCommit: (Int) -> Unit): android.widget.EditText =
        android.widget.EditText(this).apply {
            setText(initial.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            gravity = android.view.Gravity.END
            setTextColor(IosUi.secondaryLabel(this@CakeChatTrainingActivity))
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(IosUi.dp(context, 72f), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    // An empty box is left empty and committed as 0. Clamping it to the minimum
                    // instead would silently turn "no limit" into a one-dialog run.
                    val typed = text.toString().trim()
                    if (typed.isEmpty()) {
                        onCommit(0)
                    } else {
                        val value = readField(this, 1, 512)
                        setText(value.toString())
                        onCommit(value)
                    }
                }
            }
        }

    /** A label on the left, its field on the right. */
    private fun settingRow(label: String, field: android.widget.EditText): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = IosUi.dp(context, 12f)
            setPadding(pad, pad, pad, pad)
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 16f
                    setTextColor(IosUi.label(this@CakeChatTrainingActivity))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(field)
        }

    /** Whatever is in the box, clamped to something trainable. Blank or nonsense falls back to [min]. */
    private fun readField(field: android.widget.EditText, min: Int, max: Int): Int =
        field.text.toString().trim().toIntOrNull()?.coerceIn(min, max) ?: min

}
