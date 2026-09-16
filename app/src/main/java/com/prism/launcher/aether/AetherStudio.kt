package com.prism.launcher.aether

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.NoraVideoWriter
import com.prism.launcher.platform.AndroidImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The Prism-facing surface of Aether -- everything the launcher touches goes through here.
 * Mirrors `NoraStudio`'s shape (one resident brain, lazily built, a `busy` flag guarding it)
 * exactly, since it's the same problem: a single shared mutable connectome that must not run two
 * generations/training passes at once.
 */
object AetherStudio {

    @Volatile
    private var brainInstance: AetherConnectome? = null

    @Volatile
    var busy: Boolean = false
        private set

    @Volatile
    private var connectomeLoaded = false

    fun brain(ctx: Context): AetherConnectome {
        brainInstance?.let { return it }
        return synchronized(this) {
            brainInstance ?: buildBrain(ctx).also { brainInstance = it }
        }
    }

    private fun buildBrain(ctx: Context): AetherConnectome {
        AetherLog.info(AetherLog.Area.BRAIN, "Building Aether's connectome (${AetherConfig.geometry.signature()})")
        val b = try {
            AetherConnectome(geometry = AetherConfig.geometry)
        } catch (e: OutOfMemoryError) {
            AetherLog.fatal(AetherLog.Area.BRAIN, "Out of memory constructing Aether's connectome", e)
            throw e
        }
        val file = AetherConfig.weightsFile(AetherConfig.geometry)
        connectomeLoaded = b.load(file)
        if (connectomeLoaded) {
            AetherLog.success(AetherLog.Area.BRAIN, "Connectome loaded (${file.length() / 1024} KB)")
        } else if (file.exists()) {
            AetherLog.warn(AetherLog.Area.BRAIN, "A connectome file exists but was rejected -- wrong version/geometry. Erase it and retrain.")
        } else {
            AetherLog.info(AetherLog.Area.BRAIN, "No connectome on disk -- starting from infancy")
        }
        // Apply whatever the background receiver already had staged by the time this brain was
        // built -- mirrors main.py's bounded pre-generation wait, except here the receiver has
        // typically been polling since app startup, so there is usually already something ready
        // rather than a wait to insert.
        if (AetherKnowledgeSync.isRunning()) {
            // Gated: see AetherConfig.akefAutoMerge for why merging independently
            // initialised connectomes destroys both rather than combining them.
            if (AetherConfig.akefAutoMerge) AetherKnowledgeSync.applyPending(b, connectomeLoaded)
        }
        return b
    }

    fun isTrained(ctx: Context): Boolean { brain(ctx); return connectomeLoaded }

    /**
     * Same question as [isTrained], answered WITHOUT constructing a connectome.
     *
     * [AetherService] runs in its own process now (`android:process=":aether"`); it is the only
     * process meant to ever hold a resident [AetherConnectome], via [brain]. A UI screen in the
     * MAIN process calling [isTrained]/[brain] just to render a status line would build a SECOND,
     * independent connectome there -- exactly the heap pressure the process split exists to
     * avoid, and a second copy that can silently drift from the one the service is actually
     * training. `AetherSettingsActivity`'s status/brain-size display uses this instead; only
     * actions that must touch the live connectome (training, generation, applying a knowledge
     * merge) go through [AetherService], never through a direct [brain] call from the main
     * process.
     */
    fun hasTrainedWeights(): Boolean = AetherConfig.weightsFile(AetherConfig.geometry).exists()

    fun reload(ctx: Context) {
        synchronized(this) { brainInstance = null; connectomeLoaded = false }
    }

    fun forget(ctx: Context) {
        synchronized(this) {
            AetherConfig.weightsFile(AetherConfig.geometry).delete()
            AetherChatStore.clear(ctx)
            brainInstance = null
            connectomeLoaded = false
        }
        if (PrismSettings.getMeshEnabled()) AetherMeshSync.revoke()
    }

    /**
     * Resizes Aether's connectome, same contract as `NoraStudio.applyGeometry`: refuses while
     * busy, drops the in-memory brain, persists the new geometry. The next [brain] call rebuilds
     * at the new size and loads (or starts fresh for) that size's own weight file.
     */
    fun applyGeometry(ctx: Context, g: AetherGeometry): Boolean {
        if (busy) {
            AetherLog.warn(AetherLog.Area.BRAIN, "Refused a resize: Aether is busy")
            return false
        }
        val before = AetherConfig.geometry.signature()
        synchronized(this) {
            brainInstance = null
            connectomeLoaded = false
            AetherConfig.saveGeometry(g)
            AetherConfig.install(g)
        }
        AetherLog.info(AetherLog.Area.BRAIN, "Resized $before -> ${AetherConfig.geometry.signature()}")
        return true
    }

    data class Generated(val uri: Uri?, val file: File?, val note: String)

    private fun generator(ctx: Context): AetherGenerator = AetherGenerator(brain(ctx))

    suspend fun generateSimple(ctx: Context, prompt: String): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val pixels = AetherTextRenderer.render(prompt)
            val result = generator(ctx).generateSimple(pixels)
            val file = File(AetherConfig.outputDir(), "aether_${System.currentTimeMillis()}.png")
            com.prism.core.PrismPlatform.images.encodePng(result.image, file)
            val uri = publishImage(ctx, AndroidImageCodec.toBitmap(result.image))
            val wavFile = File(AetherConfig.outputDir(), "aether_${System.currentTimeMillis()}.wav")
            AetherMotorDecoder().writeWav(result.audio, 44100, wavFile)
            Generated(uri, file, "\"${result.text.ifBlank { "(no legible speech yet)" }}\"")
        } catch (e: OutOfMemoryError) {
            AetherLog.fatal(AetherLog.Area.GENERATE, "Out of memory generating \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Try a smaller prompt or free up RAM.")
        } catch (e: Exception) {
            AetherLog.error(AetherLog.Area.GENERATE, "Generation failed for \"$prompt\"", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * `test_hypothesis.py::run_temporal_probe`, ported straight -- see
     * [AetherGenerator.runTemporalProbe]'s doc comment for why this reads Broca's raw
     * millisecond-by-millisecond output instead of going through [AetherMotorDecoder].
     */
    suspend fun generateTemporalProbe(ctx: Context, prompt: String): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val pixels = AetherTextRenderer.render(prompt)
            val result = generator(ctx).runTemporalProbe(pixels)
            val activeMs = result.steps.count { it.winner != null }
            val note = if (result.recoveredSequence.isNotEmpty())
                "\"${result.recoveredSequence}\" ($activeMs/${result.steps.size} active ms -- fragments of " +
                    "\"$prompt\" here mean the flashcard reading survived the noise)"
            else
                "(silent -- nothing crossed the detection floor across ${result.steps.size} ms; try training more first)"
            Generated(null, null, note)
        } catch (e: OutOfMemoryError) {
            AetherLog.fatal(AetherLog.Area.GENERATE, "Out of memory running the temporal probe on \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Try a smaller prompt or free up RAM.")
        } catch (e: Exception) {
            AetherLog.error(AetherLog.Area.GENERATE, "Temporal probe failed for \"$prompt\"", e)
            Generated(null, null, "Temporal probe failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    suspend fun generateAutoRegression(ctx: Context, prompt: String, onProgress: ((Int, Int, String) -> Unit)? = null): Generated =
        withContext(Dispatchers.Default) {
            busy = true
            try {
                val pixels = AetherTextRenderer.render(prompt)
                val words = generator(ctx).generateAutoRegression(pixels, onProgress = onProgress)
                Generated(null, null, words.joinToString(" "))
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, "Auto-regression failed for \"$prompt\"", e)
                Generated(null, null, "Generation failed: ${e.message}")
            } finally {
                busy = false
            }
        }

    suspend fun generateHallucination(ctx: Context, prompt: String, onProgress: ((Int, Int) -> Unit)? = null): Generated =
        withContext(Dispatchers.Default) {
            busy = true
            try {
                val pixels = AetherTextRenderer.render(prompt)
                val frames = generator(ctx).generateHallucinationVideo(pixels, onProgress = onProgress)
                if (frames.isEmpty()) return@withContext Generated(null, null, "No frames were produced.")
                val bitmaps = frames.map { AndroidImageCodec.toBitmap(it) }
                val file = File(AetherConfig.outputDir(), "aether_${System.currentTimeMillis()}.mp4")
                val written = NoraVideoWriter.write(bitmaps, file)
                val uri = written?.let { publishVideo(ctx, it) }
                for (bm in bitmaps) bm.recycle()
                if (written == null) Generated(null, null, "Frames generated but the encoder failed.")
                else Generated(uri, written, "${frames.size} frames, dreamed from her own output frame to frame.")
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, "Hallucination failed for \"$prompt\"", e)
                Generated(null, null, "Generation failed: ${e.message}")
            } finally {
                busy = false
            }
        }

    suspend fun generateDeepExposure(ctx: Context, prompt: String, onProgress: ((Int, Int) -> Unit)? = null): Generated =
        withContext(Dispatchers.Default) {
            busy = true
            try {
                val pixels = AetherTextRenderer.render(prompt)
                val img = generator(ctx).generateDeepExposure(pixels, onProgress = onProgress)
                val file = File(AetherConfig.outputDir(), "aether_${System.currentTimeMillis()}.png")
                com.prism.core.PrismPlatform.images.encodePng(img, file)
                val uri = publishImage(ctx, AndroidImageCodec.toBitmap(img))
                Generated(uri, file, "300 steps of exposure, prompt released after the first 30.")
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, "Deep exposure failed for \"$prompt\"", e)
                Generated(null, null, "Generation failed: ${e.message}")
            } finally {
                busy = false
            }
        }

    suspend fun generateSaccadicDrawing(ctx: Context, prompt: String, onProgress: ((Int, Int) -> Unit)? = null): Generated =
        withContext(Dispatchers.Default) {
            busy = true
            try {
                val pixels = AetherTextRenderer.render(prompt)
                val img = generator(ctx).generateSaccadicDrawing(pixels, onProgress = onProgress)
                val file = File(AetherConfig.outputDir(), "aether_${System.currentTimeMillis()}.png")
                com.prism.core.PrismPlatform.images.encodePng(img, file)
                val uri = publishImage(ctx, AndroidImageCodec.toBitmap(img))
                Generated(uri, file, "15 saccades across a 256x256 canvas, each looking at what she'd already drawn.")
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, "Saccadic drawing failed for \"$prompt\"", e)
                Generated(null, null, "Generation failed: ${e.message}")
            } finally {
                busy = false
            }
        }

    suspend fun train(
        ctx: Context, epochs: Int,
        onProgress: (AetherTrainer.Progress) -> Unit,
        /** Every line the ANN-baseline-conversion calibration pass prints, forwarded on top of
         * the [AetherLog] calls it already makes -- [AetherService] wires this to the same
         * emitLog/relayLog pair the per-epoch [onProgress] lines already go through, so
         * calibration shows up in Aether's training Log tab exactly like ordinary training does. */
        onCalibrationProgress: (String) -> Unit = {}
    ): String {
        busy = true
        return try {
            // Experimental ANN-baseline-conversion (see AetherAnnBaseline, and the settings
            // checkbox's own gate in AetherSettingsActivity): only ever applies to a genuinely
            // fresh connectome, checked BEFORE brain(ctx) builds/loads it, mirroring train.py's
            // own was_untrained gate exactly.
            val wasUntrained = !hasTrainedWeights()
            val connectome = brain(ctx)

            var calibrationCharGain: FloatArray? = null
            if (wasUntrained && PrismSettings.getAetherAnnBaselineEnabled()) {
                val modelPath = PrismSettings.getLocalAiModelPath()
                if (modelPath.isEmpty()) {
                    AetherLog.warn(AetherLog.Area.TRAIN, "AnnBaseline: enabled but Sam has no active local text model -- ignoring it.")
                } else {
                    val captions = AetherTrainer.loadDataset(AetherConfig.datasetDir()).map { it.label }
                    val result = AetherAnnBaseline.runCalibration(
                        modelPath, captions,
                        // Soft-target distillation is automatic -- see AetherAnnBaseline.runCalibration's
                        // own doc comment: the only gate it needs is the one this whole block already
                        // runs under (a local text model loaded and active). The other two are optional,
                        // each behind its own Settings checkbox.
                        useSoftTargetDistill = true,
                        useSurprisalWeighting = PrismSettings.getAetherSurprisalWeightingEnabled(),
                        useCooccurrencePrior = PrismSettings.getAetherCooccurrencePriorEnabled(),
                        onProgress = onCalibrationProgress
                    )
                    if (result != null) {
                        connectome.applyCalibratedInitScale(result.weightScale)
                        calibrationCharGain = result.charGain
                        val line = "AnnBaseline: applied calibrated weight scale %.3f to the fresh connectome.".format(result.weightScale)
                        AetherLog.success(AetherLog.Area.TRAIN, line)
                        onCalibrationProgress(line)
                        if (result.cooccurrence.isNotEmpty()) {
                            connectome.applyCooccurrencePrior(result.cooccurrence)
                            val coLine = "AnnBaseline: applied co-occurrence structural prior " +
                                "(${result.cooccurrence.size} character pair(s)) to the fresh connectome."
                            AetherLog.success(AetherLog.Area.TRAIN, coLine)
                            onCalibrationProgress(coLine)
                        }
                    }
                }
            }

            val bioTrain = PrismSettings.getAetherBiotrainEnabled()
            val summary = AetherTrainer(
                connectome,
                textRenderer = AetherTextRenderer::render,
                wideTextRenderer = AetherTextRenderer::renderWide
            ).train(
                epochs, bioTrainMode = bioTrain,
                checkpointIntervalEpochs = PrismSettings.getAetherCheckpointIntervalEpochs(),
                charGain = calibrationCharGain, onProgress = onProgress
            )
            connectomeLoaded = true
            summary
        } finally {
            busy = false
            // Safe point for a peer merge staged by AetherKnowledgeSync's background receiver --
            // training just finished, nothing else is touching these arrays right now. Mirrors
            // train.py's own apply_pending() call at its sleep-consolidation point.
            if (AetherKnowledgeSync.isRunning()) {
                if (AetherConfig.akefAutoMerge) brainInstance?.let { AetherKnowledgeSync.applyPending(it, connectomeLoaded) }
            }
            announceOverMeshIfEnabled(ctx)
        }
    }

    /**
     * Gossips this device's Aether hosting state (trained or not, current geometry) to mesh
     * peers, gated on both the feature toggle and the mesh actually being connected to anyone --
     * an announce to zero peers is a harmless no-op, but the gate keeps this from doing work (and
     * logging) on every checkpoint save for a device that has the mesh entirely turned off.
     */
    private fun announceOverMeshIfEnabled(ctx: Context) {
        if (!PrismSettings.getAetherShareKnowledgeEnabled()) return
        if (!PrismSettings.getMeshEnabled()) return
        if (com.prism.launcher.mesh.PrismMeshService.getPeerCount() <= 0) return
        AetherMeshSync.announce(ctx)
    }

    fun datasetSize(): Int = AetherTrainer.loadDataset(AetherConfig.datasetDir()).size

    /** Cheap, see [hasTrainedWeights] -- does not construct a connectome. */
    fun status(): String =
        if (hasTrainedWeights()) "Connectome loaded from disk." else "Untrained -- fresh from infancy."

    private fun publishImage(ctx: Context, bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aether_${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Prism/Aether")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Aether", "Could not publish image to MediaStore: ${e.message}")
            null
        }
    }

    private fun publishVideo(ctx: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Prism/Aether")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } } }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Aether", "Could not publish video to MediaStore: ${e.message}")
            null
        }
    }
}
