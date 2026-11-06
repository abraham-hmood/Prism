package com.prism.launcher.storage.compress

import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.VectorCompressor
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The compression networks built in ModelCraft, as codecs a drive can be compressed with.
 *
 * Every Compression model the user has made is listed. A trained, lossless, vector-based one
 * compresses for real; any other is listed with the reason it cannot, and choosing it is refused
 * with that reason rather than quietly falling back to another codec.
 *
 * A network works on 1 MB units rather than NTFS's 64 KB: it predicts from what came before, and a
 * unit that small would leave it almost nothing to predict from.
 */
object NeuralCompressors {

    private const val UNIT = 1024 * 1024

    /**
     * How many times a drive run may teach the model something new. The model is adaptive -- data
     * it finds unfamiliar is learned for good -- but each lesson keeps a snapshot of the weights so
     * the files compressed before it still open. One per run keeps it learning without a drive full
     * of small files filling the disk with snapshots. Reset by [DriveCompression] at each run.
     */
    private val lessons = AtomicInteger(1)

    fun newRun() = lessons.set(1)

    /** Why [model] cannot compress a drive, or null when it can. */
    fun unavailableReason(model: CraftedModel): String? = when {
        !VectorCompressor.isVector(model) ->
            "${model.name} is a ${model.family.ifBlank { model.architecture }} network; this build trains and runs only " +
                "the vector-based family on the device. Its recipe and script train it elsewhere."
        model.status != ModelCraft.STATUS_TRAINED || VectorCompressor.current(model) == null ->
            "Train ${model.name} in ModelCraft first."
        // A drive's files have to come back exactly. A lossy network compresses archives in
        // ModelCraft, never somebody's drive in place.
        VectorCompressor.lossy(model) -> "${model.name} is lossy, and a drive is only compressed losslessly. Use a lossless model."
        else -> null
    }

    fun codecs(): List<Codec> = runCatching {
        ModelCraft.Store.list().filter { it.kind == ModelCraft.Kind.COMPRESSION }
    }.getOrDefault(emptyList()).map { model ->
        val mode = if (VectorCompressor.lossy(model)) "lossy" else "lossless"
        Codec(
            id = "modelcraft:${model.id}",
            label = "ModelCraft: ${model.name} ($mode neural network)" + if (unavailableReason(model) != null) " -- not ready" else "",
            unitSize = UNIT,
            encode = { data, offset, length ->
                unavailableReason(model)?.let { throw IllegalStateException(it) }
                val out = ByteArrayOutputStream(length / 2 + 128)
                val unit = data.copyOfRange(offset, offset + length)
                val learn = lessons.get() > 0
                val result = VectorCompressor.compress(model, listOf(VectorCompressor.Entry("unit", false, unit)), out, learn)
                if (result.learned) lessons.decrementAndGet()
                out.toByteArray()
            },
            decode = { stored, length ->
                var restored: ByteArray? = null
                VectorCompressor.decompress(stored.inputStream()) { _, directory, data -> if (!directory) restored = data }
                restored?.takeIf { it.size == length } ?: throw java.io.IOException("${model.name} did not restore the unit")
            },
            unavailable = { unavailableReason(model) },
        )
    }
}
