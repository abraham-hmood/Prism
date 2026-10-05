package com.prism.desktop.aether

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.aether.AetherConfig
import com.prism.launcher.aether.AetherConnectome
import com.prism.launcher.aether.AetherGenerator
import com.prism.launcher.aether.AetherLog
import com.prism.launcher.aether.AetherTrainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aether on a desktop: the brain, its training, and the four generation routes. PHASE 76.
 *
 * ## Why this is a wiring job rather than a port
 *
 * Everything that thinks is already in `:core` and already portable — the connectome, the cortices, the
 * neurons, the tensors, the tokenizer, the trainer, the generator. The plan said as much and it was
 * right. What Android has that a desktop does not is a way to DRAW TEXT for the retina to read, and
 * that is [DesktopTextRenderer]. The rest of this file is a lifecycle: build the brain once, keep it,
 * and hand it to the trainer or the generator.
 *
 * ## Why the brain is a singleton held here
 *
 * It is hundreds of megabytes of weights. Building a second one would not be slow, it would be an
 * out-of-memory error — and two connectomes trained separately cannot be merged afterwards, so a second
 * one is not even a wasteful copy, it is a divergent brain.
 *
 * ## Why a desktop is a better home for this than a phone
 *
 * The same reason as the crawler: no doze, no background limits, and memory to spare. Aether's training
 * loop is hours of arithmetic, and a phone spends much of that suspended.
 */
object DesktopAether {

    private const val TAG = "PrismAether"

    @Volatile private var connectome: AetherConnectome? = null
    @Volatile private var loadedFromDisk = false

    @Volatile
    var busy: Boolean = false
        private set

    /** The last thing that happened, for a page to show. */
    @Volatile
    var status: String = "idle"
        private set

    /**
     * The brain, built on first use and kept.
     *
     * Slow and memory-hungry the first time. Callers run it off the UI thread; it is synchronized so
     * two of them cannot each build one.
     */
    fun brain(): AetherConnectome {
        connectome?.let { return it }
        return synchronized(this) {
            connectome ?: build().also { connectome = it }
        }
    }

    private fun build(): AetherConnectome {
        AetherLog.info(AetherLog.Area.BRAIN, "Building Aether's connectome (${AetherConfig.geometry.signature()})")
        val built = AetherConnectome(geometry = AetherConfig.geometry)
        val file = AetherConfig.weightsFile(AetherConfig.geometry)
        loadedFromDisk = built.load(file)

        if (loadedFromDisk) {
            AetherLog.success(AetherLog.Area.BRAIN, "Connectome loaded (${file.length() / 1024} KB)")
        } else if (file.exists()) {
            // WORTH SAYING OUT LOUD: a rejected file is almost always a geometry change, and the fix
            // is to erase and retrain rather than to wonder why nothing was learned.
            AetherLog.warn(
                AetherLog.Area.BRAIN,
                "A connectome file exists but was rejected -- wrong version or geometry. Erase it and retrain.",
            )
        } else {
            AetherLog.info(AetherLog.Area.BRAIN, "No connectome on disk -- starting from infancy")
        }
        return built
    }

    /** Whether there are trained weights, as opposed to a brain built from nothing. */
    fun isTrained(): Boolean {
        brain()
        return loadedFromDisk
    }

    fun weightsFile(): File = AetherConfig.weightsFile(AetherConfig.geometry)

    fun datasetDir(): File = AetherConfig.datasetDir()

    /** Forgets everything. Irreversible, which is why nothing calls it without asking first. */
    fun forget() {
        synchronized(this) {
            runCatching { weightsFile().delete() }
            connectome = null
            loadedFromDisk = false
            status = "forgotten"
            AetherLog.warn(AetherLog.Area.BRAIN, "Connectome erased")
        }
    }

    // ── Training ───────────────────────────────────────────────────────────

    /**
     * Trains on whatever is in the dataset directory.
     *
     * The renderer is INJECTED, which is the one seam :core leaves open: the trainer routes text
     * through the visual pathway and has no way to draw it. On Android that is AetherTextRenderer, here
     * it is [DesktopTextRenderer], and the two draw identically on purpose — see that file.
     */
    suspend fun train(
        epochs: Int,
        bioTrainMode: Boolean = true,
        onProgress: (AetherTrainer.Progress) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        if (busy) return@withContext "Aether is already busy."
        busy = true
        status = "training"
        try {
            val trainer = AetherTrainer(
                brain = brain(),
                textRenderer = DesktopTextRenderer::render,
            )
            val result = trainer.train(
                epochs = epochs,
                bioTrainMode = bioTrainMode,
                onProgress = { progress ->
                    status = "epoch " + progress.epoch + " of " + epochs
                    onProgress(progress)
                },
            )
            loadedFromDisk = true
            status = "trained"
            result
        } catch (e: Exception) {
            AetherLog.error(AetherLog.Area.TRAIN, "Training failed", e)
            status = "training failed"
            "Training failed: " + e.message
        } finally {
            busy = false
        }
    }

    // ── The four generation routes ─────────────────────────────────────────

    private fun generator(): AetherGenerator = AetherGenerator(brain())

    /** Words, one after another, each conditioned on what came before. */
    suspend fun autoRegression(
        prompt: String,
        onProgress: ((Int, Int, String) -> Unit)? = null,
    ): String = run("auto-regression") {
        generator().generateAutoRegression(DesktopTextRenderer.render(prompt), onProgress = onProgress)
            .joinToString(" ")
    }

    /** A clip dreamed frame by frame, each one from the last rather than from the prompt. */
    suspend fun hallucinationVideo(
        prompt: String,
        frames: Int = 20,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<PrismImage> = runFrames("hallucination") {
        generator().generateHallucinationVideo(
            DesktopTextRenderer.render(prompt), frames, onProgress,
        )
    }

    /** One long gaze, with the prompt released partway through. */
    suspend fun deepExposure(
        prompt: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): PrismImage? = runImage("deep exposure") {
        generator().generateDeepExposure(DesktopTextRenderer.render(prompt), onProgress = onProgress)
    }

    /** Drawn the way an eye moves: fixate, mark, jump. */
    suspend fun saccadicDrawing(
        prompt: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): PrismImage? = runImage("saccadic drawing") {
        generator().generateSaccadicDrawing(DesktopTextRenderer.render(prompt), onProgress = onProgress)
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private suspend fun run(label: String, block: () -> String): String =
        withContext(Dispatchers.Default) {
            if (busy) return@withContext "Aether is already busy."
            busy = true
            status = label
            try {
                block()
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, label + " failed", e)
                label + " failed: " + e.message
            } finally {
                busy = false
                status = "idle"
            }
        }

    private suspend fun runImage(label: String, block: () -> PrismImage): PrismImage? =
        withContext(Dispatchers.Default) {
            if (busy) return@withContext null
            busy = true
            status = label
            try {
                block()
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, label + " failed", e)
                null
            } finally {
                busy = false
                status = "idle"
            }
        }

    private suspend fun runFrames(label: String, block: () -> List<PrismImage>): List<PrismImage> =
        withContext(Dispatchers.Default) {
            if (busy) return@withContext emptyList()
            busy = true
            status = label
            try {
                block()
            } catch (e: Exception) {
                AetherLog.error(AetherLog.Area.GENERATE, label + " failed", e)
                emptyList()
            } finally {
                busy = false
                status = "idle"
            }
        }

    /** A one-line description of the brain, for a page or a console. */
    fun describe(): String = buildString {
        append(AetherConfig.geometry.signature())
        append(" · ")
        append(if (isTrained()) "trained" else "untrained")
        val file = weightsFile()
        if (file.isFile) {
            append(" · ")
            append(file.length() / 1024 / 1024)
            append(" MB on disk")
        }
    }
}
