package com.prism.launcher.cakechat

import android.content.Context
import com.prism.launcher.PrismLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Fetching CakeChat's source onto the device, and knowing what state it is in.
 *
 * ## Why the source is downloaded rather than bundled
 *
 * CakeChat is a third-party repository under its own licence, and it is unmaintained -- pinning a
 * copy into the APK means shipping someone else's abandoned code to every user whether they want it
 * or not, and paying for it in install size. Downloading on request keeps it opt-in, which is what a
 * user choosing this backend is doing anyway.
 *
 * ## The three states
 *
 * ABSENT, INSTALLED and TRAINED are distinct because the UI has to do something different in each.
 * Absent means download. Installed but untrained means open the trainer -- there are no pretrained
 * weights any more, the upstream `tools/fetch.py` links died with the project, so a fresh install
 * genuinely cannot answer anything yet. Trained means it is usable as a backend.
 */
object CakeChatInstall {

    /** A GitHub tarball of the default branch. No auth, no API rate limit worth worrying about. */
    private const val ARCHIVE_URL = "https://github.com/lukalabs/cakechat/archive/refs/heads/master.zip"

    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    enum class State { ABSENT, INSTALLED, TRAINED }

    /** Where the repository is unpacked. */
    fun root(context: Context): File = File(context.filesDir, "cakechat")

    /** Where a trained model is written. Kept outside [root] so re-downloading source keeps weights. */
    fun weightsDir(context: Context): File =
        File(context.filesDir, "cakechat-weights").apply { mkdirs() }

    /** The user's own corpus, in CakeChat's format: one JSON list of utterances per line. */
    fun datasetFile(context: Context): File = File(weightsDir(context), "train_dialogs.txt")

    /** The roleplay corpus shipped in assets, for a first run with nothing to supply. */
    private const val BUNDLED_ASSET = "cakechat/roleplay_dialogs.txt"

    private const val PREFS = "prism_cakechat"
    private const val KEY_USE_BUNDLED = "use_bundled_dataset"
    private const val KEY_EPOCHS = "cakechat_epochs"
    private const val KEY_BATCH_SIZE = "cakechat_batch_size"
    private const val KEY_SUBSET = "cakechat_subset_size"
    private const val KEY_HIDDEN_DIM = "cakechat_hidden_dim"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun useBundledDataset(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_BUNDLED, false)

    fun setUseBundledDataset(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_BUNDLED, enabled).apply()
    }

    /**
     * How many passes over the corpus a run makes.
     *
     * Defaults to 1, not to upstream's 2. A phone takes long enough over a single epoch that the
     * honest default is the one a user can watch finish; a second pass is a deliberate choice.
     */
    fun epochs(context: Context): Int =
        prefs(context).getInt(KEY_EPOCHS, 1).coerceIn(1, 100)

    fun setEpochs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_EPOCHS, value.coerceIn(1, 100)).apply()
    }

    /**
     * Dialogs per gradient step.
     *
     * Defaults to 32 against upstream's 196, which was sized for a GPU with gigabytes to spare.
     * Larger is faster per dialog and worse for memory; the trainer halves it automatically if a
     * run exhausts memory, so an over-large value here costs time rather than the whole run.
     */
    fun batchSize(context: Context): Int =
        prefs(context).getInt(KEY_BATCH_SIZE, 32).coerceIn(1, 512)

    fun setBatchSize(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_BATCH_SIZE, value.coerceIn(1, 512)).apply()
    }

    /**
     * How many dialogs of the corpus a run uses; 0 means all of them.
     *
     * The setting that decides how long training takes. Steps per epoch is the corpus divided by
     * the batch size, and on a phone each step is measured in seconds -- so a full corpus is a run
     * of hours whether or not anyone intended that.
     */
    fun subsetSize(context: Context): Int =
        prefs(context).getInt(KEY_SUBSET, 0).coerceAtLeast(0)

    fun setSubsetSize(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SUBSET, value.coerceAtLeast(0)).apply()
    }

    /**
     * Width of the recurrent layers; 0 means CakeChat's own 768.
     *
     * THE SETTING THAT DECIDES WHETHER A MODEL RUNS ON THIS PHONE. A GRU's parameters grow with the
     * SQUARE of this, so it dominates both the weights file and the memory needed to load it --
     * measured at 850 MB peak for the default width, which is more than Android hands one process.
     * 256 is roughly a ninth of the parameters of 768.
     *
     * A model must be loaded at the width it was trained at, so changing this means retraining
     * rather than adjusting; the value used is recorded beside the weights and travels in the
     * export bundle.
     */
    fun hiddenDim(context: Context): Int =
        prefs(context).getInt(KEY_HIDDEN_DIM, 0).coerceIn(0, 2048)

    fun setHiddenDim(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_HIDDEN_DIM, value.coerceIn(0, 2048)).apply()
    }

    /**
     * The bundled corpus, extracted to a real file.
     *
     * COPIED OUT OF ASSETS RATHER THAN READ IN PLACE. An APK asset is not a filesystem path -- it
     * lives inside the archive behind an AssetManager -- and CakeChat's Python opens the corpus with
     * `open()`. Handing it an `assets/` path would fail with a FileNotFoundError that points at a
     * file the user can plainly see in the app, which is a confusing way to learn this.
     *
     * Re-extracted whenever the sizes differ, so a corpus updated in a new build replaces the copy
     * left by the old one instead of being silently ignored.
     */
    fun bundledDatasetFile(context: Context): File? {
        val target = File(weightsDir(context), "bundled_roleplay_dialogs.txt")
        return runCatching {
            val expected = context.assets.open(BUNDLED_ASSET).use { it.available().toLong() }
            if (!target.isFile || target.length() != expected) {
                context.assets.open(BUNDLED_ASSET).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                PrismLogger.logInfo(
                    "CakeChat", "Extracted the bundled corpus (${target.length() / 1024} KB)"
                )
                // Run through the SAME converter as an imported file, not trusted because it
                // shipped with the app. It normalises whitespace, drops any single-utterance line
                // and guarantees the trailing newline CakeChat's line iterator expects -- and if a
                // future edit to the asset breaks its shape, that surfaces here rather than as a
                // training failure nobody attributes to the bundled data.
                runCatching {
                    val result = CakeChatBridge.corpusModule(context)
                        .callAttr("convert", target.absolutePath, target.absolutePath)
                        .asList()
                    PrismLogger.logInfo("CakeChat", "Bundled corpus: ${result[1]}")
                }.onFailure {
                    PrismLogger.logWarning(
                        "CakeChat", "Could not normalise the bundled corpus: ${it.message}"
                    )
                }
            }
            target
        }.onFailure {
            PrismLogger.logError("CakeChat", "Could not extract the bundled corpus", it)
        }.getOrNull()
    }

    /**
     * Whichever corpus training should actually read.
     *
     * Resolved here rather than at each call site so the trainer, the validator and the activity
     * cannot disagree about which file is in use -- which would show a validated user file on screen
     * while training the bundled one, or the reverse.
     */
    fun activeDatasetFile(context: Context): File? =
        if (useBundledDataset(context)) bundledDatasetFile(context)
        else datasetFile(context).takeIf { it.isFile }

    /**
     * The stage a dead inference process was in, consumed once.
     *
     * READ FROM THE SURVIVING PROCESS. The Python side writes this file and fsyncs it before each
     * risky step, but a process killed by the kernel cannot report anything afterwards -- so the
     * launcher reads it instead. This is the only evidence that distinguishes "ran out of memory
     * loading the weights" from "died generating", and the two have different fixes.
     *
     * Cleared as it is read, so one crash is reported once rather than on every later message.
     */
    fun consumeInferenceStage(context: Context): String? {
        val file = java.io.File(weightsDir(context), "inference_stage.txt")
        if (!file.isFile) return null
        val stage = runCatching { file.readLines().firstOrNull()?.trim() }.getOrNull()
        runCatching { file.delete() }
        return stage?.takeIf { it.isNotEmpty() }
    }

    fun state(context: Context): State = when {
        !File(root(context), "cakechat").isDirectory -> State.ABSENT
        // By CONTENT, not extension. CakeChat writes weights to its own extensionless model path,
        // and a PC-trained import carries that same name -- matching only ".h5" reported a trained
        // model as untrained.
        weightsDir(context).listFiles()?.any { it.name.endsWith(".h5") || isHdf5(it) } == true ->
            State.TRAINED
        else -> State.INSTALLED
    }

    /**
     * Downloads and unpacks the repository.
     *
     * Blocking; call it off the main thread. [onProgress] receives bytes so far and the total when
     * the server declares one, or -1 when it does not.
     *
     * The archive nests everything under `cakechat-master/`, and that prefix is stripped so paths
     * match the repository layout the Python code expects -- `cakechat/`, `tools/`, `data/` -- rather
     * than being one directory deeper than every import assumes.
     */
    fun install(
        context: Context,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean {
        val target = root(context)
        val zip = File(context.cacheDir, "cakechat-master.zip")
        return runCatching {
            http.newCall(Request.Builder().url(ARCHIVE_URL).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty response")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
            }

            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            unzipStrippingTopLevel(zip, target)
            zip.delete()

            // Nothing is copied into the repository. The port lives in
            // app/src/main/python/prism_cakechat.py, which Chaquopy already puts on the Python
            // path -- so re-downloading the source cannot undo it.
            PrismLogger.logSuccess("CakeChat", "Installed into ${target.absolutePath}")
            true
        }.onFailure {
            PrismLogger.logError("CakeChat", "Install failed", it)
            runCatching { zip.delete() }
        }.getOrDefault(false)
    }

    private fun unzipStrippingTopLevel(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                // Strip `cakechat-master/`.
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val out = File(target, relative)
                // A zip entry naming `../` would write outside the target. The archive is from a
                // known host, but a path check costs nothing and the alternative is arbitrary file
                // write.
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it, 64 * 1024) }
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Whether a PC-trained model has been imported.
     *
     * Weights ALONE are not enough. They are sized by the vocabulary that produced them, so
     * `load_weights(by_name=True)` against a different index_to_token either fails or loads into the
     * wrong shape -- a model that appears to work and answers nonsense. All three files are required
     * before this reports true.
     */
    fun hasImportedModel(context: Context): Boolean {
        val files = weightsDir(context).listFiles() ?: return false
        val names = files.map { it.name }
        return names.any { it.startsWith("t_idx_") } &&
            names.any { it.startsWith("c_idx_") } &&
            files.any { isHdf5(it) }
    }

    /** HDF5's signature. Weights land under CakeChat's own extensionless path, so name matching is not enough. */
    private fun isHdf5(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val magic = ByteArray(8)
            stream.read(magic) == 8 &&
                magic.contentEquals(byteArrayOf(0x89.toByte(), 0x48, 0x44, 0x46, 0x0D, 0x0A, 0x1A, 0x0A))
        }
    }.getOrDefault(false)

    /**
     * Imports a directory holding a CakeChat model trained elsewhere.
     *
     * A DIRECTORY, not a file, because three things have to arrive together: the weights and both
     * index files. Picking them one at a time invites importing weights without their vocabulary,
     * which is the failure that looks like a working model giving bad answers.
     *
     * Returns a human-readable summary. Blocking; call it off the main thread.
     */
    fun importTrainedModel(context: Context, tree: android.net.Uri): String {
        val resolver = context.contentResolver
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )

        var weights = 0
        var tokenIndex = 0
        var conditionIndex = 0
        var converted = 0
        var skipped = 0

        runCatching {
            resolver.query(
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
                    val child = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree, documentId
                    )
                    val target = File(weightsDir(context), name)

                    val copied = runCatching {
                        resolver.openInputStream(child)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        } != null
                    }.getOrDefault(false)

                    if (!copied) {
                        skipped++
                        continue
                    }
                    when {
                        name == SIZING_NAME -> Unit          // carried, not counted
                        // KEPT, NOT COUNTED. The final branch deletes anything unrecognised, so a
                        // file that is neither weights nor an index has to be named here or it is
                        // written and then thrown away -- which is what happened to the converted
                        // model: the bundle carried it, the import discarded it, and answering fell
                        // back to TensorFlow with nothing to say why.
                        name in TFLITE_NAMES -> converted++
                        name.startsWith("t_idx_") -> tokenIndex++
                        name.startsWith("c_idx_") -> conditionIndex++
                        name.endsWith(".h5") || isHdf5(target) -> weights++
                        else -> {
                            // Anything unrecognised is removed rather than left to confuse the
                            // HDF5 scan that decides whether a model exists.
                            target.delete()
                            skipped++
                        }
                    }
                }
            }
        }.onFailure {
            PrismLogger.logError("CakeChat", "Import failed", it)
            return "Could not read that folder: ${it.message}"
        }

        PrismLogger.logInfo(
            "CakeChat",
            "Imported $weights weight file(s), $tokenIndex token index, $conditionIndex condition index"
        )

        return summarise(weights, tokenIndex, conditionIndex, converted) +
            if (skipped > 0) " ($skipped unrelated file(s) ignored.)" else ""
    }

    /**
     * The manifest written into an exported bundle.
     *
     * Small, but it is what makes an import able to REFUSE a bad bundle rather than half-apply it.
     * Without it, a zip missing its index files imports weights that then load into the wrong shape
     * and answer nonsense -- a failure with no error attached to it.
     */
    private const val MANIFEST_NAME = "prism_cakechat.json"

    /** Written by the Python trainer beside the weights; see `_record_sizing`. */
    private const val SIZING_NAME = "prism_model_sizing.json"

    /** The converted model: two graphs plus the metadata the Kotlin sampler is driven by. */
    private val TFLITE_NAMES = setOf(
        "cakechat_decoder.tflite", "cakechat_encoder.tflite", "cakechat_tflite.json",
    )

    /**
     * Bundles a trained model into a single zip.
     *
     * ONE FILE, because the three pieces are only meaningful together: weights are sized by the
     * vocabulary in the index files, so a user who copies "the model" and means only the weights
     * produces something that loads into the wrong shape. A zip makes the unit of sharing the whole
     * thing.
     *
     * Blocking; call it off the main thread. Returns a summary for the UI.
     */
    /**
     * Copies the vocabulary files next to the weights if they are not already there.
     *
     * WEIGHTS ARE SIZED BY THEIR VOCABULARY. The embedding and the output projection both have a
     * dimension equal to the token count, so weights without their index files load into the wrong
     * shape -- and weights paired with a DIFFERENT corpus's indices load fine and answer nonsense,
     * which is worse. A bundle carrying one without the other is not a model.
     *
     * Needed because harvesting used to copy only the weights, leaving the indices in the
     * repository where an export could not see them. A model trained before that was fixed still
     * has that layout, and retraining to repair a bundle is hours of work to undo a copy that takes
     * milliseconds -- so they are fetched from the repository on the way out.
     *
     * Returns how many were put in place.
     */
    private fun backfillIndexFiles(context: Context): Int {
        val dir = weightsDir(context)
        val already = dir.listFiles()?.any {
            it.name.startsWith("t_idx_") || it.name.startsWith("c_idx_")
        } == true
        if (already) return 0

        val repo = root(context)
        var copied = 0
        for (source in listOf(
            java.io.File(repo, "data/tokens_index"),
            java.io.File(repo, "data/conditions_index"),
        )) {
            for (file in source.listFiles().orEmpty()) {
                if (!file.isFile) continue
                if (!file.name.startsWith("t_idx_") && !file.name.startsWith("c_idx_")) continue
                runCatching { file.copyTo(java.io.File(dir, file.name), overwrite = true) }
                    .onSuccess { copied++ }
            }
        }
        return copied
    }

    fun exportTrainedModel(context: Context, target: java.io.OutputStream): String {
        backfillIndexFiles(context)

        val dir = weightsDir(context)
        val files = dir.listFiles()?.filter { file ->
            file.isFile && (
                file.name.startsWith("t_idx_") ||
                    file.name.startsWith("c_idx_") ||
                    // The architecture record. Weights only fit the shape that produced them, and
                    // a model trained at a non-default width cannot be rebuilt without this -- so
                    // a bundle that omits it is unloadable on any other device.
                    file.name == SIZING_NAME ||
                    // Carried so an imported model can run without TensorFlow on the far side too;
                    // re-converting requires the Python stack the import may exist to avoid.
                    file.name in TFLITE_NAMES ||
                    file.name.endsWith(".h5") ||
                    isHdf5(file)
                )
        }.orEmpty()

        val weights = files.count { it.name.endsWith(".h5") || isHdf5(it) }
        val indexes = files.count { it.name.startsWith("t_idx_") || it.name.startsWith("c_idx_") }
        if (weights == 0) return "Nothing to export — no trained weights on this device yet."
        // Refused rather than exported: a bundle without indices imports and then answers nonsense,
        // and discovering that on the other device is far worse than failing here.
        if (indexes == 0) {
            return "Nothing to export — the vocabulary files are missing, so the weights cannot " +
                "be loaded anywhere. Train again to rebuild them."
        }

        return runCatching {
            java.util.zip.ZipOutputStream(target.buffered()).use { zip ->
                val manifest = com.prism.core.json.JSONObject().apply {
                    put("format", "prism-cakechat-model")
                    put("version", 1)
                    put("weights", weights)
                    put("indexes", indexes)
                    put("exported_at", System.currentTimeMillis())
                }
                zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()

                for (file in files) {
                    // Flat, no directory prefix. The importer matches on file NAME -- t_idx_, c_idx_,
                    // HDF5 magic -- so nesting would only add a path to strip.
                    zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            PrismLogger.logSuccess("CakeChat", "Exported $weights weight file(s), $indexes index file(s)")
            "Exported $weights weight file(s) and $indexes index file(s)." +
                if (indexes < 2) " Warning: an index file is missing, so this bundle may not load." else ""
        }.onFailure {
            PrismLogger.logError("CakeChat", "Export failed", it)
        }.getOrElse { "Export failed: ${it.message}" }
    }

    /**
     * Unpacks a bundle written by [exportTrainedModel].
     *
     * Accepts any zip carrying the same files, manifest or not -- the manifest is a courtesy, and
     * refusing a hand-made zip that is otherwise correct would be pedantry. What it will not do is
     * report success for a bundle whose pieces do not add up; see the return values.
     */
    fun importTrainedModelZip(context: Context, source: java.io.InputStream): String {
        var weights = 0
        var tokenIndex = 0
        var conditionIndex = 0
        var converted = 0

        return runCatching {
            java.util.zip.ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }

                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name == MANIFEST_NAME) { zip.closeEntry(); continue }

                    val target = java.io.File(weightsDir(context), name)
                    // Path traversal: a crafted entry name must not write outside the directory.
                    if (!target.canonicalPath.startsWith(
                            weightsDir(context).canonicalPath + java.io.File.separator)) {
                        zip.closeEntry(); continue
                    }

                    target.outputStream().use { out -> zip.copyTo(out, 64 * 1024) }
                    zip.closeEntry()

                    when {
                        name == SIZING_NAME -> Unit          // carried, not counted
                        // KEPT, NOT COUNTED. The final branch deletes anything unrecognised, so a
                        // file that is neither weights nor an index has to be named here or it is
                        // written and then thrown away -- which is what happened to the converted
                        // model: the bundle carried it, the import discarded it, and answering fell
                        // back to TensorFlow with nothing to say why.
                        name in TFLITE_NAMES -> converted++
                        name.startsWith("t_idx_") -> tokenIndex++
                        name.startsWith("c_idx_") -> conditionIndex++
                        name.endsWith(".h5") || isHdf5(target) -> weights++
                        else -> target.delete()
                    }
                }
            }

            PrismLogger.logInfo(
                "CakeChat",
                "Imported bundle: $weights weight(s), $tokenIndex token index, $conditionIndex condition index"
            )
            summarise(weights, tokenIndex, conditionIndex, converted)
        }.onFailure {
            PrismLogger.logError("CakeChat", "Bundle import failed", it)
        }.getOrElse { "Could not read that bundle: ${it.message}" }
    }

    /** Shared wording, so a folder import and a zip import cannot describe the same state differently. */
    private fun summarise(
        weights: Int, tokenIndex: Int, conditionIndex: Int, converted: Int,
    ): String = when {
        weights == 0 ->
            "No weights found. A model bundle needs CakeChat's weights plus both index files."
        tokenIndex == 0 || conditionIndex == 0 ->
            "Imported $weights weight file(s), but an index file is missing. Weights are sized by " +
                "their vocabulary, so both t_idx_*.json and c_idx_*.json must come with them."
        else -> "Imported $weights weight file(s) with both index files. CakeChat is ready." +
            // STATED EITHER WAY. Without the converted model the phone answers through TensorFlow,
            // which is the path that gets killed for memory -- and the difference is invisible
            // until it fails, so it is worth a sentence here rather than a crash later.
            if (converted >= 3) " It includes a converted model, so it runs without TensorFlow."
            else " No converted model was included, so answering will use TensorFlow and far more " +
                "memory — convert it on the machine that trained it and export again."
    }

    fun uninstall(context: Context) {
        runCatching { root(context).deleteRecursively() }
        runCatching { weightsDir(context).deleteRecursively() }
    }
}
