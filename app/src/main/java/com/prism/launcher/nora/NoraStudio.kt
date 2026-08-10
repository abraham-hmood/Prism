package com.prism.launcher.nora

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The Prism-facing surface of Nora. Everything the launcher touches goes through here.
 *
 * One brain instance for the process, lazily built and lazily loaded from disk. Building the
 * connectome allocates the Gabor bank, the orientation map, and all the link weights, which is
 * on the order of a second -- worth doing once.
 */
object NoraStudio {

    @Volatile
    private var brainInstance: NoraBrain? = null

    /** True while a generation or training run holds the brain. */
    @Volatile
    var busy: Boolean = false
        private set

    /** Whether a connectome was actually loaded, as opposed to merely existing on disk. */
    @Volatile
    private var connectomeLoaded = false

    fun brain(ctx: Context): NoraBrain {
        brainInstance?.let { return it }
        return synchronized(this) {
            brainInstance ?: buildBrain(ctx).also { brainInstance = it }
        }
    }

    private fun buildBrain(ctx: Context): NoraBrain {
        NoraLog.info(NoraLog.Area.BRAIN, "Building a brain — ${NoraLog.describeGeometry()}")
        // Swap regions from the previous brain are not reachable any more; reopening the
        // file empty is what stops a rebuild from leaking the whole of the old connectome.
        NoraSwap.reset()
        val b = try {
            NoraBrain()
        } catch (e: OutOfMemoryError) {
            // The size the user chose does not fit. Logged as fatal because the process is
            // usually moments from death, and because this is the single most useful line to
            // find afterwards when someone asks why Prism died opening a chat.
            NoraLog.fatal(
                NoraLog.Area.BRAIN,
                "Out of memory constructing the brain at ${NoraConfig.geometry.signature()}. " +
                    "Reduce Nora's size in Settings.",
                e
            )
            throw e
        }

        connectomeLoaded = NoraPersistence.load(b)
        when {
            connectomeLoaded -> NoraLog.success(
                NoraLog.Area.BRAIN,
                "Connectome loaded (${NoraPersistence.sizeBytes() / 1024} KB)"
            )
            NoraPersistence.exists() -> NoraLog.warn(
                NoraLog.Area.BRAIN,
                "A connectome file exists but was rejected — wrong version, changed geometry, " +
                    "or non-finite weights. Erase it and retrain."
            )
            else -> NoraLog.info(
                NoraLog.Area.BRAIN, "No connectome on disk — starting from a naive brain"
            )
        }
        return b
    }

    /**
     * True only when a connectome was successfully loaded or trained this session.
     *
     * Deliberately not "does the file exist": a rejected file left Nora reporting herself as
     * trained while running on naive weights, which is precisely the kind of confidently wrong
     * status that made the divergence bug so hard to see.
     */
    fun isTrained(ctx: Context): Boolean {
        brain(ctx)
        return connectomeLoaded
    }

    /** Called by the trainer once a healthy checkpoint has been written. */
    fun markTrained() {
        connectomeLoaded = true
    }

    /**
     * Drops the in-memory brain so the next access reloads from disk.
     *
     * Needed after an import: the files on storage have changed underneath a brain that is
     * still holding the old weights, and without this the restored connectome would not take
     * effect until the process restarted.
     */
    fun reload(ctx: Context) {
        synchronized(this) {
            brainInstance = null
            connectomeLoaded = false
            NoraHealth.reset()
        }
    }

    fun status(ctx: Context): String = brain(ctx).status()

    /**
     * Drops the in-memory brain, keeping everything on disk.
     *
     * Used by the model test, which builds a second brain of its own: at a large geometry two
     * resident brains is the difference between fitting and an OutOfMemoryError. Safe because
     * the connectome is checkpointed every epoch and the service refuses overlapping jobs, so
     * nothing is ever mid-write when this is called.
     */
    fun releaseBrain() {
        synchronized(this) {
            if (brainInstance != null) {
                NoraLog.info(NoraLog.Area.BRAIN, "Released the in-memory brain to free heap")
                // The spill file belongs to this brain's episode indices and means nothing
                // without them, so it goes with it rather than lingering as dead bytes.
                brainInstance?.hippocampus?.closeSpill()
            }
            brainInstance = null
            connectomeLoaded = false
        }
    }

    /**
     * Changes the brain's size.
     *
     * Order matters and is the whole reason this exists rather than callers writing the setting
     * directly. Every region, link and analytic model reads its dimensions from NoraConfig at
     * CONSTRUCTION time, so the live brain must be dropped before the geometry moves under it --
     * otherwise the next access returns an object whose sheets are the old size and whose
     * config says otherwise, which would corrupt silently rather than fail.
     *
     * The connectome is not deleted. Files are keyed by geometry, so the brain trained at the
     * previous size is parked and comes back if the user returns to it.
     *
     * @return false if Nora is busy; changing size mid-run would tear the connectome in half.
     */
    fun applyGeometry(ctx: Context, g: NoraGeometry): Boolean {
        if (busy) {
            NoraLog.warn(NoraLog.Area.GEOMETRY, "Refused a resize: Nora is busy")
            return false
        }
        val before = NoraConfig.geometry.signature()
        synchronized(this) {
            brainInstance = null
            connectomeLoaded = false
            NoraHealth.reset()
            NoraConfig.saveGeometry(g)
            NoraConfig.install(g)
        }
        NoraLog.info(
            NoraLog.Area.GEOMETRY,
            "Resized $before -> ${NoraLog.describeGeometry()}"
        )
        return true
    }

    /** Forgets everything. Deletes the connectome and drops the in-memory brain. */
    fun forget(ctx: Context) {
        synchronized(this) {
            NoraPersistence.deleteConnectome()
            // Feedback traces reference IT patterns from a connectome that no longer exists.
            // Applying one to a naive brain would reinforce noise, so they go with it.
            NoraFeedback.clear()
            brainInstance = null
            connectomeLoaded = false
            NoraHealth.reset()
        }
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /**
     * @param feedbackToken identifies the trace this generation left behind, so a thumb pressed
     *        later can be routed back to the cortical state that produced it. Null when the
     *        trace could not be written -- the UI then offers no thumbs rather than buttons that
     *        would silently do nothing.
     */
    data class Generated(
        val uri: Uri?,
        val file: File?,
        val note: String,
        val feedbackToken: String? = null
    )

    suspend fun generateImage(
        ctx: Context,
        prompt: String,
        mode: NoraImageryMode = NoraImageryMode.DETERMINISTIC,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(prompt)
            val bitmap = imagery.generateStill(prompt, mode = mode, bias = bias, onProgress = onProgress)

            // Written through the platform codec rather than Bitmap.compress: the model now
            // produces a PrismImage, and the only reason to materialize a Bitmap at all is the
            // gallery publish below, which is a genuinely Android-specific step.
            val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.png")
            com.prism.core.PrismPlatform.images.encodePng(bitmap, file)
            val uri = publishImage(ctx, com.prism.launcher.platform.AndroidImageCodec.toBitmap(bitmap))

            if (b.lastSurfaceRange <= 1e-4f) {
                NoraLog.warn(
                    NoraLog.Area.GENERATE,
                    "\"$prompt\" produced a constant (surface range %.6f) — blank image. "
                        .format(b.lastSurfaceRange) +
                        "V1->retina gain surface %.2f / contrast %.2f".format(
                            b.surfacePathwayStrength(), b.contrastPathwayStrength()
                        )
                )
            } else {
                NoraLog.info(
                    NoraLog.Area.GENERATE,
                    "Generated \"$prompt\" (${mode.name.lowercase()}, grounding %.3f, range %.4f)"
                        .format(grounding, b.lastSurfaceRange)
                )
            }
            Generated(uri, file, groundingNote(ctx, prompt, grounding, bias), traceFor(ctx, prompt, imagery, b))
        } catch (e: OutOfMemoryError) {
            NoraLog.fatal(NoraLog.Area.GENERATE, "Out of memory generating \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Reduce Nora's size in Settings.")
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.GENERATE, "Image generation failed for \"$prompt\"", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * Files the trace a later thumbs-up or thumbs-down will act on.
     *
     * Records the concept the image was ACTUALLY built from rather than re-deriving it from the
     * prompt: hippocampal recall settles stochastically, so a second derivation is not
     * guaranteed to land on the same pattern, and reinforcing a pattern the user never saw would
     * be worse than not reinforcing at all.
     */
    private fun traceFor(
        ctx: Context,
        prompt: String,
        imagery: MentalImagery,
        b: NoraBrain
    ): String? {
        val concept = imagery.lastConcept ?: return null
        return NoraFeedback.record(
            prompt = prompt,
            caption = prompt,
            semantic = b.semanticHub.encode(prompt),
            itPattern = concept
        )
    }

    suspend fun generateVideo(
        ctx: Context,
        prompt: String,
        frames: Int = NoraConfig.VIDEO_FRAMES,
        motion: String? = "expansion",
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(prompt)
            val bitmaps = imagery.generateVideo(prompt, frames, motion, bias, onProgress)
            if (bitmaps.isEmpty()) return@withContext Generated(null, null, "No frames were produced.")

            val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.mp4")
            // The video encoder is MediaCodec, so frames are converted at that boundary and
            // nowhere earlier.
            val frameBitmaps = bitmaps.map { com.prism.launcher.platform.AndroidImageCodec.toBitmap(it) }
            val written = NoraVideoWriter.write(frameBitmaps, file)
            val uri = written?.let { publishVideo(ctx, it) }
            for (bm in frameBitmaps) bm.recycle()

            if (written == null) {
                Generated(null, null, "Frames generated but the encoder failed -- see diagnostics.")
            } else {
                Generated(
                    uri, written,
                    groundingNote(ctx, prompt, grounding, bias),
                    traceFor(ctx, prompt, imagery, b)
                )
            }
        } catch (e: OutOfMemoryError) {
            NoraLog.fatal(NoraLog.Area.GENERATE, "Out of memory generating the clip \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Reduce Nora's size in Settings.")
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.GENERATE, "Video generation failed for \"$prompt\"", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /** Recursive video dreaming -- see [MentalImagery.generateHallucination]. Not saccadic. */
    suspend fun generateHallucination(
        ctx: Context,
        prompt: String,
        frames: Int = NoraConfig.HALLUCINATION_FRAMES,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(prompt)
            val bitmaps = imagery.generateHallucination(prompt, frames, bias, onProgress)
            if (bitmaps.isEmpty()) return@withContext Generated(null, null, "No frames were produced.")

            val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.mp4")
            val frameBitmaps = bitmaps.map { com.prism.launcher.platform.AndroidImageCodec.toBitmap(it) }
            val written = NoraVideoWriter.write(frameBitmaps, file)
            val uri = written?.let { publishVideo(ctx, it) }
            for (bm in frameBitmaps) bm.recycle()

            if (written == null) {
                Generated(null, null, "Frames generated but the encoder failed -- see diagnostics.")
            } else {
                Generated(
                    uri, written,
                    groundingNote(ctx, prompt, grounding, bias),
                    traceFor(ctx, prompt, imagery, b)
                )
            }
        } catch (e: OutOfMemoryError) {
            NoraLog.fatal(NoraLog.Area.GENERATE, "Out of memory hallucinating \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Reduce Nora's size in Settings.")
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.GENERATE, "Hallucination failed for \"$prompt\"", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /** Deep latent exposure -- see [MentalImagery.generateDeepExposure]. Not saccadic. */
    suspend fun generateDeepExposure(
        ctx: Context,
        prompt: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Generated = withContext(Dispatchers.Default) {
        busy = true
        try {
            val b = brain(ctx)
            val grounding = b.groundingOf(prompt)
            val imagery = MentalImagery(b)
            val bias = NoraFeedback.biasFor(prompt)
            val bitmap = imagery.generateDeepExposure(
                prompt,
                bias = bias,
                onProgress = onProgress
            )
            val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.png")
            com.prism.core.PrismPlatform.images.encodePng(bitmap, file)
            val uri = publishImage(ctx, com.prism.launcher.platform.AndroidImageCodec.toBitmap(bitmap))
            Generated(uri, file, groundingNote(ctx, prompt, grounding, bias), traceFor(ctx, prompt, imagery, b))
        } catch (e: OutOfMemoryError) {
            NoraLog.fatal(NoraLog.Area.GENERATE, "Out of memory during deep exposure on \"$prompt\"", e)
            Generated(null, null, "Ran out of memory. Reduce Nora's size in Settings.")
        } catch (e: Exception) {
            NoraLog.error(NoraLog.Area.GENERATE, "Deep exposure failed for \"$prompt\"", e)
            Generated(null, null, "Generation failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    /**
     * Tells the user honestly how grounded their prompt is.
     *
     * A prompt made entirely of words Nora has never been trained on will produce something,
     * but it will not be a picture of what was asked for -- the semantic hub has nothing to
     * associate, so IT gets a near-empty pattern and generation runs off the priors alone.
     * Saying so is better than shipping noise and letting the user guess why.
     */
    /**
     * Says so when the image is blank because the generative pathway produced a constant.
     *
     * This is the difference between "she drew something bad" and "there is nothing coming out
     * of V1", and those are indistinguishable by looking at a black rectangle. Reporting it is
     * the same principle as NoraHealth: a numerical failure that produces clean-looking output
     * is worse than a crash.
     */
    private fun collapseNote(b: NoraBrain): String {
        if (b.lastSurfaceRange > 1e-4f) return ""
        return "\n\nThat came out blank because my surface channels produced a constant " +
            "(range %.6f), not because of the prompt. ".format(b.lastSurfaceRange) +
            "The V1->retina brightness pathway has decayed relative to the contrast " +
            "pathway — /status shows both. More training should pull it back now that each " +
            "channel has its own weight budget; if it doesn't, /forget and retrain."
    }

    private fun groundingNote(ctx: Context, prompt: String, grounding: Float, bias: Float): String {
        val feedbackNote = when {
            bias <= -0.15f ->
                "\n\nYou've told me this one was wrong before, so I started somewhere else " +
                    "rather than settling back into the same picture."
            bias >= 0.15f ->
                "\n\nYou've liked this before, so I held the concept harder and let it settle " +
                    "closer to what worked."
            else -> ""
        }
        return groundingText(ctx, prompt, grounding) + feedbackNote + collapseNote(brain(ctx))
    }

    private fun groundingText(ctx: Context, prompt: String, grounding: Float): String {
        if (!isTrained(ctx)) {
            return "I haven't been trained yet, so this is what an untrained visual cortex " +
                "produces -- structure without content. Train me from the Nora page first."
        }
        val hub = brain(ctx).semanticHub
        // Same tokenizer the encoder uses, and an exact vocabulary lookup rather than a
        // membership test against the 400 most frequent words -- that ranking reported any
        // rare-but-learned word as unknown.
        val words = hub.tokenize(prompt)
        val unknown = words.filter { !hub.knows(it) }
        return when {
            hub.knownWords() == 0 ->
                "My vocabulary is empty — training ran but nothing was bound to a caption. " +
                    "Check the training log for the 'taught nothing' warning."
            grounding < 0.02f && unknown.size == words.size ->
                "None of those words are grounded in anything I've seen. This is essentially " +
                    "unconditioned -- teach me with images named after what they show."
            grounding < 0.02f ->
                "I know those words but they aren't bound to any visual pattern yet, so this " +
                    "is mostly prior. More epochs on those images should fix it."
            unknown.isNotEmpty() ->
                "Grounded on ${words.size - unknown.size} of ${words.size} words " +
                    "(new to me: ${unknown.joinToString(", ")})."
            else -> "Grounded on all ${words.size} words."
        }
    }

    // ── Training ────────────────────────────────────────────────────────────

    suspend fun train(
        ctx: Context,
        epochs: Int,
        focus: String? = null,
        onProgress: (NoraTrainer.Progress) -> Unit
    ): String {
        busy = true
        return try {
            NoraTrainer(brain(ctx)).train(epochs, focus, onProgress = onProgress)
        } finally {
            busy = false
        }
    }

    fun datasetSize(ctx: Context): Int = NoraTrainer.loadDataset().size

    // ── MediaStore publishing ───────────────────────────────────────────────

    private fun publishImage(ctx: Context, bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "nora_${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Prism/Nora")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not publish image to MediaStore: ${e.message}")
            null
        }
    }

    private fun publishVideo(ctx: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Prism/Nora")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
            }
            uri
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Could not publish video to MediaStore: ${e.message}")
            null
        }
    }
}
