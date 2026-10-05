package com.prism.launcher.science

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.protein.FoldingModel
import com.prism.launcher.protein.FoldingConfig
import com.prism.launcher.protein.FoldingPrediction
import com.prism.launcher.protein.FoldingTrainer
import com.prism.launcher.protein.ProteinChain
import com.prism.launcher.protein.ProteinChemistry
import com.prism.launcher.protein.ProteinLibrary
import com.prism.launcher.protein.TrainingPlan
import kotlin.math.min
import kotlin.random.Random

/**
 * Proteins: training a folding model, and folding a sequence with it.
 *
 * ## What the model is, honestly
 *
 * An ESMFold-shaped model, at a size that fits on a phone. That means: a residue-level transformer
 * trunk trained with masked language modelling, a pair representation built from it and refined by
 * triangle multiplicative updates, and a structure module that places rigid backbone frames by
 * invariant point attention — the AlphaFold-2 architecture with the MSA replaced by a sequence model.
 *
 * The MSA is what is missing and it is the biggest single difference from AlphaFold. AlphaFold's input
 * is a multiple sequence alignment: for each query it searches databases of hundreds of millions of
 * sequences to find its evolutionary relatives, and the *co-variation* across that alignment is most of
 * where the structural signal comes from. That search is a terabyte-scale job per query and is not
 * something a phone does. ESMFold's finding, which this follows, is that a language model trained on
 * the same databases has already internalised much of that co-variation, so the alignment can be
 * replaced by the model's own representation of the single sequence. It works, and it is meaningfully
 * worse than AlphaFold on sequences with few relatives.
 *
 * The second difference is size. AlphaFold-2 is about 93 million parameters and was trained for weeks
 * on TPUs; ESM-2's folding model is 3 billion. The presets here run from about a hundred thousand
 * parameters to a few million, because that is what trains on a phone in a time anybody will wait.
 * A model this size, trained on the data one person can gather, will not produce accurate structures
 * for novel folds. What it does produce is a real implementation of the method, with real gradients,
 * whose own confidence estimate is honest about how little it knows — which is why pLDDT is drawn on
 * everything here rather than tucked away.
 *
 * ## The two tabs
 *
 * Training and folding, kept apart because they are different activities on different timescales:
 * training is a long run you start and leave, folding is a thing you do to one sequence and look at.
 * Sharing one scroll view between them would mean the training log moving under the structure.
 */
class ProteinPanel(context: Context) : LinearLayout(context) {

    private val tabs = LinearLayout(context)
    private val stage = android.widget.FrameLayout(context)

    private val trainTab: View by lazy { buildTrainTab() }
    private val foldTab: View by lazy { buildFoldTab() }

    private var selectedTab = 0

    // Tab 1 state
    private lateinit var modelList: LinearLayout
    private lateinit var datasetList: LinearLayout
    private lateinit var trainingStatus: TextView
    private lateinit var trainingLog: TextView
    private lateinit var meshNote: TextView
    private lateinit var trainButton: View

    // Tab 2 state
    private lateinit var sequenceField: EditText
    private lateinit var structureView: StructureView
    private lateinit var foldSummary: TextView
    private lateinit var confidenceStrip: ConfidenceStrip
    private lateinit var foldDetail: TextView

    /** The model in use, loaded lazily and kept: loading is megabytes off disk. */
    private var activeModel: FoldingModel? = null
    private var activeEntry: ProteinLibrary.ModelEntry? = null

    @Volatile private var training = false
    @Volatile private var cancelRequested = false
    @Volatile private var folding = false

    private var lastPrediction: FoldingPrediction? = null

    init {
        orientation = VERTICAL
        buildTabs()
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        select(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (selectedTab == 0) refreshTrainTab()
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    private fun buildTabs() {
        tabs.orientation = HORIZONTAL
        tabs.setBackgroundColor(IosUi.cardBackground(context))
        val labels = listOf("Models & training", "Fold a sequence")
        labels.forEachIndexed { index, label ->
            tabs.addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, IosUi.dp(context, 13f), 0, IosUi.dp(context, 13f))
                setOnClickListener { select(index) }
                tag = "tab$index"
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(IosUi.hairline(context))
    }

    private fun select(index: Int) {
        selectedTab = index
        stage.removeAllViews()
        val view = if (index == 0) trainTab else foldTab
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        stage.addView(view, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as? TextView)?.apply {
                setTextColor(if (i == index) IosUi.accent(context) else IosUi.secondaryLabel(context))
            }
        }
        if (index == 0) refreshTrainTab() else refreshFoldTab()
    }

    // ── Tab 1: models, datasets, training ──────────────────────────────────

    private fun buildTrainTab(): View {
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val pad = IosUi.dp(context, 16f)
        column.setPadding(pad, pad, pad, pad)

        column.addView(IosUi.sectionHeader(context, "MODELS"))
        modelList = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(modelList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column.addView(IosUi.tintedButton(context, "New model…").apply {
            setOnClickListener { promptNewModel() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        column.addView(IosUi.sectionFooter(
            context,
            "A folding model here is an ESMFold-shaped network: a residue transformer, a pair " +
                "representation refined by triangle updates, and a structure module that places " +
                "backbone frames by invariant point attention. No multiple sequence alignment — " +
                "searching one would mean a terabyte database per query, so the sequence model stands " +
                "in for it, which is ESMFold's whole idea and also its main weakness."
        ))

        column.addView(IosUi.sectionHeader(context, "DATASETS"))
        datasetList = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(datasetList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val importRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        importRow.addView(IosUi.tintedButton(context, "Import structures").apply {
            setOnClickListener { ProteinImportActivity.launch(context, ProteinLibrary.Kind.STRUCTURES) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        importRow.addView(View(context), LayoutParams(IosUi.dp(context, 8f), 1))
        importRow.addView(IosUi.tintedButton(context, "Import sequences").apply {
            setOnClickListener { ProteinImportActivity.launch(context, ProteinLibrary.Kind.SEQUENCES) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(importRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        column.addView(IosUi.sectionFooter(
            context,
            "Structures — PDB or mmCIF — train the distogram, the frame-aligned point error, and the " +
                "confidence head. Sequence databases train masked-residue prediction, which is where " +
                "contact information comes from when there is no structure: positions that mutate " +
                "together are usually positions that touch."
        ))

        column.addView(IosUi.sectionHeader(context, "TRAINING"))

        trainingStatus = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
        }
        column.addView(trainingStatus)

        meshNote = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 6f), 0, 0)
        }
        column.addView(meshNote)

        trainButton = IosUi.filledButton(context, "Train").apply {
            setOnClickListener { if (training) cancelRequested = true else promptTrain() }
        }
        column.addView(trainButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        trainingLog = TextView(context).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        }
        column.addView(trainingLog)

        return column
    }

    private fun refreshTrainTab() {
        val models = ProteinLibrary.models(ProteinImportActivity.modelRoot(context))
        modelList.removeAllViews()
        if (models.isEmpty()) {
            modelList.addView(hint("No model yet. Create one to train or fold with."))
        } else {
            models.forEach { entry -> modelList.addView(modelCard(entry)) }
        }

        val datasets = ProteinLibrary.datasets(ProteinImportActivity.datasetRoot(context))
        datasetList.removeAllViews()
        if (datasets.isEmpty()) {
            datasetList.addView(hint("No dataset yet. Import structures or a sequence database."))
        } else {
            datasets.forEach { dataset -> datasetList.addView(datasetCard(dataset)) }
        }

        trainingStatus.text = when {
            training -> "Training…"
            activeEntry == null -> "Pick a model above, then a dataset, then train."
            else -> "Ready: ${activeEntry?.name} — ${activeEntry?.describe()}"
        }
        (trainButton as? TextView)?.text = if (training) "Stop" else "Train"

        // Off the main thread: describing where the work will run asks the pay-later policy, which
        // derives the wallet seed, reads the debt ledger off disk and looks up a chain balance. None of
        // that belongs in a refresh that happens every time this tab is shown.
        Thread({
            val line = MeshFolding.describe(training = true)
            post { meshNote.text = line }
        }, "protein-mesh-note").start()
    }

    private fun modelCard(entry: ProteinLibrary.ModelEntry): View {
        val card = IosUi.card(context)
        val chosen = entry.file == activeEntry?.file

        card.addView(TextView(context).apply {
            text = (if (chosen) "✓  " else "") + entry.name
            textSize = 15f
            setTextColor(if (chosen) IosUi.accent(context) else IosUi.label(context))
        })
        card.addView(TextView(context).apply {
            text = entry.describe()
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 4f), 0, 0)
        })
        if (entry.trainedOn.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = "Trained on ${entry.trainedOn.joinToString(", ")}"
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, IosUi.dp(context, 3f), 0, 0)
            })
        }

        card.isClickable = true
        card.setOnClickListener { useModel(entry) }
        card.setOnLongClickListener {
            PrismDialogFactory.show(
                context,
                "Delete ${entry.name}?",
                "The weights go with it. Anything trained into this model is lost.",
                positiveText = "Delete",
                onPositive = {
                    if (entry.file == activeEntry?.file) {
                        activeEntry = null
                        activeModel = null
                    }
                    ProteinLibrary.deleteModel(entry)
                    refreshTrainTab()
                },
            )
            true
        }
        return card
    }

    private fun datasetCard(dataset: ProteinLibrary.Dataset): View {
        val card = IosUi.card(context)
        card.addView(TextView(context).apply {
            text = dataset.name
            textSize = 15f
            setTextColor(IosUi.label(context))
        })
        card.addView(TextView(context).apply {
            text = "${dataset.kind.label()} · ${dataset.describe()}"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 4f), 0, 0)
        })
        card.isClickable = true
        card.setOnLongClickListener {
            PrismDialogFactory.show(
                context,
                "Delete ${dataset.name}?",
                "Removes the imported copy and its index. The original folder is untouched.",
                positiveText = "Delete",
                onPositive = {
                    ProteinLibrary.delete(dataset)
                    refreshTrainTab()
                },
            )
            true
        }
        return card
    }

    private fun useModel(entry: ProteinLibrary.ModelEntry) {
        activeEntry = entry
        activeModel = null
        refreshTrainTab()
        toast("${entry.name} selected")
    }

    /** Loads the selected model's weights, off the caller's assumption that it is cheap. */
    private fun ensureModel(): FoldingModel? {
        activeModel?.let { return it }
        val entry = activeEntry ?: return null
        val loaded = ProteinLibrary.loadModel(entry) ?: return null
        activeModel = loaded.model
        return loaded.model
    }

    private fun promptNewModel() {
        val presets = listOf(
            "Small" to FoldingConfig.small(),
            "Standard" to FoldingConfig.standard(),
            "Large" to FoldingConfig.large(),
        )

        val field = EditText(context).apply {
            hint = "Model name"
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
        }

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = IosUi.dp(context, 4f)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(field)

        var chosen = 0
        val buttons = presets.mapIndexed { index, (label, config) ->
            TextView(context).apply {
                text = "$label — ${ProteinLibrary.formatCount(config.parameterCount())} params, " +
                    "${config.dModel} wide, crop ${config.maxLength}, " +
                    "≈${ProteinLibrary.formatBytes(com.prism.launcher.protein.FoldingCheckpoint.sizeOf(config))}"
                textSize = 13f
                setPadding(0, IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
                setTextColor(if (index == 0) IosUi.accent(context) else IosUi.secondaryLabel(context))
            }
        }
        buttons.forEachIndexed { index, view ->
            view.setOnClickListener {
                chosen = index
                buttons.forEachIndexed { i, v ->
                    v.setTextColor(if (i == index) IosUi.accent(context) else IosUi.secondaryLabel(context))
                }
            }
            column.addView(view)
        }

        column.addView(TextView(context).apply {
            text = "Bigger models fold better and train slower. The crop length is the longest " +
                "stretch trained on at once — memory for the pair representation grows with its " +
                "square, so a long crop on a large model is what runs a phone out of memory."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 8f), 0, 0)
        })

        PrismDialogFactory.show(
            context,
            "New folding model",
            message = "",
            customView = column,
            positiveText = "Create",
            onPositive = {
                val name = field.text.toString().trim()
                if (name.isBlank()) {
                    toast("Give it a name.")
                    return@show
                }
                val created = ProteinLibrary.createModel(
                    ProteinImportActivity.modelRoot(context),
                    name,
                    presets[chosen].second,
                )
                if (created == null) {
                    toast("A model called \"$name\" already exists.")
                } else {
                    useModel(created)
                }
            },
        )
    }

    // ── Training ───────────────────────────────────────────────────────────

    private fun promptTrain() {
        val entry = activeEntry
        if (entry == null) {
            toast("Pick a model first.")
            return
        }
        val datasets = ProteinLibrary.datasets(ProteinImportActivity.datasetRoot(context))
            .filter { it.indexed }
        if (datasets.isEmpty()) {
            toast("Import a dataset first.")
            return
        }

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = IosUi.dp(context, 4f)
            setPadding(pad, pad, pad, pad)
        }

        var chosen = 0
        val rows = datasets.mapIndexed { index, dataset ->
            TextView(context).apply {
                text = "${dataset.name} — ${dataset.kind.label()}, ${dataset.describe()}"
                textSize = 13f
                setPadding(0, IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
                setTextColor(if (index == 0) IosUi.accent(context) else IosUi.secondaryLabel(context))
            }
        }
        rows.forEachIndexed { index, view ->
            view.setOnClickListener {
                chosen = index
                rows.forEachIndexed { i, v ->
                    v.setTextColor(if (i == index) IosUi.accent(context) else IosUi.secondaryLabel(context))
                }
            }
            column.addView(view)
        }

        val stepsField = EditText(context).apply {
            hint = "Steps (e.g. 200)"
            setText("200")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
        }
        column.addView(stepsField)

        val distributed = MeshFolding.allowed(training = true)
        column.addView(TextView(context).apply {
            text = if (distributed) {
                "Distributed training is on and ${MeshFolding.peers().size} peer(s) are selling " +
                    "compute, so this run will be spread across the meshnet: each peer trains a " +
                    "shard and Prism averages how far their weights moved."
            } else {
                MeshFolding.describe(training = true) + "\n\nThis run will use this device."
            }
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 10f), 0, 0)
        })

        PrismDialogFactory.show(
            context,
            "Train ${entry.name}",
            message = "",
            customView = column,
            positiveText = "Start",
            onPositive = {
                val steps = stepsField.text.toString().toIntOrNull()?.coerceIn(1, 100_000) ?: 200
                startTraining(entry, datasets[chosen], steps, distributed)
            },
        )
    }

    /**
     * Runs the training loop on a background thread.
     *
     * A plain thread rather than a coroutine scope tied to the view: a run of a few hundred steps takes
     * minutes and the user is expected to leave the page, and a run cancelled by navigating away is a
     * run whose checkpoint never got written. Cancellation is explicit, through the Stop button and the
     * [cancelRequested] flag, which is checked between steps.
     */
    private fun startTraining(
        entry: ProteinLibrary.ModelEntry,
        dataset: ProteinLibrary.Dataset,
        steps: Int,
        distributed: Boolean,
    ) {
        if (training) return
        val model = ensureModel()
        if (model == null) {
            toast("That model's weights could not be loaded.")
            return
        }

        training = true
        cancelRequested = false
        trainingLog.text = ""
        refreshTrainTab()

        Thread({
            val rng = Random(System.nanoTime())
            var lastLoss = 0f
            var completed = 0

            val sequences = if (dataset.kind == ProteinLibrary.Kind.SEQUENCES) {
                ProteinLibrary.SequenceIndex.open(dataset.directory)
            } else {
                null
            }
            val structures = if (dataset.kind == ProteinLibrary.Kind.STRUCTURES) {
                ProteinLibrary.StructureIndex.open(dataset.directory)
            } else {
                null
            }

            fun nextChain(): ProteinChain? = sequences?.sample(rng) ?: structures?.sample(rng)

            try {
                if (sequences == null && structures == null) {
                    post { toast("That dataset's index could not be opened. Re-import it.") }
                    return@Thread
                }

                if (distributed) {
                    completed = trainAcrossMesh(model, ::nextChain, steps) { line -> logLine(line) }
                } else {
                    val plan = TrainingPlan(steps = steps, checkpointEvery = 50)
                    val trainer = FoldingTrainer(model, plan, rng.nextLong())
                    for (i in 0 until steps) {
                        if (cancelRequested) break
                        val chain = nextChain()
                        if (chain == null) {
                            logLine("no usable example at step $i")
                            continue
                        }
                        val result = runCatching {
                            if (chain.hasStructure) {
                                trainer.trainOnStructure(chain)
                            } else {
                                trainer.trainOnSequence(chain)
                            }
                        }.getOrNull() ?: continue

                        lastLoss = result.loss
                        completed++
                        if (result.diverged) {
                            logLine("step $i diverged — stopping before the weights are ruined")
                            break
                        }
                        if (i % 5 == 0 || i == steps - 1) {
                            logLine(
                                "step %d  loss %.4f  |g| %.3f  lr %.2e  %d res  %dms".format(
                                    i, result.loss, result.gradientNorm, result.learningRate,
                                    result.residues, result.elapsedMillis,
                                )
                            )
                        }
                        // Checkpointed as it goes, not only at the end. A run interrupted by Android
                        // killing the process would otherwise lose every step of it.
                        if (completed % plan.checkpointEvery == 0) {
                            saveProgress(entry, model, dataset, completed, lastLoss)
                            logLine("checkpointed at $completed steps")
                        }
                    }
                }
            } catch (e: Throwable) {
                logLine("failed: ${e.message}")
            } finally {
                runCatching { sequences?.close() }
                if (completed > 0) saveProgress(entry, model, dataset, completed, lastLoss)
                training = false
                post {
                    logLine(
                        if (cancelRequested) "stopped after $completed step(s)"
                        else "finished $completed step(s)"
                    )
                    refreshTrainTab()
                }
            }
        }, "protein-train").start()
    }

    /**
     * Federated rounds across the mesh, until the step budget is spent.
     *
     * The round size is a compromise that only exists because the mesh is phones. Small rounds mean the
     * averaged model tracks a single trainer closely but pays a full weight transfer per round; large
     * rounds amortise the transfer but let each peer drift further from the others before their work is
     * reconciled, which is where local SGD loses ground against true data parallelism.
     */
    private fun trainAcrossMesh(
        model: FoldingModel,
        nextChain: () -> ProteinChain?,
        steps: Int,
        log: (String) -> Unit,
    ): Int {
        val peers = MeshFolding.peers()
        if (peers.isEmpty()) {
            log("no peers answered; nothing was run")
            return 0
        }

        val perRound = STEPS_PER_ROUND.coerceAtMost(steps)
        var done = 0
        var round = 0

        while (done < steps && !cancelRequested) {
            val budget = min(perRound, steps - done)
            // One example per step per peer, so no peer runs out of shard mid-round.
            val examples = ArrayList<ProteinChain>(budget * peers.size)
            repeat(budget * peers.size) { nextChain()?.let { examples.add(it) } }
            if (examples.isEmpty()) {
                log("no usable examples left")
                break
            }

            val outcome = MeshFolding.trainRound(
                model = model,
                examples = examples,
                stepsPerPeer = budget,
                learningRate = 3e-4f,
                seed = System.nanoTime(),
                step = done,
            )
            round++
            log("round $round — ${outcome.describe()}")
            if (!outcome.applied) {
                log("giving up on the mesh for this run")
                break
            }
            // Counted as the steps the averaged model actually advanced, which is the round size —
            // not the sum across peers. Three peers running 20 steps each and being averaged is 20
            // steps of progress on 60 steps' worth of examples, and calling it 60 would be a lie the
            // loss curve would immediately contradict.
            done += budget
        }
        return done
    }

    private fun saveProgress(
        entry: ProteinLibrary.ModelEntry,
        model: FoldingModel,
        dataset: ProteinLibrary.Dataset,
        steps: Int,
        loss: Float,
    ) {
        val trainedOn = (entry.trainedOn + dataset.name).distinct()
        val saved = ProteinLibrary.saveModel(
            ProteinImportActivity.modelRoot(context),
            entry.name,
            model,
            step = entry.step + steps,
            trainedOn = trainedOn,
            lastLoss = loss,
        )
        if (saved != null) post { activeEntry = saved }
    }

    private fun logLine(line: String) {
        post {
            val existing = trainingLog.text.toString()
            // Bounded, because a thousand-step run at one line per five steps is a TextView being
            // asked to lay out a few hundred lines every time one is added.
            val kept = existing.lines().takeLast(40).joinToString("\n")
            trainingLog.text = if (kept.isBlank()) line else "$kept\n$line"
        }
    }

    // ── Tab 2: folding ─────────────────────────────────────────────────────

    private fun buildFoldTab(): View {
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val pad = IosUi.dp(context, 16f)
        column.setPadding(pad, pad, pad, pad)

        column.addView(IosUi.sectionHeader(context, "SEQUENCE"))

        sequenceField = EditText(context).apply {
            hint = "One-letter amino acid sequence, or paste a FASTA record"
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = IosUi.fieldBackground(context)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(sequenceField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(IosUi.filledButton(context, "Fold").apply {
            setOnClickListener { fold() }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(context), LayoutParams(IosUi.dp(context, 8f), 1))
        row.addView(IosUi.tintedButton(context, "Example").apply {
            setOnClickListener {
                // Human ubiquitin, 76 residues: small, extremely well characterised, and short enough
                // to fold on a phone in seconds.
                sequenceField.setText(UBIQUITIN)
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        foldSummary = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        }
        column.addView(foldSummary)

        column.addView(IosUi.sectionHeader(context, "STRUCTURE"))
        structureView = StructureView(context)
        column.addView(structureView, LayoutParams(
            LayoutParams.MATCH_PARENT, IosUi.dp(context, 300f)
        ))

        val controls = LinearLayout(context).apply { orientation = HORIZONTAL }
        controls.addView(IosUi.tintedButton(context, "Colour").apply {
            setOnClickListener {
                structureView.colouring =
                    if (structureView.colouring == StructureView.Colouring.CONFIDENCE) {
                        StructureView.Colouring.RAINBOW
                    } else {
                        StructureView.Colouring.CONFIDENCE
                    }
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(View(context), LayoutParams(IosUi.dp(context, 8f), 1))
        controls.addView(IosUi.tintedButton(context, "Spin").apply {
            setOnClickListener { structureView.spinning = !structureView.spinning }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(View(context), LayoutParams(IosUi.dp(context, 8f), 1))
        controls.addView(IosUi.tintedButton(context, "Reset").apply {
            setOnClickListener { structureView.reset() }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 8f)
        })

        column.addView(IosUi.sectionHeader(context, "CONFIDENCE PER RESIDUE"))
        confidenceStrip = ConfidenceStrip(context)
        column.addView(confidenceStrip, LayoutParams(
            LayoutParams.MATCH_PARENT, IosUi.dp(context, 40f)
        ))

        foldDetail = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 12f), 0, 0)
        }
        column.addView(foldDetail)

        column.addView(IosUi.sectionFooter(
            context,
            "pLDDT is the model's own estimate of how accurate each residue's position is, on the " +
                "same 0–100 scale AlphaFold uses. It is a prediction like any other and a model this " +
                "small is often confidently wrong — but a stretch it marks below 50 is a stretch it " +
                "is telling you not to believe, and that is worth more than the picture."
        ))

        return column
    }

    private fun refreshFoldTab() {
        foldSummary.text = when {
            folding -> "Folding…"
            activeEntry == null -> "No model selected. Create or pick one on the first tab."
            lastPrediction == null -> "Ready — ${activeEntry?.name}"
            else -> foldSummary.text
        }
    }

    /** Strips FASTA headers, whitespace and anything that is not a residue letter. */
    private fun cleanSequence(raw: String): String = raw
        .lineSequence()
        .filterNot { it.trimStart().startsWith(">") }
        .joinToString("")
        .filter { it.isLetter() }
        .uppercase()

    private fun fold() {
        if (folding) return
        val entry = activeEntry
        if (entry == null) {
            toast("Pick a model on the first tab.")
            return
        }
        val sequence = cleanSequence(sequenceField.text.toString())
        if (sequence.length < 2) {
            toast("That is not a sequence.")
            return
        }
        val unknown = sequence.count { ProteinChemistry.indexOf(it) == ProteinChemistry.UNKNOWN_INDEX }
        if (sequence.length > entry.config.maxLength) {
            // Not refused: the model crops during training but folds whatever it is given, and a
            // sequence longer than the crop folds worse rather than not at all. Saying so is better
            // than silently truncating.
            toast(
                "${sequence.length} residues, longer than this model's ${entry.config.maxLength}-residue " +
                    "crop — it will fold, less well."
            )
        }

        folding = true
        foldSummary.text = "Folding ${sequence.length} residues…"

        Thread({
            val started = System.currentTimeMillis()
            var prediction: FoldingPrediction? = null
            var where = "this device"

            try {
                // Distributed first, and only when a peer is genuinely stronger. A single fold cannot
                // be split — see MeshFolding — so offloading it to a weaker phone would be slower for
                // money.
                if (MeshFolding.allowed(training = false)) {
                    val better = MeshFolding.betterPeerFor()
                    if (better != null) {
                        val model = ensureModel()
                        if (model != null) {
                            val outcome = MeshFolding.foldDistributed(
                                model, listOf(sequence), entry.step,
                            )
                            prediction = outcome.predictions.firstOrNull()
                            if (prediction != null) where = prediction.computedOn
                        }
                    }
                }

                if (prediction == null) {
                    val model = ensureModel()
                    if (model == null) {
                        post { toast("That model's weights could not be loaded.") }
                        return@Thread
                    }
                    prediction = model.fold(sequence)
                }
            } catch (e: Throwable) {
                post { toast("Folding failed: ${e.message}") }
            } finally {
                folding = false
                val finished = prediction
                val elapsed = System.currentTimeMillis() - started
                post {
                    if (finished != null) show(finished, where, elapsed, unknown, entry)
                }
            }
        }, "protein-fold").start()
    }

    private fun show(
        prediction: FoldingPrediction,
        where: String,
        elapsedMillis: Long,
        unknownResidues: Int,
        entry: ProteinLibrary.ModelEntry,
    ) {
        lastPrediction = prediction
        structureView.show(prediction)
        confidenceStrip.show(prediction)

        foldSummary.text = buildString {
            append("%.0f mean pLDDT — ".format(prediction.confidence))
            append(prediction.confidenceBand())
            append("\n")
            append("${prediction.length} residues · ${elapsedMillis / 1000f}s · on $where")
        }

        // The geometry is reported because it is checkable, unlike the fold itself. Consecutive Cα
        // spacing that is not 3.8 Å means the structure module produced something that is not a
        // protein backbone, and that is worth surfacing whatever the confidence head claims.
        val trace = prediction.caTrace()
        val spacings = (0 until trace.size - 1).map {
            com.prism.launcher.protein.RigidFrame.distance(trace[it], trace[it + 1])
        }
        val radiusOfGyration = radiusOfGyration(trace)
        // An expectation from polymer physics, not from this model: compact globular proteins scale as
        // roughly 2.2·N^0.38 Å. A prediction far above it has not folded, it has stayed extended.
        val expected = 2.2 * Math.pow(trace.size.toDouble(), 0.38)

        foldDetail.text = buildString {
            append("Model: ${entry.name}, ${ProteinLibrary.formatCount(entry.config.parameterCount())} ")
            append("parameters, ${entry.step} training steps\n")
            if (spacings.isNotEmpty()) {
                append("Cα–Cα spacing: %.2f Å mean (ideal 3.80)\n".format(spacings.average()))
            }
            append("Radius of gyration: %.1f Å (compact would be ≈%.1f)\n".format(radiusOfGyration, expected))
            val lowConfidence = prediction.plddt.count { it < 50f }
            if (lowConfidence > 0) {
                append("$lowConfidence residue(s) below 50 pLDDT — the model is saying it does not know\n")
            }
            if (unknownResidues > 0) {
                append("$unknownResidues residue(s) were not standard amino acids and were folded as unknown\n")
            }
            if (entry.step == 0) {
                append(
                    "\nThis model has not been trained. What you are looking at is its initialisation: " +
                        "correct bond geometry arranged by random weights. Train it on the first tab."
                )
            }
        }
    }

    private fun radiusOfGyration(trace: List<FloatArray>): Double {
        if (trace.isEmpty()) return 0.0
        val centre = DoubleArray(3)
        trace.forEach { for (k in 0 until 3) centre[k] += it[k] }
        for (k in 0 until 3) centre[k] /= trace.size
        var sum = 0.0
        trace.forEach {
            for (k in 0 until 3) {
                val d = it[k] - centre[k]
                sum += d * d
            }
        }
        return Math.sqrt(sum / trace.size)
    }

    // ── Bits ───────────────────────────────────────────────────────────────

    private fun hint(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(IosUi.tertiaryLabel(context))
        setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 6f))
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /**
     * pLDDT along the sequence, in AlphaFold's bands.
     *
     * Worth having next to the structure rather than only as a colour on it: the 3D view shows which
     * *parts* are uncertain but not how much of the chain that is, and a model whose confidence is
     * bimodal — a confident core and a hopeless tail — reads at a glance here and not there.
     */
    private class ConfidenceStrip(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var scores: FloatArray = FloatArray(0)

        fun show(prediction: FoldingPrediction) {
            scores = prediction.plddt
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (scores.isEmpty()) {
                paint.color = IosUi.fill(context)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                return
            }
            val barWidth = width.toFloat() / scores.size
            scores.forEachIndexed { index, score ->
                paint.color = when {
                    score >= 90f -> Color.rgb(0, 83, 214)
                    score >= 70f -> Color.rgb(101, 203, 243)
                    score >= 50f -> Color.rgb(255, 219, 19)
                    else -> Color.rgb(255, 125, 69)
                }
                // Height carries the score as well as the colour, so the strip is readable to someone
                // who cannot distinguish the blue from the yellow.
                val barHeight = height * (0.25f + 0.75f * (score / 100f).coerceIn(0f, 1f))
                canvas.drawRect(
                    index * barWidth, height - barHeight,
                    (index + 1) * barWidth, height.toFloat(), paint,
                )
            }
        }
    }

    private companion object {
        /**
         * Steps each peer runs before its movement is averaged in.
         *
         * Twenty, because a weight transfer is megabytes and a step is a fraction of a second: rounds
         * much shorter than this spend most of the run moving weights, and rounds much longer let the
         * peers diverge before they are reconciled.
         */
        const val STEPS_PER_ROUND = 20

        /** Human ubiquitin, P0CG48 residues 1–76. */
        const val UBIQUITIN =
            "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG"
    }
}
