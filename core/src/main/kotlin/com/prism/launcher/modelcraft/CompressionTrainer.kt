package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Trains ModelCraft's Compression models.
 *
 * The vector-based family trains here, on the device, on every file in its dataset whatever its
 * type -- see [VectorCompressor]. The other families (the transformer, diffusion, state-space and
 * the rest shared with every other kind) produce their recipe and exported training script; this
 * build has no local trainer for a transformer compressor, and the run says so rather than
 * pretending one was made.
 */
object CompressionTrainer {

    private const val TAG = "PrismCompressionTrainer"

    /** Every file the dataset names, folders expanded. */
    fun datasetFiles(model: CraftedModel): List<File> = model.dataset
        .map(::File)
        .flatMap { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) }
        .filter { it.isFile && it.length() > 0 }

    fun train(model: CraftedModel, @Suppress("UNUSED_PARAMETER") recipe: File): String? {
        if (!VectorCompressor.isVector(model)) {
            PrismPlatform.log.info(TAG, "${model.family} has no local trainer in this build; the recipe is the result")
            return null
        }
        val files = datasetFiles(model)
        if (files.isEmpty()) {
            PrismPlatform.log.warn(TAG, "${model.name} has no dataset files to train on")
            return null
        }
        val tableBits = model.param(VectorCompressor.KEY_TABLE_BITS).toIntOrNull()?.coerceIn(12, 22) ?: 16
        // Continues from what the model already knows: training again adds to it.
        val weights = VectorCompressor.current(model)?.first?.takeIf { it.tableBits == tableBits }
            ?: VectorCompressor.Weights(tableBits)
        val epochs = ModelCraft.schedule(model).epochs
        // Every intercept learned on counts as used, so no later archive produces the same point.
        val bitsPerByte = VectorCompressor.train(
            weights, files, VectorCompressor.Settings.of(model), epochs,
            onIntercepts = { VectorCompressor.addUses(model.id, it) },
            layerBase = { VectorCompressor.usesSoFar(model.id) },
        )
        VectorCompressor.save(model, weights)
        val bytes = files.sumOf { it.length() }
        return "${files.size} file(s), %.1f MB, %d epoch(s): %.3f bits per byte (%s)".format(
            bytes / 1_048_576.0, epochs, bitsPerByte,
            if (VectorCompressor.lossy(model)) "lossy" else "lossless",
        )
    }
}
