package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Keeping the list of installed models honest.
 *
 * ## The bug this exists to fix
 *
 * A downloaded model stopped appearing after a restart. The file was on disk the whole time -- what was
 * missing was the REGISTRATION, and the reason is worth writing down because the shape of the mistake is
 * common.
 *
 * The models page enqueued a download and then launched a coroutine to wait for it:
 *
 *     scope.launch { watchAndImport(target, model.name) { reload() } }
 *
 * `scope` there is the page's composition scope. Navigate away from the models page and the coroutine is
 * cancelled; the download itself carries on, because the downloader is process-scoped, and the file lands
 * correctly. Nothing then registers it. It showed up for the rest of that session only because the
 * downloader's own active list still remembered it, and vanished on restart.
 *
 * Multi-gigabyte models take long enough that leaving the page while one downloads is the NORMAL case, not
 * an edge case -- so the feature was broken for almost every real download and worked in testing, where the
 * file is small and nobody navigates away.
 *
 * ## Two halves to the fix
 *
 * [install] is called from the DOWNLOADER's completion hook, which lives as long as the process does. No UI
 * scope is involved, so navigating away, closing the window or opening a different page cannot lose it.
 *
 * [reconcile] scans the models directory and registers anything present but unrecorded. That is what
 * repairs installs already in this state -- somebody with four downloaded models and an empty list gets them
 * back on next launch without re-downloading. It also covers files dropped in by hand, which people do.
 */
object ModelRegistry {

    private const val TAG = "PrismModels"

    /**
     * The type a finished download should be registered as, keyed by destination path.
     *
     * A file extension cannot tell an ONNX image generator from an ONNX frame interpolator, and
     * it cannot tell a diffusion GGUF from a language model's GGUF either -- so the store records
     * what the user was browsing for before the download starts, and registration consumes it.
     *
     * PERSISTED, not a field in memory. A multi-gigabyte download outlives the process that
     * started it on both platforms: desktop's downloader reports completion to a callback in a
     * later session, and Android's DownloadManager broadcasts to a receiver that may be started
     * fresh. Either way the in-memory version was gone by then, and the fallback filed the model
     * by inspection -- which is how SD-Turbo ended up in the Text list. See
     * [PrismSettings.setPendingModelType].
     */
    fun expect(file: File, type: String) {
        if (file.absolutePath.isBlank() || type.isBlank()) return
        PrismSettings.setPendingModelType(file.absolutePath, type)
    }

    /** Extensions worth looking at. Anything else in the folder is somebody's own business. */
    private val EXTENSIONS = setOf("gguf", "bin", "onnx", "safetensors", "task", "pskn")

    /**
     * Where models live.
     *
     * ## THERE WERE TWO, AND THAT WAS THE OTHER HALF OF THE BUG
     *
     * `ModelsPage` downloaded to `documentsDir()/Models` while `P2pModelTransfer.modelsDir()` used
     * `dataDir()/models` -- under a comment reading "Shared with the store's own downloads", which it was
     * not. So a model fetched from the store and a model fetched over the mesh landed in different places,
     * and anything that scanned one could not see the other.
     *
     * THE DOCUMENTS FOLDER WINS, for a reason beyond picking one: a model is a multi-gigabyte file the user
     * may well want to find, copy to another machine or delete without going through Prism. `dataDir()` on
     * Windows is under %LOCALAPPDATA%, which is hidden from the user by convention. The data directory is
     * the right place for things Prism owns; a downloaded model is the user's.
     */
    fun directory(): File = File(PrismPlatform.host.documentsDir(), "Models").apply { mkdirs() }

    /**
     * Where models used to be put, still scanned so nothing is lost.
     *
     * Kept rather than migrated: moving gigabytes on somebody's behalf at startup is not a thing to do
     * quietly, and a model that works where it is does not need to move. [reconcile] registers what it
     * finds here with its real path.
     */
    private fun legacyDirectory(): File = File(PrismPlatform.host.dataDir(), "models")

    /**
     * Records a model, deciding its type by INSPECTING it when nobody said.
     *
     * Not by extension and not by which button was pressed: a `.bin` from HuggingFace may be
     * either a text model or an image one, and a mislabelled entry fails much later and much less
     * legibly than it does here. Idempotent -- `addImportedModel` replaces any entry with the same
     * path.
     *
     * [type] wins when the caller knows (a store download knows the filter it was found under),
     * then the recorded pending type -- which is the same answer arriving from another process --
     * and only then inspection.
     */
    fun install(file: File, displayName: String? = null, type: String? = null): Boolean {
        if (!file.isFile || file.length() == 0L) {
            PrismPlatform.log.warn(TAG, "Not registering " + file.name + ": it is not a finished file")
            return false
        }
        val resolved = type
            ?: PrismSettings.takePendingModelType(file.absolutePath)
            ?: inferType(file)
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = file.absolutePath,
                displayName = displayName ?: file.nameWithoutExtension,
                type = resolved,
            )
        )
        PrismPlatform.log.info(
            TAG,
            "Registered " + file.name + " as " + resolved + " (" + (file.length() / (1024 * 1024)) + " MB)",
        )
        return true
    }

    /**
     * The type of an untyped file, by reading it.
     *
     * THE GGUF CASE IS WHY THIS IS NOT A LOOKUP TABLE. `isGguf` used to decide TEXT, which is the
     * bug that put SD-Turbo in the text list: stable-diffusion.cpp reads GGUF as well, and its
     * checkpoints are not language models. [ModelFormat.ggufFamily] reads the tensor names and
     * tells them apart; safetensors is the diffusion container, so it is an image model unless a
     * caller says otherwise.
     */
    fun inferType(file: File): String {
        val extension = file.extension.lowercase()
        if (extension == "safetensors") return PrismSettings.MODEL_TYPE_IMAGE
        if (extension != "gguf" && !runCatching {
                GgufInferenceService.isGgufFile(file.absolutePath)
            }.getOrDefault(false)
        ) {
            return PrismSettings.MODEL_TYPE_IMAGE
        }
        return when (ModelFormat.ggufFamily(file)) {
            ModelFormat.GgufFamily.DIFFUSION -> PrismSettings.MODEL_TYPE_IMAGE
            else -> PrismSettings.MODEL_TYPE_TEXT
        }
    }

    /**
     * Brings the registry in line with what is actually on disk.
     *
     * TWO DIRECTIONS, AND BOTH MATTER:
     *
     *  - A file in the models folder that nothing recorded is registered. That is the repair for the bug
     *    above, and it is also how a hand-copied model appears without an import step.
     *  - An entry whose file is GONE is dropped, because a model the user can select and that cannot load
     *    is worse than one that is missing from the list. A deleted file is a deliberate act.
     *
     * A PARTIAL DOWNLOAD IS NOT REGISTERED. The downloader writes to a temporary name and moves the file
     * into place on success, so anything in the folder under its final name is complete -- but a `.part` or
     * `.tmp` left by an interrupted run would otherwise be picked up as a model, load, and produce garbage.
     *
     * @return how many entries were added and removed.
     */
    fun reconcile(): Pair<Int, Int> {
        val recorded = PrismSettings.getImportedModels()
        val byPath = recorded.associateBy { it.path }

        var added = 0
        // BOTH DIRECTORIES. See directory() -- downloads and mesh transfers used to land in different
        // places, so an install predating that fix has models in either or both.
        val found = runCatching {
            val current = directory().listFiles { f: File -> f.isFile }?.toList().orEmpty()
            val legacy = legacyDirectory().takeIf { it.isDirectory }
                ?.listFiles { f: File -> f.isFile }?.toList().orEmpty()
            current + legacy
        }.getOrDefault(emptyList())

        found.forEach { file ->
            val extension = file.extension.lowercase()
            if (extension !in EXTENSIONS) return@forEach
            // Anything still being written, by whatever convention.
            if (file.name.endsWith(".part") || file.name.endsWith(".tmp")) return@forEach
            if (byPath.containsKey(file.absolutePath)) return@forEach
            if (install(file)) added++
        }

        val missing = recorded.filterNot { File(it.path).isFile }
        missing.forEach { PrismSettings.removeImportedModel(it.path) }

        // REPAIRING A WRONG TYPE, not just avoiding new ones. An entry recorded as TEXT that is a
        // diffusion checkpoint on disk was filed by the old "every GGUF is text" rule, and it is
        // wrong in the direction that breaks something: the image slot it belongs in looks empty
        // while the text list offers a model that cannot answer. Re-tagging costs a header read per
        // entry and means an existing install fixes itself on the next visit to the Models page
        // instead of needing a delete and a re-download.
        var retyped = 0
        recorded.filter { it.type == PrismSettings.MODEL_TYPE_TEXT }.forEach { entry ->
            val file = File(entry.path)
            if (!file.isFile) return@forEach
            if (file.extension.lowercase() != "gguf") return@forEach
            if (ModelFormat.ggufFamily(file) != ModelFormat.GgufFamily.DIFFUSION) return@forEach
            PrismSettings.addImportedModel(entry.copy(type = PrismSettings.MODEL_TYPE_IMAGE))
            retyped++
        }

        if (added > 0 || missing.isNotEmpty() || retyped > 0) {
            PrismPlatform.log.info(
                TAG,
                "Reconciled models: " + added + " added, " + missing.size + " dropped as missing, " +
                    retyped + " re-tagged as image models",
            )
        }
        return added to missing.size
    }

    /**
     * Registers every download that completes, for as long as the process lives.
     *
     * ## Why the hook chains rather than replaces
     *
     * `Downloader.onProgress` is a single mutable field, and the models page sets it too -- to republish
     * progress into composition. Whichever ran last would otherwise silently disable the other, which is
     * the same class of bug as the one this file fixes. So the previous listener is kept and called.
     *
     * ## Why completion is matched by name and not by id
     *
     * The progress record carries an id and a name, not a destination. The name is what was passed to
     * `enqueue`, and the destination is derived from it the same way here as there -- so a completed
     * download is found by looking for the file that should now exist. Matching on a remembered id would
     * mean holding state that a restart loses, which is exactly what went wrong before.
     */
    fun watchDownloads() {
        val previous = PrismPlatform.downloader.onProgress
        PrismPlatform.downloader.onProgress = { progress ->
            runCatching { previous?.invoke(progress) }

            if (progress.state == com.prism.core.Downloader.State.COMPLETE) {
                // Off the downloader's own thread: inspecting a GGUF header and rewriting the settings file
                // should not hold up whatever it does next.
                Thread({
                    runCatching {
                        val landed = directory().listFiles { f: File -> f.isFile }
                            ?.firstOrNull { it.nameWithoutExtension == progress.name || it.name == progress.name }
                            ?: legacyDirectory().takeIf { it.isDirectory }
                                ?.listFiles { f: File -> f.isFile }
                                ?.firstOrNull { it.nameWithoutExtension == progress.name || it.name == progress.name }
                        if (landed != null) {
                            install(landed, progress.name)
                        } else {
                            // The destination was somewhere else -- a caller may download outside the models
                            // folder. A full reconcile is the safe answer rather than guessing a path.
                            reconcile()
                        }
                    }
                }, "model-register").apply { isDaemon = true }.start()
            }
        }
        PrismPlatform.log.info(TAG, "Watching downloads, so a completed model is registered without a page open.")
    }
}
